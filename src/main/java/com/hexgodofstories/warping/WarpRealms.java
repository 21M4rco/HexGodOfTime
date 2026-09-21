package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.FractureAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.ITeleporter;
import java.util.*;

/**
 * Bounded preparation work, persistent instance clocks and the environmental
 * rules of nine levels.
 *
 * Three rules hold everywhere, because every realm broke one of them:
 *  - nothing leaves the world. Every entity is caught by a floor, a ceiling
 *    and a horizontal leash around its own instance, whatever its realm does.
 *  - a field applies to players as well as mobs. A server-side velocity change
 *    only reaches a player as a motion packet, so pushes are strided and
 *    speed-capped instead of being written every tick and fought by client
 *    prediction.
 *  - a realm hazard either resolves or lets go. Nothing may hold a victim in a
 *    state it cannot leave or die from.
 */
public final class WarpRealms {
    /** Ticks of silence before a slain Void Sea hunter is replaced. */
    public static final int HUNTER_RESPAWN=1800;
    private static final ResourceKey<DamageType> SINGULARITY=
        ResourceKey.create(Registries.DAMAGE_TYPE,HexGodOfStories.id("singularity"));

    public static final class Ledger extends SavedData {
        int next;final Map<Long,Long> clocks=new HashMap<>();final Set<Long> ready=new HashSet<>();
        static Ledger load(CompoundTag n){Ledger l=new Ledger();l.next=n.getInt("next");for(long x:n.getLongArray("ready"))l.ready.add(x);ListTag a=n.getList("clocks",10);for(int i=0;i<a.size();i++){var c=a.getCompound(i);l.clocks.put(c.getLong("cell"),c.getLong("time"));}return l;}
        public CompoundTag save(CompoundTag n){n.putInt("next",next);n.putLongArray("ready",ready.stream().mapToLong(Long::longValue).toArray());ListTag a=new ListTag();clocks.forEach((x,t)->{CompoundTag c=new CompoundTag();c.putLong("cell",x);c.putLong("time",t);a.add(c);});n.put("clocks",a);return n;}
    }
    private record Job(ServerLevel level,Destination d,double cell,Iterator<RealmLayout.Voxel> blocks){}
    private static final List<Job> JOBS=new ArrayList<>();
    private static final Map<UUID,ArrayDeque<Vec3>> HISTORY=new HashMap<>();
    private static final Map<Long,Long> HUNTER_DUE=new HashMap<>();

    private static Ledger ledger(ServerLevel l){return l.getDataStorage().computeIfAbsent(Ledger::load,Ledger::new,"warping_realms");}
    public static double allocate(ServerLevel l){Ledger a=ledger(l);a.next++;a.setDirty();return a.next*1024.0;}
    public static double latest(ServerLevel l){return ledger(l).next*1024.0;}
    public static void prepare(ServerLevel l,Destination d,double x){if(!ready(l,x)&&JOBS.stream().noneMatch(j->j.level==l&&j.cell==x))JOBS.add(new Job(l,d,x,RealmLayout.blocks(d).iterator()));}
    public static boolean ready(ServerLevel l,double x){return l!=null&&ledger(l).ready.contains((long)x);}
    public static void start(ServerLevel l,double x){Ledger a=ledger(l);a.clocks.put((long)x,l.getGameTime());a.setDirty();}
    public static long age(ServerLevel l,double x){return Math.max(0,l.getGameTime()-ledger(l).clocks.getOrDefault((long)x,l.getGameTime()));}

    public static void transfer(Entity e,Destination d,double cell,boolean owner) {
        ServerLevel old=(ServerLevel)e.level(),to=old.getServer().getLevel(d.key);if(to==null)return;
        Vec3 pos=d.arrival.add(cell,owner?8:0,0);
        if(e instanceof ServerPlayer p){
            if(Destination.from(old)==null)HexData.get(p).put("warpReturn",new FractureAnchor(old.dimension(),p.position(),p.getYRot(),p.getXRot()).save());
            p.stopRiding();p.teleportTo(to,pos.x,pos.y,pos.z,p.getYRot(),p.getXRot());
            p.setDeltaMovement(0,owner?0:-.3,0);p.fallDistance=0;p.hurtMarked=true;
            if(owner){com.hexgodofstories.server.CosmicFlight.tick(p);p.getAbilities().flying=true;p.onUpdateAbilities();}
            HexNetwork.arrival(p);
            return;
        }
        e.stopRiding();
        // changeDimension returns the copy that now exists in the destination; the original is gone.
        Entity moved=e.changeDimension(to,new ITeleporter(){public Entity placeEntity(Entity entity,ServerLevel current,ServerLevel dest,float yaw,java.util.function.Function<Boolean,Entity> reposition){Entity placed=reposition.apply(false);if(placed!=null){placed.moveTo(pos.x,pos.y,pos.z,yaw,0);placed.setDeltaMovement(0,-.3,0);placed.fallDistance=0;}return placed;}});
        if(moved!=null)HexNetwork.arrival(moved);
    }

    public static void tick(ServerLevel l) {
        Destination d=Destination.from(l);if(d==null)return;
        int budget=4096;
        for(var it=JOBS.iterator();it.hasNext()&&budget>0;){
            Job j=it.next();if(j.level!=l)continue;
            while(j.blocks.hasNext()&&budget-->0){var v=j.blocks.next();l.setBlock(v.pos().offset((int)j.cell,0,0),v.state(),2|16);}
            if(!j.blocks.hasNext()){ledger(l).ready.add((long)j.cell);ledger(l).setDirty();populate(l,d,j.cell);it.remove();}
        }
        long now=l.getGameTime();
        List<AbyssalLeviathan> hunters=d==Destination.VOID_SEA?new ArrayList<>():null;
        List<Entity> active=new ArrayList<>();l.getAllEntities().forEach(active::add);
        for(Entity e:active){
            if(!e.isAlive()||e.isSpectator())continue;
            if(e instanceof AbyssalLeviathan hunter){if(hunters!=null)hunters.add(hunter);continue;}
            if(e instanceof WarpHazard)continue;
            double cell=WarpMath.cellX(e.getX());
            long age=age(l,cell);
            if(e instanceof ServerPlayer p&&now%10==0){CompoundTag n=new CompoundTag();n.putLong("age",age);n.putLong("time",now);n.putDouble("cell",cell);HexNetwork.to(p,new HexNetwork.Message(HexNetwork.WARP_REALM,0,n));}
            if(e instanceof ServerPlayer listener&&now%140==0)ambience(d,listener);
            boolean sovereign=Warping.sovereign(e);
            // The floor, the ceiling and the leash are not hazards; they hold for everything, always.
            contain(l,d,e,cell,sovereign);
            if(sovereign&&d!=Destination.SUN){e.fallDistance=0;continue;}
            if(e instanceof Player p&&p.isCreative())continue;
            switch(d){
                case SUN -> solarExposure(l,e,cell,now);
                case VOID_SEA -> voidSea(l,e,now);
                case GRAVITY_WELL -> singularity(l,e,cell,now);
                case SHATTERED_WORLD -> shattered(e,age,now);
                case TIME_STORM -> timeStorm(l,e,age,now);
                case FALLING_WORLD -> falling(e,now);
                case FROZEN_MOMENT -> frozen(e,now);
                case CRUSHING_REALM -> crushing(l,e,cell,age,now);
                case END_OF_TIME -> endOfTime(l,e,age,now);
            }
        }
        if(hunters!=null&&now%20==0)hunter(l,hunters,now);
        if(d==Destination.TIME_STORM&&now%200==0)HISTORY.keySet().removeIf(id->l.getEntity(id)==null);
    }

    // ------------------------------------------------------------------ bounds --
    /** Nothing falls out of a realm, rises out of one, or wanders into the next instance. */
    private static void contain(ServerLevel l,Destination d,Entity e,double cell,boolean sovereign) {
        double floor=switch(d){
            case VOID_SEA -> 1;
            case CRUSHING_REALM -> 88;
            case FALLING_WORLD,SUN -> 40;
            default -> 24;
        };
        if(e.getY()<floor){
            switch(d){
                // A star and an endless collapse both answer a fall with another fall.
                case SUN,FALLING_WORLD -> {e.teleportTo(e.getX(),d==Destination.SUN?190:231,e.getZ());e.fallDistance=0;e.setDeltaMovement(e.getDeltaMovement().x,0,e.getDeltaMovement().z);e.hurtMarked=true;}
                default -> recall(e,d,cell);
            }
        }
        double ceiling=l.getMaxBuildHeight()-6;
        if(e.getY()>ceiling){
            e.teleportTo(e.getX(),ceiling-2,e.getZ());
            e.setDeltaMovement(e.getDeltaMovement().x,Math.min(0,e.getDeltaMovement().y),e.getDeltaMovement().z);e.hurtMarked=true;
        }
        double dx=e.getX()-cell,dz=e.getZ();
        double spread=Math.sqrt(dx*dx+dz*dz);
        if(spread>WarpMath.LEASH_HARD){recall(e,d,cell);return;}
        if(spread>WarpMath.LEASH&&!sovereign){
            Vec3 inward=new Vec3(-dx,0,-dz).normalize().scale(.08+(spread-WarpMath.LEASH)/(WarpMath.LEASH_HARD-WarpMath.LEASH)*.22);
            e.setDeltaMovement(e.getDeltaMovement().add(inward));e.hurtMarked=true;
        }
    }

    /** Puts an entity back on its arrival point rather than letting it leave the world. */
    private static void recall(Entity e,Destination d,double cell) {
        Vec3 at=d.arrival.add(cell,0,0);
        e.teleportTo(at.x,at.y,at.z);
        e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;e.hurtMarked=true;
        HexNetwork.fx(e,"arrive");
    }

    /**
     * Server-side velocity only reaches a player as a correction packet, so a field
     * that writes every tick fights the client's own physics and reads as jitter.
     * Players are pushed on a stride with a proportionally larger impulse instead,
     * and everything is capped well under the movement checks.
     */
    private static void field(Entity e,Vec3 accel,long now) {
        int stride=e instanceof ServerPlayer?3:1;
        if(now%stride!=0)return;
        Vec3 v=e.getDeltaMovement().add(accel.scale(stride));
        if(v.lengthSqr()>WarpMath.FIELD_SPEED*WarpMath.FIELD_SPEED)v=v.normalize().scale(WarpMath.FIELD_SPEED);
        e.setDeltaMovement(v);e.hurtMarked=true;
    }

    // ------------------------------------------------------------------ realms --
    private static void ambience(Destination d,ServerPlayer listener) {
        net.minecraft.sounds.SoundEvent ambience=switch(d){
            case SUN -> HexGodOfStories.BRANCH_ROAR.get();
            case GRAVITY_WELL -> HexGodOfStories.BRANCH_HUM.get();
            case TIME_STORM -> HexGodOfStories.BRANCH_SHIMMER.get();
            case FALLING_WORLD -> HexGodOfStories.METEOR_ROAR.get();
            case CRUSHING_REALM -> HexGodOfStories.BRANCH_PRESSURE.get();
            case VOID_SEA -> HexGodOfStories.BRANCH_HUM.get();
            default -> null;
        };
        if(ambience!=null)listener.playNotifySound(ambience,net.minecraft.sounds.SoundSource.AMBIENT,.14f,.55f);
    }

    private static final ResourceKey<DamageType> SOLAR_HEAT=
        ResourceKey.create(Registries.DAMAGE_TYPE,HexGodOfStories.id("solar_heat"));

    /** Stellar heat is not ordinary fire: the mantle's fire resistance cannot make the Sun harmless. */
    public static void solarExposure(ServerLevel level,Entity entity,double cell,long now) {
        if(!entity.isAlive()||entity.isSpectator()||entity instanceof Player p&&p.isCreative())return;
        double distance=entity.position().distanceTo(new Vec3(cell,WarpMath.SUN_Y,0));
        float damage=WarpMath.solarDamage(distance);
        if(damage<=0)return;
        entity.setSecondsOnFire(8);
        if(entity instanceof LivingEntity living&&now%20==0)
            living.addEffect(new MobEffectInstance(MobEffects.GLOWING,40,0,false,false));
        if(now%10==0)entity.hurt(source(level,SOLAR_HEAT),damage);
    }

    /** Depth crushes and the dark hides the hunter; the hunter itself is the realm's threat. */
    private static void voidSea(ServerLevel l,Entity e,long now) {
        if(!(e instanceof LivingEntity living))return;
        if(now%40==0)living.addEffect(new MobEffectInstance(MobEffects.DARKNESS,120,0,false,false));
        double depth=136-e.getY();
        if(depth>70&&now%20==0){
            living.hurt(l.damageSources().magic(),(float)Math.min(8,(depth-70)*.12));
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,40,1,false,false));
        }
    }

    /**
     * A singularity, not a magnet. Pull follows an inverse square with a floor and
     * a cap, a tangent keeps victims spiralling instead of pinning them to the
     * centre taking damage forever, and the horizon is a clean kill rather than a
     * place to be stuck.
     */
    private static void singularity(ServerLevel l,Entity e,double cell,long now) {
        Vec3 core=new Vec3(cell,WarpMath.WELL_Y,0);
        Vec3 toward=core.subtract(e.position());
        double distance=toward.length();
        if(distance<=WarpMath.EVENT_HORIZON){
            e.hurt(source(l,SINGULARITY),1000F);
            if(e.isAlive()&&!(e instanceof Player))e.discard();
            return;
        }
        Vec3 inward=toward.scale(1/Math.max(.001,distance));
        Vec3 spin=new Vec3(-inward.z,0,inward.x).scale(WarpMath.orbit(distance));
        double gravity=e.isNoGravity()?0:.08;
        field(e,inward.scale(WarpMath.pull(distance)).add(spin).add(0,gravity,0),now);
        if(distance<WarpMath.TIDAL&&now%10==0&&e instanceof LivingEntity living){
            living.hurt(source(l,SINGULARITY),(float)(2+(WarpMath.TIDAL-distance)*.35));
            living.addEffect(new MobEffectInstance(MobEffects.CONFUSION,80,0,false,false));
        }
    }

    /** Islands drift apart; every fortieth second the ground stops agreeing which way is down. */
    private static void shattered(Entity e,long age,long now) {
        long phase=age%260;
        if(phase<40)field(e,new Vec3(Math.sin(age*.05)*.012,.055,Math.cos(age*.05)*.012),now);
        else if(phase<48&&e instanceof LivingEntity living&&now%20==0)
            living.addEffect(new MobEffectInstance(MobEffects.LEVITATION,25,0,false,false));
    }

    private static void timeStorm(ServerLevel l,Entity e,long age,long now) {
        if(now%5==0){
            var h=HISTORY.computeIfAbsent(e.getUUID(),k->new ArrayDeque<>());
            h.addLast(e.position());
            while(h.size()>13)h.removeFirst();
            if(age>60&&age%100<5&&h.size()>10){
                Vec3 back=h.getFirst();
                e.teleportTo(back.x,back.y,back.z);e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;e.hurtMarked=true;
                HexNetwork.fx(e,"slip");h.clear();
            }
        }
        if(age%100>85&&e instanceof LivingEntity living){
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,8,3,false,false));
            if(now%20==0)living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,25,2,false,false));
        }
    }

    /** The collapse never lands. Terminal velocity is enforced so the loop stays survivable to watch. */
    private static void falling(Entity e,long now) {
        Vec3 v=e.getDeltaMovement();
        if(v.y>-1.6)field(e,new Vec3(0,-.045,0),now);
        e.fallDistance=0;
    }

    /** A held catastrophe: everything in it is held too, until Loki lets the spears go. */
    private static void frozen(Entity e,long now) {
        if(!(e instanceof LivingEntity living))return;
        if(now%10!=0)return;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,20,5,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,20,4,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,20,0,false,false));
    }

    private static void crushing(ServerLevel l,Entity e,double cell,long age,long now) {
        double floor=WarpMath.floor(age)+1,ceiling=WarpMath.ceiling(age);
        if(e.getY()<floor){
            e.teleportTo(e.getX(),floor,e.getZ());e.fallDistance=0;
            e.setDeltaMovement(e.getDeltaMovement().x,Math.max(0,e.getDeltaMovement().y),e.getDeltaMovement().z);e.hurtMarked=true;
        }
        if(e.getY()+e.getBbHeight()>ceiling){
            e.teleportTo(e.getX(),Math.max(floor,ceiling-e.getBbHeight()),e.getZ());
            e.setDeltaMovement(e.getDeltaMovement().x,Math.min(0,e.getDeltaMovement().y),e.getDeltaMovement().z);e.hurtMarked=true;
            if(now%10==0)e.hurt(l.damageSources().inWall(),ceiling-floor<2?20:6);
        }
        if(Math.abs(e.getX()-cell)>42||Math.abs(e.getZ())>42)
            field(e,new Vec3(cell,100,0).subtract(e.position()).normalize().scale(.05),now);
    }

    private static void endOfTime(ServerLevel l,Entity e,long age,long now) {
        if(!(e instanceof LivingEntity living)||now%40!=0)return;
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,65,2,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,65,1,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,65,0,false,false));
        if(age>200)living.hurt(l.damageSources().wither(),Math.min(6F,2F+age/900F));
    }

    private static DamageSource source(ServerLevel l,ResourceKey<DamageType> type) {
        return new DamageSource(l.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type));
    }

    // ----------------------------------------------------------------- hunters --
    /** Exactly one hunter to an instance. Extras are removed; a slain one stays dead for a while. */
    private static void hunter(ServerLevel l,List<AbyssalLeviathan> present,long now) {
        Map<Long,AbyssalLeviathan> keep=new HashMap<>();
        for(AbyssalLeviathan h:present){
            long cell=(long)WarpMath.cellX(h.getX());
            if(keep.putIfAbsent(cell,h)!=null)h.discard();
            else h.bind(cell);
        }
        for(ServerPlayer p:l.players()){
            if(p.isSpectator()||p.isCreative())continue;
            long cell=(long)WarpMath.cellX(p.getX());
            if(keep.containsKey(cell)){HUNTER_DUE.remove(cell);continue;}
            long due=HUNTER_DUE.computeIfAbsent(cell,k->now+HUNTER_RESPAWN);
            if(now<due)continue;
            HUNTER_DUE.remove(cell);
            spawnHunter(l,cell,p.getX(),p.getZ());
        }
    }

    private static void spawnHunter(ServerLevel l,double cell,double nearX,double nearZ) {
        AbyssalLeviathan hunter=HexGodOfStories.LEVIATHAN.get().create(l);
        if(hunter==null)return;
        hunter.bind(cell);
        hunter.moveTo(nearX+52,60,nearZ+40,0,0);
        l.addFreshEntity(hunter);
        l.playSound(null,BlockPos.containing(nearX,80,nearZ),net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_CURSE,SoundSource.HOSTILE,4F,.3F);
    }

    private static void populate(ServerLevel l,Destination d,double cell) {
        if(d==Destination.VOID_SEA)spawnHunter(l,cell,cell+30,30);
        if(d==Destination.FALLING_WORLD||d==Destination.FROZEN_MOMENT){
            Random r=new Random(819+d.ordinal());int count=d==Destination.FALLING_WORLD?48:32;
            for(int i=0;i<count;i++){
                WarpHazard h=HexGodOfStories.WARP_HAZARD.get().create(l);if(h==null)continue;
                h.configure(d==Destination.FROZEN_MOMENT?(i%4==0?5:1):i%3+2,cell,i);
                h.moveTo(cell+r.nextInt(100)-50,140+r.nextInt(90),r.nextInt(100)-50,0,0);
                l.addFreshEntity(h);
            }
        }
    }

    public static void releaseHazards(ServerPlayer p) {
        if(!Warping.sovereign(p))return;
        for(WarpHazard h:p.serverLevel().getEntitiesOfClass(WarpHazard.class,p.getBoundingBox().inflate(96)))h.release(p.getLookAngle());
        HexNetwork.fx(p,"resume");
    }

    public static void reset(){JOBS.clear();HISTORY.clear();HUNTER_DUE.clear();}
}
