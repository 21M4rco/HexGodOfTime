package com.hexgodofstories.mixin;

import com.hexgodofstories.client.TimeStopScreen;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the copied main depth texture to Roundabout's bubble post pass. */
@Mixin(PostChain.class)
public abstract class TimeStopDepthTargetMixin {
    @Inject(method="getRenderTarget",at=@At("HEAD"),cancellable=true)
    private void hgos$depth(String name,CallbackInfoReturnable<RenderTarget> ci) {
        if(name.equals("hgos_timestop_depth")&&TimeStopScreen.depth()!=null)
            ci.setReturnValue(TimeStopScreen.depth());
    }
}
