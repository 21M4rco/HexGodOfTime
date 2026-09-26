package com.hexgodofstories.client;

import com.hexgodofstories.warping.Destination;
import com.hexgodofstories.warping.VoidSea;
import com.hexgodofstories.warping.VoidSeaWaves;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the Void Sea's swell.
 *
 * <p>A heightfield around the camera, rebuilt every frame from {@link VoidSeaWaves} and laid over
 * the water the realm already has. No block is moved and no chunk is remeshed: the ocean is a
 * formula and this is a picture of it, sampled at a few thousand points and interpolated between
 * them. The formula takes a fractional tick, so the motion is as smooth as the frame rate however
 * rarely anything on the server runs.
 *
 * <p><b>Why this cannot uncover the creature.</b> Three reasons, each sufficient alone.
 *
 * <p>The surface only ever rises. Every term of the wave field is non-negative by construction, so
 * this mesh sits at or above the still waterline everywhere, always. A trough here is the absence
 * of a crest and never a dip below the water that is already there, so nothing in this system can
 * thin the column between the sky and what is under it. Waves put water in front of the hunter;
 * they can never take any away.
 *
 * <p>It draws after the entities do. The stage it runs in comes after everything alive is already
 * on the screen, so this surface blends over the creature and never under it. All a crest passing
 * overhead can do to a submerged body is hide more of it.
 *
 * <p>And it has no say in the matter regardless. Concealment is decided in {@code
 * LeviathanWaterVeil}, from the still waterline and the camera — neither of which this touches.
 * The wave height is not an input to it and this class is not on its path, so a swell towering
 * over a swimmer and a dead calm hand that code exactly the same numbers.
 */
public final class VoidSeaWaveRenderer {
    private VoidSeaWaveRenderer() { }

    /** Blocks between grid samples. Close enough that a crest reads as curved, not as a staircase. */
    private static final int CELL = 3;
    /** Where the vanilla fluid surface of a full water block actually sits inside its block. */
    private static final double WATER_TOP = 0.8888889;
    /** Clear of that surface even at dead calm, so the two never argue over the same depth. */
    private static final double LIFT = 0.06;
    /** How present the sheet is over water that is doing nothing, and over a face that is. */
    private static final float ALPHA_FLAT = 0.07f, ALPHA_LIT = 0.55f;
    /** The colour a face of water throws back. Cool, because the thing overhead is a galaxy. */
    private static final float GLINT_R = 0.45f, GLINT_G = 0.72f, GLINT_B = 0.92f;
    /** Slope at which a face is fully lit, and the height at which a crest is a crest. */
    private static final float SLOPE_GAIN = 3.5f, CREST_REF = 2.6f;
    /** Blocks over which the mesh settles back onto the flat water at its outer edge. */
    private static final double SKIRT = 52.0;
    /** The camera is inside the water it is drawing, so the first few blocks of it are faded out. */
    private static final double NEAR_IN = 2.0, NEAR_OUT = 7.0;
    /** Beyond this much height difference the sea is not usefully on screen. */
    private static final double RANGE_Y = 420.0;

    /** Packed light with both channels at maximum; see where it is assigned. */
    private static final int FULL_BRIGHT = 15728880;

    private static float[] field = new float[0];
    private static int span = -1;

    // Resolved once a frame and read by every vertex. Rendering is single threaded.
    private static double camX, camY, camZ, originX, originZ, waterline;
    private static int points, radius, light;
    private static float tintR, tintG, tintB;

    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Destination.from(mc.level) != Destination.VOID_SEA) return;

        Vec3 camera = event.getCamera().getPosition();
        waterline = VoidSea.SURFACE + WATER_TOP;
        if (Math.abs(camera.y - waterline) > RANGE_Y) return;
        camX = camera.x; camY = camera.y; camZ = camera.z;

        radius = Mth.clamp(mc.options.getEffectiveRenderDistance() * 16, 96, 224);
        int cells = (2 * radius) / CELL;
        points = cells + 1;
        if (span != points) { span = points; field = new float[points * points]; }

        // Snapped to the cell grid, so vertices hold still in the world as the camera moves through
        // them rather than swimming along with it.
        originX = Math.floor(camX / CELL) * CELL - radius;
        originZ = Math.floor(camZ / CELL) * CELL - radius;
        double time = mc.level.getGameTime() + (double) event.getPartialTick();

        // The dozen or so waves that can reach this patch, resolved once for the whole frame rather
        // than re-derived at each of several thousand grid points.
        VoidSeaWaves.Wave[] waves = VoidSeaWaves.collect(camX, camZ, radius + CELL * 2.0, time);
        for (int i = 0; i < points; i++) {
            double x = originX + i * CELL;
            for (int j = 0; j < points; j++) {
                double z = originZ + j * CELL;
                // Tapered to nothing at the rim, so the mesh meets the flat water it sits on
                // instead of ending in a wall.
                field[i * points + j] =
                    (float) ((LIFT + VoidSeaWaves.height(x, z, 0, time, waves)) * skirt(x, z));
            }
        }

        // Deliberately not the block light here. The realm has no skylight and a flat ambient of
        // 0.18 under it, so every block of the water column is lit to the same near nothing, and a
        // surface drawn through that lightmap came out around two percent different from the water
        // behind it: present in the buffer, invisible on the screen. What a swell actually shows
        // you is light coming back off it, which is not the block's light anyway, so the shading
        // below is done in the vertex colour and the lightmap is taken out of the argument. Fog
        // still applies, so distance still swallows the far water.
        light = FULL_BRIGHT;
        int tint = mc.level.getBiome(BlockPos.containing(camX, VoidSea.SURFACE, camZ)).value().getWaterColor();
        tintR = (tint >> 16 & 0xFF) / 255f; tintG = (tint >> 8 & 0xFF) / 255f; tintB = (tint & 0xFF) / 255f;
        Vector3f look = event.getCamera().getLookVector();

        PoseStack pose = event.getPoseStack();
        // corner() already subtracts the camera in double precision. Translating the pose as
        // well applied that offset twice, putting the waves a whole camera-height below the sea
        // (and displacing them horizontally). Keep only the event's view rotation here.
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        var buffers = mc.renderBuffers().bufferSource();
        RenderType type = SurfaceRenderType.TYPE;
        VertexConsumer buffer = buffers.getBuffer(type);

        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                double dx = originX + (i + 0.5) * CELL - camX, dz = originZ + (j + 0.5) * CELL - camZ;
                double flat = dx * dx + dz * dz;
                if (flat > radius * (double) radius) continue;
                // Only cells squarely behind the viewer are dropped, well outside any field of view
                // the game offers, so nothing can pop in at the edge of the screen.
                if (flat > 24 * 24 && (dx * look.x() + dz * look.z()) / Math.sqrt(flat) < -0.5) continue;
                corner(buffer, matrix, normal, i, j);
                corner(buffer, matrix, normal, i, j + 1);
                corner(buffer, matrix, normal, i + 1, j + 1);
                corner(buffer, matrix, normal, i + 1, j);
            }
        }
        buffers.endBatch(type);
    }

    /** Own batch and an explicit particles target for Fabulous transparency compositing. */
    private static final class SurfaceRenderType extends RenderType {
        private static final RenderType TYPE = create("hexgodofstories_void_sea_waves",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 262144, false, true,
            CompositeState.builder()
                // The slope colours below supply the lighting. The unlit shader also preserves
                // low vertex alpha, so the calm water and the near/edge fades remain continuous.
                .setShaderState(new ShaderStateShard(ForgeHooksClient.ClientEvents::getEntityTranslucentUnlitShader))
                .setTextureState(new TextureStateShard(WorldEffects.WHITE, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setOutputState(PARTICLES_TARGET)
                .createCompositeState(false));

        private SurfaceRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int size, boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }
    }

    /** One at the heart of the patch, easing to nothing over the last stretch before the rim. */
    private static double skirt(double x, double z) {
        double reach = Math.sqrt(sqr(x - camX) + sqr(z - camZ));
        return Mth.clamp((radius - reach) / SKIRT, 0, 1);
    }

    private static void corner(VertexConsumer buffer, Matrix4f matrix, Matrix3f normals, int i, int j) {
        float height = field[i * points + j];
        double x = originX + i * CELL, z = originZ + j * CELL, y = waterline + height;

        // Slope from the samples either side, and it is the slope that is the whole picture. Water
        // lying flat is left very nearly alone, so a calm sea looks exactly as it always did; a
        // face tipped against the light comes up bright, so what the eye picks out is the shape of
        // the wave rather than a sheet laid over the ocean. The raised body of a swell is lifted a
        // little too, so something big reads as a mass of water and not only as two lit faces.
        float dx = (sample(i + 1, j) - sample(i - 1, j)) / (2f * CELL);
        float dz = (sample(i, j + 1) - sample(i, j - 1)) / (2f * CELL);
        float inverse = (float) (1.0 / Math.sqrt(dx * dx + dz * dz + 1.0));
        float nx = -dx * inverse, ny = inverse, nz = -dz * inverse;
        float lit = Mth.clamp((float) Math.sqrt(dx * dx + dz * dz) * SLOPE_GAIN
            + Mth.clamp(height / CREST_REF, 0f, 1f) * 0.45f, 0f, 1f);

        double distance = Math.sqrt(sqr(x - camX) + sqr(y - camY) + sqr(z - camZ));
        float near = (float) Mth.clamp((distance - NEAR_IN) / (NEAR_OUT - NEAR_IN), 0, 1);
        float alpha = (ALPHA_FLAT + (ALPHA_LIT - ALPHA_FLAT) * lit) * near * (float) skirt(x, z);

        buffer.vertex(matrix, (float) (x - camX), (float) (y - camY), (float) (z - camZ))
            .color(Mth.lerp(lit, tintR, GLINT_R), Mth.lerp(lit, tintG, GLINT_G), Mth.lerp(lit, tintB, GLINT_B), alpha)
            .uv(0.5f, 0.5f)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normals, nx, ny, nz)
            .endVertex();
    }

    private static float sample(int i, int j) {
        return field[Mth.clamp(i, 0, points - 1) * points + Mth.clamp(j, 0, points - 1)];
    }

    private static double sqr(double v) { return v * v; }
}
