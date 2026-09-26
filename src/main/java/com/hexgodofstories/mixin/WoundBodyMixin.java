package com.hexgodofstories.mixin;

import com.hexgodofstories.client.WoundAnchor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the moment a living body's own model is emitted, before any of its layers — armour, held
 * items, a cape, a parrot on the shoulder. A Scepter wound is pinned only to a part drawn here: the
 * layers go out in later batches, after the point at which the wound's opening has to be cut.
 *
 * <p>Optional on purpose: another mod may redirect this same call, and losing the bracket must not
 * stop the game from starting. Without it, wounds pin to whichever part the beam met first.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class WoundBodyMixin {
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),require=0)
    private void hgos$bodyBegin(LivingEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        WoundAnchor.body(true);
    }

    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",shift=At.Shift.AFTER),require=0)
    private void hgos$bodyEnd(LivingEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        WoundAnchor.body(false);
    }
}
