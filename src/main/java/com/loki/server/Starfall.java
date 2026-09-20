package com.loki.server;

import com.loki.entity.StarfallEntity;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The sanctum hunting whoever is troubling its owner.
 *
 * <p>Hostility is read, not assumed: a visitor standing about is left alone, and only something that
 * is actually hunting, angry at, or has recently struck the owner draws a star. That test is the
 * same one the projections use, so anything a mod adds is covered without a list.
 *
 * <p>Cost is bounded from three directions. Nothing runs at all unless an owner is standing in their
 * own island, the sweep for targets happens on a slow cadence over one bounded box rather than the
 * world, and each quarry carries its own staggered timer under a hard cap on stars in the air — so
 * a crowd of attackers reads as a barrage rather than a wall.
 */
public final class Starfall {
    private Starfall() {}

    private static final int SWEEP=20;
    private static final int COOLDOWN=70,STAGGER=60;
    private static final int MAX_IN_FLIGHT=6,MAX_TARGETS=12;
    private static final double RANGE=40,HEIGHT=34,OFFSET=22;
    private static final Map<UUID,Long> NEXT=new HashMap<>();

    public static void tick(ServerLevel level) {
        if(!PocketRealm.inside(level)||level.players().isEmpty())return;
        long now=level.getGameTime();
        if(now%SWEEP!=0)return;
        if(now%400==0)NEXT.entrySet().removeIf(e->e.getValue()<now-1200);
        for(ServerPlayer owner:level.players()) {
            if(!PocketRealm.ownsHere(owner)||owner.isSpectator())continue;
            int flying=level.getEntitiesOfClass(StarfallEntity.class,owner.getBoundingBox().inflate(RANGE+16)).size();
            if(flying>=MAX_IN_FLIGHT)continue;
            List<LivingEntity> hostile=hunt(level,owner);
            if(hostile.isEmpty())continue;
            for(LivingEntity quarry:hostile) {
                if(flying>=MAX_IN_FLIGHT)break;
                Long ready=NEXT.get(quarry.getUUID());
                if(ready!=null&&ready>now)continue;
                NEXT.put(quarry.getUUID(),now+COOLDOWN+owner.getRandom().nextInt(STAGGER));
                StarfallEntity.fall(owner,quarry,origin(owner,quarry));
                flying++;
            }
        }
    }

    /**
     * One bounded query, filtered by the shared hostility test and ordered so whatever is actively
     * pressing the owner is answered first.
     */
    private static List<LivingEntity> hunt(ServerLevel level,ServerPlayer owner) {
        AABB box=owner.getBoundingBox().inflate(RANGE);
        List<LivingEntity> found=new ArrayList<>();
        for(LivingEntity candidate:level.getEntitiesOfClass(LivingEntity.class,box,e->e!=owner&&e.isAlive())) {
            if(!Hostility.hostile(candidate,owner))continue;
            found.add(candidate);
            if(found.size()>=MAX_TARGETS)break;
        }
        long now=level.getGameTime();
        found.sort(Comparator.comparingDouble(e->-Threat.against(owner,e,now)));
        return found;
    }

    /** High above and off to one side, so the star arrives on a diagonal rather than dropping. */
    private static Vec3 origin(ServerPlayer owner,LivingEntity quarry) {
        double angle=owner.getRandom().nextDouble()*Math.PI*2;
        Vec3 aside=new Vec3(Math.cos(angle)*OFFSET,0,Math.sin(angle)*OFFSET);
        return quarry.getBoundingBox().getCenter().add(aside).add(0,HEIGHT,0);
    }

    public static void forget(LivingEntity e) {NEXT.remove(e.getUUID());}
    public static void reset() {NEXT.clear();}
}
