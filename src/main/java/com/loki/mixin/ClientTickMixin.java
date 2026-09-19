package com.loki.mixin;
import com.loki.client.ClientState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientLevel.class)
public abstract class ClientTickMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
    private void loki$suspend(Entity entity,CallbackInfo ci) {if(ClientState.frozen(entity.getId())||(ClientState.SLOWED.contains(entity.getId())&&Math.floorMod(entity.level().getGameTime()+entity.getId(),5)!=0))ci.cancel();}
}
