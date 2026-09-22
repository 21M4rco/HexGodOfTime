package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.ParadiseFood;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes one particular stack edible when its item is not.
 *
 * <p>Minecraft decides what can be eaten per item rather than per stack: {@code Item#isEdible} is
 * a field on the item, and the four methods that make eating happen all read it. There is no way
 * to say "this grass block in particular is food" from outside, which is exactly what Paradise
 * needs to say — the realm's own turf is food and the identical block from anywhere else is not.
 *
 * <p>So the four answers are given per stack instead, each behind the same tag check, and every
 * one of them leaves an unmarked stack completely alone. Vanilla then does all of the rest by
 * itself: because the animation answers {@code EAT}, the chewing particles, the eating sound and
 * the hand pose all arrive without being asked for, and because the duration is non-zero the use
 * timer runs and finishes on its own.
 */
@Mixin(Item.class)
public abstract class ParadiseFoodMixin {
    @Inject(method = "getUseAnimation", at = @At("HEAD"), cancellable = true)
    private void hgos$chew(ItemStack stack, CallbackInfoReturnable<UseAnim> cir) {
        if (ParadiseFood.edible(stack)) cir.setReturnValue(UseAnim.EAT);
    }

    @Inject(method = "getUseDuration", at = @At("HEAD"), cancellable = true)
    private void hgos$mouthful(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (ParadiseFood.edible(stack)) cir.setReturnValue(ParadiseFood.CHEW);
    }

    /** Reached when the use is not aimed at a block; the crouch case is handled by an event. */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void hgos$bite(Level level, Player player, InteractionHand hand,
                           CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack stack = player.getItemInHand(hand);
        if (!ParadiseFood.edible(stack)) return;
        if (!player.canEat(false)) { cir.setReturnValue(InteractionResultHolder.fail(stack)); return; }
        player.startUsingItem(hand);
        cir.setReturnValue(InteractionResultHolder.consume(stack));
    }

    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true)
    private void hgos$swallow(ItemStack stack, Level level, LivingEntity eater, CallbackInfoReturnable<ItemStack> cir) {
        if (ParadiseFood.edible(stack)) cir.setReturnValue(ParadiseFood.eat(stack, level, eater));
    }
}
