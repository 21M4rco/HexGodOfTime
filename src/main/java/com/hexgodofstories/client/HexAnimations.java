package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.logging.LogUtils;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess.PlayerAssociatedAnimationData;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

public final class HexAnimations {
   private static final ResourceLocation LAYER = HexGodOfStories.id("casting");
   /** Under the moves: the stances a weapon is carried in (BladeClient), which a move plays over and returns to. */
   private static final ResourceLocation STANCE = HexGodOfStories.id("stance");
   private static final Set<String> OWN_FIRST_PERSON_ARM = Set.of("branch_punch", "time_stop");
   private static final Logger LOGGER = LogUtils.getLogger();

   private HexAnimations() {
   }

   private static ModifierLayer<IAnimation> layerFor(AbstractClientPlayer player) {
      PlayerAssociatedAnimationData playerassociatedanimationdata = PlayerAnimationAccess.getPlayerAssociatedData(player);
      Object object = playerassociatedanimationdata.get(LAYER);
      if (object instanceof ModifierLayer) {
         return (ModifierLayer<IAnimation>)object;
      } else {
         ModifierLayer<IAnimation> modifierlayer = new ModifierLayer();
         PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(900, modifierlayer);
         playerassociatedanimationdata.set(LAYER, modifierlayer);
         LOGGER.info("Created fallback animation layer for {}", player.getGameProfile().getName());
         return modifierlayer;
      }
   }

   /** A player's stance, or none: faded between over a few ticks, so a guard settles in and a charge eases out. */
   public static void stance(AbstractClientPlayer player, String name) {
      PlayerAssociatedAnimationData data = PlayerAnimationAccess.getPlayerAssociatedData(player);
      ModifierLayer<IAnimation> layer;
      if (data.get(STANCE) instanceof ModifierLayer<?> existing) {
         layer = (ModifierLayer<IAnimation>) existing;
      } else {
         layer = new ModifierLayer<>();
         PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(800, layer);
         data.set(STANCE, layer);
      }
      if (name == null) {layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(5, Ease.INOUTQUAD), null); return;}
      KeyframeAnimation animation = PlayerAnimationRegistry.getAnimation(HexGodOfStories.id(name));
      if (animation == null) {LOGGER.warn("Stance '{}' is not loaded.", name); return;}
      // The guard is seen in first person too (the arm and the long blade low at the side); the charge and the rest are
      // not, so the sword stays where a held item is while running, or standing about.
      boolean seen = !name.equals("blade_run") && !name.equals("blade_sword_idle");
      KeyframeAnimationPlayer player1 = new KeyframeAnimationPlayer(animation)
         .setFirstPersonMode(seen ? FirstPersonMode.THIRD_PERSON_MODEL : FirstPersonMode.NONE)
         .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowRightArm(seen).setShowLeftArm(false).setShowRightItem(true).setShowLeftItem(true));
      layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(5, Ease.INOUTQUAD), player1);
   }

   /** Whether a move (anything on the casting layer) is playing on this player right now. */
   public static boolean busy(AbstractClientPlayer player) {
      return PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER) instanceof ModifierLayer<?> layer && layer.isActive();
   }

   public static void playRemote(int entityId, String name, int fadeTicks) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.level.getEntity(entityId) instanceof AbstractClientPlayer abstractclientplayer) {
         ModifierLayer modifierlayer = layerFor(abstractclientplayer);
         if ("__clear__".equals(name)) {
            modifierlayer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(Math.max(0, fadeTicks), Ease.INOUTQUAD), null);
         } else {
            boolean left=abstractclientplayer.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT;
            if(left&&name.startsWith("scepter_"))name+="_left";
            boolean manifest=name.startsWith("scepter_manifest")||name.startsWith("scepter_fire");
            // Gotcha!'s pointing arm, and the arm held out toward whatever telekinesis holds: seen in first person too.
            boolean pointing=name.equals("gotcha")||name.equals("telekinesis");
            // Gravity Grasp's free hand, pointed out along the look and hauled back: the left arm, seen in first person.
            boolean grasp=name.equals("grasp_point")||name.equals("grasp_pull");
            // The blades' moves (tools/blade_moves.py): the sword arm is seen in first person, blade and all. Both
            // blades are one-handed; the free arm works for balance and stays out of the view.
            boolean blade=name.startsWith("blade_");
            // The charge's slam takes the sword in both hands, and Gravity Grasp's stab holds the body by the shoulder
            // with the free one: both arms come into view.
            boolean twoHanded=name.equals("blade_sword_dash")||name.equals("blade_grasp_stab")||name.equals("blade_sword_ignite");
            ResourceLocation resourcelocation = HexGodOfStories.id(name);
            KeyframeAnimation keyframeanimation = PlayerAnimationRegistry.getAnimation(resourcelocation);
            if (keyframeanimation == null) {
               LOGGER.warn("Animation '{}' was requested but not loaded. Expected it under assets/{}/player_animation/.", resourcelocation, "hexgodofstories");
            } else {
               boolean flag = OWN_FIRST_PERSON_ARM.contains(name)||name.startsWith("scepter_");
               KeyframeAnimationPlayer keyframeanimationplayer = new KeyframeAnimationPlayer(keyframeanimation)
                  .setFirstPersonMode(flag ? FirstPersonMode.NONE : FirstPersonMode.THIRD_PERSON_MODEL)
                  .setFirstPersonConfiguration(
                     new FirstPersonConfiguration().setShowRightArm(manifest&&!left||pointing||blade).setShowLeftArm(manifest&&left||twoHanded||grasp).setShowRightItem(true).setShowLeftItem(true)
                  );
               // Shots cut straight in so the kick lands on the tick it fires; raising to aim eases in.
               // A combo's moves follow each other within a few ticks: each cuts in almost at once, or the wind-up is lost in the blend.
               int fade=name.startsWith("scepter_fire")||name.startsWith("scepter_shot")?0:name.startsWith("scepter_aim")||blade?(blade?1:2):Math.max(0,fadeTicks);
               // The burning Deceiver's fire: the sword arm follows the look up and down, so the blade (and the jet out of
               // its point) goes where the bearer looks.
               IAnimation played=keyframeanimationplayer;
               // So do Complete Evisceration's dash and thrust: the point goes in wherever the bearer looks, a pig's gut or a
               // giant's.
               if(name.equals("blade_sword_flame")||name.equals("blade_sword_evis_dash")||name.equals("blade_sword_evis_thrust")) {
                  // Linked through addModifierLast and setAnimation: ModifierLayer's constructor that takes modifiers
                  // only lists them, never hands them the animation, and a modifier with nothing in it plays nothing.
                  ModifierLayer<IAnimation> follow=new ModifierLayer<>();
                  follow.addModifierLast(new FollowLook(abstractclientplayer,"rightArm"));
                  follow.setAnimation(keyframeanimationplayer);
                  played=follow;
               }
               // Gravity Grasp's free hand points where the caster looks, up and down, and is hauled back from there.
               if(grasp) {
                  ModifierLayer<IAnimation> follow=new ModifierLayer<>();
                  follow.addModifierLast(new FollowLook(abstractclientplayer,"leftArm"));
                  follow.setAnimation(keyframeanimationplayer);
                  played=follow;
               }
               modifierlayer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fade, Ease.INOUTQUAD), played);
            }
         }
      }
   }

   /** Turns an arm by the player's look pitch: an arm keyed straight out points where they look. */
   private static final class FollowLook extends dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier {
      FollowLook(AbstractClientPlayer player,String arm) {
         super(part -> part.equals(arm)
            ? java.util.Optional.of(new PartModifier(new dev.kosmx.playerAnim.core.util.Vec3f((float)Math.toRadians(player.getXRot()),0,0),dev.kosmx.playerAnim.core.util.Vec3f.ZERO))
            : java.util.Optional.empty());
      }
      // Whole from the first frame (its own fade-in divides by the animation's begin tick, which is 0).
      @Override protected float getFadeIn(float delta) {return 1;}
   }

   @EventBusSubscriber(
      modid = "hexgodofstories",
      value = {Dist.CLIENT},
      bus = Bus.MOD
   )
   public static final class Init {
      @SubscribeEvent
      public static void clientSetup(FMLClientSetupEvent event) {
         event.enqueueWork(() -> {
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(HexAnimations.LAYER, 900, player -> new ModifierLayer());
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(HexAnimations.STANCE, 800, player -> new ModifierLayer());
         });
      }
   }
}
