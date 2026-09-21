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
        long opened=-1;int held;final Set<UUID> moved=new HashSet<>();final List<BlockPos> replaced=new ArrayList<>();
        Charge(ServerPlayer p,Vec3 at,Destination d,double cell){level=p.serverLevel();this.at=at;destination=d;start=level.getGameTime();this.cell=cell;ownerId=p.getId();}
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
        double cell=HexData.get(p).getDouble("warpPrepared_"+d.name());
        if(cell==0){cell=WarpRealms.allocate(target);HexData.get(p).putDouble("warpPrepared_"+d.name(),cell);}
        WarpRealms.prepare(target,d,cell);
        Charge c=new Charge(p,new Vec3(hit.getBlockPos().getX()+.5,hit.getLocation().y+.025,hit.getBlockPos().getZ()+.5),d,cell);
        CHARGES.put(p.getUUID(),c);HexNetwork.animate(p,"threads");send(c,false);
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1,.65f);
    }
    public static void release(ServerPlayer p){
        Charge c=CHARGES.get(p.getUUID());if(c==null||c.opened>=0)return;
        int held=(int)(c.level.getGameTime()-c.start);
        if(held<WarpMath.MIN_CHARGE||!valid(p,c)||!WarpRealms.ready(p.server.getLevel(c.destination.key),c.cell)){cancel(p);notice(p,"The fracture did not stabilize.");return;}
        c.held=Math.min(held,WarpMath.FULL_CHARGE);c.opened=c.level.getGameTime();
        CHARGES.remove(p.getUUID());PORTALS.put(p.getUUID(),c);
        HexData.spend(p,Ability.WARPING.cost);HexData.get(p).putLong("cd_WARPING",HexData.now(p)+Ability.WARPING.cooldown);
        HexServer.reward(p,Ability.WARPING.discipline,90);HexNetwork.sync(p);
        // Existing persistence/restoration code owns every replaced block, including block entities.
        double r=WarpMath.width(c.held)*.5;
        for(int x=(int)Math.ceil(c.at.x-r);x<c.at.x+r;x++)for(int z=(int)Math.ceil(c.at.z-r);z<c.at.z+r;z++){
            BlockPos b=BlockPos.containing(x,c.at.y-.1,z);
            // Only replace whole cells inside the mirror, keeping the jagged perimeter on intact terrain.
            boolean interior=true;
            for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++)
                interior&=WarpMath.inside(b.getX()+dx-c.at.x,b.getZ()+dz-c.at.z,c.held);
            if(!interior)continue;
            if(c.level.getBlockState(b).getDestroySpeed(c.level,b)>=0&&Nothingness.take(c.level,b,c.opened+WarpMath.OPEN_TICKS))c.replaced.add(b);
        }
        WarpRealms.start(p.server.getLevel(c.destination.key),c.cell);
        HexData.get(p).putDouble("warpCell_"+c.destination.name(),c.cell);
        c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_OPEN.get(),SoundSource.PLAYERS,1.25f,1.15f);
        send(c,false);
    }
    private static boolean valid(ServerPlayer p,Charge c){
        if(!p.isAlive()||!HexData.access(p)||!HexData.unlocked(p,Ability.WARPING)||p.isSpectator()||p.level()!=c.level||HexData.selected(p)!=Ability.WARPING||TemporalEngine.frozen(p)||Erasure.erasing(p))return false;
        if(HexData.energy(p)<Ability.WARPING.cost)return false;
        BlockHitResult h=aim(p);return h!=null&&h.getLocation().distanceToSqr(c.at)<2.25;
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
                Nothingness.restoreDue(level,c.replaced);PORTALS.remove(entry.getKey());send(c,true);
                level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);continue;
            }
            double r=WarpMath.width(c.held)*.5;
            AABB area=new AABB(c.at.x-r,c.at.y-.4,c.at.z-r,c.at.x+r,c.at.y+1.3,c.at.z+r);
            for(Entity e:level.getEntities((Entity)null,area,e->e.isAlive()&&!e.isSpectator()&&
                (e.getUUID().equals(entry.getKey())||!sovereign(e)&&!(e instanceof net.minecraft.world.entity.player.Player q&&q.isCreative())))){
                if(e.getY()<c.at.y-.4||e.getY()>c.at.y+.3||!WarpMath.inside(e.getX()-c.at.x,e.getZ()-c.at.z,c.held)||!c.moved.add(e.getUUID()))continue;
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
            double cell=HexData.get(p).getDouble("warpCell_"+selected.name());
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
        CompoundTag n=new CompoundTag();n.putBoolean("clear",clear);n.putDouble("x",c.at.x);n.putDouble("y",c.at.y);n.putDouble("z",c.at.z);n.putLong("start",c.start);n.putInt("destination",c.destination.ordinal());n.putLong("opened",c.opened);n.putInt("held",c.held);n.putLong("until",c.opened<0?c.level.getGameTime()+12:Math.min(c.opened+WarpMath.OPEN_TICKS,c.level.getGameTime()+12));
        n.putString("dimension",c.level.dimension().location().toString());n.putLong("sent",c.level.getGameTime());
        ServerLevel target=c.level.getServer().getLevel(c.destination.key);
        n.putLong("realmAge",c.opened>=0&&target!=null?WarpRealms.age(target,c.cell):0);
        HexNetwork.near(c.level,c.at,96,new HexNetwork.Message(HexNetwork.WARP,c.ownerId,n));
    }
    public static void cancel(ServerPlayer p){Charge c=CHARGES.remove(p.getUUID());if(c!=null){send(c,true);c.level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.RIFT_CLOSE.get(),SoundSource.PLAYERS,.8f,.7f);}}
    public static void reset(){CHARGES.clear();PORTALS.clear();WarpRealms.reset();}
    private static void notice(ServerPlayer p,String text){p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),true);}
}
