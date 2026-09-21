package com.hexgodofstories.warping.leviathan;

/**
 * Top level behavioural states. Glow and speed scalars are shared by the server AI and by the
 * client renderer, so a state byte is the only thing the network has to carry.
 */
public enum LeviathanState {
    /** No prey is committed to. Wide, slow sweeps of the dimension. */
    SEARCH(0.55f, 0.18f, 120, 520),
    /** Prey located far away. Closing distance while staying deep. */
    TRACK(0.85f, 0.32f, 80, 400),
    /** Held below and behind the prey, lights dimmed, deliberately hard to see. */
    STALK(0.60f, 0.05f, 100, 600),
    /** Deliberate non lethal harassment. The hunt is being extended on purpose. */
    TOY(0.90f, 0.45f, 60, 260),
    /** Committed approach. No longer hiding. */
    HUNT(1.30f, 0.80f, 40, 220),
    /** Repositioning to a blind angle with the bioluminescence shut down. */
    AMBUSH(0.70f, 0.02f, 50, 240),
    /** An attack pattern is executing. Duration is owned by the combat controller. */
    ATTACK(1.60f, 1.00f, 10, 200),
    /** Fast chaining of lethal patterns. Toying is suppressed. */
    FRENZY(1.85f, 1.00f, 100, 700);

    /** Multiplier applied to the creature's base swim speed. */
    public final float speed;
    /** Target bioluminescence, 0 is fully extinguished. */
    public final float glow;
    /** Minimum and maximum ticks the state may hold before the AI reconsiders. */
    public final int minTicks, maxTicks;

    LeviathanState(float speed, float glow, int minTicks, int maxTicks) {
        this.speed = speed; this.glow = glow; this.minTicks = minTicks; this.maxTicks = maxTicks;
    }

    public boolean hidden() { return this == STALK || this == AMBUSH; }
    public boolean committed() { return this == HUNT || this == ATTACK || this == FRENZY; }
    public static LeviathanState byId(int id) { return values()[Math.floorMod(id, values().length)]; }
}
