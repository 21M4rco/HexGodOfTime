package com.hexgodofstories.client;

import com.hexgodofstories.server.GravityGrasp;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Gravity Grasp, kept square: while a player's hand points, hauls or the dagger is in a body's neck, their body is
 * turned to their look. The pull itself is drawn as the air bending (GraspLens), never as anything solid on the palm.
 */
public final class GraspRenderer {
    private GraspRenderer() {}

    /** Whether this player is pointing, hauling or stabbing with it: their body is kept square to their look. */
    private static boolean squared(Player p) {
        var d=ClientState.data(p.getId());
        long stab=d.getLong(GravityGrasp.STABBING);
        return d.getLong(GravityGrasp.HOLDING)>0||stab>0&&ClientState.now()-stab<=GravityGrasp.STAB_END;
    }

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        // After the players' own ticks: their moves are played from the body, but the hand's aim and the body caught are
        // placed from the look (GravityGrasp), and vanilla lets the body trail the head by as much as fifty degrees;
        // the arm would point off beside what it means, and the blade go in beside the body it was meant for.
        for(Player p:mc.level.players())if(squared(p)&&!p.isSpectator())p.yBodyRot=p.getYHeadRot();
    }
}
