package com.hexgodofstories.compat;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.ThrownDagger;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * Saves written by older versions load into this one (dedicated server, no client classes): every item and entity an
 * older world can hold is still registered, abilities keep their saved numbers, a thrown dagger saved before blades
 * kept their angle comes back at the angle it was drawn at then (and one saved falling, or carried by a body, comes
 * back falling rather than hanging in the air), and flags only a session in progress can mean (the guard up, a grasp
 * open, a blade in hand for a combo, the retired graspDagger) are gone when a player saved with them joins again.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class LegacySaveRegression {
    private static boolean done;

    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        done = true;

        // Everything an older world's inventories and chunks can hold is still registered.
        for (String item : new String[]{"dagger", "deceiver", "scepter", "time_stick"})
            check(ForgeRegistries.ITEMS.containsKey(new ResourceLocation(HexGodOfStories.ID, item)), "item " + item + " registered");
        check(ForgeRegistries.ENTITY_TYPES.containsKey(new ResourceLocation(HexGodOfStories.ID, "thrown_dagger")), "thrown_dagger registered");

        // Abilities are saved by number: the ones older saves name stay where they were.
        check(Ability.valueOf("TWIN_DAGGERS").ordinal() < Ability.values().length, "TWIN_DAGGERS still an ability");
        check(Ability.valueOf("THREADS") != null && Ability.valueOf("DAGGERS") != null, "Anchor Being and Conjure Daggers still abilities");

        // A dagger lodged in a block, saved before the angle it went in at was kept: drawn at its own turn, as then.
        ThrownDagger original = HexGodOfStories.THROWN_DAGGER.get().create(level);
        check(original != null, "thrown dagger created");
        original.setYRot(37);
        original.setXRot(-12);
        CompoundTag old = original.saveWithoutId(new CompoundTag());
        old.remove("entryYaw");
        old.remove("entryPitch");
        old.putInt("state", ThrownDagger.IN_BLOCK);
        ThrownDagger lodged = HexGodOfStories.THROWN_DAGGER.get().create(level);
        lodged.load(old);
        check(lodged.state() == ThrownDagger.IN_BLOCK, "old lodged dagger stays lodged");
        check(Math.abs(lodged.entryYaw() - 37) < 1e-3 && Math.abs(lodged.entryPitch() + 12) < 1e-3,
            "old lodged dagger keeps the angle it was drawn at: " + lodged.entryYaw() + ", " + lodged.entryPitch());

        // One saved carried in a body (its state the body's id) comes back falling, not stuck where the body was.
        CompoundTag carried = old.copy();
        carried.putInt("state", 4321);
        ThrownDagger dropped = HexGodOfStories.THROWN_DAGGER.get().create(level);
        dropped.load(carried);
        check(dropped.state() == ThrownDagger.FALLING, "carried dagger comes back falling");

        // Saved by this version: the angle round-trips.
        CompoundTag now = lodged.saveWithoutId(new CompoundTag());
        check(now.contains("entryYaw") && Math.abs(now.getFloat("entryYaw") - 37) < 1e-3, "entry angle saved");
        ThrownDagger again = HexGodOfStories.THROWN_DAGGER.get().create(level);
        again.load(now);
        check(again.state() == ThrownDagger.IN_BLOCK && Math.abs(again.entryPitch() + 12) < 1e-3, "entry angle round-trips");

        // A player saved mid-session (a server stopped hard) joins without the session's flags, and keeps the rest.
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "LegacySave"));
        CompoundTag d = HexData.get(player);
        d.putBoolean("swordGuard", true);
        d.putLong("graspStart", 1234);
        d.putLong("graspStab", 1234);
        d.putLong("graspDagger", 1234);
        d.putLong("bladeHeld", 1234);
        d.putInt("bladeKind", 3);
        d.putLong("bladeFormed", 1234);
        d.putBoolean("abilitiesEnabled", true);
        d.putLong("graspReady", 777);
        HexData.clearSessionOnly(player);
        for (String key : new String[]{"swordGuard", "graspStart", "graspStab", "graspDagger", "bladeHeld", "bladeKind", "bladeFormed"})
            check(!d.contains(key), "stale " + key + " cleared on joining");
        check(d.getBoolean("abilitiesEnabled") && d.getLong("graspReady") == 777, "lasting data kept");

        System.out.println("LEGACY_SAVE_REGRESSIONS_PASSED");
    }

    private static void check(boolean ok, String message) {if (!ok) throw new AssertionError(message);}
}
