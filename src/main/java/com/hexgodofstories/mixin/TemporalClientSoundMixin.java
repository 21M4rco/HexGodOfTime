package com.hexgodofstories.mixin;

import com.hexgodofstories.client.WorldEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Roundabout mutes weather and block ambience inside stopped time, while letting break sounds play. */
@Mixin(ClientLevel.class)
public abstract class TemporalClientSoundMixin {
    @Inject(method="playLocalSound",at=@At("HEAD"),cancellable=true,require=0)
    private void hgos$quiet(double x,double y,double z,SoundEvent event,SoundSource category,
                            float volume,float pitch,boolean delayed,CallbackInfo ci) {
        if((category==SoundSource.WEATHER||category==SoundSource.BLOCKS)
            &&!event.getLocation().getPath().contains("break")
            &&WorldEffects.heldSince(x,y,z)!=Long.MIN_VALUE)ci.cancel();
    }
}
