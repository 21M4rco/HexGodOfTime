package com.hexgodofstories.mixin;
import com.hexgodofstories.client.HexSkin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AbstractClientPlayer.class)
public abstract class SkinMixin {
    @Inject(method="getSkinTextureLocation",at=@At("RETURN"),cancellable=true)
    private void hgos$skin(CallbackInfoReturnable<ResourceLocation> ci) {ci.setReturnValue(HexSkin.resolve((AbstractClientPlayer)(Object)this,ci.getReturnValue()));}
}
