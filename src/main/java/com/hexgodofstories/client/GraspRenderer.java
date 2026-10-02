package com.hexgodofstories.client;

import com.hexgodofstories.server.GravityGrasp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Gravity Grasp, seen: the Gravity Well's black hole (WarpScene), small enough to hold on a palm. A sphere of true black
 * with a softer dark round it and a thin ring of bent light hugging its edge; about it a tilted accretion disk of fine
 * concentric rings, white-hot lilac within, violet, then deep purple without, each turning and flickering as it goes;
 * and streaks of light falling in out of the space round it. It grows as it is held. Drawn wherever the server says a
 * player is holding it (their {@code graspStart}), from the same place the server pulls toward, and nothing past
 * forty-eight blocks.
 */
public final class GraspRenderer {
    private GraspRenderer() {}

    /** Drawn in this order: what lies behind the dark, the dark, and what lies in front of it. */
    private static final BranchVfx.Painter BACK=new BranchVfx.Painter(),DARK=new BranchVfx.Painter(),FRONT=new BranchVfx.Painter();
    private static final double VISIBLE=48*48;
    private static final int RIM=28,RINGS=9,SEGMENTS=40,STREAKS=36;
    /** The Gravity Well's disk tilt, and its colours from the inside out (WarpScene, GRAVITY_WELL). */
    private static final double TILT=.28;
    private static final int HOT=0xf4d4ff,VIOLET=0xae67ff,DEEP=0x452464,FALLING=0xd6aaff;

    /** Where a player's hole hangs this frame: the same as the server's {@link GravityGrasp#hole}, smoothed for drawing. */
    static Vec3 hole(Player p,float partial) {
        Vec3 look=p.getViewVector(partial),flat=new Vec3(look.x,0,look.z);
        Vec3 right=flat.lengthSqr()<1e-6?Vec3.ZERO:new Vec3(-flat.z,0,flat.x).normalize();
        return p.getEyePosition(partial).add(look.scale(1.25)).add(right.scale(.3)).add(0,-.38,0);
    }

    /** How big it has grown, from a marble to a plum, over the whole hold. */
    private static double radius(Player p,double time) {
        long start=ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING);
        return .085+.075*Mth.clamp((time-start)/(GravityGrasp.MOST*.5),0,1);
    }

    private static boolean holding(Player p) {
        return ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING)>0&&!p.isSpectator();
    }

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        var mc=Minecraft.getInstance();
        if(mc.level==null){discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.time(partial);
        boolean any=false;
        for(Player p:mc.level.players()) {
            if(!holding(p))continue;
            Vec3 at=hole(p,partial);
            if(at.distanceToSqr(camera)>VISIBLE)continue;
            any=true;
            double r=radius(p,time);
            Vec3 right=BranchVfx.cameraRight(),up=BranchVfx.cameraUp();
            // The disk's plane: level with the caster's right and their forward tipped up by the Well's tilt, so it is
            // seen as the Well's is, a flattened ring about the dark.
            Vec3 look=p.getViewVector(partial),flat=new Vec3(look.x,0,look.z);
            flat=flat.lengthSqr()<1e-6?new Vec3(0,0,1):flat.normalize();
            Vec3 u=new Vec3(-flat.z,0,flat.x),v=flat.scale(Math.cos(TILT)).add(0,Math.sin(TILT),0),normal=u.cross(v).normalize();
            // The disk's far half first, then the dark over it, then the near half over the dark.
            disk(BACK,at,r,u,v,time,false);
            BranchVfx.billboard(DARK,BranchVfx.shadow(),at,r*1.6,0,0x000000,.45f);
            BranchVfx.billboard(DARK,BranchVfx.shadow(),at,r,0,0x000000,1f);
            // The photon ring: light bent right round the edge of it.
            Vec3 last=null;
            for(int i=0;i<=RIM;i++) {
                double a=i*Math.PI*2/RIM+time*.3;
                Vec3 point=at.add(right.scale(Math.cos(a)*r*1.06)).add(up.scale(Math.sin(a)*r*1.06));
                if(last!=null)BranchVfx.ribbon(FRONT,BranchVfx.glow(),last,point,r*.07,HOT,.9f);
                last=point;
            }
            disk(FRONT,at,r,u,v,time,true);
            // Light falling in out of the space round it, on spirals that tighten as they go.
            for(int i=0;i<STREAKS;i++) {
                double phase=((i*.37-time*.03)%1+1)%1,rr=r*(1.2+phase*4.2),a=i*2.399+time*.1+phase*2.5;
                Vec3 off=u.scale(Math.cos(a)*rr).add(v.scale(Math.sin(a)*rr)).add(normal.scale(Math.sin(i*1.7)*rr*.35));
                Vec3 from=at.add(off),to=from.add(at.subtract(from).normalize().scale(r*(.5+phase*1.4)));
                BranchVfx.ribbon(off.dot(camera.subtract(at))>0?FRONT:BACK,BranchVfx.glow(),from,to,r*.05,FALLING,(float)(.75*(1-phase*.6)));
            }
        }
        if(any){BACK.flush(pose,buffers);DARK.flush(pose,buffers);FRONT.flush(pose,buffers);}
        else discard();
    }

    private static void discard() {BACK.discard();DARK.discard();FRONT.discard();}

    /** One half of the accretion disk (the half nearer the camera, or the further): the Well's fine turning rings. */
    private static void disk(BranchVfx.Painter painter,Vec3 at,double r,Vec3 u,Vec3 v,double time,boolean near) {
        Vec3 camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 toward=camera.subtract(at);
        for(int ring=0;ring<RINGS;ring++) {
            double radius=r*(1.35+ring*.2),turn=time*(.16-ring*.008)+ring*.08;
            int colour=ring<2?HOT:ring<5?VIOLET:DEEP;
            Vec3 last=null;
            for(int i=0;i<=SEGMENTS;i++) {
                double a=i*Math.PI*2/SEGMENTS+turn;
                Vec3 point=at.add(u.scale(Math.cos(a)*radius)).add(v.scale(Math.sin(a)*radius));
                if(last!=null&&(point.add(last).scale(.5).subtract(at).dot(toward)>0)==near) {
                    float shine=(float)(.65+.35*Math.sin(a*3-time*.4));
                    BranchVfx.ribbon(painter,BranchVfx.glow(),last,point,r*.075,com.hexgodofstories.client.WarpMesh.shade(colour,shine),.6f);
                }
                last=point;
            }
        }
    }

    /** Portal motes streaming in from all round every hole in sight: they start out from it and fall into it. */
    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        for(Player p:mc.level.players()) {
            if(!holding(p))continue;
            Vec3 at=hole(p,1);
            if(at.distanceToSqr(eye)>VISIBLE)continue;
            for(int i=0;i<4;i++) {
                Vec3 from=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).normalize().scale(1+random.nextDouble()*2);
                mc.level.addParticle(ParticleTypes.PORTAL,at.x,at.y,at.z,from.x,from.y,from.z);
            }
        }
    }
}
