package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent ownership and ticking for bodies Warping has delivered to a destination realm.
 *
 * <p>An empty custom dimension still gets a ServerLevel tick, but its entity chunks do not remain
 * entity-ticking just because an entity was once teleported into them. That made victims freeze the
 * moment the last player left the realm: somebody thrown at the Sun would hang in mid-air until a
 * Loki player personally entered and caused the chunk to tick again.
 *
 * <p>Each warped body therefore owns one non-expiring, simulation-enabled region ticket following
 * its current chunk. The ticket is persisted as the body's last chunk and is recreated after a
 * restart. Mobs are also marked persistent: a deliberate dimensional imprisonment must end by the
 * realm killing the body or by Warping releasing it, not by an ordinary despawn sweep.
 *
 * <p>The same ledger is the source of truth for Y/release. Recall no longer guesses that everything
 * must still be inside a small square around the original arrival point.
 */
public final class WarpResidency extends SavedData {
    private static final String DATA="warping_residency";
    /** Radius three is the vanilla/Forge entity-ticking forced-chunk tier on 1.20.1. */
    private static final int TICKET_RADIUS=3;
    private static final TicketType<UUID> TICKET=TicketType.create(
        "hexgodofstories_warp_resident", Comparator.comparing(UUID::toString)
    );
    private static final Map<UUID,Integer> MISSING=new java.util.HashMap<>();

    private record Resident(UUID owner,long chunk) { }
    private final Map<UUID,Resident> residents=new LinkedHashMap<>();

    private WarpResidency() { }

    private static WarpResidency load(CompoundTag tag){
        WarpResidency data=new WarpResidency();
        ListTag list=tag.getList("residents",10);
        for(int i=0;i<list.size();i++){
            CompoundTag n=list.getCompound(i);
            if(!n.hasUUID("id")||!n.hasUUID("owner"))continue;
            data.residents.put(n.getUUID("id"),new Resident(n.getUUID("owner"),n.getLong("chunk")));
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag){
        ListTag list=new ListTag();
        residents.forEach((id,resident)->{
            CompoundTag n=new CompoundTag();
            n.putUUID("id",id);n.putUUID("owner",resident.owner());n.putLong("chunk",resident.chunk());
            list.add(n);
        });
        tag.put("residents",list);
        return tag;
    }

    private static WarpResidency data(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(WarpResidency::load,WarpResidency::new,DATA);
    }

    /** Remember a body immediately after Warping has successfully placed it in a destination. */
    public static void track(Entity entity,UUID owner){
        if(entity==null||owner==null||!(entity.level() instanceof ServerLevel level)||Destination.from(level)==null)return;
        if(entity instanceof Mob mob)mob.setPersistenceRequired();
        WarpResidency data=data(level);
        ChunkPos chunk=new ChunkPos(entity.blockPosition());
        Resident old=data.residents.put(entity.getUUID(),new Resident(owner,chunk.toLong()));
        if(old!=null&&old.chunk()!=chunk.toLong())release(level,entity.getUUID(),new ChunkPos(old.chunk()));
        hold(level,entity.getUUID(),chunk);
        data.setDirty();MISSING.remove(entity.getUUID());
    }

    /** Remove a body from this realm's residency after it leaves or is confirmed gone. */
    public static void untrack(ServerLevel level,UUID id){
        if(level==null||id==null)return;
        WarpResidency data=data(level);
        Resident old=data.residents.remove(id);
        if(old!=null){
            release(level,id,new ChunkPos(old.chunk()));
            data.setDirty();
        }
        MISSING.remove(id);
    }

    public static boolean known(ServerLevel level,UUID id){
        return level!=null&&id!=null&&data(level).residents.containsKey(id);
    }

    /** All bodies this caster actually sent to this realm, in stable insertion order. */
    public static List<UUID> owned(ServerLevel level,UUID owner){
        List<UUID> ids=new ArrayList<>();
        if(level==null||owner==null)return ids;
        data(level).residents.forEach((id,resident)->{if(owner.equals(resident.owner()))ids.add(id);});
        return ids;
    }

    /** Bring the exact chunks belonging to this caster's prisoners back into memory before recall. */
    public static void wakeOwned(ServerLevel level,UUID owner){
        if(level==null||owner==null)return;
        WarpResidency data=data(level);
        for(var entry:data.residents.entrySet()){
            if(!owner.equals(entry.getValue().owner()))continue;
            ChunkPos chunk=new ChunkPos(entry.getValue().chunk());
            hold(level,entry.getKey(),chunk);
            if(!level.getChunkSource().hasChunk(chunk.x,chunk.z))level.getChunk(chunk.x,chunk.z);
        }
    }

    /** Resolve a tracked body even when the realm had previously gone cold. */
    public static Entity resolve(ServerLevel level,UUID id){
        if(level==null||id==null)return null;
        ServerPlayer player=level.getServer().getPlayerList().getPlayer(id);
        if(player!=null&&player.serverLevel()==level)return player;
        Entity loaded=level.getEntity(id);
        if(loaded!=null)return loaded;
        Resident resident=data(level).residents.get(id);
        if(resident==null)return null;
        ChunkPos chunk=new ChunkPos(resident.chunk());
        hold(level,id,chunk);
        if(!level.getChunkSource().hasChunk(chunk.x,chunk.z))level.getChunk(chunk.x,chunk.z);
        return level.getEntity(id);
    }

    /**
     * Refresh the residency contract for one destination.
     *
     * <p>The ticket type has no timeout, but re-adding the same ticket is intentionally harmless
     * and makes this self-healing after a server restart without maintaining a second runtime-only
     * "tickets installed" cache. If a resident walks across a chunk edge, its ticket moves with it.
     */
    public static void tick(ServerLevel level){
        if(Destination.from(level)==null)return;
        WarpResidency data=data(level);
        boolean dirty=false;
        Iterator<Map.Entry<UUID,Resident>> it=data.residents.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next();
            UUID id=entry.getKey();Resident resident=entry.getValue();
            ChunkPos recorded=new ChunkPos(resident.chunk());
            hold(level,id,recorded);
            if(!level.getChunkSource().hasChunk(recorded.x,recorded.z))level.getChunk(recorded.x,recorded.z);

            ServerPlayer online=level.getServer().getPlayerList().getPlayer(id);
            if(online!=null&&online.serverLevel()!=level){
                release(level,id,recorded);it.remove();MISSING.remove(id);dirty=true;continue;
            }

            Entity entity=online!=null?online:level.getEntity(id);
            if(entity!=null){
                if(!entity.isAlive()||entity.isRemoved()){
                    release(level,id,recorded);it.remove();MISSING.remove(id);dirty=true;continue;
                }
                ChunkPos current=new ChunkPos(entity.blockPosition());
                if(current.toLong()!=resident.chunk()){
                    release(level,id,recorded);hold(level,id,current);
                    entry.setValue(new Resident(resident.owner(),current.toLong()));
                    dirty=true;
                }
                MISSING.remove(id);
                continue;
            }

            // The chunk is forced and synchronously available here. Give entity storage a short
            // grace window after startup, then treat continued absence as death/despawn/removal.
            int missing=MISSING.merge(id,1,Integer::sum);
            if(missing>40){
                release(level,id,recorded);it.remove();MISSING.remove(id);dirty=true;
            }
        }
        if(dirty)data.setDirty();
    }

    private static void hold(ServerLevel level,UUID id,ChunkPos chunk){
        level.getChunkSource().addRegionTicket(TICKET,chunk,TICKET_RADIUS,id,true);
    }

    private static void release(ServerLevel level,UUID id,ChunkPos chunk){
        level.getChunkSource().removeRegionTicket(TICKET,chunk,TICKET_RADIUS,id,true);
    }

    public static void resetRuntime(){MISSING.clear();}
}
