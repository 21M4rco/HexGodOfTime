package com.hexgodofstories.warping.leviathan;

/** Converts heading/pitch/local bank into the renderer's extrinsic Z-Y-X rotation order. */
public final class LeviathanPoseMath {
    private LeviathanPoseMath() { }

    public static float[] angles(float yawDegrees, float pitchDegrees, float bankDegrees) {
        double y = Math.toRadians(180.0 - yawDegrees);
        double p = Math.toRadians(-pitchDegrees), r = Math.toRadians(bankDegrees);
        double cy = Math.cos(y), sy = Math.sin(y), cp = Math.cos(p), sp = Math.sin(p);
        double cr = Math.cos(r), sr = Math.sin(r);
        // Matrix Ry(heading) Rx(pitch) Rz(local bank), decomposed as Rz Ry Rx.
        double m00 = cy * cr + sy * sp * sr, m10 = cp * sr;
        double m20 = -sy * cr + cy * sp * sr;
        double m21 = sy * sr + cy * sp * cr, m22 = cy * cp;
        double horizontal = Math.hypot(m00, m10);
        double x = horizontal < 1.0E-7 ? Math.atan2(sp, cp * cr) : Math.atan2(m21, m22);
        double ey = Math.atan2(-m20, horizontal);
        double z = horizontal < 1.0E-7 ? 0 : Math.atan2(m10, m00);
        return new float[] {(float)x, (float)ey, (float)z};
    }
}
