package com.hexgodofstories.warping.leviathan;

/**
 * <b>Trill of the Hunt.</b> Hexor's passive: the busier its ocean is, the less of a game the hunt
 * is allowed to be.
 *
 * <p>One swimmer is the hunt the creature was written around, and against one swimmer nothing here
 * moves: it still circles, watches, grazes, feints and takes its time, because a lone thing in an
 * ocean is something to play with. Every additional living thing in the realm is another reason
 * not to bother. The curve runs from one occupant to {@link #CROWD}, past which the water is
 * simply full and the passive is at its limit.
 *
 * <p>Three things follow from it, and the first two come out of mood rather than out of new
 * behaviour, because the mood is what the whole repertoire already reads:
 *
 * <ul>
 *   <li><b>It plays less.</b> A ceiling drops onto patience and a floor rises under frenzy, and
 *       the state roll's play share is the product of the two — so toying, stalking and the feint
 *       are squeezed out from both ends, and a full sea leaves the creature permanently past the
 *       frenzy threshold, where the only states left are the ones that end in a strike. The
 *       commit clock also runs faster, so circling turns into committing sooner.
 *   <li><b>It moves faster.</b> Every speed the behaviour asks for is multiplied on the way into
 *       the move control, so the whole repertoire keeps its relative pacing and simply happens
 *       harder. The turn radius is untouched: it covers ground faster, it does not corner tighter.
 *   <li><b>It hits harder.</b> Every blow is multiplied before {@link HexorBlow} weighs it against
 *       the victim, so the violence lands on a player and a boss alike.
 * </ul>
 *
 * <p>Pure arithmetic with no Minecraft in it, so the curve can be checked without a world.
 */
public final class TrillOfTheHunt {
    private TrillOfTheHunt() { }

    /** One occupant is the hunt as written. At or below this the passive does nothing at all. */
    public static final int ALONE = 1;

    /** Occupants at which the passive is fully wound up. Beyond it there is nothing left to give. */
    public static final int CROWD = 8;

    /** Frenzy this leaves under the creature, out of a maximum of one. */
    private static final float FRENZY_FLOOR = 0.85f;
    /** Patience it takes away, out of a maximum of one. */
    private static final float PATIENCE_TAKEN = 0.9f;
    /** Extra ticks on the commit clock per tick of fruitless hunting, at a full sea. */
    private static final float PRESSURE_GAIN = 2f;
    /** Extra swimming speed at a full sea. */
    private static final double URGENCY_GAIN = 0.45;
    /** Extra damage at a full sea. */
    private static final float VIOLENCE_GAIN = 0.6f;

    /**
     * Nought while the creature has the ocean to itself, one once the water is full.
     *
     * @param occupants living things in the realm that are not Hexor: players and anything else
     *                  that ended up in the water with them
     */
    public static float thrill(int occupants) {
        if (occupants <= ALONE) return 0f;
        return Math.min(1f, (occupants - ALONE) / (float) (CROWD - ALONE));
    }

    /** Frenzy the creature cannot fall below while the water is this busy. */
    public static float frenzyFloor(float thrill) { return FRENZY_FLOOR * clamp(thrill); }

    /** Patience it cannot rise above. Play is patience against frenzy, so both ends are squeezed. */
    public static float patienceCeiling(float thrill) { return 1f - PATIENCE_TAKEN * clamp(thrill); }

    /** What one tick of prey in reach and nothing landed is worth on the commit clock. */
    public static int pressureStep(float thrill) { return 1 + Math.round(PRESSURE_GAIN * clamp(thrill)); }

    /** Multiplier on every speed the behaviour asks for. */
    public static double urgency(float thrill) { return 1.0 + URGENCY_GAIN * clamp(thrill); }

    /** Multiplier on every blow, applied before the blow is weighed against its victim. */
    public static float violence(float thrill) { return 1f + VIOLENCE_GAIN * clamp(thrill); }

    /** NaN, and anything outside the curve, resolves to the calm end of it. */
    private static float clamp(float thrill) { return thrill > 0f ? Math.min(thrill, 1f) : 0f; }
}
