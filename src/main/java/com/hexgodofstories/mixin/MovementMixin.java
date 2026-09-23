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
        // Sinking into a Warping pool, where there is deliberately no ground under the feet at
        // all. The floating check disconnects a player who has not descended a thirty-second of a
        // block for eighty ticks, and somebody fighting their way back out of a pool holds very
        // nearly still for as long as they can keep it up — which is the whole point of the
        // struggle and would otherwise be a way to get kicked for flying.
        if(com.hexgodofstories.warping.WarpCrossing.crossing(player)) {
            clientIsFloating=false;
            player.fallDistance=0;
        }
        // Paradise, where a fall is meant to be slow and a jump is meant to be long.
        //
        // Neither of the two things the server does with a descent suits a realm with a quarter of
        // the gravity in it. The floating check disconnects a player who has not fallen a
        // thirty-second of a block for eighty ticks, which a high jump's apex brushes against and
        // which drifting between islands sits near for far longer than a normal fall ever does.
        // And fall damage counts blocks travelled, so the crossing the realm is built around —
        // stepping off one island and landing on the next — would be paid for every time. Ground
        // detection is deliberately untouched: the player still walks, sprints and jumps normally.
        if(com.hexgodofstories.warping.Destination.from(player.level())==com.hexgodofstories.warping.Destination.PARADISE) {
            clientIsFloating=false;
            player.fallDistance=0;
        }
    }
    @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
    private void hgos$hold(ServerboundMovePlayerPacket packet,CallbackInfo ci) {
        if(!player.serverLevel().getServer().isSameThread())return;
        if(TemporalEngine.frozen(player)||Erasure.erasing(player)){ci.cancel();return;}
        if(com.hexgodofstories.warping.CandyCorruption.noLegs(player)&&!player.isSpectator()) {
            float yaw=packet.getYRot(player.getYRot()),pitch=packet.getXRot(player.getXRot());
            if(Float.isFinite(yaw)&&Float.isFinite(pitch)){player.setYRot(yaw);player.setXRot(net.minecraft.util.Mth.clamp(pitch,-90,90));}
            player.setDeltaMovement(0,player.getDeltaMovement().y,0);
            clientIsFloating=false;ci.cancel();return;
        }
        if(com.hexgodofstories.warping.Destination.from(player.level())==com.hexgodofstories.warping.Destination.GRAVITY_WELL&&!player.isSpectator()) {
            float yaw=packet.getYRot(player.getYRot()),pitch=packet.getXRot(player.getXRot());
            if(Float.isFinite(yaw)&&Float.isFinite(pitch)){player.setYRot(yaw);player.setXRot(net.minecraft.util.Mth.clamp(pitch,-90,90));}
            clientIsFloating=false;ci.cancel();
        }
    }
}
