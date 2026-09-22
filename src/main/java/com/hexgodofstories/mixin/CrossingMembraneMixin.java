package com.hexgodofstories.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the loading screen back down when a fall carries somebody into another dimension.
 *
 * <p>Minecraft puts a "downloading terrain" overlay up on every dimension change, which is correct
 * for a nether portal and wrong for falling through a hole in the floor: the brief for this
 * mechanic is that there is never a moment that reads as being moved rather than as moving, and a
 * loading screen is the most explicit version of that moment there is.
 *
 * <p>So a crossing dismisses it on the frame it appears and puts a few frames of refraction over
 * the seam instead. Nothing else is touched — any other dimension change, including every vanilla
 * one, keeps the overlay — because the only thing that reaches this is a client that was being told
 * by the server, a fraction of a second earlier, that it was inside a Warping break.
 */
@Mixin(ClientPacketListener.class)
public abstract class CrossingMembraneMixin {
    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void hgos$membrane(ClientboundRespawnPacket packet, CallbackInfo ci) {
        com.hexgodofstories.client.WarpCrossingClient.respawned();
    }
}
