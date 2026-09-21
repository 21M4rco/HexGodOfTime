package com.hexgodofstories.warping;

import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraft.network.syncher.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraftforge.network.NetworkHooks;

/** Cohesive falling architecture or a suspended execution spear; no terrain griefing. */
public final class WarpHazard extends Entity {
    private static final EntityDataAccessor<Integer> KIND=SynchedEntityData.defineId(WarpHazard.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> RELEASED=SynchedEntityData.defineId(WarpHazard.class,EntityDataSerializers.BOOLEAN);
    private double cell;private int index;
    public WarpHazard(EntityType<?> type,Level level){super(type,level);noPhysics=true;setNoGravity(true);}
    protected void defineSynchedData(){entityData.define(KIND,1);entityData.define(RELEASED,false);}
    public int kind(){return entityData.get(KIND);}
    public boolean released(){return entityData.get(RELEASED);}
    public void configure(int kind,double cell,int index){entityData.set(KIND,kind);this.cell=cell;this.index=index;}
    public void release(Vec3 direction){if(!spear()||released())return;entityData.set(RELEASED,true);setDeltaMovement(direction.scale(1.7).add(0,-.8,0));}
    @Override public void tick(){
        super.tick();if(level().isClientSide)return;
        if(spear()&&!released())return;
        double speed=spear()?getDeltaMovement().y:-.15-index%5*.035;
        Vec3 movement=spear()?getDeltaMovement():new Vec3(0,speed,0);
        AABB sweep=getBoundingBox().expandTowards(movement).inflate(spear()?.5:1);
        for(LivingEntity e:level().getEntitiesOfClass(LivingEntity.class,sweep,e->e.isAlive()&&!Warping.sovereign(e))){
            if(spear()){e.hurt(damageSources().magic(),24);e.setDeltaMovement(movement.scale(.5));e.hurtMarked=true;discard();return;}
            // Riders are carried by ordinary collision now - see canBeCollidedWith - so nothing is
            // teleported onto the platform every tick. Only what the mass runs through is hurt.
            if(e.getY()+e.getBbHeight()>getY()&&e.getY()<getY()+height()-.35&&tickCount%20==0)
                e.hurt(damageSources().fallingBlock(this),10);
        }
        setPos(position().add(movement));
        if(getY()<45){if(spear()){discard();return;}setPos(getX(),237,getZ());}
        if(spear()&&released()&&tickCount>2400)discard();
    }
    /** Kinds 1 and 5 are the suspended execution spears; everything else is architecture. */
    public boolean spear(){return kind()==1||kind()==5;}
    public float height(){return kind()==3?10:spear()?3:2;}
    @Override public EntityDimensions getDimensions(Pose pose){return EntityDimensions.fixed(spear()?1:6,height());}
    /** Cohesive debris is solid: victims stand on it and are carried, instead of being repositioned. */
    @Override public boolean canBeCollidedWith(){return !spear();}
    @Override public boolean isPickable(){return true;}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(KIND.equals(key))refreshDimensions();}
    protected void readAdditionalSaveData(CompoundTag n){entityData.set(KIND,n.getInt("kind"));entityData.set(RELEASED,n.getBoolean("released"));cell=n.getDouble("cell");index=n.getInt("index");}
    protected void addAdditionalSaveData(CompoundTag n){n.putInt("kind",kind());n.putBoolean("released",released());n.putDouble("cell",cell);n.putInt("index",index);}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket(){return NetworkHooks.getEntitySpawningPacket(this);}
}
