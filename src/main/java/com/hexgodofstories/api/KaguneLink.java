package com.hexgodofstories.api;

import com.hexgodofstories.server.BodyFlags;
import com.hexgodofstories.server.Erasure;
import com.hexgodofstories.server.Evisceration;
import com.hexgodofstories.server.Frostbite;
import com.hexgodofstories.server.GravityGrasp;
import com.hexgodofstories.server.ScepterBlast;
import com.hexgodofstories.server.Telekinesis;
import com.hexgodofstories.server.TemporalEngine;
import com.hexgodofstories.warping.WarpEmergence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * What HexKagunes asks this mod, when both are installed, on the server.
 *
 * <p>HexKagunes 1.0.3 and later look this class up by name and call these methods through method handles, and carry on
 * as they would alone when it is not there; so their names, parameters and meanings are a contract between the two
 * mods. Add to it; do not change it. Every answer is about this mod's own states only, and none of them changes
 * anything.
 */
public final class KaguneLink {
    private KaguneLink() {}

    /** A player's kagune may act. */
    public static final int FREE = 0;
    /** A player is held still (stopped in time, frozen, stunned): their kagune neither acts nor moves what it holds. */
    public static final int HELD = 1;
    /** A player is finished (cut in two, being erased): their kagune lets go of everything and does nothing more. */
    public static final int FINISHED = 2;

    /** Whether, and how far, a player is kept from acting by this mod: {@link #FREE}, {@link #HELD} or {@link #FINISHED}. */
    public static int state(Entity player) {
        if (player == null) return FREE;
        if (Evisceration.halved(player) || Erasure.erasing(player)) return FINISHED;
        if (TemporalEngine.frozen(player) || Frostbite.frozen(player)
            || player instanceof LivingEntity living && ScepterBlast.stunned(living)) return HELD;
        return FREE;
    }

    /** Nothing may strike or take hold of it: a body lying cut in two, a body being erased. */
    public static boolean untouchable(Entity e) {
        return e != null && (Evisceration.halved(e) || Erasure.erasing(e));
    }

    /** One of this mod's holds has it (the unseen hand, a Gravity Grasp's blade, an Evisceration's, a Warping pool's rise). */
    public static boolean claimed(Entity e) {
        return e != null && (Telekinesis.heldBySomeone(e) || GravityGrasp.caught(e) || Evisceration.impaled(e)
            || WarpEmergence.active(e) || Erasure.erasing(e));
    }

    /** Nothing may take hold of it: untouchable, stopped in time or frozen, or {@link #claimed}. */
    public static boolean unholdable(Entity e) {
        return e != null && (untouchable(e) || TemporalEngine.frozen(e) || Frostbite.frozen(e) || claimed(e));
    }

    /** The body's own NoGravity, past any hold of this mod's that has it switched on for now (BodyFlags). */
    public static boolean ownNoGravity(Entity e) {return e != null && BodyFlags.of(e).noGravity();}

    /** The body's own NoAI, past any hold of this mod's that has it switched on for now (BodyFlags). */
    public static boolean ownNoAi(Entity e) {return e != null && BodyFlags.of(e).noAi();}
}
