package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
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
public final class HexClient {
    public static final KeyMapping MENU=key("mastery",GLFW.GLFW_KEY_K),SELECT=key("select",GLFW.GLFW_KEY_V),
        PRIMARY=key("primary",GLFW.GLFW_KEY_R),SECONDARY=key("secondary",GLFW.GLFW_KEY_G),
        TRANSFORM=key("transform",GLFW.GLFW_KEY_H),RELEASE=key("release",GLFW.GLFW_KEY_X),FLIGHT=key("flight",GLFW.GLFW_KEY_J),
        /** Warping's other direction: reach into the realm chosen with G and pull its creatures out. */
        RECALL=key("recall",GLFW.GLFW_KEY_Y),
        TIME_STOP=key("time_stop",GLFW.GLFW_KEY_Z),TIME_RESUME=key("time_resume",GLFW.GLFW_KEY_B),
        TIME_REWIND=key("time_rewind",GLFW.GLFW_KEY_N),TIME_DILATE=key("time_dilate",GLFW.GLFW_KEY_M);
    /** The permanent time commands, paired with the value {@link HexServer#TIME} carries for each. */
    public static final KeyMapping[] TIME_KEYS={TIME_STOP,TIME_RESUME,TIME_REWIND,TIME_DILATE};
    private static KeyMapping key(String name,int key){return new KeyMapping("key.hexgodofstories."+name,InputConstants.Type.KEYSYM,key,"key.categories.hexgodofstories");}
    private static boolean primaryDown,selectDown,primaryWasHold,primaryLatched;
    private static int repeat;
    /** The server syncs this flag. Missing/false means this client gets no mod UI or controls at all. */
    public static boolean enabled(){return ClientState.self().getBoolean("abilitiesEnabled");}

    @Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent public static void dimensionEffects(RegisterDimensionSpecialEffectsEvent e){e.register(HexGodOfStories.id("pocket"),new RealmSky());e.register(HexGodOfStories.id("warping"),new WarpSky());}
        /**
         * Tells the common side how to ask this one whether a body is sinking.
         *
         * <p>The physics hook that keeps the floor out of the way runs on both sides, and a
         * player's own body is moved here rather than on the server, so the answer for the one
         * player this client owns lives here and nowhere else. Installed as a function so that
         * nothing common ever has to name a client-only class.
         */
        @SubscribeEvent public static void crossings(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent e) {
            e.enqueueWork(()->com.hexgodofstories.warping.WarpCrossing.clientGrant(WarpCrossingClient::phasing));
        }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) {
            for(KeyMapping k:new KeyMapping[]{MENU,SELECT,PRIMARY,SECONDARY,TRANSFORM,RELEASE,FLIGHT,RECALL})e.register(k);
            for(KeyMapping k:TIME_KEYS)e.register(k);
        }
        @SubscribeEvent public static void entities(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(HexGodOfStories.ILLUSION.get(),IllusionRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.PILGRIM.get(),com.hexgodofstories.client.leviathan.AbyssalPilgrimRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.WARP_HAZARD.get(),WarpHazardRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.PROJECTILE.get(),SpellRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.THROWN_DAGGER.get(),DaggerRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.RIFT.get(),RiftRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.STARFALL.get(),MeteorRenderer::new);
            e.registerEntityRenderer(HexGodOfStories.THRONE_SEAT.get(),net.minecraft.client.renderer.entity.NoopRenderer::new);
        }
        @SubscribeEvent public static void layers(EntityRenderersEvent.AddLayers e) {
            for(String skin:e.getSkins()) {
                net.minecraft.client.renderer.entity.player.PlayerRenderer renderer=e.getSkin(skin);
                if(renderer!=null){renderer.addLayer(new HexLayer(renderer));renderer.addLayer(new BranchFistLayer(renderer));}
            }
        }
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent e) {
            e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)r->{
                WarpRenderer.clear();HexLayer.clear();WeaponRenderer.clear();RiftRenderer.clear();RealmSky.clear();CosmicNebula.clear();
                BranchVfx.clear();TimeBranchRenderer.clear();ErasureRenderer.clear();BranchAudio.clear();MeteorAudio.clear();
                DisguiseRenderer.clear();DisguiseRenderer.forgive();Blood.clear();WoundAnchor.clear();TemporalScreen.close();
            });
        }
    }

    @Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
            if(e.phase!=TickEvent.Phase.END)return;
            ClientState.tick();
            Minecraft mc=Minecraft.getInstance();
            if(mc.player==null){BranchKeyInput.cancel(false);primaryDown=false;selectDown=false;QuickBar.closeBar(false);return;}
            if(!enabled()) {
                // Locked means invisible and inert, not merely server-rejected. Swallow every mod input
                // and close any mod-only screen immediately when access is revoked.
                BranchKeyInput.cancel(false);
                primaryLatched=primaryPhysicallyDown();
                primaryDown=false;primaryWasHold=false;repeat=0;
                selectDown=SELECT.isDown();
                QuickBar.closeBar(false);
                if(mc.screen instanceof WarpScreen||mc.screen instanceof MasteryScreen||mc.screen instanceof FractureScreen)mc.setScreen(null);
                drain();return;
            }
            if(mc.screen!=null) {
                BranchKeyInput.cancel(true);
                // Ending the hold here means the key is no longer "down" as far as this loop knows,
                // so a key that is still physically held would read as a brand new press the moment
                // the screen closes. Crossing a dimension puts the terrain screen up mid-hold, which
                // is exactly how arriving in the sanctum used to open a second break on arrival.
                if(primaryDown){primaryDown=false;primaryLatched=true;if(primaryWasHold)HexNetwork.send(HexServer.HOLD_END,0);}
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
            if(primaryLatched){if(primaryPhysicallyDown())primary=false;else primaryLatched=false;}
            boolean branchInput=BranchKeyInput.tick(primary,primaryDown);
            if(branchInput)primaryWasHold=false;
            if(!branchInput&&primary&&!primaryDown){primaryWasHold=selected.hold;HexNetwork.send(selected.hold?HexServer.HOLD_BEGIN:HexServer.CAST,0);repeat=0;}
            // Holding an ordinary spell repeats it; the server's own rate limit and cooldown set the pace.
            else if(!branchInput&&primary&&!selected.hold&&++repeat>=5){repeat=0;HexNetwork.send(HexServer.CAST,0);}
            if(!branchInput&&!primary&&primaryDown&&primaryWasHold)HexNetwork.send(HexServer.HOLD_END,0);
            primaryDown=primary;

            // The alternate key configures the Fracture, but only inside the sanctum, which is the
            // only place the break has a choice to make. Outside it there is one destination and the
            // cast key is the whole control. Every other spell keeps its ordinary alternate action.
            while(SECONDARY.consumeClick()) {
                if(Ability.at(ClientState.self().getInt("selected"))==Ability.WARPING)mc.setScreen(new WarpScreen());
                else if(Ability.at(ClientState.self().getInt("selected"))!=Ability.RIFT)HexNetwork.send(HexServer.ALTERNATE,0);
                else FractureScreen.open();
            }
            while(TRANSFORM.consumeClick())HexNetwork.send(HexServer.TRANSFORM,0);
            while(RELEASE.consumeClick())HexNetwork.send(HexServer.UTILITY,0);
            while(RECALL.consumeClick())HexNetwork.send(HexServer.WARP_RECALL,0);
            while(FLIGHT.consumeClick())HexNetwork.send(HexServer.FLIGHT,0);
            for(int i=0;i<TIME_KEYS.length;i++)while(TIME_KEYS[i].consumeClick())HexNetwork.send(HexServer.TIME,i);
            drain();
        }
        /** Called when the world changes underfoot: a held cast must not survive the crossing. */
        static void releaseHeldCast() {
            BranchKeyInput.cancel(false);
            if(primaryDown||primaryPhysicallyDown())primaryLatched=true;
            primaryDown=false;
        }
        private static boolean primaryPhysicallyDown() {
            InputConstants.Key key=PRIMARY.getKey();
            long window=Minecraft.getInstance().getWindow().getWindow();
            if(key.getType()==InputConstants.Type.MOUSE)
                return GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS;
            if(key.getType()==InputConstants.Type.KEYSYM&&key.getValue()!=GLFW.GLFW_KEY_UNKNOWN)
                return InputConstants.isKeyDown(window,key.getValue());
            return PRIMARY.isDown();
        }
        private static void drain() {
            for(KeyMapping k:new KeyMapping[]{MENU,SELECT,PRIMARY,SECONDARY,TRANSFORM,RELEASE,FLIGHT,RECALL})
                while(k.consumeClick());
            for(KeyMapping k:TIME_KEYS)while(k.consumeClick());
        }

        @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent e) {
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.screen!=null||!enabled())return;
            if(QuickBar.scroll(e.getScrollDelta())){e.setCanceled(true);return;}
            if(ClientState.self().getInt("grip")>0) {
                // Both hands are busy holding something; the wheel pushes and pulls it instead of the hotbar.
                HexNetwork.send(HexServer.SCROLL,8+(int)Math.signum(e.getScrollDelta()));
                e.setCanceled(true);
            }
        }
        @SubscribeEvent public static void mouse(InputEvent.InteractionKeyMappingTriggered e) {
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.screen!=null||!enabled())return;
            if(!(mc.player.getMainHandItem().getItem() instanceof ConjuredWeapon)||!(e.isAttack()||e.isUseItem()))return;
            e.setCanceled(true);e.setSwingHand(false);
            if(!ClientState.frozen(mc.player.getId()))HexNetwork.send(HexServer.WEAPON,e.isUseItem()?1:0);
        }
        /**
         * Walking, sprinting, strafing, jumping and flight input all stop while the caster is planted
         * holding the torrent, while a body is being erased, and inside a suspended moment. Only the
         * mouse is left alone, so a planted caster can still aim what they are about to fire.
         */
        @SubscribeEvent public static void movement(MovementInputUpdateEvent e) {
            if(!ClientState.immobile(e.getEntity()))return;
            var input=e.getInput();
            input.forwardImpulse=0;input.leftImpulse=0;
            input.up=false;input.down=false;input.left=false;input.right=false;
            input.jumping=false;input.shiftKeyDown=false;
            e.getEntity().setSprinting(false);
        }
        /**
         * The Void Sea's swell, on the one player whose movement this client owns.
         *
         * <p>The server runs exactly the same call on exactly the same formula, from the realm's
         * own tick, so the two agree without a packet: the server decides what the water is doing
         * and the client cannot choose differently, while the local copy is what makes being
         * carried feel like being carried rather than like being corrected.
         */
        @SubscribeEvent public static void voidSeaSwell(TickEvent.PlayerTickEvent e) {
            if(e.phase!=TickEvent.Phase.START)return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null||e.player!=mc.player||WarpCrossingClient.phasing(mc.player.getId()))return;
            com.hexgodofstories.warping.VoidSeaWaves.apply(e.player,mc.level.getGameTime());
        }
        /**
         * Sinking into an open Warping pool, on the one player this client owns. Start of the tick,
         * for the same reason Paradise's gravity is: a velocity set after the movement it is meant
         * to cause has a tick of gravity added to it first.
         */
        @SubscribeEvent public static void warpSink(TickEvent.PlayerTickEvent e) {
            if(e.phase!=TickEvent.Phase.START)return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null||e.player!=mc.player)return;
            WarpCrossingClient.sink(mc.player);
        }
        /**
         * Paradise's weak gravity, on the one player whose movement this client owns, and the
         * sugar hanging in the air around them.
         *
         * <p>Exactly the arrangement the swell above uses, and for exactly the same reason. The
         * realm's own tick runs the identical call on the identical formula, so the server decides
         * what gravity is and this cannot choose differently; running it here as well is what makes
         * a four block jump feel like a jump instead of like the server correcting a fall.
         */
        @SubscribeEvent public static void paradise(TickEvent.PlayerTickEvent e) {
            if(e.phase!=TickEvent.Phase.START)return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null||e.player!=mc.player)return;
            // A body going down into a pool is between worlds, and this one's gravity has let go of
            // it. Weak gravity hands back more each tick than the sink takes, so leaving it running
            // would not slow the crossing, it would reverse it.
            if(WarpCrossingClient.phasing(mc.player.getId()))return;
            if(com.hexgodofstories.warping.Destination.from(mc.level)!=com.hexgodofstories.warping.Destination.PARADISE)return;
            com.hexgodofstories.warping.Paradise.gravity(e.player);
            if(mc.level.getGameTime()%2!=0||mc.options.particles().get()==net.minecraft.client.ParticleStatus.MINIMAL)return;
            var random=mc.level.random;
            for(int i=0;i<2;i++)mc.level.addParticle(HexGodOfStories.CANDY.get(),
                mc.player.getX()+(random.nextDouble()-.5)*26,mc.player.getY()+random.nextDouble()*15-4,mc.player.getZ()+(random.nextDouble()-.5)*26,
                (random.nextDouble()-.5)*.012,.005+random.nextDouble()*.01,(random.nextDouble()-.5)*.012);
        }
        @SubscribeEvent public static void hud(RenderGuiOverlayEvent.Post e) {
            if(e.getOverlay().id().equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.HOTBAR.id()))HexHud.render(e.getGuiGraphics());
        }
        @SubscribeEvent public static void world(RenderLevelStageEvent e) {
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_SKY){CapeRenderer.beginFrame(e);WoundAnchor.beginFrame(e);}
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_ENTITIES)WarpRenderer.renderRealm(e);
            // Forge's supported translucent-effects stage, paired with the wave's particles
            // target so Fabulous composites the swell correctly over the water and entities.
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES)VoidSeaWaveRenderer.render(e);
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES){WorldEffects.render(e);WarpRenderer.render(e);}
            if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL)TemporalScreen.render(e.getPartialTick());
        }
        @SubscribeEvent public static void player(RenderPlayerEvent.Pre e) {
            // Checked before anything pushes a pose: a cancelled pre-event never gets its post-event, so
            // the erasure has to bow out here or the matching pop would be lost.
            if(ErasureRenderer.consumed(e.getEntity())){e.setCanceled(true);return;}
            if(ClientState.data(e.getEntity().getId()).getLong("vanishUntil")>ClientState.now()){e.setCanceled(true);return;}
            DisguiseRenderer.render(e);
            if(!e.isCanceled())WorldEffects.beforePlayer(e);
        }
        /** The same stand-down for every other living thing; players are answered above. */
        @SubscribeEvent public static void living(net.minecraftforge.client.event.RenderLivingEvent.Pre<?,?> e) {
            if(e.getEntity() instanceof net.minecraft.world.entity.player.Player)return;
            if(ErasureRenderer.consumed(e.getEntity()))e.setCanceled(true);
        }
        @SubscribeEvent public static void playerEnd(RenderPlayerEvent.Post e){WorldEffects.afterPlayer(e);}
        /** Breaches, impacts and the standing tremor of something enormous passing underneath. */
        @SubscribeEvent public static void pilgrimCamera(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles e){com.hexgodofstories.client.leviathan.LeviathanEffects.camera(e);}
    }
}
