package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.CandyRush;
import com.hexgodofstories.client.FrostClient;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The tremble of far too much sugar.
 *
 * <p>Minecraft already owns a body that will not keep still: a fully frozen entity has its body
 * yaw driven by a fast cosine while it is drawn, which is read instantly as shivering. Candy Rush
 * wants the same reading with the opposite cause, so it answers the same question rather than
 * inventing a second shake beside it — one behaviour, one look, and an effect a player understands
 * the first time they see somebody else with it.
 *
 * <p>This touches the rendered pose and nothing else. No input is swallowed, no movement is
 * slowed, no damage is dealt and nothing about it reaches the server: the shaking body walks,
 * jumps, aims and fights exactly as it did before, which is what keeps a positive effect positive.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class CandyShakeMixin {
    @Inject(method = "isShaking", at = @At("HEAD"), cancellable = true)
    private void hgos$candyRush(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        // Vanilla's fully-frozen renderer shakes an ice statue. Laevateinn's hold is still.
        if (FrostClient.frozen(entity.getId())) cir.setReturnValue(false);
        else if (CandyRush.on(entity)) cir.setReturnValue(true);
    }
}
