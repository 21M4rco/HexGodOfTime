package com.hexgodofstories.mixin;

import com.hexgodofstories.client.FrostClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;

/** GeckoLib draws many modded mobs without LivingEntityRenderer; keep their own GeoModel too. */
@Mixin(GeoRenderer.class)
public interface FrostGeoRenderMixin {
    ResourceLocation HGOS_ICE=new ResourceLocation("minecraft","textures/block/ice.png");

    @Inject(method="getTextureLocation",at=@At("HEAD"),cancellable=true,remap=false)
    private void hgos$geoIce(GeoAnimatable animatable,CallbackInfoReturnable<ResourceLocation> cir) {
        if(animatable instanceof Entity entity&&FrostClient.frozen(entity.getId()))cir.setReturnValue(HGOS_ICE);
    }
}
