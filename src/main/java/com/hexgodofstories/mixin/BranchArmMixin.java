package com.hexgodofstories.mixin;

import com.hexgodofstories.client.BranchFistLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First-person arm uses the same geometry as the third-person bone-attached layer. */
@Mixin(PlayerRenderer.class)
public abstract class BranchArmMixin {
    @Inject(method="renderRightHand",at=@At("RETURN"))
    private void hgos$fist(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer player,CallbackInfo ci) {
        float partial=Minecraft.getInstance().getFrameTime();
        if(!BranchFistLayer.visible(player.getId(),partial))return;
        pose.pushPose();
        ((PlayerRenderer)(Object)this).getModel().rightArm.translateAndRotate(pose);
        BranchFistLayer.draw(pose,buffers,player,partial);
        pose.popPose();
    }
}
