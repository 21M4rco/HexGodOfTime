package com.hexgodofstories.warping;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Destination-side Warping motion: the body begins mostly under an opaque black puddle and slowly
 * rises to the destination's existing arrival coordinate. No destination generation or routing lives here.
 */
public final class WarpEmergence {
    private WarpEmergence(){}

    /** 2.6 seconds: deliberately slow enough to watch the body push through the goo. */
    public static final int DURATION=52;
    private static final int VISUAL_HELD=30;
    private record Rise(Entity entity,ResourceKey<Level> level,UUID id,Vec3 top,double startY,long start,Vec3 after,
                        boolean oldNoPhysics,boolean oldNoGravity,Boolean oldNoAi,float yaw,float pitch){}
    private static final Map<UUID,Rise> ACTIVE=new HashMap<>();

    public static void begin(Entity e,Vec3 top,Vec3 after,boolean makePuddle){
        if(!(e.level() instanceof ServerLevel level)||e.isRemoved())return;
        cancel(e);
        // Keep a player's eye near/above the skin while most of the body starts below it; this avoids
        // first-person rendering the inside of the ground while still making the emergence obvious.
        double depth=Math.min(1.35,Math.max(.72,e.getBbHeight()*.70));
        double startY=top.y-depth;
        Boolean noAi=e instanceof Mob mob?mob.isNoAi():null;
        Rise s=new Rise(e,level.dimension(),e.getUUID(),top,startY,level.getGameTime(),
            after==null?Vec3.ZERO:after,e.noPhysics,e.isNoGravity(),noAi,e.getYRot(),e.getXRot());
        ACTIVE.put(e.getUUID(),s);

        e.noPhysics=true;e.setNoGravity(true);
        if(e instanceof Mob mob)mob.setNoAi(true);
        place(e,top.x,startY,top.z,s.yaw(),s.pitch());
        e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;e.hurtMarked=true;

        if(makePuddle)puddle(level,e,top);
        if(e instanceof ServerPlayer p){
            CompoundTag n=new CompoundTag();
            n.putDouble("x",top.x);n.putDouble("z",top.z);n.putDouble("topY",top.y);n.putDouble("startY",startY);
            n.putLong("start",s.start());n.putInt("duration",DURATION);
            HexNetwork.to(p,new HexNetwork.Message(HexNetwork.WARP_EMERGE,p.getId(),n));
        }
    }

    /** Synthetic visual-only Warping pool. It has no crossing authority and expires by itself. */
    private static void puddle(ServerLevel level,Entity e,Vec3 at){
        long now=level.getGameTime();
        CompoundTag n=new CompoundTag();
        n.putBoolean("clear",false);n.putBoolean("arrival",true);
        n.putDouble("x",at.x);n.putDouble("y",at.y);n.putDouble("z",at.z);
        n.putLong("start",now-VISUAL_HELD);n.putLong("opened",now);n.putInt("held",VISUAL_HELD);
        n.putInt("destination",Destination.SANCTUM.ordinal());
        n.putLong("seed",e.getUUID().getLeastSignificantBits()^now*1000003L);
        n.putLong("until",now+DURATION+16);n.putInt("window",DURATION+16);
        n.putBoolean("recall",false);n.putString("dimension",level.dimension().location().toString());
        n.putLong("sent",now);n.putLong("realmAge",0);
        int visual=Integer.MIN_VALUE+(e.getId()&0x3fffffff);
        HexNetwork.near(level,at,96,new HexNetwork.Message(HexNetwork.WARP,visual,n));
    }

    public static void tick(ServerLevel level){
        long now=level.getGameTime();
        for(var entry:new ArrayList<>(ACTIVE.entrySet())){
            Rise s=entry.getValue();
            if(!s.level().equals(level.dimension()))continue;
            Entity e=level.getEntity(s.id());
            if(e==null||!e.isAlive()||e.isRemoved()){cancel(s.entity());continue;}

            double t=Math.max(0,Math.min(1,(now-s.start())/(double)DURATION));
            double eased=t*t*(3-2*t);
            double y=s.startY()+(s.top().y-s.startY())*eased;
            place(e,s.top().x,y,s.top().z,s.yaw(),s.pitch());
            e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;e.hurtMarked=true;
            if(t<1)continue;

            ACTIVE.remove(entry.getKey());
            place(e,s.top().x,s.top().y,s.top().z,s.yaw(),s.pitch());
            e.noPhysics=s.oldNoPhysics();e.setNoGravity(s.oldNoGravity());
            if(e instanceof Mob mob&&s.oldNoAi()!=null)mob.setNoAi(s.oldNoAi());
            e.setDeltaMovement(s.after());e.fallDistance=0;e.hurtMarked=true;
            if(e instanceof ServerPlayer p){
                CompoundTag n=new CompoundTag();n.putBoolean("clear",true);
                HexNetwork.to(p,new HexNetwork.Message(HexNetwork.WARP_EMERGE,p.getId(),n));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            }
        }
    }

    private static void place(Entity e,double x,double y,double z,float yaw,float pitch){
        if(e instanceof ServerPlayer p)p.setPos(x,y,z);
        else e.moveTo(x,y,z,yaw,pitch);
        e.setYRot(yaw);e.setXRot(pitch);
    }

    public static boolean active(Entity e){return e!=null&&ACTIVE.containsKey(e.getUUID());}
    /** Restore leased physics/AI before logout, removal, death or a different dimension takes over. */
    public static void cancel(Entity e){
        Rise s=ACTIVE.remove(e.getUUID());if(s==null)return;
        if(e.isAlive()&&!e.isRemoved()&&e.level().dimension().equals(s.level()))
            place(e,s.top().x,s.top().y,s.top().z,e.getYRot(),e.getXRot());
        e.noPhysics=e.isSpectator()||s.oldNoPhysics();e.setNoGravity(s.oldNoGravity());
        if(e instanceof Mob mob&&s.oldNoAi()!=null)mob.setNoAi(s.oldNoAi());
        e.fallDistance=0;
        if(e instanceof ServerPlayer p){
            CompoundTag n=new CompoundTag();n.putBoolean("clear",true);
            HexNetwork.to(p,new HexNetwork.Message(HexNetwork.WARP_EMERGE,p.getId(),n));
        }
    }
    public static void reset(){
        for(Rise rise:new ArrayList<>(ACTIVE.values()))cancel(rise.entity());
        ACTIVE.clear();
    }
}
