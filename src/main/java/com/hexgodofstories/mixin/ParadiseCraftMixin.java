package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tag the result before the crafting grid is consumed or the output is moved. */
@Mixin(ResultSlot.class)
public abstract class ParadiseCraftMixin {
    @Shadow @Final private CraftingContainer craftSlots;

    @Inject(method="onTake",at=@At("HEAD"))
    private void hgos$inherit(Player player,ItemStack result,CallbackInfo ci) {
        for(int i=0;i<craftSlots.getContainerSize();i++) {
            if(ParadiseFood.edible(craftSlots.getItem(i))) {
                ParadiseFood.mark(result);
                return;
            }
        }
    }
}
