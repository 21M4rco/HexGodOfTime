package com.hexgodofstories.warping.leviathan;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Fully three dimensional steering for a body that weighs as much as a small island.
 *
 * <p>Turning is rate limited rather than instant, and the limit scales with how committed the
 * creature currently is: drifting turns are a degree or so per tick, an attack turn is allowed to
 * be violent. Speed is bled off during hard turns so momentum reads as mass. Above the waterline
 * steering authority collapses and the motion becomes ballistic, which is what makes a breach arc
 * look like a thrown body instead of a flying one.
 */
public final class LeviathanMoveControl extends MoveControl {
    private static final double GRAVITY = 0.075;
    private static final double WATER_DRAG = 0.91;
    private static final double AIR_DRAG = 0.985;

    private final AbyssalPilgrimEntity leviathan;
    private Vec3 wanted = Vec3.ZERO;
    private double speed = 0.4;
    /** 0 is a lazy drift, 1 is an attack turn. */
    private float authority = 0.25f;
    private boolean active;
    private boolean allowAir;
    private double burst;
    private float rollIntent;

    public LeviathanMoveControl(AbyssalPilgrimEntity leviathan) {
        super(leviathan);
        this.leviathan = leviathan;
    }

    public void moveTo(Vec3 target, double blocksPerTick, float turnAuthority) {
        this.wanted = target;
        this.speed = blocksPerTick;
        this.authority = Mth.clamp(turnAuthority, 0.02f, 1.0f);
        this.active = true;
        super.setWantedPosition(target.x, target.y, target.z, blocksPerTick);
    }

    public void stopMoving() { this.active = false; this.burst = 0; }
    public void setAllowAir(boolean allow) { this.allowAir = allow; }
    /** One off forward impulse, used by lunges, deep charges and breach launches. */
    public void addBurst(double amount) { this.burst = Math.max(this.burst, amount); }
    public void setRollIntent(float degrees) { this.rollIntent = degrees; }
    public Vec3 wanted() { return wanted; }
    public boolean active() { return active; }

    @Override
    public void tick() {
        boolean submerged = leviathan.isSubmerged();
        Vec3 motion = leviathan.getDeltaMovement();

        if (!submerged) {
            // Airborne. Gravity owns the arc; the creature may only twist along it.
            motion = motion.add(0, -GRAVITY, 0).scale(AIR_DRAG);
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

        float wantYaw = (float) (Mth.atan2(-desired.x, desired.z) * Mth.RAD_TO_DEG);
        float wantPitch = (float) (-Math.asin(Mth.clamp(desired.y, -1, 1)) * Mth.RAD_TO_DEG);

        // A body this long cannot snap around. Authority buys degrees per tick, nothing more.
        float maxYaw = 0.9f + 8.6f * authority * authority;
        float maxPitch = 0.7f + 6.4f * authority * authority;
        float yawError = Mth.wrapDegrees(wantYaw - leviathan.getYRot());
        float pitchError = Mth.wrapDegrees(wantPitch - leviathan.getXRot());
        float yawStep = Mth.clamp(yawError, -maxYaw, maxYaw);
        float pitchStep = Mth.clamp(pitchError, -maxPitch, maxPitch);
        leviathan.setYRot(Mth.wrapDegrees(leviathan.getYRot() + yawStep));
        leviathan.setXRot(Mth.clamp(leviathan.getXRot() + pitchStep, -88f, 88f));
        leviathan.yBodyRot = leviathan.getYRot();
        leviathan.setBankIntent(rollIntent != 0 ? rollIntent : Mth.clamp(-yawStep * 5.5f, -45f, 45f));

        // Hard turns cost speed. Facing away from the goal costs most of it.
        double alignment = Math.max(0.12, 1.0 - (Math.abs(yawError) + Math.abs(pitchError)) / 180.0);
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
        float wantYaw = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
        float wantPitch = (float) (-Math.asin(Mth.clamp(d.y, -1, 1)) * Mth.RAD_TO_DEG);
        leviathan.setYRot(leviathan.getYRot() + Mth.wrapDegrees(wantYaw - leviathan.getYRot()) * blend);
        leviathan.setXRot(leviathan.getXRot() + Mth.wrapDegrees(wantPitch - leviathan.getXRot()) * blend);
        leviathan.yBodyRot = leviathan.getYRot();
    }
}
