package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import com.hexgodofstories.server.ScepterBlast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Scepter's right click, seen from the client: tap for a shot, hold to charge the stone.
 *
 * <p>The server decides whether anything fires and how hard; this only presents it. The local
 * player's own press and release are shown the tick they happen rather than a round trip later, and
 * every other player is driven by the server's charge and shot messages. Aim and recoil are springs
 * stepped once a tick and interpolated per frame, so rapid taps, a long hold and a release mid-raise
 * all blend without a pop.
 */
public final class ScepterClient {
    private ScepterClient() { }

    private static final class State {
        /** Tick the current hold began, or -1 when nothing is held. */
        long charging = -1;
        long shot = Long.MIN_VALUE / 2;
        float shotPower;
        float aim, aimPrev, recoil, recoilPrev, flash, flashPrev;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final Map<Integer, Hum> HUMS = new HashMap<>();
    private static boolean useDown;
    private static float attackLift, attackLiftPrev;
    private static int attackLiftTicks;

    public static void attackPressed() {attackLiftTicks = 4;}

    public static float attackLift() {
        return Mth.lerp(partial(), attackLiftPrev, attackLift);
    }
    private static long nextShot;
    /** Our own release that came inside the recovery, shown when the server will fire it. */
    private static long queuedAt = -1;
    private static float queuedPower;

    public static void clear() {
        var manager = Minecraft.getInstance().getSoundManager();
        HUMS.values().forEach(h -> {h.retire(); manager.stop(h);});
        HUMS.clear();
        STATES.clear();
        useDown = false;
        attackLift = attackLiftPrev = 0;
        attackLiftTicks = 0;
        queuedAt = -1;
    }

    /** Server word on another player's Scepter (or a correction for our own). */
    public static void receive(int entity, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && mc.player.getId() == entity;
        State s = STATES.computeIfAbsent(entity, k -> new State());
        switch (data.getString("state")) {
            case "charge" -> {if (!self) s.charging = data.getLong("start");}
            case "cancel" -> s.charging = -1;
            case "shot" -> {
                // Our own shots were already shown on release.
                if (!self) shoot(s, data.getFloat("power"));
            }
            default -> { }
        }
    }

    private static void shoot(State s, float power) {
        s.charging = -1;
        s.shot = ClientState.now();
        s.shotPower = power;
        s.recoil = Math.max(s.recoil, .55f + .45f * power);
        s.flash = 1;
    }

    /** Is the right-click on the Scepter ours to handle this tick? */
    public static boolean holding(Player player) {
        return player != null && player.getMainHandItem().getItem() instanceof ConjuredWeapon w && w.kind == 1
            && ConjuredWeapon.belongsTo(player.getMainHandItem(), player);
    }

    /** Called once per client tick from HexClient with the use key's physical state. */
    public static void input(boolean down) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {useDown = false; return;}
        boolean able = holding(player) && mc.screen == null && !ClientState.immobile(player) && player.isAlive();
        long now = ClientState.now();
        if (!able) {
            State held = STATES.get(player.getId());
            if (useDown || held != null && held.charging >= 0) HexNetwork.send(HexServer.SCEPTER_RELEASE, 0);
            if (held != null) held.charging = -1;
            useDown = false;
            return;
        }
        if (!down && !useDown) return;
        State s = STATES.computeIfAbsent(player.getId(), k -> new State());
        if (down && !useDown) {
            HexNetwork.send(HexServer.SCEPTER_PRESS, 0);
            s.charging = now;
        } else if (!down && useDown) {
            HexNetwork.send(HexServer.SCEPTER_RELEASE, 0);
            if (s.charging >= 0) {
                long held = now - s.charging;
                float power = ScepterBlast.power(held);
                // Mirror the server's recovery and its one-shot queue, so what is shown firing is
                // exactly what the server is going to fire, on the tick it will.
                if (now >= nextShot) {
                    shoot(s, power);
                    ScepterFx.fired(player, power);
                    nextShot = now + ScepterBlast.recovery(power);
                } else {
                    s.charging = -1;
                    if (queuedAt < 0) {
                        queuedAt = nextShot;
                        queuedPower = power;
                        nextShot += ScepterBlast.recovery(power);
                    }
                }
            }
        }
        useDown = down;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {clear(); return;}
        attackLiftPrev = attackLift;
        boolean canLift = holding(mc.player) && mc.screen == null && mc.player.isAlive()
            && !ClientState.immobile(mc.player);
        boolean lift = canLift && (mc.options.keyAttack.isDown() || attackLiftTicks > 0);
        attackLift += ((lift ? 1f : 0f) - attackLift) * (lift ? .6f : .3f);
        if (!canLift) {attackLift = attackLiftPrev = 0; attackLiftTicks = 0;}
        else if (attackLiftTicks > 0) attackLiftTicks--;
        long now = ClientState.now();
        if (queuedAt >= 0 && now >= queuedAt && mc.player != null) {
            queuedAt = -1;
            if (holding(mc.player)) {
                shoot(STATES.computeIfAbsent(mc.player.getId(), k -> new State()), queuedPower);
                ScepterFx.fired(mc.player, queuedPower);
            }
        }
        var manager = mc.getSoundManager();
        STATES.entrySet().removeIf(entry -> {
            Entity e = mc.level.getEntity(entry.getKey());
            State s = entry.getValue();
            if (e == null) return true;
            // A held charge that the server has let go of (the item changed hands, the caster froze).
            if (s.charging >= 0 && !(e instanceof Player p && holding(p))) s.charging = -1;
            boolean raised = s.charging >= 0 || now - s.shot < 12;
            s.aimPrev = s.aim;
            s.aim += ((raised ? 1 : 0) - s.aim) * (raised ? .62f : .2f);
            if (Math.abs(s.aim - (raised ? 1 : 0)) < .002f) s.aim = raised ? 1 : 0;
            s.recoilPrev = s.recoil;
            s.recoil *= .52f;
            s.flashPrev = s.flash;
            s.flash *= .72f;
            return !raised && s.aim == 0 && s.recoil < .001f && s.flash < .001f && s.charging < 0;
        });
        HUMS.entrySet().removeIf(entry -> {
            if (charging(entry.getKey()) && !entry.getValue().isStopped()) return false;
            entry.getValue().retire();
            manager.stop(entry.getValue());
            return true;
        });
        for (var entry : STATES.entrySet()) {
            if (entry.getValue().charging < 0) continue;
            float c = charge(entry.getValue(), 0);
            Entity caster = mc.level.getEntity(entry.getKey());
            if (caster != null && c > 0) ScepterFx.charging(caster, c);
            if (HUMS.containsKey(entry.getKey())) continue;
            Hum hum = new Hum(entry.getKey());
            HUMS.put(entry.getKey(), hum);
            manager.play(hum);
        }
    }

    private static State state(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().hasUUID("conjurer")) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        UUID owner = stack.getTag().getUUID("conjurer");
        Player player = mc.level.getPlayerByUUID(owner);
        return player == null ? null : STATES.get(player.getId());
    }

    private static float partial() {return Minecraft.getInstance().getFrameTime();}

    /** 0 at rest, 1 fully raised to aim. */
    public static float aim(ItemStack stack) {
        State s = state(stack);
        if (s == null) return 0;
        float a = Mth.lerp(partial(), s.aimPrev, s.aim);
        return a * a * (3 - 2 * a);
    }

    /** A sharp kick after each shot, dying away over a few ticks. */
    public static float recoil(ItemStack stack) {
        State s = state(stack);
        return s == null ? 0 : Mth.lerp(partial(), s.recoilPrev, s.recoil);
    }

    public static float charge(ItemStack stack) {
        State s = state(stack);
        return s == null ? 0 : charge(s, partial());
    }

    public static float charge(int entity, float partial) {
        State s = STATES.get(entity);
        return s == null ? 0 : charge(s, partial);
    }

    /** 0..1 over the charge window, rising only once the hold outlasts a tap. */
    private static float charge(State s, float partial) {
        if (s.charging < 0) return 0;
        float held = ClientState.now() + partial - s.charging;
        return Mth.clamp((held - ScepterBlast.TAP) / ScepterBlast.FULL, 0, 1);
    }

    /** The conjurer's interpolated vertical look, which the raised staff follows in third person. */
    public static float pitch(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().hasUUID("conjurer")) return 0;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.level == null ? null : mc.level.getPlayerByUUID(stack.getTag().getUUID("conjurer"));
        return player == null ? 0 : player.getViewXRot(partial());
    }

    public static boolean charging(int entity) {
        State s = STATES.get(entity);
        return s != null && s.charging >= 0;
    }

    /** How hard the stone burns: resting glow, a charge building in it, a flash on release. */
    public static float power(ItemStack stack) {
        State s = state(stack);
        if (s == null) return 1;
        float p = partial();
        return 1 + 1.9f * charge(s, p) + 2.2f * Mth.lerp(p, s.flashPrev, s.flash) * (.45f + .55f * s.shotPower);
    }

    /** The charge hum: the Warfork lasergun loop, rising in pitch and weight as the stone fills. */
    private static final class Hum extends AbstractTickableSoundInstance {
        private final int caster;
        private boolean retired;

        Hum(int caster) {
            super(HexGodOfStories.SCEPTER_CHARGE.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.caster = caster;
            looping = true;
            delay = 0;
            volume = .001f;
            pitch = .7f;
            attenuation = SoundInstance.Attenuation.LINEAR;
        }

        void retire() {retired = true;}

        @Override public void tick() {
            var mc = Minecraft.getInstance();
            Entity source = mc.level == null ? null : mc.level.getEntity(caster);
            if (retired || source == null || !charging(caster)) {volume = 0; stop(); return;}
            x = source.getX();
            y = source.getEyeY();
            z = source.getZ();
            float c = charge(caster, 0);
            volume = .18f + .62f * c;
            pitch = .62f + .95f * c + (c >= 1 ? Mth.sin(ClientState.now() * 1.3f) * .03f : 0);
        }
    }

    /** One-shot cue at a point in the world. */
    public static void play(SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.PLAYERS, volume, pitch,
            SoundInstance.createUnseededRandom(), x, y, z));
    }
}
