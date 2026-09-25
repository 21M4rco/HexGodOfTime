package com.hexgodofstories.entity;

import com.hexgodofstories.server.HexServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.function.Consumer;

public final class ConjuredWeapon extends Item {
    /** 0 dagger, 1 Scepter (the old Laevateinn registry ID), 2 time stick. */
    public final int kind;
    public ConjuredWeapon(int kind) {super(new Properties().stacksTo(1).rarity(kind==2?Rarity.RARE:Rarity.UNCOMMON));this.kind=kind;}
    /** Vanilla's normal attack cooldown and hit path apply to the Scepter alone. */
    @Override public Multimap<Attribute,AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        if(kind!=1||slot!=EquipmentSlot.MAINHAND)return super.getDefaultAttributeModifiers(slot);
        return ImmutableMultimap.<Attribute,AttributeModifier>builder()
            .put(Attributes.ATTACK_DAMAGE,new AttributeModifier(BASE_ATTACK_DAMAGE_UUID,"Scepter attack damage",3,AttributeModifier.Operation.ADDITION))
            .put(Attributes.ATTACK_SPEED,new AttributeModifier(BASE_ATTACK_SPEED_UUID,"Scepter attack speed",-2.4,AttributeModifier.Operation.ADDITION))
            .build();
    }
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
        if(player instanceof ServerPlayer p) {
            if(kind==0)HexServer.weapon(p,true,hand);
            else HexServer.weapon(p,true);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public boolean isEnchantable(ItemStack stack) {return false;}
    @Override public boolean canBeHurtBy(net.minecraft.world.damagesource.DamageSource source) {return false;}
    @Override public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions(){
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {return com.hexgodofstories.client.WeaponRenderer.instance();}
        });
    }
}
