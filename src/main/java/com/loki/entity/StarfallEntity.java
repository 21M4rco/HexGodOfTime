package com.loki.entity;

import com.loki.Loki;
import com.loki.network.LokiNetwork;
import com.loki.server.SanctumWard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;
import java.util.UUID;

/**
 * A falling star the sanctum throws at whoever is troubling its owner.
 *
 * <p>It comes in high and at an angle, not straight down, and it does not fly true: it wobbles,
 * over-corrects and drifts, closing on its quarry with the unsteady insistence of something that is
 * only half a projectile. It burns brighter the nearer it gets.
 *
 * <p>It never touches the island. There is no explosion call anywhere in it — the impact is damage
 * to bodies and a great deal of light, and the throne, the tree and the ground are untouched.
 */
public final class StarfallEntity extends Entity {
    private static final EntityDataAccessor<Integer> QUARRY=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CHARGE=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.FLOAT);

    /** Ten hearts where it lands, falling off across a small radius. */
    public static final float DAMAGE=20,SPLASH_RADIUS=3.2f;
    private static final int LIFETIME=120;
    private static final double SPEED=1.05,TURN=.19;

    private UUID owner;
    private int age;

    public StarfallEntity(EntityType<? extends StarfallEntity> type,Level level) {super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {entityData.define(QUARRY,0);entityData.define(CHARGE,0f);}

    /** 0 far out, 1 about to land: the client brightens the core and thickens the trail with it. */
    public float charge() {return entityData.get(CHARGE);}
    public int quarryId() {return entityData.get(QUARRY);}

    public static void fall(ServerPlayer caster,LivingEntity quarry,Vec3 from) {
        StarfallEntity star=new StarfallEntity(Loki.STARFALL.get(),quarry.level());
        star.owner=caster.getUUID();
        star.setPos(from.x,from.y,from.z);
        star.entityData.set(QUARRY,quarry.getId());
        Vec3 aim=quarry.getBoundingBox().getCenter().subtract(from);
        star.setDeltaMovement(aim.lengthSqr()<1e-6?new Vec3(0,-SPEED,0):aim.normalize().scale(SPEED));
        quarry.level().addFreshEntity(star);
        LokiNetwork.fx(star,"starfall_open");
        quarry.level().playSound(null,star.blockPosition(),Loki.RIFT_OPEN.get(),SoundSource.AMBIENT,.6f,1.7f);
    }

    @Override public void tick() {
        super.tick();
        if(level().isClientSide) {
            setPos(getX()+getDeltaMovement().x,getY()+getDeltaMovement().y,getZ()+getDeltaMovement().z);
            return;
        }
        if(++age>LIFETIME){burst(null);return;}
        Entity quarry=level().getEntity(quarryId());
        Vec3 velocity=getDeltaMovement();
        if(quarry!=null&&quarry.isAlive()) {
            Vec3 aim=quarry.getBoundingBox().getCenter().subtract(position());
            double distance=aim.length();
            entityData.set(CHARGE,(float)Math.max(0,Math.min(1,1-distance/48)));
            if(distance<1.4){burst(quarry);return;}
            Vec3 want=aim.scale(1/Math.max(1e-4,distance));
            // Over-correct, then let the wobble pull it off line again: it hunts, it does not track.
            double wander=age*.31;
            Vec3 wobble=new Vec3(Math.sin(wander)*.34,Math.sin(wander*.7+1.3)*.12,Math.cos(wander*1.17)*.34);
            velocity=velocity.add(want.scale(TURN)).add(wobble.scale(.09));
            double speed=SPEED+charge()*.55;
            velocity=velocity.normalize().scale(speed);
        } else velocity=velocity.add(0,-.05,0);
        setDeltaMovement(velocity);
        Vec3 next=position().add(velocity);
        // The island itself stops the star, but nothing about that damages it.
        var hit=level().clip(new ClipContext(position(),next,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(hit.getType()!=HitResult.Type.MISS){setPos(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z);burst(null);return;}
        setPos(next.x,next.y,next.z);
    }

    /** Light and harm, never terrain. The owner is untouched by every part of it. */
    private void burst(Entity direct) {
        if(isRemoved())return;
        ServerPlayer caster=owner==null||!(level() instanceof ServerLevel level)?null:level.getServer().getPlayerList().getPlayer(owner);
        Vec3 at=position();
        for(LivingEntity victim:level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(SPLASH_RADIUS))) {
            if(victim==caster||SanctumWard.owner(victim))continue;
            if(caster!=null&&victim.getUUID().equals(owner))continue;
            double distance=victim.getBoundingBox().getCenter().distanceTo(at);
            float share=victim==direct?1:(float)Math.max(0,1-distance/SPLASH_RADIUS);
            if(share<=.05f)continue;
            var source=caster!=null?victim.damageSources().indirectMagic(this,caster):victim.damageSources().magic();
            victim.invulnerableTime=0;
            victim.hurt(source,DAMAGE*share);
        }
        LokiNetwork.fx(this,"starfall_impact");
        level().playSound(null,blockPosition(),Loki.ASCEND.get(),SoundSource.AMBIENT,.85f,1.45f);
        discard();
    }

    @Override public boolean isPickable() {return false;}
    @Override public boolean canBeCollidedWith() {return false;}
    @Override public boolean isPushable() {return false;}
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount) {return false;}
    @Override public boolean shouldRenderAtSqrDistance(double distance) {return distance<36864;}
    @Override protected void readAdditionalSaveData(CompoundTag n) {if(n.hasUUID("owner"))owner=n.getUUID("owner");age=n.getInt("age");}
    @Override protected void addAdditionalSaveData(CompoundTag n) {if(owner!=null)n.putUUID("owner",owner);n.putInt("age",age);}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
