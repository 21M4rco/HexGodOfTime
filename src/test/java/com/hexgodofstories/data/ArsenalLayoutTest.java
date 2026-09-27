package com.hexgodofstories.data;

/**
 * Offline checks of {@link ArsenalLayout}: the Crown of Barrels' timeline, its arch, its fire and its missiles'
 * flight. Run by Gradle's {@code verifyArsenalLayout}, with no Minecraft.
 */
public final class ArsenalLayoutTest {
    private ArsenalLayoutTest() { }

    public static void main(String[] args) {
        theTimelineRunsInOrder();
        everyGunIsFormedBeforeItFires();
        eachGunFiresAtItsOwnRate();
        theGunsFireOutOfStepInRaggedBursts();
        theRoundsSprayAboutTheMark();
        theArchIsSymmetricAndUncrowded();
        missilesFlyFromWhereTheyFormToWhereTheyAreSent();
        missilesWanderAndDoNotFlyTogether();
        missilesAreSlowAndSpeedUpSmoothly();
        gunsFormOutwardAndAimTrue();
        muzzlesAreWhereTheModelsSayTheyAre();
        gotchasGunFacesTheBackItShoots();
        System.out.println("ArsenalLayoutTest: the crown forms, fires out of step in ragged bursts about the mark and lets its missiles wander to it.");
    }

    private static void theTimelineRunsInOrder() {
        require(ArsenalLayout.FIRST_GUN < ArsenalLayout.RAISE, "the first gun forms while the arms are still rising");
        require(ArsenalLayout.FIRE_START > ArsenalLayout.RAISE, "the guns fire only once the arms are up");
        require(ArsenalLayout.LAUNCH == ArsenalLayout.HOLD && ArsenalLayout.HOLD == 260, "the missiles go at thirteen seconds");
        require(ArsenalLayout.FIRE == ArsenalLayout.FIRE_END - ArsenalLayout.FIRE_START && ArsenalLayout.FIRE > 160, "eight seconds and more of fire");
        require(ArsenalLayout.FIRE_END < ArsenalLayout.THROW && ArsenalLayout.THROW < ArsenalLayout.LAUNCH, "fire, then the throw, then the launch");
        require(ArsenalLayout.LAUNCH - ArsenalLayout.FIRE_END >= ArsenalLayout.MISSILE_REVEAL, "the missiles are whole before they go");
    }

    private static void everyGunIsFormedBeforeItFires() {
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            require(ArsenalLayout.formed(gun, ArsenalLayout.forms(gun)) == 0, "a gun has not formed before it begins to");
            require(ArsenalLayout.formed(gun, ArsenalLayout.FIRE_START) == 1, "gun " + gun + " is whole when the firing starts");
            require(ArsenalLayout.shots(gun, ArsenalLayout.FIRE_START - 1) == 0, "nothing fires before the firing starts");
            float last = -1;
            for (float t = 0; t <= ArsenalLayout.FIRE_START; t += .25f) {
                float f = ArsenalLayout.formed(gun, t);
                require(f >= last, "a gun only ever forms further");
                last = f;
            }
            require(ArsenalLayout.forms(gun) == ArsenalLayout.forms(ArsenalLayout.GUNS - 1 - gun), "the two sides of the arch form together");
        }
        require(ArsenalLayout.forms(ArsenalLayout.GUNS / 2) == ArsenalLayout.FIRST_GUN, "the top gun forms first");
        require(ArsenalLayout.unformed(0) == 1 && ArsenalLayout.unformed(ArsenalLayout.UNFORM) == 0, "a gun comes apart completely");
    }

    private static void eachGunFiresAtItsOwnRate() {
        int total = 0;
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            require(ArsenalLayout.rate(gun) < 1, "no gun fires twice in a tick");
            int fired = 0;
            for (int tick = 0; tick < ArsenalLayout.FIRE_END + 40; tick++) {
                int n = ArsenalLayout.shots(gun, tick);
                require(n == 0 || n == 1, "never more than a round a tick from one gun");
                require(n == 0 || tick >= ArsenalLayout.FIRE_START && tick < ArsenalLayout.FIRE_END, "rounds only while firing");
                fired += n;
            }
            double expected = ArsenalLayout.firing(gun, ArsenalLayout.FIRE_END) * ArsenalLayout.rate(gun);
            require(Math.abs(fired - expected) <= 1, "gun " + gun + " fires " + fired + " rounds, not about " + expected);
            total += fired;
            require(ArsenalLayout.sinceShot(gun, ArsenalLayout.FIRE_START - 1) == -1, "no flash before the first round");
            // Every round is fired when the count says it is, and a flash is timed from it.
            for (long k = 1; k <= fired; k++) {
                double at = ArsenalLayout.shotTime(gun, k);
                require(at >= ArsenalLayout.FIRE_START && at < ArsenalLayout.FIRE_END, "round " + k + " of gun " + gun + " within the fire");
                require(Math.floor(ArsenalLayout.rounds(gun, at + 1e-6)) == k && Math.floor(ArsenalLayout.rounds(gun, at - 1e-6)) == k - 1,
                    "round " + k + " of gun " + gun + " is fired at " + at);
                require(Math.abs(ArsenalLayout.sinceShot(gun, at + .5)) - .5 < 1e-6, "a flash is timed from its round");
            }
        }
        // Once every gun has opened up, no tick of the firing passes silent, and no two together are thin: ragged, but
        // the barrage never stutters to a stop.
        int last = -1;
        for (int tick = ArsenalLayout.FIRE_START + ArsenalLayout.OPENING + 1; tick < ArsenalLayout.FIRE_END; tick++) {
            int now = 0;
            for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) now += ArsenalLayout.shots(gun, tick);
            require(now >= 1, "a round every tick of the barrage, not none at " + tick);
            require(last < 0 || last + now >= 4, "at least four rounds every two ticks of the barrage, not " + (last + now) + " at " + tick);
            last = now;
        }
        require(total > 900, "a barrage: " + total + " rounds in eight seconds");
    }

    private static void theGunsFireOutOfStepInRaggedBursts() {
        java.util.Set<Integer> openings = new java.util.HashSet<>();
        java.util.Set<String> rhythms = new java.util.HashSet<>();
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            openings.add(ArsenalLayout.opens(gun));
            require(ArsenalLayout.opens(gun) >= ArsenalLayout.FIRE_START && ArsenalLayout.opens(gun) <= ArsenalLayout.FIRE_START + ArsenalLayout.OPENING,
                "gun " + gun + " opens up soon after the firing begins");
            // Bursts: stretches of the fire in which a gun fires nothing for a few ticks together.
            StringBuilder rhythm = new StringBuilder();
            int silent = 0, pauses = 0;
            for (int tick = ArsenalLayout.opens(gun); tick < ArsenalLayout.FIRE_END; tick++) {
                int n = ArsenalLayout.shots(gun, tick);
                rhythm.append(n);
                silent = n == 0 ? silent + 1 : 0;
                if (silent == 4) pauses++;
            }
            require(pauses >= 3, "gun " + gun + " fires in bursts, not " + pauses + " pauses");
            rhythms.add(rhythm.toString());
            double still = ArsenalLayout.sinceShot(gun, ArsenalLayout.FIRE_END - 1);
            require(still >= 0, "gun " + gun + " has fired by the end");
        }
        require(openings.size() >= 6, "the guns open up one after another, not " + openings.size() + " at once");
        require(rhythms.size() == ArsenalLayout.GUNS, "no two guns keep the same rhythm");
        // How many guns fire in a tick changes all the time: never a steady drum.
        int least = Integer.MAX_VALUE, most = 0;
        for (int tick = ArsenalLayout.FIRE_START + ArsenalLayout.OPENING + 1; tick < ArsenalLayout.FIRE_END; tick++) {
            int now = 0;
            for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) now += ArsenalLayout.shots(gun, tick);
            least = Math.min(least, now);
            most = Math.max(most, now);
        }
        require(most - least >= 5, "the barrage swells and thins, from " + least + " to " + most + " rounds a tick");
    }

    private static void theRoundsSprayAboutTheMark() {
        require(ArsenalLayout.SPREAD >= .02, "a round strays well off its line");
        double[] aim = {0, 1.6, 20};
        java.util.Set<String> spots = new java.util.HashSet<>();
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            double widest = 0;
            double[] last = ArsenalLayout.wander(gun, ArsenalLayout.FIRE_START);
            for (double t = ArsenalLayout.FIRE_START; t < ArsenalLayout.FIRE_END; t += .5) {
                double[] w = ArsenalLayout.wander(gun, t);
                require(Math.abs(w[0]) <= ArsenalLayout.WANDER + 1e-9 && Math.abs(w[1]) <= ArsenalLayout.WANDER + 1e-9, "a gun wanders only so far");
                require(Math.abs(w[0] - last[0]) < .004 && Math.abs(w[1] - last[1]) < .004, "a gun's aim drifts, it does not jump");
                widest = Math.max(widest, Math.hypot(w[0], w[1]));
                last = w;
            }
            require(widest > ArsenalLayout.WANDER * .4, "gun " + gun + " sprays about the mark");
            double[] own = ArsenalLayout.aimOf(gun, aim, ArsenalLayout.FIRE_START + 30);
            require(Math.abs(own[2] - aim[2]) < 1e-9 && distance(own, aim) < aim[2] * ArsenalLayout.WANDER * 1.5, "gun " + gun + " aims near the mark");
            spots.add(Math.round(own[0] * 20) + "," + Math.round(own[1] * 20));
        }
        require(spots.size() >= 10, "the guns do not all aim at one point, only " + spots.size() + " spots");
        require(ArsenalLayout.aimOf(0, null, 0) == null, "nothing to aim at, nothing aimed at");
    }

    private static void theArchIsSymmetricAndUncrowded() {
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            double[] a = ArsenalLayout.slot(gun), b = ArsenalLayout.slot(ArsenalLayout.GUNS - 1 - gun);
            require(Math.abs(a[0] + b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && Math.abs(a[2] - b[2]) < 1e-9, "the arch mirrors");
            require(ArsenalLayout.type(gun) == ArsenalLayout.type(ArsenalLayout.GUNS - 1 - gun), "and so do its guns");
            require(a[1] > .9 && a[1] < 2.7, "every gun between the hips and a block over the head: " + a[1]);
            require(a[2] < 0, "every gun behind the shoulders");
            if (gun > 0) {
                double[] c = ArsenalLayout.slot(gun - 1);
                double gap = Math.sqrt(Math.pow(a[0] - c[0], 2) + Math.pow(a[1] - c[1], 2) + Math.pow(a[2] - c[2], 2));
                require(gap > .3 && gap < .45, "neighbours are a gun's width apart, not " + gap);
            }
        }
        double[] top = ArsenalLayout.slot(ArsenalLayout.GUNS / 2);
        require(top[1] > 2.4, "the top of the arch is well above a head");
    }

    private static void missilesFlyFromWhereTheyFormToWhereTheyAreSent() {
        double[] right = {1, 0, 0};
        for (double far : new double[]{8, 25, 60, 140}) {
            double[] start = {-.65, 65.6, 0}, target = {3, 60, far};
            ArsenalLayout.Flight f = new ArsenalLayout.Flight(start, target, right, -1, 42);
            require(close(f.at(0), start, 1e-9), "it leaves from where it formed");
            require(close(f.at(f.duration), target, 1e-6), "it arrives where it was sent");
            require(f.progress(f.duration - 1) < 1, "and not before its time");
            double last = -1;
            for (double t = 0; t <= f.duration; t += .5) {
                double p = f.progress(t);
                require(p >= last - 1e-12, "it never flies backward");
                last = p;
            }
            ArsenalLayout.Flight again = new ArsenalLayout.Flight(start, target, right, -1, 42);
            for (double t = 0; t <= f.duration; t += 3.7)
                require(close(f.at(t), again.at(t), 0), "the same throw flies the same path everywhere it is worked out");
        }
    }

    private static void missilesWanderAndDoNotFlyTogether() {
        double[] right = {1, 0, 0}, target = {0, 64, 40};
        ArsenalLayout.Flight left = new ArsenalLayout.Flight(new double[]{-.65, 65.6, 0}, target, right, -1, 7);
        ArsenalLayout.Flight rightOne = new ArsenalLayout.Flight(new double[]{.65, 65.6, 0}, target, right, 1, 7);
        double wander = 0, apart = 0;
        for (int i = 1; i < 100; i++) {
            double u = i / 100.0;
            double[] p = left.point(u);
            // Off the straight line from where it formed to the target.
            double[] a = {-.65, 65.6, 0}, d = {target[0] - a[0], target[1] - a[1], target[2] - a[2]};
            double dl = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            double[] w = {p[0] - a[0], p[1] - a[1], p[2] - a[2]};
            double along = (w[0] * d[0] + w[1] * d[1] + w[2] * d[2]) / dl;
            double off = Math.sqrt(Math.max(0, w[0] * w[0] + w[1] * w[1] + w[2] * w[2] - along * along));
            wander = Math.max(wander, off);
            double[] q = rightOne.point(u);
            apart = Math.max(apart, Math.sqrt(Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2) + Math.pow(p[2] - q[2], 2)));
        }
        require(wander > 4, "a missile curves well off the straight line: " + wander);
        require(apart > 4, "the two missiles take their own ways: " + apart);
    }

    private static void missilesAreSlowAndSpeedUpSmoothly() {
        ArsenalLayout.Flight f = new ArsenalLayout.Flight(new double[]{0, 65, 0}, new double[]{0, 64, 50}, new double[]{1, 0, 0}, 1, 3);
        require(f.cruise < 1.2, "slow: a cruise of " + f.cruise + " blocks a tick");
        require(f.duration > 50, "it takes its time over fifty blocks: " + f.duration + " ticks");
        double last = 0;
        for (double t = .25; t <= f.duration; t += .25) {
            double v = (f.travelled(t) - f.travelled(t - .25)) / .25;
            require(v >= last - 1e-9 && v <= f.cruise + 1e-9, "it only ever speeds up, to its cruise");
            last = v;
        }
        require(f.travelled(1) - f.travelled(0) < .15, "it leaves at a crawl");
    }

    private static void gunsFormOutwardAndAimTrue() {
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            double[] slot = ArsenalLayout.slot(gun), out = ArsenalLayout.out(gun);
            ArsenalLayout.Pose born = ArsenalLayout.pose(slot, out, null, 0, 0);
            require(dot(born.forward(), out) > .999, "a gun comes into being pointing out from the arch");
            require(distance(born.at(), slot) > .3, "and nearer the middle than its place");
            ArsenalLayout.Pose rest = ArsenalLayout.pose(slot, out, null, 1, 0);
            require(distance(rest.at(), slot) < 1e-9, "formed, it is in its place");
            require(rest.forward()[2] > .9, "and faces ahead, splayed a little");
            double[] aim = {3, 1.5, 30};
            ArsenalLayout.Pose aimed = ArsenalLayout.pose(slot, out, aim, 1, 1);
            double[] to = {aim[0] - slot[0], aim[1] - slot[1], aim[2] - slot[2]};
            double l = Math.sqrt(dot(to, to));
            require(dot(aimed.forward(), new double[]{to[0] / l, to[1] / l, to[2] / l}) > .99999, "aimed, it points at the mark");
            for (ArsenalLayout.Pose p : new ArsenalLayout.Pose[]{born, rest, aimed}) {
                require(Math.abs(dot(p.forward(), p.up())) < 1e-9 && Math.abs(dot(p.forward(), p.right())) < 1e-9
                    && Math.abs(dot(p.up(), p.right())) < 1e-9, "a gun's frame is square");
                // Not mirrored. The caster's frame (right, up, forward) is left-handed, facing south a right hand points
                // west, so worked in its coordinates a true right, top and muzzle make right x top = +forward; the model's
                // own +x right, +y top and +z back then land in the world as a rotation, never a reflection.
                require(dot(cross(p.right(), p.up()), p.forward()) > .999, "and is not mirrored");
            }
            require(born.up()[2] < -.999, "born, its top faces back, to pitch forward into place");
            require(dot(rest.up(), out) > .9, "at rest, its top faces out from the arch");
            require(dot(aimed.up(), out) > .5, "and still does, aimed");
            // It never rolls on the way: its right side keeps to the arch's plane of turning.
            double[] lastRight = born.right();
            for (float f = .05f; f <= 1; f += .05f) {
                ArsenalLayout.Pose p = ArsenalLayout.pose(slot, out, null, f, 0);
                require(dot(p.right(), lastRight) > .99, "a forming gun turns smoothly, without flipping over");
                lastRight = p.right();
            }
        }
    }

    private static void gotchasGunFacesTheBackItShoots() {
        require(0 < ArsenalLayout.SNEAK_FORM && ArsenalLayout.SNEAK_FORM <= ArsenalLayout.SNEAK_FIRE
            && ArsenalLayout.SNEAK_FIRE < ArsenalLayout.SNEAK_GONE, "Gotcha!'s gun is whole before it fires, and fires before it goes");
        double[][] aims = {{0, 1, 5}, {4, 0, -3}, {-2, 3, 1}, {0, -6, 0}, {0, 6, .001}};
        for (double[] aim : aims) {
            ArsenalLayout.Pose pose = ArsenalLayout.aimed(new double[]{0, 0, 0}, aim);
            double[] to = aim.clone();
            double l = Math.sqrt(dot(to, to));
            require(Math.abs(dot(pose.forward(), to) / l - 1) < 1e-9, "Gotcha!'s gun points at the back it shoots");
            require(Math.abs(dot(pose.forward(), pose.up())) < 1e-9 && Math.abs(dot(pose.forward(), pose.right())) < 1e-9
                && Math.abs(dot(pose.up(), pose.right())) < 1e-9, "its axes are square to each other");
            require(Math.abs(dot(pose.up(), pose.up()) - 1) < 1e-9 && Math.abs(dot(pose.right(), pose.right()) - 1) < 1e-9, "and of unit length");
            // In the world's right-handed coordinates a right hand is the muzzle's way crossed with the top's.
            require(close(cross(pose.forward(), pose.up()), pose.right(), 1e-9), "its right side is a true right side");
            double[] muzzle = pose.point(ArsenalLayout.MUZZLE[ArsenalLayout.SNEAK_TYPE], ArsenalLayout.GUN_SCALE);
            require(dot(muzzle, to) / l > .4, "its muzzle is the end nearer the back");
        }
        // Facing south with the top up, a right hand points west.
        ArsenalLayout.Pose south = ArsenalLayout.aimed(new double[]{0, 0, 0}, new double[]{0, 0, 10});
        require(close(south.right(), new double[]{-1, 0, 0}, 1e-9) && close(south.up(), new double[]{0, 1, 0}, 1e-9), "upright, right hand west");
    }

    private static void muzzlesAreWhereTheModelsSayTheyAre() {
        String[] models = {"m249", "rpk", "fn_evolys"};
        for (int type = 0; type < models.length; type++) {
            float[] head = meshHead("src/main/resources/assets/hexgodofstories/arsenal/" + models[type] + ".mesh");
            for (int i = 0; i < 3; i++) {
                double middle = (head[6 + i] + head[9 + i]) / 2;
                require(Math.abs(head[i] - middle - ArsenalLayout.MUZZLE[type][i]) < 1e-3, models[type] + "'s muzzle is where its model has it");
            }
        }
    }

    /** The twelve floats after a mesh's count: muzzle, shell port, bounds low, bounds high. */
    private static float[] meshHead(String path) {
        try (var in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(path))) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(in.readNBytes(12 + 48)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            require(b.getInt() == 0x4d415848, "a mesh file");
            b.getInt();
            b.getInt();
            float[] head = new float[12];
            for (int i = 0; i < 12; i++) head[i] = b.getFloat();
            return head;
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    private static double dot(double[] a, double[] b) {return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];}
    private static double distance(double[] a, double[] b) {return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));}
    private static double[] cross(double[] a, double[] b) {return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};}

    private static boolean close(double[] a, double[] b, double eps) {
        return Math.abs(a[0] - b[0]) <= eps && Math.abs(a[1] - b[1]) <= eps && Math.abs(a[2] - b[2]) <= eps;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
