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
    private static final EntityDataAccessor<Boolean> VACUUM=SynchedEntityData.defineId(RiftEntity.class,EntityDataSerializers.BOOLEAN);
    public static final int DURATION=140,OPENING=14,CHARGE_TICKS=12,PULL_TICKS=10;
    private boolean charging;
    private int pullAge;
    private final List<UUID> captured=new ArrayList<>();
    private UUID caster;
    private final Map<UUID,Long> recent=new HashMap<>();

    public RiftEntity(EntityType<? extends RiftEntity> type,Level level) {super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {entityData.define(LIFE,DURATION);entityData.define(SEED,0);entityData.define(HOMEWARD,false);entityData.define(VACUUM,false);}
    public boolean vacuum(){return entityData.get(VACUUM);}
    public void armCharge(){charging=true;}
    public void releaseCharge(){charging=false;}
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
        e.entityData.set(HOMEWARD,PocketRealm.inside(p.level()));
        p.level().addFreshEntity(e);
        p.level().playSound(null,e.blockPosition(),Loki.RIFT_OPEN.get(),SoundSource.PLAYERS,.85f,.96f+p.getRandom().nextFloat()*.08f);
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
        if(charging&&!vacuum()&&DURATION-remaining>=CHARGE_TICKS) {
            charging=false;entityData.set(VACUUM,true);
            Vec3 centre=position().add(0,1,0);
            for(Entity e:level().getEntities(this,new AABB(centre,centre).inflate(5),this::eligible))
                if(e.getBoundingBox().getCenter().distanceToSqr(centre)<=25)captured.add(e.getUUID());
        }
        if(vacuum()){pullAndCross();return;}
        if(remaining>DURATION-OPENING)return;
        long now=level().getGameTime();
        recent.values().removeIf(v->v<now);
        AABB mouth=new AABB(getX()-1.1,getY()-.2,getZ()-1.1,getX()+1.1,getY()+2.4,getZ()+1.1);
        ServerPlayer owner=((net.minecraft.server.level.ServerLevel)level()).getServer().getPlayerList().getPlayer(caster);
        if(owner==null)return;
        for(Entity entity:level().getEntities(this,mouth,this::eligible)) {
            if(recent.containsKey(entity.getUUID())||PocketRealm.crossingCooldown(entity))continue;
            boolean crossed=PocketRealm.cross(entity,owner);
            recent.put(entity.getUUID(),now+(crossed?60:20));
            if(isRemoved())return;
        }
    }
    private boolean eligible(Entity e) {
        return e.isAlive()&&!e.isSpectator()&&!(e instanceof RiftEntity)&&!(e instanceof ThroneSeat)&&e.canChangeDimensions();
    }
    private void pullAndCross() {
        var source=(net.minecraft.server.level.ServerLevel)level();
        ServerPlayer owner=source.getServer().getPlayerList().getPlayer(caster);
        if(owner==null||!owner.isAlive()||owner.level()!=level()){discard();return;}
        pullAge++;
        Vec3 centre=position().add(0,1,0);
        for(UUID id:captured) {
            Entity e=source.getEntity(id);if(e==null||!eligible(e))continue;
            Vec3 delta=centre.subtract(e.getBoundingBox().getCenter());
            e.setDeltaMovement(delta.scale(.28).add(0,.045,0));e.hurtMarked=true;e.resetFallDistance();
        }
        if(pullAge<PULL_TICKS)return;
        // Transfer a snapshot, caster last: its dimension-change cleanup may discard this rift.
        List<Entity> group=new ArrayList<>();
        for(UUID id:captured){Entity e=source.getEntity(id);if(e!=null&&eligible(e))group.add(e);}
        group.sort(Comparator.comparing(e->e.getUUID().equals(caster)));
        int failed=0;
        for(Entity e:group)if(!PocketRealm.cross(e,owner)){e.setDeltaMovement(Vec3.ZERO);failed++;}
        source.playSound(null,blockPosition(),Loki.RIFT_CLOSE.get(),SoundSource.PLAYERS,.9f,1);
        LokiNetwork.fx(this,"rift_close");discard();
        if(failed>0)owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Some entities could not cross safely and remain at the source."),true);
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
