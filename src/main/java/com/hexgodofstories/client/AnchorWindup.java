package com.hexgodofstories.client;

import com.hexgodofstories.client.leviathan.LeviathanEffects;
import com.hexgodofstories.entity.IllusionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Anchor Being's copy, struck, winding up to its burst: the power in it cutting out with a low groan, a charge that
 * climbs the whole length of the fuse, a heartbeat coming faster and faster, green light and dust drawn in to it, a
 * core swelling at its chest, and the ground round it trembling, blocks jumping in place, harder and harder. Only seen and heard; the server keeps the
 * fuse ({@link IllusionEntity#burstAt}) and nothing here sends anything back.
 */
public final class AnchorWindup {
    private AnchorWindup() {}

    /** Heard out to here, and felt underfoot to here; a grand one much further. */
    private static final double HEARD = 96, GRAND_HEARD = 160, FELT = 24, GRAND_FELT = 48;
    /** Where through the fuse the heart beats: closer and closer together. */
    private static final float[] BEATS = {0, .3f, .5f, .64f, .75f, .84f, .9f, .95f};

    public static void tick(IllusionEntity e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float t = e.laughAge(0);
        boolean grand = e.grand();
        int fuse = IllusionEntity.burstAt(grand), age = (int) t, last = e.windup;
        if (t < 0 || age <= last || age >= fuse) return;
        e.windup = age;
        Vec3 core = e.position().add(0, e.getBbHeight() * .6, 0);
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(core);
        if (d > (grand ? GRAND_HEARD : HEARD)) return;
        float progress = age / (float) fuse, before = last < 0 ? -1 : last / (float) fuse;
        if (last < 0) {
            play(SoundEvents.BEACON_DEACTIVATE, core, grand ? 4 : 2, .5f);
            // The warden's charge, stretched to fill the fuse: deeper and longer for a grand one.
            play(SoundEvents.WARDEN_SONIC_CHARGE, core, grand ? 6 : 3, Math.max(.5f, 34f / fuse));
            if (grand) play(SoundEvents.PORTAL_TRIGGER, core, 6, .5f);
            ArsenalClient.tremor(e.position(), grand ? 12 : 7, fuse - age);
        }
        float beat = -1;
        for (float b : BEATS) if (b > before && b <= progress) beat = b;
        if (beat >= 0) play(SoundEvents.WARDEN_HEARTBEAT, core, grand ? 5 : 2.5f, .7f + .6f * beat);
        ArsenalFx.anchorGather(core, e.position(), progress, grand, age);
        double felt = grand ? GRAND_FELT : FELT;
        if (d < felt) LeviathanEffects.quake((float) ((.15 + 1.6 * progress * progress) * (grand ? 1.4 : 1) * (1 - d / felt)));
    }

    private static void play(SoundEvent sound, Vec3 at, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.PLAYERS, volume, pitch,
            SoundInstance.createUnseededRandom(), at.x, at.y, at.z));
    }
}
