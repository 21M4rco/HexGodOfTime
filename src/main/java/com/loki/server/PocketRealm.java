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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import java.util.*;
import java.util.function.Function;

/**
 * Every Loki who fractures reality is given one hundred by one hundred blocks that belong to nobody else.
 * Plots are allocated once and remembered; the sanctum floor around the arrival point is laid immediately
 * so nobody lands in the void, and the remainder is built out over the following ticks.
 */
public final class PocketRealm {
    public static final ResourceKey<Level> KEY=ResourceKey.create(Registries.DIMENSION,Loki.id("pocket"));
    public static final int SIZE=100,SPACING=512,FLOOR_Y=64,WALL=18,COLUMNS=4*SIZE-4;
    private static final int FLOOR_CELLS=SIZE*SIZE,ISLAND_DEPTH=8;
    private static final int TERRAIN_END=FLOOR_CELLS*(ISLAND_DEPTH+1),WALL_END=TERRAIN_END+COLUMNS*WALL;
    private static List<RealmGarden.Block> garden;
    private static List<RealmGarden.Block> garden(){if(garden==null)garden=RealmGarden.plan();return garden;}
    private static final int IMMEDIATE=4,BUDGET=1800,UPDATE_CLIENTS=2;
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
            for(int v:n.getIntArray("gardens_v2"))r.gardens.add(v);
            ListTag pending=n.getList("garden_progress_v2",Tag.TAG_COMPOUND);
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
            n.putIntArray("gardens_v2",gardens.stream().mapToInt(Integer::intValue).toArray());
            ListTag pending=new ListTag();
            progress.forEach((plot,index)->{CompoundTag e=new CompoundTag();e.putInt("plot",plot);e.putInt("index",index);pending.add(e);});
            n.put("garden_progress_v2",pending);
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

    public static ServerLevel level(MinecraftServer server) {return server.getLevel(KEY);}

    /** @return true when the caster was actually moved, so the rift only reports success on a real crossing. */
    public static boolean enter(ServerPlayer p) {
        ServerLevel realm=level(p.server);
        if(realm==null){p.displayClientMessage(Component.literal("The sanctum will not open; its dimension is missing."),true);return false;}
        if(p.level()==realm)return false;
        CompoundTag d=LokiData.get(p);
        // Deliberately stored outside the transient block so a dimension change cannot erase the way home.
        d.putString("returnDim",p.level().dimension().location().toString());
        d.putDouble("returnX",p.getX());d.putDouble("returnY",p.getY());d.putDouble("returnZ",p.getZ());
        d.putFloat("returnYaw",p.getYRot());d.putFloat("returnPitch",p.getXRot());
        int plot=realms(realm).plot(p.getUUID());
        prepare(realm,plot);
        Vec3 spawn=centre(plot);
        p.changeDimension(realm,new Placement(spawn,180,0));
        LokiNetwork.fx(p,"rift_cross");
        p.displayClientMessage(Component.literal("Your world tree. Fracture to leave, or crouch on the arrival sigil."),true);
        return true;
    }

    public static boolean leave(ServerPlayer p) {
        CompoundTag d=LokiData.get(p);
        ResourceKey<Level> key=Level.OVERWORLD;
        if(d.contains("returnDim")) {
            ResourceLocation id=ResourceLocation.tryParse(d.getString("returnDim"));
            if(id!=null)key=ResourceKey.create(Registries.DIMENSION,id);
        }
        ServerLevel destination=p.server.getLevel(key);
        if(destination==null)destination=p.server.overworld();
        Vec3 home=d.contains("returnX")
            ?new Vec3(d.getDouble("returnX"),d.getDouble("returnY"),d.getDouble("returnZ"))
            :Vec3.atBottomCenterOf(destination.getSharedSpawnPos());
        if(p.level()==destination) {
            p.teleportTo(home.x,home.y,home.z);
        } else {
            p.changeDimension(destination,new Placement(home,d.getFloat("returnYaw"),d.getFloat("returnPitch")));
        }
        LokiNetwork.fx(p,"rift_cross");
        return true;
    }

    /** Persisted progress only completes after the final block. A restart resumes unfinished gardens. */
    private static void prepare(ServerLevel realm,int plot) {
        Realms ledger=realms(realm);
        if(!ledger.needsBuild(plot)||PENDING.stream().anyMatch(job->job[0]==plot))return;
        BlockPos o=origin(plot);
        for(int x=SIZE/2-IMMEDIATE;x<=SIZE/2+IMMEDIATE;x++)
            for(int z=72-IMMEDIATE;z<=72+IMMEDIATE;z++) {
                replaceTerrain(realm,o.offset(x,-1,z),Blocks.POLISHED_BLACKSTONE.defaultBlockState(),Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                replaceTerrain(realm,o.offset(x,0,z),floor(x,z),legacyFloor(x,z));
            }
        PENDING.add(new int[]{plot,ledger.progress.getOrDefault(plot,0)});
    }

    public static void tick(ServerLevel level) {
        if(level.dimension()!=KEY)return;
        for(ServerPlayer player:level.players())prepare(level,realms(level).plot(player.getUUID()));
        if(!PENDING.isEmpty()) {
            int[] job=PENDING.get(0);
            BlockPos o=origin(job[0]);
            int placed=0;
            int total=WALL_END+garden().size();
            while(job[1]<total&&placed<BUDGET) {place(level,o,job[1]++);placed++;}
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
        if(index<TERRAIN_END) {
            int depth=index/FLOOR_CELLS,i=index%FLOOR_CELLS,x=i%SIZE,z=i/SIZE;
            double radius=Math.hypot(x-49.5,z-49.5);
            double edge=47-depth*2.5+Math.sin(x*.25)*.65+Math.cos(z*.24)*.65;
            BlockState state=radius>edge?Blocks.AIR.defaultBlockState():depth==0?floor(x,z):
                (depth==1?Blocks.POLISHED_BLACKSTONE.defaultBlockState():Blocks.DEEPSLATE.defaultBlockState());
            BlockState previous=depth==0?legacyFloor(x,z):depth==1?Blocks.POLISHED_BLACKSTONE.defaultBlockState():Blocks.AIR.defaultBlockState();
            replaceTerrain(level,o.offset(x,-depth,z),state,previous);
            return;
        }
        if(index>=WALL_END) {
            RealmGarden.Block block=garden().get(index-WALL_END);
            BlockPos pos=o.offset(block.offset());
            // Above the old floor only place into air, preserving player-built sanctum furnishings.
            if(level.getBlockState(pos).isAir())level.setBlock(pos,block.state(),UPDATE_CLIENTS);
            return;
        }
        // Remove only the exact wall states laid by v1, so older worlds get the open sky too.
        int i=index-TERRAIN_END,column=i/WALL,height=i%WALL;
        int x,z,along;
        if(column<SIZE){x=column;z=0;along=x;}
        else if(column<SIZE*2){x=column-SIZE;z=SIZE-1;along=x;}
        else if(column<SIZE*2+SIZE-2){x=0;z=column-SIZE*2+1;along=z;}
        else {x=SIZE-1;z=column-(SIZE*2+SIZE-2)+1;along=z;}
        BlockPos pos=o.offset(x,height+1,z);
        if(level.getBlockState(pos).equals(wall(along,height)))level.setBlock(pos,Blocks.AIR.defaultBlockState(),UPDATE_CLIENTS);
    }
    private static void replaceTerrain(ServerLevel level,BlockPos pos,BlockState state,BlockState previous) {
        BlockState current=level.getBlockState(pos);
        if((current.isAir()||current.equals(previous))&&!current.equals(state))level.setBlock(pos,state,UPDATE_CLIENTS);
    }
    private static BlockState floor(int x,int z) {
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

    private record Placement(Vec3 target,float yaw,float pitch) implements ITeleporter {
        @Override public Entity placeEntity(Entity entity,ServerLevel from,ServerLevel to,float unusedYaw,Function<Boolean,Entity> reposition) {
            Entity moved=reposition.apply(false);
            moved.teleportTo(target.x,target.y,target.z);
            moved.setYRot(yaw);moved.setXRot(pitch);
            moved.setDeltaMovement(Vec3.ZERO);
            moved.fallDistance=0;
            return moved;
        }
        @Override public boolean playTeleportSound(ServerPlayer player,ServerLevel from,ServerLevel to) {return false;}
    }

    public static void reset() {PENDING.clear();KNEELING.clear();garden=null;}
}
