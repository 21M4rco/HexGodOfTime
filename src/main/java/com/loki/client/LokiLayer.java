package com.loki.client;

import com.loki.Loki;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Head and shoulder pieces that genuinely belong to the player's skeleton. The cloak is deliberately
 * not here: it is solved and drawn in world space by {@link CapeRenderer}, which is what keeps it
 * fixed to the shoulders instead of inheriting a mirrored model transform.
 */
public final class LokiLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
    public static final ResourceLocation MATERIAL=Loki.id("textures/material.png"),CLOTH=Loki.id("textures/cloth.png");
    private static AuthoredMesh crown,collar;
    public LokiLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
    public static void clear(){crown=null;collar=null;CapeRenderer.clear();}

    public static void crown(PoseStack pose,MultiBufferSource buffers,int light,float progress) {
        if(crown==null)crown=new AuthoredMesh("crown");
        float growth=Mth.clamp((progress-.42f)/.53f,0,1);
        crown.draw(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(MATERIAL)),light,(group,v)->{
            if(group.startsWith("horn")) {
                float t=Mth.clamp((-v.y()-.42f)/.85f,0,1);
                if(t>growth)return null;
                float s=Mth.clamp((growth-t)*8,0,1);
                float anchor=v.x()>0?.22f:-.22f;
                return new AuthoredMesh.Point(anchor+(v.x()-anchor)*s,v.y(),v.z(),v.u(),v.v());
            }
            return progress<.35f?null:v;
        });
    }

    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,float walk,float walkAmount,float partial,float age,float headYaw,float headPitch) {
        float progress=ClientState.progress(p.getId(),partial);
        if(progress<=0||ClientState.data(p.getId()).contains("disguise"))return;
        pose.pushPose();getParentModel().head.translateAndRotate(pose);crown(pose,buffers,light,progress);pose.popPose();
        if(progress<.14f)return;
        pose.pushPose();getParentModel().body.translateAndRotate(pose);
        if(collar==null)collar=new AuthoredMesh("collar");
        float rise=Mth.clamp(progress*3,0,1);
        collar.draw(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(CLOTH)),light,
            (g,v)->new AuthoredMesh.Point(v.x(),v.y()*rise,v.z(),v.u(),v.v()));
        pose.popPose();
    }
}
