package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/**
 * What was there before the torrent went through it, and putting it back.
 *
 * <p>This is the safety net for the whole ability, so it is built to assume the worst. The original state
 * of every position is written down <em>before</em> anything is replaced, and it is written down completely:
 * the block state with every property it carries — orientation, half, shape, waterlogging, a fluid's own
 * level, whatever a mod added — plus the full block entity tag, so a chest comes back with its inventory,
 * a furnace with its burn and its contents, a sign with its text, and a modded machine with as much of its
 * data as it saves.
 *
 * <p>Four decisions make it hard to lose a build:
 *
 * <ul>
 * <li><b>It is saved with the world, not held in memory.</b> A restart, a crash or a server stopped inside
 *     the half-minute window does not strand a black tunnel: the record is on disk and the restore resumes,
 *     overdue, the next time the level ticks.
 * <li><b>A block entity is detached before its block is replaced.</b> A chest's own removal hook throws its
 *     entire inventory onto the floor, which would then be duplicated by the restore. Removing the block
 *     entity first means there is nothing for that hook to empty.
 * <li><b>Nothing cascades.</b> Replacing and restoring both suppress shape updates, so a door does not lose
 *     its other half, a torch does not pop off a wall that briefly stopped existing, and no neighbour is
 *     ever told to re-evaluate a world that is mid-surgery.
 * <li><b>Nothingness is unbreakable, so the window cannot be interfered with.</b> Nothing can be mined,
 *     placed, pushed or blown up where a record is waiting, which is what makes the restore conflict-free
 *     rather than merely careful. A second torrent through the same wall extends the wait instead of
 *     recording the void as the thing to put back.
 * </ul>
 */
public final class Nothingness extends SavedData {
    private static final String NAME="hexgodofstories_nothingness",LEGACY_NAME="loki_nothingness";
    /** Clients see it, and nothing else in the world is told: no cascade, no drops, no side effects. */
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    /** Restores per pass, and the cadence of passes: a carved hall knits back over about a second. */
    private static final int PER_PASS=400,CADENCE=5;
    /** Passes a position may wait for its chunk before the restore is forced through regardless. */
    private static final int PATIENCE=900;

    /** One position taken out of the world, and everything needed to put it back. */
    private static final class Wound {
        final BlockPos pos;final CompoundTag state;final CompoundTag blockEntity;
        long due;int waited;
        Wound(BlockPos pos,CompoundTag state,CompoundTag blockEntity,long due,int waited) {
            this.pos=pos;this.state=state;this.blockEntity=blockEntity;this.due=due;this.waited=waited;
        }
    }
    /** Insertion ordered, so a tunnel knits back the way it was carved. */
    private final Map<Long,Wound> wounds=new LinkedHashMap<>();

    public static Nothingness of(ServerLevel level) {
        var storage=level.getDataStorage();
        return storage.computeIfAbsent(Nothingness::load,()->{
            // Voids recorded before the rebrand still have a world owed back to them.
            Nothingness carried=storage.get(Nothingness::load,LEGACY_NAME);
            if(carried==null)return new Nothingness();
            carried.setDirty();
            return carried;
        },NAME);
    }

    /**
     * Takes one position out of the world, recording it first.
     *
     * @return true when this call is what replaced it.
     */
    public static boolean take(ServerLevel level,BlockPos pos,long due) {
        if(!level.hasChunkAt(pos))return false;
        BlockState state=level.getBlockState(pos);
        if(state.isAir())return false;
        Nothingness data=of(level);
        if(state.is(HexGodOfStories.NOTHINGNESS.get())) {
            // A second torrent through the same wall. Recording the void as the original is how a build
            // would be lost for good, so the existing record simply waits longer instead.
            Wound held=data.wounds.get(pos.asLong());
            if(held!=null&&due>held.due){held.due=due;data.setDirty();}
            return false;
        }
        CompoundTag saved=NbtUtils.writeBlockState(state);
        CompoundTag entity=null;
        BlockEntity attached=level.getBlockEntity(pos);
        if(attached!=null) {
            entity=attached.saveWithFullMetadata();
            // Detached before the block goes. Otherwise the block's own removal hook empties a chest onto
            // the floor, and the restore below would hand the same items back a second time.
            level.removeBlockEntity(pos);
        }
        level.setBlock(pos,HexGodOfStories.NOTHINGNESS.get().defaultBlockState(),FLAGS);
        data.wounds.put(pos.asLong(),new Wound(pos.immutable(),saved,entity,due,0));
        data.setDirty();
        return true;
    }

    /** One pass over the record, restoring whatever has come due. */
    public static void tick(ServerLevel level) {
        if(level.getGameTime()%CADENCE!=0)return;
        Nothingness data=of(level);
        if(data.wounds.isEmpty())return;
        long now=level.getGameTime();
        List<Wound> ready=new ArrayList<>();
        for(Wound w:data.wounds.values()) {
            if(w.due>now)continue;
            ready.add(w);
            if(ready.size()>=PER_PASS)break;
        }
        if(ready.isEmpty())return;
        for(Wound w:ready) {
            if(!level.hasChunkAt(w.pos)&&++w.waited<PATIENCE) {
                // The record is on disk, so waiting costs nothing and loses nothing. Past all patience it
                // is forced through rather than left black, because a loaded chunk is cheaper than a hole.
                w.due=now+CADENCE*4;
                continue;
            }
            data.restore(level,w);
            data.wounds.remove(w.pos.asLong());
        }
        data.setDirty();
    }

    /**
     * Puts everything back at once, whatever its due time. Used when the server is going down while chunks
     * are still loaded, so a shutdown inside the window cannot leave a tunnel waiting on a world that may
     * never be opened again.
     */
    public static void restoreAll(ServerLevel level) {
        Nothingness data=of(level);
        if(data.wounds.isEmpty())return;
        for(Wound w:new ArrayList<>(data.wounds.values())) {
            // Only what can actually be reached. A position whose chunk is already gone keeps its record
            // and is restored, overdue, the next time that chunk loads — dropping it here because the
            // server happened to be closing is exactly the permanent loss this class exists to prevent.
            if(!level.hasChunkAt(w.pos))continue;
            data.restore(level,w);
            data.wounds.remove(w.pos.asLong());
        }
        data.setDirty();
    }

    /** A short-lived Warping aperture restores its own due cells without waiting behind a distant torrent.
     * A later torrent may extend a cell's lifetime; that ownership is respected rather than shortened.
     */
    public static void restoreDue(ServerLevel level,Collection<BlockPos> positions) {
        Nothingness data=of(level);boolean changed=false;
        for(BlockPos pos:positions){
            Wound wound=data.wounds.get(pos.asLong());
            if(wound==null||wound.due>level.getGameTime()||!level.hasChunkAt(pos))continue;
            data.restore(level,wound);data.wounds.remove(pos.asLong());changed=true;
        }
        if(changed)data.setDirty();
    }

    /** How many positions are still owed a restore, for diagnostics and commands. */
    public static int pending(ServerLevel level) {return of(level).wounds.size();}

    private void restore(ServerLevel level,Wound w) {
        BlockState current=level.getBlockState(w.pos);
        // Only ever put something back over the void it left, or over air if something removed that. Any
        // other block means the world has moved on and overwriting it would be the corruption this whole
        // class exists to avoid.
        if(!current.is(HexGodOfStories.NOTHINGNESS.get())&&!current.isAir())return;
        BlockState original;
        try {
            HolderGetter<Block> blocks=level.holderLookup(Registries.BLOCK);
            original=NbtUtils.readBlockState(blocks,w.state);
        } catch(Exception ignored) {
            // A block whose mod has since been removed cannot come back; leaving air is the only honest
            // answer, and it is still better than leaving an unbreakable void.
            level.setBlock(w.pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),FLAGS);
            return;
        }
        level.setBlock(w.pos,original,FLAGS);
        if(w.blockEntity==null)return;
        BlockEntity attached=level.getBlockEntity(w.pos);
        if(attached==null)return;
        try {
            CompoundTag tag=w.blockEntity.copy();
            tag.putInt("x",w.pos.getX());tag.putInt("y",w.pos.getY());tag.putInt("z",w.pos.getZ());
            attached.load(tag);
            attached.setChanged();
        } catch(Exception ignored) {
            // The block is back even if a mod refused its own tag; better a blank chest than a black hole.
        }
    }

    // ------------------------------------------------------------------ persistence ---

    public Nothingness() {}

    public static Nothingness load(CompoundTag tag) {
        Nothingness data=new Nothingness();
        ListTag list=tag.getList("wounds",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {
            CompoundTag entry=list.getCompound(i);
            BlockPos pos=BlockPos.of(entry.getLong("pos"));
            data.wounds.put(pos.asLong(),new Wound(pos,entry.getCompound("state"),
                entry.contains("entity")?entry.getCompound("entity"):null,
                entry.getLong("due"),entry.getInt("waited")));
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag list=new ListTag();
        for(Wound w:wounds.values()) {
            CompoundTag entry=new CompoundTag();
            entry.putLong("pos",w.pos.asLong());
            entry.put("state",w.state);
            if(w.blockEntity!=null)entry.put("entity",w.blockEntity);
            entry.putLong("due",w.due);
            entry.putInt("waited",w.waited);
            list.add(entry);
        }
        tag.put("wounds",list);
        return tag;
    }
}
