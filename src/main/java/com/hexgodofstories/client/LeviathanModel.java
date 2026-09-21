package com.hexgodofstories.client;

import com.hexgodofstories.warping.AbyssalLeviathan;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import java.util.Map;

/**
 * Motion for the generated {@link LeviathanParts} skeleton.
 *
 * Swimming is a travelling wave: one fixed phase lag per link and a growing
 * amplitude toward the tail, so the shape runs nose to fluke instead of every
 * segment flexing together. Fins, jaw idle and barbel drift hang off the same
 * phase at their own offsets. The bite is an authored curve driven by the
 * entity's synchronized countdown, so what a player sees is exactly the hit
 * they are about to take.
 *
 * Authoring space (+Y up, +Z forward) is a half turn about X away from model
 * space, so Y and Z rotation offsets are subtracted where X is added.
 */
public final class LeviathanModel extends HierarchicalModel<AbyssalLeviathan> {
    private static final float DEG = (float)(Math.PI / 180.0);
    private static final String[] CHAIN = {"head","neck_1","neck_0","spine_0","spine_1","spine_2",
        "spine_3","spine_4","spine_5","spine_6","spine_7","tail_tip"};
    private static final float[] AMP = {.30F,.46F,.62F,.78F,.95F,1.12F,1.32F,1.55F,1.80F,2.05F,2.30F,2.55F};
    private static final float[] LAG = {0F,.30F,.62F,.95F,1.30F,1.66F,2.02F,2.38F,2.74F,3.10F,3.46F,3.82F};
    private static final float BASE = 5.2F, FIN = 14F;

    /** Authored bite curve, in ticks. Seven ticks of wind-up, a tick and a half of snap. */
    private static final float[][] JAW = {{0,2},{4,14},{7,58},{10,62},{11.5F,3},{14,9},{17,4},{21,2},{28,2}};
    private static final float[][] HEAD_PITCH = {{0,0},{4,-13},{7,-19},{10,10},{12,17},{15,8},{18,2},{28,0}};
    private static final float[][] HEAD_YAW = {{0,0},{12,0},{14,9},{16,-9},{18,5},{21,0},{28,0}};
    private static final float[][] SURGE = {{0,0},{6,-6},{10,16},{13,19},{20,4},{28,0}};
    private static final float[][] COIL = {{0,0},{6,1},{10,-.7F},{16,.5F},{22,-.2F},{28,0}};
    private static final float[][] GILL = {{0,0},{8,32},{16,0},{28,0}};

    private final ModelPart root, body, lowerJaw;
    private final Map<String,ModelPart> parts;
    private final ModelPart[] chain = new ModelPart[CHAIN.length];

    public LeviathanModel(ModelPart root) {
        this.root = root;
        this.parts = LeviathanParts.index(root);
        this.body = parts.get("body");
        this.lowerJaw = parts.get("lower_jaw");
        for (int i = 0; i < CHAIN.length; i++) chain[i] = parts.get(CHAIN[i]);
    }

    @Override public ModelPart root() { return root; }

    @Override
    public void setupAnim(AbyssalLeviathan entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float partial = Mth.clamp(ageInTicks - entity.tickCount, 0F, 1F);
        double speed = entity.getDeltaMovement().length();
        float gain = (float)Mth.clamp(.42 + speed * 2.6, .42, 1.9);
        float cycle = (float)Mth.clamp(96 - speed * 260, 30, 96);
        float phase = ageInTicks * ((float)Math.PI * 2F / cycle);

        for (int i = 0; i < chain.length; i++) {
            ModelPart part = chain[i];
            if (part == null) continue;
            float amp = BASE * AMP[i] * gain;
            float yaw = amp * Mth.sin(phase - LAG[i]);
            float pitch = amp * .18F * Mth.sin(phase * 2 - LAG[i] - .8F);
            float roll = -amp * .18F * Mth.sin(phase - LAG[i] + 1.2F);
            part.xRot += pitch * DEG;
            part.yRot -= yaw * DEG;
            part.zRot -= roll * DEG;
        }

        float sweep = FIN * Mth.sin(phase - .9F) * Mth.clamp(gain, .4F, 1.4F);
        for (int s = 0; s < 2; s++) {
            String tag = s == 0 ? "l" : "r";
            int sign = s == 0 ? 1 : -1;
            rotate(parts.get("pec_" + tag), sweep * .8F, 0, sign * sweep * .5F);
            rotate(parts.get("pec_tip_" + tag), sweep * .6F, 0, -sign * sweep * .7F);
            rotate(parts.get("pelvic_" + tag), sweep * .5F, 0, sign * sweep * .4F);
            for (int j = 0; j < 2; j++) {
                float drift = 6F * Mth.sin(phase - 1.6F - j * .5F);
                rotate(parts.get("barb" + j + "_a_" + tag), drift, sign * drift * .6F, 0);
                rotate(parts.get("barb" + j + "_b_" + tag), drift * 1.2F, 0, 0);
            }
        }
        rotate(parts.get("fluke_up"), 0, BASE * 2.2F * gain * Mth.sin(phase - 4.2F), 0);
        rotate(parts.get("fluke_down"), 0, BASE * 2F * gain * Mth.sin(phase - 4.3F), 0);

        ModelPart head = parts.get("head");
        if (head != null) {
            head.yRot += netHeadYaw * DEG * .35F;
            head.xRot += headPitch * DEG * .30F;
        }

        float idleJaw = 2F + 1.2F * Mth.sin(phase * .5F);
        float bite = entity.biteProgress(partial);
        if (bite <= 0) {
            if (lowerJaw != null) lowerJaw.xRot += idleJaw * DEG;
            return;
        }
        if (lowerJaw != null) lowerJaw.xRot += curve(JAW, bite) * DEG;
        if (head != null) {
            head.xRot += curve(HEAD_PITCH, bite) * DEG;
            head.yRot -= curve(HEAD_YAW, bite) * DEG;
        }
        // The lunge is a translation of the whole animal: forward is -Z in model space.
        body.z -= curve(SURGE, bite);
        float coil = curve(COIL, bite);
        for (int i = 4; i < chain.length; i++) {
            ModelPart part = chain[i];
            if (part == null) continue;
            float side = (i % 2 == 0 ? 1 : -1) * coil * (5F + (i - 4) * 1.6F);
            part.yRot -= side * DEG;
        }
        for (int s = 0; s < 2; s++) {
            int sign = s == 0 ? 1 : -1;
            for (int i = 0; i < 3; i++) rotate(parts.get("gill_" + i + "_" + (s == 0 ? "l" : "r")), 0, sign * curve(GILL, bite), 0);
        }
    }

    /** Applies an authoring-space rotation offset in degrees. */
    private static void rotate(ModelPart part, float x, float y, float z) {
        if (part == null) return;
        part.xRot += x * DEG;
        part.yRot -= y * DEG;
        part.zRot -= z * DEG;
    }

    private static float curve(float[][] frames, float t) {
        if (t <= frames[0][0]) return frames[0][1];
        float[] last = frames[frames.length - 1];
        if (t >= last[0]) return last[1];
        for (int i = 0; i < frames.length - 1; i++) {
            float[] a = frames[i], b = frames[i + 1];
            if (t <= b[0]) {
                float k = (t - a[0]) / Math.max(1.0E-4F, b[0] - a[0]);
                k = k * k * (3 - 2 * k);
                return a[1] + (b[1] - a[1]) * k;
            }
        }
        return last[1];
    }
}
