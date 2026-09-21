package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import net.minecraft.client.Minecraft;

/**
 * Everything the entity needs to do on a client, kept in a class a dedicated server never loads.
 * The entity reaches this only through DistExecutor.
 */
public final class LeviathanClientHooks {
    private LeviathanClientHooks() { }

    public static void clientTick(AbyssalPilgrimEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.player == null ? 1024 : Math.sqrt(entity.segments().segment(0).distanceToSqr(mc.player.position()));
        entity.setViewerDistance(distance);
        if (distance < 120) LeviathanEffects.surfaceWake(entity);
    }
}
