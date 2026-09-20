package com.loki.client;

import com.loki.entity.IllusionEntity;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import org.joml.Matrix4f;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * Draws every visible cloak straight in world space from {@link TemporalCloth}'s solved grid. Doing it here
 * rather than inside a model-part transform is what keeps the cape on the shoulders: there is no mirrored
 * model space to fight and no bone to inherit, only the same world coordinates the solver already produced.
 */
public final class CapeRenderer {
    private static final Map<UUID,TemporalCloth> CLOTHS=new HashMap<>();
    private static final int MAX_TRACKED=48;
    private static final Map<UUID,TemporalCloth.BodyFrame> FRAMES=new HashMap<>();
    private static Matrix4f inverseView;
    private static Vec3 frameCamera=Vec3.ZERO;

    public static void beginFrame(RenderLevelStageEvent event) {
        FRAMES.clear();
        inverseView=new Matrix4f(event.getPoseStack().last().pose()).invert();
        frameCamera=event.getCamera().getPosition();
    }
    /** Called inside the torso layer, after vanilla and Player Animator have posed the actual body. */
    public static void capture(LivingEntity wearer,PoseStack bodyPose) {
        if(inverseView!=null)FRAMES.put(wearer.getUUID(),new TemporalCloth.BodyFrame(
            new Matrix4f(inverseView).mul(bodyPose.last().pose()),frameCamera));
    }

    public static void clear() {CLOTHS.clear();FRAMES.clear();inverseView=null;}

    public static void renderAll(PoseStack pose,MultiBufferSource buffers,float partial) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)return;
        if(CLOTHS.size()>MAX_TRACKED)CLOTHS.clear();
        VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(LokiLayer.CLOTH));
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        boolean firstPerson=mc.options.getCameraType().isFirstPerson();
        Set<UUID> alive=new HashSet<>();
        for(var entity:mc.level.entitiesForRendering()) {
            float grow;
            if(entity instanceof AbstractClientPlayer player) {
                if(player.isSpectator()||ClientState.hidden(player))continue;
                if(firstPerson&&player==mc.player)continue;
                if(ClientState.data(player.getId()).contains("disguise"))continue;
                grow=Mth.clamp((ClientState.progress(player.getId(),partial)-.18f)/.7f,0,1);
            } else if(entity instanceof IllusionEntity illusion&&illusion.finalForm()) {
                grow=1;
            } else continue;
            if(grow<=0)continue;
            LivingEntity wearer=(LivingEntity)entity;
            TemporalCloth.BodyFrame frame=FRAMES.get(wearer.getUUID());
            if(frame==null||wearer.isInvisible())continue;
            if(wearer.position().distanceToSqr(camera)>4096)continue;
            alive.add(wearer.getUUID());
            TemporalCloth cloth=CLOTHS.computeIfAbsent(wearer.getUUID(),k->new TemporalCloth());
            cloth.tick(wearer,frame);
            int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(wearer.position().add(0,1,0)));
            draw(pose,out,cloth,grow,partial,light);
        }
        if(CLOTHS.size()>alive.size()*2+8)CLOTHS.keySet().retainAll(alive);
    }

    private static void draw(PoseStack pose,VertexConsumer out,TemporalCloth cloth,float grow,float partial,int light) {
        if(!cloth.ready())return;
        float span=grow*(TemporalCloth.ROWS-1);
        int full=(int)span;
        float frac=span-full;
        for(int row=0;row<TemporalCloth.ROWS-1;row++) {
            if(row>full)break;
            float blend=row==full?frac:1;
            if(blend<=1e-3f)break;
            for(int col=0;col<TemporalCloth.COLS-1;col++) {
                Vec3 topLeft=cloth.node(row,col,partial);
                Vec3 topRight=cloth.node(row,col+1,partial);
                Vec3 bottomLeft=cloth.node(row,col,partial).lerp(cloth.node(row+1,col,partial),blend);
                Vec3 bottomRight=cloth.node(row,col+1,partial).lerp(cloth.node(row+1,col+1,partial),blend);
                float v0=row/(float)(TemporalCloth.ROWS-1);
                float v1=(row+blend)/(TemporalCloth.ROWS-1);
                float u0=col/(float)(TemporalCloth.COLS-1);
                float u1=(col+1)/(float)(TemporalCloth.COLS-1);
                float[][] quad={vec(topLeft),vec(topRight),vec(bottomRight),vec(bottomLeft)};
                float[][] uv={{u0,v0},{u1,v0},{u1,v1},{u0,v1}};
                // Slightly darker toward the hem gives the cloth depth without a second texture.
                int shade=shade(v1);
                WorldEffects.quad(pose,out,quad,uv,light,shade,1);
                WorldEffects.quad(pose,out,new float[][]{quad[3],quad[2],quad[1],quad[0]},new float[][]{uv[3],uv[2],uv[1],uv[0]},light,shade,1);
            }
        }
    }

    private static int shade(float v) {
        int tone=Math.round(255-52*Mth.clamp(v,0,1));
        return tone<<16|tone<<8|tone;
    }
    private static float[] vec(Vec3 v) {return new float[]{(float)v.x,(float)v.y,(float)v.z};}
}
