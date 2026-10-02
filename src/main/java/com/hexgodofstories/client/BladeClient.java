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

    // ------------------------------------------------------------------ The Deceiver's guard and stances

    /** Whether this client is holding The Deceiver's guard (the use key, the sword in hand). */
    private static boolean guardDown;

    /** Every client tick: the use key with The Deceiver in hand raises the guard, and letting go drops it. */
    public static void input(boolean use) {
        var mc=Minecraft.getInstance();
        boolean want=use&&mc.player!=null&&mc.screen==null&&HexClient.enabled()&&mc.player.getMainHandItem().is(HexGodOfStories.DECEIVER.get());
        if(want==guardDown)return;
        guardDown=want;
        com.hexgodofstories.network.HexNetwork.send(want?com.hexgodofstories.server.HexServer.GUARD_BEGIN:com.hexgodofstories.server.HexServer.GUARD_END,0);
    }
    /** The world went away: nothing to tell anyone. */
    public static void forget() {guardDown=false;STANCES.clear();GUARD_SINCE.clear();STILL_SINCE.clear();}

    /** Whether this player stands in The Deceiver's guard: this client's own key, or what the server says of anyone else. */
    public static boolean guarding(AbstractClientPlayer p) {
        if(!p.getMainHandItem().is(HexGodOfStories.DECEIVER.get()))return false;
        return p==Minecraft.getInstance().player?guardDown:ClientState.data(p.getId()).getBoolean(com.hexgodofstories.server.SwordGuard.GUARD);
    }

    /** The stance each player is shown in, by id: the guard, walking in it, the charge, or the rest, under any move. */
    private static final java.util.Map<Integer,String> STANCES=new java.util.HashMap<>();
    /** When each player raised the guard: its first ticks are the wind-up into it (blade_guard_enter), not the stance. */
    private static final java.util.Map<Integer,Long> GUARD_SINCE=new java.util.HashMap<>();
    private static final int GUARD_ENTER=11;
    /** Since when each player has stood idle with The Deceiver in hand: after a while they fall into its rest. */
    private static final java.util.Map<Integer,Long> STILL_SINCE=new java.util.HashMap<>();
    private static final int IDLE_AFTER=50;

    /**
     * Every client tick, for everyone in sight holding The Deceiver: the guard (or its walk) while the use key is held,
     * the charge while sprinting, and the rest after standing idle a while. A layer under the moves (HexAnimations): a cut or a deflection plays over it and
     * the stance is there again when it ends.
     */
    @SubscribeEvent public static void stances(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
        if(e.phase!=net.minecraftforge.event.TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){STANCES.clear();return;}
        for(AbstractClientPlayer p:mc.level.players()) {
            String want=null;
            if(p.getMainHandItem().is(HexGodOfStories.DECEIVER.get())&&!p.isSpectator()&&!ClientState.hidden(p)) {
                double dx=p.getX()-p.xo,dz=p.getZ()-p.zo;
                boolean moving=dx*dx+dz*dz>4e-4;
                if(guarding(p)) {
                    long since=GUARD_SINCE.computeIfAbsent(p.getId(),id->ClientState.now());
                    want=ClientState.now()-since<GUARD_ENTER?"blade_guard_enter":moving?"blade_guard_walk":"blade_guard";
                }
                else if(p.isSprinting()&&moving)want="blade_run";
                // Standing about: not moving, not swinging, not in the air or crouched, no move playing. A little of
                // it and the sword comes to rest, point on the ground; any of them and it is taken up again.
                boolean idle=!moving&&want==null&&p.onGround()&&!p.isCrouching()&&!p.swinging&&!p.isUsingItem()&&!HexAnimations.busy(p)
                    &&ClientState.data(p.getId()).getLong(BladeCombo.HELD)<=ClientState.now();
                if(!idle)STILL_SINCE.put(p.getId(),ClientState.now());
                else if(ClientState.now()-STILL_SINCE.computeIfAbsent(p.getId(),id->ClientState.now())>=IDLE_AFTER)want="blade_sword_idle";
            }
            else STILL_SINCE.remove(p.getId());
            if(want==null||!want.startsWith("blade_guard"))GUARD_SINCE.remove(p.getId());
            String now=STANCES.get(p.getId());
            if(java.util.Objects.equals(want,now))continue;
            if(want==null)STANCES.remove(p.getId());else STANCES.put(p.getId(),want);
            HexAnimations.stance(p,want);
        }
        STANCES.keySet().removeIf(id->mc.level.getEntity(id)==null);
        GUARD_SINCE.keySet().removeIf(id->mc.level.getEntity(id)==null);
        STILL_SINCE.keySet().removeIf(id->mc.level.getEntity(id)==null);
    }

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
