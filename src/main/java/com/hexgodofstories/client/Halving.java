package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HoleHeat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A body Complete Evisceration's cut killed (server/Evisceration), drawn in two halves that come apart and fall.
 *
 * <p>Nothing about the body needs to be known. It is drawn twice by its own renderer, whatever renderer that is (a
 * vanilla mob, a player, GeckoLib, any mod's), each time through buffers that keep only what lies on one side of the
 * cut's plane: every quad or triangle handed to them is clipped against it as it arrives, in the space its corners
 * arrive in, and what is left goes on in pieces. Where a closed surface (a cube of a model) is cut, the points where
 * its faces cross the plane close into a loop, and the loop is filled: the cut face. It is drawn as a Scepter hole
 * in a body is, burned: red-hot as the blade leaves it, cooling through orange to a dim glow and then a charred dark
 * red (HoleHeat), smoking while it is hot, the blood pouring out of it.
 *
 * <p>Each half is its own body from then on: thrown apart by the cut (the upper one along the blade's line and away,
 * the lower one buckling a beat later), turning over under its own weight about its middle (the upper) or its far
 * foot (the lower) until it lies on the ground, landing on whatever is really under it with a wet thud. They lie
 * there ten seconds, then sink out of sight. The body itself is never drawn meanwhile, alive or dead, and is drawn
 * from its last state after the world has let it go.
 */
public final class Halving {
    private Halving() {}

    /** Ticks the halves lie on the ground before they sink, and ticks they take to sink. */
    private static final int LIE = 200, SINK = 30;
    /** Cuts at once; a body that is gone and still not drawn is given up on after this long. */
    private static final int MAX = 24, ABANDON = 2400;
    private static final float GRAVITY = .075f, MOST_SPIN = .5f;
    /** Ticks for the cut face to cool from red-hot to nothing, as a Scepter hole in a body does. */
    private static final float COOLING = HoleHeat.BODY;
    /** How near two of a loop's points must be to be one (blocks, squared). */
    private static final float JOIN = 1e-6f;
    private static final int MOST_SEGMENTS = 4096;
    /** Raw flesh at the middle of a cut face and charred at its edge, under the heat's own light. */
    private static final float[] FLESH = {.5f, .05f, .045f}, CHAR = {.16f, .03f, .02f};

    /** One half: which side of the cut it keeps, and where it is and how it is turned (both from the anchor). */
    private static final class Piece {
        final float side;
        /** What it turns about (its middle, or the lower half's far foot), and its corners, for its lowest point. */
        final Vector3f pivot;
        final List<Vector3f> hull;
        final Vector3f axis;
        final float topple, accel;
        final int delay;
        Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO, vel;
        final Quaternionf rot = new Quaternionf(), prevRot = new Quaternionf();
        float angle, spin;
        boolean grounded;

        Piece(float side, Vector3f pivot, List<Vector3f> hull, Vec3 vel, Vector3f axis, float topple, float spin, float accel, int delay) {
            this.side = side;
            this.pivot = pivot;
            this.hull = hull;
            this.vel = vel;
            this.axis = axis.normalize();
            this.topple = topple;
            this.spin = spin;
            this.accel = accel;
            this.delay = delay;
        }

        /** A point of this piece, from the anchor, where the piece now is (or between its last tick and this one). */
        Vector3f place(Vector3f local, float partial) {
            Quaternionf q = partial >= 1 ? new Quaternionf(rot) : new Quaternionf(prevRot).slerp(rot, partial);
            Vec3 at = partial >= 1 ? pos : prevPos.lerp(pos, partial);
            Vector3f v = new Vector3f(local).sub(pivot).rotate(q).add(pivot);
            return v.add((float) at.x, (float) at.y, (float) at.z);
        }
    }

    private static final class Cut {
        final Entity body;
        final ClientLevel level;
        final Vec3 anchor;
        /** On the plane, from the anchor, and the side of it the upper piece keeps. */
        final Vector3f point, normal;
        final long start;
        final float height;
        final Piece upper, lower;
        boolean broken;

        Cut(Entity body, ClientLevel level, Vec3 anchor, Vector3f point, Vector3f normal, long start, float height, Piece upper, Piece lower) {
            this.body = body;
            this.level = level;
            this.anchor = anchor;
            this.point = point;
            this.normal = normal;
            this.start = start;
            this.height = height;
            this.upper = upper;
            this.lower = lower;
        }
    }

    private static final Map<Integer, Cut> CUTS = new HashMap<>();

    public static void clear() {CUTS.clear();}

    /** Whether this body is drawn as its halves instead (ErasureRenderMixin leaves it undrawn). */
    public static boolean hidden(Entity e) {
        Cut cut = CUTS.get(e.getId());
        return cut != null && cut.body == e && !cut.broken;
    }

    /** The server's word that this body is cut in two (Evisceration#halve). */
    public static void begin(int id, CompoundTag n) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity body = mc.level.getEntity(id);
        if (body == null) return;
        if (CUTS.size() >= MAX) CUTS.remove(CUTS.keySet().iterator().next());
        var random = mc.level.random;
        Vec3 anchor = body.position();
        float h = body.getBbHeight(), w = body.getBbWidth();
        Vector3f point = new Vector3f(0, (float) n.getDouble("oy"), 0);
        Vector3f normal = new Vector3f((float) n.getDouble("nx"), (float) n.getDouble("ny"), (float) n.getDouble("nz")).normalize();
        Vec3 line = new Vec3(n.getDouble("lx"), n.getDouble("ly"), n.getDouble("lz")).normalize();
        Vec3 forward = new Vec3(n.getDouble("fx"), 0, n.getDouble("fz")).normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        // Bigger bodies are heavier: thrown slower and further, turning over slower.
        float size = (float) Math.sqrt(Math.max(.5, Math.max(h, w)) / 1.8), slow = 1 / (float) Math.sqrt(size);

        // Where each half's mass is, and its corners: the body's box, sampled, on either side of the plane.
        List<Vector3f> up = new ArrayList<>(), down = new ArrayList<>();
        int steps = 6;
        for (int i = 0; i <= steps; i++) for (int j = 0; j <= steps; j++) for (int k = 0; k <= steps; k++) {
            Vector3f p = new Vector3f(w * (i / (float) steps - .5f), h * j / (float) steps, w * (k / (float) steps - .5f));
            (new Vector3f(p).sub(point).dot(normal) >= 0 ? up : down).add(p);
        }
        Vector3f upperMiddle = middle(up, new Vector3f(point).add(new Vector3f(normal).mul(h * .2f)));
        Vector3f lowerMiddle = middle(down, new Vector3f(point).sub(new Vector3f(normal).mul(h * .2f)));
        // The lower half goes over its far foot, away from the bearer and a little to their left.
        Vec3 falls = forward.add(right.scale(-.35)).normalize();
        Vector3f foot = new Vector3f((float) (falls.x * w * .5), 0, (float) (falls.z * w * .5));

        // The upper half slides off down the cut, the way the blade went, thrown on away from the bearer and lifted
        // off the lower one: its top goes over to the right, and away.
        Vec3 thrown = forward.scale(.22).add(line.scale(.17)).add(0, .15, 0).scale(Math.sqrt(size));
        Piece upper = new Piece(1, upperMiddle, up.isEmpty() ? List.of(upperMiddle) : up, thrown,
            vec(forward.add(right.scale(-.6))), (float) Math.toRadians(100 + random.nextFloat() * 25), .1f * slow, .035f * slow, 0);
        // The lower half stands a beat, then buckles and goes over the far way.
        Vec3 knocked = forward.scale(.14).add(line.scale(-.04)).add(0, .05, 0).scale(Math.sqrt(size));
        Piece lower = new Piece(-1, foot, down.isEmpty() ? List.of(lowerMiddle) : down, knocked,
            vec(right.scale(-1).add(forward.scale(-.45))), (float) Math.toRadians(84 + random.nextFloat() * 10), 0, .028f * slow, 3);
        CUTS.put(id, new Cut(body, mc.level, anchor, point, normal, ClientState.now(), h, upper, lower));
        if (body instanceof LivingEntity living) {living.hurtTime = 0; living.deathTime = 0;}

        // The blade's line through the body: blood thrown off both ways along it, and the cut seared as it opens.
        Vec3 middle = anchor.add(0, point.y, 0);
        for (int i = 0; i < 80; i++) {
            double along = (random.nextDouble() - .5) * Math.max(w, h * .8);
            Vec3 at = middle.add(line.scale(along)).add(forward.scale((random.nextDouble() - .5) * w));
            Vec3 out = new Vec3(normal.x, normal.y, normal.z).scale((random.nextBoolean() ? 1 : -1) * (.05 + random.nextDouble() * .12));
            Vfx.spark(HexGodOfStories.BLOOD.get(), at, out.add(line.scale(.12 + random.nextDouble() * .2)).add(0, .05, 0));
            if (i % 4 == 0) Vfx.spark(ParticleTypes.SMOKE, at, new Vec3(0, .03, 0));
            if (i % 8 == 0) Vfx.spark(ParticleTypes.SMALL_FLAME, at, out.scale(.3));
        }
    }

    private static Vector3f middle(List<Vector3f> points, Vector3f otherwise) {
        if (points.isEmpty()) return otherwise;
        Vector3f sum = new Vector3f();
        for (Vector3f p : points) sum.add(p);
        return sum.div(points.size());
    }

    private static Vector3f vec(Vec3 v) {return new Vector3f((float) v.x, (float) v.y, (float) v.z);}

    /** Every client tick: the halves fall, turn over, land and lie; their cut faces pour and smoke. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {CUTS.clear(); return;}
        long now = ClientState.now();
        Iterator<Map.Entry<Integer, Cut>> it = CUTS.entrySet().iterator();
        while (it.hasNext()) {
            Cut cut = it.next().getValue();
            long age = now - cut.start;
            if (cut.level != mc.level || age > ABANDON || age < 0) {it.remove(); continue;}
            // Gone from sight once sunk; let go of only once the body itself is gone (or alive again).
            if (age > LIE + SINK) {
                if (cut.body.isRemoved() || cut.body.isAlive()) it.remove();
                continue;
            }
            if (cut.body instanceof LivingEntity living) {living.hurtTime = 0; living.deathTime = 0;}
            for (Piece piece : new Piece[]{cut.upper, cut.lower}) {
                step(cut, piece, age);
                pour(cut, piece, age);
            }
        }
    }

    private static void step(Cut cut, Piece p, long age) {
        p.prevPos = p.pos;
        p.prevRot.set(p.rot);
        if (age > LIE) {p.pos = p.pos.add(0, -cut.height / SINK, 0); return;}
        if (age >= p.delay && p.angle < p.topple) {
            p.spin = Math.min(p.spin + p.accel, MOST_SPIN);
            p.angle = Math.min(p.topple, p.angle + p.spin);
            p.rot.rotationAxis(p.angle, p.axis);
        }
        p.vel = p.vel.add(0, -GRAVITY, 0);
        Vec3 was = p.pos;
        p.pos = p.pos.add(p.vel);
        // Never into a wall: a half that would go into one stops against it.
        Vector3f middle = p.place(p.pivot, 1);
        BlockPos inside = BlockPos.containing(cut.anchor.x + middle.x, cut.anchor.y + middle.y, cut.anchor.z + middle.z);
        if (!cut.level.getBlockState(inside).getCollisionShape(cut.level, inside).isEmpty()) {
            p.pos = new Vec3(was.x, p.pos.y, was.z);
            p.vel = new Vec3(0, p.vel.y, 0);
        }
        // Its lowest point on whatever is really under it.
        float low = Float.MAX_VALUE;
        Vector3f sum = new Vector3f();
        for (Vector3f corner : p.hull) {
            Vector3f at = p.place(corner, 1);
            low = Math.min(low, at.y);
            sum.add(at);
        }
        sum.div(p.hull.size());
        double ground = ground(cut, cut.anchor.add(sum.x, sum.y, sum.z));
        double bottom = cut.anchor.y + low;
        if (bottom < ground) {
            p.pos = p.pos.add(0, ground - bottom, 0);
            if (!p.grounded && p.vel.y < -.1) land(cut, p, cut.anchor.add(sum.x, ground - cut.anchor.y + .05, sum.z));
            if (p.vel.y < 0) p.vel = new Vec3(p.vel.x * .5, -p.vel.y * .15, p.vel.z * .5);
            p.grounded = true;
        }
        if (p.grounded) p.vel = new Vec3(p.vel.x * .78, p.vel.y, p.vel.z * .78);
    }

    /** The top of whatever solid is under a point, a short way down; far below when there is nothing. */
    private static double ground(Cut cut, Vec3 at) {
        var hit = cut.level.clip(new ClipContext(at.add(0, .3, 0), at.add(0, -12, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, cut.body));
        return hit.getType() == HitResult.Type.MISS ? cut.anchor.y - 64 : hit.getLocation().y;
    }

    /** A half coming down: a wet, heavy thud, and the blood it lands in. */
    private static void land(Cut cut, Piece p, Vec3 at) {
        float weight = Math.min(1.5f, .6f + cut.height * .25f);
        cut.level.playLocalSound(at.x, at.y, at.z, SoundEvents.HONEY_BLOCK_FALL, SoundSource.PLAYERS, weight, .55f, false);
        cut.level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.PLAYERS, weight * .7f, .7f, false);
        var random = cut.level.random;
        for (int i = 0; i < 40; i++)
            Vfx.spark(HexGodOfStories.BLOOD.get(), at, new Vec3((random.nextDouble() - .5) * .3, .05 + random.nextDouble() * .15, (random.nextDouble() - .5) * .3));
        for (int i = 0; i < 4; i++)
            Blood.pool(cut.body, at.add((random.nextDouble() - .5) * cut.height * .5, 0, (random.nextDouble() - .5) * cut.height * .5),
                .3 + random.nextDouble() * .4);
    }

    /** The cut face of a half: pouring blood, heavily at first, and smoking while it is hot. */
    private static void pour(Cut cut, Piece p, long age) {
        if (age > 120) return;
        var random = cut.level.random;
        Vector3f face = p.place(cut.point, 1);
        Vector3f out = new Vector3f(cut.normal).mul(-p.side).rotate(p.rot);
        Vec3 at = cut.anchor.add(face.x, face.y, face.z);
        float flow = 1 - age / 120f;
        for (int i = 0; i < Math.round(14 * flow * flow) + 1; i++) {
            Vec3 spot = at.add((random.nextDouble() - .5) * cut.height * .25, (random.nextDouble() - .5) * .1, (random.nextDouble() - .5) * cut.height * .25);
            Vfx.spark(HexGodOfStories.BLOOD.get(), spot,
                new Vec3(out.x * (.04 + random.nextDouble() * .1), out.y * .06 - .02, out.z * (.04 + random.nextDouble() * .1)));
        }
        if (age % 3 == 0) Blood.pool(cut.body, at.add((random.nextDouble() - .5) * .6, 0, (random.nextDouble() - .5) * .6), .25 + random.nextDouble() * .35 * flow);
        float heat = heat(age);
        if (heat > .25f && age % 2 == 0) Vfx.spark(ParticleTypes.SMOKE, at.add((random.nextDouble() - .5) * .3, 0, (random.nextDouble() - .5) * .3), new Vec3(0, .02 + .03 * heat, 0));
        if (heat > .7f && age % 5 == 0) Vfx.spark(ParticleTypes.SMALL_FLAME, at, new Vec3(out.x * .02, .01, out.z * .02));
    }

    /** How hot the cut face is, red-hot as the blade leaves it and cooling as a Scepter hole in a body does. */
    private static float heat(float age) {return HoleHeat.heat(age + HoleHeat.RISE, COOLING, 0, 0);}

    /** After the world's entities: each cut body's halves, where they are now. */
    public static void render(RenderLevelStageEvent e) {
        if (CUTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = e.getCamera().getPosition();
        float partial = e.getPartialTick();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Set<RenderType> used = new HashSet<>();
        for (Cut cut : CUTS.values()) {
            float age = ClientState.since(cut.start, partial);
            if (cut.broken || cut.level != mc.level || age > LIE + SINK) continue;
            // One's own halves are not drawn from inside one's own eyes.
            if (cut.body == mc.getCameraEntity() && !e.getCamera().isDetached()) continue;
            @SuppressWarnings("unchecked")
            EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) mc.getEntityRenderDispatcher().getRenderer(cut.body);
            float heat = heat(age);
            for (Piece piece : new Piece[]{cut.upper, cut.lower}) {
                // A pose of its own, so a renderer that fails part way cannot leave the world's unbalanced.
                PoseStack pose = new PoseStack();
                pose.last().pose().set(e.getPoseStack().last().pose());
                pose.last().normal().set(e.getPoseStack().last().normal());
                Vec3 at = piece.prevPos.lerp(piece.pos, partial);
                Quaternionf turn = new Quaternionf(piece.prevRot).slerp(piece.rot, partial);
                pose.translate(cut.anchor.x - camera.x + at.x, cut.anchor.y - camera.y + at.y, cut.anchor.z - camera.z + at.z);
                pose.translate(piece.pivot.x, piece.pivot.y, piece.pivot.z);
                pose.mulPose(turn);
                pose.translate(-piece.pivot.x, -piece.pivot.y, -piece.pivot.z);
                Matrix4f m = pose.last().pose();
                Matrix3f nm = pose.last().normal();
                Vector3f plane = m.transformPosition(new Vector3f(cut.point));
                Vector3f keep = nm.transform(new Vector3f(cut.normal)).mul(piece.side).normalize();
                Vector3f world = piece.place(piece.pivot, partial);
                int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(cut.anchor.x + world.x, cut.anchor.y + world.y, cut.anchor.z + world.z));
                Clipper clipper = new Clipper(buffers, plane, keep, used);
                if (cut.body instanceof LivingEntity living) {living.hurtTime = 0; living.deathTime = 0;}
                try {
                    renderer.render(cut.body, cut.body.getYRot(), 1, pose, clipper, light);
                } catch (Throwable failed) {
                    // A renderer that cannot draw its body like this: the body goes back to being drawn its own way.
                    cut.broken = true;
                    break;
                }
                clipper.faces(heat, light);
            }
        }
        for (RenderType type : used) buffers.endBatch(type);
    }

    /**
     * Buffers that keep only what is on one side of a plane, given in the space the corners arrive in: each quad or
     * triangle clipped against it as it is handed over, and where one is cut, the line it is cut along kept, to
     * close into the cut face.
     */
    private static final class Clipper implements MultiBufferSource {
        private final MultiBufferSource buffers;
        private final Vector3f point, keep;
        private final Set<RenderType> used;
        /** Each cut line, x y z and x y z. */
        private final List<float[]> lines = new ArrayList<>();

        Clipper(MultiBufferSource buffers, Vector3f point, Vector3f keep, Set<RenderType> used) {
            this.buffers = buffers;
            this.point = point;
            this.keep = keep;
            this.used = used;
        }

        @Override public VertexConsumer getBuffer(RenderType type) {
            VertexFormat.Mode mode = type.mode();
            if (mode != VertexFormat.Mode.QUADS && mode != VertexFormat.Mode.TRIANGLES) return DROP;
            used.add(type);
            return new Half(buffers.getBuffer(type), mode == VertexFormat.Mode.QUADS ? 4 : 3);
        }

        private float side(float[] p, int o) {
            float d = (p[o] - point.x) * keep.x + (p[o + 1] - point.y) * keep.y + (p[o + 2] - point.z) * keep.z;
            return Math.abs(d) < 1e-6f ? 1e-6f : d;
        }

        /** What of a polygon (corners of {@link QuadCarver#STRIDE} floats) is on the kept side, its cut line noted. */
        float[] clip(float[] polygon) {
            int s = QuadCarver.STRIDE, n = polygon.length / s;
            float[] out = new float[(n + 2) * s];
            float[] crossed = new float[6];
            int kept = 0, crossings = 0;
            for (int i = 0; i < n; i++) {
                int a = i * s, b = (i + 1) % n * s;
                float da = side(polygon, a), db = side(polygon, b);
                if (da >= 0) {System.arraycopy(polygon, a, out, kept * s, s); kept++;}
                if (da >= 0 != db >= 0) {
                    float t = da / (da - db);
                    for (int c = 0; c < s; c++) out[kept * s + c] = polygon[a + c] + (polygon[b + c] - polygon[a + c]) * t;
                    if (crossings < 2) System.arraycopy(out, kept * s, crossed, crossings * 3, 3);
                    crossings++;
                    kept++;
                }
            }
            if (crossings == 2 && lines.size() < MOST_SEGMENTS) lines.add(crossed);
            float[] left = new float[kept * s];
            System.arraycopy(out, 0, left, 0, left.length);
            return left;
        }

        /** The cut faces: each closed loop of cut lines, filled, red-hot and cooling from its rim in. */
        void faces(float heat, int light) {
            if (lines.isEmpty()) return;
            VertexConsumer out = buffers.getBuffer(RenderType.entitySolid(WorldEffects.WHITE));
            used.add(RenderType.entitySolid(WorldEffects.WHITE));
            float[] glow = HoleHeat.glow(heat, new float[3]);
            int bright = Math.max(LightTexture.block(light), Math.round(15 * heat));
            int lit = LightTexture.pack(bright, LightTexture.sky(light));
            Vector3f outward = new Vector3f(keep).negate();
            boolean[] done = new boolean[lines.size()];
            for (int first = 0; first < lines.size(); first++) {
                if (done[first]) continue;
                done[first] = true;
                List<Vector3f> loop = new ArrayList<>();
                float[] f = lines.get(first);
                loop.add(new Vector3f(f[0], f[1], f[2]));
                Vector3f end = new Vector3f(f[3], f[4], f[5]);
                boolean closed = false;
                while (loop.size() < 512) {
                    if (end.distanceSquared(loop.get(0)) < JOIN) {closed = true; break;}
                    loop.add(end);
                    int next = -1;
                    Vector3f far = null;
                    for (int j = 0; j < lines.size() && next < 0; j++) {
                        if (done[j]) continue;
                        float[] l = lines.get(j);
                        Vector3f a = new Vector3f(l[0], l[1], l[2]), b = new Vector3f(l[3], l[4], l[5]);
                        if (a.distanceSquared(end) < JOIN) {next = j; far = b;}
                        else if (b.distanceSquared(end) < JOIN) {next = j; far = a;}
                    }
                    if (next < 0) break;
                    done[next] = true;
                    end = far;
                }
                if (!closed || loop.size() < 3) continue;
                fill(out, loop, outward, glow, lit);
            }
        }

        /** One loop, a fan from its middle: raw at the middle, charred at the rim, the heat's light over both. */
        private static void fill(VertexConsumer out, List<Vector3f> loop, Vector3f outward, float[] glow, int light) {
            Vector3f middle = new Vector3f(), turn = new Vector3f();
            for (int i = 0; i < loop.size(); i++) {
                Vector3f a = loop.get(i), b = loop.get((i + 1) % loop.size());
                middle.add(a);
                turn.add((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y));
            }
            middle.div(loop.size());
            // Wound to face out of the half, so only its outside is drawn.
            if (turn.dot(outward) < 0) java.util.Collections.reverse(loop);
            float[] in = {Math.min(1, FLESH[0] + glow[0] * .55f), Math.min(1, FLESH[1] + glow[1] * .55f), Math.min(1, FLESH[2] + glow[2] * .55f)};
            float[] rim = {Math.min(1, CHAR[0] + glow[0]), Math.min(1, CHAR[1] + glow[1]), Math.min(1, CHAR[2] + glow[2])};
            for (int i = 0; i < loop.size(); i++) {
                Vector3f a = loop.get(i), b = loop.get((i + 1) % loop.size());
                corner(out, middle, in, light, outward);
                corner(out, a, rim, light, outward);
                corner(out, b, rim, light, outward);
                corner(out, b, rim, light, outward);
            }
        }

        private static void corner(VertexConsumer out, Vector3f at, float[] c, int light, Vector3f n) {
            out.vertex(at.x, at.y, at.z, c[0], c[1], c[2], 1, 0, 0, OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
        }

        /** Takes a buffer's corners a primitive at a time, and passes on what the plane leaves of each. */
        private final class Half extends Corners {
            private final VertexConsumer out;

            Half(VertexConsumer out, int size) {
                super(size);
                this.out = out;
            }

            @Override void finish(float[] polygon) {
                float[] left = clip(polygon);
                int s = QuadCarver.STRIDE, n = left.length / s;
                if (n < 3) return;
                if (size == 4) {QuadCarver.quads(out, left, overlay, nx, ny, nz); return;}
                for (int i = 1; i + 1 < n; i++)
                    for (int corner : new int[]{0, i, i + 1}) {
                        int o = corner * s;
                        out.vertex(left[o], left[o + 1], left[o + 2], left[o + 3], left[o + 4], left[o + 5], left[o + 6], left[o + 7], left[o + 8],
                            overlay, (Math.round(left[o + 9]) & 0xFFFF) | Math.round(left[o + 10]) << 16, nx, ny, nz);
                    }
            }
        }
    }

    /** Anything drawn as lines or strips is left out of the halves. */
    private static final VertexConsumer DROP = new Corners(4) {@Override void finish(float[] polygon) { }};

    /** Corners gathered {@code size} at a time (a quad's four or a triangle's three), as {@link QuadCarver.Quads} does. */
    private abstract static class Corners implements VertexConsumer {
        final int size;
        final float[] polygon;
        int corners, overlay, light;
        float nx, ny, nz, x, y, z, r = 1, g = 1, b = 1, a = 1, u, v;

        Corners(int size) {
            this.size = size;
            this.polygon = new float[size * QuadCarver.STRIDE];
        }

        abstract void finish(float[] polygon);

        @Override public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                                     int overlay, int light, float nx, float ny, float nz) {
            int o = corners * QuadCarver.STRIDE;
            polygon[o] = x; polygon[o + 1] = y; polygon[o + 2] = z;
            polygon[o + 3] = red; polygon[o + 4] = green; polygon[o + 5] = blue; polygon[o + 6] = alpha;
            polygon[o + 7] = u; polygon[o + 8] = v;
            polygon[o + 9] = light & 0xFFFF; polygon[o + 10] = light >>> 16;
            this.overlay = overlay;
            this.nx = nx; this.ny = ny; this.nz = nz;
            if (++corners == size) {
                corners = 0;
                finish(polygon.clone());
            }
        }

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
}
