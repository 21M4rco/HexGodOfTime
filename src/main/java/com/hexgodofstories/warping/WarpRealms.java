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
import net.minecraft.util.Mth;
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
    private static final ResourceKey<DamageType> COLLAPSE=
        ResourceKey.create(Registries.DAMAGE_TYPE,HexGodOfStories.id("realm_collapse"));
    /** Realm age at which a destination with no inherent lethality starts closing on its victims. */
    public static final int ATTRITION=2400;

    public static final class Ledger extends SavedData {
        int next;final Map<Long,Long> clocks=new HashMap<>();final Set<Long> ready=new HashSet<>();
        static Ledger load(CompoundTag n){Ledger l=new Ledger();l.next=n.getInt("next");for(long x:n.getLongArray("ready"))l.ready.add(x);ListTag a=n.getList("clocks",10);for(int i=0;i<a.size();i++){var c=a.getCompound(i);l.clocks.put(c.getLong("cell"),c.getLong("time"));}return l;}
        public CompoundTag save(CompoundTag n){n.putInt("next",next);n.putLongArray("ready",ready.stream().mapToLong(Long::longValue).toArray());ListTag a=new ListTag();clocks.forEach((x,t)->{CompoundTag c=new CompoundTag();c.putLong("cell",x);c.putLong("time",t);a.add(c);});n.put("clocks",a);return n;}
    }
    private record Job(ServerLevel level,Destination d,double cell,Iterator<RealmLayout.Voxel> blocks){}
    private static final List<Job> JOBS=new ArrayList<>();
    private static final Map<UUID,ArrayDeque<Vec3>> HISTORY=new HashMap<>();
    private static final Map<Long,Long> HUNTER_DUE=new HashMap<>();
    private static final Map<Long,Integer> GROUND=new HashMap<>();

    private static Ledger ledger(ServerLevel l){return l.getDataStorage().computeIfAbsent(Ledger::load,Ledger::new,"warping_realms");}
    public static double allocate(ServerLevel l){Ledger a=ledger(l);a.next++;a.setDirty();return a.next*1024.0;}
    public static double latest(ServerLevel l){return ledger(l).next*1024.0;}
    public static void prepare(ServerLevel l,Destination d,double x){if(!ready(l,x)&&JOBS.stream().noneMatch(j->j.level==l&&j.cell==x))JOBS.add(new Job(l,d,x,RealmLayout.blocks(d).iterator()));}
    public static boolean ready(ServerLevel l,double x){return l!=null&&ledger(l).ready.contains((long)x);}
    public static void start(ServerLevel l,double x) {
        Ledger a=ledger(l);a.clocks.put((long)x,l.getGameTime());a.setDirty();GROUND.remove((long)x);
        // The press grinds its own columns away. A trap opened a second time gets them back,
        // otherwise the room has no clock in it and the realm reads as empty.
        Destination d=Destination.from(l);
        if(d==Destination.CRUSHING_REALM&&JOBS.stream().noneMatch(j->j.level==l&&j.cell==x))
            JOBS.add(new Job(l,d,x,RealmLayout.blocks(d).iterator()));
    }
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
            contain(l,d,e,cell,age,sovereign);
            if(sovereign&&d!=Destination.SUN){e.fallDistance=0;continue;}
            if(e instanceof Player p&&p.isCreative())continue;
            switch(d){
                case SUN -> solarExposure(l,e,cell,now);
                case VOID_SEA -> voidSea(l,e,now);
                case GRAVITY_WELL -> singularity(l,e,cell,now);
                case SHATTERED_WORLD -> shattered(l,e,age,now);
                case TIME_STORM -> timeStorm(l,e,age,now);
                case FALLING_WORLD -> falling(e,now);
                case FROZEN_MOMENT -> frozen(l,e,age,now);
                case CRUSHING_REALM -> crushing(l,e,cell,age,now);
                case END_OF_TIME -> endOfTime(l,e,age,now);
            }
        }
        if(hunters!=null&&now%20==0)hunter(l,hunters,now);
        if(d==Destination.CRUSHING_REALM)for(ServerPlayer p:l.players())press(l,WarpMath.cellX(p.getX()),age(l,WarpMath.cellX(p.getX())),now);
        if(d==Destination.TIME_STORM&&now%200==0)HISTORY.keySet().removeIf(id->l.getEntity(id)==null);
    }

    // ------------------------------------------------------------------ bounds --
    /** Nothing falls out of a realm, rises out of one, or wanders into the next instance. */
    private static void contain(ServerLevel l,Destination d,Entity e,double cell,long age,boolean sovereign) {
        // Creative is outside the world's rules everywhere else; a realm is no different. An admin
        // flying a trap apart should not be recalled to its arrival point mid-inspection.
        if(e instanceof Player p&&p.isCreative())return;
        double floor=switch(d){
            case VOID_SEA -> 1;
            case CRUSHING_REALM -> 88;
            case FALLING_WORLD,SUN -> 40;
            default -> 24;
        };
        if(e.getY()<floor){
            switch(d){
                // A star and an endless collapse both answer a fall with another fall.
                case SUN,FALLING_WORLD -> {
                    // The collapse has a bottom. Hitting it hurts exactly as much as the fall earned,
                    // and then the world puts the victim back at the top to do it again.
                    // Containment runs ahead of the sovereign exemption, so the impact has to
                    // respect it here: the collapse does not get to hurt the one who built it.
                    if(d==Destination.FALLING_WORLD&&!sovereign&&e instanceof LivingEntity faller){
                        float impact=(float)Math.min(30,-e.getDeltaMovement().y*11-4);
                        if(impact>0)faller.hurt(l.damageSources().fall(),impact);
                    }
                    e.teleportTo(e.getX(),d==Destination.SUN?190:231,e.getZ());
                    e.fallDistance=0;e.setDeltaMovement(e.getDeltaMovement().x,0,e.getDeltaMovement().z);e.hurtMarked=true;
                }
                default -> recall(e,d,cell,age);
            }
        }
        double ceiling=l.getMaxBuildHeight()-6;
        if(e.getY()>ceiling){
            e.teleportTo(e.getX(),ceiling-2,e.getZ());
            e.setDeltaMovement(e.getDeltaMovement().x,Math.min(0,e.getDeltaMovement().y),e.getDeltaMovement().z);e.hurtMarked=true;
        }
        double dx=e.getX()-cell,dz=e.getZ();
        double spread=Math.sqrt(dx*dx+dz*dz);
        if(spread>WarpMath.LEASH_HARD){recall(e,d,cell,age);return;}
        if(spread>WarpMath.LEASH&&!sovereign){
            Vec3 inward=new Vec3(-dx,0,-dz).normalize().scale(.08+(spread-WarpMath.LEASH)/(WarpMath.LEASH_HARD-WarpMath.LEASH)*.22);
            e.setDeltaMovement(e.getDeltaMovement().add(inward));e.hurtMarked=true;
        }
    }

    /** Puts an entity back on its arrival point rather than letting it leave the world. */
    private static void recall(Entity e,Destination d,double cell,long age) {
        Vec3 at=d.arrival.add(cell,0,0);
        // The press has a shrinking ceiling: never put anything back above it.
        double y=d==Destination.CRUSHING_REALM?Math.min(at.y,WarpMath.ceiling(age)-2.5):at.y;
        e.teleportTo(at.x,y,at.z);
        e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;e.hurtMarked=true;
        if(e instanceof LivingEntity)HexNetwork.fx(e,"arrive");
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
     * The ring, not a magnet.
     *
     * The disk that is drawn is the disk that is flown: victims are flattened into
     * its plane, carried around it at a Keplerian speed that rises as they are
     * dragged inward, and decay through the ring over about twenty seconds. Only
     * the middle kills, and it kills on contact.
     *
     * Velocity is set toward a target rather than accumulated as force. A force
     * that a player's own movement fights reads as a stutter; a swept orbit reads
     * as being carried, which is the thing this realm is supposed to be.
     */
    private static final Vec3 DISK_NORMAL=
        new Vec3(0,-Math.cos(WarpMath.DISK_TILT),Math.sin(WarpMath.DISK_TILT));

    private static void singularity(ServerLevel l,Entity e,double cell,long now) {
        Vec3 core=new Vec3(cell,WarpMath.WELL_Y,0);
        Vec3 offset=e.position().subtract(core);
        if(offset.length()<=WarpMath.EVENT_HORIZON){
            e.hurt(source(l,SINGULARITY),1000F);
            if(e.isAlive()&&!(e instanceof Player))e.discard();
            return;
        }
        double height=offset.dot(DISK_NORMAL);
        Vec3 plane=offset.subtract(DISK_NORMAL.scale(height));
        double radius=plane.length();
        Vec3 out=radius<.75?new Vec3(1,0,0):plane.scale(1/radius);
        Vec3 around=DISK_NORMAL.cross(out);
        Vec3 target=around.scale(WarpMath.orbitSpeed(radius))
            .subtract(out.scale(WarpMath.inwardDrift(radius)))
            .subtract(DISK_NORMAL.scale(Mth.clamp(height*.12,-.55,.55)));
        sweep(e,target,now);
        if(radius<WarpMath.DISK_INNER&&now%20==0&&e instanceof LivingEntity living){
            living.hurt(source(l,SINGULARITY),(float)(2+(WarpMath.DISK_INNER-radius)*.4));
            living.addEffect(new MobEffectInstance(MobEffects.CONFUSION,90,0,false,false));
        }
    }

    /** Carries an entity along a target velocity instead of fighting it with force. */
    private static void sweep(Entity e,Vec3 target,long now) {
        boolean player=e instanceof ServerPlayer;
        int stride=player?2:1;
        if(now%stride!=0)return;
        Vec3 v=e.getDeltaMovement();
        Vec3 next=v.add(target.subtract(v).scale(player?.85:.35));
        if(next.lengthSqr()>WarpMath.FIELD_SPEED*WarpMath.FIELD_SPEED)next=next.normalize().scale(WarpMath.FIELD_SPEED);
        e.setDeltaMovement(next);e.hurtMarked=true;
    }

    /** Islands drift apart; every thirteenth second the ground stops agreeing which way is down. */
    private static void shattered(ServerLevel l,Entity e,long age,long now) {
        long phase=age%260;
        if(phase<40)field(e,new Vec3(Math.sin(age*.05)*.012,.055,Math.cos(age*.05)*.012),now);
        else if(phase<48&&e instanceof LivingEntity living&&now%20==0)
            living.addEffect(new MobEffectInstance(MobEffects.LEVITATION,25,0,false,false));
        attrition(l,e,age,now);
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
        attrition(l,e,age,now);
    }

    /** The collapse never lands. Terminal velocity is enforced so the loop stays survivable to watch. */
    private static void falling(Entity e,long now) {
        Vec3 v=e.getDeltaMovement();
        if(v.y>-1.6)field(e,new Vec3(0,-.045,0),now);
        e.fallDistance=0;
    }

    /**
     * A held catastrophe: everything in it is held too, until Loki lets the spears
     * go. Being held inside a stopped instant is not something a body survives, so
     * after half a minute the moment starts closing on whatever is standing in it.
     */
    private static void frozen(ServerLevel l,Entity e,long age,long now) {
        if(!(e instanceof LivingEntity living))return;
        if(now%10==0){
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,20,5,false,false));
            living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,20,4,false,false));
            living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,20,0,false,false));
        }
        if(age>600&&now%40==0)living.hurt(source(l,COLLAPSE),Math.min(10F,2F+(age-600)/240F));
    }

    /**
     * The press. The floor is real blocks and never moves - an invisible floor
     * rising away from a visible slab was the part that made no sense - and one
     * plane comes down onto it, grinding the columns between them away as it goes.
     * Nothing is teleported onto the floor any more; standing on it is ordinary.
     */
    private static void crushing(ServerLevel l,Entity e,double cell,long age,long now) {
        double ceiling=WarpMath.ceiling(age),floor=WarpMath.floor(age);
        double head=e.getY()+e.getBbHeight();
        if(head>ceiling){
            // Pushed down by the plane in steps it could plausibly push, not snapped through it.
            double push=Math.min(head-ceiling,.6);
            e.teleportTo(e.getX(),Math.max(floor,e.getY()-push),e.getZ());
            e.setDeltaMovement(e.getDeltaMovement().x,Math.min(0,e.getDeltaMovement().y),e.getDeltaMovement().z);
            e.hurtMarked=true;e.fallDistance=0;
        }
        double room=ceiling-floor-e.getBbHeight();
        if(room<.35&&now%10==0){
            float crush=room<=.05?1000F:(float)Math.max(5,(.35-room)*70);
            e.hurt(l.damageSources().inWall(),crush);
        }
        if(Math.abs(e.getX()-cell)>44||Math.abs(e.getZ())>44)
            field(e,new Vec3(cell,floor+2,0).subtract(e.position()).normalize().scale(.06),now);
    }

    /** Grinds away whatever the descending plane has reached since the last check. */
    private static void press(ServerLevel l,double cell,long age,long now) {
        if(now%4!=0)return;
        long key=(long)cell;
        int reached=WarpMath.pressGround(age);
        Integer last=GROUND.put(key,reached);
        if(last==null||reached>=last)return;
        boolean broke=false;
        for(int y=Math.max(reached,last-8);y<last;y++)
            for(int[] pillar:RealmLayout.pressPillars())
                for(int dx=0;dx<RealmLayout.PILLAR;dx++)for(int dz=0;dz<RealmLayout.PILLAR;dz++){
                    BlockPos pos=new BlockPos((int)cell+pillar[0]+dx,y,pillar[1]+dz);
                    if(l.getBlockState(pos).isAir())continue;
                    l.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2|16);
                    broke=true;
                }
        if(broke)l.playSound(null,BlockPos.containing(cell,reached,0),
            net.minecraft.sounds.SoundEvents.DEEPSLATE_BREAK,SoundSource.BLOCKS,4F,.45F);
    }

    private static void endOfTime(ServerLevel l,Entity e,long age,long now) {
        if(!(e instanceof LivingEntity living)||now%40!=0)return;
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,65,2,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,65,1,false,false));
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,65,0,false,false));
        if(age>200)living.hurt(l.damageSources().wither(),Math.min(6F,2F+age/900F));
    }

    /**
     * Every destination resolves. A trap that can hold a victim indefinitely without
     * killing them is a softlock: no exit, no death, nothing to do. The realms with no
     * lethality of their own close on whatever is still inside them.
     */
    private static void attrition(ServerLevel l,Entity e,long age,long now) {
        if(age<ATTRITION||now%40!=0)return;
        e.hurt(source(l,COLLAPSE),Math.min(12F,1F+(age-ATTRITION)/300F));
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

    public static void reset(){JOBS.clear();HISTORY.clear();HUNTER_DUE.clear();GROUND.clear();}
}
