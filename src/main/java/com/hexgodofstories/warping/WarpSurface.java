package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
 * <p>Two rules keep it from becoming a set of square decals. The answer is per column rather than
 * per piece, so two points in the same column agree exactly and a piece that spans a step is drawn
 * as one slanted surface between two heights rather than as two tiles at different levels. And the
 * vertical window widens with distance from the impact, so a fracture is allowed to climb a slope
 * as it travels without being allowed to jump onto a cliff that happens to be nearby.
 *
 * <p>A column with no surface inside its window answers {@link Double#NaN}: there is nothing there
 * for reality to crack across, and the caller drops that piece rather than floating it.
 */
public final class WarpSurface {
    private WarpSurface() { }

    /** How far above the origin a surface may still be part of the same break. */
    private static final double RISE = 1.6;
    /** How far below, which is larger because a fracture falling off a ledge still reads. */
    private static final double DROP = 2.6;
    /** Extra window per block of distance, and the most it may ever grow to. */
    private static final double SPREAD = 0.28, LIMIT = 7.0;

    /**
     * @param distance blocks from the centre of the break, which widens the window it may find a
     *                 surface in
     * @return the world Y of the surface in this column, or NaN when nothing in it can be cracked
     */
    public static double height(BlockGetter level, double x, double z, double originY, double distance) {
        double window = Math.min(LIMIT, distance * SPREAD);
        double up = originY + RISE + window, down = originY - DROP - window;
        int top = Mth.floor(up), bottom = Mth.floor(down);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), top, Mth.floor(z));
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double surface = y + shape.max(Direction.Axis.Y);
            if (surface > up) continue;      // the block is there but its top is above the window
            return surface;
        }
        return Double.NaN;
    }

    /** The same question asked straight down from the break's own centre. */
    public static double height(BlockGetter level, double x, double z, double originY) {
        return height(level, x, z, originY, 0);
    }
}
