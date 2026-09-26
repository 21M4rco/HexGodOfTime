package com.hexgodofstories.data;

import java.util.Random;

/**
 * The Scepter's hole is carved out of a limb, never laid on it: a beam that meets a limb at an edge or a
 * corner bites that edge or corner away, and nothing of the hole — opening, tunnel wall or rim — ever
 * lies outside the limb. Checked on a limb's cube from every side, and on the same cube handed over as
 * drawn faces, the way a body with no vanilla model parts arrives. No Minecraft bootstrap required.
 */
public final class WoundCarveTest {
    private static final float[] CUBE = {0, 0, 0, 1, 1, 1};
    private static final float[] PLANES = WoundCarve.boxPlanes(CUBE);
    private static final float EPS = 1e-4f;
    /** The area of the whole round opening: the inscribed polygon's. */
    private static final float R = .2f, WHOLE = WoundCarve.SIDES / 2f * R * R * (float) Math.sin(2 * Math.PI / WoundCarve.SIDES);

    public static void main(String[] args) {
        aHoleInTheMiddleIsWholeAndGoesThrough();
        aHoleAtAnEdgeBitesTheEdgeAway();
        aHoleAtACornerCarvesTheCornerAway();
        aBeamBesideTheLimbCutsNothing();
        nothingOfAHoleIsEverOutsideTheLimb();
        theNearestPointOfALimbToABeam();
        aDrawnBodyIsCutTheSameWay();
        System.out.println("WoundCarveTest: holes are carved out of the limb, corners included, and never hang outside it.");
    }

    private static void aHoleInTheMiddleIsWholeAndGoesThrough() {
        WoundCarve.Cylinder c = new WoundCarve.Cylinder(.5f, .5f, -1, 0, 0, 1, R);
        for (int f = 0; f < 6; f++) {
            float[] opening = WoundCarve.opening(WoundCarve.boxFace(CUBE, f), c);
            if (f >= 4) require(opening != null && near(area(opening), WHOLE, 1e-4f), "face " + f + " should have the whole opening");
            else require(opening == null, "a beam down the middle must not cut side face " + f);
        }
        float reach = WoundCarve.reach(CUBE, c);
        for (int k = 0; k < WoundCarve.SIDES; k++) {
            float[] wall = WoundCarve.wall(c, k, PLANES, reach);
            require(wall != null, "every side of the tunnel runs through the limb");
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < wall.length; i += 3) {
                float along = c.along(wall[i], wall[i + 1], wall[i + 2]);
                lo = Math.min(lo, along); hi = Math.max(hi, along);
            }
            require(near(lo, 1, EPS) && near(hi, 2, EPS), "the tunnel runs from face to face, no further: " + lo + ".." + hi);
        }
        require(near(WoundCarve.depth(PLANES, c, .6f, .5f, .5f), 1, EPS), "halfway through is the deepest");
        require(near(WoundCarve.depth(PLANES, c, .6f, .5f, 0), 0, EPS), "a mouth is not deep at all");
        require(near(WoundCarve.depth(PLANES, c, .6f, .5f, .25f), .5f, EPS), "a quarter of the way in is halfway deep");
    }

    private static void aHoleAtAnEdgeBitesTheEdgeAway() {
        // The beam's line runs down the face x = 1: half of the hole is in the limb, and it opens the side.
        WoundCarve.Cylinder c = new WoundCarve.Cylinder(1, .5f, -1, 0, 0, 1, R);
        float[] front = WoundCarve.opening(WoundCarve.boxFace(CUBE, 5), c);
        require(front != null && near(area(front), WHOLE / 2, 1e-4f), "the front loses half a hole at its edge, not a whole one");
        float[] side = WoundCarve.opening(WoundCarve.boxFace(CUBE, 0), c);
        require(side != null && near(area(side), 2 * R, 1e-4f), "the side is opened along the whole bite: " + (side == null ? 0 : area(side)));
        int walls = 0;
        for (int k = 0; k < WoundCarve.SIDES; k++) if (WoundCarve.wall(c, k, PLANES, WoundCarve.reach(CUBE, c)) != null) walls++;
        require(walls == WoundCarve.SIDES / 2, "only the half of the tunnel inside the limb has walls: " + walls);
    }

    private static void aHoleAtACornerCarvesTheCornerAway() {
        // The beam's line runs down the limb's corner: a quarter of the hole is cut, and both sides are opened.
        WoundCarve.Cylinder c = new WoundCarve.Cylinder(1, 1, -1, 0, 0, 1, R);
        float[] front = WoundCarve.opening(WoundCarve.boxFace(CUBE, 5), c);
        require(front != null && near(area(front), WHOLE / 4, 1e-4f), "the corner is carved by a quarter of a hole");
        for (int f : new int[]{0, 2}) {
            float[] side = WoundCarve.opening(WoundCarve.boxFace(CUBE, f), c);
            require(side != null && near(area(side), R, 1e-4f), "both faces beside the corner are opened along it");
        }
        int pieces = 0;
        for (int k = 0; k < WoundCarve.SIDES; k++) {
            float[] rim = WoundCarve.rim(WoundCarve.boxFace(CUBE, 5), c, k);
            if (rim == null) continue;
            pieces++;
            for (int i = 0; i < rim.length; i += 3)
                require(rim[i] <= 1 + EPS && rim[i + 1] <= 1 + EPS, "the rim stops at the face's own edge");
        }
        require(pieces == WoundCarve.SIDES / 4, "the rim follows the carved quarter only: " + pieces);
    }

    private static void aBeamBesideTheLimbCutsNothing() {
        WoundCarve.Cylinder c = new WoundCarve.Cylinder(1.5f, .5f, -1, 0, 0, 1, R);
        for (int f = 0; f < 6; f++) {
            require(WoundCarve.opening(WoundCarve.boxFace(CUBE, f), c) == null, "no opening on face " + f);
            for (int k = 0; k < WoundCarve.SIDES; k++) require(WoundCarve.rim(WoundCarve.boxFace(CUBE, f), c, k) == null, "no rim");
        }
        for (int k = 0; k < WoundCarve.SIDES; k++) require(WoundCarve.wall(c, k, PLANES, WoundCarve.reach(CUBE, c)) == null, "no tunnel");
    }

    private static void nothingOfAHoleIsEverOutsideTheLimb() {
        Random random = new Random(20260926);
        int carved = 0, bitten = 0;
        for (int trial = 0; trial < 4000; trial++) {
            float r = .02f + random.nextFloat() * .5f;
            float x = -.4f + random.nextFloat() * 1.8f, y = -.4f + random.nextFloat() * 1.8f, z = -.4f + random.nextFloat() * 1.8f;
            float dx = (float) random.nextGaussian(), dy = (float) random.nextGaussian(), dz = (float) random.nextGaussian();
            WoundCarve.Cylinder c = new WoundCarve.Cylinder(x, y, z, dx, dy, dz, r);
            float reach = WoundCarve.reach(CUBE, c);
            boolean any = false, whole = true;
            for (int f = 0; f < 6; f++) {
                float[] face = WoundCarve.boxFace(CUBE, f);
                float[] opening = WoundCarve.opening(face, c);
                if (opening != null) {
                    any = true;
                    for (int i = 0; i < opening.length; i += 3) {
                        inCube(opening, i, "an opening");
                        require(c.distance(opening[i], opening[i + 1], opening[i + 2]) <= r + EPS, "an opening reaches past the beam");
                    }
                    // Smaller than the same plane's cut, unbounded: the face's edge took some of it.
                    float[] plane = CUBE.clone();
                    for (int j = 0; j < 3; j++) {plane[j] = -9; plane[j + 3] = 9;}
                    plane[f / 2] = plane[f / 2 + 3] = f % 2 == 0 ? CUBE[f / 2 + 3] : CUBE[f / 2];
                    if (area(opening) < area(WoundCarve.opening(WoundCarve.boxFace(plane, f), c)) - 1e-4f) whole = false;
                }
                for (int k = 0; k < WoundCarve.SIDES; k++) {
                    float[] rim = WoundCarve.rim(face, c, k);
                    if (rim == null) continue;
                    for (int i = 0; i < rim.length; i += 3) {
                        inCube(rim, i, "a rim");
                        float out = c.distance(rim[i], rim[i + 1], rim[i + 2]);
                        require(out >= c.apothem - EPS && out <= r * WoundCarve.RIM + EPS, "a rim is not around its opening");
                        float depth = WoundCarve.rimDepth(c, k, rim[i], rim[i + 1], rim[i + 2]);
                        require(depth >= 0 && depth <= 1, "rim depth out of range");
                    }
                }
            }
            for (int k = 0; k < WoundCarve.SIDES; k++) {
                float[] wall = WoundCarve.wall(c, k, PLANES, reach);
                if (wall == null) continue;
                for (int i = 0; i < wall.length; i += 3) {
                    inCube(wall, i, "a tunnel wall");
                    float out = c.distance(wall[i], wall[i + 1], wall[i + 2]);
                    require(out >= c.apothem - EPS && out <= r + EPS, "a tunnel wall is off the beam's side");
                }
            }
            if (any) carved++;
            if (any && !whole) bitten++;
        }
        require(carved > 1000 && bitten > 300, "the random beams should carve plenty of holes and bites: " + carved + ", " + bitten);
    }

    private static void theNearestPointOfALimbToABeam() {
        float[] beside = WoundCarve.nearest(CUBE, 1.5f, .5f, -3, 0, 0, 1);
        require(near(beside[0], 1, EPS) && near(beside[1], .5f, EPS) && near(beside[3], .5f, EPS), "a beam beside a face is nearest that face");
        float s = (float) Math.sqrt(.5);
        float[] corner = WoundCarve.nearest(CUBE, 2, 2, .5f, s, -s, 0);
        require(near(corner[0], 1, 1e-3f) && near(corner[1], 1, 1e-3f) && near(corner[3], (float) Math.sqrt(2), 1e-3f),
            "a beam past a corner is nearest the corner");
        require(near(WoundCarve.nearest(CUBE, .5f, .5f, -3, 0, 0, 1)[3], 0, EPS), "a beam through the limb is no distance from it");
        require(WoundCarve.span(PLANES, 1.5f, .5f, -3, 0, 0, 1) == null, "a line beside the limb never enters it");
        float[] leg = {0, 0, 0, .25f, .375f, .25f};
        require(near(new WoundCarve.Cylinder(0, .2f, -1, 0, 0, 1, R).width(leg), .25f, EPS), "a leg is as wide across a level beam as it is thick");
        require(near(new WoundCarve.Cylinder(0, 0, -1, 0, 1, 0, R).width(leg), .25f, EPS), "and a beam down it meets its square end");
        float[] span = WoundCarve.span(PLANES, .5f, .5f, -3, 0, 0, 1);
        require(span != null && near(span[0], 3, EPS) && near(span[1], 4, EPS), "a line down the middle enters and leaves at the faces");
    }

    private static void aDrawnBodyIsCutTheSameWay() {
        // The cube again, as a body with no vanilla parts hands it over: six drawn faces of four corners.
        float[] faces = new float[6 * 12];
        byte[] corners = new byte[6];
        for (int f = 0; f < 6; f++) {
            System.arraycopy(WoundCarve.boxFace(CUBE, f), 0, faces, f * 12, 12);
            corners[f] = 4;
        }
        WoundCarve.Cylinder c = new WoundCarve.Cylinder(1, 1, -1, 0, 0, 1, R);
        int[] near = WoundCarve.near(faces, corners, 6, c);
        require(near.length == 4 && java.util.Arrays.stream(near).noneMatch(f -> f == 1 || f == 3),
            "the four faces at the corner are within the rim's reach, and the two far from it are not");
        float[] down = WoundCarve.through(faces, corners, near, .5f, .5f, -1, 0, 0, 1);
        require(down != null && near(down[0], 1, EPS) && near(down[1], 2, EPS), "the middle line crosses front and back");
        require(WoundCarve.through(faces, corners, near, 1.2f, .5f, -1, 0, 0, 1) == null, "a line past the side crosses nothing");
        float[] across = WoundCarve.toward(faces, corners, 6, 1.5f, 1.25f, -1, 0, 0, 1);
        require(across != null && near(across[0], -.5f, EPS) && near(across[1], -.25f, EPS) && near(across[2], 0, EPS),
            "a line beside the body is moved square across to its nearest corner");
        float cut = 0;
        for (int f : near) {
            float[] opening = WoundCarve.opening(WoundCarve.face(faces, corners, f), c);
            if (opening != null) cut += area(opening);
        }
        require(near(cut, 2 * WHOLE / 4 + 2 * R, 1e-4f), "front, back and both sides are carved at the corner: " + cut);
    }

    private static void inCube(float[] points, int i, String what) {
        for (int j = 0; j < 3; j++)
            require(points[i + j] >= CUBE[j] - EPS && points[i + j] <= CUBE[j + 3] + EPS, what + " lies outside the limb");
    }

    /** A flat polygon's area, by Newell's method. */
    private static float area(float[] p) {
        double x = 0, y = 0, z = 0;
        int n = p.length / 3;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            x += (p[a + 1] - p[b + 1]) * (p[a + 2] + p[b + 2]);
            y += (p[a + 2] - p[b + 2]) * (p[a] + p[b]);
            z += (p[a] - p[b]) * (p[a + 1] + p[b + 1]);
        }
        return (float) (Math.sqrt(x * x + y * y + z * z) / 2);
    }

    private static boolean near(float a, float b, float within) {return Math.abs(a - b) <= within;}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
