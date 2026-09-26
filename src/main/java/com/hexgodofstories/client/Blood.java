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
 */
public final class Blood {
    private Blood() {}

    public static final ResourceLocation POOL=HexGodOfStories.id("textures/blood_pool.png");
    private record Splat(Vec3 at,float yaw,double size,long start,int life) {}
    private static final List<Splat> SPLATS=new ArrayList<>();
    private static final Map<Integer,Vec3> LAST=new HashMap<>();
    private static final int MAX_SPLATS=96,LIFE=320;
    private static final double RANGE=1024,SPREAD=.55;

    /** Bodies a partial Scepter beam left on their last breath, by id, with the tick they fall. */
    private static final Map<Integer,Long> STANDING=new HashMap<>();

    public static void clear() {SPLATS.clear();LAST.clear();STANDING.clear();}

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
                if(random.nextInt(3)!=0)continue;
                Vec3 at=blades.isEmpty()
                    ?e.position().add((random.nextDouble()-.5)*e.getBbWidth(),e.getBbHeight()*(.35+random.nextDouble()*.4),(random.nextDouble()-.5)*e.getBbWidth())
                    :WoundAnchor.world(blades.get(random.nextInt(blades.size())),e,1);
                Vfx.spark(HexGodOfStories.BLOOD.get(),at,new Vec3((random.nextDouble()-.5)*.03,-.04,(random.nextDouble()-.5)*.03));
            }

            Vec3 previous=LAST.put(entry.getKey(),e.position());
            if(previous==null)continue;
            double travelled=previous.distanceToSqr(e.position());
            // A still body drips slowly; a running one leaves a trail.
            int odds=travelled>.0016?Math.max(2,9-stacks*2):40;
            if(random.nextInt(odds)!=0)continue;
            drop(mc,e,random.nextDouble()*.25+.2);
        }
        LAST.keySet().retainAll(seen);
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
            if(random.nextInt(5)==0)drop(mc,e,random.nextDouble()*.3+.25);
        }
    }

    /** Lays one pool on the surface below a body, following steps and slabs rather than assuming flat. */
    private static void drop(Minecraft mc,Entity e,double size) {
        if(SPLATS.size()>=MAX_SPLATS)SPLATS.remove(0);
        Vec3 from=e.position().add((mc.level.random.nextDouble()-.5)*e.getBbWidth()*.8,.1,(mc.level.random.nextDouble()-.5)*e.getBbWidth()*.8);
        var hit=mc.level.clip(new ClipContext(from,from.add(0,-3,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e));
        if(hit.getType()==HitResult.Type.MISS)return;
        SPLATS.add(new Splat(hit.getLocation().add(0,.012,0),mc.level.random.nextFloat()*360,size,ClientState.now(),LIFE));
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
            // Spreading wet, then drying: the pool opens quickly and thins away over its whole life.
            double size=splat.size*(SPREAD+(1-SPREAD)*Math.min(1,age/14.0));
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
