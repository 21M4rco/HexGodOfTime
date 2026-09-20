package com.loki.client;

import com.loki.entity.StarfallEntity;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The falling star itself: a small, very bright celestial core wrapped in layered green haze, with a
 * tail drawn back along the path it came in on. It brightens and lengthens as it closes, so the last
 * second before impact reads as the dangerous one. The debris and nebula around it are particles,
 * emitted from the ambient pass; this is only the body.
 */
public final class StarRenderer extends EntityRenderer<StarfallEntity> {
    private static final int CORE=0xd8ffe8,HALO=0x3dffa0,DEEP=0x0f9f66;

    public StarRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(StarfallEntity e){return WorldEffects.WHITE;}
    @Override public boolean shouldRender(StarfallEntity e,net.minecraft.client.renderer.culling.Frustum frustum,double x,double y,double z){return true;}

    @Override public void render(StarfallEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        float charge=e.charge();
        float pulse=.82f+.18f*Mth.sin((e.tickCount+partial)*.55f);
        float size=(.34f+.30f*charge)*pulse;
        VertexConsumer out=buffers.getBuffer(RenderType.entityTranslucentEmissive(WorldEffects.WHITE));
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f right=new Vector3f(1,0,0).rotate(camera.rotation());
        Vector3f up=new Vector3f(0,1,0).rotate(camera.rotation());
        Vec3 r=new Vec3(right.x,right.y,right.z),u=new Vec3(up.x,up.y,up.z);

        // Three billboards: a wide soft haze, a tighter green shell and a small white-hot centre.
        billboard(pose,out,r,u,Vec3.ZERO,size*2.6f,DEEP,.20f+.16f*charge);
        billboard(pose,out,r,u,Vec3.ZERO,size*1.5f,HALO,.42f+.26f*charge);
        billboard(pose,out,r,u,Vec3.ZERO,size*.62f,CORE,.92f);

        // A tail back along the flight line, tapering and fading toward its end.
        Vec3 travel=e.getDeltaMovement();
        if(travel.lengthSqr()>1e-6) {
            Vec3 back=travel.normalize().scale(-1);
            int steps=6;
            for(int i=0;i<steps;i++) {
                float t=i/(float)steps;
                Vec3 at=back.scale(.45+i*(.55+charge*.35));
                billboard(pose,out,r,u,at,size*(1.25f-t)*(.85f+charge*.4f),i%2==0?HALO:DEEP,(1-t)*(.26f+.2f*charge));
            }
        }
    }

    private static void billboard(PoseStack pose,VertexConsumer out,Vec3 r,Vec3 u,Vec3 at,float size,int colour,float alpha) {
        if(alpha<=.004f||size<=1e-4f)return;
        Vec3 rr=r.scale(size),uu=u.scale(size);
        Vec3[] corners={at.subtract(rr).subtract(uu),at.add(rr).subtract(uu),at.add(rr).add(uu),at.subtract(rr).add(uu)};
        float[][] quad=new float[4][3];
        for(int i=0;i<4;i++)quad[i]=new float[]{(float)corners[i].x,(float)corners[i].y,(float)corners[i].z};
        WorldEffects.quad(pose,out,quad,new float[][]{{0,1},{1,1},{1,0},{0,0}},15728880,colour,alpha);
    }
}
