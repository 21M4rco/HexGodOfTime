package com.hexgodofstories.client;

import com.hexgodofstories.warping.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import java.util.*;

/** Depth-tested stencil windows: the destination is spatial geometry seen through jagged floor apertures. */
public final class WarpRenderer {
    private static final Map<Integer,CompoundTag> WINDOWS=new HashMap<>();
    private static CompoundTag realm=new CompoundTag();
    public static void receive(int id,CompoundTag n){if(n.getBoolean("clear"))WINDOWS.remove(id);else WINDOWS.put(id,n);}
    public static String chargeLabel(int id){
        CompoundTag n=WINDOWS.get(id);if(n==null)return "";
        if(n.getLong("opened")>=0)return "REALITY OPEN";
        int ticks=(int)(ClientState.now()-n.getLong("start"));
        return String.format(java.util.Locale.ROOT,"%s  %d%%  /  %.1f blocks",ticks<WarpMath.MIN_CHARGE?"Forming":"Release to trap",Math.min(100,ticks),WarpMath.width(ticks));
    }
    public static void realm(CompoundTag n){realm=n;}
    public static void clear(){WINDOWS.clear();realm=new CompoundTag();WarpScene.clear();}
    public static void render(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        Destination d=Destination.from(mc.level);
        if(d!=null){
            pose.pushPose();pose.translate(WarpMath.cellX(camera.x)-camera.x,-camera.y,-camera.z);
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShaderColor(1,1,1,1);
            try{WarpScene.draw(pose,d,time,realm.contains("time")?realm.getLong("age")+(long)time-realm.getLong("time"):0,false);}finally{pose.popPose();RenderSystem.enableCull();RenderSystem.disableBlend();}
        }
        WINDOWS.values().removeIf(n->n.getLong("until")<mc.level.getGameTime());
        if(WINDOWS.isEmpty())return;
        // Forge exposes a depth-stencil target. No alternate world load, invasive renderer replacement or recursion.
        if(!mc.getMainRenderTarget().isStencilEnabled())mc.getMainRenderTarget().enableStencil();
        for(CompoundTag n:WINDOWS.values())window(pose,camera,time,n);
    }
    private static void window(PoseStack pose,Vec3 camera,double time,CompoundTag n){
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(camera.distanceToSqr(at)>96*96||camera.y<at.y)return;
        Destination d=Destination.at(n.getInt("destination"));boolean open=n.getLong("opened")>=0;
        int held=open?n.getInt("held"):(int)(time-n.getLong("start"));double half=WarpMath.width(held)*.5;
        double progress=open?1:Math.min(.87,.08+held/115.0);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableCull();
        GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilMask(255);GL11.glClearStencil(0);GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilFunc(GL11.GL_ALWAYS,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_REPLACE);
        pose.pushPose();pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
        try{
            RenderSystem.colorMask(false,false,false,false);RenderSystem.depthMask(false);RenderSystem.enableDepthTest();
            aperture(pose.last().pose(),half,progress,false,time);
            GL11.glStencilMask(0);GL11.glStencilFunc(GL11.GL_EQUAL,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_KEEP);
            // Reset depth only inside the visible opening. The surrounding terrain and creatures remain occluders.
            RenderSystem.depthMask(true);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthRange(1,1);aperture(pose.last().pose(),half,progress,false,time);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            pose.pushPose();pose.translate(-d.arrival.x,-d.arrival.y,-d.arrival.z);
            WarpScene.sky(pose,d,time);WarpScene.draw(pose,d,time,open?(long)(time-n.getLong("opened")):0,true);pose.popPose();
            // Restore the floor depth after drawing the remote scene, so it cannot occlude unrelated world effects.
            RenderSystem.colorMask(false,false,false,false);GL11.glDepthFunc(GL11.GL_ALWAYS);aperture(pose.last().pose(),half,progress,false,time);GL11.glDepthFunc(GL11.GL_LEQUAL);RenderSystem.colorMask(true,true,true,true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);RenderSystem.depthMask(false);aperture(pose.last().pose(),half,progress,true,time);
        }finally{
            pose.popPose();GL11.glStencilMask(255);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        }
    }
    private static void aperture(Matrix4f m,double half,double progress,boolean edge,double time){
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for(int i=0;i<32;i++){
            double a=i*Math.PI/16,angle=(i+1)*Math.PI/16;
            Vec3 p=boundary(a,half,progress,i),q=boundary(angle,half,progress,(i+1)%32);
            if(edge){WarpMesh.ribbon(b,m,p.add(0,.012,0),q.add(0,.012,0),.018,0xc3a5db,.9f);
                if(i%4==0){Vec3 shard=p.scale(.92).add(0,Math.sin(time*.045+i)*.04+.09,0);WarpMesh.quad(b,m,p,p.add(.1,.12,.1),shard,shard,0x392340,.8f);}}
            else WarpMesh.quad(b,m,Vec3.ZERO,p,q,Vec3.ZERO,0xffffff,1);
        }
        BufferUploader.drawWithShader(b.end());
    }
    private static Vec3 boundary(double a,double half,double progress,int i){double c=Math.cos(a),s=Math.sin(a),radius=half/Math.max(Math.abs(c),Math.abs(s));double spread=progress>=1?1:i%2==0?1:progress;return new Vec3(c*radius*spread,0,s*radius*spread);}
}
