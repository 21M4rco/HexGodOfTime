package com.loki.client;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.entity.*;
import com.loki.network.LokiNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class LokiClient {
    public static final KeyMapping MENU=key("mastery",GLFW.GLFW_KEY_K),SELECT=key("select",GLFW.GLFW_KEY_V),PRIMARY=key("primary",GLFW.GLFW_KEY_R),SECONDARY=key("secondary",GLFW.GLFW_KEY_G),TRANSFORM=key("transform",GLFW.GLFW_KEY_H),RELEASE=key("release",GLFW.GLFW_KEY_X);
    private static KeyMapping key(String name,int key){return new KeyMapping("key.loki."+name,InputConstants.Type.KEYSYM,key,"key.categories.loki");}
    @Mod.EventBusSubscriber(modid=Loki.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e){for(KeyMapping k:new KeyMapping[]{MENU,SELECT,PRIMARY,SECONDARY,TRANSFORM,RELEASE})e.register(k);}
        @SubscribeEvent public static void entities(EntityRenderersEvent.RegisterRenderers e){e.registerEntityRenderer(Loki.ILLUSION.get(),IllusionRenderer::new);e.registerEntityRenderer(Loki.PROJECTILE.get(),SpellRenderer::new);}
        @SubscribeEvent public static void layers(EntityRenderersEvent.AddLayers e){for(String skin:e.getSkins()){var renderer=e.getSkin(skin);if(renderer!=null)renderer.addLayer(new LokiLayer(renderer));}}
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent e){e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)r->{LokiLayer.clear();WeaponRenderer.clear();TemporalScreen.close();});}
    }
    @Mod.EventBusSubscriber(modid=Loki.ID,value=Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){if(e.phase!=TickEvent.Phase.END)return;ClientState.tick();Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.screen!=null)return;
            while(MENU.consumeClick())mc.setScreen(new MasteryScreen(false));
            while(SELECT.consumeClick())mc.setScreen(new MasteryScreen(true));
            while(PRIMARY.consumeClick())LokiNetwork.send(0,0);
            while(SECONDARY.consumeClick())LokiNetwork.send(1,0);
            while(TRANSFORM.consumeClick())LokiNetwork.send(3,0);
            while(RELEASE.consumeClick())LokiNetwork.send(2,0);
        }
        @SubscribeEvent public static void mouse(InputEvent.InteractionKeyMappingTriggered e){var mc=Minecraft.getInstance();if(mc.player!=null&&mc.screen==null&&mc.player.getMainHandItem().getItem() instanceof ConjuredWeapon&&(e.isAttack()||e.isUseItem())){e.setCanceled(true);e.setSwingHand(false);if(!ClientState.frozen(mc.player.getId()))LokiNetwork.send(4,e.isUseItem()?1:0);}}
        @SubscribeEvent public static void movement(MovementInputUpdateEvent e){if(ClientState.frozen(e.getEntity().getId())){e.getInput().forwardImpulse=0;e.getInput().leftImpulse=0;e.getInput().jumping=false;}}
        @SubscribeEvent public static void hud(RenderGuiOverlayEvent.Post e){if(e.getOverlay().id().equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.HOTBAR.id()))LokiHud.render(e.getGuiGraphics());}
        @SubscribeEvent public static void world(RenderLevelStageEvent e){if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES)WorldEffects.render(e);if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL)TemporalScreen.render(e.getPartialTick());}
        @SubscribeEvent public static void player(RenderPlayerEvent.Pre e){DisguiseRenderer.render(e);if(!e.isCanceled())WorldEffects.beforePlayer(e);}
        @SubscribeEvent public static void playerEnd(RenderPlayerEvent.Post e){WorldEffects.afterPlayer(e);}
    }
}
