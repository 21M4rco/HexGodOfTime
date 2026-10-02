package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.Discipline;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The blades' combo starters, made by holding the blade's own key in reach of a body you are looking at.
 *
 * <ul>
 * <li><b>Flurry</b> (Conjure Daggers): forehand, backhand, a cut down from high on the right, a rising backhand, and a
 * push kick that throws the body back. Quick, light, and over in under two seconds.</li>
 * <li><b>Master Cuts</b> (The Deceiver): after Liechtenauer's longsword, the Zornhau (the wrath cut, down from the right
 * shoulder), the Zwerchhau (the thwart cut, flat across at the head), the Zornort (the wrath's point, a thrust out of
 * the bind) and an Unterhau rising from below that lifts the body off its feet. Slower and heavier than the Flurry.</li>
 * </ul>
 *
 * <p>The body is held stunned from the first cut to the last; the caster is not, and may walk, turn and circle as the
 * moves play (they are upper-body animations). Each move asks again that the body is still alive, still in reach and
 * still looked at, and the combo ends there the moment it is not. Every cut throws the body's blood toward the side
 * the blade went (the same swing tools/generate_blades.py checks its animations against), and the last move leaves a
 * second of bleeding. The damage is a starter's, not a finisher's: four hearts from the Flurry, five and a half from
 * the Master Cuts, with no knockback until the kick or the rising cut.
 *
 * <p>Without the blade in hand, the combo forms one for itself: a short flourish puts it in the hand (drawn, never
 * given: nothing enters the inventory), it does its work, and it is gone as the combo ends.
 */
public final class BladeCombo {
    private BladeCombo() {}

    /** Synced to watchers: until when a blade formed only for a combo is in the caster's hand, which one, and since when. */
    public static final String HELD = "bladeHeld", KIND = "bladeKind", FORMED = "bladeFormed";
    /** Each combo's own recovery, apart from the conjuring's. */
    public static final String FLURRY_READY = "flurryReady", CUTS_READY = "masterCutsReady";
    public static final int FLURRY_RECOVERY = 120, CUTS_RECOVERY = 160;
    /** Ticks a blade takes to form in an empty hand before the first cut. */
    private static final int DRAW = 8;
    /** Ticks after the last contact before the combo is over. */
    private static final int TAIL = 8;

    /**
     * One move: when it begins (ticks after the combo's first), its animation, the tick of the blade's contact into
     * it, its damage, and where the blood goes in the caster's own right, up and forward. The animations and the
     * contacts are tools/generate_blades.py's.
     */
    private record Move(int at, String animation, int contact, float damage, double right, double up, double forward) { }

    // The flip into an icepick backhand across the throat, the hammer stab down at the collarbone, the flip back into
    // a lunging thrust, the low spinning cut, and the push kick.
    private static final Move[] FLURRY = {
        new Move(0, "blade_dagger_0", 4, 1.5f, 1, 0, 0),
        new Move(9, "blade_dagger_1", 3, 1.5f, 0, -.8, .6),
        new Move(17, "blade_dagger_2", 5, 1.5f, 0, 0, 1),
        new Move(28, "blade_dagger_3", 6, 1.5f, 1, 0, 0),
        // The kick: no edge, so no blood of its own; the bleeding it leaves is the cuts'.
        new Move(39, "blade_dagger_kick", 5, 2f, 0, 0, 0)};
    // The stepping Zornhau, a spinning Zwerchhau out of a full turn, the thrust out of the plough in a deep lunge, and
    // the launcher rising from a crouch.
    private static final Move[] MASTER_CUTS = {
        new Move(0, "blade_sword_0", 5, 2.5f, -.7, -.7, 0),
        new Move(11, "blade_sword_1", 7, 2.5f, 1, 0, 0),
        new Move(23, "blade_sword_2", 5, 2.5f, 0, 0, 1),
        new Move(34, "blade_sword_3", 6, 3f, 0, 1, 0)};

    /** Eye to the body's middle, less half its width: how far each blade reaches. And how nearly it must be looked at. */
    private static final double DAGGER_REACH = 3.4, SWORD_REACH = 4.6, LOOK = .62;

    private static final class Run {
        final int kind;
        final LivingEntity target;
        final boolean phantom;
        /** The tick of the first move. */
        final long start;
        /** Moves begun (their animation sent) and struck. */
        int begun, struck;

        Run(int kind, LivingEntity target, boolean phantom, long start) {
            this.kind = kind;
            this.target = target;
            this.phantom = phantom;
            this.start = start;
        }
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();
    private static boolean dealing;

    /** A combo's own blow is landing: no knockback but the one the combo gives itself (ServerEvents). */
    public static boolean dealing() {return dealing;}

    public static boolean running(ServerPlayer p) {return RUNS.containsKey(p.getUUID());}

    private static Move[] moves(int kind) {return kind == 0 ? FLURRY : MASTER_CUTS;}
    private static String name(int kind) {return kind == 0 ? "Flurry" : "Master Cuts";}
    private static String ready(int kind) {return kind == 0 ? FLURRY_READY : CUTS_READY;}
    private static int recovery(int kind) {return kind == 0 ? FLURRY_RECOVERY : CUTS_RECOVERY;}
    private static double reach(int kind) {return kind == 0 ? DAGGER_REACH : SWORD_REACH;}

    /** Whether this caster's combo is still recovering. A tick further off than its recovery could reach counts as ready. */
    public static boolean recovering(ServerPlayer p, int kind) {
        long left = HexData.get(p).getLong(ready(kind)) - HexData.now(p);
        return left > 0 && left <= recovery(kind);
    }

    /** The blade's key held: Conjure Daggers' or The Deceiver's. */
    public static void start(ServerPlayer p, Ability a) {
        int kind = a == Ability.DAGGERS ? 0 : 3;
        if (!HexData.unlocked(p, a)) {HexServer.notice(p, "This chapter of your story is still locked."); return;}
        if (RUNS.containsKey(p.getUUID()) || Arsenal.active(p) || GravityGrasp.holding(p) || ScepterBlast.stunned(p)) return;
        SwordGuard.end(p);
        if (recovering(p, kind)) {HexServer.notice(p, name(kind) + " is recovering."); return;}
        LivingEntity target = aimed(p, reach(kind));
        if (target == null) {HexServer.notice(p, "Get within reach of a body and look at it."); return;}
        long now = HexData.now(p);
        boolean phantom = !holding(p, kind);
        Run run = new Run(kind, target, phantom, now + (phantom ? DRAW : 0));
        RUNS.put(p.getUUID(), run);
        CompoundTag d = HexData.get(p);
        d.putLong(ready(kind), now + recovery(kind));
        // Held from the first moment: a body that saw the blade forming does not get to walk away from it.
        ScepterBlast.stun(target, (int) (run.start - now) + moves(kind)[0].contact + 6);
        if (phantom) {
            Move[] m = moves(kind);
            d.putLong(HELD, run.start + m[m.length - 1].at + m[m.length - 1].contact + TAIL);
            d.putInt(KIND, kind);
            d.putLong(FORMED, now);
            HexNetwork.animate(p, "blade_draw");
            p.level().playSound(null, p.blockPosition(), HexGodOfStories.CONJURE.get(), SoundSource.PLAYERS, .8f, kind == 0 ? 1.15f : .95f);
        }
        HexServer.reward(p, Discipline.CONJURATION, 90);
        HexNetwork.sync(p);
    }

    /** Every tick of a caster's. */
    public static void tick(ServerPlayer p) {
        Run run = RUNS.get(p.getUUID());
        if (run == null) return;
        long now = HexData.now(p);
        Move[] m = moves(run.kind);
        if (!p.isAlive() || p.isSpectator() || TemporalEngine.frozen(p)) {end(p, run, true); return;}
        if (run.begun < m.length && now >= run.start + m[run.begun].at) {
            if (!holds(p, run)) {end(p, run, true); return;}
            Move move = m[run.begun++];
            HexNetwork.animate(p, move.animation);
            p.level().playSound(null, p.blockPosition(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS,
                run.kind == 0 ? .8f : 1f, (run.kind == 0 ? 1.15f : .85f) + p.getRandom().nextFloat() * .1f);
            // Held through to just past this move's contact; each move renews it.
            ScepterBlast.stun(run.target, move.contact + 6);
        }
        if (run.struck < run.begun && now >= run.start + m[run.struck].at + m[run.struck].contact) {
            if (!holds(p, run)) {end(p, run, true); return;}
            Move move = m[run.struck++];
            strike(p, run, move, run.struck == m.length);
        }
        if (run.struck == m.length && now >= run.start + m[m.length - 1].at + m[m.length - 1].contact + TAIL) end(p, run, false);
    }

    private static void strike(ServerPlayer p, Run run, Move move, boolean last) {
        LivingEntity target = run.target;
        target.invulnerableTime = 0;
        boolean hit;
        dealing = true;
        // The Master Cuts with a burning Deceiver in hand burn what they cut (never the Flurry: that is the knife's).
        boolean fire = run.kind != 0;
        try {hit = target.hurt(p.damageSources().playerAttack(p), move.damage + (fire ? BladeFire.scorch(p, target) : 0));} finally {dealing = false;}
        if (hit && fire) BladeFire.burn(p, target);
        Vec3 forward = forward(p);
        if (hit && (move.right != 0 || move.up != 0 || move.forward != 0))
            blood(p, target, move.right, move.up, move.forward, run.kind == 0 ? 1.3f : 1.7f);
        p.level().playSound(null, target.blockPosition(), HexGodOfStories.BLADE_HIT.get(), SoundSource.PLAYERS, .9f,
            (run.kind == 0 ? 1.1f : .9f) + p.getRandom().nextFloat() * .12f);
        if (!last) return;
        // The end of it: the hold lets go, the cuts bleed for a second, and the body goes back or up.
        ScepterBlast.clear(target);
        Bleed.apply(p, target, 1, 20);
        if (run.kind == 0) {
            target.setDeltaMovement(forward.x * 1.25, .32, forward.z * 1.25);
            p.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1, .8f);
            HexNetwork.fx(target, "impact");
        } else {
            target.setDeltaMovement(forward.x * .2, .78, forward.z * .2);
            p.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1, .75f);
        }
        target.hurtMarked = true;
    }

    /**
     * Ends a combo: finished, or broken off (the body gone, out of reach or out of sight). A broken one lets the body go
     * and drops the caster's arms; a blade formed for it is gone either way.
     */
    private static void end(ServerPlayer p, Run run, boolean broken) {
        RUNS.remove(p.getUUID());
        if (broken) {
            ScepterBlast.clear(run.target);
            HexNetwork.animate(p, "__clear__");
        }
        if (run.phantom) vanish(p);
    }

    private static void vanish(ServerPlayer p) {
        CompoundTag d = HexData.get(p);
        if (!d.contains(HELD)) return;
        d.remove(HELD);
        d.remove(KIND);
        d.remove(FORMED);
        if (p.level() instanceof ServerLevel level) {
            Vec3 hand = p.getEyePosition().add(forward(p).scale(.45)).add(right(p).scale(.35)).add(0, -.45, 0);
            level.sendParticles(HexGodOfStories.GOLD_EMBER.get(), hand.x, hand.y, hand.z, 8, .12, .2, .12, .02);
            level.sendParticles(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, 2, .08, .15, .08, .01);
        }
        HexNetwork.sync(p);
    }

    /** A death, a logout, a crossing: nothing of a combo outlives them. */
    public static void forget(ServerPlayer p) {
        Run run = RUNS.remove(p.getUUID());
        if (run != null) ScepterBlast.clear(run.target);
        CompoundTag d = HexData.get(p);
        d.remove(HELD);
        d.remove(KIND);
        d.remove(FORMED);
    }

    public static void reset() {RUNS.clear();}

    /**
     * A blade's cut opening a body: the blood thrown off toward the side the blade went, in the caster's own right, up
     * and forward. Also used by the blades' ordinary attacks.
     */
    public static void blood(ServerPlayer p, LivingEntity victim, double right, double up, double forward, float power) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Vec3 swing = right(p).scale(right).add(0, up, 0).add(forward(p).scale(forward));
        if (swing.lengthSqr() < 1e-6) return;
        swing = swing.normalize();
        Vec3 middle = victim.getBoundingBox().getCenter();
        Vec3 toward = p.getEyePosition().subtract(middle);
        toward = new Vec3(toward.x, 0, toward.z);
        toward = toward.lengthSqr() < 1e-6 ? Vec3.ZERO : toward.normalize().scale(victim.getBbWidth() * .45);
        Vec3 at = new Vec3(middle.x, victim.getY() + victim.getBbHeight() * .62, middle.z).add(toward);
        spray(level, victim, at, swing, power, p);
    }

    /** Blood out of `victim` at `at`, thrown along `swing` (Blood.slash); `by`'s own view kicks with it, if anyone's. */
    public static void spray(ServerLevel level, Entity victim, Vec3 at, Vec3 swing, float power, Entity by) {
        if (swing.lengthSqr() < 1e-6) return;
        CompoundTag n = new CompoundTag();
        n.putDouble("x", at.x); n.putDouble("y", at.y); n.putDouble("z", at.z);
        n.putDouble("dx", swing.x); n.putDouble("dy", swing.y); n.putDouble("dz", swing.z);
        n.putFloat("power", power);
        // Who cut: their own view kicks with it (Blood.slash), the harder the heavier the blade.
        if (by != null) n.putInt("by", by.getId());
        HexNetwork.near(level, at, 64, new HexNetwork.Message(HexNetwork.BLADE, victim.getId(), n));
    }

    /** Whether the combo may go on: the body alive, here, in reach and in sight, and still looked at. */
    private static boolean holds(ServerPlayer p, Run run) {
        LivingEntity t = run.target;
        return t.isAlive() && !t.isRemoved() && t.level() == p.level() && within(p, t, reach(run.kind)) && p.hasLineOfSight(t);
    }

    private static boolean within(ServerPlayer p, LivingEntity t, double reach) {
        Vec3 eye = p.getEyePosition(), middle = t.getBoundingBox().getCenter(), to = middle.subtract(eye);
        double distance = to.length() - t.getBbWidth() / 2;
        return distance <= reach && (to.lengthSqr() < 1e-6 || to.normalize().dot(p.getLookAngle()) >= LOOK);
    }

    /** The body the caster means: the one their look passes through, or failing that the nearest well inside it. */
    private static LivingEntity aimed(ServerPlayer p, double reach) {
        if (HexServer.target(p, reach + 1) instanceof LivingEntity looked && HexServer.foe(p, looked) && within(p, looked, reach)) return looked;
        LivingEntity best = null;
        double nearest = Double.MAX_VALUE;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(reach + 1),
            e -> HexServer.foe(p, e) && within(p, e, reach) && p.hasLineOfSight(e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(p.getEyePosition());
            double off = 1 - to.normalize().dot(p.getLookAngle());
            if (off > .12) continue;
            if (to.lengthSqr() < nearest) {nearest = to.lengthSqr(); best = e;}
        }
        return best;
    }

    /** Whether this caster holds this blade in their main hand already. */
    private static boolean holding(ServerPlayer p, int kind) {
        ItemStack held = p.getMainHandItem();
        return held.getItem() instanceof ConjuredWeapon w && w.kind == kind && ConjuredWeapon.belongsTo(held, p);
    }

    private static Vec3 forward(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The caster's right, level with the ground. */
    private static Vec3 right(ServerPlayer p) {
        Vec3 f = forward(p);
        return new Vec3(-f.z, 0, f.x);
    }
}
