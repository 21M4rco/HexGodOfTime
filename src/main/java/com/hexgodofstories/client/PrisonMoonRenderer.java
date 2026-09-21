package com.hexgodofstories.client;

import com.hexgodofstories.warping.*;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Cratered ash-grey surface, with the exact radius sampled by radial ground collision. */
public final class PrisonMoonRenderer {
    private static VertexBuffer surface;
    public static void clear(){if(surface!=null){surface.close();surface=null;}}
    public static void draw(PoseStack pose,double time) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if(surface==null)surface=bake();
        surface.bind();surface.drawWithShader(pose.last().pose(),RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());VertexBuffer.unbind();
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m=pose.last().pose();
        // Three luminous restraint bands surround the moon; their geometry never obstructs walking.
        for(int ring=0;ring<3;ring++) {
            double tilt=.42+ring*.92,radius=66+ring*4;
            WarpMesh.ring(b,m,MoonGravity.CENTER,radius,.22,0xaf7fe8,.5f,tilt,time*.0008*(ring==1?-1:1));
            WarpMesh.ring(b,m,MoonGravity.CENTER,radius+.32,.06,0xe0c7ff,.65f,tilt,0);
            for(int rune=0;rune<24;rune++) {
                double a=rune*Math.PI/12+ring*.41;
                Vec3 at=MoonGravity.CENTER.add(Math.cos(a)*radius,Math.sin(a)*radius*Math.sin(tilt),Math.sin(a)*radius*Math.cos(tilt));
                Vec3 radial=at.subtract(MoonGravity.CENTER).normalize();
                WarpMesh.ribbon(b,m,at.subtract(radial.scale(.7)),at.add(radial.scale(.7)),.10,0xd2a7ff,.8f);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }
    private static VertexBuffer bake() {
        int longitude=192,latitude=96;
        BufferBuilder b=new BufferBuilder(3*1024*1024);b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix=new Matrix4f();
        for(int lat=0;lat<latitude;lat++)for(int lon=0;lon<longitude;lon++) {
            for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
                double a=(lon+corner[0])*Math.PI*2/longitude,t=(lat+corner[1])*Math.PI/latitude;
                Vec3 n=new Vec3(Math.sin(t)*Math.cos(a),Math.cos(t),Math.sin(t)*Math.sin(a));
                double r=MoonGravity.radius(n),noise=RealmSky.fbm(n.x*38,n.y*38,n.z*38);
                double light=.42+.58*Math.max(0,n.dot(new Vec3(-.4,.7,.6).normalize()));
                int stone=r<CosmicPhysics.MOON_RADIUS-.6?0x5b5b69:noise>.52?0xb0adb6:noise>.39?0x85828e:0x6b6874;
                WarpMesh.vertex(b,m,MoonGravity.CENTER.add(n.scale(r)),WarpMesh.shade(stone,light),1);
            }
        }
        VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();buffer.upload(b.end());VertexBuffer.unbind();return buffer;
    }
    private PrisonMoonRenderer() {}
}
