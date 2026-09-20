package com.loki.mixin;

import com.loki.client.ErasureRenderer;
import com.loki.client.WoundAnchor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Pass the fade only to the affected entity's registered renderer, including custom mod renderers. */
@Mixin(EntityRenderDispatcher.class)
public abstract class ErasureRenderMixin {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private <E extends Entity> void loki$fade(EntityRenderer<E> renderer,E entity,float yaw,float partial,
                                             PoseStack pose,MultiBufferSource buffers,int light) {
        if(ErasureRenderer.consumed(entity))return;
        WoundAnchor.beginEntity(entity,partial);
        try {
            renderer.render(entity,yaw,partial,pose,ErasureRenderer.fadingBuffers(entity,partial,pose,buffers),light);
        } finally {WoundAnchor.endEntity();}
    }
}
