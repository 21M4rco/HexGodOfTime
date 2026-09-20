package com.loki.client;

import com.loki.data.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Named ability cards with explicit selection, keys, energy and recovery above vanilla survival HUD. */
public final class LokiHud {
    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui)return;
        var d=ClientState.self();var l=QuickBar.layout();Ability a=QuickBar.displayed();
        int left=l.left(),top=l.top(),width=l.width();
        g.fill(left-4,top-27,left+width+4,top+96,0xe607110d);
        String key=LokiClient.SELECT.getTranslatedKeyMessage().getString();
        String selection=QuickBar.open()?"CHOOSE ABILITY":"SELECTED";
        g.drawString(mc.font,selection,left+2,top-23,0x94bca6,false);
        String change=QuickBar.open()?"Scroll • release "+key:"Hold "+key+" + scroll";
        g.drawString(mc.font,change,left+width-mc.font.width(change)-2,top-23,0xc7cbb9,false);
        String title=a==null?"Empty slot":a.title;
        g.drawString(mc.font,title,left+2,top-12,0xf1dfaa,false);
        String primary=LokiClient.PRIMARY.getTranslatedKeyMessage().getString();
        String hint=a==Ability.RIFT?primary+": tap / hold to pull":primary+(a!=null&&a.hold?": hold to cast":": cast");
        g.drawString(mc.font,hint,left+width-mc.font.width(hint)-2,top-12,0xa0d6b9,false);
        QuickBar.render(g);
        float max=100+MasteryScreen.mastery(d,Discipline.TEMPORAL)*.2f+(d.getBoolean("ascended")?150:0);
        float energy=d.getFloat("energy");int y=top+83;
        String value="ENERGY "+Math.round(energy)+" / "+Math.round(max);
        g.drawString(mc.font,value,left+2,y,0xd0ddcd,false);
        int barX=left+mc.font.width(value)+12;
        g.fill(barX,y+2,left+width-2,y+7,0xff263e31);
        g.fill(barX,y+2,barX+(int)((left+width-2-barX)*Math.max(0,Math.min(1,energy/max))),y+7,0xff74d8a6);
        if(d.getBoolean("ascended")) {
            String flight=LokiClient.FLIGHT.getTranslatedKeyMessage().getString()+" • "+(d.getBoolean("cosmicFlying")?"FLYING  |  Space ↑  Crouch ↓":"COSMIC FLIGHT");
            g.drawCenteredString(mc.font,flight,mc.getWindow().getGuiScaledWidth()/2,top-40,0xb4f6d0);
        }
        if(ClientState.frozen(mc.player.getId()))g.drawCenteredString(mc.font,"BETWEEN MOMENTS",mc.getWindow().getGuiScaledWidth()/2,15,0xd8d6be);
    }
}
