package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the Paradise tag authoritative at the ItemStack level.
 *
 * <p>Doing this on Item rather than ItemStack was not enough for "literally anything": bows,
 * shields and modded tools may override Item#use/getUseAnimation. ItemStack is the dispatch point
 * before those overrides, so a Candy-tagged crafted object always gets the same chew behaviour
 * regardless of what class its underlying item uses.
 */
@Mixin(ItemStack.class)
public abstract class ParadiseFoodMixin {
    private ItemStack hgos$self(){return (ItemStack)(Object)this;}

    @Inject(method="getUseAnimation",at=@At("HEAD"),cancellable=true)
    private void hgos$chew(CallbackInfoReturnable<UseAnim> cir) {
        if(ParadiseFood.edible(hgos$self())&&ParadiseFood.biting(hgos$self()))cir.setReturnValue(UseAnim.EAT);
    }

    @Inject(method="getUseDuration",at=@At("HEAD"),cancellable=true)
    private void hgos$mouthful(CallbackInfoReturnable<Integer> cir) {
        if(ParadiseFood.edible(hgos$self())&&ParadiseFood.biting(hgos$self()))cir.setReturnValue(ParadiseFood.CHEW);
    }

    @Inject(method="use",at=@At("HEAD"),cancellable=true)
    private void hgos$bite(Level level,Player player,InteractionHand hand,
                           CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack stack=hgos$self();
        if(!ParadiseFood.edible(stack))return;
        if(!player.isShiftKeyDown()){ParadiseFood.endBite(stack);return;}
        if(!player.canEat(false)){ParadiseFood.endBite(stack);cir.setReturnValue(InteractionResultHolder.fail(stack));return;}
        ParadiseFood.beginBite(stack);
        player.startUsingItem(hand);
        cir.setReturnValue(InteractionResultHolder.consume(stack));
    }

    @Inject(method="finishUsingItem",at=@At("HEAD"),cancellable=true)
    private void hgos$swallow(Level level,LivingEntity eater,CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack=hgos$self();
        if(ParadiseFood.edible(stack)&&ParadiseFood.biting(stack))cir.setReturnValue(ParadiseFood.eat(stack,level,eater));
    }
}
