package com.loki.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/**
 * Who hurt whom, recently. Two questions are asked of it: whether something has made itself a valid
 * enemy of a keeper (which is what lets a projection answer an attack from an otherwise peaceful
 * creature or another player), and how much a particular body has earned a mob's attention (which is
 * what lets a projection's own blows pull aggression off its caster).
 *
 * <p>Everything is event driven — nothing here scans the world — and both tables are bounded and
 * pruned on a slow cadence, so a long battle cannot grow them without limit.
 */
public final class Threat {
    private Threat() {}

    private record Mark(long expires,float weight) {}
    private static final int MAX_SUBJECTS=256,MAX_SOURCES=16;
    /** How long an attack keeps something on a keeper's enemy list. */
    public static final int GRUDGE=400;
    /** How long damage counts toward a mob's choice of target. */
    private static final int MEMORY=200;

    private static final Map<UUID,Map<UUID,Mark>> HARMED=new HashMap<>();

    /** Records that {@code attacker} harmed {@code victim}; both directions are useful to different callers. */
    public static void record(LivingEntity victim,Entity attacker,float amount) {
        if(attacker==null||victim==null||attacker==victim)return;
        // Both questions this table answers involve a keeper or one of their copies on one side of
        // the blow; recording the rest of the world's quarrels would only crowd the bound.
        if(!(victim instanceof net.minecraft.world.entity.player.Player
            ||attacker instanceof net.minecraft.world.entity.player.Player
            ||attacker instanceof com.loki.entity.IllusionEntity))return;
        long now=victim.level().getGameTime();
        Map<UUID,Mark> sources=HARMED.get(victim.getUUID());
        if(sources==null) {
            if(HARMED.size()>=MAX_SUBJECTS)prune(now);
            if(HARMED.size()>=MAX_SUBJECTS)return;
            HARMED.put(victim.getUUID(),sources=new HashMap<>());
        }
        Mark prior=sources.get(attacker.getUUID());
        float weight=Math.min(40,(prior==null||prior.expires<now?0:prior.weight)+Math.max(.5f,amount));
        if(prior==null&&sources.size()>=MAX_SOURCES)sources.entrySet().removeIf(e->e.getValue().expires<now);
        if(prior==null&&sources.size()>=MAX_SOURCES)return;
        sources.put(attacker.getUUID(),new Mark(now+Math.max(GRUDGE,MEMORY),weight));
    }

    /** True while {@code attacker} is still on {@code victim}'s list of things that struck first. */
    public static boolean harmedBy(UUID victim,Entity attacker,long now) {
        Map<UUID,Mark> sources=HARMED.get(victim);
        if(sources==null||attacker==null)return false;
        Mark mark=sources.get(attacker.getUUID());
        return mark!=null&&mark.expires>now;
    }

    /** Accumulated damage {@code candidate} has dealt to {@code victim} inside the memory window. */
    public static float against(LivingEntity victim,Entity candidate,long now) {
        Map<UUID,Mark> sources=HARMED.get(victim.getUUID());
        if(sources==null||candidate==null)return 0;
        Mark mark=sources.get(candidate.getUUID());
        return mark==null||mark.expires<now?0:mark.weight;
    }

    public static void forget(Entity entity) {HARMED.remove(entity.getUUID());}

    public static void tick(long now) {
        if(now%200!=0)return;
        prune(now);
    }
    private static void prune(long now) {
        HARMED.values().forEach(sources->sources.entrySet().removeIf(e->e.getValue().expires<now));
        HARMED.entrySet().removeIf(e->e.getValue().isEmpty());
    }
    public static void reset() {HARMED.clear();}
}
