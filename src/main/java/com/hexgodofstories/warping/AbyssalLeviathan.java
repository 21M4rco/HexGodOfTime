package com.hexgodofstories.warping;

import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.syncher.*;
import net.minecraft.nbt.CompoundTag;

/** A real, killable aquatic hunter. Every survival player is prey, including its summoner. */
public final class AbyssalLeviathan extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> STRIKING=SynchedEntityData.defineId(AbyssalLeviathan.class,EntityDataSerializers.BOOLEAN);
    private int attackCooldown;private Vec3 desired=Vec3.ZERO;
    public final Vec3[] trail=new Vec3[96];private int trailHead;
    public AbyssalLeviathan(EntityType<? extends PathfinderMob> type,Level level){super(type,level);setNoGravity(true);setPersistenceRequired();xpReward=80;}
    public static AttributeSupplier.Builder attributes(){return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,650).add(Attributes.ARMOR,16).add(Attributes.ATTACK_DAMAGE,60).add(Attributes.MOVEMENT_SPEED,.45).add(Attributes.FOLLOW_RANGE,160).add(Attributes.KNOCKBACK_RESISTANCE,1);}
    @Override protected void defineSynchedData(){super.defineSynchedData();entityData.define(STRIKING,false);}
    public boolean striking(){return entityData.get(STRIKING);}
    @Override public boolean canBreatheUnderwater(){return true;}
    @Override protected void registerGoals(){}
    @Override public void tick(){
        super.tick();setAirSupply(300);trailHead=(trailHead+1)%trail.length;trail[trailHead]=position();
        if(level().isClientSide)return;
        if(attackCooldown>0)attackCooldown--;
        LivingEntity prey=getTarget();
        if(tickCount%10==0||prey==null||!prey.isAlive()){
            Player p=level().getNearestPlayer(getX(),getY(),getZ(),160,e->e instanceof Player q&&!q.isSpectator()&&!q.isCreative());
            setTarget(p);prey=p;
        }
        if(prey!=null){
            double distance=distanceTo(prey);boolean strike=distance<25&&attackCooldown<25;
            entityData.set(STRIKING,strike);
            // Circle underneath before climbing sharply into a bite; deep silhouette precedes the attack.
            desired=prey.position().add(strike?0:Math.cos(tickCount*.035)*12,strike?.3:-12,strike?0:Math.sin(tickCount*.035)*12).subtract(position()).normalize().scale(strike?1.15:.65);
            if(distance<45&&tickCount%100==0)level().playSound(null,blockPosition(),net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_AMBIENT,net.minecraft.sounds.SoundSource.HOSTILE,1.2f,.45f);
            if(distance<6.8&&attackCooldown==0){
                prey.hurt(damageSources().mobAttack(this),60);Vec3 knock=prey.position().subtract(position()).normalize().scale(1.3).add(0,.65,0);prey.setDeltaMovement(knock);prey.hurtMarked=true;attackCooldown=65;
                level().playSound(null,blockPosition(),net.minecraft.sounds.SoundEvents.RAVAGER_ROAR,net.minecraft.sounds.SoundSource.HOSTILE,2,.55f);
            }
        }else{
            entityData.set(STRIKING,false);double cell=WarpMath.cellX(getX());desired=new Vec3(cell+Math.cos(tickCount*.012)*34,108+Math.sin(tickCount*.02)*8,Math.sin(tickCount*.012)*34).subtract(position()).normalize().scale(.4);
        }
        Vec3 v=getDeltaMovement().scale(.88).add(desired.scale(.12));
        if(getY()>134)v=v.add(0,-.1,0);if(getY()<15)v=v.add(0,.12,0);
        setDeltaMovement(v);move(MoverType.SELF,v);setYRot((float)(Math.atan2(-v.x,v.z)*180/Math.PI));setXRot((float)(-Math.atan2(v.y,Math.sqrt(v.x*v.x+v.z*v.z))*180/Math.PI));yBodyRot=getYRot();
    }
    public Vec3 tail(int delay){Vec3 p=trail[Math.floorMod(trailHead-delay,trail.length)];return p==null?position().subtract(getLookAngle().scale(delay*.4)):p;}
    @Override public void travel(Vec3 input){} // Navigation is volumetric, with no ground friction or walk animation.
    @Override public boolean removeWhenFarAway(double distance){return false;}
    @Override protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource s){return net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_HURT;}
    @Override protected net.minecraft.sounds.SoundEvent getDeathSound(){return net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_DEATH;}
    @Override public void addAdditionalSaveData(CompoundTag n){super.addAdditionalSaveData(n);n.putInt("biteRecovery",attackCooldown);}
    @Override public void readAdditionalSaveData(CompoundTag n){super.readAdditionalSaveData(n);attackCooldown=n.getInt("biteRecovery");}
}
