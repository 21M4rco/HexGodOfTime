package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.ThrownDagger;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Bleeding, seen rather than counted.
 *
 * <p>Wounds spatter from the blade that made them, not from an entity's centre, so a body with two
 * daggers in it bleeds from two places. A body that keeps moving leaves that blood behind it: small
 * pools are laid on whatever surface is actually underneath, they spread for a moment and then dry
 * out. Everything is bounded — a fixed pool budget, a view-distance cut, emission gated on movement
 * and stack count — so a long fight stains the ground without flooding the particle engine.
 *
 * <p>A Scepter beam's hole bleeds far harder than any blade: it pours from the hole itself in a steady
 * stream, spurts with every heartbeat, and pools under the body into a spreading puddle that lies there
 * long after, a red trail behind anything that walks on with it.
 */
public final class Blood {
    private Blood() {}

    public static final ResourceLocation POOL=HexGodOfStories.id("textures/blood_pool.png");
    /** A pool: where, turned how far, how wide at the most, since when, for how long, and over how many ticks it spreads. */
    private record Splat(Vec3 at,float yaw,double size,long start,int life,int spread) {}
    private static final List<Splat> SPLATS=new ArrayList<>();
    private static final Map<Integer,Vec3> LAST=new HashMap<>();
    /** Pools at once; how long a wound's pool lies, and a beam hole's puddle. */
    private static final int MAX_SPLATS=320,LIFE=600,PUDDLE_LIFE=900;
    private static final double RANGE=1024,SPREAD=.55;
    /** Ticks between the spurts of a beam's hole: a racing heart. */
    private static final int BEAT=18;

    /** Bodies bleeding from a Scepter beam's hole, by id. */
    private static final Set<Integer> POURING=new HashSet<>();

    public static void pouring(int entity,boolean pouring) {
        if(pouring){if(POURING.size()<256)POURING.add(entity);}
        else POURING.remove(entity);
    }

    /** Bodies a partial Scepter beam left on their last breath, by id, with the tick they fall. */
    private static final Map<Integer,Long> STANDING=new HashMap<>();

    public static void clear() {SPLATS.clear();LAST.clear();STANDING.clear();POURING.clear();}

    public static void lastMoments(int entity,long until) {
        if(until>ClientState.now()){if(STANDING.size()<128)STANDING.put(entity,until);}
        else STANDING.remove(entity);
    }

    /**
     * @param wounds embedded blades by the id of the body carrying them, gathered once by the caller's
     *               single pass over the visible entities.
     */
    public static void tick(Minecraft mc,Map<Integer,Integer> bleeding,Map<Integer,List<ThrownDagger>> wounds) {
        if(mc.level==null||mc.player==null)return;
        long now=ClientState.now();
        SPLATS.removeIf(s->now-s.start>s.life);
        standing(mc,now);
        POURING.retainAll(bleeding.keySet());
        if(bleeding.isEmpty()){LAST.clear();return;}
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        Set<Integer> seen=new HashSet<>();
        for(var entry:bleeding.entrySet()) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null||e.position().distanceToSqr(eye)>RANGE)continue;
            seen.add(entry.getKey());
            int stacks=Mth.clamp(entry.getValue(),1,5);
            List<ThrownDagger> blades=wounds.getOrDefault(entry.getKey(),List.of());

            // Spatter: from each blade if any are still in, otherwise from the body itself.
            for(int i=0;i<stacks;i++) {
                if(random.nextInt(2)!=0)continue;
                Vec3 at=blades.isEmpty()
                    ?e.position().add((random.nextDouble()-.5)*e.getBbWidth(),e.getBbHeight()*(.35+random.nextDouble()*.4),(random.nextDouble()-.5)*e.getBbWidth())
                    :WoundAnchor.world(blades.get(random.nextInt(blades.size())),e,1);
                Vfx.spark(HexGodOfStories.BLOOD.get(),at,new Vec3((random.nextDouble()-.5)*.03,-.04,(random.nextDouble()-.5)*.03));
            }

            Vec3 previous=LAST.put(entry.getKey(),e.position());
            if(previous==null)continue;
            boolean moving=previous.distanceToSqr(e.position())>.0016;
            if(POURING.contains(entry.getKey())){pour(mc,e,now,moving);continue;}
            // A still body drips; a running one leaves a trail.
            int odds=moving?Math.max(2,7-stacks):14;
            if(random.nextInt(odds)!=0)continue;
            drop(mc,e,null,random.nextDouble()*.3+.25,LIFE,14);
        }
        LAST.keySet().retainAll(seen);
    }

    /**
     * A Scepter beam's hole, pouring: a stream from the hole itself, a spurt thrown out of it with every
     * heartbeat, and a puddle spreading under the body — or a trail behind it, pool after pool, if it moves.
     */
    private static void pour(Minecraft mc,Entity e,long now,boolean moving) {
        var random=mc.level.random;
        Vec3 hole=BeamWounds.bleedPoint(e);
        if(hole==null)hole=e.position().add(0,e.getBbHeight()*.6,0);
        // Out of the body, through the hole, level with the ground: the way a spurt is thrown.
        double ox=hole.x-e.getX(),oz=hole.z-e.getZ(),out=Math.sqrt(ox*ox+oz*oz);
        if(out<.05){double a=random.nextDouble()*Math.PI*2;ox=Math.cos(a);oz=Math.sin(a);out=1;}
        ox/=out;oz/=out;
        for(int i=0;i<3;i++)
            Vfx.spark(HexGodOfStories.BLOOD.get(),hole.add((random.nextDouble()-.5)*.06,(random.nextDouble()-.5)*.06,(random.nextDouble()-.5)*.06),
                new Vec3(ox*.02+(random.nextDouble()-.5)*.02,-.03-random.nextDouble()*.05,oz*.02+(random.nextDouble()-.5)*.02));
        if(Math.floorMod(now+e.getId()*7L,BEAT)==0) {
            for(int i=0;i<12;i++) {
                double speed=.1+random.nextDouble()*.14;
                Vfx.spark(HexGodOfStories.BLOOD.get(),hole,new Vec3(ox*speed+(random.nextDouble()-.5)*.06,.04+random.nextDouble()*.1,oz*speed+(random.nextDouble()-.5)*.06));
            }
            // Where the spurt comes down.
            for(int i=0;i<2;i++) {
                double reach=.5+random.nextDouble()*1.1;
                drop(mc,e,new Vec3(hole.x+ox*reach+(random.nextDouble()-.5)*.4,hole.y,hole.z+oz*reach+(random.nextDouble()-.5)*.4),
                    random.nextDouble()*.2+.18,PUDDLE_LIFE,24);
            }
        }
        // Under the hole it pools and spreads; on the move it is laid down behind, one pool after another.
        if(random.nextInt(moving?2:3)!=0)return;
        double spread=e.getBbWidth()*.35;
        drop(mc,e,new Vec3(hole.x+(random.nextDouble()-.5)*spread,hole.y,hole.z+(random.nextDouble()-.5)*spread),
            random.nextDouble()*.4+.4,PUDDLE_LIFE,moving?20:60);
    }

    /**
     * A body on its last breath pours from the hole the beam left in it and pools where it stands, far
     * faster than an ordinary wound, for as long as it stays on its feet.
     */
    private static void standing(Minecraft mc,long now) {
        if(STANDING.isEmpty())return;
        STANDING.entrySet().removeIf(entry->entry.getValue()<=now||mc.level.getEntity(entry.getKey())==null);
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        for(int id:STANDING.keySet()) {
            Entity e=mc.level.getEntity(id);
            if(e==null||e.position().distanceToSqr(eye)>RANGE)continue;
            Vec3 at=BeamWounds.bleedPoint(e);
            if(at==null)at=e.position().add(0,e.getBbHeight()*.6,0);
            for(int i=0;i<2;i++)
                Vfx.spark(HexGodOfStories.BLOOD.get(),at.add((random.nextDouble()-.5)*.08,(random.nextDouble()-.5)*.08,(random.nextDouble()-.5)*.08),
                    new Vec3((random.nextDouble()-.5)*.05,-.02-random.nextDouble()*.04,(random.nextDouble()-.5)*.05));
            if(random.nextInt(3)==0)drop(mc,e,at,random.nextDouble()*.35+.3,PUDDLE_LIFE,40);
        }
    }

    /**
     * Lays one pool on the surface below {@code over} (anywhere under the body when null), following steps and
     * slabs rather than assuming flat. Each lies a hair above or below the next, so where pools overlap into a
     * puddle one is always drawn over the other rather than both flickering.
     */
    private static void drop(Minecraft mc,Entity e,Vec3 over,double size,int life,int spread) {
        if(SPLATS.size()>=MAX_SPLATS)SPLATS.remove(0);
        var random=mc.level.random;
        Vec3 from=over==null?e.position().add((random.nextDouble()-.5)*e.getBbWidth()*.8,.1,(random.nextDouble()-.5)*e.getBbWidth()*.8)
            :new Vec3(over.x,Math.min(over.y,e.getY()+.1),over.z);
        var hit=mc.level.clip(new ClipContext(from,from.add(0,-3,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e));
        // Nothing below within reach, or the pool would start inside a wall a spurt was thrown against.
        if(hit.getType()==HitResult.Type.MISS||hit.isInside())return;
        SPLATS.add(new Splat(hit.getLocation().add(0,.01+random.nextDouble()*.01,0),random.nextFloat()*360,size,ClientState.now(),life,spread));
    }

    public static void render(PoseStack pose,MultiBufferSource buffers,float partial) {
        if(SPLATS.isEmpty())return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)return;
        double now=ClientState.time(partial);
        VertexConsumer out=buffers.getBuffer(RenderType.entityTranslucent(POOL));
        for(Splat splat:SPLATS) {
            double age=now-splat.start;
            if(age<0)continue;
            float life=Mth.clamp((float)(age/splat.life),0,1);
            // Spreading wet, then drying: the pool opens over its first ticks and thins away over its whole life.
            double size=splat.size*(SPREAD+(1-SPREAD)*Math.min(1,age/splat.spread));
            float alpha=Mth.clamp((float)Math.min(1,age/4.0)*(1-life*life),0,1)*.85f;
            if(alpha<=.01f)continue;
            int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(splat.at.add(0,.1,0)));
            double cos=Math.cos(Math.toRadians(splat.yaw))*size,sin=Math.sin(Math.toRadians(splat.yaw))*size;
            Vec3 a=splat.at.add(-cos+sin,0,-sin-cos);
            Vec3 b=splat.at.add(cos+sin,0,sin-cos);
            Vec3 c=splat.at.add(cos-sin,0,sin+cos);
            Vec3 d=splat.at.add(-cos-sin,0,-sin+cos);
            float[][] quad={vec(a),vec(b),vec(c),vec(d)};
            WorldEffects.quad(pose,out,quad,new float[][]{{0,0},{1,0},{1,1},{0,1}},light,0xffffff,alpha);
        }
    }
    private static float[] vec(Vec3 v) {return new float[]{(float)v.x,(float)v.y,(float)v.z};}
}
