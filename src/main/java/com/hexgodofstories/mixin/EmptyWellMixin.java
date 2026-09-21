package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.Destination;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** This realm cannot grow terrain, fluids, falling-block landings, or player-built platforms. */
@Mixin(Level.class)
public abstract class EmptyWellMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void hgos$emptySpace(BlockPos pos,BlockState state,int flags,int recursion,CallbackInfoReturnable<Boolean> ci){
        if(Destination.from((Level)(Object)this)==Destination.GRAVITY_WELL&&!state.isAir())ci.setReturnValue(false);
    }
}
