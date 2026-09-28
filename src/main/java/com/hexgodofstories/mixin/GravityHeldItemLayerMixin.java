package com.hexgodofstories.mixin;

import com.hexgodofstories.client.AnchorClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The grasp/slash owns the right hand visually; no real inventory stack is moved or replaced. */
@Mixin(ItemInHandLayer.class)
public abstract class GravityHeldItemLayerMixin {
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void hgos$gravityHand(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,
                                  PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        if(entity instanceof AbstractClientPlayer&&arm==HumanoidArm.RIGHT&&AnchorClient.busy(entity.getId()))ci.cancel();
    }
}
