package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.VoidSea;
import com.hexgodofstories.warping.WarpResidency;
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
     * Keeps the hunter's own chunk ticking wherever it has wandered to. Radius 3 gives the current
     * chunk an entity-ticking safety apron as well as the centre itself, so a fast manual setPos()
     * cannot step across a border into a non-ticking chunk between ticket handoffs. The ticket
     * lapses on its own if this stops renewing.
     */
    private static final TicketType<ChunkPos> HUNT =
        TicketType.create("hexgodofstories:abyssal_pilgrim", Comparator.comparingLong(ChunkPos::toLong), 120);

    /** Who was in the water last time we looked. A dry to wet transition is a detection event. */
    private static final Set<UUID> WET = new HashSet<>();

    /** Ticks between sweeps of the realm. Also what each sweep spends off an occupant's clock. */
    private static final int SURVEY = 10;

    private PilgrimWarden() { }

    public static void reset() { WET.clear(); EnoughIsEnough.reset(); }

    /** Holds one chunk in an entity ticking state for the lifetime of the ticket. */
    public static void hold(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addRegionTicket(HUNT, pos, 3, pos, true);
    }

    /** Drop the current hunter ticket when the sea has no prey; older timed tickets expire naturally. */
    private static void sleep(ServerLevel level) {
        ChunkPos last = PilgrimRegistry.of(level).lastSeen();
        if (last != null) level.getChunkSource().removeRegionTicket(HUNT, last, 3, last, true);
        WET.clear();
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
            if (level.getEntity(registry.pilgrim()) instanceof AbyssalPilgrimEntity owner && owner.isAlive()) {
                registry.found();
                ChunkPos at = new ChunkPos(owner.blockPosition());
                registry.remember(at);
                hold(level, at);
                return owner;
            }
            ChunkPos last = registry.lastSeen();
            if (last != null && !registry.stale(level.getGameTime())) {
                // It exists, it is only unloaded. Pull its chunk back rather than spawn a rival.
                hold(level, last);
                if (WarpResidency.active(level) && !level.getChunkSource().hasChunk(last.x, last.z))
                    level.getChunk(last.x, last.z);
                return level.getEntity(registry.pilgrim()) instanceof AbyssalPilgrimEntity loaded && loaded.isAlive() ? loaded : null;
            }
            // The chunk has been held and it never came back. The claim is dead; start over.
            registry.release();
        }

        // Rare path: adopt whatever is actually in the realm, and remove every rival.
        List<AbyssalPilgrimEntity> loaded = level.getEntitiesOfClass(AbyssalPilgrimEntity.class, EVERYWHERE, e -> e.isAlive());
        if (!loaded.isEmpty()) {
            AbyssalPilgrimEntity keep = loaded.get(0);
            for (int i = 1; i < loaded.size(); i++) loaded.get(i).discard();
            registry.claim(keep.getUUID(), new ChunkPos(keep.blockPosition()));
            registry.found();
            return keep;
        }
        Vec3 anchor = null;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && !player.isCreative()) { anchor = player.position(); break; }
        }
        return spawn(level, anchor);
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
        double radius = 65 + random.nextDouble() * 25;
        double x = anchor.x + Math.cos(angle) * radius;
        double z = anchor.z + Math.sin(angle) * radius;
        double y = Mth.clamp(Math.min(anchor.y, VoidSea.SURFACE) - 35 - random.nextDouble() * 20, VoidSea.FLOOR + 40, VoidSea.SURFACE - 25);

        ChunkPos spawnChunk = new ChunkPos(BlockPos.containing(x, y, z));
        hold(level, spawnChunk);
        level.getChunk(spawnChunk.x, spawnChunk.z);
        AbyssalPilgrimEntity pilgrim = HexGodOfStories.PILGRIM.get().create(level);
        if (pilgrim == null) return null;
        pilgrim.moveTo(x, y, z, random.nextFloat() * 360f, 0f);
        pilgrim.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.STRUCTURE, null, null);
        if (!level.addFreshEntity(pilgrim)) return null;
        ChunkPos at = new ChunkPos(pilgrim.blockPosition());
        registry.claim(pilgrim.getUUID(), at);
        hold(level, at);
        return pilgrim;
    }

    // ------------------------------------------------------------------ upkeep

    /** Cheap upkeep. Most ticks do nothing at all. */
    public static void tick(ServerLevel level, long now) {
        // The sea is alive because prey is in it, not because a spectator/caster happens to be
        // watching. Once the last meaningful resident is gone, stop renewing Hexor's own chunk and
        // let the custom dimension become dormant again.
        if (!WarpResidency.active(level)) {
            if (now % 20 == 0) sleep(level);
            return;
        }

        List<ServerPlayer> players = level.players();

        if (now % 20 == 0) {
            ChunkPos last = PilgrimRegistry.of(level).lastSeen();
            if (last != null) {
                hold(level, last);
                if (!level.getChunkSource().hasChunk(last.x, last.z)) level.getChunk(last.x, last.z);
            }
        }
        if (now % 20 == 0) {
            AbyssalPilgrimEntity owner = ensure(level);
            if (owner != null) {
                for (net.minecraft.world.entity.Entity entity : level.getAllEntities())
                    if (entity instanceof AbyssalPilgrimEntity rival && rival != owner) rival.discard();
            }
        }
        if (now % SURVEY == 0) surveyRealm(level, now);
        if (now % 20 == 0) keepHunting(level);
        if (now % 200 == 0 && !players.isEmpty()) reposition(level, players);
    }

    /**
     * One walk of the realm, for the two things that have to know who is in it.
     *
     * <p>Anything crossing into the water is an event, not a statistic. Waiting for the hunt
     * controller's deliberately fuzzed long range sampling to notice a swimmer is what made the
     * ocean feel empty: you could drop in, dive, and be treated as scenery. A dry to wet
     * transition reaches the creature with an exact position attached.
     *
     * <p>The same pass counts how many of them there are, which is what Trill of the Hunt reads.
     * Everything that could be hunted is counted wherever it is — treading water, standing on a
     * boat or falling toward the surface — because the passive is about how busy the ocean is, not
     * about how many things are currently wet. Loaded entities only, which in practice is every
     * player and everything near one.
     */
    private static void surveyRealm(ServerLevel level, long now) {
        Set<UUID> wetNow = new HashSet<>();
        AbyssalPilgrimEntity pilgrim = ensure(level);
        if (pilgrim == null || pilgrim.ai() == null || pilgrim.isDying()) return;
        PilgrimRegistry registry = PilgrimRegistry.of(level);
        net.minecraft.world.entity.Entity entrant = null;
        int occupants = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity) || entity instanceof AbyssalPilgrimEntity
                    || !entity.isAlive() || entity.isSpectator()
                    || entity instanceof Player p && p.isCreative()) continue;
            occupants++;
            // Both passives are fed from here: how many there are, and how long each one has left.
            EnoughIsEnough.present(entity.getUUID(), now, SURVEY);
            if (!(entity instanceof Player)) registry.rememberQuarry(entity.getUUID(), new ChunkPos(entity.blockPosition()));
            // Airborne presence is handled by global hunting. WET must only track actual water.
            if (!entity.isInWater()) continue;
            wetNow.add(entity.getUUID());
            if (!WET.contains(entity.getUUID()) && (entrant == null || entity instanceof Player)) entrant = entity;
        }
        pilgrim.ai().occupants(occupants);
        if (entrant != null) pilgrim.ai().alert(entrant);
        EnoughIsEnough.sweep(now);
        WET.clear(); WET.addAll(wetNow);
    }

    /**
     * The sea does not stop because nobody is watching it.
     *
     * <p>A player leaving the dimension used to end the hunt for everything else in it. Only the
     * creature holds a chunk ticket, so the moment the last player leaves, every animal, summon and
     * imported mob falls out of the entity manager: the hunt scans loaded entities and finds an
     * empty ocean, and whatever was left swimming survives by being unobserved.
     *
     * <p>Two tickets fix that, and never more than one at a time. Whatever is being hunted keeps
     * its own chunk ticking, so a chase does not end because the prey drifted out of the creature's
     * own held radius. With nothing loaded to hunt, the nearest thing the realm remembers is pulled
     * back in instead, which puts it in front of the ordinary target scan and the hunt resumes on
     * its own. A memory whose chunk comes back without it is dropped: that is what a thing having
     * genuinely left looks like from here.
     */
    private static void keepHunting(ServerLevel level) {
        AbyssalPilgrimEntity pilgrim = ensure(level);
        if (pilgrim == null || pilgrim.ai() == null || pilgrim.isDying()) return;
        net.minecraft.world.entity.Entity target = pilgrim.ai().hunt().target();
        if (target != null && target.isAlive() && target.level() == level) {
            hold(level, new ChunkPos(target.blockPosition()));
            return;
        }
        PilgrimRegistry registry = PilgrimRegistry.of(level);
        UUID nearest = null; ChunkPos where = null; double best = Double.MAX_VALUE;
        // Copied, because a memory that turns out to be stale is dropped while we are walking it.
        for (var entry : new java.util.LinkedHashMap<>(registry.quarry()).entrySet()) {
            ChunkPos at = entry.getValue();
            if (level.hasChunk(at.x, at.z) && level.getEntity(entry.getKey()) == null) {
                // Its chunk is here and it is not. It died, despawned or was taken out of the realm.
                registry.forgetQuarry(entry.getKey());
                EnoughIsEnough.forget(entry.getKey());
                continue;
            }
            double distance = pilgrim.distanceToSqr(at.getMiddleBlockX(), pilgrim.getY(), at.getMiddleBlockZ());
            if (distance < best) { best = distance; nearest = entry.getKey(); where = at; }
        }
        if (nearest == null || where == null) return;
        hold(level, where);
        // Remembered, loadable, and most of a kilometre away: swim to it rather than wait for it.
        if (best > LOST * LOST) relocateNear(level, pilgrim,
            new Vec3(where.getMiddleBlockX(), Mth.clamp(pilgrim.getY(), VoidSea.FLOOR + 40, VoidSea.SURFACE - 50), where.getMiddleBlockZ()));
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
        relocateNear(level, pilgrim, nearest.position().add(0, -200, 0));
    }

    /** Puts the creature a few hundred blocks from a point, deep, and facing nowhere in particular. */
    private static void relocateNear(ServerLevel level, AbyssalPilgrimEntity pilgrim, Vec3 anchor) {
        RandomSource random = level.random;
        double angle = random.nextDouble() * Mth.TWO_PI;
        double radius = 180 + random.nextDouble() * 160;
        pilgrim.moveTo(anchor.x + Math.cos(angle) * radius,
            Mth.clamp(anchor.y, VoidSea.FLOOR + 40, VoidSea.SURFACE - 50),
            anchor.z + Math.sin(angle) * radius, random.nextFloat() * 360f, 0f);
        ChunkPos destination = new ChunkPos(pilgrim.blockPosition());
        PilgrimRegistry.of(level).remember(destination);
        // relocateNear() is a teleport, not ordinary swimming. Secure and synchronously load the
        // destination now; waiting for the next entity tick recreates the exact off-screen freeze
        // this warden exists to prevent.
        hold(level, destination);
        if (!level.getChunkSource().hasChunk(destination.x, destination.z))
            level.getChunk(destination.x, destination.z);
    }

    /** Called by the creature itself once it is ticking, so it can roam past simulation distance. */
    public static void renew(ServerLevel level, AbyssalPilgrimEntity pilgrim) {
        if (pilgrim.isDying()) return;   // a sinking corpse must never inherit the realm's claim
        PilgrimRegistry registry = PilgrimRegistry.of(level);
        if (!registry.claimed()) registry.claim(pilgrim.getUUID(), new ChunkPos(pilgrim.blockPosition()));
        else if (!registry.owns(pilgrim.getUUID())) { pilgrim.discard(); return; }

        ChunkPos pos = new ChunkPos(pilgrim.blockPosition());
        registry.remember(pos);
        // No prey, no simulation ticket. The persisted UUID/chunk is enough to wake the same Hexor
        // again later without keeping an empty ocean ticking forever.
        if (WarpResidency.active(level)) {
            hold(level, pos);
            // Most handoffs are into the safety apron and are already loaded. If a modded burst or
            // relocation crossed farther than that, make the destination real immediately instead
            // of relying on a future Hexor tick that may never arrive.
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) level.getChunk(pos.x, pos.z);
        }
    }
}
