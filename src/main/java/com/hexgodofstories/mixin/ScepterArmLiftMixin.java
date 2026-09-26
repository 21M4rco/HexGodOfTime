package com.hexgodofstories.mixin;

import com.hexgodofstories.client.ScepterClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply after animation setup so the arm, sleeve and held staff share the small lift. */
@Mixin(LivingEntityRenderer.class)
public abstract class ScepterArmLiftMixin {
    @Inject(method="render", at=@At(value="INVOKE",
        target="Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
        shift=At.Shift.AFTER))
    private void hgos$scepterLift(LivingEntity entity, float yaw, float partial, PoseStack pose,
                                  MultiBufferSource buffers, int light, CallbackInfo ci) {
        var player=Minecraft.getInstance().player;
        if(entity!=player || !ScepterClient.holding(player))return;
        var model=((LivingEntityRenderer<?, ?>)(Object)this).getModel();
        if(!(model instanceof PlayerModel<?> body))return;
        float lift=ScepterClient.attackLift()*(1-ScepterClient.aim(player.getMainHandItem()));
        boolean left=player.getMainArm()==HumanoidArm.LEFT;
        var arm=left?body.leftArm:body.rightArm;
        arm.xRot-=(float)Math.toRadians(12)*lift;
        (left?body.leftSleeve:body.rightSleeve).copyFrom(arm);
    }
}
