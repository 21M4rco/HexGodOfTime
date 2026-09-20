package com.hexgodofstories.client;
import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.Mth;

/** Dedicated post chain, leaving Minecraft's existing effect selection intact. Never changes the camera. */
public final class TemporalScreen {
    /** Chromatic compression while a charge is held, and the opening of the torrent on release. */
    public static final int BRANCH_CHARGE=6,BRANCH_RELEASE=5;
    private static PostChain chain;
    private static long start,end;
    private static int mode,width,height;
    private static boolean failed;
    public static void trigger(String effect,boolean self){int duration;switch(effect){case "stop"-> {mode=1;duration=120;}case "slip"->{mode=2;duration=28;}case "ascend"->{mode=3;duration=self?140:65;}case "resume"->{mode=4;duration=16;}case "dilate"->{mode=1;duration=60;}case "fracture","rift_open"->{mode=2;duration=34;}case "rift_cross"->{mode=3;duration=24;}case "branch_release"->{mode=BRANCH_RELEASE;duration=self?34:22;}default->{return;}}start=ClientState.now();end=start+duration;}
    public static void close(){if(chain!=null)chain.close();chain=null;failed=false;end=0;}
    public static void render(float partial){var mc=Minecraft.getInstance();if(mc.level==null||mc.options.hideGui||failed)return;boolean suspended=mc.player!=null&&ClientState.frozen(mc.player.getId());
        // A held charge drives the effect continuously rather than decaying from a trigger, and it is
        // kept deliberately mild: the caster still has to be able to aim through it.
        int charge=mc.player==null?-1:ClientState.branchHeld(mc.player.getId(),partial);
        float sustained=charge<0?0:Mth.clamp(com.hexgodofstories.data.BranchCharge.power(charge)*.62f
            +com.hexgodofstories.data.BranchCharge.overcharge(charge)*.2f,0,.82f);
        // Phase is driven from the charge itself, so the warp animates with the hold rather than
        // sitting still at whatever a trigger's decay curve happened to leave it on.
        if(sustained>0){mode=BRANCH_CHARGE;start=ClientState.now()-charge;end=ClientState.now()+20;}
        if(ClientState.now()>end&&!suspended&&sustained<=0)return;
        try{
            if(chain==null){chain=new PostChain(mc.getTextureManager(),mc.getResourceManager(),mc.getMainRenderTarget(),HexGodOfStories.id("shaders/post/temporal.json"));width=0;}
            int w=mc.getWindow().getWidth(),h=mc.getWindow().getHeight();if(width!=w||height!=h){chain.resize(w,h);width=w;height=h;}
            float age=ClientState.now()+partial-start;float duration=Math.max(1,end-start);float power=suspended?1:Math.min(1,age/5)*Mth.clamp((duration-age)/12,0,1);
            if(sustained>0)power=sustained;
            for(var pass:((com.hexgodofstories.mixin.PostChainAccessor)(Object)chain).hgos$passes()) {var fx=pass.getEffect();var uniform=fx.getUniform("Strength");if(uniform!=null)uniform.set(power);uniform=fx.getUniform("Phase");if(uniform!=null)uniform.set(age/20);uniform=fx.getUniform("Mode");if(uniform!=null)uniform.set((float)mode);}
            RenderSystem.disableDepthTest();chain.process(partial);mc.getMainRenderTarget().bindWrite(false);RenderSystem.enableDepthTest();
        }catch(Exception ex){failed=true;if(chain!=null){chain.close();chain=null;}org.slf4j.LoggerFactory.getLogger("HexGodOfStories").warn("Temporal post effect unavailable",ex);mc.getMainRenderTarget().bindWrite(false);}
    }
}
