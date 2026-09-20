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
    private static final int SLOT=22,GAP=2;
    private static boolean open;
    private static int cursor;
    private static long openedAt;

    public static boolean open() {return open;}

    public static void openBar() {
        if(open)return;
        open=true;openedAt=ClientState.now();
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

    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();
        if(mc.player==null)return;
        CompoundTag data=ClientState.self();
        int selected=data.getInt("selected");
        int width=LokiData.QUICK_SLOTS*SLOT+(LokiData.QUICK_SLOTS-1)*GAP;
        int left=(mc.getWindow().getGuiScaledWidth()-width)/2;
        int top=mc.getWindow().getGuiScaledHeight()-(open?78:60);
        float reveal=open?Mth.clamp((ClientState.now()-openedAt)/3f,0,1):1;
        float energy=data.getFloat("energy");

        for(int i=0;i<LokiData.QUICK_SLOTS;i++) {
            int x=left+i*(SLOT+GAP),y=top;
            int ability=slot(data,i);
            boolean active=ability>=0&&ability==selected;
            boolean hovered=open&&i==cursor;
            g.fill(x,y,x+SLOT,y+SLOT,hovered?0xe0121f18:0xb00a1511);
            if(ability<0) {
                g.fill(x,y+SLOT-1,x+SLOT,y+SLOT,0x40605a44);
                continue;
            }
            Ability a=Ability.at(ability);
            int tint=a.discipline.color;
            long cooldown=Math.max(0,data.getLong("cd_"+a.name())-ClientState.now());
            boolean ready=cooldown<=0&&energy>=a.cost;
            g.fill(x,y,x+SLOT,y+1,0xff000000|tint);
            g.drawCenteredString(mc.font,abbreviation(a),x+SLOT/2,y+7,ready?0xffe6f0dd:0xff6f7a66);
            if(cooldown>0) {
                int shade=Math.min(SLOT-2,(int)(SLOT*Math.min(1,cooldown/(float)Math.max(1,a.cooldown))));
                g.fill(x+1,y+SLOT-shade,x+SLOT-1,y+SLOT-1,0x99101a14);
            }
            if(a.cost>0)g.fill(x+2,y+SLOT-3,x+2+(int)((SLOT-4)*Mth.clamp(energy/Math.max(1,a.cost),0,1)),y+SLOT-2,0xffc3ab67);
            if(active||hovered) {
                int border=active?0xffd8c27a:0xff8fae92;
                g.fill(x-1,y-1,x+SLOT+1,y,border);g.fill(x-1,y+SLOT,x+SLOT+1,y+SLOT+1,border);
                g.fill(x-1,y,x,y+SLOT,border);g.fill(x+SLOT,y,x+SLOT+1,y+SLOT,border);
            }
            g.drawString(mc.font,String.valueOf(i+1),x+2,y+SLOT-9,0x55ffffff,false);
        }
        if(!open||reveal<.2f)return;
        int ability=slot(data,cursor);
        String label=ability>=0?Ability.at(ability).title:"Empty";
        g.drawCenteredString(mc.font,label,mc.getWindow().getGuiScaledWidth()/2,top-13,0xffdfe7d2);
        g.drawCenteredString(mc.font,"scroll to choose",mc.getWindow().getGuiScaledWidth()/2,top+SLOT+4,0xff7d8f78);
    }
}
