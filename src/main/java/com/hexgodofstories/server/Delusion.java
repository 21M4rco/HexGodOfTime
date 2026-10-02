package com.hexgodofstories.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Creatures turned on something by a lie: Mass Delusion's mobs on each other, the Impostor's on the one whose face was
 * stolen. A turned creature keeps that target until the lie runs out, whatever its own instincts would otherwise have
 * it pick (see {@code ServerEvents#target}), and is pointed back at it if anything knocks it off. One map entry per
 * creature, read on each of its target changes and walked twice a second.
 */
public final class Delusion {
    private Delusion() { }

    private record Lie(int target, long until) { }
    private static final Map<UUID, Lie> LIES = new HashMap<>();
    /** The most creatures deluded at once, across the server. */
    private static final int MOST = 512;

    /** {@code mob} hunts {@code target} for {@code ticks}, whatever else it wanted. */
    public static void force(Mob mob, LivingEntity target, int ticks) {
        if (mob == target || !mob.isAlive() || !target.isAlive()) return;
        if (LIES.size() >= MOST && !LIES.containsKey(mob.getUUID())) return;
        LIES.put(mob.getUUID(), new Lie(target.getId(), mob.level().getGameTime() + ticks));
        mob.setTarget(target);
    }

    /** The target a deluded creature must keep, or null when it is not deluded (or the lie is over). */
    public static LivingEntity forced(Mob mob) {
        if (LIES.isEmpty()) return null;
        Lie lie = LIES.get(mob.getUUID());
        if (lie == null) return null;
        if (mob.level().getGameTime() > lie.until() || !(mob.level().getEntity(lie.target()) instanceof LivingEntity target) || !target.isAlive()) {
            LIES.remove(mob.getUUID());
            return null;
        }
        return target;
    }

    /** Twice a second: a deluded creature that has lost its target is pointed back at it; a finished lie is let go. */
    public static void tick(ServerLevel level) {
        if (LIES.isEmpty() || level.getGameTime() % 10 != 0) return;
        // Pointed back after the walk, not during it: turning a creature runs the target event, which reads this map.
        java.util.List<Mob> astray = new java.util.ArrayList<>();
        java.util.List<LivingEntity> wanted = new java.util.ArrayList<>();
        for (Iterator<Map.Entry<UUID, Lie>> it = LIES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Lie> entry = it.next();
            // Every level shares one clock, so a lie whose creature is in another world, or unloaded, still runs out.
            if (level.getGameTime() > entry.getValue().until()) {it.remove(); continue;}
            Entity e = level.getEntity(entry.getKey());
            if (e == null) continue;
            if (!(e instanceof Mob mob) || !mob.isAlive()) {it.remove(); continue;}
            if (!(level.getEntity(entry.getValue().target()) instanceof LivingEntity target) || !target.isAlive()) {it.remove(); continue;}
            if (mob.getTarget() != target) {astray.add(mob); wanted.add(target);}
        }
        for (int i = 0; i < astray.size(); i++) astray.get(i).setTarget(wanted.get(i));
    }

    public static void forget(Entity e) {LIES.remove(e.getUUID());}
    public static void reset() {LIES.clear();}
}
