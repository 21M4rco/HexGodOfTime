package com.hexgodofstories.client;

import com.hexgodofstories.data.HoleHeat;
import com.hexgodofstories.data.WoundCarve;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.RenderTypeHelper;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The holes a Scepter beam leaves in walls: the same hole it leaves in a body — seen through, cauterised
 * inside, scorched round the mouth — through every block it went through, open for most of a minute and
 * then knitting shut. Nothing is broken. The blocks are all still there, solid, to stand on, mine and
 * build against; only the look of them is carved.
 *
 * <p>How it is made. A block the beam went through is left out of its chunk's mesh while its hole is open,
 * and drawn here instead, every frame, from its own model and its own light,
 * less whatever lies inside the beam's cylinder ({@link WoundCarve}). What the hole shows is then simply
 * what is behind it. Its walls are the block's own texture, charred deeper in. A hole near a block's edge
 * runs on into the block beside it, as it would through a body, and that block is carved too. Where the
 * hole ends against a block it did not go through — the one that stopped it, or one beside the run — the
 * chunk hides that block's face, since it is up against a solid one, so that face is drawn back in, seared.
 *
 * <p>It leaves the mesh two ways, whichever the chunk renderer takes: vanilla chunk building's own call for
 * each block ({@code BlockMeshMixin}), and, for a renderer that builds meshes some other way (Embeddium,
 * for one), the block's model, which renders nothing for a holed block when chunk building asks for it
 * ({@link HoleModel}). A block is only drawn carved here once a chunk build has been seen leaving it out,
 * and only goes back to its chunk once a build has been seen taking it back; until then it is drawn here
 * whole. A renderer that does neither is never shown a carved block over a whole one: its holes are scorch
 * marks, a charred disc with the scorch round it.
 *
 * <p>What has no model of its own in the chunk is cut as it is drawn instead ({@link QuadCarver}): a block its
 * block entity draws (a chest, a bed), from that renderer's own buffers; a hanging thing the beam went
 * through (an item frame and what is in it, a painting), from the entity's. Grass, flowers and anything else
 * soft in the beam's way, which never stops it and so is never among its walls, is cut away where the beam
 * went, and nothing more: no tunnel, no scorch, no heat.
 *
 * <p>A hole comes up red-hot as the beam goes through, running down the tunnel the way the beam did, and cools
 * through orange to a dim yellowish glow before it goes out, a while after a hole in a body would, the inside
 * of the tunnel last ({@link HoleHeat}). The glow is light added over the tunnel, round each mouth and on the
 * face the hole ends against; and while it lasts, every mouth that opens onto the air smokes a little, now
 * and then a molten drop runs off its lower edge, and the hole sizzles, quieter as it cools ({@link HoleSizzle}).
 */
public final class BlockWounds {
    private BlockWounds() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How the inside of a hole is charred: at its mouths, and halfway down the run of walls. */
    private static final float MOUTH = .72f, DEEP = .3f;
    /** A face the hole meets at its end or its side, seared by the beam that stopped against it. */
    private static final float SEARED = .45f;
    /** What a quad's corner carries here: see {@link QuadCarver#STRIDE}. */
    private static final int STRIDE = QuadCarver.STRIDE;
    private static final float[] FULL = {0, 0, 0, 1, 1, 1};

    /** One shot's hole down a run of walls. */
    private static final class Hole {
        final Vec3 from, along;
        final float radius;
        final long start;
        final int life;
        /** How far along the line the run of walls begins and ends. */
        double enter, leave;
        /** Where the hole opens onto the air, for its smoke: x, y, z, the way out, and how far down the run it is. */
        final List<double[]> mouths = new ArrayList<>();
        /** Whether any block of it is still holed, as of this tick; and its sizzle, while it can be heard. */
        boolean standing;
        HoleSizzle sizzle;

        Hole(Vec3 from, Vec3 along, float radius, long start, int life) {
            this.from = from;
            this.along = along;
            this.radius = radius;
            this.start = start;
            this.life = life;
        }

        /** Open for three quarters of its life, then knitting shut: the same as a body's. */
        float radius(float partial) {
            float close = Mth.clamp((ClientState.since(start, partial) / life - .75f) / .25f, 0, 1);
            return radius * (1 - close * close * (3 - 2 * close));
        }

        /** How hot it is at its hottest, deep inside, from 0 to 1. */
        float heat() {return HoleHeat.heat(ClientState.since(start, 0), HoleHeat.WALL, 1, 0);}

        boolean cold() {return HoleHeat.cold(ClientState.since(start, 0), HoleHeat.WALL, (float) (leave - enter));}

        /**
         * Where it is heard from: the point of its run of walls nearest the listener, while any of it is still
         * hot and any block of it still holed; null after that.
         */
        Vec3 heardFrom() {
            if (!standing || cold()) return null;
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            return from.add(along.scale(Mth.clamp(ear.subtract(from).dot(along), enter, leave)));
        }

        /** How far along the line a point of the block at {@code pos} lies. */
        double along(BlockPos pos, double x, double y, double z) {
            return (pos.getX() + x - from.x) * along.x + (pos.getY() + y - from.y) * along.y + (pos.getZ() + z - from.z) * along.z;
        }

        /**
         * This hole at radius {@code r}, in the space of the block at {@code pos} (its corner at the origin),
         * from the point of the line nearest the block's middle, so every number stays small.
         */
        WoundCarve.Cylinder local(BlockPos pos, float r) {
            double t = along(pos, .5, .5, .5);
            return new WoundCarve.Cylinder((float) (from.x + along.x * t - pos.getX()), (float) (from.y + along.y * t - pos.getY()),
                (float) (from.z + along.z * t - pos.getZ()), (float) along.x, (float) along.y, (float) along.z, r);
        }
    }

    /**
     * A hole where it passes through one block: the block's own cylinder, how far along the line its point is,
     * and how many ticks ago the beam went through.
     */
    private record Cut(Hole hole, WoundCarve.Cylinder local, double base, float age) {
        /** How deep into the run of walls a point of the block lies: 0 at either end of it, 1 halfway. */
        float depth(float x, float y, float z) {
            double run = hole.leave - hole.enter;
            if (run < 1e-6) return 0;
            float along = (float) Mth.clamp((base + local.along(x, y, z) - hole.enter) / run, 0, 1);
            return 1 - Math.abs(along * 2 - 1);
        }

        /** The light the hole's heat gives off at a point of the block, into {@code rgb}. */
        float[] glow(float x, float y, float z, float[] rgb) {
            return HoleHeat.glow(age, HoleHeat.WALL, depth(x, y, z), (float) (base + local.along(x, y, z) - hole.enter), rgb);
        }
    }

    /** A block holes are carved from, and how far it is through leaving its chunk's mesh, or going back. */
    private static final class Spot {
        final BlockPos pos;
        final BlockState state;
        final List<Hole> holes = new ArrayList<>();
        /** Beside a hole, not in it: only scorched where the scorch round a mouth runs over onto it, never carved. */
        boolean scorched;
        /** Soft, a plant or the like the beam went through without being stopped: only cut, nothing more. */
        boolean soft;
        /** Drawn by its block entity (a chest, a bed), never by its chunk: carved as that draws it, from the start. */
        boolean entity;
        /** The frame a chunk build was first seen leaving it out; -1 while its chunk still draws it. */
        int out = -1;
        /** Once its holes have closed: the tick they did, and the frame a build was seen taking it back. */
        long closed = -1;
        int back = -1;
        /** When it was holed, and its chunk asked to leave it out. */
        long since = ClientState.now();

        Spot(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }

        boolean carved() {return !scorched && closed < 0 && out >= 0;}
    }

    private static final Map<Long, Spot> SPOTS = new HashMap<>();
    /** What chunk builds leave out, and what they are watched putting back: read on their own threads, so replaced whole. */
    private static volatile LongSet hidden = LongSets.EMPTY_SET, returning = LongSets.EMPTY_SET;
    /** What those builds have been seen doing, noted on their threads. */
    private static final Set<Long> LEFT_OUT = ConcurrentHashMap.newKeySet(), TAKEN_BACK = ConcurrentHashMap.newKeySet();
    /** The block states holes are open in, or closing in, whose models are handed out wrapped; replaced whole. */
    private static volatile Set<BlockState> holedStates = Set.of();
    private static final Map<BakedModel, HoleModel> WRAPPED = new ConcurrentHashMap<>();
    /** Every shot's holes while they are still hot, for their smoke. */
    private static final List<Hole> HOLES = new ArrayList<>();
    /** Hanging things the beam went through (item frames, paintings), by entity id, with the holes through them. */
    private static final Map<Integer, List<Hole>> THROUGH = new HashMap<>();
    /**
     * This frame's glow, gathered while the blocks are drawn and added over them once they all are: polygons of
     * {@link #LIT} floats a corner, x, y and z in view space and the red, green and blue light it gives off.
     */
    private static final List<float[]> GLOW = new ArrayList<>();
    private static final int LIT = 6;
    private static int frame;
    private static boolean warned;

    public static void clear() {
        for (Hole hole : HOLES) if (hole.sizzle != null) hole.sizzle.end();
        SPOTS.clear();
        HOLES.clear();
        THROUGH.clear();
        GLOW.clear();
        hidden = LongSets.EMPTY_SET;
        returning = LongSets.EMPTY_SET;
        holedStates = Set.of();
        WRAPPED.clear();
        LEFT_OUT.clear();
        TAKEN_BACK.clear();
    }

    /** From chunk building, on its own threads: whether the block at {@code pos} is left out of the mesh. */
    public static boolean hidden(BlockPos pos) {
        LongSet out = hidden, back = returning;
        if (out.isEmpty() && back.isEmpty()) return false;
        long key = pos.asLong();
        if (out.contains(key)) {
            LEFT_OUT.add(key);
            return true;
        }
        if (back.contains(key)) TAKEN_BACK.add(key);
        return false;
    }

    /**
     * From every model lookup, on any thread: while holes are open in blocks of {@code state}, its model
     * wrapped so that chunk building leaves those blocks out; otherwise null, and the lookup is untouched.
     */
    public static BakedModel model(BlockState state, BakedModel original) {
        Set<BlockState> states = holedStates;
        if (states.isEmpty() || original instanceof HoleModel || !states.contains(state)) return null;
        return WRAPPED.computeIfAbsent(original, HoleModel::new);
    }

    /**
     * A shot's holes, from its blast: the line it went down, as far as it went, and the walls it went through
     * on the way; and anything soft or hanging in its way.
     */
    public static void open(CompoundTag n) {
        ClientLevel level = Minecraft.getInstance().level;
        long[] walls = n.getLongArray("walls");
        Vec3 along = new Vec3(n.getDouble("dx"), n.getDouble("dy"), n.getDouble("dz"));
        if (level == null || !n.contains("hr") || along.lengthSqr() < 1e-8) return;
        Hole hole = new Hole(new Vec3(n.getDouble("ex"), n.getDouble("ey"), n.getDouble("ez")), along.normalize(), n.getFloat("hr"),
            n.getLong("hs"), Math.max(1, n.getInt("hl")));
        double reach = new Vec3(n.getDouble("tx"), n.getDouble("ty"), n.getDouble("tz")).subtract(hole.from).dot(hole.along);
        softs(level, hole, reach);
        hangings(level, hole, reach);
        if (walls.length > 0) run(level, hole, walls, n);
        refresh();
    }

    /** The walls a shot went through: the run of them it holed, the blocks beside its edges, and the scorch round its mouths. */
    private static void run(ClientLevel level, Hole hole, long[] walls, CompoundTag n) {
        // Where the run of walls begins and ends along the line: its first way in and its last way out.
        hole.enter = Double.POSITIVE_INFINITY;
        hole.leave = Double.NEGATIVE_INFINITY;
        List<double[]> inside = new ArrayList<>();
        for (long wall : walls) {
            BlockPos pos = BlockPos.of(wall);
            WoundCarve.Cylinder line = hole.local(pos, hole.radius);
            double base = hole.along(pos, .5, .5, .5);
            for (AABB box : level.getBlockState(pos).getShape(level, pos).toAabbs()) {
                float[] span = WoundCarve.span(WoundCarve.boxPlanes(box(box)), line.cx, line.cy, line.cz, line.ax, line.ay, line.az);
                if (span == null) continue;
                hole.enter = Math.min(hole.enter, base + span[0]);
                hole.leave = Math.max(hole.leave, base + span[1]);
                inside.add(new double[]{base + span[0], base + span[1]});
            }
        }
        // None of it is solid on this client: a chunk not loaded here, or already changed.
        if (hole.enter > hole.leave) return;
        // Its mouths: each end of every stretch of the line inside the walls, wherever the air is just past it.
        inside.sort(Comparator.comparingDouble(stretch -> stretch[0]));
        double first = inside.get(0)[0], last = inside.get(0)[1];
        for (int i = 1; i <= inside.size(); i++) {
            if (i < inside.size() && inside.get(i)[0] <= last + 1e-3) {
                last = Math.max(last, inside.get(i)[1]);
                continue;
            }
            mouth(level, hole, first, -1);
            mouth(level, hole, last, 1);
            if (i < inside.size()) {
                first = inside.get(i)[0];
                last = inside.get(i)[1];
            }
        }
        HOLES.add(hole);
        LongSet run = new LongOpenHashSet(walls), tried = new LongOpenHashSet();
        boolean stopped = n.contains("stop");
        long stop = n.getLong("stop");
        for (long wall : walls) carve(level, hole, BlockPos.of(wall), false);
        // A hole near a block's edge runs on into the block beside it, as it would through a body; never
        // into the block that stopped it, which it only met.
        for (long wall : walls)
            for (Direction d : Direction.values()) {
                BlockPos next = BlockPos.of(wall).relative(d);
                long key = next.asLong();
                if (run.contains(key) || stopped && key == stop || !tried.add(key)) continue;
                if (reaches(level, hole, next, hole.radius)) carve(level, hole, next, false);
            }
        // And the scorch round a mouth runs over onto the faces of the blocks beside it, carved or not.
        List<BlockPos> holed = new ArrayList<>();
        for (Spot spot : SPOTS.values()) if (!spot.scorched && !spot.soft && spot.holes.contains(hole)) holed.add(spot.pos);
        for (BlockPos pos : holed)
            for (Direction d : Direction.values()) {
                BlockPos next = pos.relative(d);
                Spot there = SPOTS.get(next.asLong());
                if (there != null && !there.scorched || stopped && next.asLong() == stop) continue;
                if (reaches(level, hole, next, hole.radius * WoundCarve.RIM)) scorched(level, hole, next);
            }
    }

    /**
     * Everything soft the beam went through, as far as it went: a block nothing collides with (grass, a
     * flower, a sapling) whose outline its cylinder cuts. Sampled down the line, a quarter block at a time,
     * across the whole width of the hole.
     */
    private static void softs(ClientLevel level, Hole hole, double reach) {
        LongSet tried = new LongOpenHashSet();
        double r = hole.radius;
        for (double t = 0; t <= reach; t += .25)
            for (int i = 0; i < 27; i++) {
                BlockPos pos = BlockPos.containing(hole.from.x + hole.along.x * t + (i % 3 - 1) * r, hole.from.y + hole.along.y * t + (i / 3 % 3 - 1) * r,
                    hole.from.z + hole.along.z * t + (i / 9 - 1) * r);
                if (!tried.add(pos.asLong())) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || !state.getFluidState().isEmpty()
                    || !state.getCollisionShape(level, pos).isEmpty()) continue;
                WoundCarve.Cylinder line = hole.local(pos, hole.radius);
                double base = hole.along(pos, .5, .5, .5);
                for (AABB box : state.getShape(level, pos).toAabbs()) {
                    float[] b = box(box);
                    float[] q = WoundCarve.nearest(b, line.cx, line.cy, line.cz, line.ax, line.ay, line.az);
                    double at = base + line.along(q[0], q[1], q[2]);
                    if (q[3] < r && at > -r && at < reach + r) {carve(level, hole, pos, true); break;}
                }
            }
    }

    /** The hanging things the beam went through, as far as it went: item frames and paintings, holed as they are drawn. */
    private static void hangings(ClientLevel level, Hole hole, double reach) {
        Vec3 end = hole.from.add(hole.along.scale(Math.max(0, reach)));
        for (HangingEntity e : level.getEntitiesOfClass(HangingEntity.class, new AABB(hole.from, end).inflate(hole.radius + 1))) {
            AABB b = e.getBoundingBox();
            float[] box = {(float) (b.minX - hole.from.x), (float) (b.minY - hole.from.y), (float) (b.minZ - hole.from.z),
                (float) (b.maxX - hole.from.x), (float) (b.maxY - hole.from.y), (float) (b.maxZ - hole.from.z)};
            float[] q = WoundCarve.nearest(box, 0, 0, 0, (float) hole.along.x, (float) hole.along.y, (float) hole.along.z);
            double at = q[0] * hole.along.x + q[1] * hole.along.y + q[2] * hole.along.z;
            if (q[3] >= hole.radius || at < -hole.radius || at > reach + hole.radius) continue;
            List<Hole> holes = THROUGH.computeIfAbsent(e.getId(), k -> new ArrayList<>());
            if (holes.size() >= 8) holes.remove(0);
            holes.add(hole);
        }
    }

    /**
     * From the entity render dispatcher, for every entity drawn: its buffers, carved if the beam went through
     * it and the hole is still open. Only hanging things are ever holed this way.
     */
    public static MultiBufferSource hanging(Entity entity, MultiBufferSource buffers) {
        if (THROUGH.isEmpty()) return buffers;
        List<Hole> holes = THROUGH.get(entity.getId());
        Matrix4f view = BeamWounds.view();
        if (holes == null || view == null) return buffers;
        Vec3 camera = BeamWounds.camera();
        float partial = Minecraft.getInstance().getFrameTime();
        List<WoundCarve.Cylinder> cuts = new ArrayList<>(holes.size());
        for (Hole hole : holes) {
            float r = hole.radius(partial);
            if (r < .004f) continue;
            Vector3f p = view.transformPosition(new Vector3f((float) (hole.from.x - camera.x), (float) (hole.from.y - camera.y), (float) (hole.from.z - camera.z)));
            Vector3f a = view.transformDirection(new Vector3f((float) hole.along.x, (float) hole.along.y, (float) hole.along.z));
            cuts.add(new WoundCarve.Cylinder(p.x, p.y, p.z, a.x, a.y, a.z, r));
        }
        return cuts.isEmpty() ? buffers : QuadCarver.carve(buffers, () -> cuts, () -> true);
    }

    /**
     * From the block entity render dispatcher, for every block entity drawn, its pose already at the block:
     * its buffers, carved if a hole is open through the block (a chest, a bed, or whatever else a block entity
     * draws over a holed block).
     */
    public static MultiBufferSource entityBuffers(BlockPos pos, PoseStack pose, MultiBufferSource buffers) {
        if (SPOTS.isEmpty()) return buffers;
        Spot spot = SPOTS.get(pos.asLong());
        if (spot == null || spot.soft || !spot.carved()) return buffers;
        float partial = Minecraft.getInstance().getFrameTime();
        Matrix4f m = pose.last().pose();
        List<WoundCarve.Cylinder> cuts = new ArrayList<>(spot.holes.size());
        for (Hole hole : spot.holes) {
            float r = hole.radius(partial);
            if (r >= .004f) cuts.add(inView(hole.local(spot.pos, r), m));
        }
        return cuts.isEmpty() ? buffers : QuadCarver.carve(buffers, () -> cuts, () -> true);
    }

    /** Notes a mouth of the hole {@code at} along its line, opening {@code way} along it, if the air is just past it there. */
    private static void mouth(ClientLevel level, Hole hole, double at, int way) {
        Vec3 point = hole.from.add(hole.along.scale(at)), past = point.add(hole.along.scale(way * .05));
        BlockPos pos = BlockPos.containing(past);
        if (!level.getFluidState(pos).isEmpty()) return;
        for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) if (box.move(pos).contains(past)) return;
        hole.mouths.add(new double[]{point.x, point.y, point.z, hole.along.x * way, hole.along.y * way, hole.along.z * way, at - hole.enter});
    }

    /** Whether {@code reach} of the hole's axis takes in some of the solid block at {@code pos}, along its run of walls. */
    private static boolean reaches(ClientLevel level, Hole hole, BlockPos pos, float reach) {
        BlockState state = level.getBlockState(pos);
        if (state.getCollisionShape(level, pos).isEmpty()) return false;
        WoundCarve.Cylinder line = hole.local(pos, hole.radius);
        double base = hole.along(pos, .5, .5, .5);
        for (AABB box : state.getShape(level, pos).toAabbs()) {
            float[] b = box(box);
            double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                double at = base + line.along(b[(i & 1) == 0 ? 0 : 3], b[(i & 2) == 0 ? 1 : 4], b[(i & 4) == 0 ? 2 : 5]);
                first = Math.min(first, at);
                last = Math.max(last, at);
            }
            if (last <= hole.enter || first >= hole.leave) continue;
            if (WoundCarve.nearest(b, line.cx, line.cy, line.cz, line.ax, line.ay, line.az)[3] < reach) return true;
        }
        return false;
    }

    private static void carve(ClientLevel level, Hole hole, BlockPos pos, boolean soft) {
        BlockState state = level.getBlockState(pos);
        // A block drawn from its model is carved from it; one its block entity draws (a chest, a bed) is carved as that draws it.
        boolean entity = state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED && level.getBlockEntity(pos) != null;
        if (!entity && state.getRenderShape() != RenderShape.MODEL || state.getShape(level, pos).isEmpty()) return;
        long key = pos.asLong();
        Spot spot = SPOTS.get(key);
        if (spot == null || spot.state != state) {
            spot = new Spot(pos.immutable(), state);
            spot.soft = soft;
            spot.entity = entity;
            // Never in its chunk's mesh, so there is nothing to wait for: carved from the first frame.
            if (entity) spot.out = frame;
            SPOTS.put(key, spot);
            forget(key);
        } else if (spot.scorched) {
            // Only scorched until now: from here it leaves its mesh like any other.
            spot.scorched = false;
            spot.entity = entity;
            spot.out = entity ? frame : -1;
            spot.closed = -1;
            forget(key);
        } else if (spot.closed >= 0) {
            // Holed again on its way back into its mesh: if a build has already taken it back, it must be
            // seen leaving again before it is drawn carved.
            if (spot.back >= 0) {
                spot.out = -1;
                LEFT_OUT.remove(key);
            }
            spot.closed = -1;
            spot.back = -1;
            TAKEN_BACK.remove(key);
            if (spot.entity) spot.out = frame;
        }
        if (!spot.holes.contains(hole)) spot.holes.add(hole);
    }

    /** Marks a block beside a hole for the scorch that runs over onto it, unless it is already holed. */
    private static void scorched(ClientLevel level, Hole hole, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Scorch is burnt onto something solid; a plant beside a hole would only wear a floating ring.
        if (state.getShape(level, pos).isEmpty() || state.getCollisionShape(level, pos).isEmpty()) return;
        long key = pos.asLong();
        Spot spot = SPOTS.get(key);
        if (spot == null || spot.state != state) {
            spot = new Spot(pos.immutable(), state);
            spot.scorched = true;
            SPOTS.put(key, spot);
        }
        if (!spot.holes.contains(hole)) spot.holes.add(hole);
    }

    private static void forget(long key) {
        LEFT_OUT.remove(key);
        TAKEN_BACK.remove(key);
    }

    /** Tells chunk building what to leave out now, and has every chunk whose answer changed built again. */
    private static void refresh() {
        LongSet out = new LongOpenHashSet(), back = new LongOpenHashSet();
        Set<BlockState> states = new HashSet<>();
        for (Spot spot : SPOTS.values()) {
            if (spot.scorched || spot.entity) continue;
            (spot.closed < 0 ? out : back).add(spot.pos.asLong());
            states.add(spot.state);
        }
        LongSet was = hidden;
        hidden = out.isEmpty() ? LongSets.EMPTY_SET : out;
        returning = back.isEmpty() ? LongSets.EMPTY_SET : back;
        holedStates = states.isEmpty() ? Set.of() : states;
        if (states.isEmpty()) WRAPPED.clear();
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        LongSet changed = new LongOpenHashSet(out);
        was.forEach((long key) -> {if (!changed.remove(key)) changed.add(key);});
        changed.forEach((long key) -> {
            BlockPos pos = BlockPos.of(key);
            renderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        });
    }

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {clear(); return;}
        smoulder(level);
        long now = ClientState.now();
        if (!THROUGH.isEmpty())
            THROUGH.entrySet().removeIf(entry -> {
                entry.getValue().removeIf(hole -> now >= hole.start + hole.life);
                return entry.getValue().isEmpty() || level.getEntity(entry.getKey()) == null;
            });
        if (SPOTS.isEmpty()) return;
        boolean changed = false;
        for (Iterator<Spot> it = SPOTS.values().iterator(); it.hasNext(); ) {
            Spot spot = it.next();
            long key = spot.pos.asLong();
            // Changed under the hole, broken or replaced: what is there now is its chunk's to draw.
            if (level.hasChunkAt(spot.pos) && level.getBlockState(spot.pos) != spot.state) {
                forget(key);
                it.remove();
                changed = true;
                continue;
            }
            spot.holes.removeIf(hole -> now >= hole.start + hole.life);
            if (spot.scorched) {
                if (spot.holes.isEmpty()) it.remove();
                continue;
            }
            if (!warned && spot.closed < 0 && spot.out < 0 && now - spot.since > 200) {
                warned = true;
                LOGGER.warn("Scepter holes in walls are drawn as scorch marks here: ten seconds on, no chunk build has left the holed {} "
                    + "at {} out of its mesh. The chunk renderer meshes neither through vanilla's own call nor through Forge's "
                    + "block models.", spot.state, spot.pos);
            }
            if (spot.holes.isEmpty() && spot.closed < 0) {
                spot.closed = now;
                spot.back = -1;
                TAKEN_BACK.remove(key);
                changed = true;
            }
            // Back in its mesh, or never out of it, or its chunk has gone: nothing is left to draw here.
            if (spot.closed >= 0 && (spot.entity || spot.out < 0 || spot.back >= 0 && frame - spot.back >= 3 || now - spot.closed > 100)) {
                forget(key);
                it.remove();
                changed = true;
            }
        }
        if (changed) refresh();
    }

    /**
     * A little smoke off every mouth of a hole still hot, and now and then a molten drop running off its lower
     * edge, while the block it opens from is still holed; and the hole sizzling for as long as any of it glows
     * and any block of it is still holed ({@link HoleSizzle}). The drops go as the heat does, and soonest.
     */
    private static void smoulder(ClientLevel level) {
        HOLES.removeIf(Hole::cold);
        if (HOLES.isEmpty()) return;
        Set<Hole> standing = new HashSet<>();
        for (Spot spot : SPOTS.values()) if (!spot.scorched && !spot.soft) standing.addAll(spot.holes);
        for (Hole hole : HOLES) {
            hole.standing = standing.contains(hole);
            // Started once it is holed, and again whenever it has stopped being heard (out of earshot, no room).
            if (hole.standing && (hole.sizzle == null || !hole.sizzle.heard())) hole.sizzle = HoleSizzle.start(hole::heardFrom, hole::heat);
            float age = ClientState.since(hole.start, 0);
            for (double[] mouth : hole.mouths) {
                float heat = HoleHeat.heat(age, HoleHeat.WALL, 0, (float) mouth[6]);
                if (heat <= 0) continue;
                Spot spot = SPOTS.get(BlockPos.containing(mouth[0] - mouth[3] * .05, mouth[1] - mouth[4] * .05, mouth[2] - mouth[5] * .05).asLong());
                if (spot == null || !spot.holes.contains(hole)) continue;
                ScepterFx.holeSmoke(mouth[0], mouth[1], mouth[2], mouth[3], mouth[4], mouth[5], hole.radius, heat);
                if (level.random.nextFloat() < .05f * heat * heat) drip(level, mouth, hole.radius);
            }
        }
    }

    /**
     * A drop of lava hanging off a hot mouth, then falling: from the lowest point of the hole's edge, where the
     * mouth meets the face it opens in, just out in the air. Vanilla's own drip, which cools as it hangs.
     */
    private static void drip(ClientLevel level, double[] mouth, float radius) {
        double px = mouth[0], py = mouth[1], pz = mouth[2], ax = mouth[3], ay = mouth[4], az = mouth[5];
        // The face the mouth is in: the one whose plane the point lies on, facing the way out.
        double[] off = {Math.abs(px - Math.rint(px)), Math.abs(py - Math.rint(py)), Math.abs(pz - Math.rint(pz))};
        int k = off[0] <= off[1] && off[0] <= off[2] ? 0 : off[1] <= off[2] ? 1 : 2;
        double[] way = {ax, ay, az}, n = new double[3];
        n[k] = way[k] < 0 ? -1 : 1;
        // Down across the hole: the lowest point of its edge. A hole running straight up or down has none, so any.
        double dx = ay * ax, dy = -1 + ay * ay, dz = ay * az, dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < .2) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double ux = Math.abs(ay) < .9 ? -az : 0, uy = Math.abs(ay) < .9 ? 0 : az, uz = Math.abs(ay) < .9 ? ax : -ay;
            double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= ul; uy /= ul; uz /= ul;
            double vx = ay * uz - az * uy, vy = az * ux - ax * uz, vz = ax * uy - ay * ux;
            dx = ux * Math.cos(angle) + vx * Math.sin(angle);
            dy = uy * Math.cos(angle) + vy * Math.sin(angle);
            dz = uz * Math.cos(angle) + vz * Math.sin(angle);
            dl = 1;
        }
        double qx = px + dx / dl * radius * .9, qy = py + dy / dl * radius * .9, qz = pz + dz / dl * radius * .9;
        // Along the hole's side back onto the face, and a hair out of it.
        double across = way[k] * n[k];
        double t = Math.abs(across) < 1e-3 ? 0 : -((qx - px) * n[0] + (qy - py) * n[1] + (qz - pz) * n[2]) / across;
        level.addParticle(ParticleTypes.DRIPPING_LAVA, qx + ax * t + n[0] * .03, qy + ay * t + n[1] * .03, qz + az * t + n[2] * .03, 0, 0, 0);
    }

    /** Every frame, once the entities are drawn: the carved blocks, their tunnels, what they open onto, the scorch and the heat. */
    public static void render(RenderLevelStageEvent e) {
        frame++;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        GLOW.clear();
        if (level == null || SPOTS.isEmpty()) return;
        // What chunk building has been seen doing since the last frame. A carved block is drawn here from the
        // frame its mesh is seen leaving it out: for a frame or two the old mesh still has it, drawn the same.
        for (Spot spot : SPOTS.values()) {
            long key = spot.pos.asLong();
            if (spot.closed < 0 && spot.out < 0 && LEFT_OUT.remove(key)) spot.out = frame;
            if (spot.closed >= 0 && spot.back < 0 && TAKEN_BACK.remove(key)) spot.back = frame;
        }
        float partial = e.getPartialTick();
        Vec3 camera = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Set<RenderType> used = new HashSet<>();
        List<Spot> failed = new ArrayList<>();
        for (Spot spot : SPOTS.values()) {
            if (!level.hasChunkAt(spot.pos)) continue;
            pose.pushPose();
            pose.translate(spot.pos.getX() - camera.x, spot.pos.getY() - camera.y, spot.pos.getZ() - camera.z);
            try {
                if (spot.closed >= 0) {
                    // Closed, and waiting for its chunk to take it back: drawn whole meanwhile.
                    if (spot.out >= 0 && !spot.entity) model(level, spot.state, spot.pos, pose, buffers, used, true, UnaryOperator.identity());
                } else {
                    List<Cut> cuts = new ArrayList<>(spot.holes.size());
                    for (Hole hole : spot.holes) {
                        float r = hole.radius(partial);
                        if (r >= .004f) cuts.add(new Cut(hole, hole.local(spot.pos, r), hole.along(spot.pos, .5, .5, .5), ClientState.since(hole.start, partial)));
                    }
                    if (spot.scorched) scorch(level, spot, cuts, pose.last().pose(), buffers, used, Mark.BESIDE);
                    else if (spot.soft) {
                        // A plant is only cut away; left in its chunk, it is simply left whole.
                        if (spot.out >= 0) model(level, spot.state, spot.pos, pose, buffers, used, true, out -> new QuadCarver.Carved(out, seen(cuts, pose)));
                    } else if (spot.out >= 0) carved(level, spot, cuts, pose, buffers, used);
                    else scorch(level, spot, cuts, pose.last().pose(), buffers, used, Mark.DISC);
                }
            } catch (Throwable t) {
                // A block's model is arbitrary code, a mod's as often as not: one that cannot be drawn here
                // goes back to its chunk, whole, rather than costing the session. The machine's own failures
                // are not ours to swallow.
                if (t instanceof VirtualMachineError error) throw error;
                LOGGER.warn("A Scepter hole could not be drawn in {} at {}; the block is drawn whole instead", spot.state, spot.pos, t);
                failed.add(spot);
            } finally {
                pose.popPose();
            }
        }
        for (RenderType type : used) buffers.endBatch(type);
        // The heat, added over everything above once all of it is drawn, so it is hidden only by what is in front of it.
        if (!GLOW.isEmpty()) {
            RenderType type = ScepterRenderTypes.glow(WorldEffects.WHITE);
            VertexConsumer out = buffers.getBuffer(type);
            for (float[] polygon : GLOW) emitGlow(out, polygon);
            buffers.endBatch(type);
            GLOW.clear();
        }
        if (failed.isEmpty()) return;
        for (Spot spot : failed) {
            forget(spot.pos.asLong());
            SPOTS.remove(spot.pos.asLong());
        }
        refresh();
    }

    /** The cuts in view space, for quads that arrive there. */
    private static List<WoundCarve.Cylinder> seen(List<Cut> cuts, PoseStack pose) {
        Matrix4f m = pose.last().pose();
        List<WoundCarve.Cylinder> seen = new ArrayList<>(cuts.size());
        for (Cut cut : cuts) seen.add(inView(cut.local, m));
        return seen;
    }

    /**
     * A block left out of its mesh, drawn less its holes, with their walls, what they open onto, and the scorch.
     * A block its block entity draws is carved as that draws it; here it only gets the rest.
     */
    private static void carved(ClientLevel level, Spot spot, List<Cut> cuts, PoseStack pose, MultiBufferSource buffers, Set<RenderType> used) {
        Matrix4f m = pose.last().pose();
        List<WoundCarve.Cylinder> seen = seen(cuts, pose);
        if (!spot.entity) model(level, spot.state, spot.pos, pose, buffers, used, true, out -> new QuadCarver.Carved(out, seen));
        if (cuts.isEmpty()) return;
        walls(level, spot, cuts, pose, buffers, used);
        for (Direction d : Direction.values()) {
            BlockPos next = spot.pos.relative(d);
            Spot other = SPOTS.get(next.asLong());
            if (other != null && other.carved()) continue;
            BlockState state = level.getBlockState(next);
            // A face the chunk already draws, or none at all.
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || Block.shouldRenderFace(state, level, next, d.getOpposite(), spot.pos)) continue;
            float[] shared = WoundCarve.boxFace(FULL, face(d));
            boolean through = false;
            for (Cut cut : cuts) through |= WoundCarve.opening(shared, cut.local) != null;
            if (!through) continue;
            Vector3f facing = pose.last().normal().transform(new Vector3f(-d.getStepX(), -d.getStepY(), -d.getStepZ()));
            // Where the hole meets that face is as hot as the hole is there.
            List<float[]> heat = new ArrayList<>(cuts.size());
            for (Cut cut : cuts) heat.add(cut.glow(.5f + .5f * d.getStepX(), .5f + .5f * d.getStepY(), .5f + .5f * d.getStepZ(), new float[3]));
            pose.pushPose();
            pose.translate(d.getStepX(), d.getStepY(), d.getStepZ());
            try {
                model(level, state, next, pose, buffers, used, false, out -> new Cap(out, seen, facing, heat));
            } finally {
                pose.popPose();
            }
        }
        scorch(level, spot, cuts, m, buffers, used, Mark.RIM);
    }

    /**
     * Draws a block from its own model, as its chunk would, through {@code through}: with every face its
     * chunk would hide left out when {@code sides} is true, or with every face when it is not.
     */
    private static void model(ClientLevel level, BlockState state, BlockPos pos, PoseStack pose, MultiBufferSource buffers, Set<RenderType> used,
                              boolean sides, UnaryOperator<VertexConsumer> through) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(pos);
        RandomSource random = RandomSource.create(seed);
        ModelData data = model.getModelData(level, pos, state, ModelData.EMPTY);
        for (RenderType type : model.getRenderTypes(state, random, data)) {
            RenderType target = RenderTypeHelper.getMovingBlockRenderType(type);
            used.add(target);
            blocks.getModelRenderer().tesselateBlock(level, model, state, pos, pose, through.apply(buffers.getBuffer(target)), sides, random, seed,
                OverlayTexture.NO_OVERLAY, data, type);
        }
    }

    /** The tunnel through a carved block: each hole's sides where they run inside it, in its own texture, charred deeper in. */
    private static void walls(ClientLevel level, Spot spot, List<Cut> cuts, PoseStack pose, MultiBufferSource buffers, Set<RenderType> used) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(spot.state);
        TextureAtlasSprite sprite = model.getParticleIcon(model.getModelData(level, spot.pos, spot.state, ModelData.EMPTY));
        // Tinted as the block is, except a grass block: its inside is dirt, which vanilla never tints either.
        int tint = spot.state.is(Blocks.GRASS_BLOCK) ? -1 : Minecraft.getInstance().getBlockColors().getColor(spot.state, level, spot.pos, 0);
        float tr = tint == -1 ? 1 : (tint >> 16 & 255) / 255f, tg = tint == -1 ? 1 : (tint >> 8 & 255) / 255f, tb = tint == -1 ? 1 : (tint & 255) / 255f;
        int light = light(level, spot.pos);
        Matrix4f m = pose.last().pose();
        Matrix3f normals = pose.last().normal();
        VertexConsumer out = buffers.getBuffer(RenderType.solid());
        used.add(RenderType.solid());
        for (AABB box : spot.state.getShape(level, spot.pos).toAabbs()) {
            float[] b = box(box), planes = WoundCarve.boxPlanes(b);
            for (Cut cut : cuts) {
                float reach = WoundCarve.reach(b, cut.local);
                for (int k = 0; k < WoundCarve.SIDES; k++) {
                    float[] wall = WoundCarve.wall(cut.local, k, planes, reach);
                    if (wall == null) continue;
                    // No wall stands where another hole has taken the block away.
                    List<float[]> pieces = List.of(wall);
                    for (Cut other : cuts) if (other != cut) pieces = QuadCarver.minus(pieces, 3, other.local);
                    for (float[] piece : pieces) {
                        wall(out, piece, cut, sprite, tr, tg, tb, light, m, normals);
                        glow(BeamWounds.nearer(BeamWounds.transform(m, piece), .003f), heat(piece, cut, null));
                    }
                }
            }
        }
    }

    /** One piece of tunnel wall. It is cut facing out of the tunnel, so it is drawn turned round, to be seen from inside. */
    private static void wall(VertexConsumer out, float[] piece, Cut cut, TextureAtlasSprite sprite, float tr, float tg, float tb, int light,
                             Matrix4f m, Matrix3f normals) {
        float[] n = WoundCarve.normal(piece);
        float nx = -n[0], ny = -n[1], nz = -n[2];
        // Textured as if the block had been sawn along the face most square to this piece of wall.
        int axis = Math.abs(nx) > Math.abs(ny) ? Math.abs(nx) > Math.abs(nz) ? 0 : 2 : Math.abs(ny) > Math.abs(nz) ? 1 : 2;
        float shade = nx * nx * .6f + nz * nz * .8f + ny * ny * (ny > 0 ? 1 : .5f);
        int count = piece.length / 3;
        float[] polygon = new float[count * STRIDE];
        Vector3f p = new Vector3f();
        for (int i = 0; i < count; i++) {
            int from = (count - 1 - i) * 3, o = i * STRIDE;
            float x = piece[from], y = piece[from + 1], z = piece[from + 2];
            float s = axis == 0 ? z : x, t = axis == 1 ? z : y;
            float light01 = (MOUTH + (DEEP - MOUTH) * cut.depth(x, y, z)) * shade;
            m.transformPosition(p.set(x, y, z));
            polygon[o] = p.x; polygon[o + 1] = p.y; polygon[o + 2] = p.z;
            polygon[o + 3] = tr * light01; polygon[o + 4] = tg * light01; polygon[o + 5] = tb * light01; polygon[o + 6] = 1;
            polygon[o + 7] = sprite.getU(Mth.clamp(s, 0, 1) * 16);
            polygon[o + 8] = sprite.getV((1 - Mth.clamp(t, 0, 1)) * 16);
            polygon[o + 9] = light & 0xFFFF;
            polygon[o + 10] = light >>> 16;
        }
        Vector3f normal = normals.transform(new Vector3f(nx, ny, nz));
        QuadCarver.quads(out, polygon, OverlayTexture.NO_OVERLAY, normal.x, normal.y, normal.z);
    }

    /** What is burnt onto a face: the scorch round a mouth; that and a charred disc for the hole; or the scorch run over from beside. */
    private enum Mark {RIM, DISC, BESIDE}

    /**
     * The scorch round each mouth, on every face of the block its chunk would show that the hole opens, or
     * that one beside it runs over onto; and, while its chunk still draws the block whole, a charred disc
     * over each opening in place of the hole.
     */
    private static void scorch(ClientLevel level, Spot spot, List<Cut> cuts, Matrix4f m, MultiBufferSource buffers, Set<RenderType> used, Mark mark) {
        if (cuts.isEmpty()) return;
        RenderType type = ScepterRenderTypes.glass(WorldEffects.WHITE);
        VertexConsumer out = buffers.getBuffer(type);
        used.add(type);
        for (AABB box : spot.state.getShape(level, spot.pos).toAabbs()) {
            float[] b = box(box);
            for (Direction d : Direction.values()) {
                int f = face(d);
                // A face on the block's own boundary shows only where its chunk would show it; one inside it
                // (the top of a slab) always does.
                boolean boundary = f % 2 == 0 ? b[f / 2 + 3] >= 1 - 1e-4f : b[f / 2] <= 1e-4f;
                if (boundary && !Block.shouldRenderFace(spot.state, level, spot.pos, d, spot.pos.relative(d))) continue;
                float[] face = WoundCarve.boxFace(b, f);
                int light = LevelRenderer.getLightColor(level, spot.pos.relative(d));
                for (Cut cut : cuts) {
                    if (mark == Mark.BESIDE) {
                        // Only a face the beam comes at, not one running alongside it, which would take a long stripe.
                        float at = d.getStepX() * cut.local.ax + d.getStepY() * cut.local.ay + d.getStepZ() * cut.local.az;
                        if (Math.abs(at) < .25f) continue;
                    } else {
                        float[] disc = WoundCarve.opening(face, cut.local);
                        if (disc == null) continue;
                        if (mark == Mark.DISC) {
                            scorch(out, BeamWounds.nearer(BeamWounds.transform(m, disc), .004f), null, light);
                            glow(BeamWounds.nearer(BeamWounds.transform(m, disc), .005f), heat(disc, cut, null));
                        }
                    }
                    for (int k = 0; k < WoundCarve.SIDES; k++) {
                        float[] piece = WoundCarve.rim(face, cut.local, k);
                        if (piece == null) continue;
                        // Not across another hole's opening.
                        List<float[]> pieces = List.of(piece);
                        for (Cut other : cuts) if (other != cut) pieces = QuadCarver.minus(pieces, 3, other.local);
                        for (float[] kept : pieces) {
                            float[] depth = new float[kept.length / 3];
                            for (int i = 0; i < depth.length; i++) depth[i] = WoundCarve.rimDepth(cut.local, k, kept[i * 3], kept[i * 3 + 1], kept[i * 3 + 2]);
                            scorch(out, BeamWounds.nearer(BeamWounds.transform(m, kept), .004f), depth, light);
                            glow(BeamWounds.nearer(BeamWounds.transform(m, kept), .005f), heat(kept, cut, depth));
                        }
                    }
                }
            }
        }
    }

    /** Burnt black at the mouth, fading out into the block's own face; a disc ({@code depth} null) is charred through. */
    private static void scorch(VertexConsumer out, float[] polygon, float[] depth, int light) {
        int n = polygon.length / 3;
        for (int i = 1; i + 1 < n; i++)
            for (int corner : new int[]{0, i, i + 1}) {
                float o = depth == null ? 0 : depth[corner];
                float r = depth == null ? .05f : .09f + .04f * o, g = depth == null ? .035f : .06f + .04f * o, b = depth == null ? .03f : .045f + .035f * o;
                out.vertex(polygon[corner * 3], polygon[corner * 3 + 1], polygon[corner * 3 + 2], r, g, b, depth == null ? .92f : .88f * (1 - o),
                    .5f, .5f, OverlayTexture.NO_OVERLAY, light, 0, 1, 0);
            }
    }

    /**
     * The light each corner of a polygon in the block's own space gives off, three floats a corner: as hot as
     * the hole is there, and on a rim ({@code out} given, 0 at the hole's edge to 1 at the scorch's) fading
     * away from the hole.
     */
    private static float[] heat(float[] polygon, Cut cut, float[] out) {
        int n = polygon.length / 3;
        float[] light = new float[n * 3], rgb = new float[3];
        for (int i = 0; i < n; i++) {
            cut.glow(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2], rgb);
            float fade = out == null ? 1 : (1 - out[i]) * (1 - out[i]);
            for (int c = 0; c < 3; c++) light[i * 3 + c] = rgb[c] * fade;
        }
        return light;
    }

    /** Adds a view-space polygon to this frame's glow, each corner giving off its three floats of {@code light}; nothing if it is all cold. */
    private static void glow(float[] polygon, float[] light) {
        int n = polygon.length / 3;
        float[] lit = new float[n * LIT];
        float most = 0;
        for (int i = 0; i < n; i++) {
            System.arraycopy(polygon, i * 3, lit, i * LIT, 3);
            System.arraycopy(light, i * 3, lit, i * LIT + 3, 3);
            most = Math.max(most, light[i * 3] + light[i * 3 + 1] + light[i * 3 + 2]);
        }
        if (most > .004f) GLOW.add(lit);
    }

    /** One polygon of glow, as the added light's triangles. */
    private static void emitGlow(VertexConsumer out, float[] p) {
        int n = p.length / LIT;
        for (int i = 1; i + 1 < n; i++)
            for (int corner : new int[]{0, i, i + 1}) {
                int o = corner * LIT;
                out.vertex(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], 1, .5f, .5f, OverlayTexture.NO_OVERLAY,
                    LightTexture.FULL_BRIGHT, 0, 1, 0);
            }
    }

    /** The light a hole's walls are seen in: the brightest of what lies around the block. */
    private static int light(ClientLevel level, BlockPos pos) {
        int block = 0, sky = 0;
        for (Direction d : Direction.values()) {
            BlockPos next = pos.relative(d);
            block = Math.max(block, level.getBrightness(LightLayer.BLOCK, next));
            sky = Math.max(sky, level.getBrightness(LightLayer.SKY, next));
        }
        return LightTexture.pack(block, sky);
    }

    private static WoundCarve.Cylinder inView(WoundCarve.Cylinder c, Matrix4f m) {
        Vector3f p = m.transformPosition(new Vector3f(c.cx, c.cy, c.cz)), a = m.transformDirection(new Vector3f(c.ax, c.ay, c.az));
        return new WoundCarve.Cylinder(p.x, p.y, p.z, a.x, a.y, a.z, c.r);
    }

    /** A direction's face, in the order of {@link WoundCarve#boxPlanes}. */
    private static int face(Direction d) {
        return switch (d) {
            case EAST -> 0;
            case WEST -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case SOUTH -> 4;
            case NORTH -> 5;
        };
    }

    private static float[] box(AABB b) {
        return new float[]{(float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ};
    }

    /** A neighbour's face turned to a carved block: only where a hole opens onto it, seared, and as hot as the hole is there. */
    private static final class Cap extends QuadCarver.Quads {
        private final List<WoundCarve.Cylinder> cuts;
        private final Vector3f facing;
        /** The light each hole gives off where it meets this face. */
        private final List<float[]> heat;

        Cap(VertexConsumer out, List<WoundCarve.Cylinder> cuts, Vector3f facing, List<float[]> heat) {
            super(out);
            this.cuts = cuts;
            this.facing = facing;
            this.heat = heat;
        }

        @Override void finish(float[] quad) {
            if (nx * facing.x + ny * facing.y + nz * facing.z < .9f) return;
            for (int k = 0; k < cuts.size(); k++) {
                float[] piece = WoundCarve.inside(quad, STRIDE, cuts.get(k));
                if (piece == null) continue;
                int n = piece.length / STRIDE;
                float[] at = new float[n * 3], lit = new float[n * 3];
                for (int i = 0; i < n; i++) {
                    int o = i * STRIDE;
                    piece[o + 3] *= SEARED;
                    piece[o + 4] *= SEARED;
                    piece[o + 5] *= SEARED;
                    System.arraycopy(piece, o, at, i * 3, 3);
                    System.arraycopy(heat.get(k), 0, lit, i * 3, 3);
                }
                QuadCarver.quads(out, piece, overlay, nx, ny, nz);
                // Only noted here: this is the middle of drawing the block, and the glow is drawn once every block is.
                glow(BeamWounds.nearer(at, .003f), lit);
            }
        }
    }
}
