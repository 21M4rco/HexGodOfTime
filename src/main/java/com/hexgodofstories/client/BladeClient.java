package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.server.BladeCombo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A blade formed only for a combo starter (BladeCombo): it is never an item anyone holds, only one everybody sees in
 * the caster's hand for as long as the combo lasts. Third person, and first person while a combo move is playing (the
 * moves draw the body's own arms there), it takes the main hand's place in the held-item layer (BladeHeldLayerMixin).
 * First person between moves, it is drawn here in the hand's place, the way vanilla holds any item.
 */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class BladeClient {
    private BladeClient() {}

    /** The blade in this player's hand that is not really there, or empty when there is none to draw. */
    public static ItemStack phantom(AbstractClientPlayer p) {
        CompoundTag d=ClientState.data(p.getId());
        if(d.getLong(BladeCombo.HELD)<=ClientState.now())return ItemStack.EMPTY;
        Item item=d.getInt(BladeCombo.KIND)==3?HexGodOfStories.DECEIVER.get():HexGodOfStories.DAGGER.get();
        if(p.getMainHandItem().is(item))return ItemStack.EMPTY;
        ItemStack stack=new ItemStack(item);
        stack.getOrCreateTag().putUUID("conjurer",p.getUUID());
        // It forms in the hand over the draw, as a conjured blade does.
        stack.getOrCreateTag().putLong("formed",d.getLong(BladeCombo.FORMED));
        return stack;
    }

    /**
     * A bleeding player cannot jump (Bleed). The jump is made on their own client, so it is here it is undone: no rise,
     * and no sprint-jump's push either.
     */
    @SubscribeEvent public static void bleedJump(net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent e) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||e.getEntity()!=mc.player||!WorldEffects.grounded(mc.player.getId()))return;
        var v=mc.player.getDeltaMovement();
        if(mc.player.isSprinting()) {
            float yaw=mc.player.getYRot()*Mth.DEG_TO_RAD;
            v=v.add(Mth.sin(yaw)*.2f,0,-Mth.cos(yaw)*.2f);
        }
        mc.player.setDeltaMovement(v.x,Math.min(0,v.y),v.z);
    }

    /** First person, between the moves: the formed blade held where vanilla would hold it, swing and all. */
    @SubscribeEvent public static void hand(RenderHandEvent e) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||e.getHand()!=InteractionHand.MAIN_HAND)return;
        ItemStack blade=phantom(mc.player);
        if(blade.isEmpty())return;
        e.setCanceled(true);
        HumanoidArm arm=mc.player.getMainArm();
        int side=arm==HumanoidArm.RIGHT?1:-1;
        float swing=e.getSwingProgress(),equip=e.getEquipProgress();
        PoseStack pose=e.getPoseStack();
        pose.pushPose();
        // Vanilla's own hold (ItemInHandRenderer#renderArmWithItem, its ordinary-item case).
        float root=Mth.sqrt(swing);
        pose.translate(side*-.4f*Mth.sin(root*Mth.PI),.2f*Mth.sin(root*Mth.PI*2),-.2f*Mth.sin(swing*Mth.PI));
        pose.translate(side*.56f,-.52f+equip*-.6f,-.72f);
        float f=Mth.sin(swing*swing*Mth.PI);
        pose.mulPose(Axis.YP.rotationDegrees(side*(45+f*-20)));
        float f1=Mth.sin(root*Mth.PI);
        pose.mulPose(Axis.ZP.rotationDegrees(side*f1*-20));
        pose.mulPose(Axis.XP.rotationDegrees(f1*-80));
        pose.mulPose(Axis.YP.rotationDegrees(side*-45));
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(mc.player,blade,
            arm==HumanoidArm.RIGHT?ItemDisplayContext.FIRST_PERSON_RIGHT_HAND:ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
            arm!=HumanoidArm.RIGHT,pose,e.getMultiBufferSource(),e.getPackedLight());
        pose.popPose();
    }
}
