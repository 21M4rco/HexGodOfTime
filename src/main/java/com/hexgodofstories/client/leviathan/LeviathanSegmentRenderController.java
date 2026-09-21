package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import com.hexgodofstories.warping.leviathan.LeviathanSegmentController;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Per frame presentation state for one leviathan: the secondary motion of everything that is not
 * spine, plus the level of detail budget.
 *
 * <p>Tendrils, fin membranes and rib appendages are not animated by keyframes. They are driven by
 * what the body is actually doing: turn rate, acceleration, whether there is water around them at
 * all, and how hard the last surface crossing was. Out of the water the drag term disappears, so
 * the same appendages hang and snap instead of trailing.
 */
public final class LeviathanSegmentRenderController {
    /** Beyond this the appendage solver is skipped and the spine alone is posed. */
    private static final double DETAIL_RANGE = 90.0;
    private static final double COARSE_RANGE = 220.0;

    private final float[] sway = new float[LeviathanSegmentController.SEGMENTS];
    private final float[] swayVel = new float[LeviathanSegmentController.SEGMENTS];
    private final float[] lift = new float[LeviathanSegmentController.SEGMENTS];
    private final float[] liftVel = new float[LeviathanSegmentController.SEGMENTS];

    private float headYaw, headPitch;
    private float impact;
    private boolean wasSubmerged = true;
    private int detail = 2;
    private int simulatedTick = Integer.MIN_VALUE;

    /** 2 full appendage solve, 1 spine plus coarse sway, 0 spine only. */
    public int detail() { return detail; }
    public float sway(int i) { return sway[Mth.clamp(i, 0, sway.length - 1)]; }
    public float lift(int i) { return lift[Mth.clamp(i, 0, lift.length - 1)]; }
    public float headYaw() { return headYaw; }
    public float headPitch() { return headPitch; }
    public float impact() { return impact; }

    public void update(AbyssalPilgrimEntity entity, float partialTick) {
        double distance = entity.viewerDistance();
        detail = distance < DETAIL_RANGE ? 2 : distance < COARSE_RANGE ? 1 : 0;

        // One spring step per game tick, independent of FPS and the emissive render pass.
        if (simulatedTick == entity.tickCount) return;
        simulatedTick = entity.tickCount;
        boolean submerged = entity.isSubmerged();
        if (wasSubmerged && !submerged) impact = 0.4f;          // leaving the water
        if (!wasSubmerged && submerged) impact = 1.0f;          // and the far more violent return
        wasSubmerged = submerged;
        impact *= 0.92f;

        Vec3 motion = entity.getDeltaMovement();
        float speed = (float) motion.length();
        // In air there is nothing to push against, so the restoring force collapses and damping with it.
        float stiffness = submerged ? 0.22f : 0.055f;
        float damping = submerged ? 0.74f : 0.93f;

        LeviathanSegmentController segments = entity.segments();
        int step = detail == 2 ? 1 : detail == 1 ? 2 : LeviathanSegmentController.SEGMENTS;
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i += step) {
            float lead = segments.yaw(Math.max(0, i - 1));
            float turn = Mth.wrapDegrees(lead - segments.yaw(i));
            float drive = turn * (submerged ? 1.4f : 3.1f) + (float) Math.sin((entity.tickCount + partialTick) * 0.17 + i * 0.6) * speed * (submerged ? 9f : 2f);
            drive += impact * (float) Math.sin(i * 1.7 + entity.tickCount * 0.9) * 46f;
            swayVel[i] = swayVel[i] * damping + (drive - sway[i]) * stiffness;
            sway[i] = Mth.clamp(sway[i] + swayVel[i], -85f, 85f);

            float pitchLead = segments.pitch(Math.max(0, i - 1));
            float rise = Mth.wrapDegrees(pitchLead - segments.pitch(i)) * 1.2f + (submerged ? 0f : 26f);
            liftVel[i] = liftVel[i] * damping + (rise - lift[i]) * stiffness;
            lift[i] = Mth.clamp(lift[i] + liftVel[i], -70f, 70f);
        }
        if (step > 1) for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i++) if (i % step != 0) { sway[i] = sway[i - i % step]; lift[i] = lift[i - i % step]; }

        updateHeadTracking(entity, partialTick);
    }

    /** The head watches prey without the body agreeing to go there. */
    private void updateHeadTracking(AbyssalPilgrimEntity entity, float partialTick) {
        var target = entity.lookTarget();
        float wantYaw = 0, wantPitch = 0;
        if (target != null) {
            Vec3 head = entity.segments().segment(0);
            Vec3 toward = target.getEyePosition(partialTick).subtract(head);
            double flat = Math.sqrt(toward.x * toward.x + toward.z * toward.z);
            if (toward.lengthSqr() > 1.0E-4) {
                float absoluteYaw = (float) (Mth.atan2(-toward.x, toward.z) * Mth.RAD_TO_DEG);
                float absolutePitch = (float) (-Mth.atan2(toward.y, flat) * Mth.RAD_TO_DEG);
                wantYaw = Mth.clamp(Mth.wrapDegrees(absoluteYaw - entity.getYRot()), -58f, 58f);
                wantPitch = Mth.clamp(Mth.wrapDegrees(absolutePitch - entity.getXRot()), -42f, 42f);
            }
        }
        headYaw += (wantYaw - headYaw) * 0.11f;
        headPitch += (wantPitch - headPitch) * 0.11f;
    }
}
