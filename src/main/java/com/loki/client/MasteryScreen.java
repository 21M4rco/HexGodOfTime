package com.loki.client;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.loki.server.LokiServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * The Book of Glorious Purpose. Choosing an ability selects it; choosing a quick slot first binds it
 * there instead, which is the only assignment gesture in the mod.
 */
public final class MasteryScreen extends Screen {
    private Discipline chapter=Discipline.MISCHIEF;
    private final boolean quick;
    private int left,top,w,h,binding=-1;

    public MasteryScreen(boolean quick) {
        super(Component.literal(quick?"Spell selection":"The Book of Glorious Purpose"));
        this.quick=quick;
        var p=Minecraft.getInstance().player;
        if(p!=null)chapter=Ability.at(ClientState.data(p.getId()).getInt("selected")).discipline;
    }
    @Override public boolean isPauseScreen(){return false;}
    private CompoundTag data(){return minecraft==null||minecraft.player==null?new CompoundTag():ClientState.data(minecraft.player.getId());}
    static int mastery(CompoundTag n,Discipline d){return MasteryCurve.levelForXp(n.getLong("xp_"+d.name()));}
    static boolean unlocked(CompoundTag n,Ability a) {
        if(n.getBoolean("unlock_"+a.name()))return true;
        int sum=0;
        for(Discipline d:Discipline.values())if(d.ordinal()<4)sum+=mastery(n,d);
        return mastery(n,a.discipline)>=a.level
            &&(a.discipline!=Discipline.TEMPORAL||sum>=600)
            &&(a.discipline!=Discipline.PURPOSE||mastery(n,Discipline.TEMPORAL)>=800);
    }
    private static int quickSlot(CompoundTag n,int index) {
        int[] slots=n.getIntArray("quick");
        return slots.length==LokiData.QUICK_SLOTS&&index>=0&&index<slots.length?slots[index]:-1;
    }

    @Override protected void init() {
        w=Math.min(650,width-24);h=Math.min(392,height-24);
        left=(width-w)/2;top=(height-h)/2;
        int nav=Math.max(108,w/4);
        for(Discipline d:Discipline.values())
            addRenderableWidget(Button.builder(Component.literal(d.title),b->{chapter=d;rebuildWidgets();})
                .bounds(left+12,top+69+d.ordinal()*29,nav-20,23).build());

        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter).toList();
        int card=Math.min(48,(h-136)/Math.max(1,list.size()));
        int i=0;
        for(Ability a:list) {
            int y=top+70+i++*card;
            Button button=Button.builder(Component.literal(a.title),b->choose(a))
                .bounds(left+nav+14,y,w-nav-30,21).build();
            button.active=unlocked(data(),a);
            button.setTooltip(Tooltip.create(Component.literal(a.description)));
            addRenderableWidget(button);
        }

        int slotWidth=Math.min(46,(w-40)/LokiData.QUICK_SLOTS);
        int barLeft=left+(w-slotWidth*LokiData.QUICK_SLOTS)/2;
        for(int slot=0;slot<LokiData.QUICK_SLOTS;slot++) {
            final int index=slot;
            int ability=quickSlot(data(),slot);
            String label=ability<0?"—":QuickBar.abbreviation(Ability.at(ability));
            Button button=Button.builder(Component.literal(label),b->{binding=binding==index?-1:index;rebuildWidgets();})
                .bounds(barLeft+slot*slotWidth,top+h-40,slotWidth-3,20).build();
            button.setTooltip(Tooltip.create(Component.literal(
                binding==index?"Now pick an ability to bind here.":"Quick slot "+(index+1)+" — click, then pick an ability.")));
            addRenderableWidget(button);
        }
    }

    private void choose(Ability a) {
        if(binding>=0) {
            QuickBar.assign(binding,a.ordinal());
            binding=-1;
            LokiNetwork.send(LokiServer.SELECT,a.ordinal());
            rebuildWidgets();
            return;
        }
        LokiNetwork.send(LokiServer.SELECT,a.ordinal());
        if(quick)onClose();
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,0x9905080a);
        g.fill(left-1,top-1,left+w+1,top+h+1,0xff756447);
        g.fill(left,top,left+w,top+h,0xff0b1612);
        for(int y=top+2;y<top+h;y+=4)g.fill(left+1,y,left+w-1,y+1,0x14000000);
        g.drawString(font,"T V A   /   VARIANT ARCHIVE",left+14,top+12,0xb2a47e,false);
        g.drawString(font,"GLORIOUS PURPOSE",left+14,top+29,0xe4dfc7,false);
        g.drawString(font,"A story only you can write",left+14,top+44,0x719680,false);

        int nav=Math.max(108,w/4);
        g.fill(left+nav,top+63,left+nav+1,top+h-52,0xff394537);
        int master=mastery(data(),chapter);
        g.drawString(font,chapter.title.toUpperCase(Locale.ROOT)+"  /  "+master+" : 1000",left+nav+14,top+49,chapter.color,false);

        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter).toList();
        int card=Math.min(48,(h-136)/Math.max(1,list.size()));
        int i=0;
        for(Ability a:list) {
            int y=top+70+i++*card;
            boolean open=unlocked(data(),a);
            String text=open
                ?(a.cost>0?a.cost+" Temporal Energy":"Sorcery")+"  |  "+a.cooldown/20f+"s recovery"+(a.hold?"  |  hold to shape":"")
                :"Mastery "+a.level+" required";
            g.drawString(font,text,left+nav+17,y+25,open?0x87a48b:0x8c8069,false);
        }

        g.fill(left+14,top+h-16,left+w-14,top+h-14,0xff293a2d);
        g.fill(left+14,top+h-16,left+14+(int)((w-28)*master/1000f),top+h-14,0xff000000|chapter.color);
        g.drawString(font,binding>=0?"BINDING SLOT "+(binding+1):"QUICK SLOTS",left+14,top+h-52,binding>=0?0xd8c27a:0x6d8672,false);
        super.render(g,mx,my,partial);
    }
}
