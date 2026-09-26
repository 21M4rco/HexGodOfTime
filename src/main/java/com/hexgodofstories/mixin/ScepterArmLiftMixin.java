package com.hexgodofstories.mixin;

import com.hexgodofstories.client.ScepterClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Apply after animation setup so the arm, sleeve and held staff share the lift.
 *
 * <p>Holding the Scepter's right click, and for a moment after each shot, the arm comes forward to
 * forty-five degrees and holds there instead of swinging; everyone sees it, since every player's charge
 * and shots reach every viewer. A left click lifts the caster's own arm a little.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ScepterArmLiftMixin {
    private static final float RAISE=(float)Math.toRadians(45);

    @Inject(method="render", at=@At(value="INVOKE",
        target="Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
        shift=At.Shift.AFTER))
    private void hgos$scepterLift(LivingEntity entity, float yaw, float partial, PoseStack pose,
                                  MultiBufferSource buffers, int light, CallbackInfo ci) {
        if(!(entity instanceof Player player) || !ScepterClient.holding(player))return;
        var model=((LivingEntityRenderer<?, ?>)(Object)this).getModel();
        if(!(model instanceof PlayerModel<?> body))return;
        boolean left=player.getMainArm()==HumanoidArm.LEFT;
        var arm=left?body.leftArm:body.rightArm;
        float raise=ScepterClient.aim(player.getId());
        if(raise>0)arm.xRot=Mth.lerp(raise,arm.xRot,-RAISE);
        if(player==Minecraft.getInstance().player)arm.xRot-=(float)Math.toRadians(12)*ScepterClient.attackLift()*(1-raise);
        (left?body.leftSleeve:body.rightSleeve).copyFrom(arm);
    }
}
