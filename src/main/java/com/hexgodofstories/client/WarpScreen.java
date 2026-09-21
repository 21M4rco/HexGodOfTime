package com.hexgodofstories.client;

import com.hexgodofstories.warping.Destination;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact lower-left selector; the battlefield remains visible. */
public final class WarpScreen extends Screen {
    private int x,y,w;
    public WarpScreen(){super(Component.literal("Warping"));}
    @Override protected void init(){
        x=8;w=Math.min(250,width-16);y=Math.max(8,height-258);
        for(Destination d:Destination.values())addRenderableWidget(Button.builder(Component.literal(d.title),button->{HexNetwork.send(HexServer.WARP_CHOICE,d.ordinal());onClose();}).bounds(x+6,y+25+d.ordinal()*21,w-12,19).build());
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(x,y,x+w,Math.min(height-4,y+250),0xe00a0710);g.fill(x,y,x+2,Math.min(height-4,y+250),0xffb28ade);
        g.drawString(font,"WARPING  /  CHOOSE A DESTINATION",x+9,y+8,0xe6d4ff,false);
        super.render(g,mx,my,partial);
        for(Destination d:Destination.values())if(mx>=x+6&&mx<=x+w-6&&my>=y+25+d.ordinal()*21&&my<y+44+d.ordinal()*21)g.renderTooltip(font,font.split(Component.literal(d.description),220),mx,my);
        g.drawString(font,"Hold R: fracture   X: control / follow",x+9,y+222,0xb2a5bc,false);
        g.drawString(font,"Inside a realm: R opens the way home",x+9,y+235,0xb2a5bc,false);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(HexClient.SECONDARY.matches(key,scan)){onClose();return true;}return super.keyPressed(key,scan,modifiers);}
}
