package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Bottling any source-water block in Paradise makes Paradise Waters.
 *
 * <p>This deliberately remains a vanilla potion stack rather than introducing a second custom
 * bottle item. Vanilla therefore owns drinking, bottle return, particles and inventory behaviour;
 * Paradise only supplies the name, colour and its five-minute Candy Rush.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class ParadiseWaters {
    private static final int PINK = 0xff69c8;
    private ParadiseWaters() { }

    @SubscribeEvent public static void bottle(PlayerInteractEvent.RightClickItem e) {
        if (!e.getItemStack().is(Items.GLASS_BOTTLE) || Destination.from(e.getLevel()) != Destination.PARADISE) return;

        Player player = e.getEntity();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(5.0D));
        HitResult result = e.getLevel().clip(new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player
        ));
        if (!(result instanceof BlockHitResult hit) || hit.getType()!=HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        var fluid = e.getLevel().getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return;

        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));
        if (e.getLevel().isClientSide) return;

        ItemStack drink = create();
        ItemStack bottles = e.getItemStack();
        if (!player.getAbilities().instabuild) {
            bottles.shrink(1);
            if (bottles.isEmpty()) player.setItemInHand(e.getHand(), drink);
            else if (!player.getInventory().add(drink)) player.drop(drink, false);
        } else if (!player.getInventory().add(drink)) {
            player.drop(drink, false);
        }

        player.awardStat(Stats.ITEM_USED.get(Items.GLASS_BOTTLE));
        e.getLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 1.0F, 1.1F);
    }

    public static ItemStack create() {
        ItemStack potion = new ItemStack(Items.POTION);
        PotionUtils.setPotion(potion, Potions.WATER);
        PotionUtils.setCustomEffects(potion, List.of(
            new MobEffectInstance(HexGodOfStories.CANDY_RUSH.get(), Paradise.CANDY_RUSH_TICKS, 0, false, true, true)
        ));
        potion.getOrCreateTag().putInt("CustomPotionColor", PINK);
        potion.setHoverName(Component.literal("Paradise Waters").withStyle(ChatFormatting.LIGHT_PURPLE));
        return potion;
    }
}
