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
 *     the short restore window does not strand a black tunnel: the record is on disk and the restore resumes,
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
 *
 * <p>A crater (the Crown of Barrels' missiles, {@link #takeCrater}) is the same record put to a gentler use: the
 * ground is simply gone, open to anyone, and grows back the way Paradise's does, one block at a time, each with a
 * gathering of light the moment before it returns and a glint and a whisper of its own sound when it does. Unlike a
 * torrent's cavity it does not return through somebody standing in it: it waits for them to move, and past all
 * patience lifts them clear as it closes.
 */
public final class Nothingness extends SavedData {
    private static final String NAME="hexgodofstories_nothingness",LEGACY_NAME="loki_nothingness";
    /** Clients see it, and nothing else in the world is told: no cascade, no drops, no side effects. */
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    /** Restores per pass, and the cadence of passes: a carved hall knits back over about a second. */
    private static final int PER_PASS=400,CADENCE=5;
    /** Passes a position may wait for its chunk before the restore is forced through regardless. */
    private static final int PATIENCE=900;
    /** Ticks before a crater's block returns that light begins gathering where it will be; and passes it waits for a body in its way. */
    private static final int GATHER=24,CRATER_PATIENCE=40;

    /** One position taken out of the world, and everything needed to put it back. */
    private static final class Wound {
        final BlockPos pos;final CompoundTag state;final CompoundTag blockEntity;
        /** Beam wounds must restore exactly even if fluid flowed in or something was placed meanwhile. */
        final boolean beam;
        /** A crater's: it gathers before it returns, waits for whoever stands in it, and shows itself returning. */
        final boolean crater;
        long due;int waited;boolean gathering;
        Wound(BlockPos pos,CompoundTag state,CompoundTag blockEntity,long due,int waited,boolean beam,boolean crater) {
            this.pos=pos;this.state=state;this.blockEntity=blockEntity;this.due=due;this.waited=waited;this.beam=beam;this.crater=crater;
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
     * Legacy/full Nothingness replacement used by callers that genuinely want the whole position black.
     */
    public static boolean take(ServerLevel level,BlockPos pos,long due) {
        return takeInternal(level,pos,due,true,false);
    }

    /**
     * Time Branch terrain surgery.
     *
     * @param shell true for the outer black Nothingness skin; false for the hollow AIR core.
     * @return true when this call recorded and replaced the original position.
     */
    public static boolean takeBeam(ServerLevel level,BlockPos pos,long due,boolean shell) {
        return takeInternal(level,pos,due,shell,true,false);
    }

    /**
     * A block blown out of a crater: gone, open air, until {@code due}, when it grows back exactly as it was,
     * contents and all. Nothing drops.
     *
     * @return true when this call recorded and removed the original position.
     */
    public static boolean takeCrater(ServerLevel level,BlockPos pos,long due) {
        return takeInternal(level,pos,due,false,true,true);
    }

    private static boolean takeInternal(ServerLevel level,BlockPos pos,long due,boolean black,boolean beam,boolean crater) {
        if(!level.hasChunkAt(pos))return false;
        Nothingness data=of(level);
        Wound existing=data.wounds.get(pos.asLong());
        if(existing!=null) {
            // Overlapping beams never record an already-temporary state as the original. They only keep
            // the one real snapshot alive long enough for the newest pass to finish.
            if(due>existing.due){existing.due=due;data.setDirty();}
            return false;
        }

        BlockState state=level.getBlockState(pos);
        if(state.isAir()||state.is(HexGodOfStories.NOTHINGNESS.get()))return false;

        // Free-standing fluids are not terrain for this effect. In particular, never create a row of
        // Nothingness cubes through water; the beam simply travels through the fluid untouched.
        if(beam&&!state.getFluidState().isEmpty()&&state.getCollisionShape(level,pos).isEmpty())return false;
        // Nothing unbreakable is ever taken, whatever asks.
        if(crater&&state.getDestroySpeed(level,pos)<0)return false;

        CompoundTag saved=NbtUtils.writeBlockState(state);
        CompoundTag entity=null;
        BlockEntity attached=level.getBlockEntity(pos);
        if(attached!=null) {
            entity=attached.saveWithFullMetadata();
            // Detach before replacement so a chest/machine cannot dump or duplicate its inventory.
            level.removeBlockEntity(pos);
        }

        BlockState replacement=black
            ?HexGodOfStories.NOTHINGNESS.get().defaultBlockState()
            :net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        level.setBlock(pos,replacement,FLAGS);
        data.wounds.put(pos.asLong(),new Wound(pos.immutable(),saved,entity,due,0,beam,crater));
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
            if(w.crater&&!w.gathering&&w.due-GATHER<=now&&level.hasChunkAt(w.pos)){w.gathering=true;gather(level,w.pos);}
            if(w.due>now||ready.size()>=PER_PASS)continue;
            ready.add(w);
        }
        if(ready.isEmpty())return;
        for(Wound w:ready) {
            if(!level.hasChunkAt(w.pos)&&++w.waited<PATIENCE) {
                // The record is on disk, so waiting costs nothing and loses nothing. Past all patience it
                // is forced through rather than left black, because a loaded chunk is cheaper than a hole.
                w.due=now+CADENCE*4;
                continue;
            }
            if(w.crater&&occupied(level,w.pos)&&++w.waited<CRATER_PATIENCE) {
                // Somebody is standing where it would return. It waits for them, gathering again when it is ready.
                w.due=now+CADENCE*2;
                w.gathering=false;
                continue;
            }
            data.restore(level,w);
            if(w.crater)mended(level,w.pos);
            data.wounds.remove(w.pos.asLong());
        }
        data.setDirty();
    }

    /** Light drawing itself together where a crater's block is about to be. */
    private static void gather(ServerLevel level,BlockPos pos) {
        level.sendParticles(HexGodOfStories.GOLD_EMBER.get(),pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,4,.45,.45,.45,.005);
    }

    /** Whether anything alive stands in the block at {@code pos}. */
    private static boolean occupied(ServerLevel level,BlockPos pos) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(pos),e->e.isAlive()&&!e.isSpectator()).isEmpty();
    }

    /** A crater's block back in place: a glint, now and then a whisper of its own sound, and anybody still in it lifted clear. */
    private static void mended(ServerLevel level,BlockPos pos) {
        double x=pos.getX()+.5,y=pos.getY()+.5,z=pos.getZ()+.5;
        level.sendParticles(HexGodOfStories.GOLD_EMBER.get(),x,y,z,5,.36,.36,.36,.03);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,x,y,z,1,.25,.25,.25,.01);
        BlockState state=level.getBlockState(pos);
        if(level.random.nextInt(3)==0) {
            var sound=state.getSoundType(level,pos,null);
            level.playSound(null,pos,sound.getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,.3f*sound.getVolume(),sound.getPitch()*(.9f+level.random.nextFloat()*.2f));
        }
        var shape=state.getCollisionShape(level,pos);
        if(shape.isEmpty())return;
        for(var e:level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,shape.bounds().move(pos),e->!e.isSpectator()))
            e.setPos(e.getX(),pos.getY()+shape.max(net.minecraft.core.Direction.Axis.Y)+1e-3,e.getZ());
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
        // Ordinary Nothingness wounds preserve the old conflict-avoidance rule. Time Branch wounds are
        // explicitly temporary world surgery: they must return the exact original state even if water
        // flowed into the cavity or somebody placed a block during the four-second window.
        if(!w.beam&&!current.is(HexGodOfStories.NOTHINGNESS.get())&&!current.isAir())return;
        // A crater is open ground, and whatever somebody built into it in the meantime is theirs: it comes out as
        // it would if they broke it, contents and all, before the ground returns.
        if(w.crater&&!current.isAir()&&!current.canBeReplaced())level.destroyBlock(w.pos,true);
        if(w.beam) {
            BlockEntity displaced=level.getBlockEntity(w.pos);
            if(displaced!=null)level.removeBlockEntity(w.pos);
        }
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
                entry.getLong("due"),entry.getInt("waited"),entry.getBoolean("beam"),entry.getBoolean("crater")));
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
            entry.putBoolean("beam",w.beam);
            if(w.crater)entry.putBoolean("crater",true);
            list.add(entry);
        }
        tag.put("wounds",list);
        return tag;
    }
}
