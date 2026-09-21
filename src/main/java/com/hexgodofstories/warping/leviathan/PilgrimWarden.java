package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.VoidSea;
import com.hexgodofstories.warping.WarpMath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Keeps the Void Sea a hunting ground rather than an ecosystem.
 *
 * <p>Warping partitions each realm into 1024 block cells along X, one per opened portal, so this
 * works per cell: exactly one Abyssal Pilgrim exists in a cell that has occupants, it is never
 * allowed to wander into a neighbour's water, and anything that looks like wildlife is removed.
 * Nothing here hunts; that is entirely the creature's own AI. This only guarantees it is present.
 */
public final class PilgrimWarden {
    /** A creature this far from every occupant has genuinely lost them and may be moved. */
    private static final double LOST = 1100.0;

    private PilgrimWarden() { }

    private static AABB cellBounds(double cell) {
        return new AABB(cell - 560, VoidSea.MIN_Y, -1100, cell + 560, VoidSea.MAX_Y, 1100);
    }

    /** Exactly one apex creature per cell, always. Returns it. */
    @Nullable
    public static AbyssalPilgrimEntity ensure(ServerLevel level, double cell) {
        List<AbyssalPilgrimEntity> present = level.getEntitiesOfClass(AbyssalPilgrimEntity.class, cellBounds(cell));
        if (!present.isEmpty()) {
            for (int i = 1; i < present.size(); i++) present.get(i).discard();
            return present.get(0);
        }
        return spawn(level, cell, null);
    }

    @Nullable
    public static AbyssalPilgrimEntity spawn(ServerLevel level, double cell, @Nullable Vec3 near) {
        RandomSource random = level.random;
        Vec3 anchor = near == null ? new Vec3(cell, VoidSea.SURFACE - 40, 0) : near;
        double angle = random.nextDouble() * Mth.TWO_PI;
        double radius = 260 + random.nextDouble() * 220;
        double x = VoidSea.clampX(cell, anchor.x + Math.cos(angle) * radius);
        double z = VoidSea.clampZ(anchor.z + Math.sin(angle) * radius);
        // Deep enough that arriving players never see it first.
        double y = Mth.clamp(anchor.y - 180 - random.nextDouble() * 260, VoidSea.FLOOR + 40, VoidSea.SURFACE - 60);

        AbyssalPilgrimEntity pilgrim = HexGodOfStories.PILGRIM.get().create(level);
        if (pilgrim == null) return null;
        pilgrim.moveTo(x, y, z, random.nextFloat() * 360f, 0f);
        pilgrim.setCell(cell);
        pilgrim.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.STRUCTURE, null, null);
        level.addFreshEntity(pilgrim);
        return pilgrim;
    }

    /** Cheap upkeep. Most ticks do nothing. */
    public static void tick(ServerLevel level, long now) {
        if (now % 40 != 0) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // One creature per occupied cell.
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (ServerPlayer player : players) if (!player.isSpectator()) cells.add((long) WarpMath.cellX(player.getX()));
        for (long cell : cells) {
            AbyssalPilgrimEntity pilgrim = ensure(level, cell);
            if (pilgrim != null) pilgrim.setCell(cell);
        }

        if (now % 200 == 0) {
            purge(level, players);
            reposition(level, players);
        }
    }

    /**
     * There is no wildlife here by construction, because the biome ships an empty spawner table.
     * Anything that still turns up is swept. Monsters and player conjurations are left alone on
     * purpose: the design wants them present, as prey.
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
            for (AbyssalPilgrimEntity pilgrim : level.getEntitiesOfClass(AbyssalPilgrimEntity.class, cellBounds(cell))) {
                double nearest = Double.MAX_VALUE;
                for (ServerPlayer occupant : players) if (WarpMath.cellX(occupant.getX()) == cell) nearest = Math.min(nearest, pilgrim.distanceToSqr(occupant));
                if (nearest > LOST * LOST) {
                    RandomSource random = level.random;
                    double angle = random.nextDouble() * Mth.TWO_PI;
                    double radius = 340 + random.nextDouble() * 200;
                    pilgrim.moveTo(VoidSea.clampX(cell, player.getX() + Math.cos(angle) * radius),
                        Mth.clamp(player.getY() - 220, VoidSea.FLOOR + 40, VoidSea.SURFACE - 60),
                        VoidSea.clampZ(player.getZ() + Math.sin(angle) * radius), random.nextFloat() * 360f, 0f);
                }
            }
        }
    }
}
