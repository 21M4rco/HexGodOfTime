package com.hexgodofstories.mixin;

import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A server restart cannot serialize a projectile's temporary zero velocity as its real momentum. */
@Mixin(Entity.class)
public abstract class TemporalSaveMixin {
    @Inject(method="saveWithoutId",at=@At("RETURN"))
    private void hgos$saveOriginalMotion(CompoundTag tag,CallbackInfoReturnable<CompoundTag> cir) {
        Vec3 motion=TemporalEngine.savedVelocity((Entity)(Object)this);
        if(motion==null)return;
        ListTag saved=new ListTag();
        saved.add(DoubleTag.valueOf(motion.x));
        saved.add(DoubleTag.valueOf(motion.y));
        saved.add(DoubleTag.valueOf(motion.z));
        cir.getReturnValue().put("Motion",saved);
    }
}
