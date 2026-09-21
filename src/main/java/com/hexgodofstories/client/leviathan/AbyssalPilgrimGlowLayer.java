package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * Emissive pass for the bioluminescence.
 *
 * <p>Brightness is not modulated here on purpose. The emissive mask only covers the glow organ
 * bones, and {@link AbyssalPilgrimModel} scales and hides those bones from the creature's current
 * behaviour, so "dim while stalking" and "extinguished while ambushing" are real geometry changes
 * rather than a colour multiply. That keeps the model visible at all times instead of being buried
 * under an effect.
 */
public class AbyssalPilgrimGlowLayer extends AutoGlowingGeoLayer<AbyssalPilgrimEntity> {
    public AbyssalPilgrimGlowLayer(GeoRenderer<AbyssalPilgrimEntity> renderer) {
        super(renderer);
    }
}
