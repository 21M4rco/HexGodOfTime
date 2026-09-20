package com.loki.client;

import com.loki.Loki;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Shapes rather than showers. Every helper here places a small number of particles along a deliberate
 * figure — a ring, a spiral, a cone — because a designed silhouette reads better than density and keeps
 * the player visible inside their own magic.
 */
public final class Vfx {
    private Vfx() {}
    private static ClientLevel level() {return Minecraft.getInstance().level;}
    private static RandomSource random() {return Minecraft.getInstance().level.random;}

    public static void spark(ParticleOptions type,Vec3 at,Vec3 velocity) {
        ClientLevel level=level();
        if(level!=null)level.addParticle(type,at.x,at.y,at.z,velocity.x,velocity.y,velocity.z);
    }

    /** A flat ring of motes, optionally turned on its side. */
    public static void ring(ParticleOptions type,Vec3 centre,double radius,int count,double outward,double rise) {
        ClientLevel level=level();
        if(level==null)return;
        double phase=random().nextDouble()*Math.PI*2;
        for(int i=0;i<count;i++) {
            double a=phase+i*Math.PI*2/count;
            Vec3 edge=new Vec3(Math.cos(a),0,Math.sin(a));
            Vec3 at=centre.add(edge.scale(radius));
            level.addParticle(type,at.x,at.y,at.z,edge.x*outward,rise,edge.z*outward);
        }
    }

    /** A rising helix, used where magic gathers around a limb or a body. */
    public static void spiral(ParticleOptions type,Vec3 base,double radius,double height,int count,double turns) {
        ClientLevel level=level();
        if(level==null)return;
        for(int i=0;i<count;i++) {
            double t=i/(double)Math.max(1,count-1);
            double a=t*Math.PI*2*turns;
            Vec3 at=base.add(Math.cos(a)*radius,height*t,Math.sin(a)*radius);
            level.addParticle(type,at.x,at.y,at.z,-Math.sin(a)*.03,.02,Math.cos(a)*.03);
        }
    }

    /** A directed spray, for impacts and thrown steel. */
    public static void cone(ParticleOptions type,Vec3 at,Vec3 direction,int count,double speed,double spread) {
        ClientLevel level=level();
        if(level==null)return;
        RandomSource random=random();
        Vec3 forward=direction.lengthSqr()<1e-6?new Vec3(0,1,0):direction.normalize();
        for(int i=0;i<count;i++) {
            Vec3 jitter=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).scale(spread);
            Vec3 v=forward.scale(speed).add(jitter);
            level.addParticle(type,at.x,at.y,at.z,v.x,v.y,v.z);
        }
    }

    /** The green wisp trail that marks Loki's sorcery in flight. */
    public static void trail(Vec3 at,Vec3 velocity,int count) {
        ClientLevel level=level();
        if(level==null)return;
        RandomSource random=random();
        for(int i=0;i<count;i++) {
            Vec3 jitter=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).scale(.012);
            level.addParticle(Loki.EMBER.get(),at.x,at.y,at.z,velocity.x*-.12+jitter.x,velocity.y*-.12+jitter.y,velocity.z*-.12+jitter.z);
        }
    }

    /** One glyph struck open at a point, for the instant a spell resolves. */
    public static void glyph(Vec3 at) {spark(Loki.RUNE.get(),at,Vec3.ZERO);}
}
