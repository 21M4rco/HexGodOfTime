package com.hexgodofstories.client;

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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
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
 */
public final class BlockWounds {
    private BlockWounds() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How the inside of a hole is charred: at its mouths, and halfway down the run of walls. */
    private static final float MOUTH = .72f, DEEP = .3f;
    /** A face the hole meets at its end or its side, seared by the beam that stopped against it. */
    private static final float SEARED = .45f;
    /** What a quad's corner carries here: x, y, z, red, green, blue, alpha, u, v, block light, sky light. */
    private static final int STRIDE = 11;
    private static final float[] FULL = {0, 0, 0, 1, 1, 1};

    /** One shot's hole down a run of walls. */
    private static final class Hole {
        final Vec3 from, along;
        final float radius;
        final long start;
        final int life;
        /** How far along the line the run of walls begins and ends. */
        double enter, leave;

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

    /** A hole where it passes through one block: the block's own cylinder, and how far along the line its point is. */
    private record Cut(Hole hole, WoundCarve.Cylinder local, double base) {
        /** How deep into the run of walls a point of the block lies: 0 at either end of it, 1 halfway. */
        float depth(float x, float y, float z) {
            double run = hole.leave - hole.enter;
            if (run < 1e-6) return 0;
            float along = (float) Mth.clamp((base + local.along(x, y, z) - hole.enter) / run, 0, 1);
            return 1 - Math.abs(along * 2 - 1);
        }
    }

    /** A block holes are carved from, and how far it is through leaving its chunk's mesh, or going back. */
    private static final class Spot {
        final BlockPos pos;
        final BlockState state;
        final List<Hole> holes = new ArrayList<>();
        /** Beside a hole, not in it: only scorched where the scorch round a mouth runs over onto it, never carved. */
        boolean scorched;
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
    private static int frame;
    private static boolean warned;

    public static void clear() {
        SPOTS.clear();
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

    /** A shot's holes, from its blast: the walls it went through and the line it went down. */
    public static void open(CompoundTag n) {
        ClientLevel level = Minecraft.getInstance().level;
        long[] walls = n.getLongArray("walls");
        Vec3 along = new Vec3(n.getDouble("dx"), n.getDouble("dy"), n.getDouble("dz"));
        if (level == null || walls.length == 0 || along.lengthSqr() < 1e-8) return;
        Hole hole = new Hole(new Vec3(n.getDouble("ex"), n.getDouble("ey"), n.getDouble("ez")), along.normalize(), n.getFloat("hr"),
            n.getLong("hs"), Math.max(1, n.getInt("hl")));
        // Where the run of walls begins and ends along the line: its first way in and its last way out.
        hole.enter = Double.POSITIVE_INFINITY;
        hole.leave = Double.NEGATIVE_INFINITY;
        for (long wall : walls) {
            BlockPos pos = BlockPos.of(wall);
            WoundCarve.Cylinder line = hole.local(pos, hole.radius);
            double base = hole.along(pos, .5, .5, .5);
            for (AABB box : level.getBlockState(pos).getShape(level, pos).toAabbs()) {
                float[] span = WoundCarve.span(WoundCarve.boxPlanes(box(box)), line.cx, line.cy, line.cz, line.ax, line.ay, line.az);
                if (span == null) continue;
                hole.enter = Math.min(hole.enter, base + span[0]);
                hole.leave = Math.max(hole.leave, base + span[1]);
            }
        }
        // None of it is solid on this client: a chunk not loaded here, or already changed.
        if (hole.enter > hole.leave) return;
        LongSet run = new LongOpenHashSet(walls), tried = new LongOpenHashSet();
        boolean stopped = n.contains("stop");
        long stop = n.getLong("stop");
        for (long wall : walls) carve(level, hole, BlockPos.of(wall));
        // A hole near a block's edge runs on into the block beside it, as it would through a body; never
        // into the block that stopped it, which it only met.
        for (long wall : walls)
            for (Direction d : Direction.values()) {
                BlockPos next = BlockPos.of(wall).relative(d);
                long key = next.asLong();
                if (run.contains(key) || stopped && key == stop || !tried.add(key)) continue;
                if (reaches(level, hole, next, hole.radius)) carve(level, hole, next);
            }
        // And the scorch round a mouth runs over onto the faces of the blocks beside it, carved or not.
        List<BlockPos> holed = new ArrayList<>();
        for (Spot spot : SPOTS.values()) if (!spot.scorched && spot.holes.contains(hole)) holed.add(spot.pos);
        for (BlockPos pos : holed)
            for (Direction d : Direction.values()) {
                BlockPos next = pos.relative(d);
                Spot there = SPOTS.get(next.asLong());
                if (there != null && !there.scorched || stopped && next.asLong() == stop) continue;
                if (reaches(level, hole, next, hole.radius * WoundCarve.RIM)) scorched(level, hole, next);
            }
        refresh();
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

    private static void carve(ClientLevel level, Hole hole, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Only a block drawn from its model can be drawn carved; a chest or a bed keeps its own renderer.
        if (state.getRenderShape() != RenderShape.MODEL || state.getShape(level, pos).isEmpty()) return;
        long key = pos.asLong();
        Spot spot = SPOTS.get(key);
        if (spot == null || spot.state != state) {
            spot = new Spot(pos.immutable(), state);
            SPOTS.put(key, spot);
            forget(key);
        } else if (spot.scorched) {
            // Only scorched until now: from here it leaves its mesh like any other.
            spot.scorched = false;
            spot.out = -1;
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
        }
        if (!spot.holes.contains(hole)) spot.holes.add(hole);
    }

    /** Marks a block beside a hole for the scorch that runs over onto it, unless it is already holed. */
    private static void scorched(ClientLevel level, Hole hole, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getShape(level, pos).isEmpty()) return;
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
            if (spot.scorched) continue;
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
        if (SPOTS.isEmpty()) return;
        long now = ClientState.now();
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
            if (spot.closed >= 0 && (spot.out < 0 || spot.back >= 0 && frame - spot.back >= 3 || now - spot.closed > 100)) {
                forget(key);
                it.remove();
                changed = true;
            }
        }
        if (changed) refresh();
    }

    /** Every frame, once the entities are drawn: the carved blocks, their tunnels, what they open onto, and the scorch. */
    public static void render(RenderLevelStageEvent e) {
        frame++;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
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
                    if (spot.out >= 0) model(level, spot.state, spot.pos, pose, buffers, used, true, UnaryOperator.identity());
                } else {
                    List<Cut> cuts = new ArrayList<>(spot.holes.size());
                    for (Hole hole : spot.holes) {
                        float r = hole.radius(partial);
                        if (r >= .004f) cuts.add(new Cut(hole, hole.local(spot.pos, r), hole.along(spot.pos, .5, .5, .5)));
                    }
                    if (spot.scorched) scorch(level, spot, cuts, pose.last().pose(), buffers, used, Mark.BESIDE);
                    else if (spot.out >= 0) carved(level, spot, cuts, pose, buffers, used);
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
        if (failed.isEmpty()) return;
        for (Spot spot : failed) {
            forget(spot.pos.asLong());
            SPOTS.remove(spot.pos.asLong());
        }
        refresh();
    }

    /** A block left out of its mesh, drawn less its holes, with their walls, what they open onto, and the scorch. */
    private static void carved(ClientLevel level, Spot spot, List<Cut> cuts, PoseStack pose, MultiBufferSource buffers, Set<RenderType> used) {
        Matrix4f m = pose.last().pose();
        List<WoundCarve.Cylinder> seen = new ArrayList<>(cuts.size());
        for (Cut cut : cuts) seen.add(inView(cut.local, m));
        model(level, spot.state, spot.pos, pose, buffers, used, true, out -> new Carved(out, seen));
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
            pose.pushPose();
            pose.translate(d.getStepX(), d.getStepY(), d.getStepZ());
            try {
                model(level, state, next, pose, buffers, used, false, out -> new Cap(out, seen, facing));
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
        int tint = Minecraft.getInstance().getBlockColors().getColor(spot.state, level, spot.pos, 0);
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
                    for (Cut other : cuts) if (other != cut) pieces = minus(pieces, 3, other.local);
                    for (float[] piece : pieces) wall(out, piece, cut, sprite, tr, tg, tb, light, m, normals);
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
        quads(out, polygon, OverlayTexture.NO_OVERLAY, normal.x, normal.y, normal.z);
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
                        if (mark == Mark.DISC) scorch(out, BeamWounds.nearer(BeamWounds.transform(m, disc), .004f), null, light);
                    }
                    for (int k = 0; k < WoundCarve.SIDES; k++) {
                        float[] piece = WoundCarve.rim(face, cut.local, k);
                        if (piece == null) continue;
                        // Not across another hole's opening.
                        List<float[]> pieces = List.of(piece);
                        for (Cut other : cuts) if (other != cut) pieces = minus(pieces, 3, other.local);
                        for (float[] kept : pieces) {
                            float[] depth = new float[kept.length / 3];
                            for (int i = 0; i < depth.length; i++) depth[i] = WoundCarve.rimDepth(cut.local, k, kept[i * 3], kept[i * 3 + 1], kept[i * 3 + 2]);
                            scorch(out, BeamWounds.nearer(BeamWounds.transform(m, kept), .004f), depth, light);
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

    /** What every cut leaves of each polygon (points of {@code stride} floats). */
    private static List<float[]> minus(List<float[]> polygons, int stride, WoundCarve.Cylinder cut) {
        List<float[]> out = new ArrayList<>();
        for (float[] polygon : polygons) out.addAll(WoundCarve.outside(polygon, stride, cut));
        return out;
    }

    /** A polygon of {@link #STRIDE}-float corners, as the quads a block's buffer takes: a fan, the odd triangle a quad with a corner twice. */
    private static void quads(VertexConsumer out, float[] p, int overlay, float nx, float ny, float nz) {
        int n = p.length / STRIDE;
        for (int i = 1; i + 1 < n; i += 2)
            for (int corner : new int[]{0, i, i + 1, Math.min(i + 2, n - 1)}) {
                int o = corner * STRIDE;
                out.vertex(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6], p[o + 7], p[o + 8], overlay,
                    (Math.round(p[o + 9]) & 0xFFFF) | Math.round(p[o + 10]) << 16, nx, ny, nz);
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

    /** Takes a block's quads as its model renderer hands them over, four corners at a time, and passes each on as {@link #finish} decides. */
    private abstract static class Quads implements VertexConsumer {
        final VertexConsumer out;
        final float[] quad = new float[4 * STRIDE];
        int corners, overlay, light;
        float nx, ny, nz, x, y, z, r = 1, g = 1, b = 1, a = 1, u, v;

        Quads(VertexConsumer out) {this.out = out;}

        abstract void finish(float[] quad);

        @Override public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                                     int overlay, int light, float nx, float ny, float nz) {
            int o = corners * STRIDE;
            quad[o] = x; quad[o + 1] = y; quad[o + 2] = z;
            quad[o + 3] = red; quad[o + 4] = green; quad[o + 5] = blue; quad[o + 6] = alpha;
            quad[o + 7] = u; quad[o + 8] = v;
            quad[o + 9] = light & 0xFFFF; quad[o + 10] = light >>> 16;
            this.overlay = overlay;
            this.nx = nx; this.ny = ny; this.nz = nz;
            if (++corners == 4) {
                corners = 0;
                finish(quad.clone());
            }
        }

        // For anything that builds its vertices a call at a time: gathered, then handled the same way.
        @Override public VertexConsumer vertex(double x, double y, double z) {this.x = (float) x; this.y = (float) y; this.z = (float) z; return this;}
        @Override public VertexConsumer color(int r, int g, int b, int a) {this.r = r / 255f; this.g = g / 255f; this.b = b / 255f; this.a = a / 255f; return this;}
        @Override public VertexConsumer uv(float u, float v) {this.u = u; this.v = v; return this;}
        @Override public VertexConsumer overlayCoords(int u, int v) {this.overlay = u & 0xFFFF | v << 16; return this;}
        @Override public VertexConsumer uv2(int u, int v) {this.light = u & 0xFFFF | v << 16; return this;}
        @Override public VertexConsumer normal(float x, float y, float z) {this.nx = x; this.ny = y; this.nz = z; return this;}
        @Override public void endVertex() {vertex(x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz);}
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }

    /** A carved block's own quads: what the holes leave of each, in pieces. */
    private static final class Carved extends Quads {
        private final List<WoundCarve.Cylinder> cuts;

        Carved(VertexConsumer out, List<WoundCarve.Cylinder> cuts) {
            super(out);
            this.cuts = cuts;
        }

        @Override void finish(float[] quad) {
            List<float[]> pieces = List.of(quad);
            for (WoundCarve.Cylinder cut : cuts) pieces = minus(pieces, STRIDE, cut);
            for (float[] piece : pieces) quads(out, piece, overlay, nx, ny, nz);
        }
    }

    /** A neighbour's face turned to a carved block: only where a hole opens onto it, and seared. */
    private static final class Cap extends Quads {
        private final List<WoundCarve.Cylinder> cuts;
        private final Vector3f facing;

        Cap(VertexConsumer out, List<WoundCarve.Cylinder> cuts, Vector3f facing) {
            super(out);
            this.cuts = cuts;
            this.facing = facing;
        }

        @Override void finish(float[] quad) {
            if (nx * facing.x + ny * facing.y + nz * facing.z < .9f) return;
            for (WoundCarve.Cylinder cut : cuts) {
                float[] piece = WoundCarve.inside(quad, STRIDE, cut);
                if (piece == null) continue;
                for (int o = 0; o < piece.length; o += STRIDE) {
                    piece[o + 3] *= SEARED;
                    piece[o + 4] *= SEARED;
                    piece[o + 5] *= SEARED;
                }
                quads(out, piece, overlay, nx, ny, nz);
            }
        }
    }
}
