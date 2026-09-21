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
    private int callTimer = 400;
    /** Ticks the ambient clip still has to run. Nothing starts a second one over the top of it. */
    private int callPlaying;
    private long lastKill;
    /**
     * Ticks spent with prey in reach and nothing landed on it.
     *
     * <p>The stalking repertoire is the best thing this creature does and none of it has been taken
     * away, but every one of those behaviours could be chosen again the instant the last one ended,
     * with nothing anywhere counting how long that had been going on. A run of them could last
     * minutes, and a player who simply stood still could watch the ocean circle them indefinitely.
     *
     * <p>This is the clock that says enough. It rises while prey is close and unhurt, it collapses
     * the moment something connects, and everything that decides between playing and committing
     * reads it through {@link #commitment()}. Circling is still how a hunt starts; it is no longer
     * how one can end.
     */
    private int pressure;
    /** Set when the last pattern chosen was a feint, so two can never run back to back. */
    private boolean lastWasFeint;
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

    /** Inside this, prey counts as engaged and the commit clock runs. */
    private static final double ENGAGE = 150.0;
    /** Ticks of fruitless hunting that take the creature from patient to fully committed. */
    private static final int PATIENCE_LIMIT = 360;

    public LeviathanHuntController hunt() { return hunt; }
    public LeviathanCombatController combat() { return combat; }

    /** Nought while the hunt is young, one once it has gone on too long to keep playing. */
    public float commitment() { return Mth.clamp(pressure / (float) PATIENCE_LIMIT, 0f, 1f); }

    /** Starts a pattern and puts the state machine into the state that matches it, together. */
    private void commit(LeviathanAttack pattern, @Nullable Entity target) {
        lastWasFeint = pattern == LeviathanAttack.FAKE_ATTACK;
        combat.begin(pattern, target);
        setState(LeviathanState.ATTACK);
    }

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
        // Landing something is the only thing that buys patience back. Nothing else resets this:
        // not a new state, not a new target, not another lap. Read on the tick the blow lands
        // rather than from the standing flag, which stays set for the rest of the pattern and
        // would otherwise hold the clock at zero right through the next bout of circling.
        if (combat.landed()) pressure = 0;
        else if (hunt.hasTarget() && hunt.targetDistance() < ENGAGE) pressure++;
        else pressure = Math.max(0, pressure - 2);
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
        if (takeLeap()) { commit(LeviathanAttack.SKY_LEAP, hunt.target()); return; }
        // The follow up used to start its pattern without moving the state machine, which left the
        // creature visibly performing an attack while its navigation was still running whichever
        // circling state it had been in. Everything that starts a pattern now goes through one
        // place that does both.
        if (followUp >= 0) { LeviathanAttack next = LeviathanAttack.byId(followUp); followUp = -1; if (combat.ready()) { commit(next, hunt.target()); return; } }

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
        // A stalk allowed to run for thirty seconds is a stalk the player experiences as the
        // creature having lost interest in them. While something is genuinely in reach, the states
        // that do not commit get a window rather than a free run: a ceiling that tightens as the
        // hunt wears on, and a floor, because a state that ends after two seconds ends before the
        // behaviour inside it has had time to reach its own strike.
        if (!next.committed() && hunt.hasTarget() && hunt.targetDistance() < ENGAGE)
            stateLimit = Mth.clamp(stateLimit, 60, (int) (150 - 90 * commitment()));
    }

    private void chooseState(RandomSource random) {
        if (!hunt.hasTarget()) { setState(LeviathanState.SEARCH); return; }
        double distance = hunt.targetDistance();

        if (frenzy > 0.75f) { setState(LeviathanState.FRENZY); return; }
        if (distance > 260) { setState(LeviathanState.TRACK); return; }
        if (distance > 110) { setState(random.nextFloat() < 0.4f ? LeviathanState.AMBUSH : LeviathanState.TRACK); return; }

        // Inside a hundred blocks the decision is the whole design: play, hide, or commit. What
        // has changed is that playing is no longer the default and no longer open ended. Roughly
        // half of a fresh hunt is still spent circling, watching and hiding, because that is the
        // creature; every share of it is bled into hunting as the commit clock runs, so the first
        // laps are atmosphere and the tenth is not.
        float commitment = commitment();
        float play = patience * (1f - frenzy) * (1f - commitment);
        float toyShare = play * 0.34f;
        float stalkShare = toyShare + 0.20f * (1f - commitment);
        float ambushShare = stalkShare + 0.12f * (1f - commitment);
        float roll = random.nextFloat();
        if (roll < toyShare) { setState(LeviathanState.TOY); pickToy(random); }
        else if (roll < stalkShare) setState(LeviathanState.STALK);
        else if (roll < ambushShare) setState(LeviathanState.AMBUSH);
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
        // The ring closes as the hunt wears on, so a long stalk reads as a decision being made
        // rather than as a loop with no exit.
        self.control().moveTo(hunt.stalkPoint(commitment()), 0.75 + 0.5 * commitment(), 0.22f);
        if (stateTicks % 70 == 0 && self.getRandom().nextFloat() < 0.5f) self.voice(HexGodOfStories.PILGRIM_CLICKING.get(), 34f, 0.9f + self.getRandom().nextFloat() * 0.2f);
        // Seen while stalking means going somewhere else entirely.
        Entity target = hunt.target();
        if (target != null && hunt.insideViewOf(target) && self.getRandom().nextFloat() < 0.02f) setState(LeviathanState.AMBUSH);
    }

    private void ambush(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        Vec3 point = hunt.ambushPoint();
        self.control().moveTo(point, 1.1 + commitment(), 0.25f);
        // Eighteen blocks was a tolerance a hundred and fifty blocks of creature almost never met:
        // the steering carves past anything inside its turning circle, so it orbited the station
        // rather than arriving at it and the strike below simply never fired. Forty is a distance
        // the body can genuinely be said to be holding.
        boolean set = self.position().distanceToSqr(point) < 40 * 40;
        boolean unseen = !hunt.insideViewOf(target);
        // Being watched used to veto the strike outright and forever, which made standing still
        // and staring at the water a perfect defence — the one posture that never breaks line of
        // sight was the one the ambush was waiting for it to break. It still prefers to be unseen
        // and it will still wait for it, but the waiting is bounded now, and a patient starer gets
        // taken anyway.
        // Kept below the ceiling setState puts on this state, or it would expire every time just
        // before the wait it is counting ran out, and the strike would never happen at all.
        int willWait = (int) (110 - 80 * commitment());
        if (combat.ready() && stateTicks > 30 && (set || stateTicks > willWait) && (unseen || stateTicks > willWait)) {
            // The ocean has been silent for a while. Now it stops being silent.
            LeviathanAttack strike = random.nextFloat() < 0.45f && hunt.airborneTarget() ? LeviathanAttack.BREACH_BITE
                : random.nextFloat() < 0.5f ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE;
            commit(strike, target);
        }
    }

    /**
     * The committed approach, and the point at which circling has to turn into an attack.
     *
     * <p>Two things used to stop this state from ever producing one. The approach drove at a point
     * eight to eighteen blocks behind the prey, which is inside the move control's turning circle
     * and therefore a point it carves past rather than arrives at; and the range gate compared the
     * body's distance against a pattern's raw range without any allowance for the thirteen blocks
     * of jaw ahead of that body or the ground the windup itself covers, so patterns whose own run
     * would comfortably have reached were rejected on every tick. Between them the creature could
     * sit in HUNT indefinitely, closing to a fixed standoff and never firing.
     */
    private void hunting(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        float commitment = commitment();
        double distance = hunt.targetDistance();
        self.control().moveTo(hunt.approachPoint(commitment), 1.5 + frenzy + commitment, 0.45f + 0.35f * commitment);
        if (!combat.ready()) return;

        LeviathanAttack pick = pickAttack(random, target, distance, false);
        if (pick == null) return;
        // The jaws lead the body, and the windup is spent closing, so both count toward reach.
        double reach = pick.range + AbyssalPilgrimEntity.MOUTH_REACH + 24;
        if (distance < reach) { commit(pick, target); return; }
        // Too far for what came up, but not too far to be attacked: close with something whose run
        // covers the gap rather than doing nothing and going back round.
        if (distance < LeviathanAttack.DEEP_CHARGE.range + 70)
            commit(distance > 90 ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE, target);
    }

    private void frenzied(RandomSource random) {
        Entity target = hunt.target();
        if (target == null) { setState(LeviathanState.SEARCH); return; }
        // Driven past the prey rather than at it, for the same reason every strike now is: a body
        // this long cannot turn onto a point it is already almost on top of, and trying reads as a
        // lap around them.
        self.control().moveTo(hunt.approachPoint(1.0), 2.1, 0.7f);
        if (stateTicks == 1) roar(HexGodOfStories.PILGRIM_ROAR.get(), 112f, 0.8f, 900 + random.nextInt(1400));
        if (!combat.ready()) return;
        LeviathanAttack pick = pickAttack(random, target, hunt.targetDistance(), true);
        if (pick != null) commit(pick, target);
    }

    /**
     * Whether a leap is both possible and off cooldown, claiming the cooldown when it answers yes.
     *
     * <p>Both routes into the pattern ask here — the direct answer to something leaving the water,
     * and the ordinary attack roll — so the creature cannot chain leaps by coming at it from the
     * other side. A leviathan that is permanently in the air is a fountain, not a hunter.
     */
    private boolean takeLeap() {
        if (leapCooldown > 0 || self.isDying() || self.depth() >= 220) return false;
        if (!hunt.leapable(58, LeviathanAttack.SKY_LEAP.range)) return false;
        leapCooldown = 140 + self.getRandom().nextInt(200);
        return true;
    }

    @Nullable
    private LeviathanAttack pickAttack(RandomSource random, Entity target, double distance, boolean lethalOnly) {
        boolean surface = target.getY() > self.surfaceY() - 6;
        boolean holding = self.held() != null;

        if (holding) return random.nextFloat() < 0.5f ? LeviathanAttack.AIR_THROW : LeviathanAttack.DRAG_BELOW;
        // Anything off the water is answered by leaving the water. The short leap is the ordinary
        // reply; the long breach is saved for prey far enough up to be worth the whole run up.
        if (takeLeap()) return LeviathanAttack.SKY_LEAP;
        if (hunt.airborneTarget() && distance < LeviathanAttack.BREACH_BITE.range) return LeviathanAttack.BREACH_BITE;
        // The feint is a great trick and was being pulled far too often — better than one approach
        // in five ended with the jaws opening on nothing, which is most of what "it looks like it
        // is about to attack and then does not" was. It stays, at a third of the rate, never twice
        // running, and never once the hunt has gone on long enough to have stopped being a game.
        if (!lethalOnly && !lastWasFeint && commitment() < 0.3f && patience > 0.55f && random.nextFloat() < 0.08f)
            return LeviathanAttack.FAKE_ATTACK;

        float roll = random.nextFloat();
        if (surface && roll < 0.2f) return LeviathanAttack.SURFACE_RAM;
        if (distance > 44) return roll < 0.5f ? LeviathanAttack.DEEP_CHARGE : LeviathanAttack.ABYSSAL_LUNGE;
        // Weighted toward the jaws and the two patterns that end in a body passing through the
        // prey. The slow set-pieces are still here; they are no longer what usually comes up.
        if (roll < 0.06f) return LeviathanAttack.VOID_SCREAM;
        if (roll < 0.14f) return LeviathanAttack.WATER_VORTEX;
        if (roll < 0.26f) return LeviathanAttack.TAIL_SWEEP;
        if (roll < 0.38f) return LeviathanAttack.TENDRIL_GRAB;
        if (roll < 0.46f) return LeviathanAttack.BODY_CRUSH;
        if (roll < 0.68f) return LeviathanAttack.ABYSSAL_LUNGE;
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
            case CIRCLE -> self.control().moveTo(hunt.stalkPoint(commitment()), 0.95, 0.4f);
            case WATCH -> {
                Vec3 spot = hunt.observePoint();
                self.control().moveTo(spot, 0.8, 0.25f);
                if (stateTicks % 260 == 0) self.voice(HexGodOfStories.PILGRIM_DEEP_IDLE.get(), 96f, 0.7f);
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
                    near.hurt(self.attackDamage(near), 2f);
                    Vec3 away = near.position().subtract(self.segments().segment(2));
                    if (away.lengthSqr() > 1.0E-4) near.setDeltaMovement(near.getDeltaMovement().add(away.normalize().scale(1.1)));
                    near.hurtMarked = true;
                    pickToy(random);
                }
            }
            case DRAG_RELEASE -> { if (combat.ready()) commit(LeviathanAttack.DRAG_BELOW, target); }
            case TENDRIL_TOSS -> { if (combat.ready()) { followUp = LeviathanAttack.AIR_THROW.ordinal(); commit(LeviathanAttack.TENDRIL_GRAB, target); } }
            case BITE_RELEASE -> { if (combat.ready()) commit(LeviathanAttack.PREDATORY_BITE, target); }
            // Playing is allowed to include a bluff, but not once the hunt has stopped being one:
            // past the halfway mark on the commit clock the feint becomes the bite it imitates.
            case FEINT -> { if (combat.ready()) commit(commitment() > 0.5f || lastWasFeint
                ? LeviathanAttack.PREDATORY_BITE : LeviathanAttack.FAKE_ATTACK, target); }
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
                if (stateTicks % 200 == 0) self.voice(HexGodOfStories.PILGRIM_STALK.get(), 48f, 0.85f);
            }
        }
    }

    @Nullable
    private Boat nearestBoat(Entity near) {
        AABB box = new AABB(near.position(), near.position()).inflate(24);
        return self.level().getEntitiesOfClass(Boat.class, box).stream().findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- ambience

    /**
     * Hexor's ambient voice: one clip, rarely, and never over itself.
     *
     * <p>It is close to seven seconds of very loud, and the fastest way to make something enormous
     * stop being frightening is to let it be heard on a schedule the player can predict, or often
     * enough to tune out. Two rules keep that from happening. Nothing starts while the last call is
     * still sounding, so copies can never stack into a drone; and the gap that follows is a minute
     * to three minutes, randomised, so the silence between calls is most of the experience and the
     * next one is never where it is expected. Distance and cover decide only how far it carries and
     * how low it is pitched — a call from across open water and a call from something already
     * beneath you are the same voice, heard differently.
     */
    private void ambience(ServerLevel level) {
        if (callPlaying > 0) { callPlaying--; return; }
        if (--callTimer > 0) return;
        RandomSource random = self.getRandom();
        boolean far = !hunt.hasTarget() || hunt.targetDistance() > 260;
        // Counted from the moment the clip finishes rather than from the moment it starts, so the
        // silence is the number written here and not that number minus seven seconds.
        roar(HexGodOfStories.HEXOR_AMBIENT.get(),
            far ? 150f : state.hidden() ? 72f : 100f,
            (far ? 0.58f : 0.74f) + random.nextFloat() * 0.16f,
            (far ? 1500 : 1100) + random.nextInt(far ? 2100 : 1700));
    }

    /**
     * Sounds the one voice the creature has, unless that voice is already sounding.
     *
     * <p>Everything else Hexor used to make a noise with is now the water reacting to it, so this
     * is the only thing it says and there are two places that can ask for it: the scheduled call,
     * and the moment it tips into frenzy. Both come through here, because seven seconds of roar
     * started on top of seven seconds of roar is two creatures rather than one, and the gap the
     * caller hands over is then counted from the end of the clip rather than the start of it.
     */
    private boolean roar(net.minecraft.sounds.SoundEvent voice, float volume, float pitch, int gap) {
        if (callPlaying > 0) return false;
        self.voice(voice, volume, pitch);
        callPlaying = HexGodOfStories.HEXOR_AMBIENT_TICKS;
        callTimer = gap;
        return true;
    }

    public void noteKill() {
        lastKill = self.level().getGameTime();
        frenzy = Math.max(0f, frenzy - 0.35f);
        patience = Math.min(1f, patience + 0.2f);
        pressure = 0;
        lastWasFeint = false;
        hunt.forget();
    }

    private Vec3 clampWater(Vec3 point) {
        return new Vec3(point.x, Mth.clamp(point.y, self.floorY() + 8, self.surfaceY() - 3), point.z);
    }

    // ---------------------------------------------------------------- persistence

    public void save(CompoundTag tag) {
        tag.putFloat("PilgrimPatience", patience);
        tag.putFloat("PilgrimFrenzyAi", frenzy);
        tag.putByte("PilgrimAiState", (byte) state.ordinal());
        tag.putInt("HexorPressure", pressure);
    }

    public void load(CompoundTag tag) {
        patience = tag.contains("PilgrimPatience") ? tag.getFloat("PilgrimPatience") : 0.7f;
        frenzy = tag.getFloat("PilgrimFrenzyAi");
        state = LeviathanState.byId(tag.getByte("PilgrimAiState"));
        pressure = Mth.clamp(tag.getInt("HexorPressure"), 0, PATIENCE_LIMIT);
    }
}
