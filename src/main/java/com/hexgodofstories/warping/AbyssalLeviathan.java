package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import java.util.List;

/**
 * One hunter to a Void Sea instance, and it is hostile to everything alive in
 * it: players, the caster who opened the trap, and every other creature.
 *
 * It is deliberately slow. It does not burst, it does not ambush, and it never
 * stops. The bite is a telegraph the length of a second and a half - the jaw
 * opens seven ticks before the strike lands - so a victim always sees it
 * coming and can always try to be somewhere else, which is the whole of the
 * fight. What lands is fifty hearts through armour: nothing survives one.
 *
 * Health and armour are ordinary, so it can be killed. The realm replaces a
 * slain hunter after a long silence rather than leaving the sea safe.
 */
public final class AbyssalLeviathan extends PathfinderMob {
    // Every tunable lives in HuntMath, where the headless checks can reach it.
    public static final int BITE_TICKS=HuntMath.BITE_TICKS,BITE_STRIKE=HuntMath.BITE_STRIKE;
    public static final float BITE_DAMAGE=HuntMath.BITE_DAMAGE;
    public static final double MOUTH_REACH=HuntMath.MOUTH_REACH,MOUTH_RADIUS=HuntMath.MOUTH_RADIUS;

    public static final ResourceKey<DamageType> BITE_TYPE=
        ResourceKey.create(Registries.DAMAGE_TYPE,HexGodOfStories.id("leviathan_bite"));

    private static final EntityDataAccessor<Integer> BITE=SynchedEntityData.defineId(AbyssalLeviathan.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HUNTING=SynchedEntityData.defineId(AbyssalLeviathan.class,EntityDataSerializers.BOOLEAN);
    private int recovery,voice;
    private double cell;

    public AbyssalLeviathan(EntityType<? extends PathfinderMob> type,Level level) {
        super(type,level);setNoGravity(true);setPersistenceRequired();xpReward=120;
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH,650)
            .add(Attributes.ARMOR,16)
            .add(Attributes.ATTACK_DAMAGE,BITE_DAMAGE)
            .add(Attributes.MOVEMENT_SPEED,HuntMath.HUNT)
            .add(Attributes.FOLLOW_RANGE,192)
            .add(Attributes.KNOCKBACK_RESISTANCE,1);
    }

    @Override protected void defineSynchedData() {
        super.defineSynchedData();entityData.define(BITE,0);entityData.define(HUNTING,false);
    }

    /** 0 while idle, otherwise the position in the authored clip, in ticks. */
    public float biteProgress(float partial) {
        int remaining=entityData.get(BITE);
        return remaining<=0?0:BITE_TICKS-remaining+partial;
    }
    public boolean hunting() {return entityData.get(HUNTING);}
    public void bind(double cell) {this.cell=cell;}
    public double cell() {return cell;}

    @Override protected void registerGoals() {}
    @Override public boolean canBreatheUnderwater() {return true;}
    @Override public boolean isPushedByFluid() {return false;}
    @Override public boolean removeWhenFarAway(double distance) {return false;}
    @Override public boolean requiresCustomPersistence() {return true;}
    /** Volumetric navigation: no ground friction, no walk cycle, no step sounds. */
    @Override public void travel(Vec3 input) {}
    @Override protected void checkFallDamage(double y,boolean ground,net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {}

    @Override public void tick() {
        super.tick();
        setAirSupply(getMaxAirSupply());
        if(level().isClientSide)return;
        if(recovery>0)recovery--;
        int bite=entityData.get(BITE);
        if(bite>0)entityData.set(BITE,bite-1);

        LivingEntity prey=prey();
        setTarget(prey);
        entityData.set(HUNTING,prey!=null);

        Vec3 mouth=mouth();
        Vec3 steer;
        if(prey!=null) {
            Vec3 aim=prey.position().add(0,prey.getBbHeight()*.5,0);
            double gap=aim.distanceTo(mouth);
            if(bite>0) {
                // Committed. It drives forward through the whole telegraph, so the jaws arrive
                // where they were aimed and the victim has the wind-up to not be there.
                steer=aim.subtract(mouth).normalize().scale(HuntMath.telegraph(BITE_TICKS-bite)?HuntMath.LUNGE:HuntMath.HUNT*.5);
                if(BITE_TICKS-bite==BITE_STRIKE)strike();
            } else if(gap<HuntMath.STRIKE_RANGE&&recovery<=0) {
                begin();steer=aim.subtract(mouth).normalize().scale(HuntMath.LUNGE);
            } else {
                // Approach from below and slightly off-line: the silhouette rises into view before the jaw does.
                Vec3 under=aim.add(Math.cos(tickCount*.02)*4,-7,Math.sin(tickCount*.02)*4);
                steer=under.subtract(position()).normalize().scale(HuntMath.HUNT);
            }
            if(tickCount%90==0)voice(prey);
        } else {
            double x=cell+Math.cos(tickCount*.008)*40,z=Math.sin(tickCount*.008)*40;
            steer=new Vec3(x,96+Math.sin(tickCount*.013)*12,z).subtract(position()).normalize().scale(HuntMath.CRUISE);
        }

        double blend=bite>0?HuntMath.LUNGE_BLEND:HuntMath.DRIFT_BLEND;
        Vec3 velocity=getDeltaMovement().scale(1-blend).add(steer.scale(blend));
        if(getY()>HuntMath.SURFACE)velocity=velocity.add(0,-.06,0);
        if(getY()<HuntMath.BOTTOM)velocity=velocity.add(0,.08,0);
        setDeltaMovement(velocity);
        move(MoverType.SELF,velocity);
        if(velocity.horizontalDistanceSqr()>1.0E-5) {
            setYRot((float)(Math.atan2(-velocity.x,velocity.z)*180/Math.PI));
            setXRot((float)Mth.clamp(-Math.atan2(velocity.y,velocity.horizontalDistance())*180/Math.PI,-55,55));
            yBodyRot=getYRot();yHeadRot=getYRot();
        }
    }

    /** Everything alive is prey. Creative and spectator players are not in the world as far as it cares. */
    private LivingEntity prey() {
        LivingEntity current=getTarget();
        boolean usable=current!=null&&current.isAlive()&&current.level()==level()
            &&distanceToSqr(current)<200*200&&edible(current);
        // Scanning is the expensive part, so it happens twice a second and nothing else re-walks it.
        if(tickCount%10!=0)return usable?current:null;
        if(usable&&distanceToSqr(current)<64*64)return current;
        double range=getAttributeValue(Attributes.FOLLOW_RANGE);
        Player player=level().getNearestPlayer(getX(),getY(),getZ(),range,this::edible);
        if(player!=null)return player;
        List<LivingEntity> nearby=level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(48),this::edible);
        LivingEntity best=null;double closest=Double.MAX_VALUE;
        for(LivingEntity e:nearby) {
            double d=distanceToSqr(e);
            if(d<closest){closest=d;best=e;}
        }
        return best;
    }

    private boolean edible(Entity e) {
        if(!(e instanceof LivingEntity living)||e==this||!e.isAlive()||e.isSpectator())return false;
        if(e instanceof AbyssalLeviathan)return false;
        if(e instanceof net.minecraft.world.entity.decoration.ArmorStand)return false;
        return !(e instanceof Player p&&p.isCreative())&&living.attackable();
    }

    private void begin() {
        entityData.set(BITE,BITE_TICKS);recovery=BITE_TICKS+HuntMath.RECOVERY;
        level().playSound(null,blockPosition(),SoundEvents.ELDER_GUARDIAN_CURSE,SoundSource.HOSTILE,3F,.35F);
    }

    /** The jaws close on a volume, not on one entity: whatever is in the mouth is in the mouth. */
    private void strike() {
        Vec3 mouth=mouth();
        AABB maw=new AABB(mouth.x-MOUTH_RADIUS,mouth.y-MOUTH_RADIUS,mouth.z-MOUTH_RADIUS,
            mouth.x+MOUTH_RADIUS,mouth.y+MOUTH_RADIUS,mouth.z+MOUTH_RADIUS);
        boolean caught=false;
        for(LivingEntity victim:level().getEntitiesOfClass(LivingEntity.class,maw,this::edible)) {
            if(victim.position().add(0,victim.getBbHeight()*.5,0).distanceToSqr(mouth)>MOUTH_RADIUS*MOUTH_RADIUS)continue;
            victim.hurt(bite(),BITE_DAMAGE);
            victim.setDeltaMovement(victim.position().subtract(mouth).normalize().scale(.7).add(0,.25,0));
            victim.hurtMarked=true;caught=true;
        }
        level().playSound(null,BlockPos.containing(mouth),
            caught?SoundEvents.RAVAGER_ATTACK:SoundEvents.RAVAGER_ROAR,SoundSource.HOSTILE,3F,caught?.4F:.5F);
    }

    private DamageSource bite() {
        if(level() instanceof ServerLevel server)
            return new DamageSource(server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BITE_TYPE),this);
        return damageSources().mobAttack(this);
    }

    public Vec3 mouth() {
        Vec3 look=getLookAngle();
        return position().add(look.scale(MOUTH_REACH)).add(0,1.5+look.y*.5,0);
    }

    private void voice(LivingEntity prey) {
        double gap=distanceTo(prey);
        if(gap>96)return;
        voice=(voice+1)%3;
        level().playSound(null,blockPosition(),voice==0?SoundEvents.ELDER_GUARDIAN_AMBIENT:SoundEvents.WARDEN_HEARTBEAT,
            SoundSource.HOSTILE,gap<40?2.4F:1.4F,.35F);
    }

    @Override public boolean hurt(DamageSource source,float amount) {
        boolean hurt=super.hurt(source,amount);
        if(hurt&&source.getEntity() instanceof LivingEntity attacker&&edible(attacker))setTarget(attacker);
        return hurt;
    }

    @Override protected SoundEvent getHurtSound(DamageSource source) {return SoundEvents.ELDER_GUARDIAN_HURT;}
    @Override protected SoundEvent getDeathSound() {return SoundEvents.ELDER_GUARDIAN_DEATH;}
    @Override protected float getSoundVolume() {return 2.5F;}
    @Override public net.minecraft.sounds.SoundSource getSoundSource() {return SoundSource.HOSTILE;}

    @Override public void addAdditionalSaveData(CompoundTag n) {
        super.addAdditionalSaveData(n);n.putInt("recovery",recovery);n.putInt("bite",entityData.get(BITE));n.putDouble("cell",cell);
    }
    @Override public void readAdditionalSaveData(CompoundTag n) {
        super.readAdditionalSaveData(n);recovery=n.getInt("recovery");entityData.set(BITE,n.getInt("bite"));cell=n.getDouble("cell");
    }
}
