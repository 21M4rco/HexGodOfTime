package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.warping.VoidSea;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Abyssal Pilgrim. Sole apex creature of the abyss dimension and, by design, the only thing
 * alive in it.
 *
 * <p>It cannot be damaged, knocked back, pushed, stunned, mounted or held. Blows land visually and
 * change nothing. The only way it leaves the world is the deliberate death sequence in
 * {@link #beginDeath()}, which an operator triggers; there is no in game path to it, because
 * "immortal to everything" and "has a death animation" are only compatible if the death is
 * administrative.
 *
 * <p>Nothing about the body is networked. Clients rebuild all twenty two joints from the entity's
 * own interpolated position using the same {@link LeviathanSegmentController} the server runs, so a
 * hundred and fifty block creature costs the same bandwidth as a squid.
 */
public class AbyssalPilgrimEntity extends Mob implements GeoEntity {
    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> ATTACK = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> ATTACK_TICK = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> GLOW = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FRENZY = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> HELD = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DYING = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LOOK = SynchedEntityData.defineId(AbyssalPilgrimEntity.class, EntityDataSerializers.INT);

    /** Ticks the death sequence runs for before the entity is finally removed. */
    public static final int DEATH_TICKS = 320;

    private final LeviathanSegmentController segments = new LeviathanSegmentController();
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final LeviathanMultipartHitbox[] parts;
    private AbyssalPilgrimAI ai;

    private float bankIntent, bank, prevBank;
    private float renderGlow, prevRenderGlow;
    private double cachedSurface = VoidSea.SURFACE, cachedFloor = VoidSea.FLOOR;
    private int terrainClock;
    /** Client side, used to fade ambience and decide appendage detail. */
    private double viewerDistance = 1024;
    /** The Warping cell this creature owns. It never leaves it, so two portals never share a hunter. */
    private double cell = Double.NaN;

    public AbyssalPilgrimEntity(EntityType<? extends AbyssalPilgrimEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.setNoGravity(true);
        this.setPersistenceRequired();
        this.moveControl = new LeviathanMoveControl(this);
        this.navigation = new LeviathanNavigation(this, level);
        this.parts = buildParts();
        if (!level.isClientSide) this.ai = new AbyssalPilgrimAI(this);
    }

    private LeviathanMultipartHitbox[] buildParts() {
        LeviathanMultipartHitbox[] built = new LeviathanMultipartHitbox[LeviathanSegmentController.SEGMENTS];
        for (int i = 0; i < built.length; i++) {
            LeviathanMultipartHitbox.Section section =
                i == 0 ? LeviathanMultipartHitbox.Section.HEAD
              : i < LeviathanSegmentController.BODY_START ? LeviathanMultipartHitbox.Section.NECK
              : i < LeviathanSegmentController.TAIL_START ? LeviathanMultipartHitbox.Section.BODY
              : LeviathanMultipartHitbox.Section.TAIL;
            float r = (float) LeviathanSegmentController.radius(i);
            built[i] = new LeviathanMultipartHitbox(this, section, i, r * 2f, r * 1.7f);
        }
        return built;
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 1024.0)
            .add(Attributes.MOVEMENT_SPEED, 0.6)
            .add(Attributes.FOLLOW_RANGE, 2048.0)
            .add(Attributes.ATTACK_DAMAGE, 24.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STATE, (byte) LeviathanState.SEARCH.ordinal());
        this.entityData.define(ATTACK, (byte) -1);
        this.entityData.define(ATTACK_TICK, 0);
        this.entityData.define(GLOW, 0.2f);
        this.entityData.define(FRENZY, 0f);
        this.entityData.define(HELD, -1);
        this.entityData.define(DYING, 0);
        this.entityData.define(LOOK, -1);
    }

    @Override protected void registerGoals() { }

    // ------------------------------------------------------------------ state accessors

    public LeviathanSegmentController segments() { return segments; }
    public LeviathanMoveControl control() { return (LeviathanMoveControl) this.moveControl; }
    @Nullable public AbyssalPilgrimAI ai() { return ai; }

    public LeviathanState state() { return LeviathanState.byId(this.entityData.get(STATE)); }
    public void setState(LeviathanState state) { this.entityData.set(STATE, (byte) state.ordinal()); }

    @Nullable public LeviathanAttack attack() { byte b = this.entityData.get(ATTACK); return b < 0 ? null : LeviathanAttack.byId(b); }
    public void setAttack(@Nullable LeviathanAttack attack) { this.entityData.set(ATTACK, (byte) (attack == null ? -1 : attack.ordinal())); }
    public int attackTick() { return this.entityData.get(ATTACK_TICK); }
    public void setAttackTick(int tick) { this.entityData.set(ATTACK_TICK, tick); }

    public float glow() { return this.entityData.get(GLOW); }
    public void setGlow(float glow) { this.entityData.set(GLOW, Mth.clamp(glow, 0f, 1f)); }
    public float renderGlow(float partial) { return Mth.lerp(partial, prevRenderGlow, renderGlow); }

    public float frenzy() { return this.entityData.get(FRENZY); }
    public void setFrenzy(float value) { this.entityData.set(FRENZY, Mth.clamp(value, 0f, 1f)); }

    public int heldId() { return this.entityData.get(HELD); }
    public void setHeldId(int id) { this.entityData.set(HELD, id); }
    @Nullable public Entity held() { int id = heldId(); return id < 0 ? null : level().getEntity(id); }

    /** Entity the head is currently watching. One integer, changed rarely, drives all head tracking. */
    public int lookTargetId() { return this.entityData.get(LOOK); }
    public void setLookTargetId(int id) { this.entityData.set(LOOK, id); }
    @Nullable public Entity lookTarget() { int id = lookTargetId(); return id < 0 ? null : level().getEntity(id); }

    public int dying() { return this.entityData.get(DYING); }
    public boolean isDying() { return dying() > 0; }

    public void setBankIntent(float degrees) { this.bankIntent = degrees; }
    public float bank(float partial) { return Mth.lerp(partial, prevBank, bank); }
    public double viewerDistance() { return viewerDistance; }
    public void setViewerDistance(double distance) { this.viewerDistance = distance; }
    public double cell() { return Double.isNaN(cell) ? com.hexgodofstories.warping.WarpMath.cellX(getX()) : cell; }
    public void setCell(double cell) { this.cell = cell; }

    // ------------------------------------------------------------------ world queries

    public double surfaceY() { return cachedSurface; }
    public double floorY() { return cachedFloor; }
    public boolean nearSurface() { return getY() > cachedSurface - 14; }
    public boolean isSubmerged() { return getY() < cachedSurface - 0.4 && level().getFluidState(blockPosition()).is(FluidTags.WATER) || getY() < cachedSurface - 3; }
    /** Depth below the waterline in blocks, zero at the surface. */
    public double depth() { return Math.max(0, cachedSurface - getY()); }

    private void refreshTerrain() {
        int x = Mth.floor(getX()), z = Mth.floor(getZ());
        if (!level().hasChunkAt(blockPosition())) return;
        cachedSurface = level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        cachedFloor = level().getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        if (cachedFloor > cachedSurface - 8) cachedFloor = cachedSurface - 8;
    }

    // ------------------------------------------------------------------ immortality

    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && !isDying() && amount > 0 && source.getEntity() != null && tickCount % 4 == 0) {
            playSound(HexGodOfStories.PILGRIM_HURT.get(), 6f, 0.7f + random.nextFloat() * 0.2f);
            if (ai != null) ai.provoke(source.getEntity());
        }
        return false;
    }

    @Override public boolean isInvulnerableTo(DamageSource source) { return !isDying(); }
    @Override public boolean isPushable() { return false; }
    @Override public void push(double x, double y, double z) { }
    @Override protected void pushEntities() { }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean canBreatheUnderwater() { return true; }
    @Override public boolean fireImmune() { return true; }
    @Override public boolean canChangeDimensions() { return false; }
    @Override public boolean isNoGravity() { return true; }
    @Override public boolean canBeLeashed(net.minecraft.world.entity.player.Player player) { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void checkDespawn() { }
    @Override public boolean displayFireAnimation() { return false; }
    @Override public boolean addEffect(net.minecraft.world.effect.MobEffectInstance effect, @Nullable Entity source) { return false; }
    /** Vanilla travel is bypassed entirely; motion belongs to the move control. */
    @Override public void travel(Vec3 input) { }
    @Override protected void doPush(Entity entity) { }

    // ------------------------------------------------------------------ multipart

    @Override public boolean isMultipartEntity() { return true; }
    @Override public PartEntity<?>[] getParts() { return parts; }
    public LeviathanMultipartHitbox[] hitboxes() { return parts; }

    @Override public AABB getBoundingBoxForCulling() { return segments.primed() ? segments.bounds() : super.getBoundingBoxForCulling().inflate(160); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return true; }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        prevBank = bank;
        prevRenderGlow = renderGlow;
        if (terrainClock-- <= 0) { terrainClock = 20; refreshTerrain(); }

        super.tick();

        if (!level().isClientSide) {
            // Without this the creature freezes the moment it leaves simulation distance, which is
            // most of its life: it hunts from beyond sight on purpose.
            if (tickCount % 20 == 0 && level() instanceof ServerLevel server) PilgrimWarden.renew(server, this);
            if (isDying()) tickDeathSequence();
            Vec3 motion = getDeltaMovement();
            if (motion.lengthSqr() > 1.0E-8) {
                // Stay inside this portal's cell, off the floor, and under the ceiling a breach needs.
                double nx = VoidSea.clampX(cell(), getX() + motion.x);
                double nz = VoidSea.clampZ(getZ() + motion.z);
                double ny = Mth.clamp(getY() + motion.y, cachedFloor + 3.5, Math.min(cachedSurface + 190, VoidSea.MAX_Y - 12));
                setPos(nx, ny, nz);
            }
            setYHeadRot(getYRot());
            if (tickCount % 2 == 0) bodyContact();
        }

        bank += Mth.wrapDegrees(bankIntent - bank) * 0.12f;
        renderGlow += (glow() - renderGlow) * 0.14f;

        segments.push(position(), getYRot(), getXRot());
        segments.rebuild();
        positionParts();

        // Client only presentation lives in a class the dedicated server never resolves.
        if (level().isClientSide) net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> com.hexgodofstories.client.leviathan.LeviathanClientHooks.clientTick(this));
    }

    /**
     * Full body contact. Anything overlapping a segment is shoved clear of it, and a body moving at
     * speed hurts on the way past. Without this a hundred and sixty blocks of creature read as
     * scenery: it would slide through a swimmer and neither would notice.
     */
    private void bodyContact() {
        if (isDying() || !segments.primed()) return;
        List<LivingEntity> near = level().getEntitiesOfClass(LivingEntity.class, segments.bounds(),
            e -> e != this && e.isAlive() && !(e instanceof AbyssalPilgrimEntity)
                 && !(e instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator())));
        if (near.isEmpty()) return;
        double speed = getDeltaMovement().length();
        for (LivingEntity victim : near) {
            int index = segments.nearest(victim.position());
            Vec3 centre = segments.segment(index);
            double reach = LeviathanSegmentController.radius(index) + victim.getBbWidth() * 0.5;
            Vec3 away = victim.position().subtract(centre);
            double distance = away.length();
            if (distance > reach) continue;
            away = distance < 1.0E-4 ? new Vec3(0, 1, 0) : away.scale(1.0 / distance);
            double shove = 0.30 + speed * 0.85;
            victim.setDeltaMovement(victim.getDeltaMovement().scale(0.55).add(away.scale(shove)));
            victim.hurtMarked = true;
            victim.fallDistance = 0;
            if (speed > 0.5 && tickCount % 10 == 0) victim.hurt(damageSources().mobAttack(this), (float) (2.0 + speed * 4.0));
        }
    }

    private void positionParts() {
        for (int i = 0; i < parts.length; i++) {
            Vec3 p = segments.segment(i);
            LeviathanMultipartHitbox part = parts[i];
            part.setPos(p.x, p.y - part.getBbHeight() * 0.5, p.z);
            part.xo = p.x; part.yo = p.y; part.zo = p.z;
        }
    }

    @Override
    protected void customServerAiStep() {
        if (ai != null && !isDying()) ai.tick();
    }

    // ------------------------------------------------------------------ deliberate death

    /** Operator only. Starts the sinking sequence; nothing in normal play can reach this. */
    public void beginDeath() {
        if (isDying()) return;
        this.entityData.set(DYING, 1);
        setAttack(null);
        control().stopMoving();
        if (level() instanceof ServerLevel server) {
            HexNetwork.pilgrimEffect(this, "death", segments.segment(0), 1f);
            server.playSound(null, blockPosition(), HexGodOfStories.PILGRIM_DEATH.get(), SoundSource.HOSTILE, 64f, 0.6f);
        }
    }

    /** Named apart from LivingEntity#tickDeath: this creature never dies the vanilla way. */
    private void tickDeathSequence() {
        int t = dying();
        this.entityData.set(DYING, t + 1);
        // Coordination fails first, then the body simply stops holding itself up.
        double sink = -0.02 - Math.min(0.32, t / 900.0);
        double wobble = Math.sin(t * 0.07) * 0.035 * Math.min(1, t / 60.0);
        setDeltaMovement(getDeltaMovement().scale(0.965).add(wobble, sink, wobble * 0.6));
        setGlow(Math.max(0f, 0.55f * (1f - t / (float) DEATH_TICKS) * (0.55f + 0.45f * (float) Math.sin(t * 0.33))));
        if (t == 40 && level() instanceof ServerLevel server) server.playSound(null, blockPosition(), HexGodOfStories.PILGRIM_ROAR.get(), SoundSource.HOSTILE, 72f, 0.45f);
        if (t >= DEATH_TICKS && getY() <= cachedFloor + 6) discard();
        if (t >= DEATH_TICKS * 3) discard();
    }

    // ------------------------------------------------------------------ sound plumbing

    public void voice(SoundEvent sound, float volume, float pitch) {
        if (level() instanceof ServerLevel server) server.playSound(null, getX(), getY(), getZ(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    @Override protected SoundEvent getAmbientSound() { return null; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return null; }
    @Override protected SoundEvent getDeathSound() { return null; }
    @Override public boolean isSilent() { return false; }

    // ------------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("PilgrimState", (byte) state().ordinal());
        tag.putFloat("PilgrimFrenzy", frenzy());
        tag.putInt("PilgrimDying", dying());
        if (!Double.isNaN(cell)) tag.putDouble("PilgrimCell", cell);
        if (ai != null) ai.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setState(LeviathanState.byId(tag.getByte("PilgrimState")));
        setFrenzy(tag.getFloat("PilgrimFrenzy"));
        this.entityData.set(DYING, tag.getInt("PilgrimDying"));
        if (tag.contains("PilgrimCell")) cell = tag.getDouble("PilgrimCell");
        if (ai != null) ai.load(tag);
        segments.reset(position(), getYRot(), getXRot());
    }

    @Override
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        super.moveTo(x, y, z, yaw, pitch);
        segments.reset(new Vec3(x, y, z), yaw, pitch);
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "locomotion", 14, this::locomotion));
        controllers.add(new AnimationController<>(this, "action", 6, this::action));
    }

    private PlayState locomotion(AnimationState<AbyssalPilgrimEntity> event) {
        if (isDying()) return event.setAndContinue(AbyssalPilgrimAnimations.DEATH);
        if (!isSubmerged()) return event.setAndContinue(AbyssalPilgrimAnimations.AIRBORNE);
        double speed = getDeltaMovement().length();
        LeviathanState state = state();
        if (state == LeviathanState.FRENZY) return event.setAndContinue(AbyssalPilgrimAnimations.FRENZY);
        if (state == LeviathanState.AMBUSH || state == LeviathanState.STALK) return event.setAndContinue(AbyssalPilgrimAnimations.STALK);
        if (getXRot() < -42 && speed > 0.6) return event.setAndContinue(AbyssalPilgrimAnimations.VERTICAL_ASCENT);
        if (getXRot() > 42 && speed > 0.6) return event.setAndContinue(AbyssalPilgrimAnimations.DEEP_DIVE);
        if (speed > 1.05) return event.setAndContinue(AbyssalPilgrimAnimations.FAST_SWIM);
        if (state == LeviathanState.TOY) return event.setAndContinue(AbyssalPilgrimAnimations.CIRCLE);
        return event.setAndContinue(AbyssalPilgrimAnimations.SWIM);
    }

    private PlayState action(AnimationState<AbyssalPilgrimEntity> event) {
        LeviathanAttack attack = attack();
        if (attack == null || isDying()) return PlayState.STOP;
        RawAnimation animation = AbyssalPilgrimAnimations.forAttack(attack, attackTick());
        return animation == null ? PlayState.STOP : event.setAndContinue(animation);
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    @Override public double getTick(Object entity) { return ((Entity) entity).tickCount; }
}
