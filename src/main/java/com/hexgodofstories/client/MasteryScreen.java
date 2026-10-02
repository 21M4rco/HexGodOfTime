package com.hexgodofstories.client;

import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import java.util.*;

/**
 * The Book of Stories: every ability, by discipline, and the seven bind slots drawn as the keys they are.
 *
 * <p>Binding works either way round. Click an ability and then a key (or just press the key itself, Z to M, while the
 * archive is open); or click a key and then an ability. A binding shows at once: the archive keeps its own copy of
 * the slots up to date the moment it asks the server for the change, rather than waiting a round trip to redraw, so
 * a slot never shows the ability it held before.
 *
 * <p>Everything is drawn here, row by row, rather than built out of stock buttons, so nothing overlaps: rows are as
 * tall as what they say, the ability under the mouse (or the one picked) is described in full in its own column, and
 * the list scrolls when a small window cannot hold it.
 */
public final class MasteryScreen extends Screen {
    private static final int ROW=30,NAV_ROW=24,GOLD=0xd8b86a,INK=0xe6efe8,MUTED=0x86a191,DIM=0x5d6f64,EDGE=0xff2d3f33;

    private Discipline chapter=Discipline.MISCHIEF;
    /** The ability picked in the list: the next key clicked or pressed binds it. */
    private Ability picked;
    /** A key clicked first: the next ability clicked is bound to it. */
    private int pending=-1;
    /** The slot just bound, and when, for a moment's glow on its key. */
    private int flashed=-1;
    private long flashedAt;
    private double scroll;

    private int left,top,w,h,navW,listX,listY,listW,listH,detailX,detailW,keysY,keyW,keysX;

    public MasteryScreen(boolean quick) {
        super(Component.literal("The Book of Stories"));
        var p=Minecraft.getInstance().player;
        if(p!=null)chapter=Ability.at(ClientState.data(p.getId()).getInt("selected")).discipline;
    }
    @Override public boolean isPauseScreen(){return false;}

    private CompoundTag data(){return minecraft==null||minecraft.player==null?new CompoundTag():ClientState.data(minecraft.player.getId());}
    static int mastery(CompoundTag n,Discipline d){return MasteryCurve.levelForXp(n.getLong("xp_"+d.name()));}
    static boolean unlocked(CompoundTag n,Ability a) {
        if(a==Ability.SLOW_FIELD)return false;
        if(n.getBoolean("unlock_"+a.name()))return true;
        if(a==Ability.ARSENAL&&!unlocked(n,Ability.LAEVATEINN))return false;
        int sum=0;
        for(Discipline d:Discipline.values())if(d.ordinal()<4)sum+=mastery(n,d);
        return mastery(n,a.discipline)>=a.level
            &&(a.discipline!=Discipline.TEMPORAL||sum>=600)
            &&(a.discipline!=Discipline.PURPOSE||mastery(n,Discipline.TEMPORAL)>=800);
    }
    /** The permanent key a dedicated command answers to, so the archive can name it. */
    static String key(Ability a) {
        int index=switch(a){case TIME_STOP->0;case REWIND->1;case TIME_BRANCH->2;default->-1;};
        return index<0?"":QuickBar.shortKey(HexClient.TIME_KEYS[index].getTranslatedKeyMessage().getString());
    }
    /** The abilities of a chapter that are shown: never the two retired ones. */
    private static List<Ability> abilities(Discipline d) {
        List<Ability> list=new ArrayList<>();
        for(Ability a:Ability.values())if(a.discipline==d&&a!=Ability.RIFT&&a!=Ability.SLOW_FIELD)list.add(a);
        return list;
    }

    @Override protected void init() {
        w=Math.min(680,width-16);h=Math.min(410,height-16);
        left=(width-w)/2;top=(height-h)/2;
        navW=Math.max(96,Math.min(136,w/5));
        detailW=w>=560?Math.min(230,w/3):0;
        keysY=top+h-58;
        listX=left+navW+10;listY=top+46;
        listW=w-navW-20-(detailW>0?detailW+10:0);
        listH=keysY-18-listY;
        detailX=listX+listW+10;
        keyW=Math.min(86,(w-24)/HexData.QUICK_SLOTS);
        keysX=left+(w-keyW*HexData.QUICK_SLOTS)/2;
        clampScroll();
    }

    private void clampScroll() {
        int content=abilities(chapter).size()*ROW;
        scroll=Mth.clamp(scroll,0,Math.max(0,content-listH));
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        HexUi.dim(g,width,height);
        HexUi.panel(g,left,top,w,h,chapter.color);
        CompoundTag data=data();
        // The header: whose book this is, and how far into the chapter shown.
        g.drawString(font,"THE ARCHIVE",left+12,top+10,INK,false);
        g.drawString(font,"Hex God of Stories",left+12,top+22,DIM,false);
        int master=mastery(data,chapter);
        String head=chapter.title.toUpperCase(Locale.ROOT)+"   "+master+" / 1000";
        g.drawString(font,head,listX,top+16,chapter.color,false);
        int barX=listX+font.width(head)+10,barEnd=left+w-12;
        if(barEnd-barX>30) {
            g.fill(barX,top+19,barEnd,top+21,0xff21302a);
            g.fill(barX,top+19,barX+(int)((barEnd-barX)*Math.min(1,master/1000f)),top+21,0xff000000|chapter.color);
        }
        g.fill(left+navW,top+40,left+navW+1,keysY-10,EDGE);

        nav(g,data,mx,my);
        Ability hovered=list(g,data,mx,my);
        if(detailW>0)detail(g,data,hovered!=null?hovered:picked!=null?picked:Ability.slot(data.getInt("selected")));
        keys(g,data,mx,my);
    }

    private void nav(GuiGraphics g,CompoundTag data,int mx,int my) {
        int y=top+46;
        for(Discipline d:Discipline.values()) {
            boolean here=d==chapter,over=inside(mx,my,left+6,y,navW-12,NAV_ROW-3);
            HexUi.card(g,left+6,y,navW-12,NAV_ROW-3,over,here,here?d.color:0x26352d);
            g.drawString(font,HexUi.fit(font,d.title,navW-46),left+13,y+7,here?INK:over?0xbfd0c4:MUTED,false);
            String m=String.valueOf(mastery(data,d));
            g.drawString(font,m,left+navW-9-font.width(m),y+7,here?d.color:DIM,false);
            y+=NAV_ROW;
        }
    }

    /** The chapter's abilities, one row each. @return the one under the mouse, if any. */
    private Ability list(GuiGraphics g,CompoundTag data,int mx,int my) {
        List<Ability> list=abilities(chapter);
        Ability hovered=null;
        int selected=data.getInt("selected");
        boolean ascended=data.getBoolean("ascended");
        g.enableScissor(listX,listY,listX+listW,listY+listH);
        for(int i=0;i<list.size();i++) {
            Ability a=list.get(i);
            int y=listY+i*ROW-(int)scroll;
            if(y+ROW<listY||y>listY+listH)continue;
            boolean open=unlocked(data,a),over=inside(mx,my,listX,y,listW,ROW-3)&&my>=listY&&my<listY+listH;
            if(over)hovered=a;
            boolean isPicked=a==picked,isSelected=a.ordinal()==selected;
            HexUi.card(g,listX,y,listW,ROW-3,over,isPicked,open?a.discipline.color:0x2b3530);
            String name=ascended&&open&&Ascended.changes(a)?Ascended.title(a):a.title;
            g.drawString(font,HexUi.fit(font,name,listW-80),listX+8,y+5,open?(ascended&&Ascended.changes(a)?0xf0d58a:INK):DIM,false);
            String info=!open?"Needs "+a.discipline.title+" "+a.level
                :(a.cost>0?a.cost+" energy  ·  ":"")+fmt(a.cooldown)+" recovery"+(a.hold?"  ·  hold":"");
            g.drawString(font,HexUi.fit(font,info,listW-74),listX+8,y+16,open?MUTED:0x6e6a58,false);
            // On the right: the key it answers to, and whether the mantle changes it.
            int right=listX+listW-6;
            String tag=a.dedicated?key(a):boundKey(data,a);
            if(!tag.isEmpty()) {
                int tw=font.width(tag)+8;
                g.fill(right-tw,y+5,right,y+17,a.dedicated?0xff3a3322:0xff26402f);
                g.drawString(font,tag,right-tw+4,y+7,a.dedicated?GOLD:0xd7f0de,false);
                right-=tw+4;
            }
            if(isSelected&&open)g.drawString(font,"●",right-7,y+7,0x9fd8b4,false);
            if(open&&Ascended.changes(a))g.drawString(font,"✦",right-7,y+18,GOLD,false);
        }
        g.disableScissor();
        // A thin scroll bar when the chapter does not fit.
        int content=list.size()*ROW;
        if(content>listH) {
            int bar=Math.max(16,listH*listH/content),at=listY+(int)((listH-bar)*(scroll/(content-listH)));
            g.fill(listX+listW+2,at,listX+listW+4,at+bar,0xff3c5246);
        }
        return hovered;
    }

    /** Everything the archive knows about one ability: what it does, and what the mantle makes of it. */
    private void detail(GuiGraphics g,CompoundTag data,Ability a) {
        int x=detailX,y=listY,width=detailW;
        HexUi.rounded(g,x,y,width,keysY-10-y,0xff0e1814);
        HexUi.outline(g,x,y,width,keysY-10-y,HexUi.BORDER);
        if(a==null){g.drawString(font,"Point at an ability.",x+8,y+8,DIM,false);return;}
        boolean open=unlocked(data,a);
        g.fill(x,y,x+width,y+2,0xff000000|a.discipline.color);
        y+=8;
        for(FormattedCharSequence line:font.split(Component.literal(a.title),width-16)){g.drawString(font,line,x+8,y,INK,false);y+=11;}
        String status=!open?"Locked: "+a.discipline.title+" "+a.level
            :a.dedicated?"Its own key: "+key(a):boundKey(data,a).isEmpty()?"Not bound to a key":"Bound to "+boundKey(data,a);
        g.drawString(font,HexUi.fit(font,status,width-16),x+8,y,open?0x9fd8b4:0x8c8069,false);
        y+=14;
        int bottom=keysY-14;
        y=wrap(g,a.description,x+8,y,width-16,bottom,0xb6c8bc);
        if(Ascended.changes(a)&&y<bottom-20) {
            y+=5;
            g.fill(x+8,y,x+width-8,y+1,0x806d5e42);
            y+=5;
            g.drawString(font,"✦ TRANSFORMED — "+Ascended.title(a).toUpperCase(Locale.ROOT),x+8,y,GOLD,false);
            y+=12;
            wrap(g,Ascended.text(a),x+8,y,width-16,bottom,0xd9c690);
        }
    }

    private int wrap(GuiGraphics g,String text,int x,int y,int width,int bottom,int colour) {
        for(FormattedCharSequence line:font.split(Component.literal(text),width)) {
            if(y+9>bottom)break;
            g.drawString(font,line,x,y,colour,false);
            y+=10;
        }
        return y;
    }

    /** The seven slots, as keys: the letter, what is bound there, and its discipline along the bottom. */
    private void keys(GuiGraphics g,CompoundTag data,int mx,int my) {
        String hint=pending>=0?"Now click the ability to bind to "+QuickBar.key(pending)+".   (Esc or click the key again to stop.)"
            :picked!=null&&!picked.dedicated&&unlocked(data,picked)?"Click a key below, or press it, to bind "+picked.title+"."
            :"Pick an ability, then a key — or a key, then an ability. In game, a key casts what is bound to it.";
        g.drawString(font,HexUi.fit(font,hint,w-24),left+12,keysY-12,pending>=0||picked!=null?GOLD:DIM,false);
        long now=ClientState.now();
        for(int i=0;i<HexData.QUICK_SLOTS;i++) {
            int x=keysX+i*keyW+3,width=keyW-6,y=keysY+4,tall=44;
            Ability a=Ability.slot(QuickBar.slot(data,i));
            boolean over=inside(mx,my,x,y,width,tall),waiting=pending==i;
            float glow=flashed==i?Math.max(0,1-(now-flashedAt)/14f):0;
            // A keycap: framed like every card, its discipline along the bottom rather than the side.
            HexUi.rounded(g,x,y,width,tall,waiting?0xff2c2818:over?HexUi.CARD_HOVER:HexUi.CARD);
            HexUi.outline(g,x,y,width,tall,waiting?0xff000000|GOLD:over?HexUi.BORDER_HOVER:HexUi.BORDER);
            g.fill(x+2,y+tall-3,x+width-2,y+tall-1,a==null?0xff26302a:0xff000000|a.discipline.color);
            if(glow>0)HexUi.rounded(g,x,y,width,tall,((int)(glow*90)<<24)|0x7dffb0);
            String key=QuickBar.shortKey(QuickBar.key(i));
            g.pose().pushPose();
            g.pose().translate(x+6,y+5,0);
            g.pose().scale(1.5f,1.5f,1);
            g.drawString(font,font.plainSubstrByWidth(key,(int)((width-10)/1.5f)),0,0,a==null?DIM:INK,false);
            g.pose().popPose();
            String name=a==null?"empty":a.title;
            g.drawString(font,HexUi.fit(font,name,width-10),x+5,y+tall-15,a==null?DIM:waiting?GOLD:0xbdd6c6,false);
        }
    }

    private String boundKey(CompoundTag data,Ability a) {
        int slot=QuickBar.slotOf(data,a);
        return slot<0?"":QuickBar.shortKey(QuickBar.key(slot));
    }

    private static String fmt(int ticks) {return ticks%20==0?ticks/20+"s":String.format(Locale.ROOT,"%.1fs",ticks/20f);}
    private static boolean inside(double mx,double my,int x,int y,int w,int h) {return mx>=x&&mx<x+w&&my>=y&&my<y+h;}

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(button!=0)return super.mouseClicked(mx,my,button);
        CompoundTag data=data();
        int y=top+46;
        for(Discipline d:Discipline.values()) {
            if(inside(mx,my,left+6,y,navW-12,NAV_ROW-3)){if(chapter!=d){chapter=d;scroll=0;clampScroll();}click();return true;}
            y+=NAV_ROW;
        }
        List<Ability> list=abilities(chapter);
        if(inside(mx,my,listX,listY,listW,listH)) {
            int index=(int)((my-listY+scroll)/ROW);
            if(index>=0&&index<list.size()&&(my-listY+scroll)-index*ROW<ROW-3) {
                Ability a=list.get(index);
                click();
                if(!unlocked(data,a)||a.dedicated){picked=a;pending=-1;return true;}
                if(pending>=0){bind(pending,a);return true;}
                picked=picked==a?null:a;
                // It is also the chosen ability, as choosing one from the archive always was.
                HexNetwork.send(HexServer.SELECT,a.ordinal());
                data.putInt("selected",a.ordinal());
                return true;
            }
        }
        for(int i=0;i<HexData.QUICK_SLOTS;i++) {
            int x=keysX+i*keyW+3;
            if(!inside(mx,my,x,keysY+4,keyW-6,44))continue;
            click();
            if(picked!=null&&!picked.dedicated&&unlocked(data,picked))bind(i,picked);
            else pending=pending==i?-1:i;
            return true;
        }
        return super.mouseClicked(mx,my,button);
    }

    @Override public boolean mouseScrolled(double mx,double my,double delta) {
        if(inside(mx,my,listX,listY,listW+6,listH)){scroll-=delta*ROW;clampScroll();return true;}
        return super.mouseScrolled(mx,my,delta);
    }

    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        // The real key binds: with an ability picked, pressing Z to M puts it there.
        for(int i=0;i<HexData.QUICK_SLOTS;i++)
            if(HexClient.SLOTS[i].matches(key,scan)&&picked!=null&&!picked.dedicated&&unlocked(data(),picked)){bind(i,picked);click();return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE&&(pending>=0||picked!=null)){pending=-1;picked=null;return true;}
        if(HexClient.MENU.matches(key,scan)){onClose();return true;}
        return super.keyPressed(key,scan,modifiers);
    }

    /**
     * Binds an ability to a slot, here and on the server. The archive's own copy of the slots changes at once, the way
     * the server will change its own (an ability lives in one slot only), so the key shows its new ability this frame.
     */
    private void bind(int slot,Ability a) {
        CompoundTag data=data();
        QuickBar.assign(slot,a.ordinal());
        int[] slots=data.getIntArray("quick");
        if(slots.length==HexData.QUICK_SLOTS) {
            slots=slots.clone();
            for(int i=0;i<slots.length;i++)if(slots[i]==a.ordinal())slots[i]=-1;
            slots[slot]=a.ordinal();
            data.putIntArray("quick",slots);
        }
        flashed=slot;flashedAt=ClientState.now();
        pending=-1;picked=null;
    }

    private void click() {
        if(minecraft!=null)minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK,1));
    }
}
