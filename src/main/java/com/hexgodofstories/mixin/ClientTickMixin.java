package com.hexgodofstories.mixin;
import com.hexgodofstories.client.ClientState;
import com.hexgodofstories.client.FrostClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Only a genuine hold withholds a client tick. Dilated bodies keep ticking at the reduced velocity
 * the server gave them, which is what makes slow motion interpolate instead of stutter.
 */
@Mixin(ClientLevel.class)
public abstract class ClientTickMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
    private void hgos$suspend(Entity entity,CallbackInfo ci) {if(ClientState.frozen(entity.getId())||FrostClient.frozen(entity.getId()))ci.cancel();}
}
