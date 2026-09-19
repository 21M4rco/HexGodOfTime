package com.loki.client;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class MasteryScreen extends Screen {
    private Discipline chapter=Discipline.MISCHIEF;
    private final boolean quick;
    private int left,top,w,h;
    public MasteryScreen(boolean quick){super(Component.literal(quick?"Spell selection":"The Book of Glorious Purpose"));this.quick=quick;var p=Minecraft.getInstance().player;if(p!=null)chapter=Ability.at(ClientState.data(p.getId()).getInt("selected")).discipline;}
    @Override public boolean isPauseScreen(){return false;}
    private CompoundTag data(){return minecraft.player==null?new CompoundTag():ClientState.data(minecraft.player.getId());}
    static int mastery(CompoundTag n,Discipline d){return MasteryCurve.levelForXp(n.getLong("xp_"+d.name()));}
    static boolean unlocked(CompoundTag n,Ability a){if(n.getBoolean("unlock_"+a.name()))return true;int sum=0;for(Discipline d:Discipline.values())if(d.ordinal()<4)sum+=mastery(n,d);return mastery(n,a.discipline)>=a.level&&(a.discipline!=Discipline.TEMPORAL||sum>=600)&&(a.discipline!=Discipline.PURPOSE||mastery(n,Discipline.TEMPORAL)>=800);}
    @Override protected void init(){w=Math.min(650,width-24);h=Math.min(380,height-24);left=(width-w)/2;top=(height-h)/2;int nav=Math.max(108,w/4);
        for(Discipline d:Discipline.values())addRenderableWidget(Button.builder(Component.literal(d.title),b->{chapter=d;rebuildWidgets();}).bounds(left+12,top+69+d.ordinal()*29,nav-20,23).build());
        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter).toList();int card=Math.min(48,(h-100)/Math.max(1,list.size()));int i=0;
        for(Ability a:list){int y=top+70+i++*card;Button button=Button.builder(Component.literal(a.title),b->{LokiNetwork.send(5,a.ordinal());if(quick)onClose();}).bounds(left+nav+14,y,w-nav-30,21).build();button.active=unlocked(data(),a);button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(a.description)));addRenderableWidget(button);}
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){g.fill(0,0,width,height,0x9905080a);g.fill(left-1,top-1,left+w+1,top+h+1,0xff756447);g.fill(left,top,left+w,top+h,0xff0b1612);
        for(int y=top+2;y<top+h;y+=4)g.fill(left+1,y,left+w-1,y+1,0x14000000);
        g.drawString(font,"T V A   /   VARIANT ARCHIVE",left+14,top+12,0xb2a47e,false);g.drawString(font,"GLORIOUS PURPOSE",left+14,top+29,0xe4dfc7,false);g.drawString(font,"A story only you can write",left+14,top+44,0x719680,false);
        int nav=Math.max(108,w/4);g.fill(left+nav,top+63,left+nav+1,top+h-24,0xff394537);int master=mastery(data(),chapter);
        g.drawString(font,chapter.title.toUpperCase()+"  /  "+master+" : 1000",left+nav+14,top+49,chapter.color,false);
        var list=Arrays.stream(Ability.values()).filter(a->a.discipline==chapter).toList();int card=Math.min(48,(h-100)/Math.max(1,list.size()));int i=0;
        for(Ability a:list){int y=top+70+i++*card;int color=unlocked(data(),a)?0x87a48b:0x8c8069;String text=unlocked(data(),a)?(a.cost>0?a.cost+" Temporal Energy":"Sorcery")+"  |  "+a.cooldown/20f+"s recovery":"Mastery "+a.level+" required";g.drawString(font,text,left+nav+17,y+25,color,false);}
        g.fill(left+14,top+h-16,left+w-14,top+h-14,0xff293a2d);g.fill(left+14,top+h-16,left+14+(int)((w-28)*master/1000f),top+h-14,0xff000000|chapter.color);
        super.render(g,mx,my,partial);
    }
}
