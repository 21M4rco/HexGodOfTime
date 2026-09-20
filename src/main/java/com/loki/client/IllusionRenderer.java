package com.loki.client;

import com.loki.entity.IllusionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * A projection wears its caster's face. It borrows the real player model, skin, armour, worn head
 * gear and equipment, and its cloak is solved by {@link CapeRenderer} exactly like a real Loki's —
 * which is the point: nothing on screen should tell an onlooker which one is breathing.
 *
 * <p>It carries a name tag too, drawn under the same distance and crouch rules a player's own tag
 * follows. A nameless figure standing beside a named one is the single easiest tell there is, and
 * removing it costs nothing else.
 */
public final class IllusionRenderer extends MobRenderer<IllusionEntity,PlayerModel<IllusionEntity>> {
    private final PlayerModel<IllusionEntity> classic,slim;

    public IllusionRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER),false),.45f);
        classic=model;
        slim=new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM),true);
        addLayer(new HumanoidArmorLayer<>(this,
            new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
        addLayer(new ItemInHandLayer<>(this,ctx.getItemInHandRenderer()));
        addLayer(new CustomHeadLayer<>(this,ctx.getModelSet(),ctx.getItemInHandRenderer()));
        addLayer(new ElytraLayer<>(this,ctx.getModelSet()));
        addLayer(new RenderLayer<IllusionEntity,PlayerModel<IllusionEntity>>(this) {
            @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,IllusionEntity e,
                                         float a,float b,float partial,float age,float yaw,float pitch) {
                if(!e.finalForm()||e.isInvisible())return;
                pose.pushPose();
                getParentModel().head.translateAndRotate(pose);
                LokiLayer.crown(pose,buffers,light,1);
                pose.popPose();
                pose.pushPose();
                getParentModel().body.translateAndRotate(pose);
                CapeRenderer.capture(e,pose);
                LokiLayer.collar(pose,buffers,light,1);
                pose.popPose();
            }
        });
    }

    @Override public ResourceLocation getTextureLocation(IllusionEntity e) {
        var mc=Minecraft.getInstance();
        if(e.owner()!=null&&mc.level!=null&&mc.level.getPlayerByUUID(e.owner()) instanceof AbstractClientPlayer p)return p.getSkinTextureLocation();
        var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;
        return info!=null?info.getSkinLocation():DefaultPlayerSkin.getDefaultSkin();
    }
    @Override public void render(IllusionEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        var mc=Minecraft.getInstance();
        var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;
        model=info!=null&&"slim".equals(info.getModelName())?slim:classic;
        super.render(e,yaw,partial,pose,buffers,light);
    }
    /** Mirrors the rules a player's own tag follows, so the group's labels match at every distance. */
    @Override protected boolean shouldShowName(IllusionEntity e) {
        if(!Minecraft.renderNames()||e.isInvisible()||e.getCustomName()==null)return false;
        float limit=e.isDiscrete()?32:64;
        return entityRenderDispatcher.distanceToSqr(e)<limit*limit;
    }
}
