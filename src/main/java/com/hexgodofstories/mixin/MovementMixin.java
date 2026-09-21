package com.hexgodofstories.mixin;
import com.hexgodofstories.server.Erasure;
import com.hexgodofstories.server.TemporalEngine;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Movement the server refuses outright.
 *
 * <p>A suspended moment and a body mid-erasure both stop a player where they stand, position and
 * rotation alike.
 *
 * <p>Holding the torrent is deliberately not handled here. Refusing a caster's move packets would take
 * their rotation with it on any tick that carried both, and they need to keep aiming. The plant is
 * enforced instead by {@link com.hexgodofstories.server.TimeBranch}, which zeroes their motion every tick and puts
 * a drifting body back where the charge began — so the feet are fixed by the server either way, and the
 * head stays free.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovementMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Inject(method="handleMovePlayer",at=@At("RETURN"))
    private void hgos$radialGround(ServerboundMovePlayerPacket packet,CallbackInfo ci) {
        if(com.hexgodofstories.warping.MoonGravity.active(player)||com.hexgodofstories.warping.Destination.from(player.level())==com.hexgodofstories.warping.Destination.GRAVITY_WELL) {
            clientIsFloating=false;
            player.setOnGround(com.hexgodofstories.warping.MoonGravity.grounded(player));
            player.fallDistance=0;
        }
    }
    @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
    private void hgos$hold(ServerboundMovePlayerPacket packet,CallbackInfo ci) {
        if(!player.serverLevel().getServer().isSameThread())return;
        if(TemporalEngine.frozen(player)||Erasure.erasing(player)){ci.cancel();return;}
        if(com.hexgodofstories.warping.Destination.from(player.level())==com.hexgodofstories.warping.Destination.GRAVITY_WELL&&!player.isSpectator()) {
            float yaw=packet.getYRot(player.getYRot()),pitch=packet.getXRot(player.getXRot());
            if(Float.isFinite(yaw)&&Float.isFinite(pitch)){player.setYRot(yaw);player.setXRot(net.minecraft.util.Mth.clamp(pitch,-90,90));}
            clientIsFloating=false;ci.cancel();
        }
    }
}
