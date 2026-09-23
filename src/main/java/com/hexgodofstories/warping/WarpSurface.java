package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The floor a break is spreading across, column by column.
 *
 * <p>A portal drawn on one flat plane is a portal that clips through the step it runs into and
 * hangs over the one it runs off. Every point of the fracture asks here what it is lying on
 * instead, and gets the top of whatever is actually there: the collision shape's own upper face,
 * so a slab is half a block, a stair's lower half is half a block, and a full block is a block.
 *
 * <p>The portal is a puddle, not ivy. It may cross slabs, stairs and a single-block terrain step,
 * but it must never discover a tree canopy, wall top or roof several blocks above and then climb
 * vertically toward it. Upward reach is therefore tightly capped from the original floor plane;
 * only the downward search widens with distance so the goo may spill down a bank without crawling
 * up obstacles.
 *
 * <p>Tree logs and leaves are never treated as floor. A candidate surface must also have collision-
 * free space in the block directly above it. That makes a wall column, trunk column or other solid
 * vertical obstacle terminate the puddle instead of becoming a staircase for it.
 *
 * <p>A column with no valid ground surface answers {@link Double#NaN}: there is nothing there for
 * the goo to spread across, and the caller drops that piece rather than floating or climbing.
 */
public final class WarpSurface {
    private WarpSurface() { }

    /** A puddle may take one normal terrain step upward, but never climb a vertical obstacle. */
    private static final double RISE = 1.05;
    /** Downhill spill is more permissive because falling off a bank still reads as liquid. */
    private static final double DROP = 2.6;
    /** Only the downward search widens with distance; upward reach stays hard-capped. */
    private static final double SPREAD = 0.28, LIMIT = 7.0;

    /**
     * @param distance blocks from the centre of the break, which widens the window it may find a
     *                 surface in
     * @return the world Y of the surface in this column, or NaN when nothing in it can be cracked
     */
    public static double height(BlockGetter level, double x, double z, double originY, double distance) {
        double window = Math.min(LIMIT, distance * SPREAD);
        // Critical: distance is allowed to increase only the downhill spill. Letting it increase
        // 'up' is what made a large charged portal discover leaves and roofs and climb them.
        double up = originY + RISE, down = originY - DROP - window;
        int top = Mth.floor(up), bottom = Mth.floor(down);
        int bx=Mth.floor(x),bz=Mth.floor(z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, top, bz);
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos(bx, top+1, bz);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            // Trees are obstacles, not terrain. Skipping them also means a canopy can never become
            // a false floor several blocks above the real ground.
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) continue;

            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double surface = y + shape.max(Direction.Axis.Y);
            if (surface > up) continue;

            // A floor surface needs open/non-colliding room directly above it. Solid stacked
            // columns are walls/trunks/buildings and terminate the puddle instead of lifting it.
            above.setY(y+1);
            BlockState over = level.getBlockState(above);
            if (!over.getCollisionShape(level, above).isEmpty()) continue;

            return surface;
        }
        return Double.NaN;
    }

    /** The same question asked straight down from the break's own centre. */
    public static double height(BlockGetter level, double x, double z, double originY) {
        return height(level, x, z, originY, 0);
    }
}
