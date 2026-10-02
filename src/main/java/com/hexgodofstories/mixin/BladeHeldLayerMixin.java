package com.hexgodofstories.mixin;

import com.hexgodofstories.client.BladeClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A blade formed only for a combo (BladeClient) takes the main hand's place in the held-item layer, whatever is
 * really held there; so it is seen in third person and, while a combo move plays its arms in first person, there too.
 */
@Mixin(ItemInHandLayer.class)
public abstract class BladeHeldLayerMixin {
    @Shadow protected abstract void renderArmWithItem(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,
                                                      PoseStack pose,MultiBufferSource buffers,int light);

    /** Both hands empty: vanilla draws neither, so the formed blade is drawn here. */
    @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at=@At("HEAD"))
    private void hgos$emptyHanded(PoseStack pose,MultiBufferSource buffers,int light,LivingEntity entity,float walk,float walkAmount,
                                  float partial,float age,float headYaw,float headPitch,CallbackInfo ci) {
        if(!(entity instanceof AbstractClientPlayer player)||!entity.getMainHandItem().isEmpty()||!entity.getOffhandItem().isEmpty())return;
        ItemStack blade=BladeClient.phantom(player);
        if(blade.isEmpty())return;
        HumanoidArm arm=player.getMainArm();
        pose.pushPose();
        renderArmWithItem(entity,blade,arm==HumanoidArm.RIGHT?ItemDisplayContext.THIRD_PERSON_RIGHT_HAND:ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
            arm,pose,buffers,light);
        pose.popPose();
    }

    /** Something (or nothing) in either hand: the main hand's draw is given the formed blade instead. */
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void hgos$formedBlade(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,
                                  PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        if(!(entity instanceof AbstractClientPlayer player)||arm!=player.getMainArm())return;
        ItemStack blade=BladeClient.phantom(player);
        if(blade.isEmpty()||stack.is(blade.getItem()))return;
        ci.cancel();
        renderArmWithItem(entity,blade,context,arm,pose,buffers,light);
    }
}
