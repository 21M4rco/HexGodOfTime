package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.data.ScepterPose;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Scepter's right click, owned by the server.
 *
 * <p>A press starts holding the stone open; a release fires. Let go inside {@link #MIN_HOLD} ticks, a
 * second, and nothing fires at all: the stone has not taken yet, and needs no recovery. Held longer, it
 * fills over {@link #FULL_HOLD} ticks, ten seconds, from a quarter of its power to all of it, into a beam
 * that burns through up to six bodies; and the moment it is full it fires itself. It goes straight through
 * walls, {@link #WALLS} solid blocks of them after a second, rising to {@link #FULL_WALLS} the longer the
 * stone was held, and holes each one the way it holes a body: a cauterised hole that can be seen through, open
 * for a minute or more and then knitting shut. The blocks are never broken; the hole is only drawn, by
 * every client in sight ({@code BlockWounds}). Nothing it reaches explodes.
 *
 * <p>The beam does not break a body; it holds one. It hits lightly, and every body it passes through is
 * stunned, reeling and blind for a moment, slowed for {@link #SLOW} ticks after that, and keeps a
 * cauterised hole that bleeds a heart a second, through armour, for ten to thirty seconds by how long the
 * stone was held; see {@link Bleed#flow}.
 *
 * <p>Every shot leaves the stone recovering for ten to twenty seconds, again by how long it was held. It
 * smokes the whole time and takes no right click at all until it has cooled. The recovery is kept in the
 * player's saved data, like every other cooldown, so no death, relog or crossing cools it early, and
 * clients read it from that same data: that is how everyone watching sees the stone smoke.
 *
 * <p>Only a {@link #full} stone unmakes what it kills. A lesser beam that would kill a creature leaves it
 * standing on its last breath instead, holed and bleeding, until it dies the ordinary way; see
 * {@link LastMoments}.
 *
 * <p>Aim is always taken from the caster's eyes, so a shot lands on the crosshair; the stone's position
 * is only where the beam is drawn from. Since a full stone fires itself, no hold outlasts
 * {@link #FULL_HOLD} ticks, and a lost release can never leave it held forever.
 */
public final class ScepterBlast {
    private ScepterBlast() { }

    public static final double RANGE = 100;
    /** Solid blocks a shot passes through, the next one stopping it: nine for a tap, rising to sixteen for a full stone. */
    public static final int WALLS = 9, FULL_WALLS = 16;
    /** Ticks a press must be held for anything to fire: a second. */
    public static final int MIN_HOLD = 20;
    /** Ticks of holding for the stone to fill completely, at which it fires itself: ten seconds. */
    public static final int FULL_HOLD = 200;
    /** Recovery after a shot, in ticks: ten seconds for the least of them, rising to twenty for a full stone. */
    public static final int COOLDOWN = 200, FULL_COOLDOWN = 400;
    /** How long a hole bleeds, in ticks: ten seconds for a tap, rising to thirty for a full stone. */
    public static final int BLEED = 200, FULL_BLEED = 600;
    /** How long a hole lasts, in ticks, knitting shut included: a minute for a tap, rising to a minute and a half for a full stone. */
    public static final int HOLE_LIFE = 1200, FULL_HOLE_LIFE = 1800;
    /** The stun, in ticks: two seconds for a tap, rising to four for a full stone. */
    public static final int STUN = 40, FULL_STUN = 80;
    /** Nausea and blindness, whatever the charge: two seconds. */
    public static final int DAZE = 40;
    /** Slowness, whatever the charge: fifteen seconds, running on under the stun and past it. */
    public static final int SLOW = 300;
    /** Where the recovery lives in the player's saved data: the tick the stone has cooled. */
    public static final String READY = "scepterReady";
    /** A press this few ticks before the stone has cooled still counts: a client's clock can run a little ahead. */
    private static final int GRACE = 2;
    private static final int MAX_PIERCE = 6, MAX_STUNS = 192;

    /**
     * How hard a hold of {@code held} ticks fires: 0, nothing at all, short of {@link #MIN_HOLD}; from there a
     * quarter of full power, rising to all of it at {@link #FULL_HOLD}.
     */
    public static float power(long held) {
        return held < MIN_HOLD ? 0 : .25f + .75f * Mth.clamp((held - MIN_HOLD) / (float) (FULL_HOLD - MIN_HOLD), 0, 1);
    }

    /** A stone filled all the way: the only shot that unmakes what it kills. */
    public static boolean full(float power) {return power >= 1;}

    /** How far the hold went, 0 at a second to 1 for a full stone: what the recovery, bleed and stun scale with. */
    public static float fill(float power) {return power > 0 ? Mth.clamp((power - .25f) / .75f, 0, 1) : 0;}

    /** How far off the shot is heard: a volume past one carries further rather than louder. */
    public static float shotVolume(float power) {return 1.6f + .8f * power;}

    public static int cooldown(float power) {return COOLDOWN + Math.round((FULL_COOLDOWN - COOLDOWN) * fill(power));}

    private static int bleed(float power) {return BLEED + Math.round((FULL_BLEED - BLEED) * fill(power));}

    private static int stunFor(float power) {return STUN + Math.round((FULL_STUN - STUN) * fill(power));}

    /** How many solid blocks a shot goes through before the next one stops it. */
    private static int wallDepth(float power) {return WALLS + Math.round((FULL_WALLS - WALLS) * fill(power));}

    /** A heart for a tap, one and a half to three for a charged beam. The bleed does the rest. */
    private static float damage(float power) {return power > 0 ? 2 + 4 * power : 2;}

    private record Stun(LivingEntity victim, long until, boolean hadNoAi) { }
    private record Hit(LivingEntity victim, Vec3 at, double distance) { }

    private static final Map<UUID, Long> CHARGES = new HashMap<>();
    private static final Map<UUID, Stun> STUNS = new HashMap<>();

    private static boolean armed(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        return p.isAlive() && !p.isSpectator() && HexData.access(p) && !TemporalEngine.frozen(p) && !stunned(p)
            && held.getItem() instanceof ConjuredWeapon weapon && weapon.kind == 1 && ConjuredWeapon.belongsTo(held, p);
    }

    public static boolean charging(ServerPlayer p) {return CHARGES.containsKey(p.getUUID());}

    /**
     * The tick this caster's stone has cooled, already past once it has. A value further off than any
     * recovery could reach (player data carried over from a world with a later clock) counts as cooled,
     * so it can never lock the Scepter for good.
     */
    public static long ready(ServerPlayer p) {
        long ready = HexData.get(p).getLong(READY);
        return ready - HexData.now(p) > FULL_COOLDOWN ? 0 : ready;
    }

    public static void press(ServerPlayer p) {
        if (!armed(p) || CHARGES.containsKey(p.getUUID())) return;
        long now = HexData.now(p);
        long ready = ready(p);
        if (now < ready - GRACE) {
            // Still smoking from the last shot: no right click at all. The caster's own client is told
            // when the stone cools, so a press it let through on a stale view never shows as a charge.
            CompoundTag n = new CompoundTag();
            n.putString("state", "cooling");
            n.putLong("ready", ready);
            HexNetwork.to(p, new HexNetwork.Message(HexNetwork.SCEPTER, p.getId(), n));
            return;
        }
        CHARGES.put(p.getUUID(), now);
        CompoundTag n = new CompoundTag();
        n.putString("state", "charge");
        n.putLong("start", now);
        HexNetwork.tracking(p, new HexNetwork.Message(HexNetwork.SCEPTER, p.getId(), n));
    }

    public static void release(ServerPlayer p) {
        Long start = CHARGES.remove(p.getUUID());
        if (start == null) return;
        if (!armed(p)) {cancelled(p); return;}
        long held = HexData.now(p) - start;
        // Short of a second the stone has not taken: nothing fires, and there is nothing to recover from.
        if (held < MIN_HOLD) {cancelled(p); return;}
        fire(p, power(held));
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
        Long start = CHARGES.get(p.getUUID());
        if (start == null) return;
        if (!armed(p)) {cancel(p); return;}
        // Full: it fires itself, the tick it fills.
        if (HexData.now(p) - start >= FULL_HOLD) release(p);
    }

    /** A death, a logout or a crossing drops the hold; the recovery stays in the player's data. */
    public static void forget(ServerPlayer p) {
        CHARGES.remove(p.getUUID());
    }

    static void fire(ServerPlayer caster, float power) {
        ServerLevel level = caster.serverLevel();
        boolean charged = power > 0;
        long ready = HexData.now(caster) + cooldown(power);
        HexData.get(caster).putLong(READY, ready);
        Vec3 eye = caster.getEyePosition();
        Vec3 direction = caster.getLookAngle().normalize();
        Vec3 muzzle = ScepterPose.stoneMuzzle(caster);
        // Straight through walls: nine solid blocks for a tap, up to sixteen for a full stone, and the next stops it.
        List<Wall> walls = new ArrayList<>();
        Stop end = stop(level, eye, eye.add(direction.scale(RANGE)), caster, wallDepth(power), walls);
        Vec3 stop = end.at();

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
        // A bolt stops in the first body; a charged beam stops in the last one it had strength for,
        // unless it came out the far side of every body in its path. Nothing it stops in explodes.
        boolean inBody = !struck.isEmpty() && (!charged || hits.size() > through);
        if (inBody) impact = struck.get(struck.size() - 1).at;

        for (Hit hit : struck) strike(caster, hit, direction, power);
        // Every wall it crossed before it stopped is holed the way a body is, and for as long. Past the
        // body it stopped in, none.
        double reach = eye.distanceToSqr(impact);
        long[] holed = walls.stream().filter(wall -> wall.distance < reach).mapToLong(wall -> wall.pos.asLong()).toArray();
        // Every hole it leaves crackles hot for as long as it glows: each client hears that from the hole it draws.

        // Everyone near hears the shot from the stone; the caster already heard it on release.
        level.playSound(caster, muzzle.x, muzzle.y, muzzle.z, HexGodOfStories.SCEPTER_SHOT.get(), SoundSource.PLAYERS, shotVolume(power),
            .97f + caster.getRandom().nextFloat() * .06f);

        CompoundTag state = new CompoundTag();
        state.putString("state", "shot");
        state.putFloat("power", power);
        // When the stone cools, so every client watching starts it smoking on this very shot.
        state.putLong("ready", ready);
        // No arm clip: in third person the staff stays in its low carry, whatever the caster looks at.
        HexNetwork.tracking(caster, new HexNetwork.Message(HexNetwork.SCEPTER, caster.getId(), state));

        CompoundTag fx = new CompoundTag();
        fx.putString("effect", "scepter_blast");
        fx.putDouble("x", muzzle.x); fx.putDouble("y", muzzle.y); fx.putDouble("z", muzzle.z);
        fx.putDouble("tx", impact.x); fx.putDouble("ty", impact.y); fx.putDouble("tz", impact.z);
        fx.putFloat("power", power);
        ListTag pierced = new ListTag();
        for (Hit hit : struck) {
            pierced.add(DoubleTag.valueOf(hit.at.x));
            pierced.add(DoubleTag.valueOf(hit.at.y));
            pierced.add(DoubleTag.valueOf(hit.at.z));
        }
        fx.put("through", pierced);
        // The holes are drawn by each client, down the same line the walls were found on: through the walls, and
        // through whatever soft or hanging things were in its way, which never stop it and so are never among them.
        fx.putDouble("ex", eye.x); fx.putDouble("ey", eye.y); fx.putDouble("ez", eye.z);
        fx.putDouble("dx", direction.x); fx.putDouble("dy", direction.y); fx.putDouble("dz", direction.z);
        fx.putFloat("hr", holeRadius(power));
        fx.putLong("hs", level.getGameTime());
        fx.putInt("hl", holeLife(power));
        if (holed.length > 0) {
            fx.putLongArray("walls", holed);
            // The block that stopped it is not holed, only met: the tunnel ends against it.
            if (!inBody && end.block() != null) fx.putLong("stop", end.block().asLong());
        }
        HexNetwork.near(level, muzzle, 160, new HexNetwork.Message(HexNetwork.FX, caster.getId(), fx));
    }

    /** A body the beam passes through: lightly burned, holed, thrown, stunned and reeling, then slowed and bleeding a long while. */
    private static void strike(ServerPlayer caster, Hit hit, Vec3 direction, float power) {
        LivingEntity victim = hit.victim;
        boolean charged = power > 0;
        // Vanilla's hurt immunity would swallow a light shot that follows a heavier blow; this one lands.
        victim.invulnerableTime = 0;
        var source = caster.damageSources().indirectMagic(caster, caster);
        float damage = damage(power);
        // Anything short of a full stone that would kill a creature leaves it on its feet instead.
        boolean held = false;
        if (full(power)) victim.hurt(source, damage);
        else held = LastMoments.hurt(victim, source, damage);
        victim.level().playSound(null, hit.at.x, hit.at.y, hit.at.z, HexGodOfStories.SCEPTER_BURN.get(), SoundSource.PLAYERS,
            1.2f, .9f + victim.getRandom().nextFloat() * .25f);
        if (victim.isDeadOrDying()) {if (full(power)) dissolve(victim, direction, power); return;}
        // A clean hole that stays open for a minute or more before it knits shut. Bleed, never fire.
        BeamWound.open(victim, hit.at, direction, holeRadius(power), holeLife(power));
        // Held on its last breath: it stays exactly where it was hit, bleeding, until it falls.
        if (held) {LastMoments.hold(caster, victim); return;}
        // The hole pours a heart a second, through armour, for as long as the stone was held.
        Bleed.flow(caster, victim, bleed(power));
        Vec3 push = direction.scale(charged ? 1.1 + 2.6 * power : .5).add(0, charged ? .3 + .4 * power : .16, 0);
        victim.setDeltaMovement(victim.getDeltaMovement().add(push));
        victim.hurtMarked = true;
        stun(victim, stunFor(power));
        daze(victim);
        // Slowness I: the stun's own slowdown is stronger and shorter, so this waits under it and then runs on.
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW, 0, false, false, true));
    }

    /** Two seconds of the world reeling and going dark, whatever the charge. */
    private static void daze(LivingEntity victim) {
        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, DAZE, 0, false, false, true));
        victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DAZE, 0, false, false, true));
        if (!(victim instanceof ServerPlayer player)) return;
        // Vanilla never draws nausea this short, so the victim's own client is told to draw it; sent
        // after the effect, so it arrives to find the nausea already there. See ScepterClient.sway.
        CompoundTag n = new CompoundTag();
        n.putString("state", "dazed");
        n.putLong("until", victim.level().getGameTime() + DAZE);
        HexNetwork.to(player, new HexNetwork.Message(HexNetwork.SCEPTER, player.getId(), n));
    }

    /**
     * A body a full-charge beam kills comes apart the way Time Branch Unleashing unmakes one, only far
     * faster: the same fracturing surface, dust and threads, run inside vanilla's twenty-tick death so
     * the last fragment goes as the body does. Bosses keep their own deaths.
     */
    static void dissolve(LivingEntity victim, Vec3 direction, float power) {
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

    /** A solid block a shot passed through, and the squared distance from the eye at which it went in. */
    private record Wall(BlockPos pos, double distance) { }

    /** Where a shot ends, and the block that ended it, when one did. */
    private record Stop(Vec3 at, BlockPos block) { }

    /**
     * Where a shot from {@code from} toward {@code to} is stopped, collecting every wall it passed through
     * on the way into {@code walls}. It stops where it enters the solid block after the {@code most}th,
     * at any block nothing can break (bedrock, a barrier), at the edge of the loaded world, or nowhere
     * short of {@code to}. Every block whose collision shape the line actually crosses counts once, slabs
     * and panes included; nothing that can be walked through counts, fluids among it.
     */
    private static Stop stop(ServerLevel level, Vec3 from, Vec3 to, ServerPlayer caster, int most, List<Wall> walls) {
        BlockPos[] stopper = new BlockPos[1];
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster);
        Vec3 stopped = BlockGetter.traverseBlocks(from, to, context, (c, pos) -> {
            // Never load a chunk to find out what is in it: the shot ends where the loaded world does.
            if (!level.hasChunkAt(pos)) {
                BlockHitResult edge = Shapes.block().clip(from, to, pos);
                return edge == null ? from : edge.getLocation();
            }
            BlockState state = level.getBlockState(pos);
            BlockHitResult hit = c.getBlockShape(state, level, pos).clip(from, to, pos);
            if (hit == null) return null;
            if (walls.size() >= most || state.getDestroySpeed(level, pos) < 0) {
                stopper[0] = pos.immutable();
                return hit.getLocation();
            }
            walls.add(new Wall(pos.immutable(), from.distanceToSqr(hit.getLocation())));
            return null;
        }, c -> null);
        return new Stop(stopped == null ? to : stopped, stopper[0]);
    }

    /** How long a hole stays open, in a body or a wall, knitting shut included: a minute for a tap, rising to a minute and a half for a full stone. */
    private static int holeLife(float power) {return HOLE_LIFE + Math.round((FULL_HOLE_LIFE - HOLE_LIFE) * fill(power));}

    /** How wide a hole is, in a body or a wall: a hand across for a tap, wider the more the stone was held. */
    private static float holeRadius(float power) {return power > 0 ? .20f + .14f * power : .15f;}

    /**
     * Marks a mob this stun switched NoAI on for. NoAI is saved with the mob, so one unloaded or saved
     * mid-stun would otherwise come back stunned for good; loading it with this tag undoes that.
     */
    static final String STUN_TAG = "hexgodofstoriesStunned";

    static void stun(LivingEntity victim, int ticks) {
        if (STUNS.size() >= MAX_STUNS && !STUNS.containsKey(victim.getUUID())) return;
        Stun old = STUNS.get(victim.getUUID());
        boolean hadNoAi = old != null ? old.hadNoAi : victim instanceof Mob mob && mob.isNoAi();
        long until = Math.max(old == null ? 0 : old.until, victim.level().getGameTime() + ticks);
        STUNS.put(victim.getUUID(), new Stun(victim, until, hadNoAi));
        if (victim instanceof Mob mob) {
            mob.setNoAi(true);
            if (!hadNoAi) victim.getPersistentData().putBoolean(STUN_TAG, true);
        }
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 255, false, false, true));
        victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 255, false, false, true));
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
                release(victim, stun);
                CompoundTag state = new CompoundTag();
                state.putLong("until", 0);
                if (!victim.isRemoved()) HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.STUN, victim.getId(), state));
            }
        }
        LastMoments.tick(level);
    }

    private static void release(LivingEntity victim, Stun stun) {
        if (victim instanceof Mob mob && !stun.hadNoAi) mob.setNoAi(false);
        victim.getPersistentData().remove(STUN_TAG);
    }

    public static void clear(LivingEntity victim) {
        Stun stun = STUNS.remove(victim.getUUID());
        if (stun != null) release(victim, stun);
    }

    /** A mob loaded with the stun tag was saved mid-stun. The stun did not survive the save, so neither does its NoAI. */
    public static void loaded(LivingEntity victim) {
        if (!victim.getPersistentData().getBoolean(STUN_TAG) || STUNS.containsKey(victim.getUUID())) return;
        victim.getPersistentData().remove(STUN_TAG);
        if (victim instanceof Mob mob) mob.setNoAi(false);
    }

    public static void track(ServerPlayer viewer, net.minecraft.world.entity.Entity entity) {
        if (entity instanceof LivingEntity living && stunned(living)) {
            CompoundTag state = new CompoundTag();
            state.putLong("until", STUNS.get(living.getUUID()).until);
            HexNetwork.to(viewer, new HexNetwork.Message(HexNetwork.STUN, entity.getId(), state));
        }
        if (entity instanceof LivingEntity living) BeamWound.track(viewer, living);
        LastMoments.track(viewer, entity);
    }

    public static void reset() {
        for (Stun stun : STUNS.values()) release(stun.victim, stun);
        STUNS.clear();
        CHARGES.clear();
        BeamWound.reset();
        LastMoments.reset();
    }
}
