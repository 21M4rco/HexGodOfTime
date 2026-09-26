package com.hexgodofstories.mixin;

import com.hexgodofstories.client.ClientState;
import com.hexgodofstories.client.ErasureRenderer;
import com.hexgodofstories.client.WoundAnchor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/**
 * Pass the fade only to the affected entity's registered renderer, including custom mod renderers.
 *
 * <p>A body held in stopped time is also drawn at one fixed instant. Everything a renderer animates
 * between ticks — limb swing, idle motion, a dropped item's spin and bob, wings, tails, keyframed
 * animations — is interpolated with the partial tick, and with the body's ticks withheld that
 * interpolation would replay the same fraction of motion every tick. A constant partial tick holds it.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ErasureRenderMixin {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private <E extends Entity> void hgos$fade(EntityRenderer<E> renderer,E entity,float yaw,float partial,
                                             PoseStack pose,MultiBufferSource buffers,int light) {
        if(ErasureRenderer.consumed(entity))return;
        float instant=ClientState.suspended(entity)?1f:partial;
        WoundAnchor.beginEntity(entity,instant);
        try {
            renderer.render(entity,yaw,instant,pose,ErasureRenderer.fadingBuffers(entity,instant,pose,buffers),light);
        } finally {WoundAnchor.endEntity();}
    }
}
