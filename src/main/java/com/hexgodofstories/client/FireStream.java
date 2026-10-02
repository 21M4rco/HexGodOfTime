package com.hexgodofstories.client;

import com.hexgodofstories.server.FlameStream;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The burning Deceiver's fire loosed (FlameStream), after Surtur's sword, and made of the very fire on its blade
 * (DeceiverFlame): the same tongues of flame, the same sheet, the same red edges, orange bodies, white-hot cores and blue
 * at the steel, poured out of the point. They leave it fast, drawn out into long thin streaks that run together into a
 * stream; slowing, they widen and begin to lick and sway, and their own heat turns them upward, so the stream thickens
 * into a rolling body of fire that curls up at its end; what reaches a wall or the ground spreads along it and climbs,
 * burning there. A white-hot tongue and blue licks at the point where the fire leaves the steel, and smoke off the
 * spent fire.
 *
 * <p>The tongues live on their own once loosed, so a stream swept across the world leaves its fire hanging and rolling
 * behind it. It is all drawn here (save the smoke): the red and orange laid over the world as colour, so they stay those
 * colours against a bright sky, and only the white-hot cores and the blue added as light.
 */
public final class FireStream {
    private FireStream() {}

    private static final int RED = 0xff4a12, ORANGE = 0xff9628, CORE = 0xfff0c4, BLUE = 0x3a62ff;
    /** Tongues loosed each tick, how fast they leave the point, and how many may burn at once for any one bearer. */
    private static final int LOOSED = 16, CLIMBING = 3, MOST = 600;
    private static final double SPEED = 1.6, SPREAD = .045;

    private static final class Tongue {
        Vec3 pos, last, vel;
        final double speed;
        final int life;
        final float size, phase;
        boolean struck;
        int age;

        Tongue(Vec3 pos, Vec3 vel, int life, float size, boolean struck, RandomSource random) {
            this.pos = pos; this.last = pos; this.vel = vel; this.life = life; this.size = size; this.struck = struck;
            this.speed = Math.max(1e-3, vel.length());
            this.phase = random.nextFloat() * Mth.TWO_PI;
        }
    }

    private static final class Pour {
        final List<Tongue> tongues = new ArrayList<>();
        long seen;
    }

    private static final Map<Integer, Pour> POURS = new HashMap<>();

    public static void clear() {POURS.clear();}

    /** Whether the server says this player is pouring fire. */
    public static boolean pouring(Player p) {
        return !p.isSpectator() && ClientState.data(p.getId()).getLong(FlameStream.POURING) > 0;
    }

    /** Where the fire leaves the steel: the blade's point as drawn this frame, or reckoned from the look if it was not. */
    private static Vec3 nozzle(Player p, float partial) {
        Vec3 tip = DeceiverFlame.tip(p.getId());
        if (tip != null) return tip;
        Vec3 eye = p.getEyePosition(partial), look = p.getViewVector(partial), flat = new Vec3(look.x, 0, look.z);
        Vec3 right = flat.lengthSqr() < 1e-6 ? Vec3.ZERO : new Vec3(-flat.z, 0, flat.x).normalize();
        return eye.add(look.scale(1.9)).add(right.scale(.28)).add(0, -.22, 0);
    }

    /** What the look meets within the stream's reach: the same line the server burns along. */
    private static BlockHitResult aim(Player p, float partial) {
        Vec3 eye = p.getEyePosition(partial);
        return p.level().clip(new ClipContext(eye, eye.add(p.getViewVector(partial).scale(FlameStream.RANGE)), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, p));
    }

    // ------------------------------------------------------------------ the fire's lives

    /** Every client tick: fire loosed from every point in sight, and every tongue carried on, slowed, lifted and spent. */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {clear(); return;}
        long now = ClientState.now();
        RandomSource random = mc.level.random;
        Vec3 eye = mc.player.getEyePosition();
        for (Player p : mc.level.players()) {
            if (!pouring(p) || p.position().distanceToSqr(eye) > 96 * 96) continue;
            Pour pour = POURS.computeIfAbsent(p.getId(), id -> new Pour());
            pour.seen = now;
            BlockHitResult hit = aim(p, 1);
            Vec3 from = nozzle(p, 1), to = hit.getLocation(), axis = to.subtract(from);
            if (axis.lengthSqr() < .04) continue;
            axis = axis.normalize();
            for (int i = 0; i < LOOSED && pour.tongues.size() < MOST; i++) {
                Vec3 vel = axis.scale(SPEED * (.8 + .4 * random.nextDouble()))
                    .add(random.nextGaussian() * SPREAD, random.nextGaussian() * SPREAD, random.nextGaussian() * SPREAD);
                pour.tongues.add(new Tongue(from.add(axis.scale(random.nextDouble() * SPEED)), vel, 13 + random.nextInt(8),
                    .16f + .16f * random.nextFloat(), false, random));
            }
            // Where it strikes, the fire takes hold and climbs.
            if (hit.getType() == HitResult.Type.BLOCK)
                for (int i = 0; i < CLIMBING && pour.tongues.size() < MOST; i++) {
                    Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
                    Vec3 vel = normal.scale(.05).add(random.nextGaussian() * .05, .06 + random.nextDouble() * .05, random.nextGaussian() * .05);
                    pour.tongues.add(new Tongue(to.add(normal.scale(.1)), vel, 12 + random.nextInt(8), .25f + .15f * random.nextFloat(), true, random));
                }
        }
        for (Iterator<Pour> it = POURS.values().iterator(); it.hasNext(); ) {
            Pour pour = it.next();
            for (Iterator<Tongue> tongues = pour.tongues.iterator(); tongues.hasNext(); ) {
                Tongue f = tongues.next();
                if (++f.age >= f.life) {
                    // Spent fire leaves its smoke.
                    if (random.nextInt(7) == 0) mc.level.addParticle(ParticleTypes.SMOKE, f.pos.x, f.pos.y + .3, f.pos.z, 0, .04, 0);
                    tongues.remove();
                    continue;
                }
                f.last = f.pos;
                // Dragged to a stop, and turned upward by its own heat the slower it goes.
                double fast = Math.min(1, f.vel.length() / f.speed);
                f.vel = f.vel.scale(.93).add(0, .035 * (1 - fast) + .004, 0);
                Vec3 next = f.pos.add(f.vel);
                if (solid(mc.level, next)) {
                    // Against the world: spread along it and climb.
                    f.struck = true;
                    f.vel = new Vec3(f.vel.x * .5 + random.nextGaussian() * .05, Math.abs(f.vel.y) * .25 + .07, f.vel.z * .5 + random.nextGaussian() * .05);
                    next = solid(mc.level, f.pos.add(f.vel)) ? f.pos : f.pos.add(f.vel);
                }
                f.pos = next;
            }
            if (pour.tongues.isEmpty() && now - pour.seen > 2) it.remove();
        }
    }

    private static boolean solid(Level level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    // ------------------------------------------------------------------ drawing

    /** One tongue as it is drawn this frame. */
    private record Drawn(Vec3 foot, Vec3 axis, float tall, float wide, float sway, float fade, float t) {}

    /** In the world pass, with the pose already moved to world coordinates (WorldEffects). */
    public static void render(PoseStack pose, MultiBufferSource buffers, float partial) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || POURS.isEmpty()) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition(), up = new Vec3(0, 1, 0);
        double time = ClientState.time(partial);
        List<Drawn> drawn = new ArrayList<>();
        for (Pour pour : POURS.values())
            for (Tongue f : pour.tongues) {
                float t = Mth.clamp((f.age + partial) / f.life, 0, 1);
                Vec3 at = f.last.lerp(f.pos, partial);
                double fast = Math.min(1, f.vel.length() / f.speed), lean = Math.min(1, fast * 1.3);
                Vec3 axis = f.vel.lengthSqr() < 1e-8 ? up : f.vel.normalize().scale(lean).add(up.scale(1 - lean + .15));
                axis = axis.normalize();
                // Fast, a long thin streak; slowed, a broad tongue that licks and sways.
                float tall = (float) (f.size * (1 + (f.age + partial) * .3) * (f.struck ? 1.5 : 1) * (1 + 1.6 * fast));
                float wide = (float) (.5 - .3 * fast);
                float sway = (float) (.25 * Math.sin(time * .3 + f.phase) * (1 - fast));
                float fade = (float) Math.pow(Math.sin(Math.PI * t), .6);
                drawn.add(new Drawn(at.subtract(axis.scale(tall * .35)), axis, tall, wide, sway, fade, t));
            }
        // Furthest first, so the near fire is laid over the far.
        drawn.sort((a, b) -> Double.compare(b.foot.distanceToSqr(camera), a.foot.distanceToSqr(camera)));
        Matrix4f m = pose.last().pose();
        VertexConsumer out = buffers.getBuffer(FireTypes.veil(DeceiverFlame.flameSheet()));
        for (Drawn d : drawn) tongue(out, pose, m, camera, d.foot, d.axis, d.tall, d.tall * d.wide, d.sway, RED, .5f * d.fade);
        for (Drawn d : drawn)
            tongue(out, pose, m, camera, d.foot, d.axis, d.tall * .8f, d.tall * d.wide * .66f, d.sway * .8f, ORANGE, .55f * d.fade * (1 - .5f * d.t));
        out = buffers.getBuffer(FireTypes.light(DeceiverFlame.flameSheet()));
        for (Drawn d : drawn)
            tongue(out, pose, m, camera, d.foot, d.axis, d.tall * .5f, d.tall * d.wide * .3f, d.sway * .5f, CORE, .5f * d.fade * (1 - d.t));
        // At the point: blue licks at the steel and a white-hot tongue driven out of it.
        RandomSource random = mc.level.random;
        for (Player p : mc.level.players()) {
            if (!pouring(p) || !POURS.containsKey(p.getId())) continue;
            BlockHitResult hit = aim(p, partial);
            Vec3 from = nozzle(p, partial), axis = hit.getLocation().subtract(from);
            if (axis.lengthSqr() < .04) continue;
            axis = axis.normalize();
            float flicker = (float) (.85 + .15 * Math.sin(time * 2.3 + p.getId()));
            for (int k = 0; k < 3; k++)
                tongue(out, pose, m, camera, from.add(random.nextGaussian() * .02, random.nextGaussian() * .02, random.nextGaussian() * .02), axis,
                    .35f + .1f * k, .09f, 0, BLUE, .7f * flicker);
            tongue(out, pose, m, camera, from, axis, 1.8f * flicker, .16f, 0, CORE, .55f);
        }
    }

    /** One tongue: from `foot` along `axis` for `tall`, `wide` across, turned to the eye, its tip swayed aside. */
    private static void tongue(VertexConsumer out, PoseStack pose, Matrix4f m, Vec3 camera, Vec3 foot, Vec3 axis, float tall, float wide,
                               float sway, int colour, float alpha) {
        if (alpha <= .01f || tall <= .005f) return;
        Vec3 side = axis.cross(camera.subtract(foot));
        if (side.lengthSqr() < 1e-9) return;
        side = side.normalize();
        Vec3 tip = foot.add(axis.scale(tall)).add(side.scale(sway * tall)), base = foot.subtract(axis.scale(tall * .08)), half = side.scale(wide);
        int r = colour >> 16 & 255, g = colour >> 8 & 255, b = colour & 255, a = Mth.clamp((int) (alpha * 255), 0, 255);
        vertex(out, pose, m, tip.subtract(half), 0, 0, r, g, b, a);
        vertex(out, pose, m, tip.add(half), 1, 0, r, g, b, a);
        vertex(out, pose, m, base.add(half), 1, 1, r, g, b, a);
        vertex(out, pose, m, base.subtract(half), 0, 1, r, g, b, a);
    }

    private static void vertex(VertexConsumer out, PoseStack pose, Matrix4f m, Vec3 at, float u, float v, int r, int g, int b, int a) {
        out.vertex(m, (float) at.x, (float) at.y, (float) at.z).color(r, g, b, a).uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(pose.last().normal(), 0, 1, 0).endVertex();
    }
}
