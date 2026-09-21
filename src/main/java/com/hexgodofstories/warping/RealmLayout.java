package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** The same deterministic architecture is built by the server and drawn inside the aperture. */
public final class RealmLayout {
    public record Voxel(BlockPos pos,BlockState state){}
    private static final Map<Destination,List<Voxel>> CACHE=new EnumMap<>(Destination.class);
    public static List<Voxel> blocks(Destination d){return CACHE.computeIfAbsent(d,RealmLayout::create);}
    private static List<Voxel> create(Destination d){
        Map<BlockPos,BlockState> b=new LinkedHashMap<>();Random r=new Random(73019+d.ordinal());
        switch(d){
            case SUN -> {
                // A solid stellar interior and exposed lava shell; the renderer supplies the luminous photosphere.
                for(int x=-33;x<=33;x++)for(int y=-33;y<=33;y++)for(int z=-33;z<=33;z++){
                    double n=x*x+y*y+z*z;if(n>1089||n<28*28)continue;
                    put(b,x,94+y,z,n>31*31?Blocks.LAVA.defaultBlockState():Blocks.MAGMA_BLOCK.defaultBlockState());
                }
            }
            case VOID_SEA,GRAVITY_WELL,FALLING_WORLD -> {}
            case SHATTERED_WORLD -> {
                island(b,0,136,0,10,false,r);
                for(int i=0;i<18;i++){
                    double a=i*2.399;int x=(int)(Math.cos(a)*(24+i*3)),z=(int)(Math.sin(a)*(24+i*3));int y=90+r.nextInt(80);
                    island(b,x,y,z,6+r.nextInt(7),i%4==0,r);
                    if(i%3==0)ruin(b,x,y+1,z,6+i%4);
                    if(i%5==0)for(int j=1;j<22;j++)put(b,x+4,y-j,z,Blocks.WATER.defaultBlockState());
                }
            }
            case TIME_STORM -> {
                for(int i=0;i<8;i++){double a=i*Math.PI/4;island(b,(int)(Math.cos(a)*27),117+i%3*7,(int)(Math.sin(a)*27),6,false,r);}
                island(b,0,126,0,9,false,r);
                for(int i=0;i<7;i++)ruin(b,(i-3)*14,122,(i%2==0?1:-1)*30,14-i);
            }
            case FROZEN_MOMENT -> {
                island(b,0,126,0,19,false,r);
                for(int i=0;i<12;i++){double a=i*Math.PI/6;ruin(b,(int)(Math.cos(a)*20),126,(int)(Math.sin(a)*20),9+i%7);}
            }
            case CRUSHING_REALM -> {
                // Upper plane is an authoritative moving boundary, never thousands of block edits per tick.
                for(int x=-42;x<=42;x++)for(int z=-42;z<=42;z++)put(b,x,98,z,(Math.abs(x)%13==0||Math.abs(z)%13==0?Blocks.CRYING_OBSIDIAN:Blocks.POLISHED_BLACKSTONE).defaultBlockState());
            }
            case END_OF_TIME -> {
                island(b,0,126,0,12,false,r);
                for(int i=0;i<24;i++){
                    double a=i*2.399;int x=(int)(Math.cos(a)*(25+i*3)),z=(int)(Math.sin(a)*(25+i*3));
                    island(b,x,105+r.nextInt(36),z,4+r.nextInt(4),true,r);
                    if(i%3==0)ruin(b,x,131,z,7+r.nextInt(12));
                }
            }
        }
        return b.entrySet().stream().map(e->new Voxel(e.getKey(),e.getValue())).toList();
    }
    private static void island(Map<BlockPos,BlockState>b,int cx,int cy,int cz,int radius,boolean inverted,Random r){
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            double edge=radius-Math.sqrt(x*x+z*z);if(edge<r.nextDouble()*1.8)continue;
            int depth=1+(int)(edge*.7);for(int y=0;y<depth;y++)put(b,cx+x,cy+(inverted?y:-y),cz+z,(y==0&&!inverted?Blocks.GRASS_BLOCK:Blocks.DEEPSLATE).defaultBlockState());
        }
        if(!inverted)for(int i=0;i<3;i++){
            int x=cx+r.nextInt(radius)-radius/2,z=cz+r.nextInt(radius)-radius/2;
            for(int y=1;y<7;y++)put(b,x,cy+y,z,Blocks.DARK_OAK_LOG.defaultBlockState());
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=4;dy<=7;dy++)if(dx*dx+dz*dz+(dy-5)*(dy-5)<8)put(b,x+dx,cy+dy,z+dz,Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true));
        }
    }
    private static void ruin(Map<BlockPos,BlockState>b,int x,int y,int z,int height){
        for(int dy=0;dy<height;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            if((Math.abs(dx)==3||Math.abs(dz)==3)&&dx+dy<height-2&&(dy%5!=2||dx%3!=0))put(b,x+dx,y+dy,z+dz,(dy%5==0?Blocks.CHISELED_STONE_BRICKS:Blocks.CRACKED_STONE_BRICKS).defaultBlockState());
        }
    }
    private static void put(Map<BlockPos,BlockState>b,int x,int y,int z,BlockState state){b.put(new BlockPos(x,y,z),state);}
}
