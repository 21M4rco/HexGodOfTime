package com.hexgodofstories.warping;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Shared terrain and visual coordinates for the bounded candy archipelago. */
public final class Paradise {
    private Paradise() { }

    /** Y of the topmost solid block of the central island: everything else is placed around it. */
    public static final int SURFACE = 160;
    /** The hot spring's middle, its radius, and how deep the water in it stands. */
    public static final double SPRING_RADIUS = 8.5;
    public static final int SPRING_DEPTH = 3;
    /** Below this there is nothing left to fall past, and a faller is put back at {@link #CEILING}. */
    public static final int FLOOR = 48, CEILING = 286;
    public static final double BOUNDARY = 152, FOLD_INSET = 12;
    public static final double SPRING_X = 0, SPRING_Z = 13;
    /**
     * Where a traveller comes in: sixteen blocks of air over the meadow beside the spring.
     *
     * <p>It lives here rather than in {@link Destination} because it is a fact about this island —
     * which part of it is open ground, and how much sky there is above that — and because the
     * layout, the planting and the build's own checks all need it. The destination table reads it
     * from here, exactly as it reads the Void Sea's waterline from {@link VoidSea}.
     */
    public static final Vec3 ARRIVAL = new Vec3(0.5, SURFACE + 16, 28.5);
    /**
     * Safe return point for anything that misses an island.
     *
     * <p>This is deliberately on the heart meadow instead of at the old ceiling loop. Falling out
     * of Paradise now folds the body back onto solid ground rather than making it repeat the fall.
     */
    public static final Vec3 RESCUE = new Vec3(ARRIVAL.x, SURFACE + 1.0, ARRIVAL.z);

    // ------------------------------------------------------------------ gravity

    /**
     * How much of Minecraft's own gravity Paradise gives back each tick.
     *
     * <p>Vanilla pulls a body down by 0.08 blocks per tick per tick. Handing 0.064 of that back
     * leaves 0.016, a fifth of normal, and the build measures what that actually buys rather than
     * trusting the arithmetic: a running jump reaches four and a half blocks up and carries a
     * little over fourteen along, against vanilla's one and a quarter and four. The island spacing
     * is set from those two numbers, so this constant and the layout are one decision rather than
     * two — move it and {@code ParadiseShapeTest} will say what the realm has become.
     *
     * <p>It is deliberately not weaker than this. Past about a fifth of normal gravity a body
     * stops descending in any way the eye reads as falling, the movement stops being Minecraft's,
     * and a player hangs near the top of a jump long enough for the server's own anti-flight check
     * to take an interest. This is weak gravity, not the absence of it.
     */
    public static final double LIFT = 0.064;
    /** Terminal descent, in blocks per tick. Slow enough that the void below is scenery. */
    public static final double SINK = 0.55;
    /**
     * The fastest anything is ever left rising by this, in blocks per tick.
     *
     * <p>Nothing that reaches here should be able to climb — the lift is smaller than the gravity
     * that follows it in the same tick, so a body always loses ground over the tick as a whole.
     * This is the guard that means "should" does not have to be trusted: whatever state a body
     * turns out to be in, the most Paradise can leave it doing is a jump's worth of upward speed.
     */
    private static final double RISE_CAP = 0.62;

    /**
     * One tick of Paradise's gravity on one body.
     *
     * <p>Run by the realm's own tick for everything in it and, separately, by each client for the
     * one player that client owns — exactly the arrangement the Void Sea's swell uses. The server
     * decides what gravity is and the client cannot choose differently, while the local copy is
     * what makes a jump feel light instead of feeling corrected.
     *
     * <p>It runs on living bodies only, and that is the whole of why it is safe. A living body is
     * pulled down by 0.08 a tick, which is more than the 0.058 handed back here, so every tick is
     * still a tick of falling. Thrown things, dropped items and vehicles each fall at their own
     * rate — an item's is 0.04 — and handing any of them this much lift would be handing them
     * flight. They are left to the terminal-speed clamp instead, which is what makes a dropped
     * thing drift down through the void rather than plummet.
     *
     * <p>The two states that change what gravity means for a living body are refused for the same
     * reason: Slow Falling replaces the pull with 0.01, and an elytra replaces the physics
     * entirely. Either would turn weak gravity into no gravity.
     */
    public static void gravity(Entity e) {
        if (!(e instanceof LivingEntity living) || e.isNoGravity() || e.isSpectator() || e.onGround()) return;
        if (living.isFallFlying() || living.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) return;
        if (e instanceof Player p && p.getAbilities().flying) return;
        if (e.isInWater() || e.isInLava()) return;   // the water has its own, gentler rules
        Vec3 v = e.getDeltaMovement();
        if (v.y < -SINK) { e.setDeltaMovement(v.x, -SINK, v.z); e.fallDistance = 0; e.hurtMarked = true; return; }
        double y = Math.min(v.y + LIFT, RISE_CAP);
        if (y <= v.y) return;
        e.setDeltaMovement(v.x, y, v.z);
        e.fallDistance = 0;
        // A player's own client runs this same call on the same tick, so the server neither has to
        // nor should correct them; everything else is moved by the server alone and must be told.
        if (!(e instanceof Player)) e.hurtMarked = true;
    }

    // ------------------------------------------------------------------ the islands

    /**
     * One floating island.
     *
     * @param radius the average radius; {@link #rim} turns it into an outline that is nowhere even
     * @param keel   how far the underside runs past the ordinary taper, as a multiple of the radius
     * @param shape  this island's own seed, which is the whole of what makes it not the others
     */
    public record Isle(double x, double y, double z, double radius, double keel, long shape) {
        public double distance(double px, double pz) { return Math.hypot(px - x, pz - z); }
    }

    /**
     * A cascade.
     *
     * @param angle  the direction the water left the rock in, which is what tells the terrain which
     *               way is back into the island and which way is open air
     * @param length how far the column of water runs before the client's own spray takes over
     * @param onto   the island this lands on, or -1 for one that lands on nothing at all
     */
    public record Fall(double x, double y, double z, double angle, int length, int onto) { }

    private static final List<Isle> ISLES = new ArrayList<>();
    private static final List<Fall> FALLS = new ArrayList<>();
    private static final List<Isle> LEGACY_ISLES;
    private static final List<Fall> LEGACY_FALLS;
    public static List<Isle> legacyIsles() { return LEGACY_ISLES; }
    public static List<Fall> legacyFalls() { return LEGACY_FALLS; }
    public record Bridge(int from, int to) { }
    public static final List<Bridge> BRIDGES = List.of(
        new Bridge(0,1), new Bridge(0,2), new Bridge(0,3),
        new Bridge(1,3), new Bridge(2,3), new Bridge(0,4), new Bridge(0,5));
    public static final int INHABITED_ISLES = 6;

    /** Return toward the same island without crossing into another realm cell. */
    public static Vec3 fold(Vec3 position) {
        double radius = Math.hypot(position.x, position.z);
        if (radius <= BOUNDARY && position.y <= CEILING) return position;
        double scale = radius > BOUNDARY ? (BOUNDARY - FOLD_INSET) / radius : 1;
        return new Vec3(position.x * scale, Math.min(position.y, CEILING - FOLD_INSET), position.z * scale);
    }

    public static Vec3 bridgeEnd(Isle from, Isle toward) {
        double angle = Math.atan2(toward.z - from.z, toward.x - from.x);
        double reach = rim(from, angle) - 7;
        return new Vec3(from.x + Math.cos(angle) * reach, from.y + 1, from.z + Math.sin(angle) * reach);
    }

    public static List<Isle> isles() { return ISLES; }
    public static List<Fall> falls() { return FALLS; }
    /** The central island, which is the one with the hot spring in it. */
    public static Isle heart() { return ISLES.get(0); }

    static {
        // The heart, and a spiral of islands climbing away from it.
        //
        // Every island is one jump from the one before it, and a jump here reaches four and a half
        // blocks, so no step outward is more than four blocks of climb. That is the difference
        // between a place you move around in and a diagram of islands: the rings would look
        // identical from above either way, and only one of them can actually be walked. The
        // cascade shelves below are the deliberate exception — leaving one is a dive.
        isle(0, SURFACE, 0, 26, 0.95, 0x51A7);
        ring(25, 41, SURFACE - 4, 12, 0.72, 0x2C13);
        ring(115, 41, SURFACE + 4, 11, 0.72, 0x7761);
        ring(205, 41, SURFACE - 3, 12, 0.72, 0x1AF5);
        ring(295, 41, SURFACE + 4, 11, 0.72, 0x63B2);
        ring(33, 64, SURFACE - 8, 9, 0.62, 0x4D20);
        ring(123, 64, SURFACE + 8, 9, 0.62, 0x0E9C);
        ring(213, 64, SURFACE - 7, 9, 0.62, 0x35D8);
        ring(303, 64, SURFACE + 8, 9, 0.62, 0x6A41);
        ring(41, 83, SURFACE - 12, 6, 0.55, 0x1204);
        ring(131, 83, SURFACE + 12, 6, 0.55, 0x58EE);
        ring(221, 83, SURFACE - 11, 6, 0.55, 0x7C37);

        // Cascades. A shelf is hung directly under a rim so the water visibly lands on it and runs
        // off its own far side, which is what turns two islands into one vertical composition.
        // These are the only islands not on the spiral: they hang well below whatever they fall
        // from, and leaving one is a dive rather than a jump.
        cascade(0, Math.PI * 0.5, 22, 8, 0x33A9);
        cascade(2, Math.PI * 1.15, 19, 7, 0x2B70);
        cascade(3, Math.PI * 1.62, 20, 7, 0x4E15);
        // And falls that land on nothing at all, which is the other half of being in open space.
        endless(0, Math.PI * 1.35, 74);
        endless(1, Math.PI * 0.18, 66);
        endless(4, Math.PI * 1.78, 70);
        endless(5, Math.PI * 0.62, 62);
        endless(7, Math.PI * 1.05, 58);
        // Keep the exact previous blueprint available for a one-time, state-matched migration.
        LEGACY_ISLES = List.copyOf(ISLES);
        LEGACY_FALLS = List.copyOf(FALLS);
        ISLES.clear(); FALLS.clear();
        isle(0, SURFACE, 0, 42, 1.0, 0x51A7);           // castle and arrival garden
        isle(-70, 154, 10, 25, 1.05, 0x2C13);          // gingerbread village
        isle(70, 158, 12, 25, 1.05, 0x7761);           // windmill
        isle(0, 150, 76, 25, 1.1, 0x1AF5);            // foreground lagoon
        isle(-54, 174, -66, 17, 1.15, 0x63B2);        // castle satellite
        isle(56, 178, -66, 16, 1.15, 0x4D20);
        isle(-104, 182, -35, 9, 1.1, 0x0E9C);
        isle(106, 169, -37, 10, 1.2, 0x35D8);
        isle(-62, 128, 83, 10, 1.1, 0x6A41);
        isle(67, 133, 88, 9, 1.0, 0x1204);
        isle(-24, 191, -111, 8, 1.2, 0x58EE);
        isle(28, 197, -112, 7, 1.3, 0x7C37);
        endless(0, 0.66, 86);
        endless(0, 2.35, 78);
        endless(1, 1.6, 78);
        endless(2, 0.9, 86);
        endless(3, 1.35, 82);
        endless(4, 3.4, 74);
        endless(5, -0.35, 82);

    }

    /** An island placed by bearing and distance, which is how the spiral is actually laid out. */
    private static void ring(double degrees, double distance, double y, double radius, double keel, long shape) {
        double a = Math.toRadians(degrees);
        isle(Math.cos(a) * distance, y, Math.sin(a) * distance, radius, keel, shape);
    }

    private static void isle(double x, double y, double z, double radius, double keel, long shape) {
        ISLES.add(new Isle(x, y, z, radius, keel, shape));
    }

    /**
     * Hangs a shelf under one island's rim and runs water from the rim onto it.
     *
     * <p>The lip is a point on the parent's own outline rather than on a circle around it, so the
     * water leaves where the rock actually ends. The shelf is pushed three blocks further out and
     * is wider than that, which is what guarantees the column comes down inside its footprint.
     */
    private static void cascade(int parent, double angle, int drop, double radius, long shape) {
        Isle above = ISLES.get(parent);
        double rim = rim(above, angle), dx = Math.cos(angle), dz = Math.sin(angle);
        double lipX = above.x + dx * (rim + 1), lipZ = above.z + dz * (rim + 1);
        int shelf = ISLES.size();
        isle(lipX + dx * 3, above.y - drop, lipZ + dz * 3, radius, 0.55, shape);
        FALLS.add(new Fall(lipX, above.y, lipZ, angle, drop - 1, shelf));
        // Off the far side of the shelf, and onward into open space.
        double onward = angle + 0.25;
        Isle below = ISLES.get(shelf);
        double shelfRim = rim(below, onward);
        FALLS.add(new Fall(below.x + Math.cos(onward) * (shelfRim + 1), below.y,
            below.z + Math.sin(onward) * (shelfRim + 1), onward, 52, -1));
    }

    private static void endless(int parent, double angle, int length) {
        Isle from = ISLES.get(parent);
        double rim = rim(from, angle);
        FALLS.add(new Fall(from.x + Math.cos(angle) * (rim + 1), from.y,
            from.z + Math.sin(angle) * (rim + 1), angle, length, -1));
    }

    // ------------------------------------------------------------------ the outline

    /**
     * How far this island's rock reaches in one direction. Never the same twice around.
     *
     * <p>Four harmonics on the island's own seed, and a floor under the sum of them. The floor is
     * the part that is easy to leave out and expensive to get wrong: without it the harmonics can
     * agree on a very small number in one direction, and an island with a bite taken out of it
     * down to a third of its radius is not an interesting silhouette, it is a broken one. Between
     * the floor and the peak an outline still runs from about 0.62 of the radius to about 1.2 of
     * it, which is twice as far one way as the other.
     */
    public static double rim(Isle isle, double angle) {
        double n = 0.88
            + 0.14 * Math.sin(angle * 2 + phase(isle.shape, 1))
            + 0.09 * Math.sin(angle * 3 + phase(isle.shape, 2))
            + 0.055 * Math.sin(angle * 5 + phase(isle.shape, 3))
            + 0.035 * Math.sin(angle * 7 + phase(isle.shape, 4));
        return isle.radius * Math.max(0.62, n);
    }

    /**
     * How far into this island's rock a point is, in blocks, or a negative number when it is past
     * the rim. This is the one function the terrain, the vegetation and the preview all share.
     */
    public static double inland(Isle isle, double px, double pz) {
        double dx = px - isle.x, dz = pz - isle.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-6) return isle.radius;
        return rim(isle, Math.atan2(dz, dx)) - distance;
    }

    /** How far down the rock goes under a column that is {@code inland} blocks inside the rim. */
    public static int depth(Isle isle, double inland, double distance) {
        if (inland < 0) return 0;
        double taper = 1 + Math.pow(inland, 0.78) * 1.35;
        // A keel under the middle, so the island is a teardrop from below rather than a plate.
        double core = Math.max(0, 1 - distance / Math.max(1, isle.radius * 0.62));
        return (int) Math.max(1, taper + core * core * isle.radius * isle.keel);
    }

    /** The hot spring's own outline, which is uneven for the same reason the islands are. */
    public static double springRim(double angle) {
        return SPRING_RADIUS * (0.86 + 0.14 * Math.sin(angle * 3 + 0.8) + 0.08 * Math.sin(angle * 5 - 1.4));
    }

    /** True where the central island's surface is standing water rather than ground. */
    public static boolean spring(double px, double pz) {
        Isle heart = heart();
        double dx = px - SPRING_X, dz = pz - SPRING_Z, distance = Math.hypot(dx, dz);
        return distance <= springRim(Math.atan2(dz, dx));
    }

    private static double phase(long shape, int k) {
        long h = shape * 2654435761L + k * 40503L;
        h ^= h >>> 17;
        return (Math.abs(h) % 100000) / 100000.0 * Math.PI * 2;
    }

    // ------------------------------------------------------------------ what the water does

    /** How long the spring's regeneration and health boost linger after leaving the water. */
    public static final int BATHE_TICKS = 220;
    /** Candy Rush is the lasting gift: touching Paradise water gives a full five minutes. */
    public static final int CANDY_RUSH_TICKS = 20 * 60 * 5;

    /**
     * Whether this body is in Paradise's water, which is the only thing that hands the buffs out.
     *
     * <p>Every pool in the realm counts, not only the hot spring: the ponds on the outer islands
     * and the cascades themselves are the same water, and a realm that rewarded exactly one puddle
     * would be a realm with one place worth standing in.
     */
    public static boolean bathing(LivingEntity e) {
        return e.isAlive() && !e.isSpectator() && e.isInWater();
    }
}
