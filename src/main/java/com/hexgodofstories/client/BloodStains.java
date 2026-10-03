package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Blood on the ground: puddles that spread, run together and dry, and the marks drops leave where they land.
 *
 * <p>A puddle is fed rather than stacked: blood running into a puddle that is already there widens it (by half the
 * area it brings, the rest being where the two already lie over each other), so a body bleeding where it stands grows
 * one puddle under it instead of a heap of overlapping discs, and pools that meet merge into one deep, even red with
 * a meniscus at its rim. Fresh blood shines, with the sky's highlights in it that catch more the lower you look across
 * it; it darkens and dulls as it dries, still red, and at the end of its time thins away. A drop's mark is laid where
 * the drop itself came down (HexParticles.Drip), stretched along the way it was going with its tail ahead, or round
 * with a splashed crown if it fell straight; one landing in a puddle runs into it instead.
 *
 * <p>Every stain lies only on what holds it up: it is drawn a block cell at a time, and only over cells whose top is
 * the surface it lies on, so it stops at a ledge or a wall instead of hanging off it.
 *
 * <p>The puddles used to flicker in and out as the view turned. They were drawn after the particles, into the shared
 * buffer source, as ordinary translucent entity geometry: depth-written, sorted by distance, layered a hundredth of a
 * block apart, and flushed whenever that shared batch was. Now they are drawn straight after the opaque terrain,
 * before anything that might stand in them or pass over them, through a buffer of their own that is flushed then and
 * there; they write no depth, so they can neither fight each other nor cut into what is drawn after them, and they are
 * pulled toward the eye by a polygon offset rather than lifted off the ground, so they never fight the ground either.
 * The order they are drawn in is fixed (oldest first, by texture), never sorted by distance, and nothing is culled
 * by the view, so nothing about them changes as you look around.
 */
public final class BloodStains {
    private BloodStains() {}

    static final int POOL_KINDS = 6, MARK_KINDS = 4;
    /** The round, crowned mark of a drop that fell straight down; the others are thrown drops'. */
    static final int FALLEN = 0;
    private static final ResourceLocation[] POOL = new ResourceLocation[POOL_KINDS], SHEEN = new ResourceLocation[POOL_KINDS],
        MARK = new ResourceLocation[MARK_KINDS];
    private static final RenderType[] POOL_TYPE = new RenderType[POOL_KINDS], SHEEN_TYPE = new RenderType[POOL_KINDS],
        MARK_TYPE = new RenderType[MARK_KINDS];
    static {
        for (int i = 0; i < POOL_KINDS; i++) {
            POOL[i] = HexGodOfStories.id("textures/blood/pool_" + i + ".png");
            SHEEN[i] = HexGodOfStories.id("textures/blood/pool_" + i + "_sheen.png");
            POOL_TYPE[i] = Types.stain(POOL[i]);
            SHEEN_TYPE[i] = Types.stain(SHEEN[i]);
        }
        for (int i = 0; i < MARK_KINDS; i++) {
            MARK[i] = HexGodOfStories.id("textures/blood/spatter_" + i + ".png");
            MARK_TYPE[i] = Types.stain(MARK[i]);
        }
    }

    /** Stains at once; past this the oldest goes. */
    private static final int MOST = 720;
    /** The widest a single puddle grows, half across, in blocks; blood beyond that starts a puddle beside it. */
    private static final double WIDEST = 1.35;
    /** How close (as a share of its half-width) blood has to land to run into a puddle rather than start its own. */
    private static final double INTO = .6;
    /** How far a stain is drawn, and the most new marks a tick (a burst of drops lands all at once). */
    private static final double SEEN = 96 * 96;
    private static final int MARKS_A_TICK = 28;
    /** Fresh blood's tint and dry blood's: both red. Drops' marks dry faster than a puddle does. */
    private static final float[] FRESH = {.86f, .03f, .035f}, DRY = {.55f, .035f, .03f};
    private static final int WET = 240, DRYING = 520, MARK_WET = 80, MARK_DRYING = 240;
    /** The share of its life over which a stain thins away at the end, and its sheen's strongest. */
    private static final float FADE = .2f, SHINE = .55f;
    private static final float LIFT = .0015f;

    private static final class Stain {
        final double x, y, z;
        /** The texture's forward (+V) is (sin yaw, cos yaw) across the ground: a thrown drop's mark points the way it went. */
        final float yaw, stretch;
        final boolean pool;
        final int kind;
        final long born;
        /** Half-width it is spreading to, half-width drawn this tick and last, and how fast it gets there. */
        double size, shown, before;
        float rate;
        long fed;
        int life, light;
        /** Which block cells under it hold it up, over its footprint from (x0, z0), cells wide; refreshed now and then. */
        int x0, z0, wide;
        boolean[] held;
        long checked = Long.MIN_VALUE;

        Stain(double x, double y, double z, float yaw, float stretch, boolean pool, int kind, double size, double start, int spread, int life, long now) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.stretch = stretch; this.pool = pool; this.kind = kind;
            this.size = size; this.shown = this.before = start; this.rate = 1 - (float) Math.exp(-3.0 / Math.max(1, spread));
            this.born = this.fed = now; this.life = life;
        }

        /** Half the side of the square, square to the world, that the turned stain fits in at its full size. */
        double reach() {
            double c = Math.abs(Math.cos(yaw)), s = Math.abs(Math.sin(yaw));
            double w = Math.max(size, shown), l = w * stretch;
            return Math.max(w * c + l * s, w * s + l * c);
        }
    }

    private static final List<Stain> STAINS = new ArrayList<>();
    private static int marked;
    private static long markedTick = Long.MIN_VALUE;
    private static BufferBuilder builder;
    private static MultiBufferSource.BufferSource buffers;

    public static void clear() {STAINS.clear();}

    /**
     * Blood run onto the ground at {@code at} (on its surface): into the puddle it lands in, which widens, or a new
     * one, spreading out from a third of its size over about {@code spread} ticks.
     */
    public static void pool(Level level, Vec3 at, double size, int life, int spread) {
        long now = level.getGameTime();
        Stain into = into(at, 0);
        if (into != null) {
            into.size = Math.min(WIDEST, Math.sqrt(into.size * into.size + size * size * .5));
            into.rate = Math.max(into.rate, 1 - (float) Math.exp(-3.0 / Math.max(1, spread)));
            into.fed = now;
            into.life = Math.max(into.life, life);
            return;
        }
        add(new Stain(at.x, at.y, at.z, level.random.nextFloat() * Mth.TWO_PI, 1, true, level.random.nextInt(POOL_KINDS),
            Math.min(WIDEST, size), size * .35, spread, life, now));
    }

    /**
     * A drop's mark where it came down: stretched along its way ({@code vx}, {@code vz}), the faster the longer, or
     * crowned and round if it fell nearly straight; into a puddle, if it came down in one.
     */
    public static void mark(Level level, Vec3 at, double vx, double vz, double size, int life) {
        long now = level.getGameTime();
        if (markedTick != now) {markedTick = now; marked = 0;}
        Stain into = into(at, .02);
        if (into != null) {
            into.size = Math.min(WIDEST, Math.sqrt(into.size * into.size + size * size * .3));
            into.fed = now;
            return;
        }
        if (marked >= MARKS_A_TICK) return;
        marked++;
        double speed = Math.sqrt(vx * vx + vz * vz);
        boolean thrown = speed > .04;
        float yaw = thrown ? (float) Math.atan2(vx, vz) : level.random.nextFloat() * Mth.TWO_PI;
        float stretch = thrown ? (float) Math.min(2.8, 1.25 + speed * 9) : 1;
        int kind = thrown ? 1 + level.random.nextInt(MARK_KINDS - 1) : FALLEN;
        add(new Stain(at.x, at.y, at.z, yaw, stretch, false, kind, size, size, 1, life, now));
    }

    /** The fresh puddle this lands in, on the same surface, well inside it; or none. */
    private static Stain into(Vec3 at, double margin) {
        Stain best = null;
        double nearest = Double.MAX_VALUE;
        for (Stain s : STAINS) {
            if (!s.pool || Math.abs(s.y - at.y) > .03 || s.size >= WIDEST) continue;
            double dx = s.x - at.x, dz = s.z - at.z, d = Math.sqrt(dx * dx + dz * dz);
            if (d < Math.max(s.shown, s.size * .5) * INTO + margin && d < nearest) {nearest = d; best = s;}
        }
        return best;
    }

    private static void add(Stain stain) {
        if (STAINS.size() >= MOST) STAINS.remove(0);
        STAINS.add(stain);
    }

    /** Each client tick: spreading, ageing, the light on each, and now and then what each still lies on. */
    public static void tick(Level level) {
        if (STAINS.isEmpty()) return;
        long now = level.getGameTime();
        STAINS.removeIf(s -> now - s.fed > s.life);
        for (int i = 0; i < STAINS.size(); i++) {
            Stain s = STAINS.get(i);
            s.before = s.shown;
            s.shown += (s.size - s.shown) * s.rate;
            if (Math.abs(s.size - s.shown) < 1e-3) s.shown = s.size;
            // What holds it up: on landing, as it spreads, and once a second after in case the ground under it went.
            if (s.held == null || s.shown > s.before + 1e-4 && (now + i) % 4 == 0 || (now + i) % 20 == 0) support(level, s);
            if ((now + i) % 5 == 0 || s.checked == now) s.light = LevelRenderer.getLightColor(level, BlockPos.containing(s.x, s.y + .1, s.z));
        }
    }

    /** The block cells under a stain whose top is the surface it lies on, with nothing solid over them there. */
    private static void support(Level level, Stain s) {
        double reach = s.reach();
        int x0 = Mth.floor(s.x - reach), x1 = Mth.floor(s.x + reach), z0 = Mth.floor(s.z - reach), z1 = Mth.floor(s.z + reach);
        int wide = x1 - x0 + 1, deep = z1 - z0 + 1;
        boolean[] held = new boolean[wide * deep];
        int by = Mth.floor(s.y - 1e-3);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int ix = 0; ix < wide; ix++)
            for (int iz = 0; iz < deep; iz++) {
                at.set(x0 + ix, by, z0 + iz);
                VoxelShape shape = level.getBlockState(at).getCollisionShape(level, at);
                if (shape.isEmpty() || Math.abs(by + shape.max(Direction.Axis.Y) - s.y) > .03) continue;
                // A full block resting on that surface would hide it anyway; a wall beside a puddle stops it.
                if (s.y - by > .97) {
                    at.setY(by + 1);
                    if (level.getBlockState(at).isCollisionShapeFullBlock(level, at)) continue;
                }
                held[ix * deep + iz] = true;
            }
        s.x0 = x0; s.z0 = z0; s.wide = wide; s.held = held;
        if (s.checked == Long.MIN_VALUE) s.light = LevelRenderer.getLightColor(level, BlockPos.containing(s.x, s.y + .1, s.z));
        s.checked = level.getGameTime();
    }

    /** Straight after the opaque terrain, through a buffer of their own flushed here (see the class notes). */
    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (STAINS.isEmpty() || mc.level == null) return;
        if (buffers == null) {builder = new BufferBuilder(1 << 16); buffers = MultiBufferSource.immediate(builder);}
        Vec3 eye = event.getCamera().getPosition();
        float partial = event.getPartialTick();
        long now = mc.level.getGameTime();
        PoseStack.Pose pose = event.getPoseStack().last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        // The stains under each texture, oldest first; then every wet puddle's shine over the lot.
        for (int kind = 0; kind < POOL_KINDS; kind++) draw(buffers, POOL_TYPE[kind], true, kind, false, eye, partial, now, matrix, normal);
        for (int kind = 0; kind < MARK_KINDS; kind++) draw(buffers, MARK_TYPE[kind], false, kind, false, eye, partial, now, matrix, normal);
        for (int kind = 0; kind < POOL_KINDS; kind++) draw(buffers, SHEEN_TYPE[kind], true, kind, true, eye, partial, now, matrix, normal);
        buffers.endBatch();
    }

    private static void draw(MultiBufferSource.BufferSource buffers, RenderType type, boolean pools, int kind, boolean sheen, Vec3 eye,
                             float partial, long now, Matrix4f matrix, Matrix3f normal) {
        VertexConsumer out = null;
        for (Stain s : STAINS) {
            if (s.pool != pools || s.kind != kind || s.held == null) continue;
            double dx = s.x - eye.x, dy = s.y - eye.y, dz = s.z - eye.z, away = dx * dx + dy * dy + dz * dz;
            if (away > SEEN) continue;
            float age = (float) (now - s.fed + partial), left = s.life - age;
            float alpha = Mth.clamp(left / (s.life * FADE), 0, 1) * Mth.clamp((float) (now - s.born + partial) / 2f, 0, 1);
            float wet = wetness(s, age);
            float r, g, b;
            if (sheen) {
                if (wet <= .02f) continue;
                // The sky in it: catching more the lower the eye looks across it.
                double steep = Math.abs(dy) / Math.sqrt(Math.max(away, 1e-6));
                float grazing = (float) (1 - steep);
                alpha *= SHINE * wet * (.22f + .78f * grazing * grazing);
                r = g = b = 1;
            } else {
                r = Mth.lerp(wet, DRY[0], FRESH[0]); g = Mth.lerp(wet, DRY[1], FRESH[1]); b = Mth.lerp(wet, DRY[2], FRESH[2]);
            }
            if (alpha <= .01f) continue;
            if (out == null) out = buffers.getBuffer(type);
            cells(out, s, Mth.lerp(partial, s.before, s.shown), eye, matrix, normal, r, g, b, alpha);
        }
    }

    /** 1 while fresh, down to 0 as it dries: a puddle stays wet longer, and blood running into it wets it again. */
    private static float wetness(Stain s, float age) {
        int wet = s.pool ? WET : MARK_WET, drying = s.pool ? DRYING : MARK_DRYING;
        return 1 - Mth.clamp((age - wet) / drying, 0, 1);
    }

    /** The stain, a block cell at a time, over the cells that hold it up; its texture turned and stretched across them. */
    private static void cells(VertexConsumer out, Stain s, double half, Vec3 eye, Matrix4f matrix, Matrix3f normal,
                              float r, float g, float b, float alpha) {
        if (half <= 1e-3) return;
        double longHalf = half * s.stretch;
        double fx = Math.sin(s.yaw), fz = Math.cos(s.yaw), rx = fz, rz = -fx;
        double c = Math.abs(fx), d = Math.abs(fz);
        // The square, square to the world, the turned stain fits in at this size.
        double ex = half * Math.abs(rx) + longHalf * c, ez = half * Math.abs(rz) + longHalf * d;
        int deep = s.held.length / Math.max(1, s.wide);
        float y = (float) (s.y - eye.y) + LIFT;
        int light = s.light, overlay = OverlayTexture.NO_OVERLAY;
        int alpha8 = Mth.clamp((int) (alpha * 255), 0, 255);
        for (int ix = 0; ix < s.wide; ix++)
            for (int iz = 0; iz < deep; iz++) {
                if (!s.held[ix * deep + iz]) continue;
                double ax = Math.max(s.x - ex, s.x0 + ix), bx = Math.min(s.x + ex, s.x0 + ix + 1);
                double az = Math.max(s.z - ez, s.z0 + iz), bz = Math.min(s.z + ez, s.z0 + iz + 1);
                if (ax >= bx || az >= bz) continue;
                corner(out, matrix, normal, s, ax, az, half, longHalf, rx, rz, fx, fz, eye, y, r, g, b, alpha8, light, overlay);
                corner(out, matrix, normal, s, ax, bz, half, longHalf, rx, rz, fx, fz, eye, y, r, g, b, alpha8, light, overlay);
                corner(out, matrix, normal, s, bx, bz, half, longHalf, rx, rz, fx, fz, eye, y, r, g, b, alpha8, light, overlay);
                corner(out, matrix, normal, s, bx, az, half, longHalf, rx, rz, fx, fz, eye, y, r, g, b, alpha8, light, overlay);
            }
    }

    private static void corner(VertexConsumer out, Matrix4f matrix, Matrix3f normal, Stain s, double x, double z, double half,
                               double longHalf, double rx, double rz, double fx, double fz, Vec3 eye, float y,
                               float r, float g, float b, int alpha, int light, int overlay) {
        double ox = x - s.x, oz = z - s.z;
        float u = (float) ((ox * rx + oz * rz) / (2 * half) + .5), v = (float) ((ox * fx + oz * fz) / (2 * longHalf) + .5);
        out.vertex(matrix, (float) (x - eye.x), y, (float) (z - eye.z))
            .color((int) (r * 255), (int) (g * 255), (int) (b * 255), alpha)
            .uv(u, v)
            .overlayCoords(overlay)
            .uv2(light)
            .normal(normal, 0, 1, 0)
            .endVertex();
    }

    /**
     * A stain's render type: the entity translucent shader (lit by the world, fogged with it), its texture smooth
     * and clamped, drawn over the ground by a polygon offset, writing colour only. Not sorted: drawn in the order given.
     */
    private static final class Types extends RenderType {
        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sorted,
                      Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }

        static RenderType stain(ResourceLocation texture) {
            return create("hexgodofstories_blood_stain", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1 << 16, false, false,
                CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(texture, true, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
        }
    }
}
