package com.hexgodofstories.client;

import com.hexgodofstories.server.FlameStream;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
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
 * The burning Deceiver's fire loosed (FlameStream), after Surtur's sword: a jet straight out of the point to what the
 * bearer looks at. A white-hot thread down its middle inside a thick, ragged column of fire that widens as it goes,
 * every layer of it streaming outward along the jet; licks of flame tearing off its sides and drifting on, cooling from
 * gold to red as they go; and where it strikes, a churning fireball rolling up and out off the surface, white at its
 * heart, smoke lifting off the top of it. A blue-white flare at the point where the fire leaves the steel.
 *
 * <p>All of it is drawn here, nothing of it is a vanilla particle. The jet is drawn each frame from where the blade's
 * point was drawn (DeceiverFlame) along the bearer's look; the licks and the fireball are puffs that live on their own
 * once loosed, so a jet swept across the world leaves its fire hanging and rolling behind it. Red, orange and smoke are
 * colour laid over the world (orange against a bright sky, not washed white); only what is white-hot is added light.
 */
public final class FireStream {
    private FireStream() {}

    private static final int LICKS = 7, BLOOMS = 4, MOST = 700, SEGMENTS = 28;
    private static final int SHEATH = 0xd8400c, BODY = 0xff7c1a, GLOW = 0xff9a2c, HOT = 0xffd27a, WHITE = 0xfff6e0;
    private static final int GOLD = 0xffb040, EMBER = 0xff7418, RED = 0xc3300c, BLOOM_HOT = 0xfff0c0, BLOOM = 0xffa030, BLOOM_RED = 0xe0501a,
        SMOKE = 0x5a4c42, NOZZLE = 0x7f9cff, HEART = 0xffd890;

    private static final class Puff {
        Vec3 pos, last, vel;
        int age;
        final int life, sheet;
        final float size, grow, spin, turn;
        final boolean bloom;

        Puff(Vec3 pos, Vec3 vel, int life, float size, float grow, boolean bloom, RandomSource random) {
            this.pos = pos; this.last = pos; this.vel = vel; this.life = life; this.size = size; this.grow = grow; this.bloom = bloom;
            this.sheet = random.nextInt(4);
            this.spin = random.nextFloat() * Mth.TWO_PI;
            this.turn = (random.nextFloat() - .5f) * .12f;
        }
    }

    private static final class Pour {
        final List<Puff> puffs = new ArrayList<>();
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

    /** What the look meets within the jet's reach: the same line the server burns along. */
    private static BlockHitResult aim(Player p, float partial) {
        Vec3 eye = p.getEyePosition(partial);
        return p.level().clip(new ClipContext(eye, eye.add(p.getViewVector(partial).scale(FlameStream.RANGE)), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, p));
    }

    private static Vec3 jitter(RandomSource random, double by) {
        return new Vec3((random.nextDouble() - .5) * by, (random.nextDouble() - .5) * by, (random.nextDouble() - .5) * by);
    }

    // ------------------------------------------------------------------ the puffs' lives

    /** Every client tick: fire loosed off every jet in sight, and every puff carried on, slowed, lifted and spent. */
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
            Vec3 from = nozzle(p, 1), end = hit.getLocation(), axis = end.subtract(from);
            double length = axis.length();
            if (length < .2) continue;
            axis = axis.scale(1 / length);
            // Licks torn off the column's sides, carried on along it, wider the further out they tear.
            for (int i = 0; i < LICKS && pour.puffs.size() < MOST; i++) {
                double f = random.nextDouble();
                Vec3 at = from.add(axis.scale(f * length)).add(jitter(random, .1 + f * .5));
                Vec3 vel = axis.scale(.5 + random.nextDouble() * .45).add(jitter(random, .06)).add(0, .02, 0);
                pour.puffs.add(new Puff(at, vel, 6 + random.nextInt(8), (float) (.14 + f * .4), .05f, false, random));
            }
            // Where it strikes: the fireball, thrown up and out off the surface.
            if (hit.getType() == HitResult.Type.BLOCK) {
                Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
                for (int i = 0; i < BLOOMS && pour.puffs.size() < MOST; i++) {
                    Vec3 vel = normal.scale(.06 + random.nextDouble() * .1).add(jitter(random, .2)).add(axis.scale(.04))
                        .add(0, .03 + random.nextDouble() * .05, 0);
                    pour.puffs.add(new Puff(end.add(normal.scale(.2)), vel, 14 + random.nextInt(12), (float) (.35 + random.nextDouble() * .3),
                        .085f, true, random));
                }
            }
        }
        for (Iterator<Pour> it = POURS.values().iterator(); it.hasNext(); ) {
            Pour pour = it.next();
            pour.puffs.removeIf(f -> ++f.age >= f.life);
            for (Puff f : pour.puffs) {
                f.last = f.pos;
                // Dragged to a stop, and lifted by its own heat; checked against the world so it rolls along a wall
                // or a floor rather than through it.
                f.vel = f.vel.scale(f.bloom ? .9 : .86).add(0, f.bloom ? .016 : .009, 0);
                Vec3 next = f.pos.add(f.vel);
                if (solid(mc.level, next)) {
                    f.vel = new Vec3(f.vel.x * .3, Math.abs(f.vel.y) * .5 + .02, f.vel.z * .3);
                    next = solid(mc.level, f.pos.add(f.vel)) ? f.pos : f.pos.add(f.vel);
                }
                f.pos = next;
            }
            if (pour.puffs.isEmpty() && now - pour.seen > 2) it.remove();
        }
    }

    private static boolean solid(Level level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    // ------------------------------------------------------------------ drawing

    /** In the world pass, with the pose already moved to world coordinates (WorldEffects). */
    public static void render(PoseStack pose, MultiBufferSource buffers, float partial) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || POURS.isEmpty()) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double time = ClientState.time(partial);
        List<Player> jets = new ArrayList<>();
        for (Player p : mc.level.players()) if (pouring(p) && POURS.containsKey(p.getId())) jets.add(p);
        List<Puff> puffs = new ArrayList<>();
        for (Pour pour : POURS.values()) puffs.addAll(pour.puffs);
        // Furthest first, so the near smoke and fire are laid over the far.
        puffs.sort((a, b) -> Double.compare(b.pos.distanceToSqr(camera), a.pos.distanceToSqr(camera)));
        Vec3 right = BranchVfx.cameraRight(), up = BranchVfx.cameraUp();

        // Colour first: the column's sheath and body, then every puff's fire and smoke.
        VertexConsumer out = buffers.getBuffer(FireTypes.veil(beamSheet()));
        for (Player p : jets) {
            Vec3[] jet = jet(p, partial);
            if (jet == null) continue;
            boolean struck = jet[2] != null;
            column(out, pose, camera, jet[0], jet[1], time, p.getId(), .16, 1.05, .75, .7, SHEATH, .6f, struck);
            column(out, pose, camera, jet[0], jet[1], time, p.getId(), .12, .78, .8, .9, BODY, .55f, struck);
        }
        out = buffers.getBuffer(FireTypes.veil(puffSheet()));
        for (Puff f : puffs) {
            float t = Mth.clamp((f.age + partial) / f.life, 0, 1);
            Vec3 at = f.last.lerp(f.pos, partial);
            float size = f.size + f.grow * (f.age + partial), spin = f.spin + f.turn * (f.age + partial);
            if (f.bloom) {
                if (t >= .7f) puff(out, pose, at.add(0, .3, 0), size * 1.15f, spin, f.sheet, right, up, SMOKE, .22f * (1 - t) / .3f);
                else if (t >= .14f) puff(out, pose, at, size, spin, f.sheet, right, up, t < .4f ? BLOOM : BLOOM_RED,
                    .6f * (float) Math.sqrt(Math.sin(Math.PI * Math.min(1, t / .7f))));
            } else {
                puff(out, pose, at, size, spin, f.sheet, right, up, t < .35f ? GOLD : t < .7f ? EMBER : RED,
                    .42f * (float) Math.pow(Math.sin(Math.PI * t), .7));
            }
        }

        // Then the light: the column's glow, its gold and its white-hot thread, the youngest fire, and the flares.
        out = buffers.getBuffer(FireTypes.light(beamSheet()));
        for (Player p : jets) {
            Vec3[] jet = jet(p, partial);
            if (jet == null) continue;
            boolean struck = jet[2] != null;
            column(out, pose, camera, jet[0], jet[1], time, p.getId(), .09, .5, .85, 1, GLOW, .6f, struck);
            column(out, pose, camera, jet[0], jet[1], time, p.getId(), .05, .28, 1, 1.3, HOT, .7f, struck);
            column(out, pose, camera, jet[0], jet[1], time, p.getId(), .025, .1, 1, 1.6, WHITE, .8f, struck);
        }
        out = buffers.getBuffer(FireTypes.light(puffSheet()));
        for (Puff f : puffs) {
            float t = Mth.clamp((f.age + partial) / f.life, 0, 1);
            Vec3 at = f.last.lerp(f.pos, partial);
            float size = f.size + f.grow * (f.age + partial), spin = f.spin + f.turn * (f.age + partial);
            if (f.bloom && t < .14f) puff(out, pose, at, size, spin, f.sheet, right, up, BLOOM_HOT, .6f);
            else if (!f.bloom && t < .25f) puff(out, pose, at, size * .55f, spin, f.sheet, right, up, HOT, .35f * (1 - t / .25f));
        }
        for (Player p : jets) {
            Vec3[] jet = jet(p, partial);
            if (jet == null) continue;
            float flicker = (float) (.85 + .15 * Math.sin(time * 2.3 + p.getId()));
            puff(out, pose, jet[0], .22f, (float) time * .2f, 0, right, up, NOZZLE, .7f * flicker);
            puff(out, pose, jet[0], .13f, (float) -time * .3f, 1, right, up, WHITE, .9f);
            if (jet[2] != null) puff(out, pose, jet[1], .9f * flicker, (float) time * .05f, 2, right, up, HEART, .6f);
        }
    }

    /** The jet this frame: where it leaves the point, where it ends, and the face it struck (null when it struck nothing). */
    private static Vec3[] jet(Player p, float partial) {
        BlockHitResult hit = aim(p, partial);
        Vec3 from = nozzle(p, partial), end = hit.getLocation();
        if (end.distanceToSqr(from) < .04) return null;
        return new Vec3[]{from, end, hit.getType() == HitResult.Type.BLOCK ? Vec3.atLowerCornerOf(hit.getDirection().getNormal()) : null};
    }

    /**
     * One layer of the column: a strip from `from` to `to` turned to face the eye all along, `w0` wide at the point and
     * `w1` at the end (growing by `power`), wavering more the further it goes, its sheet streaming outward at `speed`.
     * Faded in at the point, and out at its end when it struck nothing.
     */
    private static void column(VertexConsumer out, PoseStack pose, Vec3 camera, Vec3 from, Vec3 to, double time, int seed, double w0, double w1,
                               double power, double speed, int colour, float alpha, boolean struck) {
        Vec3 axis = to.subtract(from);
        double length = axis.length();
        axis = axis.scale(1 / length);
        Vec3 u1 = BranchVfx.perpendicular(axis), u2 = axis.cross(u1).normalize();
        Vec3[] centre = new Vec3[SEGMENTS + 1], side = new Vec3[SEGMENTS + 1];
        float[] fade = new float[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double f = i / (double) SEGMENTS;
            double wob = f * f * .6;
            Vec3 c = from.add(axis.scale(f * length))
                .add(u1.scale(Math.sin(time * .9 + f * 9 + seed) * .25 * wob)).add(u2.scale(Math.sin(time * 1.7 + f * 17 + seed * 2) * .12 * wob));
            centre[i] = c;
            Vec3 along = i == 0 ? axis : c.subtract(centre[i - 1]);
            Vec3 s = along.cross(camera.subtract(c));
            s = s.lengthSqr() < 1e-9 ? u1 : s.normalize();
            side[i] = s.scale(w0 + (w1 - w0) * Math.pow(f, power));
            fade[i] = (float) (Math.min(1, f / .04) * (struck ? 1 : Math.min(1, (1 - f) / .2)));
        }
        Matrix4f m = pose.last().pose();
        int r = colour >> 16 & 255, g = colour >> 8 & 255, b = colour & 255;
        for (int i = 0; i < SEGMENTS; i++) {
            float v0 = (float) (i / (double) SEGMENTS * length * .16 - time * .12 * speed);
            float v1 = (float) ((i + 1) / (double) SEGMENTS * length * .16 - time * .12 * speed);
            vertex(out, pose, m, centre[i].subtract(side[i]), 0, v0, r, g, b, alpha * fade[i]);
            vertex(out, pose, m, centre[i].add(side[i]), 1, v0, r, g, b, alpha * fade[i]);
            vertex(out, pose, m, centre[i + 1].add(side[i + 1]), 1, v1, r, g, b, alpha * fade[i + 1]);
            vertex(out, pose, m, centre[i + 1].subtract(side[i + 1]), 0, v1, r, g, b, alpha * fade[i + 1]);
        }
    }

    /** One puff: a round of fire or smoke facing the eye, turned by `spin`, from one of the four quarters of its sheet. */
    private static void puff(VertexConsumer out, PoseStack pose, Vec3 at, float size, float spin, int sheet, Vec3 right, Vec3 up,
                             int colour, float alpha) {
        if (alpha <= .01f || size <= .005f) return;
        float c = Mth.cos(spin) * size, s = Mth.sin(spin) * size;
        Vec3 a = right.scale(c).add(up.scale(s)), b = right.scale(-s).add(up.scale(c));
        float u0 = (sheet & 1) * .5f, v0 = (sheet >> 1) * .5f;
        Matrix4f m = pose.last().pose();
        int r = colour >> 16 & 255, g = colour >> 8 & 255, bl = colour & 255;
        vertex(out, pose, m, at.subtract(a).subtract(b), u0, v0, r, g, bl, alpha);
        vertex(out, pose, m, at.add(a).subtract(b), u0 + .5f, v0, r, g, bl, alpha);
        vertex(out, pose, m, at.add(a).add(b), u0 + .5f, v0 + .5f, r, g, bl, alpha);
        vertex(out, pose, m, at.subtract(a).add(b), u0, v0 + .5f, r, g, bl, alpha);
    }

    private static void vertex(VertexConsumer out, PoseStack pose, Matrix4f m, Vec3 at, float u, float v, int r, int g, int b, float alpha) {
        out.vertex(m, (float) at.x, (float) at.y, (float) at.z).color(r, g, b, Mth.clamp((int) (alpha * 255), 0, 255)).uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(pose.last().normal(), 0, 1, 0).endVertex();
    }

    // ------------------------------------------------------------------ the sheets

    private static ResourceLocation beam, puffs;

    /**
     * The column's sheet: streaks running along the flow (it repeats along its length, so it can stream without a seam),
     * soft across it and torn ragged at its edges.
     */
    private static ResourceLocation beamSheet() {
        if (beam != null) return beam;
        int w = 32, h = 128;
        NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double across = Math.abs((x + .5) / w * 2 - 1);
                double n = fbm((x + .5) / w, (y + .5) / h, 2, 6, 11);
                double a = (1 - Math.pow(across, 1.6)) * (.55 + .7 * (n - .5)) * 1.25 * Math.pow(Math.max(0, 1 - across), .4);
                image.setPixelRGBA(x, y, (int) (Mth.clamp((float) a, 0, 1) * 255) << 24 | 0x00ffffff);
            }
        return beam = Minecraft.getInstance().getTextureManager().register("hexgodofstories_fire_column", new DynamicTexture(image));
    }

    /** Four rounds of churning fire, one to a quarter: soft and wispy at their edges, no two alike. */
    private static ResourceLocation puffSheet() {
        if (puffs != null) return puffs;
        int size = 128, tile = 64;
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int k = (x / tile) + 2 * (y / tile);
                double u = (x % tile + .5) / tile * 2 - 1, v = (y % tile + .5) / tile * 2 - 1, d = Math.sqrt(u * u + v * v);
                double n = fbm((x % tile + .5) / tile, (y % tile + .5) / tile, 3, 3, 40 + k);
                double a = Math.pow(Mth.clamp((float) ((1 - d / (.55 + .45 * n)) * 1.6), 0, 1), 1.3) * (.6 + .6 * n);
                // Nothing at a tile's edge, so no quarter bleeds into the next.
                if (Math.max(Math.abs(u), Math.abs(v)) > .96) a = 0;
                image.setPixelRGBA(x, y, (int) (Mth.clamp((float) a, 0, 1) * 255) << 24 | 0x00ffffff);
            }
        return puffs = Minecraft.getInstance().getTextureManager().register("hexgodofstories_fire_puffs", new DynamicTexture(image));
    }

    /** Fractal value noise over [0,1)², repeating across both: `cx` by `cy` cells at its coarsest, four octaves. */
    private static double fbm(double x, double y, int cx, int cy, int seed) {
        double sum = 0, amp = .5, total = 0;
        for (int o = 0; o < 4; o++) {
            int nx = cx << o, ny = cy << o;
            sum += amp * value(x * nx, y * ny, nx, ny, seed + o);
            total += amp;
            amp *= .5;
        }
        return sum / total;
    }

    private static double value(double x, double y, int nx, int ny, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double tx = x - ix, ty = y - iy;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        double a = lattice(ix, iy, nx, ny, seed), b = lattice(ix + 1, iy, nx, ny, seed);
        double c = lattice(ix, iy + 1, nx, ny, seed), d = lattice(ix + 1, iy + 1, nx, ny, seed);
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
    }

    private static double lattice(int x, int y, int nx, int ny, int seed) {
        x = Math.floorMod(x, nx);
        y = Math.floorMod(y, ny);
        double s = Math.sin(x * 127.1 + y * 311.7 + seed * 74.7) * 43758.5453;
        return s - Math.floor(s);
    }
}
