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
    private record Echo(int entity,Vec3 pos,long end) {}
    private record Projection(Vec3 origin,long until,boolean preview,long shown,List<IllusoryStructure.Placement> blocks) {}
    private record Field(Vec3 centre,double radius,boolean stop,long started,long expires) {}
    private record Grip(int[] targets,double distance) {}

    private static final List<Echo> ECHOES=new ArrayList<>();
    private static final Map<Integer,Projection> PROJECTIONS=new HashMap<>();
    private static final Map<Integer,Field> FIELDS=new HashMap<>();
    private static final Map<Integer,Grip> GRIPS=new HashMap<>();
    private static final Map<Integer,Integer> BLEEDING=new HashMap<>();
    private static final Set<Integer> POSED=new HashSet<>();
    private static final Map<Integer,Long> REFORMING=new HashMap<>(),SLIPPING=new HashMap<>();

    public static void clear() {
        ECHOES.clear();PROJECTIONS.clear();FIELDS.clear();GRIPS.clear();BLEEDING.clear();
        POSED.clear();REFORMING.clear();SLIPPING.clear();
        Vfx.clear();Blood.clear();
    }

    public static void add(int entity,CompoundTag n) {
        String name=n.getString("effect");
        Vec3 pos=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        var p=Minecraft.getInstance().player;
        if(p!=null&&p.distanceToSqr(pos)<1600)TemporalScreen.trigger(name,entity==p.getId());
        if(name.equals("arrive")||name.equals("disguise")||name.equals("rift_cross")||name.equals("arrive_realm"))REFORMING.put(entity,ClientState.now()+18);
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
    /**
     * @return the tick at which a local hold took this point, or {@link Long#MIN_VALUE} when time is
     *         still running there. Weather and loose particles read this to stop where they are.
     */
    public static long heldSince(double x,double y,double z) {
        long best=Long.MIN_VALUE;
        for(Field f:FIELDS.values()) {
            if(!f.stop)continue;
            double dx=f.centre.x-x,dy=f.centre.y-y,dz=f.centre.z-z;
            if(dx*dx+dy*dy+dz*dz>f.radius*f.radius)continue;
            best=Math.max(best,f.started);
        }
        return best;
    }
    public static boolean anyHold() {
        for(Field f:FIELDS.values())if(f.stop)return true;
        return false;
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
        ECHOES.removeIf(e->now>e.end);
        PROJECTIONS.values().removeIf(p->p.until<now);
        FIELDS.values().removeIf(f->f.expires<now);
        REFORMING.entrySet().removeIf(e->e.getValue()<now);
        SLIPPING.entrySet().removeIf(e->e.getValue()<now);
        while(ECHOES.size()>72)ECHOES.remove(0);
        Vfx.tick();
        ambient(now);
    }

    /**
     * Continuous, low-rate emission for states rather than events. Everything here is gated on one
     * pass over the entities already being rendered, on a view-distance cut and on a slow cadence,
     * so a battlefield full of wounded, suspended and held bodies stays cheap.
     */
    private static void ambient(long now) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Vec3 eye=mc.player.getEyePosition();
        for(var id:ClientState.FROZEN.keySet()) {
            Entity e=mc.level.getEntity(id);
            if(e==null||e.position().distanceToSqr(eye)>1024||now%3!=0)continue;
            Vec3 at=e.position().add((mc.level.random.nextDouble()-.5)*(e.getBbWidth()+.6),e.getBbHeight()*mc.level.random.nextDouble(),(mc.level.random.nextDouble()-.5)*(e.getBbWidth()+.6));
            Vfx.spark(Loki.MOTE.get(),at,Vec3.ZERO);
        }
        for(Field f:FIELDS.values()) {
            if(f.centre.distanceToSqr(eye)>6400)continue;
            // The held edge keeps breathing so a long suspension never settles into a static shell.
            if(now%2==0) {
                double a=mc.level.random.nextDouble()*Math.PI*2,tilt=(mc.level.random.nextDouble()-.5)*Math.PI;
                Vec3 edge=new Vec3(Math.cos(a)*Math.cos(tilt),Math.sin(tilt)*.55,Math.sin(a)*Math.cos(tilt));
                Vfx.spark(f.stop?Loki.GOLD_EMBER.get():Loki.EMBER.get(),f.centre.add(edge.scale(f.radius)),edge.scale(-.01));
            }
            if(f.stop&&now%3==0)Vfx.cloud(Loki.VEIL.get(),f.centre.add(0,1,0),f.radius*.75,1,.002);
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
        // One pass: blade trails, rift breath, and the embedded steel each wound bleeds from.
        Map<Integer,List<com.loki.entity.ThrownDagger>> wounds=new HashMap<>();
        for(var entity:mc.level.entitiesForRendering()) {
            if(entity instanceof com.loki.entity.ThrownDagger dagger) {
                if(dagger.flying()) {
                    if(dagger.position().distanceToSqr(eye)<1024)Vfx.trail(dagger.position(),dagger.getDeltaMovement(),1);
                } else if(!BLEEDING.isEmpty()) {
                    Entity host=dagger.carrier();
                    if(host!=null&&BLEEDING.containsKey(host.getId()))
                        wounds.computeIfAbsent(host.getId(),k->new ArrayList<>()).add(dagger);
                }
                continue;
            }
            if(entity instanceof com.loki.entity.StarfallEntity star) {
                if(star.position().distanceToSqr(eye)>4096)continue;
                float charge=star.charge();
                Vec3 back=star.getDeltaMovement().lengthSqr()<1e-6?Vec3.ZERO:star.getDeltaMovement().normalize().scale(-.06);
                Vfx.cloud(Loki.NEBULA.get(),star.position(),.3+.3*charge,1+(now%2==0?1:0),.01);
                Vfx.spark(Loki.EMBER.get(),star.position(),back);
                // Unstable debris: irregular, and heavier as the star closes on its quarry.
                if(mc.level.random.nextFloat()<.25f+charge*.45f)
                    Vfx.cone(Loki.SHARD.get(),star.position(),back,1,.06,.09);
                if(charge>.55f&&now%3==0)Vfx.spark(Loki.STAR.get(),star.position(),Vec3.ZERO);
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
            if(now%6==0)Vfx.cloud(Loki.NEBULA.get(),rift.position().add(0,1.3,0),1.1,1,.006);
        }
        Blood.tick(mc,BLEEDING,wounds);
    }

    private static Vec3 hand(Entity e) {
        Vec3 forward=e.getLookAngle();
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        if(side.lengthSqr()<1e-6)side=new Vec3(1,0,0);
        return e.getEyePosition().add(side.normalize().scale(.36)).add(forward.scale(.42)).add(0,-.2,0);
    }

    /**
     * Schedules an ability's presentation. Nothing is emitted on the frame the spell lands: each of
     * these unfolds across a stretch of ticks, and inside that stretch the rate follows a swell, so
     * the effect gathers, peaks and disperses. The palette is shared deliberately — the same soft
     * nebula volume sits behind every ability, with the sharp material on top of it changed per
     * spell — which is what keeps a dozen different effects reading as one mod.
     */
    private static void emit(String kind,int entity,Vec3 pos) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        Entity source=mc.level.getEntity(entity);
        Vec3 palm=source!=null?hand(source):pos.add(0,1.3,0);
        Vec3 look=source!=null?source.getLookAngle():new Vec3(0,1,0);
        ParticleOptions green=Loki.EMBER.get(),gold=Loki.GOLD_EMBER.get();
        ParticleOptions nebula=Loki.NEBULA.get(),veil=Loki.VEIL.get(),star=Loki.STAR.get(),smoke=Loki.SMOKE.get();
        switch(kind) {
            case "cast","hold","enchant","memory" -> Vfx.bloom(entity,palm,look,14,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.ring(green,at,.16+.22*Vfx.ease(t),Vfx.count(swell*3.5f),.02,.008);
                Vfx.cloud(nebula,at,.34,Vfx.count(swell*1.4f),.01);
                if(t<.12f)Vfx.glyph(at);
            });
            case "conjure" -> Vfx.bloom(entity,palm,look,18,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.gather(green,at.add(0,-.1,0),.55*(1-Vfx.ease(t))+.1,Vfx.count(swell*3f),.09);
                Vfx.cloud(nebula,at,.26,Vfx.count(swell),.008);
                if(t>.6f)Vfx.spark(star,at,Vec3.ZERO);
            });
            case "depart" -> Vfx.bloom(entity,pos,look,14,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.column(nebula,at,.42,2.1,Vfx.count(swell*3f),t);
                Vfx.ring(green,at.add(0,.05,0),.34+.5*Vfx.ease(t),Vfx.count(swell*4f),.06,.04);
            });
            case "arrive" -> Vfx.bloom(entity,pos,look,16,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.gather(green,at.add(0,.9,0),1.4*(1-Vfx.ease(t))+.2,Vfx.count(swell*4f),.12);
                Vfx.cloud(nebula,at.add(0,.9,0),.55,Vfx.count(swell*1.6f),.01);
                if(t>.75f)Vfx.spark(star,at.add(0,1,0),Vec3.ZERO);
            });
            // Surfacing inside the sanctum: nebula gathering into the shape of a body, nothing more.
            // No mirror, no shards, no ring — the break itself is left behind on the other side.
            case "arrive_realm" -> Vfx.bloom(entity,pos,look,18,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.gather(nebula,at.add(0,1,0),1.1*(1-Vfx.ease(t))+.2,Vfx.count(swell*2.4f),.09);
                Vfx.cloud(veil,at.add(0,.9,0),.5,Vfx.count(swell*1.2f),.006);
            });
            case "rift_cross" -> Vfx.bloom(entity,pos,look,16,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.ring(gold,at.add(0,1,0),.9*(1-Vfx.ease(t))+.1,Vfx.count(swell*4f),-.05,0);
                Vfx.cone(Loki.SHARD.get(),at.add(0,1.1,0),aim,Vfx.count(swell*2.5f),.16,.09);
                Vfx.cloud(nebula,at.add(0,1,0),.6,Vfx.count(swell),.012);
            });
            case "fracture","rift_open" -> Vfx.bloom(entity,pos,look,22,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cone(Loki.SHARD.get(),at.add(0,1.1,0),aim.scale(-1),Vfx.count(swell*4f),.22,.18);
                Vfx.ring(gold,at.add(0,1.1,0),.25+.5*Vfx.ease(t),Vfx.count(swell*3f),.14,.02);
                Vfx.cloud(smoke,at.add(0,1.1,0),.8,Vfx.count(swell*1.4f),.02);
            });
            case "rift_close" -> Vfx.bloom(entity,pos,look,14,(at,aim,t)->{
                Vfx.gather(Loki.SHARD.get(),at.add(0,1.1,0),1.6*(1-Vfx.ease(t))+.2,Vfx.count(Vfx.swell(t)*4f),.18);
                Vfx.cloud(smoke,at.add(0,1.1,0),.5,Vfx.count(Vfx.swell(t)),.01);
            });
            case "slip" -> Vfx.bloom(entity,pos,look,20,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cone(gold,at.add(0,1,0),aim,Vfx.count(swell*3f),.12,.16);
                Vfx.cloud(nebula,at.add(0,1,0),.7,Vfx.count(swell*1.5f),.006);
                Vfx.cone(green,at.add(0,1,0),aim.scale(-1),Vfx.count(swell*2f),.09,.14);
            });
            // A stopped moment arrives slowly on purpose: the edge of the field walks outward and the
            // suspended dust thickens behind it, so the world looks like it is being held, not switched.
            case "stop" -> Vfx.bloom(entity,pos,look,46,(at,aim,t)->{
                float swell=Vfx.swell(t);
                double radius=1+10*Vfx.ease(Math.min(1,t*1.6f));
                Vfx.ring(gold,at.add(0,.1,0),radius,Vfx.count(swell*7f),.03,.006);
                Vfx.dome(veil,at.add(0,1,0),radius*.82,Vfx.count(swell*3f),.004);
                Vfx.cloud(nebula,at.add(0,1.1,0),1.6,Vfx.count(swell*1.6f),.004);
                Vfx.ring(Loki.MOTE.get(),at.add(0,1.1,0),radius*.55,Vfx.count(swell*4f),.004,.002);
            });
            case "dilate" -> Vfx.bloom(entity,pos,look,34,(at,aim,t)->{
                float swell=Vfx.swell(t);
                double radius=1+7*Vfx.ease(Math.min(1,t*1.7f));
                Vfx.ring(gold,at.add(0,.1,0),radius,Vfx.count(swell*5f),.02,.005);
                Vfx.cloud(veil,at.add(0,1,0),1.3,Vfx.count(swell*1.3f),.005);
            });
            case "resume" -> Vfx.bloom(entity,pos,look,18,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.gather(gold,at.add(0,.9,0),3.2*(1-Vfx.ease(t))+.3,Vfx.count(swell*6f),.22);
                Vfx.cloud(nebula,at.add(0,1,0),.9,Vfx.count(swell),.02);
                if(t>.8f)Vfx.spark(star,at.add(0,1.1,0),Vec3.ZERO);
            });
            case "bind","marked","command" -> Vfx.bloom(entity,pos,look,14,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.ring(gold,at.add(0,1,0),.55+.4*Vfx.ease(t),Vfx.count(swell*3.5f),.01,.01);
                Vfx.cloud(nebula,at.add(0,1,0),.5,Vfx.count(swell),.006);
                if(t<.1f)Vfx.glyph(at.add(0,1.2,0));
            });
            case "impact" -> Vfx.bloom(entity,pos,look,9,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cone(green,at.add(0,1,0),aim,Vfx.count(swell*4f),.16,.1);
                if(t<.2f)Vfx.spark(star,at.add(0,1,0),Vec3.ZERO);
                Vfx.cloud(nebula,at.add(0,1,0),.4,Vfx.count(swell*.8f),.012);
            });
            case "slash" -> Vfx.bloom(entity,palm,look,7,(at,aim,t)->
                Vfx.cone(green,at,aim,Vfx.count(Vfx.swell(t)*3f),.22,.09));
            case "throw" -> Vfx.bloom(entity,palm,look,8,(at,aim,t)->{
                Vfx.cone(green,at,aim,Vfx.count(Vfx.swell(t)*3f),.3,.05);
                Vfx.cloud(nebula,at,.25,Vfx.count(Vfx.swell(t)*.7f),.01);
            });
            case "blade_bite" -> Vfx.bloom(-1,pos,look,12,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cone(Loki.BLOOD.get(),at,aim.scale(-1),Vfx.count(swell*3f),.14,.1);
                if(t<.2f)Vfx.cone(green,at,aim.scale(-1),3,.1,.08);
            });
            case "hurl" -> Vfx.bloom(entity,pos.add(0,.9,0),look,10,(at,aim,t)->
                Vfx.cone(green,at,aim,Vfx.count(Vfx.swell(t)*4f),.26,.12));
            case "dispel" -> Vfx.bloom(-1,pos,look,18,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cloud(smoke,at.add(0,.9,0),.55+.5*Vfx.ease(t),Vfx.count(swell*2f),.018);
                Vfx.ring(green,at.add(0,.9,0),.7*(1-Vfx.ease(t)*.6),Vfx.count(swell*3f),-.08,.02);
            });
            case "banked" -> Vfx.bloom(entity,pos,look,10,(at,aim,t)->
                Vfx.ring(gold,at.add(0,1,0),.4,Vfx.count(Vfx.swell(t)*2f),.02,.03));
            case "release" -> Vfx.bloom(entity,pos,look,14,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cone(Loki.BLOOD.get(),at.add(0,1,0),new Vec3(0,1,0),Vfx.count(swell*5f),.24,.2);
                Vfx.ring(gold,at.add(0,1,0),.5+.9*Vfx.ease(t),Vfx.count(swell*4f),.1,.05);
            });
            case "ward" -> Vfx.bloom(entity,pos,look,26,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.dome(veil,at.add(0,1,0),.9+.35*Vfx.ease(t),Vfx.count(swell*3f),.004);
                Vfx.spiral(green,at,.6,1.9,Vfx.count(swell*3f),1.2);
            });
            case "push" -> Vfx.bloom(entity,pos,look,12,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.ring(green,at.add(0,.9,0),.6+2.4*Vfx.ease(t),Vfx.count(swell*6f),.22,.02);
                Vfx.cloud(nebula,at.add(0,1,0),.8,Vfx.count(swell),.03);
            });
            // The shape change is the slowest thing the mod does on purpose: the shroud closes over
            // the body, holds, and opens again on the creature that was underneath it.
            case "disguise" -> Vfx.bloom(entity,pos,look,30,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.cloud(nebula,at.add(0,1,0),.5+.7*swell,Vfx.count(swell*2.6f),.006);
                Vfx.spiral(veil,at,.42,1.9,Vfx.count(swell*2f),2.6);
                if(t>.45f&&t<.6f)Vfx.spark(star,at.add(0,1.1,0),Vec3.ZERO);
            });
            // The owner leaving: a bank of nebula far thicker than flight, closing over where they
            // stood and hanging there after the blow has gone through it.
            case "demanifest" -> Vfx.bloom(-1,pos,look,26,(at,aim,t)->{
                float swell=Vfx.swell(t);
                float rush=t<.25f?1:0;
                Vfx.cloud(nebula,at.add(0,1,0),.45+.55*Vfx.ease(t),Vfx.count(swell*5f+rush*6f),.014);
                Vfx.cloud(veil,at.add(0,1,0),.8+.5*Vfx.ease(t),Vfx.count(swell*3f+rush*3f),.008);
                if(t<.3f)Vfx.column(nebula,at,.4,2.1,Vfx.count(4-t*8),t);
                if(t<.12f)Vfx.spark(star,at.add(0,1.1,0),Vec3.ZERO);
            });
            case "remanifest" -> Vfx.bloom(entity,pos,look,16,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.gather(nebula,at.add(0,1,0),1.5*(1-Vfx.ease(t))+.25,Vfx.count(swell*4f),.14);
                Vfx.cloud(veil,at.add(0,1,0),.6,Vfx.count(swell*1.6f),.01);
                if(t>.7f)Vfx.spark(star,at.add(0,1.1,0),Vec3.ZERO);
            });
            // A star opening high above, then landing: a green celestial burst, never an explosion.
            case "starfall_open" -> Vfx.bloom(entity,pos,look,12,(at,aim,t)->{
                Vfx.cloud(nebula,at,.7,Vfx.count(Vfx.swell(t)*2f),.02);
                if(t<.2f)Vfx.spark(star,at,Vec3.ZERO);
            });
            case "starfall_impact" -> Vfx.bloom(-1,pos,look,22,(at,aim,t)->{
                float swell=Vfx.swell(t);
                double radius=.4+4.2*Vfx.ease(Math.min(1,t*1.9f));
                Vfx.ring(green,at.add(0,.15,0),radius,Vfx.count(swell*7f),.16,.05);
                Vfx.dome(veil,at.add(0,.7,0),radius*.8,Vfx.count(swell*4f),.02);
                Vfx.cloud(nebula,at.add(0,.8,0),1.5,Vfx.count(swell*3f),.03);
                Vfx.cone(Loki.SHARD.get(),at.add(0,.4,0),new Vec3(0,1,0),Vfx.count(swell*4f),.32,.26);
                if(t<.18f){Vfx.spark(star,at.add(0,.8,0),Vec3.ZERO);Vfx.ring(star,at.add(0,.6,0),1.1,5,.18,.09);}
            });
            case "ascend" -> Vfx.bloom(entity,pos,look,64,(at,aim,t)->{
                float swell=Vfx.swell(t);
                Vfx.column(nebula,at,.75,2.5,Vfx.count(swell*3.2f),t);
                Vfx.spiral(gold,at,.7,2.3,Vfx.count(swell*2.4f),2.4);
                Vfx.dome(veil,at.add(0,1.1,0),1.1+.5*Vfx.ease(t),Vfx.count(swell*1.6f),.006);
                if(t>.85f)Vfx.spark(star,at.add(0,1.2,0),Vec3.ZERO);
            });
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

        Blood.render(pose,buffers,partial);
        CapeRenderer.renderAll(pose,buffers,partial);
        CosmicNebula.render(pose,buffers,partial);
        pose.popPose();
        buffers.endBatch(RenderType.entityTranslucent(WHITE));
        buffers.endBatch(RenderType.entityTranslucent(Blood.POOL));
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
