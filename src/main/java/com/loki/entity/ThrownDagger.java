package com.loki.entity;

import com.loki.Loki;
import com.loki.data.Discipline;
import com.loki.server.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;

/**
 * A conjured blade in flight. It keeps its own orientation, buries itself in whatever it strikes and
 * opens a wound before dissolving. {@code STATE} is the single synced authority on where it lives:
 * {@code 0} in flight, {@code -1} buried in terrain, any other value the id of the body carrying it.
 */
public final class ThrownDagger extends ThrowableProjectile {
    private static final EntityDataAccessor<Integer> STATE=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> OFFSET_X=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> OFFSET_Y=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> OFFSET_Z=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> ILLUSORY=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.BOOLEAN);
    public static final int FLYING=0,IN_BLOCK=-1;
    private int life=200;

    public ThrownDagger(EntityType<? extends ThrownDagger> type,Level level) {super(type,level);}

    @Override protected void defineSynchedData() {
        entityData.define(STATE,FLYING);entityData.define(OFFSET_X,0f);entityData.define(OFFSET_Y,0f);entityData.define(OFFSET_Z,0f);
        entityData.define(ROLL,0f);entityData.define(ILLUSORY,false);
    }
    public int state() {return entityData.get(STATE);}
    public boolean flying() {return state()==FLYING;}
    public boolean illusory() {return entityData.get(ILLUSORY);}
    public float roll() {return entityData.get(ROLL);}
    public Vec3 offset() {return new Vec3(entityData.get(OFFSET_X),entityData.get(OFFSET_Y),entityData.get(OFFSET_Z));}

    public static ThrownDagger throwFrom(ServerPlayer p,Vec3 from,Vec3 aim,boolean illusory) {
        ThrownDagger e=new ThrownDagger(Loki.THROWN_DAGGER.get(),p.level());
        e.setOwner(p);e.setPos(from.x,from.y,from.z);
        e.entityData.set(ILLUSORY,illusory);
        e.entityData.set(ROLL,p.getRandom().nextFloat()*360);
        e.shoot(aim.x,aim.y,aim.z,1.85f,illusory?2.5f:.7f);
        p.level().addFreshEntity(e);
        p.level().playSound(null,p.blockPosition(),Loki.BLADE_THROW.get(),SoundSource.PLAYERS,.8f,.95f+p.getRandom().nextFloat()*.12f);
        return e;
    }

    @Override protected float getGravity() {return flying()?.026f:0;}
    @Override protected boolean canHitEntity(Entity e) {
        return flying()&&e!=getOwner()&&(!(getOwner() instanceof ServerPlayer p)||LokiServer.validTarget(p,e))&&super.canHitEntity(e);
    }

    @Override public void tick() {
        int state=state();
        if(state==FLYING) {
            Vec3 velocity=getDeltaMovement();
            super.tick();
            if(velocity.lengthSqr()>1e-6) {
                setYRot((float)(Mth.atan2(velocity.x,velocity.z)*Mth.RAD_TO_DEG));
                setXRot((float)(Mth.atan2(velocity.y,velocity.horizontalDistance())*Mth.RAD_TO_DEG));
                yRotO=getYRot();xRotO=getXRot();
            }
            if(!level().isClientSide&&tickCount>120)dissolve();
            return;
        }
        if(state!=IN_BLOCK) {
            Entity host=level().getEntity(state);
            if(host==null||!host.isAlive()) {
                // The body carrying the blade is gone; let it hang where it fell and fade out quickly.
                if(!level().isClientSide){entityData.set(STATE,IN_BLOCK);life=Math.min(life,30);}
            } else {
                Vec3 local=offset();
                float yaw=host instanceof LivingEntity living?living.yBodyRot:host.getYRot();
                double sin=Math.sin(-yaw*Mth.DEG_TO_RAD),cos=Math.cos(-yaw*Mth.DEG_TO_RAD);
                Vec3 world=new Vec3(local.x*cos+local.z*sin,local.y,-local.x*sin+local.z*cos);
                setPos(host.getX()+world.x,host.getY()+world.y,host.getZ()+world.z);
            }
        }
        setDeltaMovement(Vec3.ZERO);
        if(!level().isClientSide&&--life<=0)dissolve();
    }

    @Override protected void onHitEntity(EntityHitResult hit) {
        if(level().isClientSide||!flying())return;
        Entity victim=hit.getEntity();
        Vec3 contact=hit.getLocation();
        if(!illusory()&&getOwner() instanceof ServerPlayer p) {
            float damage=5+Math.min(4,com.loki.data.LokiData.mastery(p,Discipline.CONJURATION)*.005f);
            if(victim.hurt(damageSources().thrown(this,p),damage)) {
                if(victim instanceof LivingEntity living)Bleed.apply(p,living,1,160);
                LokiServer.reward(p,Discipline.CONJURATION,40);
            }
        }
        level().playSound(null,blockPosition(),Loki.BLADE_HIT.get(),SoundSource.PLAYERS,.9f,1f+random.nextFloat()*.15f);
        LokiNetwork.fx(this,"blade_bite",contact.x,contact.y,contact.z);
        if(illusory()){dissolve();return;}
        // Store the contact point in the victim's yaw frame so the blade rides along as it turns.
        float yaw=victim instanceof LivingEntity living?living.yBodyRot:victim.getYRot();
        Vec3 relative=contact.subtract(victim.position());
        double sin=Math.sin(yaw*Mth.DEG_TO_RAD),cos=Math.cos(yaw*Mth.DEG_TO_RAD);
        entityData.set(OFFSET_X,(float)(relative.x*cos+relative.z*sin));
        entityData.set(OFFSET_Y,(float)relative.y);
        entityData.set(OFFSET_Z,(float)(-relative.x*sin+relative.z*cos));
        entityData.set(STATE,victim.getId());
        life=170;
        setDeltaMovement(Vec3.ZERO);
    }

    @Override protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if(level().isClientSide||!flying())return;
        setPos(hit.getLocation().subtract(getDeltaMovement().normalize().scale(.12)));
        entityData.set(STATE,IN_BLOCK);
        setDeltaMovement(Vec3.ZERO);
        life=140;
        level().playSound(null,blockPosition(),Loki.BLADE_EMBED.get(),SoundSource.PLAYERS,.7f,.9f+random.nextFloat()*.2f);
    }

    private void dissolve() {if(!isRemoved()){LokiNetwork.fx(this,"dispel");discard();}}

    @Override protected void addAdditionalSaveData(CompoundTag n) {
        super.addAdditionalSaveData(n);n.putInt("state",state());n.putInt("life",life);n.putFloat("roll",roll());n.putBoolean("illusory",illusory());
    }
    @Override protected void readAdditionalSaveData(CompoundTag n) {
        super.readAdditionalSaveData(n);
        // A carried blade cannot be restored to a body that may not reload; drop it to a short-lived prop.
        entityData.set(STATE,n.getInt("state")==FLYING?FLYING:IN_BLOCK);
        life=Math.min(140,Math.max(1,n.getInt("life")));entityData.set(ROLL,n.getFloat("roll"));entityData.set(ILLUSORY,n.getBoolean("illusory"));
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
