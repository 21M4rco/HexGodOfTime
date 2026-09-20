package com.loki.entity;

import com.loki.Loki;
import com.loki.data.Discipline;
import com.loki.server.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraftforge.network.NetworkHooks;

/**
 * A conjured blade in flight. It keeps its own orientation, buries itself in whatever it strikes and
 * opens a wound before dissolving. {@code STATE} is the single synced authority on where it lives:
 * {@code 0} in flight, {@code -1} buried in terrain, any other value the id of the body carrying it.
 *
 * <p>The contact point is what is remembered, not the victim's feet. It is stored as a fraction of
 * the struck body's own width and height, in that body's yaw frame, alongside the part it landed in
 * and the angle it went in at. A blade in the chest therefore stays in the chest while the creature
 * walks, turns, is knocked back, grows or animates, and a blade in an arm travels with the arm.
 */
public final class ThrownDagger extends ThrowableProjectile {
    private static final EntityDataAccessor<Integer> STATE=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> OFFSET_X=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> OFFSET_Y=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> OFFSET_Z=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ENTRY_YAW=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ENTRY_PITCH=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> PART=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ILLUSORY=SynchedEntityData.defineId(ThrownDagger.class,EntityDataSerializers.BOOLEAN);
    public static final int FLYING=0,IN_BLOCK=-1;
    /** Which piece of the body is carrying the blade; drives how the wound moves as the body animates. */
    public static final int TORSO=0,HEAD=1,RIGHT_ARM=2,LEFT_ARM=3,RIGHT_LEG=4,LEFT_LEG=5;
    private int life=200;
    private Vec3 embeddedPosition;

    public ThrownDagger(EntityType<? extends ThrownDagger> type,Level level) {super(type,level);}

    @Override protected void defineSynchedData() {
        entityData.define(STATE,FLYING);entityData.define(OFFSET_X,0f);entityData.define(OFFSET_Y,0f);entityData.define(OFFSET_Z,0f);
        entityData.define(ROLL,0f);entityData.define(ENTRY_YAW,0f);entityData.define(ENTRY_PITCH,0f);
        entityData.define(PART,TORSO);entityData.define(ILLUSORY,false);
    }
    public int state() {return entityData.get(STATE);}
    public boolean flying() {return state()==FLYING;}
    public boolean illusory() {return entityData.get(ILLUSORY);}
    public float roll() {return entityData.get(ROLL);}
    public int part() {return entityData.get(PART);}
    public float entryYaw() {return entityData.get(ENTRY_YAW);}
    public float entryPitch() {return entityData.get(ENTRY_PITCH);}
    /** The wound, as a fraction of the carrier's width and height in its own yaw frame. */
    public Vec3 offset() {return new Vec3(entityData.get(OFFSET_X),entityData.get(OFFSET_Y),entityData.get(OFFSET_Z));}
    /** The body this blade is riding, or null while it is in flight or in terrain. */
    public Entity carrier() {
        int state=state();
        return state==FLYING||state==IN_BLOCK?null:level().getEntity(state);
    }

    public static ThrownDagger throwFrom(ServerPlayer p,Vec3 from,Vec3 aim,boolean illusory) {
        ThrownDagger e=new ThrownDagger(Loki.THROWN_DAGGER.get(),p.level());
        e.setOwner(p);e.setPos(from.x,from.y,from.z);
        e.entityData.set(ILLUSORY,illusory);
        e.entityData.set(ROLL,p.getRandom().nextFloat()*360);
        e.shoot(aim.x,aim.y,aim.z,1.85f,.7f);
        p.level().addFreshEntity(e);
        p.level().playSound(null,e.blockPosition(),Loki.BLADE_THROW.get(),SoundSource.PLAYERS,.8f,.95f+p.getRandom().nextFloat()*.12f);
        return e;
    }

    @Override protected float getGravity() {return flying()?.026f:0;}
    @Override protected boolean canHitEntity(Entity e) {
        // The owner is the player for damage credit; friendly copies must not intercept the blade
        // at its release point (or consume another copy's throw on the way to the quarry).
        if(e instanceof IllusionEntity clone&&getOwner()!=null&&getOwner().getUUID().equals(clone.owner()))return false;
        return flying()&&e!=getOwner()&&(!(getOwner() instanceof ServerPlayer p)||LokiServer.validTarget(p,e))&&super.canHitEntity(e);
    }

    @Override public void tick() {
        int state=state();
        if(state==FLYING) {
            Vec3 velocity=getDeltaMovement();
            super.tick();
            // ThrowableProjectile advances using its pre-hit velocity even after onHit embeds us.
            // Restore the server's contact point immediately, without a one-tick ghost continuation.
            if(!flying()) {
                setDeltaMovement(Vec3.ZERO);
                if(embeddedPosition!=null)setPos(embeddedPosition);
                return;
            }
            if(velocity.lengthSqr()>1e-6) {
                setYRot((float)(Mth.atan2(velocity.x,velocity.z)*Mth.RAD_TO_DEG));
                setXRot((float)(Mth.atan2(velocity.y,velocity.horizontalDistance())*Mth.RAD_TO_DEG));
                yRotO=getYRot();xRotO=getXRot();
            }
            if(!level().isClientSide&&tickCount>120)dissolve();
            return;
        }
        xo=getX();yo=getY();zo=getZ();
        xOld=getX();yOld=getY();zOld=getZ();
        if(state!=IN_BLOCK) {
            Entity host=level().getEntity(state);
            if(host==null||!host.isAlive()) {
                // The body carrying the blade is gone; let it hang where it fell and fade out quickly.
                if(!level().isClientSide){entityData.set(STATE,IN_BLOCK);life=Math.min(life,30);}
            } else {
                Vec3 world=carried(host);
                setPos(host.getX()+world.x,host.getY()+world.y,host.getZ()+world.z);
            }
        }
        setDeltaMovement(Vec3.ZERO);
        if(!level().isClientSide&&--life<=0)dissolve();
    }

    /** The stored fraction turned back into a world offset from the carrier's feet. */
    private Vec3 carried(Entity host) {
        Vec3 unit=offset();
        double width=Math.max(.1,host.getBbWidth()),height=Math.max(.1,host.getBbHeight());
        double x=unit.x*width,y=unit.y*height,z=unit.z*width;
        float yaw=bodyYaw(host);
        double sin=Math.sin(-yaw*Mth.DEG_TO_RAD),cos=Math.cos(-yaw*Mth.DEG_TO_RAD);
        return new Vec3(x*cos+z*sin,y,-x*sin+z*cos);
    }
    private static float bodyYaw(Entity host) {return host instanceof LivingEntity living?living.yBodyRot:host.getYRot();}

    @Override protected void onHitEntity(EntityHitResult hit) {
        if(level().isClientSide||!flying())return;
        Entity victim=hit.getEntity();
        Vec3 contact=contact(victim);
        if(!illusory()&&getOwner() instanceof ServerPlayer p) {
            float damage=5+Math.min(4,com.loki.data.LokiData.mastery(p,Discipline.CONJURATION)*.005f);
            if(victim.hurt(damageSources().thrown(this,p),damage)) {
                if(victim instanceof LivingEntity living)Bleed.apply(p,living,1,160);
                LokiServer.reward(p,Discipline.CONJURATION,40);
            }
        }
        level().playSound(null,blockPosition(),Loki.BLADE_HIT.get(),SoundSource.PLAYERS,.9f,1f+random.nextFloat()*.15f);
        LokiNetwork.fx(this,"blade_bite",contact.x,contact.y,contact.z);
        if(illusory()){dissolve();return;}
        embed(victim,contact);
        life=170;
        setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Vanilla's ThrowableProjectile entity hit may only contain the entity's base position. Re-clip
     * the actual swept segment; a base point inside the bounding box is not an impact location.
     */
    private Vec3 contact(Entity victim) {
        AABB box=victim.getBoundingBox();
        Vec3 from=position(),to=from.add(getDeltaMovement());
        Vec3 candidate=box.clip(from,to).orElseGet(()->new Vec3(
                Mth.clamp(from.x,box.minX,box.maxX),
                Mth.clamp(from.y,box.minY,box.maxY),
                Mth.clamp(from.z,box.minZ,box.maxZ)));
        // Sink it a little way in along the flight line so the hilt, not the whole blade, stands proud.
        Vec3 heading=getDeltaMovement();
        if(heading.lengthSqr()>1e-6)candidate=candidate.add(heading.normalize().scale(Math.min(.18,victim.getBbWidth()*.3)));
        return new Vec3(
            Mth.clamp(candidate.x,box.minX,box.maxX),
            Mth.clamp(candidate.y,box.minY,box.maxY),
            Mth.clamp(candidate.z,box.minZ,box.maxZ));
    }

    /** Stores the wound in the victim's own frame, as fractions, plus the angle the steel went in at. */
    private void embed(Entity victim,Vec3 contact) {
        float yaw=bodyYaw(victim);
        Vec3 relative=contact.subtract(victim.position());
        double sin=Math.sin(yaw*Mth.DEG_TO_RAD),cos=Math.cos(yaw*Mth.DEG_TO_RAD);
        double width=Math.max(.1,victim.getBbWidth()),height=Math.max(.1,victim.getBbHeight());
        float x=(float)((relative.x*cos+relative.z*sin)/width);
        float y=(float)(relative.y/height);
        float z=(float)((-relative.x*sin+relative.z*cos)/width);
        entityData.set(OFFSET_X,x);entityData.set(OFFSET_Y,y);entityData.set(OFFSET_Z,z);
        entityData.set(PART,classify(victim,x,y));
        Vec3 heading=getDeltaMovement();
        entityData.set(ENTRY_YAW,Mth.wrapDegrees((float)(Mth.atan2(heading.x,heading.z)*Mth.RAD_TO_DEG)+yaw));
        entityData.set(ENTRY_PITCH,(float)(Mth.atan2(heading.y,heading.horizontalDistance())*Mth.RAD_TO_DEG));
        entityData.set(STATE,victim.getId());
        embeddedPosition=contact;setPos(contact);noPhysics=true;
    }

    /**
     * Which limb took it, in fractions of the body. Only shapes that plausibly have a humanoid
     * skeleton are split up; anything else is treated as one mass, which is the right answer for a
     * spider, a slime or a creature no one here has heard of.
     */
    private static int classify(Entity victim,float x,float y) {
        if(!(victim instanceof LivingEntity))return TORSO;
        boolean humanoid=victim.getBbHeight()>1.1&&victim.getBbWidth()<1.3&&victim.getBbHeight()/Math.max(.1f,victim.getBbWidth())>1.6;
        if(!humanoid)return TORSO;
        if(y>.76)return HEAD;
        if(y<.46)return x<0?RIGHT_LEG:LEFT_LEG;
        if(Math.abs(x)>.34)return x<0?RIGHT_ARM:LEFT_ARM;
        return TORSO;
    }

    @Override protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if(level().isClientSide||!flying())return;
        setPos(hit.getLocation().subtract(getDeltaMovement().normalize().scale(.12)));
        embeddedPosition=position();
        entityData.set(STATE,IN_BLOCK);
        setDeltaMovement(Vec3.ZERO);
        life=140;
        level().playSound(null,blockPosition(),Loki.BLADE_EMBED.get(),SoundSource.PLAYERS,.7f,.9f+random.nextFloat()*.2f);
    }

    private void dissolve() {if(!isRemoved()){LokiNetwork.fx(this,"dispel");discard();}}

    @Override protected void addAdditionalSaveData(CompoundTag n) {
        super.addAdditionalSaveData(n);n.putInt("state",state());n.putInt("life",life);n.putFloat("roll",roll());n.putBoolean("illusory",illusory());
    }
    @Override protected void readAdditionalSaveData(CompoundTag n) {
        super.readAdditionalSaveData(n);
        // A carried blade cannot be restored to a body that may not reload; drop it to a short-lived prop.
        entityData.set(STATE,n.getInt("state")==FLYING?FLYING:IN_BLOCK);
        life=Math.min(140,Math.max(1,n.getInt("life")));entityData.set(ROLL,n.getFloat("roll"));entityData.set(ILLUSORY,n.getBoolean("illusory"));
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
