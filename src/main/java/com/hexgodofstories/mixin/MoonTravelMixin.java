package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.MoonGravity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MoonTravelMixin {
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void hgos$moonTravel(Vec3 input,CallbackInfo ci){
        LivingEntity e=(LivingEntity)(Object)this;
        if(MoonGravity.active(e)){MoonGravity.travel(e,input);ci.cancel();}
        else if(com.hexgodofstories.warping.Destination.from(e.level())==com.hexgodofstories.warping.Destination.GRAVITY_WELL&&!e.isSpectator()) {
            // The server transports all entities on the exact orbit. Local travel must not add
            // vertical gravity or WASD movement between those authoritative position updates.
            e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;ci.cancel();
        }
    }
    @Inject(method="jumpFromGround",at=@At("HEAD"),cancellable=true)
    private void hgos$moonJump(CallbackInfo ci){
        LivingEntity e=(LivingEntity)(Object)this;
        if(MoonGravity.active(e)){MoonGravity.jump(e);ci.cancel();}
    }
}
