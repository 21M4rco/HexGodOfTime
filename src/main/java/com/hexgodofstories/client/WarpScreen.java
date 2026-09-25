package com.hexgodofstories.client;

import com.hexgodofstories.warping.Destination;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.server.HexServer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/** Compact lower-left selector; the battlefield remains visible. */
public final class WarpScreen extends Screen {
    /** Every other realm is a hazard. This one is a creature, so it is named in its own colour. */
    private static final int ALARM = 0xffff4a3a;

    private int x,y,w,row;
    public WarpScreen(){super(Component.literal("Warping"));}

    private int bx(Destination d){return x+6+(d.ordinal()%2)*(w-12)/2;}
    private int by(Destination d){return y+25+(d.ordinal()/2)*row;}
    private int bw(){return (w-16)/2;}

    @Override protected void init(){
        x=8;w=Math.min(282,width-16);row=21;y=Math.max(8,height-196);
        for(Destination d:Destination.values()){
            Component label=d.lethal()
                ? Component.literal(d.title).withStyle(s->s.withColor(TextColor.fromRgb(ALARM&0xffffff)).withBold(true))
                : Component.literal(d.title);
            addRenderableWidget(Button.builder(label,button->{HexNetwork.send(HexServer.WARP_CHOICE,d.ordinal());onClose();})
                .bounds(bx(d),by(d),bw(),19).build());
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(x,y,x+w,Math.min(height-4,y+188),0xe00a0710);g.fill(x,y,x+2,Math.min(height-4,y+188),0xffb28ade);
        g.drawString(font,"WARPING  /  CHOOSE A DESTINATION",x+9,y+8,0xe6d4ff,false);
        super.render(g,mx,my,partial);
        // A red frame around the one destination that hunts back, drawn over the button so no
        // widget state can wash it out.
        for(Destination d:Destination.values()){
            if(!d.lethal())continue;
            int l=bx(d),t=by(d),r=l+bw(),b=t+19;
            g.fill(l,t,r,t+1,ALARM);g.fill(l,b-1,r,b,ALARM);
            g.fill(l,t,l+1,b,ALARM);g.fill(r-1,t,r,b,ALARM);
        }
        for(Destination d:Destination.values())if(mx>=bx(d)&&mx<bx(d)+bw()&&my>=by(d)&&my<by(d)+19)g.renderTooltip(font,font.split(Component.literal(d.description),220),mx,my);
        g.drawString(font,"VOID SEA: a cosmic sea god hunts it. It cannot be killed.",x+9,y+146,ALARM,false);
        g.drawString(font,"Hold R: spread pool   Y: pull normal realm creatures to you",x+9,y+159,0xb2a5bc,false);
        g.drawString(font,"WORLD TREE / WARP REALMS: G chooses exit   R leaves",x+9,y+172,0xb2a5bc,false);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(HexClient.SECONDARY.matches(key,scan)){onClose();return true;}return super.keyPressed(key,scan,modifiers);}
}
