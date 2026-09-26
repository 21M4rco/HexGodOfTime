package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Scepter as a solid model, shaded on the CPU once per frame.
 *
 * <p>The mesh comes from {@code scripts/generate_scepter.py}: triangles with smooth or creased
 * normals, per-part UVs and a baked colour (material tint times ambient occlusion). Every frame each
 * vertex is lit in view space by a fixed studio rig — a key from the upper left, a fill from the right
 * and a sky/floor reflection — and whatever reflects the key hard enough is added again on top as a
 * highlight. That is what lets a Minecraft entity pass read as polished metal: vanilla entity light has
 * no specular term at all. {@code tools/preview_scepter.py} reproduces this exactly for offline review.
 *
 * <p>The stone is its own light: an additive core, a translucent cellular shell, a halo, and blue light
 * cast onto the metal that holds it, all driven by {@code power} so a charge visibly builds in it.
 */
public final class ScepterModel {
    private static final ResourceLocation STEEL = HexGodOfStories.id("textures/scepter/steel.png");
    private static final ResourceLocation GOLD = HexGodOfStories.id("textures/scepter/gold.png");
    private static final ResourceLocation SHAFT = HexGodOfStories.id("textures/scepter/shaft.png");
    private static final ResourceLocation GEM = HexGodOfStories.id("textures/scepter/gem.png");
    private static final ResourceLocation HALO = HexGodOfStories.id("textures/scepter/glow.png");

    // Studio rig in view space; tools/preview_scepter.py uses the same numbers.
    private static final float[] KEY = unit(-0.45f, 0.70f, 0.55f);
    private static final float[] FILL = unit(0.75f, 0.10f, 0.55f);
    private static final float AMBIENT = .26f, KEY_DIFFUSE = .60f, FILL_DIFFUSE = .22f, SKY_DIFFUSE = .14f;

    private enum Material {
        STEEL(ScepterModel.STEEL, .46f, .48f, .18f, .86f, 1.00f, 34, .35f, 1.00f, 1.00f, 1.05f),
        GOLD(ScepterModel.GOLD, .50f, .55f, .25f, 1.00f, .90f, 24, .30f, 1.00f, .86f, .55f),
        SHAFT(ScepterModel.SHAFT, .50f, .55f, .25f, 1.00f, .90f, 24, .30f, 1.00f, .86f, .55f),
        DARK(ScepterModel.STEEL, .60f, .35f, .30f, 1.00f, .55f, 40, .25f, .80f, .86f, .95f),
        SHELL(GEM, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1),
        CORE(WorldEffects.WHITE, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1);

        final ResourceLocation texture;
        final float diffuse, environment, floor, sky, highlight, rim, tr, tg, tb;
        final int shine;

        Material(ResourceLocation texture, float diffuse, float environment, float floor, float sky, float highlight,
                 int shine, float rim, float tr, float tg, float tb) {
            this.texture = texture;
            this.diffuse = diffuse;
            this.environment = environment;
            this.floor = floor;
            this.sky = sky;
            this.highlight = highlight;
            this.shine = shine;
            this.rim = rim;
            this.tr = tr;
            this.tg = tg;
            this.tb = tb;
        }

        boolean metal() {return this != SHELL && this != CORE;}
    }

    private static ScepterModel instance;

    public static ScepterModel get() {
        if (instance == null) instance = new ScepterModel("laevateinn");
        return instance;
    }

    public static void clear() {instance = null;}

    // Render vertices: one per unique position/uv/normal corner.
    private final float[] position, normal, uv, baked, gemLight;
    private final Material[] material;
    private final int count;
    /** Triangles, as vertex index triples, grouped by material. */
    private final int[][] triangles = new int[Material.values().length][];
    /** Metal triangles close enough to the stone to take its light. */
    private final int[] lit;
    private final float minY, maxY;
    private final float[] stone = new float[3];
    private final float stoneRadius;

    // Per-frame scratch.
    private final float[] view, viewNormal, shade, sheen;

    private ScepterModel(String asset) {
        List<float[]> v = new ArrayList<>(), vt = new ArrayList<>(), vn = new ArrayList<>();
        // One render vertex per distinct position/uv/normal corner within a material.
        Map<String, Integer> corners = new HashMap<>();
        List<int[]> refs = new ArrayList<>();
        List<Material> owner = new ArrayList<>();
        List<List<Integer>> tris = new ArrayList<>();
        for (int i = 0; i < Material.values().length; i++) tris.add(new ArrayList<>());
        Material current = Material.STEEL;
        ResourceLocation id = HexGodOfStories.id("models/" + asset + ".obj");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            Minecraft.getInstance().getResourceManager().open(id), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] s = line.trim().split("\\s+");
                switch (s[0]) {
                    case "v" -> v.add(s.length >= 7
                        ? new float[]{f(s[1]), f(s[2]), f(s[3]), f(s[4]), f(s[5]), f(s[6])}
                        : new float[]{f(s[1]), f(s[2]), f(s[3]), 1, 1, 1});
                    case "vt" -> vt.add(new float[]{f(s[1]), f(s[2])});
                    case "vn" -> vn.add(new float[]{f(s[1]), f(s[2]), f(s[3])});
                    case "g" -> current = materialOf(s.length > 1 ? s[1] : "");
                    case "f" -> {
                        if (s.length != 4) throw new IOException("Expected a triangle: " + line);
                        List<Integer> target = tris.get(current.ordinal());
                        for (int k = 1; k <= 3; k++) {
                            String key = current.ordinal() + "|" + s[k];
                            Integer index = corners.get(key);
                            if (index == null) {
                                String[] parts = s[k].split("/");
                                index = owner.size();
                                corners.put(key, index);
                                owner.add(current);
                                refs.add(new int[]{Integer.parseInt(parts[0]) - 1, Integer.parseInt(parts[1]) - 1,
                                    Integer.parseInt(parts[2]) - 1});
                            }
                            target.add(index);
                        }
                    }
                    default -> { }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load Scepter model " + id, e);
        }
        count = owner.size();
        position = new float[count * 3];
        normal = new float[count * 3];
        uv = new float[count * 2];
        baked = new float[count * 3];
        gemLight = new float[count];
        material = owner.toArray(new Material[0]);
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        double sx = 0, sy = 0, sz = 0;
        int core = 0;
        for (int i = 0; i < count; i++) {
            int[] c = refs.get(i);
            float[] p = v.get(c[0]), t = vt.get(c[1]), n = vn.get(c[2]);
            System.arraycopy(p, 0, position, i * 3, 3);
            System.arraycopy(p, 3, baked, i * 3, 3);
            System.arraycopy(t, 0, uv, i * 2, 2);
            System.arraycopy(n, 0, normal, i * 3, 3);
            low = Math.min(low, p[1]);
            high = Math.max(high, p[1]);
            if (material[i] == Material.CORE) {sx += p[0]; sy += p[1]; sz += p[2]; core++;}
        }
        minY = low;
        maxY = high;
        if (core > 0) {stone[0] = (float) (sx / core); stone[1] = (float) (sy / core); stone[2] = (float) (sz / core);}
        float reach = 0;
        for (int i = 0; i < count; i++) if (material[i] == Material.SHELL) reach = Math.max(reach, distance(i, stone));
        stoneRadius = Math.max(reach, 1e-3f);
        for (int m = 0; m < triangles.length; m++) triangles[m] = tris.get(m).stream().mapToInt(Integer::intValue).toArray();

        // Light the stone throws onto the metal around it: facing it, and falling off with distance.
        float falloff = stoneRadius * 3.2f;
        for (int i = 0; i < count; i++) {
            if (!material[i].metal()) continue;
            float dx = stone[0] - position[i * 3], dy = stone[1] - position[i * 3 + 1], dz = stone[2] - position[i * 3 + 2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1e-5f || d >= falloff) continue;
            float facing = (dx * normal[i * 3] + dy * normal[i * 3 + 1] + dz * normal[i * 3 + 2]) / d;
            float near = d / (stoneRadius * 1.1f);
            gemLight[i] = Math.max(0, .25f + .75f * facing) / (1 + near * near) * (1 - d / falloff);
        }
        List<Integer> near = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.metal()) continue;
            int[] list = triangles[m.ordinal()];
            for (int t = 0; t < list.length; t += 3)
                if (gemLight[list[t]] + gemLight[list[t + 1]] + gemLight[list[t + 2]] > .02f) {
                    near.add(list[t]); near.add(list[t + 1]); near.add(list[t + 2]);
                }
        }
        lit = near.stream().mapToInt(Integer::intValue).toArray();
        view = new float[count * 3];
        viewNormal = new float[count * 3];
        shade = new float[count * 3];
        sheen = new float[count];
    }

    private float distance(int i, float[] to) {
        float dx = to[0] - position[i * 3], dy = to[1] - position[i * 3 + 1], dz = to[2] - position[i * 3 + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float f(String s) {return Float.parseFloat(s);}

    /** Vertex colour bytes wrap rather than saturate, so everything written is clamped first. */
    private static float c(float x) {return x < 0 ? 0 : Math.min(x, 1);}

    private static float[] unit(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    private static Material materialOf(String group) {
        if (group.startsWith("steel")) return Material.STEEL;
        if (group.startsWith("gold")) return Material.GOLD;
        if (group.startsWith("shaft")) return Material.SHAFT;
        if (group.startsWith("dark")) return Material.DARK;
        if (group.startsWith("gem_core")) return Material.CORE;
        return Material.SHELL;
    }

    /** Model-space centre of the stone, for effects that have to leave from it. */
    public float[] stone() {return stone.clone();}

    public float extent() {return Math.max(Math.abs(minY), Math.abs(maxY));}

    /**
     * Draws the Scepter in model space: grip at the origin, head toward +Y.
     *
     * @param orthographic true in inventory slots, where there is no perspective to take a view ray from
     * @param reveal       0..1 manifestation: only the part within reveal*extent of the grip exists yet
     * @param power        how hard the stone burns: ~1 at rest, up to ~3 at full charge
     * @param detailed     false for small or distant copies: no highlight pass
     */
    public void render(PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay, boolean orthographic,
                       float reveal, float power, float time, boolean detailed) {
        if (reveal <= 0) return;
        if (!detailed && !orthographic) {
            // Highlights for anything close enough to read them, whoever is holding it.
            Matrix4f m = pose.pose();
            float x = m.m30(), y = m.m31(), z = m.m32();
            detailed = x * x + y * y + z * z < 144;
        }
        light(pose, orthographic);
        float boundary = reveal >= 1 ? Float.POSITIVE_INFINITY : reveal * extent();
        for (Material m : Material.values()) {
            if (!m.metal()) continue;
            emit(buffers.getBuffer(ScepterRenderTypes.metal(m.texture)), triangles[m.ordinal()], boundary, light, overlay);
        }
        if (detailed) emitSheen(buffers.getBuffer(ScepterRenderTypes.SHEEN), boundary, light, overlay);
        if (reveal > .55f) {
            float bloom = Math.min(1, (reveal - .55f) / .3f);
            emitStoneLight(buffers.getBuffer(ScepterRenderTypes.glow(WorldEffects.WHITE)), boundary, power * bloom);
            emitCore(buffers.getBuffer(ScepterRenderTypes.glow(WorldEffects.WHITE)), power * bloom, time);
            emitShell(buffers.getBuffer(ScepterRenderTypes.glass(GEM)), power * bloom, time, overlay, orthographic);
            emitHalo(pose, buffers.getBuffer(ScepterRenderTypes.glow(HALO)), power * bloom, time);
        }
    }

    /** Transforms and shades every vertex for this pose. */
    private void light(PoseStack.Pose pose, boolean orthographic) {
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
        float n00 = n.m00(), n01 = n.m01(), n02 = n.m02(), n10 = n.m10(), n11 = n.m11(), n12 = n.m12();
        float n20 = n.m20(), n21 = n.m21(), n22 = n.m22();
        for (int i = 0; i < count; i++) {
            int o = i * 3;
            float x = position[o], y = position[o + 1], z = position[o + 2];
            float vx = m00 * x + m10 * y + m20 * z + m30, vy = m01 * x + m11 * y + m21 * z + m31, vz = m02 * x + m12 * y + m22 * z + m32;
            view[o] = vx; view[o + 1] = vy; view[o + 2] = vz;
            float a = normal[o], b = normal[o + 1], c = normal[o + 2];
            float nx = n00 * a + n10 * b + n20 * c, ny = n01 * a + n11 * b + n21 * c, nz = n02 * a + n12 * b + n22 * c;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1e-6f) {nx /= nl; ny /= nl; nz /= nl;}
            viewNormal[o] = nx; viewNormal[o + 1] = ny; viewNormal[o + 2] = nz;
            Material mat = material[i];
            if (!mat.metal()) {shade[o] = baked[o]; shade[o + 1] = baked[o + 1]; shade[o + 2] = baked[o + 2]; sheen[i] = 0; continue;}
            float ex, ey, ez;
            if (orthographic) {ex = 0; ey = 0; ez = 1;}
            else {
                float el = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (el < 1e-6f) el = 1;
                ex = -vx / el; ey = -vy / el; ez = -vz / el;
            }
            float ndv = nx * ex + ny * ey + nz * ez;
            if (ndv < 0) {nx = -nx; ny = -ny; nz = -nz; ndv = -ndv;}
            float rx = 2 * ndv * nx - ex, ry = 2 * ndv * ny - ey, rz = 2 * ndv * nz - ez;
            float diffuse = AMBIENT + KEY_DIFFUSE * Math.max(0, nx * KEY[0] + ny * KEY[1] + nz * KEY[2])
                + FILL_DIFFUSE * Math.max(0, nx * FILL[0] + ny * FILL[1] + nz * FILL[2]) + SKY_DIFFUSE * (.5f + .5f * ny);
            float t = Math.min(1, Math.max(0, (ry + .25f)));
            float env = mat.floor + (mat.sky - mat.floor) * t * t * (3 - 2 * t);
            float base = mat.diffuse * diffuse + mat.environment * env;
            shade[o] = Math.min(1, baked[o] * base);
            shade[o + 1] = Math.min(1, baked[o + 1] * base);
            shade[o + 2] = Math.min(1, baked[o + 2] * base);
            float key = Math.max(0, rx * KEY[0] + ry * KEY[1] + rz * KEY[2]);
            float edge = 1 - ndv;
            edge *= edge;
            sheen[i] = mat.highlight * pow(key, mat.shine) + mat.rim * edge * edge * env;
        }
    }

    private static float pow(float x, int n) {
        float r = 1;
        while (n > 0) {
            if ((n & 1) != 0) r *= x;
            x *= x;
            n >>= 1;
        }
        return r;
    }

    private void emit(VertexConsumer out, int[] list, float boundary, int light, int overlay) {
        boolean whole = boundary == Float.POSITIVE_INFINITY;
        for (int t = 0; t < list.length; t += 3) {
            int a = list[t], b = list[t + 1], c = list[t + 2];
            if (!whole) {
                boolean ia = inside(a, boundary), ib = inside(b, boundary), ic = inside(c, boundary);
                if (!ia && !ib && !ic) continue;
                if (!ia || !ib || !ic) {clipped(out, a, b, c, boundary, light, overlay); continue;}
            }
            put(out, a, light, overlay);
            put(out, b, light, overlay);
            put(out, c, light, overlay);
        }
    }

    private boolean inside(int i, float boundary) {return Math.abs(position[i * 3 + 1]) <= boundary;}

    private void put(VertexConsumer out, int i, int light, int overlay) {
        int o = i * 3;
        out.vertex(view[o], view[o + 1], view[o + 2], shade[o], shade[o + 1], shade[o + 2], 1,
            uv[i * 2], uv[i * 2 + 1], overlay, light, viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
    }

    /** A triangle cut by the manifestation front: the part inside, as a fan. */
    private void clipped(VertexConsumer out, int a, int b, int c, float boundary, int light, int overlay) {
        float[][] poly = {vertexData(a), vertexData(b), vertexData(c)};
        poly = clip(poly, boundary, 1);
        poly = clip(poly, boundary, -1);
        for (int k = 1; k + 1 < poly.length; k++) {
            putData(out, poly[0], light, overlay);
            putData(out, poly[k], light, overlay);
            putData(out, poly[k + 1], light, overlay);
        }
    }

    // [modelY, vx, vy, vz, r, g, b, u, v, nx, ny, nz]
    private float[] vertexData(int i) {
        int o = i * 3;
        return new float[]{position[o + 1], view[o], view[o + 1], view[o + 2], shade[o], shade[o + 1], shade[o + 2],
            uv[i * 2], uv[i * 2 + 1], viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]};
    }

    private static void putData(VertexConsumer out, float[] d, int light, int overlay) {
        out.vertex(d[1], d[2], d[3], d[4], d[5], d[6], 1, d[7], d[8], overlay, light, d[9], d[10], d[11]);
    }

    private static float[][] clip(float[][] poly, float boundary, int side) {
        List<float[]> result = new ArrayList<>();
        if (poly.length == 0) return poly;
        float[] previous = poly[poly.length - 1];
        for (float[] current : poly) {
            boolean before = previous[0] * side <= boundary, after = current[0] * side <= boundary;
            if (before != after) {
                float t = (side * boundary - previous[0]) / (current[0] - previous[0]);
                float[] mix = new float[current.length];
                for (int k = 0; k < mix.length; k++) mix[k] = previous[k] + (current[k] - previous[k]) * t;
                result.add(mix);
            }
            if (after) result.add(current);
            previous = current;
        }
        return result.toArray(new float[0][]);
    }

    /** Additive key highlights and rim, only where there is any. */
    private void emitSheen(VertexConsumer out, float boundary, int light, int overlay) {
        for (Material m : Material.values()) {
            if (!m.metal()) continue;
            int[] list = triangles[m.ordinal()];
            for (int t = 0; t < list.length; t += 3) {
                int a = list[t], b = list[t + 1], c = list[t + 2];
                if (sheen[a] + sheen[b] + sheen[c] < .012f) continue;
                if (boundary != Float.POSITIVE_INFINITY && (!inside(a, boundary) || !inside(b, boundary) || !inside(c, boundary))) continue;
                glint(out, a, m, light, overlay);
                glint(out, b, m, light, overlay);
                glint(out, c, m, light, overlay);
            }
        }
    }

    private void glint(VertexConsumer out, int i, Material m, int light, int overlay) {
        int o = i * 3;
        float s = sheen[i];
        out.vertex(view[o], view[o + 1], view[o + 2], c(s * m.tr), c(s * m.tg), c(s * m.tb), 1, .5f, .5f, overlay, light,
            viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
    }

    /** The stone's blue light on the cage and blade around it. Full bright: it is emitted, not reflected. */
    private void emitStoneLight(VertexConsumer out, float boundary, float power) {
        float strength = .22f * power;
        for (int t = 0; t < lit.length; t += 3) {
            int a = lit[t], b = lit[t + 1], c = lit[t + 2];
            if (boundary != Float.POSITIVE_INFINITY && (!inside(a, boundary) || !inside(b, boundary) || !inside(c, boundary))) continue;
            stoneLit(out, a, strength);
            stoneLit(out, b, strength);
            stoneLit(out, c, strength);
        }
    }

    private void stoneLit(VertexConsumer out, int i, float strength) {
        int o = i * 3;
        float g = gemLight[i] * strength;
        out.vertex(view[o], view[o + 1], view[o + 2], c(.18f * g), c(.52f * g), c(g), 1, .5f, .5f, 0, 15728880,
            viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
    }

    private void emitCore(VertexConsumer out, float power, float time) {
        int[] list = triangles[Material.CORE.ordinal()];
        float flicker = .88f + .12f * (float) Math.sin(time * .9f) * (float) Math.sin(time * .37f + 1.3f);
        float k = Math.min(1.6f, .55f * power * flicker);
        for (int t = 0; t < list.length; t += 3) {
            for (int j = 0; j < 3; j++) {
                int i = list[t + j];
                int o = i * 3;
                out.vertex(view[o], view[o + 1], view[o + 2], c(.45f * k), c(.80f * k), c(k), 1, .5f, .5f, 0, 15728880,
                    viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
            }
        }
    }

    /** Front faces only: on a convex stone they never overlap, so translucency needs no sorting. */
    private void emitShell(VertexConsumer out, float power, float time, int overlay, boolean orthographic) {
        int[] list = triangles[Material.SHELL.ordinal()];
        float glow = Math.min(1, .72f + .14f * power);
        float drift = time * .004f;
        for (int t = 0; t < list.length; t += 3) {
            int a = list[t] * 3, b = list[t + 1] * 3, c = list[t + 2] * 3;
            float ux = view[b] - view[a], uy = view[b + 1] - view[a + 1], uz = view[b + 2] - view[a + 2];
            float wx = view[c] - view[a], wy = view[c + 1] - view[a + 1], wz = view[c + 2] - view[a + 2];
            float fx = uy * wz - uz * wy, fy = uz * wx - ux * wz, fz = ux * wy - uy * wx;
            // Outward face normal from the vertex normals' side, against the ray to the camera.
            float nx = viewNormal[a] + viewNormal[b] + viewNormal[c];
            float ny = viewNormal[a + 1] + viewNormal[b + 1] + viewNormal[c + 1];
            float nz = viewNormal[a + 2] + viewNormal[b + 2] + viewNormal[c + 2];
            if (fx * nx + fy * ny + fz * nz < 0) {fx = -fx; fy = -fy; fz = -fz;}
            float cx = view[a] + view[b] + view[c], cy = view[a + 1] + view[b + 1] + view[c + 1], cz = view[a + 2] + view[b + 2] + view[c + 2];
            if (orthographic ? fz < 0 : fx * -cx + fy * -cy + fz * -cz < 0) continue;
            for (int j = 0; j < 3; j++) {
                int i = list[t + j];
                int o = i * 3;
                out.vertex(view[o], view[o + 1], view[o + 2], .55f * glow + .1f, .78f * glow + .1f, 1.0f, .82f,
                    uv[i * 2] + drift, uv[i * 2 + 1] - drift * .6f, overlay, 15728880,
                    viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
            }
        }
    }

    /** A camera-facing bloom around the stone. */
    private void emitHalo(PoseStack.Pose pose, VertexConsumer out, float power, float time) {
        Matrix4f m = pose.pose();
        float cx = m.m00() * stone[0] + m.m10() * stone[1] + m.m20() * stone[2] + m.m30();
        float cy = m.m01() * stone[0] + m.m11() * stone[1] + m.m21() * stone[2] + m.m31();
        float cz = m.m02() * stone[0] + m.m12() * stone[1] + m.m22() * stone[2] + m.m32();
        float scale = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float breathe = 1 + .06f * (float) Math.sin(time * .21f);
        float size = stoneRadius * scale * (2.6f + .9f * power) * breathe;
        float k = Math.min(1.4f, .32f * power);
        quad(out, cx, cy, cz, size, c(.30f * k), c(.62f * k), c(k));
        // A tighter, whiter heart.
        float h = Math.min(1.2f, .22f * power);
        quad(out, cx, cy, cz, size * .42f, c(.70f * h), c(.90f * h), c(h));
    }

    private static void quad(VertexConsumer out, float x, float y, float z, float s, float r, float g, float b) {
        float[][] c = {{x - s, y - s, 0, 1}, {x + s, y - s, 1, 1}, {x + s, y + s, 1, 0}, {x - s, y + s, 0, 0}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i : order)
            out.vertex(c[i][0], c[i][1], z, r, g, b, 1, c[i][2], c[i][3], 0, 15728880, 0, 0, 1);
    }

    /**
     * The glowing seam at the manifestation front: metal within a sliver of the front burns green.
     * Drawn after {@link #render}, which leaves the transformed vertices in place.
     */
    public void renderFront(MultiBufferSource buffers, float reveal, float fade) {
        if (reveal >= 1 || reveal <= 0 || fade <= 0) return;
        VertexConsumer out = buffers.getBuffer(ScepterRenderTypes.glow(WorldEffects.WHITE));
        float front = reveal * extent();
        float band = .035f;
        for (Material m : Material.values()) {
            if (!m.metal()) continue;
            int[] list = triangles[m.ordinal()];
            for (int t = 0; t < list.length; t += 3) {
                boolean near = false;
                for (int j = 0; j < 3; j++) near |= Math.abs(Math.abs(position[list[t + j] * 3 + 1]) - front) < band;
                if (!near) continue;
                for (int j = 0; j < 3; j++) {
                    int i = list[t + j];
                    int o = i * 3;
                    float closeness = 1 - Math.min(1, Math.abs(Math.abs(position[o + 1]) - front) / band);
                    float k = closeness * fade;
                    out.vertex(view[o], view[o + 1], view[o + 2], c(.27f * k), c(k), c(.74f * k), 1, .5f, .5f, 0, 15728880,
                        viewNormal[o], viewNormal[o + 1], viewNormal[o + 2]);
                }
            }
        }
    }
}
