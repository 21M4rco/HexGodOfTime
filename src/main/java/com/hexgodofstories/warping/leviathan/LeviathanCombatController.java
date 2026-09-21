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
    private int breachPreparation;
    private Vec3 anchor = Vec3.ZERO;
    private double orbit;
    private int orbitSign = 1;
    private boolean crossedSurface, splashed, escapeWindow;
    private final Set<UUID> struck = new HashSet<>();
    /** Where the jaws were on the previous tick, so a pass at speed can be swept rather than sampled. */
    @Nullable private Vec3 lastMouth;
    private Vec3 sweepFrom = Vec3.ZERO;
    /** Whether the pattern now running has actually put damage on something. */
    private boolean connected;
    /** The same, narrowed to the tick it happened on. Cleared at the top of every tick. */
    private boolean landedThisTick;
    /** Ticks before the jaws may be heard working again. */
    private int chewCooldown;
    /** How far below the waterline a dragged victim is ever taken. */
    private static final double DRAG_LIMIT = 150.0;
    /** How far above the waterline a leap will ever aim. Higher than this, flight has won. */
    private static final double MAX_LEAP = 110.0;
    /** Ticks of borrowed breath given to anything the creature intends to keep playing with. */
    private static final int LUNG = 700;

    public LeviathanCombatController(AbyssalPilgrimEntity self) { this.self = self; }

    public boolean active() { return attack != null; }
    @Nullable public LeviathanAttack current() { return attack; }
    @Nullable public Entity victim() { return victim; }
    public boolean ready() { return cooldown <= 0; }
    public void cool(int ticks) { cooldown = Math.max(cooldown, ticks); }
    /** True once the pattern currently running, or the last one that ran, drew blood. */
    public boolean connected() { return connected; }
    /** True only on the tick a blow actually landed, so a caller can edge trigger on it. */
    public boolean landed() { return landedThisTick; }
    /** Ticks left before another pattern may start. */
    public int cooldown() { return cooldown; }

    public void begin(LeviathanAttack pattern, @Nullable Entity target) {
        attack = pattern;
        victim = target;
        tick = 0;
        breachPreparation = 0;
        struck.clear();
        crossedSurface = splashed = escapeWindow = connected = false;
        anchor = target != null ? target.position() : self.position();
        orbit = self.getRandom().nextDouble() * Mth.TWO_PI;
        orbitSign = self.getRandom().nextBoolean() ? 1 : -1;
        self.setAttack(pattern);
        self.setAttackTick(0);
        self.control().setAllowAir(pattern.aerial());
        announce(pattern);
    }

    public void abort() {
        // Short: an abandoned pattern is a miss, and a predator that misses comes straight back
        // round. The old three second penalty was most of why a broken off approach turned into
        // another full lap of circling.
        if (attack != null) cooldown = 10 + self.getRandom().nextInt(16);
        attack = null; victim = null; tick = 0;
        self.setAttack(null);
        self.setAttackTick(0);
        self.control().setAllowAir(false);
        releaseHold(Vec3.ZERO, false);
    }

    public void tick() {
        landedThisTick = false;
        // Recorded every tick, attack or not, so the first tick of a pattern already has a real
        // previous position to sweep from instead of a zero.
        Vec3 mouthNow = mouth();
        sweepFrom = lastMouth == null ? mouthNow : lastMouth;
        lastMouth = mouthNow;

        if (crossedSurface && !splashed && self.getY() < self.surfaceY() - 1) {
            splashed = true;
            impact(new Vec3(self.getX(), self.surfaceY(), self.getZ()));
        }
        if (cooldown > 0) cooldown--;
        if (chewCooldown > 0) chewCooldown--;
        if (attack == null) return;
        if (victim != null && (!victim.isAlive() || victim.level() != self.level())) victim = null;

        self.setAttackTick(tick);
        carryHeld();

        // A leap at something that has already come back down is a leap at nothing. Anything that
        // only exists to reach the air gives up the moment the air stops being where the prey is.
        if (attack.huntsAir() && tick < attack.windup && victim != null && victim.isInWater()) { abort(); return; }

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
            case SKY_LEAP -> skyLeap();
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
            // Attacks are meant to be frequent without being a stream. The pattern clocks already
            // cost forty to a hundred ticks each, so this is the pause *between* them: about a
            // second and a half at rest, under a second at full frenzy, and a long one only after
            // the scream, which is an area denial pattern rather than a strike.
            cooldown = (finished.lethal() ? 16 : 8) + self.getRandom().nextInt(finished == LeviathanAttack.VOID_SCREAM ? 160 : 26);
            // A pattern that connected has earned a beat to let the victim react; one that missed
            // has earned nothing, and comes back round faster.
            if (connected) cooldown += 10;
            cooldown = (int) (cooldown * (1.0 - 0.5 * self.frenzy()));
        }
    }

    // ---------------------------------------------------------------- patterns

    /**
     * The commit. Line the jaws up over the windup, then run them through the prey.
     *
     * <p>Both halves used to steer at the victim's own position, which is why the pattern so often
     * ended with the mouth open a few blocks to one side of somebody who took no damage: the aim
     * point sat inside the move control's turning circle, so the body carved past it rather than
     * turning onto it, and the jaws — thirteen blocks ahead of the position being steered — were
     * never where the aim point was anyway. The windup now closes on the prey with the mouth
     * itself, and the active phase drives at a point well beyond them so the run is a straight
     * line the body can actually hold and the jaws cross the prey on the way past.
     */
    private void bite() {
        if (victim == null) { abort(); return; }
        if (tick < attack.windup) {
            // Closing, and already aimed past them: this is the moment the creature stops circling
            // and points itself, so the aim point has to stay outside the radius inside which the
            // steering gives up on turning. Shorter than the run itself, so it still reads as a
            // gather rather than as the strike starting early.
            steer(aimThrough(victim, 34), 1.5, 0.95f);
            self.control().addBurst(0.35);
        } else if (tick < attack.windup + attack.active) {
            steer(aimThrough(victim, 46), 2.4, 1.0f);
            self.control().addBurst(1.0);
            if (tick == attack.windup + 1) self.voice(HexGodOfStories.PILGRIM_BITE.get(), 22f, 0.85f);
            for (LivingEntity hit : headSweep(2.6)) {
                damage(hit, 14f, 0.55);
                if (hit == victim && attack.canHold && self.getRandom().nextFloat() < 0.55f) takeHold(hit);
            }
        } else if (self.held() != null) {
            // Held in the jaws: shaken rather than killed, if patience allows it. Whether or not it
            // has decided to finish them, it chews, on its own rhythm rather than on this clock.
            shakeVictim(0.9f);
            eat(30f, false);
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
            // Aimed at where the prey is now rather than where it was when the run started: a
            // charge that commits to a stale anchor is a charge that misses anything that moved
            // during the windup, and the pass is long enough for that to be most things.
            steer(victim.isAlive() ? aimThrough(victim, 70)
                : anchor.add(anchor.subtract(self.position()).normalize().scale(70)), 3.1, 0.55f);
            self.control().addBurst(1.6);
            for (LivingEntity hit : headSweep(2.8)) damage(hit, 16f, 1.6);
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
            steer(mouthOnto(lead(victim, 12)), 1.4, 0.85f);
        } else if (tick < attack.windup + attack.active) {
            if (tick == attack.windup) self.voice(HexGodOfStories.PILGRIM_GRAB.get(), 24f, 1.0f);
            // The tendrils reach from the mouth, so the mouth is what has to be brought to bear.
            steer(mouthOnto(lead(victim, 6)), 1.4, 0.9f);
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
            // This windup has to end with the jaws actually on someone or the pattern aborts, so
            // it drives through them rather than holding station short of them.
            steer(aimThrough(victim, 34), 1.9, 0.95f);
            for (LivingEntity hit : headSweep(2.5)) { takeHold(hit); break; }
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
        // The cage closes from fifty six blocks to thirty, which is the tightest ring a hundred and
        // twenty six blocks of spine can actually hold. Tighter than that is not a coil, it is a
        // knot: the body folds through itself and the whole pattern stops reading as a creature.
        double radius = Mth.lerp(progress, 56.0, 30.0);
        // Angular rate follows the radius so the tangential speed stays somewhere a body can swim.
        orbit += orbitSign * (1.7 / radius);
        Vec3 ring = victim.position().add(Math.cos(orbit) * radius, Math.sin(orbit * 0.7) * radius * 0.30, Math.sin(orbit) * radius);
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
                // The ring is wide, so the kill is the water it moves rather than the hull itself.
                for (LivingEntity hit : around(victim.position(), 13)) damage(hit, 18f, 0.4);
                for (LivingEntity hit : contacts(LeviathanMultipartHitbox.Section.BODY, 2.0)) damage(hit, 10f, 0.6);
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
            // Straight up and through, not up to. Stopping level with the prey is a charge that
            // arrives underneath them with the jaws already past.
            steer(aimThrough(victim, 50).add(0, 6, 0), 3.0, 0.7f);
            self.control().addBurst(1.4);
            self.setGlow(1f);
            for (LivingEntity hit : headSweep(2.8)) {
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
            for (LivingEntity hit : headSweep(2.0)) { damage(hit, 11f, 1.0); hit.setDeltaMovement(hit.getDeltaMovement().add(0, 1.35, 0)); hit.hurtMarked = true; }
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
        Vec3 aim = victim != null ? intercept(victim) : new Vec3(self.getX(), line + 45, self.getZ());
        if (tick < attack.windup) {
            breachPreparation++;
            double dive = Math.max(self.floorY() + 16, line - 95);
            Vec3 launch = new Vec3(anchor.x, dive, anchor.z);
            if (tick < attack.windup - 26) {
                steer(launch, 2.1, 0.85f);
                // Finish the physical descent before starting the ascent, with a bounded fallback.
                if (tick == attack.windup - 27 && self.position().distanceToSqr(launch) > 30 * 30
                        && breachPreparation < 200) tick--;
            } else {
                // Come up under the interception point with the whole body already pointing at it.
                steer(new Vec3(aim.x, self.getY() + 60, aim.z), 2.6, 1.0f);
                self.control().addBurst(0.9);
                if (tick == attack.windup - 1 && self.getXRot() > -45 && breachPreparation < 240) tick--;
            }
            self.setGlow(Mth.clamp(tick / (float) attack.windup, 0.2f, 1f));
            if (breachPreparation == 1) self.voice(HexGodOfStories.PILGRIM_BREACH_CHARGE.get(), 110f, 0.7f);
        } else if (tick == attack.windup) {
            // One solved impulse, then the arc belongs to gravity. Steering at a point in the sky
            // is what used to leave the body wallowing at the surface with its nose in the air.
            self.setGlow(1f);
            self.control().launch(arcTo(aim), 40);
            self.control().setAirSteer(0.04);
            self.voice(HexGodOfStories.PILGRIM_BREACH.get(), 128f, 0.85f);
        } else if (tick < attack.windup + attack.active) {
            steer(aim, 3.4, 0.45f);
            if (!crossedSurface && self.getY() >= line - 1) {
                crossedSurface = true;
                splash(new Vec3(self.getX(), line, self.getZ()), 2.0f);
            }
            for (LivingEntity hit : headSweep(3.0)) {
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

    /**
     * The jump. Everything else the creature does happens in water; this is the one pattern whose
     * whole purpose is to stop being in it.
     *
     * <p>Prey that has left the surface — thrown, flying, gliding, or simply standing on something
     * — used to be safe by default, because a swimming body aimed at a point in the sky arrives
     * late and short every time. So the arc is solved instead of steered: the creature reads where
     * the target will be, works out the launch that meets it there, lines up underneath, and then
     * throws itself. After the launch nothing corrects it but one tail flick's worth of drift.
     */
    private void skyLeap() {
        if (victim == null) { abort(); return; }
        double line = self.surfaceY();
        Vec3 aim = intercept(victim);

        if (tick < attack.windup) {
            // Run up. Deep enough to build speed, directly under the interception point, nose up.
            double depth = Math.max(self.floorY() + 20, line - 52);
            steer(new Vec3(aim.x, depth, aim.z), 2.4, 0.9f);
            self.control().addBurst(0.6);
            if (tick == 0) self.voice(HexGodOfStories.PILGRIM_BREACH_CHARGE.get(), 96f, 0.95f);
            self.setGlow(Mth.clamp(tick / (float) attack.windup, 0.25f, 1f));
            // Launching from three hundred blocks down is a leap that ends underwater. Hold the
            // windup until the run up has actually been made, but never indefinitely.
            if (tick == attack.windup - 1 && self.depth() > 85 && breachPreparation++ < 140) tick--;
        } else if (tick == attack.windup) {
            anchor = aim;
            self.setGlow(1f);
            self.control().launch(arcTo(aim), 30);
            self.control().setAirSteer(0.05);
            self.voice(HexGodOfStories.PILGRIM_BREACH.get(), 132f, 0.95f);
        } else if (tick < attack.windup + attack.active) {
            // Keep the aim fresh so the permitted drift is spent on the right place.
            steer(aim, 2.6, 0.3f);
            if (!crossedSurface && self.getY() >= line - 1) {
                crossedSurface = true;
                splash(new Vec3(self.getX(), line, self.getZ()), 2.2f);
            }
            for (LivingEntity hit : headSweep(3.2)) {
                damage(hit, 22f, 1.0);
                if (self.held() == null && attack.canHold) takeHold(hit);
            }
            // Down again, one way or the other.
            if (crossedSurface && self.getY() < line - 1 && !splashed) {
                splashed = true;
                impact(new Vec3(self.getX(), line, self.getZ()));
            }
        } else {
            if (crossedSurface && !splashed && self.getY() < line) { splashed = true; impact(new Vec3(self.getX(), line, self.getZ())); }
            steer(new Vec3(self.getX(), line - 45, self.getZ()), 1.3, 0.3f);
        }
    }

    /**
     * Where the prey will be by the time a leap could reach it. Three passes, because the flight
     * time depends on the height and the height depends on the lead.
     */
    private Vec3 intercept(Entity prey) {
        Vec3 here = prey.position();
        Vec3 drift = prey.getDeltaMovement();
        Vec3 aim = here;
        for (int pass = 0; pass < 3; pass++) {
            double rise = Math.max(6.0, aim.y - self.getY());
            double climb = Math.min(140, Math.sqrt(2 * rise / LeviathanMoveControl.GRAVITY));
            // Leads are damped: prey that jinks should be missed sometimes, not chased by magic.
            aim = new Vec3(here.x + drift.x * climb * 0.7, here.y + drift.y * climb * 0.35, here.z + drift.z * climb * 0.7);
        }
        return new Vec3(aim.x, Math.min(aim.y, self.surfaceY() + MAX_LEAP), aim.z);
    }

    /**
     * Launch velocity whose upward arc passes through {@code aim}.
     *
     * <p>Solved in two parts, because the creature does not start in the air. The speed it needs at
     * the waterline is ordinary projectile arithmetic; the speed it needs at the launch is that
     * plus whatever the remaining column of water will take off it on the way up.
     */
    private Vec3 arcTo(Vec3 aim) {
        double gravity = LeviathanMoveControl.GRAVITY;
        double surface = self.surfaceY();
        double above = Mth.clamp(aim.y - surface, 3.0, MAX_LEAP);
        // The extra fifteen percent pays for the air drag that this solution ignores, and the
        // floor keeps even a leap at something barely off the water a breach rather than a wallow.
        double atLine = Math.sqrt(2 * gravity * above);
        double depth = Math.max(0, surface - self.getY());
        double up = Mth.clamp(atLine * 1.15, 1.9, 4.6);
        // Time to the top: the climb through water, then the climb against gravity.
        double climb = Math.max(1.0, depth / Math.max(0.5, up) + atLine / gravity);
        double dx = aim.x - self.getX(), dz = aim.z - self.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-4) return new Vec3(0, up, 0);
        double forward = Math.min(flat / climb, 2.4);
        return new Vec3(dx / flat * forward, up, dz / flat * forward);
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
            // Something it threw into the air is something in the air, so the jump answers this
            // too: thrown, then met on the way down. If they land first the leap gives itself up.
            if (tick == attack.windup + 2 && victim.getY() > self.surfaceY() + 6) {
                begin(LeviathanAttack.SKY_LEAP, victim);
                tick = -1;   // begin zeroed the clock, and the caller is about to increment it
                return;
            }
            // Otherwise, try to be underneath them when they come down.
            steer(new Vec3(victim.getX(), Math.min(victim.getY(), self.surfaceY()) - 6, victim.getZ()), 2.4, 0.8f);
            for (LivingEntity hit : headSweep(2.6)) damage(hit, 12f, 0.8);
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
        // Wide enough for the body to hold the circle; the water it drags does the rest.
        double radius = 34;
        orbit += orbitSign * (2.0 / radius);
        Vec3 ring = anchor.add(Math.cos(orbit) * radius, -6 + Math.sin(orbit * 0.5) * 5, Math.sin(orbit) * radius);
        steer(ring, 2.6, 1.0f);
        if (tick >= attack.windup && tick < attack.windup + attack.active) {
            for (LivingEntity near : around(anchor, 36)) {
                Vec3 inward = anchor.subtract(near.position());
                double d = Math.max(0.8, inward.length());
                Vec3 swirl = new Vec3(-inward.z, 0, inward.x).scale(orbitSign / d);
                near.setDeltaMovement(near.getDeltaMovement().scale(0.86).add(inward.scale(0.10 / d)).add(swirl.scale(0.16)));
                near.hurtMarked = true;
            }
            for (Boat boat : self.level().getEntitiesOfClass(Boat.class, new AABB(anchor, anchor).inflate(36))) {
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

    // ---------------------------------------------------------------- aiming

    /**
     * Where the prey will be in {@code ticks}.
     *
     * <p>Horizontal drift is taken at face value and vertical drift is damped, because a swimmer's
     * vertical velocity is mostly buoyancy bobbing and leading it puts the jaws under their feet.
     * Something standing perfectly still has no drift at all and therefore no lead, which is the
     * point: a stationary target is the easiest thing in the ocean to hit, not the hardest.
     */
    private Vec3 lead(Entity prey, double ticks) {
        Vec3 drift = prey.getDeltaMovement();
        return prey.position().add(drift.x * ticks, drift.y * ticks * 0.3, drift.z * ticks);
    }

    /**
     * The point to drive the body at so the jaws pass clean through the prey.
     *
     * <p>Steering at the prey's own position is what made a strike a near miss. Two things go
     * wrong with it. The move control refuses to turn toward anything inside its own turning
     * circle — it carves past and comes back round, which is exactly the endless orbiting the
     * creature was doing — and a point the body arrives at is a point the body stops at, with
     * {@link AbyssalPilgrimEntity#MOUTH_REACH} blocks of skull already past the victim.
     *
     * <p>Aiming well beyond them fixes both at once. The aim point is never inside the turning
     * circle, so the heading converges the whole way in; and because the mouth rides ahead of the
     * body on the same straight line, whatever the body is driven through the jaws reach first.
     */
    private Vec3 aimThrough(Entity prey, double beyond) {
        Vec3 mark = lead(prey, 8);
        Vec3 run = mark.subtract(self.position());
        Vec3 line = run.lengthSqr() < 1.0E-6 ? self.getLookAngle() : run.normalize();
        return mark.add(line.scale(beyond));
    }

    /** Where the body must sit for the jaws to rest on {@code point} rather than past it. */
    private Vec3 mouthOnto(Vec3 point) {
        return point.subtract(self.getLookAngle().scale(AbyssalPilgrimEntity.MOUTH_REACH));
    }


    public Vec3 mouth() {
        return self.mouthPosition();
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

    /**
     * Everything the jaws crossed between the previous tick and this one.
     *
     * <p>A single frame overlap test is the wrong test for a head that covers several blocks a
     * tick. The box is somewhere else the tick before and somewhere else again the tick after, so
     * a pass at strike speed can go straight through a player without ever sampling them — the
     * player sees the mouth close on them and takes nothing. Sweeping the head's own travel makes
     * the damage code agree with what was on screen.
     */
    private List<LivingEntity> headSweep(double inflate) {
        Vec3 now = mouth();
        Vec3 was = sweepFrom;
        double reach = LeviathanSegmentController.radius(0) + inflate;
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity candidate : self.level().getEntitiesOfClass(
                LivingEntity.class, new AABB(was, now).inflate(reach), this::prey)) {
            AABB grown = candidate.getBoundingBox().inflate(reach);
            if (grown.contains(now) || grown.contains(was) || grown.clip(was, now).isPresent()) found.add(candidate);
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
        connected = landedThisTick = true;
        entity.hurt(self.attackDamage(entity), amount);
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
        // The water it displaced, and then what the jaws found inside it.
        self.voice(HexGodOfStories.PILGRIM_GRAB.get(), 26f, 0.9f);
        eat(32f, true);
    }

    /**
     * The sound of something being eaten.
     *
     * <p>Three recordings of the same jaws share one sound event, so the game draws a different one
     * on every play and a long meal never becomes a loop. Every one of them is pitched well under
     * where it was recorded, because the thing doing the chewing is a hundred and fifty blocks long
     * and bone at its recorded pitch reads as a dog with a biscuit.
     *
     * <p>The cooldown is a chewing rhythm rather than a rate limit: about a second and a half to
     * two and a half between mouthfuls, which at this pitch is roughly the length of one. Catching
     * something and killing it both insist, because those are the moments worth hearing.
     */
    void eat(float volume, boolean insist) {
        if (chewCooldown > 0 && !insist) return;
        chewCooldown = 30 + self.getRandom().nextInt(22);
        self.voice(HexGodOfStories.HEXOR_EAT.get(), volume, 0.45f + self.getRandom().nextFloat() * 0.17f);
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
        // The leap is telegraphed for the same reason the breach is: something this size lining up
        // underneath you should be felt before it arrives, or the kill is not a fair one.
        if (pattern == LeviathanAttack.VOID_SCREAM || pattern == LeviathanAttack.BREACH_BITE
            || pattern == LeviathanAttack.SKY_LEAP) HexNetwork.pilgrimEffect(self, "charge", self.segments().segment(0), 1f);
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
