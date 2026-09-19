package com.loki.client;

import com.loki.Loki;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.*;
import org.joml.Vector3f;
import java.util.*;

public final class WorldEffects {
    public static final ResourceLocation WHITE=Loki.id("textures/white.png");
    private record Effect(int entity,String kind,Vec3 pos,long start,int duration) {}
    private record Echo(int entity,Vec3 pos,long end) {}
    private record Terrain(Vec3 origin,long end) {}
    private static final List<Terrain> TERRAIN=new ArrayList<>();
    private static final Map<Integer,Long> REFORMING=new HashMap<>();
    private static final List<Effect> EFFECTS=new ArrayList<>();
    private static final List<Echo> ECHOES=new ArrayList<>();
    private static final Set<Integer> POSED=new HashSet<>();
    private static final Map<Integer,Long> SLIPPING=new HashMap<>();
    public static void clear(){EFFECTS.clear();TERRAIN.clear();REFORMING.clear();ECHOES.clear();POSED.clear();SLIPPING.clear();}
    public static void add(int entity,CompoundTag n){String name=n.getString("effect");Vec3 pos=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));int duration=name.equals("ascend")?140:name.equals("stop")?120:name.equals("slip")?26:24;
        if(EFFECTS.size()>=96)EFFECTS.remove(0);EFFECTS.add(new Effect(entity,name,pos,ClientState.now(),duration));
        var p=Minecraft.getInstance().player;
        if(p!=null&&p.distanceToSqr(pos)<1600)TemporalScreen.trigger(name,entity==p.getId());
        if(name.equals("arrive")||name.equals("disguise"))REFORMING.put(entity,ClientState.now()+18);
        if(name.equals("slip")){SLIPPING.put(entity,ClientState.now()+26);for(int i=0;i<5;i++)ECHOES.add(new Echo(entity,pos.add(0,i*.08,i*.12),ClientState.now()+24+i*2));}
        if(name.equals("depart")||name.equals("arrive"))ECHOES.add(new Echo(entity,pos,ClientState.now()+18));
    }
    public static void terrain(CompoundTag n){if(TERRAIN.size()>16)TERRAIN.remove(0);TERRAIN.add(new Terrain(new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z")),n.getLong("until")));}
    public static void memory(int entity,CompoundTag n){int count=Math.min(24,n.getInt("count"));for(int i=0;i<count;i+=2){CompoundTag p=n.getCompound("p"+i);ECHOES.add(new Echo(entity,new Vec3(p.getDouble("x"),p.getDouble("y"),p.getDouble("z")),ClientState.now()+80));}}
    public static void tick(){TERRAIN.removeIf(e->e.end<ClientState.now());REFORMING.entrySet().removeIf(e->e.getValue()<ClientState.now());EFFECTS.removeIf(e->ClientState.now()>e.start+e.duration);ECHOES.removeIf(e->ClientState.now()>e.end);SLIPPING.entrySet().removeIf(e->e.getValue()<ClientState.now());while(ECHOES.size()>72)ECHOES.remove(0);}
    public static void beforePlayer(RenderPlayerEvent.Pre e){Long until=SLIPPING.get(e.getEntity().getId());Long reform=REFORMING.get(e.getEntity().getId());if(reform!=null){float phase=1-(reform-ClientState.now()-e.getPartialTick())/18f;e.getPoseStack().pushPose();POSED.add(e.getEntity().getId());float smooth=Mth.clamp(phase*phase*(3-2*phase),.02f,1);e.getPoseStack().scale(smooth,1,smooth);return;}if(until==null)return;float t=(until-ClientState.now()-e.getPartialTick())/26f;float wave=(float)Math.sin(t*Math.PI);e.getPoseStack().pushPose();POSED.add(e.getEntity().getId());e.getPoseStack().scale(1-wave*.18f,1+wave*.45f,1-wave*.28f);e.getPoseStack().mulPose(Axis.ZP.rotationDegrees((float)Math.sin(t*18)*wave*12));}
    public static void afterPlayer(RenderPlayerEvent.Post e){if(POSED.remove(e.getEntity().getId()))e.getPoseStack().popPose();}
    public static void render(RenderLevelStageEvent event){var mc=Minecraft.getInstance();if(mc.level==null)return;PoseStack pose=event.getPoseStack();Vec3 camera=event.getCamera().getPosition();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);var buffers=mc.renderBuffers().bufferSource();var out=buffers.getBuffer(RenderType.entityTranslucent(WHITE));double now=ClientState.now()+event.getPartialTick();
        for(Effect e:EFFECTS){float age=(float)(now-e.start),t=Mth.clamp(age/e.duration,0,1);Entity entity=mc.level.getEntity(e.entity);Vec3 origin=e.kind.equals("ascend")&&entity!=null?entity.getPosition(event.getPartialTick()):e.pos;
            boolean time=e.kind.equals("stop")||e.kind.equals("slip")||e.kind.equals("dilate")||e.kind.equals("ascend")||e.kind.equals("bind");int color=time?0xc8ba7b:0x39cf78;
            if(e.kind.equals("stop")||e.kind.equals("dilate")){float radius=Math.min(18,age*1.1f);ring(pose,out,origin.add(0,.08,0),radius,.025f,color,(1-t)*.8f,0);if(age<24)ring(pose,out,origin.add(0,1,0),radius*.7f,.013f,0xefffe9,1-age/24,Math.PI/2);}
            else {int count=e.kind.equals("ascend")?14:6;for(int i=0;i<count;i++){Vec3 last=null;for(int j=0;j<18;j++){float f=j/17f;double a=i*Math.PI*2/count+f*2.6+age*.045;double radius=(e.kind.equals("ascend")?.7:.35)*(1+.25*Math.sin(f*Math.PI));Vec3 v=origin.add(Math.cos(a)*radius,.15+f*2.2,Math.sin(a)*radius);if(last!=null)ribbon(pose,out,last,v,.008f*(float)Math.sin(f*Math.PI),i%3==0?0xd8c780:color,(1-t)*.7f);last=v;}}}
        }
        for(var entry:ClientState.THREADS.entrySet()){Entity owner=mc.level.getEntity(entry.getKey()),target=mc.level.getEntity(entry.getValue().target());if(owner==null||target==null)continue;
            Vec3 forward=owner.getLookAngle(),side=new Vec3(-forward.z,0,forward.x).normalize();Vec3 hand=owner.getPosition(event.getPartialTick()).add(0,1.35,0).add(side.scale(.33)).add(forward.scale(.45));Vec3 end=target.getPosition(event.getPartialTick()).add(0,target.getBbHeight()*.55,0);
            for(int thread=0;thread<4;thread++){Vec3 last=hand;for(int i=1;i<=40;i++){double t=i/40.0,a=t*Math.PI*6+now*.025+thread*Math.PI/2;Vec3 v=hand.lerp(end,t).add(Math.cos(a)*Math.sin(t*Math.PI)*.22,Math.sin(a)*.18-Math.sin(t*Math.PI)*.25,Math.sin(a)*Math.sin(t*Math.PI)*.22);ribbon(pose,out,last,v,.007f,thread==0?0xe6db9b:0x66c98a,.8f);last=v;}ring(pose,out,end.add(0,(thread-1.5)*.2,0),target.getBbWidth()*.7+.2,.009f,0xbdd68b,.8f,thread*.3);}
        }
        for(Terrain terrain:TERRAIN)for(int x=-1;x<=1;x++)for(int y=0;y<2;y++){pose.pushPose();pose.translate(terrain.origin.x+x,terrain.origin.y+y,terrain.origin.z);mc.getBlockRenderer().renderSingleBlock(net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(),pose,buffers,15728880,OverlayTexture.NO_OVERLAY);pose.popPose();}
        for(Echo echo:ECHOES){Entity entity=mc.level.getEntity(echo.entity);if(!(entity instanceof AbstractClientPlayer p))continue;var renderer=mc.getEntityRenderDispatcher().getRenderer(p);if(!(renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer))continue;
            pose.pushPose();pose.translate(echo.pos.x,echo.pos.y,echo.pos.z);pose.mulPose(Axis.YP.rotationDegrees(180-p.yBodyRot));pose.scale(-1,-1,1);pose.translate(0,-1.501,0);float alpha=(float)Math.min(.23f,(echo.end-now)/60f);var model=playerRenderer.getModel();model.setupAnim(p,0,0,p.tickCount,p.getYHeadRot()-p.yBodyRot,p.getXRot());model.renderToBuffer(pose,buffers.getBuffer(RenderType.entityTranslucent(p.getSkinTextureLocation())),15728880,OverlayTexture.NO_OVERLAY,.55f,.95f,.7f,Math.max(0,alpha));pose.popPose();
        }
        pose.popPose();buffers.endBatch(RenderType.entityTranslucent(WHITE));
    }
    public static void ring(PoseStack pose,VertexConsumer out,Vec3 center,double radius,float width,int color,float alpha,double tilt){Vec3 last=null;for(int i=0;i<=80;i++){double a=i*Math.PI*2/80;Vec3 v=center.add(Math.cos(a)*radius,Math.sin(a)*radius*Math.sin(tilt),Math.sin(a)*radius*Math.cos(tilt));if(last!=null)ribbon(pose,out,last,v,width,color,alpha);last=v;}}
    public static void ribbon(PoseStack pose,VertexConsumer out,Vec3 a,Vec3 b,float width,int color,float alpha){Vec3 view=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().subtract(a).normalize();Vec3 side=b.subtract(a).cross(view).normalize().scale(width);Vec3[] v={a.subtract(side),a.add(side),b.add(side),b.subtract(side)};float[][] q=new float[4][3];for(int i=0;i<4;i++)q[i]=new float[]{(float)v[i].x,(float)v[i].y,(float)v[i].z};quad(pose,out,q,new float[][]{{0,0},{1,0},{1,1},{0,1}},15728880,color,alpha);}
    public static void quad(PoseStack pose,VertexConsumer out,float[][] v,float[][] uv,int light,int color,float alpha){Vector3f n=new Vector3f(v[1][0]-v[0][0],v[1][1]-v[0][1],v[1][2]-v[0][2]).cross(new Vector3f(v[2][0]-v[0][0],v[2][1]-v[0][1],v[2][2]-v[0][2]));if(n.lengthSquared()<1e-12f)return;n.normalize();for(int i=0;i<4;i++)out.vertex(pose.last().pose(),v[i][0],v[i][1],v[i][2]).color(color>>16&255,color>>8&255,color&255,Mth.clamp((int)(alpha*255),0,255)).uv(uv[i][0],uv[i][1]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.last().normal(),n.x,n.y,n.z).endVertex();}
}
