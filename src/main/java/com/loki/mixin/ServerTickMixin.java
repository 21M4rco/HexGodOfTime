package com.loki.mixin;
import com.loki.server.TemporalEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class ServerTickMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
    private void loki$suspend(Entity entity,CallbackInfo ci) {if(TemporalEngine.skipTick(entity))ci.cancel();}
}
