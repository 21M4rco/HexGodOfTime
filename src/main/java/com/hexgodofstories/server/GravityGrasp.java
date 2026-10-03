package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gravity Grasp: G, held, whatever is in the hand (with The Deceiver in it, G is Complete Evisceration instead). After
 * Pain's Bansho Ten'in. Held, the free hand points out along the look, open, gathering the pull (grasp_point), for as
 * long as the key stays down ({@link #MOST} at most). Let go once it has gathered ({@link #GATHER}), the hand coils back
 * as if hauling the air itself in (grasp_pull), and the one body the caster is looking at that moment is torn off its
 * feet and carried across to them, within {@link #REACH} blocks. Only that body: nothing else near it is touched, and
 * there is no black hole. Within arm's reach it is caught: a dagger forms in the hand already turned over (or the one
 * held is turned over) and is driven down into the side of its neck and left there, the body held stunned on it and
 * leaned on, for a second; then it is torn out across the throat. Six hearts, ten seconds' bleeding, and a second more of
 * the stun after.
 *
 * <p>Its {@link #RECOVERY} starts only when a body is caught. Let go too soon, at nothing, or at a body that is never
 * brought in (too heavy, a wall in the way, gone), and nothing is owed.
 *
 * <p>Everything here is the server's: who is pulled, how hard, who is caught and when the blade goes in and comes out.
 * Clients are told only that the caster is pointing or pulling (player data, {@code graspStart}); the blade during the
 * stab is the combo starters' (BladeCombo's keys, so a dagger thrown away meanwhile is still drawn in the hand) and the
 * move is blade_grasp_stab (tools/blade_moves.py). Bounded: one body, and one world query, on the letting go.
 */
public final class GravityGrasp {
    private GravityGrasp() { }

    /**
     * How long the hand must point before letting go pulls (a tap does nothing), how much longer it gathers after that
     * before the pull is at its strongest, the longest it can be held out before it drops of itself, and the longest the
     * pull is given to bring the body in.
     */
    public static final int GATHER = 10, RAMP = 100, MOST = 200;
    static final int HAUL = 40;
    /**
     * The reach, and how nearly the body must be looked at when the look itself passes by it (1 - cosine). The pull, in
     * blocks a tick added each tick, from the least (already strong: nothing short of a boss stays on the ground) to the
     * most, gathered over {@link #RAMP}: the longer the hand points, the harder and faster the body comes, and the
     * heavier the body it can bring.
     */
    static final double REACH = 22, AIM = .012, KEPT = .88;
    static final double PULL_LEAST = .5, PULL_MOST = 1.3, FASTEST_LEAST = 2.4, FASTEST_MOST = 3.4, LIFT_LEAST = .14, LIFT_MOST = .3;
    /** Where the body is hauled to, straight out in front of the eye; and how close it must come to be caught. */
    static final double PALM = 1.25, DOWN = .38, MELEE = 2.7;
    /** The stab: in on {@link #STAB_IN}, torn out on {@link #STAB_OUT}, the blade gone by {@link #STAB_END}. */
    static final int STAB_IN = 6, STAB_OUT = 27, AFTER_STUN = 20;
    public static final int STAB_END = 34;
    static final float STAB = 6, RIP = 6;
    static final int BLEED = 200, BLEED_STACKS = 2;
    /** Each spray of the stab's blood, going in and torn out: as heavy as a client draws (Blood.slash). */
    private static final float PIERCE_BLOOD = 2.5f;
    /**
     * Where the blade goes in (tools/blade_moves.py, blade_grasp_stab): the fist ends {@link #PIN} in front of the
     * caster's middle, the blade driven in at about {@link #NECK} off the ground. The body is held with its near side
     * there, and one too short to have its neck at that height is held up on the blade until it does.
     */
    static final double PIN = .85, NECK = 1.45;
    /** Recovery, from the catch, and where it lives. */
    public static final int RECOVERY = 100;
    public static final String READY = "graspReady", HOLDING = "graspStart", STABBING = "graspStab";
    /** Synced while a body is being hauled in: since when, and which (clients draw the air bending toward the hand). */
    public static final String HAULING = "graspHaul", TARGET = "graspTarget";

    /** How much of the pull's growth a hand pointed this many ticks has gathered, nought to one. */
    public static double gathered(long pointed) {return Math.max(0, Math.min(1, (pointed - GATHER) / (double) RAMP));}

    private static final class Hold {
        final long start;
        /** The body being hauled in, from the letting go; null while the hand is still pointing. */
        LivingEntity target;
        long hauledAt;
        /** How much the pull had gathered when it was let go (gathered). */
        double power;
        /** How far a creature being carried in moved last tick: its momentum, kept here since it has none of its own. */
        Vec3 carried = Vec3.ZERO;
        LivingEntity caught;
        long caughtAt;

        Hold(long start) {this.start = start;}
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    public static boolean holding(ServerPlayer p) {return HOLDS.containsKey(p.getUUID());}

    /** Whether this body is caught by somebody's grasp, on the blade (api.KaguneLink). */
    public static boolean caught(net.minecraft.world.entity.Entity e) {
        for (Hold hold : HOLDS.values()) if (hold.caught == e) return true;
        return false;
    }

    /** Whether it is still recovering. A tick further off than its recovery could reach (a moved world clock) counts as ready. */
    public static boolean recovering(ServerPlayer p) {
        long left = HexData.get(p).getLong(READY) - HexData.now(p);
        return left > 0 && left <= RECOVERY;
    }

    /** G pressed: the free hand goes out along the look and points. */
    public static boolean begin(ServerPlayer p) {
        if (holding(p) || recovering(p) || p.isSpectator() || !p.isAlive()) return false;
        if (BladeCombo.running(p) || Evisceration.running(p) || ScepterBlast.stunned(p)) return false;
        long now = HexData.now(p);
        HOLDS.put(p.getUUID(), new Hold(now));
        HexData.get(p).putLong(HOLDING, now);
        HexNetwork.animate(p, "grasp_point");
        p.level().playSound(null, p.blockPosition(), HexGodOfStories.GRIP_HOLD.get(), SoundSource.PLAYERS, .9f, .45f);
        HexNetwork.sync(p);
        return true;
    }

    /**
     * G let go. Pointed long enough, the hand is hauled back and whatever the caster looks at now is pulled in; too soon,
     * or {@code haul} false (a screen opened, the key was taken away), the hand simply drops. A pull or a stab already
     * under way is seen through.
     */
    public static void release(ServerPlayer p, boolean haul) {
        Hold hold = HOLDS.get(p.getUUID());
        if (hold == null || hold.target != null || hold.caught != null) return;
        long now = HexData.now(p);
        if (!haul || now - hold.start < GATHER) {
            if (haul) HexServer.notice(p, "Hold G a moment to gather the pull, then let go.");
            drop(p);
            return;
        }
        LivingEntity target = aimed(p);
        // The hand comes back whether or not anything comes with it.
        HexNetwork.animate(p, "grasp_pull");
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .8f, .55f);
        if (target == null) {
            HOLDS.remove(p.getUUID());
            unmark(p);
            HexServer.notice(p, "Nothing in your sight to pull.");
            HexNetwork.sync(p);
            return;
        }
        hold.target = target;
        hold.hauledAt = now;
        hold.power = gathered(now - hold.start);
        CompoundTag d = HexData.get(p);
        d.putLong(HAULING, now);
        d.putInt(TARGET, target.getId());
        p.level().playSound(null, target.getX(), target.getY(), target.getZ(), HexGodOfStories.GRIP_HOLD.get(), SoundSource.PLAYERS, 1, .35f);
        HexNetwork.sync(p);
    }

    /** The hand dropped, or the pull given up (its body gone, or never brought in): nothing caught, and nothing owed. */
    private static void drop(ServerPlayer p) {
        HOLDS.remove(p.getUUID());
        unmark(p);
        HexNetwork.animate(p, "__clear__");
        HexNetwork.sync(p);
    }

    /** A death, a logout or a crossing: dropped where it stands. */
    public static void forget(ServerPlayer p) {
        Hold hold = HOLDS.remove(p.getUUID());
        if (hold == null) return;
        unmark(p);
        if (hold.caught != null) sheathe(p);
    }

    /** No longer pointing or hauling. */
    private static void unmark(ServerPlayer p) {
        CompoundTag d = HexData.get(p);
        d.remove(HOLDING);
        d.remove(HAULING);
        d.remove(TARGET);
    }

    public static void reset() {HOLDS.clear();}

    /** Where the body is hauled to: straight out in front of the caster, where the hand closes on it. */
    public static Vec3 palm(ServerPlayer p) {
        return p.getEyePosition().add(p.getLookAngle().scale(PALM)).add(0, -DOWN, 0);
    }

    /** Every tick of a caster's. */
    public static void tick(ServerPlayer p) {
        Hold hold = HOLDS.get(p.getUUID());
        if (hold == null) return;
        long now = HexData.now(p), held = now - hold.start;
        if (hold.caught != null) {stab(p, hold, now); return;}
        if (!p.isAlive() || TemporalEngine.frozen(p) || ScepterBlast.stunned(p)) {drop(p); return;}
        LivingEntity body = hold.target;
        // Still pointing: nothing moves yet. Held out as long as it can be, the hand drops of itself.
        if (body == null) {if (held >= MOST) drop(p); return;}
        if (now - hold.hauledAt >= HAUL || !body.isAlive() || body.isRemoved() || body.level() != p.level()
            || !HexServer.validTarget(p, body) || BodyFlags.kaguneHolds(body) || p.distanceTo(body) > REACH + 6) {drop(p); return;}
        if (p.distanceTo(body) - body.getBbWidth() / 2 <= MELEE) {seize(p, hold, body, now); return;}
        Vec3 palm = palm(p);
        Vec3 middle = body.getBoundingBox().getCenter(), to = palm.subtract(middle);
        double distance = to.length();
        // The bigger the body, the harder it is to drag; right at the hand it is held there rather than overshooting it.
        // Whatever stands on the ground is torn off it, or the ground's grip would hold it back. The longer the hand
        // pointed, the harder all of it.
        double mass = Math.max(1, body.getBbWidth() * body.getBbWidth() * body.getBbHeight() / 1.2);
        double pull = PULL_LEAST + (PULL_MOST - PULL_LEAST) * hold.power, lift = LIFT_LEAST + (LIFT_MOST - LIFT_LEAST) * hold.power;
        double fastest = FASTEST_LEAST + (FASTEST_MOST - FASTEST_LEAST) * hold.power;
        boolean player = body instanceof ServerPlayer;
        Vec3 velocity = (player ? body.getDeltaMovement() : hold.carried).scale(distance < 1.2 ? .4 : KEPT);
        if (distance > .6) velocity = velocity.add(to.scale(pull / mass / distance));
        if (body.onGround() && distance > 2) velocity = velocity.add(0, lift / mass, 0);
        double speed = velocity.length();
        if (speed > fastest) velocity = velocity.scale(fastest / speed);
        body.fallDistance = 0;
        if (body instanceof ServerPlayer target) {
            // A player moves themselves: they are thrown, and their own client carries it out.
            body.setDeltaMovement(velocity);
            body.hurtMarked = true;
            target.connection.send(new ClientboundSetEntityMotionPacket(target));
            return;
        }
        // A creature is gripped by the pull itself and carried, never merely pushed: a velocity is only ever a suggestion
        // to a body's own physics, and one held still by anything (a stun, another of this mod's holds, a realm's own
        // physics, a creature with no AI) ignores it entirely. Held as the stab will hold it (no AI, no steps of its own,
        // no fall), it is moved here every tick, stopped by whatever walls are in the way, and keeps what it moved as
        // its momentum for the next.
        ScepterBlast.stun(body, 3);
        Vec3 before = body.position();
        body.setDeltaMovement(Vec3.ZERO);
        body.move(MoverType.SELF, velocity);
        hold.carried = body.position().subtract(before);
    }

    /** Hauled within arm's reach: the body is seized and stunned, and the dagger turned over in the hand for the stab. */
    private static void seize(ServerPlayer p, Hold hold, LivingEntity body, long now) {
        hold.caught = body;
        hold.caughtAt = now;
        unmark(p);
        CompoundTag d = HexData.get(p);
        // Brought in: only now is the recovery owed.
        d.putLong(READY, now + RECOVERY);
        d.putLong(STABBING, now);
        d.putLong(BladeCombo.HELD, now + STAB_END);
        d.putInt(BladeCombo.KIND, 0);
        d.putLong(BladeCombo.FORMED, now);
        ScepterBlast.stun(body, STAB_OUT + AFTER_STUN);
        pin(p, body);
        HexNetwork.animate(p, "blade_grasp_stab");
        p.level().playSound(null, body.getX(), body.getY(), body.getZ(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, .7f, 1.25f);
        HexNetwork.sync(p);
    }

    /** Each tick of the stab: the body held on the blade, the blade going in, leaned on, and torn out. */
    private static void stab(ServerPlayer p, Hold hold, long now) {
        LivingEntity body = hold.caught;
        long t = now - hold.caughtAt;
        boolean gone = !body.isAlive() || body.isRemoved() || body.level() != p.level() || p.distanceTo(body) > 6;
        if (!p.isAlive() || t >= STAB_END) {finish(p); return;}
        // Killed by the blade going in (or gone some other way): there is nothing left to hold it in, so it ends there.
        if (gone && t >= STAB_IN) {finish(p); HexNetwork.animate(p, "__clear__"); return;}
        if (t <= STAB_OUT) pin(p, body);
        // Torn off the blade, a body held up on it comes down again (stunned, it has no fall of its own).
        else if (!(body instanceof ServerPlayer) && !body.onGround()) body.move(MoverType.SELF, new Vec3(0, -.35, 0));
        ServerLevel level = p.serverLevel();
        Vec3 neck = neck(p, body);
        if (t == STAB_IN) {
            // In: down into the side of the neck, to the hilt. The blood comes out round it from here on (Blood.impale).
            body.invulnerableTime = 0;
            body.hurt(p.damageSources().playerAttack(p), STAB);
            // Driven in, the blood is forced on along the blade, and bursts back out round it toward the one who drove it,
            // each as heavy as a client draws. (Only the first kicks the caster's view.)
            BladeCombo.spray(level, body, neck, inward(p).add(0, -.5, 0).normalize(), PIERCE_BLOOD, p);
            BladeCombo.spray(level, body, neck, inward(p).scale(-1).add(0, .35, 0).normalize(), PIERCE_BLOOD, null);
            CompoundTag n = new CompoundTag();
            n.putString("state", "impale");
            n.putInt("id", body.getId());
            n.putInt("by", p.getId());
            n.putInt("ticks", STAB_OUT - STAB_IN);
            HexNetwork.near(level, body.position(), 64, new HexNetwork.Message(HexNetwork.ARSENAL, body.getId(), n));
            // The recording of a blade going into a body, from the tick it goes in, alone: nothing laid over it. No play is
            // heard above full scale (a volume over one only carries it further), so it is played twice at once, the two
            // adding in the mix, and carried twice as far.
            for (int i = 0; i < 2; i++)
                level.playSound(null, body.getX(), body.getEyeY(), body.getZ(), HexGodOfStories.BLADE_PIERCE.get(), SoundSource.PLAYERS, 2, 1);
        } else if (t == 19) {
            // Leaned on, pushed deeper. (The first push, on the ninth, is the loudest moment of the recording itself.)
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.PLAYERS, .9f, .5f);
        } else if (t == STAB_OUT) {
            // Out: torn across the throat and away to the right, a sheet of blood thrown after it.
            body.invulnerableTime = 0;
            body.hurt(p.damageSources().playerAttack(p), RIP);
            if (body.isAlive()) {
                Bleed.apply(p, body, BLEED_STACKS, BLEED);
                ScepterBlast.stun(body, AFTER_STUN);
            }
            Vec3 right = right(p);
            // The sheet thrown off after the blade, high and low along the way it went.
            BladeCombo.spray(level, body, neck, right.add(0, .25, 0).normalize(), PIERCE_BLOOD, p);
            BladeCombo.spray(level, body, neck, right.add(0, -.3, 0).normalize(), PIERCE_BLOOD, null);
            CompoundTag cut = new CompoundTag();
            cut.putString("state", "throat");
            cut.putInt("id", body.getId());
            cut.putDouble("rx", right.x);
            cut.putDouble("rz", right.z);
            HexNetwork.near(level, body.position(), 64, new HexNetwork.Message(HexNetwork.ARSENAL, body.getId(), cut));
            level.playSound(null, body.getX(), body.getY(), body.getZ(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, 1, .8f);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, .75f);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1, .45f);
            HexServer.reward(p, com.hexgodofstories.data.Discipline.CONJURATION, 60);
        }
    }

    /** The stab done (or its body gone): the blade is gone from the hand and the grasp is over. */
    private static void finish(ServerPlayer p) {
        HOLDS.remove(p.getUUID());
        sheathe(p);
        HexNetwork.sync(p);
    }

    private static void sheathe(ServerPlayer p) {
        CompoundTag d = HexData.get(p);
        d.remove(STABBING);
        d.remove(BladeCombo.HELD);
        d.remove(BladeCombo.KIND);
        d.remove(BladeCombo.FORMED);
    }

    /**
     * Held on the blade: carried a step in front of the caster, near side on the point, neck at the blade's height, and
     * facing them, wherever they go. Clients turn the caster's body to their look while it lasts (GraspRenderer), so
     * the move's blade comes out of the very direction the body is held in.
     */
    private static void pin(ServerPlayer p, LivingEntity body) {
        Vec3 ahead = inward(p);
        double lift = Mth.clamp(NECK - body.getBbHeight() * .82, 0, 1);
        Vec3 want = p.position().add(ahead.scale(PIN + body.getBbWidth() / 2)).add(0, lift, 0);
        Vec3 gap = want.subtract(body.position());
        body.fallDistance = 0;
        if (body instanceof ServerPlayer target) {
            // A player moves themselves: they are thrown onto it, and their own client carries it out.
            Vec3 v = gap.scale(.6);
            if (v.length() > 1.2) v = v.scale(1.2 / v.length());
            target.setDeltaMovement(v);
            target.hurtMarked = true;
            target.connection.send(new ClientboundSetEntityMotionPacket(target));
            return;
        }
        // Stunned, a mob has no physics of its own (ScepterBlast's NoAI): a velocity would never move it. So it is
        // carried there outright, a little over half the remaining way each tick, stopped by whatever walls are in the way.
        Vec3 step = gap.scale(.55);
        if (step.length() > .8) step = step.scale(.8 / step.length());
        body.setDeltaMovement(Vec3.ZERO);
        body.move(MoverType.SELF, step);
        float yaw = (float) (Math.toDegrees(Math.atan2(-ahead.x, ahead.z)) + 180);
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.yBodyRot = yaw;
    }

    /** Where the blade goes in: the near side of the neck. */
    private static Vec3 neck(ServerPlayer p, LivingEntity body) {
        return new Vec3(body.getX(), body.getY() + body.getBbHeight() * .82, body.getZ()).subtract(inward(p).scale(body.getBbWidth() * .45));
    }

    /**
     * The one body meant: the one the look passes through, or failing that the one nearest the line of the look, well
     * inside it. Never more than one.
     */
    private static LivingEntity aimed(ServerPlayer p) {
        if (HexServer.target(p, REACH) instanceof LivingEntity looked && pullable(p, looked)) return looked;
        LivingEntity best = null;
        double nearest = AIM;
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(REACH), e -> pullable(p, e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            if (to.lengthSqr() > REACH * REACH) continue;
            double off = 1 - to.normalize().dot(look);
            if (off <= nearest) {nearest = off; best = e;}
        }
        return best;
    }

    /** Something the caster could harm, in sight, and not already held in the air by a Kagune's tendril (HexKagunes). */
    private static boolean pullable(ServerPlayer p, LivingEntity e) {
        return e.isAlive() && !e.isRemoved() && e.level() == p.level() && HexServer.foe(p, e) && p.hasLineOfSight(e) && !BodyFlags.kaguneHolds(e);
    }

    /** The caster's forward, level with the ground. */
    private static Vec3 inward(ServerPlayer p) {
        Vec3 look = p.getLookAngle(), flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The caster's right, level with the ground. */
    private static Vec3 right(ServerPlayer p) {
        Vec3 f = inward(p);
        return new Vec3(-f.z, 0, f.x);
    }
}
