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
     * A slot's key was pressed: its ability becomes the chosen one, here at once (before the server has answered) and
     * on the server, which checks it against its own copy of the slots. The caller then casts it.
     *
     * @return the ability chosen, or null when there is nothing on that key or nothing may be cast now
     */
    public static Ability press(int index) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.screen!=null||!enabled())return null;
        CompoundTag data=ClientState.self();
        int ability=slot(data,index);
        Ability a=Ability.slot(ability);
        if(a==null)return null;
        data.putInt("selected",ability);
        HexNetwork.send(HexServer.SLOT,index);
        return a;
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

    /** A key's name short enough for a small cell: "Left Alt" is LAlt, "Right Shift" RShft, and nothing past five letters. */
    static String shortKey(String name) {
        String s=name.replace("Left ","L").replace("Right ","R").replace("Shift","Shft").replace("Control","Ctrl").replace(" ","");
        return s.length()>5?s.substring(0,5):s;
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
            HexUi.rounded(g,left,y,CELL,TALL,chosen?0xe81c3627:0xd00a1310);
            HexUi.outline(g,left,y,CELL,TALL,HexUi.BORDER);
            g.fill(left+2,y+1,left+CELL-2,y+3,a==null?0xff2c3630:0xff000000|a.discipline.color);
            if(a!=null) {
                long cooldown=Math.max(0,data.getLong("cd_"+a.name())-now);
                // Measured against the recovery the spell actually left: the mantle's variants mostly take longer.
                int whole=Math.max(a.cooldown,ascended&&Ascended.recovery(a)>0?Ascended.recovery(a):0);
                if(cooldown>0&&whole>0) {
                    float left01=Math.min(1,cooldown/(float)whole);
                    int cover=Math.round((TALL-2)*left01);
                    g.fill(left,y+TALL-cover,left+CELL,y+TALL,0x9a000000);
                }
            }
            if(chosen)HexUi.outline(g,left,y,CELL,TALL,ascended&&a!=null&&Ascended.changes(a)?0xffe2c46a:0xff9fd8b4);
            String key=shortKey(key(index));
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
