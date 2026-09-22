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
    public static void clear(){TERRAIN.values().forEach(VertexBuffer::close);TERRAIN.clear();SunRenderer.clear();PrisonMoonRenderer.clear();ParadiseSky.clear();WarpShadows.clear();}
    public static void sky(PoseStack pose,Destination d,double time){
        if(d==Destination.SUN||d==Destination.VOID_SEA){
            // Same detailed sky in the portal and in the destination, at the preview's spatial scale.
            float near=RenderSystem.getShaderFogStart(),far=RenderSystem.getShaderFogEnd();
            pose.pushPose();pose.translate(0,110,0);pose.scale(3,3,3);
            net.minecraft.client.renderer.FogRenderer.setupNoFog();
            try{RealmSky.drawBackdrop(pose,RenderSystem.getProjectionMatrix(),(float)time,d==Destination.VOID_SEA?RealmSky.Palette.OCEAN:RealmSky.Palette.STELLAR);}
            finally{pose.popPose();RenderSystem.setShaderFogStart(near);RenderSystem.setShaderFogEnd(far);RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();}
            return;
        }
        if(d==Destination.PARADISE){
            // Twenty thousand quads of dome, uploaded once. Drawn through its own path rather than
            // through the shared immediate buffer below, which could not hold it at any frame rate.
            float near=RenderSystem.getShaderFogStart(),far=RenderSystem.getShaderFogEnd();
            net.minecraft.client.renderer.FogRenderer.setupNoFog();
            try{ParadiseSky.sky(pose,time);}
            finally{RenderSystem.setShaderFogStart(near);RenderSystem.setShaderFogEnd(far);RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();}
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=pose.last().pose();
        if(d==Destination.FROZEN_MOMENT){
            // A catastrophe held still is a whiteout, not a starfield: a blank bright shell with
            // suspended ice hanging in it, and nothing beyond it to give the eye any distance.
            WarpMesh.sphere(b,m,new Vec3(0,110,0),310,310,310,0xeef7ff,1,32,0,false);
            Random ice=new Random(4471);
            for(int i=0;i<520;i++){
                Vec3 n=new Vec3(ice.nextGaussian(),ice.nextGaussian(),ice.nextGaussian()).normalize();
                Vec3 p=new Vec3(0,110,0).add(n.scale(120+ice.nextDouble()*160));
                double size=.5+ice.nextDouble()*2.4;
                WarpMesh.box(b,m,p.x,p.y,p.z,size,size*.35,size,i%5==0?0xffffff:0xbcd9ef,.85f);
            }
            BufferUploader.drawWithShader(b.end());
            return;
        }
        int[] pal=space(d);
        WarpMesh.sphere(b,m,new Vec3(0,110,0),310,310,310,pal[0],1,32,0,false);
        Random r=new Random(81031);int stars=d==Destination.END_OF_TIME?100:850;
        for(int i=0;i<stars;i++){
            Vec3 n=new Vec3(r.nextGaussian(),r.nextGaussian(),r.nextGaussian()).normalize(),p=new Vec3(0,110,0).add(n.scale(295));
            Vec3 side=n.cross(new Vec3(0,1,0)).normalize().scale(.09+r.nextDouble()*.22),up=n.cross(side);
            WarpMesh.quad(b,m,p.subtract(side).subtract(up),p.add(side).subtract(up),p.add(side).add(up),p.subtract(side).add(up),i%4==0?pal[4]:0xe6e9ef,.8f);
        }
        if(d!=Destination.END_OF_TIME)for(int i=0;i<220;i++){
            double a=r.nextDouble()*Math.PI*2,rad=240+r.nextDouble()*35;Vec3 p=new Vec3(Math.cos(a)*rad,100+Math.sin(a*2)*70+r.nextGaussian()*18,Math.sin(a)*rad);
            double size=2+r.nextDouble()*12;WarpMesh.sphere(b,m,p,size,size*.45,size,pal[1],.045f,8,0,false);
        }
        // Galaxies have spiral arms, distant cores and a tilted dust band, not a flat portal texture.
        for(int g=0;g<(d==Destination.END_OF_TIME?0:3);g++)for(int i=0;i<230;i++){
            double a=i*.16+g*2,rad=i*.085;Vec3 center=new Vec3(Math.cos(g*2.1)*235,190+g*20,Math.sin(g*2.1)*235);
            Vec3 p=center.add(Math.cos(a)*rad,Math.sin(a)*rad*.4,Math.sin(a)*rad*.7);WarpMesh.box(b,m,p.x,p.y,p.z,.35,.35,.35,i<30?pal[3]:pal[2],.55f);
        }
        if(d==Destination.SUN)for(int i=0;i<5;i++){
            double phase=(time+i*117)%520;if(phase>55)continue;
            double a=i*2.399;Vec3 p=new Vec3(Math.cos(a)*220+phase,205-phase*.3,Math.sin(a)*220);
            WarpMesh.ribbon(b,m,p,p.add(-8,2.4,0),.12,0xffffff,(float)Math.sin(phase/55*Math.PI));
        }
        BufferUploader.drawWithShader(b.end());
    }
    /**
     * Deep space colours, per realm: backdrop, nebula, galaxy arm, galaxy core, star tint.
     *
     * <p>Every realm drew the same violet nebulae and the same white-on-lilac galaxies over the
     * same near black shell, which is most of why eight very different places looked like one
     * place seen eight times. The geometry was already per realm; only the colour was not.
     */
    private static int[] space(Destination d){
        return switch(d){
            case GRAVITY_WELL    -> new int[]{0x01000a,0x3a1060,0x5a2a86,0xe0c2ff,0xcfd6ff};
            case SHATTERED_WORLD -> new int[]{0x04090c,0x2c6b6e,0x49a0a0,0xdffbff,0xe8f6f4};
            case TIME_STORM      -> new int[]{0x0a0316,0x7b2ecc,0xa964ff,0xffe6ff,0xe6ccff};
            case CRUSHING_REALM  -> new int[]{0x0a0207,0x5a1030,0x8c2050,0xffc4dd,0xffd0d8};
            case END_OF_TIME     -> new int[]{0x070609,0x3a3640,0x55505e,0x9a94a4,0x8e8896};
            case SUN             -> new int[]{0x100401,0xa33a10,0xe0731c,0xfff0c0,0xffe9bf};
            default              -> new int[]{0x020106,0x6e398d,0x784c9e,0xf1dfff,0xe6e9ef};
        };
    }

    /**
     * The far side's occupants, drawn into the destination scene an aperture is already showing.
     *
     * <p>Its own pass rather than part of {@link #draw}, because it is the one thing in the scene
     * that is per portal rather than per realm: two breaks onto the same destination are two
     * different windows onto it, and each is told separately what is in front of it.
     */
    public static void shadows(PoseStack pose,int portal,double time,Destination d){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        WarpShadows.draw(b,pose.last().pose(),portal,time,d.color);
        BufferUploader.drawWithShader(b.end());
    }
    public static void draw(PoseStack pose,Destination d,double time,long age,boolean preview){
        if(d==Destination.SUN){SunRenderer.draw(pose,time);return;}
        if(d==Destination.CRUSHING_REALM){PrisonMoonRenderer.draw(pose,time);return;}
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if(preview&&d!=Destination.SUN&&d!=Destination.VOID_SEA){
            VertexBuffer mesh=TERRAIN.computeIfAbsent(d,WarpScene::bakeTerrain);mesh.bind();mesh.drawWithShader(pose.last().pose(),RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());VertexBuffer.unbind();
        }
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);Matrix4f m=pose.last().pose();
        switch(d){
            case VOID_SEA -> {
                if(preview){WarpMesh.box(b,m,-250,0,-250,500,136,500,0x020810,1);for(int i=0;i<90;i++){double x=Math.sin(i*5.2)*180,z=Math.cos(i*3.7)*180;WarpMesh.ribbon(b,m,new Vec3(x,136.03,z),new Vec3(x+5,136.03,z+Math.sin(time*.03+i)),.08,0x244454,.5f);}leviathanSilhouette(b,m,time);}
            }
            case GRAVITY_WELL -> {
                Vec3 c=new Vec3(0,96,0);
                for(int i=0;i<18;i++)WarpMesh.ring(b,m,c,10+i*1.4,1.9,i<4?0xf4d4ff:i<10?0xae67ff:0x452464,.6f,.28,time*.008+i*.08);
                WarpMesh.sphere(b,m,c,CosmicPhysics.HORIZON,CosmicPhysics.HORIZON,CosmicPhysics.HORIZON,0x000000,1,64,0,false);
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
            // Waterfalls, mist, drifting confectionery and glitter. The blocks are the server's;
            // everything here is what the blocks cannot be.
            case PARADISE -> ParadiseSky.scene(b,m,time,preview);
            case FROZEN_MOMENT -> {
                // The same seed the server populates from, so the preview holds the same spears.
                if(preview){Random r=new Random(819+d.ordinal());for(int i=0;i<32;i++){int x=r.nextInt(100)-50,y=140+r.nextInt(90),z=r.nextInt(100)-50;hazard(b,m,i%4==0?5:1,x,y,z);}}
                for(int i=0;i<130;i++){double a=i*2.399;Vec3 p=new Vec3(Math.cos(a)*(8+i%30),130+i%45,Math.sin(a)*(8+i%30));WarpMesh.box(b,m,p.x,p.y,p.z,.13,.13,.13,0xc6ecff,.7f);}
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
