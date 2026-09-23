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
                    if(x*x+z*z<100&&y>24)continue;
                    put(b,x,94+y,z,Blocks.MAGMA_BLOCK.defaultBlockState());
                }
                // The lava lies inside the photosphere, contained by the solid stellar shell.
                // An open pole lets captives fall into it without thousands of lava streams spilling into space.
                for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)if(x*x+z*z<81)
                    put(b,x,116,z,Blocks.LAVA.defaultBlockState());
            }
            case VOID_SEA -> {}
            case PARADISE -> paradise(b,r);
            // The singularity and moon use spatial meshes and radial physics, never block platforms.
            case GRAVITY_WELL,CRUSHING_REALM -> {}
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
            case END_OF_TIME -> {
                island(b,0,126,0,12,false,r);
                for(int i=0;i<24;i++){
                    double a=i*2.399;int x=(int)(Math.cos(a)*(25+i*3)),z=(int)(Math.sin(a)*(25+i*3));
                    island(b,x,105+r.nextInt(36),z,4+r.nextInt(4),true,r);
                    if(i%3==0)ruin(b,x,131,z,7+r.nextInt(12));
                }
            }
        }
        if(d==Destination.END_OF_TIME||d==Destination.TIME_STORM){
            b.entrySet().removeIf(e->e.getValue().is(Blocks.DARK_OAK_LEAVES));
            b.replaceAll((p,state)->state.is(Blocks.GRASS_BLOCK)?Blocks.COARSE_DIRT.defaultBlockState():state.is(Blocks.DARK_OAK_LOG)?Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState():state);
        }
        int landing=d==Destination.SHATTERED_WORLD?136:126;
        if(d==Destination.SHATTERED_WORLD||d==Destination.TIME_STORM||d==Destination.FROZEN_MOMENT||d==Destination.END_OF_TIME)
            b.entrySet().removeIf(e->Math.abs(e.getKey().getX())<=2&&Math.abs(e.getKey().getZ())<=2&&e.getKey().getY()>landing&&e.getKey().getY()<landing+16);
        return b.entrySet().stream().map(e->new Voxel(e.getKey(),e.getValue())).toList();
    }

    // ------------------------------------------------------------------ Paradise

    /**
     * Paradise, grown from the island table in {@link Paradise}.
     *
     * <p>Three passes per island, in an order that matters. The rock goes down first and every
     * column that ended in soil is written down. Then the water is cut into it — the hot spring in
     * the heart, a pond on most of the others — and the columns it took are struck off that list,
     * because a flower placed in the bottom of a lake is the kind of detail that makes a beautiful
     * place look unfinished. Only then is what is left planted, which is why nothing here is ever
     * standing in water it should not be, and why the polished stone around the spring stays
     * polished stone.
     *
     * <p>The cascades come after all of it, so the basin one lands in is cut through whatever the
     * island below had already grown there.
     */
    private static void paradise(Map<BlockPos,BlockState> b,Random r){
        List<Paradise.Isle> isles=Paradise.isles();
        for(int i=0;i<isles.size();i++)paradiseIsle(b,isles.get(i),i==0,r);
        for(Paradise.Fall fall:Paradise.falls())paradiseFall(b,fall,r);
    }

    /** Where the traveller arrives. Nothing tall is grown here, so nobody lands inside a tree. */
    private static boolean paradiseArrival(int x,int z){
        return Math.abs(x-(int)Paradise.ARRIVAL.x)<=5&&Math.abs(z-(int)Paradise.ARRIVAL.z)<=5;
    }

    private static void paradiseIsle(Map<BlockPos,BlockState>b,Paradise.Isle isle,boolean heart,Random r){
        int cx=(int)Math.round(isle.x()),cy=(int)Math.round(isle.y()),cz=(int)Math.round(isle.z());
        int reach=(int)Math.ceil(isle.radius()*1.3)+2;
        List<int[]> soil=new ArrayList<>();
        for(int dx=-reach;dx<=reach;dx++)for(int dz=-reach;dz<=reach;dz++){
            int x=cx+dx,z=cz+dz;
            double inland=Paradise.inland(isle,x+.5,z+.5);
            if(inland<0)continue;
            double distance=isle.distance(x+.5,z+.5);
            int depth=Paradise.depth(isle,inland,distance);
            // The last couple of blocks of rock roll off rather than ending in a wall, so the
            // island has a shoulder at the rim instead of the flat top and vertical cliff a
            // radius alone would give it.
            int top=cy-(int)Math.max(0,Math.min(2,2.4-inland));
            for(int y=top;y>top-depth;y--){
                int down=top-y;
                BlockState state;
                if(down==0){
                    // The rim is iced in alternating strawberry/vanilla stone. Farther in, the
                    // island stays a meadow so Paradise remains playable rather than becoming a
                    // solid concrete prop.
                    if(inland<1.35)state=(r.nextDouble()<.58?Blocks.PINK_TERRACOTTA:Blocks.SMOOTH_QUARTZ).defaultBlockState();
                    else if(inland<2.4&&r.nextDouble()<.45)state=Blocks.PINK_TERRACOTTA.defaultBlockState();
                    else state=(r.nextDouble()<.25?Blocks.MOSS_BLOCK:Blocks.GRASS_BLOCK).defaultBlockState();
                }else if(down<=2){
                    if(inland<2.1)state=(down==1?Blocks.WHITE_CONCRETE:Blocks.PINK_TERRACOTTA).defaultBlockState();
                    else state=(r.nextDouble()<.3?Blocks.ROOTED_DIRT:Blocks.DIRT).defaultBlockState();
                }else if(down>=depth-2)state=Blocks.CALCITE.defaultBlockState();
                else state=paradiseRock(r);
                put(b,x,y,z,state);
            }
            soil.add(new int[]{x,top,z});
        }
        if(heart)paradiseSpring(b,isle,soil,r);
        else if(r.nextDouble()<.62)paradisePond(b,isle,soil,r);
        paradisePlanting(b,isle,soil,r);
    }

    /**
     * The body of an island: pale stone with warm and luminous seams through it.
     *
     * <p>Kept light on purpose. These are the undersides you see from every other island, and a
     * dark rock under a bright meadow reads as two different places stuck together.
     */
    private static BlockState paradiseRock(Random r){
        double roll=r.nextDouble();
        return (roll<.28?Blocks.CALCITE:roll<.46?Blocks.DIORITE:roll<.60?Blocks.PINK_TERRACOTTA
            :roll<.72?Blocks.SMOOTH_QUARTZ:roll<.82?Blocks.TUFF
            :roll<.93?Blocks.AMETHYST_BLOCK:Blocks.PEARLESCENT_FROGLIGHT).defaultBlockState();
    }

    /**
     * The hot spring, cut into the middle of the heart.
     *
     * <p>Its outline is uneven for the same reason the islands are, it stands three blocks deep,
     * and its floor is lit from underneath so the water glows rather than merely being blue. A
     * course of polished stone is laid around the waterline, which is what keeps it reading as a
     * spring rather than as a hole that filled up.
     */
    private static void paradiseSpring(Map<BlockPos,BlockState>b,Paradise.Isle isle,List<int[]> soil,Random r){
        for(Iterator<int[]> it=soil.iterator();it.hasNext();){
            int[] column=it.next();
            double dx=column[0]+.5-isle.x(),dz=column[2]+.5-isle.z();
            double distance=Math.hypot(dx,dz),rim=Paradise.springRim(Math.atan2(dz,dx));
            if(distance<=rim){
                int top=column[1];
                for(int y=top;y>top-Paradise.SPRING_DEPTH;y--)put(b,column[0],y,column[2],Blocks.WATER.defaultBlockState());
                double floor=r.nextDouble();
                put(b,column[0],top-Paradise.SPRING_DEPTH,column[2],
                    (floor<.30?Blocks.SEA_LANTERN:floor<.55?Blocks.PRISMARINE_BRICKS:floor<.8?Blocks.PRISMARINE:Blocks.SMOOTH_QUARTZ).defaultBlockState());
                it.remove();
            }else if(distance<=rim+1.8){
                // Left in the planting list deliberately: a course of polished stone that nothing
                // at all grows on reads as masonry, and this is a spring, not a fountain.
                put(b,column[0],column[1],column[2],(r.nextDouble()<.45?Blocks.CALCITE:Blocks.SMOOTH_QUARTZ).defaultBlockState());
            }
        }
    }

    /** A small pond somewhere on an outer island, never close enough to the rim to breach it. */
    private static void paradisePond(Map<BlockPos,BlockState>b,Paradise.Isle isle,List<int[]> soil,Random r){
        double radius=2+r.nextDouble()*1.4,angle=r.nextDouble()*Math.PI*2,out=r.nextDouble()*isle.radius()*.28;
        double px=isle.x()+Math.cos(angle)*out,pz=isle.z()+Math.sin(angle)*out;
        // Far enough inside the rim that the pool is a pool rather than a notch cut out of the edge.
        if(Paradise.inland(isle,px,pz)<radius+1.8)return;
        for(Iterator<int[]> it=soil.iterator();it.hasNext();){
            int[] column=it.next();
            double distance=Math.hypot(column[0]+.5-px,column[2]+.5-pz);
            if(distance>radius)continue;
            int top=column[1];
            put(b,column[0],top,column[2],Blocks.WATER.defaultBlockState());
            put(b,column[0],top-1,column[2],Blocks.WATER.defaultBlockState());
            put(b,column[0],top-2,column[2],(r.nextDouble()<.3?Blocks.SEA_LANTERN:Blocks.SMOOTH_QUARTZ).defaultBlockState());
            it.remove();
        }
    }

    /**
     * Everything growing on an island, in one pass over the columns the water did not take.
     *
     * <p>One roll per column decides what it carries, and the three things large enough to compose
     * with — a cherry, a crystal formation, a piece of confectionery — also have to stand clear of
     * anything else that size. That spacing is the whole difference between a designed meadow and
     * a scatter: without it the low rolls clump, and a beautiful place turns into a cluttered one.
     */
    private static void paradisePlanting(Map<BlockPos,BlockState>b,Paradise.Isle isle,List<int[]> soil,Random r){
        List<int[]> standing=new ArrayList<>();
        for(int[] column:soil){
            int x=column[0],top=column[1],z=column[2],y=top+1;
            if(paradiseArrival(x,z))continue;
            BlockState ground=b.get(new BlockPos(x,top,z));
            boolean grass=ground!=null&&(ground.is(Blocks.GRASS_BLOCK)||ground.is(Blocks.MOSS_BLOCK));
            double inland=Paradise.inland(isle,x+.5,z+.5),roll=r.nextDouble();
            if(grass&&inland>3.5&&roll<.030&&paradiseClear(standing,x,z,7)){paradiseCherry(b,x,y,z,r);standing.add(new int[]{x,z});continue;}
            if(inland>3&&roll<.042&&paradiseClear(standing,x,z,9)){paradiseCrystals(b,x,y,z,r);standing.add(new int[]{x,z});continue;}
            if(grass&&inland>4&&roll<.078&&paradiseClear(standing,x,z,11)){paradiseCandy(b,x,y,z,r);standing.add(new int[]{x,z});continue;}
            if(!grass){
                // Polished stone keeps a little moss and the occasional crystal, and nothing else.
                if(roll<.10)put(b,x,y,z,Blocks.AMETHYST_CLUSTER.defaultBlockState());
                continue;
            }
            if(roll<.30)put(b,x,y,z,(r.nextDouble()<.2?Blocks.FERN:Blocks.GRASS).defaultBlockState());
            else if(roll<.40)put(b,x,y,z,paradiseFlower(r));
            else if(roll<.47)put(b,x,y,z,Blocks.PINK_PETALS.defaultBlockState());
            else if(roll<.50)put(b,x,y,z,(r.nextDouble()<.5?Blocks.FLOWERING_AZALEA:Blocks.AZALEA).defaultBlockState());
            else if(roll<.515)put(b,x,y,z,Blocks.AMETHYST_CLUSTER.defaultBlockState());
        }
    }

    private static BlockState paradiseFlower(Random r){
        double roll=r.nextDouble();
        return (roll<.30?Blocks.PINK_TULIP:roll<.50?Blocks.ALLIUM:roll<.66?Blocks.LILY_OF_THE_VALLEY
            :roll<.80?Blocks.CORNFLOWER:roll<.92?Blocks.AZURE_BLUET:Blocks.OXEYE_DAISY).defaultBlockState();
    }

    private static boolean paradiseClear(List<int[]> standing,int x,int z,int spacing){
        for(int[] taken:standing)if(Math.abs(taken[0]-x)<spacing&&Math.abs(taken[1]-z)<spacing)return false;
        return true;
    }

    /** A cherry in blossom, occasionally lit from inside by a single froglight in its crown. */
    private static void paradiseCherry(Map<BlockPos,BlockState>b,int x,int y,int z,Random r){
        int height=4+r.nextInt(3);
        for(int i=0;i<height;i++)put(b,x,y+i,z,Blocks.CHERRY_LOG.defaultBlockState());
        BlockState leaves=Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true);
        int crown=y+height-1;
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-1;dy<=2;dy++){
            double shape=dx*dx+dz*dz*1.0+(dy-.5)*(dy-.5)*2.6;
            if(shape>9.5||r.nextDouble()<.12)continue;
            put(b,x+dx,crown+dy,z+dz,leaves);
        }
        if(r.nextDouble()<.35)put(b,x,crown+1,z,Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
        for(int i=0;i<3;i++)if(r.nextDouble()<.5)
            put(b,x+r.nextInt(3)-1,crown-2,z+r.nextInt(3)-1,leaves);
    }

    /** Amethyst spires standing out of a calcite base, the way they grow in a geode. */
    private static void paradiseCrystals(Map<BlockPos,BlockState>b,int x,int y,int z,Random r){
        int spires=2+r.nextInt(3);
        for(int i=0;i<spires;i++){
            int ox=x+r.nextInt(5)-2,oz=z+r.nextInt(5)-2,height=2+r.nextInt(4);
            for(int dy=0;dy<height;dy++)put(b,ox,y+dy,oz,Blocks.AMETHYST_BLOCK.defaultBlockState());
            put(b,ox,y+height,oz,Blocks.AMETHYST_CLUSTER.defaultBlockState());
        }
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            if(dx*dx+dz*dz<=4&&r.nextDouble()<.45&&!b.containsKey(new BlockPos(x+dx,y,z+dz)))
                put(b,x+dx,y,z+dz,Blocks.CALCITE.defaultBlockState());
    }

    /**
     * A piece of confectionery grown out of the ground, at the scale of a small tree.
     *
     * <p>Sparse and deliberate. Paradise is a beautiful natural place with magic in it rather than
     * a landscape made of sweets, so these stand alone in a meadow the way a sculpture would, and
     * the ground they stand on is still grass.
     */
    private static void paradiseCandy(Map<BlockPos,BlockState>b,int x,int y,int z,Random r){
        int kind=r.nextInt(6);
        if(kind==0){
            // Candy cane.
            int height=5+r.nextInt(3);
            for(int i=0;i<height;i++)put(b,x,y+i,z,(i%2==0?Blocks.WHITE_CONCRETE:Blocks.RED_CONCRETE).defaultBlockState());
            int dir=r.nextBoolean()?1:-1;
            put(b,x+dir,y+height,z,Blocks.RED_CONCRETE.defaultBlockState());
            put(b,x,y+height,z,Blocks.WHITE_CONCRETE.defaultBlockState());
            put(b,x+dir*2,y+height,z,Blocks.WHITE_CONCRETE.defaultBlockState());
            put(b,x+dir*2,y+height-1,z,Blocks.RED_CONCRETE.defaultBlockState());
        }else if(kind==1){
            // Lollipop.
            int height=4+r.nextInt(3);
            for(int i=0;i<height;i++)put(b,x,y+i,z,Blocks.QUARTZ_PILLAR.defaultBlockState());
            BlockState swirl=(r.nextBoolean()?Blocks.PINK_GLAZED_TERRACOTTA:Blocks.MAGENTA_GLAZED_TERRACOTTA).defaultBlockState();
            for(int dx=-1;dx<=1;dx++)for(int dy=0;dy<=2;dy++){
                if(dx*dx+(dy-1)*(dy-1)>2)continue;
                put(b,x+dx,y+height+dy,z,swirl);
            }
        }else if(kind==2){
            // Gumdrop.
            BlockState skin=switch(r.nextInt(4)){
                case 0 -> Blocks.PINK_CONCRETE.defaultBlockState();
                case 1 -> Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
                case 2 -> Blocks.YELLOW_CONCRETE.defaultBlockState();
                default -> Blocks.MAGENTA_CONCRETE.defaultBlockState();
            };
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=0;dy<=2;dy++){
                if(dx*dx+dz*dz+dy*dy*1.7>5.2)continue;
                put(b,x+dx,y+dy,z+dz,skin);
            }
            put(b,x,y+3,z,Blocks.WHITE_CONCRETE.defaultBlockState());
        }else if(kind==3){
            // Cupcake: chocolate wrapper, frosting dome, cherry.
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
                if(Math.abs(dx)+Math.abs(dz)<3)put(b,x+dx,y,z+dz,Blocks.BROWN_CONCRETE.defaultBlockState());
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=1;dy<=3;dy++){
                if(dx*dx+dz*dz+(dy-1.5)*(dy-1.5)*2.2>5.8)continue;
                put(b,x+dx,y+dy,z+dz,(dy==1?Blocks.PINK_CONCRETE:Blocks.WHITE_CONCRETE).defaultBlockState());
            }
            put(b,x,y+4,z,Blocks.RED_CONCRETE.defaultBlockState());
        }else if(kind==4){
            // Macaron sandwich standing upright.
            BlockState shell=(r.nextBoolean()?Blocks.PINK_CONCRETE:Blocks.LIGHT_BLUE_CONCRETE).defaultBlockState();
            for(int dx=-2;dx<=2;dx++)for(int dy=0;dy<=4;dy++){
                if(dx*dx+(dy-2)*(dy-2)>5)continue;
                BlockState layer=dy==2?Blocks.WHITE_CONCRETE.defaultBlockState():shell;
                put(b,x+dx,y+dy,z,layer);
                if(Math.abs(dx)<=1)put(b,x+dx,y+dy,z+1,layer);
            }
        }else{
            // Chocolate bar with strawberry icing squares.
            for(int dx=-2;dx<=2;dx++)for(int dy=0;dy<=4;dy++)
                put(b,x+dx,y+dy,z,Blocks.BROWN_CONCRETE.defaultBlockState());
            for(int dx=-1;dx<=1;dx++)for(int dy=1;dy<=3;dy++)
                if((dx+dy&1)==0)put(b,x+dx,y+dy,z+1,Blocks.PINK_CONCRETE.defaultBlockState());
        }
    }

    /**
     * One cascade: a spout in the rim, the column of water leaving it, and the basin it lands in.
     *
     * <p>The water is placed as still source blocks with neighbour updates suppressed, exactly as
     * every other block in every realm is. That is what makes a hundred-block waterfall cost
     * nothing: it is a standing column that renders and swims like water and never schedules a
     * fluid tick, rather than a live flow spreading a puddle across the bottom of the world.
     */
    private static void paradiseFall(Map<BlockPos,BlockState>b,Paradise.Fall fall,Random r){
        BlockState water=Blocks.WATER.defaultBlockState();
        int x=net.minecraft.util.Mth.floor(fall.x()),z=net.minecraft.util.Mth.floor(fall.z()),y=(int)Math.round(fall.y());
        double ax=Math.cos(fall.angle()),az=Math.sin(fall.angle());
        int px=(int)Math.round(-az),pz=(int)Math.round(ax);
        // The spout: the block the water leaves through, cut back into the rim so it reads as
        // coming out of the island rather than as appearing beside it.
        put(b,net.minecraft.util.Mth.floor(fall.x()-ax),y,net.minecraft.util.Mth.floor(fall.z()-az),water);
        put(b,x,y,z,water);
        int sheet=6+r.nextInt(4);
        for(int i=1;i<=fall.length();i++){
            put(b,x,y-i,z,water);
            // A second strand at the head, so the fall starts as a sheet and narrows as it drops.
            if(i<=sheet&&(px!=0||pz!=0))put(b,x+px,y-i,z+pz,water);
        }
        if(fall.onto()<0)return;
        Paradise.Isle shelf=Paradise.isles().get(fall.onto());
        int sy=(int)Math.round(shelf.y());
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(dx*dx+dz*dz>5)continue;
            int bx=x+dx,bz=z+dz;
            if(Paradise.inland(shelf,bx+.5,bz+.5)<2.5)continue;
            put(b,bx,sy,bz,water);
            put(b,bx,sy-1,bz,water);
            put(b,bx,sy-2,bz,(r.nextDouble()<.35?Blocks.SEA_LANTERN:Blocks.SMOOTH_QUARTZ).defaultBlockState());
        }
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
