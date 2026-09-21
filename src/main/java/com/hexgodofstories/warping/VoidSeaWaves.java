package com.hexgodofstories.warping;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Void Sea's swell: one moving ocean, described by a formula rather than by blocks.
 *
 * <p>Not a single water block is touched and nothing about the sea is networked. The whole surface
 * is a pure function of position and the world's game time, so the server can ask it what the water
 * is doing under a swimmer and every client can ask it what the water looks like out to the
 * horizon, and the two agree without a packet passing between them. Game time is already
 * synchronised; that is the only shared state this needs.
 *
 * <p><b>Everything travels one way.</b> There is a single heading for the realm and every wave in
 * it runs along that heading. Crests are allowed to bend — a wavefront that is a perfect ruler
 * reads as a wall rather than as water — but the bend is a shape applied across the front, not a
 * change of course, so nothing ever crosses anything else and the sea never looks stirred.
 *
 * <p><b>The surface never goes below the still waterline.</b> Every term here is non-negative by
 * construction, which is both physically reasonable — solitary swells are elevations, not
 * oscillations about the mean — and the property the renderer depends on. A trough is the absence
 * of a crest, not a hole. Nothing this system produces can open a gap in the water between the sky
 * and whatever is underneath it, which is what keeps the hunter concealed while the sea moves.
 *
 * <p><b>Randomness decides what exists, never what a wave is doing.</b> Waves are not spawned and
 * they are not stored. Each size class bins the axis it travels along, and a hash of the bin index
 * decides whether that bin carries a wave and what shape it has. A wave's amplitude, wavelength,
 * crest shape and bend therefore cannot change while it is running — they are properties of its
 * bin, not of the moment it is asked about — and asking about a point is O(a handful of bins) with
 * no history to keep and nothing to tick.
 */
public final class VoidSeaWaves {
    private VoidSeaWaves() { }

    /** The realm's heading. One number, one direction, the whole sea. */
    private static final double HEADING = Math.toRadians(27.0);
    /** Unit vector the waves travel along. */
    public static final double DIR_X = Math.cos(HEADING), DIR_Z = Math.sin(HEADING);
    /** The same, turned ninety degrees: the axis a wavefront runs across. */
    private static final double CROSS_X = -DIR_Z, CROSS_Z = DIR_X;

    private static final double TAU = Math.PI * 2;
    /** Fixed, so client and server generate the same ocean without being told about it. */
    private static final long SEED = 0x5EA0FB1EL;

    /**
     * The standing chop: always there, small, and what stops the sea ever being a mirror.
     *
     * <p>Columns are amplitude, wavelength, how far the crest wanders across the front, over what
     * distance it wanders, and a phase. Written as a raised sine so every component is at or above
     * zero.
     */
    private static final double[][] CHOP = {
        { 0.44, 27.0, 7.0, 73.0, 0.0 },
        { 0.28, 17.0, 4.0, 45.0, 2.3 },
    };

    /**
     * The four sizes of wave, from constant small stuff to the swells you watch coming.
     *
     * <p>Columns: least and greatest amplitude, least and greatest wavelength, how fast the class
     * travels, how long a bin on its axis is, and how often a bin carries anything. Bigger waves
     * run faster, which is both how real water disperses and what keeps the composite from ever
     * repeating: the classes slide through each other instead of marching in step.
     */
    private static final double[][] CLASS = {
        //  Amin  Amax   Lmin   Lmax  speed    bin  density
        {   0.55, 1.60,  30.0,  52.0, 0.34,  104.0, 0.78 },
        {   1.70, 3.00,  50.0,  84.0, 0.45,  190.0, 0.60 },
        {   3.00, 4.80,  80.0, 122.0, 0.56,  360.0, 0.42 },
        {   5.20, 8.00, 118.0, 182.0, 0.70, 1020.0, 0.32 },
    };

    /** One wave, resolved for the moment it was asked about. Nothing here changes while it runs. */
    public static final class Wave {
        /** Where along the heading the peak is right now. */
        final double crest;
        final double amplitude;
        /** Half widths of the leading and trailing faces. A swell is steep in front and long behind. */
        final double front, back;
        /** How far, and over what cross distance, the crest line wanders. */
        final double bend, bendScale, bendPhase;
        /** Blocks below the surface at which this wave is felt at about a third of its strength. */
        final double decay;
        /** Cached support, so a point outside the wave costs two comparisons. */
        final double lead, tail;

        Wave(double crest, double amplitude, double front, double back,
             double bend, double bendScale, double bendPhase, double decay) {
            this.crest = crest; this.amplitude = amplitude; this.front = front; this.back = back;
            this.bend = bend; this.bendScale = bendScale; this.bendPhase = bendPhase; this.decay = decay;
            this.lead = front;
            this.tail = back * TRAIL_SPAN;
        }
    }

    /** How far behind the crest the trailing secondary reaches, in units of the back half width. */
    private static final double TRAIL_SPAN = 2.95;
    /** Height of the secondary crest, as a fraction of the main one. It makes a swell read as a swell. */
    private static final double TRAIL_HEIGHT = 0.30;

    // ------------------------------------------------------------------ the field

    /**
     * Every wave that can reach within {@code radius} of a point, at one moment.
     *
     * <p>Resolve this once and hand the result to {@link #height} for as many points as needed. The
     * renderer evaluates thousands of points a frame and must not re-derive the same dozen waves
     * for every one of them.
     */
    public static Wave[] collect(double x, double z, double radius, double time) {
        double centre = x * DIR_X + z * DIR_Z;
        List<Wave> found = new ArrayList<>(24);
        for (int c = 0; c < CLASS.length; c++) {
            double[] spec = CLASS[c];
            double speed = spec[4], bin = spec[5], density = spec[6];
            // The widest this class could be felt from, so nothing in range is missed.
            double reach = radius + spec[3] * (TRAIL_SPAN * 0.45 + 0.3);
            // A wave belonging to bin m has its peak at m*bin + offset + speed*time. Inverting that
            // for the bins whose peaks currently lie within reach gives a count that depends only
            // on the radius, never on how long the world has been running.
            double travelled = speed * time;
            long first = (long) Math.floor((centre - reach - travelled) / bin);
            long last = (long) Math.floor((centre + reach - travelled) / bin);
            for (long m = first; m <= last; m++) {
                long key = mix(SEED + c * 0x9E3779B1L + m * 0x7F4A7C15L);
                if (roll(key, 1) >= density) continue;
                double amplitude = lerp(roll(key, 2), spec[0], spec[1]);
                double length = lerp(roll(key, 3), spec[2], spec[3]);
                double offset = roll(key, 4) * bin;
                found.add(new Wave(
                    m * bin + offset + travelled,
                    amplitude,
                    length * lerp(roll(key, 5), 0.16, 0.24),
                    length * lerp(roll(key, 6), 0.30, 0.44),
                    lerp(roll(key, 7), 3.0, 13.0),
                    lerp(roll(key, 8), 80.0, 240.0),
                    roll(key, 9) * TAU,
                    length / TAU));
            }
        }
        return found.toArray(new Wave[0]);
    }

    /** The waves around one entity, sized for reading the water it is actually in. */
    public static Wave[] collect(Entity entity, double time) {
        return collect(entity.getX(), entity.getZ(), 8.0, time);
    }

    /**
     * How far the surface stands above the still waterline at a point, felt {@code depth} blocks
     * down. Never negative. Pass a depth of zero for the surface itself.
     */
    public static double height(double x, double z, double depth, double time, Wave[] waves) {
        double along = x * DIR_X + z * DIR_Z;
        double across = x * CROSS_X + z * CROSS_Z;
        double sum = 0;

        for (double[] chop : CHOP) {
            double length = chop[1];
            double fall = attenuate(depth, length / TAU);
            if (fall <= 1.0E-4) continue;
            double bent = along + chop[2] * Math.sin(TAU * across / chop[3] + chop[4]);
            double speed = 0.11 * Math.sqrt(length);
            double phase = TAU * (bent - speed * time) / length + chop[4];
            // Raised rather than centred on zero, so the chop lifts the surface and never drops it.
            sum += chop[0] * 0.5 * (1.0 + Math.sin(phase));
        }

        for (Wave wave : waves) {
            double fall = attenuate(depth, wave.decay);
            if (fall <= 1.0E-4) continue;
            double bent = along + wave.bend * Math.sin(TAU * across / wave.bendScale + wave.bendPhase);
            double s = bent - wave.crest;
            if (s > wave.lead || s < -wave.tail) continue;
            double body = wave.amplitude * bump(s, wave.front, wave.back);
            // One smaller crest following the first. A lone hill of water reads as terrain; a crest
            // with something behind it reads as a sea with more of itself on the way.
            double trail = wave.amplitude * TRAIL_HEIGHT
                * bump(s + wave.back * 1.75, wave.front * 1.15, wave.back * 1.15);
            sum += (body + trail) * fall;
        }
        return sum;
    }

    /** Surface elevation, resolving the waves on the spot. For the few points the server asks about. */
    public static double height(double x, double z, double depth, double time) {
        return height(x, z, depth, time, collect(x, z, 8.0, time));
    }

    /**
     * The crest profile: nothing, rising to one at the peak, and nothing again.
     *
     * <p>The leading face is given the shorter half width, so the wave is steep on the side it is
     * running into and draws out long behind — which is the difference between something that
     * looks like weather and something that looks like a bump travelling across a pond.
     */
    private static double bump(double s, double front, double back) {
        double width = s > 0 ? front : back;
        double t = s / width;
        if (t <= -1.0 || t >= 1.0) return 0;
        return 0.5 + 0.5 * Math.cos(t * Math.PI);
    }

    /** How much of a wave of this scale survives down to {@code depth}. */
    private static double attenuate(double depth, double scale) {
        if (depth <= 0) return 1.0;
        return Math.exp(-depth / Math.max(1.0, scale));
    }

    // ------------------------------------------------------------------ what it does to people

    /**
     * Blocks per tick the water flows along the heading, per block the surface stands proud.
     *
     * <p>These three are set against Minecraft's own water drag rather than in the abstract, since
     * a fifth of the applied motion survives to the next tick. What comes out the far side is about
     * a third of a swimmer's own speed under the standing chop — felt, and swum against; twice
     * their speed under an ordinary wave, where holding a position stops being possible; and
     * getting on for four times it under one of the big swells, which simply takes them with it.
     */
    private static final double SURGE = 0.17;
    /** How much of the surface's own rise and fall becomes the swimmer's. */
    private static final double LIFT = 2.0;
    /** Per tick, how far a swimmer's motion is drawn toward the water's. Momentum, not a shove. */
    private static final double COUPLE = 0.22, COUPLE_VERTICAL = 0.30;
    /** How far above the waterline the sea still has hold of something. */
    private static final double GRIP_ABOVE = 2.0;
    /** Sampling step for reading the slope of the surface, in blocks and in ticks. */
    private static final double STEP = 2.0;

    /**
     * Moves players once a tick, matching the local player's prediction on the client.
     *
     * <p>Mobs are handled by WarpRealms' existing entity loop, so extending the swell to NPCs
     * adds no second scan of the dimension and never applies it to a player twice.
     */
    public static void tick(ServerLevel level, long now) {
        for (Player player : level.players()) apply(player, now);
    }

    /**
     * Applies one tick of water movement to one entity, if the sea is entitled to move it.
     *
     * <p>Players keep their existing prediction and exemptions. Ordinary vanilla and modded mobs
     * use the same forces on the server and normal entity movement tracking on clients. Hexor is
     * explicitly exempt; dry mobs and passengers must not acquire their own swimming motion.
     */
    public static void apply(Entity entity, double time) {
        if (entity instanceof Player player) {
            if (player.isSpectator() || player.isCreative()) return;
        } else {
            if (!(entity instanceof Mob) || entity instanceof AbyssalPilgrimEntity) return;
            if (entity.level().isClientSide || !entity.isAlive()
                    || !entity.isInWater() || entity.isPassenger()) return;
        }
        if (Destination.from(entity.level()) != Destination.VOID_SEA) return;

        double y = entity.getY();
        if (y > VoidSea.SURFACE + GRIP_ABOVE) return;
        if (!entity.isInWater() && y > VoidSea.SURFACE) return;
        double depth = Math.max(0, VoidSea.SURFACE - y);

        double x = entity.getX(), z = entity.getZ();
        Wave[] waves = collect(x, z, 8.0, time);
        double here = height(x, z, depth, time, waves);
        // The rate the surface is changing under them, read a couple of ticks apart so a single
        // tick's arithmetic noise does not become a twitch. The waves are re-resolved either side
        // because a wave that has moved is a different wave to ask about.
        double ahead = height(x, z, depth, time + STEP);
        double behind = height(x, z, depth, time - STEP);
        double rate = (ahead - behind) / (2 * STEP);

        double flow = here * SURGE;
        double rise = rate * LIFT;

        Vec3 motion = entity.getDeltaMovement();
        double wantX = DIR_X * flow, wantZ = DIR_Z * flow;
        entity.setDeltaMovement(
            motion.x + (wantX - motion.x) * COUPLE,
            motion.y + (rise - motion.y) * COUPLE_VERTICAL,
            motion.z + (wantZ - motion.z) * COUPLE);
    }

    // ------------------------------------------------------------------ deterministic noise

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A stable value in [0,1) for one draw from one key. */
    private static double roll(long key, int draw) {
        return (mix(key + draw * 0x632BE59BD9B4E019L) >>> 11) * 0x1.0p-53;
    }

    private static double lerp(double t, double from, double to) { return from + (to - from) * t; }
}
