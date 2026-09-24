package com.hexgodofstories.server;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/**
 * Kill attribution when the summoner logs off before Unknown finishes hunting.
 * Direct online player-source kills are counted by Minecraft itself; only offline
 * kills are journaled here, so reconnect never double-credits an ordinary kill.
 */
public final class UnknownKillCredit extends SavedData {
    private static final String FILE="hexgodofstories_unknown_kills";
    private static final class Pending {
        int mobs,players;
        final Map<String,Integer> types=new LinkedHashMap<>();
    }
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    private static UnknownKillCredit of(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(UnknownKillCredit::load,UnknownKillCredit::new,FILE);
    }
    public static void recordOffline(ServerLevel level,UUID owner,LivingEntity victim) {
        if(owner==null)return;
        UnknownKillCredit ledger=of(level);
        Pending entry=ledger.pending.computeIfAbsent(owner,k->new Pending());
        if(victim instanceof Player)entry.players++;else entry.mobs++;
        ResourceLocation type=ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
        if(type!=null&&entry.types.size()<512)entry.types.merge(type.toString(),1,Integer::sum);
        ledger.setDirty();
    }
    /** Apply journaled counts once, at player login, using the current scoreboard name. */
    public static void deliver(ServerPlayer owner) {
        UnknownKillCredit ledger=of(owner.serverLevel());
        Pending entry=ledger.pending.remove(owner.getUUID());
        if(entry==null)return;
        ledger.setDirty();
        if(entry.mobs>0)owner.awardStat(Stats.CUSTOM.get(Stats.MOB_KILLS),entry.mobs);
        if(entry.players>0)owner.awardStat(Stats.CUSTOM.get(Stats.PLAYER_KILLS),entry.players);
        for(var type:entry.types.entrySet()) {
            ResourceLocation key;
            try {key=new ResourceLocation(type.getKey());}catch(RuntimeException invalid){continue;}
            var entity=ForgeRegistries.ENTITY_TYPES.getValue(key);
            if(entity!=null)owner.awardStat(Stats.ENTITY_KILLED.get(entity),type.getValue());
        }
        int total=entry.mobs+entry.players;
        var board=owner.getScoreboard();
        board.forAllObjectives(ObjectiveCriteria.TOTAL_KILL_COUNT,owner.getScoreboardName(),score->score.add(total));
        if(entry.players>0)board.forAllObjectives(ObjectiveCriteria.PLAYER_KILL_COUNT,owner.getScoreboardName(),score->score.add(entry.players));
    }
    private static UnknownKillCredit load(CompoundTag tag) {
        UnknownKillCredit data=new UnknownKillCredit();
        ListTag entries=tag.getList("pending",Tag.TAG_COMPOUND);
        for(int i=0;i<entries.size();i++){
            CompoundTag n=entries.getCompound(i);
            if(!n.hasUUID("owner"))continue;
            Pending p=new Pending();p.mobs=n.getInt("mobs");p.players=n.getInt("players");
            CompoundTag types=n.getCompound("types");
            for(String key:types.getAllKeys())p.types.put(key,types.getInt(key));
            data.pending.put(n.getUUID("owner"),p);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag entries=new ListTag();
        for(var entry:pending.entrySet()){
            CompoundTag n=new CompoundTag();n.putUUID("owner",entry.getKey());
            n.putInt("mobs",entry.getValue().mobs);n.putInt("players",entry.getValue().players);
            CompoundTag types=new CompoundTag();
            entry.getValue().types.forEach(types::putInt);
            n.put("types",types);entries.add(n);
        }
        tag.put("pending",entries);return tag;
    }
}
