package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-owned five-second ice hold, with a separate ten-second post-shatter frostbite. */
public final class Frostbite {
    private Frostbite() { }
    private record Ice(LivingEntity entity,Vec3 position,Vec3 velocity,float yaw,float pitch,int age,long expires) { }
    private record Chill(LivingEntity entity,long expires) { }
    private static final Map<UUID,Ice> ICE=new HashMap<>();
    private static final Map<UUID,Chill> CHILLED=new HashMap<>();
    private static final int MAX_ICE=128,MAX_CHILLED=256;

    public static boolean frozen(Entity e) {
        Ice ice=ICE.get(e.getUUID());
        return ice!=null&&ice.entity==e&&e.level().getGameTime()<ice.expires;
    }

    /** One bounded entity lookup, then a cone and a block ray per candidate. Aim is sampled at discharge. */
    public static void burst(ServerPlayer caster) {
        ServerLevel level=caster.serverLevel();
        Vec3 origin=caster.getEyePosition(),forward=caster.getLookAngle().normalize();
        Vec3 end=origin.add(forward.scale(10));
        AABB bounds=new AABB(origin,end).inflate(3.5);
        for(LivingEntity victim:level.getEntitiesOfClass(LivingEntity.class,bounds,
            e->e!=caster&&!(e instanceof Player)&&HexServer.validTarget(caster,e))) {
            Vec3 center=victim.getBoundingBox().getCenter();
            Vec3 delta=center.subtract(origin);
            double distance=delta.dot(forward);
            if(distance<0||distance>10+victim.getBbWidth()*.5)continue;
            double spread=.38+Math.min(10,distance)*.29+Math.max(victim.getBbWidth(),victim.getBbHeight())*.4;
            if(delta.subtract(forward.scale(distance)).lengthSqr()>spread*spread)continue;
            BlockHitResult wall=level.clip(new ClipContext(origin,center,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,caster));
            if(wall.getType()!=HitResult.Type.MISS&&wall.getLocation().distanceToSqr(origin)+.12<delta.lengthSqr())continue;
            freeze(victim);
        }
        level.playSound(null,caster.blockPosition(),SoundEvents.GLASS_BREAK,SoundSource.PLAYERS,.85f,1.3f);
    }

    private static void freeze(LivingEntity target) {
        if(TemporalEngine.frozen(target))return;
        long until=target.level().getGameTime()+100;
        Ice prior=ICE.get(target.getUUID());
        if(prior==null&&ICE.size()>=MAX_ICE)return;
        Ice ice=prior!=null&&prior.entity==target
            ?new Ice(target,prior.position,prior.velocity,prior.yaw,prior.pitch,prior.age,until)
            :new Ice(target,target.position(),target.getDeltaMovement(),target.getYRot(),target.getXRot(),target.tickCount,until);
        ICE.put(target.getUUID(),ice);
        hold(ice);
        state(target,true,ice);
    }

    private static void hold(Ice ice) {
        LivingEntity e=ice.entity;
        e.setPos(ice.position);e.setYRot(ice.yaw);e.setXRot(ice.pitch);
        e.setYHeadRot(ice.yaw);e.yBodyRot=ice.yaw;
        e.setDeltaMovement(Vec3.ZERO);e.setOldPosAndRot();e.hurtMarked=true;
        e.invulnerableTime=0;
    }

    /** The additional damage is part of the direct hit, so armor and other damage hooks still apply. */
    public static float shatter(LivingEntity target,Entity attacker) {
        Ice ice=ICE.get(target.getUUID());
        if(ice==null||ice.entity!=target||attacker==null)return 0;
        release(ice);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,200,2));
        if(CHILLED.containsKey(target.getUUID())||CHILLED.size()<MAX_CHILLED)
            CHILLED.put(target.getUUID(),new Chill(target,target.level().getGameTime()+200));
        CompoundTag n=new CompoundTag();n.putBoolean("shiver",true);n.putLong("until",target.level().getGameTime()+200);
        HexNetwork.tracking(target,new HexNetwork.Message(HexNetwork.FROST,target.getId(),n));
        target.level().playSound(null,target.blockPosition(),SoundEvents.GLASS_BREAK,SoundSource.HOSTILE,1.2f,.68f);
        HexNetwork.fx(target,"frost_shatter");
        return 6;
    }

    private static void state(LivingEntity e,boolean frozen,Ice ice) {
        CompoundTag n=new CompoundTag();n.putBoolean("frozen",frozen);
        if(frozen){
            n.putDouble("x",ice.position.x);n.putDouble("y",ice.position.y);n.putDouble("z",ice.position.z);
            n.putFloat("yaw",ice.yaw);n.putFloat("pitch",ice.pitch);n.putInt("age",ice.age);
            n.putLong("until",ice.expires);
        }
        HexNetwork.tracking(e,new HexNetwork.Message(HexNetwork.FROST,e.getId(),n));
    }

    private static void release(Ice ice) {
        LivingEntity e=ice.entity;
        ICE.remove(e.getUUID());
        if(!e.isRemoved()) {
            e.setDeltaMovement(ice.velocity);e.hurtMarked=true;
            state(e,false,ice);
        }
    }

    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        for(Ice ice:java.util.List.copyOf(ICE.values())) {
            LivingEntity e=ice.entity;
            if(e.level()!=level)continue;
            if(e.isRemoved()||!e.isAlive()||now>=ice.expires||TemporalEngine.frozen(e))release(ice);
            else hold(ice);
        }
        // Frostbite damage can kill its victim and fire LivingDeathEvent, which removes it from
        // CHILLED. Walk a bounded snapshot so that cleanup cannot invalidate this iteration.
        for(Chill chill:java.util.List.copyOf(CHILLED.values())) {
            LivingEntity e=chill.entity;
            if(e.level()!=level)continue;
            if(e.isRemoved()||!e.isAlive()||now>=chill.expires){CHILLED.remove(e.getUUID(),chill);continue;}
            if(now%20==0)e.hurt(level.damageSources().freeze(),1);
        }
    }

    public static void track(ServerPlayer viewer,Entity entity) {
        Ice ice=ICE.get(entity.getUUID());
        if(ice!=null&&ice.entity==entity){
            CompoundTag n=new CompoundTag();n.putBoolean("frozen",true);
            n.putDouble("x",ice.position.x);n.putDouble("y",ice.position.y);n.putDouble("z",ice.position.z);
            n.putFloat("yaw",ice.yaw);n.putFloat("pitch",ice.pitch);n.putInt("age",ice.age);n.putLong("until",ice.expires);
            HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.FROST,entity.getId(),n));
        }
        Chill chill=CHILLED.get(entity.getUUID());
        if(chill!=null&&chill.entity==entity) {
            CompoundTag n=new CompoundTag();n.putBoolean("shiver",true);n.putLong("until",chill.expires);
            HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.FROST,entity.getId(),n));
        }
    }
    public static void clear(Entity e){Ice ice=ICE.get(e.getUUID());if(ice!=null&&ice.entity==e)release(ice);CHILLED.remove(e.getUUID());}
    public static void reset(){ICE.clear();CHILLED.clear();}
}
