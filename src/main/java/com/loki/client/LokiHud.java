package com.loki.client;

import com.loki.data.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Locale;

/** One small bottom-left ability readout; selecting another slot changes its instructions in place. */
public final class LokiHud {
    private static final int WIDTH=204,HEIGHT=96;
    private static final float SCALE=.8f;
    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui)return;
        var d=ClientState.self();Ability a=QuickBar.displayed();
        if(a==null)return;
        int screenWidth=mc.getWindow().getGuiScaledWidth(),screenHeight=mc.getWindow().getGuiScaledHeight();
        int bottom=screenHeight-(screenWidth<540?54:8);
        int x=8,y=bottom-(int)(HEIGHT*SCALE);
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(SCALE,SCALE,1);
        g.fill(0,0,WIDTH,HEIGHT,0xb807110d);
        g.fill(0,0,2,HEIGHT,0xff000000|a.discipline.color);
        String title=a.title;
        g.drawString(mc.font,title,7,6,0xe6f3e8,false);
        long cd=Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());
        boolean home=a==Ability.RIFT&&com.loki.server.PocketRealm.inside(mc.player.level());
        String status=home?"Return: free":cd>0?String.format(Locale.ROOT,"Recovery %.1fs",cd/20f):d.getFloat("energy")<a.cost?"Low energy":"Ready";
        g.drawString(mc.font,status+"  |  Cost "+(home?0:a.cost),7,18,cd>0&&!home?0xd2b27f:0x93caaa,false);
        String primary=LokiClient.PRIMARY.getTranslatedKeyMessage().getString();
        String secondary=LokiClient.SECONDARY.getTranslatedKeyMessage().getString();
        var lines=mc.font.split(net.minecraft.network.chat.Component.literal(primary+"  "+primary(a)),WIDTH-14);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(mc.font,lines.get(i),7,32+i*10,0xc9d8ce,false);
        g.drawString(mc.font,secondary+"  "+alternate(a),7,54,0x9cb6a6,false);
        String select=LokiClient.SELECT.getTranslatedKeyMessage().getString();
        g.drawString(mc.font,select+" + scroll: choose ability",7,67,0x779d87,false);
        float max=100+MasteryScreen.mastery(d,Discipline.TEMPORAL)*.2f+(d.getBoolean("ascended")?150:0),energy=d.getFloat("energy");
        String value="Energy "+Math.round(energy)+" / "+Math.round(max);
        g.drawString(mc.font,value,7,82,0xb3cbbd,false);
        int start=mc.font.width(value)+14;
        g.fill(start,84,WIDTH-7,88,0xff263e31);
        g.fill(start,84,start+(int)((WIDTH-7-start)*Math.max(0,Math.min(1,energy/max))),88,0xff74d8a6);
        if(QuickBar.open())QuickBar.renderChoices(g,0,-39,WIDTH);
        else if(d.getBoolean("ascended")) {
            String flight=LokiClient.FLIGHT.getTranslatedKeyMessage().getString()+": "+(d.getBoolean("cosmicFlying")?"flying (Space / crouch)":"flight");
            g.drawString(mc.font,flight,3,-12,0xaadabd,false);
        }
        g.pose().popPose();
        if(ClientState.frozen(mc.player.getId()))g.drawCenteredString(mc.font,"BETWEEN MOMENTS",screenWidth/2,15,0xd8d6be);
    }
    private static String primary(Ability a) {
        return switch(a) {
            case RIFT -> "Tap: portal. Hold: pull 5 blocks.";
            case DUPLICATE -> "Create a living decoy.";
            case PROJECTION_SWAP -> "Swap with your nearest decoy.";
            case MASQUERADE -> "Copy a humanoid appearance.";
            case MIRAGE -> "Create decoys and briefly vanish.";
            case ARCHITECTURE -> "Hold: raise an illusory building.";
            case BOLT -> "Fire an emerald magic bolt.";
            case PUSH -> "Push nearby enemies away.";
            case TELEKINESIS -> "Grab target. Scroll: move it.";
            case BLINK -> "Teleport toward your aim.";
            case WARD -> "Raise a defensive veil.";
            case DAGGERS -> "Conjure a dagger in an empty hand.";
            case TWIN_DAGGERS -> "Conjure twin daggers. Use: throw.";
            case LAEVATEINN -> "Conjure the Void sword.";
            case ENCHANT -> "Charm a creature to follow you.";
            case MEMORY -> "Reveal a target's recent steps.";
            case TIME_SLIP -> "Slip to a recent moment.";
            case REWIND -> "Rewind position and some health.";
            case SLOW_FIELD -> "Slow nearby creatures and shots.";
            case TIME_STOP -> "Freeze the local battlefield.";
            case SELECTIVE_STOP -> "Freeze the target in your aim.";
            case THREADS -> "Bind your target in time.";
            case ASCENSION -> "Toggle your final transformation.";
        };
    }
    private static String alternate(Ability a) {
        return switch(a) {
            case RIFT -> "Close portal / open exit";
            case DUPLICATE -> "Direct / dismiss decoys";
            case PROJECTION_SWAP -> "Place a decoy at your aim";
            case MASQUERADE -> "Remove disguise";
            case ARCHITECTURE -> "Change building design";
            case BOLT -> "Stronger impact";
            case TELEKINESIS -> "Throw held target";
            case DAGGERS,TWIN_DAGGERS,LAEVATEINN -> "Dismiss weapons";
            case ENCHANT -> "Direct charmed creatures";
            case SLOW_FIELD,TIME_STOP -> "Resume time";
            case SELECTIVE_STOP -> "Exempt an ally";
            case THREADS -> "Pull bound target";
            default -> "Same action";
        };
    }
}
