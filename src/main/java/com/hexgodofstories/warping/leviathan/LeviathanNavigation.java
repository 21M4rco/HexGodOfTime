package com.hexgodofstories.warping.leviathan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Open water navigation. The abyss has one flat floor and no structures, so long range pathfinding
 * would burn CPU to rediscover a straight line every tick. The hunt controller steers the move
 * control directly and this class exists so that vanilla systems asking the mob to navigate still
 * get sane three dimensional answers.
 */
public final class LeviathanNavigation extends net.minecraft.world.entity.ai.navigation.PathNavigation {
    public LeviathanNavigation(Mob mob, Level level) { super(mob, level); }

    @Override
    protected PathFinder createPathFinder(int maxNodes) {
        this.nodeEvaluator = new SwimNodeEvaluator(true);
        return new PathFinder(this.nodeEvaluator, maxNodes);
    }

    @Override protected boolean canUpdatePath() { return true; }

    @Override protected Vec3 getTempMobPos() { return this.mob.position(); }

    @Override
    protected boolean canMoveDirectly(Vec3 from, Vec3 to) {
        return this.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS;
    }

    @Override public boolean isStableDestination(BlockPos pos) { return !this.level.getBlockState(pos).isSolidRender(this.level, pos); }

    @Override public boolean isInLiquid() { return true; }
}
