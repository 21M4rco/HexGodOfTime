package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraft.sounds.SoundSource;
import java.util.*;

/** Server-owned floor selection; release never trusts a client position, duration or destination. */
public final class Warping {
    private static final Map<UUID,Charge> CHARGES=new HashMap<>();
    // Released portals own their lifetime, even when their caster crosses, logs out or changes spells.
    private static final Map<UUID,Charge> PORTALS=new HashMap<>();
    private static final class Charge {
        final ServerLevel level;final Vec3 at;final Destination destination;final long start;final double cell;final int ownerId;
        /** This pool's own shape. Sent to the client so both sides spread it identically. */
        final long seed;
        long opened=-1;int held;final Set<UUID> moved=new HashSet<>();
        /** Whether the far side was last reported as having anything in it, so an empty one is sent once. */
        boolean shadowed;
        /**
         * A recall break, which reaches into its destination rather than delivering anything to it.
         *
         * <p>It is the same object and the same geometry as an ordinary break on purpose: the
         * client is told about it through the identical packet and draws the identical spreading
         * fracture, so reality cracks open the same way whichever direction the ability is running
         * in. Only what happens once it is open differs, and these three fields are the whole of
         * that difference.
         */
        boolean recall;ArrayDeque<UUID> summons;long nextArrival;
        int openTicks(){return recall?RECALL_OPEN:WarpMath.OPEN_TICKS;}
        /** The opened pool, built once: held stops changing the moment the portal is released. */
        private double[] shape;private double extent;
        Charge(ServerPlayer p,Vec3 at,Destination d,double cell){level=p.serverLevel();this.at=at;destination=d;start=level.getGameTime();this.cell=cell;ownerId=p.getId();
            seed=start*1000003L^(long)Math.floor(at.x*17)*31L^(long)Math.floor(at.z*7919)^(long)p.getUUID().getLeastSignificantBits();}
        double[] shape(){
            if(shape==null){shape=WarpPool.rim(seed,WarpMath.reach(held),1);extent=WarpPool.extent(shape);}
            return shape;
        }
        double extent(){shape();return extent;}
    }
    public static boolean charging(ServerPlayer p){return CHARGES.containsKey(p.getUUID());}
    public static boolean sovereign(Entity e){return e instanceof ServerPlayer p&&HexData.access(p)&&HexData.unlocked(p,Ability.WARPING);}
    public static void choose(ServerPlayer p,int id){
        if(id<0||id>=Destination.values().length||charging(p)||HexData.selected(p)!=Ability.WARPING||!HexData.unlocked(p,Ability.WARPING))return;
        HexData.get(p).putInt("warpDestination",id);HexNetwork.sync(p);
    }
    private static BlockHitResult aim(ServerPlayer p){
        if(p.getLookAngle().y>=-.08)return null;
        BlockHitResult h=p.level().clip(new ClipContext(p.getEyePosition(),p.getEyePosition().add(p.getLookAngle().scale(32)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if(h.getType()!=HitResult.Type.BLOCK||h.getDirection()!=Direction.UP||!p.level().getBlockState(h.getBlockPos()).isFaceSturdy(p.level(),h.getBlockPos(),Direction.UP))return null;
        return h;
    }
    public static void begin(ServerPlayer p){
        if(charging(p)||PORTALS.containsKey(p.getUUID()))return;
        BlockHitResult hit=aim(p);
        if(hit==null){notice(p,"Warping requires a solid floor within 32 blocks.");return;}
        Destination d=Destination.at(HexData.get(p).getInt("warpDestination"));
        ServerLevel target=p.server.getLevel(d.key);
        if(target==null){notice(p,"Warping dimensions are unavailable. Restart the server after installing the update.");return;}
        // Fixed, shared and identical on every opening. Saves from before this change may carry a
        // private slice; it is ignored rather than honoured, so everyone converges on the one realm.
        double cell=WarpRealms.CELL;
        HexData.get(p).putDouble("warpPrepared_"+d.name(),cell);
        WarpRealms.prepare(target,d,cell);
        Charge c=new Charge(p,new Vec3(hit.getBlockPos().getX()+.5,hit.getLocation().y+.025,hit.getBlockPos().getZ()+.5),d,cell);
        CHARGES.put(p.getUUID(),c);HexNetwork.animate(p,"threads");send(c,false);
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1,.65f);
    }
    public static void release(ServerPlayer p){
        Charge c=CHARGES.get(p.getUUID());if(c==null||c.opened>=0||c.recall)return;
        int held=Math.min((int)(c.level.getGameTime()-c.start),WarpMath.FULL_CHARGE);
        // The tear is as wide as it was paid for. A caster who held past what their energy covers
        // opens the largest break that energy buys rather than being told it did not stabilize.
        held=Math.min(held,WarpMath.affordable(HexData.energy(p),Ability.WARPING.cost));
        if(held<WarpMath.MIN_CHARGE||!valid(p,c)||!WarpRealms.ready(p.server.getLevel(c.destination.key),c.cell)){cancel(p);notice(p,"The fracture did not stabilize.");return;}
        c.held=held;c.opened=c.level.getGameTime();
        CHARGES.remove(p.getUUID());PORTALS.put(p.getUUID(),c);
        HexData.spend(p,(float)(Ability.WARPING.cost*WarpMath.costScale(c.held)));HexData.get(p).putLong("cd_WARPING",HexData.now(p)+Ability.WARPING.cooldown);
        HexServer.reward(p,Ability.WARPING.discipline,90);HexNetwork.sync(p);
        // The black surface and glass share a sub-block polygon on the client. Do not voxelize it
        // into Nothingness cubes: their square tops protrude past the cracks and cannot match the rim.
        // Ground collision stays intact until an entity's feet enter the authoritative polygon.
        WarpRealms.start(p.server.getLevel(c.destination.key),c.cell);
        HexData.get(p).putDouble("warpCell_"+c.destination.name(),c.cell);
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1.25f,1.15f);
        send(c,false);
    }
    /**
     * Whether the caster can still be tearing this point open.
     *
     * <p>Where they are looking is deliberately not part of this. The point was chosen with the
     * crosshair on the tick the hold began and locked there; from then on the hold is concentration
     * rather than aim, so the caster may turn, look up, look behind them or lose sight of the floor
     * entirely and the break keeps opening where they put it. Nothing re-casts a ray while charging
     * and nothing moves the break to wherever the camera went.
     *
     * <p>Being hit is the one thing that takes it away, and that arrives through
     * {@link #interrupt} rather than being tested here.
     */
    private static boolean valid(ServerPlayer p,Charge c){
        if(!p.isAlive()||!HexData.access(p)||!HexData.unlocked(p,Ability.WARPING)||p.isSpectator()||p.level()!=c.level||HexData.selected(p)!=Ability.WARPING||TemporalEngine.frozen(p)||Erasure.erasing(p))return false;
        // A recall was paid for in full the moment it began; the floor below is an ordinary hold
        // still buying width, and applying it to a recall would cancel one on the tick after it
        // charged a caster who had exactly enough for it.
        return c.recall||HexData.energy(p)>=Ability.WARPING.cost*WarpMath.costScale(WarpMath.MIN_CHARGE);
    }
    /**
     * A blow lands on somebody mid-tear. The break they were holding open comes apart with them:
     * the charge, its locked point, its shape and everything the watching clients were drawing.
     */
    public static void interrupt(ServerPlayer p){
        if(!charging(p))return;
        cancel(p);
        notice(p,"The fracture collapsed.");
    }
    public static void tick(ServerLevel level){
        for(var entry:new ArrayList<>(CHARGES.entrySet())){
            Charge c=entry.getValue();if(c.level!=level)continue;
            ServerPlayer p=level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(p==null||p.level()!=level||!p.isAlive()||!HexData.access(p)){CHARGES.remove(entry.getKey());forget(c);send(c,true);continue;}
            long now=level.getGameTime();
            if(!valid(p,c)){cancel(p);continue;}
            // A recall is not held open by anybody: it spreads for a fixed count and then opens.
            if(c.recall){if(now-c.start>=RECALL_FORM)openRecall(p,c);else if(now%2==0)send(c,false);continue;}
            if(now-c.start>=WarpMath.MAX_HOLD){release(p);continue;}
            if(now%4==0)send(c,false);
        }
        for(var entry:new ArrayList<>(PORTALS.entrySet())){
            Charge c=entry.getValue();if(c.level!=level)continue;
            long now=level.getGameTime();
            if(c.opened<0||now<c.opened||now-c.opened>=c.openTicks()){
                PORTALS.remove(entry.getKey());forget(c);
                // Anybody still inside the opening when it shuts: past the point of no return they
                // finish the crossing, and short of it they get the floor back and are stood on it.
                if(!c.recall)WarpCrossing.closing(brk(entry.getKey(),c),now);
                send(c,true);
                level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);continue;
            }
            // What the far side has in it, for whoever is looking into the hole. Every few ticks
            // ordinarily, and every tick while something is crossing, because those are the ticks
            // an observer must not lose sight of the body going through.
            if(now%3==0||WarpCrossing.busy(entry.getKey(),now))shadows(level,c);
            // A recall opens downward into its realm and lifts things out. Nothing standing on it
            // is taken anywhere, so the entry sweep below is not merely skipped for tidiness: it
            // would send the caster into the realm they are reaching into.
            if(c.recall){emerge(level,c,now);if(now%3==0)send(c,false);continue;}
            // The way through is the break itself: the impact hole, the wedges opened around it and
            // the roots of the fractures wide enough to fall into. Hairline cracks are scenery and
            // carry nobody, which is what keeps this from being an invisible circle of triggers.
            //
            // Who may use it is unchanged — the caster, and anything that is not another keeper or
            // a creative player. What that permission now buys is different: not a teleport on
            // contact, but the right to sink through the opening. WarpCrossing owns the rest of it.
            final UUID owner=entry.getKey();
            WarpCrossing.tick(brk(owner,c),now,e->e.isAlive()&&!e.isSpectator()&&
                (e.getUUID().equals(owner)||!sovereign(e)&&!(e instanceof net.minecraft.world.entity.player.Player q&&q.isCreative())));
            if(now%4==0)send(c,false);
        }
    }

    /** How far around a realm's entry point the far side is watched, and how far down its drop. */
    private static final double SHADOW_REACH=72,SHADOW_DROP=112;
    /** The most bodies one opening reports at once. A window, not a census. */
    private static final int SHADOW_MAX=8;

    /**
     * What is on the other side, for the clients that can see the hole.
     *
     * <p>Without this, anything that falls through a break vanishes the instant the server changes
     * which dimension it is in, which undoes the whole of the crossing: every frame up to it says
     * "a hole in the floor", and then the body that went through it winks out of existence. So the
     * transforms of whatever is near the realm's entry — and, crucially, anything falling away down
     * the drop below it — go to the players standing at the opening, and their clients draw them in
     * the destination scene they are already rendering through it.
     *
     * <p>Nothing is created anywhere: these are positions, not entities. One bounded query every
     * three ticks per open break, of a level that is usually empty, and nothing at all is sent while
     * the far side stays empty.
     *
     * <p>Hexor is left out deliberately. A hundred and twenty six blocks of articulated body cannot
     * be honestly reported as one position and one bounding box, and the Void Sea's view through a
     * break already draws its own silhouette of the creature.
     */
    private static void shadows(ServerLevel level,Charge c){
        ServerLevel to=level.getServer().getLevel(c.destination.key);
        if(to==null)return;
        Vec3 heart=c.destination.arrival;
        AABB area=new AABB(heart.x-SHADOW_REACH,heart.y-SHADOW_DROP,heart.z-SHADOW_REACH,
            heart.x+SHADOW_REACH,heart.y+28,heart.z+SHADOW_REACH);
        List<Entity> seen=to.getEntities((Entity)null,area,e->e.isAlive()&&!e.isRemoved()&&!e.isSpectator()
            &&!(e instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)
            &&!(e instanceof WarpHazard)&&e.getBbHeight()>.25);
        ListTag list=new ListTag();
        if(!seen.isEmpty()){
            seen.sort(java.util.Comparator.comparingDouble(e->e.position().distanceToSqr(heart)));
            for(Entity e:seen){
                if(list.size()>=SHADOW_MAX)break;
                CompoundTag t=new CompoundTag();
                t.putInt("id",e.getId());t.putDouble("x",e.getX());t.putDouble("y",e.getY());t.putDouble("z",e.getZ());
                t.putFloat("yaw",e.getYRot());t.putFloat("w",e.getBbWidth());t.putFloat("h",e.getBbHeight());
                list.add(t);
            }
        }
        if(list.isEmpty()&&!c.shadowed)return;
        c.shadowed=!list.isEmpty();
        CompoundTag n=new CompoundTag();n.put("shades",list);
        HexNetwork.near(level,c.at,96,new HexNetwork.Message(HexNetwork.WARP_SHADOWS,c.ownerId,n));
    }

    public static void utility(ServerPlayer p){
        if(charging(p)){cancel(p);return;}
        if(Destination.from(p.level())==Destination.FROZEN_MOMENT){WarpRealms.releaseHazards(p);return;}
        Destination d=Destination.from(p.level());
        if(d!=null){leave(p);return;}
        // Deliberate entry separate from the trap: crouch + X while Warping is selected.
        if(p.isShiftKeyDown()){
            Destination selected=Destination.at(HexData.get(p).getInt("warpDestination"));ServerLevel level=p.server.getLevel(selected.key);if(level==null)return;
            double cell=WarpRealms.CELL;
            if(!WarpRealms.ready(level,cell)){notice(p,"Open a stabilized trap before entering its destination.");return;}
            WarpRealms.transfer(p,selected,cell,true);
        }
    }
    public static boolean leave(ServerPlayer p){
        if(Destination.from(p.level())==null||!sovereign(p))return false;
        FractureAnchor a=FractureAnchor.load(HexData.get(p).getCompound("warpReturn"));
        ServerLevel to=a==null?null:a.level(p.server);
        if(to==null){to=p.server.overworld();a=new FractureAnchor(to.dimension(),Vec3.atBottomCenterOf(to.getSharedSpawnPos()),p.getYRot(),p.getXRot());}
        // Where they went in is not necessarily somewhere they fit coming back: a crossing falls
        // through the floor with collision off, so the recorded point is usually a little under the
        // surface, and the ground there may have changed since in any case.
        Vec3 back=WarpRealms.daylight(to,p,a.at());
        cancel(p);HexNetwork.fx(p,"depart");p.teleportTo(to,back.x,back.y,back.z,a.yaw(),a.pitch());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        HexNetwork.arrival(p);return true;
    }
    /** This break, as much of it as a crossing needs. Cheap: the shape behind it is already built. */
    private static WarpCrossing.Break brk(UUID owner,Charge c){
        return new WarpCrossing.Break(owner,c.level,c.at,c.destination,c.cell,c.shape(),c.extent(),c.moved);
    }
    private static void send(Charge c,boolean clear){
        CompoundTag n=new CompoundTag();n.putBoolean("clear",clear);n.putDouble("x",c.at.x);n.putDouble("y",c.at.y);n.putDouble("z",c.at.z);n.putLong("start",c.start);n.putInt("destination",c.destination.ordinal());n.putLong("opened",c.opened);n.putInt("held",c.held);n.putLong("seed",c.seed);n.putLong("until",c.opened<0?c.level.getGameTime()+12:Math.min(c.opened+c.openTicks(),c.level.getGameTime()+12));n.putBoolean("recall",c.recall);n.putInt("window",c.openTicks());
        n.putString("dimension",c.level.dimension().location().toString());n.putLong("sent",c.level.getGameTime());
        ServerLevel target=c.level.getServer().getLevel(c.destination.key);
        n.putLong("realmAge",c.opened>=0&&target!=null?WarpRealms.age(target,c.cell):0);
        HexNetwork.near(c.level,c.at,96,new HexNetwork.Message(HexNetwork.WARP,c.ownerId,n));
    }
    public static void cancel(ServerPlayer p){Charge c=CHARGES.remove(p.getUUID());if(c!=null){forget(c);send(c,true);c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);}}
    public static void reset(){CHARGES.values().forEach(Warping::forget);PORTALS.values().forEach(Warping::forget);CHARGES.clear();PORTALS.clear();RECALLING.clear();WarpRealms.reset();}

    // ------------------------------------------------------------------ dimension entity recall

    /** How long the break takes to spread before it opens, and the charge that reads as. */
    private static final int RECALL_FORM=44;
    /** How long the way stays open afterwards. Long enough for the whole queue to climb out. */
    private static final int RECALL_OPEN=110;
    /** Ticks between arrivals, the most that come through at once, and what it costs. */
    private static final int RECALL_GAP=6,RECALL_MAX=10;
    private static final float RECALL_COST=(float)(Ability.WARPING.cost*.75);
    /** Recovery after a recall, and the shorter one for a realm that had nothing to give. */
    private static final int RECALL_COOLDOWN=600,RECALL_EMPTY=100;
    /** How hard something is thrown out of the break. A shove, not a catapult. */
    private static final double RECALL_LAUNCH=.56;

    /**
     * Everything currently being pulled through, anywhere.
     *
     * <p>A packet arriving twice, two casters reaching into the same realm on the same tick, or one
     * caster opening a second break before the first has emptied would all otherwise queue the same
     * creature more than once. An entity enters this set when it is queued and leaves it when it has
     * either arrived or been given up on, so it can only ever be in one recall at a time.
     */
    private static final Set<UUID> RECALLING=new HashSet<>();

    /**
     * Reaching into a realm instead of stepping into it.
     *
     * <p>Warping has only ever been a way out. This is the other direction: the same break, opened
     * on the same locked point of ground, reaching into whatever the G menu currently names and
     * pulling its inhabitants up through the floor of wherever the caster is standing. Nobody
     * travels. The realm comes to them.
     *
     * <p>Everything that decides what happens is decided here, on the server. The client sends one
     * bare keypress — no destination, no position, no entity list, no count — and every part of the
     * answer is worked out from state the server already owns: which realm is selected, what the
     * caster is looking at, whether the recall has recovered, what is alive in that realm, and
     * which of those things are allowed to be moved. A client cannot name an entity to teleport,
     * because it is never asked for one.
     *
     * <p>The point on the floor is chosen by the same ray the ordinary break uses and locked the
     * same way, so a recall obeys the same terrain rules: it conforms to slabs, steps and slopes,
     * it refuses a place with no solid floor under it, and it does not follow the camera once it
     * has begun.
     */
    public static void recall(ServerPlayer p){
        if(!sovereign(p)||HexData.selected(p)!=Ability.WARPING){notice(p,"Select Warping to reach into a realm.");return;}
        if(charging(p)||PORTALS.containsKey(p.getUUID())){notice(p,"A break is already open.");return;}
        long now=HexData.now(p);
        long recovered=HexData.get(p).getLong("cd_WARP_RECALL");
        if(recovered>now){notice(p,"Warping is still recovering. "+((recovered-now+19)/20)+"s");return;}
        if(HexData.energy(p)<RECALL_COST){notice(p,"Not enough Temporal Energy.");return;}
        Destination d=Destination.at(HexData.get(p).getInt("warpDestination"));
        if(Destination.from(p.level())==d){notice(p,"You are standing in it.");return;}
        ServerLevel target=p.server.getLevel(d.key);
        if(target==null){notice(p,"Warping dimensions are unavailable. Restart the server after installing the update.");return;}
        BlockHitResult hit=aim(p);
        if(hit==null){notice(p,"Warping requires a solid floor within 32 blocks.");return;}
        // Wake the realm, and ask it what is in it once the break has finished spreading. The two
        // seconds the fracture takes are not a delay to be worked around here — they are exactly
        // the window the level needs to bring an unattended realm's creatures back into memory.
        wake(target,d);
        Charge c=new Charge(p,new Vec3(hit.getBlockPos().getX()+.5,hit.getLocation().y+.025,hit.getBlockPos().getZ()+.5),d,WarpRealms.CELL);
        c.recall=true;c.summons=new ArrayDeque<>();
        CHARGES.put(p.getUUID(),c);
        HexData.spend(p,RECALL_COST);
        HexData.get(p).putLong("cd_WARP_RECALL",now+RECALL_COOLDOWN);
        HexServer.reward(p,Ability.WARPING.discipline,70);
        HexNetwork.sync(p);HexNetwork.animate(p,"threads");
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1.1f,.55f);
        send(c,false);
        notice(p,"Reaching into "+d.title+"...");
    }

    /**
     * Brings a bounded patch of a realm into memory so that there is something to find in it.
     *
     * <p>A dimension with nobody in it has no chunks loaded and therefore no entities loaded, so
     * asking a cold realm what lives in it truthfully answers "nothing" however full it is. This
     * reads forty nine chunks — a hundred and twelve blocks square, centred on the realm's own
     * arrival point, which is where anything that was ever sent there was sent — and no more.
     *
     * <p>It takes no ticket and forces nothing: these are ordinary reads, and the chunks fall out
     * of memory again on their own once nobody is looking at them. It happens once per recall,
     * behind a thirty second recovery, so the worst this can be asked to do is forty nine chunk
     * loads every half minute per caster. The realm is not swept, scanned, ticked or held.
     */
    private static void wake(ServerLevel target,Destination d){
        net.minecraft.world.level.ChunkPos centre=new net.minecraft.world.level.ChunkPos(BlockPos.containing(d.arrival));
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)target.getChunk(centre.x+dx,centre.z+dz);
    }

    /**
     * What a realm has to give.
     *
     * <p>{@code getAllEntities} walks the level's own list of what is already in memory. It loads
     * no chunk, ticks nothing and forces nothing, which is what makes this safe to call on a
     * dimension nobody is standing in; {@link #wake} is what makes it useful to.
     *
     * <p>The ones nearest the realm's own arrival point are preferred, because that is the part of
     * a realm anything sent there was sent to, and the part this has just woken.
     */
    private static List<UUID> answering(ServerLevel target,Destination d){
        List<LivingEntity> found=new ArrayList<>();
        for(Entity e:target.getAllEntities()){
            if(!recallable(e)||RECALLING.contains(e.getUUID()))continue;
            found.add((LivingEntity)e);
            if(found.size()>RECALL_MAX*4)break;
        }
        Vec3 heart=d.arrival;
        found.sort(java.util.Comparator.comparingDouble(e->e.position().distanceToSqr(heart)));
        List<UUID> ids=new ArrayList<>();
        for(LivingEntity e:found){if(ids.size()>=RECALL_MAX)break;ids.add(e.getUUID());}
        return ids;
    }

    /**
     * Whether this is a creature a recall may take.
     *
     * <p>Living, alive, and its own body. Everything a realm contains that is not a creature —
     * items, orbs, projectiles, the falling architecture, the portal helpers, display entities,
     * markers and seats — fails the first test, because none of them is a {@code LivingEntity} at
     * all. What has to be named explicitly is the handful of things that are.
     *
     * <p><b>Hexor is never any of this.</b> The Void Sea's god is a {@code Mob}, which means every
     * generic test for "a living thing" says yes to it, and a recall that took it would take the
     * one creature the whole realm exists to contain and put it in somebody's garden. It is refused
     * twice — by its class and by its registered type — so neither a subclass nor a rebuild of the
     * entity can slip past a single check, and it is refused here, in the server-side filter that
     * every path into a transfer runs through, rather than anywhere a client could be asked.
     * Nothing else about it is touched: it is not moved, copied, marked, despawned or looked at
     * again. The sea keeps it.
     */
    private static boolean recallable(Entity e){
        if(!(e instanceof LivingEntity living)||!living.isAlive()||living.isRemoved()||living.isSpectator())return false;
        if(living instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)return false;
        if(living.getType()==HexGodOfStories.PILGRIM.get())return false;
        // People are not livestock, and a projection is a lie with a body rather than a creature.
        if(living instanceof net.minecraft.world.entity.player.Player)return false;
        if(living instanceof com.hexgodofstories.entity.IllusionEntity)return false;
        if(living.getType()==HexGodOfStories.ILLUSION.get())return false;
        // An armour stand is a living entity the way a coat rack is a person.
        if(living instanceof net.minecraft.world.entity.decoration.ArmorStand)return false;
        // Anything riding or ridden is left where it is: half a pair through a portal is worse
        // than neither, and untangling the other half is not this ability's job.
        return !living.isPassenger()&&!living.isVehicle();
    }

    /**
     * The forming break has spread as far as it is going to; the way is open, and now the realm on
     * the other side of it is asked what it has.
     *
     * <p>Asked here rather than when the key was pressed, because the realm has spent the last two
     * seconds waking up and because two seconds is long enough for a creature to have died or been
     * mounted. A break that finds nothing still opens, hangs there and closes — which is a far
     * better answer than a refusal, and is the whole of the "there was nothing there" response —
     * but it hands most of its cost back and recovers in five seconds rather than thirty, because
     * an empty realm is not something the caster could have known about in advance.
     */
    private static void openRecall(ServerPlayer p,Charge c){
        ServerLevel target=c.level.getServer().getLevel(c.destination.key);
        List<UUID> caught=target==null?new ArrayList<>():answering(target,c.destination);
        c.summons.addAll(caught);RECALLING.addAll(caught);
        c.held=RECALL_FORM;c.opened=c.level.getGameTime();c.nextArrival=c.opened+2;
        CHARGES.remove(p.getUUID());PORTALS.put(p.getUUID(),c);
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1.3f,caught.isEmpty()?.6f:.85f);
        if(caught.isEmpty()){
            HexData.get(p).putLong("cd_WARP_RECALL",HexData.now(p)+RECALL_EMPTY);
            HexData.energy(p,HexData.energy(p)+RECALL_COST*.5f);
            HexNetwork.sync(p);
            notice(p,"Nothing living answered from "+c.destination.title+".");
        }else notice(p,caught.size()+(caught.size()==1?" creature is":" creatures are")+" coming through.");
        send(c,false);
    }

    /**
     * One arrival.
     *
     * <p>Spaced rather than dumped: one creature every few ticks, each into its own place around
     * the break, so a herd does not arrive inside itself on a single tick and then get pushed
     * apart by the collision solver.
     *
     * <p>The creature is not recreated. {@code changeDimension} is the same mechanism a nether
     * portal uses — the body is carried across with its full tag, so health, equipment, name, age,
     * anger, owner, custom data and whatever a mod has written on it all arrive intact — and what
     * is left behind is nothing rather than a copy. A mob that came through is then marked as
     * persistent, because a creature deliberately dragged out of another dimension vanishing on
     * the next despawn sweep would make the whole ability pointless.
     */
    private static void emerge(ServerLevel level,Charge c,long now){
        if(c.summons==null||c.summons.isEmpty()||now<c.nextArrival)return;
        c.nextArrival=now+RECALL_GAP;
        ServerLevel source=level.getServer().getLevel(c.destination.key);
        UUID id=c.summons.poll();
        if(id==null)return;
        RECALLING.remove(id);
        if(source==null||source==level)return;
        Entity waiting=source.getEntity(id);
        // Asked again at the moment of the transfer rather than trusted from the keypress: a
        // creature can die, be removed or be mounted in the two seconds the break takes to spread.
        if(!recallable(waiting))return;
        int index=RECALL_MAX-c.summons.size();
        Vec3 spot=footing(level,c,(LivingEntity)waiting,index);
        double spread=index*2.399;
        Entity arrived=waiting.changeDimension(level,new ITeleporter(){
            public Entity placeEntity(Entity entity,ServerLevel from,ServerLevel to,float yaw,java.util.function.Function<Boolean,Entity> reposition){
                Entity moved=reposition.apply(false);
                if(moved!=null){moved.moveTo(spot.x,spot.y,spot.z,yaw,moved.getXRot());moved.setDeltaMovement(Vec3.ZERO);moved.fallDistance=0;}
                return moved;
            }
        });
        if(arrived==null)return;
        // Thrown up and slightly outward, so it reads as having climbed out of the break rather
        // than as having been placed beside it.
        arrived.setDeltaMovement(Math.cos(spread)*.09,RECALL_LAUNCH,Math.sin(spread)*.09);
        arrived.hurtMarked=true;arrived.fallDistance=0;
        if(arrived instanceof Mob mob)mob.setPersistenceRequired();
        HexNetwork.arrival(arrived);
        emergence(level,c.destination,arrived);
    }

    /**
     * Somewhere the creature actually fits.
     *
     * <p>Tried at the middle of the break first and then in a widening spiral around it, with the
     * ground under each candidate asked for by the same {@link WarpSurface} the fracture itself is
     * drawn on — so an arrival onto a slope, a step or a stair lands on the step rather than inside
     * it. A place is only accepted once the creature's whole box is clear of blocks and out of any
     * liquid, which is what stops a large body arriving with its head in the ceiling.
     */
    private static Vec3 footing(ServerLevel level,Charge c,LivingEntity living,int index){
        double reach=Math.max(1.4,c.extent()*.6);
        for(int attempt=0;attempt<16;attempt++){
            double a=index*2.399+attempt*.98;
            double radius=attempt==0?0:Math.min(reach,.7+attempt*.42);
            double x=c.at.x+Math.cos(a)*radius,z=c.at.z+Math.sin(a)*radius;
            double surface=WarpSurface.height(level,x,z,c.at.y,radius);
            double y=(Double.isNaN(surface)?c.at.y:surface)+.08;
            AABB box=living.getType().getAABB(x,y,z);
            if(level.noCollision(box)&&!level.containsAnyLiquid(box))return new Vec3(x,y,z);
        }
        // Nothing around the break fit. The break's own point is the fallback, and it is lifted
        // clear rather than trusted, so a crowded arrival surfaces instead of burying itself.
        return WarpRealms.daylight(level,living,new Vec3(c.at.x,c.at.y+.1,c.at.z));
    }

    /**
     * The realm arriving with its inhabitant.
     *
     * <p>Each destination signs its own arrivals in the material it is already made of, so nothing
     * new had to be drawn for this: Paradise brings sugar, the Void Sea brings the spectral wash
     * its hunter moves in, the Sun brings embers. The white rod on top of it is the dimensional
     * part, and is the same for every realm.
     */
    private static void emergence(ServerLevel level,Destination d,Entity arrived){
        net.minecraft.core.particles.ParticleOptions sign=switch(d){
            case PARADISE -> HexGodOfStories.CANDY.get();
            case VOID_SEA -> HexGodOfStories.SPECTRAL.get();
            case SUN -> HexGodOfStories.GOLD_EMBER.get();
            case TIME_STORM -> HexGodOfStories.TEMPORAL_DUST.get();
            case GRAVITY_WELL -> HexGodOfStories.VEIL.get();
            case CRUSHING_REALM -> HexGodOfStories.ASH.get();
            case FROZEN_MOMENT -> HexGodOfStories.MOTE.get();
            case SHATTERED_WORLD -> HexGodOfStories.SHARD.get();
            case END_OF_TIME -> HexGodOfStories.SMOKE.get();
        };
        double height=Math.max(.6,arrived.getBbHeight());
        level.sendParticles(sign,arrived.getX(),arrived.getY()+height*.5,arrived.getZ(),26,
            arrived.getBbWidth()*.6,height*.45,arrived.getBbWidth()*.6,.06);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,arrived.getX(),arrived.getY()+.1,arrived.getZ(),
            10,.28,.06,.28,.09);
        level.playSound(null,arrived.blockPosition(),HexGodOfStories.RIFT_OPEN.get(),SoundSource.HOSTILE,.55f,1.4f);
    }

    /** Lets go of whatever a break was still going to pull through, so nothing is left reserved. */
    private static void forget(Charge c){
        if(c.summons==null)return;
        RECALLING.removeAll(c.summons);
        c.summons.clear();
    }

    private static void notice(ServerPlayer p,String text){p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),true);}
}
