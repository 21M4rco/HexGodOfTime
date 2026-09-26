package com.hexgodofstories.client;

import com.hexgodofstories.entity.ConjuredWeapon;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.*;
import org.joml.Quaternionf;
import java.util.*;

/**
 * Conjured weapons are authored with the grip at the origin and the blade running up +Y. Minecraft's item
 * renderer hands a custom renderer the corner of the item's unit cube, which is the same space a vanilla
 * sprite lives in, so the only thing needed for correct hand alignment is to put the grip where a vanilla
 * hilt sits — on the lower-left of that cube — and lay the blade along the up-right diagonal the inherited
 * {@code item/handheld} transforms are built around. Off-hand twins get the same treatment rotated a half
 * turn, which is what makes the reverse grip read as deliberate rather than as a flipped model.
 *
 * <p>The Scepter is the exception: a solid, separately shaded model ({@link ScepterModel}) with its own
 * poses. In first person it rests as it does in third: carried low, pointing forward with the tip toward
 * the ground and the blade curving up, and raises into a forward point — blade arched over it — to fire.
 */
public final class WeaponRenderer extends BlockEntityWithoutLevelRenderer {
    /** Grip position inside the item cube and an overall size trim, one entry per weapon kind. */
    private record Fit(float grip,float scale) {}
    private static final Fit[] FITS={new Fit(.24f,1f),new Fit(.29f,.72f),new Fit(.27f,1f)};
    private static final float DIAGONAL=(float)(1/Math.sqrt(2));
    /** Full-length staff in the hand; the grip stays on the shaft where the hand is. */
    private static final float STAFF=2.30f;
    private static WeaponRenderer INSTANCE;
    private static final Map<Integer,AuthoredMesh> MODELS=new HashMap<>();

    // First-person rest and aim, as rotations applied X then Y then Z to the staff; tuned in
    // tools/preview_scepter.py against the game's hand transform and 70 degree hand field of view.
    private static final Quaternionf REST=euler(180,80,73),AIM=euler(-82,-50,6);
    private static final Quaternionf REST_LEFT=euler(180,-80,-73),AIM_LEFT=euler(-82,50,-6);
    /** Where the grip moves to when aiming (hand space) and how far the hand slides up the shaft. */
    private static final float AIM_X=-.34f,AIM_Y=.38f,AIM_Z=-.05f,AIM_SLIDE=.20f;
    /** Grip at rest (hand space): raised so the stone, off the shaft's axis, sits where it did with the blade down. */
    private static final float REST_X=-.014f,REST_Y=.255f,REST_Z=-.077f;

    private static Quaternionf euler(float x,float y,float z) {
        return new Quaternionf().rotateX((float)Math.toRadians(x)).rotateY((float)Math.toRadians(y)).rotateZ((float)Math.toRadians(z));
    }

    public WeaponRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public static WeaponRenderer instance(){if(INSTANCE==null)INSTANCE=new WeaponRenderer();return INSTANCE;}
    public static void clear(){MODELS.clear();ScepterModel.clear();}
    public static AuthoredMesh mesh(int kind) {return MODELS.computeIfAbsent(kind,k->new AuthoredMesh(k==1?"laevateinn":k==2?"time_stick":"dagger"));}

    /** Draws in weapon space: grip at the origin, blade toward +Y. Used by the hand, the projectile and the decoys. */
    public static void draw(int kind,PoseStack pose,MultiBufferSource buffers,int light,float growth) {
        if(kind==1) {
            float time=ClientState.now()+Minecraft.getInstance().getFrameTime();
            ScepterModel.get().render(pose.last(),buffers,light,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                false,growth,1,time,false);
            return;
        }
        mesh(kind).drawManifesting(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(HexLayer.MATERIAL)),light,growth,0);
    }

    /** Hand-local magic rings follow both reveal fronts; no world-space drift while moving. */
    private static void manifestScepter(PoseStack pose,MultiBufferSource buffers,float progress) {
        var out=buffers.getBuffer(RenderType.lightning());
        float time=ClientState.now()+Minecraft.getInstance().getFrameTime();
        float fade=Math.min(1,(1-progress)*6);
        float extent=ScepterModel.get().extent();
        for(int side:new int[]{-1,1}) {
            float y=side*extent*progress;
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
        ScepterModel.get().renderFront(buffers,progress,fade);
    }

    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!(stack.getItem() instanceof ConjuredWeapon w))return;
        if(w.kind==1){renderScepter(stack,context,pose,buffers,light,overlay);return;}
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
        pose.translate(grip,grip,.5f);
        pose.mulPose(Axis.ZP.rotationDegrees(reverse?135:-45));
        if(fit.scale!=1)pose.scale(fit.scale,fit.scale,fit.scale);
        draw(w.kind,pose,buffers,light,growth);
        pose.popPose();
    }

    /** Dedicated staff transform: the model origin is the middle of the hand grip. */
    private static void renderScepter(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        boolean first=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean third=context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        boolean left=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        float partial=Minecraft.getInstance().getFrameTime();
        float time=ClientState.now()+partial;
        float reveal=1;
        if((first||third)&&stack.hasTag()&&stack.getTag().contains("formed"))
            reveal=Math.max(.05f,Math.min(1,(time-stack.getTag().getLong("formed"))/24f));
        float aim=ScepterClient.aim(stack),recoil=ScepterClient.recoil(stack),charge=ScepterClient.charge(stack);
        float power=ScepterClient.power(stack);
        pose.pushPose();
        // ItemRenderer subtracts (.5,.5,.5) after the JSON display transform; cancel it so the grip
        // sits exactly on the hand pivot.
        pose.translate(.5,.5,.5);
        if(first) {
            float side=left?-1:1;
            // A slow breath at rest, and a live tremor in the hand while the stone is filling.
            float breath=(1-aim)*(float)Math.sin(time*.07f);
            float tremor=charge*charge*.0035f;
            float rest=1-aim;
            pose.translate(side*(REST_X*rest+AIM_X*aim)+(float)Math.sin(time*2.9f)*tremor,
                REST_Y*rest+AIM_Y*aim+.006f*breath+(float)Math.cos(time*3.7f)*tremor,
                REST_Z*rest+AIM_Z*aim+.10f*recoil);
            Quaternionf rest=left?REST_LEFT:REST,point=left?AIM_LEFT:AIM;
            pose.mulPose(new Quaternionf(rest).slerp(point,aim));
            // Recoil pitches the head up and drives the staff back along its own length.
            pose.mulPose(Axis.XP.rotationDegrees(9*recoil));
            pose.scale(STAFF,STAFF,STAFF);
            pose.translate(0,-AIM_SLIDE*aim-.045f*recoil,0);
        } else if(third) {
            // Always the low carry, tip toward the ground; it never follows the caster's look up or
            // down. A shot only kicks the head up for a moment.
            pose.mulPose(Axis.XP.rotationDegrees(-20+6*recoil));
            pose.mulPose(Axis.YP.rotationDegrees(90));
            pose.scale(STAFF,STAFF,STAFF);
        } else if(context==ItemDisplayContext.GUI||context==ItemDisplayContext.FIXED) {
            pose.translate(-.07,-.10,0);
            pose.mulPose(Axis.ZP.rotationDegrees(-28));
            pose.mulPose(Axis.YP.rotationDegrees(-22));
            pose.scale(.62f,.62f,.62f);
        } else if(context==ItemDisplayContext.GROUND) {
            pose.translate(0,.45,0);
            pose.scale(.8f,.8f,.8f);
        }
        ScepterModel.get().render(pose.last(),buffers,light,overlay,context==ItemDisplayContext.GUI,reveal,power,time,first);
        if(reveal<1)manifestScepter(pose,buffers,reveal);
        pose.popPose();
    }
}
