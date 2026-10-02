package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The burning Deceiver's fire loosed: its use key held (where, unlit, it would raise the guard). The sword arm is thrust
 * out along the look and a jet of fire pours from the point to whatever the look meets, sixteen blocks at most; it
 * burns whatever it passes through and whatever stands where it strikes. Burns, not cuts: fire damage (what cannot
 * burn, or is warded against fire, takes none), and every body set alight. Never more than forty hearts to any one
 * body from one pour, however long it is held on it, and Temporal Energy drawn all the while it pours. A body it kills
 * comes apart the way Time Branch Unleashing unmakes one, only fast (ScepterBlast#dissolve).
 *
 * <p>The server owns who burns and how much. Clients are told only that the bearer is pouring (player data,
 * {@link #POURING}) and draw the jet from the bearer's own look (client FireStream), the same line burned along here.
 */
public final class FlameStream {
    private FlameStream() {}

    /** Synced: the tick the bearer began pouring (0 or absent when not). */
    public static final String POURING = "flameStream";
    /** The jet's reach; how wide it burns about its line (more the further out), and round where it strikes. */
    public static final double RANGE = 16;
    static final double CORE = .45, SPREAD = .05, SPLASH = 1.8;
    /** Each body in it burns this much every {@link #EVERY} ticks, up to {@link #MOST} from one pour (forty hearts). */
    static final float BURN = 2.5f, MOST = 80;
    static final int EVERY = 4, ALIGHT = 60;
    /** Temporal Energy a tick while it pours, and the least it needs to start. */
    static final float DRAIN = 1.2f, START = 5;

    private static final class Pour {
        final long start;
        final Map<UUID, Float> dealt = new HashMap<>();

        Pour(long start) {this.start = start;}
    }

    private static final Map<UUID, Pour> POURS = new HashMap<>();

    public static boolean pouring(ServerPlayer p) {return POURS.containsKey(p.getUUID());}

    /** The use key pressed with the blade burning. @return whether it pours. */
    public static boolean begin(ServerPlayer p) {
        if (pouring(p) || !BladeFire.burning(p) || HexData.energy(p) < START || BladeCombo.running(p) || Evisceration.running(p)) return false;
        long now = HexData.now(p);
        POURS.put(p.getUUID(), new Pour(now));
        HexData.get(p).putLong(POURING, now);
        HexNetwork.animate(p, "blade_sword_flame");
        ServerLevel level = p.serverLevel();
        level.playSound(null, p.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2f, .55f);
        level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1, .5f);
        HexNetwork.sync(p);
        return true;
    }

    /** Let go (or out of fire, or energy): the jet stops at the point. */
    public static void end(ServerPlayer p) {
        if (POURS.remove(p.getUUID()) == null) return;
        HexData.get(p).remove(POURING);
        HexNetwork.animate(p, "__clear__");
        p.serverLevel().playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .5f, 1.4f);
        HexNetwork.sync(p);
    }

    public static void forget(ServerPlayer p) {POURS.remove(p.getUUID()); HexData.get(p).remove(POURING);}
    public static void reset() {POURS.clear();}

    /** Every tick of a bearer's: while it pours, the energy it draws, the roar of it, and everything it burns. */
    public static void tick(ServerPlayer p) {
        Pour pour = POURS.get(p.getUUID());
        if (pour == null) return;
        if (!p.isAlive() || !BladeFire.burning(p) || TemporalEngine.frozen(p) || ScepterBlast.stunned(p) || !HexData.spend(p, DRAIN)) {
            end(p);
            return;
        }
        long now = HexData.now(p), t = now - pour.start;
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = hit.getLocation();
        double length = end.distanceTo(eye);
        boolean struck = hit.getType() == HitResult.Type.BLOCK;
        if (t % 5 == 0) {
            level.playSound(null, BlockPos.containing(eye.add(look.scale(1.5))), SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.4f, .55f + p.getRandom().nextFloat() * .1f);
            if (struck) level.playSound(null, BlockPos.containing(end), SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.6f, .45f);
        }
        if (t % 12 == 0) level.playSound(null, BlockPos.containing(eye.add(look.scale(2))), SoundEvents.BLAZE_BURN, SoundSource.PLAYERS, .9f, .5f);
        if (t % EVERY != 0) return;
        DamageSource fire = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.IN_FIRE), p);
        for (LivingEntity body : level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(SPLASH + 1), e -> HexServer.validTarget(p, e))) {
            Vec3 middle = body.getBoundingBox().getCenter();
            double along = middle.subtract(eye).dot(look), half = body.getBbWidth() / 2;
            if (along < .3) continue;
            Vec3 nearest = eye.add(look.scale(Math.min(along, length)));
            boolean inJet = along <= length + half && middle.distanceTo(nearest) <= CORE + SPREAD * along + half;
            boolean inBloom = struck && middle.distanceTo(end) <= SPLASH + half;
            if (!inJet && !inBloom || !p.hasLineOfSight(body)) continue;
            float dealt = pour.dealt.getOrDefault(body.getUUID(), 0f);
            if (dealt >= MOST) continue;
            float burn = Math.min(BURN, MOST - dealt);
            body.invulnerableTime = 0;
            if (!body.hurt(fire, burn)) continue;
            pour.dealt.put(body.getUUID(), dealt + burn);
            body.setRemainingFireTicks(Math.max(body.getRemainingFireTicks(), ALIGHT));
            if (body.isDeadOrDying()) {
                ScepterBlast.dissolve(body, look, 1);
                level.playSound(null, body.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, .9f, .7f);
            }
        }
        if (t % 20 == 0) HexServer.reward(p, com.hexgodofstories.data.Discipline.CONJURATION, 8);
    }
}
