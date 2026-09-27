package com.hexgodofstories.mixin;

import com.hexgodofstories.client.BlockWounds;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands out a block state's model wrapped in a HoleModel while Scepter holes are open in blocks of that
 * state, so any chunk renderer that meshes through Forge's models leaves those blocks out. Nothing else is
 * touched: no hole open, and the lookup is exactly vanilla's. Optional, like the other hole hooks.
 */
@Mixin(BlockModelShaper.class)
public abstract class HoleModelMixin {
    @Inject(method = "getBlockModel", at = @At("RETURN"), cancellable = true, require = 0)
    private void hgos$holed(BlockState state, CallbackInfoReturnable<BakedModel> cir) {
        BakedModel holed = BlockWounds.model(state, cir.getReturnValue());
        if (holed != null) cir.setReturnValue(holed);
    }
}
