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
 * for as long as the key is held, up to twenty seconds. Let go before that and the guns simply come apart. Hold it
 * to the end and the arms are thrown down, and two missiles that formed beside the head fly, slowly and never
 * straight, to the point aimed at, and blow a crater there that knits itself back together a block at a time.
 *
 * <p>Nothing is an entity and nothing is an item. The guns exist only on the clients, who draw them from {@link
 * ArsenalLayout}'s numbers; the rounds are the server's own instant rays, from the same muzzles to the same mark;
 * the missiles fly the same {@link ArsenalLayout.Flight} here and on every client, with a single message at launch
 * and a single message where each lands. The whole of it costs the server a handful of rays a tick.
 *
 * <p>Damage. A round is half a heart. A body takes at most one round a tick however many are aimed at it, dealt
 * four ticks' worth at a time so that it is not made to cry out twenty times a second, and never knocked about by
 * it. A missile is thirty hearts to anything within five blocks of where it bursts, less to the edge of its nine,
 * and sets it burning.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class Arsenal {
    private Arsenal() { }

    /** A round: half a heart. */
    static final float ROUND = 1;
    /** Ticks a body's owed rounds are gathered over before they are dealt. */
    static final int DEAL_EVERY = 4;
    /** How far the crown sees to aim, and how far the missiles are thrown when there is nothing to see. */
    static final double REACH = 128, THROW_REACH = 90, NEAREST_MARK = 6;
    /** How far a round strays from the mark, in radians: a burst, not a laser. */
    static final double SPREAD = .011;
    /** A missile: its blast, the reach of that blast at full strength and at all, its burn, and its crater's power. */
    static final float BLAST = 60, BURN_SECONDS = 8, CRATER = 6;
    static final double BLAST_CORE = 5, BLAST_REACH = 9;
    /** How long a crater stays open before the first block comes back, and over how long the rest follow: Paradise's own. */
    static final int KNIT_DELAY = 170, KNIT_SCATTER = 190;
    /** Recovery after the crown ends: the whole of it, or a short one when let go before a round was fired. */
    static final int SHORT_RECOVERY = 200;

    private static final class Crown {
        final long start;
        /** The mark this tick, for the rounds; recomputed every tick the crown fires. */
        Vec3 mark;
        /** Rounds owed to each body, by entity id, and when they were last dealt. */
        final Map<Integer, Float> owed = new HashMap<>();
        final Map<Integer, Long> dealt = new HashMap<>();
        final Map<Integer, Long> struck = new HashMap<>();

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

    private static final Map<UUID, Crown> CROWNS = new HashMap<>();
    private static final List<Missile> MISSILES = new ArrayList<>();
    private static int nextMissile;
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
            HexData.get(p).putString("arsenalEnding", "finale");
            state(p, "finale", now);
        }
        if (t == ArsenalLayout.THROW) HexNetwork.animate(p, "arsenal_throw");
        if (t >= ArsenalLayout.LAUNCH) {
            launch(p, now);
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
        Vec3 eye = p.getEyePosition();
        // Everything any round this tick could reach: once, not once a round.
        List<Entity> bodies = level.getEntities(p, new AABB(eye, crown.mark).inflate(3), e -> e instanceof LivingEntity && HexServer.validTarget(p, e));
        for (int gun = 0; gun < ArsenalLayout.GUNS; gun++) {
            if (ArsenalLayout.shots(gun, t) == 0) continue;
            ArsenalLayout.Pose pose = ArsenalLayout.pose(ArsenalLayout.slot(gun), ArsenalLayout.out(gun), aim, 1, 1);
            Vec3 muzzle = world(p, pose.point(ArsenalLayout.MUZZLE[ArsenalLayout.type(gun)], ArsenalLayout.GUN_SCALE));
            Vec3 to = crown.mark.subtract(muzzle);
            double distance = to.length();
            if (distance < .5) continue;
            Vec3 direction = to.scale(1 / distance).add(p.getRandom().nextGaussian() * SPREAD, p.getRandom().nextGaussian() * SPREAD,
                p.getRandom().nextGaussian() * SPREAD).normalize();
            Vec3 end = muzzle.add(direction.scale(distance + 3));
            BlockHitResult wall = level.clip(new ClipContext(muzzle, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
            Entity hit = null;
            double nearest = Double.MAX_VALUE;
            for (Entity e : bodies) {
                var at = e.getBoundingBox().inflate(.1).clip(muzzle, stop);
                if (at.isEmpty()) continue;
                double d = muzzle.distanceToSqr(at.get());
                if (d < nearest) {nearest = d; hit = e;}
            }
            if (hit == null) continue;
            // One round a tick for any one body, however many barrels are on it.
            if (crown.struck.getOrDefault(hit.getId(), -1L) == now) continue;
            crown.struck.put(hit.getId(), now);
            crown.owed.merge(hit.getId(), ROUND, Float::sum);
            crown.dealt.putIfAbsent(hit.getId(), now);
        }
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
            if (!(p.serverLevel().getEntity(owed.getKey()) instanceof LivingEntity body) || !body.isAlive()) continue;
            body.invulnerableTime = 0;
            striking = true;
            try {
                body.hurt(source, owed.getValue());
            } finally {
                striking = false;
            }
        }
        if (crown.struck.size() > 64) crown.struck.values().removeIf(t -> now - t > 20);
    }

    /** The crown's rounds sting; they do not shove. */
    @SubscribeEvent public static void knockback(LivingKnockBackEvent e) {
        if (striking) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ the missiles

    private static void launch(ServerPlayer p, long now) {
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
        for (int side = -1; side <= 1; side += 2) {
            Vec3 start = world(p, new double[]{side * ArsenalLayout.MISSILE_SIDE, ArsenalLayout.MISSILE_HEIGHT, .05});
            ArsenalLayout.Flight flight = new ArsenalLayout.Flight(array(start), array(mark), right, side, seed);
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
    }

    /** Every tick of a level: each missile in it flies on, and bursts on whatever it meets or where it was sent. */
    public static void tickLevel(ServerLevel level) {
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
                    }
                }
            }
            if (burst == null && ticks >= m.flight.duration) burst = at;
            m.last = at;
            if (burst == null) continue;
            it.remove();
            explode(level, burst, caster, m);
        }
    }

    private static void explode(ServerLevel level, Vec3 at, ServerPlayer caster, Missile m) {
        DamageSource source = caster != null ? level.damageSources().explosion(caster, caster) : level.damageSources().explosion(null, null);
        crater(level, at, caster, source);
        // Every body within reach: thirty hearts at the heart of it, less toward the edge, and burning.
        for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(BLAST_REACH), e -> e instanceof LivingEntity
            && e.isAlive() && !e.isSpectator() && !e.getUUID().equals(m.caster) && (caster == null || HexServer.validTarget(caster, e)))) {
            Vec3 middle = e.getBoundingBox().getCenter();
            double distance = Math.max(0, middle.distanceTo(at) - e.getBbWidth() / 2);
            if (distance > BLAST_REACH) continue;
            float strength = distance <= BLAST_CORE ? 1 : (float) (1 - .65 * (distance - BLAST_CORE) / (BLAST_REACH - BLAST_CORE));
            e.invulnerableTime = 0;
            e.hurt(source, BLAST * strength);
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

    private static Vec3 vec(double[] v) {return new Vec3(v[0], v[1], v[2]);}
    private static double[] array(Vec3 v) {return new double[]{v.x, v.y, v.z};}
}
