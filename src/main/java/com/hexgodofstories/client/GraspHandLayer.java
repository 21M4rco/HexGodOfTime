package com.hexgodofstories.client;

import com.hexgodofstories.server.GravityGrasp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Where Gravity Grasp's hand really is: the left arm as this frame draws it, moves and all (grasp_point, grasp_pull,
 * walking, the look), read off the model the way a held item is placed. Nothing is drawn here; GraspLens fixes the
 * cone of bent air on the palm it records, in first person as in third.
 */
public final class GraspHandLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
    public GraspHandLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent) {super(parent);}

    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,
                                 float walk,float amount,float partial,float age,float yaw,float pitch) {
        if(p.isSpectator()||ClientState.data(p.getId()).getLong(GravityGrasp.HOLDING)<=0)return;
        pose.pushPose();
        getParentModel().leftArm.translateAndRotate(pose);
        Matrix4f m=pose.last().pose();
        // The arm hangs down its own +y from the shoulder, twelve pixels long; the palm is just past its end, across the
        // middle of the arm (narrower on a slim model).
        float x=("slim".equals(p.getModelName())?.5f:1f)/16f;
        Vector4f palm=m.transform(new Vector4f(x,11f/16f,0,1));
        pose.popPose();
        GraspLens.hand(p,palm);
    }
}
