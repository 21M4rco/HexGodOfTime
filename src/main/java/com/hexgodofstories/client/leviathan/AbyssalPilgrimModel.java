package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import com.hexgodofstories.warping.leviathan.LeviathanAttack;
import com.hexgodofstories.warping.leviathan.LeviathanSegmentController;
import com.hexgodofstories.warping.leviathan.LeviathanState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Poses the creature. Keyframed clips supply character; this class supplies the shape.
 *
 * <p>The spine is a parent chain of twenty two bones, so only the angle between one joint and the
 * one in front of it ever has to be written. Feeding those deltas from the shared segment
 * controller means the rendered body traces the exact path the server used for its hitboxes, with
 * no body data on the wire.
 *
 * <p>Bioluminescence is deliberately expressed as geometry rather than as a shader uniform: the
 * emissive pixels live only on the glow bones, so hiding those bones genuinely extinguishes the
 * creature, which is what the stalking behaviour needs.
 */
public class AbyssalPilgrimModel extends GeoModel<AbyssalPilgrimEntity> {
    private static final ResourceLocation MODEL = HexGodOfStories.id("geo/abyssal_pilgrim.geo.json");
    private static final ResourceLocation TEXTURE = HexGodOfStories.id("textures/entity/abyssal_pilgrim.png");
    private static final ResourceLocation ANIMATION = HexGodOfStories.id("animations/abyssal_pilgrim.animation.json");

    /**
     * Blockbench and Minecraft disagree about the sign of a yaw rotation depending on which way a
     * model is authored to face. These exist so the body can be flipped without touching the maths.
     */
    private static final float YAW_SIGN = -1f, PITCH_SIGN = 1f, ROLL_SIGN = 1f;

    private static final String[] SPINE = new String[LeviathanSegmentController.SEGMENTS];
    static {
        SPINE[0] = "head";
        for (int i = LeviathanSegmentController.NECK_START; i < LeviathanSegmentController.BODY_START; i++) SPINE[i] = "neck_" + (i - LeviathanSegmentController.NECK_START);
        for (int i = LeviathanSegmentController.BODY_START; i < LeviathanSegmentController.TAIL_START; i++) SPINE[i] = "body_" + (i - LeviathanSegmentController.BODY_START);
        for (int i = LeviathanSegmentController.TAIL_START; i < LeviathanSegmentController.SEGMENTS; i++) SPINE[i] = "tail_" + (i - LeviathanSegmentController.TAIL_START);
    }

    private final Map<AbyssalPilgrimEntity, LeviathanSegmentRenderController> controllers = new WeakHashMap<>();
    /** Set by the renderer each frame; see {@link #getRenderType}. */
    private boolean blended;

    @Override public ResourceLocation getModelResource(AbyssalPilgrimEntity animatable) { return MODEL; }
    @Override public ResourceLocation getTextureResource(AbyssalPilgrimEntity animatable) { return TEXTURE; }
    @Override public ResourceLocation getAnimationResource(AbyssalPilgrimEntity animatable) { return ANIMATION; }

    /** Told once a frame whether any part of the body is currently being dimmed by the water. */
    public void setBlended(boolean blended) { this.blended = blended; }

    /**
     * Cutout while the creature is in plain sight, blended while the water is hiding it.
     *
     * <p>The cutout pass has no blending at all: a vertex alpha below one is simply written opaque,
     * so the per joint fade the renderer computes would be discarded and the body would stay as
     * sharp under two hundred blocks of ocean as it is at the surface. Switching rather than
     * always blending keeps the fully exposed creature on exactly the pass it has always used, so
     * a breach looks identical to how it looked before any of this existed.
     */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(AbyssalPilgrimEntity animatable, ResourceLocation texture) {
        return blended ? net.minecraft.client.renderer.RenderType.entityTranslucent(texture)
                       : net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture);
    }

    public LeviathanSegmentRenderController controllerFor(AbyssalPilgrimEntity entity) {
        return controllers.computeIfAbsent(entity, e -> new LeviathanSegmentRenderController());
    }

    @Override
    public void setCustomAnimations(AbyssalPilgrimEntity animatable, long instanceId, AnimationState<AbyssalPilgrimEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float partial = animationState == null ? 1f : animationState.getPartialTick();
        LeviathanSegmentRenderController render = controllerFor(animatable);
        render.update(animatable, partial);

        LeviathanSegmentController segments = animatable.segments();
        poseSpine(animatable, segments, render, partial);
        poseHead(animatable, render, partial);
        poseAppendages(animatable, render, partial);
        poseGlow(animatable, partial);
    }

    private void poseSpine(AbyssalPilgrimEntity entity, LeviathanSegmentController segments, LeviathanSegmentRenderController render, float partial) {
        net.minecraft.world.phys.Vec3 origin = new net.minecraft.world.phys.Vec3(
            Mth.lerp(partial, entity.xOld, entity.getX()),
            Mth.lerp(partial, entity.yOld, entity.getY()),
            Mth.lerp(partial, entity.zOld, entity.getZ()));
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i++) {
            GeoBone bone = bone(SPINE[i]);
            if (bone == null) continue;
            net.minecraft.world.phys.Vec3 offset = segments.segment(i, partial).subtract(origin);
            // GeckoLib negates position X, but not Y/Z. Independent root children follow the
            // recorded collision path directly; Euler differences are not valid 3D joint rotations.
            bone.setPosX((float) -offset.x * 16f);
            bone.setPosY((float) offset.y * 16f);
            bone.setPosZ((float) offset.z * 16f - bone.getPivotZ());
            float yaw = i == 0 ? Mth.rotLerp(partial, entity.yRotO, entity.getYRot()) : segments.yaw(i, partial);
            float pitch = i == 0 ? Mth.lerp(partial, entity.xRotO, entity.getXRot()) : segments.pitch(i, partial);
            if (i == 0 && entity.attack() == null) {
                yaw += render.headYaw(); pitch += render.headPitch();
            }
            // Compose local banking after heading/pitch, then express it in GeckoLib's Z-Y-X order.
            float[] angles = com.hexgodofstories.warping.leviathan.LeviathanPoseMath.angles(
                yaw, pitch, i == 0 ? entity.bank(partial) : segments.roll(i, partial));
            bone.setRotX(angles[0]); bone.setRotY(angles[1]); bone.setRotZ(angles[2]);
        }
    }

    private void poseHead(AbyssalPilgrimEntity entity, LeviathanSegmentRenderController render, float partial) {
        float open = jawOpen(entity, partial);
        GeoBone upper = bone("upper_jaw"), lower = bone("lower_jaw");
        if (upper != null) upper.setRotX(open * 0.30f);
        if (lower != null) lower.setRotX(-open * 0.98f);
        // The jaw does not just hinge, it splits.
        GeoBone left = bone("jaw_split_left"), right = bone("jaw_split_right");
        if (left != null) { left.setRotY(open * 0.58f); left.setRotZ(open * 0.26f); }
        if (right != null) { right.setRotY(-open * 0.58f); right.setRotZ(-open * 0.26f); }

        GeoBone sensory = bone("sensory_organs");
        if (sensory != null) {
            sensory.setScaleX(1); sensory.setScaleY(1); sensory.setScaleZ(1);
            sensory.setRotY(0); // Eye clusters stay embedded in the cheek armour.
        }
    }

    /** How wide the mouth is right now, 0 closed and 1 fully split open. */
    private float jawOpen(AbyssalPilgrimEntity entity, float partial) {
        LeviathanAttack attack = entity.attack();
        float idle = 0.29f + 0.035f * Mth.sin((entity.tickCount + partial) * 0.05f);
        if (entity.heldId() >= 0) return 0.42f;
        if (attack == null) return idle;
        float t = entity.attackTick() + partial;
        return switch (attack) {
            case BREACH_BITE, SKY_LEAP, DEEP_CHARGE ->
                t < attack.windup ? Mth.clamp(t / attack.windup, 0, 1)
                : t < attack.windup + attack.active ? 0.95f
                : Mth.clamp(1f - (t - attack.windup - attack.active) / 12f, idle, 1f);
            // The jaws used to slam shut five ticks into the active phase and then stay shut while
            // the pattern went on dealing damage for the rest of it, so the mouth the player saw
            // and the mouth the damage came out of were two different things. They now hold open
            // across the whole pass, close as the head comes off the prey, and relax to idle over
            // the recovery instead of popping back.
            case PREDATORY_BITE -> {
                float snap = attack.windup + attack.active - 6f;
                float shut = attack.windup + attack.active;
                yield t < attack.windup ? Mth.clamp(t / attack.windup, 0f, 1f)
                    : t < snap ? 0.98f
                    : t < shut ? Mth.clamp(1f - (t - snap) / 6f, 0.02f, 1f)
                    : Mth.lerp(Mth.clamp((t - shut) / Math.max(1f, attack.recover), 0f, 1f), 0.02f, idle);
            }
            case VOID_SCREAM -> Mth.clamp(t / attack.windup, 0, 1);
            case FAKE_ATTACK -> t < attack.windup ? Mth.clamp(t / attack.windup, 0, 1) * 0.9f : Mth.clamp(1f - (t - attack.windup) / 10f, 0f, 1f) * 0.9f;
            case TENDRIL_GRAB, DRAG_BELOW, AIR_THROW -> 0.55f;
            default -> idle;
        };
    }

    private void poseAppendages(AbyssalPilgrimEntity entity, LeviathanSegmentRenderController render, float partial) {
        if (render.detail() == 0) return;
        int bodyCount = LeviathanSegmentController.TAIL_START - LeviathanSegmentController.BODY_START;
        for (int b = 0; b < bodyCount; b++) {
            int segment = LeviathanSegmentController.BODY_START + b;
            float s = render.sway(segment) * Mth.DEG_TO_RAD;
            float l = render.lift(segment) * Mth.DEG_TO_RAD;
            GeoBone fin = bone("dorsal_fin_" + b);
            if (fin != null) { fin.setRotZ(fin.getRotZ() + s * 0.22f); fin.setRotX(fin.getRotX() + l * 0.35f); }
            // Rib blades are swept back along the hull and stay swept. They ripple; they do not
            // splay. A fan of blades standing off the body is the shape that reads as debris.
            GeoBone ribLeft = bone("rib_appendage_l_" + b), ribRight = bone("rib_appendage_r_" + b);
            if (ribLeft != null) { ribLeft.setRotZ(0.20f + s * 0.30f); ribLeft.setRotX(l * 0.45f); }
            if (ribRight != null) { ribRight.setRotZ(-0.20f + s * 0.30f); ribRight.setRotX(l * 0.45f); }
        }
        if (render.detail() < 2) return;
        for (int t = 0; t < 6; t++) {
            GeoBone tendril = bone("head_tendril_" + t);
            if (tendril == null) continue;
            float phase = (entity.tickCount + partial) * 0.09f + t * 0.9f;
            float amount = render.sway(0) * Mth.DEG_TO_RAD * 0.55f + Mth.sin(phase) * (entity.isSubmerged() ? 0.11f : 0.22f);
            tendril.setRotZ(amount);
            tendril.setRotX(Mth.cos(phase * 0.7f) * (entity.isSubmerged() ? 0.09f : 0.20f) + render.lift(0) * Mth.DEG_TO_RAD * 0.3f);
        }
        for (int t = 0; t < 4; t++) {
            GeoBone tendril = bone("tail_tendril_" + t);
            if (tendril == null) continue;
            int segment = LeviathanSegmentController.SEGMENTS - 1;
            float phase = (entity.tickCount + partial) * 0.11f + t * 1.3f;
            tendril.setRotZ(render.sway(segment) * Mth.DEG_TO_RAD * 0.65f + Mth.sin(phase) * 0.16f);
            tendril.setRotX(render.lift(segment) * Mth.DEG_TO_RAD * 0.45f);
        }
    }

    /**
     * Stalking is almost dark, tracking pulses slowly, hunting is bright, attacking is flat out and
     * frenzy is an unstable flicker. Below a threshold the organs are hidden outright.
     */
    private void poseGlow(AbyssalPilgrimEntity entity, float partial) {
        float base = entity.renderGlow(partial);
        float time = entity.tickCount + partial;
        LeviathanState state = entity.state();
        float intensity = switch (state) {
            case STALK, AMBUSH -> base * (0.55f + 0.45f * Mth.sin(time * 0.014f));
            case TRACK, SEARCH -> base * (0.62f + 0.38f * Mth.sin(time * 0.045f));
            case TOY -> base * (0.78f + 0.22f * Mth.sin(time * 0.07f));
            case HUNT -> base * (0.86f + 0.14f * Mth.sin(time * 0.12f));
            case ATTACK -> base;
            case FRENZY -> base * (0.7f + 0.3f * Mth.sin(time * 0.61f) * Mth.cos(time * 0.29f));
        };
        if (entity.isDying()) intensity = base;
        boolean dark = intensity < 0.07f;
        // Capped near life size: an organ scaled half again as large pushes out through the hull
        // it is supposed to be embedded in, which at a hundred and fifty blocks long is very visible.
        float scale = Mth.clamp(0.45f + intensity * 0.8f, 0.2f, 1.22f);
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i++) {
            GeoBone organ = bone("glow_organs_" + i);
            if (organ == null) continue;
            // Dying shuts the lights down from the tail forward.
            boolean off = dark || (entity.isDying() && entity.dying() > 40 && i > LeviathanSegmentController.SEGMENTS - 1 - (entity.dying() - 40) / 12);
            organ.setHidden(off);
            if (!off) { organ.setScaleX(scale); organ.setScaleY(scale); organ.setScaleZ(scale); }
        }
        GeoBone headOrgans = bone("glow_organs_head");
        if (headOrgans != null) {
            headOrgans.setHidden(dark);
            if (!dark) { headOrgans.setScaleX(1); headOrgans.setScaleY(1); headOrgans.setScaleZ(1); }
        }
    }

    private GeoBone bone(String name) { return getBone(name).orElse(null); }
}
