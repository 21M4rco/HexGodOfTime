package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.ConjuredWeapon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Deceiver alight: held in a transformed hand it slowly catches fire (client/DeceiverFlame draws it) and burns until
 * it leaves that hand or the transformation ends. Once it has caught, every blow of it sets what it struck burning for
 * half a second, and the burn is dealt with the blow itself (half a second of vanilla fire is too short to reach its
 * first tick of harm), the heart of fire that half second stands for.
 */
public final class BladeFire {
    private BladeFire() {}

    /** As the client's: how long the blade takes to catch, and how far in it is caught enough to burn. */
    static final int IGNITE = 32, CAUGHT = 15;
    /** Half a second of fire, and the harm it stands for. */
    static final int BURN = 10;
    static final float SCORCH = 1;

    private static final Map<UUID, Long> LIT = new HashMap<>();

    /** Every tick of a player's: lit while transformed with The Deceiver in the main hand, out the moment either stops. */
    public static void tick(ServerPlayer p) {
        if (!alight(p)) {LIT.remove(p.getUUID()); return;}
        if (LIT.putIfAbsent(p.getUUID(), HexData.now(p)) != null) return;
        // Drawn burning: the fire lit by hand along the flat of the blade, and the sword shaken out (blade_sword_ignite),
        // unless a combo has the arm.
        if (!BladeCombo.running(p)) com.hexgodofstories.network.HexNetwork.animate(p, "blade_sword_ignite");
        p.serverLevel().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.PLAYERS, 1, .6f);
        p.serverLevel().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BLAZE_SHOOT, net.minecraft.sounds.SoundSource.PLAYERS, .6f, .7f);
    }

    /** Whether this player's blade is alight at all, caught or still catching: its use key looses fire, not the guard. */
    public static boolean lit(ServerPlayer p) {return LIT.containsKey(p.getUUID()) && alight(p);}

    public static void forget(ServerPlayer p) {LIT.remove(p.getUUID());}
    public static void reset() {LIT.clear();}

    private static boolean alight(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        return p.isAlive() && !p.isSpectator() && Transformation.transformed(p)
            && held.getItem() instanceof ConjuredWeapon w && w.kind == 3 && ConjuredWeapon.belongsTo(held, p);
    }

    /** Whether this player's blade burns now: alight, and caught. */
    public static boolean burning(ServerPlayer p) {
        Long since = LIT.get(p.getUUID());
        return since != null && HexData.now(p) - since >= CAUGHT && alight(p);
    }

    /** What a blow of the burning blade adds to its harm: the burn, unless what it strikes cannot burn. */
    public static float scorch(ServerPlayer p, Entity struck) {
        return burning(p) && !struck.fireImmune() ? SCORCH : 0;
    }

    /** After a blow lands: what it struck burns for half a second. */
    public static void burn(ServerPlayer p, Entity struck) {
        if (burning(p) && !struck.fireImmune()) struck.setRemainingFireTicks(Math.max(struck.getRemainingFireTicks(), BURN));
    }
}
