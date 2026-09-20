package com.loki.mixin;

import com.loki.client.WorldEffects;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loose particles inside a stopped moment hang where they are. Withholding the whole per-particle
 * tick keeps position, age and fade exactly as they were, so smoke, sparks and splashes resume from
 * the same frame rather than catching up. The cheap global check comes first so a world with no hold
 * in it pays nothing.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleMixin {
    @Inject(method="tickParticle",at=@At("HEAD"),cancellable=true,require=0)
    private void loki$hold(Particle particle,CallbackInfo ci) {
        if(!WorldEffects.anyHold())return;
        Vec3 at=particle.getBoundingBox().getCenter();
        if(WorldEffects.heldSince(at.x,at.y,at.z)!=Long.MIN_VALUE)ci.cancel();
    }
}
