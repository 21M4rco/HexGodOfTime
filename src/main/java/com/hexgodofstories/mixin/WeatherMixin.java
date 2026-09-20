package com.hexgodofstories.mixin;

import com.hexgodofstories.client.WorldEffects;
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
    @Unique private int hgos$saved;
    @Unique private boolean hgos$held;

    @Inject(method="renderSnowAndRain",at=@At("HEAD"),require=0)
    private void hgos$hold(LightTexture light,float partial,double x,double y,double z,CallbackInfo ci) {
        hgos$held=false;
        long since=WorldEffects.heldSince(x,y,z);
        if(since==Long.MIN_VALUE)return;
        hgos$saved=ticks;
        ticks=(int)(since&0x7fffffffL);
        hgos$held=true;
    }

    @ModifyVariable(method="renderSnowAndRain",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private float hgos$still(float partial) {
        // Computed independently of the head injection above; two handlers on one instruction have
        // no guaranteed order between them.
        var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return WorldEffects.heldSince(camera.x,camera.y,camera.z)==Long.MIN_VALUE?partial:0;
    }

    @Inject(method="renderSnowAndRain",at=@At("RETURN"),require=0)
    private void hgos$release(LightTexture light,float partial,double x,double y,double z,CallbackInfo ci) {
        if(hgos$held){ticks=hgos$saved;hgos$held=false;}
    }
}
