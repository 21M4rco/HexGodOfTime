package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crown of Barrels' fire: a missile's blast and the backblast of its launch, as real Photon emitters ticked
 * and drawn by Photon's particle engine, with vanilla-side smoke and cinders under them.
 *
 * <p>A blast is big and brief. A white flash some fourteen blocks across, gone in a third of a second; a fireball
 * of flame thrown out and swelling as it slows, cooling from white through orange to a dull red; sparks flung out
 * and falling; then a pall of smoke that climbs and thins over several seconds, cinders that bounce where they
 * land, and a little fire left licking the crater. All of it bounded, and scaled down with the particle setting,
 * never switched off by it.
 */
final class ArsenalFx {
    private ArsenalFx() { }

    private static final ResourceLocation FLARE = HexGodOfStories.id("textures/particle/scepter_flare.png"),
        FIRE = HexGodOfStories.id("textures/particle/meteor_fire.png");
    private static final List<Emitter> ACTIVE = new ArrayList<>();
    /** Emitters alive at once, at most: a blast is five of them. */
    private static final int MOST = 40;

    /** Colour stops a mote passes through over its life, as 0xRRGGBB, with the alpha it has at each. */
    private static final int[] WHITE_HOT = {0xfffbe8, 0xffd27a, 0xff8a2a}, FLAME = {0xfff2c4, 0xffa23c, 0x8c2208},
        SPARK = {0xfffbe0, 0xffc861, 0xff6a1a}, GLOW = {0xffc978, 0xff7a24, 0x7a1c06};

    static void clear() {
        for (Emitter e : ACTIVE) e.remove(true);
        ACTIVE.clear();
    }

    /** How much of each effect the particle setting allows: all of it, some of it, a little of it. */
    static float amount() {
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        return status == ParticleStatus.ALL ? 1 : status == ParticleStatus.DECREASED ? .55f : .3f;
    }

    private static ParticleEmitter emitter(Vec3 at, int lifetime, ResourceLocation texture) {
        Minecraft mc = Minecraft.getInstance();
        ParticleEmitter emitter = new ParticleEmitter();
        var c = emitter.config;
        c.setDuration(1);
        c.setLooping(false);
        c.setMaxParticles(400);
        c.setParallelUpdate(false);
        c.setParallelRendering(false);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        c.setStartLifetime(NumberFunction.constant(lifetime));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartSize(new NumberFunction3(1, 1, 1));
        c.physics.setEnable(false);
        c.sizeOverLifetime.setEnable(false);
        c.material.setMaterial(new TextureMaterial(texture));
        c.material.setCull(false);
        c.material.setDepthTest(true);
        c.material.setDepthMask(false);
        c.material.getBlendMode().setDstColorFactor(GlStateManager.DestFactor.ONE);
        c.renderer.setBloomEffect(true);
        // A fireball is far wider than the point it is emitted from: never culled by that point alone.
        c.renderer.getCull().setEnable(false);
        ACTIVE.removeIf(e -> !e.isAlive());
        while (ACTIVE.size() >= MOST) ACTIVE.remove(0).remove(true);
        emitter.setLevel(mc.level);
        emitter.setPos(at.x, at.y, at.z);
        ACTIVE.add(emitter);
        mc.particleEngine.add(emitter);
        return emitter;
    }

    /**
     * One mote with a life of its own: from one size to another, eased out, through three colours, slowed by drag
     * and pulled by gravity (negative for heat that rises), fading as it goes.
     */
    private static final class Mote extends TileParticle {
        private final float from, to, drag, gravity;
        private final int[] colours;

        Mote(ParticleEmitter emitter, int life, float from, float to, int[] colours, float drag, float gravity) {
            super(emitter, emitter.config, emitter.getRandomSource());
            this.from = from;
            this.to = to;
            this.colours = colours;
            this.drag = drag;
            this.gravity = gravity;
            setLifetime(life);
            initialSize.set(from);
            setSize(new Vector3f(from));
            paint(0);
        }

        @Override public void tick() {
            mulInternalVelocity(drag);
            if (gravity != 0) addInternalVelocity(new Vector3f(0, -gravity, 0));
            super.tick();
            float t = Math.min(1, getT(0)), eased = 1 - (1 - t) * (1 - t);
            setSize(new Vector3f(from + (to - from) * eased));
            paint(t);
        }

        private void paint(float t) {
            float half = t < .5f ? t * 2 : (t - .5f) * 2;
            int a = colours[t < .5f ? 0 : 1], b = colours[t < .5f ? 1 : 2];
            float fade = (1 - t) * (1 - t * .5f);
            setColor(new Vector4f(mix(a >> 16, b >> 16, half), mix(a >> 8, b >> 8, half), mix(a, b, half), fade));
        }

        private static float mix(int a, int b, float t) {return ((a & 255) + ((b & 255) - (a & 255)) * t) / 255f;}
    }

    private static Mote mote(ParticleEmitter emitter, Vec3 offset, Vec3 velocity, int life, float from, float to, int[] colours,
                             float drag, float gravity) {
        Mote m = new Mote(emitter, life, from, to, colours, drag, gravity);
        m.setLocalPos(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z), true);
        m.setInternalVelocity(new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
        emitter.emitParticle(m);
        return m;
    }

    /** A direction at random, leaning up by {@code lift}: a blast on the ground throws little into it. */
    private static Vec3 outward(RandomSource random, double lift) {
        Vec3 d = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        if (d.lengthSqr() < 1e-6) d = new Vec3(0, 1, 0);
        d = d.normalize();
        return new Vec3(d.x, Math.abs(d.y) * (1 - lift) + lift * .8 + (d.y < 0 ? -.15 : 0), d.z).normalize();
    }

    /** A missile's blast at {@code at}. */
    static void explode(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource random = mc.level.random;
        float amount = amount();

        // The flash: white, wide and gone almost at once, over an orange glow that lingers a little longer.
        ParticleEmitter flash = emitter(at, 14, FLARE);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 7, 14, 7, WHITE_HOT, 1, 0);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 14, 9, 12, GLOW, 1, 0);

        // The fireball: flame thrown out and slowed, swelling as it cools.
        ParticleEmitter ball = emitter(at, 34, FIRE);
        int puffs = Math.max(10, Math.round(30 * amount));
        for (int i = 0; i < puffs; i++) {
            Vec3 d = outward(random, .35);
            double speed = .14 + random.nextDouble() * .30;
            mote(ball, d.scale(random.nextDouble() * .9), d.scale(speed), 16 + random.nextInt(16),
                1.1f + random.nextFloat() * .9f, 3.0f + random.nextFloat() * 1.8f, FLAME, .86f, -.006f);
        }
        // Its heart, which burns on in place a moment after the rest has been flung out.
        for (int i = 0; i < 4; i++)
            mote(ball, outward(random, .5).scale(.4), Vec3.ZERO, 20 + random.nextInt(8), 2.2f, 3.6f, FLAME, 1, -.01f);

        // Sparks: fast, small and pulled down, each drawing its own short arc.
        ParticleEmitter sparks = emitter(at, 40, FLARE);
        int count = Math.max(16, Math.round(56 * amount));
        for (int i = 0; i < count; i++) {
            Vec3 d = outward(random, .45);
            mote(sparks, Vec3.ZERO, d.scale(.45 + random.nextDouble() * .75), 18 + random.nextInt(22),
                .22f + random.nextFloat() * .12f, .05f, SPARK, .95f, .035f);
        }

        // Smoke, cinders and a little fire left behind: vanilla-side, so they settle into the world as it is drawn.
        int smoke = Math.max(6, Math.round(22 * amount));
        for (int i = 0; i < smoke; i++) {
            Vec3 d = outward(random, .6);
            Particle puff = create(HexGodOfStories.ASH.get(), at.add(d.scale(random.nextDouble() * 2.2)),
                d.x * .06, .03 + random.nextDouble() * .07, d.z * .06);
            if (puff == null) continue;
            float grey = .16f + random.nextFloat() * .12f;
            puff.setColor(grey, grey * .96f, grey * .92f);
            puff.setLifetime(90 + random.nextInt(70));
        }
        int cinders = Math.max(8, Math.round(34 * amount));
        for (int i = 0; i < cinders; i++) {
            Vec3 d = outward(random, .5);
            create(HexGodOfStories.CINDER.get(), at.add(d.scale(.6)), d.x * (.25 + random.nextDouble() * .35),
                .2 + random.nextDouble() * .35, d.z * (.25 + random.nextDouble() * .35));
        }
        Vfx.bloom(-1, at, Vec3.ZERO, 36, (origin, look, progress) -> {
            int flames = Math.round((1 - progress) * 3 * amount + random.nextFloat());
            for (int i = 0; i < flames; i++) {
                Particle flame = create(HexGodOfStories.METEOR_FIRE.get(), origin.add((random.nextDouble() - .5) * 4.5, random.nextDouble() * .6 - .8,
                    (random.nextDouble() - .5) * 4.5), 0, .02 + random.nextDouble() * .03, 0);
                if (flame != null) flame.setLifetime(10 + random.nextInt(8));
            }
        });
    }

    /**
     * The backblast of a missile leaving: flame and white smoke thrown back out of its tail at {@code at}, along
     * {@code back}.
     */
    static void backblast(Vec3 at, Vec3 back) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource random = mc.level.random;
        float amount = amount();
        ParticleEmitter burst = emitter(at, 12, FIRE);
        for (int i = 0; i < Math.max(4, Math.round(10 * amount)); i++) {
            Vec3 d = back.add(random.nextGaussian() * .35, random.nextGaussian() * .35, random.nextGaussian() * .35).normalize();
            mote(burst, Vec3.ZERO, d.scale(.12 + random.nextDouble() * .18), 7 + random.nextInt(5), .5f, 1.4f, FLAME, .82f, -.004f);
        }
        for (int i = 0; i < Math.max(4, Math.round(14 * amount)); i++) {
            Vec3 d = back.add(random.nextGaussian() * .5, random.nextGaussian() * .5, random.nextGaussian() * .5).normalize();
            Particle puff = create(HexGodOfStories.ASH.get(), at.add(d.scale(random.nextDouble() * .6)),
                d.x * (.08 + random.nextDouble() * .1), d.y * (.08 + random.nextDouble() * .1) + .01, d.z * (.08 + random.nextDouble() * .1));
            if (puff == null) continue;
            float grey = .78f + random.nextFloat() * .14f;
            puff.setColor(grey, grey, grey * .98f);
            puff.setLifetime(50 + random.nextInt(40));
        }
    }

    /**
     * A vanilla-side particle made directly, for its colour and life to be set: out to {@value #REACH} blocks, where
     * the world's own would stop at 32, and thinned as the particle setting asks.
     */
    static Particle create(ParticleOptions type, Vec3 at, double vx, double vy, double vz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        ParticleStatus status = mc.options.particles().get();
        RandomSource random = mc.level.random;
        if (status == ParticleStatus.MINIMAL && random.nextInt(3) != 0 || status == ParticleStatus.DECREASED && random.nextInt(2) != 0)
            return null;
        if (mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(at) > REACH * REACH) return null;
        return mc.particleEngine.createParticle(type, at.x, at.y, at.z, vx, vy, vz);
    }

    private static final double REACH = 160;
}
