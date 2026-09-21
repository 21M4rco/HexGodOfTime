package com.hexgodofstories.warping.leviathan;

/**
 * Attack patterns. Every pattern is three phases: windup, active, recover. The combat controller
 * drives the phase clock; the entity only stores the ordinal and the elapsed tick so a client can
 * play the matching animation without receiving per-bone data.
 */
public enum LeviathanAttack {
    /** Jaw windup then a violent snap. Can capture the victim inside the mouth. */
    PREDATORY_BITE(18, 10, 22, 14, true),
    /** Straighten, fold the fins, and pass clean through the target instead of stopping. */
    ABYSSAL_LUNGE(22, 18, 26, 34, true),
    /** Force travels down the spine and the tail whips through the target volume. */
    TAIL_SWEEP(20, 12, 24, 30, false),
    /** Tendrils catch and reel the target in. Outcome is chosen afterwards. */
    TENDRIL_GRAB(16, 14, 18, 18, true),
    /** Take hold and dive hard. Usually ends in a release far from the surface. */
    DRAG_BELOW(14, 70, 30, 12, true),
    /** Coil, tighten slowly, allow a short escape window, then crush. */
    BODY_CRUSH(26, 80, 34, 16, false),
    /** Jaw opens fully and a pressure pulse leaves the body in every direction. */
    VOID_SCREAM(34, 16, 40, 40, false),
    /** Descend well below the target then accelerate straight up. */
    DEEP_CHARGE(30, 40, 24, 60, true),
    /** Strike upward through the surface at boats and anything floating. */
    SURFACE_RAM(20, 14, 26, 26, false),
    /** Leave the water completely with the jaws open, hunting anything airborne. */
    BREACH_BITE(34, 60, 44, 95, true),
    /** Throw a held victim into the air and try to meet them on the way down. */
    AIR_THROW(14, 40, 30, 10, false),
    /** A full windup that is abandoned on purpose. Never deals damage. */
    FAKE_ATTACK(20, 14, 26, 24, false),
    /** Orbit at speed and drag everything nearby toward the centre. */
    WATER_VORTEX(24, 90, 30, 28, false);

    public final int windup, active, recover;
    /** Distance in blocks at which the pattern may be started. */
    public final int range;
    /** Whether the pattern can end with the target held in the jaws or tendrils. */
    public final boolean canHold;

    LeviathanAttack(int windup, int active, int recover, int range, boolean canHold) {
        this.windup = windup; this.active = active; this.recover = recover; this.range = range; this.canHold = canHold;
    }

    public int total() { return windup + active + recover; }
    public boolean lethal() { return this != FAKE_ATTACK; }
    /** Patterns that need the creature to leave the water. */
    public boolean aerial() { return this == BREACH_BITE || this == AIR_THROW; }
    public static LeviathanAttack byId(int id) { return values()[Math.floorMod(id, values().length)]; }
}
