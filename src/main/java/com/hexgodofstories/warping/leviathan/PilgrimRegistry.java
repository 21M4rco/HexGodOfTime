package com.hexgodofstories.warping.leviathan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Void Sea's single occupant, written to the save file.
 *
 * <p>There is one Abyssal Pilgrim. Not one per player, not one per Warping cell, not one per
 * session — one, for the realm, forever. That is only enforceable if the claim outlives the server
 * process, because an in-memory record is empty on restart and an empty record is indistinguishable
 * from "no creature exists", which is how duplicates get born.
 *
 * <p>So the identity is persisted here, alongside the chunk it was last seen in. A lookup that
 * comes back empty is then answerable: the creature exists, it is simply unloaded, and its chunk
 * can be pulled back in rather than a rival spawned beside it.
 */
public final class PilgrimRegistry extends SavedData {
    private static final String FILE = "hexgodofstories_abyssal_pilgrim";

    @Nullable private UUID pilgrim;
    /** Consecutive lookups that failed while the claimed chunk was being held. Not persisted. */
    private transient long missingSince = -1;
    private int lastChunkX, lastChunkZ;
    private boolean located;

    /**
     * Where each thing left in the realm was last seen.
     *
     * <p>The hunt can only find loaded entities, and with nobody in the dimension the only loaded
     * chunks are the ones the creature is holding around itself. Without this, anything left in
     * the water would simply stop existing the moment the last player left and start existing
     * again when one came back, which is a sea that only happens while it is being watched.
     *
     * <p>Persisted, so a restart does not amount to an amnesty, and bounded, so a realm somebody
     * has filled with livestock cannot grow the save file without limit.
     */
    private final Map<UUID, ChunkPos> quarry = new LinkedHashMap<>();
    public static final int MAX_QUARRY = 16;

    public static PilgrimRegistry of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(PilgrimRegistry::load, PilgrimRegistry::new, FILE);
    }

    private PilgrimRegistry() { }

    private static PilgrimRegistry load(CompoundTag tag) {
        PilgrimRegistry registry = new PilgrimRegistry();
        if (tag.hasUUID("Pilgrim")) registry.pilgrim = tag.getUUID("Pilgrim");
        if (tag.contains("LastChunkX")) {
            registry.lastChunkX = tag.getInt("LastChunkX");
            registry.lastChunkZ = tag.getInt("LastChunkZ");
            registry.located = true;
        }
        ListTag remembered = tag.getList("Quarry", Tag.TAG_COMPOUND);
        for (int i = 0; i < remembered.size() && registry.quarry.size() < MAX_QUARRY; i++) {
            CompoundTag entry = remembered.getCompound(i);
            if (entry.hasUUID("Id")) registry.quarry.put(entry.getUUID("Id"), new ChunkPos(entry.getInt("X"), entry.getInt("Z")));
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (pilgrim != null) tag.putUUID("Pilgrim", pilgrim);
        if (located) { tag.putInt("LastChunkX", lastChunkX); tag.putInt("LastChunkZ", lastChunkZ); }
        ListTag remembered = new ListTag();
        quarry.forEach((id, at) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id); entry.putInt("X", at.x); entry.putInt("Z", at.z);
            remembered.add(entry);
        });
        tag.put("Quarry", remembered);
        return tag;
    }

    @Nullable public UUID pilgrim() { return pilgrim; }
    public boolean claimed() { return pilgrim != null; }

    /** True when this creature is the realm's one occupant. */
    public boolean owns(UUID id) { return pilgrim != null && pilgrim.equals(id); }

    public void claim(UUID id, ChunkPos at) {
        this.pilgrim = id;
        missingSince = -1;
        setDirty();
        remember(at);
    }

    public void remember(ChunkPos at) {
        if (located && lastChunkX == at.x && lastChunkZ == at.z) return;
        this.lastChunkX = at.x; this.lastChunkZ = at.z; this.located = true;
        setDirty();
    }

    @Nullable public ChunkPos lastSeen() { return located ? new ChunkPos(lastChunkX, lastChunkZ) : null; }

    public void found() { missingSince = -1; }

    /**
     * A claim on a creature that never loads would leave the realm empty forever, so the claim is
     * only trusted for as long as it takes a held chunk to bring it back.
     */
    public boolean stale(long now) {
        if (missingSince < 0) missingSince = now;
        return now - missingSince >= 200;
    }

    /**
     * Notes where something in the realm was, so the hunt can go back to it once the chunk it was
     * standing in has been let go of. The oldest memory is dropped when the table is full, since
     * the creature's attention is finite and the newest arrival is the one it saw last.
     */
    public void rememberQuarry(UUID id, ChunkPos at) {
        ChunkPos known = quarry.get(id);
        if (known != null && known.x == at.x && known.z == at.z) return;
        if (known == null && quarry.size() >= MAX_QUARRY) {
            UUID oldest = quarry.keySet().iterator().next();
            quarry.remove(oldest);
        }
        quarry.put(id, at);
        setDirty();
    }

    /** Eaten, gone, or looked for in a loaded chunk and not there. */
    public void forgetQuarry(UUID id) { if (quarry.remove(id) != null) setDirty(); }

    /** Everything the realm remembers having in it, oldest memory first. */
    public Map<UUID, ChunkPos> quarry() { return Collections.unmodifiableMap(quarry); }

    /** Only ever called when the claimed creature is gone for good. */
    public void release() {
        pilgrim = null; located = false;
        setDirty();
    }
}
