package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
    /** Residents discovered in a realm rather than explicitly sent by one caster. Never returned by Y. */
    private static final UUID UNOWNED=new UUID(0L,0L);
    /** Radius three is the vanilla/Forge entity-ticking forced-chunk tier on 1.20.1. */
    private static final int TICKET_RADIUS=3;
    private static final TicketType<UUID> TICKET=TicketType.create(
        "hexgodofstories_warp_resident", Comparator.comparing(UUID::toString)
    );
    private static final Map<UUID,Integer> MISSING=new java.util.HashMap<>();

    /**
     * State a living body had when Warping first made it a resident.
     *
     * <p>Dimension changes rebuild non-player entities from NBT. Most mobs tolerate that perfectly,
     * but some modded NPCs also write temporary "frozen" physics/AI flags while they are in an
     * unusual dimension. Those flags must not become their permanent state when Y brings them home.
     * Keeping the entry state here lets recall restore what the body arrived with rather than
     * guessing that every mob should use vanilla gravity or AI.
     */
    public record TransportState(boolean known,boolean noGravity,boolean noAi) { }
    private record Resident(UUID owner,long chunk,boolean transportStateKnown,boolean noGravity,boolean noAi) { }
    private final Map<UUID,Resident> residents=new LinkedHashMap<>();

    private WarpResidency() { }

    private static WarpResidency load(CompoundTag tag){
        WarpResidency data=new WarpResidency();
        ListTag list=tag.getList("residents",10);
        for(int i=0;i<list.size();i++){
            CompoundTag n=list.getCompound(i);
            if(!n.hasUUID("id"))continue;
            UUID owner=n.hasUUID("owner")?n.getUUID("owner"):UNOWNED;
            boolean known=n.contains("transportStateKnown")&&n.getBoolean("transportStateKnown");
            data.residents.put(n.getUUID("id"),new Resident(owner,n.getLong("chunk"),known,
                n.getBoolean("noGravity"),n.getBoolean("noAi")));
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag){
        ListTag list=new ListTag();
        residents.forEach((id,resident)->{
            CompoundTag n=new CompoundTag();
            n.putUUID("id",id);n.putUUID("owner",resident.owner());n.putLong("chunk",resident.chunk());
            n.putBoolean("transportStateKnown",resident.transportStateKnown());
            n.putBoolean("noGravity",resident.noGravity());n.putBoolean("noAi",resident.noAi());
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
        register(level,entity,owner);
    }

    /**
     * Adopt a real inhabitant that entered by some other route. It keeps the realm active but has
     * no Loki owner, so Y can never steal somebody else's unrelated entity out of the dimension.
     */
    private static void adopt(ServerLevel level,Entity entity){
        if(entity==null||!inhabitant(entity)||data(level).residents.containsKey(entity.getUUID()))return;
        register(level,entity,UNOWNED);
    }

    private static void register(ServerLevel level,Entity entity,UUID owner){
        if(entity instanceof Mob mob)mob.setPersistenceRequired();
        WarpResidency data=data(level);
        ChunkPos chunk=new ChunkPos(entity.blockPosition());
        Resident prior=data.residents.get(entity.getUUID());
        // Track is called immediately after the dimension hand-off, before the realm gets a tick.
        // If this is an already-known resident, keep its original entry snapshot rather than
        // learning a temporary state a second time.
        boolean noGravity=prior!=null&&prior.transportStateKnown()?prior.noGravity():entity.isNoGravity();
        boolean noAi=prior!=null&&prior.transportStateKnown()?prior.noAi():(entity instanceof Mob mob&&mob.isNoAi());
        Resident old=data.residents.put(entity.getUUID(),new Resident(owner,chunk.toLong(),true,noGravity,noAi));
        if(old!=null&&old.chunk()!=chunk.toLong())release(level,entity.getUUID(),new ChunkPos(old.chunk()));
        hold(level,entity.getUUID(),chunk);
        data.setDirty();MISSING.remove(entity.getUUID());
    }

    /** True while this destination contains at least one meaningful inhabitant. */
    public static boolean active(ServerLevel level){
        return level!=null&&Destination.from(level)!=null&&!data(level).residents.isEmpty();
    }

    public static int count(ServerLevel level){return level==null?0:data(level).residents.size();}

    /**
     * Native realm machinery does not keep its own world awake. Hexor sleeps when there is no prey;
     * hazards, projections and decorative living helpers likewise do not count as inhabitants.
     */
    private static boolean inhabitant(Entity entity){
        // Paradise is a sanctuary. Items, vehicles, placed entities and creative visitors count
        // there too; keep the existing eligibility rules of every other destination unchanged.
        if(Destination.from(entity.level())==Destination.PARADISE)
            return entity.isAlive()&&!entity.isRemoved()&&!entity.isSpectator()
                &&!(entity instanceof WarpHazard)&&!(entity instanceof com.hexgodofstories.entity.IllusionEntity);
        if(!(entity instanceof LivingEntity living)||!living.isAlive()||living.isRemoved()||living.isSpectator())return false;
        if(living instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)return false;
        if(living.getType()==com.hexgodofstories.HexGodOfStories.PILGRIM.get())return false;
        if(living instanceof com.hexgodofstories.entity.IllusionEntity)return false;
        if(living.getType()==com.hexgodofstories.HexGodOfStories.ILLUSION.get())return false;
        if(living instanceof net.minecraft.world.entity.decoration.ArmorStand)return false;
        if(living instanceof net.minecraft.world.entity.player.Player p&&(p.isCreative()||p.isSpectator()))return false;
        return true;
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

    /**
     * The transport-sensitive state this body had when it entered the realm.
     *
     * <p>Old saves predate this snapshot and deliberately return {@code known=false}. Recall has a
     * very narrow compatibility repair for those records rather than pretending we know how an
     * arbitrary old mob was configured.
     */
    public static TransportState transportState(ServerLevel level,UUID id){
        if(level==null||id==null)return new TransportState(false,false,false);
        Resident resident=data(level).residents.get(id);
        return resident==null?new TransportState(false,false,false):
            new TransportState(resident.transportStateKnown(),resident.noGravity(),resident.noAi());
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

        // Census loaded bodies before the realm logic runs. This catches entities brought in by
        // commands/mods as well as Warping itself and, crucially, installs their ticking ticket
        // before the destination's hazards/AI ask the entity manager what is present.
        if(level.getGameTime()%20L==0L){
            for(Entity entity:level.getAllEntities())if(inhabitant(entity))adopt(level,entity);
        }

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
                    entry.setValue(new Resident(resident.owner(),current.toLong(),resident.transportStateKnown(),
                        resident.noGravity(),resident.noAi()));
                    dirty=true;
                }
                MISSING.remove(id);
                continue;
            }

            // The chunk is forced and synchronously available here. Give entity storage a short
            // grace window after startup, then treat continued absence as death/despawn/removal.
            int missing=MISSING.merge(id,1,Integer::sum);
            if(missing>200){
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
