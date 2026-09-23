package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/** Volatile, per-player ten-second history. Nothing here is serialized to player saves. */
public final class PersonalRewind {
    private PersonalRewind() { }
    private static final int TICKS=200;
    private record InventoryState(List<ItemStack> slots) { }
    private record Moment(long tick,ResourceKey<Level> dimension,Vec3 position,Vec3 velocity,
                          float yaw,float pitch,float health,int food,float saturation,int air,
                          List<MobEffectInstance> effects,InventoryState inventory) { }
    private record Eaten(long tick,int slot,ItemStack item) { }
    private static final Map<UUID,ArrayDeque<Moment>> HISTORY=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Eaten>> EATEN=new HashMap<>();

    private static long clock(ServerPlayer player) {return player.server.overworld().getGameTime();}

    public static void record(ServerPlayer player) {
        if(!player.isAlive()||player.isSpectator())return;
        long tick=clock(player);
        ArrayDeque<Moment> history=HISTORY.computeIfAbsent(player.getUUID(),id->new ArrayDeque<>());
        if(!history.isEmpty()&&history.getLast().tick==tick)return;
        InventoryState previous=history.isEmpty()?null:history.getLast().inventory;
        InventoryState inventory=previous!=null&&sameInventory(player,previous)
            ?previous:copyInventory(player);
        List<MobEffectInstance> effects=new ArrayList<>();
        for(MobEffectInstance effect:player.getActiveEffects())effects.add(new MobEffectInstance(effect));
        history.addLast(new Moment(tick,player.level().dimension(),player.position(),player.getDeltaMovement(),
            player.getYRot(),player.getXRot(),player.getHealth(),player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel(),player.getAirSupply(),effects,inventory));
        while(history.size()>TICKS+2)history.removeFirst();
        ArrayDeque<Eaten> eaten=EATEN.get(player.getUUID());
        if(eaten!=null)while(!eaten.isEmpty()&&eaten.getFirst().tick<tick-TICKS-2)eaten.removeFirst();
    }

    /** Called immediately before a candy stack is consumed, on the server only. */
    public static void eaten(ServerPlayer player,ItemStack item) {
        int slot=player.getUsedItemHand()==net.minecraft.world.InteractionHand.OFF_HAND?40:player.getInventory().selected;
        EATEN.computeIfAbsent(player.getUUID(),id->new ArrayDeque<>())
            .addLast(new Eaten(clock(player),slot,item.copyWithCount(1)));
    }

    public static boolean rewind(ServerPlayer player) {
        ArrayDeque<Moment> history=HISTORY.get(player.getUUID());
        if(history==null||history.isEmpty())return false;
        long target=clock(player)-TICKS;
        Moment past=null;
        for(Moment moment:history)if(moment.tick==target){past=moment;break;}
        if(past==null)return false;
        ServerLevel destination=player.server.getLevel(past.dimension);
        BlockPos block=BlockPos.containing(past.position);
        if(destination==null||!destination.hasChunkAt(block)||!destination.getWorldBorder().isWithinBounds(block)
            ||!destination.noCollision(player,player.getBoundingBox().move(past.position.subtract(player.position())))
            ||destination.containsAnyLiquid(player.getBoundingBox().move(past.position.subtract(player.position()))))return false;
        if(!canRestore(player,past))return false;

        player.stopRiding();
        player.teleportTo(destination,past.position.x,past.position.y,past.position.z,past.yaw,past.pitch);
        if(player.level()!=destination)return false;
        player.setDeltaMovement(past.velocity);
        player.hurtMarked=true;
        player.setHealth(Math.min(player.getMaxHealth(),past.health));
        player.getFoodData().setFoodLevel(past.food);
        player.getFoodData().setSaturation(past.saturation);
        player.setAirSupply(past.air);
        player.removeAllEffects();
        for(MobEffectInstance effect:past.effects)player.addEffect(new MobEffectInstance(effect));
        for(int i=0;i<past.inventory.slots.size();i++)player.getInventory().setItem(i,past.inventory.slots.get(i).copy());
        player.inventoryMenu.broadcastChanges();
        history.clear();
        EATEN.remove(player.getUUID());
        HexNetwork.sync(player);
        return true;
    }

    private static boolean canRestore(ServerPlayer player,Moment past) {
        if(player.getInventory().getContainerSize()!=past.inventory.slots.size())return false;
        int[] consumed=new int[past.inventory.slots.size()];
        ArrayDeque<Eaten> eaten=EATEN.get(player.getUUID());
        if(eaten!=null)for(Eaten entry:eaten) {
            if(entry.tick<=past.tick||entry.slot<0||entry.slot>=consumed.length)continue;
            ItemStack old=past.inventory.slots.get(entry.slot);
            if(!ItemStack.isSameItemSameTags(old,entry.item))return false;
            consumed[entry.slot]++;
        }
        for(int i=0;i<consumed.length;i++) {
            ItemStack old=past.inventory.slots.get(i),current=player.getInventory().getItem(i);
            if(ItemStack.matches(old,current))continue;
            if(consumed[i]==0||old.isEmpty()||old.getCount()-consumed[i]!=current.getCount()
                ||!current.isEmpty()&&!ItemStack.isSameItemSameTags(old,current))return false;
        }
        return true;
    }

    private static boolean sameInventory(ServerPlayer player,InventoryState previous) {
        if(player.getInventory().getContainerSize()!=previous.slots.size())return false;
        for(int i=0;i<previous.slots.size();i++)if(!ItemStack.matches(player.getInventory().getItem(i),previous.slots.get(i)))return false;
        return true;
    }
    private static InventoryState copyInventory(ServerPlayer player) {
        List<ItemStack> slots=new ArrayList<>(player.getInventory().getContainerSize());
        for(int i=0;i<player.getInventory().getContainerSize();i++)slots.add(player.getInventory().getItem(i).copy());
        return new InventoryState(slots);
    }
    public static void clear(ServerPlayer player) {HISTORY.remove(player.getUUID());EATEN.remove(player.getUUID());}
    public static void reset() {HISTORY.clear();EATEN.clear();}
}
