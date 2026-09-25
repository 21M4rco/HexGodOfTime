package com.hexgodofstories.client;

import com.hexgodofstories.entity.ConjuredWeapon;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;

/**
 * Conjured weapons are authored with the grip at the origin and the blade running up +Y. Minecraft's item
 * renderer hands a custom renderer the corner of the item's unit cube, which is the same space a vanilla
 * sprite lives in, so the only thing needed for correct hand alignment is to put the grip where a vanilla
 * hilt sits — on the lower-left of that cube — and lay the blade along the up-right diagonal the inherited
 * {@code item/handheld} transforms are built around. Off-hand twins get the same treatment rotated a half
 * turn, which is what makes the reverse grip read as deliberate rather than as a flipped model.
 */
public final class WeaponRenderer extends BlockEntityWithoutLevelRenderer {
    /** Grip position inside the item cube and an overall size trim, one entry per weapon kind. */
    private record Fit(float grip,float scale) {}
    private static final Fit[] FITS={new Fit(.24f,1f),new Fit(.29f,.72f),new Fit(.27f,1f)};
    private static final float DIAGONAL=(float)(1/Math.sqrt(2));
    private static final ResourceLocation SCEPTER_MATERIAL=new ResourceLocation("hexgodofstories","textures/scepter.png");
    private static WeaponRenderer INSTANCE;
    private static final Map<Integer,AuthoredMesh> MODELS=new HashMap<>();

    public WeaponRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public static WeaponRenderer instance(){if(INSTANCE==null)INSTANCE=new WeaponRenderer();return INSTANCE;}
    public static void clear(){MODELS.clear();}
    public static AuthoredMesh mesh(int kind) {return MODELS.computeIfAbsent(kind,k->new AuthoredMesh(k==1?"laevateinn":k==2?"time_stick":"dagger"));}

    /** Draws in weapon space: grip at the origin, blade toward +Y. Used by the hand, the projectile and the decoys. */
    public static void draw(int kind,PoseStack pose,MultiBufferSource buffers,int light,float growth) {
        if(kind==1) {
            // Reveal from the actual grip without squeezing or warping the authored silhouette.
            mesh(1).drawRevealing(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(SCEPTER_MATERIAL)),light,growth);
            if(growth<1)manifestScepter(pose,buffers,growth);
        } else mesh(kind).drawManifesting(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(HexLayer.MATERIAL)),light,growth,0);
        if(kind==1&&growth>.65f)mesh(kind).draw(pose,buffers.getBuffer(RenderType.entityTranslucentEmissive(SCEPTER_MATERIAL)),15728880,
            (group,point)->(group.startsWith("gem_blue")||group.startsWith("gem_glint")||group.startsWith("gem_spark"))&&Math.abs(point.y())<=.60f*growth?point:null);

    }

    /** Hand-local magic rings follow both reveal fronts; no world-space drift while moving. */
    private static void manifestScepter(PoseStack pose,MultiBufferSource buffers,float progress) {
        var out=buffers.getBuffer(RenderType.lightning());
        float time=ClientState.now()+Minecraft.getInstance().getFrameTime();
        float fade=Math.min(1,(1-progress)*6);
        for(int side:new int[]{-1,1}) {
            float y=side*.60f*progress;
            float radius=.045f+.022f*(float)Math.sin(progress*Math.PI);
            for(int i=0;i<40;i++) {
                double a=i*Math.PI/20+time*.15,b=(i+1)*Math.PI/20+time*.15;
                float x0=(float)Math.cos(a)*radius,z0=(float)Math.sin(a)*radius;
                float x1=(float)Math.cos(b)*radius,z1=(float)Math.sin(b)*radius;
                out.vertex(pose.last().pose(),x0,y-.009f,z0).color(.20f,1f,.65f,.8f*fade).endVertex();
                out.vertex(pose.last().pose(),x1,y-.009f,z1).color(.20f,1f,.65f,.8f*fade).endVertex();
                out.vertex(pose.last().pose(),x1,y+.009f,z1).color(.50f,1f,.92f,.8f*fade).endVertex();
                out.vertex(pose.last().pose(),x0,y+.009f,z0).color(.50f,1f,.92f,.8f*fade).endVertex();
            }
        }
        float boundary=.60f*progress;
        mesh(1).draw(pose,buffers.getBuffer(RenderType.eyes(SCEPTER_MATERIAL)),15728880,
            (group,p)->Math.abs(Math.abs(p.y())-boundary)<.035f?p:null,
            (group,p)->new int[]{70,255,190,(int)(fade*255)});
    }

    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!(stack.getItem() instanceof ConjuredWeapon w))return;
        if(w.kind==1){renderScepter(stack,context,pose,buffers,light);return;}
        Fit fit=FITS[Math.max(0,Math.min(FITS.length-1,w.kind))];
        boolean held=context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            ||context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean reverse=held&&ConjuredWeapon.reversed(stack);
        float reach=mesh(w.kind).bottom()*fit.scale;
        float grip=reverse?fit.grip+reach*DIAGONAL:fit.grip;

        float growth=1;
        boolean still=context==ItemDisplayContext.GUI||context==ItemDisplayContext.GROUND||context==ItemDisplayContext.FIXED;
        if(stack.hasTag()&&stack.getTag().contains("formed")&&!still)
            growth=Math.max(.05f,Math.min(1,(ClientState.now()+Minecraft.getInstance().getFrameTime()-stack.getTag().getLong("formed"))/12f));

        pose.pushPose();
        if(w.kind==1&&(context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND))
            pose.translate(0,.12,0);
        pose.translate(grip,grip,.5f);
        pose.mulPose(Axis.ZP.rotationDegrees(reverse?135:w.kind==1?-18:-45));
        if(w.kind==1&&held) {
            float aim=FrostClient.aim(stack);
            if(aim>0) {
                // Bring the blade's +Y axis into the outstretched hand, then follow the user's
                // vertical look. The hit cone samples that same look on the discharge tick.
                pose.mulPose(Axis.ZP.rotationDegrees(45*aim));
                pose.mulPose(Axis.XP.rotationDegrees(-FrostClient.pitch(stack)*aim));
            }
        }
        if(fit.scale!=1)pose.scale(fit.scale,fit.scale,fit.scale);
        draw(w.kind,pose,buffers,light,growth);
        pose.popPose();
    }
    /** Dedicated staff transform: mesh origin is the middle of the hand grip. */
    private static void renderScepter(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light) {
        boolean first=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean third=context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        float growth=1;
        if((first||third)&&stack.hasTag()&&stack.getTag().contains("formed"))
            growth=Math.max(.05f,Math.min(1,(ClientState.now()+Minecraft.getInstance().getFrameTime()-stack.getTag().getLong("formed"))/24f));
        pose.pushPose();
        // ItemRenderer subtracts (.5,.5,.5) after the JSON display transform.
        // Cancel that offset, placing the actual cylindrical grip at the hand pivot.
        pose.translate(.5,.5,.5);
        if(first) {
            float aim=FrostClient.aim(stack);
            pose.mulPose(Axis.ZP.rotationDegrees(12*aim));
            pose.mulPose(Axis.XP.rotationDegrees(-110+20*aim));
            pose.mulPose(Axis.YP.rotationDegrees(-15*(1-aim)));
        } else if(third) {
            float aim=FrostClient.aim(stack);
            // Vanilla rotates held items -90 degrees around X before the JSON transform.
            // Rest: net -110 degrees (blade tip down, tail behind and up). Fire: compensate the
            // raised arm so the shaft points down the crosshair instead of into the floor.
            pose.mulPose(Axis.XP.rotationDegrees(-20+110*aim-FrostClient.pitch(stack)*aim));
            // Keep the same physical blade facing for either hand.
            pose.mulPose(Axis.YP.rotationDegrees(90));
        } else if(context==ItemDisplayContext.GUI||context==ItemDisplayContext.FIXED) {
            pose.scale(.32f,.32f,.32f);
            pose.translate(-.07,-.23,0);
        } else if(context==ItemDisplayContext.GROUND) {
            pose.translate(0,1.16,0);
        }
        // Scale around the grip: a full-length staff, with the hand remaining on the shaft.
        if(first)pose.scale(1.00f,1.00f,1.00f);
        else if(third)pose.scale(2.30f,2.30f,2.30f);
        draw(1,pose,buffers,light,growth);
        pose.popPose();
    }

}
