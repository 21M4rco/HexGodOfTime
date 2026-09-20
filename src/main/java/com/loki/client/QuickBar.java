package com.loki.client;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.loki.server.LokiServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * Eight persisted shortcuts in a compact bottom-left selector. Holding the select key opens the bar and the mouse wheel
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

    public static Ability displayed() {
        int id=open?slot(ClientState.self(),cursor):ClientState.self().getInt("selected");
        return Ability.slot(id);
    }
    /** Only three neighboring names appear while choosing; all eight slots remain scrollable. */
    public static void renderChoices(GuiGraphics g,int x,int y,int width) {
        if(!open)return;
        var mc=Minecraft.getInstance();
        for(int row=-1;row<=1;row++) {
            int index=Math.floorMod(cursor+row,LokiData.QUICK_SLOTS),id=slot(ClientState.self(),index);
            int top=y+(row+1)*12;
            g.fill(x,top,x+width,top+12,row==0?0xe41a3528:0xb509130e);
            Ability a=Ability.slot(id);
            String label=(index+1)+"  "+(a==null?"Empty":a.title);
            g.drawString(mc.font,label,x+5,top+2,row==0?0xe0f5e7:0x819a8b,false);
        }
    }
}
