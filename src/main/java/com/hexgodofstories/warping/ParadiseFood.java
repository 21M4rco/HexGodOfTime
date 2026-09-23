package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Paradise is edible.
 *
 * <p>A block mined out of this realm is not ordinary stone or ordinary turf. It comes away sweet,
 * and it can be eaten — grass, moss, calcite, amethyst, a piece of somebody's candy cane — with
 * hunger restored in proportion to how rich the material is and, for the better ones, a few
 * seconds of something pleasant on top. Nothing here hurts to eat. That is the point of it: the
 * realm reads as made of sugar all the way down rather than merely painted that colour, and the
 * player finds it out by trying rather than by being told.
 *
 * <p><b>What is edible is decided when it drops, not by what it is.</b> The stack is marked at the
 * moment {@link ParadiseRestoration} sees it fall out of the realm's own terrain, so a grass block
 * carried in from the overworld stays a grass block and one dug out of an island beside the hot
 * spring does not. Marking the stack rather than the item also means the mark travels with it: it
 * is still food in a chest at home, still food a week later, and still food when handed to
 * somebody who has never been here.
 *
 * <p>Placing one still places a block. Eating is <b>crouch and use</b>, which is the one input that
 * cannot be confused with building, and the tooltip on every marked stack says so.
 */
public final class ParadiseFood {
    private ParadiseFood() { }

    private static final String TAG = "HexParadise";
    /** How long a mouthful takes. Vanilla food is thirty-two ticks; terrain is chewier. */
    public static final int CHEW = 34;

    public static void mark(ItemStack stack) {
        if (!stack.isEmpty()) stack.getOrCreateTag().putBoolean(TAG, true);
    }

    public static boolean edible(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(TAG);
    }

    /** Pink provenance prefix used everywhere Minecraft asks for this stack's visible name. */
    public static Component candyName(ItemStack stack,Component original) {
        if(!edible(stack))return original;
        return Component.literal("Candied "+original.getString()).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    /**
     * How rich a mouthful of this is, from one to five.
     *
     * <p>Five tiers, ordered the way the realm itself is: the things growing on top are the
     * lightest, the soil under them is a mouthful, the pale stone is a solid one, anything glowing
     * is better still, and the confectionery somebody's island grew is the best of it. The scale is
     * small deliberately — a player should be able to work out which end of a Paradise block is
     * worth eating without a table in front of them.
     */
    public static int nutrition(ItemStack stack) {
        var item = stack.getItem();
        if (item == Items.PINK_CONCRETE || item == Items.LIGHT_BLUE_CONCRETE || item == Items.YELLOW_CONCRETE
            || item == Items.MAGENTA_CONCRETE || item == Items.WHITE_CONCRETE || item == Items.RED_CONCRETE
            || item == Items.PINK_GLAZED_TERRACOTTA || item == Items.MAGENTA_GLAZED_TERRACOTTA) return 5;
        if (item == Items.SEA_LANTERN || item == Items.PEARLESCENT_FROGLIGHT || item == Items.AMETHYST_BLOCK
            || item == Items.AMETHYST_CLUSTER || item == Items.PRISMARINE || item == Items.PRISMARINE_BRICKS) return 4;
        if (item == Items.CALCITE || item == Items.DIORITE || item == Items.TUFF || item == Items.SMOOTH_QUARTZ
            || item == Items.QUARTZ_PILLAR || item == Items.PINK_TERRACOTTA || item == Items.CHERRY_LOG) return 3;
        if (item == Items.GRASS_BLOCK || item == Items.MOSS_BLOCK || item == Items.DIRT || item == Items.ROOTED_DIRT) return 2;
        return 1;
    }

    public static float saturation(ItemStack stack) { return 0.08f * nutrition(stack); }

    /**
     * The few seconds of something pleasant that the better mouthfuls carry.
     *
     * <p>Tied to the tier rather than to the individual block, so there is one rule to learn: the
     * pale stone quickens the step, anything luminous knits you back together, and the
     * confectionery does what the hot spring does.
     */
    private static void reward(Player player, ItemStack stack) {
        switch (nutrition(stack)) {
            case 5 -> player.addEffect(new MobEffectInstance(HexGodOfStories.CANDY_RUSH.get(), 160, 0, true, true, true));
            case 4 -> player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, true, true));
            case 3 -> player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 120, 0, true, true, true));
            default -> { }
        }
    }

    /**
     * Swallowing one. Reached from {@code Item#finishUsingItem}, which vanilla only ever calls on
     * the server, so the food, the effect and the stat all happen exactly once.
     */
    public static ItemStack eat(ItemStack stack, Level level, LivingEntity eater) {
        if (eater instanceof Player player) {
            if (!level.isClientSide) {
                if(player instanceof net.minecraft.server.level.ServerPlayer server)
                    com.hexgodofstories.server.PersonalRewind.eaten(server,stack);
                player.getFoodData().eat(nutrition(stack), saturation(stack));
                reward(player, stack);
                CandyCorruption.consume(player,1);
                player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_BURP,
                    SoundSource.PLAYERS, .45f, 1.2f + level.random.nextFloat() * .3f);
            }
            if (!player.getAbilities().instabuild) stack.shrink(1);
        } else {
            stack.shrink(1);
        }
        return stack;
    }

    /**
     * Crouch and use: a bite rather than a placement.
     *
     * <p>Aiming at open air already reaches {@code Item#use} and eats, but a realm made of ground
     * is a realm where you are almost always aiming at a block, and there the block item would be
     * placed before the question of eating ever came up. This is the one input that has no other
     * meaning while holding terrain, and it is answered identically on both sides, which is what
     * keeps the client's chewing animation in step with the server's timer.
     */
    @Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
    public static final class Bite {
        @SubscribeEvent public static void crouched(PlayerInteractEvent.RightClickBlock e) {
            Player player = e.getEntity();
            if (!player.isShiftKeyDown() || !edible(e.getItemStack()) || player.isUsingItem()) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.CONSUME);
            player.startUsingItem(e.getHand());
        }
    }

    /**
     * Candy provenance propagates through crafting.
     *
     * <p>If a recipe is made in Paradise, its result is candy. Outside Paradise, a single candy
     * ingredient is enough to pass the provenance forward. That means Paradise logs -> candy planks
     * -> candy sticks -> candy tools/weapons/anything else built from those sticks, with no recipe
     * allow-list and no special cases for modded crafting-grid recipes.
     */
    @Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
    public static final class Crafting {
        @SubscribeEvent public static void crafted(PlayerEvent.ItemCraftedEvent e) {
            ItemStack out=e.getCrafting();
            if(out.isEmpty())return;
            boolean candy=Destination.from(e.getEntity().level())==Destination.PARADISE;
            var grid=e.getInventory();
            if(!candy)for(int i=0;i<grid.getContainerSize();i++)if(edible(grid.getItem(i))){candy=true;break;}
            if(candy)mark(out);
        }
    }

    /** What a marked stack says about itself, so nobody has to be told this feature exists. */
    @Mod.EventBusSubscriber(modid = HexGodOfStories.ID, value = Dist.CLIENT)
    public static final class Label {
        @SubscribeEvent public static void tooltip(ItemTooltipEvent e) {
            if (!edible(e.getItemStack())) return;
            e.getToolTip().add(Component.literal("Grown in Paradise").withStyle(ChatFormatting.LIGHT_PURPLE));
            e.getToolTip().add(Component.literal("Crouch + Use to eat").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
