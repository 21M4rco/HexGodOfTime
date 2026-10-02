package com.hexgodofstories.entity;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One of the four small missiles Gotcha! sends in the full transformation: the Crown's own missile, shrunk, and
 * hunting. Thrown out from beside the caster's head, it fans away for a moment and then turns after the body it was
 * sent at, slowly and never straight, wandering about its line as it closes. It turns no tighter than a few blocks
 * across, so a body that sees it coming can step aside and make it overshoot, and it is barely faster than a sprint,
 * so a long run only puts off the moment; after nine seconds it bursts wherever it has got to. It bursts on the first
 * thing it meets: five hearts to a body it flies into, less to whatever is near, a short scorch, no block broken. A
 * shield turned toward it takes it.
 *
 * <p>Steered on the server only, every tick; clients are sent its position and velocity every tick and draw it with
 * the Crown's missile model ({@code ArsenalClient}), so nothing here renders and nothing here is saved.
 */
public final class SeekerMissile extends ThrowableProjectile {
    /** Blocks a tick in flight, and while it is thrown out at the start; ticks it is thrown before it hunts. */
    private static final double SPEED = .43, THROWN = .32;
    private static final int THROW_TICKS = 9;
    /** The most it turns in a tick, in radians: about a turn four and a half blocks across. */
    private static final double TURN = .095;
    /** How far it wanders off its line as it hunts, as a share of the line. */
    private static final double WANDER = .42;
    /** Nine seconds, then it bursts wherever it is. */
    private static final int LIFE = 180;
    /** Its burst: five hearts to the body it strikes, three at the heart of the blast, less to its edge. */
    private static final float DIRECT = 10, BLAST = 6;
    private static final double CORE = 1.6, REACH = 3.4;

    /** Live on this client, for drawing: the renderer reads them here, so it never has to sweep the level for them. */
    public static final Set<SeekerMissile> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private int target;
    private double wander;

    public SeekerMissile(EntityType<? extends SeekerMissile> type, Level level) {
        super(type, level);
        // A client's copy is built only from the server's spawn packet; the renderer drops it once it is removed.
        if (level.isClientSide) LIVE.add(this);
    }

    @Override protected void defineSynchedData() { }

    /** One missile of the swarm, from {@code from}, thrown out along {@code out}, sent after {@code body}. */
    public static void launch(ServerPlayer caster, LivingEntity body, Vec3 from, Vec3 out, int index) {
        SeekerMissile m = new SeekerMissile(HexGodOfStories.SEEKER.get(), caster.level());
        m.setOwner(caster);
        m.setPos(from);
        m.target = body.getId();
        m.wander = index * 1.9 + caster.getRandom().nextDouble() * 6.28;
        m.setDeltaMovement(out.normalize().scale(THROWN));
        caster.level().addFreshEntity(m);
    }

    @Override protected float getGravity() {return 0;}

    @Override public void tick() {
        if (!level().isClientSide && tickCount > 0) {
            if (tickCount >= LIFE) {burst(null, position()); return;}
            if (steer()) return;
        }
        super.tick();
    }

    /** Turns toward the body it hunts, wandering as it goes. @return true if it was close enough to burst instead. */
    private boolean steer() {
        Vec3 v = getDeltaMovement();
        Vec3 heading = v.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : v.normalize();
        if (tickCount < THROW_TICKS) {
            setDeltaMovement(heading.scale(THROWN + (SPEED - THROWN) * tickCount / THROW_TICKS));
            return false;
        }
        if (level().getEntity(target) instanceof LivingEntity body && body.isAlive() && !body.isRemoved()) {
            Vec3 aim = body.getBoundingBox().getCenter().subtract(position());
            if (body.getBoundingBox().inflate(.45).contains(position())) {burst(body, position()); return true;}
            Vec3 want = aim.lengthSqr() < 1e-8 ? heading : aim.normalize();
            Vec3 side = want.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
            Vec3 up = side.cross(want).normalize();
            // Never straight: a slow weave across the line, and a little up and down it, its own way for each missile.
            double t = tickCount * .2 + wander;
            want = want.add(side.scale(Math.sin(t) * WANDER)).add(up.scale(Math.sin(t * .63 + wander) * WANDER * .6)).normalize();
            heading = turn(heading, want, TURN);
        }
        setDeltaMovement(heading.scale(SPEED));
        return false;
    }

    /** {@code from} turned toward {@code to} by at most {@code most} radians. */
    private static Vec3 turn(Vec3 from, Vec3 to, double most) {
        double cos = Math.max(-1, Math.min(1, from.dot(to)));
        if (Math.acos(cos) <= most) return to;
        Vec3 across = to.subtract(from.scale(cos));
        if (across.lengthSqr() < 1e-8) {
            across = from.cross(new Vec3(0, 1, 0));
            if (across.lengthSqr() < 1e-8) across = new Vec3(1, 0, 0);
        }
        across = across.normalize();
        return from.scale(Math.cos(most)).add(across.scale(Math.sin(most))).normalize();
    }

    @Override protected boolean canHitEntity(Entity e) {
        if (e == getOwner() || e instanceof SeekerMissile) return false;
        if (getOwner() instanceof ServerPlayer p && !HexServer.foe(p, e)) return false;
        return super.canHitEntity(e);
    }

    @Override protected void onHitEntity(EntityHitResult hit) {
        if (level().isClientSide) return;
        burst(hit.getEntity(), hit.getLocation());
    }

    @Override protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (level().isClientSide) return;
        burst(null, hit.getLocation());
    }

    /** The small blast: no block broken, a short scorch, and everyone near it shoved off. */
    private void burst(Entity struck, Vec3 at) {
        if (isRemoved() || !(level() instanceof ServerLevel level)) return;
        ServerPlayer caster = getOwner() instanceof ServerPlayer p ? p : null;
        DamageSource source = level.damageSources().explosion(this, caster);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(REACH), e -> e.isAlive() && !e.isSpectator()
            && e != caster && (caster == null || HexServer.foe(caster, e)))) {
            Vec3 middle = e.getBoundingBox().getCenter();
            double distance = Math.max(0, middle.distanceTo(at) - e.getBbWidth() / 2);
            if (distance > REACH) continue;
            float strength = distance <= CORE ? 1 : (float) (1 - .7 * (distance - CORE) / (REACH - CORE));
            e.invulnerableTime = 0;
            e.hurt(source, e == struck ? DIRECT : BLAST * strength);
            e.setSecondsOnFire(2);
            Vec3 away = middle.subtract(at);
            away = away.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : away.normalize();
            e.setDeltaMovement(e.getDeltaMovement().add(away.x * .55 * strength, .22 + away.y * .2, away.z * .55 * strength));
            e.hurtMarked = true;
        }
        CompoundTag n = new CompoundTag();
        n.putString("state", "impact");
        n.putBoolean("small", true);
        n.putInt("id", -1);
        n.putDouble("x", at.x); n.putDouble("y", at.y); n.putDouble("z", at.z);
        HexNetwork.near(level, at, 160, new HexNetwork.Message(HexNetwork.ARSENAL, getId(), n));
        discard();
    }

    @Override protected void addAdditionalSaveData(CompoundTag n) {super.addAdditionalSaveData(n);}
    @Override protected void readAdditionalSaveData(CompoundTag n) {super.readAdditionalSaveData(n);}
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {return NetworkHooks.getEntitySpawningPacket(this);}
}
