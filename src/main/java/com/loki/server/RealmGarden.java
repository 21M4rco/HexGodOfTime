package com.loki.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Swept boughs, a connected irregular crown, buried tapering roots and a usable carved throne. */
final class RealmGarden {
    record Block(BlockPos offset,BlockState state) {}
    private final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
    private static final BlockState WOOD=Blocks.DARK_OAK_WOOD.defaultBlockState();
    private static final BlockState LEAVES=Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
    private static final BlockState FLOWERS=Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
    private static final BlockState STONE=Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    private static final Vec3 BASE=new Vec3(50,1,36);

    static List<Block> plan() {
        RealmGarden g=new RealmGarden();
        g.curve(BASE,BASE.add(-7,26,5),BASE.add(8,60,-5),BASE.add(2,91,0),8.2,1.4);
        Random r=new Random(0x59474744);
        // Each leader sweeps out from the trunk and splits into a connected fan of smaller boughs.
        for(int arm=0;arm<11;arm++) {
            double a=arm*2.399963+.2,reach=42+r.nextDouble()*24;
            double height=76+r.nextDouble()*27;
            Vec3 start=BASE.add(1,31+arm%4*8,0);
            Vec3 end=BASE.add(Math.cos(a)*reach,height,Math.sin(a)*reach);
            Vec3 elbow=BASE.add(Math.cos(a-.24)*reach*.68,height-10,Math.sin(a-.24)*reach*.68);
            g.curve(start,start.add(Math.cos(a)*12,21,Math.sin(a)*12),elbow,end,4.1,1.0);
            for(int fork=0;fork<6;fork++) {
                double f=fork/5.0,fa=a+(f-.5)*1.65;
                Vec3 from=elbow.lerp(end,f*.75);
                double spread=13+r.nextDouble()*12;
                Vec3 tip=end.add(Math.cos(fa)*spread,3+r.nextDouble()*12,Math.sin(fa)*spread);
                g.curve(from,from.add(0,8,0),tip.add(-Math.cos(fa)*7,3,-Math.sin(fa)*7),tip,1.7,.24);
                g.crown(tip,8+r.nextDouble()*3,5+r.nextDouble()*3,arm*17+fork);
                g.crown(from.lerp(tip,.48).add(0,3,0),8,5.5,arm*31+fork);
                if(fork%2==0)g.drape(tip.add(Math.cos(fa)*5,-2,Math.sin(fa)*5),7+r.nextInt(9),arm+fork);
            }
            g.crown(elbow.add(0,4,0),11,7,arm+70);
        }
        // Upper leaders bridge the centre into a rounded, many-tipped crown rather than a stacked cone.
        for(int i=0;i<9;i++) {
            double a=i*2.399963,reach=10+i%3*8;
            Vec3 tip=BASE.add(Math.cos(a)*reach,112+i%3*5,Math.sin(a)*reach);
            g.curve(BASE.add(2,69,0),BASE.add(4,94,2),tip.add(0,-7,0),tip,2.6,.35);
            g.crown(tip,10+i%2*2,7,i+200);
        }
        g.roots();g.flowers();g.throne();
        // Actual chair clearance and an uninterrupted approach to the existing return sigil.
        for(int x=49;x<=51;x++)for(int z=51;z<=54;z++)for(int y=6;y<=8;y++)g.blocks.remove(new BlockPos(x,y,z));
        List<Block> result=new ArrayList<>(g.blocks.size());g.blocks.forEach((p,s)->result.add(new Block(p,s)));return List.copyOf(result);
    }

    private void roots() {
        Random r=new Random(0xB077);
        for(int i=0;i<10;i++) {
            double a=i*2.399963+.5,length=29+r.nextDouble()*38,bend=(i%2==0?1:-1)*(.35+r.nextDouble()*.45);
            Vec3 end=BASE.add(Math.cos(a+bend)*length,0,Math.sin(a+bend)*length);
            Vec3 c1=BASE.add(Math.cos(a)*length*.35,0,Math.sin(a)*length*.35);
            Vec3 c2=BASE.add(Math.cos(a+bend*1.6)*length*.72,0,Math.sin(a+bend*1.6)*length*.72);
            root(BASE,c1,c2,end,4.6,.25);
            for(int fork=0;fork<2;fork++) {
                double t=.48+fork*.22,fa=a+bend+(fork==0?-.7:.65);
                Vec3 from=bezier(BASE,c1,c2,end,t);
                Vec3 tip=from.add(Math.cos(fa)*(15+fork*7),0,Math.sin(fa)*(15+fork*7));
                root(from,from.add(Math.cos(fa-.4)*9,0,Math.sin(fa-.4)*9),tip.add(-3,0,2),tip,1.25,.13);
            }
        }
        // Roots visible below the island grow out of its core, then curl down and inward.
        for(int i=0;i<8;i++) {
            double a=i*2.399963,radius=64+i%3*9;
            Vec3 start=BASE.add(Math.cos(a)*23,-27,Math.sin(a)*23);
            Vec3 shoulder=BASE.add(Math.cos(a+.35)*radius,-17,Math.sin(a+.35)*radius);
            Vec3 tip=BASE.add(Math.cos(a+.65)*(radius-15),-52-i%3*2,Math.sin(a+.65)*(radius-15));
            curve(start,shoulder,shoulder.add(0,-23,0),tip,3.4,.16);
        }
    }
    private void root(Vec3 start,Vec3 a,Vec3 b,Vec3 end,double thick,double thin) {
        int steps=(int)(start.distanceTo(end)*3)+1;
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;Vec3 v=bezier(start,a,b,end,t);
            // Stay out of the throne aisle; most of each root is embedded in the soil.
            if(v.z>44&&v.z<82&&Math.abs(v.x-50)<9)continue;
            if(!RealmShape.contains((int)v.x,(int)v.z))continue;
            double rad=thin+(thick-thin)*Math.pow(1-t,2.2);
            double y=RealmShape.surface((int)v.x,(int)v.z)-.75+Math.pow(1-t,5)*3.5;
            ball(new Vec3(v.x,y,v.z),rad,WOOD);
        }
    }
    private void throne() {
        for(int tier=0;tier<3;tier++)for(int x=43+tier;x<=57-tier;x++)for(int z=46+tier;z<=59-tier;z++) {
            boolean edge=x==43+tier||x==57-tier||z==46+tier||z==59-tier;
            put(new BlockPos(x,1+tier,z),edge?Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState():STONE);
        }
        for(int tier=0;tier<3;tier++)for(int x=47;x<=53;x++)put(new BlockPos(x,1+tier,60-tier),
            Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        for(int x=47;x<=53;x++)for(int z=49;z<=55;z++)put(new BlockPos(x,4,z),STONE);
        for(int x=49;x<=51;x++)for(int z=51;z<=54;z++)put(new BlockPos(x,5,z),Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        // Narrow inset back, sculpted outline, dark seat and restrained gold trim.
        for(int y=5;y<=12;y++)for(int x=48;x<=52;x++) {
            if(y>10&&Math.abs(x-50)>12-y)continue;
            put(new BlockPos(x,y,50),x==48||x==52||y==12?Blocks.GILDED_BLACKSTONE.defaultBlockState():STONE);
            put(new BlockPos(x,y,49),STONE);
        }
        put(new BlockPos(50,10,51),Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        put(new BlockPos(50,11,51),Blocks.EMERALD_BLOCK.defaultBlockState());
        for(int x:new int[]{48,52})for(int z=51;z<=55;z++) {
            put(new BlockPos(x,5,z),STONE);
            put(new BlockPos(x,6,z),Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
            put(new BlockPos(x,7,z),Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState());
        }
        for(int side:new int[]{-1,1}) {
            curve(new Vec3(50+side*4,3,48),new Vec3(50+side*7,9,47),new Vec3(50+side*6,17,48),new Vec3(50+side*2,18,49),1.1,.2);
            for(int y=4;y<=8;y++)put(new BlockPos(50+side*4,y,55),Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            put(new BlockPos(50+side*4,9,55),Blocks.SOUL_LANTERN.defaultBlockState());
            for(int z=56;z<=65;z++)put(new BlockPos(50+side*4,RealmShape.surface(50+side*4,z),z),Blocks.GILDED_BLACKSTONE.defaultBlockState());
        }
    }
    private void flowers() {
        Random r=new Random(0xF10A3);
        for(int i=0;i<780;i++) {
            int x=RealmShape.MIN+r.nextInt(RealmShape.SIZE),z=RealmShape.MIN+r.nextInt(RealmShape.SIZE);
            if(!RealmShape.contains(x,z)||Math.hypot(x-50,z-36)<17||Math.abs(x-50)<9&&z>44&&z<82)continue;
            BlockPos at=new BlockPos(x,RealmShape.surface(x,z)+1,z);
            if(blocks.containsKey(at))continue;
            var flower=switch(i%8) {case 0->Blocks.FLOWERING_AZALEA;case 1->Blocks.ALLIUM;case 2->Blocks.AZURE_BLUET;
                case 3->Blocks.BLUE_ORCHID;case 4->Blocks.LILY_OF_THE_VALLEY;default->Blocks.MOSS_CARPET;};
            put(at,flower.defaultBlockState());
        }
        for(int i=0;i<15;i++) {
            double a=i*2.399963,radius=63+i%3*10;
            int x=50+(int)(Math.cos(a)*radius),z=50+(int)(Math.sin(a)*radius);
            BlockPos c=new BlockPos(x,RealmShape.surface(x,z)+1,z);
            if(!RealmShape.contains(x,z)||blocks.containsKey(c))continue;
            put(c,Blocks.VERDANT_FROGLIGHT.defaultBlockState());put(c.above(),Blocks.AMETHYST_CLUSTER.defaultBlockState());
            for(Direction d:Direction.Plane.HORIZONTAL)if(!blocks.containsKey(c.relative(d)))put(c.relative(d),FLOWERS);
        }
    }
    private void drape(Vec3 tip,int length,int seed) {
        for(int i=0;i<length;i++) {
            Vec3 p=tip.add(Math.sin(i*.34+seed)*1.2,-i,Math.cos(i*.27+seed)*.9);
            ball(p,i<length/3?1.6:.75,i%7==0?FLOWERS:LEAVES);
            if(i==length-2)put(BlockPos.containing(p),Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        }
    }
    private static Vec3 bezier(Vec3 p,Vec3 a,Vec3 b,Vec3 q,double t) {
        double s=1-t;return p.scale(s*s*s).add(a.scale(3*s*s*t)).add(b.scale(3*s*t*t)).add(q.scale(t*t*t));
    }
    private void curve(Vec3 from,Vec3 a,Vec3 b,Vec3 to,double thick,double thin) {
        int steps=(int)Math.ceil((from.distanceTo(a)+a.distanceTo(b)+b.distanceTo(to))*2);
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;ball(bezier(from,a,b,to,t),thin+(thick-thin)*Math.pow(1-t,1.35),WOOD);
        }
    }
    private void ball(Vec3 centre,double radius,BlockState state) {
        int r=(int)Math.ceil(radius);BlockPos c=BlockPos.containing(centre);
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)
            if(x*x+y*y+z*z<=radius*radius+.45)put(c.offset(x,y,z),state);
    }
    private void crown(Vec3 centre,double radius,double height,int seed) {
        BlockPos c=BlockPos.containing(centre);int r=(int)Math.ceil(radius+2),h=(int)Math.ceil(height+2);
        for(int x=-r;x<=r;x++)for(int y=-h;y<=h;y++)for(int z=-r;z<=r;z++) {
            double edge=1+.16*Math.sin(x*.6+seed)*Math.cos(z*.5-seed)+.12*Math.sin(y*.8+x*.3);
            double shape=(x*x+z*z)/(radius*radius)+y*y/(height*height);
            if(shape>edge)continue;
            int hash=Math.floorMod(x*7349+y*9151+z*3571+seed*101,173);
            if(shape>.78&&hash<15)continue;
            BlockPos pos=c.offset(x,y,z);
            if(!blocks.containsKey(pos))put(pos,hash==0&&shape<.8?Blocks.VERDANT_FROGLIGHT.defaultBlockState():hash<12?FLOWERS:LEAVES);
        }
    }
    private void put(BlockPos p,BlockState state) {
        if(p.getY()>-RealmShape.DEPTH&&p.getY()<150)blocks.put(p,state);
    }
}
