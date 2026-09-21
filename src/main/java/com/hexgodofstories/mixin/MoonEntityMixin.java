package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.MoonGravity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MoonEntityMixin {
    @Inject(method="move",at=@At("HEAD"),cancellable=true)
    private void hgos$wellOrbit(net.minecraft.world.entity.MoverType type,Vec3 move,CallbackInfo ci) {
        Entity e=(Entity)(Object)this;
        if(com.hexgodofstories.warping.Destination.from(e.level())==com.hexgodofstories.warping.Destination.GRAVITY_WELL&&!e.isSpectator())ci.cancel();
    }
    @Inject(method="getEyePosition()Lnet/minecraft/world/phys/Vec3;",at=@At("HEAD"),cancellable=true)
    private void hgos$eye(CallbackInfoReturnable<Vec3> ci){
        Entity e=(Entity)(Object)this;if(MoonGravity.active(e))ci.setReturnValue(MoonGravity.eye(e,1));
    }
    @Inject(method="getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",at=@At("HEAD"),cancellable=true)
    private void hgos$eye(float partial,CallbackInfoReturnable<Vec3> ci){
        Entity e=(Entity)(Object)this;if(MoonGravity.active(e))ci.setReturnValue(MoonGravity.eye(e,partial));
    }
    @Inject(method="calculateViewVector",at=@At("RETURN"),cancellable=true)
    private void hgos$look(float pitch,float yaw,CallbackInfoReturnable<Vec3> ci){
        Entity e=(Entity)(Object)this;if(MoonGravity.active(e))ci.setReturnValue(MoonGravity.transform(e,ci.getReturnValue()));
    }
    @Inject(method="makeBoundingBox",at=@At("RETURN"),cancellable=true)
    private void hgos$body(CallbackInfoReturnable<AABB> ci){
        Entity e=(Entity)(Object)this;
        if(!MoonGravity.active(e))return;
        Vec3 feet=e.position(),up=MoonGravity.up(feet),top=feet.add(up.scale(e.getBbHeight()));
        double half=e.getBbWidth()*.5;
        ci.setReturnValue(new AABB(Math.min(feet.x,top.x)-half,Math.min(feet.y,top.y)-half,Math.min(feet.z,top.z)-half,
            Math.max(feet.x,top.x)+half,Math.max(feet.y,top.y)+half,Math.max(feet.z,top.z)+half));
    }
}
