package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The Crown of Barrels' models, each put on the GPU once and drawn from there however many times a frame: TACZ's
 * machine guns, rocket and casings, as tools/import_arsenal_assets.py baked them. A model is held with its middle at
 * the origin, in its own units (+x its right, +y its top, -z its muzzle end), and the points that matter measured
 * from that middle.
 */
final class ArsenalMeshes {
    private ArsenalMeshes() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The three makes, in {@link com.hexgodofstories.data.ArsenalLayout#type} order, each with the casing it throws. */
    static final String[] GUNS = {"m249", "rpk", "fn_evolys"}, SHELLS = {"shell_556x45", "shell_762x39", "shell_308"};
    static final String ROCKET = "rpg_rocket";

    /** One model on the GPU: its buffer, its texture, and its muzzle, ejection port and bounds, from its middle. */
    record Mesh(VertexBuffer buffer, ResourceLocation texture, float[] muzzle, float[] shell, float[] low, float[] high) {
        /** Where its back and its muzzle lie along -z: the span its forming edge runs over. */
        float back() {return -high[2];}
        float front() {return -low[2];}
    }

    private static final Map<String, Mesh> LOADED = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    /**
     * Every model the crown draws, and their textures and {@code extra} ones, loaded now: when a hold begins, not on
     * the frame the first gun forms. Render thread only.
     */
    static void preload(ResourceLocation... extra) {
        for (String name : GUNS) get(name);
        for (String name : SHELLS) get(name);
        get(ROCKET);
        var textures = Minecraft.getInstance().getTextureManager();
        for (Mesh mesh : LOADED.values()) textures.getTexture(mesh.texture());
        for (ResourceLocation texture : extra) textures.getTexture(texture);
    }

    /** The model, loaded on first use; null if it cannot be, which is logged once. Render thread only. */
    static Mesh get(String name) {
        Mesh mesh = LOADED.get(name);
        if (mesh != null || FAILED.contains(name)) return mesh;
        try {
            mesh = load(name);
            LOADED.put(name, mesh);
        } catch (Exception e) {
            FAILED.add(name);
            LOGGER.error("Crown of Barrels model {} could not be loaded", name, e);
        }
        return mesh;
    }

    private static Mesh load(String name) throws IOException {
        byte[] bytes;
        try (InputStream in = new GZIPInputStream(Minecraft.getInstance().getResourceManager()
            .getResourceOrThrow(HexGodOfStories.id("arsenal/" + name + ".mesh")).open())) {
            bytes = in.readAllBytes();
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x4d415848 || b.getInt() != 1) throw new IOException("not a version 1 mesh");
        int quads = b.getInt();
        float[] head = new float[12];
        for (int i = 0; i < 12; i++) head[i] = b.getFloat();
        float[] middle = new float[3];
        for (int i = 0; i < 3; i++) middle[i] = (head[6 + i] + head[9 + i]) / 2;
        BufferBuilder builder = new BufferBuilder(quads * 4 * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (int i = 0; i < quads * 4; i++) {
            float x = b.getFloat() - middle[0], y = b.getFloat() - middle[1], z = b.getFloat() - middle[2], u = b.getFloat(), v = b.getFloat();
            byte nx = b.get(), ny = b.get(), nz = b.get(), flags = b.get();
            builder.vertex(x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2((flags & 1) != 0 ? LightTexture.FULL_BRIGHT : 0).normal(nx / 127f, ny / 127f, nz / 127f).endVertex();
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return new Mesh(buffer, HexGodOfStories.id("textures/arsenal/" + name + ".png"), from(head, 0, middle), from(head, 3, middle),
            from(head, 6, middle), from(head, 9, middle));
    }

    private static float[] from(float[] head, int at, float[] middle) {
        return new float[]{head[at] - middle[0], head[at + 1] - middle[1], head[at + 2] - middle[2]};
    }
}
