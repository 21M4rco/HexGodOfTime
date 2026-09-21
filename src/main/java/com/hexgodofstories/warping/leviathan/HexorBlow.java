package com.hexgodofstories.warping.leviathan;

/**
 * What one of Hexor's blows is worth against the thing it lands on.
 *
 * <p>Every attack pattern names its damage in points, and every one of those numbers was written
 * against a person: twenty points, so a bite is most of one and a body crush is all of it. Against
 * anything built to that scale the number is right, and nothing here touches it.
 *
 * <p>Against a health pool that is large on purpose it is the wrong number by the size of the
 * pool. A warden carries five hundred points and no armour, so as written it survives forty of
 * those bites — several minutes of being chewed on by a hundred and fifty blocks of apex predator
 * whose entire behaviour is built around playing with what it has caught. Points do not scale with
 * the fiction. A share of the pool does.
 *
 * <p>So above the pivot a blow takes the share of the victim's pool that it would have taken of a
 * person's, and the consequence is the whole point of it: anything past the pivot dies in
 * {@code PIVOT / amount} blows whatever its health bar says. Four bites. Two and a half lunges.
 * A warden, a ravager, a wither and a thousand point modded boss all take the same handful of
 * blows, and none of them is a fight — which is what a warden being a toy means.
 *
 * <p>Below the pivot nothing moves at all. A player has twenty points, and the whole hunt — the
 * drag, the chew, the escape window, every number balanced against a swimmer — is exactly as it
 * was. A player given a pool larger than two people scales like anything else with one, which is
 * the same rule rather than an exception to it.
 *
 * <p>The pool read is the victim's maximum, never its current health. Current health would shrink
 * every blow as the fight wore on and make the last point of a boss the most expensive one to
 * take, which is the opposite of what a proportional weapon is for.
 */
public final class HexorBlow {
    private HexorBlow() { }

    /** A person: the twenty points every pattern's damage was written against. */
    public static final float PERSON = 20f;

    /** Pools this size and under take their blows exactly as written. Two people. */
    public static final float PIVOT = 2f * PERSON;

    /**
     * Ceiling on the multiplier, reached at a pool of about a hundred and sixty thousand points.
     * It is there so an entity carrying an absurd or infinite max health attribute cannot turn a
     * blow into a non-finite number; nothing anyone would fight comes near it, so it is a guard
     * rather than a balance figure.
     */
    public static final float CEILING = 4096f;

    /**
     * @param amount    the blow as its pattern wrote it, in points against a person
     * @param maxHealth the victim's whole health pool
     * @return the points to actually deal
     */
    public static float against(float amount, float maxHealth) {
        // Written as failed comparisons so a NaN pool, or a blow of nothing, falls through to the
        // number the caller already had instead of propagating.
        if (!(amount > 0f) || !(maxHealth > PIVOT)) return amount;
        return amount * Math.min(maxHealth / PIVOT, CEILING);
    }

    /** How many blows of {@code amount} the pool is worth, for the record and for the tests. */
    public static int blowsToKill(float amount, float maxHealth) {
        float each = against(amount, maxHealth);
        return each <= 0f ? Integer.MAX_VALUE : (int) Math.ceil(maxHealth / each);
    }
}
