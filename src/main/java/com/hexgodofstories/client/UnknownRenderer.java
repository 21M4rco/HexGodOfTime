package com.hexgodofstories.client;
import com.hexgodofstories.entity.UnknownEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
public final class UnknownRenderer extends GeoEntityRenderer<UnknownEntity> {
    public UnknownRenderer(EntityRendererProvider.Context context) {
        super(context,new UnknownModel());
        // Source rig is 60-70 model pixels tall. ~2.25x gives a tree-sized 9+ block silhouette.
        this.scaleWidth=2.25f;this.scaleHeight=2.25f;this.shadowRadius=3.25f;
    }
}
