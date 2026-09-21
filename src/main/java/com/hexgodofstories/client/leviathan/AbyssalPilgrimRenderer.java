package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for a creature that is longer than the chunk it is standing in.
 *
 * <p>Frustum culling is refused outright: the entity's own position can easily be behind the
 * camera while eighty blocks of body are directly in front of it, and a culled leviathan popping
 * in and out is worse than the cost of drawing it.
 */
public class AbyssalPilgrimRenderer extends GeoEntityRenderer<AbyssalPilgrimEntity> {
    public AbyssalPilgrimRenderer(EntityRendererProvider.Context context) {
        super(context, new AbyssalPilgrimModel());
        this.shadowRadius = 0f;
        addRenderLayer(new AbyssalPilgrimGlowLayer(this));
    }

    @Override
    public boolean shouldRender(AbyssalPilgrimEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public boolean shouldShowName(AbyssalPilgrimEntity entity) {
        return false;
    }
}
