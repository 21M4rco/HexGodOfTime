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
 * <p>It is a meteor, so it is an event rather than a mechanic. A quarry whose timer is up is not answered
 * with a certainty — it is answered with a chance, and most of the time nothing happens at all. That is
 * what keeps the sky worth looking at: when a star does come down it is because the island decided to
 * pull one, not because something walked into a trigger.
 *
 * <p>Cost is bounded from four directions. Nothing runs at all unless an owner is standing in their own
 * island, the sweep for targets happens on a slow cadence over one bounded box rather than the world, each
 * quarry carries its own staggered timer under a hard cap on stars in the air, and the roll above throws
 * most opportunities away — so a crowd of attackers reads as an occasional barrage rather than a wall.
 */
public final class Starfall {
    private Starfall() {}

    private static final int SWEEP=20;
    private static final int COOLDOWN=180,STAGGER=140;
    private static final int MAX_IN_FLIGHT=3,MAX_TARGETS=12;
    /**
     * How far above the world it starts and how far off to one side, so the entry is a long burning
     * diagonal rather than a drop. High enough to be well past the build limit and out of sight when it
     * lights up.
     */
    private static final double RANGE=40,HEIGHT=190,OFFSET=78;
    /** The roll. Most eligible moments pass without a star, which is the whole point of it. */
    private static final float CHANCE=.22f;
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
                // The timer only earns a roll. Losing it costs a shorter wait before the next one, so
                // pressure still builds on whoever keeps at the owner without ever becoming a guarantee.
                if(owner.getRandom().nextFloat()>CHANCE) {
                    NEXT.put(quarry.getUUID(),now+COOLDOWN/2+owner.getRandom().nextInt(STAGGER));
                    continue;
                }
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

    /** Far above and well off to one side, so the star arrives on a long diagonal rather than dropping. */
    private static Vec3 origin(ServerPlayer owner,LivingEntity quarry) {
        double angle=owner.getRandom().nextDouble()*Math.PI*2;
        Vec3 aside=new Vec3(Math.cos(angle)*OFFSET,0,Math.sin(angle)*OFFSET);
        return quarry.getBoundingBox().getCenter().add(aside).add(0,HEIGHT,0);
    }

    public static void forget(LivingEntity e) {NEXT.remove(e.getUUID());}
    public static void reset() {NEXT.clear();}
}
