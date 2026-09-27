package com.hexgodofstories.data;

/**
 * The Crown of Barrels as numbers, shared by the server that fires it and every client that draws it: when each
 * part of it happens, where each gun hangs in the arch, when each one fires, and the path each missile flies.
 * Nothing here needs Minecraft, so all of it is checked offline ({@code verifyArsenalLayout}).
 *
 * <p>Space. A gun's slot is given in the caster's body frame: x to the caster's right, y up from their feet, z
 * the way their body faces. The first-person arch is given in the camera's frame: x right, y up and z the way
 * the camera looks, from the eye. Both are left-handed in the world's right-handed coordinates (facing south, a
 * right hand points west), and every cross product here is taken with that in mind; a point {@code (x, y, z)} of
 * either frame is simply {@code origin + x right + y up + z forward} in the world. A missile flies in the world's
 * own coordinates.
 */
public final class ArsenalLayout {
    private ArsenalLayout() { }

    // ------------------------------------------------------------------ when (ticks since the hold began)

    /** Guns in the arch: one at its top, and seven down each side. */
    public static final int GUNS = 15;
    /** Ticks for both arms to come up. */
    public static final int RAISE = 16;
    /**
     * The top gun begins to form a little into the raise; each ring of the arch outward from it follows {@link
     * #STAGGER} ticks later, and each gun takes {@link #REVEAL} ticks to form.
     */
    public static final int FIRST_GUN = 6, STAGGER = 5, REVEAL = 26;
    /** Every gun formed, and firing: from here for {@link #FIRE} ticks, twenty seconds, for as long as it is held. */
    public static final int FIRE_START = FIRST_GUN + (GUNS / 2) * STAGGER + REVEAL, FIRE = 400, FIRE_END = FIRE_START + FIRE;
    /**
     * Held to the end: the guns come apart while the two missiles form beside the head, over {@link
     * #MISSILE_REVEAL} ticks; the arms are thrown down at {@link #THROW}, and the missiles go at {@link #LAUNCH}.
     */
    public static final int MISSILE_REVEAL = 20, THROW = FIRE_END + 23, LAUNCH = FIRE_END + 26;
    /** Ticks for a gun to come apart, when the hold ends or is let go. */
    public static final int UNFORM = 14;

    /** Rings of the arch: 0 at its top, {@code GUNS / 2} at its two ends. */
    public static int ring(int gun) {return Math.abs(gun - GUNS / 2);}

    /** When a gun begins to form, in ticks since the hold began. */
    public static int forms(int gun) {return FIRST_GUN + ring(gun) * STAGGER;}

    /** How much of a gun has formed, from 0 to 1, {@code t} ticks into the hold. */
    public static float formed(int gun, float t) {return clamp((t - forms(gun)) / REVEAL);}

    /** How much of a gun is still there {@code t} ticks after it began to come apart, from 1 to 0. */
    public static float unformed(float t) {return 1 - clamp(t / UNFORM);}

    // ------------------------------------------------------------------ which guns, and when they fire

    /** A gun's make: 0 an M249, 1 an RPK, 2 an FN Evolys; alternating down each side from the top, so the arch mirrors. */
    public static int type(int gun) {return ring(gun) % 3;}

    /** Rounds a minute for each make, as TACZ gives them: the M249's 750, the RPK's 630, the Evolys's 750. */
    public static final float[] RPM = {750, 630, 750};

    /** Where in its cycle a gun starts, so that fifteen guns never fire in step: spread by the golden ratio. */
    public static double phase(int gun) {return gun * .6180339887 % 1;}

    /** Rounds a gun has fired by {@code t} ticks into the hold, counting fractions: 0 before the firing begins. */
    public static double rounds(int gun, double t) {
        double since = Math.min(t, FIRE_END) - FIRE_START;
        return since <= 0 ? 0 : since * RPM[type(gun)] / 1200 + phase(gun);
    }

    /** Rounds a gun fires in tick {@code tick} of the hold (from it to the next): 0 or 1, as none fires faster than once a tick. */
    public static int shots(int gun, int tick) {return (int) (Math.floor(rounds(gun, tick + 1)) - Math.floor(rounds(gun, tick)));}

    /**
     * How long ago, in ticks, a gun last fired at {@code t} ticks into the hold, or -1 if it has not fired yet: what a
     * muzzle flash and a recoil are timed from.
     */
    public static double sinceShot(int gun, double t) {
        double r = rounds(gun, t);
        if (Math.floor(r) < 1 || t > FIRE_END + 2) return -1;
        return (r - Math.floor(r)) * 1200 / RPM[type(gun)];
    }

    // ------------------------------------------------------------------ where the guns hang

    /** The arch: part of an ellipse behind the shoulders, its top above the head and its ends at the hips. */
    public static final double ARC = Math.toRadians(100), WIDE = 1.35, TALL = 1.30, MIDDLE = 1.30, BEHIND = -.30, LEAN = .15;
    /** The same arch for a caster in first person: in front of the eye, framing the top of the view. */
    public static final double FIRST_WIDE = 1.68, FIRST_TALL = 1.10, FIRST_MIDDLE = -.30, FIRST_AHEAD = 1.60;
    /** How far a gun rests splayed outward from straight ahead before it has anything to aim at, like the rays of a halo. */
    public static final double SPLAY = .35;

    /** A gun's angle round the arch: 0 at the top, negative to the caster's left. */
    public static double angle(int gun) {return (gun - GUNS / 2) / (double) (GUNS / 2) * ARC;}

    /** A gun's place in the caster's body frame: x right, y up from the feet, z forward. The top leans a little back. */
    public static double[] slot(int gun) {
        double a = angle(gun);
        return new double[]{WIDE * Math.sin(a), MIDDLE + TALL * Math.cos(a), BEHIND - LEAN * Math.cos(a)};
    }

    /** A first-person gun's place in the camera's frame: x right, y up and z ahead of the eye. */
    public static double[] firstPersonSlot(int gun) {
        double a = angle(gun);
        return new double[]{FIRST_WIDE * Math.sin(a), FIRST_MIDDLE + FIRST_TALL * Math.cos(a), FIRST_AHEAD - .25 * Math.cos(a)};
    }

    /** Outward from the middle of the arch, in its plane: the way a gun's top faces, its magazine toward the caster. */
    public static double[] out(int gun) {
        double a = angle(gun);
        return new double[]{Math.sin(a), Math.cos(a), 0};
    }

    // ------------------------------------------------------------------ how the guns are posed

    /** Blocks to a model unit: TACZ's guns, some three units long as authored, hang about a block and a fifth long. */
    public static final double GUN_SCALE = .40, FIRST_PERSON_SCALE = .27, MISSILE_SCALE = 1.10;
    /**
     * Each make's muzzle, where its rounds leave, from the middle of its model in model units (+x its right, +y its
     * top, -z its muzzle end): TACZ's own muzzle flash locator. Checked against the models by verifyArsenalLayout.
     */
    public static final double[][] MUZZLE = {{-.0156, .1442, -1.4264}, {-.0015, .2620, -1.6459}, {0, .2233, -1.4003}};
    /** How much nearer the middle of the arch a gun comes into being than where it settles. */
    public static final double DRIFT = .35;

    /** A gun's pose: where its middle is, and the ways its muzzle, its top and its right side face, in its slot's frame. */
    public record Pose(double[] at, double[] forward, double[] up, double[] right) {
        /** A point of the gun's model, given from the model's middle in model units, in the slot's frame. */
        public double[] point(double[] model, double scale) {
            return add(at, add(add(scale(right, model[0] * scale), scale(up, model[1] * scale)), scale(forward, -model[2] * scale)));
        }
    }

    /**
     * Where a gun is and which way it faces, in the frame its slot is given in (straight ahead is +z in both).
     *
     * <p>A gun comes into being pointing straight out from the arch, like a ray of a halo, its top to the back, a
     * little nearer its middle than its slot; as it forms it drifts out into place and pitches forward to rest,
     * splayed a little outward from straight ahead, its top turned out from the arch, so that round the whole arch
     * every magazine points in toward the caster. Once there is something to aim at, it swings onto it. Its top is
     * carried round with every turn, so it never rolls on its own.
     *
     * @param slot   the gun's place, {@link #slot} or {@link #firstPersonSlot}
     * @param out    {@link #out} for the same gun
     * @param aim    the point aimed at, in the same frame; null while there is none
     * @param formed how much of the gun has formed, {@link #formed}
     * @param aiming how far it has swung onto the aim, from 0 to 1
     */
    public static Pose pose(double[] slot, double[] out, double[] aim, float formed, float aiming) {
        double[] ahead = {0, 0, 1};
        double[] rest = norm(add(ahead, scale(out, SPLAY)));
        double f = clamp(formed), drift = 1 - f;
        double[] at = sub(slot, scale(out, DRIFT * drift * drift * drift));
        double[] facing = slerp(out, rest, smooth(f));
        if (aim != null && aiming > 0) {
            double[] to = sub(aim, at);
            if (len(to) > 1e-6) facing = slerp(facing, norm(to), smooth(clamp(aiming)));
        }
        // The top it was born with, facing back, turned the way the muzzle was turned from pointing out.
        double[] top = turn(new double[]{0, 0, -1}, out, facing);
        top = sub(top, scale(facing, dot(top, facing)));
        if (len(top) < 1e-6) top = new double[]{0, 1, 0};
        // In this left-handed frame a right-hand side is the top crossed with the muzzle.
        double[] right = norm(cross(top, facing));
        double[] up = norm(cross(facing, right));
        return new Pose(at, facing, up, right);
    }

    /** {@code v} turned by the least turn that takes unit {@code from} to unit {@code to}. */
    static double[] turn(double[] v, double[] from, double[] to) {
        double c = dot(from, to);
        double[] k = cross(from, to);
        double s = len(k);
        if (s < 1e-9) {
            if (c > 0) return v.clone();
            // Half a turn about anything square to them.
            double[] axis = norm(cross(from, Math.abs(from[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0}));
            return sub(scale(axis, 2 * dot(axis, v)), v);
        }
        double[] axis = scale(k, 1 / s);
        return add(add(scale(v, c), scale(cross(axis, v), s)), scale(axis, dot(axis, v) * (1 - c)));
    }

    /** Between two unit vectors along the great circle, {@code t} of the way. */
    static double[] slerp(double[] a, double[] b, double t) {
        double c = Math.max(-1, Math.min(1, dot(a, b)));
        if (c > .9995) return norm(add(scale(a, 1 - t), scale(b, t)));
        double[] bb = b;
        if (c < -.9995) {
            // Opposite: turn about anything square to them.
            double[] axis = cross(a, Math.abs(a[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0});
            bb = norm(axis);
            c = 0;
            t *= 2;
            if (t > 1) return slerp(bb, b, t - 1);
        }
        double theta = Math.acos(c), s = Math.sin(theta);
        return norm(add(scale(a, Math.sin((1 - t) * theta) / s), scale(bb, Math.sin(t * theta) / s)));
    }

    /** Eased in and out. */
    static double smooth(double x) {return x * x * (3 - 2 * x);}

    // ------------------------------------------------------------------ the missiles

    /** How far out beside the head the two missiles form (the caster's right, times -1 or 1), and how high. */
    public static final double MISSILE_SIDE = .65, MISSILE_HEIGHT = 1.62;
    /** A missile's speed as it leaves, and how long it takes to come up to its cruise, in blocks a tick and ticks. */
    public static final double LAUNCH_SPEED = .08;
    public static final int ACCELERATION = 30;
    /** Samples the path is measured at, to fly it at the speed it is meant to be flown. */
    private static final int SAMPLES = 160;

    /**
     * One missile's flight, from where it formed to the point the caster aimed at: never straight. It climbs out to
     * its own side, swings across and comes down on the target from the other, and all the way it weaves and
     * corkscrews on its line, widest in the middle of the flight and settling onto the target at the end. The two
     * missiles bow out to opposite sides and weave out of phase, so they cross and wind about each other. Slow: they
     * leave at a crawl, pushed up to their cruise over a second and a half, and even that is a stately pace.
     *
     * <p>Built the same from the same numbers wherever it is built, so the server's missile and every client's are
     * the same missile without a word passing between them in flight.
     */
    public static final class Flight {
        private final double[] start, target, right, ahead, up = {0, 1, 0};
        private final double distance, bow, cross, rise, fall, weave, twist, turns, laps, spin, shift;
        private final int side;
        private final double[] lengths = new double[SAMPLES + 1];
        /** How fast it cruises, in blocks a tick; how long the whole path is; and the ticks it takes to fly it. */
        public final double cruise, length;
        public final int duration;

        /**
         * @param start  where it formed
         * @param target the point aimed at
         * @param right  the caster's right, level, when it was thrown
         * @param side   which side it formed on: -1 left, 1 right
         * @param seed   anything, so that no two throws weave alike
         */
        public Flight(double[] start, double[] target, double[] right, int side, long seed) {
            this.start = start.clone();
            this.target = target.clone();
            this.side = side;
            double[] d = sub(target, start);
            distance = Math.max(1e-3, len(d));
            ahead = scale(d, 1 / distance);
            double[] r = sub(right, scale(ahead, dot(right, ahead)));
            this.right = len(r) < 1e-3 ? norm(cross(ahead, Math.abs(ahead[1]) < .9 ? up : new double[]{1, 0, 0})) : norm(r);
            // The shape of it, scaled to the distance so a near target is not overshot and a far one is worth watching.
            bow = 1.5 + .10 * distance;
            cross = 1.0 + .08 * distance;
            rise = 2.5 + .10 * distance;
            fall = 1.5 + .12 * distance;
            weave = Math.min(3.2, .8 + .06 * distance);
            twist = Math.min(.9, .25 + .02 * distance);
            turns = 1.25 + distance / 60;
            laps = 2.1 + distance / 45;
            spin = 3.0 + distance / 30;
            java.util.Random random = new java.util.Random(seed * 31 + side);
            shift = random.nextDouble() * Math.PI * 2;
            cruise = .75 + distance / 200;
            double total = 0;
            double[] last = point(0);
            for (int i = 1; i <= SAMPLES; i++) {
                double[] next = point(i / (double) SAMPLES);
                total += len(sub(next, last));
                lengths[i] = total;
                last = next;
            }
            length = total;
            duration = (int) Math.ceil(ticksToFly(length));
        }

        /** The path itself, from 0 (where it formed) to 1 (the target). */
        public double[] point(double u) {
            double[] base = bezier(u);
            double[] along = norm(bezierTangent(u));
            double[] n1 = cross(along, up);
            n1 = len(n1) < 1e-3 ? right.clone() : norm(n1);
            double[] n2 = cross(n1, along);
            // Widest in the middle, nothing at either end: it leaves the caster cleanly and lands where it was sent.
            double envelope = Math.pow(Math.sin(Math.PI * u), 1.2);
            double a = weave * Math.sin(2 * Math.PI * turns * u + shift + (side > 0 ? 0 : Math.PI));
            double b = .55 * weave * Math.sin(2 * Math.PI * laps * u + shift * 1.7);
            double c = twist * Math.cos(2 * Math.PI * spin * u * side), s = twist * Math.sin(2 * Math.PI * spin * u * side);
            return add(base, scale(add(scale(n1, a + c), scale(n2, b + s)), envelope));
        }

        /** Where it is {@code ticks} after launch, fractions and all; at the target once it has arrived. */
        public double[] at(double ticks) {return point(progress(ticks));}

        /** Which way it is heading {@code ticks} after launch, as a unit vector. */
        public double[] heading(double ticks) {
            double u = progress(ticks), h = 1.0 / SAMPLES;
            double[] a = point(Math.max(0, u - h)), b = point(Math.min(1, u + h));
            double[] d = sub(b, a);
            return len(d) < 1e-9 ? ahead.clone() : norm(d);
        }

        /** How far along the path it is, from 0 to 1, {@code ticks} after launch. */
        public double progress(double ticks) {
            double travelled = travelled(ticks);
            if (travelled >= length) return 1;
            int lo = 0, hi = SAMPLES;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (lengths[mid] < travelled) lo = mid;
                else hi = mid;
            }
            double span = lengths[hi] - lengths[lo];
            return (lo + (span < 1e-9 ? 0 : (travelled - lengths[lo]) / span)) / SAMPLES;
        }

        /** Blocks flown {@code ticks} after launch: easing from {@link #LAUNCH_SPEED} up to the cruise, then steady. */
        public double travelled(double ticks) {
            if (ticks <= 0) return 0;
            double gain = cruise - LAUNCH_SPEED;
            if (ticks <= ACCELERATION) {
                double x = ticks / ACCELERATION;
                return LAUNCH_SPEED * ticks + gain * ACCELERATION * (x * x * x - x * x * x * x / 2);
            }
            return LAUNCH_SPEED * ACCELERATION + gain * ACCELERATION / 2 + cruise * (ticks - ACCELERATION);
        }

        private double ticksToFly(double blocks) {
            double ramp = travelled(ACCELERATION);
            if (blocks > ramp) return ACCELERATION + (blocks - ramp) / cruise;
            double lo = 0, hi = ACCELERATION;
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                if (travelled(mid) < blocks) lo = mid;
                else hi = mid;
            }
            return hi;
        }

        private double[][] controls() {
            double reach = distance;
            double[] p1 = add(start, add(add(scale(up, rise), scale(right, side * bow)), scale(ahead, .15 * reach)));
            double[] p2 = add(target, add(add(scale(up, fall), scale(right, -side * cross)), scale(ahead, -.2 * reach)));
            return new double[][]{start, p1, p2, target};
        }

        private double[] bezier(double u) {
            double[][] p = controls();
            double v = 1 - u;
            return add(add(scale(p[0], v * v * v), scale(p[1], 3 * v * v * u)), add(scale(p[2], 3 * v * u * u), scale(p[3], u * u * u)));
        }

        private double[] bezierTangent(double u) {
            double[][] p = controls();
            double v = 1 - u;
            return add(add(scale(sub(p[1], p[0]), 3 * v * v), scale(sub(p[2], p[1]), 6 * v * u)), scale(sub(p[3], p[2]), 3 * u * u));
        }
    }

    // ------------------------------------------------------------------ small vector arithmetic, on double[3]

    static double[] add(double[] a, double[] b) {return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};}
    static double[] sub(double[] a, double[] b) {return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};}
    static double[] scale(double[] a, double k) {return new double[]{a[0] * k, a[1] * k, a[2] * k};}
    static double dot(double[] a, double[] b) {return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];}
    static double len(double[] a) {return Math.sqrt(dot(a, a));}
    static double[] norm(double[] a) {double l = len(a); return l < 1e-12 ? new double[]{0, 0, 0} : scale(a, 1 / l);}
    static double[] cross(double[] a, double[] b) {return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};}

    private static float clamp(float v) {return v < 0 ? 0 : v > 1 ? 1 : v;}
    private static double clamp(double v) {return v < 0 ? 0 : v > 1 ? 1 : v;}
}
