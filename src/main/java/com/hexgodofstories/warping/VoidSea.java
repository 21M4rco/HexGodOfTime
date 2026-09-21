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
}
