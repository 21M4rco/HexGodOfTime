package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HoleHeat;
import com.hexgodofstories.mixin.ModelPartAccessor;
import com.hexgodofstories.mixin.QuadrupedModelAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A body Complete Evisceration's cut killed (server/Evisceration), drawn in two halves that come apart and fall.
 *
 * <p>Nothing about the body needs to be known. It is drawn twice by its own renderer, whatever renderer that is (a
 * vanilla mob, a player, GeckoLib, any mod's), each time through buffers that keep only what lies on one side of the
 * cut's plane: every quad or triangle handed to them is clipped against it as it arrives, in the space its corners
 * arrive in, and what is left goes on in pieces. Where a closed surface (a cube of a model) is cut, the points where
 * its faces cross the plane close into a loop, and the loop is filled: the cut face, raw and blood red. Only when the
 * blade was burning (transformed) is it seared instead, as a Scepter hole in a body is: red-hot as the blade leaves
 * it, cooling through orange to a dim glow (HoleHeat), smoking while it is hot.
 *
 * <p>Each half is its own body from then on: thrown apart by the cut (the upper one along the blade's line and away,
 * the lower one buckling a beat later), turning over under its own weight about its middle (the upper) or its far
 * foot (the lower) until it lies on the ground, landing on whatever is really under it with a wet thud and slumping
 * back. And it goes limp, as a body does: every limb of a model built of parts (the humanoids, the four-legged, every
 * model built as one tree of named parts) that lies wholly on that half's side of the cut flops loose as it falls and
 * is thrown about again when it lands, settling splayed. A limb the cut went through stays as it was, so nothing slides
 * across the cut. Both halves pour blood, heavily, for ten seconds, and drip after. A creature's halves lie there three
 * quarters of a minute, then sink out of sight; its own death is over at once on the server (its loot dropped), so no
 * fall, flash or puff of smoke of the game's own is ever seen, and it is drawn from its last state after the world has
 * let it go. A player's lie until they die of it, five seconds on, and are gone with them. Meanwhile the player cut in
 * two sees it from inside: their eyes ride the upper half's head as it tumbles and rolls to the ground, and they can
 * look about, at their own lower half lying there, and do nothing else at all (HexClient lets go of every key).
 */
public final class Halving {
    private Halving() {}

    /** Ticks a creature's halves lie (when the server does not say), and take to sink; ticks their cut faces pour. */
    private static final int LIE = 900, SINK = 30, POUR = 200;
    /** Cuts at once; a body that is gone and still not drawn is given up on after this long. */
    private static final int MAX = 24, ABANDON = 2400;
    private static final float GRAVITY = .075f, MOST_SPIN = .5f;
    /** Ticks for a seared cut face to cool from red-hot to nothing, as a Scepter hole in a body does. */
    private static final float COOLING = HoleHeat.BODY;
    /** How near two of a loop's points must be to be one (blocks, squared). */
    private static final float JOIN = 1e-6f;
    private static final int MOST_SEGMENTS = 4096;
    /** A raw cut face: blood red at the middle, darker at the rim. Seared, the rim is charred and the heat's light over both. */
    private static final float[] RAW = {.72f, .03f, .03f}, RAW_RIM = {.48f, .015f, .015f}, FLESH = {.5f, .05f, .045f}, CHAR = {.16f, .03f, .02f};
    /** The least light a raw cut face is drawn in, so it reads red at night too. */
    private static final int RAW_LIGHT = 9;
    /** Where a part of the body lies against the cut, found the first time it is drawn: unknown, wholly kept, wholly the other half's, cut through. */
    private static final int UNKNOWN = 0, WHOLE = 1, GONE = 2, CUT = 3;

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
        /** The tick it first came down, from the cut; -1 while it has not. */
        float landed = -1;
        /** Each part of the body, by where it lies against the cut on this side. */
        final Map<ModelPart, Integer> parts = new IdentityHashMap<>();

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

        /** How far over it has turned, between its last tick and this one. */
        float prevAngle(float partial) {
            float before = 2 * (float) Math.acos(Math.min(1, Math.abs(prevRot.w)));
            return before + (angle - before) * partial;
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
        final boolean burning;
        final Piece upper, lower;
        /** Ticks the halves lie, and take to sink: a player's are gone at once when they die of it. */
        final int lie, sink;
        boolean broken;

        Cut(Entity body, ClientLevel level, Vec3 anchor, Vector3f point, Vector3f normal, long start, float height, boolean burning,
            Piece upper, Piece lower, int lie, int sink) {
            this.lie = lie;
            this.sink = sink;
            this.body = body;
            this.level = level;
            this.anchor = anchor;
            this.point = point;
            this.normal = normal;
            this.start = start;
            this.height = height;
            this.burning = burning;
            this.upper = upper;
            this.lower = lower;
        }
    }

    private static final Map<Integer, Cut> CUTS = new HashMap<>();
    /** What is being drawn right now, for the model's own hooks (limp, partBegin): the cut, the half, its clipper, the part, the moment. */
    private static Cut drawingCut;
    private static Piece drawingPiece;
    private static Clipper drawingClipper;
    private static ModelPart drawingPart;
    private static float drawingAge;
    /** The limbs posed limp for this half, and how they were before, to be put back once it is drawn. */
    private static final List<ModelPart> POSED = new ArrayList<>();
    private static final List<float[]> WAS = new ArrayList<>();

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
        boolean burning = n.getBoolean("burning");
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
        boolean player = body instanceof net.minecraft.world.entity.player.Player;
        CUTS.put(id, new Cut(body, mc.level, anchor, point, normal, ClientState.now(), h, burning, upper, lower,
            n.contains("lie") ? n.getInt("lie") : LIE, player ? 1 : SINK));
        if (body instanceof LivingEntity living) {living.hurtTime = 0; living.deathTime = 0;}

        // The blade's line through the body: blood thrown out of the whole length of it, both ways and every way,
        // pools all along under it, and, if the blade burned, the cut seared as it opens.
        Vec3 middle = anchor.add(0, point.y, 0), across = new Vec3(normal.x, normal.y, normal.z);
        for (int i = 0; i < 360; i++) {
            double along = (random.nextDouble() - .5) * Math.max(w, h * .8);
            Vec3 at = middle.add(line.scale(along)).add(forward.scale((random.nextDouble() - .5) * w));
            Vec3 out = across.scale((random.nextBoolean() ? 1 : -1) * (.05 + random.nextDouble() * .2));
            Vfx.spark(HexGodOfStories.BLOOD.get(), at, out.add(line.scale((random.nextDouble() - .3) * .35)).add(0, .02 + random.nextDouble() * .2, 0));
            if (burning && i % 6 == 0) Vfx.spark(ParticleTypes.SMOKE, at, new Vec3(0, .03, 0));
            if (burning && i % 12 == 0) Vfx.spark(ParticleTypes.SMALL_FLAME, at, out.scale(.3));
        }
        for (int i = 0; i < 14; i++)
            Blood.pool(body, middle.add(line.scale((random.nextDouble() - .5) * Math.max(w, h))).add((random.nextDouble() - .5) * 1.4, 0, (random.nextDouble() - .5) * 1.4),
                .35 + random.nextDouble() * .5);
    }

    private static Vector3f middle(List<Vector3f> points, Vector3f otherwise) {
        if (points.isEmpty()) return otherwise;
        Vector3f sum = new Vector3f();
        for (Vector3f p : points) sum.add(p);
        return sum.div(points.size());
    }

    private static Vector3f vec(Vec3 v) {return new Vector3f((float) v.x, (float) v.y, (float) v.z);}

    /** Every client tick: the halves fall, turn over, land and lie; their cut faces pour, and smoke if seared. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {CUTS.clear(); return;}
        long now = ClientState.now();
        Iterator<Map.Entry<Integer, Cut>> it = CUTS.entrySet().iterator();
        while (it.hasNext()) {
            Cut cut = it.next().getValue();
            long age = now - cut.start;
            if (cut.level != mc.level || age > ABANDON || age < 0) {it.remove(); continue;}
            // Gone from sight once sunk; let go of only once the body itself is gone, or alive again (a player respawned,
            // given a moment for the word of their death to arrive).
            if (age > cut.lie + cut.sink) {
                if (cut.body.isRemoved() || mc.level.getEntity(cut.body.getId()) != cut.body || cut.body.isAlive() && age > cut.lie + cut.sink + 40)
                    it.remove();
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
        if (age > cut.lie) {p.pos = p.pos.add(0, -cut.height / cut.sink, 0); return;}
        if (age >= p.delay && p.angle < p.topple) {
            p.spin = Math.min(p.spin + p.accel, MOST_SPIN);
            p.angle = Math.max(0, Math.min(p.topple, p.angle + p.spin));
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
            if (!p.grounded) {
                p.landed = age;
                // It slumps back off the blow of landing before it goes on over.
                if (p.angle < p.topple) p.spin *= -.3f;
                if (p.vel.y < -.1) land(cut, p, cut.anchor.add(sum.x, ground - cut.anchor.y + .05, sum.z));
            }
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

    /** A half coming down: a wet, heavy thud, and the blood it lands in, thrown up all round it. */
    private static void land(Cut cut, Piece p, Vec3 at) {
        float weight = Math.min(1.5f, .6f + cut.height * .25f);
        cut.level.playLocalSound(at.x, at.y, at.z, SoundEvents.HONEY_BLOCK_FALL, SoundSource.PLAYERS, weight, .55f, false);
        cut.level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.PLAYERS, weight * .7f, .7f, false);
        var random = cut.level.random;
        for (int i = 0; i < 140; i++)
            Vfx.spark(HexGodOfStories.BLOOD.get(), at, new Vec3((random.nextDouble() - .5) * .45, .05 + random.nextDouble() * .22, (random.nextDouble() - .5) * .45));
        for (int i = 0; i < 10; i++)
            Blood.pool(cut.body, at.add((random.nextDouble() - .5) * cut.height * .8, 0, (random.nextDouble() - .5) * cut.height * .8),
                .35 + random.nextDouble() * .5);
    }

    /** The cut face of a half: pouring blood, very heavily at first and spurting with the last beats, smoking if seared. */
    private static void pour(Cut cut, Piece p, long age) {
        if (age > cut.lie) return;
        var random = cut.level.random;
        if (age > POUR) {
            // Dripping after, for as long as it lies there.
            if (age % 4 == 0) {
                Vector3f face = p.place(cut.point, 1);
                Vfx.spark(HexGodOfStories.BLOOD.get(), cut.anchor.add(face.x + (random.nextDouble() - .5) * .3, face.y, face.z + (random.nextDouble() - .5) * .3),
                    new Vec3(0, -.03, 0));
            }
            return;
        }
        Vector3f face = p.place(cut.point, 1);
        Vector3f out = new Vector3f(cut.normal).mul(-p.side).rotate(p.rot);
        Vec3 at = cut.anchor.add(face.x, face.y, face.z);
        float flow = 1 - age / (float) POUR, spread = cut.height * .3f;
        for (int i = 0; i < Math.round(44 * flow * flow) + 4; i++) {
            Vec3 spot = at.add((random.nextDouble() - .5) * spread, (random.nextDouble() - .5) * .12, (random.nextDouble() - .5) * spread);
            Vfx.spark(HexGodOfStories.BLOOD.get(), spot,
                new Vec3(out.x * (.04 + random.nextDouble() * .14), out.y * .08 - .02, out.z * (.04 + random.nextDouble() * .14)));
        }
        // The last beats of the heart: a spurt thrown out of the cut, up and away, every fifth tick for four seconds.
        if (age < 80 && age % 5 == 0)
            for (int i = 0; i < 40; i++) {
                double speed = (.15 + random.nextDouble() * .25) * (1 - age / 100f);
                Vfx.spark(HexGodOfStories.BLOOD.get(), at, new Vec3(out.x * speed + (random.nextDouble() - .5) * .08, .08 + random.nextDouble() * .18,
                    out.z * speed + (random.nextDouble() - .5) * .08));
            }
        Blood.pool(cut.body, at.add((random.nextDouble() - .5) * .9, 0, (random.nextDouble() - .5) * .9), .3 + random.nextDouble() * .45 * flow);
        if (!cut.burning) return;
        float heat = heat(age);
        if (heat > .25f && age % 2 == 0) Vfx.spark(ParticleTypes.SMOKE, at.add((random.nextDouble() - .5) * .3, 0, (random.nextDouble() - .5) * .3), new Vec3(0, .02 + .03 * heat, 0));
        if (heat > .7f && age % 5 == 0) Vfx.spark(ParticleTypes.SMALL_FLAME, at, new Vec3(out.x * .02, .01, out.z * .02));
    }

    /** How hot a seared cut face is, red-hot as the blade leaves it and cooling as a Scepter hole in a body does. */
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
            if (cut.broken || cut.level != mc.level || age > cut.lie + cut.sink) continue;
            // One's own upper half is not drawn: the eyes are in its head. The lower one is there to be seen.
            boolean own = cut.body == mc.getCameraEntity() && !e.getCamera().isDetached();
            @SuppressWarnings("unchecked")
            EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) mc.getEntityRenderDispatcher().getRenderer(cut.body);
            for (Piece piece : own ? new Piece[]{cut.lower} : new Piece[]{cut.upper, cut.lower}) {
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
                drawingCut = cut;
                drawingPiece = piece;
                drawingClipper = clipper;
                drawingAge = age;
                try {
                    renderer.render(cut.body, cut.body.getYRot(), 1, pose, clipper, light);
                } catch (Throwable failed) {
                    // A renderer that cannot draw its body like this: the body goes back to being drawn its own way.
                    cut.broken = true;
                } finally {
                    drawingCut = null;
                    drawingPiece = null;
                    drawingClipper = null;
                    drawingPart = null;
                    unlimp();
                }
                if (cut.broken) break;
                clipper.faces(cut.burning, heat(age), light);
            }
        }
        for (RenderType type : used) buffers.endBatch(type);
    }

    // ------------------------------------------------------------------ one's own body, cut in two

    /** This client's own player, cut in two and not yet dead of it. */
    private static Cut own() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive()) return null;
        Cut cut = CUTS.get(mc.player.getId());
        return cut != null && cut.body == mc.player && !cut.broken && ClientState.now() - cut.start <= cut.lie ? cut : null;
    }

    /** Whether this client's player is lying cut in two: they can do nothing but look about (HexClient). */
    public static boolean helpless() {return own() != null;}

    /** Whether one of the halves is being drawn right now: no name is drawn over it. */
    public static boolean drawing() {return drawingPiece != null;}

    /** Where this client's eyes are while it lies cut in two: in the upper half's head, wherever that has fallen (HalvedCameraMixin). */
    public static Vec3 eye(float partial) {
        Cut cut = own();
        if (cut == null) return null;
        Vector3f eye = cut.upper.place(new Vector3f(0, cut.body.getEyeHeight(), 0), Math.min(1, Math.max(0, partial)));
        return cut.anchor.add(eye.x, eye.y, eye.z);
    }

    /**
     * The view turning over with the head as the upper half falls: the half's turn about the way one is looking rolls the
     * view, and its turn about one's right tips it up or down. Looking about, one sees the world lying as the head does.
     */
    public static void angles(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles e) {
        Cut cut = own();
        if (cut == null) return;
        float partial = (float) e.getPartialTick();
        Piece upper = cut.upper;
        float angle = upper.prevAngle(partial);
        if (angle == 0) return;
        double yaw = Math.toRadians(e.getYaw());
        Vector3f look = new Vector3f((float) -Math.sin(yaw), 0, (float) Math.cos(yaw)), right = new Vector3f(-look.z, 0, look.x);
        float degrees = (float) Math.toDegrees(angle);
        e.setRoll(e.getRoll() + degrees * upper.axis.dot(look));
        e.setPitch(Math.max(-90, Math.min(90, e.getPitch() - degrees * .5f * upper.axis.dot(right))));
    }

    // ------------------------------------------------------------------ the model's own hooks

    /** A model part about to emit its quads (WoundPartMixin): which part, and, the first time, where it lies against the cut. */
    public static void partBegin(ModelPart part, PoseStack.Pose pose, List<ModelPart.Cube> cubes) {
        if (drawingPiece == null) return;
        drawingPart = part;
        if (!drawingPiece.parts.containsKey(part)) drawingPiece.parts.put(part, drawingClipper.lies(pose.pose(), cubes));
    }

    public static void partEnd() {drawingPart = null;}

    /**
     * Before a living body's model is emitted (WoundBodyMixin): while one of its halves is being drawn, each limb of it
     * that lies wholly on that half's side goes limp: swinging loose as the half falls, thrown about again as it lands,
     * settling splayed. Posed over whatever its own animation gave it, and put back once the half is drawn.
     */
    public static void limp(Entity entity, EntityModel<?> model) {
        if (drawingPiece == null || drawingCut == null || entity != drawingCut.body || model == null) return;
        List<Limb> limbs = limbs(model);
        if (limbs.isEmpty()) return;
        Random random = new Random(entity.getId() * 31L + (drawingPiece.side > 0 ? 7 : 13));
        float t = drawingAge, ease = 1 - (float) Math.exp(-t / 6);
        float landed = drawingPiece.landed < 0 || t < drawingPiece.landed ? 0 : t - drawingPiece.landed;
        for (Limb limb : limbs) {
            float restX = limb.kind.restX(random), restZ = limb.kind.restZ(random), phase = random.nextFloat() * 6.28f, phase2 = random.nextFloat() * 6.28f;
            if (drawingPiece.parts.getOrDefault(limb.part, UNKNOWN) != WHOLE) continue;
            float swing = .85f * (float) Math.exp(-t / 9) * (float) Math.sin(t * .6f + phase);
            if (landed > 0) swing += .7f * (float) Math.exp(-landed / 7) * (float) Math.sin(landed * .9f + phase2);
            pose(limb.part, restX * ease + swing, restZ * ease + swing * .6f);
            for (ModelPart follower : limb.followers)
                if (drawingPiece.parts.getOrDefault(follower, UNKNOWN) == WHOLE) {keep(follower); follower.copyFrom(limb.part);}
        }
    }

    private static void pose(ModelPart part, float x, float z) {
        keep(part);
        part.xRot += x;
        part.zRot += z;
    }

    private static void keep(ModelPart part) {
        POSED.add(part);
        WAS.add(new float[]{part.xRot, part.yRot, part.zRot});
    }

    /** Every limb put back as its own animation had it: a model is shared by every body of its kind. */
    private static void unlimp() {
        for (int i = POSED.size() - 1; i >= 0; i--) {
            float[] r = WAS.get(i);
            POSED.get(i).setRotation(r[0], r[1], r[2]);
        }
        POSED.clear();
        WAS.clear();
    }

    /** How a kind of limb goes limp: how far over it settles, in radians. */
    private enum Kind {
        HEAD(.3f, .9f, .5f), ARM(-1.5f, .6f, .9f), LEG(-.6f, .6f, .4f), LOOSE(-.7f, .7f, .7f);

        final float lowX, highX, z;

        Kind(float lowX, float highX, float z) {this.lowX = lowX; this.highX = highX; this.z = z;}

        float restX(Random r) {return lowX + r.nextFloat() * (highX - lowX);}
        float restZ(Random r) {return (r.nextFloat() * 2 - 1) * z;}
    }

    /** A limb, and the parts its own model copies it to (a player's sleeves and trouser legs, a humanoid's hat). */
    private record Limb(ModelPart part, Kind kind, List<ModelPart> followers) { }

    private static final Map<EntityModel<?>, List<Limb>> LIMBS = new IdentityHashMap<>();

    private static List<Limb> limbs(EntityModel<?> model) {
        return LIMBS.computeIfAbsent(model, m -> {
            List<Limb> out = new ArrayList<>();
            if (m instanceof PlayerModel<?> player) {
                out.add(new Limb(player.head, Kind.HEAD, List.of(player.hat)));
                out.add(new Limb(player.rightArm, Kind.ARM, List.of(player.rightSleeve)));
                out.add(new Limb(player.leftArm, Kind.ARM, List.of(player.leftSleeve)));
                out.add(new Limb(player.rightLeg, Kind.LEG, List.of(player.rightPants)));
                out.add(new Limb(player.leftLeg, Kind.LEG, List.of(player.leftPants)));
            } else if (m instanceof HumanoidModel<?> humanoid) {
                out.add(new Limb(humanoid.head, Kind.HEAD, List.of(humanoid.hat)));
                out.add(new Limb(humanoid.rightArm, Kind.ARM, List.of()));
                out.add(new Limb(humanoid.leftArm, Kind.ARM, List.of()));
                out.add(new Limb(humanoid.rightLeg, Kind.LEG, List.of()));
                out.add(new Limb(humanoid.leftLeg, Kind.LEG, List.of()));
            } else if (m instanceof QuadrupedModel<?> && m instanceof QuadrupedModelAccessor four) {
                out.add(new Limb(four.hgos$head(), Kind.HEAD, List.of()));
                for (ModelPart leg : new ModelPart[]{four.hgos$rightHindLeg(), four.hgos$leftHindLeg(), four.hgos$rightFrontLeg(), four.hgos$leftFrontLeg()})
                    out.add(new Limb(leg, Kind.LEG, List.of()));
            } else if (m instanceof HierarchicalModel<?> tree) {
                named(tree.root(), out, 0);
            }
            return out;
        });
    }

    /** A model built as one tree: its limbs are the parts named as limbs (the names are the model's own, never remapped). */
    private static void named(ModelPart part, List<Limb> out, int depth) {
        if (depth > 6 || !((Object) part instanceof ModelPartAccessor tree)) return;
        for (Map.Entry<String, ModelPart> child : tree.hgos$children().entrySet()) {
            String name = child.getKey().toLowerCase(Locale.ROOT);
            Kind kind = name.contains("head") ? Kind.HEAD : name.contains("arm") ? Kind.ARM : name.contains("leg") ? Kind.LEG
                : name.contains("tail") || name.contains("wing") || name.contains("fin") ? Kind.LOOSE : null;
            if (kind != null) out.add(new Limb(child.getValue(), kind, List.of()));
            // A limb's own parts go with it; only what is not a limb is looked into further.
            else named(child.getValue(), out, depth + 1);
        }
    }

    /**
     * Buffers that keep only what is on one side of a plane, given in the space the corners arrive in: each quad or
     * triangle clipped against it as it is handed over, and where one is cut, the line it is cut along kept, to
     * close into the cut face. A part of the model wholly on the kept side goes through whole (so it may go limp
     * without being cut), one wholly on the other is left out.
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

        private float side(float x, float y, float z) {
            float d = (x - point.x) * keep.x + (y - point.y) * keep.y + (z - point.z) * keep.z;
            return Math.abs(d) < 1e-6f ? 1e-6f : d;
        }

        /** Where a model part's cubes lie against the plane, posed as they are now. */
        int lies(Matrix4f pose, List<ModelPart.Cube> cubes) {
            if (cubes.isEmpty()) return UNKNOWN;
            int kept = 0, gone = 0;
            Vector3f corner = new Vector3f();
            for (ModelPart.Cube cube : cubes)
                for (int c = 0; c < 8; c++) {
                    corner.set(((c & 1) == 0 ? cube.minX : cube.maxX) / 16, ((c & 2) == 0 ? cube.minY : cube.maxY) / 16, ((c & 4) == 0 ? cube.minZ : cube.maxZ) / 16);
                    pose.transformPosition(corner);
                    if (side(corner.x, corner.y, corner.z) >= 0) kept++; else gone++;
                }
            return gone == 0 ? WHOLE : kept == 0 ? GONE : CUT;
        }

        /** What of a polygon (corners of {@link QuadCarver#STRIDE} floats) is on the kept side, its cut line noted. */
        float[] clip(float[] polygon) {
            int s = QuadCarver.STRIDE, n = polygon.length / s;
            float[] out = new float[(n + 2) * s];
            float[] crossed = new float[6];
            int kept = 0, crossings = 0;
            for (int i = 0; i < n; i++) {
                int a = i * s, b = (i + 1) % n * s;
                float da = side(polygon[a], polygon[a + 1], polygon[a + 2]), db = side(polygon[b], polygon[b + 1], polygon[b + 2]);
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

        /**
         * The cut faces: each closed loop of cut lines, filled. Raw blood red, darker at the rim, never too dark to see;
         * seared instead when the blade burned, charred at the rim with the heat's light over it.
         */
        void faces(boolean seared, float heat, int light) {
            if (lines.isEmpty()) return;
            VertexConsumer out = buffers.getBuffer(RenderType.entitySolid(WorldEffects.WHITE));
            used.add(RenderType.entitySolid(WorldEffects.WHITE));
            float[] in, rim;
            int lit;
            if (seared) {
                float[] glow = HoleHeat.glow(heat, new float[3]);
                in = new float[]{Math.min(1, FLESH[0] + glow[0] * .55f), Math.min(1, FLESH[1] + glow[1] * .55f), Math.min(1, FLESH[2] + glow[2] * .55f)};
                rim = new float[]{Math.min(1, CHAR[0] + glow[0]), Math.min(1, CHAR[1] + glow[1]), Math.min(1, CHAR[2] + glow[2])};
                lit = LightTexture.pack(Math.max(LightTexture.block(light), Math.round(15 * heat)), LightTexture.sky(light));
            } else {
                in = RAW;
                rim = RAW_RIM;
                lit = LightTexture.pack(Math.max(LightTexture.block(light), RAW_LIGHT), LightTexture.sky(light));
            }
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
                fill(out, loop, outward, in, rim, lit);
            }
        }

        /** One loop, a fan from its middle. */
        private static void fill(VertexConsumer out, List<Vector3f> loop, Vector3f outward, float[] in, float[] rim, int light) {
            Vector3f middle = new Vector3f(), turn = new Vector3f();
            for (int i = 0; i < loop.size(); i++) {
                Vector3f a = loop.get(i), b = loop.get((i + 1) % loop.size());
                middle.add(a);
                turn.add((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y));
            }
            middle.div(loop.size());
            // Wound to face out of the half, so only its outside is drawn.
            if (turn.dot(outward) < 0) java.util.Collections.reverse(loop);
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
                int lies = drawingPart == null || drawingPiece == null ? UNKNOWN : drawingPiece.parts.getOrDefault(drawingPart, UNKNOWN);
                if (lies == GONE) return;
                float[] left = lies == WHOLE ? polygon : clip(polygon);
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
