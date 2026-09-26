package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

import java.util.Arrays;

/**
 * A body's drawing passed straight through, with note taken of where its surfaces are.
 *
 * <p>A Scepter wound is normally pinned to the vanilla model part the beam went through. Bodies built
 * some other way (GeckoLib, Citadel, a mod's own renderer) have no such parts to pin to, but every one of
 * them still hands its posed faces to the buffers, in view space, one primitive at a time. This records
 * the faces of the body's first batch (its own model; layers come later, in other batches) so
 * {@link BeamWounds} can find where the beam's line enters and leaves the actual surface.
 *
 * <p>The opening has to be cut before that batch reaches the GPU, and a batch is sent the moment the
 * renderer asks for a different one, so the cut runs then, or when the body is done, whichever is first.
 */
final class WoundSurface implements MultiBufferSource {
    private static final int MAX_FACES = 8192;
    private final MultiBufferSource source;
    private final Entity host;
    private final float partial;
    private final int light;
    private RenderType body;
    private boolean cut;
    /** Faces as polygons of three or four view-space points, packed twelve floats apiece. */
    private float[] faces = new float[12 * 256];
    private byte[] corners = new byte[256];
    private int count;

    WoundSurface(MultiBufferSource source, Entity host, float partial, int light) {
        this.source = source;
        this.host = host;
        this.partial = partial;
        this.light = light;
    }

    @Override public VertexConsumer getBuffer(RenderType type) {
        if (body == null) body = type;
        if (type != body) finish();
        VertexConsumer out = source.getBuffer(type);
        if (cut || type != body) return out;
        int per = type.mode() == VertexFormat.Mode.QUADS ? 4 : type.mode() == VertexFormat.Mode.TRIANGLES ? 3 : 0;
        return per == 0 ? out : new Recorder(out, per);
    }

    /** Cuts the openings now, ahead of the body's batch; then the rims go over it. Runs once. */
    void finish() {
        if (cut) return;
        cut = true;
        BeamWounds.cutSurface(host, partial, this, source, light);
    }

    int faces() {return count;}
    /** The recorded faces, twelve floats apiece; see {@link com.hexgodofstories.data.WoundGeometry}. */
    float[] packed() {return faces;}
    byte[] cornerCounts() {return corners;}

    private void add(float[] points, int n) {
        if (count >= MAX_FACES) return;
        if (count == corners.length) {
            faces = Arrays.copyOf(faces, faces.length * 2);
            corners = Arrays.copyOf(corners, corners.length * 2);
        }
        System.arraycopy(points, 0, faces, count * 12, n * 3);
        corners[count++] = (byte) n;
    }

    private final class Recorder implements VertexConsumer {
        private final VertexConsumer out;
        private final int per;
        private final float[] points = new float[12];
        private int n;
        private float x, y, z;

        Recorder(VertexConsumer out, int per) {this.out = out; this.per = per;}

        private void note() {
            if (cut) return;
            points[n * 3] = x; points[n * 3 + 1] = y; points[n * 3 + 2] = z;
            if (++n == per) {add(points, per); n = 0;}
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x; this.y = (float) y; this.z = (float) z;
            out.vertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {out.color(r, g, b, a); return this;}
        @Override public VertexConsumer uv(float u, float v) {out.uv(u, v); return this;}
        @Override public VertexConsumer overlayCoords(int u, int v) {out.overlayCoords(u, v); return this;}
        @Override public VertexConsumer uv2(int u, int v) {out.uv2(u, v); return this;}
        @Override public VertexConsumer normal(float x, float y, float z) {out.normal(x, y, z); return this;}
        @Override public void endVertex() {out.endVertex(); note();}
        @Override public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v,
                                     int overlay, int light, float nx, float ny, float nz) {
            this.x = x; this.y = y; this.z = z;
            out.vertex(x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz);
            note();
        }
        @Override public void defaultColor(int r, int g, int b, int a) {out.defaultColor(r, g, b, a);}
        @Override public void unsetDefaultColor() {out.unsetDefaultColor();}
    }
}
