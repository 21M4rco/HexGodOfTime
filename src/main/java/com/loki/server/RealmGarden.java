package com.loki.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A towering branching canopy, buttress roots, root-tail underside and a carved, solid throne. */
final class RealmGarden {
    record Block(BlockPos offset,BlockState state) {}
    private final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
    private static final BlockState WOOD=Blocks.DARK_OAK_WOOD.defaultBlockState();
    private static final BlockState LEAVES=Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
    private static final BlockState FLOWERS=Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
    private static final BlockState STONE=Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();

    static List<Block> plan() {
        RealmGarden g=new RealmGarden();
        Vec3 base=new Vec3(50,1,39);
        for(int y=1;y<=66;y++) {
            double t=y/66.0;
            g.ball(new Vec3(50+Math.sin(t*5.4)*3.0,y,39+Math.cos(t*4)*2),6.3-t*4.7,WOOD);
        }
        for(int arm=0;arm<15;arm++) {
            double a=arm*Math.PI*2/15+.24;
            Vec3 start=base.add(Math.sin(arm)*1.5,27+(arm%5)*6,Math.cos(arm)*1.5);
            Vec3 end=base.add(Math.cos(a)*(33+arm%4*4),48+(arm%5)*5,Math.sin(a)*(33+arm%4*4));
            g.limb(start,end,3.1,1.0,arm,5);
            g.canopy(end,8.5,4.3,arm);
            for(int fork=0;fork<4;fork++) {
                double fa=a+(fork-1.5)*.48;
                Vec3 tip=end.add(Math.cos(fa)*(9+fork*2),6+(fork%3)*4,Math.sin(fa)*(9+fork*2));
                g.limb(end.lerp(start,.24),tip,1.4,.32,arm+fork,3);
                g.canopy(tip,6.0+fork*.5,3.4,arm*4+fork);
                for(int twig=0;twig<2;twig++) {
                    Vec3 leaf=tip.add(Math.cos(fa+twig-.5)*5,3+twig,Math.sin(fa+twig-.5)*5);
                    g.limb(tip,leaf,.48,.12,arm+twig,1);
                    g.canopy(leaf,3.5,2.5,arm+twig);
                }
                if(fork==1)g.hanging(tip,8+arm%7);
            }
        }
        g.canopy(new Vec3(48,72,38),11,5,107);
        g.canopy(new Vec3(53,78,40),7,4,110);
        // Roots cover most of the island and fork into the grass. Keep the arrival/throne aisle open.
        for(int i=0;i<19;i++) {
            double a=i*Math.PI*2/19;
            double length=38+(i%4)*8;
            Vec3 end=base.add(Math.cos(a)*length,0,Math.sin(a)*length);
            if(end.z>45&&Math.abs(end.x-50)<12)continue;
            end=new Vec3(end.x,RealmShape.surface((int)end.x,(int)end.z)+.3,end.z);
            g.limb(base.add(0,3,0),end,3.3,.5,i,.5);
            for(int fork:new int[]{-1,1}) {
                Vec3 start=base.lerp(end,.63);
                Vec3 tip=end.add(Math.cos(a+fork*.5)*11,0,Math.sin(a+fork*.5)*11);
                if(!RealmShape.contains((int)tip.x,(int)tip.z))continue;
                tip=new Vec3(tip.x,RealmShape.surface((int)tip.x,(int)tip.z)+.3,tip.z);
                g.limb(start,tip,1.3,.2,i+fork,.5);
            }
        }
        // Thick roots curl down over the ragged shore, making the underside part of the tree itself.
        for(int i=0;i<12;i++) {
            double a=i*Math.PI*2/12+.1,r=RealmShape.edge(a)-3;
            Vec3 lip=new Vec3(50+Math.cos(a)*r,2,50+Math.sin(a)*r/1.07);
            lip=new Vec3(lip.x,RealmShape.surface((int)lip.x,(int)lip.z)+.2,lip.z);
            Vec3 inner=base.lerp(lip,.62);
            inner=new Vec3(inner.x,RealmShape.surface((int)inner.x,(int)inner.z),inner.z);
            if(!(lip.z>50&&Math.abs(lip.x-50)<10))g.limb(inner,lip,1.45,.85,i,.4);
            Vec3 tip=lip.add(-Math.cos(a)*9,-14-i%4*3,-Math.sin(a)*9);
            g.limb(lip,tip,1.3,.15,i,-2);
        }
        // Sparse light veins emphasize the bark without replacing the trunk with a neon column.
        for(int y=4;y<63;y+=3) {
            double t=y/66.0,a=y*.19,r=6.3-t*4.7;
            g.put(new BlockPos((int)Math.round(50+Math.sin(t*5.4)*3+Math.cos(a)*r),y,
                (int)Math.round(39+Math.cos(t*4)*2+Math.sin(a)*r)),
                y%9==4?Blocks.VERDANT_FROGLIGHT.defaultBlockState():Blocks.MOSS_BLOCK.defaultBlockState());
        }
        g.flowers();g.throne();
        // Reserve the seat and approach even if a root or leaf would otherwise occupy them.
        for(int x=48;x<=52;x++)for(int z=51;z<=55;z++)for(int y=6;y<=8;y++)g.blocks.remove(new BlockPos(x,y,z));
        List<Block> result=new ArrayList<>();g.blocks.forEach((p,s)->result.add(new Block(p,s)));return List.copyOf(result);
    }
    private void throne() {
        // Fill every tier, including beneath the chair. The former single-row stairs left a hole.
        for(int tier=0;tier<3;tier++)for(int x=42+tier;x<=58-tier;x++)for(int z=46+tier;z<=59-tier;z++)
            put(new BlockPos(x,1+tier,z),(x==42+tier||x==58-tier||z==46+tier||z==59-tier)
                ?Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState():STONE);
        for(int tier=0;tier<3;tier++)for(int x=47;x<=53;x++)put(new BlockPos(x,1+tier,60-tier),
            Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        for(int x=47;x<=53;x++)for(int z=50;z<=55;z++)put(new BlockPos(x,4,z),STONE);
        for(int x=48;x<=52;x++)for(int z=51;z<=54;z++)put(new BlockPos(x,5,z),Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        // A double-thick back, carved frame, silver inlay and a recessed luminous emerald crest.
        for(int y=5;y<=14;y++)for(int x=46+(y>11?1:0);x<=54-(y>11?1:0);x++)for(int z=48;z<=49;z++) {
            boolean border=x==46||x==54||y==14;
            put(new BlockPos(x,y,z),border?Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState():STONE);
        }
        for(int y=7;y<=12;y++)put(new BlockPos(50,y,50),y==10?Blocks.VERDANT_FROGLIGHT.defaultBlockState():Blocks.EMERALD_BLOCK.defaultBlockState());
        for(int x:new int[]{47,53})for(int z=50;z<=55;z++) {
            for(int y=5;y<=6;y++)put(new BlockPos(x,y,z),Blocks.GILDED_BLACKSTONE.defaultBlockState());
            put(new BlockPos(x,7,z),Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState());
            if(z==55)put(new BlockPos(x,6,z),Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        }
        for(int x:new int[]{46,54})for(int y=8;y<=13;y++)put(new BlockPos(x,y,50),Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
        // Swept root-carved crown and twin horn finials surround the back, away from the usable seat.
        for(int sign:new int[]{-1,1}) {
            limb(new Vec3(50+sign*5,3,47),new Vec3(50+sign*7,15,47),1.0,.6,sign,0);
            limb(new Vec3(50+sign*7,15,47),new Vec3(50+sign*3,20,47),.6,.12,sign,1);
        }
        for(int x:new int[]{41,59}) {
            for(int y=1;y<5;y++)put(new BlockPos(x,y,57),Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            put(new BlockPos(x,5,57),Blocks.SOUL_LANTERN.defaultBlockState());
        }
    }
    private void flowers() {
        Random r=new Random(0xF10A3);
        for(int i=0;i<400;i++) {
            int x=-24+r.nextInt(148),z=-24+r.nextInt(148);
            if(!RealmShape.contains(x,z)||Math.hypot(x-50,z-39)<14||Math.abs(x-50)<7&&z>44&&z<78)continue;
            int y=RealmShape.surface(x,z)+1;
            BlockPos at=new BlockPos(x,y,z);
            if(blocks.containsKey(at))continue;
            var flower=switch(i%7) {
                case 0->Blocks.FLOWERING_AZALEA;case 1->Blocks.ALLIUM;case 2->Blocks.AZURE_BLUET;
                case 3->Blocks.BLUE_ORCHID;case 4->Blocks.LILY_OF_THE_VALLEY;default->Blocks.MOSS_CARPET;
            };
            put(at,flower.defaultBlockState());
        }
        // Hand-built luminous blossom groves; a low leaf rosette around a crystal flower.
        for(int i=0;i<9;i++) {
            double a=i*Math.PI*2/9;int x=50+(int)(Math.cos(a)*53),z=50+(int)(Math.sin(a)*49);
            int y=RealmShape.surface(x,z)+1;BlockPos c=new BlockPos(x,y,z);
            if(blocks.containsKey(c))continue;
            put(c,Blocks.VERDANT_FROGLIGHT.defaultBlockState());
            put(c.above(),Blocks.AMETHYST_CLUSTER.defaultBlockState());
            for(Direction dir:Direction.Plane.HORIZONTAL) {
                BlockPos petal=c.relative(dir);if(!blocks.containsKey(petal))put(petal,FLOWERS);
            }
        }
    }
    private void hanging(Vec3 tip,int length) {
        for(int y=2;y<=length;y++)put(BlockPos.containing(tip).below(y),y==length?Blocks.SEA_LANTERN.defaultBlockState():Blocks.CHAIN.defaultBlockState());
    }
    private void limb(Vec3 from,Vec3 to,double thick,double thin,int seed,double arch) {
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*2));
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;
            Vec3 at=from.lerp(to,t).add(Math.sin(t*Math.PI)*Math.sin(seed)*2.2,Math.sin(t*Math.PI)*arch,Math.sin(t*Math.PI)*Math.cos(seed)*1.8);
            ball(at,thick+(thin-thick)*t,WOOD);
        }
    }
    private void ball(Vec3 centre,double radius,BlockState state) {
        int r=(int)Math.ceil(radius);BlockPos c=BlockPos.containing(centre);
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)
            if(x*x+y*y+z*z<=radius*radius+.4)put(c.offset(x,y,z),state);
    }
    private void canopy(Vec3 centre,double radius,double height,int seed) {
        BlockPos c=BlockPos.containing(centre);int r=(int)Math.ceil(radius),h=(int)Math.ceil(height);
        for(int x=-r;x<=r;x++)for(int y=-h;y<=h;y++)for(int z=-r;z<=r;z++) {
            double shape=(x*x+z*z)/(radius*radius)+y*y/(height*height);
            int hash=Math.floorMod(x*7349+y*9151+z*3571+seed*101,43);
            if(shape>1||shape>.73&&hash<9)continue;
            BlockPos pos=c.offset(x,y,z);
            if(!blocks.containsKey(pos))put(pos,hash==0?Blocks.VERDANT_FROGLIGHT.defaultBlockState():hash<6?FLOWERS:LEAVES);
        }
    }
    private void put(BlockPos p,BlockState state) {
        if(p.getY()>-RealmShape.DEPTH&&p.getY()<150)blocks.put(p,state);
    }
}
