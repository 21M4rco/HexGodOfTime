package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/** A playable confectionery kingdom: layered cake islands, a castle and lantern bridges. */
public final class ParadiseArchitecture {
    private ParadiseArchitecture() { }
    private static final Block[] SWEETS = {Blocks.PINK_CONCRETE, Blocks.MAGENTA_CONCRETE,
        Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE};

    public static void build(Map<BlockPos,BlockState> b) {
        Random random = new Random(0xCA57_1E);
        for (int i=0;i<Paradise.isles().size();i++) island(b,Paradise.isles().get(i),i,random);
        for (Paradise.Fall fall:Paradise.falls()) waterfall(b,fall);
        castle(b,0,Paradise.SURFACE+1,-12);
        cottage(b,-70,155,10,false);
        cottage(b,-77,155,1,false);
        cottage(b,70,159,12,true);
        tower(b,-54,175,-66,3,18);
        tower(b,56,179,-66,3,21);
        for (Paradise.Bridge bridge:Paradise.BRIDGES) bridge(b,bridge);
        // Paths avoid the lagoon and connect the front gate to the arrival meadow.
        path(b,-13,0,-13,29,Paradise.SURFACE);
        path(b,13,0,13,29,Paradise.SURFACE);
        path(b,-13,27,13,27,Paradise.SURFACE);
        path(b,-13,1,13,1,Paradise.SURFACE);
        for (int side:new int[]{-1,1}) {
            cane(b,side*18,161,23,8);
            lollipop(b,side*23,161,8,5,side<0?Blocks.PINK_CONCRETE:Blocks.MAGENTA_CONCRETE);
        }
        balloon(b,-95,211,52,7);
        balloon(b,93,216,60,8);
        balloon(b,12,233,-91,5);
        // The whole entry corridor stays empty, including the offset used by portal crossings.
        for(int x=-5;x<=5;x++)for(int z=24;z<=33;z++) {
            if(Paradise.inland(Paradise.heart(),x+.5,z+.5)<1)continue;
            put(b,x,Paradise.SURFACE,z,Blocks.SMOOTH_QUARTZ);
            for(int y=Paradise.SURFACE+1;y<=Paradise.SURFACE+32;y++) b.remove(new BlockPos(x,y,z));
        }
    }

    private static void island(Map<BlockPos,BlockState>b,Paradise.Isle isle,int index,Random random) {
        int cx=(int)Math.round(isle.x()),cz=(int)Math.round(isle.z()),top=(int)isle.y();
        int reach=(int)Math.ceil(isle.radius()*1.3)+2;
        List<BlockPos> meadow=new ArrayList<>();
        for(int dx=-reach;dx<=reach;dx++)for(int dz=-reach;dz<=reach;dz++) {
            int x=cx+dx,z=cz+dz;
            double inland=Paradise.inland(isle,x+.5,z+.5);
            if(inland<0)continue;
            int depth=Paradise.depth(isle,inland,isle.distance(x+.5,z+.5));
            for(int down=0;down<depth;down++) {
                int ripple=(int)Math.floor(2*Math.sin(x*.16)+Math.sin(z*.21));
                int layer=Math.floorMod(down+ripple,17);
                Block block=down==0?(inland<2?Blocks.PINK_TERRACOTTA:Blocks.GRASS_BLOCK)
                    :down<3?Blocks.PINK_TERRACOTTA
                    :layer<3?Blocks.WHITE_TERRACOTTA
                    :layer==3?Blocks.CALCITE
                    :layer<6?Blocks.PINK_TERRACOTTA
                    :(Math.floorMod(x*3+z*7+down,13)<3?Blocks.MUD_BRICKS:Blocks.BROWN_TERRACOTTA);
                put(b,x,top-down,z,block);
            }
            if(inland>3)meadow.add(new BlockPos(x,top,z));
        }
        if(index==0)pond(b,0,top,13,Paradise.SPRING_RADIUS);
        else if(index==3)pond(b,cx,top,cz,8);
        else if(index<6)pond(b,cx+8,top,cz+5,3.5);
        List<BlockPos> planted=new ArrayList<>();
        Collections.shuffle(meadow,random);
        for(BlockPos ground:meadow) {
            int x=ground.getX(),z=ground.getZ(),y=top+1;
            BlockState groundState=b.get(ground);
            if(groundState==null||!groundState.is(Blocks.GRASS_BLOCK)||reserved(index,x-cx,z-cz))continue;
            boolean spaced=planted.stream().noneMatch(p->p.distSqr(ground)<36);
            double roll=random.nextDouble();
            if(spaced&&roll<.11) {
                int kind=random.nextInt(7);
                if(kind<2)cherry(b,x,y,z,5+random.nextInt(3));
                else if(kind==2)cane(b,x,y,z,5+random.nextInt(4));
                else if(kind==3)lollipop(b,x,y,z,3,SWEETS[random.nextInt(SWEETS.length)]);
                else if(kind==4)gumdrop(b,x,y,z,SWEETS[random.nextInt(SWEETS.length)]);
                else put(b,x,y,z,kind==5?Blocks.FLOWERING_AZALEA:Blocks.AZALEA);
                planted.add(ground);
            } else if(!b.containsKey(ground.above())&&roll<.55) {
                put(b,x,y,z,switch(random.nextInt(14)) {
                    case 0,1,2->Blocks.PINK_TULIP;
                    case 3->Blocks.WHITE_TULIP;
                    case 4->Blocks.ALLIUM;
                    case 5->Blocks.LILY_OF_THE_VALLEY;
                    case 6->Blocks.OXEYE_DAISY;
                    case 7->Blocks.CORNFLOWER;
                    case 8->Blocks.AZURE_BLUET;
                    case 9,10->Blocks.PINK_PETALS;
                    case 11->Blocks.FLOWERING_AZALEA;
                    case 12->Blocks.MOSS_CARPET;
                    default->Blocks.GRASS;
                });
            }
        }
        flowerPatches(b,isle,index,random);
    }

    /** Dense little flower beds make every island read as a garden rather than bare turf. */
    private static void flowerPatches(Map<BlockPos,BlockState>b,Paradise.Isle isle,int index,Random random) {
        int cx=(int)Math.round(isle.x()),cz=(int)Math.round(isle.z()),top=(int)isle.y();
        int patches=Math.max(2,(int)Math.round(isle.radius()/8.0));
        Block[] flowers={Blocks.PINK_TULIP,Blocks.WHITE_TULIP,Blocks.ALLIUM,Blocks.LILY_OF_THE_VALLEY,
            Blocks.OXEYE_DAISY,Blocks.CORNFLOWER,Blocks.AZURE_BLUET,Blocks.FLOWERING_AZALEA};
        for(int patch=0;patch<patches;patch++) {
            double angle=random.nextDouble()*Math.PI*2;
            double distance=isle.radius()*(.28+random.nextDouble()*.38);
            int px=(int)Math.round(isle.x()+Math.cos(angle)*distance);
            int pz=(int)Math.round(isle.z()+Math.sin(angle)*distance);
            Block flower=flowers[random.nextInt(flowers.length)];
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
                if(dx*dx+dz*dz>5||random.nextDouble()<.28)continue;
                int x=px+dx,z=pz+dz;
                BlockPos ground=new BlockPos(x,top,z);
                BlockState state=b.get(ground);
                if(state==null||!state.is(Blocks.GRASS_BLOCK)||reserved(index,x-cx,z-cz)||b.containsKey(ground.above()))continue;
                put(b,x,top+1,z,(dx+dz+patch)%4==0?Blocks.PINK_PETALS:flower);
            }
        }
    }

    private static boolean reserved    private static boolean reserved(int isle,int x,int z) {
        if(isle==0)return (Math.abs(x)<20&&z<5)||(Math.abs(x)<8&&z>20)
            ||Math.abs(Math.abs(x)-13)<3||Math.abs(z-27)<3;
        if(isle==1||isle==2)return Math.abs(x)<15&&Math.abs(z)<12;
        if(isle==4||isle==5)return Math.abs(x)<7&&Math.abs(z)<7;
        return false;
    }
    private static void pond(Map<BlockPos,BlockState>b,int cx,int y,int cz,double radius) {
        int r=(int)Math.ceil(radius+2);
        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            double dist=Math.hypot(dx+.5,dz+.5);
            double rim=radius*(.91+.09*Math.sin(Math.atan2(dz+.5,dx+.5)*3+.8));
            if(dist>rim+1.3)continue;
            if(dist>rim){
                put(b,cx+dx,y,cz+dz,Blocks.SMOOTH_QUARTZ);
                if(Math.floorMod(dx*17+dz*31,11)==0)put(b,cx+dx,y+1,cz+dz,Blocks.PINK_PETALS);
                continue;
            }
            for(int d=0;d<Paradise.SPRING_DEPTH;d++)put(b,cx+dx,y-d,cz+dz,Blocks.WATER);
            put(b,cx+dx,y-Paradise.SPRING_DEPTH,cz+dz,(dx*dx+dz*dz)%5==0?Blocks.SEA_LANTERN:Blocks.PINK_CONCRETE);
            if(dist<rim-1.6&&Math.floorMod(dx*23+dz*19,17)==0)
                put(b,cx+dx,y+1,cz+dz,Blocks.LILY_PAD);
        }
    }
    private static void waterfall(Map<BlockPos,BlockState>b,Paradise.Fall f) {
        double ax=Math.cos(f.angle()),az=Math.sin(f.angle());
        int y=(int)f.y();
        // A broad, built spillway keeps every fall visibly attached to the island. The old version
        // started as a skinny source strip in open air, which is what produced detached purple
        // columns and little orphan blocks when the irregular rim changed under it.
        for(int side=-3;side<=3;side++)for(int back=-9;back<=0;back++) {
            int x=(int)Math.floor(f.x()+ax*back-az*side),z=(int)Math.floor(f.z()+az*back+ax*side);
            for(int clear=1;clear<=10;clear++)b.remove(new BlockPos(x,y+clear,z));
            if(Math.abs(side)==3) {
                put(b,x,y,z,Blocks.SMOOTH_QUARTZ);
                put(b,x,y-1,z,Blocks.PINK_CONCRETE);
            } else {
                put(b,x,y,z,Blocks.WATER);
                if(back<-1)put(b,x,y-1,z,Blocks.PINK_CONCRETE);
            }
        }
        // The curtain stays broad and continuous, then ends on a stagger rather than a perfectly
        // flat five-block cut. The client-side translucent continuation carries it into the clouds.
        for(int side=-2;side<=2;side++) {
            int trim=Math.abs(side)*4;
            int max=Math.max(16,f.length()-trim);
            for(int down=1;down<=max;down++) {
                int x=(int)Math.floor(f.x()-az*side),z=(int)Math.floor(f.z()+ax*side);
                put(b,x,y-down,z,Blocks.WATER);
            }
        }
    }
    private static void castle(Map<BlockPos,BlockState>b,int x,int y,int z) {
        // Hollow hall, two usable floors, open front doors and illuminated lancet windows.
        for(int dx=-9;dx<=9;dx++)for(int dz=-11;dz<=9;dz++)for(int dy=0;dy<=17;dy++) {
            boolean wall=Math.abs(dx)==9||dz==-11||dz==9;
            if(dy==0||dy==9||dy==17)put(b,x+dx,y+dy,z+dz,dy==0?Blocks.QUARTZ_BRICKS:Blocks.CHERRY_PLANKS);
            else if(wall) {
                boolean door=dz==9&&Math.abs(dx)<=2&&dy<=5;
                if(door){b.remove(new BlockPos(x+dx,y+dy,z+dz));continue;}
                boolean window=(dy>=4&&dy<=7||dy>=12&&dy<=15)&&(Math.floorMod(dx+dz,6)==0);
                put(b,x+dx,y+dy,z+dz,window?Blocks.OCHRE_FROGLIGHT:dy%9==1?Blocks.PINK_TERRACOTTA:Blocks.SMOOTH_QUARTZ);
            } else b.remove(new BlockPos(x+dx,y+dy,z+dz));
        }
        // A staircase along the west wall gives the upper floor a real entrance.
        for(int step=0;step<9;step++)for(int width=0;width<3;width++) {
            int sz=z+5-step;
            put(b,x-8+width,y+step+1,sz,Blocks.QUARTZ_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING,Direction.NORTH));
            for(int head=2;head<=4;head++)b.remove(new BlockPos(x-8+width,y+step+head,sz));
        }
        roof(b,x,y+18,z-1,12,20);
        for(int side:new int[]{-1,1}) {
            tower(b,x+side*13,y,z+7,4,25);
            tower(b,x+side*13,y,z-12,4,33);
        }
        tower(b,x,y+17,z-9,4,27);
        // Pink heart crest, framed in vanilla sugar and backed by warm light.
        String[] heart={".##.##.","#######","#######",".#####.","..###..","...#..."};
        for(int row=0;row<heart.length;row++)for(int col=0;col<7;col++) {
            if(heart[row].charAt(col)!='#')continue;
            for(int ox=-1;ox<=1;ox++)for(int oy=-1;oy<=1;oy++)put(b,x+col-3+ox,y+23-row+oy,z+11,Blocks.SMOOTH_QUARTZ);
        }
        for(int row=0;row<heart.length;row++)for(int col=0;col<7;col++)
            if(heart[row].charAt(col)=='#')put(b,x+col-3,y+23-row,z+12,Blocks.PINK_STAINED_GLASS);
        for(int dx=-3;dx<=3;dx++)for(int dy=18;dy<=23;dy++)put(b,x+dx,y+dy,z+10,Blocks.PEARLESCENT_FROGLIGHT);
        for(int sx:new int[]{-5,5})lamp(b,x+sx,y,z+12);
    }
    private static void tower(Map<BlockPos,BlockState>b,int x,int y,int z,int radius,int height) {
        for(int dy=0;dy<height;dy++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            double d=Math.hypot(dx,dz);
            if(d>radius+.25)continue;
            if(d>radius-1.2||dy==0||dy%8==0) {
                boolean window=dy%8>=3&&dy%8<=5&&(dx==0||dz==0);
                put(b,x+dx,y+dy,z+dz,window?Blocks.OCHRE_FROGLIGHT:dy%8==0?Blocks.PINK_TERRACOTTA:Blocks.SMOOTH_QUARTZ);
            } else b.remove(new BlockPos(x+dx,y+dy,z+dz));
        }
        roof(b,x,y+height,z,radius+2,12);
        for(int dy=0;dy<6;dy++)put(b,x,y+height+12+dy,z,Blocks.BAMBOO_BLOCK);
        for(int dx=1;dx<=4;dx++)for(int dy=0;dy<2;dy++)put(b,x+dx,y+height+16-dy,z,dy==0?Blocks.PINK_CONCRETE:Blocks.WHITE_CONCRETE);
    }
    private static void roof(Map<BlockPos,BlockState>b,int x,int y,int z,int radius,int height) {
        for(int dy=0;dy<height;dy++) {
            double r=radius*(1-dy/(double)height);
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
                if(dx*dx+dz*dz<=r*r)put(b,x+dx,y+dy,z+dz,dy%4==0?Blocks.PINK_CONCRETE:Blocks.MAGENTA_TERRACOTTA);
        }
        put(b,x,y+height,z,Blocks.GOLD_BLOCK);
    }
    private static void cottage(Map<BlockPos,BlockState>b,int x,int y,int z,boolean mill) {
        int height=mill?12:6;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=0;dy<=height;dy++) {
            boolean wall=Math.abs(dx)==4||Math.abs(dz)==4;
            if(dy==0)put(b,x+dx,y,z+dz,Blocks.SPRUCE_PLANKS);
            else if(wall) {
                if(dz==4&&Math.abs(dx)<=1&&dy<=3){b.remove(new BlockPos(x+dx,y+dy,z+dz));continue;}
                boolean window=(dy%6>=2&&dy%6<=3)&&(Math.abs(dx)==2||Math.abs(dz)==2);
                put(b,x+dx,y+dy,z+dz,window?Blocks.OCHRE_FROGLIGHT:dx%4==0||dz%4==0?Blocks.DARK_OAK_LOG:Blocks.MUD_BRICKS);
            } else b.remove(new BlockPos(x+dx,y+dy,z+dz));
        }
        for(int step=0;step<=5;step++)for(int dx=-5+step;dx<=5-step;dx++)for(int dz=-5;dz<=5;dz++)
            put(b,x+dx,y+height+step,z+dz,step%3==0?Blocks.WHITE_TERRACOTTA:Blocks.BROWN_TERRACOTTA);
        lamp(b,x-3,y,z+5);lamp(b,x+3,y,z+5);
        if(mill)for(int arm=0;arm<4;arm++)for(int length=1;length<=9;length++)for(int width=0;width<2;width++) {
            int dx=(arm==0||arm==3?1:-1)*length,dy=(arm<2?1:-1)*length;
            put(b,x+dx,y+height+dy,z+6+width,length%3==0?Blocks.WHITE_CONCRETE:Blocks.SPRUCE_PLANKS);
        }
    }
    private static void bridge(Map<BlockPos,BlockState>b,Paradise.Bridge link) {
        Paradise.Isle a=Paradise.isles().get(link.from()),c=Paradise.isles().get(link.to());
        var from=Paradise.bridgeEnd(a,c);var to=Paradise.bridgeEnd(c,a);
        double dx=to.x-from.x,dz=to.z-from.z,length=Math.hypot(dx,dz),sx=-dz/length,sz=dx/length;
        int steps=(int)Math.ceil(length*2);
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps,y=from.y+(to.y-from.y)*t-Math.sin(Math.PI*t)*Math.min(3,length*.05);
            int by=(int)Math.floor(y-.5);
            boolean upper=y-.5-by>=.5;
            BlockState deck=Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,upper?SlabType.TOP:SlabType.BOTTOM);
            for(int width=-2;width<=2;width++) {
                int x=(int)Math.floor(from.x+dx*t+sx*width),z=(int)Math.floor(from.z+dz*t+sz*width);
                b.put(new BlockPos(x,by,z),deck);
                for(int clear=1;clear<=4;clear++)b.remove(new BlockPos(x,by+clear,z));
                if(Math.abs(width)==2) {
                    // Solid timber curbs, never fence rails. They give the bridge a clean block
                    // silhouette like the reference and still leave the whole middle open.
                    put(b,x,by,z,Blocks.SPRUCE_PLANKS);
                    if(i%16==0) {
                        put(b,x,by-1,z,Blocks.CHAIN);
                        put(b,x,by-2,z,Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true));
                    }
                }
            }
        }
    }
    private static void path(Map<BlockPos,BlockState>b,int x,int z,int tx,int tz,int y) {
        int steps=Math.max(Math.abs(tx-x),Math.abs(tz-z));
        for(int i=0;i<=steps;i++)for(int w=-1;w<=1;w++) {
            int px=x+(tx-x)*i/Math.max(1,steps)+(z!=tz?w:0),pz=z+(tz-z)*i/Math.max(1,steps)+(x!=tx?w:0);
            put(b,px,y,pz,((px+pz)&3)==0?Blocks.PINK_TERRACOTTA:Blocks.SMOOTH_QUARTZ);
            for(int clear=1;clear<=3;clear++)b.remove(new BlockPos(px,y+clear,pz));
        }
    }
    private static void cherry(Map<BlockPos,BlockState>b,int x,int y,int z,int height) {
        for(int dy=0;dy<height;dy++)put(b,x,y+dy,z,Blocks.CHERRY_LOG);
        BlockState leaves=Blocks.CHERRY_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
        for(int branch=0;branch<3;branch++) {
            int bx=x+(branch==1?-3:branch==2?3:0),bz=z+(branch==0?1:-1),by=y+height-(branch==0?0:2);
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=-1;dy<=2;dy++)
                if(dx*dx+dz*dz+dy*dy*3<18)put(b,bx+dx,by+dy,bz+dz,leaves);
        }
    }
    private static void cane(Map<BlockPos,BlockState>b,int x,int y,int z,int height) {
        for(int dy=0;dy<height;dy++)put(b,x,y+dy,z,dy%2==0?Blocks.WHITE_CONCRETE:Blocks.RED_CONCRETE);
        for(int dx=0;dx<=3;dx++)put(b,x+dx,y+height,z,dx%2==0?Blocks.RED_CONCRETE:Blocks.WHITE_CONCRETE);
        put(b,x+3,y+height-1,z,Blocks.RED_CONCRETE);put(b,x+3,y+height-2,z,Blocks.WHITE_CONCRETE);
    }
    private static void lollipop(Map<BlockPos,BlockState>b,int x,int y,int z,int radius,Block color) {
        for(int dy=0;dy<radius+4;dy++)put(b,x,y+dy,z,Blocks.QUARTZ_PILLAR);
        int cy=y+radius+5;
        for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++) {
            double distance=Math.hypot(dx,dy);
            if(distance>radius+.2)continue;
            double swirl=distance*1.7-Math.atan2(dy,dx)*2;
            put(b,x+dx,cy+dy,z,Math.sin(swirl)>0?color:Blocks.WHITE_CONCRETE);
        }
    }
    private static void gumdrop(Map<BlockPos,BlockState>b,int x,int y,int z,Block color) {
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=0;dy<=3;dy++)
            if(dx*dx+dz*dz+dy*dy*.8<6)put(b,x+dx,y+dy,z+dz,Math.floorMod(dx*7+dz*3+dy,11)==0?Blocks.WHITE_CONCRETE:color);
    }
    private static void balloon(Map<BlockPos,BlockState>b,int x,int y,int z,int radius) {
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)for(int dy=-radius;dy<=radius;dy++) {
            if(dx*dx+dz*dz+dy*dy*.7>radius*radius)continue;
            put(b,x+dx,y+dy,z+dz,dy>radius/2?Blocks.BROWN_TERRACOTTA:dy>0?Blocks.WHITE_TERRACOTTA:Blocks.PINK_CONCRETE);
        }
        for(int dx:new int[]{-2,2})for(int dz:new int[]{-2,2})for(int dy=radius;dy<radius+7;dy++)put(b,x+dx,y-dy,z+dz,Blocks.CHAIN);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            put(b,x+dx,y-radius-7,z+dz,Blocks.SPRUCE_PLANKS);
            if(Math.abs(dx)==2||Math.abs(dz)==2)put(b,x+dx,y-radius-6,z+dz,Blocks.SPRUCE_FENCE);
        }
        put(b,x,y-radius-4,z,Blocks.SHROOMLIGHT);
    }
    private static void lamp(Map<BlockPos,BlockState>b,int x,int y,int z) {
        for(int dy=0;dy<3;dy++)put(b,x,y+dy,z,Blocks.DARK_OAK_FENCE);
        put(b,x,y+3,z,Blocks.LANTERN);
    }
    private static void put(Map<BlockPos,BlockState>b,int x,int y,int z,Block block) {put(b,x,y,z,block.defaultBlockState());}
    private static void put(Map<BlockPos,BlockState>b,int x,int y,int z,BlockState state) {b.put(new BlockPos(x,y,z),state);}
}
