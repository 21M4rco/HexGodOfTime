package com.hexgodofstories.server;

import com.hexgodofstories.warping.WarpEmergence;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A body's own NoGravity and NoAI, kept straight under every hold that switches them on for a while.
 *
 * <p>A stun, the unseen hand, an erasure and a body rising out of a Warping pool all hold a body still the same way:
 * they switch its NoGravity and NoAI on, remember what they were, and put that back when they end. Both flags are
 * saved with the body, so a hold that puts back the wrong thing leaves it hanging in the air or frozen for good. Two
 * holds on one body used to do exactly that: the second remembered the first one's switched-on flags as the body's
 * own and, ending last, put them back. HexKagunes' tendrils hold a body the same way, so a grab during one of these
 * did it too, from either side.
 *
 * <p>So every hold here learns a body's flags from {@link #of} (which sees past every hold still on it), and gives
 * them back through {@link #handBack}, which leaves them alone while anything else still holds the body: the last
 * hold to end puts them back, and they are the body's own. A tendril remembers what it saw when it took the body,
 * which may have been one of these holds' flags; when it lets go, the body's own are put back here ({@link #tick}).
 *
 * <p>HexKagunes is only ever read, through its own public state, and only when it is installed. Nothing here changes
 * it, and if its state cannot be read the link switches itself off and everything above still holds between the
 * holds of this mod.
 */
public final class BodyFlags {
    private BodyFlags() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A body's own flags, as they were before anything held it. NoAI is false for anything that is not a creature. */
    public record Own(boolean noGravity, boolean noAi) {}

    /** A body a tendril still holds, with the flags it is to get back once it is let go. */
    private record After(Own own, long until) {}
    private static final Map<UUID, After> AFTER = new HashMap<>();
    /** How long a body is watched for its tendril to let go: ten minutes. */
    private static final long WATCH = 12000;
    private static final int MOST = 256;

    /** What a body's flags really are: past every hold of this mod and any tendril's, else as they stand. */
    public static Own of(Entity e) {
        Own own = ours(e, null);
        if (own != null) return own;
        After after = AFTER.get(e.getUUID());
        if (after != null) return after.own;
        own = Kagune.remembered(e);
        if (own != null) return own;
        return new Own(e.isNoGravity(), e instanceof Mob mob && mob.isNoAi());
    }

    /**
     * A hold is over, and the body is to have its own flags back. Not while anything else still holds it: another hold
     * here gives them back when it ends, and a tendril when it lets go, after which they are put right if what it
     * remembered was not the body's own.
     *
     * @param self the hold that is ending, so it does not count as still holding the body
     */
    public static void handBack(Entity e, Own own, Object self) {
        if (ours(e, self) != null) return;
        Own kept = Kagune.remembered(e);
        if (kept != null) {
            if (!kept.equals(own) && (AFTER.size() < MOST || AFTER.containsKey(e.getUUID())))
                AFTER.put(e.getUUID(), new After(own, e.level().getGameTime() + WATCH));
            return;
        }
        set(e, own);
    }

    /** Whether one of HexKagunes' tendrils is holding this body now. */
    public static boolean kaguneHolds(Entity e) {return e != null && Kagune.remembered(e) != null;}

    private static void set(Entity e, Own own) {
        if (e.isNoGravity() != own.noGravity()) e.setNoGravity(own.noGravity());
        if (e instanceof Mob mob && mob.isNoAi() != own.noAi()) mob.setNoAi(own.noAi());
    }

    /** The flags a hold of this mod (other than {@code except}) has kept for this body, or null if none holds it. */
    private static Own ours(Entity e, Object except) {
        Own own = ScepterBlast.own(e, except);
        if (own == null) own = Telekinesis.own(e, except);
        if (own == null) own = Erasure.own(e, except);
        if (own == null) own = WarpEmergence.own(e, except);
        return own;
    }

    /** Each level's tick: a body a tendril has let go of gets its own flags back. */
    public static void tick(ServerLevel level) {
        if (AFTER.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, After>> it = AFTER.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, After> entry = it.next();
            if (now > entry.getValue().until) {it.remove(); continue;}
            Entity e = level.getEntity(entry.getKey());
            if (e == null) continue;
            if (!e.isAlive()) {it.remove(); continue;}
            if (Kagune.remembered(e) != null) continue;
            // Held again by one of this mod's holds since: that hold learned the body's own flags from here and gives them back itself.
            if (ours(e, null) == null) set(e, entry.getValue().own);
            it.remove();
        }
    }

    /**
     * A player logging out is saved straight away, and nothing holding them gets to let go of them first: a tendril
     * never does, since it cannot find them again. So they are saved with their own flags, not with a hold's.
     */
    public static void leaving(ServerPlayer p) {
        Own own = of(p);
        boolean held = AFTER.remove(p.getUUID()) != null;
        if (held || ours(p, null) != null || Kagune.remembered(p) != null) set(p, own);
    }

    public static void reset() {AFTER.clear();}

    /** HexKagunes, read through its public state when it is installed. Never written to. */
    private static final class Kagune {
        private static boolean looked, warned;
        private static Method peek;
        private static Field held, noGravity, noAi;

        private static boolean ready() {
            if (!looked) {
                looked = true;
                try {
                    if (ModList.get() != null && ModList.get().isLoaded("hexkagune")) {
                        Class<?> runtime = Class.forName("com.hex.kagune.gameplay.KaguneRuntime");
                        Class<?> state = Class.forName("com.hex.kagune.gameplay.KaguneRuntime$State");
                        held = state.getField("heldEntityUuids");
                        noGravity = state.getField("heldEntityWasNoGravity");
                        noAi = state.getField("heldEntityWasNoAi");
                        peek = runtime.getMethod("peek", UUID.class);
                        LOGGER.info("HexKagunes found: bodies its tendrils hold keep their own gravity and AI through this mod's holds");
                    }
                } catch (Throwable t) {
                    off(t);
                }
            }
            return peek != null;
        }

        private static void off(Throwable t) {
            peek = null;
            looked = true;
            if (warned) return;
            warned = true;
            LOGGER.warn("HexKagunes' held bodies cannot be read; holds here fall back to their own bookkeeping", t);
        }

        /** What the tendril holding this body remembered as its own flags, or null if no tendril holds it. */
        static Own remembered(Entity e) {
            if (!(e instanceof LivingEntity) || !ready()) return null;
            MinecraftServer server = e.getServer();
            if (server == null) return null;
            try {
                UUID id = e.getUUID();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    Object state = peek.invoke(null, p.getUUID());
                    if (state == null) continue;
                    UUID[] ids = (UUID[]) held.get(state);
                    for (int slot = 0; slot < ids.length; slot++) {
                        if (!id.equals(ids[slot])) continue;
                        boolean[] gravity = (boolean[]) noGravity.get(state), ai = (boolean[]) noAi.get(state);
                        return new Own(slot < gravity.length && gravity[slot], e instanceof Mob && slot < ai.length && ai[slot]);
                    }
                }
            } catch (Throwable t) {
                off(t);
            }
            return null;
        }
    }
}
