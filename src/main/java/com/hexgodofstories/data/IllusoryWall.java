package com.hexgodofstories.data;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/**
 * Borrowed Reality is one shape — a wall — at four sizes. Holding the cast key grows it from Small to
 * Massive; nothing else about it changes, so a caster always knows what they are about to raise.
 *
 * <p>The generator lives in shared code on purpose. The server builds the same blocks the viewers do,
 * because it is the one that has to answer what a mob may walk through and see through. Nothing is
 * ever placed in the world: the server keeps the occupied columns in memory and every client draws
 * the courses itself from the seed.
 */
public final class IllusoryWall {
    private IllusoryWall() {}
    public record Placement(BlockPos offset,BlockState state) {}

    public static final int SIZES=4;
    private static final String[] NAMES={"Small","Medium","Big","Massive"};
    /** Odd widths so a wall is centred on the column the caster aimed at. */
    private static final int[] WIDTH={5,9,13,19},HEIGHT={3,4,6,8};

    public static int clamp(int size) {return Math.max(1,Math.min(SIZES,size));}
    public static String name(int size) {return NAMES[clamp(size)-1];}
    public static int width(int size) {return WIDTH[clamp(size)-1];}
    public static int height(int size) {return HEIGHT[clamp(size)-1];}
    /** Quarter turns only: a wall square to the world grid is a wall that reads as masonry. */
    public static int facing(float yaw) {return Math.floorMod(Math.round(yaw/90f),4);}

    /** The courses of one wall, as offsets from the block the caster aimed at. */
    public static List<Placement> build(int size,int seed,float yaw) {
        int scale=clamp(size),half=width(scale)/2,height=height(scale),turn=facing(yaw);
        RandomSource random=RandomSource.create(seed);
        List<Placement> out=new ArrayList<>((width(scale)+2)*(height+2));
        for(int x=-half;x<=half;x++) {
            // Buttresses at both ends and every fourth course, carried one block above the parapet so
            // the silhouette has a rhythm instead of reading as a slab stood on edge.
            boolean pillar=Math.abs(x)==half||Math.floorMod(x+half,4)==0;
            int top=pillar?height+1:height;
            for(int y=0;y<top;y++)add(out,x,y,turn,pillar&&y==top-1?Blocks.CHISELED_STONE_BRICKS.defaultBlockState():stone(random,y,height));
            // Battlements: a merlon, then a low course, alternating between the buttresses.
            if(!pillar)add(out,x,top,turn,Math.floorMod(x+half,2)==0?stone(random,top,height):slab());
        }
        return out;
    }

    /** Weathering follows the courses: moss gathers at the footing, cracks run under the parapet. */
    private static BlockState stone(RandomSource random,int y,int height) {
        int roll=random.nextInt(12);
        if(y<=0)return roll<6?Blocks.STONE_BRICKS.defaultBlockState():roll<10?Blocks.MOSSY_STONE_BRICKS.defaultBlockState():Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if(y>=height-2)return roll<7?Blocks.STONE_BRICKS.defaultBlockState():roll<11?Blocks.CRACKED_STONE_BRICKS.defaultBlockState():Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        return roll<9?Blocks.STONE_BRICKS.defaultBlockState():roll<11?Blocks.CRACKED_STONE_BRICKS.defaultBlockState():Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    }
    private static BlockState slab() {return Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM);}

    private static void add(List<Placement> out,int x,int y,int turn,BlockState state) {
        out.add(new Placement(rotate(x,y,turn),state));
    }
    /** The wall is generated running along X and then turned to stand across the caster's view. */
    private static BlockPos rotate(int x,int y,int turn) {
        return switch(turn) {
            case 1 -> new BlockPos(0,y,x);
            case 2 -> new BlockPos(-x,y,0);
            case 3 -> new BlockPos(0,y,-x);
            default -> new BlockPos(x,y,0);
        };
    }
}
