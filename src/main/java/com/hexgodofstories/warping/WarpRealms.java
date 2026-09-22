package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.FractureAnchor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.Level;
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
        static final int LAYOUT=3;
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
            // A destination still hands out nothing: arriving does not grant flight, and a caster
            // without the mantle crosses on foot. What the mantle carries, it carries everywhere,
            // so a transformed keeper is left alone here rather than being stripped on arrival.
            if(!com.hexgodofstories.server.CosmicFlight.mantled(p)){
                com.hexgodofstories.server.CosmicFlight.revoke(p);
                if(!p.isCreative()&&!p.isSpectator()){p.getAbilities().flying=false;p.onUpdateAbilities();}
            }
        }else{
            e.stopRiding();e.changeDimension(to,new ITeleporter(){public Entity placeEntity(Entity entity,ServerLevel current,ServerLevel dest,float yaw,java.util.function.Function<Boolean,Entity> reposition){Entity moved=reposition.apply(false);if(moved!=null){moved.moveTo(pos.x,pos.y,pos.z,yaw,0);moved.setDeltaMovement(0,-.3,0);moved.fallDistance=0;}return moved;}});
        }
        e.setDeltaMovement(0,owner?0:-.3,0);e.fallDistance=0;HexNetwork.arrival(e);
    }
    /**
     * A crossing that carries its motion with it.
     *
     * <p>{@link #transfer} puts a body at a destination. This puts it <em>through</em> one: it is
     * what the end of a fall through a Warping break runs, and every difference between the two is
     * about not interrupting the movement that is already happening.
     *
     * <p>Three things are preserved exactly. The velocity, so a body that went in sprinting comes
     * out travelling; the heading and pitch, so the view does not so much as twitch; and the fall
     * already in progress, so a drop that began in one world is still the same drop in the next.
     *
     * <p>The one thing that has to be worked around is the position packet. A dimension change sends
     * an absolute one, and an absolute position packet makes the receiving client zero its own
     * velocity — which is exactly the "appears stationary at a destination coordinate" that the
     * whole of this is trying not to be. The motion packet sent immediately behind it puts the fall
     * back on the same tick, before the client has drawn a frame without it.
     *
     * <p>Where it comes out is the realm's own entry point, offset by however far from the middle of
     * the break the body went in. Two creatures that went through opposite sides of the same hole
     * come out on opposite sides of the entry, which is what makes an opening read as a connection
     * between two places rather than as two coordinates.
     */
    public static void fallThrough(Entity e,Destination d,double cell,Vec3 offset,Vec3 momentum,float fall,boolean owner){
        ServerLevel old=(ServerLevel)e.level(),to=old.getServer().getLevel(d.key);
        if(to==null){e.noPhysics=false;return;}
        double spreadX=net.minecraft.util.Mth.clamp(offset.x,-ENTRY_SPREAD,ENTRY_SPREAD);
        double spreadZ=net.minecraft.util.Mth.clamp(offset.z,-ENTRY_SPREAD,ENTRY_SPREAD);
        Vec3 pos=landing(to,e,d.arrival.add(cell,owner?8:0,0),spreadX,spreadZ);
        float yaw=e.getYRot(),pitch=e.getXRot();
        if(e instanceof ServerPlayer p){
            if(Destination.from(old)==null)HexData.get(p).put("warpReturn",new FractureAnchor(old.dimension(),p.position(),yaw,pitch).save());
            p.stopRiding();
            p.teleportTo(to,pos.x,pos.y,pos.z,yaw,pitch);
            p.noPhysics=false;
            // The mantle carries flight everywhere; nothing else does, and arriving grants nothing.
            if(!com.hexgodofstories.server.CosmicFlight.mantled(p)){
                com.hexgodofstories.server.CosmicFlight.revoke(p);
                if(!p.isCreative()&&!p.isSpectator()){p.getAbilities().flying=false;p.onUpdateAbilities();}
            }
            p.setDeltaMovement(momentum);
            p.fallDistance=fall;
            p.hurtMarked=true;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            emerging(to,p);
            return;
        }
        e.stopRiding();
        Entity moved=e.changeDimension(to,new ITeleporter(){public Entity placeEntity(Entity entity,ServerLevel current,ServerLevel dest,float ignored,java.util.function.Function<Boolean,Entity> reposition){
            Entity copy=reposition.apply(false);
            if(copy!=null){copy.moveTo(pos.x,pos.y,pos.z,yaw,pitch);copy.setDeltaMovement(momentum);copy.fallDistance=fall;copy.noPhysics=false;}
            return copy;}});
        if(moved==null){e.noPhysics=false;return;}
        moved.setDeltaMovement(momentum);
        moved.fallDistance=fall;
        moved.hurtMarked=true;
        emerging(to,moved);
    }
    /** How far from a realm's entry point a crossing may come out, in blocks. */
    private static final double ENTRY_SPREAD=6;

    /**
     * Somewhere at the entry that the body actually fits.
     *
     * <p>The spread is worth having — two creatures that went in on opposite sides of one pool
     * coming out on opposite sides of the entry is what makes an opening read as a connection
     * between two places rather than as two coordinates — but it was being applied as though six
     * blocks either side of a realm's entry point were necessarily six blocks of air. They are
     * not. An entry point is chosen to be clear; nothing promises the same of the ground around
     * it, so on a realm of broken islands and ruins an offset could put a body inside a rock or
     * under a ledge, which is to say arriving buried.
     *
     * <p>So the offset is a preference rather than a place. It is walked back toward the entry
     * until the body fits, and the entry itself — which every realm does guarantee — is what is
     * left if nothing on the way in does. If even that has been built over since, the answer is
     * straight up from it, because a realm's sky is the one part of it nothing generates into.
     */
    private static Vec3 landing(ServerLevel to,Entity e,Vec3 heart,double dx,double dz){
        for(double f=1;f>.001;f-=.25){
            Vec3 at=heart.add(dx*f,0,dz*f);
            if(room(to,e,at))return at;
        }
        if(room(to,e,heart))return heart;
        for(int lift=2;lift<=32;lift+=2){
            Vec3 at=heart.add(0,lift,0);
            if(room(to,e,at))return at;
        }
        return heart;
    }

    /** Whether this body's whole box is clear of blocks here. Liquid is fine: the sea is a landing. */
    private static boolean room(ServerLevel to,Entity e,Vec3 at){
        return to.noCollision(e.getType().getAABB(at.x,at.y,at.z));
    }

    /**
     * Daylight for a body that is about to be put somewhere it may no longer fit.
     *
     * <p>Coming back out of a realm had no equivalent of {@link #landing}. The return point is the
     * position the caster stood at when they went in, and a crossing goes in by falling <em>through</em>
     * the floor with collision off, so the position recorded is routinely a little under the surface
     * rather than on it. Put back at exactly that coordinate, the body arrives inside the ground —
     * and the recall does the same thing to everything it drags out with it.
     *
     * <p>So every arrival gets asked the same question the way in already asks: does this body fit
     * here? If it does, nothing changes. If it does not, the answer is straight up — the first clear
     * height above the point, and failing that the world's own surface in that column, which is the
     * one place nothing is ever buried.
     */
    public static Vec3 daylight(ServerLevel to,Entity e,Vec3 at){
        if(room(to,e,at))return at;
        double ceiling=Math.min(at.y+48,to.getMaxBuildHeight()-1);
        for(double y=Math.floor(at.y)+1;y<=ceiling;y++){
            Vec3 up=new Vec3(at.x,y,at.z);
            if(room(to,e,up))return up;
        }
        BlockPos ground=to.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,BlockPos.containing(at));
        Vec3 open=new Vec3(at.x,ground.getY(),at.z);
        return room(to,e,open)?open:at;
    }
    /**
     * Coming out of the other side. A little of the liquid trailing the body rather than the
     * arrival nebula, because a nebula around somebody still falling reads as having been put there.
     */
    private static void emerging(ServerLevel to,Entity e){
        to.sendParticles(HexGodOfStories.MOTE.get(),e.getX(),e.getY()+e.getBbHeight()*.6,e.getZ(),
            9,e.getBbWidth()*.5,e.getBbHeight()*.4,e.getBbWidth()*.5,.03);
    }

    /** The title's colour: a dark blue with the green and the violet either side of it in it. */
    private static final int COSMIC=0x354B8D;
    /**
     * The Void Sea introduces itself to whoever has just arrived in it.
     *
     * <p>Sent on the dimension change rather than from the transfer, so every way in says the same
     * thing: the trap, a deliberate crossing, an operator's teleport. The realm drops arrivals
     * fifty blocks above the waterline precisely so there is a moment to read it in, and the
     * timings here are written to outlast the fall and the splash at the end of it.
     */
    public static void greet(ServerPlayer p,ResourceKey<Level> to){
        if(!Destination.VOID_SEA.key.equals(to))return;
        p.connection.send(new ClientboundSetTitlesAnimationPacket(15,70,25));
        // Subtitle first: the client stores it, and it is the title packet that starts the animation.
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("The water feels strange...").withStyle(ChatFormatting.DARK_GRAY)));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Cosmic Sea")
            .withStyle(style->style.withColor(TextColor.fromRgb(COSMIC)).withBold(true))));
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
        if(d==Destination.VOID_SEA){com.hexgodofstories.warping.leviathan.PilgrimWarden.tick(l,now);
            // Preserve player prediction; mobs receive the same swell in the existing loop below.
            VoidSeaWaves.tick(l,now);}
        if(d==Destination.PARADISE){
            ParadiseRestoration.tick(l);
            if(now%4==0&&!l.players().isEmpty())steam(l,now);
        }
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
                    case PARADISE -> HexGodOfStories.BRANCH_SHIMMER.get();
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
            // A body going down into a Warping pool is between worlds, and this one has let go of
            // it. Every realm below pushes bodies about — a pull, a lift, a gravity pulse, a
            // rewind — and any of them applied to somebody half way into an opening would fight
            // the sink or drag them off it. Paradise is the plain case: its weak gravity hands
            // back more each tick than the sink takes, so leaving it running would not slow a
            // crossing, it would reverse one.
            if(WarpCrossing.crossing(e))continue;
            descend(d,e);
            rescue(l,d,e,cell);
            switch(d){
                case SUN -> solarExposure(l,e,cell,now);
                case VOID_SEA -> {if(e instanceof Mob)VoidSeaWaves.apply(e,now);}
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
                case PARADISE -> {Paradise.gravity(e);paradiseWater(l,e,now);}
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
     * suits none of them either; every realm is a long, slow fall through open space instead.
     * Paradise sets its own, slower still, because dropping between its islands is something you
     * are meant to be able to steer; the Gravity Well opts out entirely, because down there is
     * decided by the singularity rather than by gravity.
     */
    private static double sink(Destination d){
        return switch(d){
            case PARADISE -> Paradise.SINK;
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
        double bottom=switch(d){
            case VOID_SEA -> VoidSea.FLOOR-8;
            case PARADISE -> (double)Paradise.FLOOR;
            default -> 0;
        };
        if(e.getY()>=bottom)return;
        double back=switch(d){
            case VOID_SEA -> VoidSea.SURFACE-24;
            case PARADISE -> (double)Paradise.CEILING;
            case CRUSHING_REALM -> 120;
            default -> 200;
        };
        // Paradise folds back on itself. Everywhere else it is enough to put a faller back at the
        // top of the column they fell down, but there is nothing under an island's edge here, so
        // the same column would simply be fallen down again. A faller reappears over the heart and
        // drifts back onto it, which is the only way out of the void that is not a death.
        if(d==Destination.PARADISE)place(e,Destination.PARADISE.arrival.x+cell,back,Destination.PARADISE.arrival.z);
        else place(e,e.getX(),back,e.getZ());
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
        // The well takes flight from anyone it is dragging in — except a keeper wearing the mantle,
        // whose flight is theirs in every dimension. They are still pulled; they can still fly.
        if(e instanceof ServerPlayer p&&!com.hexgodofstories.server.CosmicFlight.mantled(p)){
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

    /**
     * Paradise's water, and what it pays out for being swum in.
     *
     * <p>Every pool in the realm counts. The hot spring is the one the place is built around, but
     * the ponds on the outer islands and the cascades themselves are the same water, and a realm
     * that rewarded exactly one puddle would be a realm with one place worth standing in.
     *
     * <p>Refreshed rather than stacked: a full duration is handed out once a second while a body
     * is in the water, so climbing out leaves eleven seconds of it and nothing lasts for ever. The
     * two vanilla gifts are the ones that read without explanation — the health coming back and the
     * hearts to hold it — and the third is this realm's own.
     */
    private static void paradiseWater(ServerLevel l,Entity e,long now){
        if(now%20!=0||!(e instanceof LivingEntity living)||!Paradise.bathing(living))return;
        living.addEffect(new MobEffectInstance(MobEffects.REGENERATION,Paradise.BATHE_TICKS,1,true,true,true));
        living.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST,Paradise.BATHE_TICKS,1,true,true,true));
        living.addEffect(new MobEffectInstance(HexGodOfStories.CANDY_RUSH.get(),Paradise.BATHE_TICKS,0,true,true,true));
    }

    /**
     * The mist standing over the hot spring, and the sugar hanging in the air above it.
     *
     * <p>Sent from the server rather than grown on each client because it belongs to a fixed place
     * rather than to a viewer: the spring is at the middle of the heart whoever is looking at it.
     * It costs one packet every fifth of a second, and only while somebody is in the realm at all.
     */
    private static void steam(ServerLevel l,long now){
        Paradise.Isle heart=Paradise.heart();
        Random r=new Random(now*2654435761L);
        for(int i=0;i<3;i++){
            double a=r.nextDouble()*Math.PI*2,reach=Math.sqrt(r.nextDouble())*Paradise.springRim(a);
            double x=heart.x()+Math.cos(a)*reach,z=heart.z()+Math.sin(a)*reach;
            l.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,x,Paradise.SURFACE+1.15,z,1,.12,.01,.12,.008);
            if(i==0)l.sendParticles(HexGodOfStories.CANDY.get(),x,Paradise.SURFACE+1.6+r.nextDouble()*2.2,z,1,.5,.35,.5,.01);
        }
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
        // layout bump would leave the Frozen Moment with two sets of spears in it, then three.
        if(!l.getEntitiesOfClass(WarpHazard.class,new AABB(cell-200,0,-200,cell+200,320,200)).isEmpty())return;
        if(d==Destination.FROZEN_MOMENT){
            Random r=new Random(819+d.ordinal());
            for(int i=0;i<32;i++){WarpHazard h=HexGodOfStories.WARP_HAZARD.get().create(l);if(h==null)continue;
                h.configure(i%4==0?5:1,cell,i);h.moveTo(cell+r.nextInt(100)-50,140+r.nextInt(90),r.nextInt(100)-50,0,0);l.addFreshEntity(h);}
        }
    }
    public static void releaseHazards(ServerPlayer p){if(!Warping.sovereign(p))return;for(WarpHazard h:p.serverLevel().getEntitiesOfClass(WarpHazard.class,p.getBoundingBox().inflate(96)))h.release(p.getLookAngle());HexNetwork.fx(p,"resume");}
    public static void reset(){RadialRealmRules.clear();MoonGravity.clear();JOBS.clear();HISTORY.clear();ParadiseRestoration.reset();WarpCrossing.reset();com.hexgodofstories.warping.leviathan.PilgrimWarden.reset();}
}
