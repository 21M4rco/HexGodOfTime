package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The floor a Warping puddle is spreading across.
 *
 * <p>The important rule is continuity, not a global height cap. Natural terrain often rises two,
 * three or more blocks across a large puddle, but it does so one step at a time. A wall rises several
 * blocks in one column. Walking the columns from the portal centre to the requested point lets the
 * goo follow the former without ever deciding the top of the latter is floor.
 */
public final class WarpSurface {
    private WarpSurface(){}

    /** Largest single upward terrain step the goo may climb. */
    private static final double STEP_UP=1.05;
    /** It may spill farther down than it may climb up. */
    private static final double STEP_DOWN=2.65;
    /** Extra downhill allowance as the puddle runs farther from its centre. */
    private static final double DOWN_SPREAD=.22,DOWN_LIMIT=5.5;

    /**
     * Resolve a target column by walking block-columns outward from the portal centre.
     *
     * <p>This is what fixes natural hills. A point two blocks above the centre is valid when the
     * route reaches it as two one-block steps, but invalid when the first column is a two-block wall.
     * Logs/leaves are never selected as floor, and a tree directly above real ground is allowed to
     * occlude the goo visually rather than turning the trunk into a staircase.
     */
    public static double height(BlockGetter level,double x,double z,double originX,double originY,double originZ){
        int ox=Mth.floor(originX),oz=Mth.floor(originZ),tx=Mth.floor(x),tz=Mth.floor(z);
        int dx=tx-ox,dz=tz-oz,steps=Math.max(Math.abs(dx),Math.abs(dz));
        double current=surfaceNear(level,ox,oz,originY,STEP_DOWN);
        if(Double.isNaN(current))current=originY;
        int lastX=ox,lastZ=oz;
        if(steps==0)return surfaceNear(level,tx,tz,current,STEP_DOWN);

        for(int i=1;i<=steps;i++){
            double f=i/(double)steps;
            int bx=Mth.floor(originX+(x-originX)*f);
            int bz=Mth.floor(originZ+(z-originZ)*f);
            if(bx==lastX&&bz==lastZ)continue;
            double distance=Math.hypot(bx-ox,bz-oz);
            double down=STEP_DOWN+Math.min(DOWN_LIMIT,distance*DOWN_SPREAD);
            double next=surfaceNear(level,bx,bz,current,down);
            if(Double.isNaN(next)||next-current>STEP_UP+.001)return Double.NaN;
            current=next;lastX=bx;lastZ=bz;
        }
        return current;
    }

    /**
     * Nearest valid walkable surface in one column around the previous surface height.
     * Solid buildings terminate the route; foliage/trunks can occlude a ground surface but never
     * become the surface themselves.
     */
    private static double surfaceNear(BlockGetter level,int bx,int bz,double around,double down){
        int top=Mth.floor(around+STEP_UP),bottom=Mth.floor(around-down);
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos(bx,top,bz);
        BlockPos.MutableBlockPos above=new BlockPos.MutableBlockPos(bx,top+1,bz);
        for(int y=top;y>=bottom;y--){
            pos.setY(y);
            BlockState state=level.getBlockState(pos);
            if(state.isAir()||state.is(BlockTags.LOGS)||state.is(BlockTags.LEAVES))continue;
            VoxelShape shape=state.getCollisionShape(level,pos);
            if(shape.isEmpty())continue;
            double surface=y+shape.max(Direction.Axis.Y);
            if(surface>around+STEP_UP+.001)continue;

            above.setY(y+1);
            BlockState over=level.getBlockState(above);
            if(!over.getCollisionShape(level,above).isEmpty()
                &&!over.is(BlockTags.LOGS)&&!over.is(BlockTags.LEAVES))continue;
            return surface;
        }
        return Double.NaN;
    }

    /** Compatibility form for callers/tests that only ask the centre column. */
    public static double height(BlockGetter level,double x,double z,double originY,double distance){
        return height(level,x,z,x,originY,z);
    }
    public static double height(BlockGetter level,double x,double z,double originY){
        return height(level,x,z,x,originY,z);
    }
}
