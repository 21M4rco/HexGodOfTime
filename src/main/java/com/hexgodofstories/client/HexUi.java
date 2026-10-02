package com.hexgodofstories.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The one look every screen of the mod shares: a near-black green panel with a thin lit frame, cards with their
 * corners cut and a coloured edge, gold for what is chosen, and text that is always fitted or wrapped to the box it is
 * in, never left to run out of it. Nothing here is a stock button.
 */
public final class HexUi {
    private HexUi() {}

    public static final int PANEL=0xf20a1310,FRAME=0xff2e4a3b,CARD=0xff111d17,CARD_HOVER=0xff182a21,CARD_ACTIVE=0xff1c3627,
        BORDER=0xff26392f,BORDER_HOVER=0xff4c7a60;
    public static final int GOLD=0xd8b86a,GREEN=0x74d8a6,TEXT=0xe8f1ea,MUTED=0x93ad9d,DIM=0x5f7568,RED=0xff5a48;

    /** The world behind a screen, darkened just enough that the panel reads. */
    public static void dim(GuiGraphics g,int width,int height) {g.fill(0,0,width,height,0x9c030706);}

    /** A box with its four corners cut by a pixel: square enough to be Minecraft, soft enough not to be a button. */
    public static void rounded(GuiGraphics g,int x,int y,int w,int h,int colour) {
        g.fill(x+1,y,x+w-1,y+h,colour);
        g.fill(x,y+1,x+1,y+h-1,colour);
        g.fill(x+w-1,y+1,x+w,y+h-1,colour);
    }

    /** The same box's outline. */
    public static void outline(GuiGraphics g,int x,int y,int w,int h,int colour) {
        g.fill(x+1,y,x+w-1,y+1,colour);
        g.fill(x+1,y+h-1,x+w-1,y+h,colour);
        g.fill(x,y+1,x+1,y+h-1,colour);
        g.fill(x+w-1,y+1,x+w,y+h-1,colour);
    }

    /** A screen's panel: its frame, its body, and a line of the screen's own colour along the top. */
    public static void panel(GuiGraphics g,int x,int y,int w,int h,int accent) {
        rounded(g,x-1,y-1,w+2,h+2,FRAME);
        rounded(g,x,y,w,h,PANEL);
        g.fill(x+2,y,x+w-2,y+1,0xff000000|accent);
    }

    /** A card: lighter under the mouse, framed in gold when it is the chosen one, its colour along its left edge. */
    public static void card(GuiGraphics g,int x,int y,int w,int h,boolean hover,boolean active,int accent) {
        rounded(g,x,y,w,h,active?CARD_ACTIVE:hover?CARD_HOVER:CARD);
        outline(g,x,y,w,h,active?0xff000000|GOLD:hover?BORDER_HOVER:BORDER);
        g.fill(x+1,y+2,x+3,y+h-2,0xff000000|accent);
    }

    /** A small filled tag, such as a key's name or a warning. @return its width. */
    public static int tag(GuiGraphics g,Font font,String text,int right,int y,int back,int colour) {
        int w=font.width(text)+6;
        rounded(g,right-w,y,w,11,back);
        g.drawString(font,text,right-w+3,y+2,colour,false);
        return w;
    }

    /** {@code text} cut to fit {@code width}, with an ellipsis if anything had to go. */
    public static String fit(Font font,String text,int width) {
        if(font.width(text)<=width)return text;
        return font.plainSubstrByWidth(text,Math.max(0,width-font.width("…")))+"…";
    }

    /**
     * {@code text} wrapped to {@code width}, at most {@code lines} lines (the last one ended with an ellipsis if there was
     * more). @return the height used.
     */
    public static int wrap(GuiGraphics g,Font font,String text,int x,int y,int width,int lines,int colour) {
        List<FormattedCharSequence> split=font.split(Component.literal(text),width);
        int shown=Math.min(lines,split.size());
        for(int i=0;i<shown;i++) {
            if(i==shown-1&&split.size()>shown) {
                // The last line that fits, cut short and marked, rather than a line hanging out of the box.
                StringBuilder rest=new StringBuilder();
                split.get(i).accept((index,style,code)->{rest.appendCodePoint(code);return true;});
                g.drawString(font,fit(font,rest+"…",width),x,y+i*10,colour,false);
            } else g.drawString(font,split.get(i),x,y+i*10,colour,false);
        }
        return shown*10;
    }

    /** How many lines {@code text} takes at {@code width}. */
    public static int lines(Font font,String text,int width) {return font.split(Component.literal(text),width).size();}

    public static boolean inside(double mx,double my,int x,int y,int w,int h) {return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
}
