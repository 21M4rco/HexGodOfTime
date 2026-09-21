package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.VoidSea;
import com.hexgodofstories.warping.WarpMath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the Void Sea a hunting ground rather than an ecosystem, and keeps its hunter awake.
 *
 * <p>Warping partitions each realm into 1024 block cells along X, one per opened portal, so this
 * works per cell: exactly one Abyssal Pilgrim per occupied cell, bound to its own water, with no
 * wildlife allowed to settle around it.
 *
 * <p>The awkward part is that a creature which hunts from beyond sight spends most of its life
 * outside the server's simulation distance, where Minecraft does not tick entities at all. A
 * leviathan sitting frozen three hundred blocks away is indistinguishable from an empty ocean, so
 * this class holds a chunk ticket on it, and remembers which chunk it was last seen in so a lookup
 * that comes back empty waits for it to load instead of quietly spawning a second one.
 */
public final class PilgrimWarden {
    /** A creature this far from every occupant has genuinely lost them and may be moved. */
    private static final double LOST = 1100.0;

    /**
     * Keeps the hunter's own chunk ticking wherever it has wandered to. Radius 2 resolves to chunk
     * level 31, which is entity ticking, and the ticket expires on its own if this stops renewing.
     */
    private static final TicketType<ChunkPos> HUNT =
        TicketType.create("hexgodofstories:abyssal_pilgrim", Comparator.comparingLong(ChunkPos::toLong), 120);

    /** Which creature owns which cell, and where it was last seen, so it is never duplicated. */
    private static final Map<Long, UUID> OCCUPANT = new HashMap<>();
    private static final Map<Long, ChunkPos> LAST_SEEN = new HashMap<>();
    /** Who was in the water last time we looked. A dry to wet transition is a detection event. */
    private static final Set<UUID> WET = new HashSet<>();

    private PilgrimWarden() { }

    public static void reset() { OCCUPANT.clear(); LAST_SEEN.clear(); WET.clear(); }

    /** Holds one chunk in an entity ticking state for the lifetime of the ticket. */
    public static void hold(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addRegionTicket(HUNT, pos, 2, pos);
    }

    private static AABB cellBounds(double cell) {
        return new AABB(cell - 560, VoidSea.MIN_Y, -1100, cell + 560, VoidSea.MAX_Y, 1100);
    }

    // ------------------------------------------------------------------ presence

    /** Exactly one apex creature per cell, always. Returns it when it is loaded. */
    @Nullable
    public static AbyssalPilgrimEntity ensure(ServerLevel level, double cell) {
        long key = (long) cell;
        UUID known = OCCUPANT.get(key);
        if (known != null) {
            if (level.getEntity(known) instanceof AbyssalPilgrimEntity alive && alive.isAlive() && !alive.isDying()) {
                LAST_SEEN.put(key, new ChunkPos(alive.blockPosition()));
                hold(level, LAST_SEEN.get(key));
                return alive;
            }
            ChunkPos last = LAST_SEEN.get(key);
            if (last != null) {
                // It exists, it is simply not loaded. Pull its chunk back in rather than spawn a rival.
                hold(level, last);
                return null;
            }
            OCCUPANT.remove(key);
        }

        List<AbyssalPilgrimEntity> present = level.getEntitiesOfClass(AbyssalPilgrimEntity.class, cellBounds(cell), e -> e.isAlive() && !e.isDying());
        if (!present.isEmpty()) {
            AbyssalPilgrimEntity keep = present.get(0);
            for (int i = 1; i < present.size(); i++) present.get(i).discard();
            keep.setCell(cell);
            OCCUPANT.put(key, keep.getUUID());
            LAST_SEEN.put(key, new ChunkPos(keep.blockPosition()));
            return keep;
        }
        return spawn(level, cell, null);
    }

    @Nullable
    public static AbyssalPilgrimEntity spawn(ServerLevel level, double cell, @Nullable Vec3 near) {
        RandomSource random = level.random;
        Vec3 anchor = near == null ? new Vec3(cell, VoidSea.SURFACE - 40, 0) : near;
        double angle = random.nextDouble() * Mth.TWO_PI;
        // Close enough to be inside a normal simulation distance on arrival, so the hunt starts at
        // once. Depth costs nothing here because chunk ticking is horizontal.
        double radius = 80 + random.nextDouble() * 70;
        double x = VoidSea.clampX(cell, anchor.x + Math.cos(angle) * radius);
        double z = VoidSea.clampZ(anchor.z + Math.sin(angle) * radius);
        double y = Mth.clamp(anchor.y - 110 - random.nextDouble() * 160, VoidSea.FLOOR + 40, VoidSea.SURFACE - 45);

        AbyssalPilgrimEntity pilgrim = HexGodOfStories.PILGRIM.get().create(level);
        if (pilgrim == null) return null;
        pilgrim.moveTo(x, y, z, random.nextFloat() * 360f, 0f);
        pilgrim.setCell(cell);
        pilgrim.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.STRUCTURE, null, null);
        level.addFreshEntity(pilgrim);
        OCCUPANT.put((long) cell, pilgrim.getUUID());
        LAST_SEEN.put((long) cell, new ChunkPos(pilgrim.blockPosition()));
        hold(level, LAST_SEEN.get((long) cell));
        return pilgrim;
    }

    // ------------------------------------------------------------------ upkeep

    /** Cheap upkeep. Most ticks do nothing. */
    public static void tick(ServerLevel level, long now) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        Set<Long> cells = new HashSet<>();
        for (ServerPlayer player : players) if (!player.isSpectator()) cells.add((long) WarpMath.cellX(player.getX()));

        if (now % 20 == 0) {
            for (long cell : cells) {
                ChunkPos last = LAST_SEEN.get(cell);
                if (last != null) hold(level, last);
            }
        }
        if (now % 40 == 0) for (long cell : cells) ensure(level, cell);
        if (now % 10 == 0) detectEntries(level, players, cells);
        if (now % 200 == 0) { purge(level, players); reposition(level, players); }
    }

    /**
     * Anything crossing into the water is an event, not a statistic.
     *
     * <p>Waiting for the hunt controller's own fuzzed sampling to notice a swimmer is what made the
     * ocean feel empty: you could drop in, dive, and be treated as scenery. A dry to wet transition
     * now goes straight to the creature with an exact position attached.
     */
    private static void detectEntries(ServerLevel level, List<ServerPlayer> players, Set<Long> cells) {
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
        if (!WET.add(id)) return;                       // already wet, not a new entry
        AbyssalPilgrimEntity pilgrim = ensure(level, WarpMath.cellX(entity.getX()));
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
     * the cell's occupants is moved, and it is put far enough away that it has to hunt again.
     */
    private static void reposition(ServerLevel level, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            double cell = WarpMath.cellX(player.getX());
            AbyssalPilgrimEntity pilgrim = ensure(level, cell);
            if (pilgrim == null) continue;
            double nearest = Double.MAX_VALUE;
            for (ServerPlayer occupant : players) if (WarpMath.cellX(occupant.getX()) == cell) nearest = Math.min(nearest, pilgrim.distanceToSqr(occupant));
            if (nearest <= LOST * LOST) continue;
            RandomSource random = level.random;
            double angle = random.nextDouble() * Mth.TWO_PI;
            double radius = 180 + random.nextDouble() * 160;
            pilgrim.moveTo(VoidSea.clampX(cell, player.getX() + Math.cos(angle) * radius),
                Mth.clamp(player.getY() - 200, VoidSea.FLOOR + 40, VoidSea.SURFACE - 50),
                VoidSea.clampZ(player.getZ() + Math.sin(angle) * radius), random.nextFloat() * 360f, 0f);
            LAST_SEEN.put((long) cell, new ChunkPos(pilgrim.blockPosition()));
        }
    }

    /** Called by the entity itself once it is ticking, so it can roam past simulation distance. */
    public static void renew(ServerLevel level, AbyssalPilgrimEntity pilgrim) {
        if (level.players().isEmpty()) return;
        ChunkPos pos = new ChunkPos(pilgrim.blockPosition());
        LAST_SEEN.put((long) pilgrim.cell(), pos);
        OCCUPANT.putIfAbsent((long) pilgrim.cell(), pilgrim.getUUID());
        hold(level, pos);
    }
}
