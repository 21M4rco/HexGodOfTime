package com.loki.server;

import com.loki.Loki;
import com.loki.data.LokiData;
import com.loki.network.LokiNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Every Loki gets a persistent, irregular floating island within their own 512-block-spaced plot.
 * Plots are allocated once and remembered; the sanctum floor around the arrival point is laid immediately
 * so nobody lands in the void, and the remainder is built out over the following ticks.
 */
public final class PocketRealm {
    public static final ResourceKey<Level> KEY=ResourceKey.create(Registries.DIMENSION,Loki.id("pocket"));
    public static final int SIZE=100,SPACING=512,FLOOR_Y=64,WALL=18,COLUMNS=4*SIZE-4;
    private static final int SURFACE_CELLS=RealmShape.SIZE*RealmShape.SIZE;
    private static final int DEEP_CELLS=SURFACE_CELLS;
    private static List<RealmGarden.Block> garden;
    private static List<RealmGarden.Block> oldGarden;
    private static List<RealmGarden.Block> garden(){if(garden==null)garden=RealmGarden.plan();return garden;}
    private static List<RealmGarden.Block> oldGarden(){
        if(oldGarden==null){
            List<RealmGarden.Block> all=new ArrayList<>();
            for(var b:LegacyRealmGarden.plan())all.add(new RealmGarden.Block(b.offset(),b.state()));
            for(var b:LegacyRealmGardenV3.plan())all.add(new RealmGarden.Block(b.offset(),b.state()));
            oldGarden=List.copyOf(all);
        }
        return oldGarden;
    }
    private static final int IMMEDIATE=10,BUDGET=2600,UPDATE_CLIENTS=2;
    private static final long BUILD_NANOS=4_000_000L;
    private static final List<int[]> PENDING=new ArrayList<>();
    private static final Map<UUID,Integer> KNEELING=new HashMap<>();
    private static final int KNEEL_TICKS=60;

    /** Persisted plot ledger. Lives in the pocket dimension's own data storage. */
    public static final class Realms extends SavedData {
        private final Map<UUID,Integer> plots=new HashMap<>();
        private final Set<Integer> built=new HashSet<>(),gardens=new HashSet<>();
        private final Map<Integer,Integer> progress=new HashMap<>();
        private int next;
        public static Realms load(CompoundTag n) {
            Realms r=new Realms();
            r.next=n.getInt("next");
            ListTag list=n.getList("plots",Tag.TAG_COMPOUND);
            for(int i=0;i<list.size();i++){CompoundTag e=list.getCompound(i);if(e.hasUUID("id"))r.plots.put(e.getUUID("id"),e.getInt("plot"));}
            for(int v:n.getIntArray("built"))r.built.add(v);
            for(int v:n.getIntArray("gardens_v4"))r.gardens.add(v);
            ListTag pending=n.getList("garden_progress_v4",Tag.TAG_COMPOUND);
            for(int i=0;i<pending.size();i++){CompoundTag e=pending.getCompound(i);r.progress.put(e.getInt("plot"),e.getInt("index"));}
            return r;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putInt("next",next);
            ListTag list=new ListTag();
            plots.forEach((id,plot)->{CompoundTag e=new CompoundTag();e.putUUID("id",id);e.putInt("plot",plot);list.add(e);});
            n.put("plots",list);
            int[] done=new int[built.size()];int i=0;for(int v:built)done[i++]=v;
            n.putIntArray("built",done);
            n.putIntArray("gardens_v4",gardens.stream().mapToInt(Integer::intValue).toArray());
            ListTag pending=new ListTag();
            progress.forEach((plot,index)->{CompoundTag e=new CompoundTag();e.putInt("plot",plot);e.putInt("index",index);pending.add(e);});
            n.put("garden_progress_v4",pending);
            return n;
        }
        int plot(UUID id) {
            Integer existing=plots.get(id);
            if(existing!=null)return existing;
            int plot=next++;plots.put(id,plot);setDirty();return plot;
        }
        boolean needsBuild(int plot){return !gardens.contains(plot);}
        void checkpoint(int plot,int index,boolean finished) {
            if(finished){gardens.add(plot);built.add(plot);progress.remove(plot);}
            else progress.put(plot,index);
            setDirty();
        }
    }

    private static Realms realms(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Realms::load,Realms::new,"loki_realms");
    }
    public static BlockPos origin(int plot) {return new BlockPos(plot%64*SPACING,FLOOR_Y,plot/64*SPACING);}
    public static Vec3 centre(int plot) {BlockPos o=origin(plot);return new Vec3(o.getX()+SIZE/2.0,FLOOR_Y+1,o.getZ()+72.0);}

    public static boolean inside(Level level) {return KEY.equals(level.dimension());}

    /** The plot whose island a world position falls on, recentred so the shape's negative side counts. */
    public static int plotAt(double x,double z) {
        int px=Math.floorDiv(net.minecraft.util.Mth.floor(x)-RealmShape.MIN,SPACING);
        int pz=Math.floorDiv(net.minecraft.util.Mth.floor(z)-RealmShape.MIN,SPACING);
        return pz*64+px;
    }
    /** True when this player is standing on the island that was allotted to them. */
    public static boolean ownsHere(ServerPlayer p) {
        if(!inside(p.level()))return false;
        CompoundTag d=LokiData.get(p);
        int mine=d.contains("realmPlot",Tag.TAG_INT)?d.getInt("realmPlot"):realms(p.serverLevel()).plot(p.getUUID());
        return plotAt(p.getX(),p.getZ())==mine;
    }
    /** Local island coordinate for a world coordinate, on the same recentred grid the terrain uses. */
    public static int local(double world) {
        return Math.floorMod(net.minecraft.util.Mth.floor(world)-RealmShape.MIN,SPACING)+RealmShape.MIN;
    }
    /** Comfortably inside the island's coast, with a margin so nothing is left teetering on the lip. */
    public static boolean onIsland(double x,double z) {
        return RealmShape.fraction(local(x),local(z))<=.95;
    }
    public static boolean crossingCooldown(ServerPlayer p) {
        // Homeward travel is immediate. After returning, outlive every old entry rift so standing
        // on the saved point cannot pull the player straight back into the realm.
        return !inside(p.level())&&LokiData.get(p).getLong("riftGraceUntil")>p.server.overworld().getGameTime();
    }

    public static ServerLevel level(MinecraftServer server) {return server.getLevel(KEY);}

    /** @return true when the caster was actually moved, so the rift only reports success on a real crossing. */
    public static boolean enter(ServerPlayer p) {
        ServerLevel realm=level(p.server);
        if(realm==null){p.displayClientMessage(Component.literal("The sanctum will not open; its dimension is missing."),true);return false;}
        if(inside(p.level()))return false;
        CompoundTag d=LokiData.get(p);
        // Deliberately stored outside the transient block so a dimension change cannot erase the way home.
        d.putString("returnDim",p.level().dimension().location().toString());
        d.putDouble("returnX",p.getX());d.putDouble("returnY",p.getY());d.putDouble("returnZ",p.getZ());
        d.putFloat("returnYaw",p.getYRot());d.putFloat("returnPitch",p.getXRot());
        int plot=realms(realm).plot(p.getUUID());
        d.putInt("realmPlot",plot);
        prepare(realm,plot);
        Vec3 spawn=centre(plot);
        if(!move(p,realm,spawn,180,0))return false;
        // Arriving in the sanctum is quiet: a small bloom of nebula where the body reforms, and no
        // second break hanging in the air beside it.
        LokiNetwork.arrival(p);
        p.displayClientMessage(Component.literal("Your world tree. Fracture to leave, or crouch on the arrival sigil."),true);
        return true;
    }

    public static boolean leave(ServerPlayer p) {
        if(!inside(p.level()))return false;
        CompoundTag d=LokiData.get(p);
        ResourceLocation id=ResourceLocation.tryParse(d.getString("returnDim"));
        ServerLevel destination=id==null?null:p.server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
        boolean saved=destination!=null&&!inside(destination)&&d.contains("returnX",Tag.TAG_ANY_NUMERIC)
            &&d.contains("returnY",Tag.TAG_ANY_NUMERIC)&&d.contains("returnZ",Tag.TAG_ANY_NUMERIC);
        if(destination==null||inside(destination))destination=p.server.overworld();
        Vec3 home=saved?new Vec3(d.getDouble("returnX"),d.getDouble("returnY"),d.getDouble("returnZ"))
            :Vec3.atBottomCenterOf(destination.getSharedSpawnPos());
        if(!Double.isFinite(home.x)||!Double.isFinite(home.y)||!Double.isFinite(home.z))
            home=Vec3.atBottomCenterOf(destination.getSharedSpawnPos());
        // Keep the exact return point whenever it is still clear; search nearby if somebody blocked it.
        home=safeReturn(p,destination,home);
        if(home==null) {
            BlockPos spawn=destination.getSharedSpawnPos();
            destination.getChunk(spawn.getX()>>4,spawn.getZ()>>4);
            int y=destination.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,spawn.getX(),spawn.getZ());
            home=safeReturn(p,destination,new Vec3(spawn.getX()+.5,y,spawn.getZ()+.5));
            p.displayClientMessage(Component.literal("Your return point was blocked; opening at world spawn."),true);
        }
        if(home==null)return false;
        if(!move(p,destination,home,d.getFloat("returnYaw"),d.getFloat("returnPitch")))return false;
        d.remove("realmPlot");
        LokiNetwork.arrival(p);
        return true;
    }

    private static int occupiedPlot(ServerPlayer p) {
        CompoundTag d=LokiData.get(p);
        return d.contains("realmPlot",Tag.TAG_INT)?d.getInt("realmPlot"):realms(p.serverLevel()).plot(p.getUUID());
    }

    private static CompoundTag travelData(net.minecraft.world.entity.Entity e) {
        if(e instanceof ServerPlayer p)return LokiData.get(p);
        CompoundTag parent=e.getPersistentData();
        if(!parent.contains("LokiFractureReturn"))parent.put("LokiFractureReturn",new CompoundTag());
        return parent.getCompound("LokiFractureReturn");
    }
    private static void remember(net.minecraft.world.entity.Entity e,CompoundTag d) {
        d.putString("returnDim",e.level().dimension().location().toString());
        d.putDouble("returnX",e.getX());d.putDouble("returnY",e.getY());d.putDouble("returnZ",e.getZ());
        d.putFloat("returnYaw",e.getYRot());d.putFloat("returnPitch",e.getXRot());
    }

    /**
     * Everything crossing a break obeys that break's destination.
     *
     * <p>Inbound, that is the caster's own plot. Outbound, it is the anchor the owner's saved
     * Fracture mode resolved to when the break was struck — <em>not</em> the travelling entity's
     * memory of where it was originally seized. A creature dragged into the sanctum in one place and
     * released in another therefore surfaces beside its captor, which is the whole point of being
     * the one who owns the door.
     *
     * @param spread index within a travelling group, so a crowd fans out around the arrival instead
     *               of piling into one column
     */
    public static boolean cross(net.minecraft.world.entity.Entity e,ServerPlayer caster) {return cross(e,caster,null,0);}
    public static boolean cross(net.minecraft.world.entity.Entity e,ServerPlayer caster,FractureAnchor anchor,int spread) {
        if(!e.isAlive()||e.isRemoved()||e.isSpectator())return false;
        boolean homeward=inside(e.level());
        CompoundTag d=travelData(e);
        ServerLevel destination;Vec3 target;float yaw=e.getYRot(),pitch=e.getXRot();
        int plot=-1;
        if(homeward||anchor!=null) {
            FractureAnchor exit=anchor!=null?anchor:FractureTravel.exit(caster);
            if(exit==null)return false;
            destination=exit.level(caster.server);
            if(destination==null||inside(destination))destination=caster.server.overworld();
            target=exit.at();
            if(e instanceof ServerPlayer)yaw=exit.yaw();
            if(e instanceof ServerPlayer)pitch=exit.pitch();
        } else {
            destination=level(caster.server);if(destination==null)return false;
            plot=realms(destination).plot(caster.getUUID());prepare(destination,plot);
            target=centre(plot);remember(e,d);
            yaw=180;pitch=0;
        }
        Vec3 at=safeArrival(e,destination,scatter(target,spread));
        if(at==null)at=safeArrival(e,destination,target);
        if(at==null)return false;
        if(e instanceof ServerPlayer p) {
            if(!homeward&&anchor==null)d.putInt("realmPlot",plot);
            if(!move(p,destination,at,yaw,pitch))return false;
            if(homeward||anchor!=null)d.remove("realmPlot");
            LokiNetwork.arrival(p);return true;
        }
        e.stopRiding();e.ejectPassengers();
        Vec3 landing=at;
        // Forge creates the destination copy through its normal lifecycle, retaining mod entity data.
        net.minecraft.world.entity.Entity moved=e.changeDimension(destination,new net.minecraftforge.common.util.ITeleporter() {
            @Override public net.minecraft.world.level.portal.PortalInfo getPortalInfo(net.minecraft.world.entity.Entity entity,ServerLevel dest,
                    java.util.function.Function<ServerLevel,net.minecraft.world.level.portal.PortalInfo> fallback) {
                return new net.minecraft.world.level.portal.PortalInfo(landing,Vec3.ZERO,entity.getYRot(),entity.getXRot());
            }
            @Override public net.minecraft.world.entity.Entity placeEntity(net.minecraft.world.entity.Entity entity,ServerLevel from,ServerLevel dest,float yaw,
                    java.util.function.Function<Boolean,net.minecraft.world.entity.Entity> reposition) {
                var copy=reposition.apply(false);
                if(copy!=null){copy.moveTo(landing.x,landing.y,landing.z,entity.getYRot(),entity.getXRot());copy.setDeltaMovement(Vec3.ZERO);copy.resetFallDistance();}
                return copy;
            }
        });
        if(moved==null)return false;
        travelData(moved).putLong("riftGraceUntil",caster.server.overworld().getGameTime()+com.loki.entity.RiftEntity.DURATION+20);
        LokiNetwork.arrival(moved);
        return true;
    }

    /** Fans a travelling group around the arrival point on a golden-angle spiral. */
    private static Vec3 scatter(Vec3 target,int index) {
        if(index<=0)return target;
        double angle=index*2.399963229728653;
        double radius=1.4+Math.min(4,index*.55);
        return target.add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
    }

    public static boolean crossingCooldown(net.minecraft.world.entity.Entity e) {
        if(e instanceof ServerPlayer p)return crossingCooldown(p);
        return !inside(e.level())&&travelData(e).getLong("riftGraceUntil")>((ServerLevel)e.level()).getServer().overworld().getGameTime();
    }
    private static Vec3 safeArrival(net.minecraft.world.entity.Entity e,ServerLevel destination,Vec3 target) {
        for(int ring=0;ring<=6;ring++)for(int x=-ring;x<=ring;x++)for(int z=-ring;z<=ring;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))!=ring)continue;
            for(int y=0;y<=10;y++) {
                Vec3 at=target.add(x,y,z);BlockPos pos=BlockPos.containing(at);
                if(at.y<destination.getMinBuildHeight()||at.y+e.getBbHeight()>=destination.getMaxBuildHeight()
                        ||!destination.getWorldBorder().isWithinBounds(pos))continue;
                destination.getChunk(pos.getX()>>4,pos.getZ()>>4);
                var box=e.getBoundingBox().move(at.subtract(e.position()));
                if(destination.noCollision(e,box)&&!destination.containsAnyLiquid(box))return at;
            }
        }
        return null;
    }

    private static Vec3 safeReturn(ServerPlayer p,ServerLevel destination,Vec3 target) {
        // Bounded search, nearest horizontal column first. Load before testing collision.
        for(int ring=0;ring<=4;ring++)for(int x=-ring;x<=ring;x++)for(int z=-ring;z<=ring;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))!=ring)continue;
            for(int y=0;y<=8;y++) {
                Vec3 at=target.add(x,y,z);
                BlockPos pos=BlockPos.containing(at);
                if(at.y<destination.getMinBuildHeight()||at.y+2>=destination.getMaxBuildHeight()
                    ||!destination.getWorldBorder().isWithinBounds(pos))continue;
                destination.getChunk(pos.getX()>>4,pos.getZ()>>4);
                var box=p.getBoundingBox().move(at.subtract(p.position()));
                if(destination.noCollision(p,box)&&!destination.containsAnyLiquid(box))return at;
            }
        }
        return null;
    }

    private static boolean move(ServerPlayer p,ServerLevel destination,Vec3 at,float yaw,float pitch) {
        p.stopRiding();
        destination.getChunk(BlockPos.containing(at).getX()>>4,BlockPos.containing(at).getZ()>>4);
        // ServerPlayer's explicit transfer synchronizes the connection and destination chunk together.
        // The old teleporter seeded PortalInfo with the source position, then relocated a second time.
        p.teleportTo(destination,at.x,at.y,at.z,yaw,pitch);
        if(p.serverLevel()!=destination||p.position().distanceToSqr(at)>.25) {
            p.displayClientMessage(Component.literal("The crossing was blocked; your return point is still saved."),true);
            return false;
        }
        p.setDeltaMovement(Vec3.ZERO);p.resetFallDistance();
        LokiData.get(p).putLong("riftGraceUntil",p.server.overworld().getGameTime()+com.loki.entity.RiftEntity.DURATION+20);
        return true;
    }

    /** Persisted progress only completes after the final block. A restart resumes unfinished gardens. */
    private static void prepare(ServerLevel realm,int plot) {
        Realms ledger=realms(realm);
        if(!ledger.needsBuild(plot)||PENDING.stream().anyMatch(job->job[0]==plot))return;
        BlockPos o=origin(plot);
        for(int x=SIZE/2-IMMEDIATE;x<=SIZE/2+IMMEDIATE;x++)
            for(int z=72-IMMEDIATE;z<=72+IMMEDIATE;z++) {
                replaceTerrain(realm,o.offset(x,-1,z),Blocks.ROOTED_DIRT.defaultBlockState(),previousTerrain(x,-1,z));
                replaceTerrain(realm,o.offset(x,0,z),floor(x,z),previousTerrain(x,0,z));
            }
        PENDING.add(new int[]{plot,ledger.progress.getOrDefault(plot,0)});
    }

    public static void tick(ServerLevel level) {
        if(!inside(level))return;
        Starfall.tick(level);
        Throne.guard(level);
        for(ServerPlayer player:level.players())prepare(level,occupiedPlot(player));
        if(!PENDING.isEmpty()) {
            int[] job=PENDING.get(0);
            BlockPos o=origin(job[0]);
            int placed=0;
            int total=SURFACE_CELLS+oldGarden().size()+garden().size()+DEEP_CELLS+COLUMNS*WALL;
            long deadline=System.nanoTime()+BUILD_NANOS;
            while(job[1]<total&&placed<BUDGET&&(placed==0||System.nanoTime()<deadline)) {
                place(level,o,job[1]++);placed++;
            }
            boolean finished=job[1]>=total;
            realms(level).checkpoint(job[0],job[1],finished);
            if(finished)PENDING.remove(0);
        }
        for(ServerPlayer p:new ArrayList<>(level.players())) {
            Vec3 middle=centre(occupiedPlot(p));
            // Kneeling on the gilded centre always sends you home, with no spell and no cooldown,
            // so nobody can be stranded here by losing an ability or forgetting the way out.
            if(p.isCrouching()&&p.distanceToSqr(middle)<9) {
                int held=KNEELING.merge(p.getUUID(),1,Integer::sum);
                if(held==KNEEL_TICKS/2)p.displayClientMessage(Component.literal("Hold still, and the way home will open."),true);
                if(held>=KNEEL_TICKS){KNEELING.remove(p.getUUID());leave(p);continue;}
            } else KNEELING.remove(p.getUUID());
            if(level.getGameTime()%20!=0)continue;
            if(p.isSpectator()||p.isCreative()||p.getY()>FLOOR_Y-24)continue;
            p.teleportTo(middle.x,middle.y,middle.z);
            p.setDeltaMovement(Vec3.ZERO);p.resetFallDistance();
        }
    }

    private static void place(ServerLevel level,BlockPos o,int index) {
        if(index<oldGarden().size()) {
            RealmGarden.Block old=oldGarden().get(index);
            BlockPos pos=o.offset(old.offset());
            // Only exact generated v2 states can be removed. Other blocks, chests and builds survive.
            if(level.getBlockState(pos).equals(old.state()))level.setBlock(pos,Blocks.AIR.defaultBlockState(),UPDATE_CLIENTS);
            return;
        }
        index-=oldGarden().size();
        if(index<SURFACE_CELLS) {
            int x=index%RealmShape.SIZE+RealmShape.MIN,z=index/RealmShape.SIZE+RealmShape.MIN;
            boolean land=RealmShape.contains(x,z);int top=RealmShape.surface(x,z);
            for(int y=-1;y<=RealmShape.TOP;y++) {
                BlockState state=!land||y>top?Blocks.AIR.defaultBlockState():y==top?floor(x,z):
                    (y==top-1?Blocks.ROOTED_DIRT.defaultBlockState():Blocks.STONE.defaultBlockState());
                replaceTerrain(level,o.offset(x,y,z),state,previousTerrain(x,y,z));
            }
            return;
        }
        index-=SURFACE_CELLS;
        if(index<garden().size()) {
            RealmGarden.Block block=garden().get(index);BlockPos pos=o.offset(block.offset());
            BlockState current=level.getBlockState(pos);
            BlockPos local=block.offset();
            // Root volume can replace this version's generated soil, but never block entities.
            if(level.getBlockEntity(pos)==null&&(current.isAir()||current.equals(terrain(local.getX(),local.getY(),local.getZ()))))
                level.setBlock(pos,block.state(),UPDATE_CLIENTS);
            return;
        }
        index-=garden().size();
        if(index<DEEP_CELLS) {
            int x=index%RealmShape.SIZE+RealmShape.MIN,z=index/RealmShape.SIZE+RealmShape.MIN;
            int bottom=RealmShape.contains(x,z)?RealmShape.depth(x,z):1;
            int oldBottom=LegacyRealmShapeV3.contains(x,z)?LegacyRealmShapeV3.depth(x,z):1;
            for(int depth=2;depth<=Math.max(bottom,oldBottom);depth++)
                replaceTerrain(level,o.offset(x,-depth,z),terrain(x,-depth,z),previousTerrain(x,-depth,z));
            return;
        }
        // Remove the exact original 100x100 v1 wall, not the new island boundary.
        int i=index-DEEP_CELLS,column=i/WALL,height=i%WALL;
        int x,z,along;
        if(column<SIZE){x=column;z=0;along=x;}
        else if(column<SIZE*2){x=column-SIZE;z=SIZE-1;along=x;}
        else if(column<SIZE*2+SIZE-2){x=0;z=column-SIZE*2+1;along=z;}
        else {x=SIZE-1;z=column-(SIZE*2+SIZE-2)+1;along=z;}
        BlockPos pos=o.offset(x,height+1,z);
        if(level.getBlockState(pos).equals(wall(along,height)))level.setBlock(pos,Blocks.AIR.defaultBlockState(),UPDATE_CLIENTS);
    }
    private static BlockState terrain(int x,int y,int z) {
        if(!RealmShape.contains(x,z)||y>RealmShape.surface(x,z)||y< -RealmShape.depth(x,z))return Blocks.AIR.defaultBlockState();
        int top=RealmShape.surface(x,z);
        if(y==top)return floor(x,z);
        if(y==top-1)return Blocks.ROOTED_DIRT.defaultBlockState();
        int depth=-y,grain=Math.floorMod(x*23+z*41+depth*17,31);
        return (depth<7?Blocks.STONE:grain<4?Blocks.TUFF:grain<8?Blocks.BASALT:Blocks.DEEPSLATE).defaultBlockState();
    }
    private static BlockState terrainV3(int x,int y,int z) {
        if(!LegacyRealmShapeV3.contains(x,z)||y>LegacyRealmShapeV3.surface(x,z)||y< -LegacyRealmShapeV3.depth(x,z))return Blocks.AIR.defaultBlockState();
        int top=LegacyRealmShapeV3.surface(x,z);
        if(y==top) {
            double sigil=Math.hypot(x-50,z-72);
            if(sigil<2)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
            if(sigil<3.3)return Blocks.EMERALD_BLOCK.defaultBlockState();
            if(Math.abs(x-50)<3&&z>55&&z<72)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
            if(LegacyRealmShapeV3.fraction(x,z)>.94)return (Math.floorMod(x*19+z*37,5)==0?Blocks.MOSS_BLOCK:Blocks.TUFF).defaultBlockState();
            return (Math.floorMod(x*73+z*37,13)<3?Blocks.ROOTED_DIRT:Blocks.MOSS_BLOCK).defaultBlockState();
        }
        if(y>=-1)return (y==top-1?Blocks.ROOTED_DIRT:Blocks.STONE).defaultBlockState();
        int depth=-y,grain=Math.floorMod(x*23+z*41+depth*17,31);
        return (depth<7?Blocks.STONE:grain<4?Blocks.TUFF:grain<8?Blocks.BASALT:Blocks.DEEPSLATE).defaultBlockState();
    }
    private static BlockState previousTerrain(int x,int y,int z) {
        if(x<0||x>=100||z<0||z>=100||y>0||y< -8)return Blocks.AIR.defaultBlockState();
        int depth=-y;
        double radius=Math.hypot(x-49.5,z-49.5);
        double edge=47-depth*2.5+Math.sin(x*.25)*.65+Math.cos(z*.24)*.65;
        if(radius>edge)return Blocks.AIR.defaultBlockState();
        return depth==0?oldFloor(x,z):depth==1?Blocks.POLISHED_BLACKSTONE.defaultBlockState():Blocks.DEEPSLATE.defaultBlockState();
    }
    private static void replaceTerrain(ServerLevel level,BlockPos pos,BlockState state,BlockState previous) {
        BlockState current=level.getBlockState(pos);
        if(level.getBlockEntity(pos)!=null)return;
        int x=Math.floorMod(pos.getX(),SPACING),z=Math.floorMod(pos.getZ(),SPACING);
        boolean legacy=x<SIZE&&z<SIZE&&(pos.getY()==FLOOR_Y&&current.equals(legacyFloor(x,z))
            ||pos.getY()==FLOOR_Y-1&&current.is(Blocks.POLISHED_BLACKSTONE));
        int localX=Math.floorMod(pos.getX()-RealmShape.MIN,SPACING)+RealmShape.MIN;
        int localZ=Math.floorMod(pos.getZ()-RealmShape.MIN,SPACING)+RealmShape.MIN;
        if((current.isAir()||current.equals(previous)||current.equals(terrainV3(localX,pos.getY()-FLOOR_Y,localZ))||legacy)&&!current.equals(state))level.setBlock(pos,state,UPDATE_CLIENTS);
    }
    private static BlockState floor(int x,int z) {
        double sigil=Math.hypot(x-50,z-72);
        if(sigil<2)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
        if(sigil<3.3)return Blocks.EMERALD_BLOCK.defaultBlockState();
        if(Math.abs(x-50)<3&&z>55&&z<72)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        double edge=RealmShape.fraction(x,z);
        if(edge>.94)return (Math.floorMod(x*19+z*37,5)==0?Blocks.MOSS_BLOCK:Blocks.TUFF).defaultBlockState();
        if(Math.sin(x*.18)+Math.cos(z*.16)+Math.sin((x+z)*.09)>2.2)return Blocks.ROOTED_DIRT.defaultBlockState();
        return Blocks.MOSS_BLOCK.defaultBlockState();
    }
    private static BlockState oldFloor(int x,int z) {
        double radius=Math.hypot(x-50,z-50),sigil=Math.hypot(x-50,z-72);
        if(sigil<2)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
        if(sigil<3.3)return Blocks.EMERALD_BLOCK.defaultBlockState();
        if(Math.abs(x-50)<3&&z>51&&z<72)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        if(radius<10)return Blocks.MOSS_BLOCK.defaultBlockState();
        if(radius>44)return Blocks.DEEPSLATE_TILES.defaultBlockState();
        if(x%13==0&&z%13==0)return Blocks.VERDANT_FROGLIGHT.defaultBlockState();
        if(Math.floorMod(x*73+z*37,11)<3)return Blocks.ROOTED_DIRT.defaultBlockState();
        return Blocks.MOSS_BLOCK.defaultBlockState();
    }
    private static BlockState legacyFloor(int x,int z) {
        double dx=x-(SIZE-1)/2.0,dz=z-(SIZE-1)/2.0,r=Math.sqrt(dx*dx+dz*dz);
        if(r<7)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
        if(r<8.6)return Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        if(x%10==0&&z%10==0)return Blocks.OCHRE_FROGLIGHT.defaultBlockState();
        if(x%10==0||z%10==0)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        return Blocks.DEEPSLATE_TILES.defaultBlockState();
    }
    /** {@code along} runs with the wall, so pillars land at the same spacing on all four sides. */
    private static BlockState wall(int along,int height) {
        boolean pillar=along%10==0;
        if(height==WALL-1)return Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        if(pillar&&(height==5||height==11))return Blocks.GLOWSTONE.defaultBlockState();
        if(pillar)return Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    }

    /** Only the actual central seat blocks accept sitting; normal building elsewhere stays unchanged. */
    public static boolean sit(ServerPlayer player,BlockPos clicked) {
        if(!inside(player.level())||player.isCrouching()||player.isPassenger()||TemporalEngine.frozen(player))return false;
        if(!player.level().getBlockState(clicked).is(Blocks.POLISHED_BLACKSTONE))return false;
        if(clicked.getY()!=FLOOR_Y+5)return false;
        int localX=Math.floorMod(clicked.getX(),SPACING),localZ=Math.floorMod(clicked.getZ(),SPACING);
        if(localX<49||localX>51||localZ<51||localZ>54)return false;
        Vec3 seatAt=new Vec3(clicked.getX()-localX+50.5,FLOOR_Y+6,clicked.getZ()-localZ+53.5);
        // One person's seat. A visitor asking for it is thrown clear rather than refused politely.
        if(!ownsHere(player))return Throne.refuse(player,seatAt);
        if(!player.level().getEntitiesOfClass(com.loki.entity.ThroneSeat.class,new net.minecraft.world.phys.AABB(seatAt,seatAt).inflate(2)).isEmpty())return true;
        var seat=new com.loki.entity.ThroneSeat(com.loki.Loki.THRONE_SEAT.get(),player.level());
        seat.setPos(seatAt);seat.setYRot(0);
        if(!player.level().noCollision(player,player.getBoundingBox().move(seatAt.subtract(player.position()))))return false;
        if(!player.level().addFreshEntity(seat))return false;
        if(!player.startRiding(seat,true)){seat.discard();return false;}
        player.setYRot(0);player.setYHeadRot(0);return true;
    }

    public static void reset() {PENDING.clear();KNEELING.clear();garden=null;oldGarden=null;Starfall.reset();SanctumWard.reset();Throne.reset();}
}
