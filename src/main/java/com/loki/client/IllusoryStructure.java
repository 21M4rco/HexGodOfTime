package com.loki.client;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/**
 * Client-side geometry for Borrowed Reality. The server never places a block and never sends one: it
 * sends the design, a size and a seed, and each viewer builds the same shape locally. That keeps the
 * projection weightless on the server, free of collision, and cheap enough to show to chosen eyes only.
 */
public final class IllusoryStructure {
    public record Placement(BlockPos offset,BlockState state) {}
    public static final int RAMPART=0,GATEHOUSE=1,HALL=2,RUIN=3;
    private static final int MAX_BLOCKS=620;

    public static List<Placement> build(int design,int scale,int seed,float yaw) {
        List<Placement> local=new ArrayList<>();
        RandomSource random=RandomSource.create(seed);
        scale=Math.max(1,Math.min(4,scale));
        switch(Math.floorMod(design,4)) {
            case GATEHOUSE -> gatehouse(local,scale,random);
            case HALL -> hall(local,scale,random);
            case RUIN -> ruin(local,scale,random);
            default -> rampart(local,scale,random,true);
        }
        int rotation=Math.floorMod(Math.round(yaw/90f),4);
        List<Placement> out=new ArrayList<>(local.size());
        for(Placement p:local) {
            if(out.size()>=MAX_BLOCKS)break;
            out.add(new Placement(rotate(p.offset,rotation),p.state));
        }
        return out;
    }

    private static BlockPos rotate(BlockPos pos,int rotation) {
        int x=pos.getX(),z=pos.getZ();
        return switch(rotation) {
            case 1 -> new BlockPos(-z,pos.getY(),x);
            case 2 -> new BlockPos(-x,pos.getY(),-z);
            case 3 -> new BlockPos(z,pos.getY(),-x);
            default -> pos;
        };
    }

    /** Weathered masonry: the same palette everywhere so the designs read as one piece of architecture. */
    private static BlockState stone(RandomSource random) {
        int roll=random.nextInt(10);
        if(roll<6)return Blocks.STONE_BRICKS.defaultBlockState();
        if(roll<8)return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if(roll<9)return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        return Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    }
    private static BlockState slab() {return Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM);}
    private static void put(List<Placement> out,int x,int y,int z,BlockState state) {out.add(new Placement(new BlockPos(x,y,z),state));}

    private static void rampart(List<Placement> out,int scale,RandomSource random,boolean crenellate) {
        int half=3*scale,height=2+scale;
        for(int x=-half;x<=half;x++) {
            for(int y=0;y<height;y++)put(out,x,y,0,stone(random));
            if(!crenellate)continue;
            if(Math.floorMod(x+half,2)==0)put(out,x,height,0,stone(random));
            else put(out,x,height,0,slab());
        }
    }

    private static void gatehouse(List<Placement> out,int scale,RandomSource random) {
        int half=3*scale,height=2+scale;
        for(int x=-half;x<=half;x++) {
            boolean arch=Math.abs(x)<=scale;
            for(int y=0;y<height;y++) {
                if(arch&&y<height-1&&Math.abs(x)<=scale-(y>=height-2?1:0))continue;
                put(out,x,y,0,stone(random));
            }
            put(out,x,height,0,Math.floorMod(x+half,2)==0?stone(random):slab());
        }
        for(int side=-1;side<=1;side+=2) {
            int cx=side*(half+2);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
                if(x==0&&z==0)continue;
                for(int y=0;y<height+3;y++)put(out,cx+x,y,z,stone(random));
            }
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
                if(Math.abs(x)==2||Math.abs(z)==2)put(out,cx+x,height+3,z,slab());
        }
    }

    private static void hall(List<Placement> out,int scale,RandomSource random) {
        int width=1+scale,depth=2+scale,height=4+scale;
        for(int x=-width;x<=width;x++) {
            for(int z=-depth;z<=depth;z++) {
                boolean edge=Math.abs(x)==width||Math.abs(z)==depth;
                if(!edge)continue;
                for(int y=0;y<height;y++) {
                    boolean door=z==-depth&&Math.abs(x)<=0&&y<3;
                    boolean window=y==height-3&&Math.abs(x)%3==1&&Math.abs(z)!=depth;
                    if(door||window)continue;
                    put(out,x,y,z,stone(random));
                }
            }
        }
        // Gabled roof, stepped inward one course at a time.
        for(int step=0;step<=width;step++) {
            int y=height+step;
            for(int z=-depth-1;z<=depth+1;z++) {
                put(out,-width+step,y,z,step==width?stone(random):slab());
                if(step!=width)put(out,width-step,y,z,slab());
            }
        }
    }

    private static void ruin(List<Placement> out,int scale,RandomSource random) {
        int half=3*scale,height=2+scale;
        for(int x=-half;x<=half;x++) {
            int standing=Math.max(0,height-(int)(random.nextInt(height+1)*(Math.abs(x)/(float)half)));
            for(int y=0;y<standing;y++) {
                if(random.nextInt(9)==0)continue;
                put(out,x,y,0,stone(random));
            }
        }
        for(int pillar=-1;pillar<=1;pillar+=2) {
            int cx=pillar*(half-scale);
            int tall=height+1+random.nextInt(scale+1);
            for(int y=0;y<tall;y++)put(out,cx,y,3,y==tall-1?slab():stone(random));
        }
        for(int i=0;i<6*scale;i++) {
            int x=random.nextInt(half*2+1)-half,z=1+random.nextInt(4);
            put(out,x,0,z,random.nextBoolean()?slab():Blocks.COBBLESTONE.defaultBlockState());
        }
    }
}
