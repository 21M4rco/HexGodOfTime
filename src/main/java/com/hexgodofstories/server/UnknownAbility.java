package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.entity.UnknownEntity;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The dedicated M-key replacement for Dilation. All authority is server-side. */
public final class UnknownAbility {
    public static final long CHARGE_MS=30_000, ACTIVE_MS=60_000, SPAWN_MS=10_000, COOLDOWN_MS=600_000;
    private record Charge(ServerLevel level,Vec3 spot,long started,long startedTick,long seed,int portalId){}
    private static final Map<UUID,Charge> CHARGING=new HashMap<>();
    private UnknownAbility(){}
    public static boolean charging(ServerPlayer p){return CHARGING.containsKey(p.getUUID());}
    private static Vec3 location(ServerPlayer p) {
        Vec3 facing=new Vec3(p.getLookAngle().x,0,p.getLookAngle().z).normalize();
        if(facing.lengthSqr()<.1)facing=new Vec3(0,0,1);
        Vec3 near=p.position().add(facing.scale(6));
        BlockPos at=BlockPos.containing(near);
        if(!p.serverLevel().hasChunkAt(at))return null;
        int y=p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ());
        if(Math.abs(y-p.getY())>10)y=p.blockPosition().getY();
        return new Vec3(at.getX()+.5,y,at.getZ()+.5);
    }
    public static boolean begin(ServerPlayer p) {
        if(CHARGING.containsKey(p.getUUID()))return false;
        CompoundTag data=HexData.get(p);
        long now=System.currentTimeMillis();
        if(now<data.getLong("unknown_active_until_ms")||now<data.getLong("unknown_cd_until_ms"))return false;
        if(HexData.energy(p)<Ability.SLOW_FIELD.cost)return false;
        Vec3 spot=location(p);if(spot==null)return false;
        long seed=p.getRandom().nextLong();
        int id=-1_000_000-Math.floorMod(p.getUUID().hashCode(),1_000_000);
        CHARGING.put(p.getUUID(),new Charge(p.serverLevel(),spot,now,p.serverLevel().getGameTime(),seed,id));
        portal(p.serverLevel(),CHARGING.get(p.getUUID()),-1,false);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("UNKNOWN — HOLD FOR 30 SECONDS"),true);
        return true;
    }
    public static void release(ServerPlayer p) {
        Charge c=CHARGING.remove(p.getUUID());
        if(c==null)return;
        if(!p.isAlive()||p.serverLevel()!=c.level||!HexData.access(p)||System.currentTimeMillis()-c.started<CHARGE_MS) {
            clearPortal(c);p.displayClientMessage(net.minecraft.network.chat.Component.literal("Unknown charge interrupted."),true);return;
        }
        if(!HexData.spend(p,Ability.SLOW_FIELD.cost)){clearPortal(c);return;}
        long now=System.currentTimeMillis(),finish=now+SPAWN_MS+ACTIVE_MS;
        // Set both timestamps immediately: logging out cannot reset the cooldown or leave it unrecorded.
        CompoundTag data=HexData.get(p);
        data.putLong("unknown_active_until_ms",finish);
        data.putLong("unknown_cd_until_ms",finish+COOLDOWN_MS);
        UnknownEntity beast=HexGodOfStories.UNKNOWN.get().create(c.level);
        if(beast==null){data.remove("unknown_active_until_ms");data.remove("unknown_cd_until_ms");clearPortal(c);return;}
        beast.setPos(c.spot);
        beast.summon(p,finish);
        if(!c.level.addFreshEntity(beast)){data.remove("unknown_active_until_ms");data.remove("unknown_cd_until_ms");clearPortal(c);return;}
        data.putUUID("unknown_entity",beast.getUUID());
        portal(c.level,c,c.level.getGameTime(),false);
        HexNetwork.animate(p,"threads");
        HexNetwork.sync(p);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("UNKNOWN — IT IS COMING."),true);
    }
    public static void tick(ServerPlayer p) {
        Charge c=CHARGING.get(p.getUUID());
        if(c==null)return;
        if(!p.isAlive()||!HexData.access(p)||p.serverLevel()!=c.level){cancel(p);return;}
        if(p.tickCount%10==0)portal(c.level,c,-1,false);
        if(p.tickCount%20==0){
            long left=Math.max(0,(CHARGE_MS-(System.currentTimeMillis()-c.started)+999)/1000);
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(left==0?"UNKNOWN — RELEASE":"UNKNOWN — "+left+"s"),true);
        }
    }
    public static void cancel(ServerPlayer p){Charge c=CHARGING.remove(p.getUUID());if(c!=null)clearPortal(c);}
    public static void finished(UnknownEntity creature) {
        UUID owner=creature.summoner();
        if(owner==null)return;
        ServerPlayer player=creature.level().getServer().getPlayerList().getPlayer(owner);
        if(player!=null){CompoundTag d=HexData.get(player);d.remove("unknown_entity");d.remove("unknown_active_until_ms");HexNetwork.sync(player);}
    }
    public static void closePortal(UnknownEntity creature) {
        if(creature.summoner()==null)return;
        ServerPlayer player=creature.level().getServer().getPlayerList().getPlayer(creature.summoner());
        if(player==null)return;
        CompoundTag n=new CompoundTag();n.putBoolean("clear",true);
        HexNetwork.near((ServerLevel)creature.level(),creature.position(),100,new HexNetwork.Message(HexNetwork.WARP,-1_000_000-Math.floorMod(player.getUUID().hashCode(),1_000_000),n));
    }
    private static void clearPortal(Charge c) {
        CompoundTag n=new CompoundTag();n.putBoolean("clear",true);
        HexNetwork.near(c.level,c.spot,100,new HexNetwork.Message(HexNetwork.WARP,c.portalId,n));
    }
    private static void portal(ServerLevel level,Charge c,long opened,boolean clear) {
        CompoundTag n=new CompoundTag();n.putBoolean("clear",clear);
        n.putDouble("x",c.spot.x);n.putDouble("y",c.spot.y);n.putDouble("z",c.spot.z);
        n.putLong("start",c.startedTick);n.putLong("opened",opened);n.putInt("held",100);
        n.putLong("seed",c.seed);n.putLong("until",level.getGameTime()+26);n.putInt("window",210);n.putInt("previewCap",100);
        n.putString("dimension",level.dimension().location().toString());n.putLong("sent",level.getGameTime());
        HexNetwork.near(level,c.spot,100,new HexNetwork.Message(HexNetwork.WARP,c.portalId,n));
    }
}
