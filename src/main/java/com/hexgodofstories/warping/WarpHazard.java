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
    public void release(Vec3 direction){if((kind()!=1&&kind()!=5)||released())return;entityData.set(RELEASED,true);setDeltaMovement(direction.scale(1.7).add(0,-.8,0));}
    @Override public void tick(){
        super.tick();if(level().isClientSide)return;
        if((kind()==1||kind()==5)&&!released())return;
        double speed=(kind()==1||kind()==5)?getDeltaMovement().y:-.15-index%5*.035;
        Vec3 movement=(kind()==1||kind()==5)?getDeltaMovement():new Vec3(0,speed,0);
        AABB sweep=getBoundingBox().expandTowards(movement).inflate(kind()==1?.5:1);
        // Falling architecture does not check who opened the way in. Exempting the caster meant the
        // Frozen Moment's spears passed straight through the one person most likely to be standing
        // in front of them.
        for(LivingEntity e:level().getEntitiesOfClass(LivingEntity.class,sweep,e->e.isAlive()&&!e.isSpectator()
                &&!(e instanceof net.minecraft.world.entity.player.Player p&&p.isCreative()))){
            if(kind()==1||kind()==5){e.hurt(damageSources().magic(),24);e.setDeltaMovement(movement.scale(.5));discard();return;}
            double top=getY()+height();
            // Riding the slab down: a server side move alone never reaches a player's client.
            if(e.getY()>=top-.5){
                if(e instanceof net.minecraft.server.level.ServerPlayer p)p.connection.teleport(e.getX(),top+movement.y,e.getZ(),p.getYRot(),p.getXRot());
                else e.teleportTo(e.getX(),top+movement.y,e.getZ());
                e.fallDistance=0;
            }
            else if(tickCount%20==0)e.hurt(damageSources().fallingBlock(this),10);
        }
        setPos(position().add(movement));
        if(getY()<45){if(kind()==1||kind()==5){discard();return;}setPos(getX(),237,getZ());}
        if((kind()==1||kind()==5)&&released()&&tickCount>2400)discard();
    }
    public float height(){return kind()==3?10:kind()==1?3:2;}
    @Override public EntityDimensions getDimensions(Pose pose){return EntityDimensions.fixed(kind()==1?1:6,height());}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(KIND.equals(key))refreshDimensions();}
    protected void readAdditionalSaveData(CompoundTag n){entityData.set(KIND,n.getInt("kind"));entityData.set(RELEASED,n.getBoolean("released"));cell=n.getDouble("cell");index=n.getInt("index");}
    protected void addAdditionalSaveData(CompoundTag n){n.putInt("kind",kind());n.putBoolean("released",released());n.putDouble("cell",cell);n.putInt("index",index);}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket(){return NetworkHooks.getEntitySpawningPacket(this);}
}
