package com.loki.client;

import com.loki.data.BranchFistState;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Arm-local geometry follows the actual right-arm bone through walking, jumping and punching. */
public final class BranchFistLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
    public BranchFistLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
    public static float impact(int id,float partial) {
        var d=ClientState.data(id);
        if(!d.contains(BranchFistState.IMPACT))return 0;
        float age=ClientState.now()+partial-d.getLong(BranchFistState.IMPACT);
        return age<0||age>7?0:(float)Math.sin(Math.PI*age/7);
    }
    public static boolean visible(int id,float partial) {
        return ClientState.data(id).getLong(BranchFistState.UNTIL)>ClientState.now()+partial||impact(id,partial)>0;
    }
    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,
                                  float walk,float amount,float partial,float age,float yaw,float pitch) {
        if(p.isSpectator()||ClientState.hidden(p)||!visible(p.getId(),partial))return;
        pose.pushPose();
        getParentModel().rightArm.translateAndRotate(pose);
        draw(pose,buffers,p,partial);
        pose.popPose();
    }
    /** Shared with first-person rendering; the glow and strands use the full beam's own sheets/palette. */
    public static void draw(PoseStack pose,MultiBufferSource buffers,AbstractClientPlayer player,float partial) {
        int id=player.getId();if(!visible(id,partial))return;
        var data=ClientState.data(id);double time=ClientState.now()+partial;
        float strike=impact(id,partial);
        float strength=data.getLong(BranchFistState.UNTIL)>time
            ?Mth.clamp((data.getLong(BranchFistState.UNTIL)-(float)time)/8,0,1):strike;
        double pulse=1+.085*Math.sin(time*1.7)+.045*Math.sin(time*3.2);
        // Three rippling sleeves. Dense around the knuckles, tapering halfway up the forearm.
        VertexConsumer out=buffers.getBuffer(BranchVfx.glow());
        for(int shell=0;shell<3;shell++)for(int y=0;y<5;y++)for(int side=0;side<12;side++) {
            double ya=.25+y*.087,yb=ya+.087;
            double ra=(.12+.065*Math.sin((y+1)*Math.PI/7))*(1+shell*.12)*pulse;
            double rb=(.12+.065*Math.sin((y+2)*Math.PI/7))*(1+shell*.12)*pulse;
            double a=side*Math.PI/6+time*(.055+shell*.025)+y*.19,b=a+Math.PI/6;
            ra*=1+.10*Math.sin(a*3+time*1.3);rb*=1+.10*Math.sin(b*4-time*1.1);
            quad(pose,out,ring(a,ya,ra),ring(b,ya,ra),ring(b+.2,yb,rb),ring(a+.2,yb,rb),
                TemporalPalette.hot((float)(time*.028+side*.05+shell*.2+y*.09),.18f+strike*.65f),
                strength*(.28f-shell*.05f));
        }
        out=buffers.getBuffer(BranchVfx.strand());
        for(int strand=0;strand<7;strand++)for(int step=0;step<12;step++) {
            double a=strand*Math.PI*2/7+step*.52+time*(strand%2==0?.38:-.31);
            double y=.27+step*.034,r=(.18+.025*Math.sin(step*.8+time*1.9))*pulse;
            Vec3 from=ring(a,y,r),to=ring(a+.52,y+.034,r);
            // Two crossed strips keep a strand volumetric even when the arm turns edge-on.
            double half=.010+strike*.012;
            Vec3 side=new Vec3(Math.cos(a)*half,0,Math.sin(a)*half),up=new Vec3(0,half,0);
            int colour=TemporalPalette.hot((float)(time*.052+strand*.13+step*.02),.55f);
            quad(pose,out,from.subtract(side),from.add(side),to.add(side),to.subtract(side),colour,strength*.9f);
            quad(pose,out,from.subtract(up),from.add(up),to.add(up),to.subtract(up),colour,strength*.7f);
        }
    }
    private static Vec3 ring(double angle,double y,double radius){return new Vec3(-.055+Math.cos(angle)*radius,y,Math.sin(angle)*radius);}
    private static void quad(PoseStack pose,VertexConsumer out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,int colour,float alpha) {
        vertex(pose,out,a,0,0,colour,alpha);vertex(pose,out,b,1,0,colour,alpha);
        vertex(pose,out,c,1,1,colour,alpha);vertex(pose,out,d,0,1,colour,alpha);
    }
    private static void vertex(PoseStack pose,VertexConsumer out,Vec3 p,float u,float v,int colour,float alpha) {
        out.vertex(pose.last().pose(),(float)p.x,(float)p.y,(float)p.z)
            .color(colour>>16&255,colour>>8&255,colour&255,Math.round(alpha*255)).uv(u,v)
            .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(15728880)
            .normal(pose.last().normal(),0,1,0).endVertex();
    }
}
