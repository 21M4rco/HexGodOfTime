package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.BranchTapGesture;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** M owns Time Branch independently of the selected quick slot. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class BranchKeyInput {
    private static final BranchTapGesture GESTURE=new BranchTapGesture();
    private static boolean latched;
    private static boolean available(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.screen==null&&HexClient.enabled();}
    @SubscribeEvent public static void key(InputEvent.Key e) {
        if(HexClient.TIME_BRANCH.matches(e.getKey(),e.getScanCode()))edge(e.getAction());
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Post e) {
        if(HexClient.TIME_BRANCH.matchesMouse(e.getButton()))edge(e.getAction());
    }
    private static void edge(int action) {
        if(action==GLFW.GLFW_PRESS&&available()&&!latched)GESTURE.press(Util.getMillis());
        else if(action==GLFW.GLFW_RELEASE) {
            if(GESTURE.pressed()&&available())release();else GESTURE.cancel();
            latched=false;
        }
    }
    static void tick() {
        boolean down=HexClient.TIME_BRANCH.isDown();
        if(!available()){cancel(true);return;}
        if(latched){if(!down)latched=false;return;}
        if(down&&!GESTURE.pressed())GESTURE.press(Util.getMillis());
        if(GESTURE.pressed()) {
            if(down)dispatch(GESTURE.tick(Util.getMillis()));
            else release();
        }
    }
    private static void release() {
        long now=Util.getMillis();
        dispatch(GESTURE.tick(now));
        dispatch(GESTURE.release(now));
    }
    private static void dispatch(BranchTapGesture.Action action) {
        switch(action) {
            case TAP -> HexNetwork.send(HexServer.BRANCH_TAP,0);
            case BEGIN -> HexNetwork.send(HexServer.BRANCH_BEGIN,0);
            case END -> HexNetwork.send(HexServer.BRANCH_END,0);
            default -> {}
        }
    }
    static void cancel(boolean release) {
        if(release&&GESTURE.holding())HexNetwork.send(HexServer.BRANCH_CANCEL,0);
        latched=HexClient.TIME_BRANCH.isDown();
        GESTURE.cancel();
    }
}
