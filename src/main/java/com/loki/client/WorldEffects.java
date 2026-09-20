package com.loki.client;

import com.loki.Loki;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.*;
import org.joml.Vector3f;
import java.util.*;

/**
 * Client presentation uses particles, clouds and projections. Decorative wire rings and
 * orbiting strands are deliberately absent.
 */
public final class WorldEffects {
    public static final ResourceLocation WHITE=Loki.id("textures/white.png");
    private record Effect(int entity,String kind,Vec3 pos,long start,int duration) {}
    private record Echo(int entity,Vec3 pos,long end) {}
    private record Projection(Vec3 origin,long until,boolean preview,long shown,List<IllusoryStructure.Placement> blocks) {}
    private record Field(Vec3 centre,double radius,boolean stop,long started,long expires) {}
    private record Grip(int[] targets,double distance) {}

    private static final List<Effect> EFFECTS=new ArrayList<>();
    private static final List<Echo> ECHOES=new ArrayList<>();
    private static final Map<Integer,Projection> PROJECTIONS=new HashMap<>();
    private static final Map<Integer,Field> FIELDS=new HashMap<>();
    private static final Map<Integer,Grip> GRIPS=new HashMap<>();
    private static final Map<Integer,Integer> BLEEDING=new HashMap<>();
    private static final Set<Integer> POSED=new HashSet<>();
    private static final Map<Integer,Long> REFORMING=new HashMap<>(),SLIPPING=new HashMap<>();

    public static void clear() {
        EFFECTS.clear();ECHOES.clear();PROJECTIONS.clear();FIELDS.clear();GRIPS.clear();BLEEDING.clear();
        POSED.clear();REFORMING.clear();SLIPPING.clear();
    }

    public static void add(int entity,CompoundTag n) {
        String name=n.getString("effect");
        Vec3 pos=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        int duration=switch(name){case "ascend"->140;case "stop"->120;case "slip"->26;case "rift_open"->30;default->24;};
        if(EFFECTS.size()>=96)EFFECTS.remove(0);
        EFFECTS.add(new Effect(entity,name,pos,ClientState.now(),duration));
        var p=Minecraft.getInstance().player;
        if(p!=null&&p.distanceToSqr(pos)<1600)TemporalScreen.trigger(name,entity==p.getId());
        if(name.equals("arrive")||name.equals("disguise")||name.equals("rift_cross"))REFORMING.put(entity,ClientState.now()+18);
        if(name.equals("slip")) {
            SLIPPING.put(entity,ClientState.now()+26);
            for(int i=0;i<5;i++)ECHOES.add(new Echo(entity,pos.add(0,i*.08,i*.12),ClientState.now()+24+i*2));
        }
        if(name.equals("depart")||name.equals("arrive"))ECHOES.add(new Echo(entity,pos,ClientState.now()+18));
        emit(name,entity,pos);
    }

    public static void architecture(int caster,CompoundTag n) {
        Vec3 origin=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        List<IllusoryStructure.Placement> blocks=IllusoryStructure.build(n.getInt("design"),n.getInt("scale"),n.getInt("seed"),n.getFloat("yaw"));
        if(PROJECTIONS.size()>12)PROJECTIONS.clear();
        Projection prior=PROJECTIONS.get(caster);
        long shown=prior!=null&&!prior.preview&&!n.getBoolean("preview")?prior.shown:ClientState.now();
        PROJECTIONS.put(caster,new Projection(origin,n.getLong("until"),n.getBoolean("preview"),shown,blocks));
    }
    public static void field(int caster,CompoundTag n) {
        if(!n.getBoolean("active")){FIELDS.remove(caster);return;}
        FIELDS.put(caster,new Field(new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z")),n.getDouble("radius"),n.getBoolean("stop"),n.getLong("started"),n.getLong("expires")));
    }
    public static void grip(int caster,CompoundTag n) {
        if(n.getInt("count")<=0){GRIPS.remove(caster);return;}
        GRIPS.put(caster,new Grip(n.getIntArray("targets"),n.getDouble("distance")));
    }
    public static void bleeding(int entity,int stacks) {if(stacks<=0)BLEEDING.remove(entity);else BLEEDING.put(entity,stacks);}
    public static void memory(int entity,CompoundTag n) {
        int count=Math.min(24,n.getInt("count"));
        for(int i=0;i<count;i+=2) {
            CompoundTag p=n.getCompound("p"+i);
            ECHOES.add(new Echo(entity,new Vec3(p.getDouble("x"),p.getDouble("y"),p.getDouble("z")),ClientState.now()+80));
        }
    }

    public static void tick() {
        long now=ClientState.now();
        EFFECTS.removeIf(e->now>e.start+e.duration);
        ECHOES.removeIf(e->now>e.end);
        PROJECTIONS.values().removeIf(p->p.until<now);
        FIELDS.values().removeIf(f->f.expires<now);
        REFORMING.entrySet().removeIf(e->e.getValue()<now);
        SLIPPING.entrySet().removeIf(e->e.getValue()<now);
        while(ECHOES.size()>72)ECHOES.remove(0);
        ambient(now);
    }

    /** Continuous, low-rate emission for states rather than events. */
    private static void ambient(long now) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Vec3 eye=mc.player.getEyePosition();
        for(var entry:BLEEDING.entrySet()) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null||e.position().distanceToSqr(eye)>1024)continue;
            for(int i=0;i<entry.getValue();i++) {
                if(mc.level.random.nextInt(3)!=0)continue;
                Vec3 at=e.position().add((mc.level.random.nextDouble()-.5)*e.getBbWidth(),e.getBbHeight()*(.35+mc.level.random.nextDouble()*.4),(mc.level.random.nextDouble()-.5)*e.getBbWidth());
                Vfx.spark(Loki.BLOOD.get(),at,new Vec3(0,-.04,0));
            }
        }
        for(var id:ClientState.FROZEN.keySet()) {
            Entity e=mc.level.getEntity(id);
            if(e==null||e.position().distanceToSqr(eye)>1024||now%3!=0)continue;
            Vec3 at=e.position().add((mc.level.random.nextDouble()-.5)*(e.getBbWidth()+.6),e.getBbHeight()*mc.level.random.nextDouble(),(mc.level.random.nextDouble()-.5)*(e.getBbWidth()+.6));
            Vfx.spark(Loki.MOTE.get(),at,Vec3.ZERO);
        }
        for(Field f:FIELDS.values()) {
            if(f.centre.distanceToSqr(eye)>6400||now%2!=0)continue;
            double a=mc.level.random.nextDouble()*Math.PI*2,tilt=(mc.level.random.nextDouble()-.5)*Math.PI;
            Vec3 edge=new Vec3(Math.cos(a)*Math.cos(tilt),Math.sin(tilt)*.55,Math.sin(a)*Math.cos(tilt));
            Vfx.spark(f.stop?Loki.GOLD_EMBER.get():Loki.EMBER.get(),f.centre.add(edge.scale(f.radius)),edge.scale(-.01));
        }
        for(var entry:GRIPS.entrySet()) {
            Entity owner=mc.level.getEntity(entry.getKey());
            if(owner==null||now%2!=0)continue;
            for(int id:entry.getValue().targets) {
                Entity held=mc.level.getEntity(id);
                if(held==null)continue;
                Vfx.ring(Loki.EMBER.get(),held.position().add(0,held.getBbHeight()*.5,0),held.getBbWidth()*.7+.25,3,.01,.005);
            }
        }
        for(var entity:mc.level.entitiesForRendering()) {
            if(entity instanceof com.loki.entity.ThrownDagger dagger&&dagger.flying()) {
                if(dagger.position().distanceToSqr(eye)<1024)Vfx.trail(dagger.position(),dagger.getDeltaMovement(),1);
                continue;
            }
            if(!(entity instanceof com.loki.entity.RiftEntity rift)||now%2!=0)continue;
            if(rift.position().distanceToSqr(eye)>2304)continue;
            if(rift.vacuum()) {
                double angle=mc.level.random.nextDouble()*Math.PI*2;
                Vec3 at=rift.position().add(Math.cos(angle)*4,mc.level.random.nextDouble()*3,Math.sin(angle)*4);
                Vfx.spark(Loki.EMBER.get(),at,rift.position().add(0,1,0).subtract(at).scale(.13));
            }
            Vfx.cone(Loki.SHARD.get(),rift.position().add(0,1.1,0),new Vec3(mc.level.random.nextGaussian(),mc.level.random.nextGaussian()*.4,mc.level.random.nextGaussian()),1,.05,.05);
            Vfx.spark(Loki.GOLD_EMBER.get(),rift.position().add((mc.level.random.nextDouble()-.5)*1.8,.3+mc.level.random.nextDouble()*1.9,(mc.level.random.nextDouble()-.5)*1.8),new Vec3(0,.01,0));
        }
    }

    private static Vec3 hand(Entity e) {
        Vec3 forward=e.getLookAngle();
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        if(side.lengthSqr()<1e-6)side=new Vec3(1,0,0);
        return e.getEyePosition().add(side.normalize().scale(.36)).add(forward.scale(.42)).add(0,-.2,0);
    }

    private static void emit(String kind,int entity,Vec3 pos) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        Entity source=mc.level.getEntity(entity);
        Vec3 palm=source!=null?hand(source):pos.add(0,1.3,0);
        Vec3 look=source!=null?source.getLookAngle():new Vec3(0,1,0);
        ParticleOptions green=Loki.EMBER.get(),gold=Loki.GOLD_EMBER.get();
        switch(kind) {
            case "cast","hold","enchant","memory" -> {Vfx.glyph(palm);Vfx.ring(green,palm,.28,8,.03,.01);}
            case "conjure" -> {Vfx.spiral(green,palm.add(0,-.25,0),.17,.5,16,2.2);Vfx.spiral(gold,palm.add(0,-.2,0),.12,.42,10,1.6);}
            case "depart" -> {Vfx.ring(green,pos.add(0,.05,0),.42,14,.10,.05);Vfx.spiral(green,pos,.30,1.9,18,2.6);}
            case "arrive" -> {Vfx.ring(green,pos.add(0,.05,0),.5,14,-.09,.06);Vfx.spiral(green,pos,.22,1.9,14,2.0);}
            case "rift_cross" -> {Vfx.ring(gold,pos.add(0,1,0),.8,16,-.06,0);Vfx.cone(Loki.SHARD.get(),pos.add(0,1.1,0),look,10,.16,.09);}
            case "fracture","rift_open" -> {Vfx.cone(Loki.SHARD.get(),pos.add(0,1.1,0),look.scale(-1),22,.22,.18);Vfx.ring(gold,pos.add(0,1.1,0),.4,12,.22,.02);}
            case "rift_close" -> Vfx.ring(Loki.SHARD.get(),pos.add(0,1.1,0),1.5,16,-.3,-.02);
            case "slip" -> {Vfx.cone(gold,pos.add(0,1,0),look,14,.12,.16);Vfx.cone(green,pos.add(0,1,0),look.scale(-1),10,.09,.14);}
            case "stop" -> {Vfx.ring(gold,pos.add(0,.1,0),1.2,22,.55,.02);Vfx.ring(Loki.MOTE.get(),pos.add(0,1.1,0),1.6,16,.2,0);}
            case "dilate" -> Vfx.ring(gold,pos.add(0,.1,0),1.1,18,.36,.02);
            case "resume" -> Vfx.ring(gold,pos.add(0,.6,0),2.4,20,-.34,.01);
            case "bind","marked","command" -> {Vfx.ring(gold,pos.add(0,1,0),.9,14,.02,.02);Vfx.glyph(pos.add(0,1.2,0));}
            case "impact" -> Vfx.cone(green,pos.add(0,1,0),look,9,.16,.1);
            case "slash" -> Vfx.cone(green,palm,look,6,.22,.09);
            case "throw" -> Vfx.cone(green,palm,look,8,.3,.05);
            case "blade_bite" -> {Vfx.cone(Loki.BLOOD.get(),pos,look.scale(-1),8,.14,.1);Vfx.cone(green,pos,look.scale(-1),4,.1,.08);}
            case "hurl" -> Vfx.cone(green,pos.add(0,.9,0),look,10,.26,.12);
            case "dispel" -> {Vfx.ring(green,pos.add(0,.9,0),.75,14,-.14,.03);Vfx.spiral(green,pos,.2,1.4,10,1.6);}
            case "banked" -> Vfx.ring(gold,pos.add(0,1,0),.5,6,.02,.03);
            case "release" -> {Vfx.cone(Loki.BLOOD.get(),pos.add(0,1,0),new Vec3(0,1,0),14,.24,.2);Vfx.ring(gold,pos.add(0,1,0),1,16,.3,.05);}
            case "ward" -> Vfx.spiral(green,pos,.62,1.9,22,1.2);
            case "push" -> Vfx.ring(green,pos.add(0,.9,0),1,18,.42,.03);
            case "disguise" -> Vfx.spiral(green,pos,.42,1.9,20,2.6);
            case "ascend" -> {Vfx.spiral(gold,pos,.7,2.3,20,2.4);Vfx.spiral(green,pos,.5,2.3,16,-1.8);}
            default -> {}
        }
    }

    public static void beforePlayer(RenderPlayerEvent.Pre e) {
        Long until=SLIPPING.get(e.getEntity().getId());
        Long reform=REFORMING.get(e.getEntity().getId());
        if(reform!=null) {
            float phase=1-(reform-ClientState.now()-e.getPartialTick())/18f;
            e.getPoseStack().pushPose();POSED.add(e.getEntity().getId());
            float smooth=Mth.clamp(phase*phase*(3-2*phase),.02f,1);
            e.getPoseStack().scale(smooth,1,smooth);
            return;
        }
        if(until==null)return;
        float t=(until-ClientState.now()-e.getPartialTick())/26f;
        float wave=(float)Math.sin(t*Math.PI);
        e.getPoseStack().pushPose();POSED.add(e.getEntity().getId());
        e.getPoseStack().scale(1-wave*.18f,1+wave*.45f,1-wave*.28f);
        e.getPoseStack().mulPose(Axis.ZP.rotationDegrees((float)Math.sin(t*18)*wave*12));
    }
    public static void afterPlayer(RenderPlayerEvent.Post e) {if(POSED.remove(e.getEntity().getId()))e.getPoseStack().popPose();}

    public static void render(RenderLevelStageEvent event) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        PoseStack pose=event.getPoseStack();
        Vec3 camera=event.getCamera().getPosition();
        float partial=event.getPartialTick();
        pose.pushPose();
        pose.translate(-camera.x,-camera.y,-camera.z);
        var buffers=mc.renderBuffers().bufferSource();
        var out=buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        double now=ClientState.now()+partial;

        for(Projection projection:PROJECTIONS.values()) {
            if(projection.origin.distanceToSqr(camera)>2304)continue;
            float build=Mth.clamp((float)(now-projection.shown)/14f,0,1);
            int height=projection.blocks.stream().mapToInt(b->b.offset().getY()).max().orElse(1)+1;
            int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(projection.origin.add(0,1,0)));
            for(IllusoryStructure.Placement block:projection.blocks) {
                if(block.offset().getY()>build*height)continue;
                pose.pushPose();
                pose.translate(projection.origin.x+block.offset().getX(),projection.origin.y+block.offset().getY(),projection.origin.z+block.offset().getZ());
                mc.getBlockRenderer().renderSingleBlock(block.state(),pose,buffers,light,OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }

        for(Echo echo:ECHOES) {
            Entity entity=mc.level.getEntity(echo.entity);
            if(!(entity instanceof AbstractClientPlayer p))continue;
            var renderer=mc.getEntityRenderDispatcher().getRenderer(p);
            if(!(renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer))continue;
            pose.pushPose();
            pose.translate(echo.pos.x,echo.pos.y,echo.pos.z);
            pose.mulPose(Axis.YP.rotationDegrees(180-p.yBodyRot));
            pose.scale(-1,-1,1);
            pose.translate(0,-1.501,0);
            float alpha=(float)Math.min(.23f,(echo.end-now)/60f);
            var model=playerRenderer.getModel();
            model.setupAnim(p,0,0,p.tickCount,p.getYHeadRot()-p.yBodyRot,p.getXRot());
            model.renderToBuffer(pose,buffers.getBuffer(RenderType.entityTranslucent(p.getSkinTextureLocation())),15728880,OverlayTexture.NO_OVERLAY,.55f,.95f,.7f,Math.max(0,alpha));
            pose.popPose();
        }

        CapeRenderer.renderAll(pose,buffers,partial);
        CosmicNebula.render(pose,buffers,partial);
        pose.popPose();
        buffers.endBatch(RenderType.entityTranslucent(WHITE));
        buffers.endBatch(RenderType.entityCutoutNoCull(LokiLayer.CLOTH));
    }

    public static void ribbon(PoseStack pose,VertexConsumer out,Vec3 a,Vec3 b,float width,int color,float alpha) {
        Vec3 view=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().subtract(a).normalize();
        Vec3 side=b.subtract(a).cross(view);
        if(side.lengthSqr()<1e-12)return;
        side=side.normalize().scale(width);
        Vec3[] v={a.subtract(side),a.add(side),b.add(side),b.subtract(side)};
        float[][] q=new float[4][3];
        for(int i=0;i<4;i++)q[i]=new float[]{(float)v[i].x,(float)v[i].y,(float)v[i].z};
        quad(pose,out,q,new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,color,alpha);
    }
    public static void quad(PoseStack pose,VertexConsumer out,float[][] v,float[][] uv,int light,int color,float alpha) {
        Vector3f n=new Vector3f(v[1][0]-v[0][0],v[1][1]-v[0][1],v[1][2]-v[0][2])
            .cross(new Vector3f(v[2][0]-v[0][0],v[2][1]-v[0][1],v[2][2]-v[0][2]));
        if(n.lengthSquared()<1e-12f)return;
        n.normalize();
        for(int i=0;i<4;i++)
            out.vertex(pose.last().pose(),v[i][0],v[i][1],v[i][2])
               .color(color>>16&255,color>>8&255,color&255,Mth.clamp((int)(alpha*255),0,255))
               .uv(uv[i][0],uv[i][1])
               .overlayCoords(OverlayTexture.NO_OVERLAY)
               .uv2(light)
               .normal(pose.last().normal(),n.x,n.y,n.z)
               .endVertex();
    }
}
