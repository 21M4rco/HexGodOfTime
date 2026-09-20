package com.loki.entity;

import com.loki.Loki;
import com.loki.network.LokiNetwork;
import com.loki.server.SanctumWard;
import net.minecraft.core.BlockPos;
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
 * A meteor the sanctum pulls down on whoever is troubling its owner.
 *
 * <p>It is a falling rock, and it is written like one. It comes in from far above the build limit on a long
 * diagonal, it <em>accelerates</em> the whole way down under its own weight rather than cruising at a set
 * speed, and it only steers a little — enough to be Loki's doing, nowhere near enough to read as a guided
 * missile. It burns hotter the lower and faster it gets, because that is where the air is, and the renderer
 * and the audio are both driven from that one number.
 *
 * <p>What it does not do is damage the island. There is no explosion call anywhere in it: the impact is
 * harm to bodies, a great deal of fire and light, and a very loud arrival. The throne, the tree and the
 * ground are untouched, and the owner is never hurt by it.
 */
public final class StarfallEntity extends Entity {
    private static final EntityDataAccessor<Integer> QUARRY=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CHARGE=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEAT=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> SEED=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.INT);

    /** Thirteen hearts where it lands, falling off across a small radius. */
    public static final float DAMAGE=26,SPLASH_RADIUS=4;
    private static final int LIFETIME=340;
    /** Entry speed, the pull that builds on it, and the speed the air will not let it pass. */
    private static final double ENTRY=.85,PULL=.12,TERMINAL=3.4;
    /** How little it corrects. A meteor is not a missile. */
    private static final double STEER=.035;

    private UUID owner;
    private int age;

    public StarfallEntity(EntityType<? extends StarfallEntity> type,Level level) {super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {
        entityData.define(QUARRY,0);entityData.define(CHARGE,0f);entityData.define(HEAT,0f);entityData.define(SEED,0);
    }

    /** 0 far out, 1 about to land. */
    public float charge() {return entityData.get(CHARGE);}
    /** How hard it is burning: speed and thickening air together. Drives the flame and the roar. */
    public float heat() {return entityData.get(HEAT);}
    public int seed() {return entityData.get(SEED);}
    public int quarryId() {return entityData.get(QUARRY);}

    public static void fall(ServerPlayer caster,LivingEntity quarry,Vec3 from) {
        StarfallEntity star=new StarfallEntity(Loki.STARFALL.get(),quarry.level());
        star.owner=caster.getUUID();
        star.setPos(from.x,from.y,from.z);
        star.entityData.set(QUARRY,quarry.getId());
        star.entityData.set(SEED,caster.getRandom().nextInt(1 << 20));
        Vec3 aim=quarry.getBoundingBox().getCenter().subtract(from);
        star.setDeltaMovement(aim.lengthSqr()<1e-6?new Vec3(0,-ENTRY,0):aim.normalize().scale(ENTRY));
        quarry.level().addFreshEntity(star);
        LokiNetwork.fx(star,"meteor_entry");
        // The sound of something arriving through the air, heard long before it lands.
        quarry.level().playSound(null,BlockPos.containing(from),Loki.METEOR_ROAR.get(),SoundSource.WEATHER,4.2f,.62f);
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
        // Weight first. It is falling, and everything else is a correction on top of that.
        velocity=velocity.add(0,-PULL,0);
        if(quarry!=null&&quarry.isAlive()) {
            Vec3 aim=quarry.getBoundingBox().getCenter().subtract(position());
            double distance=aim.length();
            entityData.set(CHARGE,(float)Math.max(0,Math.min(1,1-distance/90)));
            if(distance<1.6){burst(quarry);return;}
            velocity=velocity.add(aim.scale(STEER/Math.max(1e-4,distance)));
        }
        double speed=velocity.length();
        if(speed>TERMINAL)velocity=velocity.scale(TERMINAL/speed);
        setDeltaMovement(velocity);
        // Burning hardest when it is fast and low: that is where the atmosphere is.
        entityData.set(HEAT,(float)Math.min(1,speed/TERMINAL*.65+charge()*.5));
        Vec3 next=position().add(velocity);
        // The island stops the meteor. Nothing about that damages the island.
        var hit=level().clip(new ClipContext(position(),next,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(hit.getType()!=HitResult.Type.MISS) {
            setPos(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z);
            burst(null);return;
        }
        setPos(next.x,next.y,next.z);
    }

    /** Fire, harm and a very loud arrival. Never terrain, and never the owner. */
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
            // It was on fire the whole way down; so is whatever it hit.
            if(share>.3f&&!victim.fireImmune())victim.setSecondsOnFire(4);
        }
        LokiNetwork.fx(this,"meteor_impact");
        level().playSound(null,blockPosition(),Loki.METEOR_IMPACT.get(),SoundSource.WEATHER,3.6f,.58f);
        discard();
    }

    @Override public boolean isPickable() {return false;}
    @Override public boolean canBeCollidedWith() {return false;}
    @Override public boolean isPushable() {return false;}
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount) {return false;}
    /** Visible from a long way off on purpose: the point is watching it come down. */
    @Override public boolean shouldRenderAtSqrDistance(double distance) {return distance<90000;}
    @Override protected void readAdditionalSaveData(CompoundTag n) {if(n.hasUUID("owner"))owner=n.getUUID("owner");age=n.getInt("age");}
    @Override protected void addAdditionalSaveData(CompoundTag n) {if(owner!=null)n.putUUID("owner",owner);n.putInt("age",age);}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
