package com.loki.entity;

import com.loki.Loki;
import com.loki.server.LokiServer;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;

/**
 * Emerald Throw: a fistful of seidr hurled rather than a dot flying in a line.
 *
 * <p>The old spark was a sprite with a tapered wisp behind it, which read as a thrown pebble. This is a
 * body of energy — it carries a spin the renderer winds its husk around, it grows briefly as it leaves the
 * hand so the throw has a follow-through, and the charged form arrives with weight: more damage, a short
 * knockback and a small burst that catches whatever is pressed up against the target.
 *
 * <p>Style 0 is the quick throw, style 1 the charged one, and style 2 remains the conjured dagger's own
 * spell so nothing about thrown steel changes.
 */
public final class SpellProjectile extends ThrowableProjectile {
    private static final EntityDataAccessor<Integer> STYLE=SynchedEntityData.defineId(SpellProjectile.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SPIN=SynchedEntityData.defineId(SpellProjectile.class,EntityDataSerializers.FLOAT);
    /** How far a charged throw's burst reaches past the body it struck. */
    private static final double BURST=2.4;
    private boolean illusion;

    public SpellProjectile(EntityType<? extends SpellProjectile> type,Level level) {super(type,level);}
    @Override protected void defineSynchedData() {entityData.define(STYLE,0);entityData.define(SPIN,0f);}
    public int style() {return entityData.get(STYLE);}
    /** The seed the renderer winds the husk and the strands from, so every throw looks its own. */
    public float spin() {return entityData.get(SPIN);}
    /** 0 as it leaves the hand, 1 once it has fully opened; the throw follows through rather than popping. */
    public float opened(float partial) {return Math.min(1,(tickCount+partial)/4f);}

    public static void cast(ServerPlayer p,Vec3 from,Vec3 aim,int style,boolean fake) {
        SpellProjectile e=new SpellProjectile(Loki.PROJECTILE.get(),p.level());
        e.setOwner(p);
        e.setPos(from);
        e.entityData.set(STYLE,style);
        e.entityData.set(SPIN,p.getRandom().nextFloat()*6.2832f);
        e.illusion=fake;
        e.shoot(aim.x,aim.y,aim.z,style==1?1.65f:1.42f,0);
        p.level().addFreshEntity(e);
        if(!fake)p.level().playSound(null,e.blockPosition(),Loki.EMERALD_CAST.get(),SoundSource.PLAYERS,
            style==1?.85f:.6f,style==1?.92f:1.12f);
    }

    @Override protected float getGravity() {return 0;}
    @Override protected boolean canHitEntity(Entity e) {
        return e!=getOwner()&&(!(getOwner() instanceof ServerPlayer p)||LokiServer.validTarget(p,e))&&super.canHitEntity(e);
    }
    @Override protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if(level().isClientSide)return;
        boolean charged=style()==1;
        if(!illusion&&getOwner() instanceof ServerPlayer p) {
            hit.getEntity().hurt(damageSources().thrown(this,p),charged?9:3.5f);
            if(charged&&hit.getEntity() instanceof LivingEntity struck) {
                struck.knockback(.42,getX()-struck.getX(),getZ()-struck.getZ());
                // A charged throw does not stop dead on one body; it opens where it lands.
                for(LivingEntity near:level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(BURST),
                        e->e!=struck&&LokiServer.validTarget(p,e))) {
                    near.hurt(damageSources().indirectMagic(this,p),3);
                    near.knockback(.22,getX()-near.getX(),getZ()-near.getZ());
                }
            }
            LokiServer.reward(p,com.loki.data.Discipline.SORCERY,30);
        }
        LokiNetwork.fx(this,charged?"emerald_burst":"impact");
        discard();
    }
    @Override protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if(level().isClientSide)return;
        LokiNetwork.fx(this,style()==1?"emerald_burst":"impact");
        discard();
    }
    @Override public void tick() {super.tick();if(tickCount>100&&!level().isClientSide)discard();}
    @Override protected void addAdditionalSaveData(CompoundTag n) {
        super.addAdditionalSaveData(n);
        n.putInt("style",style());n.putFloat("spin",spin());n.putBoolean("illusion",illusion);
    }
    @Override protected void readAdditionalSaveData(CompoundTag n) {
        super.readAdditionalSaveData(n);
        entityData.set(STYLE,n.getInt("style"));entityData.set(SPIN,n.getFloat("spin"));illusion=n.getBoolean("illusion");
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
