package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.WarpCrossing;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the floor out of the way of a body that is sinking into a Warping pool.
 *
 * <p>Without this the whole crossing does not happen, and the reason is a single line of vanilla:
 * {@code Player.aiStep} assigns {@code noPhysics = isSpectator()} on every tick of every player, on
 * the client and on the server alike. A grant handed out from a tick event — which is what
 * {@link WarpCrossing} does, at the start of the level's tick, and what the client does at the start
 * of the player's — is therefore wiped before it has prevented a single collision, and the player
 * stands on top of an open portal for ever while the pool very slowly gives up on them.
 *
 * <p>So the flag is re-asserted here rather than only set there. The injection point is chosen for
 * exactly that: {@code Player.aiStep} clears it near its top and then calls {@code super.aiStep()},
 * which is this method, and the movement for the tick happens further down inside it. The head of
 * this method is the one place that is after the clearing and before the moving.
 *
 * <p>Everything that is not a player goes through here too, and for those the call is merely
 * idempotent — nothing clears their flag, so re-asserting it changes nothing. It is deliberately
 * not narrowed to players anyway: the thing being stated is "a body in the liquid does not collide
 * with this world", and that is true of all of them.
 */
@Mixin(LivingEntity.class)
public abstract class CrossingPhysicsMixin {
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void hgos$sinking(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (WarpCrossing.sinking(self)) self.noPhysics = true;
    }
}
