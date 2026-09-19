package com.loki.client;
import com.loki.data.*;
import com.loki.entity.IllusionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class LokiHud {
    public static void render(GuiGraphics g){var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui)return;var d=ClientState.data(mc.player.getId());Ability a=Ability.at(d.getInt("selected"));int x=12,y=mc.getWindow().getGuiScaledHeight()-77,w=174;
        g.fill(x,y,x+w,y+61,0xd90a1511);g.fill(x,y,x+2,y+61,0xff8d9a68);g.drawString(mc.font,a.title,x+9,y+8,0xd2dec3,false);
        long cooldown=Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());String status=cooldown>0?String.format(java.util.Locale.ROOT,"%.1fs",cooldown/20f):"Ready";g.drawString(mc.font,LokiClient.PRIMARY.getTranslatedKeyMessage().getString()+"  Cast   "+LokiClient.SECONDARY.getTranslatedKeyMessage().getString()+"  Alternate",x+9,y+23,0x8a9e88,false);g.drawString(mc.font,status,x+w-42,y+8,cooldown>0?0xc3a475:0x83c99b,false);
        float max=100+MasteryScreen.mastery(d,Discipline.TEMPORAL)*.2f+(d.getBoolean("ascended")?150:0),energy=d.getFloat("energy");g.fill(x+9,y+40,x+w-9,y+43,0xff334036);g.fill(x+9,y+40,x+9+(int)((w-18)*Math.max(0,Math.min(1,energy/max))),y+43,0xffc3ab67);
        g.drawString(mc.font,"TEMPORAL  "+Math.round(energy)+" / "+Math.round(max),x+9,y+49,0x9eb396,false);
        if(ClientState.frozen(mc.player.getId()))g.drawCenteredString(mc.font,"BETWEEN MOMENTS",mc.getWindow().getGuiScaledWidth()/2,22,0xd8d6be);
        if(d.getBoolean("ascended"))g.drawString(mc.font,"KEEPER OF TIME",x+2,y-13,0xc9bc80,false);
    }
}
