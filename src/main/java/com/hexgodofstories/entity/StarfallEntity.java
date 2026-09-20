package com.hexgodofstories.entity;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.SanctumWard;
import com.hexgodofstories.server.PocketRealm;
import com.hexgodofstories.server.Starfall;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;
import java.util.UUID;

/**
 * A meteor the sanctum pulls down on whoever is troubling its owner.
 *
 * <p>It is a falling rock, and it is written like one. It comes in from far above the build limit on a long
 * diagonal, it <em>accelerates</em> the whole way down under its own weight rather than cruising at a set
 * speed, and it only steers a little — enough to be the keeper's doing, nowhere near enough to read as a guided
 * missile. It burns hotter the lower and faster it gets, because that is where the air is, and the renderer
 * and the audio are both driven from that one number.
 *
 * <p>They are not all the same stone. Each one picks its own size, from a boulder to a five-block mass,
 * weighted so the big ones are rare — and the bigger it is the slower it falls and the more slowly it
 * gathers speed, so a large one comes down with a weight a small one never has, and hits accordingly.
 *
 * <p>Nothing in the canopy stops one. Logs, leaves and the glow in the boughs are passed straight through,
 * because a meteor that thumps to a halt in the top of the world tree is a meteor that never arrives — and
 * because the tree is the one thing on the island that should never be in the way.
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
    private static final EntityDataAccessor<Float> SIZE=SynchedEntityData.defineId(StarfallEntity.class,EntityDataSerializers.FLOAT);

    /** Blocks across. A boulder at one, a five-block mass at the top of the range. */
    public static final float MIN_SIZE=1,MAX_SIZE=5;
    /** What a one-block stone does. Everything larger scales off it. */
    private static final float BASE_DAMAGE=18,SIZE_DAMAGE=7,BASE_SPLASH=2.4f,SIZE_SPLASH=.95f;
    private static final int LIFETIME=520;
    /** Entry speed, the pull that builds on it, and the speed the air will not let it pass. */
    private static final double ENTRY=.85,PULL=.13,TERMINAL=3.6;
    /** How much of that a five-block mass gives up. Bigger is slower, and slower to get there. */
    private static final double SIZE_DRAG=.58,SIZE_INERTIA=.48;
    /** How little it corrects. A meteor is not a missile. */
    private static final double STEER=.035;

    private UUID owner;
    private int age;
    private int plot;
    private boolean bounded;

    public StarfallEntity(EntityType<? extends StarfallEntity> type,Level level) {super(type,level);noPhysics=true;}
    @Override protected void defineSynchedData() {
        entityData.define(QUARRY,0);entityData.define(CHARGE,0f);entityData.define(HEAT,0f);entityData.define(SEED,0);
        entityData.define(SIZE,MIN_SIZE);
    }

    /** 0 far out, 1 about to land. */
    public float charge() {return entityData.get(CHARGE);}
    /** How hard it is burning: speed and thickening air together. Drives the flame and the roar. */
    public float heat() {return entityData.get(HEAT);}
    public int seed() {return entityData.get(SEED);}
    /** Blocks across, one to five. Drives the renderer, the fall, the harm and the reach of the impact. */
    public float size() {return entityData.get(SIZE);}
    /** 0 for the smallest stone, 1 for the largest. */
    public float bulk() {return (size()-MIN_SIZE)/(MAX_SIZE-MIN_SIZE);}
    private double terminal() {return TERMINAL*(1-SIZE_DRAG*bulk());}
    private double pull() {return PULL*(1-SIZE_INERTIA*bulk());}
    public float damage() {return BASE_DAMAGE+SIZE_DAMAGE*size();}
    public float splash() {return BASE_SPLASH+SIZE_SPLASH*size();}
    public int quarryId() {return entityData.get(QUARRY);}

    public static void fall(ServerPlayer caster,LivingEntity quarry,Vec3 from) {
        StarfallEntity star=new StarfallEntity(HexGodOfStories.STARFALL.get(),quarry.level());
        star.owner=caster.getUUID();
        star.plot=PocketRealm.plotAt(caster.getX(),caster.getZ());star.bounded=PocketRealm.inside(caster.level());
        star.setPos(from.x,from.y,from.z);
        star.entityData.set(QUARRY,quarry.getId());
        star.entityData.set(SEED,caster.getRandom().nextInt(1 << 20));
        // Weighted low, so most are boulders and a five-block mass is something you notice arriving.
        float roll=caster.getRandom().nextFloat();
        star.entityData.set(SIZE,MIN_SIZE+(MAX_SIZE-MIN_SIZE)*(float)Math.pow(roll,2.3));
        Vec3 aim=quarry.getBoundingBox().getCenter().subtract(from);
        star.setDeltaMovement(aim.lengthSqr()<1e-6?new Vec3(0,-ENTRY,0):aim.normalize().scale(ENTRY));
        quarry.level().addFreshEntity(star);
        HexNetwork.fx(star,"meteor_entry");
        // The sound of something arriving through the air, heard long before it lands.
        quarry.level().playSound(null,BlockPos.containing(from),HexGodOfStories.METEOR_ROAR.get(),SoundSource.WEATHER,
            3.4f+star.size()*.3f,.74f-star.bulk()*.24f);
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
        velocity=velocity.add(0,-pull(),0);
        if(quarry!=null&&quarry.isAlive()&&(!bounded||Starfall.validPoint((ServerLevel)level(),plot,quarry.getX(),quarry.getZ()))) {
            Vec3 aim=quarry.getBoundingBox().getCenter().subtract(position());
            double distance=aim.length();
            entityData.set(CHARGE,(float)Math.max(0,Math.min(1,1-distance/90)));
            if(distance<1.6){burst(quarry);return;}
            velocity=velocity.add(aim.scale(STEER/Math.max(1e-4,distance)));
        }
        double terminal=terminal();
        double speed=velocity.length();
        if(speed>terminal)velocity=velocity.scale(terminal/speed);
        setDeltaMovement(velocity);
        // Burning hardest when it is fast and low: that is where the atmosphere is.
        entityData.set(HEAT,(float)Math.min(1,speed/terminal*.65+charge()*.5));
        Vec3 next=position().add(velocity);
        if(bounded&&!Starfall.validPath((ServerLevel)level(),plot,position(),next)) {
            // Never follow an escaping quarry over the coast. Keep falling above the last valid
            // column, preserving vertical acceleration, size, heat and the ordinary impact.
            velocity=new Vec3(0,velocity.y,0);setDeltaMovement(velocity);
            next=position().add(velocity);
            if(!Starfall.validPoint((ServerLevel)level(),plot,getX(),getZ())){discard();return;}
        }
        Vec3 stopped=obstruction(position(),next);
        if(stopped!=null){setPos(stopped.x,stopped.y,stopped.z);burst(null);return;}
        setPos(next.x,next.y,next.z);
    }

    /**
     * Where the ground stops it, or null for a clear step.
     *
     * <p>Stepped by hand rather than handed to the level's own clip, because the clip has no way to be
     * told to ignore something and the world tree has to be ignored. Logs, leaves and the lights in the
     * boughs are passed straight through; the first genuinely solid thing that is not the tree is the end
     * of the journey.
     */
    private Vec3 obstruction(Vec3 from,Vec3 to) {
        Vec3 along=to.subtract(from);
        double distance=along.length();
        if(distance<1e-6)return null;
        Vec3 step=along.scale(.45/distance);
        int steps=(int)Math.ceil(distance/.45);
        Vec3 at=from;
        for(int i=0;i<steps;i++) {
            at=i==steps-1?to:at.add(step);
            BlockPos pos=BlockPos.containing(at);
            if(!level().hasChunkAt(pos))continue;
            BlockState state=level().getBlockState(pos);
            if(passesThrough(state))continue;
            if(state.getCollisionShape(level(),pos).isEmpty())continue;
            return at;
        }
        return null;
    }

    /** The world tree, and anything else with nothing to stop a falling stone. */
    private static boolean passesThrough(BlockState state) {
        return state.isAir()
            ||state.is(net.minecraft.tags.BlockTags.LOGS)
            ||state.is(net.minecraft.tags.BlockTags.LEAVES)
            ||state.is(net.minecraft.world.level.block.Blocks.VERDANT_FROGLIGHT)
            ||state.is(net.minecraft.world.level.block.Blocks.AMETHYST_CLUSTER)
            ||state.is(net.minecraft.tags.BlockTags.FLOWERS)
            ||state.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET);
    }

    /** Fire, harm and a very loud arrival. Never terrain, and never the owner. */
    private void burst(Entity direct) {
        if(isRemoved())return;
        ServerPlayer caster=owner==null||!(level() instanceof ServerLevel level)?null:level.getServer().getPlayerList().getPlayer(owner);
        Vec3 at=position();
        float splash=splash();
        for(LivingEntity victim:level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(splash))) {
            if(victim==caster||SanctumWard.owner(victim))continue;
            if(caster!=null&&victim.getUUID().equals(owner))continue;
            double distance=victim.getBoundingBox().getCenter().distanceTo(at);
            float share=victim==direct?1:(float)Math.max(0,1-distance/splash);
            if(share<=.05f)continue;
            var source=caster!=null?victim.damageSources().indirectMagic(this,caster):victim.damageSources().magic();
            victim.invulnerableTime=0;
            victim.hurt(source,damage()*share);
            // It was on fire the whole way down; so is whatever it hit.
            if(share>.3f&&!victim.fireImmune())victim.setSecondsOnFire(4);
        }
        HexNetwork.fx(this,"meteor_impact");
        // A bigger stone lands louder and lower.
        level().playSound(null,blockPosition(),HexGodOfStories.METEOR_IMPACT.get(),SoundSource.WEATHER,
            2.8f+size()*.28f,.70f-bulk()*.20f);
        discard();
    }

    @Override public boolean isPickable() {return false;}
    @Override public boolean canBeCollidedWith() {return false;}
    @Override public boolean isPushable() {return false;}
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount) {return false;}
    /** Visible from a long way off on purpose: the point is watching it come down. */
    @Override public boolean shouldRenderAtSqrDistance(double distance) {return distance<90000;}
    @Override protected void readAdditionalSaveData(CompoundTag n) {
        if(n.hasUUID("owner"))owner=n.getUUID("owner");
        age=n.getInt("age");
        bounded=n.getBoolean("bounded");plot=n.getInt("plot");
        if(n.contains("size"))entityData.set(SIZE,Math.max(MIN_SIZE,Math.min(MAX_SIZE,n.getFloat("size"))));
    }
    @Override protected void addAdditionalSaveData(CompoundTag n) {
        if(owner!=null)n.putUUID("owner",owner);
        n.putInt("age",age);
        n.putBoolean("bounded",bounded);n.putInt("plot",plot);
        n.putFloat("size",size());
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
