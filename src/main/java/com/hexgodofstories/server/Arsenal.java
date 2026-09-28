package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.ArsenalLayout;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Crown of Barrels: Loki's last conjuration, and the only one that puts nothing in the hand.
 *
 * <p>Held. Both arms go up, and machine guns form one after another in an arch over the caster, the top one first
 * and then outward down both sides; formed, they fire at whatever the caster looks at, turning to follow the look,
 * each in its own ragged bursts and spraying about the mark its own way, for as long as the key is held, until the
 * missiles are called at thirteen seconds. Let go before that and the guns simply come apart. Hold it to the end
 * and the arms are thrown down, and two missiles that formed beside the head fly, slowly and never straight, to
 * the point aimed at, and blow a crater there that knits itself back together a block at a time.
 *
 * <p>Nothing is an entity and nothing is an item. The guns exist only on the clients, who draw them from {@link
 * ArsenalLayout}'s numbers; the rounds are the server's own instant rays, from the same muzzles to the same mark;
 * the missiles fly the same {@link ArsenalLayout.Flight} here and on every client, with a single message at launch
 * and a single message where each lands. The whole of it costs the server a handful of rays a tick.
 *
 * <p>Damage. The rounds hold; the missiles kill. A round stuns for a second, begun again by every round after it,
 * so a body kept under fire is held for as long as it is, and one still held when the fire ends stays held until the
 * missiles are there. A round goes through every body in its way (up to {@link #PIERCE}) but never through a block,
 * and stings for a twentieth of a heart; each second a body is under fire it takes a tiny hole where a round went
 * in, and bleeds a little from it. A body takes at most one round a tick however many are aimed at it, dealt four
 * ticks' worth at a time so that it is not made to cry out twenty times a second, and never knocked about by it.
 * A missile is forty hearts to the body it strikes, thirty to anything else within five blocks of where it bursts,
 * less to the edge of its nine, and sets it burning.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class Arsenal {
    private Arsenal() { }

    /** A round: a twentieth of a heart. The rounds hold a body; the missiles are what kill it. */
    static final float ROUND = .1f;
    /** Ticks a body's owed rounds are gathered over before they are dealt. */
    static final int DEAL_EVERY = 4;
    /** A round's stun, in ticks: a second, begun again by every round after it. */
    static final int STUN = 20;
    /** Bodies one round goes through, at most; a block always stops it. */
    static final int PIERCE = 4;
    /** A round's hole: its radius, far smaller than the Scepter's least, how long it stays open, and how often a body under fire is holed afresh. */
    static final float HOLE = .05f;
    static final int HOLE_LIFE = 600, HOLE_EVERY = 20;
    /** The bleeding the holes leave: stacks at most, and the ticks it runs on after the latest hole. */
    static final int BLEED_STACKS = 2, BLEED = 60;
    /** How far the crown sees to aim, and how far the missiles are thrown when there is nothing to see. */
    static final double REACH = 128, THROW_REACH = 90, NEAREST_MARK = 6;
    /**
     * A missile: forty hearts to the body it strikes, its blast to everything else, the reach of that blast at full
     * strength and at all, its burn, and its crater's power.
     */
    static final float DIRECT = 80, BLAST = 60, BURN_SECONDS = 8, CRATER = 6;
    static final double BLAST_CORE = 5, BLAST_REACH = 9;
    /** How long a crater stays open before the first block comes back, and over how long the rest follow: Paradise's own. */
    static final int KNIT_DELAY = 170, KNIT_SCATTER = 190;
    /** Recovery after the crown ends: the whole of it, or a short one when let go before a round was fired. */
    static final int SHORT_RECOVERY = 200;
    /** The longest a body still held when the fire ends is kept held for the missiles, in ticks from their launch. */
    static final int PIN_MOST = 120;
    /** Gotcha!: its one round, ten hearts; its hole, bigger than a round's; and the ticks its hole pours, five seconds. */
    static final float GOTCHA = 20, GOTCHA_HOLE = .12f;
    static final int GOTCHA_BLEED = 100;

    private static final class Crown {
        final long start;
        /** The mark this tick, for the rounds; recomputed every tick the crown fires. */
        Vec3 mark;
        /** Rounds owed to each body, by entity id, and when they were last dealt. */
        final Map<Integer, Float> owed = new HashMap<>();
        final Map<Integer, Long> dealt = new HashMap<>();
        final Map<Integer, Long> struck = new HashMap<>();
        /** Where the latest round went into each body and which way, for its hole; and when each was last holed. */
        final Map<Integer, Vec3[]> entry = new HashMap<>();
        final Map<Integer, Long> holed = new HashMap<>();
        /** The bodies the fire was still holding when it ended: held on until the missiles are there. */
        final List<Integer> pinned = new ArrayList<>();

        Crown(long start) {this.start = start;}
    }

    private static final class Missile {
        final ServerLevel level;
        final UUID caster;
        final int id, casterId;
        final ArsenalLayout.Flight flight;
        final long launch;
        Vec3 last;

        Missile(ServerLevel level, ServerPlayer caster, int id, ArsenalLayout.Flight flight, long launch) {
            this.level = level;
            this.caster = caster.getUUID();
            this.casterId = caster.getId();
            this.id = id;
            this.flight = flight;
            this.launch = launch;
            this.last = vec(flight.at(0));
        }
    }

    /** A body a round went into, where, and how far along the round (squared). */
    private record Hit(Entity body, Vec3 at, double distance) { }

    /** Gotcha!'s gun: where it hangs, the body it is on the back of, and when it began to form. */
    private static final class Sneak {
        final ServerLevel level;
        final UUID caster;
        final int id, target;
        final Vec3 at;
        final long start;

        Sneak(ServerLevel level, UUID caster, int id, int target, Vec3 at, long start) {
            this.level = level;
            this.caster = caster;
            this.id = id;
            this.target = target;
            this.at = at;
            this.start = start;
        }
    }

    private static final Map<UUID, Crown> CROWNS = new HashMap<>();
    private static final List<Missile> MISSILES = new ArrayList<>();
    private static final List<Sneak> SNEAKS = new ArrayList<>();
    private static int nextMissile, nextSneak;
    /** True only while one of the crown's own rounds is being dealt, so that it knocks nobody about. */
    private static boolean striking;

    public static boolean active(ServerPlayer p) {return CROWNS.containsKey(p.getUUID());}

    /** The hold begins. Returns whether it did, for the cast to be paid for. */
    public static boolean begin(ServerPlayer p) {
        if (active(p) || p.isPassenger() || p.isSpectator()) return false;
        long now = HexData.now(p);
        CROWNS.put(p.getUUID(), new Crown(now));
        CompoundTag d = HexData.get(p);
        d.putLong("arsenalStart", now);
        d.putLong("arsenalEnd", 0);
        d.putString("arsenalEnding", "");
        HexNetwork.animate(p, "arsenal_raise");
        state(p, "begin", now);
        HexNetwork.sync(p);
        return true;
    }

    /** The key is let go. Before the end of the fire that ends it; after, the throw is already coming and nothing changes. */
    public static void release(ServerPlayer p) {
        Crown crown = CROWNS.get(p.getUUID());
        if (crown == null) return;
        long now = HexData.now(p);
        if (now - crown.start >= ArsenalLayout.FIRE_END) return;
        end(p, crown, now, "cancel");
    }

    /** Ends the crown now, whatever it was doing: a death, a crossing, a logout. */
    public static void forget(ServerPlayer p) {
        Crown crown = CROWNS.get(p.getUUID());
        if (crown != null) end(p, crown, HexData.now(p), "cancel");
    }

    public static void reset() {
        CROWNS.clear();
        MISSILES.clear();
        SNEAKS.clear();
    }

    private static void end(ServerPlayer p, Crown crown, long now, String how) {
        settle(p, crown, true);
        CROWNS.remove(p.getUUID());
        CompoundTag d = HexData.get(p);
        d.putLong("arsenalEnd", now);
        d.putString("arsenalEnding", how);
        boolean fired = now - crown.start > ArsenalLayout.FIRE_START;
        d.putLong("cd_" + Ability.ARSENAL.name(), now + (fired ? Ability.ARSENAL.cooldown : SHORT_RECOVERY));
        if (how.equals("cancel")) HexNetwork.animate(p, "__clear__");
        state(p, how, now);
        HexNetwork.sync(p);
    }

    private static void state(ServerPlayer p, String state, long at) {
        CompoundTag n = new CompoundTag();
        n.putString("state", state);
        n.putLong("at", at);
        CompoundTag d = HexData.get(p);
        n.putLong("start", d.getLong("arsenalStart"));
        HexNetwork.tracking(p, new HexNetwork.Message(HexNetwork.ARSENAL, p.getId(), n));
    }

    /** Every tick of a caster's: the rounds while the crown fires, and its end, whichever end it comes to. */
    public static void tick(ServerPlayer p) {
        Crown crown = CROWNS.get(p.getUUID());
        if (crown == null) return;
        long now = HexData.now(p);
        int t = (int) (now - crown.start);
        if (!p.isAlive() || TemporalEngine.frozen(p) || p.isPassenger()) {
            end(p, crown, now, "cancel");
            return;
        }
        if (t >= ArsenalLayout.FIRE_START && t < ArsenalLayout.FIRE_END) fire(p, crown, t, now);
        if (t == ArsenalLayout.FIRE_END) {
            settle(p, crown, true);
            // The fire leads up to the missiles: whatever it was still holding stays held while they form.
            for (Map.Entry<Integer, Long> s : crown.struck.entrySet())
                if (now - s.getValue() <= STUN && p.serverLevel().getEntity(s.getKey()) instanceof LivingEntity body && body.isAlive()) {
                    crown.pinned.add(s.getKey());
                    ScepterBlast.stun(body, ArsenalLayout.LAUNCH - ArsenalLayout.FIRE_END + 2);
                }
            HexData.get(p).putString("arsenalEnding", "finale");
            state(p, "finale", now);
        }
        if (t == ArsenalLayout.THROW) HexNetwork.animate(p, "arsenal_throw");
        if (t >= ArsenalLayout.LAUNCH) {
            launch(p, crown, now);
            end(p, crown, now, "launched");
            return;
        }
        settle(p, crown, false);
    }

    // ------------------------------------------------------------------ the rounds

    /**
     * The point the caster is looking at: where the look first meets a body or a block, or as far as the crown sees.
     * The very point on the body, not its feet: the barrels converge where the crosshair is.
     */
    static Vec3 mark(ServerPlayer p, double reach) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(reach));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        double nearest = eye.distanceToSqr(stop);
        for (Entity e : p.level().getEntities(p, new AABB(eye, stop).inflate(1), e -> e instanceof LivingEntity && HexServer.validTarget(p, e))) {
            var hit = e.getBoundingBox().inflate(.3).clip(eye, stop);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < nearest) {
                nearest = eye.distanceToSqr(hit.get());
                stop = hit.get();
            }
        }
        return stop;
    }

    /** Body frame to world: x to the caster's right, y up from their feet, z the way their body faces. */
    private static Vec3 world(ServerPlayer p, double[] v) {
        float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        // Facing south, a right hand points west: right = forward x up.
        double rx = -fz, rz = fx;
        return new Vec3(p.getX() + v[0] * rx + v[2] * fx, p.getY() + v[1], p.getZ() + v[0] * rz + v[2] * fz);
    }

    /** World to the body frame. */
    private static double[] body(ServerPlayer p, Vec3 w) {
        float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), rx = -fz, rz = fx;
        double dx = w.x - p.getX(), dz = w.z - p.getZ();
        return new double[]{dx * rx + dz * rz, w.y - p.getY(), dx * fx + dz * fz};
    }

    private static void fire(ServerPlayer p, Crown crown, int t, long now) {
        ServerLevel level = p.serverLevel();
        crown.mark = mark(p, REACH);
        double[] aim = body(p, crown.mark);
        Vec3 eye = p.getEyePosition(), look = crown.mark.subtract(eye);
        double reach = look.length();
        // The rounds fly on past the mark, through whatever bodies they meet there, until a block stops them.
        Vec3 far = reach < 1e-3 ? crown.mark : eye.add(look.scale((reach + ArsenalLayout.PAST) / reach));
        // Everything any round this tick could reach: once, not once a round. Nothing to reach, nothing to trace.
        List<Entity> bodies = level.getEntities(p, new AABB(eye, far).inflate(3), e -> e instanceof LivingEntity && HexServer.validTarget(p, e));
        if (bodies.isEmpty()) return;
        List<Hit> through = new ArrayList<>();
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            if (ArsenalLayout.shots(gun, t) == 0) continue;
            // Each gun sprays about the mark its own way, and each round strays from where its gun points.
            double[] own = ArsenalLayout.aimOf(gun, aim, t);
            ArsenalLayout.Pose pose = ArsenalLayout.pose(ArsenalLayout.slot(gun), ArsenalLayout.out(gun), own, 1, 1);
            Vec3 muzzle = world(p, pose.point(ArsenalLayout.MUZZLE[ArsenalLayout.type(gun)], ArsenalLayout.GUN_SCALE));
            Vec3 to = world(p, own).subtract(muzzle);
            double distance = to.length();
            if (distance < .5) continue;
            double spread = ArsenalLayout.SPREAD;
            Vec3 direction = to.scale(1 / distance).add(p.getRandom().nextGaussian() * spread, p.getRandom().nextGaussian() * spread,
                p.getRandom().nextGaussian() * spread).normalize();
            Vec3 end = muzzle.add(direction.scale(distance + ArsenalLayout.PAST));
            BlockHitResult wall = level.clip(new ClipContext(muzzle, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
            // Through every body in its way short of the block that stops it, nearest first.
            through.clear();
            for (Entity e : bodies) {
                var at = e.getBoundingBox().inflate(.1).clip(muzzle, stop);
                if (at.isPresent()) through.add(new Hit(e, at.get(), muzzle.distanceToSqr(at.get())));
            }
            through.sort(Comparator.comparingDouble(Hit::distance));
            for (int i = 0; i < Math.min(PIERCE, through.size()); i++) strike(crown, through.get(i), direction, now);
        }
    }

    /** A round into a body: owed its sting, and where it went in kept for its hole. One round a tick for any one body. */
    private static void strike(Crown crown, Hit hit, Vec3 direction, long now) {
        int id = hit.body().getId();
        if (crown.struck.getOrDefault(id, -1L) == now) return;
        crown.struck.put(id, now);
        crown.owed.merge(id, ROUND, Float::sum);
        crown.entry.put(id, new Vec3[]{hit.at(), direction});
        // The first round into a body not already held lands at once: one round stops it where it stands.
        crown.dealt.putIfAbsent(id, hit.body() instanceof LivingEntity body && ScepterBlast.stunned(body) ? now : now - DEAL_EVERY);
    }

    /** Deals every body what it is owed, once it has been owed for {@link #DEAL_EVERY} ticks, or everything, now. */
    private static void settle(ServerPlayer p, Crown crown, boolean all) {
        if (crown.owed.isEmpty()) return;
        long now = HexData.now(p);
        DamageSource source = p.damageSources().mobProjectile(p, p);
        for (Iterator<Map.Entry<Integer, Float>> it = crown.owed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Float> owed = it.next();
            long since = crown.dealt.getOrDefault(owed.getKey(), now);
            if (!all && now - since < DEAL_EVERY) continue;
            it.remove();
            crown.dealt.remove(owed.getKey());
            Vec3[] entry = crown.entry.remove(owed.getKey());
            if (!(p.serverLevel().getEntity(owed.getKey()) instanceof LivingEntity body) || !body.isAlive()) continue;
            body.invulnerableTime = 0;
            striking = true;
            try {
                body.hurt(source, owed.getValue());
            } finally {
                striking = false;
            }
            if (body.isDeadOrDying()) continue;
            // Held where it stands for a second from its latest round: under steady fire, held for good.
            ScepterBlast.stun(body, STUN);
            // Once a second under fire, a tiny hole where a round went in, and it bleeds a little.
            if (entry != null && now - crown.holed.getOrDefault(owed.getKey(), Long.MIN_VALUE / 2) >= HOLE_EVERY) {
                crown.holed.put(owed.getKey(), now);
                BeamWound.open(body, entry[0], entry[1], HOLE, HOLE_LIFE, true);
                Bleed.apply(p, body, Bleed.stacks(body) < BLEED_STACKS ? 1 : 0, BLEED);
            }
        }
        if (crown.struck.size() > 64) crown.struck.values().removeIf(t -> now - t > 20);
        if (crown.holed.size() > 64) crown.holed.values().removeIf(t -> now - t > HOLE_EVERY);
    }

    /** The crown's rounds sting; they do not shove. */
    @SubscribeEvent public static void knockback(LivingKnockBackEvent e) {
        if (striking) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ the missiles

    private static void launch(ServerPlayer p, Crown crown, long now) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 mark = mark(p, REACH);
        if (mark.distanceTo(eye) >= REACH - 1e-3) mark = eye.add(look.scale(THROW_REACH));
        if (mark.distanceTo(eye) < NEAREST_MARK) mark = eye.add(look.scale(NEAREST_MARK));
        float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
        double[] right = {Mth.cos(yaw) * -1, 0, -Mth.sin(yaw)};
        CompoundTag n = new CompoundTag();
        n.putString("state", "launch");
        n.putLong("at", now);
        ListTag list = new ListTag();
        long seed = now * 7919 + p.getId();
        int longest = 0;
        for (int side = -1; side <= 1; side += 2) {
            Vec3 start = world(p, new double[]{side * ArsenalLayout.MISSILE_SIDE, ArsenalLayout.MISSILE_HEIGHT, .05});
            ArsenalLayout.Flight flight = new ArsenalLayout.Flight(array(start), array(mark), right, side, seed);
            longest = Math.max(longest, flight.duration);
            Missile missile = new Missile(level, p, ++nextMissile, flight, now);
            MISSILES.add(missile);
            CompoundTag m = new CompoundTag();
            m.putInt("id", missile.id);
            m.putDouble("sx", start.x); m.putDouble("sy", start.y); m.putDouble("sz", start.z);
            m.putDouble("tx", mark.x); m.putDouble("ty", mark.y); m.putDouble("tz", mark.z);
            m.putDouble("rx", right[0]); m.putDouble("ry", right[1]); m.putDouble("rz", right[2]);
            m.putInt("side", side);
            m.putLong("seed", seed);
            list.add(m);
        }
        n.put("missiles", list);
        HexNetwork.near(level, eye, 256, new HexNetwork.Message(HexNetwork.ARSENAL, p.getId(), n));
        // Whatever the fire held to the end is held on until the missiles are there, however long they wander.
        for (int id : crown.pinned)
            if (level.getEntity(id) instanceof LivingEntity body && body.isAlive() && ScepterBlast.stunned(body))
                ScepterBlast.stun(body, Math.min(PIN_MOST, longest + 3));
    }

    /** Every tick of a level: each missile in it flies on, and bursts on whatever it meets or where it was sent. */
    public static void tickLevel(ServerLevel level) {
        if (!SNEAKS.isEmpty()) sneaks(level);
        if (MISSILES.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Missile> it = MISSILES.iterator(); it.hasNext(); ) {
            Missile m = it.next();
            if (m.level != level) {
                if (m.level.getServer() != level.getServer()) it.remove();
                continue;
            }
            double ticks = now - m.launch;
            Vec3 at = vec(m.flight.at(ticks));
            if (!level.hasChunkAt(BlockPos.containing(at))) {it.remove(); continue;}
            ServerPlayer caster = level.getServer().getPlayerList().getPlayer(m.caster);
            Vec3 burst = null;
            Entity struck = null;
            if (m.last.distanceToSqr(at) > 1e-8) {
                BlockHitResult wall = level.clip(new ClipContext(m.last, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null));
                if (wall.getType() != HitResult.Type.MISS) burst = wall.getLocation();
                Vec3 stop = burst != null ? burst : at;
                double nearest = Double.MAX_VALUE;
                for (Entity e : level.getEntities((Entity) null, new AABB(m.last, stop).inflate(1), e -> e instanceof LivingEntity
                    && e.isAlive() && !e.isSpectator() && !e.getUUID().equals(m.caster) && (caster == null || HexServer.validTarget(caster, e)))) {
                    var hit = e.getBoundingBox().inflate(.35).clip(m.last, stop);
                    if (hit.isPresent() && m.last.distanceToSqr(hit.get()) < nearest) {
                        nearest = m.last.distanceToSqr(hit.get());
                        burst = hit.get();
                        struck = e;
                    }
                }
            }
            if (burst == null && ticks >= m.flight.duration) burst = at;
            m.last = at;
            if (burst == null) continue;
            it.remove();
            explode(level, burst, caster, m, struck);
        }
    }

    /** A missile bursting at {@code at}, on {@code struck} if it flew into a body and null if not. */
    private static void explode(ServerLevel level, Vec3 at, ServerPlayer caster, Missile m, Entity struck) {
        DamageSource source = caster != null ? level.damageSources().explosion(caster, caster) : level.damageSources().explosion(null, null);
        crater(level, at, caster, source);
        // Forty hearts to the body it flew into; to every other body within reach thirty at the heart of it, less toward
        // the edge; and all of them burning.
        for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(BLAST_REACH), e -> e instanceof LivingEntity
            && e.isAlive() && !e.isSpectator() && !e.getUUID().equals(m.caster) && (caster == null || HexServer.validTarget(caster, e)))) {
            Vec3 middle = e.getBoundingBox().getCenter();
            double distance = Math.max(0, middle.distanceTo(at) - e.getBbWidth() / 2);
            if (distance > BLAST_REACH) continue;
            float strength = distance <= BLAST_CORE ? 1 : (float) (1 - .65 * (distance - BLAST_CORE) / (BLAST_REACH - BLAST_CORE));
            e.invulnerableTime = 0;
            e.hurt(source, e == struck ? DIRECT : BLAST * strength);
            e.setSecondsOnFire((int) BURN_SECONDS);
            Vec3 away = middle.subtract(at);
            away = away.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : away.normalize();
            double push = 1.6 * (1 - distance / BLAST_REACH);
            e.setDeltaMovement(e.getDeltaMovement().add(away.x * push, .35 + away.y * push * .6, away.z * push));
            e.hurtMarked = true;
        }
        // Seen and heard from here by everyone within reach of the message (ArsenalClient): the boom carries much
        // further than a sound played from the server could be sent.
        CompoundTag n = new CompoundTag();
        n.putString("state", "impact");
        n.putInt("id", m.id);
        n.putDouble("x", at.x); n.putDouble("y", at.y); n.putDouble("z", at.z);
        HexNetwork.near(level, at, 256, new HexNetwork.Message(HexNetwork.ARSENAL, m.casterId, n));
    }

    /**
     * The hole a missile blows: exactly the blocks an explosion of {@link #CRATER}'s power would take, cast with
     * vanilla's own rays so that hard blocks shrug it off as they would any blast, and offered to every mod that
     * guards the world (Forge's explosion events) before anything is touched. Nothing drops and nothing is lost: each
     * block is written down whole, contents and all, and grows back as Paradise's ground does, beginning some eight
     * seconds later and one by one over the ten after that ({@link Nothingness#takeCrater}).
     */
    private static void crater(ServerLevel level, Vec3 at, ServerPlayer caster, DamageSource source) {
        Explosion explosion = new Explosion(level, caster, source, null, at.x, at.y, at.z, CRATER, false, Explosion.BlockInteraction.DESTROY);
        if (ForgeEventFactory.onExplosionStart(level, explosion)) return;
        explosion.getToBlow().addAll(rays(level, at, explosion));
        ForgeEventFactory.onExplosionDetonate(level, explosion, new ArrayList<>(), CRATER * 2);
        long now = level.getGameTime();
        List<BlockPos> blown = new ArrayList<>(explosion.getToBlow());
        int debris = 0;
        for (BlockPos pos : blown) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            // A little of what was blown out goes up as debris.
            if (debris < 48 && level.random.nextInt(Math.max(1, blown.size() / 48)) == 0) {
                debris++;
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                    8, .4, .4, .4, .45);
            }
            Nothingness.takeCrater(level, pos, now + KNIT_DELAY + level.random.nextInt(KNIT_SCATTER));
        }
    }

    /** What a blast of {@link #CRATER}'s power at {@code at} takes, by vanilla's own rays and resistances. */
    private static Set<BlockPos> rays(ServerLevel level, Vec3 at, Explosion explosion) {
        Set<BlockPos> taken = new HashSet<>();
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++)
                for (int k = 0; k < 16; k++) {
                    if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) continue;
                    double dx = i / 15.0 * 2 - 1, dy = j / 15.0 * 2 - 1, dz = k / 15.0 * 2 - 1;
                    double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= l; dy /= l; dz /= l;
                    float strength = CRATER * (.7f + level.random.nextFloat() * .6f);
                    double x = at.x, y = at.y, z = at.z;
                    for (; strength > 0; strength -= .22500001f) {
                        BlockPos pos = BlockPos.containing(x, y, z);
                        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) break;
                        BlockState state = level.getBlockState(pos);
                        FluidState fluid = level.getFluidState(pos);
                        if (!state.isAir() || !fluid.isEmpty())
                            strength -= (Math.max(state.getExplosionResistance(level, pos, explosion), fluid.getExplosionResistance(level, pos, explosion)) + .3f) * .3f;
                        if (strength > 0 && !state.isAir() && state.getDestroySpeed(level, pos) >= 0) taken.add(pos.immutable());
                        x += dx * .3;
                        y += dy * .3;
                        z += dz * .3;
                    }
                }
        return taken;
    }

    // ------------------------------------------------------------------ Gotcha!

    /**
     * Gotcha!: the alternate key tapped with the crown chosen. A single gun forms, without a sound, a little way
     * behind the body the caster looks at, turned on its back the whole while, and shoots it once in the back: ten
     * hearts, a bigger hole than a round's and five seconds of pouring. It leaves the same recovery as a crown held
     * to its missiles. Nothing happens, and nothing is spent, when there is no body in the look or no room behind it.
     *
     * @return why it could not be done, or null once it has begun
     */
    public static String gotcha(ServerPlayer p) {
        if (active(p) || p.isPassenger() || p.isSpectator() || SNEAKS.size() >= 64) return "Not now.";
        LivingEntity body = looked(p, REACH);
        if (body == null) return "Look at a body: Gotcha! puts a gun behind it.";
        ServerLevel level = p.serverLevel();
        Vec3 at = spot(level, p, body);
        if (at == null) return "There is no room anywhere around it for a gun.";
        long now = HexData.now(p);
        Sneak sneak = new Sneak(level, p.getUUID(), ++nextSneak, body.getId(), at, now);
        SNEAKS.add(sneak);
        HexData.get(p).putLong("cd_" + Ability.ARSENAL.name(), now + Ability.ARSENAL.cooldown);
        Vec3 aim = body.getBoundingBox().getCenter();
        CompoundTag n = new CompoundTag();
        n.putString("state", "gotcha");
        n.putInt("id", sneak.id);
        n.putInt("target", body.getId());
        n.putLong("start", now);
        n.putDouble("x", at.x); n.putDouble("y", at.y); n.putDouble("z", at.z);
        n.putDouble("tx", aim.x); n.putDouble("ty", aim.y); n.putDouble("tz", aim.z);
        HexNetwork.near(level, at, 160, new HexNetwork.Message(HexNetwork.ARSENAL, p.getId(), n));
        // The caster points at it: the right arm stretched straight out until the gun has fired.
        HexNetwork.animate(p, "gotcha");
        return null;
    }

    /** The body the caster looks at, as the crown finds its mark: the nearest along the look, short of any block. */
    private static LivingEntity looked(ServerPlayer p, double reach) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(reach));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        double nearest = eye.distanceToSqr(stop);
        LivingEntity best = null;
        for (Entity e : p.level().getEntities(p, new AABB(eye, stop).inflate(1), e -> e instanceof LivingEntity && HexServer.validTarget(p, e))) {
            var hit = e.getBoundingBox().inflate(.3).clip(eye, stop);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < nearest) {
                nearest = eye.distanceToSqr(hit.get());
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    /** Turns from straight back from the way a body faces, in degrees: behind it first, then further round either side. */
    private static final int[] TURNS = {0, 20, -20, 40, -40, 60, -60, 80, -80, 100, -100, 120, -120, 140, -140, 160, -160, 180};
    /** How far round the back a gun must hang to count as behind: the first, choosier search looks no further. */
    private static final int BEHIND = 60;

    /**
     * Where Gotcha!'s gun hangs. First, somewhere behind the body the caster can see: straight back from the way it
     * faces, then turned further round either side, nearer, higher. Failing that, anywhere round it at all, the caster
     * seeing it or not; and last of all, over its head. Wherever it is, the whole gun has room and its muzzle a clear
     * shot into the body. Null when there is nowhere.
     */
    private static Vec3 spot(ServerLevel level, ServerPlayer p, LivingEntity body) {
        double yaw = body.yBodyRot * Mth.DEG_TO_RAD, half = body.getBbWidth() / 2, chest = body.getY() + body.getBbHeight() * .62;
        if (!Double.isFinite(yaw)) yaw = 0;
        Vec3 eye = p.getEyePosition();
        for (int pass = 0; pass < 2; pass++)
            for (double rise : new double[]{ArsenalLayout.SNEAK_RISE, ArsenalLayout.SNEAK_RISE + .9})
                for (double back : new double[]{ArsenalLayout.SNEAK_BACK, 2.2, 1.5})
                    for (int turn : TURNS) {
                        if (pass == 0 && Math.abs(turn) > BEHIND) continue;
                        // A body faces (-sin, cos) of its turn; its back is the other way.
                        double a = yaw + Math.toRadians(turn), d = half + back;
                        Vec3 at = new Vec3(body.getX() + Math.sin(a) * d, chest + rise, body.getZ() - Math.cos(a) * d);
                        if (fits(level, body, at, pass == 0 ? eye : null)) return at;
                    }
        Vec3 over = new Vec3(body.getX(), body.getBoundingBox().maxY + 1.6, body.getZ());
        return fits(level, body, over, null) ? over : null;
    }

    /**
     * Whether Gotcha!'s gun can hang at {@code at}: in the loaded world, room for all of it from stock to muzzle, and a
     * clear shot from the muzzle into the body. Given the caster's eye, also where they can see it form: not in their
     * face, not behind a block, and not hidden behind the body itself.
     */
    private static boolean fits(ServerLevel level, LivingEntity body, Vec3 at, Vec3 eye) {
        if (!Double.isFinite(at.x) || !Double.isFinite(at.y) || !Double.isFinite(at.z)) return false;
        BlockPos pos = BlockPos.containing(at);
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return false;
        Vec3 muzzle = muzzle(at, body.getBoundingBox().getCenter());
        Vec3 forward = muzzle.subtract(at).normalize();
        if (!level.noCollision(new AABB(at.subtract(forward.scale(.7)), muzzle).inflate(.18))) return false;
        if (entry(level, body, muzzle) == null) return false;
        if (eye == null) return true;
        if (eye.distanceToSqr(at) < 4) return false;
        if (level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS) return false;
        return body.getBoundingBox().inflate(.25).clip(eye, at).isEmpty();
    }

    /** The muzzle of Gotcha!'s gun hung at {@code at} and turned on {@code aim}: where its round leaves. */
    private static Vec3 muzzle(Vec3 at, Vec3 aim) {
        return vec(ArsenalLayout.aimed(array(at), array(aim)).point(ArsenalLayout.MUZZLE[ArsenalLayout.SNEAK_TYPE], ArsenalLayout.GUN_SCALE));
    }

    /** Where a round from {@code muzzle} at the middle of the body goes into it, or null if it would not reach it: a block in the way. */
    private static Vec3 entry(ServerLevel level, LivingEntity body, Vec3 muzzle) {
        Vec3 middle = body.getBoundingBox().getCenter(), to = middle.subtract(muzzle);
        if (to.lengthSqr() < 1e-6) return null;
        var in = body.getBoundingBox().inflate(.1).clip(muzzle, middle.add(to.normalize().scale(2)));
        if (in.isEmpty()) return null;
        return level.clip(new ClipContext(muzzle, in.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() == HitResult.Type.MISS
            ? in.get() : null;
    }

    /** Every Gotcha! gun in this level that has come to its moment fires; one that cannot land gives its recovery back. */
    private static void sneaks(ServerLevel level) {
        long now = level.getGameTime();
        for (Iterator<Sneak> it = SNEAKS.iterator(); it.hasNext(); ) {
            Sneak s = it.next();
            if (s.level != level) {
                if (s.level.getServer() != level.getServer()) it.remove();
                continue;
            }
            if (now - s.start < ArsenalLayout.SNEAK_FIRE) continue;
            it.remove();
            if (shoot(level, s)) continue;
            ServerPlayer caster = level.getServer().getPlayerList().getPlayer(s.caster);
            if (caster == null) continue;
            // Only the recovery this very Gotcha! set, never one that has replaced it since.
            CompoundTag d = HexData.get(caster);
            String key = "cd_" + Ability.ARSENAL.name();
            if (d.getLong(key) != s.start + Ability.ARSENAL.cooldown) continue;
            d.putLong(key, 0);
            HexNetwork.sync(caster);
            caster.displayClientMessage(Component.literal("Gotcha! lost its shot. No recovery taken."), true);
        }
    }

    /**
     * Gotcha!'s one round, from its muzzle into the body's back wherever the body is now. False, and nothing dealt, if
     * the body is gone or has put a block between them.
     */
    private static boolean shoot(ServerLevel level, Sneak s) {
        ServerPlayer caster = level.getServer().getPlayerList().getPlayer(s.caster);
        if (caster == null || !(level.getEntity(s.target) instanceof LivingEntity body) || !body.isAlive() || !HexServer.validTarget(caster, body)) return false;
        Vec3 muzzle = muzzle(s.at, body.getBoundingBox().getCenter()), in = entry(level, body, muzzle);
        if (in == null) return false;
        Vec3 direction = body.getBoundingBox().getCenter().subtract(muzzle).normalize();
        body.invulnerableTime = 0;
        striking = true;
        try {
            body.hurt(caster.damageSources().mobProjectile(caster, caster), GOTCHA);
        } finally {
            striking = false;
        }
        if (body.isDeadOrDying()) return true;
        // Jolted forward, the way the round went, not away from the caster.
        body.setDeltaMovement(body.getDeltaMovement().add(direction.x * .3, .08, direction.z * .3));
        body.hurtMarked = true;
        BeamWound.open(body, in, direction, GOTCHA_HOLE, HOLE_LIFE, true);
        Bleed.flow(caster, body, GOTCHA_BLEED);
        return true;
    }

    private static Vec3 vec(double[] v) {return new Vec3(v[0], v[1], v[2]);}
    private static double[] array(Vec3 v) {return new double[]{v.x, v.y, v.z};}
}
