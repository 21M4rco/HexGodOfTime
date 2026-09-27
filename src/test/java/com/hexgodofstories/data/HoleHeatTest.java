package com.hexgodofstories.data;

/**
 * A Scepter hole is red-hot when the beam goes through, red from the first moment, and cools through orange
 * to a yellowish glow before it goes out; a body cools sooner than a wall, and a hole's mouths sooner than
 * its inside. No Minecraft
 * bootstrap required.
 */
public final class HoleHeatTest {
    public static void main(String[] args) {
        theHeatComesUpAsTheBeamGoesThrough();
        itCoolsAndGoesOut();
        aBodyCoolsSoonerThanAWall();
        theInsideKeepsItsHeatLongest();
        redHotCoolsToYellowish();
        itIsRedFromTheMomentTheBeamGoesThrough();
        System.out.println("HoleHeatTest: holes come up red-hot, cool to yellowish and go out, bodies first, mouths before insides.");
    }

    private static void theHeatComesUpAsTheBeamGoesThrough() {
        require(HoleHeat.heat(0, HoleHeat.WALL, 0, 0) == 0, "no heat before the shot");
        require(HoleHeat.heat(HoleHeat.RISE, HoleHeat.WALL, 0, 0) == 1, "red-hot once the heat has come up");
        float last = 0;
        for (float t = 0; t <= HoleHeat.RISE; t += .25f) {
            float h = HoleHeat.heat(t, HoleHeat.WALL, 0, 0);
            require(h >= last - 1e-6f && h <= 1, "the heat only comes up while the beam goes through: " + h + " at " + t);
            last = h;
        }
        // Down a tunnel, a point further in heats after one nearer the beam's way in.
        float near = HoleHeat.heat(2, HoleHeat.WALL, .5f, 1), far = HoleHeat.heat(2, HoleHeat.WALL, .5f, 5);
        require(near > far, "the heat runs down the tunnel the way the beam did: " + near + " then " + far);
        require(HoleHeat.heat(5 * HoleHeat.SWEEP, HoleHeat.WALL, .5f, 5) == 0, "five blocks in, nothing until the beam's heat gets there");
        require(HoleHeat.heat(16 * HoleHeat.SWEEP + HoleHeat.RISE, HoleHeat.WALL, 0, 16) == 1, "sixteen blocks in, red-hot once it has");
    }

    private static void itCoolsAndGoesOut() {
        for (float cooling : new float[]{HoleHeat.BODY, HoleHeat.WALL})
            for (float depth = 0; depth <= 1; depth += .25f) {
                float last = 1;
                for (float t = HoleHeat.RISE; t < 1000; t += 1) {
                    float h = HoleHeat.heat(t, cooling, depth, 0);
                    require(h <= last + 1e-6f && h >= 0, "once red-hot it only cools: " + h + " after " + last + " at " + t);
                    last = h;
                }
                require(last == 0, "it goes out");
                float out = HoleHeat.RISE + cooling * (1 + HoleHeat.DEEP * depth);
                require(HoleHeat.heat(out - 2, cooling, depth, 0) > 0 && HoleHeat.heat(out, cooling, depth, 0) == 0,
                    "it goes out when its cooling is done, not before");
            }
        for (float run : new float[]{0, 1, 9, 16})
            for (float t = 0; t < 1000; t += 1) {
                if (!HoleHeat.cold(t, HoleHeat.WALL, run)) continue;
                for (float along = 0; along <= run; along += .5f)
                    for (float depth = 0; depth <= 1; depth += .25f)
                        require(HoleHeat.heat(t, HoleHeat.WALL, depth, along) == 0, "nothing of a hole called cold is still hot");
            }
        // The sizzle dies away as the last of the glow does, ending when the hole is called cold: never long after.
        for (float cooling : new float[]{HoleHeat.BODY, HoleHeat.WALL})
            for (float run : new float[]{0, 1, 9, 16}) {
                float end = HoleHeat.coldAt(cooling, run);
                require(HoleHeat.cold(end, cooling, run) && !HoleHeat.cold(end - 1e-3f, cooling, run), "cold from coldAt on, and not before");
                require(HoleHeat.heat(end - 4, cooling, 1, run / 2) > 0, "the middle of the hole still glows a few ticks before it is called cold");
            }
    }

    private static void aBodyCoolsSoonerThanAWall() {
        boolean sooner = false;
        for (float t = 0; t < 1000; t += 1) {
            float body = HoleHeat.heat(t, HoleHeat.BODY, 0, 0), wall = HoleHeat.heat(t, HoleHeat.WALL, 0, 0);
            require(body <= wall, "a body is never hotter than a wall shot at the same moment");
            sooner |= body == 0 && wall > .2f;
        }
        require(sooner, "a body has gone cold while a wall still glows");
    }

    private static void theInsideKeepsItsHeatLongest() {
        for (float t = HoleHeat.RISE; t < 1000; t += 1) {
            float mouth = HoleHeat.heat(t, HoleHeat.WALL, 0, 0), inside = HoleHeat.heat(t, HoleHeat.WALL, 1, 0);
            require(inside >= mouth, "the inside is never cooler than a mouth");
        }
        require(HoleHeat.heat(HoleHeat.RISE + HoleHeat.WALL, HoleHeat.WALL, 1, 0) > 0, "the inside still glows when the mouths have gone out");
    }

    private static void redHotCoolsToYellowish() {
        float[] rgb = new float[3];
        HoleHeat.glow(1, rgb);
        require(rgb[0] > .9f && rgb[1] < .15f * rgb[0] && rgb[2] < .05f * rgb[0], "red-hot is red: " + str(rgb));
        HoleHeat.glow(.15f, rgb);
        require(rgb[1] > .75f * rgb[0] && rgb[2] < .4f * rgb[0] && rgb[0] > .2f, "nearly cool is a dim yellow: " + str(rgb));
        HoleHeat.glow(0, rgb);
        require(rgb[0] == 0 && rgb[1] == 0 && rgb[2] == 0, "cold gives off nothing");
        float lastHue = 0, lastBright = Float.POSITIVE_INFINITY;
        for (float h = 1; h > 0; h -= .01f) {
            HoleHeat.glow(h, rgb);
            float hue = rgb[1] / rgb[0], bright = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            require(hue >= lastHue - 1e-5f, "cooling only ever turns it from red toward yellow: " + str(rgb) + " at " + h);
            require(bright <= lastBright + 1e-5f, "cooling only ever dims it: " + str(rgb) + " at " + h);
            require(rgb[0] <= 1 && rgb[1] <= 1 && rgb[2] <= 1 && rgb[2] >= 0, "a light, not an overflow: " + str(rgb));
            lastHue = hue;
            lastBright = bright;
        }
        require(lastHue > .85f, "the last of it is yellowish, not red: green " + lastHue + " of red");
    }

    private static void itIsRedFromTheMomentTheBeamGoesThrough() {
        float[] rgb = new float[3], red = HoleHeat.glow(1, new float[3]);
        float last = 0;
        for (float t = .25f; t < HoleHeat.RISE; t += .25f) {
            HoleHeat.glow(t, HoleHeat.WALL, 0, 0, rgb);
            require(rgb[0] > 0 && rgb[1] < .15f * rgb[0] && rgb[2] < .05f * rgb[0], "coming up, it is red already: " + str(rgb) + " at " + t);
            require(rgb[0] >= last - 1e-6f && rgb[0] <= red[0] + 1e-6f, "coming up, it only brightens: " + str(rgb) + " at " + t);
            last = rgb[0];
        }
        HoleHeat.glow(0, HoleHeat.WALL, 0, 0, rgb);
        require(rgb[0] == 0 && rgb[1] == 0 && rgb[2] == 0, "nothing before the beam");
        HoleHeat.glow(4 * HoleHeat.SWEEP, HoleHeat.WALL, .3f, 5, rgb);
        require(rgb[0] == 0, "nothing five blocks in before the beam's heat gets there");
        // Once it is up, the light of the moment is the light of its heat.
        float[] heat = new float[3];
        for (float t = HoleHeat.RISE; t < 400; t += 7)
            for (float depth = 0; depth <= 1; depth += .5f) {
                HoleHeat.glow(t, HoleHeat.BODY, depth, 0, rgb);
                HoleHeat.glow(HoleHeat.heat(t, HoleHeat.BODY, depth, 0), heat);
                require(rgb[0] == heat[0] && rgb[1] == heat[1] && rgb[2] == heat[2], "cooling, it gives off the light of its heat at " + t);
            }
    }

    private static String str(float[] rgb) {return String.format("(%.3f, %.3f, %.3f)", rgb[0], rgb[1], rgb[2]);}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
