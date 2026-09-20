package com.hexgodofstories.client;

import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
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
    private static final int ROW=12;
    private static boolean open;
    private static int cursor;

    private static boolean enabled(){return ClientState.self().getBoolean("abilitiesEnabled");}
    public static boolean open() {return open&&enabled();}

    public static void openBar() {
        if(!enabled()||open)return;
        open=true;
        cursor=Math.max(0,indexOf(ClientState.self(),ClientState.self().getInt("selected")));
    }
    public static void closeBar(boolean commit) {
        if(!enabled()||!open)return;
        open=false;
        if(!commit||!enabled())return;
        int ability=slot(ClientState.self(),cursor);
        if(ability>=0)HexNetwork.send(HexServer.SELECT,ability);
    }
    /** @return true when the wheel was consumed by the bar rather than the hotbar. */
    public static boolean scroll(double delta) {
        if(!enabled()||!open)return false;
        int step=delta>0?-1:1;
        for(int i=0;i<HexData.QUICK_SLOTS;i++) {
            cursor=Math.floorMod(cursor+step,HexData.QUICK_SLOTS);
            if(slot(ClientState.self(),cursor)>=0)break;
        }
        return true;
    }
    public static void assign(int slot,int ability) {if(enabled())HexNetwork.send(HexServer.ASSIGN,slot*1000+ability+1);}
    public static int cursor() {return cursor;}

    private static int slot(CompoundTag data,int index) {
        int[] slots=data.getIntArray("quick");
        return slots.length==HexData.QUICK_SLOTS&&index>=0&&index<slots.length?slots[index]:-1;
    }
    private static int indexOf(CompoundTag data,int ability) {
        for(int i=0;i<HexData.QUICK_SLOTS;i++)if(slot(data,i)==ability)return i;
        return 0;
    }
    public static Ability displayed() {
        if(!enabled())return null;
        int id=open?slot(ClientState.self(),cursor):ClientState.self().getInt("selected");
        return Ability.slot(id);
    }
    /** Every slot, named, while the bar is open: which ability answers to which slot is the whole point. */
    public static void renderChoices(GuiGraphics g,int x,int y,int width) {
        if(!open)return;
        var mc=Minecraft.getInstance();
        CompoundTag data=ClientState.self();
        for(int index=0;index<HexData.QUICK_SLOTS;index++) {
            boolean here=index==cursor;
            int top=y+index*ROW;
            Ability a=Ability.slot(slot(data,index));
            g.fill(x,top,x+width,top+ROW,here?0xe41a3528:0xb509130e);
            g.fill(x,top,x+2,top+ROW,a==null?0xff2c3630:0xff000000|a.discipline.color);
            g.drawString(mc.font,(index+1)+"  "+(a==null?"Empty":a.title),x+6,top+2,here?0xe0f5e7:a==null?0x6a7f73:0x9fb8a9,false);
            if(a==null)continue;
            long cooldown=Math.max(0,data.getLong("cd_"+a.name())-ClientState.now());
            if(cooldown<=0)continue;
            String recovery=String.format(java.util.Locale.ROOT,"%.0fs",cooldown/20f);
            g.drawString(mc.font,recovery,x+width-mc.font.width(recovery)-5,top+2,0xc6a271,false);
        }
    }
    /** How tall the open bar is, so the panel above it knows where to start drawing. */
    public static int height() {return HexData.QUICK_SLOTS*ROW;}
}
