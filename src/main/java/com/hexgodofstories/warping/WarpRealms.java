package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.FractureAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.ITeleporter;
import java.util.*;

/** Bounded preparation work, persistent instance clocks and environmental rules scoped to nine new levels. */
public final class WarpRealms {
    public static final class Ledger extends SavedData {
        int next;final Map<Long,Long> clocks=new HashMap<>();final Set<Long> ready=new HashSet<>();
        static Ledger load(CompoundTag n){Ledger l=new Ledger();l.next=n.getInt("next");for(long x:n.getLongArray("ready"))l.ready.add(x);ListTag a=n.getList("clocks",10);for(int i=0;i<a.size();i++){var c=a.getCompound(i);l.clocks.put(c.getLong("cell"),c.getLong("time"));}return l;}
        public CompoundTag save(CompoundTag n){n.putInt("next",next);n.putLongArray("ready",ready.stream().mapToLong(Long::longValue).toArray());ListTag a=new ListTag();clocks.forEach((x,t)->{CompoundTag c=new CompoundTag();c.putLong("cell",x);c.putLong("time",t);a.add(c);});n.put("clocks",a);return n;}
    }
    private record Job(ServerLevel level,Destination d,double cell,Iterator<RealmLayout.Voxel> blocks){}
    private static final List<Job> JOBS=new ArrayList<>();
    private static final Map<UUID,ArrayDeque<Vec3>> HISTORY=new HashMap<>();
    private static Ledger ledger(ServerLevel l){return l.getDataStorage().computeIfAbsent(Ledger::load,Ledger::new,"warping_realms");}
    public static double allocate(ServerLevel l){Ledger a=ledger(l);a.next++;a.setDirty();return a.next*1024.0;}
    public static double latest(ServerLevel l){return ledger(l).next*1024.0;}
    public static void prepare(ServerLevel l,Destination d,double x){if(!ready(l,x)&&JOBS.stream().noneMatch(j->j.level==l&&j.cell==x))JOBS.add(new Job(l,d,x,RealmLayout.blocks(d).iterator()));}
    public static boolean ready(ServerLevel l,double x){return l!=null&&ledger(l).ready.contains((long)x);}
    public static void start(ServerLevel l,double x){Ledger a=ledger(l);a.clocks.put((long)x,l.getGameTime());a.setDirty();}
    public static long age(ServerLevel l,double x){return Math.max(0,l.getGameTime()-ledger(l).clocks.getOrDefault((long)x,l.getGameTime()));}
    public static void transfer(Entity e,Destination d,double cell,boolean owner){
        ServerLevel old=(ServerLevel)e.level(),to=old.getServer().getLevel(d.key);if(to==null)return;
        Vec3 pos=d.arrival.add(cell,owner?8:0,0);
        if(e instanceof ServerPlayer p){
            if(Destination.from(old)==null)HexData.get(p).put("warpReturn",new FractureAnchor(old.dimension(),p.position(),p.getYRot(),p.getXRot()).save());
            p.stopRiding();p.teleportTo(to,pos.x,pos.y,pos.z,p.getYRot(),p.getXRot());
            if(owner){com.hexgodofstories.server.CosmicFlight.tick(p);p.getAbilities().flying=true;p.onUpdateAbilities();}
        }else{
            e.stopRiding();e.changeDimension(to,new ITeleporter(){public Entity placeEntity(Entity entity,ServerLevel current,ServerLevel dest,float yaw,java.util.function.Function<Boolean,Entity> reposition){Entity moved=reposition.apply(false);if(moved!=null){moved.moveTo(pos.x,pos.y,pos.z,yaw,0);moved.setDeltaMovement(0,-.3,0);moved.fallDistance=0;}return moved;}});
        }
        e.setDeltaMovement(0,owner?0:-.3,0);e.fallDistance=0;HexNetwork.arrival(e);
    }
    public static void tick(ServerLevel l){
        Destination d=Destination.from(l);if(d==null)return;
        int budget=4096;
        for(var it=JOBS.iterator();it.hasNext()&&budget>0;){Job j=it.next();if(j.level!=l)continue;
            while(j.blocks.hasNext()&&budget-->0){var v=j.blocks.next();l.setBlock(v.pos().offset((int)j.cell,0,0),v.state(),2|16);}
            if(!j.blocks.hasNext()){ledger(l).ready.add((long)j.cell);ledger(l).setDirty();populate(l,d,j.cell);it.remove();}
        }
        long now=l.getGameTime();
        if(d==Destination.VOID_SEA)com.hexgodofstories.warping.leviathan.PilgrimWarden.tick(l,now);
        List<Entity> active=new ArrayList<>();l.getAllEntities().forEach(active::add);
        for(Entity e:active){
            if(!e.isAlive()||e.isSpectator()||e instanceof WarpHazard||e instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)continue;
            double cell=WarpMath.cellX(e.getX());long age=age(l,cell);
            if(e instanceof ServerPlayer p&&now%10==0){CompoundTag n=new CompoundTag();n.putLong("age",age);n.putLong("time",now);n.putDouble("cell",cell);HexNetwork.to(p,new HexNetwork.Message(HexNetwork.WARP_REALM,0,n));}
            if(e instanceof ServerPlayer listener&&now%140==0){
                net.minecraft.sounds.SoundEvent ambience=switch(d){
                    case SUN -> HexGodOfStories.BRANCH_ROAR.get();
                    case GRAVITY_WELL -> HexGodOfStories.BRANCH_HUM.get();
                    case TIME_STORM -> HexGodOfStories.BRANCH_SHIMMER.get();
                    case FALLING_WORLD -> HexGodOfStories.METEOR_ROAR.get();
                    case CRUSHING_REALM -> HexGodOfStories.BRANCH_PRESSURE.get();
                    default -> null;
                };
                if(ambience!=null)listener.playNotifySound(ambience,net.minecraft.sounds.SoundSource.AMBIENT,.14f,.65f);
            }
            if(Warping.sovereign(e)&&d!=Destination.SUN){e.fallDistance=0;double bottom=d==Destination.VOID_SEA?VoidSea.FLOOR:0,rescue=d==Destination.VOID_SEA?VoidSea.SURFACE-24:180;if(e.getY()<bottom)e.teleportTo(e.getX(),rescue,e.getZ());continue;}
            if(e instanceof net.minecraft.world.entity.player.Player p&&p.isCreative())continue;
            switch(d){
                case SUN -> solarExposure(l,e,cell,now);
                case VOID_SEA -> {}  // the realm's only hazard is alive and has its own AI
                case GRAVITY_WELL -> {
                    Vec3 toward=new Vec3(cell,96,0).subtract(e.position());double dist=toward.length();
                    e.setDeltaMovement(e.getDeltaMovement().scale(.96).add(toward.normalize().scale(WarpMath.pull(dist))).add(0,e.isNoGravity()?0:.08,0));e.hurtMarked=true;
                    if(dist<23&&now%10==0)e.hurt(l.damageSources().magic(),(float)(4+(23-dist)*2));
                }
                case SHATTERED_WORLD -> {
                    long phase=age%240;if(phase<35){e.setDeltaMovement(e.getDeltaMovement().add(Math.sin(age*.05)*.015,.075,Math.cos(age*.05)*.015));e.hurtMarked=true;}
                }
                case TIME_STORM -> {
                    if(now%5==0){var h=HISTORY.computeIfAbsent(e.getUUID(),k->new ArrayDeque<>());h.addLast(e.position());while(h.size()>13)h.removeFirst();
                        if(age>60&&age%100<5&&h.size()>10){Vec3 back=h.getFirst();e.teleportTo(back.x,back.y,back.z);e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;HexNetwork.fx(e,"slip");h.clear();}}
                    if(age%100>85&&e instanceof LivingEntity living)living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,8,3,false,false));
                }
                case FALLING_WORLD -> {if(e.getY()<48){e.teleportTo(e.getX(),231,e.getZ());e.fallDistance=0;}if(e.getDeltaMovement().y>-.15)e.setDeltaMovement(e.getDeltaMovement().add(0,-.035,0));}
                case FROZEN_MOMENT -> {}
                case CRUSHING_REALM -> {
                    double floor=WarpMath.floor(age)+1,ceiling=WarpMath.ceiling(age);
                    if(e.getY()<floor&&e.getY()>90){e.teleportTo(e.getX(),floor,e.getZ());e.fallDistance=0;}
                    if(e.getY()+e.getBbHeight()>ceiling){e.teleportTo(e.getX(),Math.max(floor,ceiling-e.getBbHeight()),e.getZ());e.setDeltaMovement(e.getDeltaMovement().x,Math.min(0,e.getDeltaMovement().y),e.getDeltaMovement().z);if(now%10==0)e.hurt(l.damageSources().inWall(),ceiling-floor<2?20:6);}
                    if(Math.abs(e.getX()-cell)>42||Math.abs(e.getZ())>42){Vec3 pull=new Vec3(cell,100,0).subtract(e.position()).normalize().scale(.15);e.setDeltaMovement(e.getDeltaMovement().add(pull));e.hurtMarked=true;}
                }
                case END_OF_TIME -> {if(e instanceof LivingEntity living&&now%40==0){living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,65,2,false,false));living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,65,1,false,false));living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,65,0,false,false));if(age>200)living.hurt(l.damageSources().wither(),2);}}
            }
        }
        if(d==Destination.TIME_STORM&&now%200==0)HISTORY.keySet().removeIf(id->l.getEntity(id)==null);
    }
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> SOLAR_HEAT=
        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,HexGodOfStories.id("solar_heat"));
    /** Stellar heat is not ordinary fire: the mantle's fire resistance cannot make the Sun harmless. */
    public static void solarExposure(ServerLevel level,Entity entity,double cell,long now){
        if(!entity.isAlive()||entity.isSpectator()||entity instanceof net.minecraft.world.entity.player.Player p&&p.isCreative())return;
        float damage=WarpMath.solarDamage(entity.position().distanceTo(new Vec3(cell,WarpMath.SUN_Y,0)));
        if(damage<=0)return;
        entity.setSecondsOnFire(8);
        if(now%10==0)entity.hurt(new net.minecraft.world.damagesource.DamageSource(
            level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(SOLAR_HEAT)),damage);
    }
    private static void populate(ServerLevel l,Destination d,double cell){
        if(d==Destination.VOID_SEA)com.hexgodofstories.warping.leviathan.PilgrimWarden.ensure(l,cell);
        if(d==Destination.FALLING_WORLD||d==Destination.FROZEN_MOMENT){
            Random r=new Random(819+d.ordinal());int count=d==Destination.FALLING_WORLD?48:32;
            for(int i=0;i<count;i++){WarpHazard h=HexGodOfStories.WARP_HAZARD.get().create(l);if(h==null)continue;
                h.configure(d==Destination.FROZEN_MOMENT?(i%4==0?5:1):i%3+2,cell,i);h.moveTo(cell+r.nextInt(100)-50,140+r.nextInt(90),r.nextInt(100)-50,0,0);l.addFreshEntity(h);}
        }
    }
    public static void releaseHazards(ServerPlayer p){if(!Warping.sovereign(p))return;for(WarpHazard h:p.serverLevel().getEntitiesOfClass(WarpHazard.class,p.getBoundingBox().inflate(96)))h.release(p.getLookAngle());HexNetwork.fx(p,"resume");}
    public static void reset(){JOBS.clear();HISTORY.clear();com.hexgodofstories.warping.leviathan.PilgrimWarden.reset();}
}
