package com.loki.client;

import com.loki.Loki;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The unseen hand, made seeable.
 *
 * <p>An invisible hand that shows nothing but a ring of motes is indistinguishable from a bug, so the
 * grasp is drawn as an actual construct: five luminous fingers reaching out of the caster's palm and
 * closing around the body, a turning shell of seidr light holding it, a tether from palm to prize with
 * pulses running along it, shards orbiting the hold and a slow sigil turning underneath it. All of it is
 * gathered into the shared painter and comes out in a couple of draw calls.
 *
 * <p>The palette is deliberately not the ultimate's. This is Loki's ordinary sorcery, so it stays in the
 * greens and golds and never reaches for the violets that belong to raw temporal radiation.
 */
public final class GripRenderer {
    private GripRenderer() {}

    private record Grasp(int[] targets,double distance,long since) {}
    private static final Map<Integer,Grasp> GRASPS=new HashMap<>();
    private static final double VISIBLE=64*64;

    public static void clear() {GRASPS.clear();}

    public static void set(int caster,CompoundTag n) {
        if(n.getInt("count")<=0){GRASPS.remove(caster);return;}
        if(GRASPS.size()>24)GRASPS.clear();
        Grasp prior=GRASPS.get(caster);
        GRASPS.put(caster,new Grasp(n.getIntArray("targets"),n.getDouble("distance"),
            prior==null?ClientState.now():prior.since()));
    }

    /** True for a body currently held: its own movement input is taken away while it is. */
    public static boolean gripped(int entity) {
        for(Grasp g:GRASPS.values())for(int id:g.targets())if(id==entity)return true;
        return false;
    }

    public static void tick(long now) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null){GRASPS.clear();return;}
        GRASPS.keySet().removeIf(id->mc.level.getEntity(id)==null);
        if(now%2!=0)return;
        Vec3 eye=mc.player.getEyePosition();
        for(var entry:GRASPS.entrySet()) {
            Entity owner=mc.level.getEntity(entry.getKey());
            if(owner==null)continue;
            for(int id:entry.getValue().targets()) {
                Entity held=mc.level.getEntity(id);
                if(held==null||held.position().distanceToSqr(eye)>VISIBLE)continue;
                Vec3 centre=held.position().add(0,held.getBbHeight()*.5,0);
                // Drawn inward: the hold is taking hold of it, not radiating off it.
                Vfx.gather(Loki.EMBER.get(),centre,held.getBbWidth()+.9,Vfx.count(.8f),.07);
                if(now%6==0)Vfx.cloud(Loki.NEBULA.get(),centre,held.getBbWidth()*.8+.3,1,.004);
                if(now%8==0)Vfx.spark(Loki.GOLD_EMBER.get(),palm(owner,1),Vec3.ZERO);
            }
        }
    }

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        if(GRASPS.isEmpty())return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.now()+partial;
        RenderType glow=BranchVfx.glow(),strand=BranchVfx.strand(),cloud=BranchVfx.cloud();
        for(var entry:GRASPS.entrySet()) {
            Entity owner=mc.level.getEntity(entry.getKey());
            if(owner==null||owner.isInvisible())continue;
            Vec3 hand=palm(owner,partial);
            for(int id:entry.getValue().targets()) {
                Entity held=mc.level.getEntity(id);
                if(held==null)continue;
                if(held.position().distanceToSqr(camera)>VISIBLE)continue;
                Vec3 centre=held.getPosition(partial).add(0,held.getBbHeight()*.5,0);
                double radius=Math.max(held.getBbWidth(),held.getBbHeight())*.62+.22;
                shell(glow,cloud,centre,radius,time);
                fingers(strand,hand,centre,radius,time);
                tether(strand,glow,hand,centre,time);
                orbit(glow,centre,radius,time);
                sigil(glow,held.getPosition(partial).add(0,.05,0),radius,time);
            }
        }
        PAINTER.flush(pose,buffers);
    }

    /** A turning membrane of held light. Calmer than the ultimate's: this is a grip, not a rupture. */
    private static void shell(RenderType glow,RenderType cloud,Vec3 centre,double radius,double time) {
        int rings=6,sides=9;
        for(int r=0;r<rings;r++) {
            double th0=r/(double)rings*Math.PI,th1=(r+1)/(double)rings*Math.PI;
            for(int s=0;s<sides;s++) {
                double p0=s*Math.PI*2/sides+time*.02,p1=(s+1)*Math.PI*2/sides+time*.02;
                PAINTER.quad(glow,skin(centre,radius,th0,p0,time),skin(centre,radius,th0,p1,time),
                    skin(centre,radius,th1,p1,time),skin(centre,radius,th1,p0,time),
                    TemporalPalette.seidr((float)(time*.02+r*.11)),.055f);
            }
        }
        for(int i=0;i<3;i++) {
            double a=i*2.3+time*.016;
            BranchVfx.billboard(PAINTER,cloud,centre.add(Math.cos(a)*radius*.5,Math.sin(a*.7)*radius*.4,Math.sin(a)*radius*.5),
                radius*1.05,a,TemporalPalette.seidr((float)(time*.013+i*.3)),.075f);
        }
    }
    private static Vec3 skin(Vec3 centre,double radius,double theta,double phi,double time) {
        double x=Math.sin(theta)*Math.cos(phi),y=Math.cos(theta),z=Math.sin(theta)*Math.sin(phi);
        double r=radius*(1+.085*BranchVfx.wobble(x*2.3+time*.05,y*2.3,z*2.3));
        return centre.add(x*r,y*r,z*r);
    }

    /** Five fingers out of the palm, bowing outward on the way and closing on the body. */
    private static void fingers(RenderType strand,Vec3 hand,Vec3 centre,double radius,double time) {
        Vec3 along=centre.subtract(hand);
        if(along.lengthSqr()<1e-6)return;
        Vec3 axis=along.normalize();
        Vec3 side=BranchVfx.perpendicular(axis),up=side.cross(axis).normalize();
        for(int f=0;f<5;f++) {
            double a=f*Math.PI*2/5+time*.012;
            Vec3 out=side.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
            int steps=7;
            Vec3[] points=new Vec3[steps+1];
            for(int i=0;i<=steps;i++) {
                double t=i/(double)steps;
                // Bowed in the middle, converging on a knuckle sitting just off the body.
                double bow=Math.sin(t*Math.PI)*(radius*1.5+.25)+(1-t)*.08;
                double curl=t*t;
                points[i]=hand.add(along.scale(t)).add(out.scale(bow*(1-curl*.55)))
                    .add(out.scale(Math.sin(time*.14+f*1.3)*.05));
            }
            seidrLine(strand,points,.032,(float)(time*.023+f*.13),.5f);
        }
    }

    /** Palm to prize, with brightness running along it so the hold reads as live rather than as a wire. */
    private static void tether(RenderType strand,RenderType glow,Vec3 hand,Vec3 centre,double time) {
        int steps=10;
        Vec3[] points=new Vec3[steps+1];
        Vec3 along=centre.subtract(hand);
        Vec3 axis=along.lengthSqr()<1e-6?new Vec3(0,1,0):along.normalize();
        Vec3 side=BranchVfx.perpendicular(axis),up=side.cross(axis).normalize();
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;
            double sway=Math.sin(t*Math.PI)*.16;
            points[i]=hand.add(along.scale(t))
                .add(side.scale(Math.sin(time*.1+t*5)*sway))
                .add(up.scale(Math.cos(time*.12+t*4)*sway));
        }
        seidrLine(strand,points,.045,(float)(time*.03),.62f);
        for(int pulse=0;pulse<2;pulse++) {
            double at=((time*.055+pulse*.5)%1);
            Vec3 point=points[Math.min(steps,(int)(at*steps))];
            BranchVfx.billboard(PAINTER,glow,point,.11,time*.2,TemporalPalette.seidr((float)(time*.06)),.55f);
        }
    }

    private static void orbit(RenderType glow,Vec3 centre,double radius,double time) {
        for(int i=0;i<6;i++) {
            double a=i*Math.PI*2/6+time*.07*(i%2==0?1:-1);
            double tilt=Math.sin(time*.04+i)*radius*.55;
            Vec3 at=centre.add(Math.cos(a)*radius*1.3,tilt,Math.sin(a)*radius*1.3);
            BranchVfx.billboard(PAINTER,glow,at,.055+.02*Math.sin(time*.2+i),a,
                TemporalPalette.seidr((float)(time*.04+TemporalPalette.offset(i))),.55f);
        }
    }

    /** A slow sigil under the hold; it is what makes a floating body look supported. */
    private static void sigil(RenderType glow,Vec3 base,double radius,double time) {
        int marks=12;
        for(int i=0;i<marks;i++) {
            double a=i*Math.PI*2/marks-time*.025;
            Vec3 at=base.add(Math.cos(a)*radius*1.5,0,Math.sin(a)*radius*1.5);
            BranchVfx.billboard(PAINTER,glow,at,.05+(i%3==0?.03:0),a,
                TemporalPalette.seidr((float)(time*.02+i*.07)),.34f);
        }
    }

    /** A polyline in sorcery colours; the ultimate's spectral version lives in {@link BranchVfx}. */
    private static void seidrLine(RenderType type,Vec3[] points,double width,float phase,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-2);
            double taper=Math.sin(t*Math.PI)*.7+.3;
            BranchVfx.ribbon(PAINTER,type,points[i],points[i+1],width*taper,
                TemporalPalette.seidr(phase+t*.35f),alpha);
        }
    }

    /** The casting hand, on the caster's right and a little in front of them. */
    private static Vec3 palm(Entity e,float partial) {
        Vec3 forward=e.getLookAngle();
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        if(side.lengthSqr()<1e-6)side=new Vec3(1,0,0);
        return e.getEyePosition(partial).add(side.normalize().scale(.36)).add(forward.scale(.42)).add(0,-.18,0);
    }
}
