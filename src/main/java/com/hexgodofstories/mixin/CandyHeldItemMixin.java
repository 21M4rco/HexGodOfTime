package com.hexgodofstories.mixin;

import com.hexgodofstories.client.CandyCorruptionClient;
import com.hexgodofstories.warping.CandyCorruption;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person item rendering is separate from the player arm model. If the physical arm is gone,
 * the stack must disappear too instead of floating where the hand used to be.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class CandyHeldItemMixin {
    @Inject(method="renderArmWithItem",at=@At("HEAD"),cancellable=true)
    private void hgos$missingArm(AbstractClientPlayer player,float partial,float pitch,InteractionHand hand,
                                 float swing,ItemStack stack,float equip,PoseStack pose,
                                 MultiBufferSource buffers,int light,CallbackInfo ci) {
        HumanoidArm arm=hand==InteractionHand.MAIN_HAND?player.getMainArm():player.getMainArm().getOpposite();
        int part=arm==HumanoidArm.RIGHT?CandyCorruption.RIGHT_ARM:CandyCorruption.LEFT_ARM;
        if(CandyCorruptionClient.broken(player.getId(),part))ci.cancel();
    }
}
