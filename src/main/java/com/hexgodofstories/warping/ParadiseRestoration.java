package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/**
 * Paradise does not stay broken.
 *
 * <p>A player may mine anything here and keep what they mine. What they cannot do is leave a hole:
 * every block of the realm's own terrain that is destroyed is written down first, and some seconds
 * later it grows back exactly as it was, in the same place, with the same state. The material in
 * their inventory is theirs to keep or eat. The world is not theirs to spend.
 *
 * <p><b>What counts as Paradise's own.</b> The realm is built from one deterministic blueprint, so
 * the blueprint is also the answer: a position that the layout has a block at, currently holding
 * that same block, is the realm's. Anything else — a chest somebody carried in, a bridge somebody
 * built, a block put back in a place the layout never had one — is not in the index and is never
 * touched, which is what keeps this from being a system that quietly duplicates player builds.
 *
 * <p><b>What it costs.</b> Nothing scans anything. There is no sweep over the dimension looking for
 * damage and no comparison of the world against the blueprint: the only positions this class knows
 * about are the ones something was seen to destroy, and once a position has grown back it is
 * forgotten. An untouched Paradise runs an empty map check every five ticks.
 *
 * <p>The record lives with the world rather than in memory, so a restart inside the window does not
 * strand a crater, and a position whose chunk is not loaded keeps waiting rather than being dropped.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class ParadiseRestoration extends SavedData {
    private static final String NAME = "hexgodofstories_paradise";
    /** Seen by clients, and nothing else in the world is told: no cascade, no drops, no side effects. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /**
     * How long a hole stays open, and how much that varies.
     *
     * <p>Eight and a half seconds before the earliest block returns, spread over the next nine and
     * a half. The spread is the part that matters: without it a mined-out wall snaps back in one
     * frame like an undo, and with it the rock knits itself closed block by block over a quarter of
     * a minute, which is what makes it read as the place healing rather than as a rollback.
     */
    private static final int DELAY = 170, SCATTER = 190;
    /** Restores per pass, and how often a pass runs. Unhurried on purpose. */
    private static final int PER_PASS = 10, CADENCE = 5;
    /** How far down the record a pass is willing to look for work before leaving the rest for later. */
    private static final int SCAN = 256;
    /** How long a blocked position waits for whatever is standing in it before it is forced. */
    private static final int PATIENCE = 40;
    /** Ticks before a block returns that the sugar starts gathering where it will be. */
    private static final int GATHER = 24;

    /** One position taken out of the realm, and everything needed to put it back. */
    private static final class Wound {
        final BlockPos pos; final CompoundTag state;
        long due; int waited; boolean gathering;
        Wound(BlockPos pos, CompoundTag state, long due, int waited, boolean gathering) {
            this.pos = pos; this.state = state; this.due = due; this.waited = waited; this.gathering = gathering;
        }
    }
    /** Insertion ordered, so the realm closes its wounds roughly in the order they were made. */
    private final Map<Long, Wound> wounds = new LinkedHashMap<>();

    public static ParadiseRestoration of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ParadiseRestoration::load, ParadiseRestoration::new, NAME);
    }

    // ------------------------------------------------------------------ what is Paradise's own

    private static Map<Long, BlockState> blueprint;

    /**
     * Every position the layout puts a block at, indexed by packed position.
     *
     * <p>Built from the same {@link RealmLayout} call the server generates the realm from, on first
     * use, and held for the life of the process. The layout is already cached there, so this costs
     * one walk over a list that exists anyway.
     */
    private static Map<Long, BlockState> blueprint() {
        if (blueprint == null) {
            Map<Long, BlockState> index = new HashMap<>();
            for (RealmLayout.Voxel voxel : RealmLayout.blocks(Destination.PARADISE)) index.put(voxel.pos().asLong(), voxel.state());
            blueprint = index;
        }
        return blueprint;
    }

    /**
     * The realm's own block at this position, or null when this is not Paradise's to put back.
     *
     * <p>Matched on the block rather than on the full state, because a state can be nudged by an
     * ordinary neighbour update — a leaf's distance, a stair's shape — without the block ever having
     * stopped being the one the layout placed. What is restored is still the layout's exact state,
     * so a refusal here is about ownership and never about accuracy.
     */
    public static BlockState natural(BlockPos pos, BlockState state) {
        BlockState original = blueprint().get(pos.asLong());
        return original != null && original.getBlock() == state.getBlock() ? original : null;
    }

    /** Whether this position belongs to the realm at all, whatever is currently standing in it. */
    public static boolean terrain(BlockPos pos) { return blueprint().containsKey(pos.asLong()); }

    // ------------------------------------------------------------------ being broken

    @SubscribeEvent public static void broken(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || Destination.from(level) != Destination.PARADISE) return;
        BlockState original = natural(e.getPos(), e.getState());
        if (original == null) return;
        long due = level.getGameTime() + DELAY + level.random.nextInt(SCATTER);
        record(level, e.getPos(), original, due);
        expect(level, e.getPos());
        // Whatever was standing on it comes off with it. A flower does not raise a break event when
        // the ground under it goes — it is simply dropped by the neighbour update — so the couple of
        // courses above a wound are checked here rather than being quietly lost from the meadow.
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = e.getPos().above(dy);
            BlockState standing = natural(above, level.getBlockState(above));
            // Stop at the first thing up there that could be stood on: that is structure rather
            // than decoration, and structure does not fall off when the block under it goes.
            if (standing == null || standing.isFaceSturdy(level, above, net.minecraft.core.Direction.UP)) break;
            record(level, above, standing, due + dy * 5L);
            expect(level, above);
        }
    }

    /** A blast in Paradise is still only a hole with a delay on it. */
    @SubscribeEvent public static void blast(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level) || Destination.from(level) != Destination.PARADISE) return;
        for (BlockPos pos : e.getAffectedBlocks()) {
            BlockState original = natural(pos, level.getBlockState(pos));
            if (original == null) continue;
            record(level, pos, original, level.getGameTime() + DELAY + level.random.nextInt(SCATTER));
            expect(level, pos);
        }
    }

    private static void record(ServerLevel level, BlockPos pos, BlockState original, long due) {
        ParadiseRestoration data = of(level);
        Wound held = data.wounds.get(pos.asLong());
        // Broken again while it was already owed back. The first record is the true one — it is the
        // state the layout put there — so the second only pushes the clock out.
        if (held != null) { if (due > held.due) held.due = due; data.setDirty(); return; }
        data.wounds.put(pos.asLong(), new Wound(pos.immutable(), NbtUtils.writeBlockState(original), due, 0, false));
        data.setDirty();
    }

    // ------------------------------------------------------------------ what fell out of it

    /**
     * Positions whose drops are still in flight.
     *
     * <p>Forge raises the break event immediately before the block is removed, and the items it
     * drops join the level inside the same tick, so a position recorded here and an item entity
     * appearing at it within a few ticks are the same event. That is the whole of how a stack is
     * known to have come out of Paradise's own ground rather than out of somebody's backpack, and
     * it means neither the break nor the drop has to be intercepted and re-implemented.
     */
    private static final Map<Long, Long> EXPECTED = new HashMap<>();

    private static void expect(ServerLevel level, BlockPos pos) { EXPECTED.put(pos.asLong(), level.getGameTime()); }

    @SubscribeEvent public static void dropped(EntityJoinLevelEvent e) {
        if (EXPECTED.isEmpty() || !(e.getEntity() instanceof ItemEntity item)) return;
        if (!(e.getLevel() instanceof ServerLevel level) || Destination.from(level) != Destination.PARADISE) return;
        long now = level.getGameTime();
        EXPECTED.values().removeIf(when -> now - when > 4);
        BlockPos at = item.blockPosition();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (!EXPECTED.containsKey(at.offset(dx, dy, dz).asLong())) continue;
            ParadiseFood.mark(item.getItem());
            return;
        }
    }

    // ------------------------------------------------------------------ growing back

    /** One pass over the record. Run from the realm's own tick, and only in Paradise. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % CADENCE != 0) return;
        ParadiseRestoration data = of(level);
        if (data.wounds.isEmpty()) return;
        long now = level.getGameTime();
        List<Wound> ready = new ArrayList<>();
        int scanned = 0;
        for (Wound w : data.wounds.values()) {
            if (++scanned > SCAN) break;
            if (!w.gathering && w.due - GATHER <= now) { w.gathering = true; gather(level, w.pos); }
            if (w.due > now) continue;
            ready.add(w);
            if (ready.size() >= PER_PASS) break;
        }
        if (ready.isEmpty()) return;
        for (Wound w : ready) {
            if (!level.hasChunkAt(w.pos)) {
                // The record is on disk, so waiting costs nothing and loses nothing.
                w.due = now + CADENCE * 8L;
                continue;
            }
            if (!data.restore(level, w) && ++w.waited < PATIENCE) { w.due = now + CADENCE * 8L; w.gathering = false; continue; }
            data.wounds.remove(w.pos.asLong());
        }
        data.setDirty();
    }

    /** Sugar drawing itself together where a block is about to be. */
    private static void gather(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return;
        level.sendParticles(HexGodOfStories.CANDY.get(), pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 6, .5, .5, .5, .01);
    }

    /**
     * Puts one block back.
     *
     * @return false when something has been put in its place and the realm should wait rather than
     *         overwrite it; true when the position is settled and the record can be forgotten.
     */
    private boolean restore(ServerLevel level, Wound w) {
        BlockState current = level.getBlockState(w.pos);
        // Over the hole it left, and over nothing else — until patience runs out. Somebody standing
        // a chest in a crater buys that chest about ten seconds; Paradise is a perfect world and the
        // rock does come back, but it is not in such a hurry that it eats a build without warning.
        if (!current.isAir() && !current.canBeReplaced() && w.waited < PATIENCE - 1) return false;
        BlockState original;
        try {
            HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
            original = NbtUtils.readBlockState(blocks, w.state);
        } catch (Exception ignored) {
            return true;   // a block whose mod has gone cannot come back; the record simply ends
        }
        level.setBlock(w.pos, original, FLAGS);
        double x = w.pos.getX() + .5, y = w.pos.getY() + .5, z = w.pos.getZ() + .5;
        level.sendParticles(HexGodOfStories.CANDY.get(), x, y, z, 14, .34, .34, .34, .06);
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 3, .22, .22, .22, .012);
        level.playSound(null, w.pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS,
            .25f, 1.35f + level.random.nextFloat() * .45f);
        return true;
    }

    /** How many positions the realm still owes itself, for diagnostics and commands. */
    public static int pending(ServerLevel level) { return of(level).wounds.size(); }

    public static void reset() { EXPECTED.clear(); }

    // ------------------------------------------------------------------ persistence

    public ParadiseRestoration() { }

    public static ParadiseRestoration load(CompoundTag n) {
        ParadiseRestoration data = new ParadiseRestoration();
        ListTag list = n.getList("wounds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong("pos"));
            data.wounds.put(pos.asLong(), new Wound(pos, entry.getCompound("state"),
                entry.getLong("due"), entry.getInt("waited"), false));
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag n) {
        ListTag list = new ListTag();
        for (Wound w : wounds.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", w.pos.asLong());
            entry.put("state", w.state);
            entry.putLong("due", w.due);
            entry.putInt("waited", w.waited);
            list.add(entry);
        }
        n.put("wounds", list);
        return n;
    }
}
