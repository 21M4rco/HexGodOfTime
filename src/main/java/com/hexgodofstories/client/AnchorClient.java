package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import java.util.*;

/** Rendering/input only. Nothing on this side chooses victims or deals damage. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class AnchorClient {
    private AnchorClient() {}
    private record Blast(Vec3 centre,long start) {}
    private static final List<Blast> BLASTS=new ArrayList<>();
    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();
    private static boolean down,latched;
    private static int heartbeat;
    private static Object world;
    public static boolean grasping(int id){return ClientState.data(id).getLong("gravityUntil")>ClientState.now();}
    public static boolean slashing(int id){return ClientState.data(id).getLong("gravitySlashUntil")>ClientState.now();}
    public static boolean busy(int id){return grasping(id)||slashing(id);}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        boolean key=HexClient.SECONDARY.isDown();
        if(world!=mc.level){BLASTS.clear();PAINTER.discard();down=false;latched=key;world=mc.level;}
        boolean usable=mc.player!=null&&mc.player.isAlive()&&mc.screen==null&&HexClient.enabled()
            &&Ability.at(ClientState.self().getInt("selected"))==Ability.THREADS&&!ClientState.immobile(mc.player);
        if(!usable) {
            if(down&&mc.getConnection()!=null)HexNetwork.send(HexServer.GRAVITY_END,0);
            down=false;if(key)latched=true;
        }
        if(!key){latched=false;if(down)HexNetwork.send(HexServer.GRAVITY_END,0);down=false;}
        if(usable&&key&&!latched) {
            if(!down){down=true;heartbeat=0;HexNetwork.send(HexServer.GRAVITY_BEGIN,0);}
            else if(++heartbeat>=10){heartbeat=0;HexNetwork.send(HexServer.GRAVITY_KEEP,0);}
        }
        BLASTS.removeIf(b->ClientState.now()-b.start>32);
    }
    public static void explode(CompoundTag n) {
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(BLASTS.size()>=16)BLASTS.remove(0);
        BLASTS.add(new Blast(at,ClientState.now()));
        DustParticleOptions green=new DustParticleOptions(new Vector3f(.1f,1f,.32f),2);
        Vfx.bloom(-1,at,Vec3.ZERO,14,(pos,look,t)->{
            Vfx.dome(green,pos,.5+t*AnchorRules.BLAST_RADIUS,12,.22);
            Vfx.ring(HexGodOfStories.EMBER.get(),pos.add(0,-.65,0),.5+t*AnchorRules.BLAST_RADIUS,16,.2,.02);
        });
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        Vec3 camera=event.getCamera().getPosition();float partial=event.getPartialTick();
        double time=ClientState.time(partial);
        for(var entry:ClientState.PLAYERS.entrySet()) {
            if(!grasping(entry.getKey()))continue;
            if(!(mc.level.getEntity(entry.getKey()) instanceof AbstractClientPlayer player)||player.distanceToSqr(camera)>4096)continue;
            Vec3 look=player.getViewVector(partial),side=new Vec3(look.z,0,-look.x).normalize();
            Vec3 centre=player.getEyePosition(partial).add(look.scale(1.25)).add(side.scale(.32)).add(0,-.25,0);
            double held=time-entry.getValue().getLong("gravityStart"),power=Mth.clamp(held/AnchorRules.HOLD_TICKS,0,1);
            double radius=.18+.12*power;
            // Dense black core, a narrow luminous rim, and spirals that visibly fall into it.
            BranchVfx.billboard(PAINTER,BranchVfx.shadow(),centre,radius,0,0x000000,1);
            Vec3 u=BranchVfx.perpendicular(look),v=look.cross(u).normalize();
            ring(centre,u,v,radius*1.1,.025,0x8affb2,.95f);
            for(int arm=0;arm<3;arm++) {
                Vec3 previous=null;
                for(int i=0;i<22;i++) {
                    double f=i/21.0,a=arm*Math.PI*2/3+f*5-time*(.16+.14*power),r=radius+.65*(1-f);
                    Vec3 next=centre.add(u.scale(Math.cos(a)*r)).add(v.scale(Math.sin(a)*r));
                    if(previous!=null)BranchVfx.ribbon(PAINTER,BranchVfx.strand(),previous,next,.012+.012*f,0x67bb91,(float)(.15+.55*f));
                    previous=next;
                }
            }
        }
        for(Blast b:BLASTS) {
            double age=time-b.start;if(age<0||age>32||b.centre.distanceToSqr(camera)>4096)continue;
            double f=age/32,r=.35+AnchorRules.BLAST_RADIUS*Math.sin(Math.min(1,f*1.5)*Math.PI/2);
            float alpha=(float)Math.pow(1-f,1.6);
            BranchVfx.billboard(PAINTER,BranchVfx.glow(),b.centre,3.8*(1-f),0,0xbaffc5,alpha);
            for(int i=0;i<24;i++) {
                double y=1-2*(i+.5)/24,a=i*2.39996323,rad=Math.sqrt(1-y*y);
                Vec3 direction=new Vec3(Math.cos(a)*rad,y,Math.sin(a)*rad);
                Vec3 at=b.centre.add(direction.scale(r*.75));
                BranchVfx.billboard(PAINTER,BranchVfx.cloud(),at,1.6+f*2.4,a,0x23ff67,alpha*.75f);
                BranchVfx.ribbon(PAINTER,BranchVfx.strand(),b.centre.add(direction.scale(r*.35)),b.centre.add(direction.scale(r)),.12,0x8aff9c,alpha);
            }
            ring(b.centre.add(0,-.7,0),new Vec3(1,0,0),new Vec3(0,0,1),r,.18,0x66ff91,alpha);
        }
        PoseStack pose=event.getPoseStack();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        PAINTER.flush(pose,mc.renderBuffers().bufferSource());pose.popPose();
    }
    private static void ring(Vec3 c,Vec3 u,Vec3 v,double radius,double width,int colour,float alpha) {
        Vec3 previous=c.add(u.scale(radius));
        for(int i=1;i<=56;i++) {
            double a=i*Math.PI*2/56;Vec3 next=c.add(u.scale(Math.cos(a)*radius)).add(v.scale(Math.sin(a)*radius));
            BranchVfx.ribbon(PAINTER,BranchVfx.glow(),previous,next,width,colour,alpha);previous=next;
        }
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Vec3 at=event.getCamera().getPosition();double time=ClientState.time((float)event.getPartialTick());
        float shake=0;
        for(Blast b:BLASTS) {
            double age=time-b.start;if(age<0||age>24)continue;
            shake+=Math.max(0,1-at.distanceTo(b.centre)/32)*Math.pow(1-age/24,2)*2.4;
        }
        event.setPitch(event.getPitch()+(float)Math.sin(time*2.3)*shake);
        event.setYaw(event.getYaw()+(float)Math.sin(time*1.7)*shake*.5f);
        event.setRoll(event.getRoll()+(float)Math.cos(time*1.9)*shake*.6f);
    }
    /** Own the right hand only while grasping. The player's real inventory is never replaced. */
    @SubscribeEvent public static void hand(RenderHandEvent event) {
        var mc=Minecraft.getInstance();if(mc.player==null)return;
        if(ClientState.self().getLong("anchorVanishUntil")>ClientState.now()){event.setCanceled(true);return;}
        if(!busy(mc.player.getId()))return;
        HumanoidArm arm=event.getHand()==InteractionHand.MAIN_HAND?mc.player.getMainArm():mc.player.getMainArm().getOpposite();
        if(arm!=HumanoidArm.RIGHT)return;
        event.setCanceled(true);
        if(ClientState.hidden(mc.player))return;
        float slash=slashProgress(mc.player.getId(),event.getPartialTick());
        float arc=slash<0?0:(float)Math.sin(slash*Math.PI);
        PoseStack pose=event.getPoseStack();pose.pushPose();
        pose.translate(.6325-arc*.56,-.315+arc*.14,-.50);
        pose.mulPose(Axis.XP.rotationDegrees(-85));pose.mulPose(Axis.ZP.rotationDegrees(arc*65));
        if(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerRenderer renderer) {
            renderer.renderRightHand(pose,event.getMultiBufferSource(),event.getPackedLight(),mc.player);
            if(slash>=0) {
                renderer.getModel().rightArm.translateAndRotate(pose);pose.translate(-.0625,.625,0);
                pose.mulPose(Axis.XP.rotationDegrees(180));
                WeaponRenderer.draw(0,pose,event.getMultiBufferSource(),event.getPackedLight(),1);
            }
        }
        pose.popPose();
    }
    private static float slashProgress(int id,float partial) {
        long end=ClientState.data(id).getLong("gravitySlashUntil");
        if(end<=ClientState.now())return -1;
        return Mth.clamp(ClientState.since(end-10,partial)/10f,0,1);
    }
    public static final class DaggerLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
        public DaggerLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
        @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer player,
                                     float walk,float amount,float partial,float age,float yaw,float pitch) {
            if(!slashing(player.getId())||ClientState.hidden(player))return;
            pose.pushPose();getParentModel().translateToHand(HumanoidArm.RIGHT,pose);
            pose.translate(-.0625,.625,0);pose.mulPose(Axis.XP.rotationDegrees(180));
            WeaponRenderer.draw(0,pose,buffers,light,1);pose.popPose();
        }
    }
}
