package com.loki.client;

import com.loki.Loki;
import com.loki.data.Ability;
import com.loki.entity.ConjuredWeapon;
import com.loki.network.LokiNetwork;
import com.loki.server.LokiServer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * One mastery key, one quick bar, and contextual primary/secondary actions. Abilities that charge are
 * driven by the press and release of the same key rather than a second binding, so the scheme stays
 * small no matter how much progression adds.
 *
 * <p>The time controls are the exception, and deliberately so. Stopping, resuming, rewinding and
 * dilating are not spells to be scrolled to — they are commands, and each owns a permanent key that
 * works whatever else is selected. None of them appears in the quick bar at all. The four chosen keys
 * are unbound in vanilla, so the scheme adds no conflicts.
 */
public final class LokiClient {
    public static final KeyMapping MENU=key("mastery",GLFW.GLFW_KEY_K),SELECT=key("select",GLFW.GLFW_KEY_V),
        PRIMARY=key("primary",GLFW.GLFW_KEY_R),SECONDARY=key("secondary",GLFW.GLFW_KEY_G),
        TRANSFORM=key("transform",GLFW.GLFW_KEY_H),RELEASE=key("release",GLFW.GLFW_KEY_X),FLIGHT=key("flight",GLFW.GLFW_KEY_J),
        TIME_STOP=key("time_stop",GLFW.GLFW_KEY_Z),TIME_RESUME=key("time_resume",GLFW.GLFW_KEY_B),
        TIME_REWIND=key("time_rewind",GLFW.GLFW_KEY_N),TIME_DILATE=key("time_dilate",GLFW.GLFW_KEY_M);
    /** The permanent time commands, paired with the value {@link LokiServer#TIME} carries for each. */
    public static final KeyMapping[] TIME_KEYS={TIME_STOP,TIME_RESUME,TIME_REWIND,TIME_DILATE};
    private static KeyMapping key(String name,int key){return new KeyMapping("key.loki."+name,InputConstants.Type.KEYSYM,key,"key.categories.loki");}
    private static boolean primaryDown,selectDown,primaryWasHold,primaryLatched;
    private static int repeat;

    @Mod.EventBusSubscriber(modid=Loki.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent public static void dimensionEffects(RegisterDimensionSpecialEffectsEvent e){e.register(Loki.id("pocket"),new RealmSky());}
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) {
            for(KeyMapping k:new KeyMapping[]{MENU,SELECT,PRIMARY,SECONDARY,TRANSFORM,RELEASE,FLIGHT})e.register(k);
            for(KeyMapping k:TIME_KEYS)e.register(k);
        }
        @SubscribeEvent public static void entities(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(Loki.ILLUSION.get(),IllusionRenderer::new);
            e.registerEntityRenderer(Loki.PROJECTILE.get(),SpellRenderer::new);
            e.registerEntityRenderer(Loki.THROWN_DAGGER.get(),DaggerRenderer::new);
            e.registerEntityRenderer(Loki.RIFT.get(),RiftRenderer::new);
            e.registerEntityRenderer(Loki.STARFALL.get(),StarRenderer::new);
            e.registerEntityRenderer(Loki.THRONE_SEAT.get(),net.minecraft.client.renderer.entity.NoopRenderer::new);
        }
        @SubscribeEvent public static void layers(EntityRenderersEvent.AddLayers e) {
            for(String skin:e.getSkins()) {
                net.minecraft.client.renderer.entity.player.PlayerRenderer renderer=e.getSkin(skin);
                if(renderer!=null)renderer.addLayer(new LokiLayer(renderer));
            }
        }
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent e) {
            e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)r->{
                LokiLayer.clear();WeaponRenderer.clear();RiftRenderer.clear();RealmSky.clear();CosmicNebula.clear();
                DisguiseRenderer.clear();DisguiseRenderer.forgive();Blood.clear();TemporalScreen.close();
            });
        }
    }

    @Mod.EventBusSubscriber(modid=Loki.ID,value=Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
            if(e.phase!=TickEvent.Phase.END)return;
            ClientState.tick();
            Minecraft mc=Minecraft.getInstance();
            if(mc.player==null){primaryDown=false;selectDown=false;QuickBar.closeBar(false);return;}
            if(mc.screen!=null) {
                // Ending the hold here means the key is no longer "down" as far as this loop knows,
                // so a key that is still physically held would read as a brand new press the moment
                // the screen closes. Crossing a dimension puts the terrain screen up mid-hold, which
                // is exactly how arriving in the sanctum used to open a second break on arrival.
                if(primaryDown){primaryDown=false;primaryLatched=true;LokiNetwork.send(LokiServer.HOLD_END,0);}
                if(selectDown){selectDown=false;QuickBar.closeBar(false);}
                drain();return;
            }
            while(MENU.consumeClick())mc.setScreen(new MasteryScreen(false));

            boolean select=SELECT.isDown();
            if(select&&!selectDown)QuickBar.openBar();
            if(!select&&selectDown)QuickBar.closeBar(true);
            selectDown=select;

            Ability selected=Ability.at(ClientState.self().getInt("selected"));
            boolean primary=PRIMARY.isDown();
            // A cast that was interrupted needs a real release before it counts as pressed again.
            if(primaryLatched){if(primary)primary=false;else primaryLatched=false;}
            if(primary&&!primaryDown){primaryWasHold=selected.hold;LokiNetwork.send(selected.hold?LokiServer.HOLD_BEGIN:LokiServer.CAST,0);repeat=0;}
            // Holding an ordinary spell repeats it; the server's own rate limit and cooldown set the pace.
            else if(primary&&!selected.hold&&++repeat>=5){repeat=0;LokiNetwork.send(LokiServer.CAST,0);}
            if(!primary&&primaryDown&&primaryWasHold)LokiNetwork.send(LokiServer.HOLD_END,0);
            primaryDown=primary;

            // The alternate key configures the Fracture, but only inside the sanctum, which is the
            // only place the break has a choice to make. Outside it there is one destination and the
            // cast key is the whole control. Every other spell keeps its ordinary alternate action.
            while(SECONDARY.consumeClick()) {
                if(Ability.at(ClientState.self().getInt("selected"))!=Ability.RIFT)LokiNetwork.send(LokiServer.ALTERNATE,0);
                else FractureScreen.open();
            }
            while(TRANSFORM.consumeClick())LokiNetwork.send(LokiServer.TRANSFORM,0);
            while(RELEASE.consumeClick())LokiNetwork.send(LokiServer.UTILITY,0);
            while(FLIGHT.consumeClick())LokiNetwork.send(LokiServer.FLIGHT,0);
            for(int i=0;i<TIME_KEYS.length;i++)while(TIME_KEYS[i].consumeClick())LokiNetwork.send(LokiServer.TIME,i);
            drain();
        }
        /** Called when the world changes underfoot: a held cast must not survive the crossing. */
        static void releaseHeldCast() {
            if(primaryDown||PRIMARY.isDown())primaryLatched=true;
            primaryDown=false;
        }
        private static void drain() {
            while(PRIMARY.consumeClick());
            while(SELECT.consumeClick());
            for(KeyMapping k:TIME_KEYS)while(k.consumeClick());
        }

        @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent e) {
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.screen!=null)return;
            if(QuickBar.scroll(e.getScrollDelta())){e.setCanceled(true);return;}
            if(ClientState.self().getInt("grip")>0) {
                // Both hands are busy holding something; the wheel pushes and pulls it instead of the hotbar.
                LokiNetwork.send(LokiServer.SCROLL,8+(int)Math.signum(e.getScrollDelta()));
                e.setCanceled(true);
            }
        }
        @SubscribeEvent public static void mouse(InputEvent.InteractionKeyMappingTriggered e) {
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.screen!=null)return;
            if(!(mc.player.getMainHandItem().getItem() instanceof ConjuredWeapon)||!(e.isAttack()||e.isUseItem()))return;
            e.setCanceled(true);e.setSwingHand(false);
            if(!ClientState.frozen(mc.player.getId()))LokiNetwork.send(LokiServer.WEAPON,e.isUseItem()?1:0);
        }
        @SubscribeEvent public static void movement(MovementInputUpdateEvent e) {
            if(!ClientState.frozen(e.getEntity().getId()))return;
            e.getInput().forwardImpulse=0;e.getInput().leftImpulse=0;e.getInput().jumping=false;
        }
        @SubscribeEvent public static void hud(RenderGuiOverlayEvent.Post e) {
            if(e.getOverlay().id().equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.HOTBAR.id()))LokiHud.render(e.getGuiGraphics());
        }
        @SubscribeEvent public static void world(RenderLevelStageEvent e) {
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_SKY)CapeRenderer.beginFrame(e);
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES)WorldEffects.render(e);
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL)TemporalScreen.render(e.getPartialTick());
        }
        @SubscribeEvent public static void player(RenderPlayerEvent.Pre e){if(ClientState.data(e.getEntity().getId()).getLong("vanishUntil")>ClientState.now()){e.setCanceled(true);return;}DisguiseRenderer.render(e);if(!e.isCanceled())WorldEffects.beforePlayer(e);}
        @SubscribeEvent public static void playerEnd(RenderPlayerEvent.Post e){WorldEffects.afterPlayer(e);}
    }
}
