package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.mixin.PostChainAccessor;
import com.hexgodofstories.mixin.TimeStopFovAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Roundabout's two-stage depth-aware bubble: a brief color pulse beneath a pale stopped world. */
public final class TimeStopScreen {
    private static RenderTarget depth;
    private static PostChain chain;
    private static int width,height;
    private static boolean failed;
    private static boolean visible() {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return false;
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        for(WorldEffects.Field f:WorldEffects.fields())
            if(f.stop()&&f.expires()==Long.MAX_VALUE&&ClientState.now()>=f.started()
                &&camera.distanceToSqr(f.position())<140*140)return true;
        return false;
    }
    public static RenderTarget depth(){return depth;}
    public static void capture() {
        if(failed||!visible())return;
        var mc=Minecraft.getInstance();int w=mc.getWindow().getWidth(),h=mc.getWindow().getHeight();
        if(w<=0||h<=0)return;
        if(depth==null||width!=w||height!=h) {
            if(chain!=null){chain.close();chain=null;}
            if(depth!=null)depth.destroyBuffers();
            depth=new TextureTarget(w,h,true,Minecraft.ON_OSX);
            width=w;height=h;
        }
        depth.copyDepthFrom(mc.getMainRenderTarget());
    }
    public static void render(float partial) {
        if(failed||!visible()||depth==null)return;
        var mc=Minecraft.getInstance();
        try {
            if(chain==null) {
                chain=new PostChain(mc.getTextureManager(),mc.getResourceManager(),mc.getMainRenderTarget(),HexGodOfStories.id("shaders/post/timestop.json"));
                chain.resize(width,height);
            }
            Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
            Quaternionf rotation=mc.gameRenderer.getMainCamera().rotation();
            float fov=(float)((TimeStopFovAccessor)mc.gameRenderer).hgos$getFov(mc.gameRenderer.getMainCamera(),partial,true);
            for(WorldEffects.Field field:WorldEffects.fields()) {
                if(!field.stop()||field.expires()!=Long.MAX_VALUE)continue;
                Vec3 centre=field.position();
                if(camera.distanceToSqr(centre)>140*140)continue;
                float age=Math.max(0,ClientState.now()+partial-field.started());
                float max=(float)field.radius();
                // The World's full charge has a colored pulse which expands and folds back under
                // a larger neutral bubble. Both passes use Roundabout's original depth shader.
                float pulse=Math.min(age*max/com.hexgodofstories.server.TemporalEngine.STOP_EXPANSION,max*2);
                if(pulse>max)pulse=max-(pulse-max);
                if(pulse>.001f)process(mc,centre,camera,rotation,fov,pulse,max,
                    .39f,.61f,.41f,pulse>=24,0f,partial);
                float outer=Math.min(age*max/com.hexgodofstories.server.TemporalEngine.STOP_EXPANSION,max);
                if(outer>.001f)process(mc,centre,camera,rotation,fov,outer,max,
                    .86f,.94f,.87f,outer>=24,0f,partial);
            }
        } catch(Exception ex) {
            failed=true;close();
            org.slf4j.LoggerFactory.getLogger("HexGodOfStories").warn("Time stop bubble unavailable",ex);
            failed=true;mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
        }
    }
    private static void process(Minecraft mc,Vec3 centre,Vec3 camera,Quaternionf rotation,float fov,
                                float radius,float max,float red,float green,float blue,boolean full,
                                float lines,float partial) {
        for(var pass:((PostChainAccessor)(Object)chain).hgos$passes()) {
            var effect=pass.getEffect();
            var u=effect.getUniform("CameraPos");if(u!=null)u.set((float)camera.x,(float)camera.y,(float)camera.z,fov);
            u=effect.getUniform("CameraRot");if(u!=null)u.set(rotation.x,rotation.y,rotation.z,rotation.w);
            u=effect.getUniform("BubblePos");if(u!=null)u.set((float)centre.x,(float)centre.y,(float)centre.z);
            u=effect.getUniform("BubbleRadius");if(u!=null)u.set(radius);
            u=effect.getUniform("BubbleMaxRadius");if(u!=null)u.set(max);
            u=effect.getUniform("BubbleTint");if(u!=null)u.set(red,green,blue);
            u=effect.getUniform("DesaturateAllInside");if(u!=null)u.set(full?1f:0f);
            u=effect.getUniform("GroundLinesOpacity");if(u!=null)u.set(lines);
        }
        RenderSystem.disableDepthTest();chain.process(partial);
        mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
    }
    public static void close() {
        if(chain!=null)chain.close();chain=null;
        if(depth!=null)depth.destroyBuffers();depth=null;
        width=0;height=0;failed=false;
    }
}
