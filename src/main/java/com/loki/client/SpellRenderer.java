package com.loki.client;
import com.loki.entity.SpellProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class SpellRenderer extends EntityRenderer<SpellProjectile> {
    public SpellRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(SpellProjectile e){return LokiLayer.MATERIAL;}
    @Override public void render(SpellProjectile e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){pose.pushPose();
        if(e.style()==2){pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partial,e.yRotO,e.getYRot())));pose.mulPose(Axis.XP.rotationDegrees(90-Mth.lerp(partial,e.xRotO,e.getXRot())));WeaponRenderer.draw(0,pose,buffers,light,1);}
        else {var out=buffers.getBuffer(RenderType.entityTranslucent(WorldEffects.WHITE));float size=e.style()==1?.16f:.07f;WorldEffects.ribbon(pose,out,Vec3.ZERO,e.getDeltaMovement().normalize().scale(-.8),size,0x42ef86,.85f);}
        pose.popPose();super.render(e,yaw,partial,pose,buffers,light);
    }
}
