package com.loki.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** One pass over the actual mesh, with no replacement geometry or size-dependent fragment grid. */
final class ErasureBuffer implements MultiBufferSource {
    private static final Map<RenderType,RenderType> TYPES=new IdentityHashMap<>();
    private final MultiBufferSource source;
    private final Matrix4f root,inverse;
    private final Vec3 direction;
    private final float phase,power,height,extent;

    ErasureBuffer(MultiBufferSource source,Matrix4f root,Entity entity,Vec3 direction,float phase,float power) {
        this.source=source;this.root=new Matrix4f(root);this.inverse=new Matrix4f(root).invert();
        this.direction=direction;this.phase=phase;this.power=power;this.height=entity.getBbHeight();
        this.extent=(float)Math.max(.1,(entity.getBbWidth()*(Math.abs(direction.x)+Math.abs(direction.z))
            +height*Math.abs(direction.y))*.5);
    }
    static void clear(){TYPES.clear();}

    @Override public VertexConsumer getBuffer(RenderType type) {
        // Retain each layer's shader, vertex layout and textures, including modded/armour layers.
        // Only blending changes, and only in the buffers supplied to a currently erased entity.
        if(TYPES.size()>128)TYPES.clear();
        RenderType fading=TYPES.computeIfAbsent(type,t->new RenderType("loki_erasure",t.format(),t.mode(),
            t.bufferSize(),t.affectsCrumbling(),true,()->{
                t.setupRenderState();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            },()->{RenderSystem.disableBlend();t.clearRenderState();}){});
        return new FadeVertex(source.getBuffer(fading));
    }

    private final class FadeVertex implements VertexConsumer {
        private final VertexConsumer out;
        private final Vector3f point=new Vector3f();
        private float alpha=1;
        FadeVertex(VertexConsumer out){this.out=out;}
        @Override public VertexConsumer vertex(double x,double y,double z) {
            inverse.transformPosition(point.set((float)x,(float)y,(float)z));
            float along=(float)((point.x*direction.x+(point.y-height*.5)*direction.y+point.z*direction.z)/extent*.5+.5);
            float front=Mth.clamp((along-(phase*1.5f-.25f)+.4f)/.8f,0,1);
            front=front*front*(3-2*front);
            alpha=(float)Math.pow(1-phase,.65)*(.22f+.78f*front);
            // A continuous pull of the connected model, never detached cubes or individual faces.
            float pull=phase*phase*(1-front)*(.25f+power*.4f);
            point.add((float)direction.x*pull,(float)direction.y*pull,(float)direction.z*pull);
            root.transformPosition(point);
            out.vertex(point.x,point.y,point.z);return this;
        }
        @Override public VertexConsumer color(int r,int g,int b,int a){out.color(r,g,b,Math.round(a*alpha));return this;}
        @Override public VertexConsumer uv(float u,float v){out.uv(u,v);return this;}
        @Override public VertexConsumer overlayCoords(int u,int v){out.overlayCoords(u,v);return this;}
        @Override public VertexConsumer uv2(int u,int v){out.uv2(u,v);return this;}
        @Override public VertexConsumer normal(float x,float y,float z){out.normal(x,y,z);return this;}
        @Override public void endVertex(){out.endVertex();}
        @Override public void defaultColor(int r,int g,int b,int a){out.defaultColor(r,g,b,Math.round(a*alpha));}
        @Override public void unsetDefaultColor(){out.unsetDefaultColor();}
    }
}
