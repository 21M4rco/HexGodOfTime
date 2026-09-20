package com.loki.client;
import com.loki.Loki;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.Mth;

/** Dedicated post chain, leaving Minecraft's existing effect selection intact. Never changes the camera. */
public final class TemporalScreen {
    private static PostChain chain;
    private static long start,end;
    private static int mode,width,height;
    private static boolean failed;
    public static void trigger(String effect,boolean self){int duration;switch(effect){case "stop"-> {mode=1;duration=120;}case "slip"->{mode=2;duration=28;}case "ascend"->{mode=3;duration=self?140:65;}case "resume"->{mode=4;duration=16;}case "dilate"->{mode=1;duration=60;}case "fracture","rift_open"->{mode=2;duration=34;}case "rift_cross"->{mode=3;duration=24;}default->{return;}}start=ClientState.now();end=start+duration;}
    public static void close(){if(chain!=null)chain.close();chain=null;failed=false;end=0;}
    public static void render(float partial){var mc=Minecraft.getInstance();if(mc.level==null||mc.options.hideGui||failed)return;boolean suspended=mc.player!=null&&ClientState.frozen(mc.player.getId());if(ClientState.now()>end&&!suspended)return;
        try{
            if(chain==null){chain=new PostChain(mc.getTextureManager(),mc.getResourceManager(),mc.getMainRenderTarget(),Loki.id("shaders/post/temporal.json"));width=0;}
            int w=mc.getWindow().getWidth(),h=mc.getWindow().getHeight();if(width!=w||height!=h){chain.resize(w,h);width=w;height=h;}
            float age=ClientState.now()+partial-start;float duration=Math.max(1,end-start);float power=suspended?1:Math.min(1,age/5)*Mth.clamp((duration-age)/12,0,1);
            for(var pass:((com.loki.mixin.PostChainAccessor)(Object)chain).loki$passes()) {var fx=pass.getEffect();var uniform=fx.getUniform("Strength");if(uniform!=null)uniform.set(power);uniform=fx.getUniform("Phase");if(uniform!=null)uniform.set(age/20);uniform=fx.getUniform("Mode");if(uniform!=null)uniform.set((float)mode);}
            RenderSystem.disableDepthTest();chain.process(partial);mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
        }catch(Exception ex){failed=true;if(chain!=null){chain.close();chain=null;}org.slf4j.LoggerFactory.getLogger("Loki").warn("Temporal post effect unavailable",ex);mc.getMainRenderTarget().bindWrite(false);}
    }
}
