package com.hexgodofstories.warping;

/**
 * The Void Sea's geometry, in one place.
 *
 * <p>The realm was originally a hundred and thirty five blocks of water, which is less than the
 * length of the creature that now lives in it. It has been rebuilt far deeper so the Abyssal
 * Pilgrim can actually do what it is built to do: dive out of sight, hold two hundred blocks below
 * a swimmer, accelerate vertically for long enough to matter, and throw itself clear of the
 * surface. These constants and the shipped dimension JSON must agree; {@code VoidSeaShapeTest}
 * fails the build if they ever drift apart.
 */
public final class VoidSea {
    private VoidSea() { }

    /** Matches data/hexgodofstories/dimension_type/warping_void_sea.json. */
    public static final int MIN_Y = -768, HEIGHT = 1280;
    public static final int MAX_Y = MIN_Y + HEIGHT - 1;

    /**
     * Layer stack in data/hexgodofstories/dimension/warping_void_sea.json.
     *
     * <p>The floor is Nothingness, not stone. It has bedrock's hardness, no loot table and no item
     * form, so the bottom of this realm cannot be mined, blown open or dug through — there is no
     * back door out of the Pilgrim's ocean, and no way to stand in a pocket underneath it.
     */
    public static final int BASE = 83, WATER = 935;

    /** Topmost water block. Everything above this is air. */
    public static final int SURFACE = MIN_Y + BASE + WATER - 1;
    /** Topmost solid block of the sea floor. */
    public static final int FLOOR = MIN_Y + BASE - 1;
    /** Usable water column in blocks. */
    public static final int DEPTH = SURFACE - FLOOR;
    /** Clear air above the waterline, which is what a full breach needs. */
    public static final int SKY = MAX_Y - SURFACE;

    /**
     * Where a warped player lands: high enough above the waterline that arriving is a fall.
     *
     * <p>Three blocks up put them in the water before they had seen any of it, which wastes the
     * one moment the realm gets to introduce itself. Fifty gives about two and a half seconds of
     * open sky and a horizon of nothing, and the water takes the fall damage, so the cost of the
     * drop is entirely in how long it lasts.
     */
    public static final int ARRIVAL = SURFACE + 50;

    /**
     * The realm has one occupant and the whole sea is its territory, so nothing bounds it
     * horizontally: it crosses Warping's cells freely and goes wherever the prey is. The only
     * bound that exists is the water column itself.
     */
    public static double clampY(double y) { return Math.max(FLOOR + 6, Math.min(SURFACE + 200, y)); }

    // ------------------------------------------------------------------ the swell

    /**
     * The sea's own shape: a storm held still. The water stands up out of the flat waterline in long swells running
     * one way, their crests bent rather than ruled, rising here and there into heavy peaks some twenty blocks tall and
     * falling away from them smoothly, never steeper than a block up for a block along. A second, smaller swell runs
     * across the first, which is what gathers the crests into peaks, and a little chop over both keeps the troughs
     * from lying flat.
     *
     * <p>It is a pure function of the column, and it is the water itself: {@link VoidSeaSwell} builds it out of water
     * blocks, once, the first time each chunk of the realm is loaded, and the realm's water never flows, so the shape
     * stays put. Nothing about it moves and nothing about it is networked.
     *
     * <p>Columns: heading in degrees, wavelength, height, how sharp the crest is, two bends of the crest line (each a
     * size and the distance it bends over), the distance along a crest over which it rises and falls, a phase, and
     * the least a crest falls to along its length.
     */
    private static final double[][] SWELLS = {
        { 27, 160, 19.0, 2.2, 18, 380, 7, 151, 230, 0.0, 0.30 },
        { 82,  96,  6.0, 1.6, 10, 210, 4,  97, 170, 1.9, 0.40 },
        {-25,  44,  1.8, 1.2,  5, 130, 2,  61, 150, 4.4, 0.50 },
    };
    private static final double CHOP = 0.5;
    private static final double TAU = Math.PI * 2;
    private static final double[] ALONG_X = new double[SWELLS.length], ALONG_Z = new double[SWELLS.length];
    static {
        for (int i = 0; i < SWELLS.length; i++) {
            double heading = Math.toRadians(SWELLS[i][0]);
            ALONG_X[i] = Math.cos(heading);
            ALONG_Z[i] = Math.sin(heading);
        }
    }
    /** The tallest the swell can stand above the waterline, in blocks: every crest and the chop at their highest. */
    public static final double SWELL_MOST;
    static {
        double most = CHOP;
        for (double[] swell : SWELLS) most += swell[2];
        SWELL_MOST = most;
    }

    /** How far the water stands above the still waterline in this column, in blocks: never below it. */
    public static double swell(double x, double z) {
        double s = 0;
        for (int i = 0; i < SWELLS.length; i++) {
            double[] w = SWELLS[i];
            double along = x * ALONG_X[i] + z * ALONG_Z[i], across = -x * ALONG_Z[i] + z * ALONG_X[i];
            double bend = w[4] * Math.sin(TAU * across / w[5] + w[9] + 1.3) + w[6] * Math.sin(TAU * across / w[7] + w[9] * 2.1 + 4.1);
            double crest = Math.pow(Math.max(0, (1 + Math.cos(TAU * (along + bend) / w[1] + w[9])) / 2), w[3]);
            double rise = .5 + .5 * Math.sin(TAU * across / w[8] + w[9] * 1.7 + .8 * Math.sin(TAU * along / (w[8] * 2.7) + w[9]));
            rise = rise * rise * (3 - 2 * rise);
            s += w[2] * crest * (w[10] + (1 - w[10]) * rise);
        }
        return s + CHOP * (.5 + .5 * Math.sin(TAU * (x * .83 + z * .56) / 23 + 1)) * (.5 + .5 * Math.sin(TAU * (x * -.4 + z * .92) / 17 + 2));
    }

    /** The waterline over this column, in {@link #SURFACE}'s own terms (the top water block), lifted by the swell. */
    public static double surface(double x, double z) { return SURFACE + swell(x, z); }
}
