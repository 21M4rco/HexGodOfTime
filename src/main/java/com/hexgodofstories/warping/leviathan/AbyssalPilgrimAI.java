package com.hexgodofstories.warping.leviathan;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The behavioural core.
 *
 * <p>Two numbers shape everything: <em>patience</em>, which is how willing the creature is to
 * extend a hunt instead of ending it, and <em>frenzy</em>, which is how far it has stopped caring.
 * High patience produces the toying repertoire below; high frenzy suppresses it entirely. Neither
 * is visible to the player, which is the point: an approach never announces what it is.
 */
public final class AbyssalPilgrimAI {
    /** Deliberately non lethal harassment. */
    private enum Toy { PASS_UNDER, CIRCLE, WATCH, BUMP_BOAT, GRAZE, DRAG_RELEASE, TENDRIL_TOSS, BITE_RELEASE, FEINT, SURFACE_BESIDE, VANISH, STARE }

    private final AbyssalPilgrimEntity self;
    private final LeviathanHuntController hunt;
    private final LeviathanCombatController combat;

    private LeviathanState state = LeviathanState.SEARCH;
    private int stateTicks, stateLimit = 120;
    private float patience = 0.7f;
    private float frenzy;
    private Toy toy = Toy.CIRCLE;
    private int toyTicks;
    private int quietTicks;
    private int callTimer = 200;
    private long lastKill;
    private int followUp = -1;
    private int alertCooldown;
    private int waterPursuit;
    /** Keeps the leap a decisive event rather than a constant fountain. */
    private int leapCooldown;

    public AbyssalPilgrimAI(AbyssalPilgrimEntity self) {
        this.self = self;
        this.hunt = new LeviathanHuntController(self);
        this.combat = new LeviathanCombatController(self);
    }

    public LeviathanHuntController hunt() { return hunt; }
    public LeviathanCombatController combat() { return combat; }

    /**
     * Something just entered the water. This is the one signal that bypasses the creature's
     * deliberately imperfect long range knowledge: it gets an exact position and commits.
     */
    public void alert(Entity prey) {
        if (prey == null || !prey.isAlive() || prey.level() != self.level()) return;
        alertCooldown = 80;
        waterPursuit = 240;
        hunt.focus(prey);
        hunt.sharpen(prey);
        frenzy = Math.min(1f, frenzy + 0.10f);
        double distance = hunt.targetDistance();
        setState(distance < 140 ? LeviathanState.HUNT : LeviathanState.TRACK);
        self.voice(HexGodOfStories.PILGRIM_TARGET_DETECTED.get(), 140f, 0.92f + self.getRandom().nextFloat() * 0.12f);
    }

    /** Being struck cannot hurt it, but it does register who tried. */
    public void provoke(Entity source) {
        frenzy = Math.min(1f, frenzy + 0.05f);
        patience = Math.max(0f, patience - 0.04f);
        if (source instanceof LivingEntity && hunt.target() != source && self.getRandom().nextFloat() < 0.5f) hunt.focus(source);
    }

    public void tick() {
        if (!(self.level() instanceof ServerLevel level)) return;
        RandomSource random = self.getRandom();

        if (alertCooldown > 0) alertCooldown--;
        hunt.tick(level);
        combat.tick();
        updateMood(level);
        ambience(level);
        syncLook();

        if (combat.active()) {
            if (state != LeviathanState.ATTACK) setState(LeviathanState.ATTACK);
            return;
        }
        if (leapCooldown > 0) leapCooldown--;
        // Prey that leaves the water is answered by leaving the water, immediately and without
        // waiting for an attack roll to come up. A hunter that only looks at the sky when its own
        // dice say so is a hunter that lets everything airborne live, which is how the surface
        // became a safe place to stand.
        if (leapCooldown <= 0 && !self.isDying() && self.depth() < 220
                && hunt.leapable(58, LeviathanAttack.SKY_LEAP.range)) {
            leapCooldown = 140 + random.nextInt(200);
            combat.begin(LeviathanAttack.SKY_LEAP, hunt.target());
            setState(LeviathanState.ATTACK);
            return;
        }
        if (followUp >= 0) { LeviathanAttack next = LeviathanAttack.byId(followUp); followUp = -1; if (combat.ready()) { combat.begin(next, hunt.target()); return; } }

        stateTicks++;
        if (waterPursuit > 0 && hunt.hasTarget()) {
            waterPursuit--;
            hunt.sharpen(hunt.target());
            if (state != LeviathanState.HUNT) setState(LeviathanState.HUNT);
        } else if (stateTicks >= stateLimit) chooseState(random);

        switch (state) {
            case SEARCH -> search(level);
            case TRACK -> track();
            case STALK -> stalk();
            case TOY -> toying(level, random);
            case HUNT -> hunting(random);
            case AMBUSH -> ambush(random);
            case FRENZY -> frenzied(random);
            case ATTACK -> setState(LeviathanState.HUNT);
        }
        applyGlow();
    }

    // ---------------------------------------------------------------- mood

    private void updateMood(ServerLevel level) {
        long now = level.getGameTime();
        boolean engaged = hunt.hasTarget() && hunt.targetDistance() < 220;

        if (engaged) {
            quietTicks = 0;
            // A hunt that will not end makes it less interested in playing.
            if (hunt.contactTicks() > 1200 && now % 40 == 0) { frenzy = Math.min(1f, frenzy + 0.02f); patience = Math.max(0.05f, patience - 0.015f); }
            // Prey that ran to the sky and came back down is the best possible news.
            if (hunt.target() != null && hunt.target().isInWater() && hunt.airborneTargetRecently) { frenzy = Math.min(1f, frenzy + 0.08f); }
        } else {
            quietTicks++;
            if (quietTicks > 400 && now % 60 == 0) { frenzy = Math.max(0f, frenzy - 0.02f); patience = Math.min(1f, patience + 0.01f); }
        }
        if (level.players().size() > 1 && now % 200 == 0) frenzy = Math.min(1f, frenzy + 0.01f);
        if (now - lastKill < 600) { patience = Math.min(1f, patience + 0.004f); frenzy = Math.max(0f, frenzy - 0.004f); }

        hunt.airborneTargetRecently = hunt.airborneTarget();
        self.setFrenzy(Mth.floor(frenzy * 16) / 16f);
    }

    private void applyGlow() {
        float base = state.glow;
        if (state == LeviathanState.STALK || state == LeviathanState.AMBUSH) base = 0.02f + 0.03f * (1f - patience);
        base = Mth.clamp(base + frenzy * 0.25f, 0f, 1f);
        float quantised = Mth.floor(base * 16) / 16f;
        if (Math.abs(self.glow() - quantised) > 0.001f) self.setGlow(quantised);
    }

    private void syncLook() {
        Entity target = hunt.target();
        int id = target == null ? -1 : target.getId();
        if (self.lookTargetId() != id) self.setLookTargetId(id);
    }

    // ---------------------------------------------------------------- state selection

    private void setState(LeviathanState next) {
        if (state == next) return;
        state = next;
        self.setState(next);
        stateTicks = 0;
        stateLimit = next.minTicks + self.getRandom().nextInt(Math.max(1, next.maxTicks - next.minTicks));
    }

    private void chooseState(RandomSource random) {
        if (!hunt.hasTarget()) { setState(LeviathanState.SEARCH); return; }
        double distance = hunt.targetDistance();

        if (frenzy > 0.75f) { setState(LeviathanState.FRENZY); return; }
        if (distance > 260) { setState(LeviathanState.TRACK); return; }
        if (distance > 110) { setState(random.nextFloat() < 0.4f ? LeviathanState.AMBUSH : LeviathanState.TRACK); return; }

        // Inside a hundred blocks the decision is the whole design: play, hide, or commit.
        float play = patience * (1f - frenzy);
        float roll = random.nextFloat();
        if (roll < play * 0.55f) { setState(LeviathanState.TOY); pickToy(random); }
        else if (roll < play * 0.55f + 0.28f) setState(LeviathanState.STALK);
        else if (roll < play * 0.55f + 0.46f) setState(LeviathanState.AMBUSH);
        else setState(LeviathanState.HUNT);
    }

    // ---------------------------------------------------------------- state behaviour

    private void search(ServerLevel level) {
        self.control().moveTo(hunt.roamPoint(level), 0.55 * LeviathanState.SEARCH.speed + 0.25, 0.14f);
    }

    private void track() {
        Vec3 aim = hunt.estimate().add(0, -40, 0);
        self.control().moveTo(clampWater(aim), 1.25, 0.3f);
    }

    private void stalk() {
        self.control().moveTo(hunt.stalkPoint(), 0.75, 0.22f);
        if (stateTicks % 70 == 0 && self.getRandom().nextFloat() < 0.5f) self.voice(HexGodOfStories.PILGRIM_CLICKING.get(), 34f, 0.9f + self.getRandom().nextFloat() * 0.2f);
        // Seen while stalking means going somewhere else entirely.
        Entity target = hunt.target();
        if (target != null && hunt.insideViewOf(target) && self.getRandom().nextFloat() < 0.02f) setState(LeviathanState.AMBUSH);
    }

    private void ambush(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        Vec3 point = hunt.ambushPoint();
        self.control().moveTo(point, 1.1, 0.25f);
        boolean set = self.position().distanceToSqr(point) < 320;
        boolean unseen = !hunt.insideViewOf(target);
        if (set && unseen && stateTicks > 50) {
            // The ocean has been silent for a while. Now it stops being silent.
            LeviathanAttack strike = random.nextFloat() < 0.45f && hunt.airborneTarget() ? LeviathanAttack.BREACH_BITE
                : random.nextFloat() < 0.5f ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE;
            if (combat.ready()) { combat.begin(strike, target); setState(LeviathanState.ATTACK); }
        }
    }

    private void hunting(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        double distance = hunt.targetDistance();
        self.control().moveTo(hunt.approachPoint(), 1.5 + frenzy, 0.45f);
        if (!combat.ready()) return;

        LeviathanAttack pick = pickAttack(random, target, distance, false);
        if (pick != null && distance < pick.range + 10) { combat.begin(pick, target); setState(LeviathanState.ATTACK); }
    }

    private void frenzied(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        self.control().moveTo(target.position(), 2.1, 0.7f);
        if (stateTicks == 1) self.voice(HexGodOfStories.PILGRIM_ROAR.get(), 112f, 0.8f);
        if (!combat.ready()) return;
        LeviathanAttack pick = pickAttack(random, target, hunt.targetDistance(), true);
        if (pick != null) { combat.begin(pick, target); setState(LeviathanState.ATTACK); }
    }

    @Nullable
    private LeviathanAttack pickAttack(RandomSource random, Entity target, double distance, boolean lethalOnly) {
        boolean surface = target.getY() > self.surfaceY() - 6;
        boolean holding = self.held() != null;

        if (holding) return random.nextFloat() < 0.5f ? LeviathanAttack.AIR_THROW : LeviathanAttack.DRAG_BELOW;
        // Anything off the water is answered by leaving the water. The short leap is the ordinary
        // reply; the long breach is saved for prey far enough up to be worth the whole run up.
        if (hunt.leapable(58, LeviathanAttack.SKY_LEAP.range)) return LeviathanAttack.SKY_LEAP;
        if (hunt.airborneTarget() && distance < LeviathanAttack.BREACH_BITE.range) return LeviathanAttack.BREACH_BITE;
        if (!lethalOnly && patience > 0.55f && random.nextFloat() < 0.22f) return LeviathanAttack.FAKE_ATTACK;

        float roll = random.nextFloat();
        if (surface && roll < 0.2f) return LeviathanAttack.SURFACE_RAM;
        if (distance > 44) return roll < 0.5f ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE;
        if (roll < 0.14f) return LeviathanAttack.VOID_SCREAM;
        if (roll < 0.26f) return LeviathanAttack.WATER_VORTEX;
        if (roll < 0.40f) return LeviathanAttack.TAIL_SWEEP;
        if (roll < 0.54f) return LeviathanAttack.TENDRIL_GRAB;
        if (roll < 0.66f) return LeviathanAttack.BODY_CRUSH;
        if (roll < 0.80f) return LeviathanAttack.ABYSSAL_LUNGE;
        return LeviathanAttack.PREDATORY_BITE;
    }

    // ---------------------------------------------------------------- toying

    private void pickToy(RandomSource random) {
        Toy[] all = Toy.values();
        toy = all[random.nextInt(all.length)];
        toyTicks = 60 + random.nextInt(140);
    }

    private void toying(ServerLevel level, RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        if (toyTicks-- <= 0) { pickToy(random); }

        switch (toy) {
            case PASS_UNDER -> {
                // Straight underneath, at speed, without ever turning toward them.
                Vec3 through = target.position().add(self.getLookAngle().scale(70)).add(0, -14, 0);
                self.control().moveTo(clampWater(through), 1.3, 0.2f);
            }
            case CIRCLE -> self.control().moveTo(hunt.stalkPoint(), 0.95, 0.4f);
            case WATCH -> {
                Vec3 spot = hunt.observePoint();
                self.control().moveTo(spot, 0.8, 0.25f);
                if (stateTicks % 120 == 0) self.voice(HexGodOfStories.PILGRIM_DEEP_IDLE.get(), 96f, 0.7f);
            }
            case BUMP_BOAT -> {
                Boat boat = nearestBoat(target);
                if (boat == null) { pickToy(random); return; }
                self.control().moveTo(boat.position().add(0, -4, 0), 1.1, 0.5f);
                if (self.segments().segment(0).distanceToSqr(boat.position()) < 90) {
                    boat.setDeltaMovement(boat.getDeltaMovement().add((random.nextDouble() - 0.5) * 0.35, 0.42, (random.nextDouble() - 0.5) * 0.35));
                    boat.hurtMarked = true;
                    HexNetwork.pilgrimEffect(self, "bump", boat.position(), 0.5f);
                    pickToy(random);
                }
            }
            case GRAZE -> {
                self.control().moveTo(target.position(), 1.4, 0.6f);
                for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, self.segments().box(2).inflate(2.5), e -> e != self && e.isAlive())) {
                    if (near instanceof Player player && player.isCreative()) continue;
                    near.hurt(self.damageSources().mobAttack(self), 2f);
                    Vec3 away = near.position().subtract(self.segments().segment(2));
                    if (away.lengthSqr() > 1.0E-4) near.setDeltaMovement(near.getDeltaMovement().add(away.normalize().scale(1.1)));
                    near.hurtMarked = true;
                    pickToy(random);
                }
            }
            case DRAG_RELEASE -> { if (combat.ready()) { combat.begin(LeviathanAttack.DRAG_BELOW, target); setState(LeviathanState.ATTACK); } }
            case TENDRIL_TOSS -> { if (combat.ready()) { combat.begin(LeviathanAttack.TENDRIL_GRAB, target); followUp = LeviathanAttack.AIR_THROW.ordinal(); setState(LeviathanState.ATTACK); } }
            case BITE_RELEASE -> { if (combat.ready()) { combat.begin(LeviathanAttack.PREDATORY_BITE, target); setState(LeviathanState.ATTACK); } }
            case FEINT -> { if (combat.ready()) { combat.begin(LeviathanAttack.FAKE_ATTACK, target); setState(LeviathanState.ATTACK); } }
            case SURFACE_BESIDE -> {
                Vec3 beside = target.position().add((random.nextBoolean() ? 9 : -9), 0, (random.nextBoolean() ? 9 : -9));
                self.control().moveTo(new Vec3(beside.x, self.surfaceY() - 2.0, beside.z), 0.9, 0.35f);
            }
            case VANISH -> {
                // Seen, so gone. It will be somewhere else and lower when it comes back.
                self.control().moveTo(new Vec3(self.getX(), self.floorY() + 14, self.getZ()).add(self.getLookAngle().scale(90)), 1.6, 0.3f);
                if (stateTicks > 90) setState(LeviathanState.AMBUSH);
            }
            case STARE -> {
                Vec3 ring = hunt.underneathPoint(22 + Math.sin(stateTicks * 0.02) * 8);
                self.control().moveTo(ring, 0.45, 0.5f);
                if (stateTicks % 90 == 0) self.voice(HexGodOfStories.PILGRIM_STALK.get(), 48f, 0.85f);
            }
        }
    }

    @Nullable
    private Boat nearestBoat(Entity near) {
        AABB box = new AABB(near.position(), near.position()).inflate(24);
        return self.level().getEntitiesOfClass(Boat.class, box).stream().findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- ambience

    private void ambience(ServerLevel level) {
        if (--callTimer > 0) return;
        RandomSource random = self.getRandom();
        double distance = hunt.targetDistance();
        if (distance > 260 || !hunt.hasTarget()) {
            self.voice(HexGodOfStories.PILGRIM_DISTANT_CALL.get(), 180f, 0.55f + random.nextFloat() * 0.15f);
            callTimer = 420 + random.nextInt(900);
        } else if (state.hidden()) {
            self.voice(HexGodOfStories.PILGRIM_STALK.get(), 64f, 0.8f + random.nextFloat() * 0.2f);
            callTimer = 260 + random.nextInt(420);
        } else {
            self.voice(HexGodOfStories.PILGRIM_DEEP_IDLE.get(), 110f, 0.7f + random.nextFloat() * 0.2f);
            callTimer = 320 + random.nextInt(560);
        }
    }

    public void noteKill() { lastKill = self.level().getGameTime(); frenzy = Math.max(0f, frenzy - 0.35f); patience = Math.min(1f, patience + 0.2f); hunt.forget(); }

    private Vec3 clampWater(Vec3 point) {
        return new Vec3(point.x, Mth.clamp(point.y, self.floorY() + 8, self.surfaceY() - 3), point.z);
    }

    // ---------------------------------------------------------------- persistence

    public void save(CompoundTag tag) {
        tag.putFloat("PilgrimPatience", patience);
        tag.putFloat("PilgrimFrenzyAi", frenzy);
        tag.putByte("PilgrimAiState", (byte) state.ordinal());
    }

    public void load(CompoundTag tag) {
        patience = tag.contains("PilgrimPatience") ? tag.getFloat("PilgrimPatience") : 0.7f;
        frenzy = tag.getFloat("PilgrimFrenzyAi");
        state = LeviathanState.byId(tag.getByte("PilgrimAiState"));
    }
}
