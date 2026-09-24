package com.hexgodofstories.unknown;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Wall-clock sequence and saved cooldown; the portal packet is the existing Warping surface. */
public final class UnknownSummoning extends SavedData {
    public static final long CHARGE_MS=30_000,EMERGE_MS=10_000,HUNT_MS=60_000,VANISH_MS=4_000,COOLDOWN_MS=600_000;
    private static final String NAME="hexgodofstories_unknown_cooldowns";
    private final Map<UUID,Long> cooldowns=new HashMap<>(),pending=new HashMap<>();
    private static final Map<UUID,Charge> CHARGES=new HashMap<>();
    private record Charge(ServerLevel level,Vec3 at,UUID caster,int casterId,long begin,long seed,long openedTick){}
    public static UnknownSummoning of(ServerLevel level){
        return level.getServer().overworld().getDataStorage().computeIfAbsent(UnknownSummoning::load,UnknownSummoning::new,NAME);
    }
    public static boolean isCharging(ServerPlayer p){return CHARGES.containsKey(p.getUUID());}
    public static void syncCooldown(ServerPlayer p){
        long until=of(p.serverLevel()).cooldowns.getOrDefault(p.getUUID(),0L);
        HexData.get(p).putLong("cd_SLOW_FIELD",HexData.now(p)+Math.max(0,(until-System.currentTimeMillis()+49)/50));
        if(!CHARGES.containsKey(p.getUUID())){
            HexData.get(p).remove("unknownChargeStartTick");HexData.get(p).remove("unknownChargeEnds");
            HexData.get(p).remove("unknownChargeSyncedAt");
        }
    }
    public static void begin(ServerPlayer p){
        long now=System.currentTimeMillis();
        UnknownSummoning data=of(p.serverLevel());
        if(isCharging(p)){cancelCharge(p);return;}
        if(now<data.pending.getOrDefault(p.getUUID(),0L))return;
        if(now<data.cooldowns.getOrDefault(p.getUUID(),0L)){syncCooldown(p);HexNetwork.sync(p);return;}
        Vec3 eye=p.getEyePosition(),end=eye.add(p.getLookAngle().scale(24));
        BlockHitResult hit=p.level().clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if(hit.getType()!=HitResult.Type.BLOCK||hit.getDirection()!=net.minecraft.core.Direction.UP){
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("Unknown requires a visible solid floor."),true);
            return;
        }
        BlockPos floor=hit.getBlockPos();
        Vec3 at=new Vec3(floor.getX()+.5,hit.getLocation().y+.03,floor.getZ()+.5);
        if(!HexData.spend(p,Ability.SLOW_FIELD.cost))return;
        Charge charge=new Charge(p.serverLevel(),at,p.getUUID(),p.getId(),now,p.getUUID().getLeastSignificantBits()^now,-1);
        CHARGES.put(p.getUUID(),charge);
        HexData.get(p).putLong("unknownChargeEnds",now+CHARGE_MS);
        HexData.get(p).putLong("unknownChargeStartTick",p.level().getGameTime());
        HexData.get(p).putLong("unknownChargeSyncedAt",now);
        HexNetwork.animate(p,"unknown_summon");
        data.pending.put(p.getUUID(),now+CHARGE_MS+EMERGE_MS+HUNT_MS+VANISH_MS);
        // If the server stops mid-hunt, cooldown and terrain repair still have a real-time deadline.
        data.cooldowns.put(p.getUUID(),now+CHARGE_MS+EMERGE_MS+HUNT_MS+VANISH_MS+COOLDOWN_MS);
        data.setDirty();
        p.serverLevel().playSound(null,floor,HexGodOfStories.UNKNOWN_CHARGE.get(),SoundSource.HOSTILE,3f,.83f);
        send(charge,false,false);
        HexNetwork.sync(p);
    }
    public static void cancelCharge(ServerPlayer p){
        Charge c=CHARGES.remove(p.getUUID());
        if(c!=null){
            send(c,true,false);
            UnknownSummoning d=of(p.serverLevel());d.pending.remove(p.getUUID());d.cooldowns.remove(p.getUUID());d.setDirty();
            HexData.energy(p,HexData.energy(p)+Ability.SLOW_FIELD.cost);
            syncCooldown(p);clearGesture(p);
        }
    }
    public static void tick(ServerLevel level){
        long now=System.currentTimeMillis();
        for(Charge c:new ArrayList<>(CHARGES.values())){
            if(c.level!=level)continue;
            ServerPlayer caster=level.getServer().getPlayerList().getPlayer(c.caster);
            if(caster==null||caster.level()!=level||!caster.isAlive()){
                CHARGES.remove(c.caster);send(c,true,false);
                if(caster!=null)clearGesture(caster);
                UnknownSummoning d=of(level);d.pending.remove(c.caster);d.cooldowns.remove(c.caster);d.setDirty();continue;
            }
            if(now-c.begin>=CHARGE_MS){
                CHARGES.remove(c.caster);
                clearGesture(caster);
                UnknownEntity creature=HexGodOfStories.UNKNOWN.get().create(level);
                if(creature==null){send(c,true,false);UnknownSummoning d=of(level);d.pending.remove(c.caster);d.cooldowns.remove(c.caster);d.setDirty();return;}
                creature.moveTo(c.at.x,c.at.y,c.at.z,caster.getYRot(),0);
                creature.begin(c.caster,now,c.at,c.seed,level.getGameTime());
                level.addFreshEntity(creature);
                level.playSound(null,BlockPos.containing(c.at),HexGodOfStories.UNKNOWN_EMERGE.get(),SoundSource.HOSTILE,6f,.84f);
                // One independent packet per creature during emergence; no Warping crossing is registered.
                portal(creature,false);
            }else if(level.getGameTime()%10==0){
                send(c,false,false);
                if(level.getGameTime()%20==0){HexData.get(caster).putLong("unknownChargeSyncedAt",now);HexNetwork.sync(caster);}
                if(level.getGameTime()%140==0)level.playSound(null,BlockPos.containing(c.at),
                        HexGodOfStories.UNKNOWN_CHARGE.get(),SoundSource.HOSTILE,2f,1f);
            }
        }
    }
    private static void clearGesture(ServerPlayer p){
        HexData.get(p).remove("unknownChargeEnds");HexData.get(p).remove("unknownChargeSyncedAt");
        HexData.get(p).remove("unknownChargeStartTick");HexNetwork.animate(p,"__clear__");HexNetwork.sync(p);
    }
    public static void portal(UnknownEntity creature,boolean clear){
        if(!(creature.level() instanceof ServerLevel level))return;
        Charge c=new Charge(level,creature.portal(),creature.caster(),creature.portalId(),
                creature.born()-CHARGE_MS,creature.portalSeed(),creature.portalGameTick());
        send(c,clear,true);
    }
    /** Reuse the exact WARP payload and jagged terrain-following renderer, with a separate id. */
    private static void send(Charge c,boolean clear,boolean open){
        long game=c.level.getGameTime(),elapsed=System.currentTimeMillis()-c.begin;
        int held=open?100:(int)Math.min(100,Math.max(0,elapsed*100/CHARGE_MS));
        CompoundTag n=new CompoundTag();
        n.putBoolean("clear",clear);
        n.putDouble("x",c.at.x);n.putDouble("y",c.at.y);n.putDouble("z",c.at.z);
        n.putLong("start",open?c.openedTick-100:game-Math.min(100,elapsed/300));n.putInt("destination",com.hexgodofstories.warping.Destination.VOID_SEA.ordinal());
        n.putLong("opened",open?c.openedTick:-1);n.putInt("held",held);n.putLong("seed",c.seed);
        n.putLong("until",game+20);n.putInt("window",200);
        n.putInt("previewCap",100);n.putString("dimension",c.level.dimension().location().toString());n.putLong("sent",game);
        HexNetwork.near(c.level,c.at,96,new HexNetwork.Message(HexNetwork.WARP,-c.casterId-1,n));
    }
    public static void finished(UnknownEntity creature){
        UnknownTerrain.release((ServerLevel)creature.level(),creature.getUUID());
        UnknownSummoning data=of((ServerLevel)creature.level());
        long until=System.currentTimeMillis()+COOLDOWN_MS;
        data.pending.remove(creature.caster());data.cooldowns.put(creature.caster(),until);data.setDirty();
        ServerPlayer caster=creature.getServer().getPlayerList().getPlayer(creature.caster());
        if(caster!=null){syncCooldown(caster);HexNetwork.sync(caster);}
    }
    public static UnknownSummoning load(CompoundTag root){
        UnknownSummoning data=new UnknownSummoning();
        CompoundTag cd=root.getCompound("cooldowns"),pending=root.getCompound("pending");
        for(String key:cd.getAllKeys())try{data.cooldowns.put(UUID.fromString(key),cd.getLong(key));}catch(IllegalArgumentException ignored){}
        for(String key:pending.getAllKeys())try{data.pending.put(UUID.fromString(key),pending.getLong(key));}catch(IllegalArgumentException ignored){}
        return data;
    }
    @Override public CompoundTag save(CompoundTag root){
        CompoundTag cd=new CompoundTag(),p=new CompoundTag();
        cooldowns.forEach((id,time)->cd.putLong(id.toString(),time));
        pending.forEach((id,time)->p.putLong(id.toString(),time));
        root.put("cooldowns",cd);root.put("pending",p);return root;
    }
}
