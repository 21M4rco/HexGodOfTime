package com.hexgodofstories.client;

import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

/**
 * The seven bind slots, along the bottom row of the keyboard: Z X C V B N M. Pressing a slot's key chooses the
 * ability bound to it on the spot, with nothing to open and nothing to scroll; the cast key then casts it. The slots
 * are drawn as a strip over the ability panel, each under its own key, so which ability answers to which key is
 * always on screen. Slot contents live in player data, so a layout survives relogging and death.
 */
public final class QuickBar {
    private QuickBar() {}

    /** A cell of the strip, in the panel's own (unscaled) units. */
    private static final int CELL=28,GAP=1,TALL=22;

    private static boolean enabled(){return ClientState.self().getBoolean("abilitiesEnabled");}

    /**
     * A slot's key was pressed. The choice is shown and kept here at once, before the server has answered, so a
     * cast pressed straight after it already goes out as the ability just chosen (a held one as a hold); the
     * server checks it against its own copy of the slots and its next sync settles any difference.
     */
    public static void press(int index) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.screen!=null||!enabled())return;
        CompoundTag data=ClientState.self();
        int ability=slot(data,index);
        if(ability<0)return;
        data.putInt("selected",ability);
        HexNetwork.send(HexServer.SLOT,index);
    }
    public static void assign(int slot,int ability) {if(enabled())HexNetwork.send(HexServer.ASSIGN,slot*1000+ability+1);}

    static int slot(CompoundTag data,int index) {
        int[] slots=data.getIntArray("quick");
        return slots.length==HexData.QUICK_SLOTS&&index>=0&&index<slots.length?slots[index]:-1;
    }
    /** @return the slot holding this ability, or -1 when it is not bound. */
    static int slotOf(CompoundTag data,Ability a) {
        for(int i=0;i<HexData.QUICK_SLOTS;i++)if(slot(data,i)==a.ordinal())return i;
        return -1;
    }
    public static Ability displayed() {
        if(!enabled())return null;
        return Ability.slot(ClientState.self().getInt("selected"));
    }

    /** The name a slot's key goes by: Z, X, C... or whatever it has been rebound to. */
    static String key(int index) {
        return index<0||index>=HexClient.SLOTS.length?"":HexClient.SLOTS[index].getTranslatedKeyMessage().getString();
    }

    /** How wide the strip is, in the panel's own units. */
    public static int width() {return HexData.QUICK_SLOTS*(CELL+GAP)-GAP;}
    public static int height() {return TALL;}

    /**
     * The strip: each slot under its key, its discipline along the top, a short name, how much of its recovery is
     * left drawn down over it, and the chosen one lit. A slot whose ability is changed by the full transformation
     * shows it in gold while the mantle is worn.
     */
    public static void render(GuiGraphics g,int x,int y) {
        var mc=Minecraft.getInstance();
        CompoundTag data=ClientState.self();
        int selected=data.getInt("selected");
        boolean ascended=data.getBoolean("ascended");
        long now=ClientState.now();
        for(int index=0;index<HexData.QUICK_SLOTS;index++) {
            int left=x+index*(CELL+GAP);
            Ability a=Ability.slot(slot(data,index));
            boolean chosen=a!=null&&a.ordinal()==selected;
            g.fill(left,y,left+CELL,y+TALL,chosen?0xe81a3528:0xb807110d);
            g.fill(left,y,left+CELL,y+2,a==null?0xff2c3630:0xff000000|a.discipline.color);
            if(a!=null) {
                long cooldown=Math.max(0,data.getLong("cd_"+a.name())-now);
                if(cooldown>0&&a.cooldown>0) {
                    float left01=Math.min(1,cooldown/(float)Math.max(a.cooldown,1));
                    int cover=Math.round((TALL-2)*left01);
                    g.fill(left,y+TALL-cover,left+CELL,y+TALL,0x9a000000);
                }
            }
            if(chosen) {
                int ring=ascended&&a!=null&&Ascended.changes(a)?0xffe2c46a:0xff9fd8b4;
                g.fill(left,y,left+1,y+TALL,ring);g.fill(left+CELL-1,y,left+CELL,y+TALL,ring);
                g.fill(left,y+TALL-1,left+CELL,y+TALL,ring);
            }
            String key=key(index);
            if(key.length()>3)key=key.substring(0,3);
            g.drawString(mc.font,key,left+3,y+4,a==null?0x5f7266:0xf2f8f3,false);
            if(a==null)continue;
            int tint=ascended&&Ascended.changes(a)?0xe2c46a:chosen?0xd9eadf:0x8fa898;
            g.pose().pushPose();
            g.pose().translate(left+2,y+14,0);
            g.pose().scale(.62f,.62f,1);
            String tag=tag(a);
            g.drawString(mc.font,tag,Math.max(0,(int)((CELL-4)/.62f-mc.font.width(tag))/2),0,tint,false);
            g.pose().popPose();
        }
    }

    /** A slot's few letters: room for a word, never a title. */
    static String tag(Ability a) {
        return switch(a) {
            case DUPLICATE -> "Proj";
            case PROJECTION_SWAP -> "Swap";
            case MASQUERADE -> "Mask";
            case MIRAGE -> "Court";
            case ARCHITECTURE -> "Wall";
            case BOLT -> "Bolt";
            case PUSH -> "Push";
            case TELEKINESIS -> "Lift";
            case BLINK -> "Step";
            case WARD -> "Ward";
            case RIFT -> "Rift";
            case DAGGERS -> "Dagger";
            case TWIN_DAGGERS -> "Twins";
            case LAEVATEINN -> "Scepter";
            case ENCHANT -> "Charm";
            case MEMORY -> "Echo";
            case TIME_SLIP -> "Slip";
            case REWIND -> "Rewind";
            case SLOW_FIELD -> "";
            case TIME_STOP -> "Stop";
            case SELECTIVE_STOP -> "Moment";
            case THREADS -> "Anchor";
            case ASCENSION -> "Mantle";
            case TIME_BRANCH -> "Branch";
            case WARPING -> "Warp";
            case ARSENAL -> "Crown";
        };
    }
}
