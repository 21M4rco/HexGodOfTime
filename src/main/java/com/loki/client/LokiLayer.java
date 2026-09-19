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
import java.util.*;

public final class LokiLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
    public static final ResourceLocation MATERIAL=Loki.id("textures/material.png"),CLOTH=Loki.id("textures/cloth.png");
    private static AuthoredMesh crown,collar;
    private static final Map<UUID,TemporalCloth> CLOTHS=new HashMap<>();
    public LokiLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
    public static void clear(){CLOTHS.clear();crown=null;collar=null;}
    public static void crown(PoseStack pose,MultiBufferSource buffers,int light,float progress){if(crown==null)crown=new AuthoredMesh("crown");float growth=Mth.clamp((progress-.42f)/.53f,0,1);crown.draw(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(MATERIAL)),light,(group,v)->{
        if(group.startsWith("horn")){float t=Mth.clamp((-v.y()-.42f)/.85f,0,1);if(t>growth)return null;float s=Mth.clamp((growth-t)*8,0,1);float anchor=v.x()>0?.22f:-.22f;return new AuthoredMesh.Point(anchor+(v.x()-anchor)*s,v.y(),v.z(),v.u(),v.v());}
        return progress<.35f?null:v;
    });}
    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,float walk,float walkAmount,float partial,float age,float headYaw,float headPitch) {
        float progress=ClientState.progress(p.getId(),partial);if(progress<=0||ClientState.data(p.getId()).contains("disguise"))return;
        pose.pushPose();getParentModel().head.translateAndRotate(pose);crown(pose,buffers,light,progress);pose.popPose();
        pose.pushPose();getParentModel().body.translateAndRotate(pose);
        if(collar==null)collar=new AuthoredMesh("collar");
        collar.draw(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(CLOTH)),light,(g,v)->progress<.14f?null:new AuthoredMesh.Point(v.x(),v.y()*Mth.clamp(progress*3,0,1),v.z(),v.u(),v.v()));
        if(CLOTHS.size()>64)CLOTHS.clear();TemporalCloth cloth=CLOTHS.computeIfAbsent(p.getUUID(),k->new TemporalCloth());cloth.tick(p);
        drawCape(pose,buffers,light,cloth,Mth.clamp((progress-.18f)/.7f,0,1),partial);
        pose.popPose();
    }
    public static void drawCape(PoseStack pose,MultiBufferSource buffers,int light,TemporalCloth cloth,float grow,float partial){VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(CLOTH));
        for(int row=0;row<26;row++) {float a=row/26f,b=(row+1)/26f;if(a>grow)break;b=Math.min(b,grow);
            for(int col=0;col<12;col++) {float u=col/12f,v=(col+1)/12f;float[][] q={point(cloth,a,u,partial),point(cloth,a,v,partial),point(cloth,b,v,partial),point(cloth,b,u,partial)};
                WorldEffects.quad(pose,out,q,new float[][]{{u,a},{v,a},{v,b},{u,b}},light,0xffffff,1);
            }
        }
    }
    private static float[] point(TemporalCloth cloth,float t,float u,float partial){return cloth.sample((u*2-1)*(.46f+.35f*t),.03f+1.85f*t,partial);}
}
