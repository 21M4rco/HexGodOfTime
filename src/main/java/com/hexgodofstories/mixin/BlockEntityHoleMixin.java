package com.hexgodofstories.mixin;

import com.hexgodofstories.client.BlockWounds;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hands a block entity's renderer buffers that cut a Scepter hole out of everything it draws, when a hole is
 * open through its block: a chest or a bed has no model in its chunk to leave out, only this renderer. The
 * pose is already at the block when this is called, whoever calls it. Optional, like the other hole hooks:
 * without it, such a block keeps its scorch and tunnel and is drawn whole.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityHoleMixin {
    @ModifyVariable(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private MultiBufferSource hgos$holed(MultiBufferSource buffers, BlockEntity entity, float partial, PoseStack pose, MultiBufferSource original) {
        return BlockWounds.entityBuffers(entity.getBlockPos(), pose, buffers);
    }
}
