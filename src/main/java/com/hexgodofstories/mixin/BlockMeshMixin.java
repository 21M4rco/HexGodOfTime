package com.hexgodofstories.mixin;

import com.hexgodofstories.client.BlockWounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves a block with a Scepter hole through it out of its chunk's mesh while the hole is open: BlockWounds
 * draws it instead, carved. This is the call vanilla chunk building makes for every block it meshes; it
 * is Forge's overload, which keeps its own name at runtime, so it is not remapped.
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockMeshMixin {
    @Inject(method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void hgos$holed(BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose, VertexConsumer consumer, boolean sides,
                            RandomSource random, ModelData data, RenderType type, CallbackInfo ci) {
        if (BlockWounds.hidden(pos)) ci.cancel();
    }
}
