package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;

import java.util.HashMap;
import java.util.Map;

/**
 * The Scepter's passes. All of them are triangle lists, so the solid model is sent without the
 * degenerate fourth vertex a quad pass would need.
 *
 * <p>The metal is drawn with the unlit entity shader: the block and sky light still darken it, but
 * the shading itself — studio key and fill, sky reflection, occlusion — comes from ScepterModel's
 * per-vertex colour. Vanilla's two fixed entity lights cannot make a surface look like polished
 * metal, and would otherwise double up with it.
 */
final class ScepterRenderTypes extends RenderType {
    private ScepterRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                               boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sorted, setup, clear);
    }

    private static final Map<ResourceLocation, RenderType> METAL = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GLASS = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GLOW = new HashMap<>();

    /** Opaque, lit by the lightmap only. */
    static RenderType metal(ResourceLocation texture) {
        return METAL.computeIfAbsent(texture, t -> create("hexgodofstories_scepter_metal",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 16, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(ForgeHooksClient.ClientEvents::getEntityTranslucentUnlitShader))
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(true)));
    }

    /** Additive highlights over the metal; lightmapped, so a dark cave keeps them dark. */
    static final RenderType SHEEN = create("hexgodofstories_scepter_sheen",
        DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 15, false, false,
        CompositeState.builder()
            .setShaderState(new ShaderStateShard(ForgeHooksClient.ClientEvents::getEntityTranslucentUnlitShader))
            .setTextureState(new TextureStateShard(WorldEffects.WHITE, false, false))
            .setTransparencyState(ADDITIVE_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(false));

    /**
     * The stone's shell: translucent, and bright at any hour because it is its own light. Never sorted
     * on upload (that path assumes quads); ScepterModel only sends the shell's front faces, which on a
     * convex stone never overlap one another.
     */
    static RenderType glass(ResourceLocation texture) {
        return GLASS.computeIfAbsent(texture, t -> create("hexgodofstories_scepter_glass",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 13, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(ForgeHooksClient.ClientEvents::getEntityTranslucentUnlitShader))
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false)));
    }

    /** Pure added light: the stone's core, its halo and the charge. */
    static RenderType glow(ResourceLocation texture) {
        return GLOW.computeIfAbsent(texture, t -> create("hexgodofstories_scepter_glow",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 13, false, false,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_EYES_SHADER)
                .setTextureState(new TextureStateShard(t, false, false))
                .setTransparencyState(ADDITIVE_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false)));
    }
}
