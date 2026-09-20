package com.loki.client;

import com.loki.data.ErasureFragmentMath;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.*;

/**
 * Fracture the registered renderer's actual surfaces, preserving texture UVs, layers and silhouette.
 * Intact surface tiles stay opaque and in place. Only detached tiles travel, shrink and dissolve.
 * No bounding-box substitute, whole-model alpha fade, texture readback or per-fragment entity.
 */
final class ErasureBuffer implements MultiBufferSource {
    private static final Map<RenderType,RenderType> TYPES=new IdentityHashMap<>();
    private final MultiBufferSource source;
    private final Matrix4f root,inverse;
    private final Vec3 direction;
    private final float phase,power,height,extent,width;
    private final int entitySeed;
    private final boolean implosion;
    // Bound extra tessellation per entity; an unusually dense mod mesh retains its original primitives.
    private int tiles=2048;

    ErasureBuffer(MultiBufferSource source,Matrix4f root,Entity entity,Vec3 direction,
                  float phase,float power,boolean implosion) {
        this.source=source;this.root=new Matrix4f(root);this.inverse=new Matrix4f(root).invert();
        this.direction=direction;this.phase=phase;this.power=power;this.implosion=implosion;
        this.height=entity.getBbHeight();this.width=entity.getBbWidth();this.entitySeed=entity.getId()*7919;
        this.extent=(float)Math.max(.1,(width*(Math.abs(direction.x)+Math.abs(direction.z))
            +height*Math.abs(direction.y))*.5);
    }
    static void clear(){TYPES.clear();}

    @Override public VertexConsumer getBuffer(RenderType type) {
        if(TYPES.size()>128)TYPES.clear();
        RenderType fragment=TYPES.computeIfAbsent(type,t->new RenderType("loki_erasure",t.format(),t.mode(),
            t.bufferSize(),t.affectsCrumbling(),true,()->{
                t.setupRenderState();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
            },()->{RenderSystem.enableCull();RenderSystem.disableBlend();t.clearRenderState();}){});
        return new FractureVertex(source.getBuffer(fragment),type.mode());
    }

    private static final class Vertex {
        float x,y,z,u,v,nx,ny,nz;
        int r=255,g=255,b=255,a=255,lightU=240,lightV=240;
        static void patch(Vertex out,Vertex a,Vertex b,Vertex c,Vertex d,float u,float v) {
            float aa=(1-u)*(1-v),bb=u*(1-v),cc=u*v,dd=(1-u)*v;
            out.x=a.x*aa+b.x*bb+c.x*cc+d.x*dd;out.y=a.y*aa+b.y*bb+c.y*cc+d.y*dd;out.z=a.z*aa+b.z*bb+c.z*cc+d.z*dd;
            out.u=a.u*aa+b.u*bb+c.u*cc+d.u*dd;out.v=a.v*aa+b.v*bb+c.v*cc+d.v*dd;
            out.nx=a.nx*aa+b.nx*bb+c.nx*cc+d.nx*dd;out.ny=a.ny*aa+b.ny*bb+c.ny*cc+d.ny*dd;out.nz=a.nz*aa+b.nz*bb+c.nz*cc+d.nz*dd;
            out.r=Math.round(a.r*aa+b.r*bb+c.r*cc+d.r*dd);out.g=Math.round(a.g*aa+b.g*bb+c.g*cc+d.g*dd);
            out.b=Math.round(a.b*aa+b.b*bb+c.b*cc+d.b*dd);out.a=Math.round(a.a*aa+b.a*bb+c.a*cc+d.a*dd);
            out.lightU=Math.round(a.lightU*aa+b.lightU*bb+c.lightU*cc+d.lightU*dd);
            out.lightV=Math.round(a.lightV*aa+b.lightV*bb+c.lightV*cc+d.lightV*dd);
        }
        double distance(Vertex b){return Math.sqrt((x-b.x)*(x-b.x)+(y-b.y)*(y-b.y)+(z-b.z)*(z-b.z));}
    }
    private final class FractureVertex implements VertexConsumer {
        private final VertexConsumer out;
        private final VertexFormat.Mode mode;
        private final Vertex[] primitive;
        private Vertex current;
        private final Vertex[] tile={new Vertex(),new Vertex(),new Vertex(),new Vertex()};
        private final Vector3f centre=new Vector3f(),radial=new Vector3f(),offset=new Vector3f(),
            transformed=new Vector3f(),normal=new Vector3f();
        private final Quaternionf spin=new Quaternionf();
        private int count;
        private boolean defaultColour;
        private int dr=255,dg=255,db=255,da=255;
        FractureVertex(VertexConsumer out,VertexFormat.Mode mode) {
            this.out=out;this.mode=mode;
            primitive=new Vertex[mode==VertexFormat.Mode.QUADS?4:mode==VertexFormat.Mode.TRIANGLES?3:1];
            for(int i=0;i<primitive.length;i++)primitive[i]=new Vertex();
            current=primitive[0];
        }
        @Override public VertexConsumer vertex(double x,double y,double z) {
            Vector3f p=inverse.transformPosition(transformed.set((float)x,(float)y,(float)z));
            current.x=p.x;current.y=p.y;current.z=p.z;return this;
        }
        @Override public VertexConsumer color(int r,int g,int b,int a){current.r=r;current.g=g;current.b=b;current.a=a;return this;}
        @Override public VertexConsumer uv(float u,float v){current.u=u;current.v=v;return this;}
        @Override public VertexConsumer overlayCoords(int u,int v){return this;}
        @Override public VertexConsumer uv2(int u,int v){current.lightU=u;current.lightV=v;return this;}
        @Override public VertexConsumer normal(float x,float y,float z){current.nx=x;current.ny=y;current.nz=z;return this;}
        @Override public void defaultColor(int r,int g,int b,int a){defaultColour=true;dr=r;dg=g;db=b;da=a;}
        @Override public void unsetDefaultColor(){defaultColour=false;}
        @Override public void endVertex() {
            if(defaultColour){current.r=dr;current.g=dg;current.b=db;current.a=da;}
            if(++count==primitive.length) {
                if(mode==VertexFormat.Mode.QUADS)tessellate();else fragment(primitive);
                count=0;
            }
            current=primitive[count];
        }
        private void tessellate() {
            Vertex a=primitive[0],b=primitive[1],c=primitive[2],d=primitive[3];
            double size=Math.max(.075,Math.max(width,height)/24.0);
            int nu=Math.min(24,Math.max(1,(int)Math.ceil(Math.max(a.distance(b),d.distance(c))/size)));
            int nv=Math.min(24,Math.max(1,(int)Math.ceil(Math.max(a.distance(d),b.distance(c))/size)));
            if(nu*nv>tiles){fragment(primitive);return;}
            tiles-=nu*nv;
            for(int u=0;u<nu;u++)for(int v=0;v<nv;v++) {
                float u0=u/(float)nu,u1=(u+1)/(float)nu,v0=v/(float)nv,v1=(v+1)/(float)nv;
                Vertex.patch(tile[0],a,b,c,d,u0,v0);Vertex.patch(tile[1],a,b,c,d,u1,v0);
                Vertex.patch(tile[2],a,b,c,d,u1,v1);Vertex.patch(tile[3],a,b,c,d,u0,v1);
                fragment(tile);
            }
        }
        private void fragment(Vertex[] vertices) {
            centre.zero();
            for(Vertex v:vertices)centre.add(v.x,v.y,v.z);
            centre.div(vertices.length);
            int seed=entitySeed^(int)Math.floor(centre.x*83)*73856093
                ^(int)Math.floor(centre.y*83)*19349663^(int)Math.floor(centre.z*83)*83492791;
            float along=(float)((centre.x*direction.x+(centre.y-height*.5)*direction.y+centre.z*direction.z)/extent*.5+.5);
            radial.set(centre.x,centre.y-height*.5f,centre.z);
            float radius=radial.length()/Math.max(.1f,Math.max(width,height)*.5f);
            float release=ErasureFragmentMath.release(along,radius,seed,implosion);
            float age=ErasureFragmentMath.age(phase,release,implosion);
            float alpha=ErasureFragmentMath.alpha(age);
            // Preserve primitive counts for uncommon strip/line renderers; fully transparent vertices
            // avoid reconnecting non-adjacent surviving vertices in a strip.
            boolean strip=primitive.length==1;
            if(alpha<=0&&!strip)return;
            float t=ErasureFragmentMath.clamp(age),scale=1;
            offset.zero();spin.identity();
            if(implosion) {
                float collapse=.58f*ErasureFragmentMath.clamp(phase/.22f);
                offset.set(radial).mul(-collapse);scale=1-collapse;
            }
            if(age>0) {
                float travel=ErasureFragmentMath.travel(age,power,implosion);
                float jx=ErasureFragmentMath.noise(seed+1)-.5f,jy=ErasureFragmentMath.noise(seed+2)-.5f,jz=ErasureFragmentMath.noise(seed+3)-.5f;
                if(implosion) {
                    if(radial.lengthSquared()<1e-8)radial.set(jx,jy+.01f,jz);
                    offset.add(radial.normalize().mul(travel));
                } else {
                    offset.add((float)direction.x*travel,(float)direction.y*travel,(float)direction.z*travel);
                }
                offset.add(jx*t*.45f,jy*t*.45f,jz*t*.45f);
                scale*=Math.max(.015f,(1-t)*(1-t));
                spin.rotateXYZ(jx*t*5,jy*t*5,jz*t*5);
            }
            for(Vertex v:vertices) {
                Vector3f p=transformed.set(v.x,v.y,v.z).sub(centre).mul(scale).rotate(spin).add(centre).add(offset);
                root.transformPosition(p);
                normal.set(v.nx,v.ny,v.nz).rotate(spin);
                // Textures and original vertex colours remain the primary material. Small white-hot edges
                // signal injection before the creature bursts, without a red hurt overlay.
                float heat=implosion?Math.max(0,1-phase*5)*.7f:0;
                int r=Math.round(v.r+(255-v.r)*heat),g=Math.round(v.g+(255-v.g)*heat),b=Math.round(v.b+(255-v.b)*heat);
                out.vertex(p.x,p.y,p.z).color(r,g,b,Math.round(v.a*alpha)).uv(v.u,v.v)
                    .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(v.lightU,v.lightV)
                    .normal(normal.x,normal.y,normal.z).endVertex();
            }
        }
    }
}
