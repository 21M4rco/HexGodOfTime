package com.hexgodofstories.mixin;

import com.hexgodofstories.client.CandyCorruptionClient;
import com.hexgodofstories.warping.CandyCorruption;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person held items are rendered by ItemInHandLayer separately from the player limb model.
 * Cancel that draw for the missing side so a sword/block/tool cannot hover beside an amputated arm.
 */
@Mixin(ItemInHandLayer.class)
public abstract class CandyHeldItemLayerMixin {
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void hgos$missingArm(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,
                                 PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        if(!(entity instanceof AbstractClientPlayer player))return;
        int part=arm==HumanoidArm.RIGHT?CandyCorruption.RIGHT_ARM:CandyCorruption.LEFT_ARM;
        if(CandyCorruptionClient.broken(player.getId(),part))ci.cancel();
    }
}
