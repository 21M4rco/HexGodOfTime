package com.loki.mixin;

import com.loki.server.BranchFist;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(Player.class)
public abstract class BranchPunchMixin {
    @Redirect(method="attack",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean loki$chargedContact(Entity target,DamageSource source,float amount) {
        return BranchFist.melee((Player)(Object)this,target,source,amount);
    }
}
