package com.hexgodofstories.data;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a Scepter beam's line meets a body drawn from arbitrary faces, for bodies that are not built
 * from vanilla model parts. Faces are packed twelve floats apiece (three or four corners); nothing here
 * needs Minecraft.
 */
public final class WoundGeometry {
    private WoundGeometry() { }

    /** A point where the line crosses a face: how far along the line, and which face. */
    public record Crossing(float t, int face) { }

    /** Every crossing of the line {@code o + t d} with the faces within {@code reach} of o, nearest first. */
    public static List<Crossing> crossings(float[] faces, byte[] corners, int count, Vector3f o, Vector3f d, float reach) {
        List<Crossing> out = new ArrayList<>();
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
        for (int f = 0; f < count; f++) {
            corner(faces, f, 0, a);
            corner(faces, f, 1, b);
            corner(faces, f, 2, c);
            float t = triangle(o, d, a, b, c);
            if (Float.isNaN(t) && corners[f] == 4) t = triangle(o, d, a, c, corner(faces, f, 3, b));
            if (!Float.isNaN(t) && Math.abs(t) <= reach) out.add(new Crossing(t, f));
        }
        out.sort((x, y) -> Float.compare(x.t, y.t));
        return out;
    }

    /**
     * The shortest move of the line, across itself, that puts it through a corner of the body: for a beam
     * that grazed the body rather than running through it. Null when there are no faces.
     */
    public static Vector3f toward(float[] faces, byte[] corners, int count, Vector3f o, Vector3f d) {
        Vector3f best = null, p = new Vector3f();
        float nearest = Float.POSITIVE_INFINITY;
        for (int f = 0; f < count; f++) {
            for (int k = 0; k < corners[f]; k++) {
                corner(faces, f, k, p);
                Vector3f rel = new Vector3f(p).sub(o);
                Vector3f across = rel.sub(new Vector3f(d).mul(rel.dot(d)), new Vector3f());
                float distance = across.lengthSquared();
                if (distance < nearest) {nearest = distance; best = across;}
            }
        }
        return best;
    }

    /** The face's unit normal, by its first three corners; zero for a degenerate face. */
    public static Vector3f normal(float[] faces, int face) {
        Vector3f a = corner(faces, face, 0, new Vector3f()), b = corner(faces, face, 1, new Vector3f()), c = corner(faces, face, 2, new Vector3f());
        Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        return n.lengthSquared() < 1e-12f ? n.zero() : n.normalize();
    }

    /** Radius of the largest circle about {@code p}, a point on the face, that stays inside the face. */
    public static float room(float[] faces, byte[] corners, int face, Vector3f p) {
        int n = corners[face];
        float room = Float.POSITIVE_INFINITY;
        Vector3f a = new Vector3f(), b = new Vector3f();
        for (int k = 0; k < n; k++) {
            corner(faces, face, k, a);
            corner(faces, face, (k + 1) % n, b);
            room = Math.min(room, segmentDistance(p, a, b));
        }
        return room;
    }

    /** Slides {@code p} along {@code axis} until it lies on the plane through {@code on} with normal {@code normal}. */
    public static Vector3f onto(Vector3f p, Vector3f axis, Vector3f on, Vector3f normal) {
        float along = axis.dot(normal);
        if (Math.abs(along) < 1e-4f) return new Vector3f(p);
        float t = new Vector3f(on).sub(p).dot(normal) / along;
        return new Vector3f(axis).mul(t).add(p);
    }

    private static Vector3f corner(float[] faces, int face, int k, Vector3f out) {
        int o = face * 12 + k * 3;
        return out.set(faces[o], faces[o + 1], faces[o + 2]);
    }

    /** Möller–Trumbore: the line parameter where {@code o + t d} meets triangle abc, NaN if it misses. */
    static float triangle(Vector3f o, Vector3f d, Vector3f a, Vector3f b, Vector3f c) {
        Vector3f e1 = new Vector3f(b).sub(a), e2 = new Vector3f(c).sub(a);
        Vector3f h = new Vector3f(d).cross(e2);
        float det = e1.dot(h);
        if (Math.abs(det) < 1e-9f) return Float.NaN;
        float inv = 1 / det;
        Vector3f s = new Vector3f(o).sub(a);
        float u = inv * s.dot(h);
        if (u < 0 || u > 1) return Float.NaN;
        Vector3f q = s.cross(e1);
        float v = inv * d.dot(q);
        if (v < 0 || u + v > 1) return Float.NaN;
        return inv * e2.dot(q);
    }

    private static float segmentDistance(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f ab = new Vector3f(b).sub(a);
        float length = ab.lengthSquared();
        float t = length < 1e-12f ? 0 : Math.max(0, Math.min(1, new Vector3f(p).sub(a).dot(ab) / length));
        return new Vector3f(ab).mul(t).add(a).distance(p);
    }
}
