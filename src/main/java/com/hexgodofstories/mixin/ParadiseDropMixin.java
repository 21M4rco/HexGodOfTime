package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import com.hexgodofstories.warping.ParadiseRestoration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tags the actual loot stacks of every native Paradise block before item entities are spawned.
 *
 * <p>This is deliberately based on the deterministic Paradise blueprint rather than on item-entity
 * position. Lily pads, azaleas, petals, grass, flowers and other support-sensitive decorations can
 * be knocked loose by neighbour updates and spawn their drop away from the exact broken coordinate;
 * their original block state still tells us unambiguously that they belonged to Paradise.
 */
@Mixin(Block.class)
public abstract class ParadiseDropMixin {
    @Inject(
        method="getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
        at=@At("RETURN")
    )
    private static void hgos$candyDrops(BlockState state,ServerLevel level,BlockPos pos,BlockEntity blockEntity,
                                        Entity breaker,ItemStack tool,CallbackInfoReturnable<List<ItemStack>> cir) {
        if(ParadiseRestoration.natural(pos,state)==null)return;
        for(ItemStack stack:cir.getReturnValue())ParadiseFood.mark(stack);
    }
}
