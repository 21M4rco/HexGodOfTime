package com.hexgodofstories.warping;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The shape of a break in reality: an impact, and the cracks that leave it.
 *
 * <p>The portal used to be a thirty-two sided polygon with a jag applied to each radius. That is a
 * rough circle, and a rough circle with cracks drawn on it still reads as a circle — the silhouette
 * is the thing the eye believes, and a silhouette that never leaves an even radius cannot be
 * mistaken for glass. Nothing here has a radius. The outline is the union of an irregular impact
 * hole and everything that has torn away from it, so the edge of the portal is made of the fracture
 * itself.
 *
 * <p>Three kinds of piece, all of them flat convex quads in portal-local blocks:
 *
 * <ul>
 *   <li><b>The impact.</b> Nine to thirteen vertices at uneven angular steps and wildly uneven
 *       radii — deep notches and long spikes are deliberately drawn far more often than an average
 *       radius. This is the largest and most usable part of the portal, and it is the only part
 *       that grows in every direction as the charge runs.
 *   <li><b>Cracks.</b> Six to ten of them, anchored on the impact's own vertices and running
 *       outward as chains of straight segments that change heading at every joint, occasionally
 *       violently. Lengths are drawn from a heavily skewed distribution, so one side of a break can
 *       carry a fracture three times longer than anything opposite it. Every chain narrows linearly
 *       to nothing, which is what makes the far end a point rather than a cap, and every crack
 *       carries up to two branches that split off part way along it and taper to points of their
 *       own.
 *   <li><b>Shards and splinters.</b> Wedges opening between adjacent cracks, anchored on the
 *       impact's rim, and thin free-standing slivers further out. These are what turn the gaps
 *       between fractures into a broken surface rather than an intact one.
 * </ul>
 *
 * <p><b>It develops rather than scaling.</b> Every feature is drawn once, from a seed, in a fixed
 * order that does not depend on the charge — so the pattern has an identity from the first tick and
 * keeps it. What the charge changes is which features have been born yet and how far each has run:
 * the impact widens, existing cracks extend along their own paths, branches appear part way out,
 * and shards open between fractures that are already there. Nothing is re-rolled, so nothing
 * flickers or re-shapes, and the growth reads as the break spreading.
 *
 * <p><b>Every break is its own.</b> The seed is the portal's. Two breaks at the same place on the
 * same tick are the same shape; two breaks anywhere else are not.
 *
 * <p>Pure geometry with no Minecraft in it: the renderer draws these pieces, the server asks them
 * who is standing in the portal, and the build checks them without a world.
 */
public final class WarpFracture {
    /** The impact hole. */
    public static final int CORE = 0;
    /** A wedge or sliver opened between fractures. */
    public static final int SHARD = 1;
    /** A length of crack. */
    public static final int CRACK = 2;

    /** Edge bits used by {@link Piece#rim}: corner 0→1, 1→2, 2→3, 3→0. */
    public static final int EDGE_0 = 1, EDGE_1 = 2, EDGE_2 = 4, EDGE_3 = 8;

    /**
     * Blocks. Nothing narrower than this is ever a way through, however large the break is.
     *
     * <p>The real gate is {@link #gate}, which scales with the break so that a hairline on a
     * twenty-eight block tear is judged as a hairline rather than as the same absolute width that
     * would be a hole in a small one. What decides it is the narrow end of a piece, not its
     * average: a fracture is a way through where it is genuinely open, and scenery from the point
     * it starts closing up to the point it ends.
     */
    public static final double SOLID_WIDTH = 0.42;

    /** The width a piece of this break has to keep to be something an entity can fall into. */
    public static double gate(double reach) { return Math.max(SOLID_WIDTH, reach * 0.075); }

    private static final double TAU = Math.PI * 2;

    private WarpFracture() { }

    /** One flat convex piece of the break, wound consistently, in portal-local blocks. */
    public static final class Piece {
        public final double[] x, z;
        public final int kind;
        /** Which of this piece's four edges lie on the outer silhouette. */
        public final int rim;
        /** Whether standing on this piece counts as standing in the portal. */
        public final boolean solid;

        Piece(double[] x, double[] z, int kind, int rim, boolean solid) {
            this.x = x; this.z = z; this.kind = kind; this.rim = rim; this.solid = solid;
        }

        public boolean contains(double px, double pz) {
            boolean in = false;
            for (int i = 0, j = 3; i < 4; j = i++) {
                if ((z[i] > pz) != (z[j] > pz)
                    && px < (x[j] - x[i]) * (pz - z[i]) / (z[j] - z[i]) + x[i]) in = !in;
            }
            return in;
        }

        /** Longest edge of the piece, which is what "how far this fracture runs" means. */
        public double span() {
            double best = 0;
            for (int i = 0, j = 3; i < 4; j = i++) best = Math.max(best, Math.hypot(x[i] - x[j], z[i] - z[j]));
            return best;
        }
    }

    // ------------------------------------------------------------------ the plan

    /** A crack, or a branch of one. Drawn once per seed and never re-rolled. */
    private static final class Vein {
        int anchor;                 // impact vertex this one leaves from, for primaries
        double aim;                 // heading offset from the anchor's own direction
        double length;              // full run, as a fraction of the portal's reach
        double width;               // width where it leaves, as a fraction of the portal's reach
        double birth;               // charge at which it starts to exist
        double[] turn, share;       // per-joint heading change and per-segment share of the run
        double at;                  // for a branch: where along the parent it splits off
        final List<Vein> children = new ArrayList<>(2);
    }

    private static final class Plan {
        double[] ang, rad;          // the impact's vertices
        final List<Vein> veins = new ArrayList<>();
        double[] shardBirth, shardOut, shardSkew;
        double[] splinterAng, splinterOut, splinterLen, splinterWidth, splinterBirth, splinterTurn;
    }

    private static Plan plan(long seed) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
        Plan p = new Plan();

        // The impact. Uneven angular steps, and radii drawn to be extreme far more often than
        // average: a fifth of them are deep notches and a fifth are long spikes.
        int spokes = 9 + r.nextInt(5);
        p.ang = new double[spokes];
        p.rad = new double[spokes];
        double[] step = new double[spokes];
        double sum = 0;
        for (int i = 0; i < spokes; i++) { step[i] = 0.45 + r.nextDouble(); sum += step[i]; }
        double a = r.nextDouble() * TAU;
        for (int i = 0; i < spokes; i++) {
            p.ang[i] = a;
            a += step[i] / sum * TAU;
            double roll = r.nextDouble();
            p.rad[i] = roll < 0.24 ? 0.30 + r.nextDouble() * 0.16
                     : roll > 0.76 ? 1.18 + r.nextDouble() * 0.55
                     : 0.55 + r.nextDouble() * 0.45;
        }

        // Cracks. The first three are there from the first tick; the rest arrive as the charge runs.
        // Anchors are dealt from a shuffled list of the impact's own vertices, so the fractures are
        // spread around it without being spaced evenly: lengths do the asymmetry, not clustering.
        int cracks = 6 + r.nextInt(5);
        int[] order = new int[spokes];
        for (int i = 0; i < spokes; i++) order[i] = i;
        for (int i = spokes - 1; i > 0; i--) { int j = r.nextInt(i + 1); int swap = order[i]; order[i] = order[j]; order[j] = swap; }
        for (int i = 0; i < cracks; i++) {
            Vein v = vein(r, i < 3 ? 0 : 0.10 + r.nextDouble() * 0.5,
                0.18 + Math.pow(r.nextDouble(), 2.4) * 0.62,
                0.055 + r.nextDouble() * 0.075);
            v.anchor = order[i % spokes];
            v.aim = (r.nextDouble() - 0.5) * 0.85;
            int branches = r.nextInt(3);
            for (int bIndex = 0; bIndex < branches; bIndex++) {
                Vein b = vein(r, Math.max(v.birth, 0.2) + r.nextDouble() * 0.45,
                    v.length * (0.25 + r.nextDouble() * 0.5),
                    v.width * (0.45 + r.nextDouble() * 0.3));
                b.at = 0.22 + r.nextDouble() * 0.6;
                b.aim = (r.nextDouble() < 0.5 ? -1 : 1) * (0.42 + r.nextDouble() * 0.8);
                if (r.nextDouble() < 0.45) {
                    Vein c = vein(r, b.birth + r.nextDouble() * 0.3, b.length * (0.3 + r.nextDouble() * 0.4),
                        b.width * (0.4 + r.nextDouble() * 0.3));
                    c.at = 0.3 + r.nextDouble() * 0.5;
                    c.aim = (r.nextDouble() < 0.5 ? -1 : 1) * (0.5 + r.nextDouble() * 0.7);
                    b.children.add(c);
                }
                v.children.add(b);
            }
            p.veins.add(v);
        }

        // Wedges opening on the impact's rim, one possible per rim edge.
        p.shardBirth = new double[spokes];
        p.shardOut = new double[spokes];
        p.shardSkew = new double[spokes];
        for (int i = 0; i < spokes; i++) {
            p.shardBirth[i] = r.nextDouble() < 0.55 ? 0.28 + r.nextDouble() * 0.55 : 2;   // 2 never arrives
            p.shardOut[i] = 0.35 + r.nextDouble() * 0.95;
            p.shardSkew[i] = (r.nextDouble() - 0.5) * 0.7;
        }

        // Free-standing slivers further out, between the fractures.
        int splinters = 5 + r.nextInt(6);
        p.splinterAng = new double[splinters];
        p.splinterOut = new double[splinters];
        p.splinterLen = new double[splinters];
        p.splinterWidth = new double[splinters];
        p.splinterBirth = new double[splinters];
        p.splinterTurn = new double[splinters];
        for (int i = 0; i < splinters; i++) {
            p.splinterAng[i] = r.nextDouble() * TAU;
            p.splinterOut[i] = 0.35 + r.nextDouble() * 0.55;
            p.splinterLen[i] = 0.18 + r.nextDouble() * 0.55;
            p.splinterWidth[i] = 0.03 + r.nextDouble() * 0.09;
            p.splinterBirth[i] = 0.35 + r.nextDouble() * 0.5;
            p.splinterTurn[i] = (r.nextDouble() - 0.5) * 1.6;
        }
        return p;
    }

    private static Vein vein(Random r, double birth, double length, double width) {
        Vein v = new Vein();
        v.birth = birth;
        v.length = length;
        v.width = width;
        int segments = 3 + r.nextInt(4);
        v.turn = new double[segments];
        v.share = new double[segments];
        double sum = 0;
        for (int i = 0; i < segments; i++) {
            // A quarter of the joints are a violent change of direction rather than a drift.
            v.turn[i] = (r.nextDouble() - 0.5) * (r.nextDouble() < 0.25 ? 1.25 : 0.42);
            v.share[i] = 0.4 + r.nextDouble();
            sum += v.share[i];
        }
        for (int i = 0; i < segments; i++) v.share[i] /= sum;
        return v;
    }

    // ------------------------------------------------------------------ the break

    /**
     * Builds the break.
     *
     * @param seed     the portal's own seed
     * @param reach    blocks the longest fracture may run to at this charge
     * @param progress nought at the first tick of the charge, one when the portal is open
     */
    public static List<Piece> build(long seed, double reach, double progress) {
        List<Piece> pieces = new ArrayList<>();
        if (!(reach > 0)) return pieces;
        double t = progress < 0 ? 0 : Math.min(1, progress);
        Plan p = plan(seed);
        int spokes = p.ang.length;
        // The impact opens with the charge, and is the only part that grows in every direction.
        double core = reach * (0.18 + 0.22 * t);
        double[] vx = new double[spokes], vz = new double[spokes];
        for (int i = 0; i < spokes; i++) {
            vx[i] = Math.cos(p.ang[i]) * p.rad[i] * core;
            vz[i] = Math.sin(p.ang[i]) * p.rad[i] * core;
        }
        for (int i = 0; i < spokes; i++) {
            int j = (i + 1) % spokes;
            pieces.add(new Piece(new double[]{0, vx[i], vx[j], 0}, new double[]{0, vz[i], vz[j], 0},
                CORE, EDGE_1, true));
        }

        // Wedges on the rim: the impact eating outward between two fractures. Each is cut in two
        // along its length, because the open part of a wedge is a way through and the point it
        // narrows to is not — a trigger that reached the tip would be a trigger on a hairline.
        double gate = gate(reach);
        for (int i = 0; i < spokes; i++) {
            if (t <= p.shardBirth[i]) continue;
            int j = (i + 1) % spokes;
            double grow = grown(t, p.shardBirth[i]);
            double mx = (vx[i] + vx[j]) * 0.5, mz = (vz[i] + vz[j]) * 0.5;
            double dir = Math.atan2(mz, mx) + p.shardSkew[i];
            double out = core * p.shardOut[i] * grow;
            double tx = mx + Math.cos(dir) * out, tz = mz + Math.sin(dir) * out;
            double cut = 0.55;
            double ax = vx[i] + (tx - vx[i]) * cut, az = vz[i] + (tz - vz[i]) * cut;
            double bx = vx[j] + (tx - vx[j]) * cut, bz = vz[j] + (tz - vz[j]) * cut;
            pieces.add(new Piece(new double[]{vx[i], ax, bx, vx[j]}, new double[]{vz[i], az, bz, vz[j]},
                SHARD, EDGE_0 | EDGE_2, Math.hypot(ax - bx, az - bz) >= gate));
            pieces.add(new Piece(new double[]{ax, tx, tx, bx}, new double[]{az, tz, tz, bz},
                SHARD, EDGE_0 | EDGE_2, false));
        }

        // The fractures themselves.
        for (Vein v : p.veins) {
            double dir = p.ang[v.anchor] + v.aim;
            run(v, vx[v.anchor], vz[v.anchor], dir, reach, t, pieces);
        }

        // Slivers between them: loose glass, pointed at both ends, and never a way through.
        for (int i = 0; i < p.splinterAng.length; i++) {
            if (t <= p.splinterBirth[i]) continue;
            double grow = grown(t, p.splinterBirth[i]);
            double ax = Math.cos(p.splinterAng[i]) * reach * p.splinterOut[i] * (0.55 + 0.45 * t);
            double az = Math.sin(p.splinterAng[i]) * reach * p.splinterOut[i] * (0.55 + 0.45 * t);
            double dir = p.splinterAng[i] + p.splinterTurn[i];
            double len = reach * p.splinterLen[i] * grow, width = reach * p.splinterWidth[i];
            double px = -Math.sin(dir) * width * 0.5, pz = Math.cos(dir) * width * 0.5;
            double tx = ax + Math.cos(dir) * len, tz = az + Math.sin(dir) * len;
            pieces.add(new Piece(new double[]{ax + px, tx, tx, ax - px}, new double[]{az + pz, tz, tz, az - pz},
                SHARD, EDGE_0 | EDGE_1 | EDGE_2 | EDGE_3, false));
        }
        return pieces;
    }

    /** Emits one vein and its branches, walking outward from where it leaves. */
    private static void run(Vein v, double startX, double startZ, double heading, double reach, double t, List<Piece> out) {
        if (t <= v.birth) return;
        double full = v.length * reach;
        double total = full * grown(t, v.birth);
        if (total <= 1.0E-4) return;
        double width = v.width * reach;
        double x = startX, z = startZ, dir = heading, travelled = 0;
        for (int s = 0; s < v.share.length && travelled < total - 1.0E-6; s++) {
            dir += v.turn[s];
            double segment = Math.min(full * v.share[s], total - travelled);
            double nx = x + Math.cos(dir) * segment, nz = z + Math.sin(dir) * segment;
            // Linear taper against the length reached so far, so the far end is a point at every
            // stage of growth rather than a cap that sharpens only once the crack is finished.
            double w0 = width * (1 - travelled / total), w1 = width * (1 - (travelled + segment) / total);
            double px = -Math.sin(dir), pz = Math.cos(dir);
            out.add(new Piece(
                new double[]{x + px * w0 * 0.5, nx + px * w1 * 0.5, nx - px * w1 * 0.5, x - px * w0 * 0.5},
                new double[]{z + pz * w0 * 0.5, nz + pz * w1 * 0.5, nz - pz * w1 * 0.5, z - pz * w0 * 0.5},
                CRACK, EDGE_0 | EDGE_2, Math.min(w0, w1) >= gate(reach)));
            // Branches split from a fixed place on the parent, so they stay where they started.
            for (Vein child : v.children) {
                double along = child.at * full;
                if (along < travelled || along > travelled + segment) continue;
                double f = segment <= 1.0E-9 ? 0 : (along - travelled) / segment;
                run(child, x + (nx - x) * f, z + (nz - z) * f, dir + child.aim, reach, t, out);
            }
            x = nx; z = nz; travelled += segment;
        }
    }

    /** Smooth arrival: a feature born at {@code birth} takes a little of the charge to reach itself. */
    private static double grown(double t, double birth) {
        double window = Math.max(0.12, 0.55 - birth * 0.4);
        return Math.min(1, (t - birth) / window);
    }

    // ------------------------------------------------------------------ what the portal is

    /** Whether this point of the floor is inside a part of the break wide enough to fall through. */
    public static boolean inside(List<Piece> pieces, double x, double z) {
        for (Piece piece : pieces) if (piece.solid && piece.contains(x, z)) return true;
        return false;
    }

    public static boolean inside(long seed, double x, double z, double reach, double progress) {
        return inside(build(seed, reach, progress), x, z);
    }

    /** How far the break runs from its centre, for the volume the server has to look in. */
    public static double extent(List<Piece> pieces) {
        double far = 0;
        for (Piece piece : pieces) for (int i = 0; i < 4; i++) far = Math.max(far, Math.max(Math.abs(piece.x[i]), Math.abs(piece.z[i])));
        return far;
    }
}
