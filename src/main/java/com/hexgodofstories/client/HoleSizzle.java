package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A hot Scepter hole, heard: a sizzle that keeps up for as long as the hole glows, from the hole itself.
 *
 * <p>One seamless loop a hole (scepter/sizzle_loop). Every tick it goes where the hole is — a wall's, the
 * point of it nearest the listener; a wound's, its mouth, however the body moves — and is as loud as the
 * hole is hot, as bright as it glows: loud while it is red-hot, fading as it cools through orange and
 * yellow, silent and stopped once it is cold. Whatever keeps the hole starts it, and starts it again if it
 * could not be heard for a while (too far off, too many sizzling at once, the sound engine reloaded): only
 * so many sizzle at once, and only within earshot.
 */
final class HoleSizzle extends AbstractTickableSoundInstance {
    /** Holes heard at once, at most: every one is a channel of the sound engine's for as long as it lasts. */
    private static final int MOST = 10;
    /** How near a hole must be to start sizzling: a little past where it can first be heard (sounds.json's 24 blocks). */
    private static final double EARSHOT = 28;
    private static final Set<HoleSizzle> PLAYING = new HashSet<>();

    private final Supplier<Vec3> where;
    private final DoubleSupplier heat;

    private HoleSizzle(Vec3 at, Supplier<Vec3> where, DoubleSupplier heat, RandomSource random) {
        super(HexGodOfStories.SCEPTER_SIZZLE_LOOP.get(), SoundSource.PLAYERS, random);
        this.where = where;
        this.heat = heat;
        looping = true;
        delay = 0;
        attenuation = SoundInstance.Attenuation.LINEAR;
        pitch = .92f + random.nextFloat() * .16f;
        x = at.x;
        y = at.y;
        z = at.z;
        volume = loudness(heat.getAsDouble());
    }

    /**
     * Starts a hole sizzling: {@code where} it is now, or null once it has cooled or gone; and how hot it is at
     * its hottest, 0 to 1. Null, and nothing heard, when it is out of earshot, when there is no room, or when
     * it is already gone.
     */
    static HoleSizzle start(Supplier<Vec3> where, DoubleSupplier heat) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0
            || mc.options.getSoundSourceVolume(SoundSource.PLAYERS) <= 0) return null;
        // Only what the engine is still playing counts: it drops every sound when it reloads or the world changes.
        PLAYING.removeIf(sizzle -> !sizzle.heard());
        if (PLAYING.size() >= MOST) return null;
        Vec3 at = where.get();
        if (at == null || at.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > EARSHOT * EARSHOT) return null;
        HoleSizzle sizzle = new HoleSizzle(at, where, heat, SoundInstance.createUnseededRandom());
        PLAYING.add(sizzle);
        mc.getSoundManager().play(sizzle);
        return sizzle;
    }

    /** Whether it is still being heard: not stopped, and still playing in the sound engine. */
    boolean heard() {return !isStopped() && Minecraft.getInstance().getSoundManager().isActive(this);}

    /** Stops it now: what it was the sizzle of has gone. */
    void end() {
        PLAYING.remove(this);
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    /** As bright as the hole glows ({@link com.hexgodofstories.data.HoleHeat#glow(float, float[])}), as loud. */
    private static float loudness(double heat) {return heat > 0 ? (float) Math.pow(Math.min(heat, 1), .6) : 0;}

    /** The heat comes up as the beam goes through, so a sizzle may begin silent. */
    @Override public boolean canStartSilent() {return true;}

    @Override public void tick() {
        Vec3 at = where.get();
        if (at == null) {
            volume = 0;
            PLAYING.remove(this);
            stop();
            return;
        }
        x = at.x;
        y = at.y;
        z = at.z;
        volume = loudness(heat.getAsDouble());
    }
}
