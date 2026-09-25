package com.hexgodofstories.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses the actual camera FOV, including sprint and potion changes, for the bubble's depth rays. */
@Mixin(GameRenderer.class)
public interface TimeStopFovAccessor {
    @Invoker("getFov") double hgos$getFov(Camera camera,float partial,boolean useFovSetting);
}
