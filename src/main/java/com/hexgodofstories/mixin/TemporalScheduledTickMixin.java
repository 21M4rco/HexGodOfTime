package com.hexgodofstories.mixin;

import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Defer scheduled block and fluid changes until their position leaves stopped time. */
@Mixin(ServerLevel.class)
public abstract class TemporalScheduledTickMixin {
    @Inject(method="tickBlock",at=@At("HEAD"),cancellable=true)
    private void hgos$block(BlockPos pos,Block block,CallbackInfo ci) {
        ServerLevel level=(ServerLevel)(Object)this;
        if(TemporalEngine.stopped(level,pos)){level.scheduleTick(pos,block,1);ci.cancel();}
    }
    @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true)
    private void hgos$fluid(BlockPos pos,Fluid fluid,CallbackInfo ci) {
        ServerLevel level=(ServerLevel)(Object)this;
        if(TemporalEngine.stopped(level,pos)){level.scheduleTick(pos,fluid,1);ci.cancel();}
    }
}
