package com.hexgodofstories.warping;

import com.hexgodofstories.warping.leviathan.LeviathanSegmentController;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PilgrimMotionTest {
    public static void main(String[] args) {
        LeviathanSegmentController path = new LeviathanSegmentController();
        path.reset(Vec3.ZERO, 0, 0);
        // Slow motion must accumulate distance without eating the historical curve.
        for (int t = 1; t <= 2000; t++) {
            double a = t * 0.0025;
            path.push(new Vec3(60 * (1 - Math.cos(a)), 8 * Math.sin(a * 0.5), 60 * Math.sin(a)), 0, 0);
            path.rebuild();
        }
        for (int i = 1; i < LeviathanSegmentController.SEGMENTS; i++) {
            double spacing = path.segment(i).distanceTo(path.segment(i - 1));
            check(spacing > 5.9 && spacing <= 6.001, "curve keeps joint spacing: " + spacing);
        }
        LeviathanSegmentController lateViewer = new LeviathanSegmentController();
        lateViewer.acceptSnapshot(path.snapshot(), path.segment(0));
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i++)
            check(path.segment(i).distanceTo(lateViewer.segment(i)) < 0.002, "late viewer reconstructs joint " + i);
        path.push(new Vec3(1000, 100, 1000), 90, 0);
        path.rebuild();
        check(path.segment(21).distanceTo(path.segment(0)) < 127, "teleport does not drag old body across world");
        // Verify composed banking survives GeckoLib's Z-Y-X Euler convention, including vertical dives.
        for (float yaw : new float[]{-179, -90, 0, 73, 179}) for (float pitch : new float[]{-88,-40,0,40,88}) {
            float y = (float)Math.toRadians(180-yaw), p = (float)Math.toRadians(-pitch);
            Quaternionf expected = new Quaternionf().rotateY(y).rotateX(p).rotateZ(0.6f);
            float[] euler = com.hexgodofstories.warping.leviathan.LeviathanPoseMath.angles(yaw, pitch, (float)Math.toDegrees(0.6));
            Quaternionf rendered = new Quaternionf().rotateZ(euler[2]).rotateY(euler[1]).rotateX(euler[0]);
            Vector3f a = expected.transform(new Vector3f(0,0,-1));
            Vector3f b = rendered.transform(new Vector3f(0,0,-1));
            check(a.distance(b) < 0.0001, "render heading survives bank at " + yaw + ", " + pitch);
        }
        noKnots();
        System.out.println("PilgrimMotionTest: slow curves, late tracking, teleport reset, 3D rotations and joint limits passed.");
    }

    /**
     * The failure this creature is most capable of: following its own recorded path into a curve
     * far tighter than its joint spacing, and folding a hundred and twenty six blocks of body into
     * a knot a metre across. Both inputs below did exactly that before the joint limits existed —
     * a tight orbit collapsed the body to a ten block span with joints 1.7 blocks apart, and a slow
     * pivot to a half block span with joints 0.1 apart.
     */
    private static void noKnots() {
        orbit("tight orbit", 4.5, 0.14, 900);
        orbit("vortex ring", 22, 0.17, 900);
        orbit("stalk orbit", 26, 0.03, 4000);
        pivot("slow pivot", 0.25, 900);
        pivot("cruising pivot", 1.0, 900);
    }

    private static void orbit(String label, double radius, double step, int ticks) {
        LeviathanSegmentController body = new LeviathanSegmentController();
        body.reset(new Vec3(radius, 0, 0), 0, 0);
        double angle = 0;
        for (int t = 0; t < ticks; t++) {
            angle += step;
            body.push(new Vec3(Math.cos(angle) * radius, Math.sin(angle * 0.31) * 3, Math.sin(angle) * radius), 0, 0);
            body.rebuild();
        }
        intact(label, body);
    }

    /** Barely moving while the heading spins, which is what an unbounded turn rate produces. */
    private static void pivot(String label, double speed, int ticks) {
        LeviathanSegmentController body = new LeviathanSegmentController();
        body.reset(Vec3.ZERO, 0, 0);
        Vec3 at = Vec3.ZERO;
        double angle = 0;
        for (int t = 0; t < ticks; t++) {
            angle += 0.16;
            at = at.add(Math.cos(angle) * speed, 0, Math.sin(angle) * speed);
            body.push(at, 0, 0);
            body.rebuild();
        }
        intact(label, body);
    }

    private static void intact(String label, LeviathanSegmentController body) {
        int n = LeviathanSegmentController.SEGMENTS;
        for (int i = 1; i < n; i++) {
            double spacing = body.segment(i).distanceTo(body.segment(i - 1));
            check(Math.abs(spacing - LeviathanSegmentController.SPACING) < 1.0E-6,
                label + ": joint " + i + " holds the model's pivot spacing, got " + spacing);
        }
        for (int i = 2; i < n; i++) {
            Vec3 a = body.segment(i - 2).subtract(body.segment(i - 1));
            Vec3 b = body.segment(i - 1).subtract(body.segment(i));
            if (a.lengthSqr() < 1.0E-9 || b.lengthSqr() < 1.0E-9) continue;
            double bend = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.normalize().dot(b.normalize())))));
            check(bend < 22, label + ": joint " + i + " bends " + Math.round(bend) + " degrees");
        }
        // Joints three apart are eighteen blocks of spine apart. Anything closer is the body
        // passing through itself, which is the whole visual failure this guards against.
        for (int i = 0; i < n; i++) for (int j = i + 3; j < n; j++) {
            double apart = body.segment(i).distanceTo(body.segment(j));
            check(apart > 12, label + ": joints " + i + " and " + j + " are " + Math.round(apart) + " blocks apart");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
