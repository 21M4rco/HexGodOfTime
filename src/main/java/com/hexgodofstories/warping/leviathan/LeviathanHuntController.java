package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.warping.VoidSea;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Target selection and hunting geometry.
 *
 * <p>The creature is told that prey exists anywhere in its dimension, but it is never handed an
 * exact position. It carries a fuzzed estimate whose error shrinks as it closes and grows again
 * whenever it loses contact, so long range movement reads as searching rather than homing. All of
 * the approach geometry deliberately favours below, behind and outside the victim's view cone.
 */
public final class LeviathanHuntController {
    private final AbyssalPilgrimEntity self;

    @Nullable private Entity target;
    private Vec3 estimate = Vec3.ZERO;
    private int estimateAge;
    private int switchCooldown;
    private int contactTicks;

    private float orbitPhase;
    private int orbitDirection = 1;
    private double orbitRadius = 46, orbitDepth = 52;
    @Nullable private Vec3 roam;
    private int roamTicks;
    /** Set by the AI each tick so a return to the water can be detected as an event. */
    public boolean airborneTargetRecently;

    public LeviathanHuntController(AbyssalPilgrimEntity self) { this.self = self; }

    @Nullable public Entity target() { return target; }
    public Vec3 estimate() { return estimate; }
    public int contactTicks() { return contactTicks; }
    public boolean hasTarget() { return target != null && target.isAlive(); }

    public void forget() { target = null; contactTicks = 0; estimateAge = 400; }

    public void focus(@Nullable Entity entity) {
        if (entity == null || entity == target) return;
        target = entity;
        estimate = entity.position();
        estimateAge = 0;
        switchCooldown = 120 + self.getRandom().nextInt(260);
        pickOrbit();
    }

    private void pickOrbit() {
        RandomSource random = self.getRandom();
        orbitDirection = random.nextBoolean() ? 1 : -1;
        orbitRadius = 26 + random.nextDouble() * 44;
        orbitDepth = 30 + random.nextDouble() * 50;
        orbitPhase = random.nextFloat() * Mth.TWO_PI;
    }

    public void tick(ServerLevel level) {
        if (switchCooldown > 0) switchCooldown--;
        if (target != null && (!target.isAlive() || target.level() != level || target.isSpectator() || (target instanceof Player p && (p.isCreative() || p.isSpectator())))) target = null;

        if (target == null || switchCooldown <= 0) {
            Entity candidate = choose(level);
            if (candidate != null && candidate != target) {
                // Unpredictable reassignment: keeps groups from learning who is safe.
                boolean swap = target == null || self.getRandom().nextFloat() < 0.35f
                    || self.distanceToSqr(candidate) < self.distanceToSqr(target) * 0.4;
                if (swap) focus(candidate);
                else switchCooldown = 140;
            }
        }
        if (target == null) { estimateAge++; return; }

        double distance = Math.sqrt(self.distanceToSqr(target));
        boolean close = distance < 120;
        if (close) contactTicks++; else contactTicks = Math.max(0, contactTicks - 1);

        // Knowledge sharpens with proximity and decays when contact is lost.
        int refresh = close ? 5 : distance < 400 ? 25 : 70;
        estimateAge++;
        if (estimateAge >= refresh) {
            estimateAge = 0;
            double error = Mth.clamp(distance * 0.16, 1.5, 70.0);
            RandomSource random = self.getRandom();
            estimate = target.position().add(
                (random.nextDouble() - 0.5) * error,
                (random.nextDouble() - 0.5) * error * 0.35,
                (random.nextDouble() - 0.5) * error);
        }
        orbitPhase += orbitDirection * (0.012f + 0.02f * self.frenzy());
    }

    @Nullable
    private Entity choose(ServerLevel level) {
        List<Entity> pool = new ArrayList<>();
        for (ServerPlayer player : level.players()) if (valid(player)) pool.add(player);
        // Anything else that wandered in is prey too, but is only looked for nearby.
        if (pool.isEmpty() || self.getRandom().nextFloat() < 0.08f) {
            AABB nearby = self.getBoundingBox().inflate(160);
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, nearby, this::valid)) pool.add(other);
        }
        if (pool.isEmpty()) return null;

        Entity best = null; double bestScore = -1;
        for (Entity candidate : pool) {
            double distance = Math.sqrt(self.distanceToSqr(candidate));
            double score = 1200.0 / (60.0 + distance);
            if (candidate instanceof Player) score *= 3.4;                 // intelligent prey is the point
            if (candidate.isInWater()) score *= 1.55;                      // back in the water means back on the menu
            if (candidate.getDeltaMovement().lengthSqr() > 0.09) score *= 1.2;
            if (candidate == target) score *= 1.35;
            score *= 0.75 + self.getRandom().nextDouble() * 0.5;
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    private boolean valid(Entity entity) {
        if (entity == null || !entity.isAlive() || entity == self || entity.isSpectator()) return false;
        if (entity instanceof LeviathanMultipartHitbox || entity instanceof AbyssalPilgrimEntity) return false;
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        return true;
    }

    // ---------------------------------------------------------------- geometry

    private double waterline() { return self.surfaceY(); }
    private double floor() { return self.floorY(); }

    private Vec3 clampToWater(Vec3 point) {
        double y = Mth.clamp(point.y, floor() + 8, waterline() - 4);
        return new Vec3(VoidSea.clampX(self.cell(), point.x), y, VoidSea.clampZ(point.z));
    }

    /** Held below and behind, on a slow orbit, at a depth that keeps the body invisible. */
    public Vec3 stalkPoint() {
        Vec3 anchor = estimate;
        double radius = orbitRadius;
        Vec3 point = anchor.add(Math.cos(orbitPhase) * radius, -orbitDepth, Math.sin(orbitPhase) * radius);
        return clampToWater(point);
    }

    /** Directly underneath, which is where a boat never looks. */
    public Vec3 underneathPoint(double below) {
        return clampToWater(estimate.add(0, -below, 0));
    }

    /** Far out, deep, and outside the victim's forward arc, ready to accelerate. */
    public Vec3 ambushPoint() {
        Vec3 anchor = estimate;
        float facing = target instanceof LivingEntity living ? living.getYRot() : 0;
        // Sit behind the victim's heading, with a random side bias.
        double angle = (facing + 180 + (self.getRandom().nextDouble() - 0.5) * 110) * Mth.DEG_TO_RAD;
        double radius = 55 + self.getRandom().nextDouble() * 60;
        Vec3 point = anchor.add(-Math.sin(angle) * radius, -(60 + self.getRandom().nextDouble() * 60), Math.cos(angle) * radius);
        return clampToWater(point);
    }

    /** Approach vector that arrives from behind and below rather than head on. */
    public Vec3 approachPoint() {
        if (target == null) return estimate;
        Vec3 anchor = target.position();
        Vec3 back = target instanceof LivingEntity living ? living.getLookAngle().reverse() : Vec3.ZERO;
        double lead = 8 + self.getRandom().nextDouble() * 10;
        Vec3 point = anchor.add(back.x * lead, -Math.max(6, orbitDepth * 0.3), back.z * lead);
        return clampToWater(point);
    }

    /** Surfaces at long range so the player sees something and cannot tell what. */
    public Vec3 observePoint() {
        Vec3 anchor = estimate;
        double radius = 90 + self.getRandom().nextDouble() * 70;
        double angle = self.getRandom().nextDouble() * Mth.TWO_PI;
        return new Vec3(VoidSea.clampX(self.cell(), anchor.x + Math.cos(angle) * radius), waterline() - 2.5,
            VoidSea.clampZ(anchor.z + Math.sin(angle) * radius));
    }

    /** Wide, aimless sweeps when nothing is being hunted. */
    public Vec3 roamPoint(ServerLevel level) {
        if (roam == null || roamTicks-- <= 0 || self.position().distanceToSqr(roam) < 400) {
            RandomSource random = self.getRandom();
            double angle = random.nextDouble() * Mth.TWO_PI;
            double radius = 180 + random.nextDouble() * 420;
            roam = clampToWater(self.position().add(Math.cos(angle) * radius, (random.nextDouble() - 0.55) * 110, Math.sin(angle) * radius));
            roamTicks = 200 + random.nextInt(420);
        }
        return roam;
    }

    /** True when the creature is currently inside the victim's forward arc at a readable range. */
    public boolean insideViewOf(Entity viewer) {
        if (!(viewer instanceof LivingEntity living)) return false;
        Vec3 toward = self.segments().segment(0).subtract(living.getEyePosition());
        double distance = toward.length();
        if (distance > 110) return false;
        if (distance < 1.0E-3) return true;
        return living.getLookAngle().dot(toward.scale(1 / distance)) > 0.35;
    }

    /** True when the victim has left the water and is high enough that only a breach reaches them. */
    public boolean airborneTarget() {
        return target != null && !target.isInWater() && target.getY() > waterline() + 1.2;
    }

    public double targetDistance() { return target == null ? Double.MAX_VALUE : Math.sqrt(self.distanceToSqr(target)); }
}
