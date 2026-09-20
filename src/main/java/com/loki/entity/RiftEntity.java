package com.loki.entity;

import com.loki.Loki;
import com.loki.network.LokiNetwork;
import com.loki.server.PocketRealm;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import java.util.*;

/** A break in the surface of the world. It hangs where it was struck, holds for seven seconds and lets anyone who steps through reach their own sanctum. */
public final class RiftEntity extends Entity {
    private static final EntityDataAccessor<Integer> LIFE=SynchedEntityData.defineId(RiftEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SEED=SynchedEntityData.defineId(RiftEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HOMEWARD=SynchedEntityData.defineId(RiftEntity.class,EntityDataSerializers.BOOLEAN);
    public static final int DURATION=140,OPENING=14;
    private UUID caster;
    private final Map<UUID,Long> recent=new HashMap<>();

    public RiftEntity(EntityType<? extends RiftEntity> type,Level level) {super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {entityData.define(LIFE,DURATION);entityData.define(SEED,0);entityData.define(HOMEWARD,false);}
    public int life() {return entityData.get(LIFE);}
    public int seed() {return entityData.get(SEED);}
    public boolean homeward() {return entityData.get(HOMEWARD);}
    /** 0 while the mirror is still shattering outward, 1 once the break is fully open. */
    public float opening(float partial) {return Math.min(1,(DURATION-life()+partial)/OPENING);}
    public float closing(float partial) {return Math.min(1,(life()-partial)/12f);}

    public static RiftEntity open(ServerPlayer p,Vec3 at,boolean homeward) {
        RiftEntity e=new RiftEntity(Loki.RIFT.get(),p.level());
        e.setPos(at.x,at.y,at.z);
        // Stand the break square to the caster so the fracture reads as a mirror rather than an edge.
        e.setYRot(p.getYRot());e.setXRot(0);
        e.caster=p.getUUID();
        e.entityData.set(SEED,p.getRandom().nextInt(1<<20));
        e.entityData.set(HOMEWARD,homeward);
        p.level().addFreshEntity(e);
        p.level().playSound(null,e.blockPosition(),Loki.RIFT_OPEN.get(),SoundSource.PLAYERS,1.1f,1);
        LokiNetwork.fx(e,"rift_open");
        return e;
    }

    @Override public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        if(level().isClientSide)return;
        int remaining=life()-1;
        entityData.set(LIFE,remaining);
        if(remaining<=0) {
            level().playSound(null,blockPosition(),Loki.RIFT_CLOSE.get(),SoundSource.PLAYERS,.9f,1);
            LokiNetwork.fx(this,"rift_close");
            discard();
            return;
        }
        if(remaining>DURATION-OPENING)return;
        long now=level().getGameTime();
        recent.values().removeIf(v->v<now);
        AABB mouth=new AABB(getX()-1.1,getY()-.2,getZ()-1.1,getX()+1.1,getY()+2.4,getZ()+1.1);
        for(ServerPlayer player:level().getEntitiesOfClass(ServerPlayer.class,mouth,p->p.isAlive()&&!p.isSpectator())) {
            if(recent.containsKey(player.getUUID()))continue;
            recent.put(player.getUUID(),now+60);
            if(homeward())PocketRealm.leave(player);
            else PocketRealm.enter(player);
            if(isRemoved())return;
        }
    }

    @Override public boolean isPickable() {return false;}
    @Override public boolean canBeCollidedWith() {return false;}
    @Override public boolean isPushable() {return false;}
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount) {return false;}
    @Override public boolean shouldRenderAtSqrDistance(double distance) {return distance<9216;}
    @Override protected void readAdditionalSaveData(CompoundTag n) {
        entityData.set(LIFE,Math.min(DURATION,Math.max(1,n.getInt("life"))));
        entityData.set(SEED,n.getInt("seed"));
        entityData.set(HOMEWARD,n.getBoolean("homeward"));
        if(n.hasUUID("caster"))caster=n.getUUID("caster");
    }
    @Override protected void addAdditionalSaveData(CompoundTag n) {
        n.putInt("life",life());n.putInt("seed",seed());n.putBoolean("homeward",homeward());
        if(caster!=null)n.putUUID("caster",caster);
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
