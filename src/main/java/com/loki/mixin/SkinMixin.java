package com.loki.mixin;
import com.loki.client.LokiSkin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AbstractClientPlayer.class)
public abstract class SkinMixin {
    @Inject(method="getSkinTextureLocation",at=@At("RETURN"),cancellable=true)
    private void loki$skin(CallbackInfoReturnable<ResourceLocation> ci) {ci.setReturnValue(LokiSkin.resolve((AbstractClientPlayer)(Object)this,ci.getReturnValue()));}
}
