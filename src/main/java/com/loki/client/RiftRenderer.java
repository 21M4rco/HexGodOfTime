package com.loki.client;

import com.loki.entity.RiftEntity;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.*;
import java.util.*;

/** Seeded, jagged mirror fragments: connected seams remain visible for the whole crossing window. */
public final class RiftRenderer extends EntityRenderer<RiftEntity> {
    private static final int SHARDS=19;
    private static final Map<Integer,float[][]> LAYOUTS=new LinkedHashMap<>();
    public RiftRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(RiftEntity e){return WorldEffects.WHITE;}
    @Override public boolean shouldRender(RiftEntity e,net.minecraft.client.renderer.culling.Frustum frustum,double x,double y,double z){return true;}
    public static void clear(){LAYOUTS.clear();}

    private static float[][] layout(int seed) {
        float[][] cached=LAYOUTS.get(seed);if(cached!=null)return cached;
        RandomSource random=RandomSource.create(seed);
        float[][] points=new float[SHARDS][7];
        for(int i=0;i<SHARDS;i++) {
            float a=(i+(random.nextFloat()-.5f)*.7f)*Mth.TWO_PI/SHARDS;
            float inner=.78f+random.nextFloat()*.32f,mid=1.45f+random.nextFloat()*.85f;
            float reach=2.6f+random.nextFloat()*1.35f,bend=(random.nextFloat()-.5f)*.28f;
            points[i]=new float[]{Mth.cos(a)*inner,Mth.sin(a)*inner*1.48f,
                Mth.cos(a+bend)*mid,Mth.sin(a+bend)*mid*1.2f,
                Mth.cos(a-bend*.6f)*reach,Mth.sin(a-bend*.6f)*reach,
                (random.nextFloat()-.5f)*.20f};
        }
        if(LAYOUTS.size()>=64)LAYOUTS.remove(LAYOUTS.keySet().iterator().next());
        LAYOUTS.put(seed,points);return points;
    }
    @Override public void render(RiftEntity e,float entityYaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        float open=e.opening(partial),fade=Mth.clamp(e.closing(partial),0,1);
        if(open<=0||fade<=0)return;
        float ease=open*open*(3-2*open),crack=Mth.clamp(open*2.8f,0,1);
        float[][] p=layout(e.seed());
        pose.pushPose();pose.translate(0,1.48,0);pose.mulPose(Axis.YP.rotationDegrees(-e.getYRot()));
        VertexConsumer out=buffers.getBuffer(RenderType.entityTranslucent(WorldEffects.WHITE));
        for(int i=0;i<SHARDS;i++) {
            float[] a=p[i],b=p[(i+1)%SHARDS];
            // An irregular polygon, not an ellipse. The shards open away from this torn edge.
            quad(pose,out,0,0,a[0]*ease,a[1]*ease,b[0]*ease,b[1]*ease,0,0,-.035f,0x04090b,fade*.98f);
            float drift=(1-ease)*.22f+(1-fade)*.7f,z=a[6]+drift;
            float ax=a[0]*ease,ay=a[1]*ease,bx=b[0]*ease,by=b[1]*ease;
            float mx=a[2]*(1+drift),my=a[3]*(1+drift),nx=b[2]*(1+drift),ny=b[3]*(1+drift);
            // Alternating triangles and large splinters break up the otherwise regular radial wedges.
            quad(pose,out,ax,ay,mx,my,nx,ny,bx,by,z,i%3==0?0xa7bccb:0x607780,fade*ease*(i%4==0?.36f:.19f));
            if(i%3==0)quad(pose,out,mx,my,a[4]*crack,a[5]*crack,nx,ny,mx,my,z+.012f,0xc6d9e4,fade*ease*.13f);
            seam(pose,out,ax,ay,mx*crack,my*crack,z+.025f,.018f,fade*ease);
            seam(pose,out,mx*crack,my*crack,a[4]*crack,a[5]*crack,z+.025f,.010f,fade*crack);
            seam(pose,out,ax,ay,bx,by,.035f,.018f,fade*ease);
            if(i%3!=1)seam(pose,out,mx*crack,my*crack,nx*crack,ny*crack,z+.03f,.009f,fade*crack*.8f);
            // Off-axis forks give each main crack a brittle, branching mirror-break silhouette.
            float fx=Mth.lerp(.62f,mx,a[4])*crack,fy=Mth.lerp(.62f,my,a[5])*crack;
            float dx=(a[4]-mx),dy=(a[5]-my),side=i%2==0?1:-1;
            seam(pose,out,fx,fy,fx+dx*.26f-dy*.34f*side,fy+dy*.26f+dx*.34f*side,z+.04f,.008f,fade*crack*.8f);
        }
        pose.popPose();
    }
    private static void seam(PoseStack pose,VertexConsumer out,float x1,float y1,float x2,float y2,float z,float width,float alpha) {
        line(pose,out,x1,y1,x2,y2,z,width*3,0x708f9d,alpha*.16f);
        line(pose,out,x1,y1,x2,y2,z+.002f,width,0xe7f0f5,alpha);
    }
    private static void quad(PoseStack pose,VertexConsumer out,float x1,float y1,float x2,float y2,float x3,float y3,float x4,float y4,float z,int color,float alpha) {
        WorldEffects.quad(pose,out,new float[][]{{x1,y1,z},{x2,y2,z},{x3,y3,z},{x4,y4,z}},new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,color,alpha);
    }
    private static void line(PoseStack pose,VertexConsumer out,float x1,float y1,float x2,float y2,float z,float width,int color,float alpha) {
        float dx=x2-x1,dy=y2-y1,length=Mth.sqrt(dx*dx+dy*dy);if(length<1e-4f)return;
        float nx=-dy/length*width,ny=dx/length*width;
        quad(pose,out,x1-nx,y1-ny,x1+nx,y1+ny,x2+nx,y2+ny,x2-nx,y2-ny,z,color,alpha);
    }
}
