package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative Scepter hit scan and a three-second, bounded stun. */
public final class ScepterBlast {
    private ScepterBlast() { }
    public static final double RANGE=100;
    private static final int STUN_TICKS=60,MAX_STUNS=192;
    private record Stun(LivingEntity victim,long until,boolean hadNoAi) { }
    private static final Map<UUID,Stun> STUNS=new HashMap<>();

    public static void fire(ServerPlayer caster) {
        ServerLevel level=caster.serverLevel();
        Vec3 origin=caster.getEyePosition(),end=origin.add(caster.getLookAngle().normalize().scale(RANGE));
        BlockHitResult block=level.clip(new ClipContext(origin,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,caster));
        double first=block.getType()==HitResult.Type.MISS?RANGE*RANGE:origin.distanceToSqr(block.getLocation());
        LivingEntity direct=null;Vec3 impact=block.getType()==HitResult.Type.MISS?end:block.getLocation();
        for(LivingEntity candidate:level.getEntitiesOfClass(LivingEntity.class,new AABB(origin,end).inflate(1),
            e->HexServer.validTarget(caster,e))) {
            Optional<Vec3> hit=candidate.getBoundingBox().inflate(.28).clip(origin,end);
            if(hit.isPresent()&&origin.distanceToSqr(hit.get())<first) {
                first=origin.distanceToSqr(hit.get());impact=hit.get();direct=candidate;
            }
        }
        if(direct!=null) {
            if(direct.hurt(caster.damageSources().indirectMagic(caster,caster),10))stun(direct);
            level.playSound(null,direct.blockPosition(),SoundEvents.BEACON_DEACTIVATE,SoundSource.PLAYERS,1.1f,1.45f);
        } else if(block.getType()!=HitResult.Type.MISS&&block.getDirection()==Direction.UP) {
            // Four by four blocks, centred on the point where the ray met the floor.
            AABB area=new AABB(impact.x-2,impact.y,impact.z-2,impact.x+2,impact.y+3,impact.z+2);
            for(LivingEntity victim:level.getEntitiesOfClass(LivingEntity.class,area,
                e->HexServer.validTarget(caster,e))) {
                victim.setSecondsOnFire(4);
                stun(victim);
            }
            level.playSound(null,net.minecraft.core.BlockPos.containing(impact),SoundEvents.GENERIC_EXPLODE,SoundSource.PLAYERS,.8f,1.4f);
        }
        CompoundTag fx=new CompoundTag();fx.putString("effect","scepter_blast");
        fx.putDouble("x",origin.x);fx.putDouble("y",origin.y);fx.putDouble("z",origin.z);
        fx.putDouble("tx",impact.x);fx.putDouble("ty",impact.y);fx.putDouble("tz",impact.z);
        fx.putBoolean("floor",direct==null&&block.getType()!=HitResult.Type.MISS&&block.getDirection()==Direction.UP);
        HexNetwork.near(level,origin,160,new HexNetwork.Message(HexNetwork.FX,caster.getId(),fx));
    }

    private static void stun(LivingEntity victim) {
        if(STUNS.size()>=MAX_STUNS&&!STUNS.containsKey(victim.getUUID()))return;
        Stun old=STUNS.get(victim.getUUID());
        boolean hadNoAi=old!=null?old.hadNoAi:victim instanceof Mob mob&&mob.isNoAi();
        STUNS.put(victim.getUUID(),new Stun(victim,victim.level().getGameTime()+STUN_TICKS,hadNoAi));
        if(victim instanceof Mob mob)mob.setNoAi(true);
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,STUN_TICKS,255,false,true));
        victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,STUN_TICKS,255,false,true));
        CompoundTag state=new CompoundTag();state.putLong("until",victim.level().getGameTime()+STUN_TICKS);
        HexNetwork.tracking(victim,new HexNetwork.Message(HexNetwork.STUN,victim.getId(),state));
    }
    public static boolean stunned(LivingEntity victim) {
        Stun stun=STUNS.get(victim.getUUID());
        return stun!=null&&stun.victim==victim&&victim.level().getGameTime()<stun.until;
    }
    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        for(Stun stun:java.util.List.copyOf(STUNS.values())) {
            LivingEntity victim=stun.victim;
            if(victim.level()!=level)continue;
            if(now>=stun.until||victim.isRemoved()||!victim.isAlive()) {
                STUNS.remove(victim.getUUID(),stun);
                if(victim instanceof Mob mob&&!stun.hadNoAi)mob.setNoAi(false);
                CompoundTag state=new CompoundTag();state.putLong("until",0);
                if(!victim.isRemoved())HexNetwork.tracking(victim,new HexNetwork.Message(HexNetwork.STUN,victim.getId(),state));
            } else {
                victim.setDeltaMovement(0,Math.min(0,victim.getDeltaMovement().y),0);
                victim.hurtMarked=true;
            }
        }
    }
    public static void clear(LivingEntity victim) {
        Stun stun=STUNS.remove(victim.getUUID());
        if(stun!=null&&victim instanceof Mob mob&&!stun.hadNoAi)mob.setNoAi(false);
    }
    public static void track(ServerPlayer viewer,net.minecraft.world.entity.Entity entity) {
        if(entity instanceof LivingEntity living&&stunned(living)) {
            CompoundTag state=new CompoundTag();state.putLong("until",STUNS.get(living.getUUID()).until);
            HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.STUN,entity.getId(),state));
        }
    }
    public static void reset(){for(Stun stun:STUNS.values())if(stun.victim instanceof Mob mob&&!stun.hadNoAi)mob.setNoAi(false);STUNS.clear();}
}
