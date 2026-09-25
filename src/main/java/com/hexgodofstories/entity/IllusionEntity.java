package com.hexgodofstories.entity;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import java.util.*;

/**
 * A copy of the keeper: same face, same posture, same cloak, and now its own quarrel with the world.
 *
 * <p>Three things make a projection worth something beyond decoration. It finds its own enemies —
 * anything hostile by nature, anything already hunting its caster, and anything that has drawn the
 * caster's blood, including another player — without waiting for an order. It carries real steel, and
 * which steel varies between copies, so a court of them does not read as one figure printed eight
 * times. And when it lands a blow the blow is attributed to the copy, so the creature it hit turns on
 * the copy rather than looking through it at the body that cast it.
 */
public final class IllusionEntity extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> FINAL=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BEHAVIOR=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> QUARRY=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LOADOUT=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> THROW_START=SynchedEntityData.defineId(IllusionEntity.class,EntityDataSerializers.LONG);
    public static final int THROW_RELEASE=8,THROW_END=16;
    public enum Behavior { APPROACH, STRAFE, FEINT, RETREAT, WATCH, BLINK, THROW }
    /** 0 a single dagger, 1 twin daggers, 2 the Void sword. */
    public static final int DAGGER=0,TWIN=1,SWORD=2;
    public record Spec(int lifespan,Behavior behavior,boolean decoy,boolean collision,boolean dispelOnHit) {}

    private Spec spec=new Spec(300,Behavior.STRAFE,true,false,true);
    private long expires,nextStrike,nextHunt,nextGlance;
    private int hunted;
    private float glanceYaw;
    private int throwTarget;
    private boolean throwIllusory;
    private ItemStack thrownHand=ItemStack.EMPTY;

    public IllusionEntity(EntityType<? extends IllusionEntity> type,Level level) {super(type,level);setPersistenceRequired();}
    public static AttributeSupplier.Builder attributes() {return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,1).add(Attributes.MOVEMENT_SPEED,.31).add(Attributes.FOLLOW_RANGE,32);}
    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(OWNER,Optional.empty());entityData.define(FINAL,false);
        entityData.define(BEHAVIOR,0);entityData.define(QUARRY,0);entityData.define(LOADOUT,DAGGER);entityData.define(THROW_START,-1L);
    }
    @Override protected void registerGoals() {goalSelector.addGoal(0,new FloatGoal(this));}
    public UUID owner() {return entityData.get(OWNER).orElse(null);}
    public boolean finalForm() {return entityData.get(FINAL);}
    public int behavior() {return entityData.get(BEHAVIOR);}
    public boolean aggressive() {return entityData.get(QUARRY)!=0;}
    public int loadout() {return entityData.get(LOADOUT);}
    public float throwAge(float partial) {
        long start=entityData.get(THROW_START);
        return start<0?-1:level().getGameTime()-start+partial;
    }
    /** Only a copy meant to be mistaken for its caster takes part in target selection. */
    public boolean convincing() {return spec.decoy;}

    public void configure(ServerPlayer p,Spec spec,int loadout) {
        this.spec=spec;expires=level().getGameTime()+spec.lifespan;entityData.set(OWNER,Optional.of(p.getUUID()));
        entityData.set(FINAL,HexData.get(p).getBoolean("ascended"));entityData.set(BEHAVIOR,spec.behavior.ordinal());
        entityData.set(LOADOUT,loadout);
        for(EquipmentSlot slot:EquipmentSlot.values())setItemSlot(slot,p.getItemBySlot(slot).copy());
        arm(p,loadout);
        // A nameless figure beside eight named ones is the easiest tell of all.
        setCustomName(p.getName());
        setCustomNameVisible(false);
        setYRot(p.getYRot());setYHeadRot(p.getYHeadRot());yBodyRot=p.yBodyRot;glanceYaw=p.getYRot();
        mirrorEffects(p);
    }

    /** Conjured steel, already fully formed so a copy never appears mid-manifestation. */
    private void arm(ServerPlayer caster,int loadout) {
        ItemStack main=conjured(loadout==SWORD?HexGodOfStories.SCEPTER.get():HexGodOfStories.DAGGER.get(),caster,false);
        setItemSlot(EquipmentSlot.MAINHAND,main);
        setItemSlot(EquipmentSlot.OFFHAND,loadout==TWIN?conjured(HexGodOfStories.DAGGER.get(),caster,true):ItemStack.EMPTY);
    }
    private ItemStack conjured(net.minecraft.world.item.Item item,ServerPlayer caster,boolean reverse) {
        ItemStack stack=new ItemStack(item);
        stack.getOrCreateTag().putUUID("conjurer",caster.getUUID());
        stack.getOrCreateTag().putLong("formed",level().getGameTime()-40);
        if(reverse)stack.getOrCreateTag().putBoolean("reverse",true);
        return stack;
    }

    /**
     * A glow or a swirl of potion motes on one figure and not the others gives the game away, so the
     * harmless part of the caster's condition is carried across. Anything that deals damage is left
     * behind: a copy has a single point of health and would dissolve to its own poison.
     */
    private void mirrorEffects(ServerPlayer p) {
        removeAllEffects();
        for(MobEffectInstance effect:p.getActiveEffects()) {
            MobEffect kind=effect.getEffect();
            if(kind==MobEffects.POISON||kind==MobEffects.WITHER||kind==MobEffects.HARM)continue;
            addEffect(new MobEffectInstance(kind,Math.min(effect.getDuration(),spec.lifespan+40),
                effect.getAmplifier(),effect.isAmbient(),effect.isVisible(),effect.showIcon()));
        }
    }

    /** Ordered onto a foe by its caster. The decoy commits to that quarry until it dies or the decoy fades. */
    public void command(LivingEntity quarry) {
        entityData.set(QUARRY,quarry==null?0:quarry.getId());
        if(quarry!=null) {
            // A commanded decoy stops loitering: pick a behaviour that actually closes on the target.
            Behavior forced=switch(getId()%4){case 0->Behavior.APPROACH;case 1->Behavior.STRAFE;case 2->Behavior.FEINT;default->Behavior.THROW;};
            spec=new Spec(spec.lifespan,forced,spec.decoy,spec.collision,spec.dispelOnHit);
            entityData.set(BEHAVIOR,forced.ordinal());
            expires=Math.max(expires,level().getGameTime()+160);
        }
    }

    private LivingEntity quarry() {
        int id=entityData.get(QUARRY);
        if(id==0)return null;
        if(level().getEntity(id) instanceof LivingEntity living&&living.isAlive())return living;
        entityData.set(QUARRY,0);
        return null;
    }

    @Override public void tick() {
        super.tick();
        if(level().isClientSide)return;
        ServerPlayer p=owner()==null?null:((ServerLevel)level()).getServer().getPlayerList().getPlayer(owner());
        if(p==null||p.level()!=level()||!p.isAlive()||level().getGameTime()>=expires||distanceToSqr(p)>4096){dispel();return;}
        tickThrow(p);
        LivingEntity enemy=quarry();
        if(enemy!=null&&(enemy.level()!=level()||distanceToSqr(enemy)>2304||!Hostility.hostile(enemy,p))){entityData.set(QUARRY,0);enemy=null;}
        // Striking runs every tick so a copy's cadence matches a person's, while the far more
        // expensive search for something to strike stays on its slow, staggered schedule below.
        if(enemy==null&&hunted!=0&&level().getEntity(hunted) instanceof LivingEntity held
            &&held.isAlive()&&held.level()==level()&&Hostility.hostile(held,p))enemy=held;
        if(enemy!=null)strike(p,enemy);
        if(tickCount%10!=getId()%10)return;
        if(enemy==null)enemy=hunt(p);
        if(enemy==null) {
            idle(p);
            return;
        }
        engage(p,enemy);
    }

    /**
     * Finds something worth fighting without a full-world sweep: one bounded query on a slow cadence,
     * with the result kept until it stops being valid.
     */
    private LivingEntity hunt(ServerPlayer owner) {
        long now=level().getGameTime();
        if(hunted!=0&&level().getEntity(hunted) instanceof LivingEntity held
            &&held.isAlive()&&held.level()==level()&&distanceToSqr(held)<1024&&Hostility.hostile(held,owner))return held;
        hunted=0;
        if(now<nextHunt)return null;
        nextHunt=now+10;
        LivingEntity best=null;
        double nearest=Double.MAX_VALUE;
        for(LivingEntity candidate:level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(20),
            e->e!=this&&e.isAlive()&&Hostility.hostile(e,owner))) {
            double distance=candidate.distanceToSqr(this);
            // Something out of sight still counts, just at a heavy penalty, so a copy will round a corner.
            if(!hasLineOfSight(candidate))distance*=4;
            if(distance<nearest){nearest=distance;best=candidate;}
        }
        if(best!=null)hunted=best.getId();
        return best;
    }

    private void engage(ServerPlayer p,LivingEntity enemy) {
        // A figure welded to its target's face is unmistakable; glance away the way a person does.
        long now=level().getGameTime();
        if(now>=nextGlance) {
            nextGlance=now+12+random.nextInt(26);
            glanceYaw=enemy.getYRot()+(random.nextFloat()-.5f)*70;
        }
        if(random.nextInt(4)>0)getLookControl().setLookAt(enemy,28,26);
        else {
            double radians=Math.toRadians(glanceYaw);
            getLookControl().setLookAt(enemy.getX()-Math.sin(radians)*3,enemy.getEyeY()+random.nextGaussian(),enemy.getZ()+Math.cos(radians)*3);
        }
        // Provoke a creature that is fighting nobody. One that is already hunting the caster is left
        // to the weighted draw in Decoy, so copies neither lose that contest by default nor win it.
        if(spec.decoy&&enemy instanceof Mob mob&&mob.getTarget()==null)mob.setTarget(this);
        Vec3 delta=position().subtract(enemy.position()).multiply(1,0,1).normalize();
        if(delta.lengthSqr()<1e-6)delta=new Vec3(1,0,0);
        double angle=getId()*2.399963229728653+tickCount*.015;
        Vec3 orbit=new Vec3(Math.cos(angle),0,Math.sin(angle)).scale(3.4);
        Vec3 goal=switch(spec.behavior) {
            case APPROACH,FEINT -> enemy.position().add(orbit.normalize().scale(1.6));
            case THROW -> enemy.position().add(delta.scale(7));
            case RETREAT -> position().add(delta.scale(5));
            case STRAFE,BLINK -> enemy.position().add(orbit);
            case WATCH -> position();
        };
        if(spec.behavior!=Behavior.WATCH)getNavigation().moveTo(goal.x,goal.y,goal.z,spec.behavior==Behavior.RETREAT?1.25:1);
        // Only the blade throwers are armed for it, and a swordsman never mimes one.
        if(spec.behavior==Behavior.THROW&&loadout()!=SWORD&&tickCount%40==getId()%10
            &&throwAge(0)<0&&hasLineOfSight(enemy)&&getMainHandItem().is(HexGodOfStories.DAGGER.get())) {
            throwTarget=enemy.getId();throwIllusory=quarry()==null;
            entityData.set(THROW_START,level().getGameTime());
        }
        if(spec.behavior==Behavior.BLINK&&tickCount%60==getId()%10&&level().noCollision(this,getBoundingBox().move(goal.subtract(position())))) {
            HexNetwork.fx(this,"dispel");setPos(goal);
        }
        strike(p,enemy);
    }

    /** One wind-up, one release from the forward hand, then recovery on the existing throw cadence. */
    private void tickThrow(ServerPlayer caster) {
        float age=throwAge(0);
        if(age<0)return;
        Entity target=level().getEntity(throwTarget);
        if(target instanceof LivingEntity enemy&&enemy.isAlive()&&Hostility.hostile(enemy,caster)) {
            getLookControl().setLookAt(enemy,45,45);
            Vec3 towards=enemy.position().subtract(position());
            float facing=(float)(-Math.atan2(towards.x,towards.z)*180/Math.PI);
            setYRot(facing);yBodyRot=facing;
            if(age==THROW_RELEASE&&hasLineOfSight(enemy)&&getMainHandItem().is(HexGodOfStories.DAGGER.get())) {
                Vec3 from=position().add(new Vec3(-.3125,1.375,.625).yRot(-facing*(float)Math.PI/180));
                ThrownDagger.throwFrom(caster,from,throwAim(from,enemy),throwIllusory);
                thrownHand=getMainHandItem();setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            }
        }
        if(age>=THROW_END) {
            if(!thrownHand.isEmpty())setItemInHand(InteractionHand.MAIN_HAND,thrownHand);
            thrownHand=ItemStack.EMPTY;throwTarget=0;entityData.set(THROW_START,-1L);
        }
    }

    /** Modest movement lead plus the projectile's existing air drag and gravity, without homing. */
    private static Vec3 throwAim(Vec3 from,LivingEntity target) {
        Vec3 centre=target.getBoundingBox().getCenter();
        Vec3 motion=target.getDeltaMovement();
        if(target.onGround())motion=motion.multiply(1,0,1);
        if(motion.lengthSqr()>.64)motion=motion.normalize().scale(.8);
        double flight=from.distanceTo(centre)/1.85;
        Vec3 aim=centre.subtract(from);
        for(int i=0;i<4;i++) {
            double t=Math.max(1,Math.min(25,flight));
            double drag=(1-Math.pow(.99,t))/.01;
            double drop=.026*(t-drag)/.01;
            aim=centre.add(motion.scale(Math.min(12,t))).subtract(from).add(0,drop,0);
            flight=aim.length()/1.85*t/drag;
        }
        return aim;
    }

    /** Each decoy owns a separate roaming sector. Never leave an old path pointing at the caster. */
    private void idle(ServerPlayer caster) {
        List<IllusionEntity> peers=level().getEntitiesOfClass(IllusionEntity.class,
            caster.getBoundingBox().inflate(64),e->Objects.equals(e.owner(),owner())&&e.isAlive());
        peers.sort(Comparator.comparingInt(Entity::getId));
        int slot=Math.max(0,peers.indexOf(this)),count=Math.max(1,peers.size());
        double angle=Math.PI*2*slot/count+.37;
        // Small, independent wander inside the assigned sector, instead of all sharing a destination.
        double wander=Math.sin((level().getGameTime()+getId()*37)*.012)*.20;
        double radius=4.5+(slot%2)*1.5+Math.sin((level().getGameTime()+getId()*51)*.009)*.45;
        Vec3 goal=caster.position().add(Math.cos(angle+wander)*radius,0,Math.sin(angle+wander)*radius);
        Vec3 separation=Vec3.ZERO;
        for(IllusionEntity peer:peers) {
            if(peer==this)continue;
            Vec3 away=position().subtract(peer.position()).multiply(1,0,1);
            double distance=away.length();
            if(distance<1.8)separation=separation.add(distance<.01
                ?new Vec3(Math.cos(angle),0,Math.sin(angle)).scale(1.8)
                :away.scale((1.8-distance)/distance));
        }
        if(separation.lengthSqr()>.01)goal=position().add(separation).lerp(goal,.4);
        var floor=level().clip(new ClipContext(goal.add(0,3,0),goal.add(0,-5,0),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(floor.getType()!=HitResult.Type.MISS)goal=new Vec3(goal.x,floor.getLocation().y,goal.z);
        if(position().subtract(goal).horizontalDistanceSqr()<.36&&separation.lengthSqr()<.01) {
            getNavigation().stop();
            loiter(caster);
        } else if(level().noCollision(this,getBoundingBox().move(goal.subtract(position())))) {
            getNavigation().moveTo(goal.x,goal.y,goal.z,spec.behavior==Behavior.RETREAT?1.05:.85);
        } else {
            // An obstructed sector must not retain the previous path to a shared combat target.
            getNavigation().stop();
        }
        // Copies that all crouch when their caster crouches read as one person seen eight times.
        if(distanceToSqr(caster)<400)setPose(caster.getPose()==Pose.CROUCHING?Pose.CROUCHING:Pose.STANDING);
        setSprinting(caster.isSprinting()&&getNavigation().isInProgress());
    }

    /** Idle attention wanders: sometimes the caster, sometimes where the caster is looking, sometimes nothing. */
    private void loiter(ServerPlayer caster) {
        long now=level().getGameTime();
        if(now>=nextGlance) {
            nextGlance=now+30+random.nextInt(70);
            glanceYaw=random.nextInt(3)==0?caster.getYRot()+(random.nextFloat()-.5f)*40:getYRot()+(random.nextFloat()-.5f)*150;
        }
        if(random.nextInt(5)==0)getLookControl().setLookAt(caster,18,18);
        else {
            double radians=Math.toRadians(glanceYaw);
            getLookControl().setLookAt(getX()-Math.sin(radians)*6,getEyeY()+Math.sin(now*.013+getId())*.5,getZ()+Math.cos(radians)*6);
        }
    }

    /**
     * A copy lands real but modest blows. The damage is attributed to the copy rather than to the
     * caster, which is what lets a wounded creature turn and fight the thing that actually cut it.
     */
    private void strike(ServerPlayer p,LivingEntity enemy) {
        if(throwAge(0)>=0)return;
        long now=level().getGameTime();
        if(now<nextStrike||!Hostility.hostile(enemy,p))return;
        double reach=getBbWidth()*.5+enemy.getBbWidth()*.5+(loadout()==SWORD?1.75:1.35);
        if(distanceToSqr(enemy)>reach*reach||!hasLineOfSight(enemy))return;
        // A sword is heavier and slower; twin daggers come in pairs from alternating hands.
        boolean offhand=loadout()==TWIN&&(now/7)%2==0;
        nextStrike=now+switch(loadout()){case SWORD->24;case TWIN->11;default->16;};
        swing(offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);
        getLookControl().setLookAt(enemy,45,45);
        float base=switch(loadout()){case SWORD->3.5f;case TWIN->1.8f;default->2.2f;};
        float damage=base+Math.min(3.5f,HexData.mastery(p,Discipline.MISCHIEF)*.0035f);
        if(enemy.hurt(damageSources().mobAttack(this),damage)) {
            enemy.knockback(loadout()==SWORD?.24:.16,getX()-enemy.getX(),getZ()-enemy.getZ());
            // The threat ledger is written from the damage event itself, so the copy is already on it.
            HexNetwork.fx(enemy,"impact");
            HexServer.reward(p,Discipline.MISCHIEF,35);
        }
    }

    public void dispel() {
        if(isRemoved())return;
        HexNetwork.fx(this,"dispel");
        // Anything hunting this copy must be free to choose again the instant it stops existing.
        if(level() instanceof ServerLevel level) {
            for(Mob mob:level.getEntitiesOfClass(Mob.class,getBoundingBox().inflate(24),m->m.getTarget()==this)) {
                Decoy.release(mob);
                mob.setTarget(null);
            }
        }
        discard();
    }
    @Override public boolean hurt(DamageSource source,float amount) {if(level().isClientSide)return true;if(spec.dispelOnHit)dispel();return true;}
    @Override public boolean isPushable() {return spec.collision;}
    @Override public boolean canCollideWith(Entity e) {return spec.collision&&super.canCollideWith(e);}
    @Override public boolean shouldShowName() {return false;}
    @Override public void addAdditionalSaveData(CompoundTag n) {super.addAdditionalSaveData(n);if(owner()!=null)n.putUUID("Owner",owner());n.putLong("expires",expires);n.putInt("loadout",loadout());}
    @Override public void readAdditionalSaveData(CompoundTag n) {
        super.readAdditionalSaveData(n);
        if(n.hasUUID("Owner"))entityData.set(OWNER,Optional.of(n.getUUID("Owner")));
        entityData.set(LOADOUT,n.getInt("loadout"));expires=0;
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
