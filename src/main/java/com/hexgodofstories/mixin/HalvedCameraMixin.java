package com.hexgodofstories.mixin;

import com.hexgodofstories.client.Halving;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player cut in two sees from the head of their upper half, wherever it has fallen (client Halving). */
@Mixin(Camera.class)
public abstract class HalvedCameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method="setup",at=@At("RETURN"))
    private void hgos$halvedEyes(BlockGetter level,Entity entity,boolean thirdPerson,boolean reverse,float partial,CallbackInfo ci) {
        Vec3 eye=Halving.eye(partial);
        if(eye!=null)setPosition(eye);
    }
}
