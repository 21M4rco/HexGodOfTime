package com.hexgodofstories.mixin;
import com.hexgodofstories.client.CandyCorruptionClient;
import com.hexgodofstories.warping.CandyCorruption;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerRenderer.class)
public abstract class CandyArmMixin {
    @Inject(method="renderRightHand",at=@At("HEAD"),cancellable=true)
    private void hgos$right(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer player,CallbackInfo ci){
        if(CandyCorruptionClient.broken(player.getId(),CandyCorruption.RIGHT_ARM))ci.cancel();
    }
    @Inject(method="renderLeftHand",at=@At("HEAD"),cancellable=true)
    private void hgos$left(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer player,CallbackInfo ci){
        if(CandyCorruptionClient.broken(player.getId(),CandyCorruption.LEFT_ARM))ci.cancel();
    }
}
