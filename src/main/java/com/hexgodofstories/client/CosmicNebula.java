package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.*;

/**
 * World-space cloud volumes: rotating, depth-sorted cloud layers around every flying keeper.
 *
 * <p>The cloud does not switch on. Each keeper carries a presence that eases toward one while they
 * fly and back toward zero when they land, and inside that the individual puffs come in staggered
 * along the body rather than all at once, so the nebula condenses out of nothing and thins away
 * again instead of blinking into existence around them.
 */
public final class CosmicNebula {
    private static ResourceLocation cloud;
    private record Puff(Vec3 at,float radius,float alpha,int tint,double rotation) {}
    /** Per-player presence, eased frame by frame; an entry is dropped once it has fully faded. */
    private static final Map<Integer,Float> PRESENCE=new HashMap<>();
    private static long presenceTick=Long.MIN_VALUE;
    /** Ticks the cloud takes to gather, and to disperse again. */
    private static final float GATHER=26,DISPERSE=34;
    public static void clear(){PRESENCE.clear();if(cloud!=null){Minecraft.getInstance().getTextureManager().release(cloud);cloud=null;}}
    private static ResourceLocation cloud() {
        if(cloud!=null)return cloud;
        int size=128;NativeImage image=new NativeImage(size,size,false);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            double u=(x+.5)/size*2-1,v=(y+.5)/size*2-1,r=u*u+v*v;
            double noise=RealmSky.fbm(u*4+8,v*4-5,3);
            double detail=RealmSky.fbm(u*11+noise*2,v*11,7);
            double density=Math.max(0,1-r)*Math.max(0,1-r)*Math.min(1,Math.max(0,(noise-.15)*2.8))*(.5+detail);
            int alpha=(int)(Math.min(1,density)*235);
            image.setPixelRGBA(x,y,alpha<<24|0x00ffffff);
        }
        cloud=Minecraft.getInstance().getTextureManager().register("hexgodofstories_cosmic_cloud",new DynamicTexture(image));
        return cloud;
    }
    /** Eases every keeper's presence once per client tick; render frames only read the result. */
    private static void advance(Minecraft mc) {
        long now=ClientState.now();
        if(presenceTick==now)return;
        long steps=presenceTick==Long.MIN_VALUE?1:Math.min(20,now-presenceTick);
        presenceTick=now;
        if(steps<=0)return;
        for(var player:mc.level.players()) {
            var d=ClientState.data(player.getId());
            boolean flying=player==mc.player?player.getAbilities().flying:d.getBoolean("cosmicFlying");
            boolean wanted=d.getBoolean("ascended")&&flying;
            float presence=PRESENCE.getOrDefault(player.getId(),0f);
            float step=(float)steps/(wanted?GATHER:DISPERSE);
            presence=wanted?Math.min(1,presence+step):Math.max(0,presence-step);
            if(presence<=0)PRESENCE.remove(player.getId());
            else PRESENCE.put(player.getId(),presence);
        }
        if(PRESENCE.size()>64)PRESENCE.clear();
    }

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.now()+partial;
        advance(mc);
        List<Puff> puffs=new ArrayList<>();
        for(var player:mc.level.players()) {
            var d=ClientState.data(player.getId());
            boolean local=player==mc.player;
            float presence=PRESENCE.getOrDefault(player.getId(),0f);
            if(presence<=.004f||ClientState.hidden(player)||player.isSpectator())continue;
            if(player.distanceToSqr(camera)>4096)continue;
            Vec3 body=player.getPosition(partial).add(0,.95,0);
            double phase=player.getId()*1.37,timePhase=time*.022+phase;
            float visibility=(local&&mc.options.getCameraType().isFirstPerson()?.24f:1)*presence;
            for(int i=0;i<38;i++) {
                double f=i/37.0,a=i*2.399963+timePhase*(i%2==0?1:-.7);
                // Puffs along the body wake in order, so the volume grows outward from the keeper.
                float share=Mth.clamp((presence-(float)f*.45f)/.55f,0,1);
                if(share<=.01f)continue;
                double radius=(.52+.34*Math.sin(f*Math.PI)+.14*Math.sin(time*.037+i*1.9))*(.45+.55*share);
                Vec3 point=body.add(Math.cos(a)*radius,(f-.5)*2.5*(.5+.5*share),Math.sin(a)*radius);
                point=point.subtract(player.getDeltaMovement().scale((1-f)*1.7));
                if(point.distanceToSqr(camera)<.36)continue;
                puffs.add(new Puff(point,(.62f+(float)Math.sin(f*Math.PI)*.36f)*(.4f+.6f*share),
                    visibility*share*(.24f+i%3*.035f),
                    i%5==0?0x8cfde6:i%3==0?0x10a269:0x32ef91,a));
            }
        }
        if(puffs.isEmpty())return;
        puffs.sort(Comparator.comparingDouble((Puff p)->p.at.distanceToSqr(camera)).reversed());
        RenderType type=RenderType.entityTranslucentEmissive(cloud());
        var out=buffers.getBuffer(type);
        Vector3f right=new Vector3f(1,0,0).rotate(mc.gameRenderer.getMainCamera().rotation());
        Vector3f up=new Vector3f(0,1,0).rotate(mc.gameRenderer.getMainCamera().rotation());
        Vec3 r=new Vec3(right.x,right.y,right.z),u=new Vec3(up.x,up.y,up.z);
        for(Puff p:puffs) {
            Vec3 rr=r.scale(Math.cos(p.rotation)).add(u.scale(Math.sin(p.rotation))).scale(p.radius);
            Vec3 uu=u.scale(Math.cos(p.rotation)).subtract(r.scale(Math.sin(p.rotation))).scale(p.radius);
            Vec3[] corners={p.at.subtract(rr).subtract(uu),p.at.add(rr).subtract(uu),p.at.add(rr).add(uu),p.at.subtract(rr).add(uu)};
            float[][] q=new float[4][3];
            for(int i=0;i<4;i++)q[i]=new float[]{(float)corners[i].x,(float)corners[i].y,(float)corners[i].z};
            WorldEffects.quad(pose,out,q,new float[][]{{0,1},{1,1},{1,0},{0,0}},15728880,p.tint,p.alpha);
        }
        buffers.endBatch(type);
    }
}
