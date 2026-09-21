package com.hexgodofstories.warping.leviathan;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Fully three dimensional steering for a body that weighs as much as a small island.
 *
 * <p>Turning is rate limited rather than instant, and the limit scales with how committed the
 * creature currently is: drifting turns are a degree or so per tick, an attack turn is allowed to
 * be violent. Speed is bled off during hard turns so momentum reads as mass. Above the waterline
 * steering authority collapses and the motion becomes ballistic, which is what makes a breach arc
 * look like a thrown body instead of a flying one.
 *
 * <p>Every turn is additionally bounded by a minimum radius. Degrees per tick alone is the wrong
 * limit for something this long: a generous allowance at attack speed is a graceful arc, and the
 * same allowance while barely moving is a pirouette that drags a hundred and twenty six blocks of
 * body into a spiral. What the spine can follow is a radius, so a radius is what is enforced, and
 * a target sitting inside that radius is carved past rather than spun at.
 */
public final class LeviathanMoveControl extends MoveControl {
    /** Blocks per tick squared once the body is clear of the water. */
    public static final double GRAVITY = 0.075;
    private static final double WATER_DRAG = 0.91;
    private static final double AIR_DRAG = 0.985;
    /**
     * Blocks. Tighter than this and the body cannot keep up with its own head: the joint limits in
     * {@link LeviathanSegmentController} would clamp the spine away from the path every tick, which
     * reads as the creature sliding sideways through its own turn. Fifty is what the seven degree
     * limit at the widest part of the hull actually permits, so the two agree and the clamp is a
     * backstop rather than something the steering fights every tick.
     */
    private static final double TURN_RADIUS = 50.0;

    /**
     * Every speed the behaviour asks for is multiplied by this before it is used.
     *
     * <p>The patterns were written in blocks per tick without much regard for how big the thing
     * carrying them is, and at a hundred and fifty blocks long the result was a creature crossing
     * sixty blocks a second: ten times a sprinting player, which reads as a torpedo rather than as
     * something enormous. Scaling here rather than at thirty call sites keeps every pattern's
     * relative pacing exactly as it was written and makes the weight one number to tune.
     *
     * <p>It leaves a cruise near a walking pace, a committed hunt at two to four times that, and a
     * lunge in the thirties. Leaps are unaffected: those are solved ballistics, not swimming.
     */
    private static final double SCALE = 0.35;

    private final AbyssalPilgrimEntity leviathan;
    private Vec3 wanted = Vec3.ZERO;
    private double speed = 0.4;
    /** 0 is a lazy drift, 1 is an attack turn. */
    private float authority = 0.25f;
    private boolean active;
    private boolean allowAir;
    private double burst;
    private double airSteer;
    private float rollIntent;
    private int ballistic;
    @Nullable private Vec3 launchImpulse;

    public LeviathanMoveControl(AbyssalPilgrimEntity leviathan) {
        super(leviathan);
        this.leviathan = leviathan;
    }

    /**
     * The creature's own urgency is applied here rather than at the thirty call sites that ask for
     * a speed, for the same reason {@link #SCALE} is: every pattern keeps the pacing it was
     * written with, and a busier ocean simply runs all of it harder. The turning radius is
     * deliberately not touched — it covers ground faster, it does not corner tighter.
     */
    public void moveTo(Vec3 target, double blocksPerTick, float turnAuthority) {
        this.wanted = target;
        this.speed = blocksPerTick * SCALE * leviathan.urgency();
        this.authority = Mth.clamp(turnAuthority, 0.02f, 1.0f);
        this.active = true;
        super.setWantedPosition(target.x, target.y, target.z, this.speed);
    }

    public void stopMoving() { this.active = false; this.burst = 0; this.airSteer = 0; this.ballistic = 0; this.launchImpulse = null; }

    /**
     * Throws the whole creature along {@code velocity} and refuses to steer for {@code commit}
     * ticks of the climb.
     *
     * <p>A leap cannot be built out of the ordinary swimming terms. Those are written to bleed
     * speed into a heading over many ticks and to cap the result, which is right for a body with
     * this much water in front of it and completely wrong for the one moment it stops swimming and
     * starts being thrown. From here until the surface, physics owns it.
     */
    public void launch(Vec3 velocity, int commit) {
        this.launchImpulse = velocity;
        this.ballistic = Math.max(1, commit);
        this.allowAir = true;
        this.active = true;
    }
    public void setAllowAir(boolean allow) { this.allowAir = allow; if (!allow) this.airSteer = 0; }
    /** Blocks per tick of horizontal correction permitted mid leap. Zero is a pure ballistic arc. */
    public void setAirSteer(double amount) { this.airSteer = Math.max(0, amount); }
    /** One off forward impulse, used by lunges, deep charges and breach launches. */
    public void addBurst(double amount) { this.burst = Math.max(this.burst, amount * SCALE * leviathan.urgency() * 0.6); }
    public void setRollIntent(float degrees) { this.rollIntent = degrees; }
    public Vec3 wanted() { return wanted; }
    public boolean active() { return active; }

    @Override
    public void tick() {
        boolean submerged = leviathan.isSubmerged();
        Vec3 motion = leviathan.getDeltaMovement();

        if (launchImpulse != null) {
            leviathan.setDeltaMovement(launchImpulse);
            orientToMotion(launchImpulse, 0.5f);
            launchImpulse = null;
            return;
        }
        if (ballistic > 0 && submerged) {
            // Still climbing through water on the launch impulse, and still driving it with the
            // tail, so the column above barely costs anything. Nothing steers it out of this.
            ballistic--;
            leviathan.setDeltaMovement(motion.scale(0.998));
            orientToMotion(motion, 0.3f);
            return;
        }
        ballistic = 0;

        if (!submerged) {
            // Airborne. Gravity owns the arc; the creature may only twist along it.
            motion = motion.add(0, -GRAVITY, 0).scale(AIR_DRAG);
            if (airSteer > 0 && active) {
                // One tail flick's worth of lateral correction, so a leap aimed at a flying target
                // can still meet it. Horizontal only: the arc's height was decided at the launch.
                Vec3 toward = wanted.subtract(leviathan.position());
                double flat = Math.sqrt(toward.x * toward.x + toward.z * toward.z);
                if (flat > 1.0E-4) motion = motion.add(toward.x / flat * airSteer, 0, toward.z / flat * airSteer);
            }
            leviathan.setDeltaMovement(motion);
            orientToMotion(motion, 0.35f);
            return;
        }

        if (!active) {
            leviathan.setDeltaMovement(motion.scale(0.94));
            orientToMotion(leviathan.getDeltaMovement(), 0.08f);
            return;
        }

        Vec3 toward = wanted.subtract(leviathan.position());
        double distance = toward.length();
        if (distance < 1.0E-4) { leviathan.setDeltaMovement(motion.scale(WATER_DRAG)); return; }
        Vec3 desired = toward.scale(1.0 / distance);

        // Nothing can turn toward a point inside its own turning circle. Attempting it is what a
        // pirouette is; a predator instead carves past and comes back around on the next pass.
        double reachable = TURN_RADIUS * 0.6;
        if (distance < reachable) {
            Vec3 ahead = leviathan.getLookAngle();
            double blend = distance / reachable;
            Vec3 carved = ahead.scale(1.0 - blend).add(desired.scale(blend));
            desired = carved.lengthSqr() < 1.0E-8 ? ahead : carved.normalize();
        }

        float wantYaw = (float) (Mth.atan2(-desired.x, desired.z) * Mth.RAD_TO_DEG);
        float wantPitch = (float) (-Math.asin(Mth.clamp(desired.y, -1, 1)) * Mth.RAD_TO_DEG);

        // A body this long cannot snap around. Authority buys degrees per tick, nothing more.
        float maxYaw = 0.9f + 8.6f * authority * authority;
        float maxPitch = 0.7f + 6.4f * authority * authority;
        // ...and degrees per tick are then bought with speed, because a turn is an arc: at one
        // block per tick a thirty block circle is worth just under two degrees of heading.
        float arc = (float) (Math.max(motion.length(), 0.03) * Mth.RAD_TO_DEG / TURN_RADIUS);
        // The floor exists only so a nearly stationary creature can still come about. Keep it low:
        // a generous floor is a tight path radius at cruising speed, which is the spiral again.
        maxYaw = Math.min(maxYaw, Math.max(0.30f, arc));
        // Vertical curves may be a little tighter: the body is far shallower than it is wide.
        maxPitch = Math.min(maxPitch, Math.max(0.30f, arc * 1.35f));
        float yawError = Mth.wrapDegrees(wantYaw - leviathan.getYRot());
        float pitchError = Mth.wrapDegrees(wantPitch - leviathan.getXRot());
        float yawStep = Mth.clamp(yawError, -maxYaw, maxYaw);
        float pitchStep = Mth.clamp(pitchError, -maxPitch, maxPitch);
        leviathan.setYRot(Mth.wrapDegrees(leviathan.getYRot() + yawStep));
        leviathan.setXRot(Mth.clamp(leviathan.getXRot() + pitchStep, -88f, 88f));
        leviathan.yBodyRot = leviathan.getYRot();
        // Heading changes are small now that they are bounded by a radius, so the bank they buy is
        // scaled up to compensate: the roll is what sells the turn, and it is all the player sees.
        leviathan.setBankIntent(rollIntent != 0 ? rollIntent : Mth.clamp(-yawStep * 9f, -42f, 42f));

        // Hard turns cost speed, but never all of it: a shark that stops to turn is a shark that
        // pivots, and pivoting is precisely what folds the body up. Misalignment buys a wider arc.
        double alignment = Math.max(0.55, 1.0 - (Math.abs(yawError) + Math.abs(pitchError)) / 180.0);
        double target = speed * alignment;
        if (burst > 0) { target += burst; burst *= 0.82; if (burst < 0.02) burst = 0; }

        Vec3 heading = leviathan.getLookAngle();
        Vec3 next = motion.scale(WATER_DRAG).add(heading.scale(target * 0.22));
        double capped = Math.max(target, 0.05) * 1.35;
        if (next.length() > capped) next = next.normalize().scale(capped);

        // Never drive the body through the floor or fling it out of the water unless asked.
        if (!allowAir && leviathan.nearSurface() && next.y > 0 && leviathan.getY() > leviathan.surfaceY() - 2.0) next = new Vec3(next.x, next.y * 0.25, next.z);
        if (leviathan.getY() < leviathan.floorY() + 6 && next.y < 0) next = new Vec3(next.x, Math.max(0, next.y), next.z);

        leviathan.setDeltaMovement(next);
    }

    private void orientToMotion(Vec3 motion, float blend) {
        if (motion.lengthSqr() < 1.0E-6) return;
        Vec3 d = motion.normalize();
        float wantPitch = (float) (-Math.asin(Mth.clamp(d.y, -1, 1)) * Mth.RAD_TO_DEG);
        // At the top of an arc the motion is almost pure vertical, and a heading read off it is
        // noise. Hold the heading through the apex instead of letting the body spin on nothing.
        float wantYaw = Math.sqrt(d.x * d.x + d.z * d.z) < 0.08 ? leviathan.getYRot()
            : (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
        leviathan.setYRot(leviathan.getYRot() + Mth.wrapDegrees(wantYaw - leviathan.getYRot()) * blend);
        leviathan.setXRot(leviathan.getXRot() + Mth.wrapDegrees(wantPitch - leviathan.getXRot()) * blend);
        leviathan.yBodyRot = leviathan.getYRot();
    }
}
