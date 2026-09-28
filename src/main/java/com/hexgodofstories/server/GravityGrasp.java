package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gravity Grasp, Anchor Being's held alternate. The caster stretches an arm out and a small black hole opens in front
 * of the hand, drawing in every body the caster could harm within {@link #REACH} blocks, harder the longer it is held,
 * for up to {@link #MOST} ticks. Whatever it drags to within arm's reach the caster cuts, once, with a dagger conjured
 * for the stroke: five hearts and ten seconds' bleeding.
 *
 * <p>Everything here is the server's: who is pulled, how hard, and who is cut. Clients are told only that the caster
 * is holding it (player data, {@code graspStart}) and when a dagger is out ({@code graspDagger}), and draw the rest.
 * Bounded: {@link #MOST_HELD} bodies at most, one bounded query a tick for each caster holding it.
 */
public final class GravityGrasp {
    private GravityGrasp() { }

    /**
     * The longest hold, the reach of the pull, and its least and greatest strength, in blocks a tick added each tick. At
     * the least a player walking away outpaces it; a couple of seconds in, not even sprinting does.
     */
    public static final int MOST = 200;
    static final double REACH = 14, PULL_LEAST = .012, PULL_MOST = .12;
    /** Where the hole hangs, out from the eye; and how close a body must come to be cut. */
    static final double HOLE = 1.5, MELEE = 2.7;
    /** The cut: five hearts, bleeding for ten seconds, and how long the dagger is seen. */
    static final float SLASH = 10;
    static final int BLEED = 200, BLEED_STACKS = 2, DAGGER = 14;
    /** Recovery once let go, and where it lives; bodies pulled at once, at most. */
    public static final int RECOVERY = 240;
    public static final String READY = "graspReady", HOLDING = "graspStart", KNIFE = "graspDagger";
    static final int MOST_HELD = 12;

    private static final class Hold {
        final long start;
        final Set<Integer> cut = new HashSet<>();
        long slashEnds = -1;

        Hold(long start) {this.start = start;}
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    public static boolean holding(ServerPlayer p) {return HOLDS.containsKey(p.getUUID());}

    /** Whether it is still recovering. A tick further off than its recovery could reach (a moved world clock) counts as ready. */
    public static boolean recovering(ServerPlayer p) {
        long left = HexData.get(p).getLong(READY) - HexData.now(p);
        return left > 0 && left <= RECOVERY;
    }

    public static boolean begin(ServerPlayer p) {
        if (holding(p) || recovering(p) || p.isSpectator() || !p.isAlive()) return false;
        long now = HexData.now(p);
        HOLDS.put(p.getUUID(), new Hold(now));
        HexData.get(p).putLong(HOLDING, now);
        HexNetwork.animate(p, "telekinesis");
        p.level().playSound(null, p.blockPosition(), HexGodOfStories.GRIP_HOLD.get(), SoundSource.PLAYERS, .8f, .5f);
        HexNetwork.sync(p);
        return true;
    }

    /** Let go: the hole closes, and the recovery starts. */
    public static void release(ServerPlayer p) {
        if (HOLDS.remove(p.getUUID()) == null) return;
        CompoundTag d = HexData.get(p);
        d.remove(HOLDING);
        d.remove(KNIFE);
        d.putLong(READY, HexData.now(p) + RECOVERY);
        HexNetwork.animate(p, "__clear__");
        HexNetwork.sync(p);
    }

    /** A death, a logout or a crossing: dropped without a recovery being owed for it. */
    public static void forget(ServerPlayer p) {
        if (HOLDS.remove(p.getUUID()) == null) return;
        HexData.get(p).remove(HOLDING);
        HexData.get(p).remove(KNIFE);
    }

    public static void reset() {HOLDS.clear();}

    /** The hole's place in the world, just out in front of the caster's hand. */
    public static Vec3 hole(ServerPlayer p) {return p.getEyePosition().add(p.getLookAngle().scale(HOLE)).add(0, -.3, 0);}

    /** Every tick of a caster's. */
    public static void tick(ServerPlayer p) {
        Hold hold = HOLDS.get(p.getUUID());
        if (hold == null) return;
        long now = HexData.now(p), held = now - hold.start;
        if (!p.isAlive() || TemporalEngine.frozen(p) || ScepterBlast.stunned(p) || held >= MOST) {release(p); return;}
        if (hold.slashEnds >= 0 && now >= hold.slashEnds) {
            // The stroke is done: the arm goes back out to the hole.
            hold.slashEnds = -1;
            HexData.get(p).remove(KNIFE);
            HexNetwork.animate(p, "telekinesis");
            HexNetwork.sync(p);
        }
        Vec3 hole = hole(p);
        double strength = PULL_LEAST + (PULL_MOST - PULL_LEAST) * Math.min(1, held / (double) MOST);
        List<Entity> near = p.level().getEntities(p, new AABB(hole, hole).inflate(REACH), e -> e instanceof LivingEntity && HexServer.validTarget(p, e));
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(hole)));
        for (int i = 0; i < Math.min(MOST_HELD, near.size()); i++) {
            LivingEntity body = (LivingEntity) near.get(i);
            Vec3 middle = body.getBoundingBox().getCenter(), to = hole.subtract(middle);
            double distance = to.length();
            if (distance > REACH) continue;
            if (p.distanceTo(body) - body.getBbWidth() / 2 <= MELEE && !hold.cut.contains(body.getId())) {slash(p, hold, body, now); return;}
            // The bigger the body, the harder it is to drag; right at the hole it is held there rather than overshooting it.
            double mass = Math.max(1, body.getBbWidth() * body.getBbWidth() * body.getBbHeight() / 1.2);
            Vec3 velocity = body.getDeltaMovement().scale(distance < 1.2 ? .4 : .92);
            if (distance > .6) velocity = velocity.add(to.scale(strength / mass / distance));
            double speed = velocity.length();
            if (speed > 1.4) velocity = velocity.scale(1.4 / speed);
            body.setDeltaMovement(velocity);
            body.fallDistance = 0;
            body.hurtMarked = true;
            if (body instanceof ServerPlayer target) target.connection.send(new ClientboundSetEntityMotionPacket(target));
        }
    }

    /** How long the one stabbed is stunned. */
    static final int STAB_STUN = 50;

    /**
     * Drawn within arm's reach: the hole closes, a dagger is conjured and driven into its gut. Five hearts, ten
     * seconds' bleeding, and it is stunned where it stands. That ends the grasp.
     */
    private static void slash(ServerPlayer p, Hold hold, LivingEntity body, long now) {
        HOLDS.remove(p.getUUID());
        body.setDeltaMovement(Vec3.ZERO);
        body.hurtMarked = true;
        body.invulnerableTime = 0;
        body.hurt(p.damageSources().playerAttack(p), SLASH);
        if (body.isAlive()) {
            Bleed.apply(p, body, BLEED_STACKS, BLEED);
            ScepterBlast.stun(body, STAB_STUN);
        }
        HexNetwork.fx(body, "impact");
        p.level().playSound(null, body.getX(), body.getY(), body.getZ(), HexGodOfStories.BLADE_SWING.get(), SoundSource.PLAYERS, 1, .8f);
        CompoundTag d = HexData.get(p);
        d.remove(HOLDING);
        d.putLong(KNIFE, now + DAGGER);
        d.putLong(READY, now + RECOVERY);
        HexNetwork.animate(p, "grasp_slash");
        HexNetwork.sync(p);
    }
}
