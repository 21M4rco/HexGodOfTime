package com.loki.client;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * The Fracture selector: what the cast key is currently pointed at.
 *
 * <p>This is configuration, not an action. Whatever is chosen here is saved on the server and stays
 * chosen — through casts, dimensions, death and a restart — until somebody deliberately opens this
 * panel and picks something else. The panel therefore never hides which mode is live: the active
 * row carries a lit border, a nebula wash and a marker, and the chosen target is printed beside it.
 *
 * <p>Drawn rather than themed: a nebula field, soft plates and a drifting star wash, so it belongs
 * to the same world as the break it configures instead of looking like an inventory.
 */
public final class FractureScreen extends Screen {
    private static final int WIDTH=340,ROW=30,PAD=12;
    private static final int PANEL=0xe6060d0c,PLATE=0x9c0d1a16,PLATE_HOVER=0xc4163428,PLATE_ACTIVE=0xd4123f2c;
    private static final int EDGE=0xff2c4a3c,EDGE_ACTIVE=0xff6fe0a8;
    private static final int TITLE=0xe6f4ea,TEXT=0xa9c4b4,DIM=0x6f8a7b,ACCENT=0x74d8a6,GOLD=0xd9bd7e;

    /** Null while choosing a mode; set while choosing who a target-taking mode points at. */
    private FractureMode picking;
    private List<PlayerInfo> candidates=List.of();
    private int left,top,panel,scroll;

    public FractureScreen() {super(Component.literal("Fracture"));}

    @Override public boolean isPauseScreen() {return false;}

    private static int index(FractureMode mode) {return FractureModes.indexOf(mode);}
    private static FractureMode active() {return FractureModes.byId(ClientState.self().getString("fractureMode"));}
    private static String activeTarget() {return ClientState.self().getString("fractureTargetName");}

    @Override protected void init() {
        rebuildCandidates();
        int rows=picking==null?FractureModes.count():Math.max(1,candidates.size());
        panel=Math.min(height-40,64+rows*ROW+PAD);
        left=(width-WIDTH)/2;
        top=(height-panel)/2;
        scroll=0;
    }

    private void rebuildCandidates() {
        if(picking==null){candidates=List.of();return;}
        net.minecraft.client.multiplayer.ClientPacketListener connection=minecraft==null?null:minecraft.getConnection();
        if(connection==null){candidates=List.of();return;}
        List<PlayerInfo> found=new ArrayList<>();
        UUID self=minecraft.player==null?null:minecraft.player.getUUID();
        for(PlayerInfo info:connection.getOnlinePlayers())
            if(self==null||!self.equals(info.getProfile().getId()))found.add(info);
        found.sort(Comparator.comparing(info->info.getProfile().getName().toLowerCase(Locale.ROOT)));
        candidates=found;
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(button==0) {
            int row=rowAt(my);
            if(row>=0) {
                if(picking==null) {
                    FractureMode mode=FractureModes.byIndex(row);
                    if(mode==null)return true;
                    if(mode.needsTarget){picking=mode;rebuildWidgets();return true;}
                    commit(index(mode),null);
                    return true;
                }
                if(row<candidates.size()) {
                    commit(index(picking),candidates.get(row).getProfile().getId());
                    return true;
                }
                return true;
            }
        }
        return super.mouseClicked(mx,my,button);
    }

    private void commit(int mode,UUID target) {
        LokiNetwork.chooseFracture(mode,target);
        onClose();
    }

    @Override public boolean keyPressed(int key,int scancode,int modifiers) {
        if(key==256&&picking!=null){picking=null;rebuildWidgets();return true;}
        return super.keyPressed(key,scancode,modifiers);
    }
    @Override public boolean mouseScrolled(double mx,double my,double delta) {
        int rows=picking==null?FractureModes.count():candidates.size();
        int visible=(panel-64)/ROW;
        if(rows>visible)scroll=Mth.clamp(scroll-(int)Math.signum(delta),0,rows-visible);
        return true;
    }

    private int listTop() {return top+52;}
    private int rowAt(double my) {
        int rows=picking==null?FractureModes.count():candidates.size();
        int visible=Math.max(1,(panel-64)/ROW);
        int relative=(int)((my-listTop())/ROW);
        if(relative<0||relative>=visible)return -1;
        int row=relative+scroll;
        return row<rows?row:-1;
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        float time=ClientState.now()+partial;
        g.fill(0,0,width,height,0xa603080a);
        nebula(g,time);
        g.fill(left-1,top-1,left+WIDTH+1,top+panel+1,EDGE);
        g.fill(left,top,left+WIDTH,top+panel,PANEL);
        motes(g,time);

        g.drawString(font,"F R A C T U R E",left+PAD,top+12,GOLD,false);
        FractureMode live=active();
        g.drawString(font,picking==null?"Choose where the break leads":"Choose who to open beside",left+PAD,top+26,DIM,false);
        String current=live.label(activeTarget());
        g.drawString(font,"NOW: "+current,left+WIDTH-PAD-font.width("NOW: "+current),top+12,ACCENT,false);
        g.drawString(font,picking==null?"The cast key keeps this until you change it":"Escape to go back",
            left+WIDTH-PAD-font.width(picking==null?"The cast key keeps this until you change it":"Escape to go back"),top+26,DIM,false);
        g.fill(left+PAD,top+42,left+WIDTH-PAD,top+43,0xff20382e);

        int visible=Math.max(1,(panel-64)/ROW);
        if(picking==null)renderModes(g,mx,my,time,live,visible);
        else renderTargets(g,mx,my,time,visible);
        super.render(g,mx,my,partial);
    }

    private void renderModes(GuiGraphics g,int mx,int my,float time,FractureMode live,int visible) {
        for(int slot=0;slot<visible;slot++) {
            int row=slot+scroll;
            FractureMode mode=FractureModes.byIndex(row);
            if(mode==null)break;
            int y=listTop()+slot*ROW;
            boolean hovered=mx>=left+PAD&&mx<=left+WIDTH-PAD&&my>=y&&my<y+ROW-4;
            boolean chosen=mode==live;
            plate(g,y,hovered,chosen,time);
            icon(g,new ItemStack(mode.icon),left+PAD+6,y+5,time,chosen);
            String title=chosen?mode.label(activeTarget()):mode.title;
            g.drawString(font,title,left+PAD+30,y+4,chosen?0xeafff2:TITLE,false);
            g.drawString(font,trim(mode.description,WIDTH-PAD*2-96),left+PAD+30,y+15,chosen?0x8fd7b5:TEXT,false);
            if(chosen) {
                g.drawString(font,"ACTIVE",left+WIDTH-PAD-8-font.width("ACTIVE"),y+9,ACCENT,false);
                g.drawString(font,"✔",left+WIDTH-PAD-4,y+9,ACCENT,false);
            } else if(mode.needsTarget&&hovered)
                g.drawString(font,"pick ›",left+WIDTH-PAD-8-font.width("pick ›"),y+9,GOLD,false);
        }
    }

    private void renderTargets(GuiGraphics g,int mx,int my,float time,int visible) {
        if(candidates.isEmpty()) {
            g.drawString(font,"Nobody else is abroad.",left+PAD+6,listTop()+8,DIM,false);
            return;
        }
        for(int slot=0;slot<visible;slot++) {
            int row=slot+scroll;
            if(row>=candidates.size())break;
            PlayerInfo info=candidates.get(row);
            int y=listTop()+slot*ROW;
            boolean hovered=mx>=left+PAD&&mx<=left+WIDTH-PAD&&my>=y&&my<y+ROW-4;
            boolean chosen=info.getProfile().getName().equals(activeTarget());
            plate(g,y,hovered,chosen,time);
            face(g,info,left+PAD+6,y+5,time,chosen);
            g.drawString(font,info.getProfile().getName(),left+PAD+30,y+4,chosen?0xeafff2:TITLE,false);
            g.drawString(font,"The break opens a short walk away",left+PAD+30,y+15,chosen?0x8fd7b5:TEXT,false);
            if(chosen)g.drawString(font,"ACTIVE",left+WIDTH-PAD-8-font.width("ACTIVE"),y+9,ACCENT,false);
        }
    }

    /** A soft plate with a lit border when the row is the saved mode, and a gentle pulse to match. */
    private void plate(GuiGraphics g,int y,boolean hovered,boolean chosen,float time) {
        int x0=left+PAD,x1=left+WIDTH-PAD,y1=y+ROW-4;
        g.fill(x0,y,x1,y1,chosen?PLATE_ACTIVE:hovered?PLATE_HOVER:PLATE);
        if(chosen) {
            int glow=0xff000000|tint(0x3f8f6c,0x9dffcf,(Mth.sin(time*.12f)+1)*.5f);
            g.fill(x0,y,x1,y+1,glow);g.fill(x0,y1-1,x1,y1,glow);
            g.fill(x0,y,x0+2,y1,EDGE_ACTIVE);g.fill(x1-1,y,x1,y1,glow);
        } else if(hovered)g.fill(x0,y,x0+1,y1,EDGE);
    }

    /** The mode's emblem inside a nebula disc, so the list reads at a glance. */
    private void icon(GuiGraphics g,ItemStack stack,int x,int y,float time,boolean chosen) {
        disc(g,x,y,time,chosen);
        g.renderFakeItem(stack,x,y);
    }
    private void face(GuiGraphics g,PlayerInfo info,int x,int y,float time,boolean chosen) {
        disc(g,x,y,time,chosen);
        RenderSystem.enableBlend();
        // The tab-list face, drawn at icon size: the overlay layer included, as vanilla does.
        g.blit(info.getSkinLocation(),x+1,y+1,14,14,8,8,8,8,64,64);
        g.blit(info.getSkinLocation(),x+1,y+1,14,14,40,8,8,8,64,64);
        RenderSystem.disableBlend();
    }
    private void disc(GuiGraphics g,int x,int y,float time,boolean chosen) {
        float pulse=chosen?(Mth.sin(time*.1f)+1)*.5f:.25f;
        int wash=0x66000000|tint(0x123326,0x2f7f5c,pulse);
        g.fill(x-2,y-1,x+18,y+17,wash);
        g.fill(x-1,y-2,x+17,y+18,wash);
    }

    /** A slow nebula wash behind the panel, built from a handful of translucent bands. */
    private void nebula(GuiGraphics g,float time) {
        for(int band=0;band<7;band++) {
            float phase=time*.006f+band*.9f;
            int y=(int)(top+panel*.5+Mth.sin(phase)*panel*.55);
            int thickness=6+band*3;
            int alpha=(int)(14+9*(Mth.cos(phase*1.7f)+1));
            g.fill(left-26,y-thickness,left+WIDTH+26,y+thickness,alpha<<24|tint(0x0d3a2a,0x1f7d58,(Mth.sin(phase*.7f)+1)*.5f));
        }
    }
    /** Sparse drifting motes: a fixed small count, entirely deterministic, nothing allocated per frame. */
    private void motes(GuiGraphics g,float time) {
        int spanX=Math.max(1,WIDTH-16),spanY=Math.max(1,panel-16);
        for(int i=0;i<26;i++) {
            float driftX=hash(i*2),driftY=hash(i*2+1);
            int x=left+8+Math.floorMod((int)(driftX*spanX+time*(.12f+driftX*.25f)),spanX);
            int y=top+8+Math.floorMod((int)(driftY*spanY+time*.05f*(i%3-1)),spanY);
            float twinkle=(Mth.sin(time*.09f+i)+1)*.5f;
            int alpha=(int)(28+70*twinkle);
            g.fill(x,y,x+1,y+1,alpha<<24|tint(0x3d7f63,0xbdf5d8,twinkle));
        }
    }
    private static float hash(int i) {
        int h=i*374761393;
        h=(h^h>>>13)*1274126177;
        return ((h^h>>>16)&0xffff)/65535f;
    }

    private static int tint(int from,int to,float t) {
        t=Mth.clamp(t,0,1);
        int r=(int)Mth.lerp(t,from>>16&255,to>>16&255);
        int g=(int)Mth.lerp(t,from>>8&255,to>>8&255);
        int b=(int)Mth.lerp(t,from&255,to&255);
        return r<<16|g<<8|b;
    }
    private String trim(String text,int width) {
        if(font.width(text)<=width)return text;
        return font.plainSubstrByWidth(text,width-font.width("…"))+"…";
    }

    /** Opened from the alternate key while the Fracture is the selected spell. */
    public static void open() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null)mc.setScreen(new FractureScreen());
    }
}
