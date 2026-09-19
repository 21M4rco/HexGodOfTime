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
    private final java.util.Map<Integer,net.minecraft.client.player.RemotePlayer> proxies=new java.util.HashMap<>();
    private final java.util.Map<Integer,TemporalCloth> cloths=new java.util.HashMap<>();
    private final PlayerModel<IllusionEntity> classic,slim;
    public IllusionRenderer(EntityRendererProvider.Context ctx){super(ctx,new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER),false),.45f);classic=model;slim=new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM),true);
        addLayer(new net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer<>(this,new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),ctx.getModelManager()));
        addLayer(new net.minecraft.client.renderer.entity.layers.ItemInHandLayer<>(this,ctx.getItemInHandRenderer()));
        addLayer(new RenderLayer<IllusionEntity,PlayerModel<IllusionEntity>>(this){@Override public void render(PoseStack pose,MultiBufferSource buffers,int light,IllusionEntity e,float a,float b,float partial,float age,float yaw,float pitch){if(!e.finalForm())return;pose.pushPose();getParentModel().head.translateAndRotate(pose);LokiLayer.crown(pose,buffers,light,1);pose.popPose();
            var mc=Minecraft.getInstance();if(mc.level==null||e.owner()==null)return;var info=mc.getConnection()==null?null:mc.getConnection().getPlayerInfo(e.owner());if(info==null)return;
            if(proxies.size()>64){proxies.clear();cloths.clear();}var proxy=proxies.computeIfAbsent(e.getId(),k->new net.minecraft.client.player.RemotePlayer(mc.level,info.getProfile()));proxy.tickCount=e.tickCount;proxy.setPos(e.position());proxy.yBodyRot=e.yBodyRot;proxy.setDeltaMovement(e.getDeltaMovement());proxy.setPose(e.getPose());var cloth=cloths.computeIfAbsent(e.getId(),k->new TemporalCloth());cloth.tick(proxy);pose.pushPose();getParentModel().body.translateAndRotate(pose);LokiLayer.drawCape(pose,buffers,light,cloth,1,partial);pose.popPose();}});
    }
    @Override public ResourceLocation getTextureLocation(IllusionEntity e){var mc=Minecraft.getInstance();if(e.owner()!=null&&mc.level!=null&&mc.level.getPlayerByUUID(e.owner()) instanceof AbstractClientPlayer p)return p.getSkinTextureLocation();var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;return info!=null?info.getSkinLocation():DefaultPlayerSkin.getDefaultSkin();}
    @Override public void render(IllusionEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){var mc=Minecraft.getInstance();var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;model=info!=null&&"slim".equals(info.getModelName())?slim:classic;super.render(e,yaw,partial,pose,buffers,light);}
    @Override protected boolean shouldShowName(IllusionEntity e){return false;}
}
