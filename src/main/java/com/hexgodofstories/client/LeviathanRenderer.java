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
        RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableBlend();
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
        glow(e,yaw,partial,p);
        super.render(e,yaw,partial,p,source,light);
    }
    /** Unlit cyan emission with additive halos; depth testing keeps it behind terrain and the body. */
    private static void glow(AbyssalLeviathan e,float yaw,float partial,PoseStack p){
        RenderSystem.enableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.enableBlend();
        RenderSystem.blendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA,org.lwjgl.opengl.GL11.GL_ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.setShaderColor(1,1,1,1);
        try{
            double time=e.tickCount+partial;Vec3 origin=e.position();
            BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
            for(int i=1;i<=21;i++){
                Vec3 local=e.tail(i*3).subtract(origin);double size=2.55*(1-i/24.0);
                float pulse=(float)(.6+.25*Math.sin(time*.065-i*.48));
                for(int side:new int[]{-1,1}){
                    Vec3 spot=local.add(side*size*.94,1.4+size*.3,0);
                    WarpMesh.sphere(b,p.last().pose(),spot,.12,.16,.20,0x54dcff,pulse,8,0,false);
                    WarpMesh.sphere(b,p.last().pose(),spot,.24,.28,.32,0x168bff,pulse*.16f,8,0,false);
                }
                Vec3 tip=local.add(0,2.6+size*1.3,0);
                WarpMesh.ribbon(b,p.last().pose(),tip.add(0,-.55,-.04),tip,.055,0x80f5ff,pulse);
            }
            BufferUploader.drawWithShader(b.end());
            p.pushPose();p.mulPose(Axis.YP.rotationDegrees(-yaw));p.mulPose(Axis.XP.rotationDegrees(e.getXRot()));
            try{
                b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);var m=p.last().pose();
                for(int side:new int[]{-1,1}){
                    Vec3 eye=new Vec3(side*2.30,2.2,1.9);
                    WarpMesh.sphere(b,m,eye,.24,.17,.30,0xb8ffff,1,12,0,false);
                    WarpMesh.sphere(b,m,eye,.40,.31,.44,0x249fff,.24f,12,0,false);
                    for(int g=0;g<4;g++)for(int j=0;j<8;j++){
                        double z=.8-g*.5,y=.85+j*.23;
                        Vec3 a=gill(side,y,z),c=gill(side,y+.23,z);
                        WarpMesh.ribbon(b,m,a,c,.13,0x197bdd,.15f);
                        WarpMesh.ribbon(b,m,a,c,.036,0x5ceaff,e.striking()?1:.7f);
                    }
                    WarpMesh.ribbon(b,m,new Vec3(side*7,.13,-4),new Vec3(side*3,1.03,-5),.045,0x4ae0ff,.55f);
                }
                BufferUploader.drawWithShader(b.end());
            }finally{p.popPose();}
        }finally{RenderSystem.depthMask(true);RenderSystem.defaultBlendFunc();RenderSystem.disableBlend();RenderSystem.enableCull();}
    }
    private static Vec3 gill(int side,double y,double z){
        double x=2.5*Math.sqrt(Math.max(0,1-Math.pow((y-1.8)/1.75,2)-Math.pow((z-1)/3.5,2)));
        return new Vec3(side*(x+.035),y,z);
    }
    private static void fang(BufferBuilder b,org.joml.Matrix4f m,double x,double y,double z,double tx,double ty,double tz){Vec3 tip=new Vec3(tx,ty,tz);for(int i=0;i<4;i++){double a=i*Math.PI/2,c=(i+1)*Math.PI/2;WarpMesh.quad(b,m,new Vec3(x+Math.cos(a)*.16,y,z+Math.sin(a)*.16),new Vec3(x+Math.cos(c)*.16,y,z+Math.sin(c)*.16),tip,tip,0xd4d8c6,1);}}
}
