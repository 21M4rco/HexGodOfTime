package com.hexgodofstories.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.player.LocalPlayer;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import com.hexgodofstories.warping.WarpMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;

/**
 * This client's half of falling through a break.
 *
 * <p>The server decides everything: whether a body is over the opening, whether it has gone far
 * enough to cross, where it comes out and what it comes out doing. What it cannot do is make a
 * player's own client agree that the floor is not there, because a player's movement is simulated
 * on their own machine — so the server says "you are inside the opening" every tick and this turns
 * that into the one local fact that has to match: no block collision while it holds.
 *
 * <p>The grant is a deadline rather than a switch. Every packet carries a tick a handful ahead, and
 * nothing here keeps it alive on its own, so a dropped packet, a death, a disconnected server or a
 * server that simply stops saying it all end the phase by themselves. A player cannot be left able
 * to walk through walls by anything going wrong.
 *
 * <p>The other job is the frame the world changes on. A dimension change puts Minecraft's
 * "downloading terrain" overlay up, which is a loading screen in the middle of a fall, so a
 * crossing takes it straight back down and puts a few frames of refraction over the seam instead.
 */
public final class WarpCrossingClient {
    private WarpCrossingClient() { }

    private record Phase(double plane, long until) { }
    private static final Map<Integer, Phase> PHASES = new HashMap<>();

    /**
     * Wall clock, not game time, and deliberately so: a crossing outlives the level whose clock the
     * game time belongs to, and the whole point of these two is to span exactly that moment.
     */
    private static long phasedAt, membraneUntil;
    private static boolean holding, jumpHeld;
    /** Lift banked by this client's own thrashing, in blocks, spent on the next tick. */
    private static double banked;

    /** How long after the last phase packet a respawn still counts as having fallen through one. */
    private static final long RECENT = 900;
    /** How long the refraction runs for. A few frames — enough to cover the seam, not to be a cut. */
    private static final long MEMBRANE = 260;

    public static void receive(int id, CompoundTag n) {
        PHASES.put(id, new Phase(n.getDouble("plane"), n.getLong("until")));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == id) phasedAt = Util.getMillis();
    }

    /** The floor a body is currently sinking through, or NaN when it is not sinking through one. */
    public static double plane(int id) {
        Phase phase = PHASES.get(id);
        return phase == null ? Double.NaN : phase.plane();
    }

    public static boolean phasing(int id) { return PHASES.containsKey(id); }

    /**
     * One tick of sinking, on the one player this client owns.
     *
     * <p>Run at the start of the player's own tick, before Minecraft moves them, because that is
     * the only place a velocity survives into the movement it is meant to cause: set at the end of
     * a tick it would have a tick of gravity added to it before it did anything, and the careful
     * sink rate would be a fall with extra steps.
     *
     * <p>The formula is the server's, on the server's numbers, because a player's movement is
     * simulated here and a server pushing against it would fight for every block. What the server
     * does instead is watch how deep this gets and decide the crossing from that.
     *
     * <p>The jump key is read directly rather than waited for: a player with no collision is never
     * on the ground, so Minecraft's own jump never fires, and the press has to be noticed rather
     * than felt. Each fresh one banks a measure of lift here and sends one to the server, so both
     * copies climb together.
     */
    public static void sink(LocalPlayer player) {
        if (player == null || !PHASES.containsKey(player.getId())) { jumpHeld = false; banked = 0; return; }
        player.noPhysics = true;
        boolean jump = Minecraft.getInstance().options.keyJump.isDown();
        if (jump && !jumpHeld) {
            banked = Math.min(banked + WarpMath.STRUGGLE_LIFT, WarpMath.STRUGGLE_LIFT * 3);
            HexNetwork.send(HexServer.WARP_STRUGGLE, 0);
        }
        jumpHeld = jump;
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x * WarpMath.SINK_DRAG, -WarpMath.SINK_RATE + banked, v.z * WarpMath.SINK_DRAG);
        player.fallDistance = 0;
        banked = 0;
    }

    /** Ticked from the client's own state pass, so it ends with everything else. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { PHASES.clear(); holding = false; return; }
        long now = mc.level.getGameTime();
        PHASES.values().removeIf(phase -> phase.until() < now);
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (PHASES.containsKey(player.getId())) {
            player.noPhysics = true;
            holding = true;
        } else if (holding) {
            holding = false;
            jumpHeld = false;
            banked = 0;
            // Never handed back to somebody who is meant to have it: a spectator's noclip is theirs.
            if (!player.isSpectator()) player.noPhysics = false;
        }
    }

    /**
     * A dimension change landed. If this client was falling through a break a moment ago, that is
     * what it was, and the seam gets covered rather than announced.
     */
    public static void respawned() {
        if (Util.getMillis() - phasedAt > RECENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ReceivingLevelScreen) mc.setScreen(null);
        membraneUntil = Util.getMillis() + MEMBRANE;
        TemporalScreen.trigger("warp_membrane", true);
    }

    /** Whether the refraction is still running, for anything that wants to stay out of its way. */
    public static boolean membrane() { return Util.getMillis() < membraneUntil; }

    /**
     * Cleared when the level changes — except for the wall-clock marks, which exist precisely to
     * survive that and would be useless if this wiped them.
     */
    public static void clear() { PHASES.clear(); holding = false; jumpHeld = false; banked = 0; }
}
