package com.loki.entity;

import com.loki.server.LokiServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import java.util.function.Consumer;

public final class ConjuredWeapon extends Item {
    /** 0 dagger, 1 Laevateinn, 2 TVA time stick. */
    public final int kind;
    public ConjuredWeapon(int kind) {super(new Properties().stacksTo(1).rarity(kind==2?Rarity.RARE:Rarity.UNCOMMON));this.kind=kind;}
    /** Off-hand twins are held point-down; the renderer reads this to flip the grip. */
    public static boolean reversed(ItemStack stack) {return stack.hasTag()&&stack.getTag().getBoolean("reverse");}
    /** A manifestation belongs to its caster, including stacks moved through a container. */
    public static boolean belongsTo(ItemStack stack,Player player) {
        return !stack.hasTag()||!stack.getTag().hasUUID("conjurer")||stack.getTag().getUUID("conjurer").equals(player.getUUID());
    }
    @Override public void inventoryTick(ItemStack stack,Level level,Entity holder,int slot,boolean selected) {
        if(level.isClientSide)return;
        if(!(holder instanceof Player player)||!belongsTo(stack,player)){stack.setCount(0);return;}
        // Creative-tab and command-created weapons become bound on their first inventory tick too.
        if(!stack.getOrCreateTag().hasUUID("conjurer"))stack.getOrCreateTag().putUUID("conjurer",player.getUUID());
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack,ItemEntity entity) {
        if(!entity.level().isClientSide)entity.discard();
        return true;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        if(!belongsTo(player.getItemInHand(hand),player))return InteractionResultHolder.fail(player.getItemInHand(hand));
        if(player instanceof ServerPlayer p)LokiServer.weapon(p,true);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public boolean isEnchantable(ItemStack stack) {return false;}
    @Override public boolean canBeHurtBy(net.minecraft.world.damagesource.DamageSource source) {return false;}
    @Override public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions(){
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {return com.loki.client.WeaponRenderer.instance();}
        });
    }
}
