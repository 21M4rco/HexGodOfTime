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
   private static final Set<String> OWN_FIRST_PERSON_ARM = Set.of("branch_punch");
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

   public static void playRemote(int entityId, String name, int fadeTicks) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.level.getEntity(entityId) instanceof AbstractClientPlayer abstractclientplayer) {
         ModifierLayer modifierlayer = layerFor(abstractclientplayer);
         if ("__clear__".equals(name)) {
            modifierlayer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(Math.max(0, fadeTicks), Ease.INOUTQUAD), null);
         } else {
            ResourceLocation resourcelocation = HexGodOfStories.id(name);
            KeyframeAnimation keyframeanimation = PlayerAnimationRegistry.getAnimation(resourcelocation);
            if (keyframeanimation == null) {
               LOGGER.warn("Animation '{}' was requested but not loaded. Expected it under assets/{}/player_animation/.", resourcelocation, "hexgodofstories");
            } else {
               boolean flag = OWN_FIRST_PERSON_ARM.contains(name);
               KeyframeAnimationPlayer keyframeanimationplayer = new KeyframeAnimationPlayer(keyframeanimation)
                  .setFirstPersonMode(flag ? FirstPersonMode.NONE : FirstPersonMode.THIRD_PERSON_MODEL)
                  .setFirstPersonConfiguration(
                     new FirstPersonConfiguration().setShowRightArm(false).setShowLeftArm(false).setShowRightItem(true).setShowLeftItem(true)
                  );
               modifierlayer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(Math.max(0, fadeTicks), Ease.INOUTQUAD), keyframeanimationplayer);
            }
         }
      }
   }

   @EventBusSubscriber(
      modid = "hexgodofstories",
      value = {Dist.CLIENT},
      bus = Bus.MOD
   )
   public static final class Init {
      @SubscribeEvent
      public static void clientSetup(FMLClientSetupEvent event) {
         event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(HexAnimations.LAYER, 900, player -> new ModifierLayer()));
      }
   }
}

