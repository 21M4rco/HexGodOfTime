package com.hexgodofstories.warping;

/**
 * The shape of a Warping portal: a pool of something that is not water, spreading across the floor.
 *
 * <p>This replaces the shattered-mirror fracture entirely. A break used to be a jagged union of an
 * impact hole, branching cracks and loose slivers, and it read as glass because that is what it
 * was. A pool reads as liquid for the opposite reasons: the outline is closed, smooth and
 * everywhere convex-ish, there are no straight lines and no points, and it does not appear at a
 * size — it runs outward from where it was poured, and keeps running for as long as the caster
 * keeps pouring.
 *
 * <p><b>The rim is a radius per direction, and that is the whole shape.</b> Ninety six directions,
 * each with its own distance, interpolated between. It makes every question about the portal cheap
 * — whether a point is in it is one interpolation and one comparison rather than a walk over two
 * hundred polygons — and it makes the one property that matters structural rather than hoped for:
 * neighbouring directions cannot disagree by much, because everything driving them is a sum of low
 * harmonics of the angle. A pool cannot grow a spike.
 *
 * <p><b>It spreads rather than scales.</b> Each direction has its own moment of starting to move
 * and its own pace, both smooth functions of the angle, so the liquid reaches one way early and
 * creeps another way late: at a quarter of the hold it is a lopsided bead, at half a broad lobed
 * pool, and at full charge it has run out to its whole reach in every direction it was ever going
 * to. Nothing retreats — at any two moments of a hold, every direction is at least as far out as it
 * was — which is what makes holding the key read as pouring rather than as resizing.
 *
 * <p>Pure geometry with no Minecraft in it: the renderer lays this across the ground, the server
 * asks it who is standing in it, and the build checks it without a world.
 */
public final class WarpPool {
    private WarpPool() { }

    /** Directions the rim is measured in. Fine enough that the outline has no corners in it. */
    public static final int STEPS = 96;

    /** How much of its reach the pool already covers on the tick it is poured. */
    private static final double BEAD = 0.11;

    private static final double TAU = Math.PI * 2;

    /**
     * The rim: how far the liquid has run in each of {@link #STEPS} directions.
     *
     * @param seed     this portal's own seed, so no two pools are the same pool
     * @param reach    how far the liquid may run at this charge, in blocks
     * @param progress nought when it is poured, one when the portal opens
     */
    public static double[] rim(long seed, double reach, double progress) {
        double[] rim = new double[STEPS];
        if (!(reach > 0)) return rim;
        double t = progress < 0 ? 0 : Math.min(1, progress);
        for (int i = 0; i < STEPS; i++) {
            double angle = i * TAU / STEPS;
            rim[i] = reach * lobe(seed, angle) * (BEAD + (1 - BEAD) * front(seed, angle, t));
        }
        return rim;
    }

    /**
     * How far out this direction has got, nought to one.
     *
     * <p>Eased at both ends, so the liquid does not start or stop moving on a frame. The start is
     * the interesting half: a direction that begins late stays a shallow edge while the rest of the
     * pool is already broad, which is what gives a spreading pool its lopsided leading edge.
     */
    public static double front(long seed, double angle, double progress) {
        double onset = onset(seed, angle);
        double run = (progress - onset) / Math.max(0.12, 1 - onset);
        if (run <= 0) return 0;
        if (run >= 1) return 1;
        // Viscous easing: the front heaves into motion and settles without looking scaled.
        return run * run * run * (run * (run * 6 - 15) + 10);
    }

    /**
     * How hard this direction is currently advancing, nought to one.
     *
     * <p>The renderer brightens the rim and throws its droplets by this, so the leading edge of the
     * pool is where the light is and a direction that has already finished running is quiet.
     */
    public static double advancing(long seed, double angle, double progress) {
        double before = front(seed, angle, Math.max(0, progress - 0.05));
        return Math.min(1, (front(seed, angle, progress) - before) * 14);
    }

    /**
     * The broad, slow bulges a spreading pool has instead of a radius.
     *
     * <p>Normalised by the most the three harmonics can ever sum to, so the furthest the liquid can
     * run in any direction is exactly the reach the charge bought and no more — a pool that could
     * quietly overrun its own paid-for size by a quarter would make the cost curve a lie. The floor
     * under it is what stops a bulge on one side leaving a spur on the other.
     */
    private static double lobe(long seed, double angle) {
        // Broad asymmetric lobes make a puddle, not a mathematically centred portal disc.
        double n = 0.89
            + 0.18 * Math.sin(angle + phase(seed, 1))
            + 0.13 * Math.sin(angle * 2 + phase(seed, 2))
            + 0.075 * Math.sin(angle * 4 + phase(seed, 3));
        return Math.max(0.46, n / 1.275);
    }

    /** When this direction starts to run. Smooth in the angle, so the edge is never ragged. */
    private static double onset(long seed, double angle) {
        double n = 0.23
            + 0.20 * Math.sin(angle + phase(seed, 4))
            + 0.11 * Math.sin(angle * 2 + phase(seed, 5))
            + 0.055 * Math.sin(angle * 3 + phase(seed, 6));
        return Math.max(0, Math.min(0.52, n));
    }

    private static double phase(long seed, int k) {
        long h = seed * 0x9E3779B97F4A7C15L + k * 0x632BE59BD9B4E019L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53) * TAU;
    }

    // ------------------------------------------------------------------ asking the pool things

    /** How far the liquid has run in one exact direction, between the two it was measured in. */
    public static double radius(double[] rim, double angle) {
        if (rim.length == 0) return 0;
        double at = (angle % TAU + TAU) % TAU / TAU * rim.length;
        int i = (int) at;
        double blend = at - i;
        return rim[i % rim.length] * (1 - blend) + rim[(i + 1) % rim.length] * blend;
    }

    /** Whether this point of the floor is under the pool. */
    public static boolean inside(double[] rim, double x, double z) {
        double distance = Math.sqrt(x * x + z * z);
        if (distance < 1.0E-6) return rim.length > 0 && rim[0] > 0;
        return distance <= radius(rim, Math.atan2(z, x));
    }

    /**
     * Whether a body of this width, standing here, is over enough pool to go through it.
     *
     * <p>A point is not what stands on a floor. Nine of the footprint's own points are asked — the
     * middle and eight around it, spread to the body's width — and the middle must be over the pool
     * with over half the rest. That makes the edge behave like the edge of a pool rather than like a
     * trigger: stand beside it and nothing happens, put one foot in and nothing happens, and go
     * through when most of you is over it. It also scales, so something two blocks across needs
     * genuinely two blocks of liquid and a small creature can use a puddle that would not take a
     * player.
     */
    public static boolean footing(double[] rim, double x, double z, double width) {
        if (!inside(rim, x, z)) return false;
        double spread = Math.max(0.12, width * 0.45);
        int open = 1, total = 1;
        for (int ix = -1; ix <= 1; ix++) for (int iz = -1; iz <= 1; iz++) {
            if (ix == 0 && iz == 0) continue;
            total++;
            if (inside(rim, x + ix * spread, z + iz * spread)) open++;
        }
        return open * 2 >= total;
    }

    /** How far the pool reaches from its middle, for the volume the server has to look in. */
    public static double extent(double[] rim) {
        double far = 0;
        for (double r : rim) far = Math.max(far, r);
        return far;
    }

    /** The shallowest the pool is anywhere, which is what decides whether it has an edge at all. */
    public static double narrowest(double[] rim) {
        double least = Double.MAX_VALUE;
        for (double r : rim) least = Math.min(least, r);
        return rim.length == 0 ? 0 : least;
    }
}
