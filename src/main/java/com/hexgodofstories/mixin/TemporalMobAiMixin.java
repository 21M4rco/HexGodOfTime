package com.hexgodofstories.mixin;

import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AI choices and attack goals advance at the same rate as movement inside Dilation. */
@Mixin(Mob.class)
public abstract class TemporalMobAiMixin {
    @Inject(method="serverAiStep",at=@At("HEAD"),cancellable=true)
    private void hgos$dilateAi(CallbackInfo ci) {
        Mob mob=(Mob)(Object)this;
        double rate=TemporalEngine.rateOf(mob);
        if(rate>=.999)return;
        if(Math.floorMod(mob.tickCount*37,100)>=rate*100)ci.cancel();
    }
}
