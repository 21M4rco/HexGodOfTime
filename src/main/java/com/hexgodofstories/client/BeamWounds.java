package com.hexgodofstories.client;

import com.hexgodofstories.data.HoleHeat;
import com.hexgodofstories.data.WoundCarve;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hole a Scepter beam leaves: an actual opening through the limb it went through, which you can see
 * the world through, with a cauterised tunnel inside and a burning rim around it, closing over time.
 *
 * <p>How it is made. The wound is pinned to the model part the beam passed through, found by
 * intersecting the beam with the posed cubes the first time the body is drawn, so it moves with that
 * limb however the body animates. Then, every frame, at the moment that part is emitted — before the
 * body's batch is actually drawn — three things happen immediately: the tunnel walls are drawn, and
 * the openings are written into the depth buffer with colour writes off. When the body's batch
 * reaches the GPU a moment later, its surface inside the openings is behind that depth and fails the
 * depth test, so what shows through is whatever was already drawn behind the body. The wet rim is
 * blended over the body once it is drawn.
 *
 * <p>The hole is carved, never stamped on: it is the beam's cylinder cut out of the cube by
 * {@link WoundCarve}. Each face loses only its own part inside the cylinder, the tunnel runs only where
 * the cylinder is inside the cube, and a rim lies only on a face around that face's opening. A beam that
 * meets a limb at an edge or a corner takes a bite out of it, and nothing of the hole is ever drawn in
 * the air beside the limb. The server strikes a body's box, which is roomier than its model, so a beam's
 * line may pass beside every cube; it is then moved across to the nearest one, until the hole bites well
 * into it.
 *
 * <p>A body with no vanilla model part to pin to (GeckoLib, Citadel, a mod's own renderer) is carved
 * the same way from the surfaces it actually draws: {@link WoundSurface} notes them as they go by, and
 * each one within reach of the beam is cut before that batch is drawn.
 *
 * <p>Everything drawn over the body's own model afterwards — worn armour, a held item, any layer — is cut as
 * it is drawn ({@link QuadCarver}) by the same hole at its full width, so a plate or a sleeve is holed with
 * the limb under it. Where the wound had to be moved in to bite a thin part (an armour stand's frame), what is
 * worn over it is cut down the line the beam really took.
 *
 * <p>A fresh hole is red-hot, and cools through orange to a dim yellowish glow before it goes out, sooner
 * than a hole in a wall does and its inside last ({@link HoleHeat}): the heat is in the tunnel's colour and
 * is light added over the rim, and while it lasts the hole smokes a little from each mouth and fire crackles
 * in it ({@link HoleSizzle}).
 */
public final class BeamWounds {
    private BeamWounds() { }

    /** Colour steps down the tunnel, from raw at each mouth to dark halfway through. */
    private static final int BANDS = 4;
    /**
     * A beam whose line passed beside the body is moved in until it runs this far off it, as a share of the
     * hole's radius: the bite is then most of the hole deep, whatever the angle it came in at.
     */
    private static final float GRAZE = .4f;
    /**
     * The widest a hole's radius may be, as a share of how wide its limb is across the beam at the narrowest:
     * a beam wider than a leg holes it rather than cutting it clean through, and a hole in the middle of a
     * face is the size it always was. Wherever it lands, it is carved at that size.
     */
    private static final float WIDEST = .45f;

    private static final class Wound {
        final Vec3 point, direction;
        final float radius;
        final long start;
        final int life;
        Object part;
        /** The beam's line in the pinned part's own space, and the cube of that part it carves. */
        final Vector3f through = new Vector3f(), axis = new Vector3f();
        float[] box;
        // Candidate while pinning: rank 0 when the line runs through the cube, keyed by how soon it enters;
        // rank 1 when it passes beside the cube, keyed by how far off.
        Object candidate;
        int candidateRank = Integer.MAX_VALUE;
        float candidateKey = Float.POSITIVE_INFINITY;
        final Vector3f candidateThrough = new Vector3f(), candidateAxis = new Vector3f();
        float[] candidateBox;
        long seen = -100;
        /** This frame's rim, in view space, drawn after the body: triangles of x, y, z and how far out, 0 to 1. */
        float[] rim;
        // Never pinned to a vanilla part after two draws: cut from the drawn surface instead.
        int misses;
        boolean surface;
        /** Where the entry opening was last drawn, in the world, for blood to pour from, and smoke. */
        Vec3 mouth;
        /** The way out on the far side, when the hole goes through, and the beam's way through the limb, both in the world, for the smoke. */
        Vec3 exit, way;
        /** The light its mouths gave off when it was last drawn: red, green and blue. */
        final float[] glow = new float[3];
        /** Pinned where the beam's line passed beside its part, and moved in to bite it. */
        boolean grazed;
        /** This frame's hole in view space, at its full width, for what is drawn over the body; and the frame it is for. */
        WoundCarve.Cylinder cut;
        long cutFrame = -1;
        /** Its sizzle while it is hot, as long as it can be heard; never, for a quiet hole (a bullet's). */
        HoleSizzle sizzle;
        final boolean quiet;

        Wound(Vec3 point, Vec3 direction, float radius, long start, int life, boolean quiet) {
            this.point = point;
            this.direction = direction;
            this.radius = radius;
            this.start = start;
            this.life = life;
            this.quiet = quiet;
        }

        float age(float partial) {return ClientState.since(start, partial) / life;}
    }

    private static final Map<Integer, List<Wound>> WOUNDS = new HashMap<>();
    private static boolean world;
    /** Frames drawn, counted, so a hole in view space is only ever used in the frame it was worked out for. */
    private static long frames;
    private static Matrix4f inverseView, view;
    private static Vec3 camera = Vec3.ZERO;

    public static void clear() {
        for (List<Wound> list : WOUNDS.values()) for (Wound w : list) if (w.sizzle != null) w.sizzle.end();
        WOUNDS.clear();
    }

    public static void receive(int entity, CompoundTag n) {
        // Body space at the instant of the hit: relative to the body, turned back by its facing.
        float yaw = n.getFloat("yaw") * Mth.DEG_TO_RAD;
        Vec3 point = new Vec3(n.getDouble("x") - n.getDouble("ex"), n.getDouble("y") - n.getDouble("ey"),
            n.getDouble("z") - n.getDouble("ez")).yRot(yaw);
        Vec3 direction = new Vec3(n.getDouble("dx"), n.getDouble("dy"), n.getDouble("dz")).normalize().yRot(yaw);
        List<Wound> list = WOUNDS.computeIfAbsent(entity, k -> new ArrayList<>());
        if (list.size() >= 8) list.remove(0);
        if (WOUNDS.size() > 256) WOUNDS.clear();
        list.add(new Wound(point, direction, n.getFloat("r"), n.getLong("start"), Math.max(1, n.getInt("life")), n.getBoolean("quiet")));
    }

    public static void tick() {
        var level = Minecraft.getInstance().level;
        if (level == null) {WOUNDS.clear(); return;}
        long now = ClientState.now();
        WOUNDS.entrySet().removeIf(e -> {
            e.getValue().removeIf(w -> now > w.start + w.life);
            return e.getValue().isEmpty() || level.getEntity(e.getKey()) == null;
        });
        // Fire crackles in a hole still hot, started again whenever it has stopped being heard (out of earshot, no room);
        // and a little smoke rises from each of its mouths, where it was drawn a moment ago.
        for (Map.Entry<Integer, List<Wound>> entry : WOUNDS.entrySet())
            for (Wound w : entry.getValue()) {
                float age = ClientState.since(w.start, 0);
                if (HoleHeat.cold(age, HoleHeat.BODY, 0)) continue;
                if (!w.quiet && (w.sizzle == null || !w.sizzle.heard())) {
                    int id = entry.getKey();
                    w.sizzle = HoleSizzle.start(() -> heardFrom(id, w), () -> HoleHeat.coldAt(HoleHeat.BODY, 0) - ClientState.since(w.start, 0));
                }
                float heat = HoleHeat.heat(age, HoleHeat.BODY, 0, 0);
                if (heat <= 0 || w.way == null || now - w.seen > 2) continue;
                float r = w.radius;
                if (w.mouth != null) ScepterFx.holeSmoke(w.mouth.x, w.mouth.y, w.mouth.z, -w.way.x, -w.way.y, -w.way.z, r, heat);
                if (w.exit != null) ScepterFx.holeSmoke(w.exit.x, w.exit.y, w.exit.z, w.way.x, w.way.y, w.way.z, r, heat);
            }
    }

    public static void beginFrame(RenderLevelStageEvent event) {
        world = true;
        frames++;
        view = new Matrix4f(event.getPoseStack().last().pose());
        inverseView = new Matrix4f(view).invert();
        camera = event.getCamera().getPosition();
    }

    public static void endFrame() {world = false;}

    /** The world's view this frame, while the world is being drawn; null otherwise (an inventory's figure). */
    static Matrix4f view() {return world ? view : null;}

    static Vec3 camera() {return camera;}

    /**
     * A body's buffers with everything drawn over its own model carved by its wounds as it is drawn: armour,
     * held and worn items, every layer. Its own model passes untouched, cut the usual way.
     */
    public static MultiBufferSource layers(Entity host, MultiBufferSource buffers) {
        if (!world) return buffers;
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null || list.isEmpty()) return buffers;
        return QuadCarver.carve(buffers, () -> cuts(list), () -> !WoundAnchor.emittingBody());
    }

    /** The holes worked out this frame for a body's wounds pinned to its model. */
    private static List<WoundCarve.Cylinder> cuts(List<Wound> list) {
        List<WoundCarve.Cylinder> out = new ArrayList<>(list.size());
        for (Wound w : list) if (w.part != null && w.cut != null && w.cutFrame == frames) out.add(w.cut);
        return out;
    }

    /**
     * A wound's hole in view space at its full width, for what is drawn over the body: down its part, so it
     * swings with the limb; or, if it was moved in to bite a thin part, down the line the beam really took.
     */
    private static WoundCarve.Cylinder layerCut(Wound w, Entity host, Matrix4f pose, float partial) {
        float r = radius(w, partial);
        if (w.grazed) {
            Vec3 at = bodyToWorld(host, w.point, partial).subtract(camera);
            Vec3 along = w.direction.yRot(-bodyYaw(host, partial) * Mth.DEG_TO_RAD);
            Vector3f o = view.transformPosition(new Vector3f((float) at.x, (float) at.y, (float) at.z));
            Vector3f d = view.transformDirection(new Vector3f((float) along.x, (float) along.y, (float) along.z));
            return new WoundCarve.Cylinder(o.x, o.y, o.z, d.x, d.y, d.z, r);
        }
        Vector3f c = pose.transformPosition(new Vector3f(w.through)), a = pose.transformDirection(new Vector3f(w.axis));
        float scale = a.length();
        return scale > 1e-6f ? new WoundCarve.Cylinder(c.x, c.y, c.z, a.x, a.y, a.z, r * scale) : null;
    }

    private static float radius(Wound w, float partial) {
        float age = w.age(partial);
        // Open for three quarters of its life, then it knits shut.
        float close = Mth.clamp((age - .75f) / .25f, 0, 1);
        return w.radius * (1 - close * close * (3 - 2 * close));
    }

    /**
     * From WoundAnchor for every posed model part of the body being drawn. {@code body} is true only for
     * the body's own model: a wound never pins to armour or another layer, which are drawn after the
     * body's batch and so too late to open it. Worn armour is cut instead by lifting the opening past it.
     */
    static void capture(Entity host, float partial, Object part, PoseStack.Pose pose, List<ModelPart.Cube> cubes, boolean body) {
        if (!world || inverseView == null || cubes.isEmpty()) return;
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null || list.isEmpty()) return;
        long frame = ClientState.now();
        Matrix4f local = new Matrix4f(inverseView).mul(pose.pose());
        Matrix4f toPart = null;
        for (Wound w : list) {
            if (w.part != null) {
                if (w.part == part) {
                    w.seen = frame;
                    draw(w, host, pose.pose(), partial);
                }
                continue;
            }
            // Not pinned yet: does the beam run through one of this part's cubes, or pass beside one?
            if (!body) continue;
            if (toPart == null) {
                toPart = new Matrix4f(local).invert();
                if (!Float.isFinite(toPart.determinant())) return;
            }
            Vec3 origin = bodyToWorld(host, w.point, partial).subtract(camera);
            Vec3 along = w.direction.yRot(-bodyYaw(host, partial) * Mth.DEG_TO_RAD);
            Vector3f o = toPart.transformPosition(new Vector3f((float) (origin.x - along.x), (float) (origin.y - along.y),
                (float) (origin.z - along.z)));
            Vector3f d = toPart.transformDirection(new Vector3f((float) along.x, (float) along.y, (float) along.z));
            if (!(d.lengthSquared() > 1e-12f)) continue;
            d.normalize();
            for (ModelPart.Cube cube : cubes) {
                float[] box = {cube.minX / 16f, cube.minY / 16f, cube.minZ / 16f, cube.maxX / 16f, cube.maxY / 16f, cube.maxZ / 16f};
                float[] span = WoundCarve.span(WoundCarve.boxPlanes(box), o.x, o.y, o.z, d.x, d.y, d.z);
                if (span != null) {
                    if (w.candidateRank > 0 || span[0] < w.candidateKey)
                        candidate(w, part, box, 0, span[0], o.x + d.x * span[0], o.y + d.y * span[0], o.z + d.z * span[0], d);
                    continue;
                }
                if (w.candidateRank == 0) continue;
                // Beside this cube: the nearest of those is carved, from a line moved in until it bites.
                float[] q = WoundCarve.nearest(box, o.x, o.y, o.z, d.x, d.y, d.z);
                if (q[3] >= w.candidateKey) continue;
                float t = (q[0] - o.x) * d.x + (q[1] - o.y) * d.y + (q[2] - o.z) * d.z;
                float px = o.x + d.x * t, py = o.y + d.y * t, pz = o.z + d.z * t;
                float gap = (float) Math.sqrt((q[0] - px) * (q[0] - px) + (q[1] - py) * (q[1] - py) + (q[2] - pz) * (q[2] - pz));
                // In reach of the hole as draw() will size it for this cube, not as the server sized it.
                float size = Math.min(w.radius, WIDEST * new WoundCarve.Cylinder(px, py, pz, d.x, d.y, d.z, 1).width(box));
                float keep = GRAZE * size, move = gap > keep ? (gap - keep) / gap : 0;
                candidate(w, part, box, 1, q[3], px + (q[0] - px) * move, py + (q[1] - py) * move, pz + (q[2] - pz) * move, d);
            }
        }
    }

    private static void candidate(Wound w, Object part, float[] box, int rank, float key, float x, float y, float z, Vector3f axis) {
        w.candidate = part;
        w.candidateRank = rank;
        w.candidateKey = key;
        w.candidateThrough.set(x, y, z);
        w.candidateAxis.set(axis);
        w.candidateBox = box;
    }

    /**
     * The buffers a body should draw into: its own, or, when one of its wounds has nothing vanilla to pin
     * to, a {@link WoundSurface} that notes where its surfaces are on the way through.
     */
    public static MultiBufferSource surface(Entity host, float partial, MultiBufferSource buffers, int light) {
        if (!world || view == null) return buffers;
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null) return buffers;
        for (Wound w : list) if (w.surface && w.part == null) return new WoundSurface(buffers, host, partial, light);
        return buffers;
    }

    /** After the body's renderer returns: cut anything still waiting to be cut. */
    public static void finish(MultiBufferSource buffers) {
        if (buffers instanceof WoundSurface surface) surface.finish();
    }

    /** From WoundSurface, ahead of the body's batch: carve every surface wound, then lay its rim over the body. */
    static void cutSurface(Entity host, float partial, WoundSurface surface, MultiBufferSource buffers, int light) {
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null || surface.faces() == 0 || view == null || inverseView == null) return;
        // Every opening is cut before any rim is asked for: asking for the rims' buffer sends the body's batch.
        List<Wound> rimmed = new ArrayList<>();
        for (Wound w : list) {
            if (!w.surface || w.part != null) continue;
            Carving carving = new Carving();
            if (!drawSurface(w, host, partial, surface, carving)) continue;
            carving.cut();
            w.rim = carving.rim();
            if (w.rim != null) rimmed.add(w);
        }
        rims(rimmed, buffers, light);
    }

    /** Carves one wound out of the recorded faces, in view space. False when the beam cuts none of them. */
    private static boolean drawSurface(Wound w, Entity host, float partial, WoundSurface surface, Carving carving) {
        float r = radius(w, partial);
        if (r < .004f) return false;
        float[] faces = surface.packed();
        byte[] corners = surface.cornerCounts();
        int count = surface.faces();
        // The beam's line through the body, as it struck, turned with the body and carried into view space.
        Vec3 at = bodyToWorld(host, w.point, partial).subtract(camera);
        Vec3 along = w.direction.yRot(-bodyYaw(host, partial) * Mth.DEG_TO_RAD);
        Vector3f o = view.transformPosition(new Vector3f((float) at.x, (float) at.y, (float) at.z));
        Vector3f d = view.transformDirection(new Vector3f((float) along.x, (float) along.y, (float) along.z)).normalize();
        WoundCarve.Cylinder hole = new WoundCarve.Cylinder(o.x, o.y, o.z, d.x, d.y, d.z, r);
        float age = ClientState.since(w.start, partial);
        int[] near = WoundCarve.near(faces, corners, count, hole);
        if (WoundCarve.through(faces, corners, near, o.x, o.y, o.z, d.x, d.y, d.z) == null) {
            // A beam whose line passed beside the body: move it across toward the body's nearest corner
            // until the hole bites well into it.
            float[] shift = WoundCarve.toward(faces, corners, count, o.x, o.y, o.z, d.x, d.y, d.z);
            if (shift == null) return false;
            float gap = (float) Math.sqrt(shift[0] * shift[0] + shift[1] * shift[1] + shift[2] * shift[2]), keep = GRAZE * r;
            if (gap > keep) hole = hole.moved(shift[0] * (gap - keep) / gap, shift[1] * (gap - keep) / gap, shift[2] * (gap - keep) / gap);
            near = WoundCarve.near(faces, corners, count, hole);
        }
        if (near.length == 0) return false;
        // The tunnel: down each side of the hole, from where the body is first met to where it is left. Where
        // the body ends partway across a side, as it does at an edge, the wall stops there too.
        float[] line = new float[3];
        float[][] ends = new float[WoundCarve.SIDES][];
        for (int k = 0; k < WoundCarve.SIDES; k++) ends[k] = span(faces, corners, near, hole, k, 0, line);
        for (int k = 0; k < WoundCarve.SIDES; k++) {
            float[] a = ends[k], b = ends[(k + 1) % WoundCarve.SIDES];
            if (a != null && b != null) {wall(carving, hole, k, 0, a, 1, b, age); continue;}
            if (a == null && b == null) continue;
            // Narrow in on the last point along the side still inside the body.
            float in = a != null ? 0 : 1, out = 1 - in;
            float[] kept = a != null ? a : b;
            for (int i = 0; i < 6; i++) {
                float mid = (in + out) / 2;
                float[] s = span(faces, corners, near, hole, k, mid, line);
                if (s != null) {in = mid; kept = s;} else out = mid;
            }
            if (a != null) wall(carving, hole, k, 0, a, in, kept, age);
            else wall(carving, hole, k, in, kept, 1, b, age);
        }
        // The openings, and the rim around each. A face seen from inside the body keeps its rim too: the
        // body's front hides it, and which side a mod's face is wound to show is not known here.
        float lift = armoured(host) ? .07f : .006f, mouth = Float.POSITIVE_INFINITY, exit = Float.NEGATIVE_INFINITY;
        Vec3 exitAt = null;
        for (int f : near) {
            float[] face = WoundCarve.face(faces, corners, f);
            float[] opening = WoundCarve.opening(face, hole);
            if (opening == null) continue;
            carving.opening(nearer(opening, lift));
            float[] centre = WoundCarve.centre(opening);
            float depth = hole.along(centre[0], centre[1], centre[2]);
            if (depth < mouth) {mouth = depth; w.mouth = world(new Vector3f(centre[0], centre[1], centre[2]));}
            if (depth > exit) {exit = depth; exitAt = world(new Vector3f(centre[0], centre[1], centre[2]));}
            for (int k = 0; k < WoundCarve.SIDES; k++) {
                float[] piece = WoundCarve.rim(face, hole, k);
                if (piece != null) carving.rim(nearer(piece, lift + .003f), out(hole, k, piece));
            }
        }
        if (mouth == Float.POSITIVE_INFINITY) return false;
        w.exit = exit > mouth + 1e-3f ? exitAt : null;
        w.way = along.normalize();
        HoleHeat.glow(age, HoleHeat.BODY, 0, 0, w.glow);
        w.seen = ClientState.now();
        return true;
    }

    /** Where the line down side {@code k} of the hole, {@code s} of the way across it, runs through the drawn body. */
    private static float[] span(float[] faces, byte[] corners, int[] near, WoundCarve.Cylinder hole, int k, float s, float[] line) {
        hole.onSide(k, s, 0, line, 0);
        float[] span = WoundCarve.through(faces, corners, near, line[0], line[1], line[2], hole.ax, hole.ay, hole.az);
        return span == null || span[1] - span[0] < 1e-4f ? null : span;
    }

    /** A strip of tunnel wall on side {@code k}, between two lines down it that each run from {@code a[0]} to {@code a[1]}, {@code age} ticks after the shot. */
    private static void wall(Carving carving, WoundCarve.Cylinder hole, int k, float s0, float[] a, float s1, float[] b, float age) {
        float[] strip = new float[12];
        for (int band = 0; band < BANDS; band++) {
            float f0 = band / (float) BANDS, f1 = (band + 1) / (float) BANDS;
            hole.onSide(k, s0, Mth.lerp(f0, a[0], a[1]), strip, 0);
            hole.onSide(k, s1, Mth.lerp(f0, b[0], b[1]), strip, 3);
            hole.onSide(k, s1, Mth.lerp(f1, b[0], b[1]), strip, 6);
            hole.onSide(k, s0, Mth.lerp(f1, a[0], a[1]), strip, 9);
            int near = tunnel(1 - Math.abs(f0 * 2 - 1), age), far = tunnel(1 - Math.abs(f1 * 2 - 1), age);
            carving.wall(strip.clone(), new int[]{near, near, far, far});
        }
    }

    /**
     * Where a hot wound is heard from: its mouth, where it was drawn a moment ago, or else the point the beam
     * struck; null once it is cold, or gone, or its body is.
     */
    private static Vec3 heardFrom(int id, Wound w) {
        var level = Minecraft.getInstance().level;
        List<Wound> list = WOUNDS.get(id);
        if (level == null || list == null || !list.contains(w) || HoleHeat.cold(ClientState.since(w.start, 0), HoleHeat.BODY, 0)) return null;
        Entity host = level.getEntity(id);
        if (host == null) return null;
        return w.mouth != null && ClientState.now() - w.seen <= 2 ? w.mouth : bodyToWorld(host, w.point, 1);
    }

    /** Where blood leaves the newest hole in this body, in the world; null when it has none. */
    public static Vec3 bleedPoint(Entity host) {
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null || list.isEmpty()) return null;
        Wound w = list.get(list.size() - 1);
        if (w.mouth != null && ClientState.now() - w.seen <= 2) return w.mouth;
        // Not drawn lately: the point the beam struck, drawn in from the widened box it was tested against.
        Vec3 at = bodyToWorld(host, w.point, 1);
        return at.lerp(new Vec3(host.getX(), at.y, host.getZ()), .35);
    }

    /** When the body has finished drawing: settle any pin found this frame. */
    static void endEntity(Entity host) {
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null) return;
        for (Wound w : list) {
            if (w.part == null && w.candidate != null) {
                w.part = w.candidate;
                w.through.set(w.candidateThrough);
                w.axis.set(w.candidateAxis);
                w.box = w.candidateBox;
                w.grazed = w.candidateRank == 1;
                w.seen = ClientState.now();
            }
            w.candidate = null;
            w.candidateRank = Integer.MAX_VALUE;
            w.candidateKey = Float.POSITIVE_INFINITY;
            // A part that has stopped being drawn (armour taken off, a model swap) lets the wound re-pin.
            if (w.part != null && ClientState.now() - w.seen > 4) w.part = null;
            if (w.part != null) {w.misses = 0; w.surface = false;}
            else if (!w.surface && ++w.misses >= 2) w.surface = true;
        }
    }

    private static Vec3 bodyToWorld(Entity host, Vec3 local, float partial) {
        return WoundAnchor.lerpPosition(host, partial).add(local.yRot(-bodyYaw(host, partial) * Mth.DEG_TO_RAD));
    }

    private static float bodyYaw(Entity host, float partial) {return WoundAnchor.bodyYaw(host, partial);}

    private static boolean armoured(Entity host) {
        if (!(host instanceof LivingEntity living)) return false;
        for (ItemStack armour : living.getArmorSlots()) if (!armour.isEmpty()) return true;
        return false;
    }

    /** Carves the hole out of its cube right now, ahead of the body's own batch, and keeps its rim for after. */
    private static void draw(Wound w, Entity host, Matrix4f pose, float partial) {
        w.rim = null;
        float r = radius(w, partial);
        if (r < .004f) return;
        // For what is drawn over the body afterwards, carved as it is drawn.
        w.cut = layerCut(w, host, pose, partial);
        w.cutFrame = frames;
        Matrix4f toPart = new Matrix4f(pose).invert();
        if (!Float.isFinite(toPart.determinant())) return;
        // The camera, in the part's own space: only a face turned to it gets a rim.
        Vector3f eye = toPart.transformPosition(new Vector3f());
        WoundCarve.Cylinder hole = new WoundCarve.Cylinder(w.through.x, w.through.y, w.through.z, w.axis.x, w.axis.y, w.axis.z, r);
        float widest = WIDEST * hole.width(w.box);
        if (r > widest) {
            if (widest < .004f) return;
            hole = hole.sized(widest);
        }
        float[] planes = WoundCarve.boxPlanes(w.box);
        Carving carving = new Carving();
        float age = ClientState.since(w.start, partial);
        // The tunnel: each side of the hole where it runs inside the cube, raw at the mouths and dark within.
        float reach = WoundCarve.reach(w.box, hole);
        for (int k = 0; k < WoundCarve.SIDES; k++) {
            float[] wall = WoundCarve.wall(hole, k, planes, reach);
            if (wall == null) continue;
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < wall.length; i += 3) {
                float along = hole.along(wall[i], wall[i + 1], wall[i + 2]);
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
            }
            for (int band = 0; band < BANDS; band++) {
                float[] piece = WoundCarve.band(wall, hole, Mth.lerp(band / (float) BANDS, lo, hi), Mth.lerp((band + 1) / (float) BANDS, lo, hi));
                if (piece == null) continue;
                int[] colours = new int[piece.length / 3];
                for (int i = 0; i < colours.length; i++) colours[i] = tunnel(WoundCarve.depth(planes, hole, piece[i * 3], piece[i * 3 + 1], piece[i * 3 + 2]), age);
                carving.wall(transform(pose, piece), colours);
            }
        }
        // Each opening is written just nearer than its face. Armour sits up to a pixel outside the body and
        // is drawn after it; an armoured body is cut from just beyond the armour's surface, so the plate is
        // holed along with the flesh under it.
        float lift = armoured(host) ? .07f : .006f, mouth = Float.POSITIVE_INFINITY, exit = Float.NEGATIVE_INFINITY;
        Vec3 exitAt = null;
        for (int f = 0; f < 6; f++) {
            float[] face = WoundCarve.boxFace(w.box, f);
            float[] opening = WoundCarve.opening(face, hole);
            if (opening == null) continue;
            carving.opening(nearer(transform(pose, opening), lift));
            float[] centre = WoundCarve.centre(opening);
            float depth = hole.along(centre[0], centre[1], centre[2]);
            if (depth < mouth) {mouth = depth; w.mouth = world(pose.transformPosition(new Vector3f(centre[0], centre[1], centre[2])));}
            if (depth > exit) {exit = depth; exitAt = world(pose.transformPosition(new Vector3f(centre[0], centre[1], centre[2])));}
            // A face seen from inside the body is behind its front; its rim would never show.
            if (planes[f * 4] * (eye.x - face[0]) + planes[f * 4 + 1] * (eye.y - face[1]) + planes[f * 4 + 2] * (eye.z - face[2]) <= 0) continue;
            for (int k = 0; k < WoundCarve.SIDES; k++) {
                float[] piece = WoundCarve.rim(face, hole, k);
                if (piece != null) carving.rim(nearer(transform(pose, piece), lift + .003f), out(hole, k, piece));
            }
        }
        carving.cut();
        w.rim = carving.rim();
        if (mouth == Float.POSITIVE_INFINITY) return;
        w.exit = exit > mouth + 1e-3f ? exitAt : null;
        Vector3f way = inverseView.transformDirection(pose.transformDirection(new Vector3f(hole.ax, hole.ay, hole.az)));
        w.way = way.lengthSquared() > 1e-12f ? new Vec3(way.x, way.y, way.z).normalize() : null;
        HoleHeat.glow(age, HoleHeat.BODY, 0, 0, w.glow);
    }

    /** One wound's carving for this frame, collected in view space, then drawn in one go. */
    private static final class Carving {
        private final List<float[]> walls = new ArrayList<>(), openings = new ArrayList<>();
        private final List<int[]> colours = new ArrayList<>();
        private float[] rim = new float[0];
        private int rimFloats;

        void wall(float[] polygon, int[] colour) {walls.add(polygon); colours.add(colour);}

        void opening(float[] polygon) {openings.add(polygon);}

        /** A piece of rim, fanned into triangles of x, y, z and how far out each corner is. */
        void rim(float[] polygon, float[] out) {
            int n = polygon.length / 3;
            if (rimFloats + (n - 2) * 12 > rim.length) rim = Arrays.copyOf(rim, Math.max(rim.length * 2, rimFloats + (n - 2) * 12));
            for (int i = 1; i + 1 < n; i++)
                for (int corner : new int[]{0, i, i + 1}) {
                    rim[rimFloats++] = polygon[corner * 3];
                    rim[rimFloats++] = polygon[corner * 3 + 1];
                    rim[rimFloats++] = polygon[corner * 3 + 2];
                    rim[rimFloats++] = out[corner];
                }
        }

        float[] rim() {return rimFloats == 0 ? null : Arrays.copyOf(rim, rimFloats);}

        /**
         * Draws the tunnel walls right now, ahead of the body's own batch, then cuts the openings into the
         * depth buffer.
         */
        void cut() {
            if (walls.isEmpty() && openings.isEmpty()) return;
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            try {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.disableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                if (!walls.isEmpty()) {
                    b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                    for (int i = 0; i < walls.size(); i++) fan(b, walls.get(i), colours.get(i));
                    BufferUploader.drawWithShader(b.end());
                }
                // Openings: depth only, and only where nothing already drawn is nearer. Written unconditionally,
                // they would erase a block standing in front of the body, and the body's own far side would
                // then show through that block.
                if (!openings.isEmpty()) {
                    RenderSystem.colorMask(false, false, false, false);
                    b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                    for (float[] opening : openings) fan(b, opening, null);
                    BufferUploader.drawWithShader(b.end());
                }
            } finally {
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableCull();
            }
        }
    }

    private static Vec3 world(Vector3f viewPoint) {
        Vector3f p = inverseView.transformPosition(new Vector3f(viewPoint));
        return camera.add(p.x, p.y, p.z);
    }

    /** From the entity's post-render event: the rims, laid over the body now that it is drawn. */
    public static void afterEntity(Entity host, MultiBufferSource buffers, int light) {
        if (!world) return;
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null) return;
        List<Wound> rimmed = new ArrayList<>();
        for (Wound w : list) if (w.rim != null && w.seen == ClientState.now()) rimmed.add(w);
        rims(rimmed, buffers, light);
    }

    /**
     * The wounds' rims, laid over the body, and then the heat still in them added over those. One buffer after
     * the other, never back and forth: asking for a different buffer sends the one before it.
     */
    private static void rims(List<Wound> rimmed, MultiBufferSource buffers, int light) {
        if (rimmed.isEmpty()) return;
        // Blended, not added: a dark wet ring reads on pale skin and dark hide alike.
        VertexConsumer out = buffers.getBuffer(ScepterRenderTypes.glass(WorldEffects.WHITE));
        for (Wound w : rimmed) emitRim(out, w.rim, light);
        out = null;
        for (Wound w : rimmed) {
            if (w.glow[0] + w.glow[1] + w.glow[2] < .004f) continue;
            if (out == null) out = buffers.getBuffer(ScepterRenderTypes.glow(WorldEffects.WHITE));
            glowRim(out, w.rim, w.glow);
        }
        for (Wound w : rimmed) w.rim = null;
    }

    /** A rim's triangles: raw at the edge of the opening, fading into the skin around it. */
    private static void emitRim(VertexConsumer out, float[] rim, int light) {
        for (int i = 0; i + 3 < rim.length; i += 4) {
            float o = rim[i + 3];
            out.vertex(rim[i], rim[i + 1], rim[i + 2], .40f - .14f * o, .02f - .01f * o, .03f - .02f * o, .95f * (1 - o),
                .5f, .5f, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, light, 0, 0, 1);
        }
    }

    /** A rim's heat, as light added over it: brightest at the edge of the opening, gone by the rim's outer edge. */
    private static void glowRim(VertexConsumer out, float[] rim, float[] rgb) {
        for (int i = 0; i + 3 < rim.length; i += 4) {
            float fade = (1 - rim[i + 3]) * (1 - rim[i + 3]);
            out.vertex(rim[i], rim[i + 1], rim[i + 2], rgb[0] * fade, rgb[1] * fade, rgb[2] * fade, 1,
                .5f, .5f, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, 0, 0, 1);
        }
    }

    /** How far out through the rim each corner of a piece lies, 0 at the opening's edge. */
    private static float[] out(WoundCarve.Cylinder hole, int k, float[] piece) {
        float[] out = new float[piece.length / 3];
        for (int i = 0; i < out.length; i++) out[i] = WoundCarve.rimDepth(hole, k, piece[i * 3], piece[i * 3 + 1], piece[i * 3 + 2]);
        return out;
    }

    /** Scratch for the heat of one corner of tunnel at a time; only ever used on the render thread. */
    private static final float[] HEAT = new float[3];

    /**
     * The tunnel's flesh, raw red at a mouth ({@code depth} 0) and darkening to the middle (1), with the heat
     * still in it {@code age} ticks after the shot added: red-hot, then orange, then yellowish, then gone.
     */
    private static int tunnel(float depth, float age) {
        HoleHeat.glow(age, HoleHeat.BODY, depth, 0, HEAT);
        float r = .52f - .36f * depth + HEAT[0], g = .06f - .045f * depth + HEAT[1], b = .06f - .04f * depth + HEAT[2];
        return 0xff000000 | ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, g) * 255) << 8) | (int) (Math.min(1, b) * 255);
    }

    private static void fan(BufferBuilder b, float[] polygon, int[] colours) {
        int n = polygon.length / 3;
        for (int i = 1; i + 1 < n; i++) {
            vertex(b, polygon, 0, colours);
            vertex(b, polygon, i, colours);
            vertex(b, polygon, i + 1, colours);
        }
    }

    private static void vertex(BufferBuilder b, float[] polygon, int i, int[] colours) {
        int argb = colours == null ? 0xff000000 : colours[i];
        b.vertex(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2]).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255).endVertex();
    }

    /**
     * A view-space polygon brought nearer the eye, each corner straight along its own line of sight, until it
     * stands {@code lift} off its own plane: it covers exactly the pixels it did, so openings that meet at a
     * carved edge still meet on screen, and none spills past its cut. At a glancing angle that would take it
     * a long way, so it goes no further than four lifts.
     */
    static float[] nearer(float[] polygon, float lift) {
        float[] n = WoundCarve.normal(polygon), out = polygon.clone();
        for (int i = 0; i < out.length; i += 3) {
            float distance = (float) Math.sqrt(out[i] * out[i] + out[i + 1] * out[i + 1] + out[i + 2] * out[i + 2]);
            if (distance < 1e-6f) continue;
            float facing = Math.abs(n[0] * out[i] + n[1] * out[i + 1] + n[2] * out[i + 2]) / distance;
            float keep = 1 - Math.min(lift / Math.max(facing, .25f), distance * .5f) / distance;
            out[i] *= keep;
            out[i + 1] *= keep;
            out[i + 2] *= keep;
        }
        return out;
    }

    static float[] transform(Matrix4f pose, float[] polygon) {
        float[] out = new float[polygon.length];
        Vector3f p = new Vector3f();
        for (int i = 0; i < polygon.length; i += 3) {
            pose.transformPosition(p.set(polygon[i], polygon[i + 1], polygon[i + 2]));
            out[i] = p.x;
            out[i + 1] = p.y;
            out[i + 2] = p.z;
        }
        return out;
    }
}
