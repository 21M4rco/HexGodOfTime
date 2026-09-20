package com.loki.server;

import com.loki.entity.IllusionEntity;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import java.util.*;

/**
 * Target selection when copies of a keeper are on the field.
 *
 * <p>Vanilla targeting looks for {@code Player.class}, so without this a projection is scenery: every
 * creature walks past it to the one real body. Here the decision is re-opened. When a mob decides to
 * hunt a keeper who has projections out, the keeper and every nearby copy are collected into one pool
 * and one of them is drawn at random, weighted by the ordinary things a creature could actually
 * perceive — how near the body is, whether it can see it, and how much that body has recently hurt it.
 * Being the real player contributes nothing. A choice is then held for a while so a goal that re-runs
 * its search several times a second cannot quietly converge back onto the original.
 */
public final class Decoy {
    private Decoy() {}

    private record Choice(UUID target,long expires) {}
    private static final Map<UUID,Choice> LOCKED=new HashMap<>();
    private static final int MAX_LOCKS=256;
    /** How long a creature stays committed before it may reconsider which body it is chasing. */
    private static final int COMMIT=70,COMMIT_SPREAD=90;
    private static final double SEARCH=28;

    /**
     * @return the body {@code hunter} should actually pursue. Returns {@code wanted} unchanged whenever
     *         no deception applies, so this is safe to call from the target event for every mob alive.
     */
    public static LivingEntity resolve(LivingEntity hunter,LivingEntity wanted) {
        if(!(wanted instanceof ServerPlayer owner)||!(hunter instanceof Mob mob))return wanted;
        if(!(hunter.level() instanceof ServerLevel level)||hunter.level()!=owner.level())return wanted;
        if(!LokiServer.hasProjections(owner.getUUID()))return wanted;
        long now=level.getGameTime();

        Choice locked=LOCKED.get(mob.getUUID());
        if(locked!=null&&locked.expires>now) {
            if(locked.target.equals(owner.getUUID()))return owner;
            if(level.getEntity(locked.target) instanceof IllusionEntity held&&held.isAlive()
                &&held.level()==level&&held.distanceToSqr(mob)<SEARCH*SEARCH)return held;
        }

        List<LivingEntity> pool=new ArrayList<>();
        pool.add(owner);
        for(IllusionEntity decoy:LokiServer.projections(level,owner.getUUID())) {
            if(!decoy.isAlive()||!decoy.convincing())continue;
            if(decoy.distanceToSqr(mob)<SEARCH*SEARCH)pool.add(decoy);
        }
        if(pool.size()<2)return wanted;

        double[] weights=new double[pool.size()];
        double total=0;
        for(int i=0;i<pool.size();i++){weights[i]=weigh(mob,pool.get(i),now);total+=weights[i];}
        if(total<=1e-6)return wanted;

        double roll=mob.getRandom().nextDouble()*total;
        LivingEntity chosen=pool.get(pool.size()-1);
        for(int i=0;i<pool.size();i++) {
            roll-=weights[i];
            if(roll<=0){chosen=pool.get(i);break;}
        }
        if(LOCKED.size()>=MAX_LOCKS)LOCKED.entrySet().removeIf(e->e.getValue().expires<now);
        LOCKED.put(mob.getUUID(),new Choice(chosen.getUUID(),now+COMMIT+mob.getRandom().nextInt(COMMIT_SPREAD)));
        return chosen;
    }

    /**
     * Ordinary perception only. Nothing in here can tell a copy from the original, which is the whole
     * point: the weights would come out the same if the real keeper were swapped for one of its lies.
     */
    private static double weigh(Mob mob,LivingEntity candidate,long now) {
        if(!candidate.isAlive())return 0;
        double distance=Math.sqrt(mob.distanceToSqr(candidate));
        if(distance>SEARCH)return 0;
        double weight=1/(1+distance/6);
        if(!mob.hasLineOfSight(candidate))weight*=.3;
        // Something that has been hitting this creature is the obvious thing to hit back.
        weight*=1+Math.min(4,Threat.against(mob,candidate,now)*.35);
        // A body already being fought by the pack draws slightly less than a fresh one, which is what
        // spreads a group of enemies over the whole court instead of piling onto one figure.
        return Math.max(0,weight);
    }

    /** Re-opens the decision for everything already hunting a keeper, used the moment new copies appear. */
    public static void scatter(ServerPlayer owner) {
        if(!(owner.level() instanceof ServerLevel level)||!LokiServer.hasProjections(owner.getUUID()))return;
        for(Mob mob:level.getEntitiesOfClass(Mob.class,owner.getBoundingBox().inflate(24),m->m.getTarget()==owner)) {
            LOCKED.remove(mob.getUUID());
            LivingEntity chosen=resolve(mob,owner);
            if(chosen!=null&&chosen!=owner)mob.setTarget(chosen);
        }
    }

    /**
     * Records a decision a creature reached on its own — retaliating against the copy that cut it, say —
     * so the commitment window protects it from being overwritten by the next routine player search.
     */
    public static void observe(LivingEntity hunter,LivingEntity target) {
        if(!(target instanceof IllusionEntity decoy)||!decoy.isAlive())return;
        long now=hunter.level().getGameTime();
        if(LOCKED.size()>=MAX_LOCKS)LOCKED.entrySet().removeIf(e->e.getValue().expires<now);
        if(LOCKED.size()>=MAX_LOCKS)return;
        LOCKED.put(hunter.getUUID(),new Choice(target.getUUID(),now+COMMIT+hunter.getRandom().nextInt(COMMIT_SPREAD)));
    }

    /** Lets a creature reconsider immediately, used when the body it was chasing dissolves. */
    public static void release(Entity hunter) {LOCKED.remove(hunter.getUUID());}

    public static void tick(long now) {
        if(now%100!=0)return;
        LOCKED.entrySet().removeIf(e->e.getValue().expires<now);
    }
    public static void reset() {LOCKED.clear();}
}
