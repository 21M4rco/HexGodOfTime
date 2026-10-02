package com.hexgodofstories.warping;

/**
 * The Void Sea's standing swell ({@link VoidSea#swell}), checked across a stretch of sea far wider than anyone will
 * swim: it never dips below the still waterline (it is built only upward, out of water, on top of a flat sea), it
 * rises into heavy peaks, it comes down from them smoothly rather than in cliffs, and its tallest crest stays well
 * under the height arrivals fall from. No Minecraft classes are loaded.
 */
public final class VoidSeaSwellTest {
    public static void main(String[] args) {
        double most = 0, steepest = 0, sum = 0;
        int count = 0, tall = 0;
        for (int x = -3000; x <= 3000; x += 3)
            for (int z = -3000; z <= 3000; z += 7) {
                double s = VoidSea.swell(x, z);
                check(Double.isFinite(s), "the swell is a number everywhere: " + x + "," + z);
                check(s >= 0, "the swell never falls below the waterline: " + s + " at " + x + "," + z);
                check(s <= VoidSea.SWELL_MOST + 1e-9, "the swell never passes its own bound: " + s);
                most = Math.max(most, s);
                sum += s;
                count++;
                if (s > 15) tall++;
                // Neighbouring columns, the way the blocks will actually stand.
                steepest = Math.max(steepest, Math.abs(VoidSea.swell(x + 1, z) - s));
                steepest = Math.max(steepest, Math.abs(VoidSea.swell(x, z + 1) - s));
            }
        check(most > 18, "the sea rises into heavy peaks: tallest " + most);
        check(tall > count / 200, "heavy peaks are common enough to meet: " + tall + " of " + count);
        check(sum / count > 3 && sum / count < 12, "the sea is swell, not a flat calm or a wall: mean " + sum / count);
        check(steepest < .95, "it comes down smoothly, never a cliff: steepest " + steepest + " blocks per block");
        check(VoidSea.ARRIVAL > VoidSea.SURFACE + VoidSea.SWELL_MOST + 10, "arrivals fall from well above the tallest crest");
        check(VoidSea.surface(0, 0) >= VoidSea.SURFACE, "the waterline is lifted, never lowered");
        System.out.printf("VoidSeaSwellTest: tallest %.1f, mean %.1f, steepest %.2f blocks per block.%n", most, sum / count, steepest);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
