package com.hexgodofstories.warping;

import java.util.Random;

/**
 * The Void Sea's swell, checked against the four things it is not allowed to stop doing.
 *
 * <p>None of these show up as an error at runtime. A field that dips below the waterline would
 * quietly reopen the hole in the water that the hunter is supposed to be hidden behind; a sea that
 * stopped being long-crested would look stirred rather than driven and no log would say so; a wave
 * that reached the sea floor would take the calm out of the deep; and a collection that stopped
 * being bounded would cost a few thousand times more per frame than it does now without ever
 * throwing. No Minecraft world is needed for any of it — the ocean is a formula, so it can simply
 * be asked.
 */
public final class VoidSeaWaveTest {
    /** Across the front: the direction a wavefront runs, at right angles to the way it travels. */
    private static final double CROSS_X = -VoidSeaWaves.DIR_Z, CROSS_Z = VoidSeaWaves.DIR_X;

    public static void main(String[] args) {
        neverBelowTheWaterline();
        travelsOneWay();
        theDeepStaysCalm();
        costIsBounded();
        variesInSizeAndSpacing();
        System.out.println("VoidSeaWaveTest: the sea moves, one way, and never opens a hole in itself.");
    }

    /**
     * The invariant the whole concealment story rests on.
     *
     * <p>The renderer lays its surface over the water the realm already has, so the moment any part
     * of the field can go negative the drawn surface drops below the still waterline and a trough
     * becomes a thinner column of water to see through from above. Every term is written to be
     * non-negative; this is what says it stayed that way.
     */
    private static void neverBelowTheWaterline() {
        Random random = new Random(20260921L);
        double lowest = Double.MAX_VALUE;
        for (int trial = 0; trial < 3000; trial++) {
            double time = random.nextDouble() * 500000.0;
            double x = (random.nextDouble() - 0.5) * 40000.0;
            double z = (random.nextDouble() - 0.5) * 40000.0;
            VoidSeaWaves.Wave[] waves = VoidSeaWaves.collect(x, z, 16.0, time);
            for (int sample = 0; sample < 8; sample++) {
                double h = VoidSeaWaves.height(x + (random.nextDouble() - 0.5) * 24,
                    z + (random.nextDouble() - 0.5) * 24, 0, time, waves);
                lowest = Math.min(lowest, h);
            }
        }
        check(lowest >= 0.0, "the surface never falls below the still waterline (lowest " + lowest + ")");
    }

    /**
     * One heading for the whole sea.
     *
     * <p>Checked by shape rather than by construction: a line drawn the way the waves travel should
     * cross crest after crest, and a line drawn along a front should stay on roughly the same
     * water. Crests are allowed to bend, so an individual place can read either way; what cannot
     * happen, if every wave really is running the same way, is for the two to average out level.
     */
    private static void travelsOneWay() {
        Random random = new Random(4113L);
        double total = 0;
        int counted = 0;
        for (int trial = 0; trial < 400; trial++) {
            double time = random.nextDouble() * 300000.0;
            double x = (random.nextDouble() - 0.5) * 16000.0;
            double z = (random.nextDouble() - 0.5) * 16000.0;
            VoidSeaWaves.Wave[] waves = VoidSeaWaves.collect(x, z, 240.0, time);
            double along = walk(x, z, VoidSeaWaves.DIR_X, VoidSeaWaves.DIR_Z, time, waves);
            double across = walk(x, z, CROSS_X, CROSS_Z, time, waves);
            if (across < 1.0E-6) continue;
            total += along / across;
            counted++;
        }
        double mean = total / Math.max(1, counted);
        check(mean > 2.0, "the sea is long crested along one heading (variation ratio " + mean + ")");
    }

    /** Total rise and fall over two hundred blocks walked in one direction. */
    private static double walk(double x, double z, double dx, double dz, double time, VoidSeaWaves.Wave[] waves) {
        double sum = 0, last = VoidSeaWaves.height(x, z, 0, time, waves);
        for (int step = 1; step <= 100; step++) {
            double h = VoidSeaWaves.height(x + dx * step * 2, z + dz * step * 2, 0, time, waves);
            sum += Math.abs(h - last);
            last = h;
        }
        return sum;
    }

    /** Sixty blocks down is the hunter's water, and the weather up top has no business in it. */
    private static void theDeepStaysCalm() {
        Random random = new Random(77L);
        double worst = 0;
        for (int trial = 0; trial < 1500; trial++) {
            double time = random.nextDouble() * 300000.0;
            double x = (random.nextDouble() - 0.5) * 20000.0;
            double z = (random.nextDouble() - 0.5) * 20000.0;
            VoidSeaWaves.Wave[] waves = VoidSeaWaves.collect(x, z, 16.0, time);
            double surface = VoidSeaWaves.height(x, z, 0, time, waves);
            if (surface < 1.0) continue;
            worst = Math.max(worst, VoidSeaWaves.height(x, z, 60, time, waves) / surface);
        }
        check(worst < 0.25, "little of the swell survives sixty blocks down (" + worst + " of it did)");
    }

    /**
     * A frame resolves the waves once and then reads them thousands of times, so what matters is
     * that "once" can never quietly become expensive.
     */
    private static void costIsBounded() {
        Random random = new Random(909L);
        int most = 0;
        for (int trial = 0; trial < 4000; trial++) {
            most = Math.max(most, VoidSeaWaves.collect(
                (random.nextDouble() - 0.5) * 200000.0,
                (random.nextDouble() - 0.5) * 200000.0,
                240.0, random.nextDouble() * 1000000.0).length);
        }
        check(most <= 48, "a render sized collection stays small (largest was " + most + ")");
    }

    /**
     * An ocean, not one animation on a loop: small water most of the time, something big
     * occasionally, and no rhythm a player could set a watch by.
     */
    private static void variesInSizeAndSpacing() {
        double x = 612.0, z = -1180.0;
        double previous = 0, beforeThat = 0;
        double smallest = Double.MAX_VALUE, largest = 0;
        // Far enough back that the first crest is never suppressed, and nowhere near the range
        // where subtracting it from a tick index can wrap.
        int crests = 0, lastCrest = -10000, shortestGap = Integer.MAX_VALUE, longestGap = 0;
        for (int tick = 0; tick < 9000; tick++) {
            double time = 250000 + tick;
            double h = VoidSeaWaves.height(x, z, 0, time, VoidSeaWaves.collect(x, z, 16.0, time));
            if (previous > beforeThat && previous >= h && previous > 1.0 && tick - lastCrest > 24) {
                crests++;
                smallest = Math.min(smallest, previous);
                largest = Math.max(largest, previous);
                if (crests > 1) {
                    shortestGap = Math.min(shortestGap, tick - lastCrest);
                    longestGap = Math.max(longestGap, tick - lastCrest);
                }
                lastCrest = tick;
            }
            beforeThat = previous;
            previous = h;
        }
        check(crests >= 20, "waves keep arriving (" + crests + " crests in seven and a half minutes)");
        check(largest > 4.0, "some of them are big (largest was " + largest + " blocks)");
        check(largest > smallest * 2.5, "they are not all the same size (" + smallest + " to " + largest + ")");
        check(longestGap > shortestGap * 3, "the spacing is not a metronome (" + shortestGap + " to " + longestGap + " ticks)");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        System.out.println("  ok: " + what);
    }
}
