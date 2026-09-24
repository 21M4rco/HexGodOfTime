package com.hexgodofstories.entity;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.Nothingness;
import com.hexgodofstories.server.UnknownAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import java.util.*;

/** Server-authoritative temporary predator, using the imported two-arm Blockbench rig. */
public final class UnknownEntity extends Monster implements GeoEntity {
    public static final int MANIFEST_TICKS=200, HUNT_TICKS=1200;
    private static final EntityDataAccessor<Integer> PHASE=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STRIKE=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> CHASING=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> ROARING=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> LEAPING=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> STALKING=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.BOOLEAN);
    private final AnimatableInstanceCache animations=GeckoLibUtil.createInstanceCache(this);
    private final Set<BlockPos> removed=new LinkedHashSet<>();
    private UUID owner, quarry;
    private long endMillis, restoreDue;
    private int strikeTicks, growlTicks, jumpTicks, lastScan;
    private boolean finished;
    private String current="idle";
    public UnknownEntity(EntityType<? extends UnknownEntity> type,Level level) {
        super(type,level);
        setNoGravity(true);
        noPhysics=true;
        setPersistenceRequired();
        xpReward=0;
    }
    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH,1024).add(Attributes.MOVEMENT_SPEED,1.1).add(Attributes.FOLLOW_RANGE,18).add(Attributes.KNOCKBACK_RESISTANCE,1);
    }
    @Override protected void defineSynchedData() {super.defineSynchedData();entityData.define(PHASE,0);entityData.define(STRIKE,0);entityData.define(CHASING,false);entityData.define(ROARING,false);entityData.define(LEAPING,false);entityData.define(STALKING,false);}
    @Override protected void registerGoals() {}
    public void summon(ServerPlayer caster,long finishAt) {
        owner=caster.getUUID();endMillis=finishAt;restoreDue=((ServerLevel)level()).getGameTime()+MANIFEST_TICKS+HUNT_TICKS+200;
        setYRot(caster.getYRot()+180);setYBodyRot(getYRot());setYHeadRot(getYRot());
        HexNetwork.fx(this,"rift_open");
    }
    public int phase(){return entityData.get(PHASE);}
    public UUID summoner(){return owner;}
    private boolean eligible(LivingEntity v) {
        if(v==this||v instanceof UnknownEntity||!v.isAlive()||v.isSpectator()||v instanceof Player p&&(p.isCreative()||p.isSpectator()))return false;
        return true; // No exemption for the caster, friendly NPCs, pets or summoned creatures.
    }
    private boolean aware(LivingEntity candidate) {
        Vec3 to=candidate.getEyePosition().subtract(getEyePosition());
        if(to.lengthSqr()>18*18)return false;
        Vec3 flat=new Vec3(to.x,0,to.z);
        Vec3 look=Vec3.directionFromRotation(0,getYRot());
        // Only immediate nearby sounds carry through cover; distant targets need direct sight.
        if(flat.lengthSqr()>8*8&&flat.normalize().dot(look)<0.12)return false;
        if(to.lengthSqr()<8*8)return true;
        HitResult hit=level().clip(new ClipContext(getEyePosition(),candidate.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        return hit.getType()==HitResult.Type.MISS;
    }
    private LivingEntity pick() {
        LivingEntity best=null;double distance=Double.MAX_VALUE;
        for(LivingEntity entity:level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(18),this::eligible)) {
            double d=distanceToSqr(entity);
            if(d<distance&&aware(entity)){distance=d;best=entity;}
        }
        return best;
    }
    @Override public void tick() {
        setDeltaMovement(Vec3.ZERO);
        super.tick();
        if(level().isClientSide)return;
        if(System.currentTimeMillis()>=endMillis||tickCount>=MANIFEST_TICKS+HUNT_TICKS+40){finish();return;}
        if(tickCount<MANIFEST_TICKS) {
            entityData.set(PHASE,0);
            if(tickCount%10==0) {
                UnknownAbility.refreshPortal(this);
                HexNetwork.pilgrimEffect(this,"unknown_manifest",position(),1);
                ((ServerLevel)level()).sendParticles(HexGodOfStories.NEBULA.get(),getX(),getY()+1,getZ(),18,1.8,.5,1.8,.05);
            }
            return;
        }
        entityData.set(PHASE,1);
        if(tickCount==MANIFEST_TICKS)UnknownAbility.closePortal(this);
        UUID previous=quarry;
        LivingEntity target=quarry==null?null:((ServerLevel)level()).getEntity(quarry) instanceof LivingEntity v?v:null;
        if(target==null||!eligible(target)||distanceToSqr(target)>26*26||tickCount-lastScan>15&&(!aware(target)&&distanceToSqr(target)>18*18)) {
            target=pick();quarry=target==null?null:target.getUUID();lastScan=tickCount;
        }
        if(strikeTicks>0){strikeTicks--;entityData.set(STRIKE,strikeTicks);}
        if(growlTicks>0)growlTicks--;
        if(jumpTicks>0)jumpTicks--;
        if(target!=null&&!target.getUUID().equals(previous)&&growlTicks==0){
            growlTicks=49;
            level().playSound(null,blockPosition(),HexGodOfStories.PILGRIM_ROAR.get(),net.minecraft.sounds.SoundSource.HOSTILE,2.4f,.72f);
            HexNetwork.pilgrimEffect(this,"unknown_roar",position(),1.4f);
        }
        entityData.set(ROARING,growlTicks>0);entityData.set(LEAPING,jumpTicks>0);
        entityData.set(CHASING,target!=null);
        if(target!=null) {
            Vec3 direction=target.position().subtract(position());
            Vec3 horizontal=new Vec3(direction.x,0,direction.z);
            if(horizontal.lengthSqr()>.0001) {
                Vec3 goal=horizontal.normalize();float yaw=(float)(Math.atan2(-goal.x,goal.z)*180/Math.PI);
                setYRot(yaw);setYBodyRot(yaw);setYHeadRot(yaw);
                double speed=strikeTicks>12?1.7:(distanceToSqr(target)>36?1.02:.57);
                Vec3 step=goal.scale(speed).add(0,Math.max(-.5,Math.min(.82,direction.y*.22)),0);
                if(direction.y>2.5&&jumpTicks==0){jumpTicks=35;entityData.set(LEAPING,true);}
                Vec3 dest=position().add(step);
                if(level().hasChunkAt(BlockPos.containing(dest))) {
                    excavate((ServerLevel)level(),dest,Math.max(0,96-removed.size()/250));
                    setPos(dest.x,dest.y,dest.z);
                }
            }
            if(distanceToSqr(target)<55&&strikeTicks==0) {
                strikeTicks=27;entityData.set(STRIKE,strikeTicks);
                level().playSound(null,blockPosition(),HexGodOfStories.PILGRIM_LUNGE.get(),net.minecraft.sounds.SoundSource.HOSTILE,2,.65f);
            }
        } else {
            boolean stalking=tickCount%180>70;
            entityData.set(STALKING,stalking);
            if(stalking) {
                if(tickCount%90==0)setYRot(getYRot()+(random.nextFloat()-.5f)*65);
                Vec3 ahead=Vec3.directionFromRotation(0,getYRot()).scale(.065);
                Vec3 dest=position().add(ahead);
                if(level().hasChunkAt(BlockPos.containing(dest)))setPos(dest.x,dest.y,dest.z);
            }
        }
        if(strikeTicks==14||tickCount%6==0) {
            // The lunge has a generous swept bite; body contact remains lethal while chasing.
            AABB mouth=getBoundingBox().inflate(strikeTicks==14?4.5:2.2,1, strikeTicks==14?4.5:2.2);
            for(LivingEntity victim:level().getEntitiesOfClass(LivingEntity.class,mouth,this::eligible)) {
                if(distanceToSqr(victim)>100)continue;
                ServerPlayer caster=owner==null?null:level().getServer().getPlayerList().getPlayer(owner);
                DamageSource source=caster!=null?victim.damageSources().playerAttack(caster):victim.damageSources().mobAttack(this);
                victim.invulnerableTime=0;
                victim.hurt(source,100000);
                if(!victim.isAlive())HexNetwork.fx(victim,"demanifest");
            }
        }
        if(tickCount%12==0) {
            excavate((ServerLevel)level(),position(),48);
            ((ServerLevel)level()).sendParticles(HexGodOfStories.TEMPORAL_DUST.get(),getX(),getY()+1,getZ(),15,1.8,1,1.8,.1);
        }
    }
    private void excavate(ServerLevel level,Vec3 centre,int budget) {
        if(budget<=0||removed.size()>=14000)return;
        BlockPos mid=BlockPos.containing(centre);
        int used=0;
        // Break only loaded cells; no remote chunk generation or cascading loot/block-entity drops.
        for(int dy=0;dy<=8&&used<budget;dy++)for(int dx=-2;dx<=2&&used<budget;dx++)for(int dz=-2;dz<=2&&used<budget;dz++) {
            BlockPos pos=mid.offset(dx,dy,dz);
            if(!level.hasChunkAt(pos)||removed.contains(pos))continue;
            var state=level.getBlockState(pos);
            if(state.isAir()||state.is(HexGodOfStories.NOTHINGNESS.get()))continue;
            if(Nothingness.takeBeam(level,pos,restoreDue,false)){removed.add(pos.immutable());used++;}
        }
    }
    private void finish() {
        if(finished||level().isClientSide)return;finished=true;
        entityData.set(PHASE,2);
        UnknownAbility.closePortal(this);
        ServerLevel server=(ServerLevel)level();
        UnknownAbility.queueRestoration(server,removed,restoreDue);
        for(int i=0;i<14;i++)server.sendParticles(HexGodOfStories.TEMPORAL_DUST.get(),getX(),getY()+i*.55,getZ(),28,2.3,.8,2.3,.13);
        HexNetwork.fx(this,"demanifest");HexNetwork.pilgrimEffect(this,"unknown_vanish",position(),2);
        UnknownAbility.finished(this);
        discard();
    }
    @Override public boolean hurt(DamageSource source,float amount){return false;}
    @Override public void push(Entity other){}
    @Override public boolean isPushable(){return false;}
    @Override public boolean canBeLeashed(Player player){return false;}
    @Override public boolean removeWhenFarAway(double distance){return false;}
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if(owner!=null)tag.putUUID("Owner",owner);
        tag.putLong("EndMillis",endMillis);tag.putLong("RestoreDue",restoreDue);
        ListTag cells=new ListTag();for(BlockPos pos:removed)cells.add(LongTag.valueOf(pos.asLong()));tag.put("Wounds",cells);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner=tag.hasUUID("Owner")?tag.getUUID("Owner"):null;
        endMillis=tag.getLong("EndMillis");restoreDue=tag.getLong("RestoreDue");
        removed.clear();ListTag cells=tag.getList("Wounds",4);
        for(int i=0;i<cells.size();i++)removed.add(BlockPos.of(((LongTag)cells.get(i)).getAsLong()));
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket(){return NetworkHooks.getEntitySpawningPacket(this);}
    private <T extends UnknownEntity> PlayState animate(software.bernie.geckolib.core.animation.AnimationState<T> state){
        String name=phase()==0?"spawn":phase()==2?"death":strikeTicks>0?"bite":isInWater()?"swim":entityData.get(CHASING)?"run":"idle";
        // Attack countdown synchronises on the server, but the client has its own current keyframe clock.
        if(phase()==1&&entityData.get(STRIKE)>0)name="bite";
        if(phase()==1&&entityData.get(STRIKE)==0){
            if(entityData.get(ROARING))name="roar";
            else if(entityData.get(LEAPING))name="jump";
            else if(!entityData.get(CHASING))name=entityData.get(STALKING)?"walk":"idle";
        }
        return state.setAndContinue(phase()==0||name.equals("bite")||name.equals("jump")||name.equals("roar")||name.equals("death")
            ?RawAnimation.begin().thenPlay("animation.unknown."+name)
            :RawAnimation.begin().thenLoop("animation.unknown."+name));
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers){
        controllers.add(new AnimationController<>(this,"unknown",2,this::animate));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache(){return animations;}
}
