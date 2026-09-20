package com.loki.client;

import com.loki.Loki;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Shapes rather than showers, and shapes that take time to form.
 *
 * <p>Every helper places a small number of particles along a deliberate figure — a ring, a spiral, a
 * cone, a volume — because a designed silhouette reads better than density and keeps the player
 * visible inside their own magic. On top of that, nothing an ability does is emitted in a single
 * frame: effects are scheduled here and released over a stretch of ticks, with the emission rate
 * following a curve that swells and falls. Combined with the fade-in built into every particle, a
 * spell gathers into being and disperses rather than switching on and off.
 */
public final class Vfx {
    private Vfx() {}

    private static ClientLevel level() {return Minecraft.getInstance().level;}
    private static RandomSource random() {
        ClientLevel level=level();
        return level==null?RandomSource.create():level.random;
    }

    // ------------------------------------------------------------------ scheduling ---

    /** One tick's worth of an unfolding effect. {@code progress} runs 0 to 1 across its whole life. */
    @FunctionalInterface public interface Emission { void emit(Vec3 origin,Vec3 look,float progress); }

    private record Pending(int entity,Vec3 offset,Vec3 fixed,Vec3 look,Emission emission,long start,int duration) {}
    private static final List<Pending> QUEUE=new ArrayList<>();
    private static final int MAX_QUEUE=48;

    /**
     * Releases {@code emission} once a tick for {@code duration} ticks. When {@code entity} names a
     * live entity the effect rides along with it, which is what keeps a transformation wrapped around
     * a walking player instead of being left behind at the spot the spell started.
     */
    public static void bloom(int entity,Vec3 origin,Vec3 look,int duration,Emission emission) {
        if(level()==null)return;
        if(QUEUE.size()>=MAX_QUEUE)QUEUE.remove(0);
        Entity source=entity<0||level()==null?null:level().getEntity(entity);
        Vec3 offset=source==null?Vec3.ZERO:origin.subtract(source.position());
        QUEUE.add(new Pending(source==null?-1:entity,offset,origin,look,emission,ClientState.now(),Math.max(1,duration)));
    }

    public static void tick() {
        if(QUEUE.isEmpty())return;
        ClientLevel level=level();
        if(level==null){QUEUE.clear();return;}
        long now=ClientState.now();
        Iterator<Pending> it=QUEUE.iterator();
        while(it.hasNext()) {
            Pending pending=it.next();
            long age=now-pending.start;
            if(age<0||age>=pending.duration){it.remove();continue;}
            Vec3 origin=pending.fixed;
            if(pending.entity>=0) {
                Entity source=level.getEntity(pending.entity);
                if(source!=null)origin=source.position().add(pending.offset);
            }
            try {
                pending.emission.emit(origin,pending.look,age/(float)pending.duration);
            } catch(Exception e) {
                it.remove();
            }
        }
    }
    public static void clear() {QUEUE.clear();}

    /** The swell: nothing at the edges, most of the material in the middle of an effect's life. */
    public static float swell(float progress) {return Mth.sin(Mth.clamp(progress,0,1)*(float)Math.PI);}
    /** Smoothstep, for radii and offsets that should ease rather than march. */
    public static float ease(float progress) {float t=Mth.clamp(progress,0,1);return t*t*(3-2*t);}
    /** Turns a fractional rate into a whole count without ever emitting a steady, mechanical stream. */
    public static int count(float rate) {
        int whole=(int)rate;
        return random().nextFloat()<rate-whole?whole+1:whole;
    }

    // -------------------------------------------------------------------- figures ---

    public static void spark(ParticleOptions type,Vec3 at,Vec3 velocity) {
        ClientLevel level=level();
        if(level!=null)level.addParticle(type,at.x,at.y,at.z,velocity.x,velocity.y,velocity.z);
    }

    /** A flat ring of motes, optionally turned on its side. */
    public static void ring(ParticleOptions type,Vec3 centre,double radius,int count,double outward,double rise) {
        ClientLevel level=level();
        if(level==null||count<=0)return;
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
        if(level==null||count<=0)return;
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
        if(level==null||count<=0)return;
        RandomSource random=random();
        Vec3 forward=direction.lengthSqr()<1e-6?new Vec3(0,1,0):direction.normalize();
        for(int i=0;i<count;i++) {
            Vec3 jitter=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).scale(spread);
            Vec3 v=forward.scale(speed).add(jitter);
            level.addParticle(type,at.x,at.y,at.z,v.x,v.y,v.z);
        }
    }

    /** A loose volume of soft cloud around a point: the flight nebula's look, at ability scale. */
    public static void cloud(ParticleOptions type,Vec3 centre,double radius,int count,double drift) {
        ClientLevel level=level();
        if(level==null||count<=0)return;
        RandomSource random=random();
        for(int i=0;i<count;i++) {
            Vec3 offset=new Vec3(random.nextGaussian(),random.nextGaussian()*.7,random.nextGaussian()).scale(radius*.42);
            Vec3 at=centre.add(offset);
            Vec3 v=offset.lengthSqr()<1e-6?Vec3.ZERO:offset.normalize().scale(drift);
            level.addParticle(type,at.x,at.y,at.z,v.x,v.y+drift*.25,v.z);
        }
    }

    /** A shell of cloud, used for wards and for the edge of a stopped moment. */
    public static void dome(ParticleOptions type,Vec3 centre,double radius,int count,double outward) {
        ClientLevel level=level();
        if(level==null||count<=0)return;
        RandomSource random=random();
        for(int i=0;i<count;i++) {
            double a=random.nextDouble()*Math.PI*2,tilt=Math.asin(random.nextDouble()*1.6-.6);
            Vec3 edge=new Vec3(Math.cos(a)*Math.cos(tilt),Math.sin(tilt),Math.sin(a)*Math.cos(tilt));
            Vec3 at=centre.add(edge.scale(radius));
            level.addParticle(type,at.x,at.y,at.z,edge.x*outward,edge.y*outward,edge.z*outward);
        }
    }

    /** Motes falling inward toward a point, for anything that assembles rather than bursts. */
    public static void gather(ParticleOptions type,Vec3 centre,double radius,int count,double speed) {
        ClientLevel level=level();
        if(level==null||count<=0)return;
        RandomSource random=random();
        for(int i=0;i<count;i++) {
            double a=random.nextDouble()*Math.PI*2;
            double height=(random.nextDouble()-.3)*radius*1.4;
            Vec3 at=centre.add(Math.cos(a)*radius,height,Math.sin(a)*radius);
            Vec3 toward=centre.subtract(at);
            if(toward.lengthSqr()<1e-6)continue;
            Vec3 v=toward.normalize().scale(speed);
            level.addParticle(type,at.x,at.y,at.z,v.x,v.y,v.z);
        }
    }

    /** A vertical column of cloud, the backbone of transformations and departures. */
    public static void column(ParticleOptions type,Vec3 base,double radius,double height,int count,float progress) {
        ClientLevel level=level();
        if(level==null||count<=0)return;
        RandomSource random=random();
        for(int i=0;i<count;i++) {
            double t=random.nextDouble();
            double a=random.nextDouble()*Math.PI*2;
            double r=radius*(.35+.65*Math.sin(t*Math.PI));
            Vec3 at=base.add(Math.cos(a)*r,height*t*ease(progress+.25f),Math.sin(a)*r);
            level.addParticle(type,at.x,at.y,at.z,-Math.sin(a)*.012,.03+t*.02,Math.cos(a)*.012);
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
