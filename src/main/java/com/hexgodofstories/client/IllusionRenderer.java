package com.hexgodofstories.client;

import com.hexgodofstories.entity.IllusionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * A projection wears its caster's face. It borrows the real player model, skin, armour, worn head
 * gear and equipment, and its cloak is solved by {@link CapeRenderer} exactly like a real keeper's —
 * which is the point: nothing on screen should tell an onlooker which one is breathing.
 *
 * <p>It carries a name tag too, drawn under the same distance and crouch rules a player's own tag
 * follows. A nameless figure standing beside a named one is the single easiest tell there is, and
 * removing it costs nothing else.
 */
public final class IllusionRenderer extends MobRenderer<IllusionEntity,PlayerModel<IllusionEntity>> {
    private final PlayerModel<IllusionEntity> classic,slim;

    public IllusionRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,new ThrowModel(ctx.bakeLayer(ModelLayers.PLAYER),false),.45f);
        classic=model;
        slim=new ThrowModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM),true);
        addLayer(new HumanoidArmorLayer<>(this,
            new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new net.minecraft.client.model.HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
        addLayer(new ItemInHandLayer<>(this,ctx.getItemInHandRenderer()));
        addLayer(new CustomHeadLayer<>(this,ctx.getModelSet(),ctx.getItemInHandRenderer()));
        addLayer(new ElytraLayer<>(this,ctx.getModelSet()));
        addLayer(new RenderLayer<IllusionEntity,PlayerModel<IllusionEntity>>(this) {
            @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,IllusionEntity e,
                                         float a,float b,float partial,float age,float yaw,float pitch) {
                if(!e.finalForm()||e.isInvisible())return;
                pose.pushPose();
                getParentModel().head.translateAndRotate(pose);
                HexLayer.crown(pose,buffers,light,1);
                pose.popPose();
                pose.pushPose();
                getParentModel().body.translateAndRotate(pose);
                CapeRenderer.capture(e,pose);
                HexLayer.collar(pose,buffers,light,1);
                pose.popPose();
            }
        });
    }

    /** Pose the actual arm and sleeve so the held item follows the wind-up and release. */
    private static final class ThrowModel extends PlayerModel<IllusionEntity> {
        ThrowModel(net.minecraft.client.model.geom.ModelPart root,boolean slim){super(root,slim);}
        private void laugh(IllusionEntity e,float t){Laugh.pose(this,t,IllusionEntity.burstAt(e.grand()));}
        @Override public void setupAnim(IllusionEntity e,float walk,float amount,float age,float yaw,float pitch) {
            super.setupAnim(e,walk,amount,age,yaw,pitch);
            float laugh=e.laughAge(age-e.tickCount);
            if(laugh>=0){laugh(e,laugh);return;}
            float t=e.throwAge(age-e.tickCount);
            if(t<0||t>IllusionEntity.THROW_END)return;
            float rotation=t<5?net.minecraft.util.Mth.lerp(t/5,-.35f,-3.25f)
                :t<IllusionEntity.THROW_RELEASE?net.minecraft.util.Mth.lerp((t-5)/3,-3.25f,-(float)Math.PI/2)
                :net.minecraft.util.Mth.lerp((t-IllusionEntity.THROW_RELEASE)/8,-(float)Math.PI/2,-.35f);
            rightArm.xRot=rotation;rightArm.yRot=0;rightArm.zRot=0;
            rightSleeve.copyFrom(rightArm);
        }
    }

    /**
     * Anchor Being's copy, struck: it looks down, a little, and holds there while whatever is in it builds; a tremor
     * takes it, harder and harder as the fuse burns down, and then it is gone.
     */
    private static final class Laugh {
        static void pose(PlayerModel<IllusionEntity> m,float t,int fuse) {
            float down=net.minecraft.util.Mth.clamp(t/6f,0,1),eased=down*down*(3-2*down);
            float build=net.minecraft.util.Mth.clamp(t/fuse,0,1),shake=.07f*build*build;
            m.head.xRot=net.minecraft.util.Mth.lerp(eased,m.head.xRot,.6f)+shake*net.minecraft.util.Mth.sin(t*7.3f);
            m.head.yRot*=1-.7f*eased;
            m.head.zRot=shake*net.minecraft.util.Mth.sin(t*9.1f+1);
            m.hat.copyFrom(m.head);
            // Hands drawn open and a little out from the sides, shaking with it.
            m.rightArm.zRot=.18f*build+shake*net.minecraft.util.Mth.sin(t*8.3f);
            m.leftArm.zRot=-.18f*build-shake*net.minecraft.util.Mth.sin(t*8.9f+2);
            m.rightSleeve.copyFrom(m.rightArm);m.leftSleeve.copyFrom(m.leftArm);
            m.body.zRot=shake*.5f*net.minecraft.util.Mth.sin(t*6.7f);
            m.jacket.copyFrom(m.body);
        }
    }

    @Override public ResourceLocation getTextureLocation(IllusionEntity e) {
        var mc=Minecraft.getInstance();
        if(e.owner()!=null&&mc.level!=null&&mc.level.getPlayerByUUID(e.owner()) instanceof AbstractClientPlayer p)return p.getSkinTextureLocation();
        var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;
        return info!=null?info.getSkinLocation():DefaultPlayerSkin.getDefaultSkin();
    }
    @Override public void render(IllusionEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        var mc=Minecraft.getInstance();
        var info=e.owner()!=null&&mc.getConnection()!=null?mc.getConnection().getPlayerInfo(e.owner()):null;
        model=info!=null&&"slim".equals(info.getModelName())?slim:classic;
        super.render(e,yaw,partial,pose,buffers,light);
    }
    /** Mirrors the rules a player's own tag follows, so the group's labels match at every distance. */
    @Override protected boolean shouldShowName(IllusionEntity e) {
        if(!Minecraft.renderNames()||e.isInvisible()||e.getCustomName()==null)return false;
        float limit=e.isDiscrete()?32:64;
        return entityRenderDispatcher.distanceToSqr(e)<limit*limit;
    }
}
