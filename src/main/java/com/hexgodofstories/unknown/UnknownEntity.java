package com.hexgodofstories.unknown;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import java.util.*;

/** The attached two-armed model, released into the overworld without taming or allegiance. */
public final class UnknownEntity extends Mob implements GeoEntity {
    private static final EntityDataAccessor<Integer> PHASE=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACTION=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACTION_TICK=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SPEED=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> LEAPING=SynchedEntityData.defineId(UnknownEntity.class,EntityDataSerializers.BOOLEAN);
    private final AnimatableInstanceCache cache=GeckoLibUtil.createInstanceCache(this);
    private final Map<UUID,MotionSample> motionSamples=new HashMap<>();
    private static final class MotionSample {
        Vec3 pos;float yaw,pitch;int seenTick;
        MotionSample(LivingEntity entity,int tick){
            pos=entity.position();yaw=entity.getYRot();pitch=entity.getXRot();seenTick=tick;
        }
        boolean update(LivingEntity entity,int tick){
            Vec3 now=entity.position();
            boolean moved=now.distanceToSqr(pos)>.0004;
            // For players, merely turning the camera is enough to give the creature a movement cue.
            if(entity instanceof Player)
                moved|=Math.abs(Mth.wrapDegrees(entity.getYRot()-yaw))>.75f
                        ||Math.abs(entity.getXRot()-pitch)>.75f;
            pos=now;yaw=entity.getYRot();pitch=entity.getXRot();seenTick=tick;
            return moved;
        }
    }
    private UUID caster,targetId,heldId;
    private Vec3 portal=Vec3.ZERO;
    private long born,portalSeed,portalGameTick;private int portalId;
    private boolean finished,heldNoGravity,throwVictim;
    private int lastSense,attackTick,attackMode,attackDelay,breakClock,roamUntil,blockedTicks,jumpCooldown,leapTicks;
    private Vec3 roamPoint;
    private Vec3 previousChasePosition;
    private double chaseSpeed;private Vec3 lastHeading=Vec3.ZERO;
    public UnknownEntity(EntityType<? extends UnknownEntity> type,Level level){
        super(type,level);setPersistenceRequired();
        this.setMaxUpStep(2.5f);
    }
    public static AttributeSupplier.Builder attributes(){
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,100).add(Attributes.MOVEMENT_SPEED,.8)
                .add(Attributes.FOLLOW_RANGE,28).add(Attributes.ATTACK_DAMAGE,30).add(Attributes.KNOCKBACK_RESISTANCE,1);
    }
    @Override protected void defineSynchedData(){
        super.defineSynchedData();entityData.define(PHASE,0);entityData.define(ACTION,0);
        entityData.define(ACTION_TICK,0);entityData.define(SPEED,0f);entityData.define(LEAPING,false);
    }
    @Override protected void registerGoals(){}
    public void begin(UUID caster,long born,Vec3 portal,long seed,long gameTick){
        this.caster=caster;this.born=born;this.portal=portal;
        this.portalSeed=seed;this.portalGameTick=gameTick;
        ServerPlayer player=getServer().getPlayerList().getPlayer(caster);
        this.portalId=player==null?getId():player.getId();
        entityData.set(PHASE,0);noPhysics=true;setNoGravity(true);
    }
    public UUID caster(){return caster;}
    public long born(){return born;}
    public long portalSeed(){return portalSeed;}
    public long portalGameTick(){return portalGameTick;}
    public int portalId(){return portalId;}
    public Vec3 portal(){return portal;}
    public int phase(){return entityData.get(PHASE);}
    public int action(){return entityData.get(ACTION);}
    public float speed(){return entityData.get(SPEED);}
    public int actionTick(){return entityData.get(ACTION_TICK);}
    @Override public boolean isInvulnerableTo(DamageSource source){return true;}
    @Override public void kill(){} // Includes /kill and the void; only the lifespan removes it.
    @Override public boolean causeFallDamage(float distance,float multiplier,DamageSource source){return false;}
    @Override public boolean removeWhenFarAway(double distance){return false;}
    @Override public void push(Entity entity){}
    @Override public boolean isPushable(){return false;}
    @Override public void knockback(double strength,double x,double z){}
    @Override public void tick(){
        super.tick();
        if(level().isClientSide||caster==null||born==0||finished)return;
        ServerLevel server=(ServerLevel)level();
        long elapsed=System.currentTimeMillis()-born;
        if(elapsed<UnknownSummoning.EMERGE_MS){
            noPhysics=true;setDeltaMovement(Vec3.ZERO);
            if(tickCount==38||tickCount==94||tickCount==170){
                server.playSound(null,blockPosition(),tickCount==170?HexGodOfStories.UNKNOWN_IMPACT.get():HexGodOfStories.UNKNOWN_DIG.get(),SoundSource.HOSTILE,2.4f,.85f);
                server.sendParticles(ParticleTypes.POOF,portal.x,portal.y+.1,portal.z,18,1.2,.06,1.2,.04);
            }
            if(tickCount%10==0){
                UnknownSummoning.portal(this,false);
                server.sendParticles(ParticleTypes.LARGE_SMOKE,portal.x,portal.y+.12,portal.z,6,1.4,.07,1.4,.008);
            }
            return;
        }
        if(phase()==0){
            noPhysics=false;setNoGravity(false);entityData.set(PHASE,1);
            UnknownSummoning.portal(this,true);
            server.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_ROAR.get(),SoundSource.HOSTILE,8f,.85f);
        }
        if(elapsed>=UnknownSummoning.EMERGE_MS+UnknownSummoning.HUNT_MS){
            if(phase()!=2)vanish(server);
            if(elapsed>=UnknownSummoning.EMERGE_MS+UnknownSummoning.HUNT_MS+UnknownSummoning.VANISH_MS)
                finish();
            return;
        }
        if(getY()<server.getMinBuildHeight()+2)teleportTo(portal.x,portal.y+2,portal.z);
        if(jumpCooldown>0)jumpCooldown--;
        if(entityData.get(LEAPING)){
            leapTicks++;
            if(leapTicks>4&&onGround()){
                entityData.set(LEAPING,false);
                leapTicks=0;
            }
        }
        if(attackTick>0){setDeltaMovement(0,getDeltaMovement().y,0);attack(server);return;}
        if(attackDelay>0)attackDelay--;
        LivingEntity target=findTarget(server);
        if(target!=null)setTarget(target);
        else setTarget(null);
        Vec3 toward=target!=null?target.position().subtract(position()):roam(server);
        double distance=target!=null?target.position().distanceTo(position()):Double.MAX_VALUE;
        if(target!=null&&distance<8.6&&attackDelay==0){
            attackMode=target.getBbWidth()<2.6&&target.getBbHeight()<4.2?2:1;
            throwVictim=attackMode==2&&random.nextInt(3)==0;
            attackTick=1;entityData.set(ACTION,attackMode);entityData.set(ACTION_TICK,1);
            chaseSpeed=0;entityData.set(SPEED,0f);
            level().playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_LUNGE.get(),SoundSource.HOSTILE,4f,.83f);
            return;
        }
        // He never idles after emerging. Without prey he still prowls quickly through a wide area,
        // and the instant he sees something edible he launches into the chase.
        chaseSpeed=target==null?Mth.lerp(.14,chaseSpeed,.34):
                Math.min(.96,Math.max(.62,chaseSpeed+.045));
        entityData.set(SPEED,(float)chaseSpeed);
        Vec3 direction=new Vec3(toward.x,0,toward.z).normalize();
        if(direction.lengthSqr()<.01){setDeltaMovement(0,getDeltaMovement().y,0);return;}
        lastHeading=direction;
        setYRot((float)(Mth.atan2(-direction.x,direction.z)*180/Math.PI));
        yBodyRot=getYRot();yHeadRot=getYRot();
        Vec3 step=direction.scale(chaseSpeed);
        // Track a real failed stride. Ordinary dirt, slopes and the floor beneath the creature
        // are NEVER valid obstructions to excavate.
        if(target!=null&&previousChasePosition!=null&&position().distanceToSqr(previousChasePosition)<.06*.06
                &&horizontalCollision&&onGround())blockedTicks++;
        else blockedTicks=0;
        previousChasePosition=position();

        // This creature is far too large to be stopped by an ordinary roof or village wall.
        // When prey is above it, or its full body actually collides with an obstacle while chasing,
        // it makes a committed high leap instead of a tiny vanilla-style hop.
        boolean wall=horizontalCollision;
        // A real obstruction gets hit immediately. This only cuts the wall/body-height volume in
        // front of the creature; it still never strips the ground underneath it.
        if(target!=null&&onGround()&&wall)breach(server,direction);
        double vertical=getDeltaMovement().y;
        boolean needsHighLeap=target!=null&&(target.getY()>getY()+2.0||wall||blockedTicks>=2);
        if(onGround()&&needsHighLeap&&jumpCooldown==0&&clearAboveForLeap(server)){
            double rise=Math.max(0,target.getY()-getY());
            vertical=Mth.clamp(1.35+rise*.025,1.35,1.58);
            step=direction.scale(Math.max(chaseSpeed,.90));
            jumpCooldown=34;
            leapTicks=0;
            blockedTicks=0;
            entityData.set(LEAPING,true);
            hasImpulse=true;
        }else if(target!=null&&blockedTicks>=2){
            // If there is not enough headroom to jump, chew through the obstruction immediately.
            breach(server,direction);
        }
        // Horizontal motion goes through vanilla travel/collision; vertical motion remains gravity.
        setDeltaMovement(step.x,vertical,step.z);hasImpulse=true;
        if(onGround()&&tickCount%13==0&&chaseSpeed>.3)
            server.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_STEP.get(),SoundSource.HOSTILE,2.1f,.86f);
        if(tickCount%18==0&&chaseSpeed>.48)
            server.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,getX(),getY()+1,getZ(),5,1,.4,1,.02);
    }
    /** A high leap needs open air over the creature's enormous 6.4 x 7.4 block body. */
    private boolean clearAboveForLeap(ServerLevel level){
        AABB body=getBoundingBox();
        // Check the first part of the ascent. Once this volume is clear, normal collision handling
        // takes over for the rest of the arc and lets the creature land naturally on rooftops.
        for(double y=1.5;y<=6.0;y+=1.5)
            if(!level.noCollision(this,body.move(0,y,0)))return false;
        return true;
    }
    /** Keep prowling far beyond the immediate spawn area. It should always be going somewhere. */
    private Vec3 roam(ServerLevel level){
        if(roamPoint==null||tickCount>=roamUntil||position().distanceToSqr(roamPoint)<25){
            double angle=random.nextDouble()*Math.PI*2.0;
            double radius=30.0+random.nextDouble()*34.0;
            int x=Mth.floor(getX()+Math.cos(angle)*radius);
            int z=Mth.floor(getZ()+Math.sin(angle)*radius);
            BlockPos ground=level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,0,z));
            roamPoint=Vec3.atBottomCenterOf(ground);
            roamUntil=tickCount+110+random.nextInt(110);
        }
        return roamPoint.subtract(position());
    }
    /**
     * Motion is the trigger, not sight. A completely still living thing is effectively invisible
     * to the creature until it moves. Players also trigger it by turning their camera.
     *
     * Once something has moved and been acquired, freezing no longer saves it: the scent is kept
     * while the victim remains within the pursuit radius, even through walls. Escaping far enough
     * still makes the creature give up, so it cannot stay locked to somebody hundreds of blocks away.
     */
    private LivingEntity findTarget(ServerLevel level){
        LivingEntity moving=senseMovement(level);
        LivingEntity current=targetId==null?null:level.getEntity(targetId) instanceof LivingEntity living?living:null;
        if(valid(current)&&distanceToSqr(current)<120*120)return current;

        targetId=null;setTarget(null);
        if(moving!=null){
            targetId=moving.getUUID();
            return moving;
        }
        return null;
    }
    /** Sample every nearby living thing every tick so standing still right now really means safe. */
    private LivingEntity senseMovement(ServerLevel level){
        LivingEntity best=null;
        double bestDistance=100*100;
        for(LivingEntity candidate:level.getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(100),
                this::valid)){
            UUID id=candidate.getUUID();
            MotionSample sample=motionSamples.get(id);
            boolean moved;
            if(sample==null){
                sample=new MotionSample(candidate,tickCount);
                motionSamples.put(id,sample);
                Vec3 velocity=candidate.getDeltaMovement();
                moved=velocity.x*velocity.x+velocity.y*velocity.y+velocity.z*velocity.z>.0004;
            }else moved=sample.update(candidate,tickCount);

            if(moved){
                double distance=distanceToSqr(candidate);
                if(distance<bestDistance){
                    bestDistance=distance;
                    best=candidate;
                }
            }
        }
        if(tickCount%40==0)
            motionSamples.entrySet().removeIf(e->tickCount-e.getValue().seenTick>40);
        return best;
    }
    private boolean valid(LivingEntity target){
        return target!=null&&target!=this&&target.isAlive()&&!target.isSpectator()
                &&!(target instanceof UnknownEntity);
    }
    /** Remove only the wall physically touching the front of the body after five failed strides. */
    private void breach(ServerLevel level,Vec3 direction){
        AABB body=getBoundingBox();
        // Tall collisions need to be above the two-block step the creature can climb.
        AABB high=new AABB(body.minX,body.minY+2.55,body.minZ,
                body.maxX,body.maxY-.35,body.maxZ).move(direction.scale(.75));
        if(level.noCollision(this,high))return;
        Vec3 front=position().add(direction.scale(getBbWidth()*.48));
        Vec3 side=new Vec3(-direction.z,0,direction.x);
        int floor=Mth.ceil(getY()-.01),taken=0;
        for(int y=floor+1;y<floor+Math.min(8,Mth.ceil(getBbHeight()));y++){
            for(double across=-getBbWidth()*.43;across<=getBbWidth()*.43;across+=.85){
                for(double ahead=0;ahead<=1.0;ahead+=.5){
                    Vec3 hit=front.add(side.scale(across)).add(direction.scale(ahead));
                    BlockPos pos=BlockPos.containing(hit.x,y,hit.z);
                    if(pos.getY()<floor||!level.hasChunkAt(pos))continue;
                    BlockState state=level.getBlockState(pos);
                    if(state.isAir()||state.getCollisionShape(level,pos).isEmpty())continue;
                    // A block touched by the horizontal face is a wall. Blocks below the feet
                    // remain structural support even when the visual tail overlaps them.
                    if(UnknownTerrain.breakBlock(level,pos.immutable(),getUUID(),
                            born+UnknownSummoning.EMERGE_MS+UnknownSummoning.HUNT_MS+UnknownSummoning.VANISH_MS+15_000)){
                        taken++;
                        // Use Minecraft's native block-break event: the block's own break sound and
                        // its normal debris particles, never Unknown's custom impact sound.
                        level.levelEvent(2001,pos,Block.getId(state));
                        if(taken>=24)break;
                    }
                }
                if(taken>=24)break;
            }
            if(taken>=24)break;
        }
        if(taken>0)blockedTicks=3; // Allow a fresh stride before the next impact.
    }
    private void attack(ServerLevel level){
        attackTick++;entityData.set(ACTION_TICK,attackTick);
        LivingEntity victim=heldId==null?null:level.getEntity(heldId) instanceof LivingEntity e?e:null;
        if(victim!=null&&victim.isAlive()){
            double progress=attackTick<46?0:Math.min(1,(attackTick-46)/18.0);
            Vec3 mouth=mouth().add(0,-progress*.85,0).subtract(lastHeading.scale(progress*.55));
            if(victim instanceof ServerPlayer player)
                player.connection.teleport(mouth.x,mouth.y,mouth.z,player.getYRot(),player.getXRot());
            else victim.teleportTo(mouth.x,mouth.y,mouth.z);
            victim.setDeltaMovement(Vec3.ZERO);victim.fallDistance=0;
        }
        if(attackMode==2&&attackTick==12){
            LivingEntity target=targetId==null?null:level.getEntity(targetId) instanceof LivingEntity e?e:null;
            if(valid(target)&&distanceToSqr(target)<144){
                heldId=target.getUUID();heldNoGravity=target.isNoGravity();
                entityData.set(ACTION,2);
                level.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_GRAB.get(),SoundSource.HOSTILE,4f,.8f);
            }
        }
        if(attackMode==2&&attackTick==28)entityData.set(ACTION,3); // chew
        if(attackMode==2&&attackTick==46)entityData.set(ACTION,throwVictim?5:4);
        if(attackMode==2&&attackTick==52&&throwVictim){
            if(victim!=null&&victim.isAlive()){
                hurtAsCaster(victim,9);
                releaseVictim(level);
                victim.setDeltaMovement(lastHeading.scale(2.3).add(0,.85,0));
                victim.hurtMarked=true;
                level.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_THROW.get(),SoundSource.HOSTILE,4f,.75f);
            }
        }
        if(attackMode==1&&attackTick==14){
            LivingEntity target=targetId==null?null:level.getEntity(targetId) instanceof LivingEntity e?e:null;
            if(valid(target)&&distanceToSqr(target)<144){
                hurtAsCaster(target,26);
                target.setDeltaMovement(lastHeading.scale(2.2).add(0,.75,0));target.hurtMarked=true;
            }
            level.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_BITE.get(),SoundSource.HOSTILE,4f,.8f);
        }
        if(attackMode==2&&!throwVictim&&attackTick>=65){
            if(victim!=null&&victim.isAlive()){
                hurtAsCaster(victim,10000);
                if(victim.isAlive()){
                    victim.setDeltaMovement(lastHeading.scale(2.3).add(0,.65,0));
                    victim.hurtMarked=true;
                }
            }
            releaseVictim(level);
        }
        if(attackTick>=(attackMode==2?70:29)){
            releaseVictim(level);attackTick=0;attackDelay=12;
            entityData.set(ACTION,0);entityData.set(ACTION_TICK,0);
            // Meal is over. Immediately forget the previous victim and resume a wide prowl instead
            // of lingering at the kill site or remaining mentally locked onto something that escaped.
            targetId=null;setTarget(null);roamPoint=null;roamUntil=0;
            chaseSpeed=Math.max(chaseSpeed,.34);entityData.set(SPEED,(float)chaseSpeed);
        }
    }
    private Vec3 mouth(){
        Vec3 forward=Vec3.directionFromRotation(0,getYRot());
        double shake=attackTick>=28&&attackTick<46?Math.sin(attackTick*1.4)*.34:0;
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        return position().add(forward.scale(7.05)).add(side.scale(shake)).add(0,4.0+shake*.28,0);
    }
    private void hurtAsCaster(LivingEntity victim,float amount){
        ServerPlayer owner=getServer().getPlayerList().getPlayer(caster);
        // An absent player cannot own a vanilla playerAttack DamageSource. Pause lethal hits until
        // they return instead of silently recording a mob kill against the wrong attacker.
        if(owner==null)return;
        victim.setLastHurtByPlayer(owner);
        victim.hurt(owner.damageSources().playerAttack(owner),amount);
    }
    private void releaseVictim(ServerLevel level){
        if(heldId!=null){
            if(level.getEntity(heldId) instanceof LivingEntity living){
                living.setNoGravity(heldNoGravity);living.fallDistance=0;
            }
            heldId=null;
        }
    }
    private void vanish(ServerLevel level){
        releaseVictim(level);entityData.set(PHASE,2);entityData.set(SPEED,0f);
        setDeltaMovement(Vec3.ZERO);noPhysics=true;
        CompoundTag n=new CompoundTag();n.putDouble("dx",lastHeading.x);n.putDouble("dy",.16);
        n.putDouble("dz",lastHeading.z);n.putInt("duration",80);n.putFloat("power",2.5f);
        n.putLong("start",level.getGameTime());n.putBoolean("implosion",false);
        HexNetwork.tracking(this,new HexNetwork.Message(HexNetwork.ERASURE,getId(),n));
        level.playSound(null,blockPosition(),HexGodOfStories.UNKNOWN_VANISH.get(),SoundSource.HOSTILE,5f,.72f);
    }
    private void finish(){
        if(finished)return;finished=true;
        UnknownSummoning.finished(this);
        discard();
    }
    @Override public void remove(RemovalReason reason){
        if(!level().isClientSide&&born>0&&!finished&&reason!=RemovalReason.UNLOADED_TO_CHUNK)
            finish();
        super.remove(reason);
    }
    @Override public void addAdditionalSaveData(CompoundTag n){
        super.addAdditionalSaveData(n);
        if(caster!=null)n.putUUID("Caster",caster);
        n.putLong("Born",born);n.putDouble("PortalX",portal.x);n.putDouble("PortalY",portal.y);
        n.putDouble("PortalZ",portal.z);n.putLong("PortalSeed",portalSeed);n.putLong("PortalGameTick",portalGameTick);n.putInt("PortalId",portalId);
        n.putInt("Phase",phase());if(targetId!=null)n.putUUID("Target",targetId);
        // Grabbed players are released on unload/restart, never left with a control/camera modifier.
    }
    @Override public void readAdditionalSaveData(CompoundTag n){
        super.readAdditionalSaveData(n);
        if(n.hasUUID("Caster"))caster=n.getUUID("Caster");
        born=n.getLong("Born");portal=new Vec3(n.getDouble("PortalX"),n.getDouble("PortalY"),n.getDouble("PortalZ"));
        portalSeed=n.getLong("PortalSeed");portalGameTick=n.getLong("PortalGameTick");portalId=n.getInt("PortalId");
        entityData.set(PHASE,n.getInt("Phase"));
        if(n.hasUUID("Target"))targetId=n.getUUID("Target");
        noPhysics=phase()!=1;
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache(){return cache;}
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers){
        controllers.add(new AnimationController<>(this,"movement",3,this::movement));
        controllers.add(new AnimationController<>(this,"attack",0,this::actionAnimation));
    }
    private PlayState movement(AnimationState<UnknownEntity> state){
        if(phase()==0)return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("spawn"));
        if(phase()==2)return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("death"));
        if(entityData.get(LEAPING))return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("jump"));
        if(speed()>.48)return state.setAndContinue(RawAnimation.begin().thenLoop("run"));
        if(speed()>.06)return state.setAndContinue(RawAnimation.begin().thenLoop("walk"));
        return state.setAndContinue(RawAnimation.begin().thenLoop("idle"));
    }
    private PlayState actionAnimation(AnimationState<UnknownEntity> state){
        if(phase()!=1||action()==0)return PlayState.STOP;
        String clip=switch(action()){case 2->"grab";case 3->"chew";case 4->"swallow";case 5->"throw";default->"bite";};
        return state.setAndContinue(RawAnimation.begin().thenPlay(clip));
    }
}
