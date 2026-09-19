package com.loki.mixin;
import com.loki.server.TemporalEngine;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovementMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
    private void loki$hold(ServerboundMovePlayerPacket packet,CallbackInfo ci) {if(player.serverLevel().getServer().isSameThread()&&TemporalEngine.frozen(player))ci.cancel();}
}
