package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import com.hexgodofstories.server.ScepterBlast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Scepter's right click, seen from the client: hold to charge the stone, at least a second, and let go
 * to fire; held the full ten seconds, it fires itself.
 *
 * <p>The server decides whether anything fires and how hard; this only presents it. The local
 * player's own press and release are shown the tick they happen rather than a round trip later, and
 * every other player is driven by the server's charge and shot messages. Aim and recoil are springs
 * stepped once a tick and interpolated per frame, so a hold and a release mid-raise blend without a pop.
 *
 * <p>The charge is heard from the press: one recording, made so that its last drop falls on the tick the
 * stone is full and fires itself. Whenever a hold ends — let go, dropped, fired — it fades out over a few
 * ticks rather than being cut.
 *
 * <p>After every shot the stone recovers, and smokes while it does, in every hand that carries one; a
 * recovering stone takes no right click at all. When it cools comes from the server, with the shot and
 * in each player's synced data, so a caster who walks into view mid-recovery is seen smoking too.
 */
public final class ScepterClient {
    private ScepterClient() { }

    private static final class State {
        /** Tick the current hold began, or -1 when nothing is held. */
        long charging = -1;
        /** Our own hold filled and was shown firing itself: the server fires it on its own, so no release is sent. */
        boolean fullShot;
        long shot = Long.MIN_VALUE / 2;
        float shotPower;
        float aim, aimPrev, recoil, recoilPrev, flash, flashPrev;
        /** The tick the stone cools, from the latest shot: the server's word, or our own until it arrives. */
        long ready;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final Map<Integer, ChargeSound> SOUNDS = new HashMap<>();
    /** A charge heard this late (another player's, told late) would be out of step with its own drop: it is not played. */
    private static final int LATE = 10;
    private static boolean useDown;
    private static float attackLift, attackLiftPrev;
    private static int attackLiftTicks;
    /** Ticks the local player has been holding the Scepter without a break: 0 when they are not. */
    private static int heldTicks;
    /** Until when the server's daze is on us, and whether the sway it asks for has been drawn; see {@link #sway}. */
    private static long dazedUntil = Long.MIN_VALUE;
    private static boolean swayed;
    /** How far the daze's sway goes, and how fast it gets there each tick past vanilla's own ease-off. */
    private static final float SWAY = .7f, SWAY_RISE = .1f;

    public static void attackPressed() {attackLiftTicks = 4;}

    public static float attackLift() {
        return Mth.lerp(partial(), attackLiftPrev, attackLift);
    }

    /** The hand has had the Scepter long enough to have finished coming up into view. */
    public static boolean settled() {return heldTicks >= 4;}

    public static void clear() {
        var manager = Minecraft.getInstance().getSoundManager();
        SOUNDS.values().forEach(manager::stop);
        SOUNDS.clear();
        STATES.clear();
        useDown = false;
        attackLift = attackLiftPrev = 0;
        attackLiftTicks = 0;
        heldTicks = 0;
        dazedUntil = Long.MIN_VALUE;
        swayed = false;
    }

    /** Server word on another player's Scepter (or a correction for our own). */
    public static void receive(int entity, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && mc.player.getId() == entity;
        String state = data.getString("state");
        if (state.equals("dazed")) {if (self) dazedUntil = data.getLong("until"); return;}
        State s = STATES.computeIfAbsent(entity, k -> new State());
        switch (state) {
            case "charge" -> {if (!self) s.charging = data.getLong("start");}
            // A dropped hold never fired, so nothing it was shown to fire is left cooling either.
            case "cancel" -> {s.charging = -1; s.ready = 0;}
            // Our press reached a server that still had the stone cooling: the charge we showed is dropped.
            case "cooling" -> {s.charging = -1; s.ready = data.getLong("ready");}
            case "shot" -> {
                float power = data.getFloat("power");
                // Our own shots were already shown on release or on filling, unless the server fired a hold we
                // still thought was open; that one is shown now.
                if (!self || s.charging >= 0) {
                    shoot(s, power);
                    if (self) ScepterFx.fired(mc.player, power);
                }
                s.ready = data.getLong("ready");
            }
            default -> { }
        }
    }

    /**
     * The tick this player's stone cools, already past once it has: whichever is later of the latest shot
     * and the player's synced data. A time further off than any recovery could reach counts as cooled.
     */
    public static long ready(int entity) {
        State s = STATES.get(entity);
        long ready = Math.max(s == null ? 0 : s.ready, ClientState.data(entity).getLong(ScepterBlast.READY));
        return ready - ClientState.now() > ScepterBlast.FULL_COOLDOWN + 20 ? 0 : ready;
    }

    /**
     * The Scepter's daze, drawn. Vanilla only sways the view for nausea while more than three seconds of it
     * remain, and takes seven to reach full sway, so a two-second nausea would never show at all. While
     * the daze lasts this drives the same sway itself, then leaves vanilla to ease it off at its own rate,
     * stopping in time for that to finish before the effect ends: vanilla paints its portal overlay over
     * any sway still left once nausea is gone. Runs after the player's own tick has eased it.
     */
    private static void sway(LocalPlayer me, long now) {
        MobEffectInstance nausea = me.getEffect(MobEffects.CONFUSION);
        if (nausea == null) {
            // Cured early, milk or death: the sway goes with it rather than leave that overlay a frame.
            if (swayed) me.spinningEffectIntensity = me.oSpinningEffectIntensity = 0;
            swayed = false;
            return;
        }
        int left = nausea.getDuration();
        // Longer nausea is vanilla's own to draw; and the sway must be able to ease off (a twentieth a tick) in time.
        if (now > dazedUntil || left > 60 || left <= me.spinningEffectIntensity / .05f + 2) return;
        float target = Math.min(SWAY, me.spinningEffectIntensity + .05f + SWAY_RISE);
        if (target > me.spinningEffectIntensity) {me.spinningEffectIntensity = target; swayed = true;}
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
        // A stone still smoking from its last shot takes no right click at all. A press made meanwhile is
        // swallowed whole, so holding on through the end of the recovery never starts a charge by itself.
        if (s.charging < 0 && now < ready(player.getId())) {useDown = down; return;}
        if (down && !useDown) {
            HexNetwork.send(HexServer.SCEPTER_PRESS, 0);
            s.charging = now;
            s.fullShot = false;
        } else if (down && s.charging >= 0 && now - s.charging >= ScepterBlast.FULL_HOLD) {
            // Full: it fires itself, here the tick it fills, as the server's own hold does. The server needs no
            // release for it, and must not get one early: that would be a shot a tick short of full.
            fired(player, s, ScepterBlast.power(now - s.charging), now);
            s.fullShot = true;
        } else if (!down && useDown) {
            if (!s.fullShot) HexNetwork.send(HexServer.SCEPTER_RELEASE, 0);
            s.fullShot = false;
            if (s.charging >= 0) {
                long held = now - s.charging;
                // Short of a second nothing fires: the hold is dropped, here as on the server.
                if (held >= ScepterBlast.MIN_HOLD) fired(player, s, ScepterBlast.power(held), now);
                else s.charging = -1;
            }
        }
        useDown = down;
    }

    /** Our own shot, shown the tick it happens: the recoil, the sound, and the same recovery the server starts. */
    private static void fired(Player player, State s, float power, long now) {
        shoot(s, power);
        ScepterFx.fired(player, power);
        // Its own word follows with the shot.
        s.ready = now + ScepterBlast.cooldown(power);
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
        heldTicks = holding(mc.player) ? Math.min(heldTicks + 1, 100) : 0;
        // Every stone still recovering smokes, in whatever hand carries it, for everyone who can see it.
        for (Player player : mc.level.players()) {
            if (!holding(player)) continue;
            long left = ready(player.getId()) - now;
            if (left > 0) ScepterFx.cooling(player, Math.min(1, left / (float) ScepterBlast.FULL_COOLDOWN));
        }
        if (mc.player != null) sway(mc.player, now);
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
            // Kept while the stone cools: this is where our own shot's recovery lives until the server's word.
            return !raised && s.aim == 0 && s.recoil < .001f && s.flash < .001f && s.charging < 0 && now >= s.ready;
        });
        // A charge sound whose hold is over fades itself out; once it has, it is forgotten.
        SOUNDS.values().removeIf(ChargeSound::isStopped);
        for (var entry : STATES.entrySet()) {
            State s = entry.getValue();
            if (s.charging < 0) continue;
            float c = charge(s, 0);
            Entity caster = mc.level.getEntity(entry.getKey());
            if (caster != null && c > 0) ScepterFx.charging(caster, c);
            ChargeSound playing = SOUNDS.get(entry.getKey());
            if (playing != null && playing.hold == s.charging || now - s.charging > LATE) continue;
            // A new hold: whatever is left of the last one's sound goes on fading by itself.
            ChargeSound sound = new ChargeSound(entry.getKey(), s.charging);
            SOUNDS.put(entry.getKey(), sound);
            manager.play(sound);
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
        return aim(state(stack));
    }

    /** The same raise for a player by entity id: charging, and a moment after each shot. */
    public static float aim(int entity) {
        return aim(STATES.get(entity));
    }

    private static float aim(State s) {
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

    /** 0..1 over the whole hold, from the press to the full stone. */
    private static float charge(State s, float partial) {
        if (s.charging < 0) return 0;
        return Mth.clamp(ClientState.since(s.charging, partial) / ScepterBlast.FULL_HOLD, 0, 1);
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

    /** The tick this player's current hold began, or -1. */
    private static long hold(int entity) {
        State s = STATES.get(entity);
        return s == null ? -1 : s.charging;
    }

    /** How hard the stone burns: resting glow, a charge building in it, a flash on release. */
    public static float power(ItemStack stack) {
        State s = state(stack);
        if (s == null) return 1;
        float p = partial();
        return 1 + 1.9f * charge(s, p) + 2.2f * Mth.lerp(p, s.flashPrev, s.flash) * (.45f + .55f * s.shotPower);
    }

    /**
     * One hold's charge, heard from the stone: the recording plays once through, its build carrying the
     * stone's filling and its last drop landing on the full stone. The moment the hold is over, however it
     * ended, it fades out over {@link #FADE} ticks along a quarter cosine, so a release never cuts it off.
     */
    private static final class ChargeSound extends AbstractTickableSoundInstance {
        private static final int FADE = 5;
        private final int caster;
        /** The hold this sound belongs to: a new press is a new sound, and this one goes on fading. */
        final long hold;
        private int fading = -1;

        ChargeSound(int caster, long hold) {
            super(HexGodOfStories.SCEPTER_CHARGE.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.caster = caster;
            this.hold = hold;
            looping = false;
            delay = 0;
            volume = 1;
            pitch = 1;
            attenuation = SoundInstance.Attenuation.LINEAR;
            follow();
        }

        private void follow() {
            var mc = Minecraft.getInstance();
            Entity source = mc.level == null ? null : mc.level.getEntity(caster);
            if (source == null) return;
            x = source.getX();
            y = source.getEyeY();
            z = source.getZ();
        }

        @Override public void tick() {
            if (fading < 0 && hold(caster) != hold) fading = 0;
            follow();
            if (fading < 0) return;
            if (++fading >= FADE) {volume = 0; stop(); return;}
            float left = (float) Math.cos(fading / (float) FADE * Math.PI / 2);
            volume = left * left;
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
