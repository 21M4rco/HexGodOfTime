package com.hexgodofstories.mixin;

import com.hexgodofstories.server.IllusoryWalls;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pathfinding treats an illusory wall's columns as blocked, including two courses of clearance above
 * it, so a mob walks around the lie instead of through it and never tries to cross its parapet.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class PathWallMixin {
    @Inject(method="getBlockPathType(Lnet/minecraft/world/level/BlockGetter;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at=@At("RETURN"),cancellable=true)
    private void hgos$illusoryWall(BlockGetter level,int x,int y,int z,Mob mob,CallbackInfoReturnable<BlockPathTypes> cir) {
        if(!IllusoryWalls.active()||cir.getReturnValue()==BlockPathTypes.BLOCKED)return;
        if(IllusoryWalls.blocksPath(mob.level(),x,y,z))cir.setReturnValue(BlockPathTypes.BLOCKED);
    }
}
