package com.hexgodofstories.client;

import com.hexgodofstories.entity.SpellProjectile;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Emerald Throw in flight.
 *
 * <p>A body of energy rather than a sprite: a white-hot core, a husk of seidr wound around it, several
 * strands braided down the wake and a few filaments shaken loose off the sides. It is drawn through one
 * additive sheet, so the whole projectile costs a single draw call however many strands it has, and the
 * whole silhouette is built from the entity's own spin seed and travel direction — no texture, no billboard
 * sprite, no recoloured line.
 */
public final class SpellRenderer extends EntityRenderer<SpellProjectile> {
    public SpellRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(SpellProjectile e){return WorldEffects.WHITE;}

    @Override public void render(SpellProjectile e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        boolean charged=e.style()==1;
        float open=e.opened(partial);
        double scale=(charged?.30:.17)*(.45+.55*open);
        double time=ClientState.now()+partial;
        float seed=e.spin();
        Vec3 travel=e.getDeltaMovement();
        Vec3 forward=travel.lengthSqr()<1e-8?new Vec3(0,0,1):travel.normalize();
        // Drawn around the entity's own origin: the pose stack is already there.
        Vec3 origin=Vec3.ZERO;
        Vec3 side=BranchVfx.perpendicular(forward),up=side.cross(forward).normalize();
        VertexConsumer out=buffers.getBuffer(BranchVfx.glow());

        // The core, and a soft body around it.
        BranchVfx.billboard(pose,out,origin,scale*1.0,seed+time*.2,TemporalPalette.hot((float)(time*.07+seed),.9f),.85f);
        BranchVfx.billboard(pose,out,origin,scale*2.1,-seed+time*.09,TemporalPalette.seidr((float)(time*.04+seed)),.34f);
        BranchVfx.billboard(pose,out,origin,scale*3.4,seed*.5+time*.05,TemporalPalette.seidr((float)(time*.03+seed+.4f)),.14f);

        // The husk: rings wound around the core, tighter at the front than the back.
        int rings=charged?5:4;
        for(int r=0;r<rings;r++) {
            double t=r/(double)rings;
            double back=-t*(charged?1.5:.95);
            double radius=scale*(1.35-t*.55);
            int points=7;
            for(int i=0;i<points;i++) {
                double a0=seed+i*Math.PI*2/points+time*.24+t*2.1,a1=seed+(i+1)*Math.PI*2/points+time*.24+t*2.1;
                Vec3 p0=origin.add(forward.scale(back)).add(side.scale(Math.cos(a0)*radius)).add(up.scale(Math.sin(a0)*radius));
                Vec3 p1=origin.add(forward.scale(back)).add(side.scale(Math.cos(a1)*radius)).add(up.scale(Math.sin(a1)*radius));
                BranchVfx.ribbon(pose,out,p0,p1,scale*.16,TemporalPalette.seidr((float)(time*.05+t*.5)),(float)(.55-t*.3));
            }
        }

        // Strands braided down the wake, so the throw has a direction you can read at a glance.
        int strands=charged?5:3;
        double length=charged?2.6:1.6;
        for(int s=0;s<strands;s++) {
            double phase=seed+s*Math.PI*2/strands;
            int steps=8;
            Vec3[] points=new Vec3[steps+1];
            for(int i=0;i<=steps;i++) {
                double t=i/(double)steps;
                double a=phase+t*(charged?7.5:5.5)-time*.3;
                double radius=scale*(1.15+t*1.5)*(1-t*.55);
                points[i]=origin.add(forward.scale(-t*length))
                    .add(side.scale(Math.cos(a)*radius)).add(up.scale(Math.sin(a)*radius));
            }
            trail(pose,out,points,scale*.22,(float)(time*.05+s*.17),.55f);
        }

        // A couple of filaments shaken loose off the sides; they are what sell it as unstable.
        for(int f=0;f<(charged?3:2);f++) {
            long bolt=(long)(time/2)*13+f+e.getId();
            double a=TemporalLightning.rand(bolt,1)*Math.PI*2;
            Vec3 from=origin.add(side.scale(Math.cos(a)*scale)).add(up.scale(Math.sin(a)*scale));
            Vec3 to=from.add(side.scale(Math.cos(a)*scale*2.6)).add(up.scale(Math.sin(a)*scale*2.6))
                .add(forward.scale(-scale*(1+TemporalLightning.rand(bolt,2)*2)));
            int steps=4;
            Vec3[] points=new Vec3[steps+1];
            for(int i=0;i<=steps;i++) {
                double t=i/(double)steps;
                points[i]=from.add(to.subtract(from).scale(t))
                    .add(side.scale((TemporalLightning.rand(bolt,10+i)-.5)*scale*.8*Math.sin(t*Math.PI)))
                    .add(up.scale((TemporalLightning.rand(bolt,20+i)-.5)*scale*.8*Math.sin(t*Math.PI)));
            }
            trail(pose,out,points,scale*.10,(float)(time*.06+f*.2),.42f);
        }
        super.render(e,yaw,partial,pose,buffers,light);
    }

    /** A polyline in sorcery colours rather than the ultimate's spectral ring. */
    private static void trail(PoseStack pose,VertexConsumer out,Vec3[] points,double width,float phase,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-2);
            double taper=Math.sin(t*Math.PI)*.7+.3;
            BranchVfx.ribbon(pose,out,points[i],points[i+1],width*taper,
                TemporalPalette.seidr(phase+t*.3f),alpha*(1-t*.5f));
        }
    }
}
