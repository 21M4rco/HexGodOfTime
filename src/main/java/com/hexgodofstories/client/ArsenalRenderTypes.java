package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The Crown of Barrels' light: muzzle flashes, tracers, a missile's flame and a blast's rings. Added to what is
 * behind it and weighted by the texture's own alpha, since none of the textures it uses is premultiplied (TACZ's
 * flash, the Scepter's flare); hidden only by what is in front of it, and never hiding anything itself. Triangle
 * lists, like the Scepter's passes.
 */
final class ArsenalRenderTypes extends RenderType {
    private ArsenalRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                               boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sorted, setup, clear);
    }

    private static final Map<ResourceLocation, RenderType> LIGHT = new HashMap<>();

    static RenderType light(ResourceLocation texture) {
        return LIGHT.computeIfAbsent(texture, t -> create("hexgodofstories_arsenal_light",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 16, false, false,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_EYES_SHADER)
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(LIGHTNING_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false)));
    }
}
