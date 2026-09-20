package com.loki.client;

import com.loki.entity.SpellProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Sorcery in flight: a short tapered wisp rather than a sprite, so it reads as light with direction. */
public final class SpellRenderer extends EntityRenderer<SpellProjectile> {
    public SpellRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(SpellProjectile e){return WorldEffects.WHITE;}
    @Override public void render(SpellProjectile e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        var out=buffers.getBuffer(RenderType.entityTranslucent(WorldEffects.WHITE));
        float size=e.style()==1?.15f:.07f;
        Vec3 tail=e.getDeltaMovement().normalize().scale(e.style()==1?-1.1:-.75);
        pose.pushPose();
        WorldEffects.ribbon(pose,out,Vec3.ZERO,tail,size,0x42ef86,.85f);
        WorldEffects.ribbon(pose,out,Vec3.ZERO,tail.scale(.45),size*.55f,0xe6ffe8,.9f);
        pose.popPose();
        super.render(e,yaw,partial,pose,buffers,light);
    }
}
