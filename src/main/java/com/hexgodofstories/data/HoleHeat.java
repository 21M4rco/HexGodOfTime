package com.hexgodofstories.data;

/**
 * How hot a Scepter hole is, and the light it gives off while it is.
 *
 * <p>The beam leaves every hole red-hot. The heat comes up as the beam goes through, running down a tunnel
 * through a wall the way the beam did; then the hole cools, through orange to a dim yellowish glow, and goes
 * out. A body cools in {@link #BODY} ticks; a wall holds its heat a while longer, {@link #WALL}. Either way
 * the inside of a hole, where the air does not reach, keeps its heat longer than its mouths do: halfway
 * down it, {@link #DEEP} longer again.
 *
 * <p>Nothing here needs Minecraft.
 */
public final class HoleHeat {
    private HoleHeat() { }

    /** Ticks for a hole in a body to cool at its mouths, from red-hot to nothing: eight seconds. */
    public static final float BODY = 160;
    /** The same for a hole in a wall: twelve seconds, for stone holds its heat longer than flesh. */
    public static final float WALL = 240;
    /** How much longer the inside of a hole keeps its heat than its mouths, halfway down it, as a share. */
    public static final float DEEP = .4f;
    /** Ticks for the heat to come up where the beam goes through. */
    public static final float RISE = 5;
    /** Ticks the beam's heat takes to run a block down a tunnel. */
    public static final float SWEEP = .35f;

    /**
     * The glow as it cools, from cold up to red-hot: at each heat, the red, green and blue of its light at
     * full strength. Deep red at the top, orange below it, then yellowish, paling as it goes out.
     */
    private static final float[] STOPS = {
        0, .92f, .84f, .42f,
        .2f, .96f, .76f, .26f,
        .45f, 1, .5f, .1f,
        .7f, 1, .26f, .04f,
        1, 1, .1f, .02f,
    };

    /**
     * How hot a point of a hole is, from 0 (cold) to 1 (red-hot), {@code age} ticks after the shot, in a hole
     * whose mouths cool in {@code cooling} ticks. {@code depth} is how far inside the hole the point lies, 0
     * at a mouth and 1 halfway down it; {@code along}, how many blocks down the hole it is from where the beam
     * went in.
     */
    public static float heat(float age, float cooling, float depth, float along) {
        float t = age - Math.max(0, along) * SWEEP;
        if (!(t > 0)) return 0;
        if (t < RISE) {
            float rise = t / RISE;
            return rise * rise * (3 - 2 * rise);
        }
        float cooled = (t - RISE) / (cooling * (1 + DEEP * clamp(depth)));
        return cooled >= 1 ? 0 : 1 - cooled;
    }

    /** Whether every point of a hole whose mouths cool in {@code cooling} ticks, down a tunnel {@code run} blocks long, is cold at {@code age}. */
    public static boolean cold(float age, float cooling, float run) {
        return age >= Math.max(0, run) * SWEEP + RISE + cooling * (1 + DEEP);
    }

    /**
     * The light a point of a hole gives off, into {@code rgb} as the red, green and blue to add over whatever is
     * drawn there, for the same point and moment as {@link #heat}. While the heat comes up it is already red,
     * only brightening; after that, {@link #glow(float, float[])} of how hot it still is.
     */
    public static float[] glow(float age, float cooling, float depth, float along, float[] rgb) {
        float t = age - Math.max(0, along) * SWEEP;
        if (!(t > 0)) return glow(0, rgb);
        if (t >= RISE) return glow(heat(age, cooling, depth, along), rgb);
        float rise = t / RISE;
        rise = rise * rise * (3 - 2 * rise);
        glow(1, rgb);
        for (int c = 0; c < 3; c++) rgb[c] *= rise;
        return rgb;
    }

    /**
     * The light a point at {@code heat} gives off as it cools, into {@code rgb} as the red, green and blue to add
     * over whatever is drawn there: deep red while it is red-hot, through orange to a dim yellowish glow as it
     * cools, and none once it is cold.
     */
    public static float[] glow(float heat, float[] rgb) {
        float h = clamp(heat);
        if (h <= 0) {
            rgb[0] = rgb[1] = rgb[2] = 0;
            return rgb;
        }
        int i = 0;
        while (i + 8 < STOPS.length && STOPS[i + 4] < h) i += 4;
        float f = (h - STOPS[i]) / (STOPS[i + 4] - STOPS[i]);
        float bright = (float) Math.pow(h, .6);
        for (int c = 0; c < 3; c++) rgb[c] = (STOPS[i + 1 + c] + (STOPS[i + 5 + c] - STOPS[i + 1 + c]) * f) * bright;
        return rgb;
    }

    private static float clamp(float v) {return v < 0 ? 0 : v > 1 ? 1 : v;}
}
