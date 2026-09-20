package com.loki.entity;

import com.loki.Loki;
import com.loki.server.LokiServer;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;

/** Emerald sorcery. Style 0 is the quick spark, style 1 the charged impact; thrown steel lives in {@link ThrownDagger}. */
public final class SpellProjectile extends ThrowableProjectile {
    private static final EntityDataAccessor<Integer> STYLE=SynchedEntityData.defineId(SpellProjectile.class,EntityDataSerializers.INT);
    private boolean illusion;
    public SpellProjectile(EntityType<? extends SpellProjectile> type,Level level) {super(type,level);}
    @Override protected void defineSynchedData() {entityData.define(STYLE,0);}
    public int style() {return entityData.get(STYLE);}
    public static void cast(ServerPlayer p,Vec3 from,Vec3 aim,int style,boolean fake) {
        SpellProjectile e=new SpellProjectile(Loki.PROJECTILE.get(),p.level());
        e.setOwner(p);e.setPos(from);e.entityData.set(STYLE,style);e.illusion=fake;
        e.shoot(aim.x,aim.y,aim.z,style==1?1.45f:1.25f,0);
        p.level().addFreshEntity(e);
    }
    @Override protected float getGravity() {return 0;}
    @Override protected boolean canHitEntity(Entity e) {return e!=getOwner()&&(!(getOwner() instanceof ServerPlayer p)||LokiServer.validTarget(p,e))&&super.canHitEntity(e);}
    @Override protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if(level().isClientSide)return;
        if(!illusion&&getOwner() instanceof ServerPlayer p) {
            hit.getEntity().hurt(damageSources().thrown(this,p),style()==1?7:3);
            LokiServer.reward(p,com.loki.data.Discipline.SORCERY,30);
        }
        LokiNetwork.fx(this,"impact");discard();
    }
    @Override protected void onHitBlock(BlockHitResult hit) {super.onHitBlock(hit);if(!level().isClientSide){LokiNetwork.fx(this,"impact");discard();}}
    @Override public void tick() {super.tick();if(tickCount>100&&!level().isClientSide)discard();}
    @Override protected void addAdditionalSaveData(CompoundTag n) {super.addAdditionalSaveData(n);n.putInt("style",style());n.putBoolean("illusion",illusion);}
    @Override protected void readAdditionalSaveData(CompoundTag n) {super.readAdditionalSaveData(n);entityData.set(STYLE,n.getInt("style"));illusion=n.getBoolean("illusion");}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
