package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mark the live result before a normal or shift-click copies it out of the menu. */
@Mixin(Slot.class)
public abstract class ParadiseResultMixin {
    @Inject(method="getItem",at=@At("RETURN"))
    private void hgos$markResult(CallbackInfoReturnable<ItemStack> cir) {
        if(!((Object)this instanceof ResultSlot slot))return;
        ItemStack output=cir.getReturnValue();
        if(output.isEmpty()||ParadiseFood.edible(output))return;
        CraftingContainer input=((ParadiseCraftAccessor)slot).hgos$craftSlots();
        for(int i=0;i<input.getContainerSize();i++)if(ParadiseFood.edible(input.getItem(i))) {
            ParadiseFood.mark(output);
            break;
        }
    }
}
