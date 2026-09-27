package com.hexgodofstories.client;

import com.hexgodofstories.data.WoundCarve;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A Scepter hole cut out of whatever is being drawn, as it is drawn: every quad handed to a buffer is
 * taken four corners at a time, clipped against the holes' cylinders — given in the space the corners
 * arrive in, view space for anything drawn in the world — and only what lies outside them goes on, in
 * pieces. Nothing about what is drawn needs to be known: a block's model, a chest, an item in a frame and
 * a suit of armour are all quads by the time they reach a buffer.
 */
final class QuadCarver {
    private QuadCarver() { }

    /** What a corner carries here: x, y, z, red, green, blue, alpha, u, v, block light, sky light. */
    static final int STRIDE = 11;

    /**
     * {@code buffers}, with every quad drawn through it carved by the cylinders {@code cuts} gives at that
     * moment, while {@code active} says so; anything drawn as triangles or lines passes untouched.
     */
    static MultiBufferSource carve(MultiBufferSource buffers, Supplier<List<WoundCarve.Cylinder>> cuts, BooleanSupplier active) {
        return type -> {
            VertexConsumer out = buffers.getBuffer(type);
            return type.mode() == VertexFormat.Mode.QUADS ? new Carved(out, cuts, active) : out;
        };
    }

    /** What every cut leaves of each polygon (points of {@code stride} floats). */
    static List<float[]> minus(List<float[]> polygons, int stride, WoundCarve.Cylinder cut) {
        List<float[]> out = new ArrayList<>();
        for (float[] polygon : polygons) out.addAll(WoundCarve.outside(polygon, stride, cut));
        return out;
    }

    /** A polygon of {@link #STRIDE}-float corners, as the quads a quad buffer takes: a fan, the odd triangle a quad with a corner twice. */
    static void quads(VertexConsumer out, float[] p, int overlay, float nx, float ny, float nz) {
        int n = p.length / STRIDE;
        for (int i = 1; i + 1 < n; i += 2)
            for (int corner : new int[]{0, i, i + 1, Math.min(i + 2, n - 1)}) {
                int o = corner * STRIDE;
                out.vertex(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6], p[o + 7], p[o + 8], overlay,
                    (Math.round(p[o + 9]) & 0xFFFF) | Math.round(p[o + 10]) << 16, nx, ny, nz);
            }
    }

    /** Takes quads as they are handed over, four corners at a time, and passes each on as {@link #finish} decides. */
    abstract static class Quads implements VertexConsumer {
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

    /** Quads less the holes: what the cutting cylinders leave of each, in pieces; whole while carving is off. */
    static final class Carved extends Quads {
        private final Supplier<List<WoundCarve.Cylinder>> cuts;
        private final BooleanSupplier active;

        Carved(VertexConsumer out, List<WoundCarve.Cylinder> cuts) {this(out, () -> cuts, () -> true);}

        Carved(VertexConsumer out, Supplier<List<WoundCarve.Cylinder>> cuts, BooleanSupplier active) {
            super(out);
            this.cuts = cuts;
            this.active = active;
        }

        @Override void finish(float[] quad) {
            List<float[]> pieces = List.of(quad);
            if (active.getAsBoolean()) for (WoundCarve.Cylinder cut : cuts.get()) pieces = minus(pieces, STRIDE, cut);
            for (float[] piece : pieces) quads(out, piece, overlay, nx, ny, nz);
        }
    }
}
