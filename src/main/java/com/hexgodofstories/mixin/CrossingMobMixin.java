package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.WarpCrossing;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A creature a Warping pool has taken thinks nothing while it goes down.
 *
 * <p>Its goals, path, move and jump controls would otherwise go on running on top of the pool: it walked on toward the
 * rim and was let go the moment it stepped off the liquid, or its path saw the next node above it as it sank and it
 * hopped back out. With them not run (and its last tick's walking and jumping cleared, CrossingPhysicsMixin) the only
 * movement it makes is the pool's. Nothing is written to the creature, so whatever comes out on the far side, or out
 * of a pool that shut early, thinks again at once.
 */
@Mixin(Mob.class)
public abstract class CrossingMobMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void hgos$swallowed(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide && WarpCrossing.crossing(self)) ci.cancel();
    }
}
