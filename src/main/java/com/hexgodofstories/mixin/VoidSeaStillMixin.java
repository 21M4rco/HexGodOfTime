package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.Destination;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Void Sea's water never flows. Its swell is built out of water standing up above the waterline (see
 * VoidSeaSwell), and water anywhere else would pour off every slope of it and flatten the sea in an afternoon. A
 * scheduled water tick in that realm is simply dropped; lava, and water in every other dimension, flow as ever.
 */
@Mixin(ServerLevel.class)
public abstract class VoidSeaStillMixin {
    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void hgos$stillSea(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (fluid.isSame(Fluids.WATER) && Destination.from((ServerLevel) (Object) this) == Destination.VOID_SEA) ci.cancel();
    }
}
