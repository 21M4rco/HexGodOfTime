package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.mixin.PostChainAccessor;
import com.hexgodofstories.mixin.TimeStopFovAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Roundabout's depth-aware time-stop bubble, tinted black/green. No world geometry or motes. */
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
                &&camera.distanceToSqr(f.centre())<96*96)return true;
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
                double distance=camera.distanceToSqr(field.centre());
                if(distance>96*96)continue;
                float progress=Mth.clamp((ClientState.now()+partial-field.started())/com.hexgodofstories.server.TemporalEngine.STOP_EXPANSION,.001f,1);
                // The reference shader eases its radius cubically. Invert that curve so the visible
                // boundary and the server's hitbox advance together, including on multiplayer clients.
                float shaderRadius=(float)(field.radius()*(1-Math.cbrt(1-progress)));
                for(var pass:((PostChainAccessor)(Object)chain).hgos$passes()) {
                    var effect=pass.getEffect();
                    var u=effect.getUniform("CameraPos");if(u!=null)u.set((float)camera.x,(float)camera.y,(float)camera.z,fov);
                    u=effect.getUniform("CameraRot");if(u!=null)u.set(rotation.x,rotation.y,rotation.z,rotation.w);
                    u=effect.getUniform("BubblePos");if(u!=null)u.set((float)field.centre().x,(float)field.centre().y,(float)field.centre().z);
                    u=effect.getUniform("BubbleRadius");if(u!=null)u.set(shaderRadius);
                    u=effect.getUniform("BubbleMaxRadius");if(u!=null)u.set((float)field.radius());
                    u=effect.getUniform("BubbleTint");if(u!=null)u.set(.27f,.56f,.34f);
                    u=effect.getUniform("DesaturateAllInside");if(u!=null)u.set(1f);
                    u=effect.getUniform("GroundLinesOpacity");if(u!=null)u.set(0f);
                }
                RenderSystem.disableDepthTest();chain.process(partial);
                mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
            }
        } catch(Exception ex) {
            failed=true;close();
            org.slf4j.LoggerFactory.getLogger("HexGodOfStories").warn("Time stop bubble unavailable",ex);
            failed=true;mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
        }
    }
    public static void close() {
        if(chain!=null)chain.close();chain=null;
        if(depth!=null)depth.destroyBuffers();depth=null;
        width=0;height=0;failed=false;
    }
}
