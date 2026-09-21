package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.AbyssalLeviathan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The abyssal leviathan: a box model on the 16-pixel grid with a countershaded
 * hide, an emissive pass for its eyes, photophores and gullet, and no shadow,
 * because it never touches a floor.
 *
 * Its hitbox is a fraction of its length, so the frustum test is widened by
 * hand or the animal vanishes while its head is still on screen.
 */
public final class LeviathanRenderer extends MobRenderer<AbyssalLeviathan,LeviathanModel> {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(HexGodOfStories.id("abyssal_leviathan"),"main");
    private static final ResourceLocation SKIN=HexGodOfStories.id("textures/entity/abyssal_leviathan.png");
    private static final ResourceLocation GLOW=HexGodOfStories.id("textures/entity/abyssal_leviathan_glow.png");

    public LeviathanRenderer(EntityRendererProvider.Context context) {
        super(context,new LeviathanModel(context.bakeLayer(LAYER)),0F);
        addLayer(new EyesLayer<AbyssalLeviathan,LeviathanModel>(this) {
            private final RenderType type=RenderType.eyes(GLOW);
            @Override public RenderType renderType() {return type;}
        });
    }

    @Override public ResourceLocation getTextureLocation(AbyssalLeviathan entity) {return SKIN;}

    @Override public boolean shouldRender(AbyssalLeviathan entity,Frustum frustum,double x,double y,double z) {
        return frustum.isVisible(entity.getBoundingBox().inflate(LeviathanParts.LENGTH));
    }

    @Override protected void setupRotations(AbyssalLeviathan entity,PoseStack pose,float age,float bodyYaw,float partial) {
        super.setupRotations(entity,pose,age,bodyYaw,partial);
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partial,entity.xRotO,entity.getXRot())));
    }
}
