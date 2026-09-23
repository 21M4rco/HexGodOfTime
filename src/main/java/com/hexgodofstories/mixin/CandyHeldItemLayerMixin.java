package com.hexgodofstories.mixin;

import com.hexgodofstories.client.CandyCorruptionClient;
import com.hexgodofstories.warping.CandyCorruption;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a held stack from hovering beside a player whose corresponding candy limb is gone. */
@Mixin(PlayerItemInHandLayer.class)
public abstract class CandyHeldItemLayerMixin {
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void hgos$missingArm(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,
                                 PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        int part=arm==HumanoidArm.RIGHT?CandyCorruption.RIGHT_ARM:CandyCorruption.LEFT_ARM;
        if(CandyCorruptionClient.broken(entity.getId(),part))ci.cancel();
    }
}
