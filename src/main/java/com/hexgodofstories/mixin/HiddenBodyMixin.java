package com.hexgodofstories.mixin;

import com.hexgodofstories.client.ErasureRenderer;
import com.hexgodofstories.client.Halving;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A body that is no longer there to see, as far as anything else on this client can tell: one drawn as its two halves
 * instead (Halving), and one an erasure has finished taking apart (ErasureRenderer). ErasureRenderMixin leaves both
 * undrawn, but what is drawn around a body asks it whether it is invisible: its shadow, and anything another mod hangs
 * off it, like HexKagunes' tendrils. Nor is it still on fire where it stood. The halves themselves are drawn through
 * the body's own renderer, so the body is seen as it is while they are.
 */
@Mixin(Entity.class)
public abstract class HiddenBodyMixin {
    @Inject(method="isInvisible",at=@At("HEAD"),cancellable=true)
    private void hgos$goneUnseen(CallbackInfoReturnable<Boolean> cir) {
        if(hgos$gone())cir.setReturnValue(true);
    }

    @Inject(method="displayFireAnimation",at=@At("HEAD"),cancellable=true)
    private void hgos$goneUnburnt(CallbackInfoReturnable<Boolean> cir) {
        if(hgos$gone())cir.setReturnValue(false);
    }

    @Unique
    private boolean hgos$gone() {
        Entity self=(Entity)(Object)this;
        if(self.level()==null||!self.level().isClientSide)return false;
        return Halving.hidden(self)&&!Halving.drawing()||ErasureRenderer.erasing(self)&&ErasureRenderer.consumed(self);
    }
}
