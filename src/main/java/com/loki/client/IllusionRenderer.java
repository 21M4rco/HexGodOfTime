package com.loki.client;

import com.loki.entity.IllusionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class IllusionRenderer extends MobRenderer<IllusionEntity,PlayerModel<IllusionEntity>> {
    private final PlayerModel<IllusionEntity> classic,slim;
    public IllusionRenderer(EntityRendererProvider.Context ctx){super(ctx,new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER),false),.45f);classic=model;slim=new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM),true);
        addLayer(new net.minecraft.client.renderer.entity.layers.ItemInHandLayer<>(this,ctx.getItemInHandRenderer()));
        addLayer(new RenderLayer<IllusionEntity,PlayerModel<IllusionEntity>>(this){@Override public void render(PoseStack pose,MultiBufferSource buffers,int light,IllusionEntity e,float a,float b,float partial,float age,float yaw,float pitch){if(!e.finalForm())return;pose.pushPose();getParentModel().head.translateAndRotate(pose);LokiLayer.crown(pose,buffers,light,1);pose.popPose();}});
    }
    @Override public ResourceLocation getTextureLocation(IllusionEntity e){var mc=Minecraft.getInstance();if(e.owner()!=null&&mc.level!=null&&mc.level.getPlayerByUUID(e.owner()) instanceof AbstractClientPlayer p)return p.getSkinTextureLocation();var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;return info!=null?info.getSkinLocation():DefaultPlayerSkin.getDefaultSkin();}
    @Override public void render(IllusionEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){var mc=Minecraft.getInstance();var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;model=info!=null&&"slim".equals(info.getModelName())?slim:classic;super.render(e,yaw,partial,pose,buffers,light);}
    @Override protected boolean shouldShowName(IllusionEntity e){return false;}
}
