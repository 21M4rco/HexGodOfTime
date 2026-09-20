package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Physical edges catch taps shorter than a client tick, including remapped mouse bindings. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class BranchKeyInput {
    private static final BranchTapGesture GESTURE=new BranchTapGesture();
    private static boolean owned,latched;
    private static boolean selected(){return Ability.at(ClientState.self().getInt("selected"))==Ability.TIME_BRANCH;}
    private static boolean available(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.screen==null&&HexClient.enabled();}
    @SubscribeEvent public static void key(InputEvent.Key e) {
        if(HexClient.PRIMARY.matches(e.getKey(),e.getScanCode()))edge(e.getAction());
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Post e) {
        if(HexClient.PRIMARY.matchesMouse(e.getButton()))edge(e.getAction());
    }
    private static void edge(int action) {
        if(action==GLFW.GLFW_PRESS&&available()&&selected()&&!latched) {
            owned=true;GESTURE.press(Util.getMillis());
        } else if(action==GLFW.GLFW_RELEASE) {
            if(owned&&available())release();else GESTURE.cancel();
            latched=false;
        }
    }
    /** Return true while this primary press belongs to Time Branch, even if the selector changes. */
    static boolean tick(boolean down,boolean previouslyDown) {
        if(!owned&&!latched&&previouslyDown)return false;
        boolean handles=owned||latched||selected();
        if(!handles)return false;
        if(!available()){cancel(true);return true;}
        if(latched){if(!down)latched=false;return true;}
        if(down&&!GESTURE.pressed()&&!owned){owned=true;GESTURE.press(Util.getMillis());}
        if(GESTURE.pressed()) {
            if(!selected()&&!GESTURE.holding()){cancel(false);return true;}
            dispatch(GESTURE.tick(Util.getMillis()));
            if(!down)release();
        }
        if(!down)owned=false;
        return true;
    }
    private static void release() {
        long now=Util.getMillis();
        if(!selected()&&!GESTURE.holding()){GESTURE.cancel();return;}
        // On a slow frame the release can arrive before tick has promoted the hold.
        dispatch(GESTURE.tick(now));
        dispatch(GESTURE.release(now));
    }
    private static void dispatch(BranchTapGesture.Action action) {
        switch(action) {
            case TAP -> HexNetwork.send(HexServer.BRANCH_TAP,0);
            case BEGIN -> HexNetwork.send(HexServer.HOLD_BEGIN,0);
            case END -> HexNetwork.send(HexServer.HOLD_END,0);
            default -> {}
        }
    }
    static void cancel(boolean release) {
        if(release&&GESTURE.holding())HexNetwork.send(HexServer.HOLD_END,0);
        latched=HexClient.PRIMARY.isDown();owned=false;GESTURE.cancel();
    }
}
