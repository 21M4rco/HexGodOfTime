package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import com.hexgodofstories.warping.leviathan.LeviathanSegmentController;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * Client presentation for a creature whose real weapon is uncertainty.
 *
 * <p>Two things are deliberately restrained. Particles are budgeted and fall off with distance,
 * because burying a hundred and fifty block silhouette under spray defeats the point of having
 * built it. And the ambient dread is a sub-degree camera tremor rather than a sound cue, so a
 * player who is being stalked feels it before they can name it.
 */
public final class LeviathanEffects {
    private static float shake, shakeDecay = 0.86f;
    private static float tremor;
    private static long tremorSeed;

    private LeviathanEffects() { }

    public static void clear() { shake = 0; tremor = 0; }

    /** Handles one broadcast presentation event. */
    public static void receive(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String effect = data.getString("effect");
        Vec3 at = new Vec3(data.getDouble("x"), data.getDouble("y"), data.getDouble("z"));
        float power = data.getFloat("power");
        double distance = mc.player.position().distanceTo(at);
        float falloff = (float) Mth.clamp(1.0 - distance / (90.0 + power * 70.0), 0.0, 1.0);
        if (falloff <= 0.001f) return;

        switch (effect) {
            case "breach" -> {
                addShake(1.35f * power * falloff, 0.90f);
                ring(at, ParticleTypes.SPLASH, (int) (70 * power * falloff), 7.0 * power, 0.55, 1.35);
                ring(at, ParticleTypes.CLOUD, (int) (26 * power * falloff), 9.0 * power, 0.25, 0.5);
                column(at, (int) (30 * power * falloff), 5.0 * power);
            }
            case "water_impact" -> {
                addShake(2.2f * power * falloff, 0.93f);
                ring(at, ParticleTypes.SPLASH, (int) (110 * power * falloff), 12.0 * power, 0.8, 1.7);
                ring(at, ParticleTypes.CLOUD, (int) (44 * power * falloff), 15.0 * power, 0.3, 0.7);
                column(at, (int) (54 * power * falloff), 9.0 * power);
            }
            case "void_scream" -> {
                addShake(2.6f * falloff, 0.955f);
                ring(at, ParticleTypes.BUBBLE, (int) (90 * falloff), 16.0, 0.9, 0.2);
                ring(at, ParticleTypes.SOUL_FIRE_FLAME, (int) (18 * falloff), 10.0, 0.35, 0.1);
            }
            // Both rings follow the radius their pattern actually holds; a six block ring
            // around a thirty block cage is a puff of bubbles in the middle of nothing.
            case "crush" -> { addShake(1.1f * falloff, 0.9f); ring(at, ParticleTypes.BUBBLE, (int) (54 * falloff), 13.0, 0.5, 0.2); }
            case "vortex" -> ring(at, ParticleTypes.BUBBLE, (int) (48 * falloff), 32.0, 0.6, 0.35);
            case "shake" -> addShake(0.9f * power, 0.8f);
            case "bump" -> { addShake(0.45f * falloff, 0.82f); ring(at, ParticleTypes.SPLASH, (int) (16 * falloff), 2.0, 0.3, 0.5); }
            case "charge" -> addShake(0.35f * falloff, 0.985f);
            case "death" -> addShake(1.6f * falloff, 0.975f);
            default -> { }
        }
    }

    public static void scepterRecoil() { addShake(1.15f, .82f); }
    /** A tap barely kicks; a full charge shoves the view. */
    public static void scepterRecoil(float strength) { addShake(1.15f * strength, .80f); }

    private static void addShake(float amount, float decay) {
        if (amount <= shake && decay <= shakeDecay) return;
        shake = Math.max(shake, Math.min(amount, 4.0f));
        shakeDecay = Math.max(shakeDecay, decay);
        tremorSeed = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private static void ring(Vec3 at, ParticleOptions type, int count, double radius, double speed, double lift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || count <= 0) return;
        RandomSource random = mc.level.random;
        int budget = Math.min(count, 160);
        for (int i = 0; i < budget; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double r = radius * Math.sqrt(random.nextDouble());
            double x = at.x + Math.cos(angle) * r, z = at.z + Math.sin(angle) * r;
            mc.level.addParticle(type, x, at.y + random.nextDouble() * 1.5, z,
                Math.cos(angle) * speed * 0.6, lift * (0.4 + random.nextDouble()), Math.sin(angle) * speed * 0.6);
        }
    }

    private static void column(Vec3 at, int count, double spread) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || count <= 0) return;
        RandomSource random = mc.level.random;
        int budget = Math.min(count, 80);
        for (int i = 0; i < budget; i++) {
            mc.level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP,
                at.x + (random.nextDouble() - 0.5) * spread, at.y - random.nextDouble() * 6, at.z + (random.nextDouble() - 0.5) * spread,
                0, 0.4 + random.nextDouble() * 0.6, 0);
        }
    }

    /** Called every client tick. Also produces the standing tremor of something large and near. */
    public static void tick() {
        shake *= shakeDecay;
        if (shake < 0.01f) { shake = 0; shakeDecay = 0.86f; }

        Minecraft mc = Minecraft.getInstance();
        tremor = 0;
        if (mc.level == null || mc.player == null) return;
        double best = Double.MAX_VALUE;
        AbyssalPilgrimEntity nearest = null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbyssalPilgrimEntity pilgrim)) continue;
            double d = Double.MAX_VALUE;
            for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i++) d = Math.min(d, pilgrim.segments().segment(i).distanceToSqr(mc.player.position()));
            if (d < best) { best = d; nearest = pilgrim; }
        }
        if (nearest == null) return;
        double distance = Math.sqrt(best);
        if (distance > 70) return;
        // Pressure, not sound. It gets stronger the closer the body passes.
        float weight = (float) (1.0 - distance / 70.0);
        tremor = weight * weight * (nearest.state().hidden() ? 0.34f : 0.16f);
    }

    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        float total = shake + tremor;
        if (total <= 0.001f) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float t = (float) (mc.level.getGameTime() - tremorSeed) + (float) event.getPartialTick();
        float amplitude = Math.min(total, 5.0f);
        event.setYaw(event.getYaw() + Mth.sin(t * 1.9f) * amplitude * 0.55f);
        event.setPitch(event.getPitch() + Mth.cos(t * 2.7f) * amplitude * 0.42f);
        event.setRoll(event.getRoll() + Mth.sin(t * 1.3f) * amplitude * 0.8f);
    }

    /** Body passing just under the surface disturbs the water above it. */
    public static void surfaceWake(AbyssalPilgrimEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entity.viewerDistance() > 120) return;
        double line = entity.surfaceY();
        RandomSource random = mc.level.random;
        for (int i = 0; i < LeviathanSegmentController.SEGMENTS; i += 3) {
            Vec3 segment = entity.segments().segment(i);
            if (segment.y > line || segment.y < line - 9) continue;
            if (random.nextFloat() > 0.34f) continue;
            double r = LeviathanSegmentController.radius(i);
            mc.level.addParticle(ParticleTypes.SPLASH,
                segment.x + (random.nextDouble() - 0.5) * r * 2, line + 0.1, segment.z + (random.nextDouble() - 0.5) * r * 2,
                0, 0.12 + random.nextDouble() * 0.2, 0);
        }
    }
}
