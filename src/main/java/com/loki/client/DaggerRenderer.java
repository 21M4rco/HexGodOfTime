package com.loki.client;

import com.loki.entity.ThrownDagger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A blade in flight points where it is going. The two rotations below put the mesh's +Y axis onto the
 * velocity the same way vanilla aims an arrow, with a spin about that axis so the flat of the blade
 * catches the light.
 *
 * <p>Once it is buried in a body the entity's own position is only the coarse, once-a-tick truth the
 * server maintains. The draw is corrected to the wound itself — the exact point of impact, carried in
 * the victim's frame and swung along with whatever limb took it — so the steel stays in the chest,
 * the arm or the skull it went into while the creature walks, turns and animates, instead of sliding
 * down to the feet between updates.
 */
public final class DaggerRenderer extends EntityRenderer<ThrownDagger> {
    private static final float SCALE=.85f;
    public DaggerRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(ThrownDagger e){return LokiLayer.MATERIAL;}

    @Override public void render(ThrownDagger e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();
        Entity host=e.carrier();
        if(host!=null&&host.isAlive()) {
            Vec3 want=WoundAnchor.world(e,host,partial);
            Vec3 self=WoundAnchor.lerpPosition(e,partial);
            pose.translate(want.x-self.x,want.y-self.y,want.z-self.z);
            pose.mulPose(WoundAnchor.rotation(e,host,partial));
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partial,e.yRotO,e.getYRot())-90));
            pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partial,e.xRotO,e.getXRot())));
            float spin=e.flying()?(e.tickCount+partial)*42+e.roll():e.roll();
            pose.mulPose(Axis.XP.rotationDegrees(spin));
            pose.mulPose(Axis.ZP.rotationDegrees(-90));
        }
        pose.scale(SCALE,SCALE,SCALE);
        // The grip sits at the mesh origin, so shift back along the blade to balance it on the flight line.
        pose.translate(0,-.2,0);
        WeaponRenderer.draw(0,pose,buffers,light,e.illusory()?.65f:1);
        pose.popPose();
        super.render(e,yaw,partial,pose,buffers,light);
    }
}
