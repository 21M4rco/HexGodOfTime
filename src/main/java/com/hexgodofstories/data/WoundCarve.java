package com.hexgodofstories.data;

import java.util.Arrays;

/**
 * The hole a Scepter beam cuts in a body, as geometry: the beam's cylinder, taken as the prism of
 * {@link #SIDES} flat sides inscribed in it, carved out of whatever the body is made of.
 *
 * <p>A hole is never drawn as a disc laid on a face. Every piece of it is the cylinder cut by the body:
 * the opening in a face is that face's own part inside the cylinder, the tunnel wall is the cylinder's
 * side where it runs inside the body, and the rim is laid on a face only around its opening. So a beam
 * that meets a limb at an edge or a corner takes a bite out of it — the corner is carved away — and no
 * part of the hole ever hangs in the air beside the body.
 *
 * <p>Points are packed as x, y, z triples in whatever space the caller works in. A convex body is a list
 * of planes, packed as nx, ny, nz, d: its inside is where {@code nx x + ny y + nz z <= d} for every one.
 * Nothing here needs Minecraft.
 */
public final class WoundCarve {
    private WoundCarve() { }

    /** Flat sides the round hole is cut with. */
    public static final int SIDES = 24;
    /** How far the rim reaches out from the opening, as a multiple of the hole's radius. */
    public static final float RIM = 1.75f;
    /** Room for any polygon clipped by the cylinder: four corners, one more per side at most, and slack. */
    private static final int ROOM = (4 + SIDES + 8) * 3;
    /** Two polygons' worth of room to clip back and forth in, so only what is kept is ever allocated. */
    private static final ThreadLocal<float[][]> SCRATCH = ThreadLocal.withInitial(() -> new float[][]{new float[ROOM * 2], new float[ROOM * 2]});

    /** The beam's cylinder: the line through {@code c} along {@code a}, radius {@code r}. */
    public static final class Cylinder {
        public final float cx, cy, cz, ax, ay, az, r;
        /** How far each flat side lies from the axis: the prism is inscribed, its corners on the circle. */
        public final float apothem;
        /** The corners' directions out from the axis, and each side's outward normal, SIDES apiece. */
        private final float[] corner = new float[SIDES * 3], side = new float[SIDES * 3];
        /** The half-space inside each side, as a plane; outside it; inside it pushed out to the rim's edge. */
        private final float[][] sides = new float[SIDES][], beyond = new float[SIDES][], rimEdge = new float[SIDES][];
        /** The half-space on the later side of each corner's direction, going round from u toward v, and on the earlier side. */
        private final float[][] after = new float[SIDES][], before = new float[SIDES][];

        public Cylinder(float cx, float cy, float cz, float ax, float ay, float az, float r) {
            float length = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (!(length > 1e-12f)) {ax = 0; ay = 0; az = 1; length = 1;}
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.ax = ax / length; this.ay = ay / length; this.az = az / length;
            this.r = r;
            this.apothem = r * (float) Math.cos(Math.PI / SIDES);
            // Any two directions square to the axis and to each other; u then v turn the same way round it.
            float rx = Math.abs(this.ay) < .9f ? 0 : 1, ry = Math.abs(this.ay) < .9f ? 1 : 0;
            float ux = this.ay * 0 - this.az * ry, uy = this.az * rx - this.ax * 0, uz = this.ax * ry - this.ay * rx;
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= ul; uy /= ul; uz /= ul;
            float vx = this.ay * uz - this.az * uy, vy = this.az * ux - this.ax * uz, vz = this.ax * uy - this.ay * ux;
            for (int k = 0; k < SIDES; k++) {
                double at = 2 * Math.PI * k / SIDES, mid = at + Math.PI / SIDES;
                float c = (float) Math.cos(at), s = (float) Math.sin(at), cm = (float) Math.cos(mid), sm = (float) Math.sin(mid);
                corner[k * 3] = ux * c + vx * s; corner[k * 3 + 1] = uy * c + vy * s; corner[k * 3 + 2] = uz * c + vz * s;
                side[k * 3] = ux * cm + vx * sm; side[k * 3 + 1] = uy * cm + vy * sm; side[k * 3 + 2] = uz * cm + vz * sm;
            }
            for (int k = 0; k < SIDES; k++) {
                sides[k] = inside(k, 1);
                beyond[k] = flip(sides[k]);
                rimEdge[k] = inside(k, RIM);
                after[k] = turning(k);
                before[k] = flip(after[k]);
            }
        }

        /** How far along the axis a point lies, from the line's own point. */
        public float along(float x, float y, float z) {return (x - cx) * ax + (y - cy) * ay + (z - cz) * az;}

        /** The same cylinder about a line moved across by (dx, dy, dz). */
        public Cylinder moved(float dx, float dy, float dz) {return new Cylinder(cx + dx, cy + dy, cz + dz, ax, ay, az, r);}

        /**
         * How wide an axis-aligned box {minX, minY, minZ, maxX, maxY, maxZ} is across the cylinder at its
         * narrowest, taken along each corner's direction.
         */
        public float width(float[] box) {
            float narrowest = Float.POSITIVE_INFINITY;
            for (int k = 0; k < SIDES / 2; k++)
                narrowest = Math.min(narrowest, Math.abs(corner[k * 3]) * (box[3] - box[0]) + Math.abs(corner[k * 3 + 1]) * (box[4] - box[1])
                    + Math.abs(corner[k * 3 + 2]) * (box[5] - box[2]));
            return narrowest;
        }

        /** The same line with another radius. */
        public Cylinder sized(float r) {return new Cylinder(cx, cy, cz, ax, ay, az, r);}

        /** How far a point lies from the axis. */
        public float distance(float x, float y, float z) {
            float px = x - cx, py = y - cy, pz = z - cz, t = px * ax + py * ay + pz * az;
            px -= t * ax; py -= t * ay; pz -= t * az;
            return (float) Math.sqrt(px * px + py * py + pz * pz);
        }

        /**
         * The point {@code s} of the way along side {@code k}, from corner k to corner k + 1, then {@code t}
         * along the axis: a line down the tunnel wall.
         */
        public void onSide(int k, float s, float t, float[] out, int o) {
            int a = k % SIDES * 3, b = (k + 1) % SIDES * 3;
            out[o] = cx + r * (corner[a] + (corner[b] - corner[a]) * s) + t * ax;
            out[o + 1] = cy + r * (corner[a + 1] + (corner[b + 1] - corner[a + 1]) * s) + t * ay;
            out[o + 2] = cz + r * (corner[a + 2] + (corner[b + 2] - corner[a + 2]) * s) + t * az;
        }

        /** The half-space inside side k, as a plane. */
        private float[] inside(int k, float scale) {
            float nx = side[k * 3], ny = side[k * 3 + 1], nz = side[k * 3 + 2];
            return new float[]{nx, ny, nz, apothem * scale + nx * cx + ny * cy + nz * cz};
        }

        private float[] turning(int k) {
            int o = k % SIDES * 3;
            // The turning direction at the corner: the axis crossed with the corner's direction.
            float mx = ay * corner[o + 2] - az * corner[o + 1], my = az * corner[o] - ax * corner[o + 2], mz = ax * corner[o + 1] - ay * corner[o];
            return new float[]{-mx, -my, -mz, -(mx * cx + my * cy + mz * cz)};
        }
    }

    /**
     * Keeps the part of the convex polygon {@code in} ({@code n} points) where
     * {@code nx x + ny y + nz z <= d}, written to {@code out}. Returns how many points are left.
     */
    static int clip(float[] in, int n, float nx, float ny, float nz, float d, float[] out) {
        if (n == 0) return 0;
        int m = 0;
        // A convex polygon gains at most one point per plane; anything that would outgrow the room is
        // numerical wreckage, and is dropped rather than drawn.
        float px = in[n * 3 - 3], py = in[n * 3 - 2], pz = in[n * 3 - 1];
        float pd = nx * px + ny * py + nz * pz - d;
        for (int i = 0; i < n; i++) {
            if ((m + 2) * 3 > out.length) return 0;
            float x = in[i * 3], y = in[i * 3 + 1], z = in[i * 3 + 2];
            float cd = nx * x + ny * y + nz * z - d;
            if (cd <= 0) {
                // Coming back inside: where the edge crosses, then the point itself.
                if (pd > 0 && cd < 0) m = cross(out, m, px, py, pz, x, y, z, pd / (pd - cd));
                out[m * 3] = x; out[m * 3 + 1] = y; out[m * 3 + 2] = z; m++;
            } else if (pd < 0) m = cross(out, m, px, py, pz, x, y, z, pd / (pd - cd));
            px = x; py = y; pz = z; pd = cd;
        }
        return m;
    }

    private static int cross(float[] out, int m, float px, float py, float pz, float x, float y, float z, float t) {
        out[m * 3] = px + (x - px) * t; out[m * 3 + 1] = py + (y - py) * t; out[m * 3 + 2] = pz + (z - pz) * t;
        return m + 1;
    }

    /** The convex polygon kept inside every plane given, or null once nothing is left. */
    private static float[] keep(float[] polygon, float[]... planes) {
        float[][] scratch = SCRATCH.get();
        int room = polygon.length + planes.length * 3 + 24;
        if (scratch[0].length < room) scratch[0] = scratch[1] = null;
        if (scratch[0] == null) {scratch[0] = new float[room * 2]; scratch[1] = new float[room * 2];}
        float[] a = scratch[0], b = scratch[1];
        System.arraycopy(polygon, 0, a, 0, polygon.length);
        int n = polygon.length / 3;
        for (float[] p : planes) {
            n = clip(a, n, p[0], p[1], p[2], p[3], b);
            if (n < 3) return null;
            float[] t = a; a = b; b = t;
        }
        // What only touches a plane along a line is no piece of anything.
        return area(a, n) < 1e-10f ? null : Arrays.copyOf(a, n * 3);
    }

    /** A flat polygon's area, by Newell's method. */
    private static float area(float[] p, int n) {
        float x = 0, y = 0, z = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            x += (p[a + 1] - p[b + 1]) * (p[a + 2] + p[b + 2]);
            y += (p[a + 2] - p[b + 2]) * (p[a] + p[b]);
            z += (p[a] - p[b]) * (p[a + 1] + p[b + 1]);
        }
        return (float) Math.sqrt(x * x + y * y + z * z) / 2;
    }

    /** The part of a convex face inside the cylinder: the opening the beam cuts in it. Null if it cuts none. */
    public static float[] opening(float[] face, Cylinder c) {return keep(face, c.sides);}

    /**
     * Piece {@code k} of the rim on a convex face: the part of the face between side k of the opening and
     * the same side {@link #RIM} times further out, within the two corners' directions. Adjacent pieces
     * meet exactly, and none reaches past the face or into the opening. Null where the face has none.
     */
    public static float[] rim(float[] face, Cylinder c, int k) {
        return keep(face, c.after[k], c.before[(k + 1) % SIDES], c.beyond[k], c.rimEdge[k]);
    }

    /** How far out through the rim a point on piece k lies: 0 at the opening's edge, 1 at the rim's outer edge. */
    public static float rimDepth(Cylinder c, int k, float x, float y, float z) {
        float[] p = c.sides[k];
        float out = p[0] * x + p[1] * y + p[2] * z - p[3];
        return Math.max(0, Math.min(1, out / (c.apothem * (RIM - 1))));
    }

    private static float[] flip(float[] plane) {return new float[]{-plane[0], -plane[1], -plane[2], -plane[3]};}

    /**
     * Side {@code k} of the tunnel inside a convex body: the flat side of the prism between corners k and
     * k + 1, where it runs inside every plane of the body. {@code reach} must be longer than the body is
     * from the cylinder's own point. Null where the side never enters the body.
     */
    public static float[] wall(Cylinder c, int k, float[] planes, float reach) {
        float[] strip = new float[12];
        c.onSide(k, 0, -reach, strip, 0);
        c.onSide(k, 1, -reach, strip, 3);
        c.onSide(k, 1, reach, strip, 6);
        c.onSide(k, 0, reach, strip, 9);
        return keep(strip, split(planes));
    }

    /** The part of a convex polygon between {@code from} and {@code to} along the cylinder's axis. */
    public static float[] band(float[] polygon, Cylinder c, float from, float to) {
        float mid = c.ax * c.cx + c.ay * c.cy + c.az * c.cz;
        return keep(polygon, new float[]{c.ax, c.ay, c.az, mid + to}, new float[]{-c.ax, -c.ay, -c.az, -(mid + from)});
    }

    /**
     * Where the line {@code p + s d} runs inside a convex body: {s entering, s leaving}, or null if it
     * misses the body.
     */
    public static float[] span(float[] planes, float px, float py, float pz, float dx, float dy, float dz) {
        float lo = Float.NEGATIVE_INFINITY, hi = Float.POSITIVE_INFINITY;
        for (int i = 0; i < planes.length; i += 4) {
            float toward = planes[i] * dx + planes[i + 1] * dy + planes[i + 2] * dz;
            float room = planes[i + 3] - (planes[i] * px + planes[i + 1] * py + planes[i + 2] * pz);
            if (Math.abs(toward) < 1e-9f) {
                if (room < 0) return null;
                continue;
            }
            float s = room / toward;
            if (toward > 0) hi = Math.min(hi, s);
            else lo = Math.max(lo, s);
        }
        return lo > hi ? null : new float[]{lo, hi};
    }

    /**
     * How deep into the tunnel a point on its wall lies, from 0 at either mouth to 1 halfway through: taken
     * along the line down the wall through that point.
     */
    public static float depth(float[] planes, Cylinder c, float x, float y, float z) {
        float[] s = span(planes, x, y, z, c.ax, c.ay, c.az);
        if (s == null || s[1] - s[0] < 1e-6f) return 0;
        float along = Math.max(0, Math.min(1, -s[0] / (s[1] - s[0])));
        return 1 - Math.abs(along * 2 - 1);
    }

    /** An axis-aligned box {minX, minY, minZ, maxX, maxY, maxZ} as the planes of its six faces. */
    public static float[] boxPlanes(float[] box) {
        return new float[]{
            1, 0, 0, box[3], -1, 0, 0, -box[0],
            0, 1, 0, box[4], 0, -1, 0, -box[1],
            0, 0, 1, box[5], 0, 0, -1, -box[2]};
    }

    /** Face {@code f} of the box, in the order of {@link #boxPlanes}, as a quad; its outward normal is that plane's. */
    public static float[] boxFace(float[] box, int f) {
        int axis = f / 2;
        float at = f % 2 == 0 ? box[axis + 3] : box[axis];
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        float[] quad = new float[12];
        float[][] corners = {{box[a], box[b]}, {box[a + 3], box[b]}, {box[a + 3], box[b + 3]}, {box[a], box[b + 3]}};
        for (int i = 0; i < 4; i++) {
            quad[i * 3 + axis] = at;
            quad[i * 3 + a] = corners[i][0];
            quad[i * 3 + b] = corners[i][1];
        }
        return quad;
    }

    /** The largest distance from the cylinder's own point to any corner of the box: how far the tunnel can run. */
    public static float reach(float[] box, Cylinder c) {
        float far = 0;
        for (int i = 0; i < 8; i++) {
            float x = box[(i & 1) == 0 ? 0 : 3] - c.cx, y = box[(i & 2) == 0 ? 1 : 4] - c.cy, z = box[(i & 4) == 0 ? 2 : 5] - c.cz;
            far = Math.max(far, x * x + y * y + z * z);
        }
        return (float) Math.sqrt(far) + 1;
    }

    /**
     * The point of the box nearest the line {@code p + t d} (d of unit length), and how far the line passes
     * from it: {x, y, z, distance}. Zero distance when the line runs through the box.
     */
    public static float[] nearest(float[] box, float px, float py, float pz, float dx, float dy, float dz) {
        // How far a point of the line is from the box only falls, then only rises, along the line; so the
        // nearest point is found by narrowing in on it, between where the box's corners lie along the line.
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            float t = (box[(i & 1) == 0 ? 0 : 3] - px) * dx + (box[(i & 2) == 0 ? 1 : 4] - py) * dy + (box[(i & 4) == 0 ? 2 : 5] - pz) * dz;
            lo = Math.min(lo, t); hi = Math.max(hi, t);
        }
        for (int i = 0; i < 80 && hi - lo > 1e-6f; i++) {
            float a = lo + (hi - lo) / 3, b = hi - (hi - lo) / 3;
            if (gap(box, px + a * dx, py + a * dy, pz + a * dz) <= gap(box, px + b * dx, py + b * dy, pz + b * dz)) hi = b;
            else lo = a;
        }
        float t = (lo + hi) / 2, x = px + t * dx, y = py + t * dy, z = pz + t * dz;
        float qx = clamp(x, box[0], box[3]), qy = clamp(y, box[1], box[4]), qz = clamp(z, box[2], box[5]);
        return new float[]{qx, qy, qz, (float) Math.sqrt(gap(box, x, y, z))};
    }

    private static float gap(float[] box, float x, float y, float z) {
        float gx = x - clamp(x, box[0], box[3]), gy = y - clamp(y, box[1], box[4]), gz = z - clamp(z, box[2], box[5]);
        return gx * gx + gy * gy + gz * gz;
    }

    private static float clamp(float v, float lo, float hi) {return v < lo ? lo : Math.min(v, hi);}

    private static float[][] split(float[] planes) {
        float[][] out = new float[planes.length / 4][];
        for (int i = 0; i < out.length; i++) out[i] = Arrays.copyOfRange(planes, i * 4, i * 4 + 4);
        return out;
    }

    // Bodies drawn from arbitrary faces, packed twelve floats apiece with three or four corners each.

    /** The faces of a drawn body that come within the rim's reach of the cylinder's axis: all a hole can touch. */
    public static int[] near(float[] faces, byte[] corners, int count, Cylinder c) {
        int[] out = new int[Math.max(0, count)];
        int n = 0;
        float reach = c.r * RIM;
        for (int f = 0; f < count; f++) {
            int k = corners[f], o = f * 12;
            if (k < 3) continue;
            float mx = 0, my = 0, mz = 0;
            for (int i = 0; i < k; i++) {mx += faces[o + i * 3]; my += faces[o + i * 3 + 1]; mz += faces[o + i * 3 + 2];}
            mx /= k; my /= k; mz /= k;
            float size = 0;
            for (int i = 0; i < k; i++) {
                float x = faces[o + i * 3] - mx, y = faces[o + i * 3 + 1] - my, z = faces[o + i * 3 + 2] - mz;
                size = Math.max(size, x * x + y * y + z * z);
            }
            if (c.distance(mx, my, mz) - (float) Math.sqrt(size) <= reach) out[n++] = f;
        }
        return Arrays.copyOf(out, n);
    }

    /** Face {@code f} of a drawn body as a polygon of its own three or four corners. */
    public static float[] face(float[] faces, byte[] corners, int f) {
        return Arrays.copyOfRange(faces, f * 12, f * 12 + corners[f] * 3);
    }

    /**
     * Where the line {@code p + t d} first and last crosses the given faces of a drawn body: {first, last},
     * the same twice if it crosses only one, or null when it crosses none.
     */
    public static float[] through(float[] faces, byte[] corners, int[] which, float px, float py, float pz, float dx, float dy, float dz) {
        float first = Float.POSITIVE_INFINITY, last = Float.NEGATIVE_INFINITY;
        int crossed = 0;
        for (int f : which) {
            int o = f * 12;
            float t = triangle(faces, o, o + 3, o + 6, px, py, pz, dx, dy, dz);
            if (Float.isNaN(t) && corners[f] == 4) t = triangle(faces, o, o + 6, o + 9, px, py, pz, dx, dy, dz);
            if (Float.isNaN(t)) continue;
            crossed++;
            first = Math.min(first, t);
            last = Math.max(last, t);
        }
        return crossed == 0 ? null : new float[]{first, last};
    }

    /**
     * The shortest move of the line {@code p + t d} (d of unit length), across itself, that puts it through
     * a corner of the drawn body: {x, y, z}, or null when there are no faces.
     */
    public static float[] toward(float[] faces, byte[] corners, int count, float px, float py, float pz, float dx, float dy, float dz) {
        float[] best = null;
        float nearest = Float.POSITIVE_INFINITY;
        for (int f = 0; f < count; f++)
            for (int k = 0; k < corners[f]; k++) {
                int o = f * 12 + k * 3;
                float x = faces[o] - px, y = faces[o + 1] - py, z = faces[o + 2] - pz, t = x * dx + y * dy + z * dz;
                x -= t * dx; y -= t * dy; z -= t * dz;
                float distance = x * x + y * y + z * z;
                if (distance < nearest) {nearest = distance; best = new float[]{x, y, z};}
            }
        return best;
    }

    /** Möller–Trumbore on the triangle of corners at a, b and c: where the line meets it, NaN if it misses. */
    private static float triangle(float[] q, int a, int b, int c, float px, float py, float pz, float dx, float dy, float dz) {
        float e1x = q[b] - q[a], e1y = q[b + 1] - q[a + 1], e1z = q[b + 2] - q[a + 2];
        float e2x = q[c] - q[a], e2y = q[c + 1] - q[a + 1], e2z = q[c + 2] - q[a + 2];
        float hx = dy * e2z - dz * e2y, hy = dz * e2x - dx * e2z, hz = dx * e2y - dy * e2x;
        float det = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(det) < 1e-12f) return Float.NaN;
        float inv = 1 / det;
        float sx = px - q[a], sy = py - q[a + 1], sz = pz - q[a + 2];
        float u = inv * (sx * hx + sy * hy + sz * hz);
        if (u < 0 || u > 1) return Float.NaN;
        float rx = sy * e1z - sz * e1y, ry = sz * e1x - sx * e1z, rz = sx * e1y - sy * e1x;
        float v = inv * (dx * rx + dy * ry + dz * rz);
        if (v < 0 || u + v > 1) return Float.NaN;
        return inv * (e2x * rx + e2y * ry + e2z * rz);
    }

    /** A flat polygon's unit normal, by the turn of all its corners (Newell's method); zero for a degenerate one. */
    public static float[] normal(float[] polygon) {
        int n = polygon.length / 3;
        float nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            nx += (polygon[a + 1] - polygon[b + 1]) * (polygon[a + 2] + polygon[b + 2]);
            ny += (polygon[a + 2] - polygon[b + 2]) * (polygon[a] + polygon[b]);
            nz += (polygon[a] - polygon[b]) * (polygon[a + 1] + polygon[b + 1]);
        }
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 1e-12f ? new float[3] : new float[]{nx / l, ny / l, nz / l};
    }

    /** The mean of a polygon's corners. */
    public static float[] centre(float[] polygon) {
        int n = polygon.length / 3;
        float[] c = new float[3];
        for (int i = 0; i < n; i++) {c[0] += polygon[i * 3]; c[1] += polygon[i * 3 + 1]; c[2] += polygon[i * 3 + 2];}
        c[0] /= n; c[1] /= n; c[2] /= n;
        return c;
    }
}
