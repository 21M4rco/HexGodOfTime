package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashMap;
import java.util.Map;

/** Real registered collision shapes, without modifying any generated world. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class WarpSurfaceRegression {
    private static final class Floor implements BlockGetter {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        void put(int x,int y,int z,BlockState state){blocks.put(new BlockPos(x,y,z),state);}
        public BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p,Blocks.AIR.defaultBlockState());}
        public BlockEntity getBlockEntity(BlockPos p){return null;}
        public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public int getHeight(){return 384;}
        public int getMinBuildHeight(){return -64;}
    }
    @SubscribeEvent public static void started(ServerStartedEvent event){
        Floor f=new Floor();
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)f.put(x,63,z,Blocks.STONE.defaultBlockState());
        eq(WarpSurface.height(f,2.2,2.2,.5,64.025,.5),64,"flat floor");
        f.put(1,64,0,Blocks.STONE.defaultBlockState());
        f.put(2,64,0,Blocks.STONE.defaultBlockState());f.put(2,65,0,Blocks.STONE.defaultBlockState());
        eq(WarpSurface.height(f,2.5,.5,.5,64.025,.5),66,"two one-block hill steps");
        f.put(0,64,1,Blocks.OAK_SLAB.defaultBlockState());
        eq(WarpSurface.height(f,.25,1.25,.5,64.025,.5),64.5,"lower slab");
        f.put(-1,64,0,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        eq(WarpSurface.height(f,-.75,.25,.5,64.025,.5),65,"upper stair tread");
        eq(WarpSurface.height(f,-.75,.75,.5,64.025,.5),64.5,"lower stair tread");
        double upper=0,lower=0;
        for(var tile:WarpSurface.tiles(f,-1,0,.5,64.025,.5)){
            double area=(tile.maxX()-tile.minX())*(tile.maxZ()-tile.minZ());
            if(tile.y()==65)upper+=area;else if(tile.y()==64.5)lower+=area;
            else throw new AssertionError("unexpected stair surface "+tile.y());
        }
        eq(upper,.5,"upper stair coverage");eq(lower,.5,"lower stair coverage");
        for(int y=64;y<70;y++)f.put(0,y,-1,Blocks.BIRCH_LOG.defaultBlockState());
        eq(WarpSurface.height(f,.5,-1.5,.5,64.025,.5),64,"floor beyond trunk");
        for(int y=64;y<69;y++)f.put(1,y,1,Blocks.STONE.defaultBlockState());
        check(Double.isNaN(WarpSurface.height(f,1.5,1.5,.5,64.025,.5)),"wall is not floor");
        f.put(-1,64,0,Blocks.AIR.defaultBlockState());
        eq(WarpSurface.height(f,-.75,.25,.5,64.025,.5),64,"removed stair reveals floor");
        System.out.println("WARP_SURFACE_REGRESSIONS_PASSED");
    }
    private static void eq(double a,double b,String message){check(Math.abs(a-b)<1E-8,message+": "+a+" != "+b);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
