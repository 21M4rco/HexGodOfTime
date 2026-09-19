package com.loki.entity;

import com.loki.server.LokiServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import java.util.function.Consumer;

public final class ConjuredWeapon extends Item {
    public final int kind;
    public ConjuredWeapon(int kind) {super(new Properties().stacksTo(1).rarity(kind==2?Rarity.RARE:Rarity.UNCOMMON));this.kind=kind;}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        if(player instanceof ServerPlayer p)LokiServer.weapon(p,true);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions(){
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {return com.loki.client.WeaponRenderer.instance();}
        });
    }
}
