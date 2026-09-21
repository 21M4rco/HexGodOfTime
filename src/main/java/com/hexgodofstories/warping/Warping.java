package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.sounds.SoundSource;
import java.util.*;

/** Server-owned floor selection; release never trusts a client position, duration or destination. */
public final class Warping {
    private static final Map<UUID,Charge> CHARGES=new HashMap<>();
    // Released portals own their lifetime, even when their caster crosses, logs out or changes spells.
    private static final Map<UUID,Charge> PORTALS=new HashMap<>();
    private static final class Charge {
        final ServerLevel level;final Vec3 at;final Destination destination;final long start;final double cell;final int ownerId;
        /** This break's own shape. Sent to the client so both sides crack reality identically. */
        final long seed;
        long opened=-1;int held;final Set<UUID> moved=new HashSet<>();
        /** The opened break, built once: held stops changing the moment the portal is released. */
        private java.util.List<WarpFracture.Piece> shape;private double extent;
        Charge(ServerPlayer p,Vec3 at,Destination d,double cell){level=p.serverLevel();this.at=at;destination=d;start=level.getGameTime();this.cell=cell;ownerId=p.getId();
            seed=start*1000003L^(long)Math.floor(at.x*17)*31L^(long)Math.floor(at.z*7919)^(long)p.getUUID().getLeastSignificantBits();}
        java.util.List<WarpFracture.Piece> shape(){
            if(shape==null){shape=WarpFracture.build(seed,WarpMath.reach(held),1);extent=WarpFracture.extent(shape);}
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
        Charge c=CHARGES.get(p.getUUID());if(c==null||c.opened>=0)return;
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
        return HexData.energy(p)>=Ability.WARPING.cost*WarpMath.costScale(WarpMath.MIN_CHARGE);
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
            if(p==null||p.level()!=level||!p.isAlive()||!HexData.access(p)){CHARGES.remove(entry.getKey());send(c,true);continue;}
            long now=level.getGameTime();
            if(!valid(p,c)){cancel(p);continue;}
            if(now-c.start>=WarpMath.MAX_HOLD){release(p);continue;}
            if(now%4==0)send(c,false);
        }
        for(var entry:new ArrayList<>(PORTALS.entrySet())){
            Charge c=entry.getValue();if(c.level!=level)continue;
            long now=level.getGameTime();
            if(!WarpMath.openAt(c.opened,now)){
                PORTALS.remove(entry.getKey());send(c,true);
                level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);continue;
            }
            // The way through is the break itself: the impact hole, the wedges opened around it and
            // the roots of the fractures wide enough to fall into. Hairline cracks are scenery and
            // carry nobody, which is what keeps this from being an invisible circle of triggers.
            double r=c.extent()+1;double lift=Math.min(9,3+c.extent()*.28);
            AABB area=new AABB(c.at.x-r,c.at.y-lift,c.at.z-r,c.at.x+r,c.at.y+lift,c.at.z+r);
            for(Entity e:level.getEntities((Entity)null,area,e->e.isAlive()&&!e.isSpectator()&&
                (e.getUUID().equals(entry.getKey())||!sovereign(e)&&!(e instanceof net.minecraft.world.entity.player.Player q&&q.isCreative())))){
                double dx=e.getX()-c.at.x,dz=e.getZ()-c.at.z;
                if(!WarpFracture.inside(c.shape(),dx,dz)||c.moved.contains(e.getUUID()))continue;
                // Standing in it means standing on the floor it broke, whatever height that floor
                // is at out there: a fracture that climbed a step takes whoever walks onto the step.
                double surface=WarpSurface.height(level,e.getX(),e.getZ(),c.at.y,Math.sqrt(dx*dx+dz*dz));
                if(Double.isNaN(surface)||e.getY()<surface-.45||e.getY()>surface+.35||!c.moved.add(e.getUUID()))continue;
                WarpRealms.transfer(e,c.destination,c.cell,e.getUUID().equals(entry.getKey())&&sovereign(e));
            }
            if(now%4==0)send(c,false);
        }
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
        cancel(p);HexNetwork.fx(p,"depart");p.teleportTo(to,a.at().x,a.at().y,a.at().z,a.yaw(),a.pitch());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        HexNetwork.arrival(p);return true;
    }
    private static void send(Charge c,boolean clear){
        CompoundTag n=new CompoundTag();n.putBoolean("clear",clear);n.putDouble("x",c.at.x);n.putDouble("y",c.at.y);n.putDouble("z",c.at.z);n.putLong("start",c.start);n.putInt("destination",c.destination.ordinal());n.putLong("opened",c.opened);n.putInt("held",c.held);n.putLong("seed",c.seed);n.putLong("until",c.opened<0?c.level.getGameTime()+12:Math.min(c.opened+WarpMath.OPEN_TICKS,c.level.getGameTime()+12));
        n.putString("dimension",c.level.dimension().location().toString());n.putLong("sent",c.level.getGameTime());
        ServerLevel target=c.level.getServer().getLevel(c.destination.key);
        n.putLong("realmAge",c.opened>=0&&target!=null?WarpRealms.age(target,c.cell):0);
        HexNetwork.near(c.level,c.at,96,new HexNetwork.Message(HexNetwork.WARP,c.ownerId,n));
    }
    public static void cancel(ServerPlayer p){Charge c=CHARGES.remove(p.getUUID());if(c!=null){send(c,true);c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);}}
    public static void reset(){CHARGES.clear();PORTALS.clear();WarpRealms.reset();}
    private static void notice(ServerPlayer p,String text){p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),true);}
}
