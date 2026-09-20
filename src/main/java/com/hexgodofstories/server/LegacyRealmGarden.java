package com.hexgodofstories.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Frozen v0.2.1 plan, used ONLY to recognize generated blocks during a non-destructive upgrade. */
final class LegacyRealmGarden {
    record Block(BlockPos offset,BlockState state) {}
    private final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
    private static final BlockState WOOD=Blocks.DARK_OAK_WOOD.defaultBlockState();
    private static final BlockState LEAVES=Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
    private static final BlockState FLOWERS=Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);

    static List<Block> plan() {
        LegacyRealmGarden g=new LegacyRealmGarden();
        Vec3 base=new Vec3(50,1,44);
        // The trunk spirals upward, splitting into sweeping limbs rather than a straight log pillar.
        for(int y=1;y<=33;y++) {
            double t=y/33.0;
            g.ball(new Vec3(50+Math.sin(t*4)*1.8,y,44+Math.cos(t*3)*1.1),3.7-t*2.5,WOOD);
        }
        for(int arm=0;arm<11;arm++) {
            double a=arm*Math.PI*2/11;
            Vec3 start=base.add(Math.sin(arm)*.9,16+(arm%4)*3,0);
            Vec3 end=base.add(Math.cos(a)*(19+arm%3*3),29+(arm%4)*3,Math.sin(a)*(19+arm%3*3));
            g.limb(start,end,2.0,.45,arm);
            g.canopy(end,5.6,2.5,arm);
            for(int fork=0;fork<3;fork++) {
                double fa=a+(fork-1)*.52;
                Vec3 tip=end.add(Math.cos(fa)*(6+fork),4+fork*2,Math.sin(fa)*(6+fork));
                g.limb(end.lerp(start,.2),tip,.85,.20,arm+fork);
                g.canopy(tip,4.3,2.1,arm+fork);
                if(fork==1)for(int d=1;d<=5+arm%5;d++)g.put(BlockPos.containing(tip).below(d),
                    d%4==0?Blocks.VERDANT_FROGLIGHT.defaultBlockState():Blocks.WARPED_FENCE.defaultBlockState());
            }
        }
        g.canopy(new Vec3(50,38,44),7,3,1);
        // Exposed roots spread through the garden but leave the south approach to the throne clear.
        for(int i=0;i<12;i++) {
            double a=i*Math.PI*2/12;
            Vec3 end=base.add(Math.cos(a)*22,-.4,Math.sin(a)*22);
            if(end.z>48&&Math.abs(end.x-50)<8)continue;
            g.limb(base.add(0,2,0),end,1.7,.35,i);
        }
        // Thin emerald veins climb the trunk and reach toward the crown.
        for(int y=2;y<31;y++) {
            double a=y*.26;
            g.put(new BlockPos((int)Math.round(50+Math.sin(y/33.0*4)*1.8+Math.cos(a)*(3.6-y*.07)),y,
                (int)Math.round(44+Math.cos(y/33.0*3)*1.1+Math.sin(a)*(3.6-y*.07))),
                y%6==0?Blocks.VERDANT_FROGLIGHT.defaultBlockState():Blocks.EMERALD_BLOCK.defaultBlockState());
        }
        g.throne();
        List<Block> result=new ArrayList<>();
        g.blocks.forEach((pos,state)->result.add(new Block(pos,state)));
        return List.copyOf(result);
    }
    private void throne() {
        // Three shallow tiers, front toward the arrival sigil (south).
        for(int tier=0;tier<3;tier++)for(int x=45+tier;x<=55-tier;x++)for(int z=47+tier;z<=56-tier;z++)
            put(new BlockPos(x,1+tier,z),(x==45+tier||x==55-tier||z==47+tier)
                ?Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState():Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        for(int tier=0;tier<3;tier++)for(int x=48;x<=52;x++)put(new BlockPos(x,1+tier,57-tier),
            Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        // Broad seat, gold armrests, tall tapered back and an emerald crest.
        for(int x=49;x<=51;x++)put(new BlockPos(x,4,52),Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        for(int y=4;y<=9;y++)for(int x=48+(y>7?1:0);x<=52-(y>7?1:0);x++)
            put(new BlockPos(x,y,50),x==50?Blocks.EMERALD_BLOCK.defaultBlockState():Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        for(int x:new int[]{48,52})for(int z=51;z<=53;z++) {
            put(new BlockPos(x,4,z),Blocks.GILDED_BLACKSTONE.defaultBlockState());
            put(new BlockPos(x,5,z),Blocks.GOLD_BLOCK.defaultBlockState());
        }
        put(new BlockPos(50,10,50),Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        // Root fan behind the seat connects it visually to the tree.
        for(int i=-4;i<=4;i++)limb(new Vec3(50,3,49),new Vec3(50+i*1.9,11+Math.abs(i),46),.60,.20,i);
        for(int x:new int[]{44,56}) {
            for(int y=1;y<4;y++)put(new BlockPos(x,y,56),Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            put(new BlockPos(x,4,56),Blocks.SOUL_LANTERN.defaultBlockState());
        }
    }
    private void limb(Vec3 from,Vec3 to,double thick,double thin,int seed) {
        int steps=(int)Math.ceil(from.distanceTo(to)*2);
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;
            Vec3 at=from.lerp(to,t).add(Math.sin(t*Math.PI)*Math.sin(seed)*1.8,Math.sin(t*Math.PI)*2.3,Math.sin(t*Math.PI)*Math.cos(seed)*1.5);
            ball(at,thick+(thin-thick)*t,WOOD);
        }
    }
    private void ball(Vec3 centre,double radius,BlockState state) {
        int r=(int)Math.ceil(radius);
        BlockPos c=BlockPos.containing(centre);
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)
            if(x*x+y*y+z*z<=radius*radius+.4)put(c.offset(x,y,z),state);
    }
    private void canopy(Vec3 centre,double radius,double height,int seed) {
        BlockPos c=BlockPos.containing(centre);int r=(int)Math.ceil(radius),h=(int)Math.ceil(height);
        for(int x=-r;x<=r;x++)for(int y=-h;y<=h;y++)for(int z=-r;z<=r;z++) {
            double shape=(x*x+z*z)/(radius*radius)+y*y/(height*height);
            int hash=Math.floorMod(x*7349+y*9151+z*3571+seed*101,37);
            if(shape>1||shape>.72&&hash<9)continue;
            BlockPos pos=c.offset(x,y,z);
            if(!blocks.containsKey(pos))put(pos,hash==0?Blocks.VERDANT_FROGLIGHT.defaultBlockState():hash<5?FLOWERS:LEAVES);
        }
    }
    private void put(BlockPos p,BlockState state){if(p.getY()>0)blocks.put(p,state);}
}
