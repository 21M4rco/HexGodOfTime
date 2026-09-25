package com.hexgodofstories.mixin;

import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Roundabout suspends the random chunk updates during a local time stop. */
@Mixin(ServerLevel.class)
public abstract class TemporalChunkTickMixin {
    @Inject(method="tickChunk",at=@At("HEAD"),cancellable=true)
    private void hgos$stopChunk(LevelChunk chunk,int randomTickSpeed,CallbackInfo ci) {
        if(TemporalEngine.stoppedChunk((ServerLevel)(Object)this,chunk.getPos()))ci.cancel();
    }
}
