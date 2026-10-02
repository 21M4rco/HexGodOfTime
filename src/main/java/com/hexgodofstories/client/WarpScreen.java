package com.hexgodofstories.client;

import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import com.hexgodofstories.warping.Destination;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Where a Warping pool leads. Every destination is a card in two columns, numbered 1 to 0 so the keys pick one without
 * the mouse; the one in use is framed in gold, the Void Sea is marked in red because its danger is a creature, and the
 * card under the mouse (or the one in use) is described in full underneath. Picking closes the screen; so does G, the
 * key that opened it.
 */
public final class WarpScreen extends Screen {
    private static final int CARD_H=26,GAP=4,ALARM=HexUi.RED;
    private int left,top,w,h,cardW,gridTop,detailTop;

    public WarpScreen(){super(Component.literal("Warping"));}

    @Override public boolean isPauseScreen(){return false;}

    private static Destination[] all(){return Destination.values();}
    private int rows(){return (all().length+1)/2;}

    @Override protected void init(){
        w=Math.min(400,width-16);
        cardW=(w-24-GAP)/2;
        h=Math.min(height-16,36+rows()*(CARD_H+GAP)+62);
        left=(width-w)/2;top=(height-h)/2;
        gridTop=top+34;
        detailTop=gridTop+rows()*(CARD_H+GAP)+4;
    }

    private int cardX(int i){return left+12+(i%2)*(cardW+GAP);}
    private int cardY(int i){return gridTop+(i/2)*(CARD_H+GAP);}

    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        HexUi.dim(g,width,height);
        HexUi.panel(g,left,top,w,h,0xb28ade);
        g.drawString(font,"WARPING",left+12,top+10,0xe6d4ff,false);
        g.drawString(font,"Choose where the pool leads",left+12,top+21,HexUi.DIM,false);
        Destination now=Destination.at(ClientState.self().getInt("warpDestination"));
        String chosen=HexUi.fit(font,"NOW: "+now.title,w/2-16);
        g.drawString(font,chosen,left+w-12-font.width(chosen),top+15,HexUi.GOLD,false);

        Destination hovered=null;
        for(int i=0;i<all().length;i++){
            Destination d=all()[i];
            int x=cardX(i),y=cardY(i);
            boolean over=HexUi.inside(mx,my,x,y,cardW,CARD_H);
            if(over)hovered=d;
            HexUi.card(g,x,y,cardW,CARD_H,over,d==now,d.lethal()?ALARM:d.color);
            String number=String.valueOf((i+1)%10);
            int right=x+cardW-5;
            right-=HexUi.tag(g,font,number,right,y+8,0xff1d2c25,HexUi.MUTED)+4;
            if(d.lethal())right-=HexUi.tag(g,font,"HUNTED",right,y+8,0xff4a1712,ALARM)+4;
            int textW=right-(x+8);
            g.drawString(font,HexUi.fit(font,d.title,textW),x+8,y+4,d.lethal()?ALARM:HexUi.TEXT,false);
            g.drawString(font,HexUi.fit(font,firstSentence(d.description),textW),x+8,y+14,HexUi.MUTED,false);
        }

        // The card under the mouse, or the one in use, in full.
        Destination shown=hovered!=null?hovered:now;
        int footer=top+h-14;
        g.fill(left+12,detailTop,left+w-12,detailTop+1,0xff1f3329);
        g.drawString(font,shown.title.toUpperCase(java.util.Locale.ROOT),left+12,detailTop+5,shown.lethal()?ALARM:HexUi.GOLD,false);
        HexUi.wrap(g,font,shown.description,left+12,detailTop+16,w-24,Math.max(1,(footer-detailTop-20)/10),0xb9c9bf);
        g.drawString(font,HexUi.fit(font,"1–0 or click to choose  ·  hold R to spread the pool  ·  Y pulls creatures out",w-24),
            left+12,footer,HexUi.DIM,false);
    }

    /** Up to the first full stop, which is what fits on a card. */
    private static String firstSentence(String text){
        int stop=text.indexOf(". ");
        return stop<0?text:text.substring(0,stop+1);
    }

    private void choose(Destination d){
        if(minecraft!=null)minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK,1));
        HexNetwork.send(HexServer.WARP_CHOICE,d.ordinal());
        ClientState.self().putInt("warpDestination",d.ordinal());
        onClose();
    }

    @Override public boolean mouseClicked(double mx,double my,int button){
        if(button==0)for(int i=0;i<all().length;i++)
            if(HexUi.inside(mx,my,cardX(i),cardY(i),cardW,CARD_H)){choose(all()[i]);return true;}
        return super.mouseClicked(mx,my,button);
    }

    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(HexClient.SECONDARY.matches(key,scan)){onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_9){int i=key-GLFW.GLFW_KEY_1;if(i<all().length){choose(all()[i]);return true;}}
        if(key==GLFW.GLFW_KEY_0&&all().length>=10){choose(all()[9]);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
