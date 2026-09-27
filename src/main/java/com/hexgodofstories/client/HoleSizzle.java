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
 * A hot Scepter hole, heard: fire crackling in it for as long as it glows, from the hole itself.
 *
 * <p>One loop a hole (scepter/sizzle), at its own level all the while the hole glows red, orange and yellow,
 * dying away smoothly over the last {@link #FADE} ticks of the glow and gone as the last of it goes out. Every
 * tick it goes where the hole is: a wall's, the point of it nearest the listener; a wound's, its mouth, however
 * the body moves. A hole gone before it is cold (mined out, or its body gone) dies away over {@link #GONE} ticks
 * where it was last heard. Whatever keeps the hole starts it, and starts it again if it could not be heard for
 * a while (too far off, too many at once, the sound engine reloaded): only so many at once, and only within earshot.
 */
final class HoleSizzle extends AbstractTickableSoundInstance {
    /** Holes heard at once, at most: every one is a channel of the sound engine's for as long as it lasts. */
    private static final int MOST = 10;
    /** How near a hole must be to start sizzling: a little past where it can first be heard (sounds.json's 24 blocks). */
    private static final double EARSHOT = 28;
    /** Ticks it dies away over as the glow goes out; and when its hole is gone before it is cold. */
    private static final int FADE = 30, GONE = 8;
    private static final Set<HoleSizzle> PLAYING = new HashSet<>();

    private final Supplier<Vec3> where;
    private final DoubleSupplier left;
    /** Ticks since its hole was found gone, and how loud it was then; -1 while the hole is there. */
    private int gone = -1;
    private float was;

    private HoleSizzle(Vec3 at, Supplier<Vec3> where, DoubleSupplier left, RandomSource random) {
        super(HexGodOfStories.SCEPTER_SIZZLE.get(), SoundSource.PLAYERS, random);
        this.where = where;
        this.left = left;
        looping = true;
        delay = 0;
        attenuation = SoundInstance.Attenuation.LINEAR;
        // A shade apart, so that two holes opened by one shot are never heard as one sound.
        pitch = .96f + random.nextFloat() * .08f;
        x = at.x;
        y = at.y;
        z = at.z;
        volume = loudness(left.getAsDouble());
    }

    /**
     * Starts a hole sizzling: {@code where} it is now, or null once it has gone; and how many ticks are {@code left}
     * before the last of its glow goes out. Null, and nothing heard, when it is out of earshot, when there is no
     * room, or when it has already gone or gone cold.
     */
    static HoleSizzle start(Supplier<Vec3> where, DoubleSupplier left) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(left.getAsDouble() > 0) || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0
            || mc.options.getSoundSourceVolume(SoundSource.PLAYERS) <= 0) return null;
        // Only what the engine is still playing counts: it drops every sound when it reloads or the world changes.
        PLAYING.removeIf(sizzle -> !sizzle.heard());
        if (PLAYING.size() >= MOST) return null;
        Vec3 at = where.get();
        if (at == null || at.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > EARSHOT * EARSHOT) return null;
        HoleSizzle sizzle = new HoleSizzle(at, where, left, SoundInstance.createUnseededRandom());
        PLAYING.add(sizzle);
        mc.getSoundManager().play(sizzle);
        return sizzle;
    }

    /** Whether it is still being heard: not stopped, and still playing in the sound engine. */
    boolean heard() {return !isStopped() && Minecraft.getInstance().getSoundManager().isActive(this);}

    /** Stops it now: the world it was heard in has gone. */
    void end() {
        PLAYING.remove(this);
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    /** At its own level while the hole glows, and over the last {@link #FADE} ticks of the glow, dying away to nothing. */
    private static float loudness(double left) {
        if (!(left > 0)) return 0;
        if (left >= FADE) return 1;
        float s = (float) Math.sin(left / FADE * Math.PI / 2);
        return s * s;
    }

    /** A hole's sizzle may begin in its last moments of glow, already dying away. */
    @Override public boolean canStartSilent() {return true;}

    @Override public void tick() {
        if (gone < 0) {
            double t = left.getAsDouble();
            Vec3 at = t > 0 ? where.get() : null;
            if (at != null) {
                x = at.x;
                y = at.y;
                z = at.z;
                volume = loudness(t);
                return;
            }
            // Gone cold, it has died away already; gone while it was still hot, it dies away now.
            if (!(t > 0) || volume <= 0) {
                finish();
                return;
            }
            gone = 0;
            was = volume;
        }
        if (++gone >= GONE) {
            finish();
            return;
        }
        float s = (float) Math.cos(gone / (float) GONE * Math.PI / 2);
        volume = was * s * s;
    }

    private void finish() {
        volume = 0;
        PLAYING.remove(this);
        stop();
    }
}
