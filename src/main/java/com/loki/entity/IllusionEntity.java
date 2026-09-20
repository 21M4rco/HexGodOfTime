package com.loki.entity;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.loki.server.LokiServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import java.util.*;

/** Lightweight decoys share ownership, rendering, navigation, commanded aggression and dispel rules. */
public final class IllusionEntity extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> FINAL=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BEHAVIOR=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> QUARRY=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.INT);
    public enum Behavior { APPROACH, STRAFE, FEINT, RETREAT, WATCH, BLINK, THROW }
    public record Spec(int lifespan,Behavior behavior,boolean decoy,boolean collision,boolean dispelOnHit) {}
    private Spec spec=new Spec(300,Behavior.STRAFE,true,false,true);
    private long expires,nextStrike;
    public IllusionEntity(EntityType<? extends IllusionEntity> type,Level level) {super(type,level);setPersistenceRequired();}
    public static AttributeSupplier.Builder attributes() {return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,1).add(Attributes.MOVEMENT_SPEED,.31).add(Attributes.FOLLOW_RANGE,32);}
    @Override protected void defineSynchedData() {super.defineSynchedData();entityData.define(OWNER,Optional.empty());entityData.define(FINAL,false);entityData.define(BEHAVIOR,0);entityData.define(QUARRY,0);}
    @Override protected void registerGoals() {goalSelector.addGoal(0,new FloatGoal(this));}
    public UUID owner() {return entityData.get(OWNER).orElse(null);}
    public boolean finalForm() {return entityData.get(FINAL);}
    public int behavior() {return entityData.get(BEHAVIOR);}
    public boolean aggressive() {return entityData.get(QUARRY)!=0;}

    public void configure(ServerPlayer p,Spec spec) {
        this.spec=spec;expires=level().getGameTime()+spec.lifespan;entityData.set(OWNER,Optional.of(p.getUUID()));
        entityData.set(FINAL,LokiData.get(p).getBoolean("ascended"));entityData.set(BEHAVIOR,spec.behavior.ordinal());
        for(EquipmentSlot slot:EquipmentSlot.values())setItemSlot(slot,p.getItemBySlot(slot).copy());
        setYRot(p.getYRot());setYHeadRot(p.getYHeadRot());yBodyRot=p.yBodyRot;
    }

    /** Ordered onto a foe by its caster. The decoy commits to that quarry until it dies or the decoy fades. */
    public void command(LivingEntity quarry) {
        entityData.set(QUARRY,quarry==null?0:quarry.getId());
        if(quarry!=null) {
            // A commanded decoy stops loitering: pick a behaviour that actually closes on the target.
            Behavior forced=switch(getId()%4){case 0->Behavior.APPROACH;case 1->Behavior.STRAFE;case 2->Behavior.FEINT;default->Behavior.THROW;};
            spec=new Spec(spec.lifespan,forced,spec.decoy,spec.collision,spec.dispelOnHit);
            entityData.set(BEHAVIOR,forced.ordinal());
            expires=Math.max(expires,level().getGameTime()+160);
        }
    }

    private LivingEntity quarry() {
        int id=entityData.get(QUARRY);
        if(id==0)return null;
        if(level().getEntity(id) instanceof LivingEntity living&&living.isAlive())return living;
        entityData.set(QUARRY,0);
        return null;
    }

    @Override public void tick() {
        super.tick();
        if(level().isClientSide)return;
        ServerPlayer p=owner()==null?null:((ServerLevel)level()).getServer().getPlayerList().getPlayer(owner());
        if(p==null||p.level()!=level()||!p.isAlive()||level().getGameTime()>=expires||distanceToSqr(p)>4096){dispel();return;}
        LivingEntity enemy=quarry();
        if(enemy!=null&&(enemy.level()!=level()||distanceToSqr(enemy)>2304)){entityData.set(QUARRY,0);enemy=null;}
        if(enemy!=null)strike(p,enemy);
        if(tickCount%10!=getId()%10)return;
        if(enemy==null)enemy=level().getEntitiesOfClass(Monster.class,getBoundingBox().inflate(20),e->e.isAlive()&&e.hasLineOfSight(this)).stream().min(Comparator.comparingDouble(e->e.distanceToSqr(this))).orElse(null);
        if(enemy==null) {
            if(spec.behavior!=Behavior.WATCH&&distanceToSqr(p)>16)getNavigation().moveTo(p,1);
            return;
        }
        getLookControl().setLookAt(enemy,35,35);
        if(spec.decoy&&enemy instanceof Mob mob&&(mob.getTarget()==p||mob.getTarget()==null))mob.setTarget(this);
        Vec3 delta=position().subtract(enemy.position()).multiply(1,0,1).normalize();
        if(delta.lengthSqr()<1e-6)delta=new Vec3(1,0,0);
        double angle=(getId()%7-3)*.4+tickCount*.025;
        Vec3 orbit=new Vec3(Math.cos(angle),0,Math.sin(angle)).scale(3.4);
        Vec3 goal=switch(spec.behavior) {
            case APPROACH,FEINT -> enemy.position().add(delta.scale(1.4));
            case THROW -> enemy.position().add(delta.scale(7));
            case RETREAT -> position().add(delta.scale(5));
            case STRAFE,BLINK -> enemy.position().add(orbit);
            case WATCH -> position();
        };
        if(spec.behavior!=Behavior.WATCH)getNavigation().moveTo(goal.x,goal.y,goal.z,spec.behavior==Behavior.RETREAT?1.25:1);
        if(spec.behavior==Behavior.THROW&&tickCount%40==getId()%10) {
            Vec3 from=position().add(0,1.4,0);
            ThrownDagger.throwFrom(p,from,enemy.getEyePosition().subtract(from),quarry()==null);
            swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        if(spec.behavior==Behavior.BLINK&&tickCount%60==getId()%10&&level().noCollision(this,getBoundingBox().move(goal.subtract(position())))) {
            LokiNetwork.fx(this,"dispel");setPos(goal);
        }
    }

    /** A commanded decoy lands real but modest blows, credited to its caster. */
    private void strike(ServerPlayer p,LivingEntity enemy) {
        long now=level().getGameTime();
        if(now<nextStrike||!LokiServer.validTarget(p,enemy))return;
        double reach=getBbWidth()*.5+enemy.getBbWidth()*.5+1.35;
        if(distanceToSqr(enemy)>reach*reach||!hasLineOfSight(enemy))return;
        nextStrike=now+18;
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        getLookControl().setLookAt(enemy,45,45);
        float damage=2+Math.min(3.5f,LokiData.mastery(p,Discipline.MISCHIEF)*.0035f);
        if(enemy.hurt(p.damageSources().indirectMagic(this,p),damage)) {
            enemy.knockback(.16,getX()-enemy.getX(),getZ()-enemy.getZ());
            LokiNetwork.fx(enemy,"impact");
            LokiServer.reward(p,Discipline.MISCHIEF,35);
        }
    }

    public void dispel() {if(!isRemoved()){LokiNetwork.fx(this,"dispel");discard();}}
    @Override public boolean hurt(DamageSource source,float amount) {if(level().isClientSide)return true;if(spec.dispelOnHit)dispel();return true;}
    @Override public boolean isPushable() {return spec.collision;}
    @Override public boolean canCollideWith(Entity e) {return spec.collision&&super.canCollideWith(e);}
    @Override public boolean shouldShowName() {return false;}
    @Override public void addAdditionalSaveData(CompoundTag n) {super.addAdditionalSaveData(n);if(owner()!=null)n.putUUID("Owner",owner());n.putLong("expires",expires);}
    @Override public void readAdditionalSaveData(CompoundTag n) {super.readAdditionalSaveData(n);if(n.hasUUID("Owner"))entityData.set(OWNER,Optional.of(n.getUUID("Owner")));expires=0;}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
