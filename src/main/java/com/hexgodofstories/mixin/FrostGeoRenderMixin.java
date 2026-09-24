package com.hexgodofstories.mixin;

import com.hexgodofstories.client.FrostClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** GeckoLib draws many modded mobs without LivingEntityRenderer; keep their own GeoModel too. */
@Mixin(GeoEntityRenderer.class)
public abstract class FrostGeoRenderMixin {
    private static final ResourceLocation HGOS_ICE=new ResourceLocation("minecraft","textures/block/ice.png");

    // GeckoLib overrides EntityRenderer#getTextureLocation. Its jar uses the named method in
    // development and Minecraft's stable 1.20.1 SRG name after Forge reobfuscation.
    @Inject(method={"getTextureLocation","m_5478_"},at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void hgos$geoIce(Entity entity,CallbackInfoReturnable<ResourceLocation> cir) {
        if(FrostClient.frozen(entity.getId()))cir.setReturnValue(HGOS_ICE);
    }
}
