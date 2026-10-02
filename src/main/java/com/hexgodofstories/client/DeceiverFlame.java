package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Deceiver alight (after Beric Dondarrion's sword): held in a transformed hand, the blade slowly catches, the fire
 * creeping up from the guard to the point over a couple of seconds, and burns for as long as it stays in that hand.
 * Switching away, dropping it, dismissing it or the transformation ending puts it out.
 *
 * <p>The fire is drawn on the blade itself, in the weapon's own space, so it is wherever the blade is: either hand
 * view, every move and stance. It is tongues of flame along the blade, each rising the way the world's up is (not
 * the blade's), licking up, stretching, tearing away and fading as the next comes up under it, swaying as it goes:
 * red at the edges, orange through the body, a white-hot core low in it, and blue where it touches the steel. Light,
 * so it is only ever added to what is behind it. In the world around a blade in sight: embers and smoke rising off
 * it, the odd spit of flame, and its crackle.
 */
public final class DeceiverFlame {
    private DeceiverFlame() {}

    /** How long the blade takes to catch, guard to point. */
    static final int IGNITE = 50;
    /** Where the blade runs, in weapon space (tools/generate_blades.py: the guard to the point). */
    private static final float FOOT = .28f, POINT = 1.52f;
    private static final int TONGUES = 34, LICKS = 14;
    private static final int RED = 0xff4a12, ORANGE = 0xff9628, CORE = 0xfff0c4, BLUE = 0x3a62ff, HEAT = 0xff7a24;

    /** Since when each player's blade has been alight, by id. */
    private static final Map<Integer, Long> LIT = new HashMap<>();
    /** Where each burning blade was drawn in the world this frame (third person), for its embers. */
    private record Drawn(Vec3 foot, Vec3 point, long tick) {}
    private static final Map<Integer, Drawn> DRAWN = new HashMap<>();
    private static final Map<Integer, Long> CRACKLE = new HashMap<>();

    public static void clear() {LIT.clear(); DRAWN.clear(); CRACKLE.clear();}

    /** Whether this player's Deceiver burns: transformed, with it in the main hand. */
    private static boolean alight(Player p) {
        return !p.isSpectator() && !ClientState.hidden(p) && ClientState.data(p.getId()).getBoolean("ascended")
            && p.getMainHandItem().is(HexGodOfStories.DECEIVER.get());
    }

    /** How far up the blade it has caught, 0 to 1 (0 when not alight at all). */
    static float caught(int id, float partial) {
        Long since = LIT.get(id);
        return since == null ? 0 : Mth.clamp((float) (ClientState.time(partial) - since) / IGNITE, 0, 1);
    }

    // ------------------------------------------------------------------ drawing

    /** From WeaponRenderer, in weapon space (the grip at the origin, the blade up +Y): the fire, if this blade burns. */
    static void draw(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers) {
        boolean first = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        boolean third = context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        var mc = Minecraft.getInstance();
        if (!(first || third) || mc.level == null || !stack.hasTag() || !stack.getTag().hasUUID("conjurer")) return;
        UUID owner = stack.getTag().getUUID("conjurer");
        Player holder = mc.level.getPlayerByUUID(owner);
        if (holder == null || !LIT.containsKey(holder.getId())) return;
        float partial = mc.getFrameTime();
        float caught = caught(holder.getId(), partial);
        if (caught <= 0) return;
        double time = ClientState.time(partial);
        Matrix4f m = pose.last().pose();
        // The world's up, and the eye, in weapon space: the hand is drawn in the camera's own frame (first person) and
        // the world in front of it (third), so both come from the camera's axes.
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
        Matrix3f inverse = new Matrix3f().set(m).invert();
        Vector3f rise = inverse.transform(new Vector3f(-left.y(), up.y(), -look.y()));
        if (rise.lengthSquared() < 1e-9f) return;
        rise.normalize();
        Vector3f eye = new Matrix4f(m).invert().transformPosition(new Vector3f());
        if (third) {
            Vec3 foot = ScepterFx.world(m, 0, FOOT, 0), point = ScepterFx.world(m, 0, FOOT + (POINT - FOOT) * caught, 0);
            if (foot != null) DRAWN.put(holder.getId(), new Drawn(foot, point, ClientState.now()));
        }
        VertexConsumer out = buffers.getBuffer(Types.light(flameSheet()));
        float front = FOOT + (POINT - FOOT) * caught;
        // Blue licks hugging the steel, low and quick, strongest toward the guard.
        for (int i = 0; i < LICKS; i++) {
            float along = (i + .5f) / LICKS, y = FOOT + (POINT - FOOT) * along;
            float grown = Mth.clamp((front - y) / .12f, 0, 1);
            if (grown <= 0) continue;
            double s = frac(time * .11 + i * .618);
            float h = (float) (.08 + .06 * s) * grown * (1.3f - .6f * along);
            float a = (float) Math.pow(Math.sin(Math.PI * s), .6) * .8f * grown * (1.2f - .7f * along);
            tongue(out, pose, eye, rise, new Vector3f(0, y, 0), h, h * .75f, 0, BLUE, a);
        }
        // The tongues, spread unevenly along the blade and overlapping into one body of fire: each licks up off a blue
        // root, stretches, tears away and fades as the next comes up under it. The tallest are toward the point,
        // which carries the fire of the whole blade above it.
        for (int i = 0; i < TONGUES; i++) {
            double r1 = hash(i + .1), r2 = hash(i + .2), r3 = hash(i + .3), r4 = hash(i + .4);
            float along = (float) ((i + .5 + (r1 - .5) * .9) / TONGUES), y = FOOT + (POINT - FOOT) * along;
            float grown = Mth.clamp((front - y) / .22f, 0, 1);
            if (grown <= 0) continue;
            double s = frac(time * (.05 + .035 * r2) + r3);
            double reach = (.22 + .3 * r4) * (1 + .9 * along * along);
            float tall = (float) (reach * (.5 + .75 * s) * (.85 + .15 * Math.sin(time * .9 + i * 2.3))) * (.35f + .65f * grown);
            float fade = (float) Math.pow(Math.sin(Math.PI * s), .6) * grown;
            float sway = (float) (.3 * Math.sin(time * .17 + i * 1.7) + .14 * Math.sin(time * .47 + i * 3.1) + (r1 - .5) * .3);
            Vector3f foot = new Vector3f((float) (r2 - .5) * .07f, y, (float) (r3 - .5) * .05f);
            foot.add(new Vector3f(rise).mul((float) (s * s) * tall * .3f + .04f));
            float wide = (float) (.45 + .28 * r1);
            tongue(out, pose, eye, rise, foot, tall, tall * wide, sway, RED, .3f * fade);
            tongue(out, pose, eye, rise, foot, tall * .8f, tall * wide * .62f, sway * .8f, ORANGE, .36f * fade);
            tongue(out, pose, eye, rise, foot, tall * .5f, tall * wide * .28f, sway * .5f, CORE, .4f * fade);
        }
        // The steel itself glowing with it, along what has caught.
        VertexConsumer glow = buffers.getBuffer(Types.light(glowSheet()));
        Vector3f axis = new Vector3f(0, 1, 0), mid = new Vector3f(0, (FOOT + front) / 2, 0);
        Vector3f toward = new Vector3f(eye).sub(mid);
        Vector3f side = new Vector3f(axis).cross(toward);
        if (side.lengthSquared() > 1e-9f) {
            side.normalize().mul(.11f);
            float flicker = (float) (.8 + .2 * Math.sin(time * 1.7) * Math.sin(time * .63));
            quad(glow, pose, new Vector3f(0, front + .08f, 0).add(side), new Vector3f(0, front + .08f, 0).sub(side),
                new Vector3f(0, FOOT - .06f, 0).sub(side), new Vector3f(0, FOOT - .06f, 0).add(side), HEAT, .4f * caught * flicker);
        }
    }

    /** One tongue of flame: from `foot`, up along `rise` for `tall`, `wide` across, facing the eye, its tip swayed. */
    private static void tongue(VertexConsumer out, PoseStack pose, Vector3f eye, Vector3f rise, Vector3f foot, float tall, float wide,
                               float sway, int colour, float alpha) {
        if (alpha <= .01f || tall <= .005f) return;
        Vector3f toward = new Vector3f(eye).sub(foot);
        Vector3f side = new Vector3f(rise).cross(toward);
        if (side.lengthSquared() < 1e-9f) return;
        side.normalize();
        Vector3f tip = new Vector3f(foot).add(new Vector3f(rise).mul(tall)).add(new Vector3f(side).mul(sway * tall));
        Vector3f half = new Vector3f(side).mul(wide);
        // A little below the foot, so the round base of the sheet sits on the steel.
        Vector3f base = new Vector3f(foot).sub(new Vector3f(rise).mul(tall * .08f));
        quad(out, pose, new Vector3f(tip).sub(half), new Vector3f(tip).add(half), new Vector3f(base).add(half), new Vector3f(base).sub(half), colour, alpha);
    }

    /** Corners in order: tip left, tip right, base right, base left (the sheet's top at the tip). */
    private static void quad(VertexConsumer out, PoseStack pose, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int colour, float alpha) {
        Vector3f[] corners = {a, b, c, d};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        int r = colour >> 16 & 255, g = colour >> 8 & 255, bl = colour & 255, al = Mth.clamp((int) (alpha * 255), 0, 255);
        for (int i = 0; i < 4; i++)
            out.vertex(pose.last().pose(), corners[i].x, corners[i].y, corners[i].z).color(r, g, bl, al).uv(uv[i][0], uv[i][1])
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(pose.last().normal(), 0, 1, 0).endVertex();
    }

    private static double frac(double v) {return v - Math.floor(v);}
    /** A fixed scatter in [0, 1) for each tongue: where it sits, how it grows, how wide. */
    private static double hash(double k) {return frac(Math.sin(k * 12.9898) * 43758.5453);}

    // ------------------------------------------------------------------ the world round it

    /** Every client tick: who is alight, and the embers, smoke, spits and crackle off every burning blade in sight. */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {clear(); return;}
        long now = ClientState.now();
        var random = mc.level.random;
        for (Player p : mc.level.players()) {
            int id = p.getId();
            if (!alight(p)) {LIT.remove(id); DRAWN.remove(id); continue;}
            if (!LIT.containsKey(id)) {
                LIT.put(id, now);
                mc.level.playLocalSound(p.getX(), p.getEyeY(), p.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, .55f, .7f, false);
            }
            if (p.position().distanceToSqr(mc.player.position()) > 48 * 48) continue;
            float caught = caught(id, 1);
            Vec3 foot, point;
            Drawn drawn = DRAWN.get(id);
            if (p == mc.player && mc.options.getCameraType().isFirstPerson()) {
                // Our own, in first person: where the blade stands in the hand's view, low on the right.
                Vec3 eye = p.getEyePosition(), view = p.getViewVector(1), across = new Vec3(-view.z, 0, view.x).normalize();
                foot = eye.add(view.scale(.75)).add(across.scale(.42)).add(0, -.42, 0);
                point = eye.add(view.scale(1.15)).add(across.scale(.62)).add(0, -.05, 0);
                point = foot.add(point.subtract(foot).scale(caught));
            } else if (drawn != null && now - drawn.tick <= 2) {foot = drawn.foot; point = drawn.point;}
            else continue;
            Vec3 along = point.subtract(foot);
            int embers = 1 + random.nextInt(2 + Math.round(2 * caught));
            for (int i = 0; i < embers; i++) {
                Vec3 at = foot.add(along.scale(random.nextDouble()));
                mc.level.addParticle(HexGodOfStories.GOLD_EMBER.get(), at.x, at.y + .1, at.z,
                    (random.nextDouble() - .5) * .03, .03 + random.nextDouble() * .05, (random.nextDouble() - .5) * .03);
            }
            if (random.nextInt(3) == 0) {
                Vec3 at = foot.add(along.scale(.4 + random.nextDouble() * .6));
                mc.level.addParticle(ParticleTypes.SMOKE, at.x, at.y + .35, at.z, (random.nextDouble() - .5) * .01, .03 + random.nextDouble() * .02,
                    (random.nextDouble() - .5) * .01);
            }
            if (random.nextInt(9) == 0) {
                Vec3 at = foot.add(along.scale(random.nextDouble()));
                mc.level.addParticle(ParticleTypes.FLAME, at.x, at.y + .15, at.z, 0, .04, 0);
            }
            if (caught > .3f && now >= CRACKLE.getOrDefault(id, 0L)) {
                CRACKLE.put(id, now + 22 + random.nextInt(30));
                Vec3 at = foot.add(along.scale(.5));
                mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, .5f + random.nextFloat() * .3f,
                    .8f + random.nextFloat() * .4f, false);
            }
        }
        if (now % 100 == 0) {
            LIT.keySet().removeIf(id -> mc.level.getEntity(id) == null);
            DRAWN.keySet().removeIf(id -> mc.level.getEntity(id) == null);
            CRACKLE.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        }
    }

    // ------------------------------------------------------------------ the sheets and the light they are drawn in

    private static ResourceLocation flame, glow;

    /**
     * One tongue of flame, white with its shape in the alpha: a round foot, widest a little above it, drawn up into a
     * point, solid at the foot and thinning toward the tip, with a few streaks torn into its upper part.
     */
    private static ResourceLocation flameSheet() {
        if (flame == null) flame = bake("hexgodofstories_deceiver_flame", 64, (u, v) -> {
            double h = (1 - v) / 2;
            double width = .8 * Math.sqrt(Mth.clamp(h / .12, 0, 1)) * Math.pow(Math.max(0, 1 - h), .62);
            if (width <= 1e-4) return 0;
            double edge = Math.abs(u) / width;
            if (edge >= 1) return 0;
            double a = Math.pow(1 - edge * edge, 1.4) * (.55 + .45 * (1 - h));
            return a * (1 - .35 * Math.max(0, Math.sin(h * 14 + u * 5)) * h);
        });
        return flame;
    }

    /** A soft round of light. */
    private static ResourceLocation glowSheet() {
        if (glow == null) glow = bake("hexgodofstories_deceiver_heat", 32, (u, v) -> Math.exp(-(u * u * 2.2 + v * v * .6) / .35));
        return glow;
    }

    private interface Field {double at(double u, double v);}

    private static ResourceLocation bake(String name, int size, Field field) {
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                double u = (x + .5) / size * 2 - 1, v = (y + .5) / size * 2 - 1;
                int alpha = (int) (Mth.clamp((float) field.at(u, v), 0, 1) * 255);
                image.setPixelRGBA(x, y, alpha << 24 | 0x00ffffff);
            }
        return Minecraft.getInstance().getTextureManager().register(name, new DynamicTexture(image));
    }

    /** Added to what is behind it, weighted by the sheet's alpha; hidden by what is in front, hiding nothing itself. */
    private static final class Types extends RenderType {
        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }

        private static final Map<ResourceLocation, RenderType> LIGHT = new HashMap<>();

        static RenderType light(ResourceLocation texture) {
            return LIGHT.computeIfAbsent(texture, t -> create("hexgodofstories_deceiver_flame", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1 << 14, false, false,
                CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(t, false, false))
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)));
        }
    }
}
