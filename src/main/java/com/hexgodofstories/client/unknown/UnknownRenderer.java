package com.hexgodofstories.client.unknown;

import com.hexgodofstories.unknown.UnknownEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Two times the actual supplied model: roughly seven blocks tall with a long articulated tail. */
public final class UnknownRenderer extends GeoEntityRenderer<UnknownEntity> {
    public UnknownRenderer(EntityRendererProvider.Context context){
        super(context,new UnknownModel());this.shadowRadius=2.4f;
    }
    @Override public void render(UnknownEntity entity,float yaw,float partial,PoseStack pose,
                                 MultiBufferSource buffers,int light){
        pose.pushPose();pose.scale(2f,2f,2f);
        super.render(entity,yaw,partial,pose,buffers,light);
        pose.popPose();
    }
    @Override public boolean shouldRender(UnknownEntity entity,Frustum frustum,double x,double y,double z){
        // The tail can remain in view when the entity's origin is well outside the frustum.
        return entity.distanceToSqr(x,y,z)<180*180;
    }
}
