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
        if(steps==0)return surfaceAt(level,x,z,current,STEP_DOWN);

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
        return surfaceAt(level,x,z,current,STEP_DOWN);
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

    /** Exact collision top under a point: a stair tread is not its block's maximum height. */
    private static double surfaceAt(BlockGetter level,double x,double z,double around,double down){
        int bx=Mth.floor(x),bz=Mth.floor(z);
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        double fx=x-bx,fz=z-bz;
        for(int y=Mth.floor(around+STEP_UP);y>=Mth.floor(around-down);y--){
            pos.set(bx,y,bz);
            BlockState state=level.getBlockState(pos);
            if(state.is(BlockTags.LOGS)||state.is(BlockTags.LEAVES))continue;
            double highest=Double.NaN;
            for(var box:state.getCollisionShape(level,pos).toAabbs()){
                if(fx<box.minX||fx>=box.maxX||fz<box.minZ||fz>=box.maxZ)continue;
                double top=y+box.maxY;
                if(top<=around+STEP_UP+.001&&(Double.isNaN(highest)||top>highest))highest=top;
            }
            if(!Double.isNaN(highest))return highest;
        }
        return Double.NaN;
    }

    public record Tile(double minX,double minZ,double maxX,double maxZ,double y) {}

    /** Split at real collision-box edges, including both treads of stairs and modded shapes. */
    public static java.util.List<Tile> tiles(BlockGetter level,int bx,int bz,
                                            double ox,double oy,double oz){
        double top=height(level,bx+.5,bz+.5,ox,oy,oz);
        if(Double.isNaN(top))return java.util.List.of();
        var xs=new java.util.TreeSet<Double>();var zs=new java.util.TreeSet<Double>();
        // Half-block subdivisions keep the animated liquid shading smooth on full cubes too.
        xs.add(0.0);xs.add(.5);xs.add(1.0);zs.add(0.0);zs.add(.5);zs.add(1.0);
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int y=Mth.floor(top-STEP_DOWN);y<=Mth.floor(top+STEP_UP);y++){
            pos.set(bx,y,bz);
            BlockState state=level.getBlockState(pos);
            if(state.is(BlockTags.LOGS)||state.is(BlockTags.LEAVES))continue;
            for(var box:state.getCollisionShape(level,pos).toAabbs()){
                xs.add(Mth.clamp(box.minX,0,1));xs.add(Mth.clamp(box.maxX,0,1));
                zs.add(Mth.clamp(box.minZ,0,1));zs.add(Mth.clamp(box.maxZ,0,1));
            }
        }
        var xx=new java.util.ArrayList<>(xs);var zz=new java.util.ArrayList<>(zs);
        var result=new java.util.ArrayList<Tile>();
        for(int i=0;i<xx.size()-1;i++)for(int j=0;j<zz.size()-1;j++){
            double x0=bx+xx.get(i),x1=bx+xx.get(i+1),z0=bz+zz.get(j),z1=bz+zz.get(j+1);
            double y=height(level,(x0+x1)*.5,(z0+z1)*.5,ox,oy,oz);
            if(Double.isFinite(y))result.add(new Tile(x0,z0,x1,z1,y));
        }
        return result;
    }

    /** Compatibility form for callers/tests that only ask the centre column. */
    public static double height(BlockGetter level,double x,double z,double originY,double distance){
        return height(level,x,z,x,originY,z);
    }
    public static double height(BlockGetter level,double x,double z,double originY){
        return height(level,x,z,x,originY,z);
    }
}
