package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Falling through the break, rather than being moved by it.
 *
 * <p>A Warping portal shows you another world through a hole in the floor. Until now, touching that
 * hole teleported you — which made a tear in reality behave like a pressure plate, and made the
 * thing you could see underneath your feet a picture rather than a place. This is the other half of
 * the illusion: the opening is genuinely open, you sink into it, and the dimension change happens
 * while your head is already below the floor.
 *
 * <p><b>How the ground stops being ground.</b> No block is touched. Instead, an entity standing over
 * a genuinely open part of the pool is given {@code noPhysics} — and given it on the server and on
 * its own client at the same time, because a player's movement is simulated by their own client and
 * the server's copy would otherwise drag them back out with its "moved wrongly" correction. The
 * grant is narrow: it lasts only while the body is still over the liquid and inside a short band
 * around the floor it came through, and it is taken away the instant either stops being true.
 *
 * <p><b>The opening is the pool's own outline, not a box around it.</b> Nine points of the body's
 * own footprint are asked whether they are over liquid; over half of them and the middle one must
 * be, so a body standing at the rim with one foot in stays standing on the floor. The floor height
 * is asked of {@link WarpSurface} per column, exactly as the pool is drawn, so liquid lying across a
 * step behaves as the step does.
 *
 * <p><b>Crossing is the head, not the feet.</b> The transfer waits until the eye — the camera, for a
 * player — is under the local plane, so feet, legs and chest go through first and the world changes
 * at the moment the view does. A tall creature has a high eye and therefore sinks further before it
 * goes, which is what it should look like.
 *
 * <p><b>It is quicksand, not a hole.</b> A body in the liquid does not fall — its descent is taken
 * over by the pool's own sink rate, with its sideways movement dragged rather than stopped. A
 * running jump into the middle does not carry anybody through: it stops them dead and starts them
 * going down. A player's eye is 1.62 blocks up, so going under takes a little under two seconds,
 * which is long enough to watch the other world rise around you.
 *
 * <p><b>And it can be fought.</b> Thrashing lifts a body, and the arithmetic is set so that six
 * presses a second exactly cancels the sink: slower loses ground, faster climbs, and the deeper
 * somebody already is the longer they have to keep it up. Wading to the rim works too, slowly.
 * Rising back above the rim by either route gives the floor back and stands the body on it.
 *
 * <p><b>Heading is never reset.</b> Yaw, pitch and whatever sideways movement survived the drag
 * cross untouched, and the body arrives at the destination offset from the realm's own entry point
 * by however far from the middle of the pool it went in.
 */
public final class WarpCrossing {
    private WarpCrossing() { }

    /** The break, as much of it as a passage needs. Built fresh each tick from the live portal. */
    public record Break(UUID portal, ServerLevel level, Vec3 at, Destination destination, double cell,
                        double[] shape, double extent, Set<UUID> crossed) { }

    /** How far under the floor the grant reaches before a body is considered lost rather than falling. */
    private static final double BAND = 7.0;
    /** How far back above the floor a body may climb before the opening lets go of it. */
    private static final double ESCAPE = 0.55;
    /** Feet this far under the plane and the crossing will finish even if the portal shuts first. */
    private static final double COMMITTED = 0.35;
    /** A moment's grace after arriving, so a break at the far end cannot catch the same body again. */
    private static final int SETTLE = 20;

    /**
     * How long a body may sit in an opening without moving at all before the floor is handed back.
     *
     * <p>A passage that cannot finish is the one failure mode worth designing against, because the
     * body is holding a grant of no-collision while it lasts. What it catches is the case where a
     * client never received, or never honoured, the word that the floor is not there: it stands on
     * ground the server thinks is open, never descends, and would otherwise wait for ever.
     *
     * <p>It is deliberately a stillness watchdog rather than a deadline. A deadline used to be
     * safe, because a crossing was a fall and took five to ten ticks; a crossing is now a sink that
     * takes forty, and anybody fighting it makes it take longer still — so a clock would hand a
     * free escape to whoever merely held on, which is precisely the thing the struggle is supposed
     * to cost. Nothing that is being sunk is ever still, so the two cases do not overlap.
     */
    private static final int PATIENCE = 60;

    /** Movement in a tick below this is no movement: a hundredth of what one tick of sinking is. */
    private static final double STIRRING = 4.0E-4;

    /** One body on its way through one break. Each is its own; nothing about this is shared. */
    private static final class Passage {
        final UUID portal; final long began;
        double plane;
        /** Where the body was last tick, and how many ticks it has failed to move from there. */
        double was = Double.NaN; int still;
        /**
         * Lift banked by somebody thrashing to get out, in blocks, spent on the next tick.
         *
         * <p>Per passage rather than per player, because it is a property of being in this pool:
         * it arrives, it is used, and it is gone. Nothing accumulates across a crossing, so a
         * player cannot bank presses before stepping in.
         */
        double struggle;
        Passage(UUID portal, double plane, long began) { this.portal = portal; this.plane = plane; this.began = began; }
    }

    private static final Map<UUID, Passage> PASSAGES = new HashMap<>();
    /**
     * Breaks something has just gone through, and for how long that still counts as news.
     *
     * <p>The far side is reported to watching clients every few ticks, which is often enough for a
     * body already falling down there and not often enough for the handful of ticks around a
     * crossing — the moment a body leaves this world is exactly the moment an observer must not
     * lose sight of it. A break stays "stirred" for a moment afterwards so those ticks are covered.
     */
    private static final Map<UUID, Long> STIRRED = new HashMap<>();
    /** Bodies that have just come out of a break somewhere, and the tick they stop being immune. */
    private static final Map<UUID, Long> SETTLING = new HashMap<>();

    public static boolean crossing(Entity e) { return PASSAGES.containsKey(e.getUUID()); }

    /** Whether anything is inside this break, or has just left through it. */
    public static boolean busy(UUID portal, long now) {
        for (Passage passage : PASSAGES.values()) if (passage.portal.equals(portal)) return true;
        Long until = STIRRED.get(portal);
        return until != null && until > now;
    }

    public static void reset() { PASSAGES.clear(); SETTLING.clear(); STIRRED.clear(); }

    // ------------------------------------------------------------------ the sweep

    /**
     * One tick of one open break: who has just stepped onto it, and how far through everybody
     * already in it has got.
     *
     * <p>The volume searched is the break's own extent and a short band under it — never a chunk,
     * never an area, and never anything that has to be scanned when nobody is near.
     */
    public static void tick(Break brk, long now, java.util.function.Predicate<Entity> allowed) {
        ServerLevel level = brk.level();
        double reach = brk.extent() + 1.5;
        AABB area = new AABB(brk.at().x - reach, brk.at().y - BAND - 1, brk.at().z - reach,
            brk.at().x + reach, brk.at().y + 2.4, brk.at().z + reach);
        for (Entity e : level.getEntities((Entity) null, area, allowed)) {
            Passage passage = PASSAGES.get(e.getUUID());
            // Somebody already going through a different break is not this break's business.
            if (passage != null && !passage.portal.equals(brk.portal())) continue;
            if (passage == null) { offer(brk, e, now); continue; }
            advance(brk, passage, e, now);
        }
        // A body that left the search volume entirely — thrown clear, killed, removed — still has a
        // passage to close. This is the only place that can notice, because it is no longer found.
        for (Map.Entry<UUID, Passage> entry : new ArrayList<>(PASSAGES.entrySet())) {
            if (!entry.getValue().portal.equals(brk.portal())) continue;
            Entity e = level.getEntity(entry.getKey());
            if (e != null && e.isAlive() && !e.isRemoved()) continue;
            PASSAGES.remove(entry.getKey());
            if (e != null) release(e);
        }
        SETTLING.values().removeIf(until -> until <= now);
        STIRRED.values().removeIf(until -> until <= now);
    }

    /** Whether this body is standing on enough open break to start going through it. */
    private static void offer(Break brk, Entity e, long now) {
        if (brk.crossed().contains(e.getUUID())) return;
        Long settling = SETTLING.get(e.getUUID());
        if (settling != null && settling > now) return;
        if (!open(brk, e)) return;
        double plane = plane(brk, e);
        if (Double.isNaN(plane)) return;
        // Standing on it, or already dropping onto it. Not leaping over it from a height, and not
        // walking past a metre underneath it.
        if (e.getY() > plane + 0.45 || e.getY() < plane - 0.8) return;
        PASSAGES.put(e.getUUID(), new Passage(brk.portal(), plane, now));
        e.noPhysics = true;
        phase(e, plane, now);
        entering(brk, e);
    }

    /** One tick of a body already on its way down. */
    private static void advance(Break brk, Passage passage, Entity e, long now) {
        double plane = plane(brk, e);
        if (!Double.isNaN(plane)) passage.plane = plane;
        // Climbed back out, or waded off the side of the opening while still above it. Both are a
        // body that has changed its mind, and both give the floor back.
        if (e.getY() > passage.plane + ESCAPE || !open(brk, e)) { abort(e, passage, true); return; }
        // Not moving at all is not the same as fighting: a body being sunk shifts every tick, so
        // anything that has genuinely stood still for three seconds is a client that never let go
        // of the floor rather than somebody holding their own against the liquid.
        double y = e.getY();
        passage.still = !Double.isNaN(passage.was) && Math.abs(y - passage.was) < STIRRING ? passage.still + 1 : 0;
        passage.was = y;
        if (passage.still > PATIENCE) { abort(e, passage, true); return; }
        // Fallen further than a break is deep without the crossing having fired. Something is wrong
        // with the floor rather than with the body, so finish the job rather than strand it.
        if (e.getY() < passage.plane - BAND) { cross(brk, passage, e, now); return; }

        e.noPhysics = true;
        sink(e, passage);
        phase(e, passage.plane, now);
        if (e.getY() + e.getEyeHeight() <= passage.plane - 0.02) cross(brk, passage, e, now);
    }

    /**
     * One tick of going under.
     *
     * <p>Quicksand rather than a hole. Whatever the body arrived doing, its descent is taken over
     * by the pool's own rate the moment it is in the liquid — a running jump into the middle does
     * not carry anybody through, it stops them dead and starts them going down — and its sideways
     * movement is dragged rather than stopped, so wading toward the rim is slow but possible and is
     * the second way out of one of these.
     *
     * <p>Whatever thrashing has been banked since the last tick is spent here, as lift. It is
     * spent rather than held so that a player cannot save presses up: the only thing that gets
     * anybody out is doing it now, fast, for as long as it takes.
     *
     * <p>A player's own client runs this identical call on the identical numbers in the same tick,
     * because a player's movement is simulated there and a server pushing against it would fight
     * for every block. The server runs it anyway — on its own copy, and on everything that is not
     * a player — so the two agree without anybody being corrected.
     */
    private static void sink(Entity e, Passage passage) {
        double lift = passage.struggle;
        passage.struggle = 0;
        Vec3 v = e.getDeltaMovement();
        e.setDeltaMovement(v.x * WarpMath.SINK_DRAG, -WarpMath.SINK_RATE + lift, v.z * WarpMath.SINK_DRAG);
        e.fallDistance = 0;
        if (!(e instanceof ServerPlayer)) e.hurtMarked = true;
    }

    /**
     * Somebody is trying very hard to get out.
     *
     * <p>One press, one measure of lift, and the arithmetic of whether that is enough is set in
     * {@link WarpMath}: six presses a second exactly cancels the sink, so slower than that loses
     * ground and faster than that climbs. Nothing here decides whether they escape — they escape by
     * actually rising back above the rim, which the ordinary abort above notices — so a client that
     * lies about pressing the key gains lift it then has to spend on a climb the server can see.
     */
    public static void struggle(ServerPlayer p) {
        Passage passage = PASSAGES.get(p.getUUID());
        if (passage == null) return;
        passage.struggle = Math.min(passage.struggle + WarpMath.STRUGGLE_LIFT, WarpMath.STRUGGLE_LIFT * 3);
    }

    /**
     * The head goes under, and the world changes around it.
     *
     * <p>Everything the body was doing is read off it first and handed to the transfer: the
     * momentum, the heading, the pitch, the fall already in progress, and how far from the middle of
     * the break it went in. Nothing here decides where it lands — the destination does — but the
     * offset is what makes a break opened at the edge of its own opening come out at the edge of
     * the realm's entry rather than snapping to the middle of it.
     */
    private static void cross(Break brk, Passage passage, Entity e, long now) {
        PASSAGES.remove(e.getUUID());
        brk.crossed().add(e.getUUID());
        SETTLING.put(e.getUUID(), now + SETTLE);
        STIRRED.put(brk.portal(), now + 8);
        Vec3 momentum = e.getDeltaMovement();
        Vec3 offset = new Vec3(e.getX() - brk.at().x, 0, e.getZ() - brk.at().z);
        float fall = e.fallDistance;
        membrane(brk, e);
        e.noPhysics = false;
        boolean owner = e.getUUID().equals(brk.portal()) && Warping.sovereign(e);
        WarpRealms.fallThrough(e, brk.destination(), brk.cell(), offset, momentum, fall, owner);
    }

    /**
     * The break shuts while somebody is still in it.
     *
     * <p>Past the point of no return — feet clearly under the plane — the crossing is finished
     * anyway, because the alternative is a body inside the floor. Barely begun, the floor is simply
     * handed back, and the body is lifted to stand on it if it has already sunk below.
     *
     * <p>Where the body is now, rather than the deepest it ever got. A body sinking into quicksand
     * dips past this line within half a second of stepping on, so a flag set once would mean that
     * fighting your way almost back out and then having the pool shut over you took you anyway —
     * which would make the struggle worth nothing in exactly the case where it was hardest.
     */
    public static void closing(Break brk, long now) {
        for (Map.Entry<UUID, Passage> entry : new ArrayList<>(PASSAGES.entrySet())) {
            Passage passage = entry.getValue();
            if (!passage.portal.equals(brk.portal())) continue;
            Entity e = brk.level().getEntity(entry.getKey());
            if (e == null) { PASSAGES.remove(entry.getKey()); continue; }
            if (e.getY() < passage.plane - COMMITTED) cross(brk, passage, e, now);
            else abort(e, passage, true);
        }
    }

    /** Gives the floor back, and puts the body on top of it rather than inside it. */
    private static void abort(Entity e, Passage passage, boolean lift) {
        PASSAGES.remove(e.getUUID());
        release(e);
        if (!lift || e.getY() >= passage.plane) return;
        double top = passage.plane + 0.02;
        if (e instanceof ServerPlayer p) p.connection.teleport(e.getX(), top, e.getZ(), p.getYRot(), p.getXRot());
        else e.teleportTo(e.getX(), top, e.getZ());
        e.setDeltaMovement(e.getDeltaMovement().x, Math.max(0, e.getDeltaMovement().y), e.getDeltaMovement().z);
    }

    private static void release(Entity e) {
        if (e.isSpectator()) return;
        e.noPhysics = false;
    }

    // ------------------------------------------------------------------ the shape of the hole

    /**
     * Whether enough of this body's footprint is over open break.
     *
     * <p>The rule itself lives with the fracture rather than here, because it is a fact about the
     * shape rather than about entities — and because the build can then check it without a world:
     * that a body on the intact stone between two cracks stays on it, that one on the impact goes
     * through, and that a hairline carries nobody wider than it is.
     */
    private static boolean open(Break brk, Entity e) {
        return WarpPool.footing(brk.shape(), e.getX() - brk.at().x, e.getZ() - brk.at().z,
            Math.max(0.3, e.getBbWidth()));
    }

    /** The floor this body is going through, taken from the column it is actually standing in. */
    private static double plane(Break brk, Entity e) {
        double dx = e.getX() - brk.at().x, dz = e.getZ() - brk.at().z;
        return WarpSurface.height(brk.level(), e.getX(), e.getZ(), brk.at().y, Math.sqrt(dx * dx + dz * dz));
    }

    // ------------------------------------------------------------------ what it looks like

    /**
     * Tells this body's own client that it is inside the opening.
     *
     * <p>Refreshed every tick with a short expiry rather than sent once as a switch, so a dropped
     * packet, a death, a disconnect or a server that simply stops talking all end the grant by
     * themselves instead of leaving a player permanently able to walk through walls.
     */
    private static void phase(Entity e, double plane, long now) {
        // Only a player needs telling. Everything else is moved by the server and drawn by every
        // client from the positions it is already sent, so the grant is one packet to one machine.
        if (!(e instanceof ServerPlayer p)) return;
        CompoundTag n = new CompoundTag();
        n.putDouble("plane", plane);
        n.putLong("until", now + 5);
        HexNetwork.to(p, new HexNetwork.Message(HexNetwork.WARP_PHASE, e.getId(), n));
    }

    /** The surface parting as a body starts into it: a small ring of liquid drawn inward. Once. */
    private static void entering(Break brk, Entity e) {
        ServerLevel level = brk.level();
        double y = e.getY() + 0.1;
        for (int i = 0; i < 6; i++) {
            double a = i * 1.047 + level.random.nextDouble();
            double reach = 0.5 + level.random.nextDouble() * Math.max(1, e.getBbWidth());
            level.sendParticles(HexGodOfStories.MOTE.get(), e.getX() + Math.cos(a) * reach, y, e.getZ() + Math.sin(a) * reach,
                0, -Math.cos(a) * 0.14, -0.10, -Math.sin(a) * 0.14, 1);
        }
    }

    /**
     * The moment the head goes under.
     *
     * <p>The muffled note of going under a surface rather than a teleport chime, played where the
     * body was so that anybody standing beside the pool hears it happen there, and a small ring of
     * liquid closing over the place it went in. No flash, no column of light, and nothing that
     * reads as an arrival somewhere else.
     */
    private static void membrane(Break brk, Entity e) {
        ServerLevel level = brk.level();
        level.playSound(null, BlockPos.containing(e.position()), net.minecraft.sounds.SoundEvents.AMBIENT_UNDERWATER_ENTER,
            SoundSource.PLAYERS, .38f, .72f + level.random.nextFloat() * .16f);
        level.playSound(null, BlockPos.containing(e.position()), HexGodOfStories.RIFT_CLOSE.get(),
            SoundSource.PLAYERS, .28f, 1.45f);
        level.sendParticles(HexGodOfStories.MOTE.get(), e.getX(), e.getY() + e.getEyeHeight() * .4, e.getZ(),
            12, e.getBbWidth() * .55, e.getBbHeight() * .25, e.getBbWidth() * .55, .035);
        level.sendParticles(HexGodOfStories.NEBULA.get(), e.getX(), e.getY() + .15, e.getZ(),
            5, e.getBbWidth() * .4, .04, e.getBbWidth() * .4, .01);
    }
}
