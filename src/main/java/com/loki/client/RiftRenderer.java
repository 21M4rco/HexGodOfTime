package com.loki.client;

import com.loki.entity.RiftEntity;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.*;

/**
 * The break in reality. A ring of irregular glass shards is struck outward from a single point of impact,
 * the fractures between them race ahead of the shards themselves, and behind it all sits a dark opening
 * that is not part of this world. The layout is derived from the entity's seed, so every viewer sees the
 * same fracture and it stays put frame to frame instead of shimmering.
 */
public final class RiftRenderer extends EntityRenderer<RiftEntity> {
    private static final int SHARDS=26,RAYS=13;
    private static final float HEIGHT=1.35f,VOID_X=.62f,VOID_Y=.94f;
    private static float[][] layout;

    public RiftRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(RiftEntity e){return WorldEffects.WHITE;}
    @Override public boolean shouldRender(RiftEntity e,net.minecraft.client.renderer.culling.Frustum frustum,double x,double y,double z){return true;}
    public static void clear(){layout=null;}

    /** Angle, outer radius and depth offset per shard, generated once and reused for every rift. */
    private static float[][] layout() {
        if(layout!=null)return layout;
        layout=new float[SHARDS][3];
        RandomSource random=RandomSource.create(0x10C1);
        for(int i=0;i<SHARDS;i++) {
            float angle=i*Mth.TWO_PI/SHARDS+(random.nextFloat()-.5f)*.16f;
            layout[i]=new float[]{angle,.95f+random.nextFloat()*.85f,(random.nextFloat()-.5f)*.16f};
        }
        return layout;
    }

    @Override public void render(RiftEntity e,float entityYaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        float open=e.opening(partial),close=e.closing(partial);
        if(open<=0)return;
        int seed=e.seed();
        pose.pushPose();
        pose.translate(0,HEIGHT,0);
        pose.mulPose(Axis.YP.rotationDegrees(-e.getYRot()));
        VertexConsumer out=buffers.getBuffer(RenderType.entityTranslucent(WorldEffects.WHITE));
        float ease=open*open*(3-2*open);
        float fade=Math.min(1,close);

        // The opening itself: a dark aperture that widens as the surface gives way.
        float vx=VOID_X*ease,vy=VOID_Y*ease;
        for(int i=0;i<RAYS*2;i++) {
            float a=i*Mth.TWO_PI/(RAYS*2),b=(i+1)*Mth.TWO_PI/(RAYS*2);
            quad(pose,out,0,0,Mth.cos(a)*vx,Mth.sin(a)*vy,Mth.cos(b)*vx,Mth.sin(b)*vy,0,0,-.02f,0x04100c,fade*.94f);
        }
        // Timeline light bleeding through from the far side.
        for(int i=0;i<6;i++) {
            float t=(e.tickCount+partial)*.014f+i*.9f;
            float y0=(Mth.sin(t)*.7f)*vy,y1=(Mth.sin(t+1.1f)*.7f)*vy;
            quad(pose,out,-vx*.85f,y0-.03f,-vx*.85f,y0+.03f,vx*.85f,y1+.03f,vx*.85f,y1-.03f,-.015f,
                i%2==0?0xd8c07a:0x53d69a,fade*.34f*ease);
        }

        float[][] shards=layout();
        for(int i=0;i<SHARDS;i++) {
            float[] a=shards[i],b=shards[(i+1)%SHARDS];
            float drift=(1-ease)*.55f+(1-fade)*1.4f;
            float depth=a[2]+drift*.35f;
            float inner=1+drift*.5f,outer=ease+drift;
            float ax=Mth.cos(a[0]),ay=Mth.sin(a[0]),bx=Mth.cos(b[0]),by=Mth.sin(b[0]);
            float tone=.62f+((seed>>i%16&1)==0?.22f:0f)+(i%3)*.05f;
            int colour=tint(tone);
            quad(pose,out,
                ax*vx*inner,ay*vy*inner,bx*vx*inner,by*vy*inner,
                bx*VOID_X*b[1]*outer,by*VOID_Y*b[1]*outer,ax*VOID_X*a[1]*outer,ay*VOID_Y*a[1]*outer,
                depth,colour,fade*.55f*ease);
            // Bright fracture along the seam between neighbouring shards.
            line(pose,out,ax*vx*inner,ay*vy*inner,ax*VOID_X*a[1]*outer,ay*VOID_Y*a[1]*outer,depth+.01f,
                .012f,0xeaf4e4,fade*Math.min(1,ease*1.6f));
        }
        // Fractures race past the shards while the surface is still breaking.
        float reach=Math.min(1,open*1.9f);
        for(int i=0;i<SHARDS;i++) {
            float[] a=shards[i];
            float ax=Mth.cos(a[0]),ay=Mth.sin(a[0]);
            float far=a[1]*reach*1.55f;
            line(pose,out,ax*VOID_X*a[1],ay*VOID_Y*a[1],ax*VOID_X*far,ay*VOID_Y*far,a[2]+.02f,
                .007f*(1-reach*.6f),0xd8e9d4,fade*(1-reach)*.9f);
        }
        pose.popPose();
    }

    private static int tint(float tone) {
        int r=Mth.clamp((int)(tone*215),0,255),g=Mth.clamp((int)(tone*238),0,255),b=Mth.clamp((int)(tone*224),0,255);
        return r<<16|g<<8|b;
    }
    private static void quad(PoseStack pose,VertexConsumer out,float x1,float y1,float x2,float y2,float x3,float y3,float x4,float y4,
                             float z,int colour,float alpha) {
        WorldEffects.quad(pose,out,
            new float[][]{{x1,y1,z},{x2,y2,z},{x3,y3,z},{x4,y4,z}},
            new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,colour,alpha);
    }
    private static void line(PoseStack pose,VertexConsumer out,float x1,float y1,float x2,float y2,float z,float width,int colour,float alpha) {
        float dx=x2-x1,dy=y2-y1;
        float length=Mth.sqrt(dx*dx+dy*dy);
        if(length<1e-4f)return;
        float nx=-dy/length*width,ny=dx/length*width;
        WorldEffects.quad(pose,out,
            new float[][]{{x1-nx,y1-ny,z},{x1+nx,y1+ny,z},{x2+nx,y2+ny,z},{x2-nx,y2-ny,z}},
            new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,colour,alpha);
    }
}
