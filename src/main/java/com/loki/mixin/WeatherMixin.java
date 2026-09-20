package com.loki.mixin;

import com.loki.client.WorldEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rain and snow are drawn from a running tick counter, so a stopped moment has to stop that counter
 * too or the sky keeps falling through a frozen battlefield. Holding both the counter and the frame
 * fraction at the instant the hold began leaves every streak suspended exactly where it was, and
 * releasing them lets the weather carry on from the same phase rather than jumping.
 *
 * <p>Both injections are optional: if a future mapping moves this method the weather simply keeps
 * falling instead of the game refusing to start.
 */
@Mixin(LevelRenderer.class)
public abstract class WeatherMixin {
    @Shadow private int ticks;
    @Unique private int loki$saved;
    @Unique private boolean loki$held;

    @Inject(method="renderSnowAndRain",at=@At("HEAD"),require=0)
    private void loki$hold(LightTexture light,float partial,double x,double y,double z,CallbackInfo ci) {
        loki$held=false;
        long since=WorldEffects.heldSince(x,y,z);
        if(since==Long.MIN_VALUE)return;
        loki$saved=ticks;
        ticks=(int)(since&0x7fffffffL);
        loki$held=true;
    }

    @ModifyVariable(method="renderSnowAndRain",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private float loki$still(float partial) {
        // Computed independently of the head injection above; two handlers on one instruction have
        // no guaranteed order between them.
        var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return WorldEffects.heldSince(camera.x,camera.y,camera.z)==Long.MIN_VALUE?partial:0;
    }

    @Inject(method="renderSnowAndRain",at=@At("RETURN"),require=0)
    private void loki$release(LightTexture light,float partial,double x,double y,double z,CallbackInfo ci) {
        if(loki$held){ticks=loki$saved;loki$held=false;}
    }
}
