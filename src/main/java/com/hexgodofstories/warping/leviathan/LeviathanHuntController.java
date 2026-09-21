package com.hexgodofstories.warping.leviathan;

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
    private int scanCooldown;

    private float orbitPhase;
    private int orbitDirection = 1;
    private double orbitRadius = 46, orbitDepth = 52;
    @Nullable private Vec3 roam;
    private int roamTicks;
    /** Held still once chosen; see {@link #ambushPoint()}. */
    @Nullable private Vec3 ambush, observe;
    private Vec3 ambushAnchor = Vec3.ZERO, observeAnchor = Vec3.ZERO;
    /** Fixed per hunt, so the approach does not jitter from tick to tick. */
    private double approachLead = 12;
    /** Set by the AI each tick so a return to the water can be detected as an event. */
    public boolean airborneTargetRecently;

    public LeviathanHuntController(AbyssalPilgrimEntity self) { this.self = self; }

    @Nullable public Entity target() { return target; }
    public Vec3 estimate() { return estimate; }
    public int contactTicks() { return contactTicks; }
    public boolean hasTarget() { return target != null && target.isAlive(); }

    public void forget() { target = null; contactTicks = 0; estimateAge = 400; ambush = observe = null; }

    /** Replaces the fuzzed estimate with the real position. Used only by hard detection events. */
    public void sharpen(Entity entity) {
        if (entity == null) return;
        estimate = entity.position();
        estimateAge = 0;
        contactTicks = Math.max(contactTicks, 40);
    }

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
        ambush = observe = null;
        approachLead = 8 + random.nextDouble() * 10;
        orbitDirection = random.nextBoolean() ? 1 : -1;
        // Never inside the creature's own turning circle. A twenty six block orbit was a request
        // for a body twenty times that long to tie itself in a knot, and it obliged.
        orbitRadius = 46 + random.nextDouble() * 44;
        orbitDepth = 30 + random.nextDouble() * 50;
        orbitPhase = random.nextFloat() * Mth.TWO_PI;
    }

    public void tick(ServerLevel level) {
        if (switchCooldown > 0) switchCooldown--;
        if (target != null && (!target.isAlive() || target.level() != level || target.isSpectator() || (target instanceof Player p && (p.isCreative() || p.isSpectator())))) target = null;

        // Enough is Enough holds the hunt on its subject. Unpredictable reassignment is what keeps
        // a group from learning who is safe, which is a property of playing; once the clock on one
        // of them has run out there is nothing left to be unpredictable about.
        boolean locked = target != null && target.isAlive() && EnoughIsEnough.marked(target.getUUID());
        if (!locked && (target == null || switchCooldown <= 0) && scanCooldown-- <= 0) {
            scanCooldown = 20;
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
        // Radians per tick that hold the tangential speed near a block a tick whatever the radius,
        // so a wide orbit is a patient one rather than an impossible sprint.
        orbitPhase += (float) (orbitDirection * (0.9 + 0.7 * self.frenzy()) / orbitRadius);
    }

    @Nullable
    private Entity choose(ServerLevel level) {
        List<Entity> pool = new ArrayList<>();
        // Scan only loaded entities, once per second at most. No radius cutoff and no chunk loads.
        // Summons and empty boats remain prey even when their owner has left the dimension.
        for (Entity other : level.getAllEntities()) {
            if (valid(other) && (other instanceof LivingEntity
                    || other instanceof net.minecraft.world.entity.vehicle.Boat
                    || other.getDeltaMovement().lengthSqr() > 0.0025)) pool.add(other);
        }
        if (pool.isEmpty()) return null;

        Entity best = null; double bestScore = -1;
        for (Entity candidate : pool) {
            double distance = Math.sqrt(self.distanceToSqr(candidate));
            double score = 1200.0 / (60.0 + distance);
            if (EnoughIsEnough.marked(candidate.getUUID())) score *= 5.0;  // it has already decided about this one
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
        return new Vec3(point.x, y, point.z);
    }

    /** Held below and behind, on a slow orbit, at a depth that keeps the body invisible. */
    public Vec3 stalkPoint() { return stalkPoint(0); }

    /**
     * The same orbit, closing.
     *
     * <p>{@code tighten} runs from nought at the start of a hunt to one once the creature has been
     * circling for a while without landing anything, and draws the ring in and up as it goes. A
     * circle held at a constant radius forever is a creature that has decided not to attack; a
     * spiral is one that has decided when. It never closes past the widest radius the spine can
     * actually hold — inside that the body folds through itself — because the commit is supposed
     * to be a straight run at the prey, not a tighter lap around it.
     */
    public Vec3 stalkPoint(double tighten) {
        Vec3 anchor = estimate;
        double t = Mth.clamp(tighten, 0, 1);
        double radius = Mth.lerp(t, orbitRadius, 46.0);
        double depth = Mth.lerp(t, orbitDepth, 16.0);
        Vec3 point = anchor.add(Math.cos(orbitPhase) * radius, -depth, Math.sin(orbitPhase) * radius);
        return clampToWater(point);
    }

    /** Directly underneath, which is where a boat never looks. */
    public Vec3 underneathPoint(double below) {
        return clampToWater(estimate.add(0, -below, 0));
    }

    /**
     * Far out, deep, and outside the victim's forward arc, ready to accelerate.
     *
     * <p>Chosen once and then held until the prey has moved a long way. It used to be rolled fresh
     * on every tick, so the creature was steering at a station that jumped tens of blocks twenty
     * times a second: it never arrived anywhere, the "am I in position" test that gates the strike
     * was a coin flip, and the whole ambush degenerated into wandering with a name.
     */
    public Vec3 ambushPoint() {
        Vec3 anchor = estimate;
        if (ambush == null || anchor.distanceToSqr(ambushAnchor) > 45 * 45) {
            float facing = target instanceof LivingEntity living ? living.getYRot() : 0;
            // Sit behind the victim's heading, with a random side bias.
            double angle = (facing + 180 + (self.getRandom().nextDouble() - 0.5) * 110) * Mth.DEG_TO_RAD;
            double radius = 55 + self.getRandom().nextDouble() * 60;
            ambushAnchor = anchor;
            ambush = clampToWater(anchor.add(-Math.sin(angle) * radius,
                -(60 + self.getRandom().nextDouble() * 60), Math.cos(angle) * radius));
        }
        return ambush;
    }

    /** Approach vector that arrives from behind and below rather than head on. */
    public Vec3 approachPoint() { return approachPoint(0); }

    /**
     * The approach, becoming less polite as the hunt wears on.
     *
     * <p>At rest it is the old one: eight to eighteen blocks behind the victim's heading and well
     * under them, so the creature arrives out of the blind arc. That is a lovely approach and a
     * hopeless attack, because a point the body arrives at is a point inside its own turning
     * circle, where the steering stops converging and carves past instead — which is precisely
     * what the endless orbiting looked like. As {@code commitment} rises the offsets collapse and
     * the aim point is pushed out past the prey, so the same approach turns into a run through.
     */
    public Vec3 approachPoint(double commitment) {
        if (target == null) return estimate;
        double c = Mth.clamp(commitment, 0, 1);
        Vec3 anchor = target.position();
        Vec3 back = target instanceof LivingEntity living ? living.getLookAngle().reverse() : Vec3.ZERO;
        double lead = approachLead * (1 - c);
        double below = Math.max(6, orbitDepth * 0.3) * (1 - 0.8 * c);
        Vec3 point = anchor.add(back.x * lead, -below, back.z * lead);
        if (c > 0.12) {
            Vec3 run = point.subtract(self.position());
            if (run.lengthSqr() > 1.0E-6) point = point.add(run.normalize().scale(26 + 44 * c));
        }
        return clampToWater(point);
    }

    /** Surfaces at long range so the player sees something and cannot tell what. */
    public Vec3 observePoint() {
        Vec3 anchor = estimate;
        // Held, for the same reason the ambush station is: a watcher that re-picks where it is
        // watching from every tick does not read as watching.
        if (observe == null || anchor.distanceToSqr(observeAnchor) > 60 * 60) {
            double radius = 90 + self.getRandom().nextDouble() * 70;
            double angle = self.getRandom().nextDouble() * Mth.TWO_PI;
            observeAnchor = anchor;
            observe = new Vec3(anchor.x + Math.cos(angle) * radius, waterline() - 2.5, anchor.z + Math.sin(angle) * radius);
        }
        return new Vec3(observe.x, waterline() - 2.5, observe.z);
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

    /** Blocks the victim is currently holding above the waterline. Zero when it is in the sea. */
    public double airborneHeight() {
        return target == null ? 0 : Math.max(0, target.getY() - waterline());
    }

    /**
     * Whether a leap could plausibly meet the target: off the water, inside a reachable arc, and
     * not so high that the jump would be a gesture at the sky.
     */
    public boolean leapable(double maxHeight, double maxReach) {
        if (!airborneTarget()) return false;
        if (airborneHeight() > maxHeight) return false;
        double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
        return dx * dx + dz * dz < maxReach * maxReach;
    }

    public double targetDistance() { return target == null ? Double.MAX_VALUE : Math.sqrt(self.distanceToSqr(target)); }
}
