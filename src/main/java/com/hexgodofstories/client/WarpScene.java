package com.hexgodofstories.client;

import com.hexgodofstories.warping.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.*;

/** Destination and aperture share this scene. Architecture comes from the server's generation blueprint. */
public final class WarpScene {
    private static final Map<Destination,VertexBuffer> TERRAIN=new EnumMap<>(Destination.class);
    public static void clear(){TERRAIN.values().forEach(VertexBuffer::close);TERRAIN.clear();}
    public static void sky(PoseStack pose,Destination d,double time){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=pose.last().pose();
        WarpMesh.sphere(b,m,new Vec3(0,110,0),310,310,310,0x020106,1,32,0,false);
        Random r=new Random(81031);int stars=d==Destination.END_OF_TIME?100:850;
        for(int i=0;i<stars;i++){
            Vec3 n=new Vec3(r.nextGaussian(),r.nextGaussian(),r.nextGaussian()).normalize(),p=new Vec3(0,110,0).add(n.scale(295));
            Vec3 side=n.cross(new Vec3(0,1,0)).normalize().scale(.09+r.nextDouble()*.22),up=n.cross(side);
            WarpMesh.quad(b,m,p.subtract(side).subtract(up),p.add(side).subtract(up),p.add(side).add(up),p.subtract(side).add(up),i%4==0?0xd8c7ff:0xe6e9ef,.8f);
        }
        if(d!=Destination.END_OF_TIME)for(int i=0;i<220;i++){
            double a=r.nextDouble()*Math.PI*2,rad=240+r.nextDouble()*35;Vec3 p=new Vec3(Math.cos(a)*rad,100+Math.sin(a*2)*70+r.nextGaussian()*18,Math.sin(a)*rad);
            double size=2+r.nextDouble()*12;WarpMesh.sphere(b,m,p,size,size*.45,size,0x6e398d,.035f,8,0,false);
        }
        // Galaxies have spiral arms, distant cores and a tilted dust band, not a flat portal texture.
        for(int g=0;g<(d==Destination.END_OF_TIME?0:3);g++)for(int i=0;i<230;i++){
            double a=i*.16+g*2,rad=i*.085;Vec3 center=new Vec3(Math.cos(g*2.1)*235,190+g*20,Math.sin(g*2.1)*235);
            Vec3 p=center.add(Math.cos(a)*rad,Math.sin(a)*rad*.4,Math.sin(a)*rad*.7);WarpMesh.box(b,m,p.x,p.y,p.z,.35,.35,.35,i<30?0xf1dfff:0x784c9e,.55f);
        }
        BufferUploader.drawWithShader(b.end());
    }
    public static void draw(PoseStack pose,Destination d,double time,long age,boolean preview){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if(preview&&d!=Destination.SUN&&d!=Destination.VOID_SEA){
            VertexBuffer mesh=TERRAIN.computeIfAbsent(d,WarpScene::bakeTerrain);mesh.bind();mesh.drawWithShader(pose.last().pose(),RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());VertexBuffer.unbind();
        }
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=pose.last().pose();
        switch(d){
            case SUN -> {
                Vec3 c=new Vec3(0,94,0);WarpMesh.sphere(b,m,c,34,34,34,0xffb537,1,64,time,true);
                for(int i=1;i<=5;i++)WarpMesh.sphere(b,m,c,34+i*.9,34+i*.9,34+i*.9,0xff922f,.035f,40,time,false);
                for(int i=0;i<12;i++){
                    double a=i*Math.PI/6+time*.001;Vec3 root=c.add(Math.cos(a)*33,Math.sin(a)*33,Math.sin(a*3)*5);
                    WarpMesh.ring(b,m,root,2.5+Math.sin(time*.018+i),.35,0xffc563,.65f,a,time*.003);
                }
                for(int i=0;i<110;i++){double a=i*2.399,timeShift=(time*.15+i*4)%45;Vec3 p=c.add(Math.cos(a)*(36+timeShift*.1),Math.sin(a)*(36+timeShift*.1),Math.sin(i*5.7)*25);WarpMesh.box(b,m,p.x,p.y,p.z,.18,.18,.18,0xffe2a0,(float)(1-timeShift/45));}
            }
            case VOID_SEA -> {
                if(preview){WarpMesh.box(b,m,-250,0,-250,500,135,500,0x020810,1);for(int i=0;i<90;i++){double x=Math.sin(i*5.2)*180,z=Math.cos(i*3.7)*180;WarpMesh.ribbon(b,m,new Vec3(x,135.03,z),new Vec3(x+5,135.03,z+Math.sin(time*.03+i)),.08,0x244454,.5f);}leviathanSilhouette(b,m,time);}
            }
            case GRAVITY_WELL -> {
                Vec3 c=new Vec3(0,96,0);
                for(int i=0;i<18;i++)WarpMesh.ring(b,m,c,10+i*1.4,1.9,i<4?0xf4d4ff:i<10?0xae67ff:0x452464,.6f,.28,time*.008+i*.08);
                WarpMesh.sphere(b,m,c,10,10,10,0x000000,1,48,0,false);
                for(int i=0;i<160;i++){
                    double a=i*2.399+time*.01,r=12+(i*7-time*.25)%65;if(r<12)r+=65;
                    Vec3 p=c.add(Math.cos(a)*r,Math.sin(i*1.7)*r*.35,Math.sin(a)*r);WarpMesh.ribbon(b,m,p,p.add(c.subtract(p).normalize().scale(1+r*.035)),.09,0xd6aaff,.75f);
                }
            }
            case SHATTERED_WORLD -> {
                for(int i=0;i<28;i++){double a=i*2.399+time*.001;Vec3 p=new Vec3(Math.cos(a)*(30+i),100+Math.sin(i*3.1)*35,Math.sin(a)*(30+i));WarpMesh.sphere(b,m,p,1.5,.7,1,0x526369,1,8,0,false);}
                if(age%240<35)for(int i=0;i<8;i++){Vec3 a=new Vec3(Math.sin(i*4)*60,110+i*6,Math.cos(i*4)*60);WarpMesh.ribbon(b,m,a,a.add(10,8,4),.06,0xe0d4ff,.6f);}
            }
            case TIME_STORM -> {
                for(int i=0;i<24;i++){
                    double a=i*2.399;Vec3 root=new Vec3(Math.cos(a)*70,80+(i%6)*22,Math.sin(a)*70);
                    for(int j=0;j<12;j++){Vec3 end=root.add(Math.sin(j*.8+i)*8,5,Math.cos(j*.8+i)*8);WarpMesh.ribbon(b,m,root,end,.13,0xb879ec,.65f);if(j%3==0)WarpMesh.ribbon(b,m,end,end.add(12,-4,-9),.055,0xe5c8ff,.4f);root=end;}
                }
                for(int i=0;i<75;i++){double a=i*2.399+time*.005;Vec3 p=new Vec3(Math.cos(a)*45,85+(i*9+time*.3)%100,Math.sin(a)*45);WarpMesh.sphere(b,m,p,.3,.3,.3,0xe0b5ff,.6f,8,0,false);}
            }
            case FALLING_WORLD,FROZEN_MOMENT -> {
                if(preview){Random r=new Random(819+d.ordinal());int count=d==Destination.FALLING_WORLD?48:32;for(int i=0;i<count;i++){int x=r.nextInt(100)-50,y=140+r.nextInt(90),z=r.nextInt(100)-50;hazard(b,m,d==Destination.FROZEN_MOMENT?(i%4==0?5:1):i%3+2,x,d==Destination.FALLING_WORLD?WarpMath.fallingY(y,age):y,z);}}
                if(d==Destination.FROZEN_MOMENT)for(int i=0;i<130;i++){double a=i*2.399;Vec3 p=new Vec3(Math.cos(a)*(8+i%30),130+i%45,Math.sin(a)*(8+i%30));WarpMesh.box(b,m,p.x,p.y,p.z,.13,.13,.13,0xc6ecff,.7f);}
            }
            case CRUSHING_REALM -> {
                double bottom=WarpMath.floor(age);WarpMesh.box(b,m,-46,bottom-3,-46,92,4,92,0x241b2e,1);
                double top=WarpMath.ceiling(age);WarpMesh.box(b,m,-46,top,-46,92,4,92,0x211527,1);
                for(int i=-40;i<=40;i+=10){WarpMesh.box(b,m,i,top-.035,-42,.15,.03,84,0xc879eb,.8f);WarpMesh.box(b,m,-42,top-.035,i,84,.03,.15,0xc879eb,.8f);}
                for(int i=0;i<30;i++){double a=i*2.399;WarpMesh.sphere(b,m,new Vec3(Math.cos(a)*44,100+(top-100)*(i%7)/7,Math.sin(a)*44),.3,.3,.3,0xc998df,.65f,8,0,false);}
            }
            case END_OF_TIME -> {
                for(int i=0;i<35;i++){double a=i*2.399;Vec3 p=new Vec3(Math.cos(a)*(32+i),125+Math.sin(i*1.7)*40,Math.sin(a)*(32+i));WarpMesh.ribbon(b,m,p,p.add(2+i%5,3,1),.18,0x605267,.55f);}
            }
        }
        BufferUploader.drawWithShader(b.end());
    }
    public static void hazard(BufferBuilder b,Matrix4f m,int kind,double x,double y,double z){
        if(kind==1){WarpMesh.box(b,m,x-.12,y,z-.12,.24,2.4,.24,0xd3e4ef,1);WarpMesh.sphere(b,m,new Vec3(x,y-.35,z),.45,.8,.45,0x9bd5ec,1,8,0,false);WarpMesh.box(b,m,x-.4,y+1.9,z-.05,.8,.45,.1,0x628197,1);}
        else if(kind==3){for(int j=0;j<10;j++){WarpMesh.box(b,m,x-3,y+j,z-3,1,1,6,j%3==0?0x78818a:0x4d5260,1);WarpMesh.box(b,m,x+2,y+j,z-3,1,1,6,0x555361,1);if(j%4==0)WarpMesh.box(b,m,x-3,y+j,z-3,6,.7,6,0x8b8b84,1);}}
        else {WarpMesh.box(b,m,x-3,y,z-3,6,2,6,kind==2?0x454954:0x6b6560,1);if(kind==2){WarpMesh.box(b,m,x-3,y+2,z-3,6,.15,6,0x50654a,1);WarpMesh.box(b,m,x,y+2,z,1,5,1,0x51453a,1);WarpMesh.box(b,m,x-2,y+6,z-2,5,2,5,0x344438,1);}}
    }
    private static void leviathanSilhouette(BufferBuilder b,Matrix4f m,double time){for(int i=0;i<17;i++){double a=time*.01-i*.07;WarpMesh.sphere(b,m,new Vec3(Math.cos(a)*20,128-i*.12,Math.sin(a)*20),i==0?3:2-i*.075,1.2,2,0x172f38,1,12,0,false);}}
    private static VertexBuffer bakeTerrain(Destination d){
        var blocks=RealmLayout.blocks(d);Set<BlockPos> occupied=new HashSet<>();blocks.forEach(v->occupied.add(v.pos()));
        BufferBuilder b=new BufferBuilder(1024*1024);b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=new Matrix4f();
        for(var v:blocks){boolean exposed=false;for(var dir:net.minecraft.core.Direction.values())if(!occupied.contains(v.pos().relative(dir))){exposed=true;break;}if(!exposed)continue;
            int color=v.state().getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,v.pos()).col;WarpMesh.box(b,m,v.pos().getX(),v.pos().getY(),v.pos().getZ(),1,1,1,color,1);
        }
        VertexBuffer mesh=new VertexBuffer(VertexBuffer.Usage.STATIC);mesh.bind();mesh.upload(b.end());VertexBuffer.unbind();return mesh;
    }
}
