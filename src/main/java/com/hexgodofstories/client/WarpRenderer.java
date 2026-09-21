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
        if(n.getLong("opened")>=0)return "REALITY OPEN  /  "+Math.max(0,(WarpMath.OPEN_TICKS-(ClientState.now()-n.getLong("opened"))+19)/20)+"s";
        int ticks=(int)(ClientState.now()-n.getLong("start"));
        return String.format(java.util.Locale.ROOT,"%s  %d%%  /  %.1f blocks",ticks<WarpMath.MIN_CHARGE?"Forming":"Release to trap",Math.min(100,ticks),WarpMath.width(ticks));
    }
    public static void realm(CompoundTag n){realm=n;}
    public static void clear(){WINDOWS.clear();realm=new CompoundTag();WarpScene.clear();}
    public static void render(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        WINDOWS.values().removeIf(n->!n.getString("dimension").equals(mc.level.dimension().location().toString())||n.getLong("until")<=mc.level.getGameTime());
        if(WINDOWS.isEmpty())return;
        // Forge exposes a depth-stencil target. No alternate world load, invasive renderer replacement or recursion.
        if(!mc.getMainRenderTarget().isStencilEnabled())mc.getMainRenderTarget().enableStencil();
        for(CompoundTag n:WINDOWS.values())window(pose,camera,time,n);
    }
    /** Render spatial realm geometry before particles, with explicit depth state independent of effects. */
    public static void renderRealm(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        Destination d=Destination.from(mc.level);
        if(d!=null){
            pose.pushPose();pose.translate(WarpMath.cellX(camera.x)-camera.x,-camera.y,-camera.z);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.depthMask(true);
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShaderColor(1,1,1,1);
            try{WarpScene.draw(pose,d,time,realm.contains("time")?realm.getLong("age")+(long)time-realm.getLong("time"):0,false);}finally{pose.popPose();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.setShaderColor(1,1,1,1);}
        }
    }
    private static void window(PoseStack pose,Vec3 camera,double time,CompoundTag n){
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(camera.distanceToSqr(at)>96*96||camera.y<at.y)return;
        Destination d=Destination.at(n.getInt("destination"));boolean open=n.getLong("opened")>=0;
        int held=open?n.getInt("held"):(int)(time-n.getLong("start"));double half=WarpMath.width(held)*.5;
        double progress=open?1:Math.min(.87,.08+held/115.0);
        double age=open?Math.max(0,time-n.getLong("opened")):0;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableCull();
        GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilMask(255);GL11.glClearStencil(0);GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilFunc(GL11.GL_ALWAYS,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_REPLACE);
        pose.pushPose();pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
        try{
            RenderSystem.colorMask(false,false,false,false);RenderSystem.depthMask(false);RenderSystem.enableDepthTest();
            aperture(pose.last().pose(),half,progress);
            GL11.glStencilMask(0);GL11.glStencilFunc(GL11.GL_EQUAL,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_KEEP);
            // Draw the Nothingness backing from the EXACT aperture polygon, including fractional edges.
            RenderSystem.colorMask(true,true,true,true);aperture(pose.last().pose(),half,progress);
            RenderSystem.colorMask(false,false,false,false);
            // Reset depth only inside the visible opening. The surrounding terrain and creatures remain occluders.
            RenderSystem.depthMask(true);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthRange(1,1);aperture(pose.last().pose(),half,progress);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            pose.pushPose();pose.translate(-d.arrival.x,-d.arrival.y,-d.arrival.z);
            try{
                WarpScene.sky(pose,d,time);
                WarpScene.draw(pose,d,time,open?n.getLong("realmAge")+(long)Math.max(0,time-n.getLong("sent")):0,true);
            }finally{pose.popPose();}
            RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            // Restore the floor depth after drawing the remote scene, so it cannot occlude unrelated world effects.
            RenderSystem.colorMask(false,false,false,false);GL11.glDepthFunc(GL11.GL_ALWAYS);aperture(pose.last().pose(),half,progress);GL11.glDepthFunc(GL11.GL_LEQUAL);RenderSystem.colorMask(true,true,true,true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);RenderSystem.depthMask(false);
            mirror(pose.last().pose(),half,progress,open,age,held,time,d.color);
        }finally{
            pose.popPose();GL11.glStencilMask(255);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        }
    }
    private static void aperture(Matrix4f m,double half,double progress){
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for(int i=0;i<WarpMath.EDGE_COUNT;i++)
            WarpMesh.quad(b,m,Vec3.ZERO,boundary(i,half,progress),boundary(i+1,half,progress),Vec3.ZERO,0x000000,1);
        BufferUploader.drawWithShader(b.end());
    }
    /** Cracks race across glass, then the facets kick upward and expose the dimensional window. */
    private static void mirror(Matrix4f m,double half,double progress,boolean open,double age,int held,double time,int color){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        double burst=open?Math.min(1,age/22.0):0;
        double closing=open?Math.max(0,(age-(WarpMath.OPEN_TICKS-14))/14.0):0;
        double crackReach=open?1:Math.min(1,held/(double)WarpMath.MIN_CHARGE);
        for(int i=0;i<WarpMath.EDGE_COUNT;i++){
            Vec3 p=boundary(i,half,progress),q=boundary(i+1,half,progress);
            Vec3 lip=p.add(0,.035,0),next=q.add(0,.035,0);
            // Layered fracture light follows straight, unequal edges, never a circular vortex.
            float pulse=(float)(.65+.18*Math.sin(time*.09+i*.71));
            WarpMesh.ribbon(b,m,lip,next,.085,color,.16f);
            WarpMesh.ribbon(b,m,lip,next,.025,color,pulse);
            WarpMesh.ribbon(b,m,lip,next,.007,0xf0faff,.94f);
            Vec3 inward=p.scale(.89).add(0,.026,0),inwardNext=q.scale(.89).add(0,.026,0);
            WarpMesh.quad(b,m,lip,next,inwardNext,inward,i%3==0?0xcdeaf2:color,.12f);
            // Offset hubs and side branches form a broken-mirror spiderweb while charging.
            Vec3 hub=new Vec3(Math.sin(i*2.7)*half*.09,.045,Math.cos(i*1.9)*half*.09);
            Vec3 elbow=p.scale(.43+(i%3)*.07).add(0,.045,0);
            Vec3 tip=hub.lerp(p.add(0,.045,0),crackReach);
            float crackAlpha=(float)(open?Math.max(.05,(1-burst)*.85):.75);
            WarpMesh.ribbon(b,m,hub,elbow.lerp(hub,1-crackReach),.012,color,crackAlpha);
            WarpMesh.ribbon(b,m,elbow.lerp(hub,1-crackReach),tip,.009,0xe5f7ff,crackAlpha);
            if(i%2==0)WarpMesh.ribbon(b,m,elbow,q.scale(.7*crackReach).add(0,.045,0),.008,0xc6e3f0,crackAlpha);
            // Facets are reflective-looking tinted glass; the shared destination scene remains visible below.
            float glass=(float)(open?Math.max((1-burst)*.42,closing*.5):.17+.1*Math.sin(i*2.1+time*.025));
            if(glass>.001)WarpMesh.quad(b,m,hub,lip,next,hub,i%3==0?0xd5f1fa:0x728aab,glass);
            // Triangular slivers peel away in a staggered burst, with sparse hovering shards at the rim.
            double t=open?Math.max(0,Math.min(1,(age-i%5)/24.0)):0;
            boolean launching=open&&age<29;
            if(launching||i%3==0){
                double lift=launching?Math.sin(t*Math.PI)*(.55+(i%4)*.24):.10+.06*Math.sin(time*.045+i);
                double spread=launching?1+t*.16:1.015;
                Vec3 a=p.scale(spread).add(0,.06+lift,0);
                Vec3 end=p.lerp(q,.38).scale(spread).add(0,.11+lift*.82,0);
                Vec3 point=p.scale(launching?.78+t*.12:.91).add(0,.23+lift*1.35,0);
                float alpha=launching?(float)((1-t)*.85):.38f;
                WarpMesh.quad(b,m,a,end,point,point,i%2==0?0xe0f5ff:0x92a9c7,alpha);
                WarpMesh.ribbon(b,m,a,point,.007,0xffffff,alpha);
            }
            // An angular light front travels outward as the mirror breaks open.
            if(open&&age<16){
                double sweep=.15+.85*age/16;
                WarpMesh.ribbon(b,m,p.scale(sweep).add(0,.06,0),q.scale(sweep).add(0,.06,0),.025,color,(float)(1-age/16));
            }
        }
        BufferUploader.drawWithShader(b.end());
    }
    private static Vec3 boundary(int i,double half,double progress){
        double spread=Math.floorMod(i,WarpMath.EDGE_COUNT)%2==0?1:progress;
        return new Vec3(WarpMath.edgeX(i,half)*spread,0,WarpMath.edgeZ(i,half)*spread);
    }
}
