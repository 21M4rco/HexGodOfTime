package com.hexgodofstories.unknown;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Snapshots only blocks hit by Unknown's body. The record survives chunk unloads and server restarts. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class UnknownTerrain extends SavedData {
    private static final String NAME="hexgodofstories_unknown_terrain";
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    private static final int MAX_PER_TICK=180,MAX_WOUNDS=12000;
    private final Map<Long,Wound> wounds=new LinkedHashMap<>();
    private static final class Wound {
        final BlockPos pos;final CompoundTag state,blockEntity;final Set<UUID> owners=new HashSet<>();
        long expires;
        Wound(BlockPos pos,CompoundTag state,CompoundTag blockEntity,long expires){
            this.pos=pos;this.state=state;this.blockEntity=blockEntity;this.expires=expires;
        }
    }
    public static UnknownTerrain of(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(UnknownTerrain::load,UnknownTerrain::new,NAME);
    }
    /** No drops, no neighbor cascade; the container is detached before its block is removed. */
    public static boolean breakBlock(ServerLevel level,BlockPos pos,UUID owner,long expires){
        if(!level.hasChunkAt(pos)||pos.getY()<level.getMinBuildHeight()||pos.getY()>=level.getMaxBuildHeight())return false;
        UnknownTerrain data=of(level);
        Wound old=data.wounds.get(pos.asLong());
        if(old!=null){old.owners.add(owner);old.expires=Math.max(old.expires,expires);data.setDirty();return false;}
        if(data.wounds.size()>=MAX_WOUNDS)return false;
        BlockState state=level.getBlockState(pos);
        if(state.isAir()||state.getDestroySpeed(level,pos)<0||state.is(HexGodOfStories.NOTHINGNESS.get())
                ||(!state.getFluidState().isEmpty()&&state.getCollisionShape(level,pos).isEmpty()))return false;
        BlockEntity be=level.getBlockEntity(pos);
        CompoundTag saved=be==null?null:be.saveWithFullMetadata();
        if(be!=null)level.removeBlockEntity(pos);
        Wound wound=new Wound(pos.immutable(),NbtUtils.writeBlockState(state),saved,expires);
        wound.owners.add(owner);
        data.wounds.put(pos.asLong(),wound);
        data.setDirty(); // Save before a crash can strand an empty position.
        level.setBlock(pos,Blocks.AIR.defaultBlockState(),FLAGS);
        return true;
    }
    /** Release a creature's wounds only once it is fully gone; overlapping creatures keep theirs open. */
    public static void release(ServerLevel level,UUID owner){
        UnknownTerrain data=of(level);
        for(Wound w:data.wounds.values())if(w.owners.remove(owner))data.setDirty();
    }
    public static void tick(ServerLevel level){
        UnknownTerrain data=of(level);
        if(data.wounds.isEmpty())return;
        long now=System.currentTimeMillis();int restored=0;
        Iterator<Wound> it=data.wounds.values().iterator();
        while(it.hasNext()&&restored<MAX_PER_TICK){
            Wound w=it.next();
            if(!w.owners.isEmpty()&&now<w.expires)continue;
            if(!level.hasChunkAt(w.pos))continue; // Keep the snapshot until that chunk loads.
            BlockEntity displaced=level.getBlockEntity(w.pos);
            if(displaced!=null)level.removeBlockEntity(w.pos);
            try{
                BlockState original=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),w.state);
                level.setBlock(w.pos,original,FLAGS);
                if(w.blockEntity!=null){
                    BlockEntity attached=level.getBlockEntity(w.pos);
                    if(attached!=null){
                        CompoundTag tag=w.blockEntity.copy();
                        tag.putInt("x",w.pos.getX());tag.putInt("y",w.pos.getY());tag.putInt("z",w.pos.getZ());
                        attached.load(tag);attached.setChanged();
                    }
                }
            }catch(Exception ex){
                // Keep the record for a transient mod/chunk failure; never silently forget inventory.
                continue;
            }
            it.remove();restored++;data.setDirty();
        }
    }
    /** An open wound cannot accept a replacement container that would be overwritten on repair. */
    @SubscribeEvent public static void place(BlockEvent.EntityPlaceEvent event){
        if(event.getLevel() instanceof ServerLevel level&&of(level).wounds.containsKey(event.getPos().asLong()))
            event.setCanceled(true);
    }
    public static UnknownTerrain load(CompoundTag root){
        UnknownTerrain data=new UnknownTerrain();
        ListTag list=root.getList("wounds",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){
            CompoundTag n=list.getCompound(i);
            Wound w=new Wound(BlockPos.of(n.getLong("pos")),n.getCompound("state"),
                    n.contains("blockEntity")?n.getCompound("blockEntity"):null,n.getLong("expires"));
            for(int j=0;j<n.getList("owners",Tag.TAG_INT_ARRAY).size();j++)
                w.owners.add(NbtUtils.loadUUID(n.getList("owners",Tag.TAG_INT_ARRAY).get(j)));
            data.wounds.put(w.pos.asLong(),w);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root){
        ListTag list=new ListTag();
        for(Wound w:wounds.values()){
            CompoundTag n=new CompoundTag();n.putLong("pos",w.pos.asLong());
            n.put("state",w.state);if(w.blockEntity!=null)n.put("blockEntity",w.blockEntity);
            n.putLong("expires",w.expires);
            ListTag owners=new ListTag();for(UUID owner:w.owners)owners.add(NbtUtils.createUUID(owner));
            n.put("owners",owners);list.add(n);
        }
        root.put("wounds",list);return root;
    }
}
