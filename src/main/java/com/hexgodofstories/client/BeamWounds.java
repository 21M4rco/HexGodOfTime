package com.hexgodofstories.client;

import com.hexgodofstories.data.WoundGeometry;
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
 * both openings are written into the depth buffer with colour writes off. When the body's batch
 * reaches the GPU a moment later, its surface inside the openings is behind that depth and fails the
 * depth test, so what shows through is whatever was already drawn behind the body. The glowing rim is
 * drawn after the body, additively. Openings are clipped to the cube face they sit on and shrink to fit
 * it, so a hole never cuts into the space beside a limb.
 *
 * <p>A body with no vanilla model part for the beam's line to meet (GeckoLib, Citadel, a mod's own
 * renderer, or a line that only grazed the parts) is cut the same way from the surfaces it actually
 * draws: {@link WoundSurface} notes them as they go by, and the openings are cut from the faces the line
 * enters and leaves through, before that batch is drawn. A line that only enters leaves a deep pit.
 */
public final class BeamWounds {
    private BeamWounds() { }

    private static final int RING = 18;

    private static final class Wound {
        final Vec3 point, direction;
        final float radius;
        final long start;
        final int life;
        Object part;
        final Vector3f entry = new Vector3f(), exit = new Vector3f(), axis = new Vector3f();
        int entryAxis, exitAxis;
        float entrySide, exitSide;
        float[] face = new float[6];
        // Candidate while pinning.
        Object candidate;
        float candidateT = Float.POSITIVE_INFINITY;
        final Vector3f candidateEntry = new Vector3f(), candidateExit = new Vector3f(), candidateAxis = new Vector3f();
        int candidateEntryAxis, candidateExitAxis;
        float candidateEntrySide, candidateExitSide;
        float[] candidateFace = new float[6];
        long seen = -100;
        // This frame's rim, in view space, drawn after the body.
        float[] rim;
        float heat;
        // Never pinned to a vanilla part after two draws: cut from the drawn surface instead.
        int misses;
        boolean surface;
        /** Where the entry opening was last drawn, in the world, for blood to pour from. */
        Vec3 mouth;

        Wound(Vec3 point, Vec3 direction, float radius, long start, int life) {
            this.point = point;
            this.direction = direction;
            this.radius = radius;
            this.start = start;
            this.life = life;
        }

        float age(float partial) {return ClientState.since(start, partial) / life;}
    }

    private static final Map<Integer, List<Wound>> WOUNDS = new HashMap<>();
    private static boolean world;
    private static Matrix4f inverseView, view;
    private static Vec3 camera = Vec3.ZERO;

    public static void clear() {WOUNDS.clear();}

    public static void receive(int entity, CompoundTag n) {
        // Body space at the instant of the hit: relative to the body, turned back by its facing.
        float yaw = n.getFloat("yaw") * Mth.DEG_TO_RAD;
        Vec3 point = new Vec3(n.getDouble("x") - n.getDouble("ex"), n.getDouble("y") - n.getDouble("ey"),
            n.getDouble("z") - n.getDouble("ez")).yRot(yaw);
        Vec3 direction = new Vec3(n.getDouble("dx"), n.getDouble("dy"), n.getDouble("dz")).normalize().yRot(yaw);
        List<Wound> list = WOUNDS.computeIfAbsent(entity, k -> new ArrayList<>());
        if (list.size() >= 8) list.remove(0);
        if (WOUNDS.size() > 256) WOUNDS.clear();
        list.add(new Wound(point, direction, n.getFloat("r"), n.getLong("start"), Math.max(1, n.getInt("life"))));
    }

    public static void tick() {
        var level = Minecraft.getInstance().level;
        if (level == null) {WOUNDS.clear(); return;}
        long now = ClientState.now();
        WOUNDS.entrySet().removeIf(e -> {
            e.getValue().removeIf(w -> now > w.start + w.life);
            return e.getValue().isEmpty() || level.getEntity(e.getKey()) == null;
        });
    }

    public static void beginFrame(RenderLevelStageEvent event) {
        world = true;
        view = new Matrix4f(event.getPoseStack().last().pose());
        inverseView = new Matrix4f(view).invert();
        camera = event.getCamera().getPosition();
    }

    public static void endFrame() {world = false;}

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
            // Not pinned yet: does the beam run through one of this part's cubes?
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
            for (ModelPart.Cube cube : cubes) {
                float[] box = {cube.minX / 16f, cube.minY / 16f, cube.minZ / 16f, cube.maxX / 16f, cube.maxY / 16f, cube.maxZ / 16f};
                float[] hit = slab(o, d, box);
                if (hit == null || hit[0] >= w.candidateT) continue;
                w.candidate = part;
                w.candidateT = hit[0];
                w.candidateEntry.set(o).add(new Vector3f(d).mul(hit[0]));
                w.candidateExit.set(o).add(new Vector3f(d).mul(hit[1]));
                w.candidateAxis.set(d).normalize();
                w.candidateEntryAxis = (int) hit[2];
                w.candidateEntrySide = hit[3];
                w.candidateExitAxis = (int) hit[4];
                w.candidateExitSide = hit[5];
                w.candidateFace = box;
            }
        }
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

    /** From WoundSurface, ahead of the body's batch: cut every surface wound, then lay its rim over the body. */
    static void cutSurface(Entity host, float partial, WoundSurface surface, MultiBufferSource buffers, int light) {
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null || surface.faces() == 0 || view == null || inverseView == null) return;
        VertexConsumer out = null;
        for (Wound w : list) {
            if (!w.surface || w.part != null || !drawSurface(w, host, partial, surface) || w.rim == null) continue;
            if (out == null) out = buffers.getBuffer(ScepterRenderTypes.glass(WorldEffects.WHITE));
            emitRim(out, w.rim, light);
            w.rim = null;
        }
    }

    /** Cuts one wound from the recorded faces, in view space. False when the line never meets the body. */
    private static boolean drawSurface(Wound w, Entity host, float partial, WoundSurface surface) {
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
        float reach = Math.max(2, host.getBbWidth() + host.getBbHeight()) * 2;
        var hits = WoundGeometry.crossings(faces, corners, count, new Vector3f(o).sub(new Vector3f(d).mul(reach)), d, reach * 2);
        if (hits.isEmpty()) {
            // A beam that only grazed the body: move its line across onto the nearest part of it.
            Vector3f shift = WoundGeometry.toward(faces, corners, count, o, d);
            if (shift == null) return false;
            o.add(shift);
            hits = WoundGeometry.crossings(faces, corners, count, new Vector3f(o).sub(new Vector3f(d).mul(reach)), d, reach * 2);
            if (hits.isEmpty()) return false;
        }
        Vector3f start = new Vector3f(o).sub(new Vector3f(d).mul(reach));
        var first = hits.get(0);
        var last = hits.get(hits.size() - 1);
        Vector3f entry = new Vector3f(d).mul(first.t()).add(start);
        Vector3f exit = new Vector3f(d).mul(last.t()).add(start);
        boolean through = hits.size() > 1 && last.t() - first.t() > .02f;
        Vector3f inNormal = WoundGeometry.normal(faces, first.face());
        if (inNormal.dot(d) > 0) inNormal.negate();
        Vector3f outNormal = through ? WoundGeometry.normal(faces, last.face()) : new Vector3f(inNormal).negate();
        if (outNormal.dot(d) < 0) outNormal.negate();
        // Fit each opening to the face it is on, but never below a third of its size on a finely cut mesh.
        float room = WoundGeometry.room(faces, corners, first.face(), entry);
        if (through) room = Math.min(room, WoundGeometry.room(faces, corners, last.face(), exit));
        r = Math.min(r, Math.max(room * .95f, r * .35f));
        if (!through) exit = new Vector3f(d).mul(Math.min(.3f, host.getBbWidth() * .45f)).add(entry);
        float lift = armoured(host) ? .07f : .006f;
        Vector3f u = new Vector3f(), v = new Vector3f();
        perpendicular(d, u, v);
        Vector3f[] in = slide(entry, d, u, v, r, inNormal);
        Vector3f[] out = through ? slide(exit, d, u, v, r, outNormal) : shrink(slide(exit, d, u, v, r, outNormal), exit, .6f);
        w.heat = Mth.clamp(1 - w.age(partial) * 1.6f, 0, 1);
        w.mouth = world(entry);
        cut(in, out, lifted(in, inNormal, lift), lifted(out, outNormal, lift), w.heat, through);
        Vector3f[] inRim = lifted(in, inNormal, lift + .003f), inOuter = lifted(slide(entry, d, u, v, r * 1.75f, inNormal), inNormal, lift + .003f);
        w.rim = through
            ? rimOf(inRim, inOuter, lifted(out, outNormal, lift + .003f), lifted(slide(exit, d, u, v, r * 1.75f, outNormal), outNormal, lift + .003f))
            : rimOf(inRim, inOuter);
        w.seen = ClientState.now();
        return true;
    }

    /** A ring about {@code centre} across the beam, laid onto the face through {@code centre}. */
    private static Vector3f[] slide(Vector3f centre, Vector3f axis, Vector3f u, Vector3f v, float r, Vector3f normal) {
        Vector3f[] out = new Vector3f[RING];
        for (int k = 0; k < RING; k++) {
            double angle = 2 * Math.PI * k / RING;
            Vector3f p = new Vector3f(centre).add(new Vector3f(u).mul((float) (Math.cos(angle) * r)))
                .add(new Vector3f(v).mul((float) (Math.sin(angle) * r)));
            out[k] = WoundGeometry.onto(p, axis, centre, normal);
        }
        return out;
    }

    private static Vector3f[] shrink(Vector3f[] ring, Vector3f centre, float by) {
        Vector3f[] out = new Vector3f[ring.length];
        for (int k = 0; k < ring.length; k++) out[k] = new Vector3f(ring[k]).sub(centre).mul(by).add(centre);
        return out;
    }

    /** Rim rings, inner then outer for each mouth, packed the way {@link #emitRim} reads them. */
    private static float[] rimOf(Vector3f[]... rings) {
        float[] rim = new float[RING * 6 * (rings.length / 2)];
        int o = 0;
        for (int m = 0; m + 1 < rings.length; m += 2)
            for (int k = 0; k < RING; k++) {
                rim[o++] = rings[m][k].x; rim[o++] = rings[m][k].y; rim[o++] = rings[m][k].z;
                rim[o++] = rings[m + 1][k].x; rim[o++] = rings[m + 1][k].y; rim[o++] = rings[m + 1][k].z;
            }
        return rim;
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
                w.entry.set(w.candidateEntry);
                w.exit.set(w.candidateExit);
                w.axis.set(w.candidateAxis);
                w.entryAxis = w.candidateEntryAxis;
                w.entrySide = w.candidateEntrySide;
                w.exitAxis = w.candidateExitAxis;
                w.exitSide = w.candidateExitSide;
                w.face = w.candidateFace;
                w.seen = ClientState.now();
            }
            w.candidate = null;
            w.candidateT = Float.POSITIVE_INFINITY;
            // A part that has stopped being drawn (armour taken off, a model swap) lets the wound re-pin.
            if (w.part != null && ClientState.now() - w.seen > 4) w.part = null;
            if (w.part != null) {w.misses = 0; w.surface = false;}
            else if (!w.surface && ++w.misses >= 2) w.surface = true;
        }
    }

    /**
     * Ray against an axis-aligned box: entry and exit distances with the face each happens on, or null.
     * Returns {tEntry, tExit, entryAxis, entrySide, exitAxis, exitSide}.
     */
    private static float[] slab(Vector3f o, Vector3f d, float[] box) {
        float near = Float.NEGATIVE_INFINITY, far = Float.POSITIVE_INFINITY;
        int nearAxis = 0, farAxis = 0;
        float nearSide = 0, farSide = 0;
        float[] origin = {o.x, o.y, o.z}, dir = {d.x, d.y, d.z};
        for (int k = 0; k < 3; k++) {
            if (Math.abs(dir[k]) < 1e-7f) {
                if (origin[k] < box[k] || origin[k] > box[k + 3]) return null;
                continue;
            }
            float t0 = (box[k] - origin[k]) / dir[k], t1 = (box[k + 3] - origin[k]) / dir[k];
            float s0 = box[k], s1 = box[k + 3];
            if (t0 > t1) {float t = t0; t0 = t1; t1 = t; float s = s0; s0 = s1; s1 = s;}
            if (t0 > near) {near = t0; nearAxis = k; nearSide = s0;}
            if (t1 < far) {far = t1; farAxis = k; farSide = s1;}
        }
        if (near > far || far < 0) return null;
        return new float[]{near, far, nearAxis, nearSide, farAxis, farSide};
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

    /** Draws the tunnel and cuts both openings, right now, ahead of the body's own batch. */
    private static void draw(Wound w, Entity host, Matrix4f pose, float partial) {
        float r = radius(w, partial);
        if (r < .004f) {w.rim = null; return;}
        float age = w.age(partial);
        // Fit each opening inside its own face so the hole never cuts the space beside the limb.
        r = Math.min(r, fit(w.entry, w.entryAxis, w.face) * .95f);
        r = Math.min(r, fit(w.exit, w.exitAxis, w.face) * .95f);
        if (r < .004f) {w.rim = null; return;}
        // Armour sits up to a pixel outside the body and is drawn after it; an armoured body is cut from
        // just beyond the armour's surface in, so the plate is holed along with the flesh under it.
        float lift = armoured(host) ? .07f : .006f;
        Vector3f u = new Vector3f(), v = new Vector3f();
        perpendicular(w.axis, u, v);
        Vector3f[] in = opening(w, w.entry, w.entryAxis, w.entrySide, u, v, r);
        Vector3f[] out = opening(w, w.exit, w.exitAxis, w.exitSide, u, v, r);
        Vector3f[] inView = transform(pose, in), outView = transform(pose, out);
        Vector3f inNormal = faceNormal(w.entryAxis, w.entrySide, w.face), outNormal = faceNormal(w.exitAxis, w.exitSide, w.face);
        Vector3f[] inLift = transform(pose, lifted(in, inNormal, lift)), outLift = transform(pose, lifted(out, outNormal, lift));
        w.heat = Mth.clamp(1 - age * 1.6f, 0, 1);

        w.mouth = world(centre(inView));
        cut(inView, outView, inLift, outLift, w.heat, true);
        // The burning rim for after the body, both mouths.
        float[] rim = new float[RING * 2 * 2 * 3];
        int o = 0;
        Vector3f[] inOuter = transform(pose, lifted(ring(w, w.entry, w.entryAxis, w.entrySide, u, v, r * 1.75f), inNormal, lift + .003f));
        Vector3f[] outOuter = transform(pose, lifted(ring(w, w.exit, w.exitAxis, w.exitSide, u, v, r * 1.75f), outNormal, lift + .003f));
        Vector3f[] inInner = transform(pose, lifted(in, inNormal, lift + .003f)), outInner = transform(pose, lifted(out, outNormal, lift + .003f));
        for (Vector3f[][] pair : new Vector3f[][][]{{inInner, inOuter}, {outInner, outOuter}})
            for (int k = 0; k < RING; k++) {
                rim[o++] = pair[0][k].x; rim[o++] = pair[0][k].y; rim[o++] = pair[0][k].z;
                rim[o++] = pair[1][k].x; rim[o++] = pair[1][k].y; rim[o++] = pair[1][k].z;
            }
        w.rim = rim;
    }

    /**
     * Draws the tunnel walls right now, ahead of the body's own batch, then cuts the openings into the
     * depth buffer. {@code through} is false for a pit the line only entered: its far end is a dark floor
     * rather than a second opening.
     */
    private static void cut(Vector3f[] inView, Vector3f[] outView, Vector3f[] inLift, Vector3f[] outLift, float heat, boolean through) {
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            // Tunnel: raw red flesh at the mouths, darkening toward the middle.
            b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            int bands = 4;
            for (int k = 0; k < RING; k++) {
                int k1 = (k + 1) % RING;
                for (int s = 0; s < bands; s++) {
                    float a0 = s / (float) bands, a1 = (s + 1) / (float) bands;
                    Vector3f p00 = mix(inView[k], outView[k], a0), p01 = mix(inView[k1], outView[k1], a0);
                    Vector3f p10 = mix(inView[k], outView[k], a1), p11 = mix(inView[k1], outView[k1], a1);
                    int c0 = tunnel(a0, heat), c1 = tunnel(a1, heat);
                    vertex(b, p00, c0); vertex(b, p01, c0); vertex(b, p11, c1);
                    vertex(b, p00, c0); vertex(b, p11, c1); vertex(b, p10, c1);
                }
            }
            if (!through) fan(b, outView);
            BufferUploader.drawWithShader(b.end());
            // Openings: depth only, and only where nothing already drawn is nearer. Written unconditionally,
            // they would erase a block standing in front of the body, and the body's own far side would
            // then show through that block.
            RenderSystem.colorMask(false, false, false, false);
            b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            if (through) {
                boolean entryNearer = centre(inLift).lengthSquared() < centre(outLift).lengthSquared();
                fan(b, entryNearer ? outLift : inLift);
                fan(b, entryNearer ? inLift : outLift);
            } else fan(b, inLift);
            BufferUploader.drawWithShader(b.end());
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableCull();
        }
    }

    private static Vec3 world(Vector3f viewPoint) {
        Vector3f p = inverseView.transformPosition(new Vector3f(viewPoint));
        return camera.add(p.x, p.y, p.z);
    }

    /** From the entity's post-render event: the rims, added over the body now that it is drawn. */
    public static void afterEntity(Entity host, MultiBufferSource buffers, int light) {
        if (!world) return;
        List<Wound> list = WOUNDS.get(host.getId());
        if (list == null) return;
        VertexConsumer out = null;
        for (Wound w : list) {
            if (w.rim == null || w.seen != ClientState.now()) continue;
            // Blended, not added: a dark wet ring reads on pale skin and dark hide alike.
            if (out == null) out = buffers.getBuffer(ScepterRenderTypes.glass(WorldEffects.WHITE));
            emitRim(out, w.rim, light);
            w.rim = null;
        }
    }

    /** Every mouth in {@code rim}: raw at the edge of the opening, fading into the skin around it. */
    private static void emitRim(VertexConsumer out, float[] rim, int light) {
        int mouths = rim.length / (RING * 6);
        for (int mouth = 0; mouth < mouths; mouth++) {
            int base = mouth * RING * 6;
            for (int k = 0; k < RING; k++) {
                int a = base + k * 6, b = base + ((k + 1) % RING) * 6;
                rimVertex(out, rim, a, true, light);
                rimVertex(out, rim, b, true, light);
                rimVertex(out, rim, b + 3, false, light);
                rimVertex(out, rim, a, true, light);
                rimVertex(out, rim, b + 3, false, light);
                rimVertex(out, rim, a + 3, false, light);
            }
        }
    }

    private static void rimVertex(VertexConsumer out, float[] rim, int i, boolean inner, int light) {
        out.vertex(rim[i], rim[i + 1], rim[i + 2], inner ? .40f : .26f, inner ? .02f : .01f, inner ? .03f : .01f, inner ? .95f : 0,
            .5f, .5f, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, light, 0, 0, 1);
    }

    private static int tunnel(float along, float heat) {
        // 0 at a mouth, 1 at the middle.
        float depth = 1 - Math.abs(along * 2 - 1);
        float r = .52f - .36f * depth, g = .06f - .045f * depth, b = .06f - .04f * depth;
        return 0xff000000 | ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, g) * 255) << 8) | (int) (Math.min(1, b) * 255);
    }

    private static void vertex(BufferBuilder b, Vector3f p, int argb) {
        b.vertex(p.x, p.y, p.z).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255).endVertex();
    }

    private static void fan(BufferBuilder b, Vector3f[] ring) {
        Vector3f c = centre(ring);
        for (int k = 0; k < ring.length; k++) {
            vertex(b, c, 0xff000000);
            vertex(b, ring[k], 0xff000000);
            vertex(b, ring[(k + 1) % ring.length], 0xff000000);
        }
    }

    private static Vector3f centre(Vector3f[] ring) {
        Vector3f c = new Vector3f();
        for (Vector3f p : ring) c.add(p);
        return c.div(ring.length);
    }

    private static Vector3f mix(Vector3f a, Vector3f b, float t) {return new Vector3f(a).lerp(b, t);}

    private static void perpendicular(Vector3f axis, Vector3f u, Vector3f v) {
        Vector3f ref = Math.abs(axis.y) < .9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        axis.cross(ref, u).normalize();
        axis.cross(u, v).normalize();
    }

    /** Where the tunnel meets a face: the cylinder's section in the face plane, clamped to the face. */
    private static Vector3f[] opening(Wound w, Vector3f centre, int axis, float side, Vector3f u, Vector3f v, float r) {
        return ring(w, centre, axis, side, u, v, r);
    }

    private static Vector3f[] ring(Wound w, Vector3f centre, int axis, float side, Vector3f u, Vector3f v, float r) {
        Vector3f[] out = new Vector3f[RING];
        float[] a = {w.axis.x, w.axis.y, w.axis.z};
        for (int k = 0; k < RING; k++) {
            double angle = 2 * Math.PI * k / RING;
            Vector3f p = new Vector3f(centre).add(new Vector3f(u).mul((float) (Math.cos(angle) * r)))
                .add(new Vector3f(v).mul((float) (Math.sin(angle) * r)));
            // Slide along the beam onto the face plane.
            float[] q = {p.x, p.y, p.z};
            if (Math.abs(a[axis]) > 1e-4f) {
                float t = (side - q[axis]) / a[axis];
                p.add(new Vector3f(w.axis).mul(t));
            }
            float[] c = {p.x, p.y, p.z};
            for (int j = 0; j < 3; j++) if (j != axis) c[j] = Mth.clamp(c[j], w.face[j], w.face[j + 3]);
            out[k] = new Vector3f(c[0], c[1], c[2]);
        }
        return out;
    }

    private static float fit(Vector3f centre, int axis, float[] face) {
        float room = Float.POSITIVE_INFINITY;
        float[] c = {centre.x, centre.y, centre.z};
        for (int j = 0; j < 3; j++) {
            if (j == axis) continue;
            room = Math.min(room, Math.min(c[j] - face[j], face[j + 3] - c[j]));
        }
        return Math.max(0, room);
    }

    private static Vector3f faceNormal(int axis, float side, float[] face) {
        Vector3f n = new Vector3f();
        float sign = Math.abs(side - face[axis]) < Math.abs(side - face[axis + 3]) ? -1 : 1;
        if (axis == 0) n.x = sign;
        else if (axis == 1) n.y = sign;
        else n.z = sign;
        return n;
    }

    private static Vector3f[] lifted(Vector3f[] ring, Vector3f normal, float by) {
        Vector3f[] out = new Vector3f[ring.length];
        for (int k = 0; k < ring.length; k++) out[k] = new Vector3f(ring[k]).add(new Vector3f(normal).mul(by));
        return out;
    }

    private static Vector3f[] transform(Matrix4f pose, Vector3f[] ring) {
        Vector3f[] out = new Vector3f[ring.length];
        for (int k = 0; k < ring.length; k++) out[k] = pose.transformPosition(new Vector3f(ring[k]));
        return out;
    }
}
