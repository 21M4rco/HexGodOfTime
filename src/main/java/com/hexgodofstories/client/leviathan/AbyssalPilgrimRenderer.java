package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import com.hexgodofstories.warping.leviathan.LeviathanSegmentController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.HashMap;
import java.util.Map;

/**
 * Renderer for a creature that is longer than the chunk it is standing in.
 *
 * <p>Frustum culling is refused outright: the entity's own position can easily be behind the
 * camera while eighty blocks of body are directly in front of it, and a culled leviathan popping
 * in and out is worse than the cost of drawing it.
 *
 * <p>It is also where the water is made to do its job. See {@link LeviathanWaterVeil} for why the
 * realm leaves a submerged body fully lit and fully legible from above; the correction is applied
 * here, per spine joint, because a body this long can have its head under a boat and its tail a
 * hundred blocks further down and the two should not fade together.
 */
public class AbyssalPilgrimRenderer extends GeoEntityRenderer<AbyssalPilgrimEntity> {
    /** Spine bone name to joint index, so a bone can be turned back into a world position. */
    private static final Map<String, Integer> SPINE = new HashMap<>();
    static {
        SPINE.put("head", 0);
        for (int i = LeviathanSegmentController.NECK_START; i < LeviathanSegmentController.BODY_START; i++)
            SPINE.put("neck_" + (i - LeviathanSegmentController.NECK_START), i);
        for (int i = LeviathanSegmentController.BODY_START; i < LeviathanSegmentController.TAIL_START; i++)
            SPINE.put("body_" + (i - LeviathanSegmentController.BODY_START), i);
        for (int i = LeviathanSegmentController.TAIL_START; i < LeviathanSegmentController.SEGMENTS; i++)
            SPINE.put("tail_" + (i - LeviathanSegmentController.TAIL_START), i);
    }

    private final AbyssalPilgrimModel geoModel;
    private Vec3 camera = Vec3.ZERO;
    private boolean viewerSubmerged;
    private float veilRed = 1f, veilGreen = 1f, veilBlue = 1f;

    public AbyssalPilgrimRenderer(EntityRendererProvider.Context context) {
        super(context, new AbyssalPilgrimModel());
        this.geoModel = (AbyssalPilgrimModel) getGeoModel();
        this.shadowRadius = 0f;
        addRenderLayer(new AbyssalPilgrimGlowLayer(this));
    }

    @Override
    public void render(AbyssalPilgrimEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        this.camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        this.viewerSubmerged = LeviathanWaterVeil.viewerSubmerged();
        int water = LeviathanWaterVeil.waterColour(entity);
        this.veilRed = (water >> 16 & 0xFF) / 255f;
        this.veilGreen = (water >> 8 & 0xFF) / 255f;
        this.veilBlue = (water & 0xFF) / 255f;

        // The render type has to be decided for the whole draw, before any bone is reached, so the
        // body is swept once for the joint the water hides least. Fully exposed means the ordinary
        // cutout pass and pixel-for-pixel the look the creature has always had; anything less has
        // to blend, or the per-joint alpha below would be quietly thrown away.
        float clearest = 0f;
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS && clearest < 1f; i++)
            clearest = Math.max(clearest, visibility(entity, entity.segments().segment(i, partialTick)));
        geoModel.setBlended(clearest < 0.995f);

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * Applies the water to one joint and to everything hanging off it.
     *
     * <p>The twenty two spine bones are the model's only top level bones, so jaws, fins, tendrils
     * and glow organs are all descendants of one of them. GeckoLib hands whatever colour a bone was
     * drawn with down to its children, which means fading a joint fades its whole section — the
     * emissive organs included, since their re-render comes back through here too. Without that,
     * bioluminescence would be the one part of the creature that a kilometre of water could not
     * dim, and a full-bright glow punching up through the surface is the thing that gave the
     * silhouette away in the first place.
     */
    @Override
    public void renderRecursively(PoseStack poseStack, AbyssalPilgrimEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        Integer joint = SPINE.get(bone.getName());
        if (joint != null) {
            float visible = visibility(animatable, animatable.segments().segment(joint, partialTick));
            // Nothing survives down there, and drawing it would be water colour smeared over water.
            if (visible <= LeviathanWaterVeil.CUTOFF) return;
            // Two effects, because either alone reads wrong. Alpha alone leaves a crisp ghost of
            // the exact silhouette; tint alone leaves a flat cut-out in the water's own colour. The
            // pair is what makes a body dissolve into the sea rather than turn into a window.
            float veil = (1f - visible) * 0.8f;
            red = Mth.lerp(veil, red, veilRed);
            green = Mth.lerp(veil, green, veilGreen);
            blue = Mth.lerp(veil, blue, veilBlue);
            alpha *= visible;
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
            partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    private float visibility(AbyssalPilgrimEntity entity, Vec3 point) {
        return LeviathanWaterVeil.visibility(entity, point, camera, viewerSubmerged);
    }

    @Override
    protected void applyRotations(AbyssalPilgrimEntity entity, PoseStack pose, float age, float yaw, float partial) {
        // Each logical body joint is posed in world space by the model.
    }

    @Override
    public boolean shouldRender(AbyssalPilgrimEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return frustum.isVisible(entity.getBoundingBoxForCulling().inflate(28));
    }

    @Override
    public boolean shouldShowName(AbyssalPilgrimEntity entity) {
        return false;
    }
}
