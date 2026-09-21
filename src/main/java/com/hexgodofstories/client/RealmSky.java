package com.hexgodofstories.client;

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
    private static final Matrix4f IDENTITY=new Matrix4f();
    public enum Palette { EMERALD, STELLAR, OCEAN }
    private static final java.util.Map<Palette,VertexBuffer> GALAXIES=new java.util.EnumMap<>(Palette.class);
    private static final java.util.Map<Palette,VertexBuffer> SPIRALS=new java.util.EnumMap<>(Palette.class);
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
        FogRenderer.setupNoFog();
        try{drawBackdrop(pose,projection,ticks+partial,Palette.EMERALD);}finally{setupFog.run();}
        return true;
    }

    /** The Fracture's continuous nebula, star field and spiral arms, recolored for the other realms. */
    public static void drawBackdrop(PoseStack pose,Matrix4f projection,float time,Palette palette){
        VertexBuffer galaxy=GALAXIES.computeIfAbsent(palette,RealmSky::bake);
        VertexBuffer spiral=SPIRALS.computeIfAbsent(palette,RealmSky::bakeSpiral);
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(time*.0018f));
        RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try{
            galaxy.bind();galaxy.drawWithShader(pose.last().pose(),projection,GameRenderer.getPositionColorShader());VertexBuffer.unbind();
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            renderGalaxies(pose,projection,time,spiral,palette);
            celestial(pose,time,palette);
        }finally{
            VertexBuffer.unbind();pose.popPose();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.depthMask(true);
        }
    }
    public static void clear(){GALAXIES.values().forEach(VertexBuffer::close);SPIRALS.values().forEach(VertexBuffer::close);GALAXIES.clear();SPIRALS.clear();}

    private static VertexBuffer bakeSpiral(Palette palette) {
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        Random r=new Random(0xC05A05);
        // Dust knots and star clusters form several winding arms around a soft galactic core.
        for(int i=0;i<3200;i++) {
            double t=r.nextDouble(),radius=Math.pow(t,.68)*17;
            double a=i%4*Math.PI/2+radius*.31+r.nextGaussian()*(.12+.17*t);
            double x=Math.cos(a)*radius,y=Math.sin(a)*radius;
            float size=(float)(.15+r.nextDouble()*.65),alpha=(float)(.055+(1-t)*.10);
            float red=i%4==0?.52f:.18f,green=.65f+(float)(1-t)*.35f,blue=i%4==0?.95f:.72f;
            skyQuad(b,IDENTITY,new Vec3(x,y,0),new Vec3(size,0,0),new Vec3(0,size,0),red,green,blue,alpha,palette);
        }
        for(int i=20;i>0;i--) {
            double size=i*.24;
            skyQuad(b,IDENTITY,Vec3.ZERO,new Vec3(size,0,0),new Vec3(0,size,0),.65f,1,.88f,.035f,palette);
        }
        VertexBuffer spiral=new VertexBuffer(VertexBuffer.Usage.STATIC);spiral.bind();spiral.upload(b.end());VertexBuffer.unbind();return spiral;
    }
    private static void renderGalaxies(PoseStack pose,Matrix4f projection,float time,VertexBuffer spiral,Palette palette) {
        for(int i=0;i<3;i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(35+i*119));
            pose.mulPose(Axis.XP.rotationDegrees(24+i*13));
            pose.translate(0,0,-91);
            pose.mulPose(Axis.XP.rotationDegrees(36+i*13));
            pose.mulPose(Axis.ZP.rotationDegrees(i*71+time*(i==1?-.012f:.009f)));
            float scale=(i==0?1.15f:.62f)*(palette==Palette.OCEAN?1.4f:palette==Palette.STELLAR?1.2f:1);pose.scale(scale,scale,scale);
            spiral.bind();spiral.drawWithShader(pose.last().pose(),projection,GameRenderer.getPositionColorShader());
            VertexBuffer.unbind();pose.popPose();
        }
    }
    private static void celestial(PoseStack pose,float time,Palette palette) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix=pose.last().pose();
        // Staggered shooting stars cross different parts of the sphere, with tapered luminous tails.
        for(int meteor=0;meteor<6;meteor++) {
            float phase=(time+meteor*107)%420;
            if(phase>58)continue;
            double angle=meteor*2.399963,progress=phase/58.0;
            Vec3 start=new Vec3(Math.cos(angle)*70,42+meteor%3*13,Math.sin(angle)*70);
            Vec3 tangent=new Vec3(-Math.sin(angle),-.45,Math.cos(angle)).normalize();
            Vec3 head=start.add(tangent.scale(progress*65-30)).normalize().scale(88);
            float fade=(float)Math.sin(progress*Math.PI);
            for(int j=0;j<18;j++) {
                double t=j/18.0;Vec3 a=head.subtract(tangent.scale(j*.7)),c=a.subtract(tangent.scale(.8));
                skyStreak(b,matrix,a,c,.035+(1-t)*.11,.65f,1,.87f,fade*(float)(1-t)*.8f,palette);
            }
            Vec3 right=head.normalize().cross(new Vec3(0,1,0)).normalize();
            Vec3 up=head.normalize().cross(right).normalize();
            skyQuad(b,matrix,head,right.scale(.25),up.scale(.25),.88f,1,.94f,fade,palette);
        }
        // Slow, three-dimensional orbiting wisps add movement between meteor passes.
        for(int wisp=0;wisp<7;wisp++) {
            double a=wisp*2.399963+time*.00065,height=12+Math.sin(time*.001+wisp)*17;
            Vec3 head=new Vec3(Math.cos(a)*84,height,Math.sin(a)*84);
            for(int j=0;j<22;j++) {
                double q=a-j*.007;
                Vec3 from=new Vec3(Math.cos(q)*84,height+Math.sin(j*.16)*1.6,Math.sin(q)*84);
                Vec3 to=new Vec3(Math.cos(q-.007)*84,height+Math.sin((j+1)*.16)*1.6,Math.sin(q-.007)*84);
                skyStreak(b,matrix,from,to,.06+j*.004,.28f,.9f,.78f,(1-j/22f)*.45f,palette);
            }
            Vec3 right=head.normalize().cross(new Vec3(0,1,0)).normalize(),up=head.normalize().cross(right).normalize();
            skyQuad(b,matrix,head,right.scale(.25),up.scale(.25),.8f,1,.91f,.8f,palette);
        }
        BufferUploader.drawWithShader(b.end());
    }
    private static void skyStreak(BufferBuilder b,Matrix4f m,Vec3 a,Vec3 c,double width,float r,float g,float blue,float alpha,Palette palette) {
        Vec3 along=c.subtract(a).scale(.5),side=along.cross(a).normalize().scale(width);
        skyQuad(b,m,a.lerp(c,.5),along,side,r,g,blue,alpha,palette);
    }
    private static void skyQuad(BufferBuilder b,Matrix4f m,Vec3 centre,Vec3 right,Vec3 up,float r,float g,float blue,float alpha,Palette palette) {
        for(int[] corner:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}}) {
            Vec3 v=centre.add(right.scale(corner[0])).add(up.scale(corner[1]));
            vertex(b,m,v.x,v.y,v.z,r,g,blue,alpha,palette);
        }
    }

    private static VertexBuffer bake(Palette palette) {
        BufferBuilder b=Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        final int longitude=192,latitude=96;
        for(int y=0;y<latitude;y++)for(int x=0;x<longitude;x++) {
            nebula(b,x,y,longitude,latitude,palette);nebula(b,x+1,y,longitude,latitude,palette);
            nebula(b,x+1,y+1,longitude,latitude,palette);nebula(b,x,y+1,longitude,latitude,palette);
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
                vertex(b,IDENTITY,v.x,v.y,v.z,bright*.8f,bright,bright*.88f,1,palette);
            }
        }
        VertexBuffer galaxy=new VertexBuffer(VertexBuffer.Usage.STATIC);
        galaxy.bind();galaxy.upload(b.end());VertexBuffer.unbind();return galaxy;
    }
    private static void nebula(BufferBuilder b,int lon,int lat,int width,int height,Palette palette) {
        double a=lon*Math.PI*2/width,v=lat*Math.PI/height;
        double x=Math.cos(a)*Math.sin(v),y=Math.cos(v),z=Math.sin(a)*Math.sin(v);
        // A tilted galactic band with branching, turbulent emerald clouds on both sides.
        double band=Math.exp(-Math.pow((y*.81+x*.38-z*.44)/.30,2));
        double cloud=fbm(x*3.8+11,y*3.8+6,z*3.8-4);
        double filaments=fbm(x*11+cloud*2,y*11,z*11);
        double glow=band*Math.max(0,cloud-.18)*2.3;
        double bright=glow*Math.max(0,filaments-.48)*2.1;
        float r=(float)(.007+glow*.16+bright*.30);
        float g=(float)(.018+glow*.64+bright*.35);
        float blue=(float)(.025+glow*.37+bright*.45);
        vertex(b,IDENTITY,x*100,y*100,z*100,r,g,blue,1,palette);
    }
    private static void vertex(BufferBuilder b,Matrix4f m,double x,double y,double z,float r,float g,float blue,float alpha,Palette palette){
        float red=r,green=g,azure=blue;
        if(palette==Palette.OCEAN){red=r*.65f+g*.035f;green=blue*1.18f+g*.12f;azure=g*1.32f+blue*.12f;}
        else if(palette==Palette.STELLAR){red=blue*.95f+r*.28f;green=g*.62f+r*.12f;azure=g*1.14f+blue*.12f;}
        b.vertex(m,(float)x,(float)y,(float)z).color(Mth.clamp(red,0,1),Mth.clamp(green,0,1),Mth.clamp(azure,0,1),alpha).endVertex();
    }
    static double fbm(double x,double y,double z) {
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
