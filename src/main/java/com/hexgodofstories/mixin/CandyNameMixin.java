package com.hexgodofstories.mixin;
import com.hexgodofstories.warping.ParadiseFood;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ItemStack.class)
public abstract class CandyNameMixin {
    @Inject(method="getHoverName",at=@At("RETURN"),cancellable=true)
    private void hgos$candyName(CallbackInfoReturnable<Component> cir){
        ItemStack self=(ItemStack)(Object)this;
        if(ParadiseFood.edible(self))cir.setReturnValue(ParadiseFood.candyName(self,cir.getReturnValue()));
    }
}
