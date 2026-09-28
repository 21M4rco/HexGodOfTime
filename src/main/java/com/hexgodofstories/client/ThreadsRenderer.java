package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Temporal Threads, seen. A body stuck in time is locked inside two clock rings of seidr light tilted across each
 * other, their hands trying to move on once a second and snapping back; threads of time are wound round it from
 * its feet to its crown; motes hang still about it; and as it is bound the threads fly out to it from the caster's
 * hand. In its last second the rings flicker, and when its moment resumes they break apart. A few hundred quads a
 * frame for each body held, gathered into the shared painter, and nothing at all past sixty-four blocks.
 */
public final class ThreadsRenderer {
    private ThreadsRenderer() {}

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();
    private static final double VISIBLE=64*64;
    /** Ticks the threads take to fly from the caster's hand to the body. */
    private static final double CAST=8;
    private static final int SEGMENTS=40,STRANDS=3,MOTES=10;
    private static final DustParticleOptions GOLD=new DustParticleOptions(new Vector3f(1f,.82f,.35f),1.1f),
        GREEN=new DustParticleOptions(new Vector3f(.4f,1f,.55f),1f);

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        if(ClientState.THREADS.isEmpty())return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.time(partial);
        RenderType glow=BranchVfx.glow();
        for(var entry:ClientState.THREADS.entrySet()) {
            ClientState.ThreadLink link=entry.getValue();
            Entity body=mc.level.getEntity(link.target());
            if(body==null||!body.isAlive()||body.position().distanceToSqr(camera)>VISIBLE)continue;
            double left=link.until()-time,age=link.start()>0?time-link.start():CAST+10;
            if(left<=0)continue;
            float alpha=(float)(Mth.clamp(left/8,0,1)*Mth.clamp(age/4,0,1));
            // Its last second: the hold is slipping.
            if(left<20)alpha*=.55f+.45f*(float)Math.abs(Math.sin(time*1.4));
            Vec3 feet=body.getPosition(partial);
            double height=body.getBbHeight(),width=body.getBbWidth();
            Vec3 centre=feet.add(0,height*.5,0);
            double radius=Math.max(width,height)*.55+.3;
            int seed=link.target();
            // Two clock rings, tilted across each other and still: time is stuck, and only their hands twitch.
            double a=(seed*.37)%(Math.PI*2);
            clock(glow,centre,radius,new Vec3(Math.sin(a),.45,Math.cos(a)).normalize(),time,0,alpha);
            clock(glow,centre,radius*.86,new Vec3(Math.cos(a),-.3,-Math.sin(a)).normalize(),time,.5f,alpha*.85f);
            threads(glow,feet,width*.5+.14,height+.12,seed,alpha);
            motes(glow,centre,radius,seed,time,alpha);
            Entity caster=mc.level.getEntity(entry.getKey());
            if(caster!=null&&age<CAST+8)cast(glow,caster,centre,age,partial,alpha);
        }
        PAINTER.flush(pose,buffers);
    }

    /** One clock ring square to {@code normal}: its rim, twelve hour marks, and a hand that twitches forward each second and snaps back. */
    private static void clock(RenderType type,Vec3 centre,double radius,Vec3 normal,double time,float phase,float alpha) {
        Vec3 u=BranchVfx.perpendicular(normal),v=normal.cross(u).normalize();
        Vec3 last=null;
        for(int i=0;i<=SEGMENTS;i++) {
            double t=i*Math.PI*2/SEGMENTS;
            Vec3 point=centre.add(u.scale(Math.cos(t)*radius)).add(v.scale(Math.sin(t)*radius));
            if(last!=null)BranchVfx.ribbon(PAINTER,type,last,point,.035,TemporalPalette.seidr(phase+i/(float)SEGMENTS*.5f),alpha*.9f);
            last=point;
        }
        for(int h=0;h<12;h++) {
            double t=h*Math.PI/6;
            Vec3 dir=u.scale(Math.cos(t)).add(v.scale(Math.sin(t)));
            BranchVfx.ribbon(PAINTER,type,centre.add(dir.scale(radius*(h%3==0?.8:.88))),centre.add(dir.scale(radius)),h%3==0?.04:.025,
                TemporalPalette.seidr(phase+.2f),alpha);
        }
        // Once a second the hand tries to move on, and cannot.
        double beat=time%20,twitch=beat<3?Math.sin(beat/3*Math.PI)*.16:0,hand=phase*Math.PI+1.1+twitch;
        Vec3 dir=u.scale(Math.cos(hand)).add(v.scale(Math.sin(hand)));
        BranchVfx.ribbon(PAINTER,type,centre,centre.add(dir.scale(radius*.72)),.03,TemporalPalette.seidr(phase+.35f),alpha);
        BranchVfx.billboard(PAINTER,type,centre,.07,0,TemporalPalette.seidr(phase+.1f),alpha);
    }

    /** Threads of time wound round the body from its feet to its crown, each its own colour along its length. */
    private static void threads(RenderType type,Vec3 feet,double radius,double height,int seed,float alpha) {
        for(int s=0;s<STRANDS;s++) {
            Vec3[] points=new Vec3[18];
            for(int i=0;i<points.length;i++) {
                double f=i/(double)(points.length-1),angle=s*Math.PI*2/STRANDS+f*Math.PI*3+seed;
                points[i]=feet.add(Math.cos(angle)*radius,f*height,Math.sin(angle)*radius);
            }
            BranchVfx.polyline(PAINTER,type,points,.03,s*.21f,.6f,alpha*.8f);
        }
    }

    /** Dust hanging still about it, caught mid-drift. */
    private static void motes(RenderType type,Vec3 centre,double radius,int seed,double time,float alpha) {
        for(int i=0;i<MOTES;i++) {
            double h=Math.sin(seed*12.9898+i*78.233)*43758.5453;
            double f=h-Math.floor(h),g=(f*7.13)%1,k=(f*3.71)%1;
            Vec3 at=centre.add((f-.5)*radius*2.2,(g-.5)*radius*2,(k-.5)*radius*2.2);
            BranchVfx.billboard(PAINTER,type,at,.035+.03*g,0,TemporalPalette.seidr((float)f),alpha*.7f);
        }
    }

    /** As it is bound: three threads flying from the caster's hand out to it, then fading. */
    private static void cast(RenderType type,Entity caster,Vec3 centre,double age,float partial,float alpha) {
        Vec3 hand=caster.getEyePosition(partial).add(caster.getViewVector(partial).scale(.6)).add(0,-.35,0);
        double reach=Mth.clamp(age/CAST,0,1),fade=age<CAST?1:1-(age-CAST)/8;
        Vec3 to=centre.subtract(hand);
        Vec3 side=BranchVfx.perpendicular(to.normalize()),up=side.cross(to.normalize()).normalize();
        for(int s=0;s<STRANDS;s++) {
            Vec3[] points=new Vec3[12];
            for(int i=0;i<points.length;i++) {
                double f=i/(double)(points.length-1)*reach,wave=Math.sin(f*Math.PI)*.35;
                double angle=s*Math.PI*2/STRANDS+f*4;
                points[i]=hand.add(to.scale(f)).add(side.scale(Math.cos(angle)*wave)).add(up.scale(Math.sin(angle)*wave));
            }
            BranchVfx.polyline(PAINTER,type,points,.035,s*.3f,.5f,(float)(alpha*fade));
        }
    }

    /** Its moment resumes: the rings break into gold and green, with the tick of time moving on. */
    public static void burst(int target) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Entity body=mc.level.getEntity(target);
        if(body==null||body.position().distanceToSqr(mc.player.position())>VISIBLE)return;
        Vec3 centre=body.position().add(0,body.getBbHeight()*.5,0);
        var random=mc.level.random;
        for(int i=0;i<28;i++) {
            Vec3 dir=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).normalize();
            mc.level.addParticle(i%2==0?GOLD:GREEN,centre.x+dir.x*.5,centre.y+dir.y*.5,centre.z+dir.z*.5,dir.x*.12,dir.y*.12,dir.z*.12);
        }
        for(int i=0;i<6;i++)mc.level.addParticle(ParticleTypes.END_ROD,centre.x,centre.y,centre.z,random.nextGaussian()*.08,random.nextGaussian()*.08,
            random.nextGaussian()*.08);
        mc.level.playLocalSound(centre.x,centre.y,centre.z,HexGodOfStories.RESUME.get(),SoundSource.PLAYERS,.5f,1.5f,false);
    }
}
