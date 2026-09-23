package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import com.hexgodofstories.warping.ParadiseRestoration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags Paradise block loot before the ItemEntity exists. This catches lily pads, bushes and other
 * support-sensitive decorations that can otherwise move/fall far enough to miss spawn-position tracking.
 */
@Mixin(Block.class)
public abstract class ParadiseDropMixin {
    @Inject(method="popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        at=@At("HEAD"))
    private static void hgos$candyDrop(Level level,BlockPos pos,ItemStack stack,CallbackInfo ci) {
        if(level instanceof ServerLevel server&&ParadiseRestoration.candyDrop(server,pos))
            ParadiseFood.mark(stack);
    }
}
