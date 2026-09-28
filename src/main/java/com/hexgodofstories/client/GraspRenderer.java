package com.hexgodofstories.client;

import com.hexgodofstories.server.GravityGrasp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Gravity Grasp, seen: a small black hole hanging just out in front of the caster's hand. A core of true black with a
 * darker halo, a thin burning rim round it, an accretion disk of seidr light turning fast about it, and portal motes
 * streaming in from all round; it grows as it is held. Drawn wherever the server says a player is holding it (their
 * {@code graspStart}), from the same place the server pulls toward, and nothing past forty-eight blocks.
 */
public final class GraspRenderer {
    private GraspRenderer() {}

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();
    private static final double VISIBLE=48*48;
    private static final int RIM=28,DISK=36;

    /** Where a player's hole hangs this frame: the same as the server's {@link GravityGrasp#hole}, smoothed for drawing. */
    static Vec3 hole(Player p,float partial) {
        return p.getEyePosition(partial).add(p.getViewVector(partial).scale(1.5)).add(0,-.3,0);
    }

    /** How big it has grown, from a pea to a fist, over the whole hold. */
    private static double radius(Player p,double time) {
        long start=ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING);
        return .16+.28*Mth.clamp((time-start)/GravityGrasp.MOST,0,1);
    }

    private static boolean holding(Player p) {
        return ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING)>0&&!p.isSpectator();
    }

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.time(partial);
        boolean any=false;
        for(Player p:mc.level.players()) {
            if(!holding(p))continue;
            Vec3 at=hole(p,partial);
            if(at.distanceToSqr(camera)>VISIBLE)continue;
            any=true;
            double r=radius(p,time);
            Vec3 right=BranchVfx.cameraRight(),up=BranchVfx.cameraUp();
            // The halo, then the core: black laid over black, so the edge of the dark is soft.
            BranchVfx.billboard(PAINTER,BranchVfx.shadow(),at,r*1.9,0,0x000000,.38f);
            BranchVfx.billboard(PAINTER,BranchVfx.shadow(),at,r,0,0x000000,.98f);
            // The rim: light bent round the edge of it.
            Vec3 last=null;
            for(int i=0;i<=RIM;i++) {
                double a=i*Math.PI*2/RIM;
                Vec3 point=at.add(right.scale(Math.cos(a)*r*1.08)).add(up.scale(Math.sin(a)*r*1.08));
                if(last!=null)BranchVfx.ribbon(PAINTER,BranchVfx.glow(),last,point,r*.09,i%2==0?0xb68cff:0x7dffb0,.85f);
                last=point;
            }
            // The disk: a tilted ring of streaks turning fast, brighter on its inner edge.
            Vec3 look=p.getViewVector(partial);
            Vec3 normal=look.add(0,1.4,0).normalize(),u=BranchVfx.perpendicular(normal),v=normal.cross(u).normalize();
            for(int band=0;band<2;band++) {
                double radiusOf=r*(1.6+band*.7),spin=time*(.55-band*.18);
                last=null;
                for(int i=0;i<=DISK;i++) {
                    double a=i*Math.PI*2/DISK+spin;
                    Vec3 point=at.add(u.scale(Math.cos(a)*radiusOf)).add(v.scale(Math.sin(a)*radiusOf));
                    float fade=(float)(.35+.45*Math.abs(Math.sin(a*1.5)));
                    if(last!=null)BranchVfx.ribbon(PAINTER,BranchVfx.glow(),last,point,r*(.14-band*.04),
                        TemporalPalette.seidr(ClientState.cycle(i/(double)DISK+time*.01)),fade*(band==0?.8f:.5f));
                    last=point;
                }
            }
        }
        if(any)PAINTER.flush(pose,buffers);
        else PAINTER.discard();
    }

    /** Portal motes streaming in from all round every hole in sight: they start out from it and fall into it. */
    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        for(Player p:mc.level.players()) {
            if(!holding(p))continue;
            Vec3 at=hole(p,1);
            if(at.distanceToSqr(eye)>VISIBLE)continue;
            for(int i=0;i<5;i++) {
                Vec3 from=new Vec3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()).normalize().scale(1.5+random.nextDouble()*2.5);
                mc.level.addParticle(ParticleTypes.PORTAL,at.x,at.y,at.z,from.x,from.y,from.z);
            }
        }
    }

    /**
     * The cut, as its caster sees it: the conjured dagger in the right hand, laid flat and raised across to the left at
     * throat height, whipped across to the right, held through the follow-through and dropped away. The hand is drawn
     * here in place of vanilla's for the length of the stroke (the grasp_slash animation leaves first person alone).
     */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid=com.hexgodofstories.HexGodOfStories.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class Stab {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void hand(net.minecraftforge.client.event.RenderHandEvent e) {
            var mc=Minecraft.getInstance();
            if(mc.player==null||e.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND)return;
            double left=ClientState.data(mc.player.getId()).getLong(GravityGrasp.KNIFE)-ClientState.time(e.getPartialTick());
            if(left<=0||left>14)return;
            e.setCanceled(true);
            double t=14-left;
            // Raised across over three ticks, whipped through in two, held four, dropped away over five.
            double raise=Math.min(1,t/3),sweep=t<3?0:Math.min(1,(t-3)/2),drop=t<9?0:(t-9)/5;
            raise=raise*raise*(3-2*raise);
            sweep=1-(1-sweep)*(1-sweep)*(1-sweep);
            drop=drop*drop;
            var stack=new net.minecraft.world.item.ItemStack(com.hexgodofstories.HexGodOfStories.DAGGER.get());
            stack.getOrCreateTag().putUUID("conjurer",mc.player.getUUID());
            stack.getOrCreateTag().putLong("formed",mc.player.level().getGameTime()-40);
            PoseStack pose=e.getPoseStack();
            pose.pushPose();
            double x=Mth.lerp(sweep,Mth.lerp(raise,.56,-.3),.85),y=Mth.lerp(raise,-.52,-.22)-.06*sweep-.9*drop,z=Mth.lerp(raise,-.72,-.85)+.1*sweep;
            pose.translate(x,y,z);
            // Laid flat, point to the left, and swung round to point ahead and right as it goes through.
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float)(80*raise+15*sweep)));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float)(35*raise-75*sweep)));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float)(-15*raise)));
            mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(mc.player,stack,
                net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,false,pose,e.getMultiBufferSource(),e.getPackedLight());
            pose.popPose();
        }
    }
}
