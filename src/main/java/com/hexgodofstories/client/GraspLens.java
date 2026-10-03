package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.server.GravityGrasp;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gravity Grasp, seen: a cone of bent air with its point fixed on the palm (shaders/core/gravity_lens), and nothing
 * else. No particle, no light and nothing solid. While the hand points, the cone opens out along the look to whatever is
 * there, the air in it drawn in toward the palm, faintly at first and harder the longer it is held; once let go and a
 * body is hauled in, it runs from that body to the hand, strong and fast; caught, it settles out in a few ticks.
 *
 * <p>It is a real cone in the world: a shell drawn against the depth of everything already there (so a wall or a body in
 * front of it hides it, as it would hide anything else), each pixel of it redrawing the frame behind from a copy taken
 * just before, bent toward the palm. The palm is where the model's own left hand is drawn this frame (GraspHandLayer),
 * so the cone sits on the hand in first person and in third alike; where no hand was drawn, where it would be.
 */
public final class GraspLens {
    private GraspLens() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double VISIBLE = 48;
    /** How far out along the look the cone reaches while the hand only points, at most. */
    private static final double POINTING = 9;
    /** Ticks it takes to settle out once the grasp is over; the most drawn at once. */
    private static final float SETTLE = 6;
    private static final int MOST = 4;
    /** The cone's shell: rings along it and quads round each. */
    private static final int RINGS = 16, SIDES = 28;
    private static final float PALM_RADIUS = .16f;

    private static ShaderInstance shader;
    private static TextureTarget scene;
    private static VertexBuffer shell;
    private static boolean failed;

    /** This frame: its count, the camera, and the way from the camera's view back to the world (for GraspHandLayer). */
    private static int frame;
    private static Vec3 camera = Vec3.ZERO;
    private static Matrix4f toWorld = new Matrix4f();

    private record Hand(int frame, Vec3 palm) {}
    private record Cone(Vec3 apex, Vec3 axis, double length, float radius, float strength, float haul) {
        Cone fade(float left) {return new Cone(apex, axis, length, radius, strength * left, haul);}
    }
    private record Fading(Cone cone, double since) {}
    private static final Map<Integer, Hand> HANDS = new HashMap<>();
    private static final Map<Integer, Cone> LAST = new HashMap<>();
    private static final Map<Integer, Fading> FADING = new HashMap<>();

    @Mod.EventBusSubscriber(modid = HexGodOfStories.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        private Shaders() {}

        @SubscribeEvent public static void register(RegisterShadersEvent e) {
            try {
                e.registerShader(new ShaderInstance(e.getResourceProvider(), HexGodOfStories.id("gravity_lens"), DefaultVertexFormat.POSITION), s -> shader = s);
            } catch (Exception broken) {
                shader = null;
                LOGGER.error("Gravity Grasp's lens shader failed to load; the pull will not be seen", broken);
            }
        }
    }

    public static void close() {
        if (scene != null) scene.destroyBuffers();
        if (shell != null) shell.close();
        scene = null;
        shell = null;
        failed = false;
        HANDS.clear();
        LAST.clear();
        FADING.clear();
    }

    /** Each frame, before anything is drawn: the camera this frame's hands are read against. */
    public static void beginFrame(RenderLevelStageEvent e) {
        frame++;
        camera = e.getCamera().getPosition();
        toWorld = new Matrix4f(e.getPoseStack().last().pose()).invert();
    }

    /** From GraspHandLayer: where this player's left palm was just drawn (view space). */
    static void hand(AbstractClientPlayer p, Vector4f palm) {
        Vector4f at = toWorld.transform(new Vector4f(palm));
        Vec3 world = camera.add(at.x, at.y, at.z);
        // Anything drawn somewhere other than the world's own pass (a hand drawn in a space of its own) lands nowhere
        // near the body: it is not taken.
        if (world.distanceToSqr(p.getEyePosition()) > 2.5 * 2.5) return;
        HANDS.put(p.getId(), new Hand(frame, world));
    }

    /** Last in the world's pass: every grasp in sight, drawn into the frame. */
    public static void render(RenderLevelStageEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || failed || shader == null) return;
        float partial = e.getPartialTick();
        double time = ClientState.time(partial);
        Vec3 eye = e.getCamera().getPosition();
        List<Cone> cones = new ArrayList<>();
        Set<Integer> live = new HashSet<>();
        for (Player p : mc.level.players()) {
            if (p.isSpectator() || p.getEyePosition(partial).distanceToSqr(eye) > VISIBLE * VISIBLE) continue;
            CompoundTag d = ClientState.data(p.getId());
            long start = d.getLong(GravityGrasp.HOLDING);
            if (start <= 0) continue;
            Cone c = cone(mc, p, d, start, partial, time);
            live.add(p.getId());
            LAST.put(p.getId(), c);
            FADING.remove(p.getId());
            cones.add(c);
        }
        // Over (caught, dropped, or out of sight): what was last drawn settles out.
        for (Iterator<Map.Entry<Integer, Cone>> it = LAST.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Cone> last = it.next();
            if (live.contains(last.getKey())) continue;
            FADING.put(last.getKey(), new Fading(last.getValue(), time));
            it.remove();
        }
        for (Iterator<Fading> it = FADING.values().iterator(); it.hasNext(); ) {
            Fading f = it.next();
            float left = 1 - (float) ((time - f.since()) / SETTLE);
            if (left <= 0) {it.remove(); continue;}
            cones.add(f.cone().fade(left));
        }
        HANDS.values().removeIf(h -> frame - h.frame() > 2);
        cones.removeIf(c -> c.strength() <= .001f);
        if (cones.isEmpty()) return;
        cones.sort(Comparator.comparingDouble(c -> c.apex().distanceToSqr(eye)));
        try {
            draw(mc, e, cones.subList(0, Math.min(MOST, cones.size())), eye, time);
        } catch (Exception broken) {
            failed = true;
            LOGGER.warn("Gravity Grasp's lens unavailable", broken);
            mc.getMainRenderTarget().bindWrite(false);
        }
    }

    /** A grasp's cone this frame: on the palm, toward what it points at or the body it hauls. */
    private static Cone cone(Minecraft mc, Player p, CompoundTag d, long start, float partial, double time) {
        Vec3 eye = p.getEyePosition(partial), look = p.getViewVector(partial);
        Hand hand = HANDS.get(p.getId());
        Vec3 palm = hand != null && frame - hand.frame() <= 1 ? hand.palm() : palm(mc, p, eye, look, partial);
        long hauled = d.getLong(GravityGrasp.HAULING);
        Entity body = hauled > 0 ? mc.level.getEntity(d.getInt(GravityGrasp.TARGET)) : null;
        Vec3 source;
        float radius, strength, haul;
        if (body != null) {
            source = body.getPosition(partial).add(0, body.getBbHeight() * .5, 0);
            double since = time - hauled, power = GravityGrasp.gathered(hauled - start);
            strength = (float) ((.75 + .25 * power) * Mth.clamp(.5 + since / 4, 0, 1));
            radius = (float) Math.max(.9, body.getBbWidth() * .8 + .5);
            haul = 1;
        } else {
            source = mc.level.clip(new ClipContext(eye, eye.add(look.scale(POINTING)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getLocation();
            double pointed = time - start, gathered = GravityGrasp.gathered((long) pointed);
            // Faint the moment the hand is out, and gathering from there: the longer it is held, the harder and wider.
            strength = (float) (Mth.clamp(pointed / 4, 0, 1) * (.5 + .5 * gathered));
            radius = (float) (.7 + .9 * gathered);
            haul = 0;
        }
        Vec3 axis = source.subtract(palm);
        double length = axis.length();
        if (length < 1.2) {axis = look; length = 1.2;}
        else axis = axis.scale(1 / length);
        return new Cone(palm, axis, length, radius, strength, haul);
    }

    /** Where the left palm is, for a hand no model drew this frame: out in front, low and to the left of the eye. */
    private static Vec3 palm(Minecraft mc, Player p, Vec3 eye, Vec3 look, float partial) {
        float yaw = p.getViewYRot(partial) * Mth.DEG_TO_RAD;
        Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw)), up = right.cross(look).normalize();
        if (up.y < 0) up = up.scale(-1);
        return eye.add(look.scale(.75)).add(right.scale(-.32)).add(up.scale(-.3));
    }

    private static void draw(Minecraft mc, RenderLevelStageEvent e, List<Cone> cones, Vec3 eye, double time) {
        // The frame as it stands, copied: what every cone bends.
        RenderTarget main = mc.getMainRenderTarget();
        if (scene == null) scene = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        else if (scene.width != main.width || scene.height != main.height) scene.resize(main.width, main.height, Minecraft.ON_OSX);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);

        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(e.getPoseStack().last().pose());
        Matrix4f projection = e.getProjectionMatrix();
        if (shell == null) shell = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        RenderSystem.setShaderTexture(0, scene.getColorTextureId());
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        try {
            for (Cone c : cones) {
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                shell(b, c, eye);
                Vec3 at = c.apex().subtract(eye);
                Vector3f apex = view.transformPosition(new Vector3f((float) at.x, (float) at.y, (float) at.z));
                Vector3f axis = view.transformDirection(new Vector3f((float) c.axis().x, (float) c.axis().y, (float) c.axis().z)).normalize();
                shader.safeGetUniform("Apex").set(apex.x, apex.y, apex.z);
                shader.safeGetUniform("Axis").set(axis.x, axis.y, axis.z);
                shader.safeGetUniform("Length").set((float) c.length());
                shader.safeGetUniform("Radii").set(PALM_RADIUS, c.radius());
                shader.safeGetUniform("Strength").set(Mth.clamp(c.strength(), 0, 1));
                shader.safeGetUniform("Haul").set(c.haul());
                shader.safeGetUniform("Time").set((float) (time / 20 % 3600));
                shell.bind();
                shell.upload(b.end());
                shell.drawWithShader(view, projection, shader);
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        }
    }

    /** A closed shell just outside the cone, palm to far end: only there to cover every pixel the cone can bend. */
    private static void shell(BufferBuilder b, Cone c, Vec3 eye) {
        Vec3 axis = c.axis(), helper = Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = axis.cross(helper).normalize(), v = u.cross(axis);
        Vec3 base = c.apex().subtract(eye);
        double from = -PALM_RADIUS, span = c.length() - from;
        Vec3[][] rings = new Vec3[RINGS + 1][SIDES];
        for (int k = 0; k <= RINGS; k++) {
            double along = from + span * k / RINGS, t = Math.max(0, along) / c.length();
            double r = (along <= 0 ? PALM_RADIUS : PALM_RADIUS + (c.radius() - PALM_RADIUS) * Math.pow(t, .85)) * 1.08 + .02;
            Vec3 middle = base.add(axis.scale(along));
            for (int j = 0; j < SIDES; j++) {
                double a = j * Math.PI * 2 / SIDES;
                rings[k][j] = middle.add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r));
            }
        }
        for (int k = 0; k < RINGS; k++)
            for (int j = 0; j < SIDES; j++)
                quad(b, rings[k][j], rings[k][(j + 1) % SIDES], rings[k + 1][(j + 1) % SIDES], rings[k + 1][j]);
        Vec3 near = base.add(axis.scale(from)), far = base.add(axis.scale(c.length()));
        for (int j = 0; j < SIDES; j++) {
            quad(b, near, near, rings[0][j], rings[0][(j + 1) % SIDES]);
            quad(b, far, far, rings[RINGS][(j + 1) % SIDES], rings[RINGS][j]);
        }
    }

    private static void quad(BufferBuilder b, Vec3 a, Vec3 c, Vec3 d, Vec3 e) {
        b.vertex(a.x, a.y, a.z).endVertex();
        b.vertex(c.x, c.y, c.z).endVertex();
        b.vertex(d.x, d.y, d.z).endVertex();
        b.vertex(e.x, e.y, e.z).endVertex();
    }
}
