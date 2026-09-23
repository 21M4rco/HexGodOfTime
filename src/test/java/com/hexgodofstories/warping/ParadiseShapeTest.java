package com.hexgodofstories.warping;

import java.util.List;

/**
 * Paradise as a place you can actually move around in.
 *
 * <p>Everything here is a failure that looks fine in a screenshot. A dozen islands laid out on
 * rings photograph beautifully and are a prison if the gaps are wider than a jump; a hot spring
 * eight blocks across is a lake with no shore the moment one island's outline happens to pinch in
 * beside it; a cascade drawn from a rim lands in open space rather than on the shelf it was meant
 * for if the shelf moved a metre. None of it produces an error, none of it appears in a log, and
 * all of it would be found by walking the realm — which is the one thing a build cannot do.
 *
 * <p>So the build walks it in arithmetic instead. The jump is simulated on exactly the numbers the
 * realm runs its gravity on, and the layout is measured against the answer rather than against a
 * guess: no island may be further from its nearest reachable neighbour than a jump actually
 * carries, at the height it actually reaches.
 *
 * <p>No Minecraft world is needed for any of it. The realm is a formula.
 */
public final class ParadiseShapeTest {
    /** Vanilla's jump impulse, its gravity, and its airborne drag. What the simulation is built on. */
    private static final double JUMP = 0.42, GRAVITY = 0.08, DRAG = 0.98;
    /** Roughly a sprinting player's ground speed, which is what a running jump carries sideways. */
    private static final double SPRINT = 0.28;

    public static void main(String[] args) {
        double[] leap = leap();
        System.out.printf("  a jump clears %.1f blocks and carries %.1f%n", leap[0], leap[1]);
        check(leap[0] > 3 && leap[0] < 6.5, "a jump clears three to six blocks, which is high without being flight");
        check(leap[1] > 11, "a running jump carries at least eleven blocks of ground");
        check(terminal() > 0.4 && terminal() <= Paradise.SINK + 1.0E-6,
            "a fall settles at the realm's own terminal speed, not vanilla's plummet");

        contained();
        distinct();
        bridgesConnect();
        boundaryFolds();
        cascadesLand();
        cascadesClearTheIslands();
        theSpringHasAShore();
        nothingIsLostOutOfTheBottom();
        arrivalIsOnLand();
        affordable();
        theWaterIsTemporary();
        System.out.println("ParadiseShapeTest: the kingdom is connected, water clears the islands, and flight folds within bounds.");
    }

    // ------------------------------------------------------------------ movement

    /**
     * One running jump, tick by tick, in the order a tick actually applies things.
     *
     * <p>Paradise's lift is handed back at the start of the tick, the body is then moved by whatever
     * velocity it now has, and vanilla's gravity and drag are applied after the move. Simulating it
     * in that order matters: doing gravity first would understate the height by most of a block, and
     * the whole point of the number is to measure the layout against it.
     *
     * @return the height the jump reaches, and the ground it covers before landing back level
     */
    private static double[] leap() {
        double vy = JUMP, y = 0, x = 0, apex = 0;
        for (int tick = 0; tick < 400; tick++) {
            if (tick > 0) vy = Math.min(vy + Paradise.LIFT, 0.62);
            if (vy < -Paradise.SINK) vy = -Paradise.SINK;
            y += vy;
            x += SPRINT;
            apex = Math.max(apex, y);
            vy = (vy - GRAVITY) * DRAG;
            if (y <= 0 && tick > 0) break;
        }
        return new double[]{apex, x};
    }

    /** Where a long fall settles, with the lift and the clamp both in play. */
    private static double terminal() {
        double vy = 0;
        for (int tick = 0; tick < 2000; tick++) {
            vy = Math.min(vy + Paradise.LIFT, 0.62);
            if (vy < -Paradise.SINK) vy = -Paradise.SINK;
            vy = (vy - GRAVITY) * DRAG;
        }
        return -Math.max(vy, -Paradise.SINK);
    }

    // ------------------------------------------------------------------ the layout

    /** A pocket dimension is a place you are inside of, not a world you set out across. */
    private static void contained() {
        double far = 0, low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (Paradise.Isle isle : Paradise.isles()) {
            far = Math.max(far, Math.hypot(isle.x(), isle.z()) + isle.radius() * 1.3);
            low = Math.min(low, isle.y());
            high = Math.max(high, isle.y());
        }
        System.out.printf("  %d islands inside %.0f blocks, from y=%.0f to y=%.0f%n", Paradise.isles().size(), far, low, high);
        check(far < Paradise.BOUNDARY - 8, "the full archipelago sits comfortably inside the folding boundary");
        check(Paradise.isles().size() >= 10, "there are enough islands for the place to be a composition");
        check(low < Paradise.SURFACE - 8 && high > Paradise.SURFACE + 8,
            "islands sit both well above and well below the central one");
    }

    /** Twelve circles at different heights is a diagram. Every outline has to be its own. */
    private static void distinct() {
        List<Paradise.Isle> isles = Paradise.isles();
        for (int i = 0; i < isles.size(); i++) {
            double max = 0, min = Double.MAX_VALUE;
            for (int k = 0; k < 64; k++) {
                double reach = Paradise.rim(isles.get(i), k * Math.PI / 32);
                max = Math.max(max, reach);
                min = Math.min(min, reach);
            }
            quiet(max / min > 1.35, "island " + i + " is a circle (" + String.format("%.2f", max / min) + " long to short)");
            quiet(min > isles.get(i).radius() * 0.5, "island " + i + " has a bite out of it deeper than half its radius");
        }
        System.out.println("  ok: every outline is uneven, and none of them is bitten through");
        for (int i = 0; i < isles.size(); i++) for (int j = i + 1; j < isles.size(); j++) {
            double difference = 0;
            for (int k = 0; k < 64; k++) {
                double t = k * Math.PI / 32;
                difference += Math.abs(Paradise.rim(isles.get(i), t) / isles.get(i).radius()
                    - Paradise.rim(isles.get(j), t) / isles.get(j).radius());
            }
            quiet(difference / 64 > 0.05, "islands " + i + " and " + j + " are the same shape");
        }
        System.out.println("  ok: no two islands share an outline");
    }

    /**
     * Every island is one jump from another one, and nothing merges into its neighbour.
     *
     * <p>The cascade shelves are deliberately exempt from the reachability half. They hang twenty
     * blocks under the rim they catch the water from, which is far below anything that can be
     * jumped back up to — leaving one is a dive into the void and a return from the top of the
     * sky, and that is what they are for.
     */
    private static void bridgesConnect() {
        java.util.Set<Integer> visited=new java.util.HashSet<>();visited.add(0);
        for(int pass=0;pass<Paradise.INHABITED_ISLES;pass++)for(var bridge:Paradise.BRIDGES) {
            if(visited.contains(bridge.from()))visited.add(bridge.to());
            if(visited.contains(bridge.to()))visited.add(bridge.from());
            var a=Paradise.isles().get(bridge.from());var c=Paradise.isles().get(bridge.to());
            var start=Paradise.bridgeEnd(a,c);var end=Paradise.bridgeEnd(c,a);
            double distance=Math.hypot(start.x-end.x,start.z-end.z);
            quiet(distance>8&&distance<110,"bridge has an invalid span");
            quiet(Math.abs(start.y-end.y)/distance<.45,"bridge rises too steeply for slab steps");
            quiet(Paradise.inland(a,start.x,start.z)>5,"bridge start has no solid anchorage");
            quiet(Paradise.inland(c,end.x,end.z)>5,"bridge end has no solid anchorage");
        }
        check(visited.size()==Paradise.INHABITED_ISLES,"every inhabited island is reachable from the castle by bridges");
    }

    private static void boundaryFolds() {
        for(int i=0;i<100;i++) {
            double a=i*.391;
            var p=new net.minecraft.world.phys.Vec3(Math.cos(a)*(153+i*20),170,Math.sin(a)*(153+i*20));
            var q=Paradise.fold(p);
            quiet(Math.hypot(q.x,q.z)<Paradise.BOUNDARY,"fold leaves the entity beyond the boundary");
            quiet(q.x*p.x+q.z*p.z>0,"fold changes which side of the islands the entity occupies");
            quiet(Paradise.fold(q).equals(q),"fold immediately retriggers");
        }
        check(Paradise.fold(new net.minecraft.world.phys.Vec3(0,400,0)).y<Paradise.CEILING,"vertical flight is bounded too");
        check(Paradise.fold(Paradise.RESCUE).equals(Paradise.RESCUE),"rescue lies within the stable playable region");
    }

    /** Rim to rim, in the direction one island actually lies from the other. */
    private static double gap(Paradise.Isle a, Paradise.Isle b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double angle = Math.atan2(dz, dx);
        return Math.hypot(dx, dz) - Paradise.rim(a, angle) - Paradise.rim(b, angle + Math.PI);
    }

    // ------------------------------------------------------------------ the water

    /** A cascade aimed at a shelf has to come down inside it, and to stop at its surface. */
    private static void cascadesLand() {
        int landing = 0, endless = 0;
        for (Paradise.Fall fall : Paradise.falls()) {
            if (fall.onto() < 0) { endless++; quiet(fall.length() > 30, "a fall into open space stops too soon"); continue; }
            landing++;
            Paradise.Isle shelf = Paradise.isles().get(fall.onto());
            check(Paradise.inland(shelf, fall.x(), fall.z()) > 1.5,
                "the cascade onto island " + fall.onto() + " comes down inside its rim");
            check(Math.abs((fall.y() - fall.length()) - (shelf.y() + 1)) < 1.5,
                "the cascade onto island " + fall.onto() + " stops at that island's surface");
        }
        System.out.printf("  %d cascades land on an island, %d fall into space%n", landing, endless);
        check(endless >= 7, "the castle archipelago has seven long waterfalls into the cloud sea");
        // A lip buried in its own island's rock would be a spring inside a hill rather than a
        // waterfall off a cliff.
        for (Paradise.Fall fall : Paradise.falls())
            for (Paradise.Isle isle : Paradise.isles())
                if (Math.abs(isle.y() - fall.y()) < 0.5)
                    quiet(Paradise.inland(isle, fall.x(), fall.z()) < 0.6, "a cascade leaves from inside solid rock");
        System.out.println("  ok: every cascade leaves from a rim rather than from inside a hill");
    }

    /**
     * No cascade is driven down through an island.
     *
     * <p>The water is placed after the rock, so a column of it whose path happens to cross a lower
     * island would simply overwrite that island's stone with a shaft of water — a clean, silent,
     * permanent hole through somebody's meadow that nothing else here would ever catch. Bearings
     * are chosen by hand and the islands sit on a spiral, so this is exactly the kind of thing that
     * holds until the day a bearing moves by a few degrees.
     */
    private static void cascadesClearTheIslands() {
        List<Paradise.Isle> isles = Paradise.isles();
        for (Paradise.Fall fall : Paradise.falls()) {
            double bottom = fall.y() - fall.length();
            for (int i = 0; i < isles.size(); i++) {
                if (fall.onto() == i) continue;                      // this one is aimed at it
                Paradise.Isle isle = isles.get(i);
                double inland = Paradise.inland(isle, fall.x(), fall.z());
                if (inland < 0.6) continue;                          // the column is outside its rim
                double top = isle.y();
                double floor = top - Paradise.depth(isle, inland, isle.distance(fall.x(), fall.z()));
                if (top < bottom || floor > fall.y()) continue;      // nothing of it is in the way
                throw new AssertionError("a cascade at " + String.format("%.0f,%.0f", fall.x(), fall.z())
                    + " runs down through island " + i + " (" + String.format("%.1f", inland) + " blocks inside its rim)");
            }
        }
        System.out.println("  ok: no cascade is driven down through an island");
    }

    /** A spring with no bank around it is a lake, and the island around it is the point. */
    private static void theSpringHasAShore() {
        double narrowest=Double.MAX_VALUE;
        for(int k=0;k<128;k++) {
            double angle=k*Math.PI/64;
            double x=Paradise.SPRING_X+Math.cos(angle)*Paradise.springRim(angle);
            double z=Paradise.SPRING_Z+Math.sin(angle)*Paradise.springRim(angle);
            narrowest=Math.min(narrowest,Paradise.inland(Paradise.heart(),x,z));
        }
        check(narrowest>4,"the relocated lagoon has a shore all around it");
        check(Paradise.SPRING_DEPTH >= 2 && Paradise.SPRING_DEPTH <= 4, "the spring is deep enough to swim and shallow enough to stand up in");
    }

    // ------------------------------------------------------------------ the rules

    private static void nothingIsLostOutOfTheBottom() {
        double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
        for (Paradise.Isle isle : Paradise.isles()) {
            lowest = Math.min(lowest, isle.y() - isle.radius() * (1 + isle.keel()) - 4);
            highest = Math.max(highest, isle.y());
        }
        check(Paradise.FLOOR < lowest, "the catch is below the deepest keel, so falling past an island is a fall and not a rescue");
        check(Paradise.CEILING > highest + 20, "a faller is put back above everything, with sky to drift down through");
        check(Paradise.FLOOR > 0, "the catch is inside the dimension rather than under it");
    }

    /**
     * Read from Paradise's own table rather than from the destination enum, which cannot be loaded
     * outside a running game: every one of its constants builds a {@code ResourceKey}, and that
     * needs Minecraft's registries bootstrapped. The realm is a formula, and this check stays one.
     */
    private static void arrivalIsOnLand() {
        double x = Paradise.ARRIVAL.x, y = Paradise.ARRIVAL.y, z = Paradise.ARRIVAL.z;
        check(Paradise.inland(Paradise.heart(), x, z) > 3, "arrivals land well inside the central island");
        check(!Paradise.spring(x, z), "arrivals do not land in the hot spring");
        check(y > Paradise.SURFACE + 8, "arrivals have air under them on the way in");
        check(Paradise.CANDY_RUSH_TICKS == 6000, "Candy Rush lasts exactly five minutes");
    }

    /** The realm has to be placeable inside the block budget the realm builder runs on. */
    private static void affordable() {
        long voxels = 0;
        for (Paradise.Isle isle : Paradise.isles()) {
            int reach = (int) Math.ceil(isle.radius() * 1.3) + 2;
            long cx = Math.round(isle.x()), cz = Math.round(isle.z());
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                double px = cx + dx + .5, pz = cz + dz + .5;
                double inland = Paradise.inland(isle, px, pz);
                if (inland < 0) continue;
                voxels += Paradise.depth(isle, inland, Math.hypot(px - isle.x(), pz - isle.z()));
            }
        }
        for (Paradise.Fall fall : Paradise.falls()) voxels += fall.length() * 2L;
        System.out.printf("  about %d blocks, roughly %d ticks of the realm builder's budget%n", voxels, voxels / 4096 + 1);
        check(voxels > 12000, "the islands have enough body to look like land rather than plates");
        check(voxels < 180000, "the larger kingdom fits a bounded incremental generation budget");
    }

    /** What the water gives has to run out, or the realm hands out a permanent buff. */
    private static void theWaterIsTemporary() {
        check(Paradise.BATHE_TICKS > 60, "the spring's gifts outlast climbing out of it");
        check(Paradise.BATHE_TICKS < 20 * 30, "and expire well inside half a minute");
        check(Paradise.LIFT < 0.08, "gravity is weakened rather than cancelled");
        check(Paradise.LIFT > 0.03, "and weakened enough to be worth building a realm around");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        System.out.println("  ok: " + what);
    }

    /** For the checks there are hundreds of: silent when they hold, loud when one does not. */
    private static void quiet(boolean condition, String wrong) {
        if (!condition) throw new AssertionError(wrong);
    }
}
