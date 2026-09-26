package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.data.ScepterPose;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Scepter's right click, owned by the server.
 *
 * <p>A press starts holding the stone open; a release fires. Let go inside {@link #TAP} ticks and it is
 * a quick bolt that can be fired again almost at once. Hold longer and the stone fills over
 * {@link #FULL} ticks into a charged beam that burns through up to six bodies before it hits whatever
 * is behind them. Either way a body the beam passes through keeps a cauterised hole in it that bleeds
 * and slowly closes, and whatever the beam finally reaches detonates.
 *
 * <p>Aim is always taken from the caster's eyes, so a shot lands on the crosshair; the stone's position
 * is only where the beam is drawn from. A charge the caster somehow never releases discharges on its own
 * after {@link #MAX_HOLD} ticks, so a lost release can never leave it held forever.
 */
public final class ScepterBlast {
    private ScepterBlast() { }

    public static final double RANGE = 100;
    /** Presses shorter than this are taps. */
    public static final int TAP = 6;
    /** Ticks of holding past a tap for the stone to fill completely. */
    public static final int FULL = 32;
    public static final int QUICK_RECOVERY = 4, CHARGED_RECOVERY = 16, MAX_HOLD = 200;
    private static final int MAX_PIERCE = 6, MAX_STUNS = 192;

    /** 0 for a tap; a charged release runs from a quarter of full power at the start of the window to all of it. */
    public static float power(long held) {
        return held < TAP ? 0 : .25f + .75f * Mth.clamp((held - TAP) / (float) FULL, 0, 1);
    }

    private record Stun(LivingEntity victim, long until, boolean hadNoAi) { }
    private record Hit(LivingEntity victim, Vec3 at, double distance) { }
    /** A release that came inside the recovery: it fires the moment the stone is ready. */
    private record Queued(float power, long at) { }

    private static final Map<UUID, Long> CHARGES = new HashMap<>();
    private static final Map<UUID, Long> READY = new HashMap<>();
    private static final Map<UUID, Queued> QUEUED = new HashMap<>();

    public static int recovery(float power) {return power > 0 ? CHARGED_RECOVERY : QUICK_RECOVERY;}
    private static final Map<UUID, Stun> STUNS = new HashMap<>();

    private static boolean armed(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        return p.isAlive() && !p.isSpectator() && HexData.access(p) && !TemporalEngine.frozen(p) && !stunned(p)
            && held.getItem() instanceof ConjuredWeapon weapon && weapon.kind == 1 && ConjuredWeapon.belongsTo(held, p);
    }

    public static boolean charging(ServerPlayer p) {return CHARGES.containsKey(p.getUUID());}

    public static void press(ServerPlayer p) {
        if (!armed(p) || CHARGES.containsKey(p.getUUID())) return;
        long now = HexData.now(p);
        CHARGES.put(p.getUUID(), now);
        CompoundTag n = new CompoundTag();
        n.putString("state", "charge");
        n.putLong("start", now);
        HexNetwork.tracking(p, new HexNetwork.Message(HexNetwork.SCEPTER, p.getId(), n));
    }

    public static void release(ServerPlayer p) {
        Long start = CHARGES.remove(p.getUUID());
        if (start == null) return;
        long now = HexData.now(p);
        if (!armed(p)) {cancelled(p); return;}
        float power = power(now - start);
        long ready = READY.getOrDefault(p.getUUID(), Long.MIN_VALUE);
        if (now >= ready) {
            READY.put(p.getUUID(), now + recovery(power));
            fire(p, power);
        } else if (!QUEUED.containsKey(p.getUUID())) {
            // Tapping faster than the stone recovers never loses a shot: it goes as soon as it can.
            QUEUED.put(p.getUUID(), new Queued(power, ready));
            READY.put(p.getUUID(), ready + recovery(power));
        }
    }

    /** Drops a hold without firing, and tells everyone watching. */
    public static void cancel(ServerPlayer p) {
        if (CHARGES.remove(p.getUUID()) != null) cancelled(p);
    }

    private static void cancelled(ServerPlayer p) {
        CompoundTag n = new CompoundTag();
        n.putString("state", "cancel");
        HexNetwork.tracking(p, new HexNetwork.Message(HexNetwork.SCEPTER, p.getId(), n));
        HexNetwork.animate(p, "__clear__");
    }

    /** Once per player tick from HexServer. */
    public static void tick(ServerPlayer p) {
        long now = HexData.now(p);
        Queued queued = QUEUED.get(p.getUUID());
        if (queued != null && now >= queued.at) {
            QUEUED.remove(p.getUUID());
            if (armed(p)) fire(p, queued.power);
        }
        Long start = CHARGES.get(p.getUUID());
        if (start == null) return;
        if (!armed(p)) {cancel(p); return;}
        if (now - start >= MAX_HOLD) release(p);
    }

    public static void forget(ServerPlayer p) {
        CHARGES.remove(p.getUUID());
        READY.remove(p.getUUID());
        QUEUED.remove(p.getUUID());
    }

    static void fire(ServerPlayer caster, float power) {
        ServerLevel level = caster.serverLevel();
        boolean charged = power > 0;
        Vec3 eye = caster.getEyePosition();
        Vec3 direction = caster.getLookAngle().normalize();
        Vec3 end = eye.add(direction.scale(RANGE));
        Vec3 muzzle = ScepterPose.stoneMuzzle(caster);
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean wall = block.getType() != HitResult.Type.MISS;
        Vec3 stop = wall ? block.getLocation() : end;

        // The beam has width: a charged one is a hand across and more.
        double width = charged ? .30 + .55 * power : .26;
        List<Hit> hits = new ArrayList<>();
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, stop).inflate(width + 1.5),
            e -> HexServer.validTarget(caster, e))) {
            Optional<Vec3> at = candidate.getBoundingBox().inflate(width).clip(eye, stop);
            at.ifPresent(v -> hits.add(new Hit(candidate, v, eye.distanceToSqr(v))));
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        int through = charged ? Math.min(MAX_PIERCE, 1 + Math.round(5 * power)) : 1;
        List<Hit> struck = hits.subList(0, Math.min(through, hits.size()));
        Vec3 impact = stop;
        boolean floor = wall && block.getDirection() == Direction.UP;
        // A bolt stops in the first body; a charged beam stops in the last one it had strength for,
        // unless it came out the far side of every body in its path.
        if (!struck.isEmpty() && (!charged || hits.size() > through)) {
            impact = struck.get(struck.size() - 1).at;
            wall = false;
            floor = false;
        }
        boolean landed = wall || !struck.isEmpty();

        Set<LivingEntity> burned = new HashSet<>();
        for (Hit hit : struck) {
            strike(caster, hit, direction, power);
            burned.add(hit.victim);
        }
        if (landed) detonate(caster, level, impact, power, burned);

        // Everyone near hears the shot from the stone; the caster already heard it on release.
        float pitch = .94f + caster.getRandom().nextFloat() * .14f;
        if (charged) {
            level.playSound(caster, muzzle.x, muzzle.y, muzzle.z, HexGodOfStories.SCEPTER_BEAM.get(), SoundSource.PLAYERS, 1.6f + .8f * power, 1.08f - .22f * power);
            level.playSound(caster, muzzle.x, muzzle.y, muzzle.z, HexGodOfStories.SCEPTER_SHOT.get(), SoundSource.PLAYERS, 1.2f, .62f);
        } else level.playSound(caster, muzzle.x, muzzle.y, muzzle.z, HexGodOfStories.SCEPTER_SHOT.get(), SoundSource.PLAYERS, 1.1f, pitch);

        CompoundTag state = new CompoundTag();
        state.putString("state", "shot");
        state.putFloat("power", power);
        // No arm clip: in third person the staff stays in its low carry, whatever the caster looks at.
        HexNetwork.tracking(caster, new HexNetwork.Message(HexNetwork.SCEPTER, caster.getId(), state));

        CompoundTag fx = new CompoundTag();
        fx.putString("effect", "scepter_blast");
        fx.putDouble("x", muzzle.x); fx.putDouble("y", muzzle.y); fx.putDouble("z", muzzle.z);
        fx.putDouble("tx", impact.x); fx.putDouble("ty", impact.y); fx.putDouble("tz", impact.z);
        fx.putBoolean("hit", landed);
        fx.putBoolean("floor", floor);
        fx.putFloat("power", power);
        ListTag pierced = new ListTag();
        for (Hit hit : struck) {
            pierced.add(DoubleTag.valueOf(hit.at.x));
            pierced.add(DoubleTag.valueOf(hit.at.y));
            pierced.add(DoubleTag.valueOf(hit.at.z));
        }
        fx.put("through", pierced);
        HexNetwork.near(level, muzzle, 160, new HexNetwork.Message(HexNetwork.FX, caster.getId(), fx));
    }

    /** A body the beam passes through: burned, holed, bleeding, thrown. */
    private static void strike(ServerPlayer caster, Hit hit, Vec3 direction, float power) {
        LivingEntity victim = hit.victim;
        boolean charged = power > 0;
        // Taps come faster than vanilla's hurt immunity; each one still lands.
        victim.invulnerableTime = 0;
        victim.hurt(caster.damageSources().indirectMagic(caster, caster), charged ? 12 + 20 * power : 6.5f);
        victim.level().playSound(null, hit.at.x, hit.at.y, hit.at.z, HexGodOfStories.SCEPTER_BURN.get(), SoundSource.PLAYERS,
            1.2f, .9f + victim.getRandom().nextFloat() * .25f);
        if (victim.isDeadOrDying()) {dissolve(victim, direction, power); return;}
        // A clean hole that stays open for most of a minute before it knits shut. Bleed, never fire.
        BeamWound.open(victim, hit.at, direction, charged ? .20f + .14f * power : .15f, charged ? 1200 : 900);
        Bleed.apply(caster, victim, charged ? 2 + Math.round(3 * power) : 1, charged ? 180 : 110);
        Vec3 push = direction.scale(charged ? 1.1 + 2.6 * power : .5).add(0, charged ? .3 + .4 * power : .16, 0);
        victim.setDeltaMovement(victim.getDeltaMovement().add(push));
        victim.hurtMarked = true;
        if (charged && power >= .5f) stun(victim, Math.round(20 + 40 * power));
    }

    /**
     * A body the beam kills comes apart the way Time Branch Unleashing unmakes one, only far faster: the
     * same fracturing surface, dust and threads, run inside vanilla's twenty-tick death so the last
     * fragment goes as the body does. Bosses keep their own deaths.
     */
    private static void dissolve(LivingEntity victim, Vec3 direction, float power) {
        if (victim.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES)) return;
        CompoundTag n = new CompoundTag();
        n.putDouble("dx", direction.x);
        n.putDouble("dy", direction.y);
        n.putDouble("dz", direction.z);
        n.putInt("duration", victim instanceof net.minecraft.world.entity.player.Player ? 30 : 19);
        n.putFloat("power", .5f + .5f * power);
        n.putLong("start", victim.level().getGameTime());
        n.putBoolean("upright", true);
        HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.ERASURE, victim.getId(), n));
    }

    /** Whatever the beam finally reaches goes up: a burst of heat, never a hole in the terrain. */
    private static void detonate(ServerPlayer caster, ServerLevel level, Vec3 at, float power, Set<LivingEntity> already) {
        boolean charged = power > 0;
        double radius = charged ? 2.2 + 3.3 * power : 1.3;
        float damage = charged ? 4 + 9 * power : 2.5f;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius),
            e -> HexServer.validTarget(caster, e) && !already.contains(e))) {
            double d = victim.getBoundingBox().getCenter().distanceTo(at);
            if (d > radius) continue;
            float falloff = (float) (1 - d / radius);
            victim.hurt(caster.damageSources().indirectMagic(caster, caster), damage * (.35f + .65f * falloff));
            Vec3 away = victim.getBoundingBox().getCenter().subtract(at);
            if (victim.isDeadOrDying()) {dissolve(victim, away.lengthSqr() > 1e-6 ? away.normalize() : new Vec3(0, 1, 0), power); continue;}
            if (away.lengthSqr() > 1e-6) {
                victim.setDeltaMovement(victim.getDeltaMovement().add(away.normalize().scale((charged ? .5 + power : .25) * falloff)).add(0, .2 * falloff, 0));
                victim.hurtMarked = true;
            }
        }
        BlockPos pos = BlockPos.containing(at);
        float pitch = .9f + level.random.nextFloat() * .2f;
        level.playSound(null, pos, HexGodOfStories.SCEPTER_IMPACT.get(), SoundSource.PLAYERS, charged ? 2.2f + power : 1.4f, charged ? pitch - .15f * power : pitch);
        if (charged) {
            level.playSound(null, pos, HexGodOfStories.SCEPTER_BOOM.get(), SoundSource.PLAYERS, 2.0f + 2 * power, .9f - .1f * power);
            if (power >= .6f) level.playSound(null, pos, HexGodOfStories.SCEPTER_BLAST.get(), SoundSource.PLAYERS, 1.8f + power, .85f);
        }
    }

    private static void stun(LivingEntity victim, int ticks) {
        if (STUNS.size() >= MAX_STUNS && !STUNS.containsKey(victim.getUUID())) return;
        Stun old = STUNS.get(victim.getUUID());
        boolean hadNoAi = old != null ? old.hadNoAi : victim instanceof Mob mob && mob.isNoAi();
        long until = Math.max(old == null ? 0 : old.until, victim.level().getGameTime() + ticks);
        STUNS.put(victim.getUUID(), new Stun(victim, until, hadNoAi));
        if (victim instanceof Mob mob) mob.setNoAi(true);
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 255, false, true));
        victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 255, false, true));
        CompoundTag state = new CompoundTag();
        state.putLong("until", until);
        HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.STUN, victim.getId(), state));
    }

    public static boolean stunned(LivingEntity victim) {
        Stun stun = STUNS.get(victim.getUUID());
        return stun != null && stun.victim == victim && victim.level().getGameTime() < stun.until;
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Stun stun : List.copyOf(STUNS.values())) {
            LivingEntity victim = stun.victim;
            if (victim.level() != level) continue;
            if (now >= stun.until || victim.isRemoved() || !victim.isAlive()) {
                STUNS.remove(victim.getUUID(), stun);
                if (victim instanceof Mob mob && !stun.hadNoAi) mob.setNoAi(false);
                CompoundTag state = new CompoundTag();
                state.putLong("until", 0);
                if (!victim.isRemoved()) HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.STUN, victim.getId(), state));
            }
        }
    }

    public static void clear(LivingEntity victim) {
        Stun stun = STUNS.remove(victim.getUUID());
        if (stun != null && victim instanceof Mob mob && !stun.hadNoAi) mob.setNoAi(false);
    }

    public static void track(ServerPlayer viewer, net.minecraft.world.entity.Entity entity) {
        if (entity instanceof LivingEntity living && stunned(living)) {
            CompoundTag state = new CompoundTag();
            state.putLong("until", STUNS.get(living.getUUID()).until);
            HexNetwork.to(viewer, new HexNetwork.Message(HexNetwork.STUN, entity.getId(), state));
        }
        if (entity instanceof LivingEntity living) BeamWound.track(viewer, living);
    }

    public static void reset() {
        for (Stun stun : STUNS.values()) if (stun.victim instanceof Mob mob && !stun.hadNoAi) mob.setNoAi(false);
        STUNS.clear();
        CHARGES.clear();
        READY.clear();
        QUEUED.clear();
        BeamWound.reset();
    }
}
