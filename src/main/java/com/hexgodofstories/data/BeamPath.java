package com.hexgodofstories.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The volume a Time Branch torrent occupies, as block positions.
 *
 * <p>Walked as slices down the axis rather than over the segment's bounding box. That is not a
 * micro-optimisation: a sixty-block beam pointed diagonally has a bounding box of some eighty thousand
 * positions, so a box walk would both cost a visible hitch and, once it hit any cap, truncate the result
 * along the world axes instead of along the beam — carving a wedge out of the world and leaving the rest.
 * Slicing keeps the cost flat whichever way the caster is facing, and the order it returns is the order
 * the front reaches them, which is the order both the carve and the restore want.
 *
 * <p>Both sides call this, so the volume the server takes and the volume the client draws coming apart are
 * the same list in the same order, with no packet per block.
 */
public final class BeamPath {
    private BeamPath() {}

    /** Steps along the axis and across each slice, in blocks. Fine enough not to miss a block. */
    private static final double SLICE=.6,GRID=.6;

    /**
     * Every position inside the cylinder that currently holds a block.
     *
     * <p>Air and free-standing fluids are skipped. The torrent passes through every solid block — stone,
     * ore, a wall, a chest, a waterlogged block, a modded machine — with no hardness allow list. Plain
     * water/lava/modded fluid cells are left as fluid instead of being replaced by black cubes. Air is also
     * left alone because filling open space with a solid black tube would wall the world off.
     */
    public static List<BlockPos> occupied(BlockGetter level,Vec3 origin,Vec3 direction,
                                         double from,double to,double radius,int cap) {
        List<BlockPos> found=new ArrayList<>();
        if(to<=from||radius<=0||cap<=0)return found;
        Vec3 axis=direction.normalize();
        Vec3 side=axis.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-6)side=axis.cross(new Vec3(1,0,0));
        if(side.lengthSqr()<1e-6)side=new Vec3(1,0,0);
        side=side.normalize();
        Vec3 up=side.cross(axis).normalize();
        Set<BlockPos> seen=new HashSet<>();
        double radiusSq=radius*radius;
        for(double t=from;t<=to;t+=SLICE) {
            Vec3 centre=origin.add(axis.scale(t));
            for(double a=-radius;a<=radius;a+=GRID)for(double b=-radius;b<=radius;b+=GRID) {
                if(a*a+b*b>radiusSq)continue;
                BlockPos pos=BlockPos.containing(centre.add(side.scale(a)).add(up.scale(b)));
                if(!seen.add(pos))continue;
                BlockState state=level.getBlockState(pos);
                // A waterlogged solid still has collision and is real terrain. A plain fluid cell does
                // not: leave it alone so Time Branch never leaves Nothingness blocks floating in water.
                boolean freeFluid=!state.getFluidState().isEmpty()&&state.getCollisionShape(level,pos).isEmpty();
                if(!state.isAir()&&!freeFluid)found.add(pos);
                if(found.size()>=cap)return found;
            }
        }
        return found;
    }

    /** How far along the axis a point sits, for scheduling the front's arrival. */
    public static double along(Vec3 origin,Vec3 direction,Vec3 point) {return point.subtract(origin).dot(direction);}

    /** Perpendicular distance from a point to the infinite beam axis. */
    public static double radial(Vec3 origin,Vec3 direction,Vec3 point) {
        Vec3 axis=direction.normalize();
        double t=point.subtract(origin).dot(axis);
        Vec3 nearest=origin.add(axis.scale(t));
        return Math.sqrt(point.distanceToSqr(nearest));
    }
}
