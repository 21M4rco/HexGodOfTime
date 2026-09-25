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
   private static final Set<String> OWN_FIRST_PERSON_ARM = Set.of("branch_punch", "time_stop");
   private static final ResourceLocation SCEPTER_VIEW = HexGodOfStories.id("scepter_view");
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
            boolean left=abstractclientplayer.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT;
            if(left&&(name.equals("scepter_fire")||name.equals("scepter_manifest")))name+="_left";
            boolean manifest=name.startsWith("scepter_manifest")||name.startsWith("scepter_fire");
            ResourceLocation resourcelocation = HexGodOfStories.id(name);
            KeyframeAnimation keyframeanimation = PlayerAnimationRegistry.getAnimation(resourcelocation);
            if (keyframeanimation == null) {
               LOGGER.warn("Animation '{}' was requested but not loaded. Expected it under assets/{}/player_animation/.", resourcelocation, "hexgodofstories");
            } else {
               boolean flag = OWN_FIRST_PERSON_ARM.contains(name);
               KeyframeAnimationPlayer keyframeanimationplayer = new KeyframeAnimationPlayer(keyframeanimation)
                  .setFirstPersonMode(flag ? FirstPersonMode.NONE : FirstPersonMode.THIRD_PERSON_MODEL)
                  .setFirstPersonConfiguration(
                     new FirstPersonConfiguration().setShowRightArm(manifest&&!left).setShowLeftArm(manifest&&left).setShowRightItem(true).setShowLeftItem(true)
                  );
               modifierlayer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(name.startsWith("scepter_fire")?0:Math.max(0, fadeTicks), Ease.INOUTQUAD), keyframeanimationplayer);
            }
         }
      }
   }

   private static final class ScepterViewLayer extends ModifierLayer<IAnimation> { boolean left; }

   /** Keep the real player hand/item render active in first person for the entire carry. */
   public static void tickScepterView() {
      var mc=Minecraft.getInstance();
      if(mc.level==null)return;
      for(AbstractClientPlayer player:mc.level.players()) {
         var data=PlayerAnimationAccess.getPlayerAssociatedData(player);
         var existing=data.get(SCEPTER_VIEW);
         ScepterViewLayer layer;
         if(existing instanceof ScepterViewLayer view)layer=view;
         else {
            layer=new ScepterViewLayer();
            PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(800,layer);
            data.set(SCEPTER_VIEW,layer);
         }
         boolean held=player.getMainHandItem().getItem() instanceof com.hexgodofstories.entity.ConjuredWeapon weapon&&weapon.kind==1;
         if(!held) { if(layer.isActive())layer.setAnimation(null); continue; }
         boolean left=player.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT;
         String key=left?"scepter_hold_left":"scepter_hold";
         // A changed main-hand preference needs the corresponding arm immediately.
         if(layer.isActive()&&layer.left==left)continue;
         var clip=PlayerAnimationRegistry.getAnimation(HexGodOfStories.id(key));
         if(clip==null)continue;
         layer.setAnimation(new KeyframeAnimationPlayer(clip)
            .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
            .setFirstPersonConfiguration(new FirstPersonConfiguration()
               .setShowRightArm(!left).setShowLeftArm(left).setShowRightItem(true).setShowLeftItem(true)));
         layer.left=left;
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
