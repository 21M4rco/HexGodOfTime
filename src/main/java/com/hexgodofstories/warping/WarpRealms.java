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
        /**
         * Bumped whenever {@link RealmLayout} changes what a realm is made of.
         *
         * <p>A realm is built once and then remembered as ready forever, which is right until the
         * blueprint changes underneath an existing save: the new architecture would then never be
         * placed in any world that had already opened the way once. A mismatch here simply forgets
         * that the realms were built, and the next portal rebuilds them over the top.
         */
        static final int LAYOUT=2;
        int next;final Map<Long,Long> clocks=new HashMap<>();final Set<Long> ready=new HashSet<>();
        static Ledger load(CompoundTag n){Ledger l=new Ledger();l.next=n.getInt("next");
            if(n.getInt("layout")==LAYOUT)for(long x:n.getLongArray("ready"))l.ready.add(x);
            ListTag a=n.getList("clocks",10);for(int i=0;i<a.size();i++){var c=a.getCompound(i);l.clocks.put(c.getLong("cell"),c.getLong("time"));}return l;}
        public CompoundTag save(CompoundTag n){n.putInt("next",next);n.putInt("layout",LAYOUT);n.putLongArray("ready",ready.stream().mapToLong(Long::longValue).toArray());ListTag a=new ListTag();clocks.forEach((x,t)->{CompoundTag c=new CompoundTag();c.putLong("cell",x);c.putLong("time",t);a.add(c);});n.put("clocks",a);return n;}
    }
    private record Job(ServerLevel level,Destination d,double cell,Iterator<RealmLayout.Voxel> blocks){}
    private static final List<Job> JOBS=new ArrayList<>();
    private static final Map<UUID,ArrayDeque<Vec3>> HISTORY=new HashMap<>();
    private static Ledger ledger(ServerLevel l){return l.getDataStorage().computeIfAbsent(Ledger::load,Ledger::new,"warping_realms");}
    /**
     * One realm per destination, shared by everyone, forever. Each portal used to be handed its own
     * 1024 block slice, which made every trip a fresh private copy; a destination that is a place
     * rather than an instance is the whole point, and the Void Sea in particular only means
     * anything if it is the same ocean with the same god in it every time you open the way.
     */
    public static final double CELL = 0.0;
    public static double allocate(ServerLevel l){return CELL;}
    public static double latest(ServerLevel l){return CELL;}
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
            // No destination hands out flight any more, not even to the caster who opened it. A
            // realm you can rise out of is scenery; these are meant to be survived from inside.
            com.hexgodofstories.server.CosmicFlight.revoke(p);
            if(!p.isCreative()&&!p.isSpectator()){p.getAbilities().flying=false;p.onUpdateAbilities();}
        }else{
            e.stopRiding();e.changeDimension(to,new ITeleporter(){public Entity placeEntity(Entity entity,ServerLevel current,ServerLevel dest,float yaw,java.util.function.Function<Boolean,Entity> reposition){Entity moved=reposition.apply(false);if(moved!=null){moved.moveTo(pos.x,pos.y,pos.z,yaw,0);moved.setDeltaMovement(0,-.3,0);moved.fallDistance=0;}return moved;}});
        }
        e.setDeltaMovement(0,owner?0:-.3,0);e.fallDistance=0;HexNetwork.arrival(e);
    }
    public static void tick(ServerLevel l){
        Destination d=Destination.from(l);if(d==null)return;
        RadialRealmRules.clean(l);
        int budget=4096;
        for(var it=JOBS.iterator();it.hasNext()&&budget>0;){Job j=it.next();if(j.level!=l)continue;
            while(j.blocks.hasNext()&&budget-->0){var v=j.blocks.next();l.setBlock(v.pos().offset((int)j.cell,0,0),v.state(),2|16);}
            if(!j.blocks.hasNext()){ledger(l).ready.add((long)j.cell);ledger(l).setDirty();populate(l,d,j.cell);it.remove();}
        }
        long now=l.getGameTime();
        if(d==Destination.VOID_SEA)com.hexgodofstories.warping.leviathan.PilgrimWarden.tick(l,now);
        List<Entity> active=new ArrayList<>();l.getAllEntities().forEach(active::add);
        for(Entity e:active){
            if(!e.isAlive()||e.isSpectator()||(d!=Destination.GRAVITY_WELL&&(e instanceof WarpHazard||e instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)))continue;
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
            // The realm acts on everyone in it, the caster included. It used to exempt any player
            // with Warping unlocked from every hazard but the Sun, which meant the one person most
            // likely to be standing in a realm was the one person nothing in it could touch: no
            // pull, no planes, no storm, no cold. Creative and spectator remain the way to look
            // around without being killed for it.
            if(e instanceof net.minecraft.world.entity.player.Player p&&p.isCreative()&&d!=Destination.GRAVITY_WELL&&!(d==Destination.CRUSHING_REALM&&MoonGravity.active(e)))continue;
            descend(d,e);
            rescue(l,d,e,cell);
            switch(d){
                case SUN -> solarExposure(l,e,cell,now);
                case VOID_SEA -> {}  // the realm's only hazard is alive and has its own AI
                case GRAVITY_WELL -> singularity(l,e,cell,now);
                case SHATTERED_WORLD -> {
                    // Gravity lets go for thirty five ticks in every twelve seconds, and what is
                    // between the islands is a long way down.
                    long phase=age%240;if(phase<35){e.setDeltaMovement(e.getDeltaMovement().add(Math.sin(age*.05)*.015,.115,Math.cos(age*.05)*.015));e.hurtMarked=true;}
                }
                case TIME_STORM -> {
                    if(now%5==0){var h=HISTORY.computeIfAbsent(e.getUUID(),k->new ArrayDeque<>());h.addLast(e.position());while(h.size()>13)h.removeFirst();
                        if(age>60&&age%100<5&&h.size()>10){Vec3 back=h.getFirst();place(e,back.x,back.y,back.z);e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;HexNetwork.fx(e,"slip");h.clear();}}
                    if(age%100>85&&e instanceof LivingEntity living)living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,8,3,false,false));
                }
                case FALLING_WORLD -> {if(e.getY()<48)place(e,e.getX(),231,e.getZ());if(e.getDeltaMovement().y>-.15)e.setDeltaMovement(e.getDeltaMovement().add(0,-.035,0));}
                case FROZEN_MOMENT -> freeze(l,e,now);
                case CRUSHING_REALM -> {
                    MoonGravity.enforce(e);
                    if(now%10==0){
                        Vec3 forward=MoonGravity.forward(e);CompoundTag frame=new CompoundTag();
                        frame.putDouble("x",forward.x);frame.putDouble("y",forward.y);frame.putDouble("z",forward.z);
                        HexNetwork.tracking(e,new HexNetwork.Message(HexNetwork.MOON_FRAME,e.getId(),frame));
                    }
                }
                case END_OF_TIME -> {if(e instanceof LivingEntity living&&now%40==0){living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,65,2,false,false));living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,65,1,false,false));living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,65,0,false,false));if(age>200)living.hurt(l.damageSources().wither(),2);}}
            }
        }
        if(d==Destination.TIME_STORM&&now%200==0)HISTORY.keySet().removeIf(id->l.getEntity(id)==null);
    }
    // ------------------------------------------------------------------ shared realm physics

    /** Centre of the singularity, and the radius inside which nothing comes back out. */
    public static final double WELL_Y=CosmicPhysics.WELL_Y,HORIZON=CosmicPhysics.HORIZON;

    /**
     * Terminal descent in blocks per tick, before Minecraft's own gravity is added on top.
     *
     * <p>These are places, and a place you hang motionless in is not one. Vanilla's terminal
     * velocity of nearly four blocks a tick is a plummet that ends a hundred blocks later, which
     * suits none of them either; every realm is a long, slow fall through open space instead. The
     * Falling World is the exception in the fast direction, because falling is the entire realm,
     * and the Gravity Well in the other, because down there is decided by the singularity.
     */
    private static double sink(Destination d){
        return switch(d){
            case FALLING_WORLD -> 1.25;
            case GRAVITY_WELL, CRUSHING_REALM, VOID_SEA -> 0;
            default -> .35;
        };
    }

    /** A gentle, inevitable descent, and never any fall damage for it. */
    private static void descend(Destination d,Entity e){
        e.fallDistance=0;
        double limit=sink(d);if(limit<=0)return;
        Vec3 v=e.getDeltaMovement();
        if(v.y<-limit){e.setDeltaMovement(v.x,-limit,v.z);e.hurtMarked=true;}
    }

    /** Nothing is ever lost out of the bottom of a realm; it is put back at the top of one. */
    private static void rescue(ServerLevel l,Destination d,Entity e,double cell){
        if(d==Destination.GRAVITY_WELL||d==Destination.CRUSHING_REALM)return;
        double bottom=d==Destination.VOID_SEA?VoidSea.FLOOR-8:0;
        if(e.getY()>=bottom)return;
        double back=switch(d){
            case VOID_SEA -> VoidSea.SURFACE-24;
            case CRUSHING_REALM -> 120;
            default -> 200;
        };
        place(e,e.getX(),back,e.getZ());
        e.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Repositions something and makes it stick.
     *
     * <p>{@code Entity#teleportTo(x, y, z)} moves the server's copy and tells nobody, so a player
     * moved that way keeps walking from where their own client still thinks they are and the
     * server accepts it on the next movement packet. Every realm that relocates you — the endless
     * fall looping back to the top, the crushing planes holding you between them, the storm
     * rewinding you — was quietly doing nothing at all to players.
     */
    private static void place(Entity e,double x,double y,double z){
        if(e instanceof ServerPlayer p)p.connection.teleport(x,y,z,p.getYRot(),p.getXRot());
        else e.teleportTo(x,y,z);
        e.fallDistance=0;
    }

    // ------------------------------------------------------------------ per realm hazards

    /** Strong circulation with guaranteed slow infall. Contact, rather than distant shear, is lethal. */
    private static void singularity(ServerLevel l,Entity e,double cell,long now){
        Vec3 center=new Vec3(CELL,WELL_Y,0),relative=e.position().subtract(center);
        AABB box=e.getBoundingBox();
        double x=net.minecraft.util.Mth.clamp(center.x,box.minX,box.maxX)-center.x;
        double y=net.minecraft.util.Mth.clamp(center.y,box.minY,box.maxY)-center.y;
        double z=net.minecraft.util.Mth.clamp(center.z,box.minZ,box.maxZ)-center.z;
        if(x*x+y*y+z*z<=HORIZON*HORIZON||relative.lengthSqr()<=HORIZON*HORIZON){
            HexNetwork.fx(e,"slip");
            e.hurt(l.damageSources().genericKill(),Float.MAX_VALUE);
            if(e.isAlive()&&e instanceof LivingEntity living){
                living.setHealth(0);living.die(l.damageSources().genericKill());
            }
            if(!(e instanceof ServerPlayer)&&e.isAlive())e.discard();
            return;
        }
        e.stopRiding();
        if(e instanceof ServerPlayer p){
            com.hexgodofstories.server.CosmicFlight.revoke(p);
            if(p.getAbilities().flying){p.getAbilities().flying=false;p.onUpdateAbilities();}
        }
        CosmicPhysics.V next=CosmicPhysics.orbit(new CosmicPhysics.V(relative.x,relative.y,relative.z));
        Vec3 target=center.add(next.x(),next.y(),next.z());
        // Authoritative positioning also catches immobile entities and mobs that ignore knockback.
        // Ordinary movement is suppressed here; no input or knockback can cancel the pull.
        place(e,target.x,target.y,target.z);
        e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;
    }

    /**
     * The frozen moment. A catastrophe held still is held still because nothing here is warm
     * enough to move, and that includes the visitor.
     *
     * <p>Driven through the vanilla freeze counter rather than a private one, so the player gets
     * the frost closing in around the screen that they already know how to read. Minecraft thaws
     * anything not standing in powder snow by two ticks each tick, so this adds three to make one.
     */
    private static void freeze(ServerLevel l,Entity e,long now){
        if(!(e instanceof LivingEntity living)||!living.canFreeze())return;
        int required=living.getTicksRequiredToFreeze();
        living.setTicksFrozen(Math.min(required*3,living.getTicksFrozen()+3));
        double chill=Math.min(1.0,living.getTicksFrozen()/(double)required);
        if(chill>.35&&now%20==0)living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,30,chill>.8?2:chill>.6?1:0,false,false));
        if(chill>=1&&now%30==0)living.hurt(l.damageSources().freeze(),3);
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
        if(d==Destination.VOID_SEA)com.hexgodofstories.warping.leviathan.PilgrimWarden.ensure(l);
        // A rebuild re-runs this, and the hazards are entities rather than blocks: without this a
        // layout bump would leave the Falling World with two sets of debris in it, then three.
        if(!l.getEntitiesOfClass(WarpHazard.class,new AABB(cell-200,0,-200,cell+200,320,200)).isEmpty())return;
        if(d==Destination.FALLING_WORLD||d==Destination.FROZEN_MOMENT){
            Random r=new Random(819+d.ordinal());int count=d==Destination.FALLING_WORLD?48:32;
            for(int i=0;i<count;i++){WarpHazard h=HexGodOfStories.WARP_HAZARD.get().create(l);if(h==null)continue;
                h.configure(d==Destination.FROZEN_MOMENT?(i%4==0?5:1):i%3+2,cell,i);h.moveTo(cell+r.nextInt(100)-50,140+r.nextInt(90),r.nextInt(100)-50,0,0);l.addFreshEntity(h);}
        }
    }
    public static void releaseHazards(ServerPlayer p){if(!Warping.sovereign(p))return;for(WarpHazard h:p.serverLevel().getEntitiesOfClass(WarpHazard.class,p.getBoundingBox().inflate(96)))h.release(p.getLookAngle());HexNetwork.fx(p,"resume");}
    public static void reset(){RadialRealmRules.clear();MoonGravity.clear();JOBS.clear();HISTORY.clear();com.hexgodofstories.warping.leviathan.PilgrimWarden.reset();}
}
