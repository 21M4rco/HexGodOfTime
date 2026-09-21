package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.MoonGravity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class MoonRenderMixin {
    @Inject(method="setupRotations",at=@At("HEAD"))
    private void hgos$radialBody(LivingEntity entity,PoseStack pose,float age,float yaw,float partial,CallbackInfo ci) {
        if(MoonGravity.active(entity))pose.mulPose(MoonGravity.rotation(entity,new Vec3(
            net.minecraft.util.Mth.lerp(partial,entity.xo,entity.getX()),net.minecraft.util.Mth.lerp(partial,entity.yo,entity.getY()),net.minecraft.util.Mth.lerp(partial,entity.zo,entity.getZ()))));
    }
}
