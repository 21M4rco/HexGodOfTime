package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Progressive, textured model fragments: downstream beam stripping or a compact inward/outward burst. */
public final class ErasureRenderer {
    private ErasureRenderer() {}

    private record Fading(Vec3 direction,long start,int duration,float power,boolean implosion) {}
    private static final Map<Integer,Fading> FADING=new HashMap<>();
    /** The original buildup before the directional fade starts. */
    private static final float TAKEOVER=.18f;
    /** Ticks past the sequence before a body that is neither gone nor alive is given up on. */
    private static final int GRACE=10,ABANDON=2400;
    /** Ticks between bursts of dust. The sequence is long, so the rate is sparse rather than constant. */
    private static final int CADENCE=3;
    private static final double VISIBLE=72*72;

    public static void clear() {FADING.clear();ErasureBuffer.clear();}

    public static void begin(int entity,CompoundTag n) {
        if(n.getBoolean("clear")){FADING.remove(entity);return;}
        Vec3 direction=new Vec3(n.getDouble("dx"),n.getDouble("dy"),n.getDouble("dz"));
        if(direction.lengthSqr()<1e-8)direction=new Vec3(1,0,0);
        if(FADING.size()>48)FADING.clear();
        FADING.put(entity,new Fading(direction.normalize(),n.getLong("start"),Math.max(1,n.getInt("duration")),n.getFloat("power"),n.getBoolean("implosion")));
        var mc=Minecraft.getInstance();
        if(mc.level!=null&&mc.level.getEntity(entity)!=null)
            BranchAudio.erase(mc.level.getEntity(entity).position());
    }

    /** 0 to 1 across the sequence, or -1 when this entity is not being erased. */
    public static float progress(int entity,float partial) {
        Fading f=FADING.get(entity);
        if(f==null)return -1;
        return Mth.clamp((ClientState.now()+partial-f.start())/(float)f.duration(),0,1);
    }
    /** Hide only when every surface fragment has finished, including the subsequent corpse. */
    public static boolean consumed(Entity e) {
        float p=progress(e.getId(),Minecraft.getInstance().getFrameTime());
        return p>=1;
    }
    /** True while a body is being erased at all: used to keep a caught player's own controls off. */
    public static boolean erasing(Entity e) {return e!=null&&FADING.containsKey(e.getId());}

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null){clear();return;}
        long now=ClientState.now();
        FADING.entrySet().removeIf(e->{
            Entity victim=mc.level.getEntity(e.getKey());
            if(victim==null)return now>e.getValue().start()+e.getValue().duration()+GRACE;
            if(e.getValue().implosion()&&victim instanceof net.minecraft.world.entity.LivingEntity living){living.hurtTime=0;living.deathTime=0;}
            long over=now-e.getValue().start()-e.getValue().duration();
            if(over<GRACE)return false;
            // Past the sequence. A body that has gone, or that genuinely survived, can be let go of. One
            // still lying there dead stays hidden, because that corpse is the death animation.
            if(over>ABANDON)return true;
            return victim.isAlive();
        });
        if(FADING.isEmpty()||mc.player==null)return;
        if(now%CADENCE!=0)return;
        Vec3 eye=mc.player.getEyePosition();
        for(var entry:FADING.entrySet()) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null||e.position().distanceToSqr(eye)>VISIBLE)continue;
            Fading f=entry.getValue();
            float phase=Mth.clamp((now-f.start())/(float)f.duration(),0,1);
            if(phase>=1)continue;
            if(f.implosion())continue;
            Vec3 front=e.position().add(0,e.getBbHeight()*(1-phase*.7),0);
            // Dust, wisps and threads, all swept the way the torrent was going.
            Vfx.cone(HexGodOfStories.TEMPORAL_DUST.get(),front,f.direction(),Vfx.count(3+f.power()*3),.34,.16);
            Vfx.cone(HexGodOfStories.BRANCH_THREAD.get(),front,f.direction(),Vfx.count(1+f.power()*1.5f),.22,.14);
            Vfx.cloud(HexGodOfStories.NEBULA.get(),front,e.getBbWidth()+.3,Vfx.count(1.1f),.02);
            if(now%2==0)Vfx.spark(HexGodOfStories.SPECTRAL.get(),front,f.direction().scale(.08));
            if(phase>.8f)Vfx.spark(HexGodOfStories.STAR.get(),front,f.direction().scale(.05));
        }
    }

    // ---------------------------------------------------------------------- render ---

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        if(FADING.isEmpty())return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.now()+partial;
        for(var entry:new ArrayList<>(FADING.entrySet())) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null)continue;
            double distance=e.position().distanceToSqr(camera);
            if(distance>VISIBLE)continue;
            float phase=progress(entry.getKey(),partial);
            // Finished. Nothing is drawn, and nothing vanilla is drawn either, so the body is simply gone.
            if(phase<0||phase>=1)continue;
            try {
                if(entry.getValue().implosion())implosion(PAINTER,e,entry.getValue(),phase,partial,time);
                else {crawl(PAINTER,e,entry.getValue(),phase,partial,time);flare(PAINTER,e,entry.getValue(),phase,partial,time);}
            } catch(Exception ignored) {
                // A failed auxiliary arc must not interrupt the entity's own render.
            }
        }
        PAINTER.flush(pose,buffers);
    }

    /** Called at the entity render dispatch; foreign renderers keep their own model and textures. */
    public static MultiBufferSource fadingBuffers(Entity entity,float partial,PoseStack pose,MultiBufferSource buffers) {
        Fading f=FADING.get(entity.getId());
        float phase=progress(entity.getId(),partial);
        if(f==null||(!f.implosion()&&phase<TAKEOVER))return buffers;
        float fracture=f.implosion()?phase:Mth.clamp((phase-TAKEOVER)/(1-TAKEOVER),0,1);
        return new ErasureBuffer(buffers,pose.last().pose(),entity,f.direction(),fracture,f.power(),f.implosion());
    }

    /** A brief injection flash, followed by slow destabilization as individual body fragments detach. */
    private static void implosion(BranchVfx.Painter painter,Entity e,Fading fading,float phase,float partial,double time) {
        Vec3 centre=e.getPosition(partial).add(0,e.getBbHeight()*.5,0);
        double w=Math.max(.2,e.getBbWidth()),h=Math.max(.3,e.getBbHeight());
        float flash=Math.max(0,1-phase*fading.duration()/6f);
        BranchVfx.billboard(painter,BranchVfx.glow(),centre,Math.max(w,h)*(.6-phase*.25),
            time*.4,TimeBranchPalette.hot((float)time*.07f,.9f),flash*.8f);
        if(phase>.65f)return;
        double radius=phase<.10f?1-phase*1.4:.86+(phase-.10)*.55;
        for(int i=0;i<7;i++) {
            long seed=e.getId()*193L+i+(long)(time/TemporalLightning.FLICKER)*13;
            Vec3 tip=centre.add((TemporalLightning.rand(seed,1)-.5)*w*radius,
                (TemporalLightning.rand(seed,2)-.5)*h*radius,(TemporalLightning.rand(seed,3)-.5)*w*radius);
            TemporalLightning.drawBranch(painter,BranchVfx.strand(),TemporalLightning.bolt(seed,centre,tip,5,w*.1,1),
                .022,(float)(time*.07+i*.14),(1-phase)*.85f);
        }
    }

    /** A hot sheet of light exactly where the front is cutting, so the cut itself is visible. */
    private static void flare(BranchVfx.Painter painter,Entity e,Fading f,float phase,float partial,double time) {
        double width=Math.max(.2,e.getBbWidth()),height=Math.max(.3,e.getBbHeight());
        Vec3 centre=e.getPosition(partial).add(0,height*.5,0);
        Vec3 d=f.direction();
        double extent=(width*Math.abs(d.x)+height*Math.abs(d.y)+width*Math.abs(d.z))*.5;
        Vec3 at=centre.add(d.scale((phase*1.18-.09-.5)*2*Math.max(.1,extent)));
        double size=Math.max(width,height)*(.55+.25*Math.sin(time*.5))*(1-phase*.35);
        BranchVfx.billboard(painter,BranchVfx.glow(),at,size,time*.1,TimeBranchPalette.hot((float)(time*.08),.85f),
            (.42f-.2f*phase)*(phase<TAKEOVER?phase/TAKEOVER:1));
    }

    /** Arcs over whatever is left of the body. They go when it goes. */
    private static void crawl(BranchVfx.Painter painter,Entity e,Fading f,float phase,float partial,double time) {
        double width=Math.max(.2,e.getBbWidth()),height=Math.max(.3,e.getBbHeight());
        Vec3 base=e.getPosition(partial);
        int arcs=1+(int)(f.power()*2);
        for(int i=0;i<arcs;i++) {
            long seed=(long)(time/TemporalLightning.FLICKER)*89+e.getId()*7+i;
            // Both ends land on the part that has not been reached yet, so the arcs die with the body.
            double surviving=Math.max(.12,1-phase);
            Vec3 from=base.add((TemporalLightning.rand(seed,1)-.5)*width,height*(1-surviving*TemporalLightning.rand(seed,2)),(TemporalLightning.rand(seed,3)-.5)*width);
            Vec3 to=base.add((TemporalLightning.rand(seed,4)-.5)*width,height*(1-surviving*TemporalLightning.rand(seed,5)),(TemporalLightning.rand(seed,6)-.5)*width);
            TemporalLightning.drawBranch(painter,BranchVfx.strand(),TemporalLightning.bolt(seed,from,to,4,width*.32,1),
                width*.045,(float)(time*.06+i*.3),.55f*(1-phase*.45f));
        }
    }

}
