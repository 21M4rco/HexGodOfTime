package com.loki.entity;

import com.loki.server.PocketRealm;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/** Invisible, transient seat. Vanilla riding supplies the sitting pose and sneak-to-stand controls. */
public final class ThroneSeat extends Entity {
    public ThroneSeat(EntityType<? extends ThroneSeat> type,Level level){super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override public void tick() {
        super.tick();setDeltaMovement(Vec3.ZERO);
        // Sitting in your own hall mends you. Renewed on a short lease, so it lapses when you stand.
        com.loki.server.Throne.crown(this);
        if(!level().isClientSide&&(getPassengers().isEmpty()||!PocketRealm.inside(level())
            ||!level().getBlockState(blockPosition().below()).is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)))discard();
    }
    @Override public double getPassengersRidingOffset(){return 0;}
    @Override protected boolean canAddPassenger(Entity passenger){return getPassengers().isEmpty();}
    @Override public boolean isPickable(){return false;}
    @Override public boolean isPushable(){return false;}
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return new Vec3(getX(),PocketRealm.FLOOR_Y+4,getZ()+3.5);
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket(){return NetworkHooks.getEntitySpawningPacket(this);}
}
