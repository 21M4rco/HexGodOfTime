package com.hexgodofstories.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Colours Hexor's kill message red.
 *
 * <p>The message itself is an ordinary vanilla death message: a damage type carrying the message id
 * {@code hexgodofstories.hexor}, whose translation lives in the language file like every other one.
 * That is deliberate — it means the line is produced once, by the same code that produces "was
 * slain by", so it obeys the {@code showDeathMessages} gamerule, reaches exactly the audience
 * vanilla would send it to, and appears on the death screen. Nothing sends a second copy to chat.
 *
 * <p>The one thing the vanilla system cannot express is colour, because a language file holds plain
 * text and the component is assembled in code. Styling the finished component at its single exit
 * point is the smallest possible place to say so.
 */
@Mixin(DamageSource.class)
public abstract class HexorDeathMessageMixin {
    /** Message id of {@code data/hexgodofstories/damage_type/hexor.json}. */
    private static final String HEXOR = "hexgodofstories.hexor";

    @Inject(method = "getLocalizedDeathMessage", at = @At("RETURN"), cancellable = true)
    private void hexgodofstories$redHexorDeath(LivingEntity victim, CallbackInfoReturnable<Component> cir) {
        DamageSource source = (DamageSource) (Object) this;
        if (!HEXOR.equals(source.type().msgId())) return;
        Component message = cir.getReturnValue();
        // Copied rather than styled in place: the caller does not own this component.
        if (message != null) cir.setReturnValue(message.copy().withStyle(ChatFormatting.RED));
    }
}
