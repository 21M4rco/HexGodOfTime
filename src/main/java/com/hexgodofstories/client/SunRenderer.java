package com.hexgodofstories.client;

import com.hexgodofstories.warping.WarpMath;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** An opaque photosphere plus depth-tested, non-depth-writing plasma. No detached orbiting rings. */
public final class SunRenderer {
    private static ResourceLocation texture;
    private static VertexBuffer surface;
    public static void clear(){
        if(surface!=null){surface.close();surface=null;}
        if(texture!=null){Minecraft.getInstance().getTextureManager().release(texture);texture=null;}
    }
    private static void bake(){
        int width=512,height=256;NativeImage pixels=new NativeImage(width,height,false);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            double longitude=x/(double)(width-1)*Math.PI*2,latitude=y/(double)(height-1)*Math.PI;
            double nx=Math.sin(latitude)*Math.cos(longitude),ny=Math.cos(latitude),nz=Math.sin(latitude)*Math.sin(longitude);
            double convection=RealmSky.fbm(nx*7+4,ny*7-2,nz*7+9);
            double grain=RealmSky.fbm(nx*39+convection*3,ny*39,nz*39);
            double filaments=1-Math.abs(RealmSky.fbm(nx*15,ny*15+3,nz*15)*2-1);
            double heat=Mth.clamp((convection-.23)*1.5+(grain-.35)*.65,0,1);
            double bright=Mth.clamp((filaments-.78)*2.5,0,.35);
            int r=(int)(235+heat*20),g=(int)Math.min(255,65+heat*172+bright*85),b=(int)Math.min(210,5+heat*45+bright*195);
            pixels.setPixelRGBA(x,y,0xff000000|(b<<16)|(g<<8)|r);
        }
        DynamicTexture image=new DynamicTexture(pixels);image.setFilter(true,false);
        texture=Minecraft.getInstance().getTextureManager().register("hexgodofstories_sun_photosphere",image);
        BufferBuilder b=new BufferBuilder(512*1024);b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        int longitude=96,latitude=48;
        for(int y=0;y<latitude;y++)for(int x=0;x<longitude;x++)for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}}){
            double u=(x+corner[0])/(double)longitude,v=(y+corner[1])/(double)latitude;
            double a=u*Math.PI*2,t=v*Math.PI,r=WarpMath.SUN_RADIUS;
            b.vertex(Math.sin(t)*Math.cos(a)*r,Math.cos(t)*r,Math.sin(t)*Math.sin(a)*r)
                .uv((float)u,(float)v).color(255,255,255,255).endVertex();
        }
        surface=new VertexBuffer(VertexBuffer.Usage.STATIC);surface.bind();surface.upload(b.end());VertexBuffer.unbind();
    }
    public static void draw(PoseStack pose,double time){
        if(surface==null)bake();
        pose.pushPose();pose.translate(0,WarpMath.SUN_Y,0);pose.mulPose(Axis.YP.rotationDegrees((float)(time*.025%360)));
        RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.depthMask(true);
        RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        try{
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);RenderSystem.setShaderTexture(0,texture);
            surface.bind();surface.drawWithShader(pose.last().pose(),RenderSystem.getProjectionMatrix(),GameRenderer.getPositionTexColorShader());VertexBuffer.unbind();
            // Glow contributes color only. It cannot erase the third-person player or write a halo into depth.
            RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.enableBlend();
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=pose.last().pose();
            for(int i=4;i>=1;i--){double r=WarpMath.SUN_RADIUS+i*.7;WarpMesh.sphere(b,m,Vec3.ZERO,r,r,r,0xff941e,.014f,48,0,false);}
            for(int i=0;i<18;i++){
                double y=1-2*(i+.5)/18.0,a=i*2.399963,rad=Math.sqrt(1-y*y);
                Vec3 normal=new Vec3(Math.cos(a)*rad,y,Math.sin(a)*rad);
                Vec3 tangent=normal.cross(Math.abs(y)>.9?new Vec3(1,0,0):new Vec3(0,1,0)).normalize();
                double height=(3+i%4*1.4)*(.78+.22*Math.sin(time*.014+i*1.9));
                for(int j=0;j<24;j++){
                    double t=j/24.0,next=(j+1)/24.0;
                    Vec3 from=flare(normal,tangent,t,height),to=flare(normal,tangent,next,height);
                    double taper=.25+Math.sin(t*Math.PI)*.75;
                    WarpMesh.ribbon(b,m,from,to,.40*taper,0xff5f10,.10f);
                    WarpMesh.ribbon(b,m,from,to,.14*taper,0xffb535,.52f);
                    WarpMesh.ribbon(b,m,from,to,.045*taper,0xfff0b0,.8f);
                }
            }
            BufferUploader.drawWithShader(b.end());
        }finally{
            VertexBuffer.unbind();pose.popPose();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();RenderSystem.disableBlend();RenderSystem.enableCull();RenderSystem.setShaderColor(1,1,1,1);
        }
    }
    private static Vec3 flare(Vec3 normal,Vec3 tangent,double t,double height){
        double angle=(t-.5)*.30,radius=WarpMath.SUN_RADIUS+.025+Math.sin(t*Math.PI)*height;
        return normal.scale(Math.cos(angle)).add(tangent.scale(Math.sin(angle))).scale(radius);
    }
    private SunRenderer(){}
}
