package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.VoidSea;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Executes one attack pattern at a time through a windup, active and recover clock.
 *
 * <p>Damage is never applied from a single global box. Each pattern nominates the body section that
 * the player can actually see performing it, and contact is tested against that section's multipart
 * hitboxes. A tail sweep can only hurt you with the tail.
 */
public final class LeviathanCombatController {
    private final AbyssalPilgrimEntity self;

    @Nullable private LeviathanAttack attack;
    @Nullable private Entity victim;
    private int tick;
    private int cooldown;
    private Vec3 anchor = Vec3.ZERO;
    private double orbit;
    private int orbitSign = 1;
    private boolean crossedSurface, splashed, escapeWindow;
    private final Set<UUID> struck = new HashSet<>();
    /** How far below the waterline a dragged victim is ever taken. */
    private static final double DRAG_LIMIT = 150.0;
    /** Ticks of borrowed breath given to anything the creature intends to keep playing with. */
    private static final int LUNG = 700;

    public LeviathanCombatController(AbyssalPilgrimEntity self) { this.self = self; }

    public boolean active() { return attack != null; }
    @Nullable public LeviathanAttack current() { return attack; }
    @Nullable public Entity victim() { return victim; }
    public boolean ready() { return cooldown <= 0; }
    public void cool(int ticks) { cooldown = Math.max(cooldown, ticks); }

    public void begin(LeviathanAttack pattern, @Nullable Entity target) {
        attack = pattern;
        victim = target;
        tick = 0;
        struck.clear();
        crossedSurface = splashed = escapeWindow = false;
        anchor = target != null ? target.position() : self.position();
        orbit = self.getRandom().nextDouble() * Mth.TWO_PI;
        orbitSign = self.getRandom().nextBoolean() ? 1 : -1;
        self.setAttack(pattern);
        self.setAttackTick(0);
        self.control().setAllowAir(pattern.aerial());
        announce(pattern);
    }

    public void abort() {
        if (attack != null) cooldown = 20 + self.getRandom().nextInt(40);
        attack = null; victim = null; tick = 0;
        self.setAttack(null);
        self.setAttackTick(0);
        self.control().setAllowAir(false);
        releaseHold(Vec3.ZERO, false);
    }

    public void tick() {
        if (cooldown > 0) cooldown--;
        if (attack == null) return;
        if (victim != null && (!victim.isAlive() || victim.level() != self.level())) victim = null;

        self.setAttackTick(tick);
        carryHeld();

        switch (attack) {
            case PREDATORY_BITE -> bite();
            case ABYSSAL_LUNGE -> lunge();
            case TAIL_SWEEP -> tailSweep();
            case TENDRIL_GRAB -> tendrilGrab();
            case DRAG_BELOW -> dragBelow();
            case BODY_CRUSH -> bodyCrush();
            case VOID_SCREAM -> voidScream();
            case DEEP_CHARGE -> deepCharge();
            case SURFACE_RAM -> surfaceRam();
            case BREACH_BITE -> breachBite();
            case AIR_THROW -> airThrow();
            case FAKE_ATTACK -> fakeAttack();
            case WATER_VORTEX -> vortex();
        }

        // A pattern may abort when its prey disappears or a grab misses.
        if (attack == null) return;
        tick++;
        if (tick >= attack.total()) {
            LeviathanAttack finished = attack;
            attack = null;
            self.setAttack(null);
            self.control().setAllowAir(false);
            cooldown = (finished.lethal() ? 30 : 12) + self.getRandom().nextInt(finished == LeviathanAttack.VOID_SCREAM ? 200 : 70);
            cooldown = (int) (cooldown * (1.0 - 0.45 * self.frenzy()));
        }
    }

    // ---------------------------------------------------------------- patterns

    private void bite() {
        if (victim == null) { abort(); return; }
        if (tick < attack.windup) {
            steer(victim.position(), 0.9, 0.55f);
        } else if (tick < attack.windup + attack.active) {
            steer(victim.position(), 1.9, 0.95f);
            self.control().addBurst(0.8);
            if (tick == attack.windup + 1) self.voice(HexGodOfStories.PILGRIM_BITE.get(), 22f, 0.85f);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 2.2)) {
                damage(hit, 14f, 0.55);
                if (hit == victim && attack.canHold && self.getRandom().nextFloat() < 0.55f) takeHold(hit);
            }
        } else if (self.held() != null) {
            // Held in the jaws: shaken rather than killed, if patience allows it.
            shakeVictim(0.9f);
            if (tick % 9 == 0 && self.held() instanceof LivingEntity living) damage(living, 2.5f, 0);
        }
    }

    private void lunge() {
        if (victim == null) { abort(); return; }
        if (tick == 0) anchor = victim.position();
        if (tick < attack.windup) {
            // Straighten out and line the body up. The fins fold, the glow runs forward.
            Vec3 line = anchor.subtract(self.position());
            steer(self.position().add(line.normalize().scale(20)), 0.6, 0.8f);
            if (tick == 4) self.voice(HexGodOfStories.PILGRIM_LUNGE.get(), 28f, 1.0f);
        } else if (tick < attack.windup + attack.active) {
            Vec3 through = anchor.add(anchor.subtract(self.position()).normalize().scale(70));
            steer(through, 3.1, 0.30f);
            self.control().addBurst(1.6);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 2.6)) damage(hit, 16f, 1.6);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.NECK, 1.6)) damage(hit, 9f, 1.2);
        } else {
            steer(self.position().add(self.getLookAngle().scale(30)), 0.8, 0.18f);
        }
    }

    private void tailSweep() {
        if (victim == null) { abort(); return; }
        // Curve hard past the victim so the whip comes from the creature's own momentum.
        Vec3 side = new Vec3(-Math.sin(orbit) * orbitSign, 0, Math.cos(orbit) * orbitSign).scale(26);
        if (tick < attack.windup) {
            steer(victim.position().add(side), 1.5, 0.95f);
        } else if (tick < attack.windup + attack.active) {
            steer(victim.position().add(side.reverse()).add(0, 4, 0), 2.0, 1.0f);
            if (tick == attack.windup) self.voice(HexGodOfStories.PILGRIM_TAIL_SWEEP.get(), 26f, 0.9f);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.TAIL, 3.0)) {
                damage(hit, 10f, 2.4);
                hit.setDeltaMovement(hit.getDeltaMovement().add(side.normalize().scale(1.8)).add(0, 0.55, 0));
                hit.hurtMarked = true;
            }
        } else {
            steer(victim.position().add(side.scale(1.6)), 0.9, 0.3f);
        }
    }

    private void tendrilGrab() {
        if (victim == null) { abort(); return; }
        Vec3 mouth = mouth();
        if (tick < attack.windup) {
            steer(victim.position(), 1.2, 0.7f);
        } else if (tick < attack.windup + attack.active) {
            if (tick == attack.windup) self.voice(HexGodOfStories.PILGRIM_GRAB.get(), 24f, 1.0f);
            steer(victim.position(), 1.1, 0.8f);
            double reach = attack.range;
            for (LivingEntity near : around(mouth, reach)) {
                Vec3 pull = mouth.subtract(near.position());
                double d = pull.length();
                if (d < 2.5) { if (self.held() == null) takeHold(near); continue; }
                near.setDeltaMovement(near.getDeltaMovement().scale(0.5).add(pull.scale(0.9 / d)));
                near.hurtMarked = true;
                near.fallDistance = 0;
            }
        }
    }

    private void dragBelow() {
        if (tick < attack.windup) {
            if (victim == null) { abort(); return; }
            steer(victim.position(), 1.6, 0.9f);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 2.5)) { takeHold(hit); break; }
            if (self.held() == null && tick == attack.windup - 1) { abort(); return; }
        } else if (tick < attack.windup + attack.active) {
            if (self.held() == null) { abort(); return; }
            // Straight down, hard. Depth itself is the weapon.
            // Bounded on purpose. Releasing a swimmer nine hundred blocks down is not toying, it
            // is a drowning, and the whole point of this pattern is that they survive to be hunted.
            double bottom = Math.max(self.floorY() + 12, self.surfaceY() - DRAG_LIMIT);
            steer(new Vec3(self.getX(), bottom, self.getZ()), 2.0, 0.65f);
            self.control().addBurst(0.5);
            if (tick % 20 == 0 && self.held() instanceof LivingEntity living && self.depth() > 90) damage(living, 3f, 0);
        } else if (tick == attack.windup + attack.active) {
            // The release is the cruelty: alive, very deep, and alone.
            releaseHold(new Vec3(0, 0.15, 0), true);
            self.voice(HexGodOfStories.PILGRIM_CLICKING.get(), 20f, 0.8f);
        } else if (victim != null) {
            steer(victim.position().add(0, -26, 0), 0.8, 0.35f);
        }
    }

    private void bodyCrush() {
        if (victim == null) { abort(); return; }
        double progress = Mth.clamp(tick / (double) (attack.windup + attack.active), 0, 1);
        double radius = Mth.lerp(progress, 20.0, 4.5);
        orbit += orbitSign * (0.10 + 0.07 * progress);
        Vec3 ring = victim.position().add(Math.cos(orbit) * radius, Math.sin(orbit * 0.7) * radius * 0.45, Math.sin(orbit) * radius);
        steer(ring, 1.5 + progress * 1.4, 1.0f);

        if (tick >= attack.windup) {
            // One short, obvious chance to get out before it closes.
            escapeWindow = progress > 0.62 && progress < 0.74;
            if (!escapeWindow) {
                for (LivingEntity near : around(victim.position(), radius + 6)) {
                    Vec3 pull = victim.position().subtract(near.position());
                    if (pull.lengthSqr() > 1.0E-4) { near.setDeltaMovement(near.getDeltaMovement().scale(0.72).add(pull.normalize().scale(0.18))); near.hurtMarked = true; }
                }
            }
            if (tick == attack.windup + attack.active - 1) {
                for (LivingEntity hit : around(victim.position(), 7)) damage(hit, 18f, 0.4);
                for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.BODY, 1.5)) damage(hit, 10f, 0.6);
                pulse(victim.position(), 14, 1.1f, "crush");
            }
        }
    }

    private void voidScream() {
        if (tick < attack.windup) {
            steer(self.position().add(self.getLookAngle().scale(6)), 0.25, 0.2f);
            self.setGlow(Mth.lerp(tick / (float) attack.windup, self.glow(), 1f));
            if (tick == 0) self.voice(HexGodOfStories.PILGRIM_VOID_SCREAM.get(), 96f, 0.75f);
        } else if (tick == attack.windup) {
            Vec3 origin = mouth();
            pulse(origin, 44, 1.6f, "void_scream");
            for (LivingEntity hit : around(origin, 44)) {
                Vec3 away = hit.position().subtract(origin);
                double d = Math.max(1.0, away.length());
                double force = 2.6 * (1.0 - d / 46.0);
                hit.setDeltaMovement(hit.getDeltaMovement().add(away.scale(force / d)).add(0, 0.3, 0));
                hit.hurtMarked = true;
                damage(hit, 6f, 0);
                hit.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, false, false));
                hit.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 2, false, false));
            }
            for (Boat boat : self.level().getEntitiesOfClass(Boat.class, new AABB(origin, origin).inflate(44))) {
                Vec3 away = boat.position().subtract(origin);
                double d = Math.max(1.0, away.length());
                boat.setDeltaMovement(boat.getDeltaMovement().add(away.scale(2.2 / d)).add(0, 0.8, 0));
                boat.hurtMarked = true;
            }
        } else {
            steer(self.position().add(self.getLookAngle().scale(18)), 0.6, 0.3f);
        }
    }

    private void deepCharge() {
        if (victim == null) { abort(); return; }
        if (tick < attack.windup) {
            steer(new Vec3(victim.getX(), Math.max(self.floorY() + 14, victim.getY() - 95), victim.getZ()), 1.6, 0.6f);
            if (tick == 0) self.voice(HexGodOfStories.PILGRIM_BREACH_CHARGE.get(), 64f, 0.8f);
        } else if (tick < attack.windup + attack.active) {
            steer(new Vec3(victim.getX(), victim.getY() + 6, victim.getZ()), 3.0, 0.5f);
            self.control().addBurst(1.4);
            self.setGlow(1f);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 2.6)) {
                damage(hit, 15f, 1.4);
                if (self.getRandom().nextFloat() < 0.4f) takeHold(hit);
            }
        } else {
            steer(self.position().add(self.getLookAngle().scale(24)), 1.0, 0.25f);
        }
    }

    private void surfaceRam() {
        if (victim == null) { abort(); return; }
        double line = self.surfaceY();
        if (tick < attack.windup) {
            steer(new Vec3(victim.getX(), line - 26, victim.getZ()), 1.7, 0.7f);
        } else if (tick < attack.windup + attack.active) {
            steer(new Vec3(victim.getX(), line + 3, victim.getZ()), 2.6, 0.7f);
            self.control().addBurst(1.2);
            if (!splashed && self.getY() > line - 4) { splashed = true; splash(new Vec3(self.getX(), line, self.getZ()), 1.3f); }
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.NECK, 2.5)) { damage(hit, 9f, 1.0); hit.setDeltaMovement(hit.getDeltaMovement().add(0, 1.35, 0)); hit.hurtMarked = true; }
            for (Boat boat : self.level().getEntitiesOfClass(Boat.class, self.segments().box(0).inflate(9))) {
                boat.setDeltaMovement(boat.getDeltaMovement().add((self.getRandom().nextDouble() - 0.5) * 0.7, 1.5, (self.getRandom().nextDouble() - 0.5) * 0.7));
                boat.hurtMarked = true;
            }
        } else {
            steer(new Vec3(self.getX(), line - 30, self.getZ()), 1.0, 0.3f);
        }
    }

    private void breachBite() {
        double line = self.surfaceY();
        Vec3 aim = victim != null ? victim.position() : self.position().add(0, 40, 0);
        if (tick < attack.windup) {
            // Retreat, straighten, and light up. This is the tell the player is given.
            double dive = Math.max(self.floorY() + 16, line - 110);
            steer(new Vec3(aim.x, dive, aim.z), 1.8, 0.55f);
            self.setGlow(Mth.clamp(tick / (float) attack.windup, 0.2f, 1f));
            if (tick == 0) self.voice(HexGodOfStories.PILGRIM_BREACH_CHARGE.get(), 110f, 0.7f);
        } else if (tick < attack.windup + attack.active) {
            self.setGlow(1f);
            steer(new Vec3(aim.x, aim.y + 22, aim.z), 3.4, 0.45f);
            self.control().addBurst(2.2);
            if (!crossedSurface && self.getY() >= line - 1) {
                crossedSurface = true;
                splash(new Vec3(self.getX(), line, self.getZ()), 2.0f);
                self.voice(HexGodOfStories.PILGRIM_BREACH.get(), 128f, 0.85f);
            }
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 3.0)) {
                damage(hit, 20f, 1.0);
                if (self.held() == null) takeHold(hit);
            }
            // Coming back down through the waterline.
            if (crossedSurface && self.getY() < line - 1 && !splashed) {
                splashed = true;
                impact(new Vec3(self.getX(), line, self.getZ()));
            }
        } else {
            if (crossedSurface && !splashed && self.getY() < line) { splashed = true; impact(new Vec3(self.getX(), line, self.getZ())); }
            steer(new Vec3(self.getX(), line - 40, self.getZ()), 1.2, 0.3f);
            if (tick == attack.total() - 12 && self.held() != null && self.getRandom().nextFloat() < 0.5f) releaseHold(new Vec3(0, 0.2, 0), true);
        }
    }

    private void airThrow() {
        Entity held = self.held();
        if (tick < attack.windup) {
            if (held == null) { abort(); return; }
            steer(self.position().add(0, 10, 0), 1.0, 0.7f);
        } else if (tick == attack.windup) {
            if (held == null) { abort(); return; }
            Vec3 fling = new Vec3((self.getRandom().nextDouble() - 0.5) * 1.6, 2.35, (self.getRandom().nextDouble() - 0.5) * 1.6);
            releaseHold(fling, false);
            self.voice(HexGodOfStories.PILGRIM_THROW.get(), 34f, 0.95f);
            anchor = self.position();
        } else if (victim != null) {
            // Try to be underneath them when they come down.
            steer(new Vec3(victim.getX(), Math.min(victim.getY(), self.surfaceY()) - 6, victim.getZ()), 2.4, 0.8f);
            for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.HEAD, 2.6)) damage(hit, 12f, 0.8);
        }
    }

    private void fakeAttack() {
        if (victim == null) { abort(); return; }
        if (tick < attack.windup) {
            // Deliberately identical to a real lunge for as long as possible.
            steer(victim.position(), 1.8, 0.85f);
            if (tick == 4) self.voice(HexGodOfStories.PILGRIM_LUNGE.get(), 26f, 1.05f);
        } else {
            Vec3 aside = victim.position().add((self.getRandom().nextBoolean() ? 22 : -22), -18, (self.getRandom().nextBoolean() ? 22 : -22));
            steer(aside, 2.4, 0.95f);
        }
    }

    private void vortex() {
        if (victim == null) { abort(); return; }
        double radius = 22;
        orbit += orbitSign * 0.17;
        Vec3 ring = anchor.add(Math.cos(orbit) * radius, -6 + Math.sin(orbit * 0.5) * 5, Math.sin(orbit) * radius);
        steer(ring, 2.6, 1.0f);
        if (tick >= attack.windup && tick < attack.windup + attack.active) {
            for (LivingEntity near : around(anchor, 32)) {
                Vec3 inward = anchor.subtract(near.position());
                double d = Math.max(0.8, inward.length());
                Vec3 swirl = new Vec3(-inward.z, 0, inward.x).scale(orbitSign / d);
                near.setDeltaMovement(near.getDeltaMovement().scale(0.86).add(inward.scale(0.10 / d)).add(swirl.scale(0.16)));
                near.hurtMarked = true;
            }
            for (Boat boat : self.level().getEntitiesOfClass(Boat.class, new AABB(anchor, anchor).inflate(32))) {
                Vec3 inward = anchor.subtract(boat.position());
                double d = Math.max(0.8, inward.length());
                boat.setDeltaMovement(boat.getDeltaMovement().scale(0.9).add(inward.scale(0.09 / d)));
                boat.hurtMarked = true;
            }
            if (tick % 30 == 0) pulse(anchor, 24, 0.5f, "vortex");
        }
    }

    // ---------------------------------------------------------------- shared mechanics

    private void steer(Vec3 point, double speed, float authority) {
        self.control().moveTo(point, speed, authority);
    }

    public Vec3 mouth() {
        return self.segments().segment(0).add(self.getLookAngle().scale(LeviathanSegmentController.radius(0) * 1.15));
    }

    private List<LivingEntity> contacts(LeviathanMultipartHitbox.Section section, double inflate) {
        List<LivingEntity> found = new ArrayList<>();
        LeviathanMultipartHitbox[] boxes = self.hitboxes();
        for (LeviathanMultipartHitbox box : boxes) {
            if (box.section() != section) continue;
            for (LivingEntity candidate : self.level().getEntitiesOfClass(LivingEntity.class, box.getBoundingBox().inflate(inflate), this::prey)) {
                if (!found.contains(candidate)) found.add(candidate);
            }
        }
        return found;
    }

    private List<LivingEntity> around(Vec3 centre, double radius) {
        return self.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius), e -> prey(e) && e.position().distanceToSqr(centre) <= radius * radius);
    }

    private boolean prey(LivingEntity entity) {
        if (entity == self || entity instanceof AbyssalPilgrimEntity) return false;
        if (!entity.isAlive() || entity.isSpectator()) return false;
        return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    private void damage(LivingEntity entity, float amount, double knockback) {
        if (!struck.add(entity.getUUID())) return;
        entity.hurt(self.damageSources().mobAttack(self), amount);
        if (knockback > 0) {
            Vec3 away = entity.position().subtract(self.segments().segment(0));
            if (away.lengthSqr() < 1.0E-4) away = new Vec3(0, 1, 0);
            entity.setDeltaMovement(entity.getDeltaMovement().add(away.normalize().scale(knockback)).add(0, knockback * 0.35, 0));
            entity.hurtMarked = true;
        }
        if (entity instanceof ServerPlayer player) player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
    }

    private void takeHold(LivingEntity entity) {
        if (self.held() != null || entity instanceof Player player && player.isCreative()) return;
        self.setHeldId(entity.getId());
        // It keeps what it is playing with alive. Drowning would rob it of the rest of the hunt.
        entity.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, LUNG, 0, false, false, false));
        self.voice(HexGodOfStories.PILGRIM_GRAB.get(), 26f, 0.9f);
    }

    private void carryHeld() {
        Entity held = self.held();
        if (held == null) return;
        if (!held.isAlive() || held.level() != self.level() || held.isSpectator()) { self.setHeldId(-1); return; }
        Vec3 seat = mouth().add(0, -held.getBbHeight() * 0.4, 0);
        held.setDeltaMovement(Vec3.ZERO);
        held.setPos(seat.x, seat.y, seat.z);
        held.fallDistance = 0;
        held.hurtMarked = true;
        if (held instanceof ServerPlayer player) player.connection.teleport(seat.x, seat.y, seat.z, player.getYRot(), player.getXRot());
    }

    private void shakeVictim(float strength) {
        Entity held = self.held();
        if (held == null) return;
        RandomSource random = self.getRandom();
        Vec3 jitter = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(0.5 * strength);
        Vec3 seat = mouth().add(jitter);
        held.setPos(seat.x, seat.y, seat.z);
        if (held instanceof ServerPlayer player) {
            player.connection.teleport(seat.x, seat.y, seat.z, player.getYRot() + (float) jitter.x * 12f, player.getXRot());
            HexNetwork.pilgrimEffect(self, "shake", seat, 0.55f * strength);
        }
    }

    /** Lets go on purpose. The impulse decides whether this reads as a throw or a discard. */
    public void releaseHold(Vec3 impulse, boolean gentle) {
        Entity held = self.held();
        self.setHeldId(-1);
        if (held == null) return;
        held.setDeltaMovement(impulse);
        held.hurtMarked = true;
        held.fallDistance = 0;
        if (held instanceof LivingEntity living) living.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, LUNG, 0, false, false, false));
        if (!gentle && held instanceof ServerPlayer player) player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
    }

    // ---------------------------------------------------------------- presentation

    private void announce(LeviathanAttack pattern) {
        if (pattern == LeviathanAttack.VOID_SCREAM || pattern == LeviathanAttack.BREACH_BITE) HexNetwork.pilgrimEffect(self, "charge", self.segments().segment(0), 1f);
    }

    private void splash(Vec3 at, float magnitude) {
        HexNetwork.pilgrimEffect(self, "breach", at, magnitude);
        self.voice(HexGodOfStories.PILGRIM_WATER_IMPACT.get(), 96f, 0.8f);
    }

    /** Re-entry: the water gets thrown, everything nearby gets shoved, the view shakes. */
    private void impact(Vec3 at) {
        HexNetwork.pilgrimEffect(self, "water_impact", at, 2.0f);
        self.voice(HexGodOfStories.PILGRIM_WATER_IMPACT.get(), 128f, 0.62f);
        for (LivingEntity near : around(at, 30)) {
            Vec3 away = near.position().subtract(at);
            double d = Math.max(1.0, away.length());
            near.setDeltaMovement(near.getDeltaMovement().add(away.scale(2.0 / d)).add(0, 0.35, 0));
            near.hurtMarked = true;
            if (d < 14) damage(near, 5f, 0);
            near.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 70, 1, false, false));
        }
        for (Boat boat : self.level().getEntitiesOfClass(Boat.class, new AABB(at, at).inflate(30))) {
            Vec3 away = boat.position().subtract(at);
            double d = Math.max(1.0, away.length());
            boat.setDeltaMovement(boat.getDeltaMovement().add(away.scale(1.9 / d)).add(0, 0.9, 0));
            boat.hurtMarked = true;
        }
    }

    private void pulse(Vec3 at, double radius, float magnitude, String name) {
        HexNetwork.pilgrimEffect(self, name, at, magnitude);
    }
}
