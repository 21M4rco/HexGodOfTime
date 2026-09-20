package com.loki.client;

import com.loki.entity.StarfallEntity;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A rock burning its way down through the air.
 *
 * <p>Drawn the way one looks: a dark, irregular body of stone with its leading faces glowing where the air
 * is grinding them away, a bright bow shock standing off the front of it, and a long tail that cools as it
 * is left behind — white at the stone, then yellow, then orange, then deep red, then nothing. The green of
 * the sanctum is present only as a faint undertone in the shock, because this is a meteor and not a spell.
 *
 * <p>Two passes and two draw calls: the stone through the ordinary translucent sheet, everything burning
 * through one additive one. The whole silhouette comes from the entity's seed, so no two look alike, and
 * the tail's length and brightness come from the entity's own heat.
 */
public final class MeteorRenderer extends EntityRenderer<StarfallEntity> {
    public MeteorRenderer(EntityRendererProvider.Context ctx){super(ctx);}
    @Override public ResourceLocation getTextureLocation(StarfallEntity e){return WorldEffects.WHITE;}
    @Override public boolean shouldRender(StarfallEntity e,net.minecraft.client.renderer.culling.Frustum frustum,double x,double y,double z){return true;}

    /** Number of stone lumps the body is built from. */
    private static final int LUMPS=7;

    @Override public void render(StarfallEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        float heat=Mth.clamp(e.heat(),0,1);
        float charge=Mth.clamp(e.charge(),0,1);
        double time=ClientState.now()+partial;
        int seed=e.seed();
        Vec3 travel=e.getDeltaMovement();
        Vec3 forward=travel.lengthSqr()<1e-8?new Vec3(0,-1,0):travel.normalize();
        Vec3 side=BranchVfx.perpendicular(forward),up=side.cross(forward).normalize();
        double size=.42+.22*heat;

        // --- the stone -----------------------------------------------------------------
        // Drawn first and on its own sheet, so the additive fire never has to fight it for a batch.
        VertexConsumer rock=buffers.getBuffer(RenderType.entityTranslucent(WorldEffects.WHITE));
        for(int i=0;i<LUMPS;i++) {
            double a=TemporalLightning.rand(seed,i*3)*Math.PI*2;
            double off=(TemporalLightning.rand(seed,i*3+1)-.5)*size*1.5;
            double along=(TemporalLightning.rand(seed,i*3+2)-.35)*size*1.6;
            double half=size*(.30+.34*TemporalLightning.rand(seed,i*3+7));
            Vec3 at=forward.scale(along).add(side.scale(Math.cos(a)*off)).add(up.scale(Math.sin(a)*off));
            // Leading faces glow: they are the ones being ground off.
            float front=(float)Mth.clamp(.5-along/size,0,1);
            int stone=TemporalPalette.blend(0x241d1a,0xff8c3a,front*(.25f+.6f*heat));
            cube(pose,rock,at,half,side,up,forward,stone,.98f);
        }

        VertexConsumer fire=buffers.getBuffer(BranchVfx.glow());

        // --- the shock standing off the front -------------------------------------------
        for(int i=0;i<4;i++) {
            double stand=size*(.9+i*.34);
            int colour=TemporalPalette.blend(0xfff3d0,0x9fffd0,.10f+i*.06f);
            BranchVfx.billboard(pose,fire,forward.scale(stand),size*(1.5-i*.22)*(.7+.5*heat),
                time*.1+i,colour,(.5f-i*.09f)*(.45f+.55f*heat));
        }

        // --- the tail --------------------------------------------------------------------
        double length=(7+22*heat)*(.55+.45*charge);
        int steps=Math.max(6,(int)(length/1.4));
        for(int i=0;i<steps;i++) {
            double t=i/(double)steps,t2=(i+1)/(double)steps;
            Vec3 from=forward.scale(-length*t),to=forward.scale(-length*t2);
            // Cooling with distance behind the stone, and widening as it is left to spread.
            int colour=cool((float)t);
            double width=size*(.85+t*2.6);
            float alpha=(float)((1-t)*(1-t)*.62*(.4+.6*heat));
            // A little churn so the tail is not a straight cone.
            double churn=Math.sin(time*.4+t*9+seed)*size*.28*t;
            BranchVfx.ribbon(pose,fire,from.add(side.scale(churn)),to.add(side.scale(churn)),width,colour,alpha);
            if(i%2==0)
                BranchVfx.billboard(pose,fire,from.add(up.scale(Math.cos(time*.3+t*7)*size*.5*t)),
                    width*1.25,t*4+time*.05,colour,alpha*.7f);
        }

        // --- streaks of ablation torn off the sides --------------------------------------
        for(int f=0;f<3;f++) {
            long bolt=(long)(time/2)*7+f+seed;
            double a=TemporalLightning.rand(bolt,1)*Math.PI*2;
            Vec3 out=side.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
            Vec3 from=out.scale(size*.8);
            int hops=4;
            for(int i=0;i<hops;i++) {
                double t=i/(double)hops,t2=(i+1)/(double)hops;
                Vec3 p0=from.add(out.scale(size*t*1.6)).add(forward.scale(-length*.12*t));
                Vec3 p1=from.add(out.scale(size*t2*1.6)).add(forward.scale(-length*.12*t2));
                BranchVfx.ribbon(pose,fire,p0,p1,size*.10*(1-t),cool((float)t*.6f),(float)((1-t)*.5*heat));
            }
        }
        super.render(e,yaw,partial,pose,buffers,light);
    }

    /** White at the stone through yellow and orange to deep red, then out. */
    private static int cool(float t) {
        if(t<.18f)return TemporalPalette.blend(0xfffdf2,0xffe08a,t/.18f);
        if(t<.45f)return TemporalPalette.blend(0xffe08a,0xff8c20,(t-.18f)/.27f);
        if(t<.75f)return TemporalPalette.blend(0xff8c20,0xc0341a,(t-.45f)/.30f);
        return TemporalPalette.blend(0xc0341a,0x3a2420,(t-.75f)/.25f);
    }

    /** One stone lump, oriented to the body's travel. */
    private static void cube(PoseStack pose,VertexConsumer out,Vec3 at,double half,Vec3 side,Vec3 up,Vec3 forward,int colour,float alpha) {
        Vec3 x=side.scale(half),y=up.scale(half),z=forward.scale(half);
        Vec3[] c={
            at.subtract(x).subtract(y).subtract(z),at.add(x).subtract(y).subtract(z),
            at.add(x).subtract(y).add(z),         at.subtract(x).subtract(y).add(z),
            at.subtract(x).add(y).subtract(z),    at.add(x).add(y).subtract(z),
            at.add(x).add(y).add(z),              at.subtract(x).add(y).add(z)};
        BranchVfx.emit(pose,out,c[4],c[5],c[6],c[7],colour,alpha);
        BranchVfx.emit(pose,out,c[3],c[2],c[1],c[0],colour,alpha);
        BranchVfx.emit(pose,out,c[0],c[1],c[5],c[4],colour,alpha);
        BranchVfx.emit(pose,out,c[2],c[3],c[7],c[6],colour,alpha);
        BranchVfx.emit(pose,out,c[1],c[2],c[6],c[5],colour,alpha);
        BranchVfx.emit(pose,out,c[3],c[0],c[4],c[7],colour,alpha);
    }
}
