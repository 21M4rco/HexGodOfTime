package com.loki.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.*;

/**
 * The geometry Time Branch Unleashing is actually made of.
 *
 * <p>Everything here is gathered into a {@link Painter} and drawn one render type at a time, which is the
 * whole performance story of the ability: the sphere, the torrent, the strands, the arcs and every
 * erasure fragment come out as a handful of batched draw calls rather than as tens of thousands of
 * particle entities. Particles embellish what these build; they never construct it.
 *
 * <p>Gathering first is not only about batch size. Minecraft's shared buffer source ends one type's batch
 * the moment a different type is asked for, so drawing the effect in the order it reads — a membrane, then
 * its haze, then an arc over it — would flush a draw call per layer and write into builders that had
 * already been closed. The painter lets the code stay in reading order and the GPU still see it in type
 * order.
 *
 * <p>Three generated sheets, baked once and kept: a cored glow for volumes and billboards, a longitudinal
 * gradient for strands and arcs, and a noise puff matching the mod's existing nebula, so the ultimate's
 * haze is visibly the same material as the flight cloud rather than a new look bolted on.
 */
public final class BranchVfx {
    private BranchVfx() {}

    private static ResourceLocation glowSheet,strandSheet,cloudSheet;
    private static RenderType glowType,strandType,cloudType;

    public static void clear() {
        var textures=Minecraft.getInstance().getTextureManager();
        if(glowSheet!=null)textures.release(glowSheet);
        if(strandSheet!=null)textures.release(strandSheet);
        if(cloudSheet!=null)textures.release(cloudSheet);
        glowSheet=null;strandSheet=null;cloudSheet=null;
        glowType=null;strandType=null;cloudType=null;
    }

    /** Additive and full-bright, for cores, membranes and anything that should read as light. */
    public static RenderType glow() {
        if(glowType==null)glowType=RenderType.energySwirl(glowSheet(),0,0);
        return glowType;
    }
    /** The same, against the strand gradient: braids, filaments and temporal lightning. */
    public static RenderType strand() {
        if(strandType==null)strandType=RenderType.energySwirl(strandSheet(),0,0);
        return strandType;
    }
    /** The soft volume that sits behind the sharp material. */
    public static RenderType cloud() {
        if(cloudType==null)cloudType=RenderType.energySwirl(cloudSheet(),0,0);
        return cloudType;
    }

    private static ResourceLocation glowSheet() {
        if(glowSheet!=null)return glowSheet;
        glowSheet=bake("loki_branch_glow",64,(u,v)->{
            double d=Math.sqrt(u*u+v*v);
            if(d>=1)return 0;
            return Math.min(1,Math.exp(-Math.pow(d/.22,2))+Math.pow(1-d,2.6)*.55);
        });
        return glowSheet;
    }
    private static ResourceLocation strandSheet() {
        if(strandSheet!=null)return strandSheet;
        // A bright filament down the middle of the quad with a soft shoulder, so a ribbon reads as a
        // round strand of light rather than as a flat band.
        strandSheet=bake("loki_branch_strand",32,(u,v)->{
            double across=Math.abs(v),along=1-Math.abs(u)*.35;
            double filament=Math.exp(-Math.pow(across/.16,2));
            double shoulder=Math.pow(Math.max(0,1-across),3.2)*.42;
            return Math.max(0,Math.min(1,(filament+shoulder)*along));
        });
        return strandSheet;
    }
    private static ResourceLocation cloudSheet() {
        if(cloudSheet!=null)return cloudSheet;
        cloudSheet=bake("loki_branch_cloud",64,(u,v)->{
            double r=u*u+v*v;
            if(r>=1)return 0;
            double shape=RealmSky.fbm(u*2.7+17,v*2.7-6,3);
            double detail=RealmSky.fbm(u*8.5+shape*2,v*8.5+3,5);
            return Math.min(1,Math.pow(1-r,1.8)*Math.min(1,Math.max(0,(shape-.16)*3))*(.5+.7*detail)*1.5);
        });
        return cloudSheet;
    }

    @FunctionalInterface private interface Field { double at(double u,double v); }
    private static ResourceLocation bake(String name,int size,Field field) {
        NativeImage image=new NativeImage(size,size,false);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            double u=(x+.5)/size*2-1,v=(y+.5)/size*2-1;
            int alpha=(int)(Mth.clamp((float)field.at(u,v),0,1)*255);
            image.setPixelRGBA(x,y,alpha<<24|0x00ffffff);
        }
        return Minecraft.getInstance().getTextureManager().register(name,new DynamicTexture(image));
    }

    // -------------------------------------------------------------------- batching ---

    private static final float[] SQUARE={0,0,1,0,1,1,0,1};

    /**
     * Quads gathered per render type and emitted in one pass each. Reused between frames, so a frame of
     * the ability allocates nothing beyond the occasional array growth.
     */
    public static final class Painter {
        /** Twelve position floats, eight texture floats and an alpha, per quad. */
        private static final int STRIDE=21;
        private static final class Batch {
            float[] data=new float[STRIDE*64];
            int[] colour=new int[64];
            int count;
        }
        private final Map<RenderType,Batch> batches=new LinkedHashMap<>();
        private int total;

        public void quad(RenderType type,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float[] uv,int colour,float alpha) {
            if(alpha<=.004f)return;
            Batch batch=batches.computeIfAbsent(type,t->new Batch());
            if((batch.count+1)*STRIDE>batch.data.length) {
                batch.data=Arrays.copyOf(batch.data,batch.data.length*2);
                batch.colour=Arrays.copyOf(batch.colour,batch.colour.length*2);
            }
            int at=batch.count*STRIDE;
            float[] out=batch.data;
            out[at]=(float)a.x;out[at+1]=(float)a.y;out[at+2]=(float)a.z;
            out[at+3]=(float)b.x;out[at+4]=(float)b.y;out[at+5]=(float)b.z;
            out[at+6]=(float)c.x;out[at+7]=(float)c.y;out[at+8]=(float)c.z;
            out[at+9]=(float)d.x;out[at+10]=(float)d.y;out[at+11]=(float)d.z;
            System.arraycopy(uv==null?SQUARE:uv,0,out,at+12,8);
            out[at+20]=alpha;
            batch.colour[batch.count++]=colour;
            total++;
        }
        /** Convenience for the common case: the whole sheet stretched over the quad. */
        public void quad(RenderType type,Vec3 a,Vec3 b,Vec3 c,Vec3 d,int colour,float alpha) {
            quad(type,a,b,c,d,null,colour,alpha);
        }
        public int size() {return total;}

        /*
         * Scratch arrays for the emit loop. WorldEffects.quad only reads what it is handed, so one set is
         * refilled per quad rather than allocating ten small arrays a quad — which at a couple of thousand
         * quads a frame is the difference between a steady effect and a stuttering one.
         */
        private final float[][] corners={new float[3],new float[3],new float[3],new float[3]};
        private final float[][] texture={new float[2],new float[2],new float[2],new float[2]};

        /** One {@code getBuffer}/{@code endBatch} pair per type, in the order the types were first used. */
        public void flush(PoseStack pose,MultiBufferSource.BufferSource buffers) {
            for(var entry:batches.entrySet()) {
                Batch batch=entry.getValue();
                if(batch.count==0)continue;
                VertexConsumer out=buffers.getBuffer(entry.getKey());
                float[] data=batch.data;
                for(int q=0;q<batch.count;q++) {
                    int at=q*STRIDE;
                    for(int i=0;i<4;i++) {
                        corners[i][0]=data[at+i*3];corners[i][1]=data[at+i*3+1];corners[i][2]=data[at+i*3+2];
                        texture[i][0]=data[at+12+i*2];texture[i][1]=data[at+13+i*2];
                    }
                    WorldEffects.quad(pose,out,corners,texture,15728880,batch.colour[q],data[at+20]);
                }
                buffers.endBatch(entry.getKey());
                batch.count=0;
            }
            total=0;
        }
        /** Drops everything gathered without drawing it, for a world change mid-frame. */
        public void discard() {batches.values().forEach(b->b.count=0);total=0;}
    }

    // ------------------------------------------------------------------- geometry ---

    public static Vec3 cameraRight() {
        Vector3f v=new Vector3f(1,0,0).rotate(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        return new Vec3(v.x,v.y,v.z);
    }
    public static Vec3 cameraUp() {
        Vector3f v=new Vector3f(0,1,0).rotate(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        return new Vec3(v.x,v.y,v.z);
    }
    /** Any unit vector square to {@code axis}; stable enough to build a ring basis from. */
    public static Vec3 perpendicular(Vec3 axis) {
        Vec3 side=axis.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-6)side=axis.cross(new Vec3(1,0,0));
        if(side.lengthSqr()<1e-6)return new Vec3(1,0,0);
        return side.normalize();
    }

    /** A camera-facing rectangle. Used for cores, motes and anything that should never turn edge-on. */
    public static void billboard(Painter painter,RenderType type,Vec3 at,double radius,double roll,int colour,float alpha) {
        Vec3 r=cameraRight(),u=cameraUp();
        Vec3 rr=r.scale(Math.cos(roll)).add(u.scale(Math.sin(roll))).scale(radius);
        Vec3 uu=u.scale(Math.cos(roll)).subtract(r.scale(Math.sin(roll))).scale(radius);
        painter.quad(type,at.subtract(rr).subtract(uu),at.add(rr).subtract(uu),at.add(rr).add(uu),at.subtract(rr).add(uu),colour,alpha);
    }

    /** A camera-facing band between two points: one segment of a strand, a braid or an arc. */
    public static void ribbon(Painter painter,RenderType type,Vec3 from,Vec3 to,double width,int colour,float alpha) {
        Vec3 camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 along=to.subtract(from);
        if(along.lengthSqr()<1e-10)return;
        Vec3 side=along.cross(from.subtract(camera));
        if(side.lengthSqr()<1e-12)side=perpendicular(along.normalize());
        side=side.normalize().scale(width);
        painter.quad(type,from.subtract(side),from.add(side),to.add(side),to.subtract(side),colour,alpha);
    }

    /**
     * A polyline drawn as a continuous strand, tapering at both ends and shifting colour along its
     * length, so one strand is never one flat hue.
     */
    public static void polyline(Painter painter,RenderType type,Vec3[] points,double width,float phase,float spread,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-2);
            double taper=Math.sin(Mth.clamp(t,0,1)*Math.PI)*.75+.25;
            ribbon(painter,type,points[i],points[i+1],width*taper,TemporalPalette.shade(phase+t*spread),alpha);
        }
    }

    /**
     * A closed tube around a centreline. Radii and colours are per ring, so a torrent can swell, pinch and
     * change colour down its length; {@code sides} is the level-of-detail dial.
     */
    public static void tube(Painter painter,RenderType type,Vec3[] centres,double[] radii,int[] colours,float[] alphas,int sides,double twist) {
        if(centres.length<2)return;
        Vec3 axis=centres[centres.length-1].subtract(centres[0]);
        if(axis.lengthSqr()<1e-10)return;
        axis=axis.normalize();
        Vec3 side=perpendicular(axis),up=side.cross(axis).normalize();
        for(int i=0;i<centres.length-1;i++) {
            for(int s=0;s<sides;s++) {
                double a0=s*Math.PI*2/sides+i*twist,a1=(s+1)*Math.PI*2/sides+i*twist;
                double b0=a0+twist,b1=a1+twist;
                painter.quad(type,
                    centres[i].add(ring(side,up,a0,radii[i])),
                    centres[i].add(ring(side,up,a1,radii[i])),
                    centres[i+1].add(ring(side,up,b1,radii[i+1])),
                    centres[i+1].add(ring(side,up,b0,radii[i+1])),
                    colours[i],(alphas[i]+alphas[i+1])*.5f);
            }
        }
    }
    private static Vec3 ring(Vec3 side,Vec3 up,double angle,double radius) {
        return side.scale(Math.cos(angle)*radius).add(up.scale(Math.sin(angle)*radius));
    }

    // ------------------------------------------------- direct, single-type drawing ---

    /*
     * An entity renderer already sits inside a render pass and only ever needs one type, so these write
     * straight to a consumer rather than gathering. Mixing them with a painter on the same frame is fine;
     * mixing two of them across two types is not, which is why every caller here uses exactly one sheet.
     */

    public static void billboard(PoseStack pose,VertexConsumer out,Vec3 at,double radius,double roll,int colour,float alpha) {
        Vec3 r=cameraRight(),u=cameraUp();
        Vec3 rr=r.scale(Math.cos(roll)).add(u.scale(Math.sin(roll))).scale(radius);
        Vec3 uu=u.scale(Math.cos(roll)).subtract(r.scale(Math.sin(roll))).scale(radius);
        emit(pose,out,at.subtract(rr).subtract(uu),at.add(rr).subtract(uu),at.add(rr).add(uu),at.subtract(rr).add(uu),colour,alpha);
    }
    public static void ribbon(PoseStack pose,VertexConsumer out,Vec3 from,Vec3 to,double width,int colour,float alpha) {
        Vec3 along=to.subtract(from);
        if(along.lengthSqr()<1e-10)return;
        Vec3 view=cameraRight().cross(cameraUp());
        Vec3 side=along.cross(view);
        if(side.lengthSqr()<1e-12)side=perpendicular(along.normalize());
        side=side.normalize().scale(width);
        emit(pose,out,from.subtract(side),from.add(side),to.add(side),to.subtract(side),colour,alpha);
    }
    public static void polyline(PoseStack pose,VertexConsumer out,Vec3[] points,double width,float phase,float spread,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-2);
            double taper=Math.sin(Mth.clamp(t,0,1)*Math.PI)*.75+.25;
            ribbon(pose,out,points[i],points[i+1],width*taper,TemporalPalette.shade(phase+t*spread),alpha);
        }
    }
    public static void emit(PoseStack pose,VertexConsumer out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,int colour,float alpha) {
        if(alpha<=.004f)return;
        WorldEffects.quad(pose,out,
            new float[][]{{(float)a.x,(float)a.y,(float)a.z},{(float)b.x,(float)b.y,(float)b.z},
                          {(float)c.x,(float)c.y,(float)c.z},{(float)d.x,(float)d.y,(float)d.z}},
            new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,colour,alpha);
    }

    // ---------------------------------------------------------------------- noise ---

    /** Deterministic, cheap and smooth. Used for every wobble so nothing ever jitters frame to frame. */
    public static double wobble(double a,double b,double c) {
        return Math.sin(a*1.37+Math.cos(b*.83)*1.9)*.5
              +Math.sin(b*2.11+Math.cos(c*1.27)*1.4)*.3
              +Math.sin(c*3.07+a*.61)*.2;
    }
}
