package com.hexgodofstories.server;

import com.hexgodofstories.entity.StarfallEntity;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded combat meteors over the generated island; the client-side ambient sky is unchanged. */
public final class Starfall {
    private Starfall() {}

    private static final int SWEEP=20;
    private static final int COOLDOWN=200;
    private static final int MAX_IN_FLIGHT=3,MAX_TARGETS=12;
    /**
     * How far above the world it starts and how far off to one side, so the entry is a long burning
     * diagonal rather than a drop. High enough to be well past the build limit and out of sight when it
     * lights up.
     */
    private static final double HEIGHT=190,OFFSET=78;
    private static final Map<UUID,Long> NEXT=new HashMap<>();

    public static void tick(ServerLevel level) {
        if(!PocketRealm.inside(level)||level.players().isEmpty())return;
        long now=level.getGameTime();
        if(now%SWEEP!=0)return;
        if(now%400==0)NEXT.entrySet().removeIf(e->e.getValue()<now-1200);
        for(ServerPlayer owner:level.players()) {
            if(!com.hexgodofstories.data.HexData.access(owner)||!PocketRealm.ownsHere(owner)||owner.isSpectator())continue;
            int plot=PocketRealm.plotAt(owner.getX(),owner.getZ());
            AABB bounds=bounds(level,plot);
            int flying=level.getEntitiesOfClass(StarfallEntity.class,bounds).size();
            if(flying>=MAX_IN_FLIGHT)continue;
            List<LivingEntity> hostile=hunt(level,owner);
            if(hostile.isEmpty()){NEXT.remove(owner.getUUID());continue;}
            long ready=NEXT.computeIfAbsent(owner.getUUID(),id->now+COOLDOWN);
            if(ready>now)continue;
            LivingEntity quarry=hostile.get(0);
            StarfallEntity.fall(owner,quarry,origin(owner,quarry));
            NEXT.put(owner.getUUID(),now+COOLDOWN);
        }
    }

    /**
     * One bounded query, filtered by the shared hostility test and ordered so whatever is actively
     * pressing the owner is answered first.
     */
    private static List<LivingEntity> hunt(ServerLevel level,ServerPlayer owner) {
        int plot=PocketRealm.plotAt(owner.getX(),owner.getZ());
        AABB box=bounds(level,plot);
        List<LivingEntity> found=new ArrayList<>();
        for(LivingEntity candidate:level.getEntitiesOfClass(LivingEntity.class,box,e->e!=owner&&e.isAlive())) {
            if(!Hostility.hostile(candidate,owner)||!validPoint(level,plot,candidate.getX(),candidate.getZ()))continue;
            found.add(candidate);
            if(found.size()>=MAX_TARGETS)break;
        }
        long now=level.getGameTime();
        found.sort(Comparator.comparingDouble(e->-Threat.against(owner,e,now)));
        return found;
    }

    /** Include the full descent, not only the small box around a player at ground level. */
    private static AABB bounds(ServerLevel level,int plot) {
        var origin=PocketRealm.origin(plot);
        return new AABB(origin.getX()+RealmShape.MIN,level.getMinBuildHeight(),origin.getZ()+RealmShape.MIN,
            origin.getX()+RealmShape.MAX+1,level.getMaxBuildHeight()+HEIGHT+128,origin.getZ()+RealmShape.MAX+1);
    }

    /** Real shoreline and a loaded, solid floor; unbuilt columns and holes are not playable ground. */
    public static boolean validPoint(ServerLevel level,int plot,double x,double z) {
        if(PocketRealm.plotAt(x,z)!=plot)return false;
        var origin=PocketRealm.origin(plot);
        int localX=net.minecraft.util.Mth.floor(x)-origin.getX();
        int localZ=net.minecraft.util.Mth.floor(z)-origin.getZ();
        if(!RealmShape.contains(localX,localZ))return false;
        var floor=new net.minecraft.core.BlockPos(net.minecraft.util.Mth.floor(x),
            PocketRealm.FLOOR_Y+RealmShape.surface(localX,localZ),net.minecraft.util.Mth.floor(z));
        return level.hasChunkAt(floor)&&!level.getBlockState(floor).getCollisionShape(level,floor).isEmpty();
    }

    /** Check the path as well as both ends: the irregular coast can cut through a diagonal. */
    public static boolean validPath(ServerLevel level,int plot,Vec3 from,Vec3 to) {
        int steps=Math.max(1,(int)Math.ceil(from.subtract(to).horizontalDistance()));
        for(int i=0;i<=steps;i++) {
            Vec3 at=from.lerp(to,i/(double)steps);
            if(!validPoint(level,plot,at.x,at.z))return false;
        }
        return true;
    }

    private static Vec3 origin(ServerPlayer owner,LivingEntity quarry) {
        int plot=PocketRealm.plotAt(owner.getX(),owner.getZ());
        Vec3 target=quarry.getBoundingBox().getCenter();
        for(int i=0;i<24;i++) {
            double angle=owner.getRandom().nextDouble()*Math.PI*2;
            double offset=OFFSET*(.3+owner.getRandom().nextDouble()*.7);
            Vec3 from=target.add(Math.cos(angle)*offset,HEIGHT,Math.sin(angle)*offset);
            if(validPath(owner.serverLevel(),plot,from,target))return from;
        }
        return target.add(0,HEIGHT,0);
    }

    public static void forget(LivingEntity e) {NEXT.remove(e.getUUID());}
    public static void reset() {NEXT.clear();}
}
