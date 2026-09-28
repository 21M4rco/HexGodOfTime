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
    private static final int MOST = 80;

    /** Colour stops a mote passes through over its life, as 0xRRGGBB, with the alpha it has at each. */
    private static final int[] WHITE_HOT = {0xfffbe8, 0xffd27a, 0xff8a2a}, FLAME = {0xfff2c4, 0xffa23c, 0x8c2208},
        SPARK = {0xfffbe0, 0xffc861, 0xff6a1a}, GLOW = {0xffc978, 0xff7a24, 0x7a1c06};
    /** The same fire, green: Anchor Being's copy bursting. */
    private static final int[] WHITE_HOT_G = {0xf4fff0, 0x9dff9a, 0x2fd96a}, FLAME_G = {0xeaffdc, 0x5cf07a, 0x0f6a2a},
        SPARK_G = {0xf4fff0, 0x8dff8a, 0x2ac45a}, GLOW_G = {0x9dffa0, 0x3fd06a, 0x0c4a1c};

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
    static void explode(Vec3 at) {explode(at, 1, false);}

    /**
     * The same blast, {@code scale} times the size and green if asked: Anchor Being's copy (a little bigger than a
     * missile) and, past twice the size, its secret form, which also throws up a stem of fire under a mushroom cap.
     */
    static void explode(Vec3 at, float scale, boolean green) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource random = mc.level.random;
        float amount = amount(), more = Math.min(3, scale);
        int[] hot = green ? WHITE_HOT_G : WHITE_HOT, flame = green ? FLAME_G : FLAME, spark = green ? SPARK_G : SPARK, glow = green ? GLOW_G : GLOW;

        // The flash: white, wide and gone almost at once, over a glow that lingers a little longer.
        ParticleEmitter flash = emitter(at, 14, FLARE);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 7, 14 * scale, 7 * scale, hot, 1, 0);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 14, 9 * scale, 12 * scale, glow, 1, 0);

        // The fireball: flame thrown out and slowed, swelling as it cools.
        ParticleEmitter ball = emitter(at, Math.round(34 * (1 + (scale - 1) * .4f)), FIRE);
        int puffs = Math.min(380, Math.max(10, Math.round(30 * amount * more)));
        for (int i = 0; i < puffs; i++) {
            Vec3 d = outward(random, .35);
            double speed = (.14 + random.nextDouble() * .30) * scale;
            mote(ball, d.scale(random.nextDouble() * .9 * scale), d.scale(speed), Math.round((16 + random.nextInt(16)) * (1 + (scale - 1) * .3f)),
                (1.1f + random.nextFloat() * .9f) * scale, (3.0f + random.nextFloat() * 1.8f) * scale, flame, .86f, -.006f);
        }
        // Its heart, which burns on in place a moment after the rest has been flung out.
        for (int i = 0; i < 4; i++)
            mote(ball, outward(random, .5).scale(.4 * scale), Vec3.ZERO, 20 + random.nextInt(8), 2.2f * scale, 3.6f * scale, flame, 1, -.01f);

        // Sparks: fast, small and pulled down, each drawing its own short arc.
        ParticleEmitter sparks = emitter(at, 40, FLARE);
        int count = Math.min(380, Math.max(16, Math.round(56 * amount * more)));
        for (int i = 0; i < count; i++) {
            Vec3 d = outward(random, .45);
            mote(sparks, Vec3.ZERO, d.scale((.45 + random.nextDouble() * .75) * Math.sqrt(scale)), 18 + random.nextInt(22),
                (.22f + random.nextFloat() * .12f) * (float) Math.sqrt(scale), .05f, spark, .95f, .035f);
        }

        if (scale > 2) {
            // A small nuke: a stem of fire climbing out of the blast, and the cap it spreads into overhead.
            ParticleEmitter stem = emitter(at, 110, FIRE);
            for (int i = 0; i < Math.round(90 * amount); i++) {
                Vec3 offset = new Vec3(random.nextGaussian() * scale * .35, random.nextDouble() * scale * .5, random.nextGaussian() * scale * .35);
                mote(stem, offset, new Vec3(0, (.12 + random.nextDouble() * .16) * scale, 0), 60 + random.nextInt(40),
                    1.6f * scale * .6f, 3.4f * scale * .6f, flame, .965f, -.003f);
            }
            ParticleEmitter cap = emitter(at.add(0, scale * 3.2, 0), 120, FIRE);
            for (int i = 0; i < Math.round(140 * amount); i++) {
                Vec3 d = new Vec3(random.nextGaussian(), Math.abs(random.nextGaussian()) * .35, random.nextGaussian()).normalize();
                mote(cap, d.scale(scale * (.6 + random.nextDouble() * 1.4)), d.scale(.06 * scale).add(0, .05 * scale, 0), 70 + random.nextInt(50),
                    2.2f * scale * .6f, 5.5f * scale * .6f, flame, .95f, -.004f);
            }
            ParticleEmitter halo = emitter(at, 30, FLARE);
            mote(halo, Vec3.ZERO, Vec3.ZERO, 30, 20 * scale, 40 * scale, glow, 1, 0);
        }

        // Smoke, cinders and a little fire left behind: vanilla-side, so they settle into the world as it is drawn.
        int smoke = Math.max(6, Math.round(22 * amount * more));
        for (int i = 0; i < smoke; i++) {
            Vec3 d = outward(random, .6);
            Particle puff = create(HexGodOfStories.ASH.get(), at.add(d.scale(random.nextDouble() * 2.2 * scale)),
                d.x * .06, .03 + random.nextDouble() * .07, d.z * .06);
            if (puff == null) continue;
            float grey = .16f + random.nextFloat() * .12f;
            if (green) puff.setColor(grey * .7f, grey * 1.25f, grey * .8f);
            else puff.setColor(grey, grey * .96f, grey * .92f);
            puff.setLifetime(90 + random.nextInt(70));
        }
        int cinders = Math.max(8, Math.round(34 * amount * more));
        for (int i = 0; i < cinders; i++) {
            Vec3 d = outward(random, .5);
            Particle cinder = create(HexGodOfStories.CINDER.get(), at.add(d.scale(.6 * scale)), d.x * (.25 + random.nextDouble() * .35) * scale,
                (.2 + random.nextDouble() * .35) * Math.sqrt(scale), d.z * (.25 + random.nextDouble() * .35) * scale);
            if (cinder != null && green) cinder.setColor(.45f, 1f, .5f);
        }
        Vfx.bloom(-1, at, Vec3.ZERO, 36, (origin, look, progress) -> {
            int flames = Math.round((1 - progress) * 3 * amount * more + random.nextFloat());
            for (int i = 0; i < flames; i++) {
                Particle fire = create(HexGodOfStories.METEOR_FIRE.get(), origin.add((random.nextDouble() - .5) * 4.5 * scale, random.nextDouble() * .6 - .8,
                    (random.nextDouble() - .5) * 4.5 * scale), 0, .02 + random.nextDouble() * .03, 0);
                if (fire != null) {fire.setLifetime(10 + random.nextInt(8)); if (green) fire.setColor(.45f, 1f, .5f);}
            }
        });
    }

    /**
     * Anchor Being's copy winding up, each tick of its fuse: green light drawn in from all round, thicker and faster as
     * it burns down; a core at its chest swelling brighter; dust dragged in along the ground to its feet.
     */
    static void anchorGather(Vec3 core, Vec3 feet, float progress, boolean grand, int age) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource random = mc.level.random;
        float amount = amount(), size = grand ? 2 : 1;
        if (age % 2 == 0) {
            ParticleEmitter in = emitter(core, 24, FLARE);
            int n = Math.round((5 + 20 * progress) * amount * size);
            for (int i = 0; i < n; i++) {
                Vec3 from = outward(random, 0).scale((3 + random.nextDouble() * 4) * size * (1.1 - .5 * progress));
                int life = 10 + random.nextInt(12);
                mote(in, from, from.scale(-1.0 / life), life, (.18f + random.nextFloat() * .2f) * size, .04f, random.nextInt(3) == 0 ? SPARK_G : GLOW_G, 1, 0);
            }
        }
        ParticleEmitter heart = emitter(core, 3, FLARE);
        float swell = (.4f + 2.2f * progress * progress) * size * (1 + .15f * (float) Math.sin(age * 1.3));
        mote(heart, Vec3.ZERO, Vec3.ZERO, 3, swell, swell * .8f, progress > .8f ? WHITE_HOT_G : GLOW_G, 1, 0);
        int dust = Math.round((1 + 4 * progress) * amount * size);
        for (int i = 0; i < dust; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = (3 + random.nextDouble() * 4) * size;
            Vec3 p = feet.add(Math.cos(a) * r, .1, Math.sin(a) * r);
            Particle d = create(HexGodOfStories.ASH.get(), p, -Math.cos(a) * r / 22, .01 + random.nextDouble() * .02, -Math.sin(a) * r / 22);
            if (d == null) continue;
            float g = .16f + random.nextFloat() * .1f;
            d.setColor(g * .85f, g * 1.1f, g * .85f);
            d.setLifetime(22);
            d.scale(1.5f + random.nextFloat() * 1.5f);
        }
    }

    /**
     * Anchor Being's copy bursting: slower and heavier than a missile, and it takes its time. A long green flash; a
     * shock of fire rolling out along the ground; a fireball that goes on swelling and climbing for three seconds (six
     * for a grand one), a fresh roll of fire out of its heart every few ticks, each slower than the last; smoke that
     * thickens as the fire dies and hangs there long after; and, past twice the size, a stem climbing under it into a
     * cap: a small green nuke.
     */
    static void anchorBlast(Vec3 at, float scale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource random = mc.level.random;
        float amount = amount(), more = Math.min(3, scale);
        boolean nuke = scale > 2;

        ParticleEmitter flash = emitter(at, 26, FLARE);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 12, 16 * scale, 9 * scale, WHITE_HOT_G, 1, 0);
        mote(flash, Vec3.ZERO, Vec3.ZERO, 26, 10 * scale, 18 * scale, GLOW_G, 1, 0);

        ParticleEmitter shock = emitter(at, 44, FIRE);
        int ring = Math.min(360, Math.round(70 * amount * more));
        for (int i = 0; i < ring; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            Vec3 d = new Vec3(Math.cos(a), .04 + random.nextDouble() * .1, Math.sin(a));
            mote(shock, d.scale(.5 * scale), d.scale((.26 + random.nextDouble() * .14) * scale), 24 + random.nextInt(16),
                .8f * scale, 2.8f * scale, FLAME_G, .9f, -.002f);
        }
        ParticleEmitter sparks = emitter(at, 50, FLARE);
        for (int i = 0; i < Math.min(360, Math.round(60 * amount * more)); i++) {
            Vec3 d = outward(random, .45);
            mote(sparks, Vec3.ZERO, d.scale((.4 + random.nextDouble() * .7) * Math.sqrt(scale)), 26 + random.nextInt(24),
                (.24f + random.nextFloat() * .12f) * (float) Math.sqrt(scale), .05f, SPARK_G, .95f, .03f);
        }

        int ticks = nuke ? 120 : 60;
        Vfx.bloom(-1, at, Vec3.ZERO, ticks, (origin, look, progress) -> {
            int age = Math.round(progress * ticks);
            Vec3 heart = at.add(0, (nuke ? 2.8 : 1.1) * scale * Math.sqrt(progress), 0);
            if (age % 3 == 0 && progress < .7f) {
                ParticleEmitter swell = emitter(heart, 70, FIRE);
                int n = Math.round(14 * amount * more);
                for (int i = 0; i < n; i++) {
                    Vec3 d = outward(random, nuke ? .15 : .3);
                    mote(swell, d.scale(random.nextDouble() * .6 * scale), d.scale((.05 + random.nextDouble() * .09) * scale * (1 - progress * .6)),
                        32 + random.nextInt(26), (1.4f + random.nextFloat() * .8f) * scale * .8f, (3.6f + random.nextFloat() * 1.8f) * scale * .8f,
                        FLAME_G, .93f, -.005f);
                }
            }
            if (nuke && age % 4 == 0 && progress < .8f) {
                ParticleEmitter stem = emitter(at, 70, FIRE);
                for (int i = 0; i < Math.round(8 * amount); i++)
                    mote(stem, new Vec3(random.nextGaussian() * scale * .3, random.nextDouble() * (heart.y - at.y), random.nextGaussian() * scale * .3),
                        new Vec3(0, (.1 + random.nextDouble() * .1) * scale * .4, 0), 40 + random.nextInt(30), .7f * scale, 1.5f * scale, FLAME_G, .95f, -.003f);
            }
            int puffs = Math.round((progress < .3f ? 1 : 3) * amount * more + random.nextFloat());
            for (int i = 0; i < puffs; i++) {
                Vec3 p = heart.add(random.nextGaussian() * scale * 1.2, random.nextGaussian() * scale * .6, random.nextGaussian() * scale * 1.2);
                Particle smoke = create(HexGodOfStories.ASH.get(), p, random.nextGaussian() * .02, .02 + random.nextDouble() * .04, random.nextGaussian() * .02);
                if (smoke == null) continue;
                float g = .12f + random.nextFloat() * .1f;
                smoke.setColor(g * .8f, g * 1.1f, g * .85f);
                smoke.setLifetime(160 + random.nextInt(120));
                smoke.scale(3f + random.nextFloat() * 3f * Math.min(2, scale));
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
