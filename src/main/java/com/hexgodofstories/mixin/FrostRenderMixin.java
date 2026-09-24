package com.hexgodofstories.mixin;

import com.hexgodofstories.client.FrostClient;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The mob retains its own geometry and renderer; only its base skin becomes ice. */
@Mixin(LivingEntityRenderer.class)
public abstract class FrostRenderMixin<T extends LivingEntity,M extends EntityModel<T>> {
    private static final ResourceLocation HGOS_ICE=new ResourceLocation("minecraft","textures/block/ice.png");
    @Shadow protected abstract RenderType getRenderType(T entity,boolean bodyVisible,boolean translucent,boolean glowing);

    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType hgos$iceSkin(LivingEntityRenderer<T,M> renderer,T entity,boolean visible,boolean translucent,boolean glowing) {
        return FrostClient.frozen(entity.getId())?RenderType.entityTranslucent(HGOS_ICE)
            :getRenderType(entity,visible,translucent,glowing);
    }
}
