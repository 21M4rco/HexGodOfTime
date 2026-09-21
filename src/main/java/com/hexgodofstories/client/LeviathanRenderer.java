package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.AbyssalLeviathan;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Articulated thirty-block predator: plated body, gills, fins and two banks of interlocking fangs. */
public final class LeviathanRenderer extends EntityRenderer<AbyssalLeviathan> {
    public LeviathanRenderer(EntityRendererProvider.Context c){super(c);shadowRadius=0;}
    @Override public boolean shouldRender(AbyssalLeviathan e,net.minecraft.client.renderer.culling.Frustum f,double x,double y,double z){return f.isVisible(e.getBoundingBox().inflate(35));}
    @Override public ResourceLocation getTextureLocation(AbyssalLeviathan e){return HexGodOfStories.id("textures/white.png");}
    @Override public void render(AbyssalLeviathan e,float yaw,float partial,PoseStack p,MultiBufferSource source,int light){
        // Flush standard entity buffers before this untextured volumetric mesh.
        if(source instanceof MultiBufferSource.BufferSource buffers)buffers.endBatch();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.disableCull();
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        Vec3 origin=e.position();
        for(int i=1;i<=21;i++){
            Vec3 local=e.tail(i*3).subtract(origin);double size=2.55*(1-i/24.0);
            WarpMesh.sphere(b,p.last().pose(),local.add(0,1.4,0),size,size*.8,2, i%3==0?0x1f3841:0x101e29,1,12,0,false);
            Vec3 crest=local.add(0,1.4+size,0);WarpMesh.quad(b,p.last().pose(),crest.add(-.1,0,-1),crest.add(.1,0,1),crest.add(0,1.2+size*.3,0),crest.add(0,1.2+size*.3,0),0x244553,1);
        }
        BufferUploader.drawWithShader(b.end());
        p.pushPose();p.mulPose(Axis.YP.rotationDegrees(-yaw));p.mulPose(Axis.XP.rotationDegrees(e.getXRot()));
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);var m=p.last().pose();
        WarpMesh.sphere(b,m,new Vec3(0,1.8,1),2.5,1.75,3.5,0x182d39,1,24,0,false);
        WarpMesh.sphere(b,m,new Vec3(0,1.7,3.35),1.9,1.05,.9,0x020306,1,20,0,false);
        double jaw=e.striking()?1.25:.3;
        WarpMesh.box(b,m,-1.7,.4-jaw,1.8,3.4,.55,3,0x243943,1);
        for(int side:new int[]{-1,1}){
            for(int i=0;i<9;i++){
                double z=1.7+i*.35,x=side*(1.55-(i>5?(i-5)*.22:0));
                fang(b,m,x,2.55,z,x*.92,1.3,z+.17);fang(b,m,x,.8-jaw,z,x*.92,1.65-jaw,z+.2);
            }
            WarpMesh.sphere(b,m,new Vec3(side*2.12,2.2,1.9),.22,.16,.28,0xa0dbcf,1,12,0,false);
            for(int g=0;g<4;g++)WarpMesh.box(b,m,side*2.2,1,1-g*.5,.13,1.4,.12,0x577584,1);
            WarpMesh.quad(b,m,new Vec3(side*1.8,1,-1),new Vec3(side*7,.1,-4),new Vec3(side*3,1,-5),new Vec3(side*1.8,1,-1),0x25414e,1);
        }
        BufferUploader.drawWithShader(b.end());p.popPose();RenderSystem.enableCull();
        super.render(e,yaw,partial,p,source,light);
    }
    private static void fang(BufferBuilder b,org.joml.Matrix4f m,double x,double y,double z,double tx,double ty,double tz){Vec3 tip=new Vec3(tx,ty,tz);for(int i=0;i<4;i++){double a=i*Math.PI/2,c=(i+1)*Math.PI/2;WarpMesh.quad(b,m,new Vec3(x+Math.cos(a)*.16,y,z+Math.sin(a)*.16),new Vec3(x+Math.cos(c)*.16,y,z+Math.sin(c)*.16),tip,tip,0xd4d8c6,1);}}
}
