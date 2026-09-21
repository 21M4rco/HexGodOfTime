package com.hexgodofstories.warping.leviathan;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Reconstructs an articulated body from the movement history of a single leading point.
 *
 * <p>Nodes are appended by distance rather than by tick, so the body keeps constant spacing whether
 * the creature is drifting at a tenth of a block per tick or breaching at two. Segment positions are
 * resolved by one linear sweep back along the recorded path, which means S-curves, spirals, coils
 * and vertical loops all fall out of the head's own motion without any extra state.
 *
 * <p>The class is deliberately free of world and entity references: the server runs one instance to
 * drive hitboxes and damage, and every client runs its own against the interpolated render position.
 * That is why no body path has to be networked.
 */
public final class LeviathanSegmentController {
    /** Head, three neck joints, twelve body joints, six tail joints. */
    public static final int SEGMENTS = 22;
    public static final int NECK_START = 1, BODY_START = 4, TAIL_START = 16;
    /**
     * Blocks between consecutive joint centres. This MUST equal the pivot step in
     * assets/hexgodofstories/geo/abyssal_pilgrim.geo.json (96 model units), or the multipart
     * hitboxes drift away from the body the player can see. VoidSeaShapeTest enforces it.
     */
    public static final double SPACING = 6.0;
    private static final double NODE_STEP = 0.6;
    private static final int MAX_NODES = 460;
    /** Anything further than this in one tick is a teleport, not motion. */
    private static final double TELEPORT = 32.0;

    /** Half widths in blocks, used for hitboxes and for the render scale of each joint. */
    private static final double[] PROFILE = {
        5.0, 4.3, 4.7, 5.1, 5.4, 5.6, 5.6, 5.4, 5.1, 4.8, 4.4,
        4.0, 3.6, 3.2, 2.8, 2.4, 1.9, 1.5, 1.15, 0.85, 0.6, 0.4
    };

    private final double[] nx = new double[MAX_NODES], ny = new double[MAX_NODES], nz = new double[MAX_NODES];
    private int count, head;

    private final Vec3[] seg = new Vec3[SEGMENTS];
    private final float[] yaw = new float[SEGMENTS], pitch = new float[SEGMENTS], roll = new float[SEGMENTS];
    private final float[] prevYaw = new float[SEGMENTS], prevPitch = new float[SEGMENTS], prevRoll = new float[SEGMENTS];
    private final float[] rollVel = new float[SEGMENTS];
    private Vec3 backward = new Vec3(0, 0, -1);
    private boolean primed;
    private Vec3 leading = Vec3.ZERO;

    public LeviathanSegmentController() {
        for (int i = 0; i < SEGMENTS; i++) seg[i] = Vec3.ZERO;
    }

    public static double radius(int index) { return PROFILE[Mth.clamp(index, 0, SEGMENTS - 1)]; }

    /** Total nose to tail length in blocks. */
    public static double length() { return SPACING * (SEGMENTS - 1) + PROFILE[0] * 2; }

    public boolean primed() { return primed; }
    public Vec3 segment(int i) { return seg[Mth.clamp(i, 0, SEGMENTS - 1)]; }
    public float yaw(int i) { return yaw[Mth.clamp(i, 0, SEGMENTS - 1)]; }
    public float pitch(int i) { return pitch[Mth.clamp(i, 0, SEGMENTS - 1)]; }
    public float roll(int i) { return roll[Mth.clamp(i, 0, SEGMENTS - 1)]; }

    /** Interpolated orientation for rendering between two client ticks. */
    public float yaw(int i, float partial) { return lerpAngle(prevYaw[clamp(i)], yaw[clamp(i)], partial); }
    public float pitch(int i, float partial) { return lerpAngle(prevPitch[clamp(i)], pitch[clamp(i)], partial); }
    public float roll(int i, float partial) { return lerpAngle(prevRoll[clamp(i)], roll[clamp(i)], partial); }

    private static int clamp(int i) { return Mth.clamp(i, 0, SEGMENTS - 1); }
    private static float lerpAngle(float a, float b, float t) { return a + Mth.wrapDegrees(b - a) * t; }

    /** Lays the whole body out straight behind a point. Used on spawn, load and teleport. */
    public void reset(Vec3 position, float yawDegrees, float pitchDegrees) {
        double yr = yawDegrees * Mth.DEG_TO_RAD, pr = pitchDegrees * Mth.DEG_TO_RAD;
        Vec3 look = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        if (look.lengthSqr() < 1.0E-6) look = new Vec3(0, 0, 1);
        backward = look.normalize().reverse();
        leading = position;
        count = 0; head = 0;
        for (int k = 0; k < MAX_NODES; k++) {
            Vec3 p = position.add(backward.scale(k * NODE_STEP));
            int idx = Math.floorMod(-k, MAX_NODES);
            nx[idx] = p.x; ny[idx] = p.y; nz[idx] = p.z;
        }
        count = MAX_NODES;
        for (int i = 0; i < SEGMENTS; i++) {
            seg[i] = position.add(backward.scale(i * SPACING));
            yaw[i] = prevYaw[i] = yawDegrees; pitch[i] = prevPitch[i] = pitchDegrees;
            roll[i] = prevRoll[i] = 0; rollVel[i] = 0;
        }
        primed = true;
    }

    /**
     * Feeds one new leading position. Returns false and re-lays the body when the point jumped,
     * so a dimension change never drags a 150 block tail across the world.
     */
    public boolean push(Vec3 position, float yawDegrees, float pitchDegrees) {
        if (!primed) { reset(position, yawDegrees, pitchDegrees); return false; }
        leading = position;
        Vec3 newest = node(0);
        double moved = newest.distanceTo(position);
        if (moved > TELEPORT) { reset(position, yawDegrees, pitchDegrees); return false; }
        if (moved >= NODE_STEP) {
            // Insert intermediate nodes so a fast tick does not leave a sparse, angular path.
            int steps = Math.min(16, (int) (moved / NODE_STEP));
            for (int s = 1; s <= steps; s++) {
                Vec3 p = newest.add(position.subtract(newest).scale(s / (double) steps));
                head = Math.floorMod(head + 1, MAX_NODES);
                nx[head] = p.x; ny[head] = p.y; nz[head] = p.z;
                count = Math.min(MAX_NODES, count + 1);
            }
            Vec3 dir = position.subtract(newest);
            if (dir.lengthSqr() > 1.0E-8) backward = dir.normalize().reverse();
        }
        return true;
    }

    private Vec3 node(int back) {
        int i = Math.floorMod(head - back, MAX_NODES);
        return new Vec3(nx[i], ny[i], nz[i]);
    }

    /**
     * Resolves every joint position, then derives yaw, pitch and a spring damped bank angle.
     * One sweep of the path serves all joints because their distances increase monotonically.
     */
    public void rebuild() {
        for (int i = 0; i < SEGMENTS; i++) { prevYaw[i] = yaw[i]; prevPitch[i] = pitch[i]; prevRoll[i] = roll[i]; }
        // Keep the unsampled leading point separate. Overwriting node(0) during slow
        // movement loses every turn until a single tick exceeds NODE_STEP.
        Vec3 cursor = leading;
        seg[0] = cursor;
        int placed = 1;
        double travelled = 0, wanted = SPACING;
        for (int k = 0; k < count && placed < SEGMENTS; k++) {
            Vec3 next = node(k);
            double step = cursor.distanceTo(next);
            if (step > 1.0E-7) {
                while (placed < SEGMENTS && travelled + step >= wanted) {
                    double f = (wanted - travelled) / step;
                    seg[placed++] = cursor.add(next.subtract(cursor).scale(f));
                    wanted += SPACING;
                }
                travelled += step;
            }
            cursor = next;
        }
        while (placed < SEGMENTS) { seg[placed] = seg[placed - 1].add(backward.scale(SPACING)); placed++; }

        for (int i = 0; i < SEGMENTS; i++) {
            Vec3 forward = i == 0 ? seg[0].subtract(seg[1]) : seg[i - 1].subtract(seg[i]);
            if (forward.lengthSqr() < 1.0E-8) { yaw[i] = prevYaw[i]; pitch[i] = prevPitch[i]; }
            else {
                Vec3 d = forward.normalize();
                yaw[i] = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
                pitch[i] = (float) (-Math.asin(Mth.clamp(d.y, -1, 1)) * Mth.RAD_TO_DEG);
            }
            // Bank into turns: the difference against the joint in front drives a damped spring.
            float lead = i == 0 ? yaw[0] : yaw[i - 1];
            float turn = Mth.wrapDegrees(lead - yaw[i]);
            float goal = Mth.clamp(turn * 1.35f, -55f, 55f);
            rollVel[i] = rollVel[i] * 0.78f + (goal - roll[i]) * 0.09f;
            roll[i] = Mth.clamp(roll[i] + rollVel[i], -70f, 70f);
        }
    }

    /** Collision volume for one joint, in world space. */
    public AABB box(int i) {
        double r = radius(i);
        Vec3 c = segment(i);
        return new AABB(c.x - r, c.y - r * 0.85, c.z - r, c.x + r, c.y + r * 0.85, c.z + r);
    }

    /** Bounding volume of the entire body, used for culling and for broad phase queries. */
    public AABB bounds() {
        double x0 = seg[0].x, y0 = seg[0].y, z0 = seg[0].z, x1 = x0, y1 = y0, z1 = z0;
        for (int i = 1; i < SEGMENTS; i++) {
            Vec3 s = seg[i];
            x0 = Math.min(x0, s.x); y0 = Math.min(y0, s.y); z0 = Math.min(z0, s.z);
            x1 = Math.max(x1, s.x); y1 = Math.max(y1, s.y); z1 = Math.max(z1, s.z);
        }
        return new AABB(x0, y0, z0, x1, y1, z1).inflate(PROFILE[0] + 1.0);
    }

    /** Nearest joint index to a world position, for routing contact to the right body section. */
    public int nearest(Vec3 point) {
        int best = 0; double d = Double.MAX_VALUE;
        for (int i = 0; i < SEGMENTS; i++) {
            double q = seg[i].distanceToSqr(point);
            if (q < d) { d = q; best = i; }
        }
        return best;
    }
}
