package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.Discipline;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Complete Evisceration: The Deceiver's G, a tap.
 *
 * <p>The bearer dashes at the body they are looking at, the sword arm cocked back at the hip and the point straight
 * ahead, and when they reach it the arm is thrown out to its full length and the blade driven in at the gut and out
 * of the back, blood bursting out of both sides of it. The pierce is the same recording as Gravity Grasp's stab.
 *
 * <p>A body that lives through the thrust is held on the blade a moment, bleeding, and let off it as the blade is torn
 * out: {@link #DAMAGE} and {@link #BLEED_STACKS} stacks of bleeding. A body the thrust would kill is not killed by it
 * ({@link LastMoments#spare}): the blade is torn back out of it, the bearer steps back off it, and the sword comes down
 * from high on the left through it to the right hip, and that kills it and cuts it in two along the blade's line
 * ({@link #halve}: every client draws it as two halves, thrown apart and falling to the ground; client/Halving). Any
 * body, any size, any mod's.
 *
 * <p>Nothing reached at the end of the dash and the thrust goes into the air, with a short recovery. Otherwise the
 * whole recovery, {@link #RECOVERY}, runs from the press.
 */
public final class Evisceration {
    private Evisceration() {}

    /** Synced: until when Complete Evisceration is recovering. */
    public static final String READY = "eviscerationReady";
    public static final int RECOVERY = 200, WHIFF_RECOVERY = 60;
    /** How far off a body may be to be dashed at, and how nearly it must be looked at (1 - cosine). */
    private static final double SEEK = 9, AIM = .1;
    /** The dash: blocks a tick, and ticks at most. */
    private static final double SPEED = 1.05;
    private static final int DASH = 8;
    /**
     * How near a body's side the dash stops: at the thrust's full stretch the fist meets it, as a blade driven in to the
     * hilt, and the point comes out of the back (some seven tenths of a block out of a zombie).
     */
    private static final double REACH = 1.2;
    /** Ticks from the thrust's start to the blade going in (tools/blade_moves.py, blade_sword_evis_thrust's contact). */
    private static final int THRUST = 2;
    /** Lived through it: ticks after the blade goes in that it is torn out, and the move is over. */
    private static final int PULL = 9, THRUST_END = 18;
    /** Doomed: ticks it is held on the blade before the cut begins, the cut's contact (blade_sword_evis_cut), its end. */
    private static final int DOOMED_HOLD = 4, CUT_HIT = 6, CUT_END = 14;
    /** The thrust's damage (five hearts to an unarmoured body: a fair wound, not a kill) and the bleeding it leaves. */
    private static final float DAMAGE = 10;
    private static final int BLEED_STACKS = 2, BLEED = 120;
    /** The cut: lethal, whatever the body is wearing. */
    private static final float FINISH = 1e6f;
    /**
     * The line the cut parts a body on: through its middle at {@link #GUT} of its height, falling from the bearer's
     * upper left to their lower right at {@link #ANGLE} degrees, as the blade went (blade_moves.SWINGS).
     */
    public static final double GUT = .55, ANGLE = 50;
    private static final float BLOOD = 2.5f;

    private enum Phase {DASH, THRUST, IMPALED, CUT}

    private static final class Run {
        LivingEntity target;
        Phase phase = Phase.DASH;
        long since;
        Vec3 heading;
        boolean doomed, whiff;

        Run(LivingEntity target, long since, Vec3 heading) {
            this.target = target;
            this.since = since;
            this.heading = heading;
        }
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();
    private static boolean dealing;

    /** The thrust or the cut landing: it knocks nothing back (ServerEvents); the body stays on the blade. */
    public static boolean dealing() {return dealing;}

    public static boolean running(ServerPlayer p) {return RUNS.containsKey(p.getUUID());}

    /** Whether it is still recovering. A tick further off than its recovery could reach counts as ready. */
    public static boolean recovering(ServerPlayer p) {
        long left = HexData.get(p).getLong(READY) - HexData.now(p);
        return left > 0 && left <= RECOVERY;
    }

    /** G, with The Deceiver in hand. */
    public static void start(ServerPlayer p) {
        if (!holding(p)) return;
        if (!HexData.unlocked(p, Ability.TWIN_DAGGERS)) {HexServer.notice(p, "This chapter of your story is still locked."); return;}
        if (running(p) || BladeCombo.running(p) || FlameStream.pouring(p) || Arsenal.active(p) || GravityGrasp.holding(p) || ScepterBlast.stunned(p)) return;
        if (recovering(p)) {HexServer.notice(p, "Complete Evisceration is recovering."); return;}
        SwordGuard.end(p);
        long now = HexData.now(p);
        LivingEntity target = aimed(p);
        RUNS.put(p.getUUID(), new Run(target, now, forward(p)));
        HexData.get(p).putLong(READY, now + RECOVERY);
        HexNetwork.animate(p, "blade_sword_evis_dash");
        p.level().playSound(null, p.blockPosition(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, .9f, .62f);
        HexNetwork.sync(p);
    }

    /** Every tick of the bearer's. */
    public static void tick(ServerPlayer p) {
        Run run = RUNS.get(p.getUUID());
        if (run == null) return;
        long now = HexData.now(p);
        if (!p.isAlive() || p.isSpectator() || TemporalEngine.frozen(p) || !holding(p)) {end(p, run, true); return;}
        long t = now - run.since;
        switch (run.phase) {
            case DASH -> dash(p, run, now, t);
            case THRUST -> {if (t >= THRUST) impale(p, run, now);}
            case IMPALED -> impaled(p, run, now, t);
            case CUT -> {
                if (t == CUT_HIT) cut(p, run);
                else if (t >= CUT_END) end(p, run, false);
                else if (t < CUT_HIT && run.target != null && run.target.isAlive()) ScepterBlast.stun(run.target, CUT_HIT - (int) t + 3);
            }
        }
    }

    /** At the body, or as far as the dash goes: straight at it, a block a tick, until it is in reach. */
    private static void dash(ServerPlayer p, Run run, long now, long t) {
        if (run.target != null && !valid(p, run.target)) run.target = null;
        if (run.target == null) run.target = met(p, run.heading);
        LivingEntity target = run.target;
        if (target != null && gap(p, target) <= REACH || t >= DASH) {thrust(p, run, now); return;}
        if (target != null) {
            Vec3 to = target.position().subtract(p.position());
            to = new Vec3(to.x, 0, to.z);
            if (to.lengthSqr() > 1e-6) run.heading = to.normalize();
        }
        // Never further in a tick than the body is from reach, so the dash stops at it rather than in it.
        double step = target == null ? SPEED : Math.max(.25, Math.min(SPEED, gap(p, target) - REACH + .15));
        move(p, run.heading.scale(step), p.onGround() ? 0 : -.1);
    }

    /** The thrust begins: the dash stops, and a body that was reached is held where it stands, turned to the blade. */
    private static void thrust(ServerPlayer p, Run run, long now) {
        run.phase = Phase.THRUST;
        run.since = now;
        move(p, Vec3.ZERO, Math.min(0, p.getDeltaMovement().y));
        HexNetwork.animate(p, "blade_sword_evis_thrust");
        p.level().playSound(null, p.blockPosition(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, 1, .78f);
        LivingEntity target = run.target;
        if (target == null || gap(p, target) > REACH + 1) {run.target = null; return;}
        ScepterBlast.stun(target, THRUST + PULL + 4);
        face(target, p);
    }

    /** The blade goes in. Whether the body would die of it decides the rest. */
    private static void impale(ServerPlayer p, Run run, long now) {
        run.phase = Phase.IMPALED;
        run.since = now;
        LivingEntity target = run.target;
        if (target == null || !valid(p, target) || gap(p, target) > REACH + 1) {
            // Into the air: nothing on the blade, and only a short recovery.
            run.whiff = true;
            run.target = null;
            HexData.get(p).putLong(READY, now + WHIFF_RECOVERY);
            HexNetwork.sync(p);
            return;
        }
        target.invulnerableTime = 0;
        int landed;
        dealing = true;
        try {landed = LastMoments.spare(target, p.damageSources().playerAttack(p), DAMAGE + BladeFire.scorch(p, target));}
        finally {dealing = false;}
        if (landed == LastMoments.MISSED && target.isAlive()) {
            // Turned aside (a shield): nothing goes in, and it is over.
            run.whiff = true;
            run.target = null;
            p.level().playSound(null, target.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1, .8f);
            return;
        }
        run.doomed = landed == LastMoments.CAPPED || !target.isAlive();
        // A doomed body is not set alight: the fire would finish it before the cut does.
        if (!run.doomed) BladeFire.burn(p, target);
        ServerLevel level = p.serverLevel();
        Vec3 f = forward(p), gut = gut(target), front = gut.subtract(f.scale(target.getBbWidth() / 2)), back = gut.add(f.scale(target.getBbWidth() / 2));
        // Out of both wounds, up and down along the body, never across the blade's line: the blade, and its point out
        // of the back, stay in plain sight.
        BladeCombo.spray(level, target, front.add(0, .12, 0), f.scale(-.25).add(0, 1, 0).normalize(), BLOOD, p);
        BladeCombo.spray(level, target, front.add(0, -.12, 0), f.scale(-.15).add(0, -1, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, back.add(0, .12, 0), f.scale(.5).add(0, 1, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, back.add(0, -.12, 0), f.scale(.4).add(0, -1, 0).normalize(), BLOOD, null);
        skewered(level, target, front, back, PULL + (run.doomed ? 0 : 2));
        // The same recording as Gravity Grasp's stab, played as that is: twice at once, carried twice as far.
        for (int i = 0; i < 2; i++)
            level.playSound(null, target.getX(), gut.y, target.getZ(), HexGodOfStories.BLADE_PIERCE.get(), SoundSource.PLAYERS, 2, .94f);
        HexServer.reward(p, Discipline.CONJURATION, 90);
        if (run.doomed) {ScepterBlast.stun(target, DOOMED_HOLD + CUT_HIT + 4); return;}
        Bleed.apply(p, target, BLEED_STACKS, BLEED);
        ScepterBlast.stun(target, PULL + 2);
    }

    /** On the blade. A body that lived is let off it as the blade is torn out; a doomed one gets the cut. */
    private static void impaled(ServerPlayer p, Run run, long now, long t) {
        LivingEntity target = run.target;
        if (run.whiff) {if (t >= THRUST_END - THRUST) end(p, run, false); return;}
        if (target == null || target.isRemoved()) {end(p, run, false); return;}
        if (run.doomed) {
            if (t < DOOMED_HOLD) return;
            run.phase = Phase.CUT;
            run.since = now;
            HexNetwork.animate(p, "blade_sword_evis_cut");
            // Torn out, and a step back off it.
            Vec3 f = forward(p), front = gut(target).subtract(f.scale(target.getBbWidth() / 2));
            BladeCombo.spray(p.serverLevel(), target, front.add(0, .1, 0), f.scale(-.3).add(0, 1, 0).normalize(), BLOOD, null);
            BladeCombo.spray(p.serverLevel(), target, front.add(0, -.1, 0), f.scale(-.2).add(0, -1, 0).normalize(), BLOOD, null);
            p.level().playSound(null, target.blockPosition(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1, .5f);
            p.level().playSound(null, p.blockPosition(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, 1, .7f);
            move(p, f.scale(-.32), Math.min(0, p.getDeltaMovement().y));
            return;
        }
        if (t == PULL) {
            // Torn out of it: a gush out of both wounds, and it is let go, staggering off the blade.
            Vec3 f = forward(p), gut = gut(target);
            ServerLevel level = p.serverLevel();
            BladeCombo.spray(level, target, gut.subtract(f.scale(target.getBbWidth() / 2)), f.scale(-1).add(0, .2, 0).normalize(), BLOOD, null);
            BladeCombo.spray(level, target, gut.add(f.scale(target.getBbWidth() / 2)), f.add(0, -.2, 0).normalize(), BLOOD, null);
            level.playSound(null, target.blockPosition(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1, .55f);
            ScepterBlast.clear(target);
            if (target.isAlive()) {
                target.knockback(.7, p.getX() - target.getX(), p.getZ() - target.getZ());
                target.hurtMarked = true;
            }
        }
        if (t >= THRUST_END - THRUST) end(p, run, false);
    }

    /** The cut lands: the body dies of it, and comes apart in two along the blade's line. */
    private static void cut(ServerPlayer p, Run run) {
        LivingEntity target = run.target;
        if (target == null || target.isRemoved()) return;
        ServerLevel level = p.serverLevel();
        ScepterBlast.clear(target);
        // A player is not killed by it, not yet: five seconds as two halves on the ground, seeing it, able to do nothing
        // but look, and only then dead (tickHalved). A totem in hand still saves one, below, the ordinary way.
        boolean player = target instanceof ServerPlayer victim && victim.isAlive() && !totem(victim);
        if (player) {
            HALVED.put(target.getUUID(), new Halved(p.getUUID(), level.getGameTime() + PLAYER_LIES));
            target.setDeltaMovement(0, Math.min(0, target.getDeltaMovement().y), 0);
            target.hurtMarked = true;
        } else if (target.isAlive()) {
            target.invulnerableTime = 0;
            dealing = true;
            try {target.hurt(p.damageSources().playerAttack(p), FINISH);}
            finally {dealing = false;}
        }
        level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.2f, .6f);
        level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.2f, .7f);
        Vec3 line = line(p), gut = gut(target);
        // A body that lived even through that (a totem): only cut, and bleeding hard.
        if (target.isAlive() && !player) {
            BladeCombo.spray(level, target, gut, line, BLOOD, p);
            Bleed.apply(p, target, BLEED_STACKS + 2, BLEED);
            return;
        }
        halve(p, target);
        // Out of the whole length of the cut, every way: along it both ways, up out of it, on after the blade, down the
        // legs and back over the bearer.
        Vec3 n = normal(p), f = forward(p), r = right(p);
        double reach = Math.max(target.getBbWidth(), target.getBbHeight() * .5) * .4;
        BladeCombo.spray(level, target, gut, line, BLOOD, p);
        BladeCombo.spray(level, target, gut.add(line.scale(reach)), line.add(0, -.4, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut.subtract(line.scale(reach)), line.scale(-1).add(0, .6, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut, n.add(f.scale(.5)).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut, n.add(f.scale(-.4)).add(0, .5, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut, n.scale(-1).add(f.scale(.6)).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut.add(0, -target.getBbHeight() * .2, 0), f.add(0, -.2, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut.add(r.scale(target.getBbWidth() * .3)), r.add(0, .3, 0).normalize(), BLOOD, null);
        BladeCombo.spray(level, target, gut.subtract(r.scale(target.getBbWidth() * .3)), r.scale(-1).add(0, .3, 0).normalize(), BLOOD, null);
        level.playSound(null, target.blockPosition(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1.4f, .4f);
        level.playSound(null, target.blockPosition(), HexGodOfStories.SCEPTER_BURN.get(), SoundSource.PLAYERS, 1.2f, .7f);
        for (int i = 0; i < 2; i++)
            level.playSound(null, target.getX(), gut.y, target.getZ(), HexGodOfStories.BLADE_PIERCE.get(), SoundSource.PLAYERS, 2, 1.25f);
        HexServer.reward(p, Discipline.CONJURATION, 120);
        // A creature's own death is over at once, its loot and experience already dropped: no fall, no red flash, no puff
        // of smoke. Its halves are all that is left of it (the blood above is sent first, while the body is still there to
        // pool under). A boss keeps its own death, which may do more than die: the dragon's makes the way home.
        if (!player && !(target instanceof net.minecraft.world.entity.player.Player) && !target.isRemoved()
            && !target.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES))
            target.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
    }

    // ------------------------------------------------------------------ a player cut in two

    /** Ticks a player cut in two lies before dying of it; ticks a creature's halves lie (client Halving). */
    public static final int PLAYER_LIES = 100, CREATURE_LIES = 900;

    /** A player cut in two: who cut them, and when they die of it. */
    private record Halved(UUID by, long until) { }

    private static final Map<UUID, Halved> HALVED = new HashMap<>();
    private static boolean finishing;

    /** Whether this body is a player lying cut in two: nothing hurts it, and it does nothing. */
    public static boolean halved(net.minecraft.world.entity.Entity e) {return e instanceof ServerPlayer && HALVED.containsKey(e.getUUID());}

    /** The blow that finally kills a player cut in two is the one hurt that lands on them. */
    public static boolean finishing() {return finishing;}

    /** Every tick of every player, before anything else of theirs: held still, and dead when their time is up. */
    public static void tickHalved(ServerPlayer p) {
        Halved h = HALVED.get(p.getUUID());
        if (h == null) return;
        if (!p.isAlive()) {HALVED.remove(p.getUUID()); return;}
        p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
        if (p.level().getGameTime() >= h.until) die(p, h);
    }

    /** Dead of the cut, credited to whoever made it; nothing saves them now. */
    private static void die(ServerPlayer p, Halved h) {
        HALVED.remove(p.getUUID());
        ServerPlayer by = p.getServer() == null ? null : p.getServer().getPlayerList().getPlayer(h.by);
        p.invulnerableTime = 0;
        finishing = true;
        try {p.hurt(by != null ? p.damageSources().playerAttack(by) : p.damageSources().genericKill(), FINISH);}
        finally {finishing = false;}
        if (p.isAlive()) {
            finishing = true;
            try {p.kill();} finally {finishing = false;}
        }
    }

    /** Leaving while cut in two is dying of it, there and then. */
    public static void loggedOut(ServerPlayer p) {
        Halved h = HALVED.get(p.getUUID());
        if (h != null) die(p, h);
    }

    private static boolean totem(ServerPlayer p) {
        return p.getMainHandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
    }

    /**
     * Every client watching the body is told to cut it in two: the plane through it (a point on it from the body's
     * feet, and the side the upper piece is on), the way the blade went along it, and the bearer's forward, which the
     * pieces are thrown along.
     */
    private static void halve(ServerPlayer p, LivingEntity target) {
        Vec3 n = normal(p), l = line(p), f = forward(p);
        CompoundTag d = new CompoundTag();
        d.putDouble("oy", target.getBbHeight() * GUT);
        d.putDouble("nx", n.x); d.putDouble("ny", n.y); d.putDouble("nz", n.z);
        d.putDouble("lx", l.x); d.putDouble("ly", l.y); d.putDouble("lz", l.z);
        d.putDouble("fx", f.x); d.putDouble("fz", f.z);
        d.putDouble("x", target.getX()); d.putDouble("y", target.getY()); d.putDouble("z", target.getZ());
        d.putFloat("yaw", target.yBodyRot);
        // How long the halves lie: a player's until they die of it, a creature's for three quarters of a minute.
        d.putInt("lie", target instanceof ServerPlayer && HALVED.containsKey(target.getUUID()) ? PLAYER_LIES : CREATURE_LIES);
        // Pure blood red at the cut, unless the blade was burning: then it is seared, and cools as a Scepter hole does.
        d.putBoolean("burning", BladeFire.burning(p));
        d.putLong("start", target.level().getGameTime());
        HexNetwork.tracking(target, new HexNetwork.Message(HexNetwork.HALVE, target.getId(), d));
    }

    /**
     * Both wounds pumping while the body is on the blade (client Blood.skewer): running and spurting up and down the
     * body from where the blade goes in and where it comes out, nothing across its line.
     */
    private static void skewered(ServerLevel level, LivingEntity target, Vec3 front, Vec3 back, int ticks) {
        CompoundTag n = new CompoundTag();
        n.putString("state", "skewer");
        n.putInt("id", target.getId());
        n.putDouble("fx", front.x); n.putDouble("fy", front.y); n.putDouble("fz", front.z);
        n.putDouble("bx", back.x); n.putDouble("by", back.y); n.putDouble("bz", back.z);
        n.putInt("ticks", ticks);
        HexNetwork.near(level, target.position(), 64, new HexNetwork.Message(HexNetwork.ARSENAL, target.getId(), n));
    }

    private static void end(ServerPlayer p, Run run, boolean broken) {
        RUNS.remove(p.getUUID());
        if (run.target != null && run.target.isAlive() && run.phase != Phase.CUT) ScepterBlast.clear(run.target);
        if (broken) HexNetwork.animate(p, "__clear__");
    }

    /** A death, a logout, a crossing: nothing of it outlives them. */
    public static void forget(ServerPlayer p) {
        HALVED.remove(p.getUUID());
        Run run = RUNS.remove(p.getUUID());
        if (run != null && run.target != null && run.target.isAlive()) ScepterBlast.clear(run.target);
    }

    public static void reset() {RUNS.clear(); HALVED.clear();}

    /** The bearer moved by their own client: the velocity is sent straight to it. */
    private static void move(ServerPlayer p, Vec3 flat, double y) {
        p.setDeltaMovement(flat.x, y, flat.z);
        p.hurtMarked = true;
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    /** The body turned to face the one driving the blade into it. */
    private static void face(LivingEntity target, ServerPlayer p) {
        Vec3 to = p.position().subtract(target.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        target.setYRot(yaw);
        target.setYHeadRot(yaw);
        target.yBodyRot = yaw;
        target.yBodyRotO = yaw;
    }

    private static boolean valid(ServerPlayer p, LivingEntity e) {
        return e.isAlive() && !e.isRemoved() && e.level() == p.level() && HexServer.foe(p, e) && p.hasLineOfSight(e);
    }

    /** The body the bearer means: the one their look passes through, or failing that the nearest well inside it. */
    private static LivingEntity aimed(ServerPlayer p) {
        if (HexServer.target(p, SEEK) instanceof LivingEntity looked && valid(p, looked)) return looked;
        LivingEntity best = null;
        double nearest = Double.MAX_VALUE;
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(SEEK), e -> valid(p, e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            if (to.lengthSqr() > SEEK * SEEK || 1 - to.normalize().dot(look) > AIM) continue;
            if (to.lengthSqr() < nearest) {nearest = to.lengthSqr(); best = e;}
        }
        return best;
    }

    /** Whatever the dash runs into: any body within reach ahead of it. */
    private static LivingEntity met(ServerPlayer p, Vec3 heading) {
        LivingEntity best = null;
        double nearest = Double.MAX_VALUE;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(REACH + 1.5), e -> valid(p, e))) {
            Vec3 to = e.position().subtract(p.position());
            to = new Vec3(to.x, 0, to.z);
            double g = gap(p, e);
            if (g > REACH || to.lengthSqr() > 1e-6 && to.normalize().dot(heading) < .5) continue;
            if (g < nearest) {nearest = g; best = e;}
        }
        return best;
    }

    /** How far, level with the ground, the bearer's middle is from the nearest side of the body's box. */
    private static double gap(ServerPlayer p, LivingEntity e) {
        AABB box = e.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - p.getX(), 0), p.getX() - box.maxX);
        double dz = Math.max(Math.max(box.minZ - p.getZ(), 0), p.getZ() - box.maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Where the blade goes in: the body's middle, at its gut. */
    private static Vec3 gut(LivingEntity e) {return new Vec3(e.getX(), e.getY() + e.getBbHeight() * .55, e.getZ());}

    private static boolean holding(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        return held.getItem() instanceof ConjuredWeapon w && w.kind == 3 && ConjuredWeapon.belongsTo(held, p);
    }

    private static Vec3 forward(ServerPlayer p) {
        Vec3 look = p.getLookAngle(), flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The bearer's right, level with the ground. */
    private static Vec3 right(ServerPlayer p) {
        Vec3 f = forward(p);
        return new Vec3(-f.z, 0, f.x);
    }

    /** The way the cut goes: down from the bearer's upper left to their lower right. */
    private static Vec3 line(ServerPlayer p) {
        double a = Math.toRadians(ANGLE);
        return right(p).scale(Math.cos(a)).add(0, -Math.sin(a), 0);
    }

    /** Square to the cut and to the bearer's forward: the side of it the upper piece (the head) is on. */
    private static Vec3 normal(ServerPlayer p) {
        double a = Math.toRadians(ANGLE);
        return right(p).scale(Math.sin(a)).add(0, Math.cos(a), 0);
    }
}
