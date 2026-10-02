package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * How fire is drawn (the burning Deceiver, its stream): colour laid over what is behind it, by the sheet's alpha, for
 * the red and orange of it and its smoke, which must stay those colours against a bright sky; and light added to what
 * is behind it, for what is white-hot. Both full-bright, hidden by what is in front of them, hiding nothing themselves.
 */
final class FireTypes extends RenderType {
    private FireTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sorted, setup, clear);
    }

    private static final Map<ResourceLocation, RenderType> VEIL = new HashMap<>(), LIGHT = new HashMap<>();

    /** Laid over what is behind it, by the sheet's alpha. */
    static RenderType veil(ResourceLocation texture) {
        return VEIL.computeIfAbsent(texture, t -> create("hexgodofstories_fire_veil", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 1 << 16, false, false,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_EYES_SHADER)
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false)));
    }

    /** Added to what is behind it, weighted by the sheet's alpha. */
    static RenderType light(ResourceLocation texture) {
        return LIGHT.computeIfAbsent(texture, t -> create("hexgodofstories_fire_light", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 1 << 16, false, false,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_EYES_SHADER)
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(LIGHTNING_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false)));
    }
}
