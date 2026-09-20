package com.loki.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.Random;

/** A continuous emerald nebula and star sphere, baked once to a GPU buffer. No vanilla End sky. */
public final class RealmSky extends DimensionSpecialEffects {
    private static VertexBuffer galaxy;
    public RealmSky(){super(Float.NaN,false,SkyType.NONE,true,false);}
    @Override public Vec3 getBrightnessDependentFogColor(Vec3 color,float sun){return new Vec3(.012,.045,.035);}
    @Override public boolean isFoggyAt(int x,int z){return false;}
    @Override public float[] getSunriseColor(float time,float partial){return null;}
    @Override public boolean renderClouds(ClientLevel level,int ticks,float partial,PoseStack pose,double x,double y,double z,Matrix4f projection){return true;}
    @Override public boolean renderSnowAndRain(ClientLevel level,int ticks,float partial,LightTexture light,double x,double y,double z){return true;}
    @Override public boolean tickRain(ClientLevel level,int ticks,Camera camera){return true;}

    @Override public boolean renderSky(ClientLevel level,int ticks,float partial,PoseStack pose,Camera camera,
                                       Matrix4f projection,boolean foggy,Runnable setupFog) {
        // Keep vanilla's underwater/lava/powder-snow visibility rules.
        if(foggy)return true;
        if(galaxy==null)bake();
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees((ticks+partial)*.0018f));
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        FogRenderer.setupNoFog();
        RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            galaxy.bind();
            galaxy.drawWithShader(pose.last().pose(),projection,GameRenderer.getPositionColorShader());
        } finally {
            VertexBuffer.unbind();
            pose.popPose();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            setupFog.run();
        }
        return true;
    }
    public static void clear(){if(galaxy!=null){galaxy.close();galaxy=null;}}

    private static void bake() {
        BufferBuilder b=Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        final int longitude=192,latitude=96;
        for(int y=0;y<latitude;y++)for(int x=0;x<longitude;x++) {
            nebula(b,x,y,longitude,latitude);nebula(b,x+1,y,longitude,latitude);
            nebula(b,x+1,y+1,longitude,latitude);nebula(b,x,y+1,longitude,latitude);
        }
        Random random=new Random(0x5947474452415349L);
        for(int i=0;i<2200;i++) {
            Vec3 direction=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).normalize();
            Vec3 centre=direction.scale(98);
            Vec3 right=direction.cross(Math.abs(direction.y)>.9?new Vec3(1,0,0):new Vec3(0,1,0)).normalize();
            Vec3 up=direction.cross(right).normalize();
            double size=.018+Math.pow(random.nextDouble(),6)*.17;
            float bright=.42f+random.nextFloat()*.58f;
            for(int[] corner:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}}) {
                Vec3 v=centre.add(right.scale(corner[0]*size)).add(up.scale(corner[1]*size));
                b.vertex(v.x,v.y,v.z).color(bright*.8f,bright,bright*.88f,1).endVertex();
            }
        }
        galaxy=new VertexBuffer(VertexBuffer.Usage.STATIC);
        galaxy.bind();galaxy.upload(b.end());VertexBuffer.unbind();
    }
    private static void nebula(BufferBuilder b,int lon,int lat,int width,int height) {
        double a=lon*Math.PI*2/width,v=lat*Math.PI/height;
        double x=Math.cos(a)*Math.sin(v),y=Math.cos(v),z=Math.sin(a)*Math.sin(v);
        // A tilted galactic band with branching, turbulent emerald clouds on both sides.
        double band=Math.exp(-Math.pow((y*.81+x*.38-z*.44)/.30,2));
        double cloud=fbm(x*3.8+11,y*3.8+6,z*3.8-4);
        double filaments=fbm(x*11+cloud*2,y*11,z*11);
        double glow=band*Math.max(0,cloud-.29)*1.55;
        double bright=glow*Math.max(0,filaments-.48)*2.1;
        float r=(float)(.007+glow*.16+bright*.30);
        float g=(float)(.018+glow*.64+bright*.35);
        float blue=(float)(.025+glow*.37+bright*.45);
        b.vertex(x*100,y*100,z*100).color(Mth.clamp(r,0,1),Mth.clamp(g,0,1),Mth.clamp(blue,0,1),1).endVertex();
    }
    private static double fbm(double x,double y,double z) {
        double sum=0,weight=.53;
        for(int i=0;i<5;i++){sum+=noise(x,y,z)*weight;x=x*2.03+3.1;y=y*2.03-1.7;z=z*2.03+5.2;weight*=.5;}
        return sum;
    }
    private static double noise(double x,double y,double z) {
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y),iz=(int)Math.floor(z);
        double fx=smooth(x-ix),fy=smooth(y-iy),fz=smooth(z-iz);
        double lo=lerp(fy,lerp(fx,hash(ix,iy,iz),hash(ix+1,iy,iz)),lerp(fx,hash(ix,iy+1,iz),hash(ix+1,iy+1,iz)));
        double hi=lerp(fy,lerp(fx,hash(ix,iy,iz+1),hash(ix+1,iy,iz+1)),lerp(fx,hash(ix,iy+1,iz+1),hash(ix+1,iy+1,iz+1)));
        return lerp(fz,lo,hi);
    }
    private static double smooth(double v){return v*v*(3-2*v);}
    private static double lerp(double t,double a,double b){return a+(b-a)*t;}
    private static double hash(int x,int y,int z) {
        int h=x*374761393+y*668265263+z*2147483647;h=(h^(h>>>13))*1274126177;
        return ((h^(h>>>16))&0x7fffffff)/(double)0x7fffffff;
    }
}
