package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.server.GravityGrasp;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Gravity Grasp, seen: the air bending toward the hand (shaders/program/gravity.fsh), and nothing else. No particle, no
 * light and nothing solid. While the hand points, the air out along it is drawn in toward the palm, faintly at first and
 * harder the longer it is held; once let go and a body is hauled in, the bend runs from that body to the hand, strong
 * and fast, and the space round the body ripples in after it; caught, it settles out in a few ticks.
 *
 * <p>Its own post chain, apart from TemporalScreen's, so a stopped world or a Time Branch charge never takes its place.
 * One grasp is drawn at a time: the nearest in sight, within {@link #VISIBLE} blocks.
 */
public final class GraspLens {
    private GraspLens() {}

    private static final double VISIBLE = 48;
    /** How far out along the look the bend reaches while the hand only points, at most. */
    private static final double POINTING = 10;
    /** Ticks it takes to settle out once the grasp is over. */
    private static final float SETTLE = 6;
    private static PostChain chain;
    private static int width, height;
    private static boolean failed;
    /** The last drawn: who, toward what, how strongly, and since when it has been settling (or -1 while live). */
    private static int caster = -1;
    private static Vec3 lastSink, lastSource;
    private static float lastStrength, lastHaul, settling = -1;

    public static void close() {
        if (chain != null) chain.close();
        chain = null;
        failed = false;
        caster = -1;
        settling = -1;
    }

    public static void render(RenderLevelStageEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || failed) return;
        float partial = e.getPartialTick();
        Vec3 camera = e.getCamera().getPosition();
        double time = ClientState.time(partial);
        Player best = null;
        double nearest = VISIBLE * VISIBLE;
        for (Player p : mc.level.players()) {
            if (p.isSpectator() || ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING) <= 0) continue;
            double far = p.getEyePosition(partial).distanceToSqr(camera);
            if (far < nearest) {nearest = far; best = p;}
        }
        Vec3 sink, source;
        float strength, haul;
        if (best != null) {
            CompoundTag d = ClientState.data(best.getId());
            long start = d.getLong(GravityGrasp.HOLDING), hauled = d.getLong(GravityGrasp.HAULING);
            sink = palm(best, partial);
            Entity body = hauled > 0 ? mc.level.getEntity(d.getInt(GravityGrasp.TARGET)) : null;
            if (body != null) {
                source = body.getPosition(partial).add(0, body.getBbHeight() * .5, 0);
                double since = time - hauled, power = GravityGrasp.gathered(hauled - start);
                strength = (float) ((.62 + .38 * power) * Mth.clamp(.45 + since / 3, 0, 1));
                haul = 1;
            } else {
                Vec3 eye = best.getEyePosition(partial), look = best.getViewVector(partial);
                HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(POINTING)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, best));
                source = hit.getLocation();
                if (source.distanceToSqr(sink) < 1) source = sink.add(look.scale(1.5));
                double pointed = time - start;
                // Faint the moment the hand is out, and gathering from there: the longer it is held, the harder the air bends.
                strength = (float) (Mth.clamp(pointed / 5, 0, 1) * (.22 + .38 * GravityGrasp.gathered((long) pointed)
                    + .1 * Mth.clamp(pointed / GravityGrasp.GATHER, 0, 1)));
                haul = 0;
            }
            // Someone else's hand behind a wall bends nothing on this side of it.
            if (best != mc.player && mc.level.clip(new ClipContext(camera, sink, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player))
                .getType() != HitResult.Type.MISS) best = null;
        } else {
            sink = source = null;
            strength = haul = 0;
        }
        if (best != null) {
            caster = best.getId();
            lastSink = sink;
            lastSource = source;
            lastStrength = strength;
            lastHaul = haul;
            settling = -1;
        } else if (caster >= 0 && lastSink != null) {
            // Over (caught, dropped, or out of sight): what was last drawn settles out.
            if (settling < 0) settling = (float) time;
            float left = 1 - (float) (time - settling) / SETTLE;
            if (left <= 0) {caster = -1; settling = -1; return;}
            sink = lastSink;
            source = lastSource;
            strength = lastStrength * left;
            haul = lastHaul;
        } else return;
        if (strength <= .001f) return;

        Matrix4f view = e.getPoseStack().last().pose(), projection = e.getProjectionMatrix();
        float[] a = screen(view, projection, sink.subtract(camera)), b = screen(view, projection, source.subtract(camera));
        if (a == null && b == null) return;
        // An end behind the camera is brought round to the near side along the corridor, so the bend still reads.
        if (a == null || b == null) {
            Vec3 front = a == null ? source : sink, back = a == null ? sink : source;
            var lv = e.getCamera().getLookVector();
            Vec3 forward = new Vec3(lv.x(), lv.y(), lv.z());
            double fd = front.subtract(camera).dot(forward), bd = back.subtract(camera).dot(forward);
            double cut = (fd - .2) / Math.max(1e-4, fd - bd);
            float[] near = screen(view, projection, front.add(back.subtract(front).scale(Mth.clamp(cut, 0, 1))).subtract(camera));
            if (near == null) return;
            if (a == null) a = near; else b = near;
        }
        float sinkWidth = Mth.clamp(a[2] * .35f, .02f, .11f), sourceWidth = Mth.clamp(b[2] * (haul > 0 ? .75f : .9f), .025f, .14f);
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), HexGodOfStories.id("shaders/post/gravity.json"));
                width = 0;
            }
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (width != w || height != h) {chain.resize(w, h); width = w; height = h;}
            for (var pass : ((com.hexgodofstories.mixin.PostChainAccessor) (Object) chain).hgos$passes()) {
                var fx = pass.getEffect();
                var u = fx.getUniform("Strength");
                if (u != null) u.set(Mth.clamp(strength, 0, 1));
                u = fx.getUniform("Phase");
                if (u != null) u.set((float) (time / 20 % 1000));
                u = fx.getUniform("Haul");
                if (u != null) u.set(haul);
                u = fx.getUniform("Sink");
                if (u != null) u.set(a[0], a[1]);
                u = fx.getUniform("Source");
                if (u != null) u.set(b[0], b[1]);
                u = fx.getUniform("Widths");
                if (u != null) u.set(sinkWidth, sourceWidth);
            }
            RenderSystem.disableDepthTest();
            chain.process(partial);
            mc.getMainRenderTarget().bindWrite(false);
            RenderSystem.enableDepthTest();
        } catch (Exception ex) {
            failed = true;
            if (chain != null) {chain.close(); chain = null;}
            org.slf4j.LoggerFactory.getLogger("HexGodOfStories").warn("Gravity Grasp's lens unavailable", ex);
            mc.getMainRenderTarget().bindWrite(false);
        }
    }

    /** Where the hand is: the same place the server hauls toward (GravityGrasp#palm), smoothed for drawing. */
    private static Vec3 palm(Player p, float partial) {
        Vec3 look = p.getViewVector(partial), flat = new Vec3(look.x, 0, look.z);
        Vec3 right = flat.lengthSqr() < 1e-6 ? Vec3.ZERO : new Vec3(-flat.z, 0, flat.x).normalize();
        return p.getEyePosition(partial).add(look.scale(1.25)).add(right.scale(-.3)).add(0, -.38, 0);
    }

    /**
     * A camera-relative point on the screen: texture x and y (0 to 1, y up as the frame is sampled) and how tall a block
     * there stands on it, as a share of the screen's height; null behind the camera.
     */
    private static float[] screen(Matrix4f view, Matrix4f projection, Vec3 at) {
        Vector4f v = new Vector4f((float) at.x, (float) at.y, (float) at.z, 1);
        view.transform(v);
        projection.transform(v);
        if (v.w <= .05f) return null;
        float x = v.x / v.w, y = v.y / v.w;
        return new float[]{x * .5f + .5f, y * .5f + .5f, projection.m11() / v.w * .5f};
    }
}
