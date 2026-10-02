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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gravity Grasp, Anchor Being's key held rather than tapped. The caster stretches an arm out and a small black hole opens
 * on the palm, tearing every body the caster could harm within {@link #REACH} blocks off its feet and in, harder the
 * longer it is held, for up to {@link #MOST} ticks. The first it drags within arm's reach is caught: the hole closes,
 * a dagger forms in the hand already turned over, and it is driven down into the side of the neck and left there, the
 * body held stunned on it and leaned on, for a second; then it is torn out across the throat. Six hearts, ten seconds'
 * bleeding, and a second more of the stun after.
 *
 * <p>Everything here is the server's: who is pulled, how hard, who is caught and when the blade goes in and comes out.
 * Clients are told only that the caster is holding it (player data, {@code graspStart}); the dagger is the combo
 * starters' phantom blade (BladeCombo's keys) and the move is blade_grasp_stab (tools/blade_moves.py). Bounded:
 * {@link #MOST_HELD} bodies at most, one bounded query a tick for each caster holding it.
 */
public final class GravityGrasp {
    private GravityGrasp() { }

    /**
     * The longest hold, the reach of the pull, and its least and greatest strength, in blocks a tick added each tick,
     * reached {@link #RAMP} ticks in. Even the least yanks a sprinting player off their feet; at the most nothing short
     * of a boss stays on the ground.
     */
    public static final int MOST = 200;
    static final int RAMP = 60;
    static final double REACH = 22, PULL_LEAST = .16, PULL_MOST = .5, KEPT = .88, FASTEST = 2.4, LIFT = .14;
    /** Where the hole hangs, out from the eye on the outstretched palm; and how close a body must come to be caught. */
    static final double HOLE = 1.25, ASIDE = .3, DOWN = .38, MELEE = 2.7;
    /** The stab: in on {@link #STAB_IN}, torn out on {@link #STAB_OUT}, the blade gone by {@link #STAB_END}. */
    static final int STAB_IN = 6, STAB_OUT = 27, STAB_END = 34, AFTER_STUN = 20;
    static final float STAB = 6, RIP = 6;
    static final int BLEED = 200, BLEED_STACKS = 2;
    /** How far in front of the caster's middle the blade goes in: the body is held there, its near side on the point. */
    static final double PIN = .85;
    /** Recovery once let go, and where it lives; bodies pulled at once, at most. */
    public static final int RECOVERY = 240;
    public static final String READY = "graspReady", HOLDING = "graspStart";
    static final int MOST_HELD = 12;

    private static final class Hold {
        final long start;
        LivingEntity caught;
        long caughtAt;

        Hold(long start) {this.start = start;}
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    public static boolean holding(ServerPlayer p) {return HOLDS.containsKey(p.getUUID());}

    /** Whether it is still recovering. A tick further off than its recovery could reach (a moved world clock) counts as ready. */
    public static boolean recovering(ServerPlayer p) {
        long left = HexData.get(p).getLong(READY) - HexData.now(p);
        return left > 0 && left <= RECOVERY;
    }

    public static boolean begin(ServerPlayer p) {
        if (holding(p) || recovering(p) || p.isSpectator() || !p.isAlive()) return false;
        long now = HexData.now(p);
        HOLDS.put(p.getUUID(), new Hold(now));
        HexData.get(p).putLong(HOLDING, now);
        HexNetwork.animate(p, "telekinesis");
        p.level().playSound(null, p.blockPosition(), HexGodOfStories.GRIP_HOLD.get(), SoundSource.PLAYERS, .9f, .45f);
        HexNetwork.sync(p);
        return true;
    }

    /** Let go: the hole closes, and the recovery starts. A stab under way is seen through: letting go does not end it. */
    public static void release(ServerPlayer p) {
        Hold hold = HOLDS.get(p.getUUID());
        if (hold == null || hold.caught != null) return;
        HOLDS.remove(p.getUUID());
        CompoundTag d = HexData.get(p);
        d.remove(HOLDING);
        d.putLong(READY, HexData.now(p) + RECOVERY);
        HexNetwork.animate(p, "__clear__");
        HexNetwork.sync(p);
    }

    /** A death, a logout or a crossing: dropped without a recovery being owed for it. */
    public static void forget(ServerPlayer p) {
        Hold hold = HOLDS.remove(p.getUUID());
        if (hold == null) return;
        HexData.get(p).remove(HOLDING);
        if (hold.caught != null) sheathe(p);
    }

    public static void reset() {HOLDS.clear();}

    /** The hole's place in the world: on the palm of the outstretched right hand. */
    public static Vec3 hole(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        Vec3 right = flat.lengthSqr() < 1e-6 ? Vec3.ZERO : new Vec3(-flat.z, 0, flat.x).normalize();
        return p.getEyePosition().add(look.scale(HOLE)).add(right.scale(ASIDE)).add(0, -DOWN, 0);
    }

    /** Every tick of a caster's. */
    public static void tick(ServerPlayer p) {
        Hold hold = HOLDS.get(p.getUUID());
        if (hold == null) return;
        long now = HexData.now(p), held = now - hold.start;
        if (hold.caught != null) {stab(p, hold, now); return;}
        if (!p.isAlive() || TemporalEngine.frozen(p) || ScepterBlast.stunned(p) || held >= MOST) {release(p); return;}
        Vec3 hole = hole(p);
        double strength = PULL_LEAST + (PULL_MOST - PULL_LEAST) * Math.min(1, held / (double) RAMP);
        List<Entity> near = p.level().getEntities(p, new AABB(hole, hole).inflate(REACH), e -> e instanceof LivingEntity && HexServer.validTarget(p, e));
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(hole)));
        for (int i = 0; i < Math.min(MOST_HELD, near.size()); i++) {
            LivingEntity body = (LivingEntity) near.get(i);
            Vec3 middle = body.getBoundingBox().getCenter(), to = hole.subtract(middle);
            double distance = to.length();
            if (distance > REACH) continue;
            if (p.distanceTo(body) - body.getBbWidth() / 2 <= MELEE) {seize(p, hold, body, now); return;}
            // The bigger the body, the harder it is to drag; right at the hole it is held there rather than overshooting
            // it. Whatever stands on the ground is torn off it, or the ground's grip would hold it back.
            double mass = Math.max(1, body.getBbWidth() * body.getBbWidth() * body.getBbHeight() / 1.2);
            Vec3 velocity = body.getDeltaMovement().scale(distance < 1.2 ? .4 : KEPT);
            if (distance > .6) velocity = velocity.add(to.scale(strength / mass / distance));
            if (body.onGround() && distance > 2) velocity = velocity.add(0, LIFT / mass, 0);
            double speed = velocity.length();
            if (speed > FASTEST) velocity = velocity.scale(FASTEST / speed);
            body.setDeltaMovement(velocity);
            body.fallDistance = 0;
            body.hurtMarked = true;
            if (body instanceof ServerPlayer target) target.connection.send(new ClientboundSetEntityMotionPacket(target));
        }
    }

    /**
     * Drawn within arm's reach: the hole closes on it, the body is seized and stunned, and a dagger forms in the hand,
     * already turned over for the stab.
     */
    private static void seize(ServerPlayer p, Hold hold, LivingEntity body, long now) {
        hold.caught = body;
        hold.caughtAt = now;
        CompoundTag d = HexData.get(p);
        d.remove(HOLDING);
        d.putLong(READY, now + RECOVERY);
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
        ServerLevel level = p.serverLevel();
        Vec3 neck = neck(p, body);
        if (t == STAB_IN) {
            // In: down into the side of the neck, to the hilt. The blood comes out round it from here on (Blood.impale).
            body.invulnerableTime = 0;
            body.hurt(p.damageSources().playerAttack(p), STAB);
            BladeCombo.spray(level, body, neck, inward(p).add(0, -.5, 0).normalize(), 1.4f, p);
            CompoundTag n = new CompoundTag();
            n.putString("state", "impale");
            n.putInt("id", body.getId());
            n.putInt("by", p.getId());
            n.putInt("ticks", STAB_OUT - STAB_IN);
            HexNetwork.near(level, body.position(), 64, new HexNetwork.Message(HexNetwork.ARSENAL, body.getId(), n));
            level.playSound(null, body.getX(), body.getY(), body.getZ(), HexGodOfStories.BLADE_HIT.get(), SoundSource.PLAYERS, 1, .7f);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1, .55f);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, .8f, .6f);
        } else if (t == 9 || t == 19) {
            // Leaned on, pushed deeper.
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
            BladeCombo.spray(level, body, neck, right.add(0, .25, 0).normalize(), 2.5f, p);
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
        d.remove(BladeCombo.HELD);
        d.remove(BladeCombo.KIND);
        d.remove(BladeCombo.FORMED);
    }

    /** Held on the blade: kept a step in front of the caster, near side on the point, facing them, wherever they go. */
    private static void pin(ServerPlayer p, LivingEntity body) {
        Vec3 ahead = inward(p);
        Vec3 want = p.position().add(ahead.scale(PIN + body.getBbWidth() / 2));
        Vec3 pull = want.subtract(body.position());
        double y = body.onGround() && Math.abs(pull.y) < .6 ? Math.min(0, body.getDeltaMovement().y) : pull.y * .5;
        Vec3 v = new Vec3(pull.x * .6, y, pull.z * .6);
        if (v.length() > 1.2) v = v.scale(1.2 / v.length());
        body.setDeltaMovement(v);
        body.fallDistance = 0;
        body.hurtMarked = true;
        if (body instanceof ServerPlayer target) target.connection.send(new ClientboundSetEntityMotionPacket(target));
        else {
            float yaw = (float) (Math.toDegrees(Math.atan2(-ahead.x, ahead.z)) + 180);
            body.setYRot(yaw);
            body.setYHeadRot(yaw);
            body.yBodyRot = yaw;
        }
    }

    /** Where the blade goes in: the near side of the neck. */
    private static Vec3 neck(ServerPlayer p, LivingEntity body) {
        return new Vec3(body.getX(), body.getY() + body.getBbHeight() * .82, body.getZ()).subtract(inward(p).scale(body.getBbWidth() * .45));
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
