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
    private static final int DEEP_CELLS=SURFACE_CELLS*(RealmShape.DEPTH-1);
    private static List<RealmGarden.Block> garden;
    private static List<LegacyRealmGarden.Block> oldGarden;
    private static List<RealmGarden.Block> garden(){if(garden==null)garden=RealmGarden.plan();return garden;}
    private static List<LegacyRealmGarden.Block> oldGarden(){if(oldGarden==null)oldGarden=LegacyRealmGarden.plan();return oldGarden;}
    private static final int IMMEDIATE=4,BUDGET=2600,UPDATE_CLIENTS=2;
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
            for(int v:n.getIntArray("gardens_v3"))r.gardens.add(v);
            ListTag pending=n.getList("garden_progress_v3",Tag.TAG_COMPOUND);
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
            n.putIntArray("gardens_v3",gardens.stream().mapToInt(Integer::intValue).toArray());
            ListTag pending=new ListTag();
            progress.forEach((plot,index)->{CompoundTag e=new CompoundTag();e.putInt("plot",plot);e.putInt("index",index);pending.add(e);});
            n.put("garden_progress_v3",pending);
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
        prepare(realm,plot);
        Vec3 spawn=centre(plot);
        if(!move(p,realm,spawn,180,0))return false;
        LokiNetwork.fx(p,"rift_cross");
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
        LokiNetwork.fx(p,"rift_cross");
        return true;
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
        for(ServerPlayer player:level.players())prepare(level,realms(level).plot(player.getUUID()));
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
            Vec3 middle=centre(realms(level).plot(p.getUUID()));
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
        if(index<oldGarden().size()) {
            LegacyRealmGarden.Block old=oldGarden().get(index);
            BlockPos pos=o.offset(old.offset());
            // Only exact generated v2 states can be removed. Other blocks, chests and builds survive.
            if(level.getBlockState(pos).equals(old.state()))level.setBlock(pos,Blocks.AIR.defaultBlockState(),UPDATE_CLIENTS);
            return;
        }
        index-=oldGarden().size();
        if(index<garden().size()) {
            RealmGarden.Block block=garden().get(index);BlockPos pos=o.offset(block.offset());
            if(level.getBlockState(pos).isAir())level.setBlock(pos,block.state(),UPDATE_CLIENTS);
            return;
        }
        index-=garden().size();
        if(index<DEEP_CELLS) {
            int depth=2+index/SURFACE_CELLS,i=index%SURFACE_CELLS;
            int x=i%RealmShape.SIZE+RealmShape.MIN,z=i/RealmShape.SIZE+RealmShape.MIN;
            boolean solid=RealmShape.contains(x,z)&&depth<=RealmShape.depth(x,z);
            BlockState state=Blocks.AIR.defaultBlockState();
            if(solid) {
                int grain=Math.floorMod(x*23+z*41+depth*17,31);
                state=(depth<7?Blocks.STONE:grain<4?Blocks.TUFF:grain<8?Blocks.BASALT:Blocks.DEEPSLATE).defaultBlockState();
            }
            replaceTerrain(level,o.offset(x,-depth,z),state,previousTerrain(x,-depth,z));
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
        int x=Math.floorMod(pos.getX(),SPACING),z=Math.floorMod(pos.getZ(),SPACING);
        boolean legacy=x<SIZE&&z<SIZE&&(pos.getY()==FLOOR_Y&&current.equals(legacyFloor(x,z))
            ||pos.getY()==FLOOR_Y-1&&current.is(Blocks.POLISHED_BLACKSTONE));
        if((current.isAir()||current.equals(previous)||legacy)&&!current.equals(state))level.setBlock(pos,state,UPDATE_CLIENTS);
    }
    private static BlockState floor(int x,int z) {
        double sigil=Math.hypot(x-50,z-72);
        if(sigil<2)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
        if(sigil<3.3)return Blocks.EMERALD_BLOCK.defaultBlockState();
        if(Math.abs(x-50)<3&&z>55&&z<72)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        double edge=RealmShape.fraction(x,z);
        if(edge>.94)return (Math.floorMod(x*19+z*37,5)==0?Blocks.MOSS_BLOCK:Blocks.TUFF).defaultBlockState();
        if(Math.floorMod(x*73+z*37,13)<3)return Blocks.ROOTED_DIRT.defaultBlockState();
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
        if(localX<48||localX>52||localZ<51||localZ>54)return false;
        Vec3 seatAt=new Vec3(clicked.getX()-localX+50.5,FLOOR_Y+6,clicked.getZ()-localZ+53.5);
        if(!player.level().getEntitiesOfClass(com.loki.entity.ThroneSeat.class,new net.minecraft.world.phys.AABB(seatAt,seatAt).inflate(2)).isEmpty())return true;
        var seat=new com.loki.entity.ThroneSeat(com.loki.Loki.THRONE_SEAT.get(),player.level());
        seat.setPos(seatAt);seat.setYRot(0);
        if(!player.level().noCollision(player,player.getBoundingBox().move(seatAt.subtract(player.position()))))return false;
        if(!player.level().addFreshEntity(seat))return false;
        if(!player.startRiding(seat,true)){seat.discard();return false;}
        player.setYRot(0);player.setYHeadRot(0);return true;
    }

    public static void reset() {PENDING.clear();KNEELING.clear();garden=null;oldGarden=null;}
}
