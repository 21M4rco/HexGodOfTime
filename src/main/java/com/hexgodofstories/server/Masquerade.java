package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Borrowing a shape. Any living thing at all can be worn — vanilla, modded, animal, boss, humanoid or
 * otherwise — because nothing here knows what it is copying. The target's own saved state is taken as
 * a snapshot and handed to the clients, which rebuild that exact creature and render it in the
 * player's place, so a specific sheep keeps its colour, a specific horse its markings and a specific
 * modded creature its variant rather than reverting to whatever its type's default happens to be.
 *
 * <p>The snapshot is trimmed and size-capped before it travels, and it never rides in the routine
 * state packet: it is sent once when the disguise is taken and again to each client that starts
 * tracking the player, which keeps a kilobyte-scale payload off the per-second wire.
 */
public final class Masquerade {
    private Masquerade() {}

    /** Save keys that carry weight but nothing visible; dropping them keeps most snapshots tiny. */
    private static final String[] DROPPED={
        "Inventory","EnderItems","recipeBook","Brain","abilities","RootVehicle","Passengers","Leash",
        "ForgeCaps","ForgeData","SelectedItemSlot","seenCredits","warden_spawn_tracker","Gossips",
        "Offers","Attributes","BukkitValues","Paper.Origin"};
    private static final int MAX_SNAPSHOT=7000,DURATION=1200;

    /** Reads a living target and dresses the caster in it. */
    public static boolean assume(ServerPlayer p,Entity target) {
        if(!(target instanceof LivingEntity living)||target==p)return false;
        ResourceLocation key=BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if(key==null)return false;
        long now=HexData.now(p);
        CompoundTag disguise=new CompoundTag();
        disguise.putString("type",key.toString());
        disguise.putUUID("uuid",target.getUUID());
        disguise.putBoolean("player",target instanceof Player);
        disguise.putFloat("width",target.getBbWidth());
        disguise.putFloat("height",target.getBbHeight());
        // What the shape reads as, decided once here so mob AI never has to build a copy to ask.
        disguise.putBoolean("enemy",target instanceof Enemy);
        disguise.putBoolean("neutral",target instanceof NeutralMob);
        disguise.putLong("start",now);
        disguise.putLong("end",now+DURATION);
        disguise.putInt("revision",HexData.get(p).getCompound("disguise").getInt("revision")+1);
        if(!(target instanceof Player))disguise.put("nbt",snapshot(living));
        HexData.get(p).put("disguise",disguise);
        broadcast(p);
        return true;
    }

    public static void drop(ServerPlayer p) {
        if(!HexData.get(p).contains("disguise"))return;
        HexData.get(p).remove("disguise");
        CompoundTag empty=new CompoundTag();
        empty.putBoolean("clear",true);
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.DISGUISE,p.getId(),empty));
        HexNetwork.fx(p,"disguise");
    }

    /** Fires the heavy payload once, to everyone who can currently see the player. */
    public static void broadcast(ServerPlayer p) {
        CompoundTag disguise=HexData.get(p).getCompound("disguise");
        if(disguise.isEmpty())return;
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.DISGUISE,p.getId(),disguise.copy()));
    }
    /** A client that has only just started seeing the player has to be told separately. */
    public static void resend(ServerPlayer viewer,ServerPlayer worn) {
        CompoundTag disguise=HexData.get(worn).getCompound("disguise");
        if(disguise.isEmpty())return;
        HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.DISGUISE,worn.getId(),disguise.copy()));
    }

    public static boolean active(Player p,long now) {
        CompoundTag disguise=HexData.get(p).getCompound("disguise");
        return !disguise.isEmpty()&&disguise.getLong("end")>now;
    }

    /**
     * Whether a creature should be fooled. Only two readings apply, both of them ones a creature could
     * plausibly make: something shaped like a monster is not prey to other monsters, and nothing hunts
     * its own species. Anything the player has actually attacked sees straight through the costume, so
     * the disguise buys approach and escape rather than immunity.
     */
    public static boolean deceives(Mob mob,ServerPlayer worn) {
        long now=mob.level().getGameTime();
        if(!active(worn,now))return false;
        if(Threat.harmedBy(mob.getUUID(),worn,now))return false;
        CompoundTag disguise=HexData.get(worn).getCompound("disguise");
        if(disguise.getBoolean("player"))return false;
        ResourceLocation key=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if(key!=null&&key.toString().equals(disguise.getString("type")))return true;
        return disguise.getBoolean("enemy")&&mob instanceof Enemy;
    }

    /**
     * Trim and cap. Saved state is the only generic route to a modded creature's variant, pattern,
     * colour or size, so it is preferred whole; when a creature saves something enormous the copy
     * degrades to its plain form rather than flooding the connection.
     */
    private static CompoundTag snapshot(LivingEntity target) {
        CompoundTag body=new CompoundTag();
        try {
            target.saveWithoutId(body);
        } catch(Exception e) {
            return new CompoundTag();
        }
        for(String key:DROPPED)body.remove(key);
        body.remove("UUID");
        body.remove("Pos");
        body.remove("Motion");
        if(estimate(body)<=MAX_SNAPSHOT)return body;
        // Second pass: keep only the small scalar state, which is where variants almost always live.
        CompoundTag lean=new CompoundTag();
        for(String key:body.getAllKeys()) {
            Tag value=body.get(key);
            if(value==null)continue;
            if(value instanceof CompoundTag||value instanceof ListTag)continue;
            lean.put(key,value.copy());
        }
        return estimate(lean)<=MAX_SNAPSHOT?lean:new CompoundTag();
    }

    /** Cheap recursive byte estimate; exact enough to keep a snapshot inside a packet. */
    private static int estimate(Tag tag) {
        if(tag instanceof CompoundTag compound) {
            int total=8;
            for(String key:compound.getAllKeys()) {
                Tag value=compound.get(key);
                total+=key.length()+3+(value==null?0:estimate(value));
                if(total>MAX_SNAPSHOT*4)return total;
            }
            return total;
        }
        if(tag instanceof ListTag list) {
            int total=8;
            for(Tag value:list) {
                total+=estimate(value);
                if(total>MAX_SNAPSHOT*4)return total;
            }
            return total;
        }
        if(tag instanceof StringTag string)return string.getAsString().length()+2;
        if(tag instanceof ByteArrayTag bytes)return bytes.getAsByteArray().length+4;
        if(tag instanceof IntArrayTag ints)return ints.getAsIntArray().length*4+4;
        if(tag instanceof LongArrayTag longs)return longs.getAsLongArray().length*8+4;
        return 8;
    }
}
