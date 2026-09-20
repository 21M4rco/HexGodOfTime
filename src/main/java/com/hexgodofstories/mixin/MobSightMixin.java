package com.hexgodofstories.mixin;

import com.hexgodofstories.server.IllusoryWalls;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Borrowed Reality wall stops a mob's sight the way masonry would. Vanilla does the rest: a hunter
 * that cannot see its quarry for long enough forgets it, which is exactly what the lie is for.
 * Players are left alone — they see straight through their own illusions.
 */
@Mixin(LivingEntity.class)
public abstract class MobSightMixin {
    @Inject(method="hasLineOfSight",at=@At("RETURN"),cancellable=true)
    private void hgos$illusoryWall(Entity target,CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ()||!IllusoryWalls.active())return;
        LivingEntity self=(LivingEntity)(Object)this;
        if(self.level().isClientSide||!IllusoryWalls.fooled(self))return;
        if(IllusoryWalls.blocksSight(self.level(),new Vec3(self.getX(),self.getEyeY(),self.getZ()),new Vec3(target.getX(),target.getEyeY(),target.getZ())))
            cir.setReturnValue(false);
    }
}
