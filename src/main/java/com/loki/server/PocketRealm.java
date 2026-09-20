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
    private static final int FLOOR_CELLS=SIZE*SIZE,TOTAL=FLOOR_CELLS*2+COLUMNS*WALL;
    private static final int IMMEDIATE=22,BUDGET=3000,UPDATE_CLIENTS=2;
    private static final List<int[]> PENDING=new ArrayList<>();

    /** Persisted plot ledger. Lives in the pocket dimension's own data storage. */
    public static final class Realms extends SavedData {
        private final Map<UUID,Integer> plots=new HashMap<>();
        private final Set<Integer> built=new HashSet<>();
        private int next;
        public static Realms load(CompoundTag n) {
            Realms r=new Realms();
            r.next=n.getInt("next");
            ListTag list=n.getList("plots",Tag.TAG_COMPOUND);
            for(int i=0;i<list.size();i++){CompoundTag e=list.getCompound(i);if(e.hasUUID("id"))r.plots.put(e.getUUID("id"),e.getInt("plot"));}
            for(int v:n.getIntArray("built"))r.built.add(v);
            return r;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putInt("next",next);
            ListTag list=new ListTag();
            plots.forEach((id,plot)->{CompoundTag e=new CompoundTag();e.putUUID("id",id);e.putInt("plot",plot);list.add(e);});
            n.put("plots",list);
            int[] done=new int[built.size()];int i=0;for(int v:built)done[i++]=v;
            n.putIntArray("built",done);
            return n;
        }
        int plot(UUID id) {
            Integer existing=plots.get(id);
            if(existing!=null)return existing;
            int plot=next++;plots.put(id,plot);setDirty();return plot;
        }
        boolean claimBuild(int plot) {if(built.add(plot)){setDirty();return true;}return false;}
    }

    private static Realms realms(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Realms::load,Realms::new,"loki_realms");
    }
    public static BlockPos origin(int plot) {return new BlockPos(plot%64*SPACING,FLOOR_Y,plot/64*SPACING);}
    public static Vec3 centre(int plot) {BlockPos o=origin(plot);return new Vec3(o.getX()+SIZE/2.0,FLOOR_Y+1,o.getZ()+SIZE/2.0);}

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
        p.changeDimension(realm,new Placement(spawn,p.getYRot(),p.getXRot()));
        LokiNetwork.fx(p,"rift_cross");
        p.displayClientMessage(Component.literal("Your sanctum. Break the air again to leave."),true);
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

    /** Lays the arrival platform at once, then queues the rest of the sanctum. */
    private static void prepare(ServerLevel realm,int plot) {
        if(!realms(realm).claimBuild(plot))return;
        BlockPos o=origin(plot);
        for(int x=SIZE/2-IMMEDIATE;x<SIZE/2+IMMEDIATE;x++)
            for(int z=SIZE/2-IMMEDIATE;z<SIZE/2+IMMEDIATE;z++) {
                realm.setBlock(o.offset(x,-1,z),Blocks.POLISHED_BLACKSTONE.defaultBlockState(),UPDATE_CLIENTS);
                realm.setBlock(o.offset(x,0,z),floor(x,z),UPDATE_CLIENTS);
            }
        PENDING.add(new int[]{plot,0});
    }

    public static void tick(ServerLevel level) {
        if(level.dimension()!=KEY)return;
        if(!PENDING.isEmpty()) {
            int[] job=PENDING.get(0);
            BlockPos o=origin(job[0]);
            int placed=0;
            while(job[1]<TOTAL&&placed<BUDGET) {place(level,o,job[1]++);placed++;}
            if(job[1]>=TOTAL)PENDING.remove(0);
        }
        if(level.getGameTime()%20!=0)return;
        for(ServerPlayer p:level.players()) {
            if(p.isSpectator()||p.isCreative())continue;
            if(p.getY()>FLOOR_Y-24)continue;
            int plot=realms(level).plot(p.getUUID());
            Vec3 safe=centre(plot);
            p.teleportTo(safe.x,safe.y,safe.z);
            p.setDeltaMovement(Vec3.ZERO);p.resetFallDistance();
        }
    }

    private static void place(ServerLevel level,BlockPos o,int index) {
        if(index<FLOOR_CELLS) {
            level.setBlock(o.offset(index%SIZE,-1,index/SIZE),Blocks.POLISHED_BLACKSTONE.defaultBlockState(),UPDATE_CLIENTS);
            return;
        }
        if(index<FLOOR_CELLS*2) {
            int i=index-FLOOR_CELLS,x=i%SIZE,z=i/SIZE;
            level.setBlock(o.offset(x,0,z),floor(x,z),UPDATE_CLIENTS);
            return;
        }
        int i=index-FLOOR_CELLS*2,column=i/WALL,height=i%WALL;
        int x,z;
        if(column<SIZE){x=column;z=0;}
        else if(column<SIZE*2){x=column-SIZE;z=SIZE-1;}
        else if(column<SIZE*2+SIZE-2){x=0;z=column-SIZE*2+1;}
        else {x=SIZE-1;z=column-(SIZE*2+SIZE-2)+1;}
        level.setBlock(o.offset(x,height+1,z),wall(x,z,height),UPDATE_CLIENTS);
    }

    private static BlockState floor(int x,int z) {
        double dx=x-(SIZE-1)/2.0,dz=z-(SIZE-1)/2.0,r=Math.sqrt(dx*dx+dz*dz);
        if(r<7)return Blocks.GILDED_BLACKSTONE.defaultBlockState();
        if(r<8.6)return Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        if(x%10==0||z%10==0)return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        return Blocks.DEEPSLATE_TILES.defaultBlockState();
    }
    private static BlockState wall(int x,int z,int height) {
        boolean pillar=x%10==0||z%10==0||x==SIZE-1||z==SIZE-1;
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

    public static void reset() {PENDING.clear();}
}
