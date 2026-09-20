package com.loki.client;

import com.loki.entity.ConjuredWeapon;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
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
    private static final Fit[] FITS={new Fit(.24f,1f),new Fit(.23f,1f),new Fit(.27f,1f)};
    private static final float DIAGONAL=(float)(1/Math.sqrt(2));
    private static WeaponRenderer INSTANCE;
    private static final Map<Integer,AuthoredMesh> MODELS=new HashMap<>();

    public WeaponRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public static WeaponRenderer instance(){if(INSTANCE==null)INSTANCE=new WeaponRenderer();return INSTANCE;}
    public static void clear(){MODELS.clear();}
    public static AuthoredMesh mesh(int kind) {return MODELS.computeIfAbsent(kind,k->new AuthoredMesh(k==1?"laevateinn":k==2?"time_stick":"dagger"));}

    /** Draws in weapon space: grip at the origin, blade toward +Y. Used by the hand, the projectile and the decoys. */
    public static void draw(int kind,PoseStack pose,MultiBufferSource buffers,int light,float growth) {
        mesh(kind).drawManifesting(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(LokiLayer.MATERIAL)),light,growth,0);
    }

    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!(stack.getItem() instanceof ConjuredWeapon w))return;
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
}
