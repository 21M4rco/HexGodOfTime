package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.VoidSea;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * There is one Abyssal Pilgrim, it is always there, and it never leaves.
 *
 * <p>Not one per player and not one per Warping cell: the realm has a single occupant and the whole
 * sea is its territory, so it crosses between cells freely and simply hunts whoever is in the water.
 * Everything here exists to make that literally true rather than approximately true.
 *
 * <ul>
 *   <li><b>Never multiple.</b> Its identity is persisted in {@link PilgrimRegistry}, so the claim
 *       survives a restart. Any leviathan in the realm that is not the claimed one is removed on
 *       sight, and a new one is only ever created when the claim is genuinely empty.
 *   <li><b>Never despawns.</b> It is persistence-required, ignores despawn rules entirely, and is
 *       saved with its chunk like any other entity.
 *   <li><b>Always present.</b> A creature that hunts from beyond sight spends most of its life
 *       outside simulation distance, where Minecraft does not tick entities. It holds a chunk
 *       ticket on itself, and when a lookup finds nothing the realm pulls its last known chunk back
 *       in rather than concluding it is gone.
 * </ul>
 */
public final class PilgrimWarden {
    /** Beyond this from every occupant it has genuinely lost the realm and is brought back. */
    private static final double LOST = 1400.0;
    /** Everything in the realm, for the singleton sweep. */
    private static final AABB EVERYWHERE = new AABB(-3.0E7, VoidSea.MIN_Y - 64, -3.0E7, 3.0E7, VoidSea.MAX_Y + 64, 3.0E7);

    /**
     * Keeps the hunter's own chunk ticking wherever it has wandered to. Radius 2 resolves to chunk
     * level 31, which is entity ticking, and the ticket lapses on its own if this stops renewing.
     */
    private static final TicketType<ChunkPos> HUNT =
        TicketType.create("hexgodofstories:abyssal_pilgrim", Comparator.comparingLong(ChunkPos::toLong), 120);

    /** Who was in the water last time we looked. A dry to wet transition is a detection event. */
    private static final Set<UUID> WET = new HashSet<>();

    private PilgrimWarden() { }

    public static void reset() { WET.clear(); }

    /** Holds one chunk in an entity ticking state for the lifetime of the ticket. */
    public static void hold(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addRegionTicket(HUNT, pos, 2, pos);
    }

    // ------------------------------------------------------------------ the one occupant

    /**
     * Returns the realm's Pilgrim, loading or creating it as needed. Never returns a second one,
     * and returns null only while the claimed creature's chunk is still being pulled back in.
     */
    @Nullable
    public static AbyssalPilgrimEntity ensure(ServerLevel level) {
        PilgrimRegistry registry = PilgrimRegistry.of(level);

        // Common path: a UUID lookup, no world sweep.
        if (registry.claimed()) {
            if (level.getEntity(registry.pilgrim()) instanceof AbyssalPilgrimEntity owner && owner.isAlive() && !owner.isDying()) {
                registry.found();
                ChunkPos at = new ChunkPos(owner.blockPosition());
                registry.remember(at);
                hold(level, at);
                return owner;
            }
            ChunkPos last = registry.lastSeen();
            if (last != null && !registry.stale()) {
                // It exists, it is only unloaded. Pull its chunk back rather than spawn a rival.
                hold(level, last);
                return null;
            }
            // The chunk has been held and it never came back. The claim is dead; start over.
            registry.release();
        }

        // Rare path: adopt whatever is actually in the realm, and remove every rival.
        List<AbyssalPilgrimEntity> loaded = level.getEntitiesOfClass(AbyssalPilgrimEntity.class, EVERYWHERE, e -> e.isAlive() && !e.isDying());
        if (!loaded.isEmpty()) {
            AbyssalPilgrimEntity keep = loaded.get(0);
            for (int i = 1; i < loaded.size(); i++) loaded.get(i).discard();
            registry.claim(keep.getUUID(), new ChunkPos(keep.blockPosition()));
            registry.found();
            return keep;
        }
        return spawn(level, null);
    }

    /** Creates the realm's occupant. Refuses if one is already claimed and reachable. */
    @Nullable
    public static AbyssalPilgrimEntity spawn(ServerLevel level, @Nullable Vec3 near) {
        PilgrimRegistry registry = PilgrimRegistry.of(level);
        if (registry.claimed() && level.getEntity(registry.pilgrim()) instanceof AbyssalPilgrimEntity existing && existing.isAlive()) return existing;

        RandomSource random = level.random;
        Vec3 anchor = near == null ? new Vec3(0, VoidSea.SURFACE - 40, 0) : near;
        double angle = random.nextDouble() * Mth.TWO_PI;
        // Close enough to sit inside a normal simulation distance on arrival, so the hunt begins at
        // once. Depth costs nothing here, because chunk ticking is horizontal.
        double radius = 80 + random.nextDouble() * 70;
        double x = anchor.x + Math.cos(angle) * radius;
        double z = anchor.z + Math.sin(angle) * radius;
        double y = Mth.clamp(anchor.y - 110 - random.nextDouble() * 160, VoidSea.FLOOR + 40, VoidSea.SURFACE - 45);

        AbyssalPilgrimEntity pilgrim = HexGodOfStories.PILGRIM.get().create(level);
        if (pilgrim == null) return null;
        pilgrim.moveTo(x, y, z, random.nextFloat() * 360f, 0f);
        pilgrim.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.STRUCTURE, null, null);
        level.addFreshEntity(pilgrim);
        ChunkPos at = new ChunkPos(pilgrim.blockPosition());
        registry.claim(pilgrim.getUUID(), at);
        hold(level, at);
        return pilgrim;
    }

    // ------------------------------------------------------------------ upkeep

    /** Cheap upkeep. Most ticks do nothing at all. */
    public static void tick(ServerLevel level, long now) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        if (now % 20 == 0) {
            ChunkPos last = PilgrimRegistry.of(level).lastSeen();
            if (last != null) hold(level, last);
        }
        if (now % 40 == 0) ensure(level);
        if (now % 10 == 0) detectEntries(level, players);
        if (now % 200 == 0) { purge(level, players); reposition(level, players); }
    }

    /**
     * Anything crossing into the water is an event, not a statistic.
     *
     * <p>Waiting for the hunt controller's deliberately fuzzed long range sampling to notice a
     * swimmer is what made the ocean feel empty: you could drop in, dive, and be treated as
     * scenery. A dry to wet transition now reaches the creature with an exact position attached.
     */
    private static void detectEntries(ServerLevel level, List<ServerPlayer> players) {
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : players) {
            if (player.isSpectator() || player.isCreative()) continue;
            seen.add(player.getUUID());
            check(level, player);
            // Anything that came in with them counts too: mobs, summons, anything thrown in.
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(96),
                    e -> e.isAlive() && !(e instanceof AbyssalPilgrimEntity) && !(e instanceof Player))) {
                seen.add(other.getUUID());
                check(level, other);
            }
        }
        WET.retainAll(seen);
    }

    private static void check(ServerLevel level, LivingEntity entity) {
        boolean wet = entity.isInWater() || entity.getY() <= VoidSea.SURFACE;
        UUID id = entity.getUUID();
        if (!wet) { WET.remove(id); return; }
        if (!WET.add(id)) return;                       // already wet, so not a new entry
        AbyssalPilgrimEntity pilgrim = ensure(level);
        if (pilgrim != null && pilgrim.ai() != null) pilgrim.ai().alert(entity);
    }

    /**
     * Removes anything that would make this an ecosystem. Summoned and player-made creatures are
     * left alone deliberately, because the design wants them present, as prey.
     */
    private static void purge(ServerLevel level, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(160))) {
                if (entity instanceof AbyssalPilgrimEntity || entity instanceof ServerPlayer || entity.hasCustomName()) continue;
                MobCategory category = entity.getType().getCategory();
                if (category == MobCategory.CREATURE || category == MobCategory.AMBIENT || category == MobCategory.WATER_CREATURE
                    || category == MobCategory.WATER_AMBIENT || category == MobCategory.UNDERGROUND_WATER_CREATURE || category == MobCategory.AXOLOTLS)
                    entity.discard();
            }
        }
    }

    /**
     * Global awareness without teleporting on top of anybody. Only a creature that has truly lost
     * everyone is moved, and it is put far enough out that it has to hunt its way back in.
     */
    private static void reposition(ServerLevel level, List<ServerPlayer> players) {
        AbyssalPilgrimEntity pilgrim = ensure(level);
        if (pilgrim == null) return;
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (player.isSpectator()) continue;
            double d = pilgrim.distanceToSqr(player);
            if (d < best) { best = d; nearest = player; }
        }
        if (nearest == null || best <= LOST * LOST) return;
        RandomSource random = level.random;
        double angle = random.nextDouble() * Mth.TWO_PI;
        double radius = 180 + random.nextDouble() * 160;
        pilgrim.moveTo(nearest.getX() + Math.cos(angle) * radius,
            Mth.clamp(nearest.getY() - 200, VoidSea.FLOOR + 40, VoidSea.SURFACE - 50),
            nearest.getZ() + Math.sin(angle) * radius, random.nextFloat() * 360f, 0f);
        PilgrimRegistry.of(level).remember(new ChunkPos(pilgrim.blockPosition()));
    }

    /** Called by the creature itself once it is ticking, so it can roam past simulation distance. */
    public static void renew(ServerLevel level, AbyssalPilgrimEntity pilgrim) {
        if (pilgrim.isDying()) return;   // a sinking corpse must never inherit the realm's claim
        PilgrimRegistry registry = PilgrimRegistry.of(level);
        if (!registry.claimed()) registry.claim(pilgrim.getUUID(), new ChunkPos(pilgrim.blockPosition()));
        else if (!registry.owns(pilgrim.getUUID())) { pilgrim.discard(); return; }
        if (level.players().isEmpty()) return;
        ChunkPos pos = new ChunkPos(pilgrim.blockPosition());
        registry.remember(pos);
        hold(level, pos);
    }
}
