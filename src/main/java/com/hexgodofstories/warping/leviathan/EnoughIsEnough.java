package com.hexgodofstories.warping.leviathan;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Enough is Enough.</b> Hexor's second passive: a clock on every single thing in its ocean.
 *
 * <p>Thirty seconds. That is how long anything gets to be a toy. From the moment the realm's own
 * sweep first sees a living thing in it, a clock runs down for that thing alone, and when it
 * reaches zero the creature is finished playing with it — not with the sea, not with everyone in
 * it, with <em>it</em>. Two swimmers who arrived a minute apart are on two different clocks, and
 * one of them can be watched from below while the other is being eaten.
 *
 * <p>Past zero, everything the hunt does for atmosphere is off the table for that target. No
 * circling, no watching, no grazing, no vanishing and no feints: the state machine is held on the
 * committed approach, the attack roll can only come up as something that kills, the pause between
 * patterns collapses, the leap stops being a rare event, and a body held in the jaws is chewed
 * nearly twice as often. The one mercy in the repertoire — the short window the body crush leaves
 * open to get out of the coil — is not offered either.
 *
 * <p>It also aims perfectly. The creature's long range knowledge is deliberately fuzzed, with an
 * error that grows with distance, so an approach reads as searching rather than homing; against a
 * decided target that fuzz is overwritten with the truth every tick, and the leads its strikes are
 * solved from stop being damped. Prey that jinks is supposed to be missed sometimes. Not this one.
 *
 * <p>The clock counts presence, not wall time: it only runs down on the ticks the sweep can
 * actually see the thing, so something that spent a minute in an unloaded chunk has not used any
 * of its thirty seconds. Being killed clears it — whatever comes back is a new toy — and so does
 * being gone long enough to be forgotten.
 *
 * <p>No Minecraft in here on purpose: the table is keyed by UUID and the choice of strike is a
 * pure function, so both can be checked by the build.
 */
public final class EnoughIsEnough {
    /** Thirty seconds at twenty ticks a second. How long anything gets to be played with. */
    public static final int PATIENCE_TICKS = 600;

    /** How long a clock survives without being seen. A minute out of the realm is a fresh start. */
    public static final int FORGET_TICKS = 1200;

    /** Hard bound on the table, so a sea somebody has filled with livestock cannot grow it forever. */
    public static final int MAX_TRACKED = 256;

    private static final class Clock {
        int remaining = PATIENCE_TICKS;
        long seen;
    }

    private static final Map<UUID, Clock> CLOCKS = new HashMap<>();

    private EnoughIsEnough() { }

    /**
     * The realm's sweep reporting that this thing is still in the water.
     *
     * @param elapsed ticks since the sweep last ran, which is what the clock spends
     */
    public static void present(UUID id, long now, int elapsed) {
        Clock clock = CLOCKS.get(id);
        if (clock == null) {
            if (CLOCKS.size() >= MAX_TRACKED) return;
            clock = new Clock();
            CLOCKS.put(id, clock);
        }
        clock.seen = now;
        if (elapsed > 0 && clock.remaining > 0) clock.remaining = Math.max(0, clock.remaining - elapsed);
    }

    /** True once this thing has had its thirty seconds. */
    public static boolean marked(UUID id) {
        Clock clock = CLOCKS.get(id);
        return clock != null && clock.remaining <= 0;
    }

    /** Ticks of being played with that are left. Full for anything the sea has not met yet. */
    public static int remaining(UUID id) {
        Clock clock = CLOCKS.get(id);
        return clock == null ? PATIENCE_TICKS : clock.remaining;
    }

    /** A kill, or a deliberate release. Whatever comes back gets its thirty seconds again. */
    public static void forget(UUID id) { CLOCKS.remove(id); }

    /** Drops the clocks of everything the sweep has not seen for a while. */
    public static void sweep(long now) {
        CLOCKS.entrySet().removeIf(entry -> now - entry.getValue().seen >= FORGET_TICKS);
    }

    public static void reset() { CLOCKS.clear(); }

    /** For the build's own checks. */
    public static int tracked() { return CLOCKS.size(); }

    /**
     * What a decided target is answered with.
     *
     * <p>Everything the creature does for effect is gone from this table. The scream and the vortex
     * are area denial, the coil is a set piece with a way out of it, and the feint is a lie — all
     * four are things it does while it is still enjoying itself. What is left is the jaws, the two
     * patterns that end with a body passing through the prey, the grab that leads to being dragged
     * down, and the leaps, which is the whole of "jump at it, drag it under and chew".
     *
     * @param roll     an even roll, nought to one
     * @param distance blocks to the target
     * @param holding  whether the jaws or tendrils already have it
     * @param airborne whether it has left the water
     * @param canLeap  whether a leap is available this tick
     */
    public static LeviathanAttack strike(float roll, double distance, boolean holding, boolean airborne, boolean canLeap) {
        // Already caught: take it down, or throw it up and meet it on the way back.
        if (holding) return roll < 0.75f ? LeviathanAttack.DRAG_BELOW : LeviathanAttack.AIR_THROW;
        // Off the water is answered by leaving the water.
        if (canLeap) return LeviathanAttack.SKY_LEAP;
        if (airborne && distance < LeviathanAttack.BREACH_BITE.range) return LeviathanAttack.BREACH_BITE;
        // Too far to bite: close the distance with the body itself.
        if (distance > 44) return roll < 0.5f ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE;
        if (roll < 0.34f) return LeviathanAttack.PREDATORY_BITE;
        if (roll < 0.62f) return LeviathanAttack.ABYSSAL_LUNGE;
        if (roll < 0.82f) return LeviathanAttack.TENDRIL_GRAB;
        return LeviathanAttack.DRAG_BELOW;
    }
}
