package com.hexgodofstories.mixin;

import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Local block entities keep their current progress until the nebula passes them. */
@Mixin(Level.class)
public abstract class TemporalBlockEntityMixin {
    @Redirect(method="tickBlockEntities",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"),require=0)
    private void hgos$hold(TickingBlockEntity ticker) {
        if((Object)this instanceof ServerLevel server&&TemporalEngine.stopped(server,ticker.getPos()))return;
        ticker.tick();
    }
}
