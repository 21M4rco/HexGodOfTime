package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Candy Rush: too much sugar, and nothing bad about it.
 *
 * <p>What the drinker gets is the pair of buffs a sugar rush ought to be — the legs of Speed II and
 * the hands of Haste III — and a body that will not keep still. The shake is the point of the
 * effect as much as the numbers are: it is the same tremble Minecraft already uses for freezing, so
 * a player who has never heard of this mod reads it instantly, and it is applied to the rendered
 * body only. Nothing about it slows a step, blocks an input or costs a heart.
 *
 * <p>The two halves are granted by different machinery because Minecraft grants them by different
 * machinery. Movement and attack speed are attributes, so they hang off this effect directly and
 * travel with it wherever it is applied. Mining speed is not an attribute: vanilla reads it out of
 * {@code MobEffects.DIG_SPEED} by name inside {@code Player#getDigSpeed}, and an unrelated effect
 * could carry a hundred modifiers without a pickaxe swinging any faster. Rather than quietly
 * stapling a hidden Haste onto the player — which would show up as a second icon, expire on its own
 * schedule and be strippable by milk without the rush going with it — the dig-speed event is
 * answered here with exactly the multiplier Haste III would have applied.
 */
public final class CandyRush extends MobEffect {
    /** Speed II is +40%: vanilla's Speed is +20% a level, and the effect is granted at level one. */
    private static final double MOVEMENT = 0.40;
    /** Haste III's own attack-speed share, which vanilla sets at a tenth per level. */
    private static final double SWING = 0.30;
    /**
     * What Haste III does to mining speed. {@code Player#getDigSpeed} multiplies by
     * {@code 1 + 0.2 * (amplifier + 1)}, and Haste III is amplifier two.
     */
    public static final float DIGGING = 1.6f;

    /** A pink that reads as sugar in the inventory's effect list and in the particle trail. */
    private static final int COLOUR = 0xff69c8;

    public CandyRush() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "3a1b9c6e-5d47-4c02-9f18-7b6a2e0d4c93",
            MOVEMENT, AttributeModifier.Operation.MULTIPLY_TOTAL);
        addAttributeModifier(Attributes.ATTACK_SPEED, "5c74e8b1-2f90-4a3d-8e56-1d9c0b7a3f42",
            SWING, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    /** Whether this body is currently on a sugar high. Safe to ask on either side. */
    public static boolean on(LivingEntity e) {
        return e != null && HexGodOfStories.CANDY_RUSH.isPresent() && e.hasEffect(HexGodOfStories.CANDY_RUSH.get());
    }

    /**
     * The hands. Fired by {@code Player#getDigSpeed} on both sides, so the client's own breaking
     * animation and the server's validation of it agree without either being told separately.
     */
    @Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
    public static final class Hands {
        @SubscribeEvent public static void digging(PlayerEvent.BreakSpeed e) {
            if (on(e.getEntity())) e.setNewSpeed(e.getNewSpeed() * DIGGING);
        }
    }
}
