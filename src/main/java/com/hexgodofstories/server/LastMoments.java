package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a Scepter beam short of a full charge does to a body it would have killed.
 *
 * <p>Only a full stone unmakes what it kills. Anything less leaves the body standing on its last
 * breath: held still, the hole open in it, bleeding hard, for three to four and a half seconds. Then it
 * dies the ordinary way, credited to the caster, with its ordinary drops. Players and bosses are never
 * held; they die at once, the ordinary way too.
 *
 * <p>The lethal blow is capped as it lands rather than undone after it: undoing a death would already
 * have fired the death event, played the death cry and dropped the loot. Bleeding cannot finish a held
 * body early, so how long it stands is this class's timer and nothing else. A body saved or unloaded
 * while it stands carries a tag, and dies a moment after it is loaded again.
 */
public final class LastMoments {
    private LastMoments() { }

    /** Health a held body is left on. */
    private static final float BREATH = .5f;
    /** Ticks it stands: three seconds, plus up to one and a half more so a crowd does not drop as one. */
    public static final int STAND = 60, SPREAD = 30;
    /** Ticks a body that was saved mid-stand keeps standing once it has loaded again. */
    private static final int RESUMED = 20;
    private static final int MAX_HELD = 128;
    static final String TAG = "hexgodofstoriesLastMoments";

    private record Held(LivingEntity victim, UUID caster, long until) { }
    private static final Map<UUID, Held> HELD = new HashMap<>();
    /** Bodies loaded with the tag, picked up on the next level tick rather than mid-load. */
    private static final List<LivingEntity> RESUMING = new ArrayList<>();
    /** The body a partial beam is hurting right now, and whether its lethal blow was capped. */
    private static LivingEntity sparing;
    private static boolean spared;

    public static boolean held(Entity e) {
        Held h = e == null ? null : HELD.get(e.getUUID());
        return h != null && h.victim == e;
    }

    private static boolean holdable(LivingEntity victim) {
        return victim instanceof Mob && !victim.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES)
            && !held(victim) && HELD.size() < MAX_HELD;
    }

    /** Hurts the body, but a blow that would kill a holdable one leaves it on its last breath. True when it did. */
    static boolean hurt(LivingEntity victim, DamageSource source, float amount) {
        if (!holdable(victim)) {victim.hurt(source, amount); return false;}
        sparing = victim;
        spared = false;
        try {victim.hurt(source, amount);}
        finally {sparing = null;}
        return spared && victim.isAlive();
    }

    /** Lowest-priority LivingDamageEvent: the amount here is final, after armour, resistance and absorption. */
    public static void damage(LivingDamageEvent e) {
        if (e.getEntity() != sparing || e.getAmount() < e.getEntity().getHealth()) return;
        e.setAmount(Math.max(0, e.getEntity().getHealth() - BREATH));
        spared = true;
    }

    /**
     * The same cap, for a blow that something else made lethal after it had already been capped. The death
     * cry has played by now, but the body still stands.
     */
    public static void death(LivingDeathEvent e) {
        if (e.getEntity() != sparing) return;
        e.setCanceled(true);
        e.getEntity().setHealth(BREATH);
        spared = true;
    }

    /** Starts the stand: still, holed and bleeding, until the timer lets it fall. */
    static void hold(ServerPlayer caster, LivingEntity victim) {
        int ticks = STAND + victim.getRandom().nextInt(SPREAD);
        begin(victim, caster.getUUID(), ticks);
        Bleed.apply(caster, victim, 5, ticks + 40);
    }

    private static void begin(LivingEntity victim, UUID caster, int ticks) {
        long until = victim.level().getGameTime() + ticks;
        HELD.put(victim.getUUID(), new Held(victim, caster, until));
        victim.getPersistentData().putBoolean(TAG, true);
        victim.setDeltaMovement(0, Math.min(0, victim.getDeltaMovement().y), 0);
        victim.hurtMarked = true;
        ScepterBlast.stun(victim, ticks + 5);
        HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.LAST_MOMENTS, victim.getId(), state(until)));
    }

    private static CompoundTag state(long until) {
        CompoundTag n = new CompoundTag();
        n.putLong("until", until);
        return n;
    }

    /** Once per level tick, after bleeding: lets every body whose time is up fall. */
    static void tick(ServerLevel level) {
        if (!RESUMING.isEmpty()) {
            for (LivingEntity victim : List.copyOf(RESUMING)) {
                if (victim.level() != level) continue;
                RESUMING.remove(victim);
                // Only a body that actually made it into the level; a cancelled load stays gone.
                if (victim.isAlive() && !victim.isRemoved() && level.getEntity(victim.getId()) == victim && !held(victim))
                    begin(victim, null, RESUMED);
            }
        }
        if (HELD.isEmpty()) return;
        long now = level.getGameTime();
        for (Held held : List.copyOf(HELD.values())) {
            LivingEntity victim = held.victim;
            if (victim.level() != level) continue;
            // Unloaded or saved: the tag brings it back to finish standing when it loads.
            if (victim.isRemoved() || !victim.isAlive()) {HELD.remove(victim.getUUID(), held); continue;}
            if (now < held.until) continue;
            HELD.remove(victim.getUUID(), held);
            fall(level, victim, held.caster);
        }
    }

    private static void fall(ServerLevel level, LivingEntity victim, UUID casterId) {
        victim.getPersistentData().remove(TAG);
        ServerPlayer caster = casterId == null ? null : level.getServer().getPlayerList().getPlayer(casterId);
        DamageSource source = caster != null ? caster.damageSources().indirectMagic(caster, caster) : level.damageSources().magic();
        victim.invulnerableTime = 0;
        victim.hurt(source, Math.max(1000, victim.getMaxHealth() * 10));
        // Something kept it alive (a totem, complete resistance): it has earned the rest of its life back.
        if (victim.isAlive()) release(victim);
    }

    private static void release(LivingEntity victim) {
        ScepterBlast.clear(victim);
        HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.LAST_MOMENTS, victim.getId(), state(0)));
    }

    /** A body loaded from disk: finish a stand that was saved half way through. */
    public static void loaded(LivingEntity victim) {
        if (!victim.getPersistentData().getBoolean(TAG) || held(victim)) return;
        if (RESUMING.size() < MAX_HELD) RESUMING.add(victim);
    }

    /** A player who starts tracking a standing body sees it bleed. */
    public static void track(ServerPlayer viewer, Entity entity) {
        Held held = entity == null ? null : HELD.get(entity.getUUID());
        if (held != null && held.victim == entity)
            HexNetwork.to(viewer, new HexNetwork.Message(HexNetwork.LAST_MOMENTS, entity.getId(), state(held.until)));
    }

    /** Dead, or gone for good: nothing is left standing. */
    public static void forget(LivingEntity victim) {
        Held held = HELD.get(victim.getUUID());
        if (held != null && held.victim == victim) HELD.remove(victim.getUUID());
        RESUMING.remove(victim);
        if (!victim.isAlive()) victim.getPersistentData().remove(TAG);
    }

    public static void reset() {
        HELD.clear();
        RESUMING.clear();
        sparing = null;
    }
}
