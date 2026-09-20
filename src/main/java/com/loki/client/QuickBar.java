package com.loki.client;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.loki.server.LokiServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * Eight persisted shortcuts above the hotbar. Holding the select key opens the bar and the mouse wheel
 * walks it; releasing commits the choice, so switching spells mid-fight costs one gesture rather than a
 * trip through a menu. Slot contents live in player data, so a layout survives relogging and death.
 */
public final class QuickBar {
    private static boolean open;
    private static int cursor;

    public static boolean open() {return open;}

    public static void openBar() {
        if(open)return;
        open=true;
        cursor=Math.max(0,indexOf(ClientState.self(),ClientState.self().getInt("selected")));
    }
    public static void closeBar(boolean commit) {
        if(!open)return;
        open=false;
        if(!commit)return;
        int ability=slot(ClientState.self(),cursor);
        if(ability>=0)LokiNetwork.send(LokiServer.SELECT,ability);
    }
    /** @return true when the wheel was consumed by the bar rather than the hotbar. */
    public static boolean scroll(double delta) {
        if(!open)return false;
        int step=delta>0?-1:1;
        for(int i=0;i<LokiData.QUICK_SLOTS;i++) {
            cursor=Math.floorMod(cursor+step,LokiData.QUICK_SLOTS);
            if(slot(ClientState.self(),cursor)>=0)break;
        }
        return true;
    }
    public static void assign(int slot,int ability) {LokiNetwork.send(LokiServer.ASSIGN,slot*1000+ability+1);}
    public static int cursor() {return cursor;}

    private static int slot(CompoundTag data,int index) {
        int[] slots=data.getIntArray("quick");
        return slots.length==LokiData.QUICK_SLOTS&&index>=0&&index<slots.length?slots[index]:-1;
    }
    private static int indexOf(CompoundTag data,int ability) {
        for(int i=0;i<LokiData.QUICK_SLOTS;i++)if(slot(data,i)==ability)return i;
        return 0;
    }
    /** Two initials read reliably at slot size where a name never would. */
    static String abbreviation(Ability a) {
        StringBuilder out=new StringBuilder();
        for(String word:a.title.split("\\s+")) {
            if(word.isEmpty()||out.length()>=2)continue;
            out.append(Character.toUpperCase(word.charAt(0)));
        }
        if(out.length()<2)out.append(Character.toUpperCase(a.title.length()>1?a.title.charAt(1):'.'));
        return out.toString();
    }

    public record Layout(int left,int top,int cardWidth,int cardHeight,int width) {}
    public static Layout layout() {
        var w=Minecraft.getInstance().getWindow();
        int card=Math.min(110,(w.getGuiScaledWidth()-28)/4),width=card*4+12;
        return new Layout((w.getGuiScaledWidth()-width)/2,Math.max(30,w.getGuiScaledHeight()-154),card,38,width);
    }
    public static Ability displayed() {
        int id=open?slot(ClientState.self(),cursor):ClientState.self().getInt("selected");
        return id<0?null:Ability.at(id);
    }
    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();if(mc.player==null)return;
        CompoundTag data=ClientState.self();Layout l=layout();
        int selected=data.getInt("selected");
        for(int i=0;i<LokiData.QUICK_SLOTS;i++) {
            int x=l.left+i%4*(l.cardWidth+4),y=l.top+i/4*(l.cardHeight+4);
            int id=slot(data,i);boolean active=id>=0&&id==selected,hovered=open&&i==cursor;
            int border=hovered?0xffffe2a0:active?0xff72f1b2:0xff344f46;
            g.fill(x,y,x+l.cardWidth,y+l.cardHeight,border);
            g.fill(x+1,y+1,x+l.cardWidth-1,y+l.cardHeight-1,hovered?0xf3213027:active?0xf3123025:0xeb091610);
            Ability a=Ability.slot(id);
            if(a==null){g.drawString(mc.font,"Empty slot",x+6,y+8,0x81918a,false);continue;}
            g.fill(x+1,y+1,x+3,y+l.cardHeight-1,0xff000000|a.discipline.color);
            var lines=mc.font.split(net.minecraft.network.chat.Component.literal(a.title),l.cardWidth-12);
            for(int line=0;line<Math.min(2,lines.size());line++)g.drawString(mc.font,lines.get(line),x+6,y+5+line*10,active||hovered?0xf3fff7:0xdce9df,false);
            long cd=Math.max(0,data.getLong("cd_"+a.name())-ClientState.now());
            boolean home=a==Ability.RIFT&&com.loki.server.PocketRealm.inside(mc.player.level());
            String status=home?"RETURN":cd>0?String.format(java.util.Locale.ROOT,"%.1fs",cd/20f):data.getFloat("energy")<a.cost?"LOW ENERGY":"READY";
            int tint=home||cd<=0&&data.getFloat("energy")>=a.cost?0x87d8a8:0xd2b27f;
            g.drawString(mc.font,status,x+6,y+l.cardHeight-11,tint,false);
            if(cd>0&&!home)g.fill(x+3,y+l.cardHeight-2,x+3+(int)((l.cardWidth-5)*Math.min(1,cd/(double)Math.max(1,a.cooldown))),y+l.cardHeight-1,0xffd2b27f);
        }
    }
}
