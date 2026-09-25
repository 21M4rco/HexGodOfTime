package com.hexgodofstories.client;

import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * The Book of Stories. Choosing an ability selects it; choosing a quick slot first binds it
 * there instead, which is the only assignment gesture in the mod.
 */
public final class MasteryScreen extends Screen {
    private Discipline chapter=Discipline.MISCHIEF;
    private final boolean quick;
    private int left,top,w,h,binding=-1;
    /** Quick-slot geometry, shared by the widgets and the panel drawn behind them. */
    private int slotWidth,barLeft,barTop;

    public MasteryScreen(boolean quick) {
        super(Component.literal(quick?"Spell selection":"The Book of Stories"));
        this.quick=quick;
        var p=Minecraft.getInstance().player;
        if(p!=null)chapter=Ability.at(ClientState.data(p.getId()).getInt("selected")).discipline;
    }
    @Override public boolean isPauseScreen(){return false;}
    private CompoundTag data(){return minecraft==null||minecraft.player==null?new CompoundTag():ClientState.data(minecraft.player.getId());}
    static int mastery(CompoundTag n,Discipline d){return MasteryCurve.levelForXp(n.getLong("xp_"+d.name()));}
    static boolean unlocked(CompoundTag n,Ability a) {
        if(a==Ability.SLOW_FIELD)return false;
        if(n.getBoolean("unlock_"+a.name()))return true;
        int sum=0;
        for(Discipline d:Discipline.values())if(d.ordinal()<4)sum+=mastery(n,d);
        return mastery(n,a.discipline)>=a.level
            &&(a.discipline!=Discipline.TEMPORAL||sum>=600)
            &&(a.discipline!=Discipline.PURPOSE||mastery(n,Discipline.TEMPORAL)>=800);
    }
    private static int quickSlot(CompoundTag n,int index) {
        int[] slots=n.getIntArray("quick");
        return slots.length==HexData.QUICK_SLOTS&&index>=0&&index<slots.length?slots[index]:-1;
    }

    @Override protected void init() {
        w=Math.min(650,width-24);h=Math.min(392,height-24);
        left=(width-w)/2;top=(height-h)/2;
        int nav=Math.max(108,w/4);
        for(Discipline d:Discipline.values())
            addRenderableWidget(Button.builder(Component.literal(d.title),b->{chapter=d;rebuildWidgets();})
                .bounds(left+12,top+69+d.ordinal()*29,nav-20,23).build());

        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter&&a!=Ability.RIFT&&a!=Ability.SLOW_FIELD).toList();
        int card=cardHeight(list.size());
        int i=0;
        for(Ability a:list) {
            int y=top+70+i++*card;
            Button button=Button.builder(Component.literal(a.dedicated?a.title+"   \u00b7   "+key(a):a.title),b->choose(a))
                .bounds(left+nav+14,y,w-nav-30,21).build();
            // A permanent time command still shows its progress here, but it is fired from its own
            // key and can never be bound to a quick slot, so it is not selectable.
            button.active=unlocked(data(),a)&&!a.dedicated;
            button.setTooltip(Tooltip.create(Component.literal(a.description)));
            addRenderableWidget(button);
        }

        // Two rows of four rather than eight narrow cells: a slot is only useful if it says, in words,
        // which ability answers to it.
        int columns=HexData.QUICK_SLOTS/2;
        slotWidth=Math.min(168,(w-36)/columns);
        barLeft=left+(w-slotWidth*columns)/2;
        barTop=top+h-66;
        for(int slot=0;slot<HexData.QUICK_SLOTS;slot++) {
            final int index=slot;
            Ability bound=Ability.slot(quickSlot(data(),slot));
            String name=bound==null?"Empty":bound.title;
            String label=(slot+1)+"  "+font.plainSubstrByWidth(name,slotWidth-26);
            Button button=Button.builder(Component.literal(label),b->{binding=binding==index?-1:index;rebuildWidgets();})
                .bounds(barLeft+slot%columns*slotWidth,barTop+slot/columns*24,slotWidth-4,22).build();
            button.setTooltip(Tooltip.create(Component.literal(binding==index?"Now pick an ability to bind to slot "+(index+1)+"."
                :bound==null?"Quick slot "+(index+1)+" — empty. Click, then pick an ability."
                :"Quick slot "+(index+1)+" — "+bound.title+". Click, then pick another ability to rebind it.")));
            addRenderableWidget(button);
        }
    }

    /** The ability list gives up whatever the two quick-slot rows need. */
    private int cardHeight(int entries) {return Math.min(48,(h-170)/Math.max(1,entries));}

    /** A coloured edge per slot: its discipline, the slot being bound, and the one selected now. */
    private void slotAccents(GuiGraphics g) {
        int columns=HexData.QUICK_SLOTS/2,selected=data().getInt("selected");
        for(int slot=0;slot<HexData.QUICK_SLOTS;slot++) {
            Ability bound=Ability.slot(quickSlot(data(),slot));
            int x=barLeft+slot%columns*slotWidth,y=barTop+slot/columns*24;
            int colour=binding==slot?0xffd8c27a:bound==null?0xff333d36:0xff000000|bound.discipline.color;
            g.fill(x,y,x+2,y+22,colour);
            if(bound!=null&&bound.ordinal()==selected)g.fill(x,y+20,x+slotWidth-4,y+22,0xff9fd8b4);
        }
    }

    /** The permanent key a dedicated command answers to, so the archive can name it. */
    static String key(Ability a) {
        int index=switch(a){case TIME_STOP->0;case REWIND->1;case TIME_BRANCH->2;default->-1;};
        return index<0?"":HexClient.TIME_KEYS[index].getTranslatedKeyMessage().getString();
    }

    private void choose(Ability a) {
        if(a.dedicated)return;
        if(binding>=0) {
            QuickBar.assign(binding,a.ordinal());
            binding=-1;
            HexNetwork.send(HexServer.SELECT,a.ordinal());
            rebuildWidgets();
            return;
        }
        HexNetwork.send(HexServer.SELECT,a.ordinal());
        if(quick)onClose();
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,0x9905080a);
        g.fill(left-1,top-1,left+w+1,top+h+1,0xff756447);
        g.fill(left,top,left+w,top+h,0xff0b1612);
        for(int y=top+2;y<top+h;y+=4)g.fill(left+1,y,left+w-1,y+1,0x14000000);
        g.drawString(font,"H E X   G O D   O F   S T O R I E S",left+14,top+12,0xb2a47e,false);
        g.drawString(font,"THE ARCHIVE",left+14,top+29,0xe4dfc7,false);
        g.drawString(font,"A story only you can write",left+14,top+44,0x719680,false);

        int nav=Math.max(108,w/4);
        g.fill(left+nav,top+63,left+nav+1,barTop-20,0xff394537);
        int master=mastery(data(),chapter);
        g.drawString(font,chapter.title.toUpperCase(Locale.ROOT)+"  /  "+master+" : 1000",left+nav+14,top+49,chapter.color,false);

        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter&&a!=Ability.SLOW_FIELD).toList();
        int card=cardHeight(list.size());
        int i=0;
        for(Ability a:list) {
            int y=top+70+i++*card;
            boolean open=unlocked(data(),a);
            String text=open
                ?(a.dedicated?"Key "+key(a)+"  |  ":"")+(a.cost>0?a.cost+" Temporal Energy":"Sorcery")
                    +"  |  "+a.cooldown/20f+"s recovery"+(a.hold?"  |  hold to shape":"")
                :"Mastery "+a.level+" required";
            g.drawString(font,text,left+nav+17,y+25,open?0x87a48b:0x8c8069,false);
        }

        g.fill(left+14,top+h-16,left+w-14,top+h-14,0xff293a2d);
        g.fill(left+14,top+h-16,left+14+(int)((w-28)*master/1000f),top+h-14,0xff000000|chapter.color);
        g.drawString(font,binding>=0?"BINDING SLOT "+(binding+1)+" — PICK AN ABILITY":"QUICK SLOTS",left+14,barTop-14,binding>=0?0xd8c27a:0x6d8672,false);
        slotAccents(g);
        super.render(g,mx,my,partial);
    }
}
