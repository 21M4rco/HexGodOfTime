package com.loki.client;

import com.loki.data.BranchCharge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The charge readout for Time Branch Unleashing.
 *
 * <p>Not a progress bar. A trunk grows out of the middle of the screen and throws off branches as the
 * charge climbs — one per stage — in the same shifting temporal colours as the sphere, so the meter is
 * part of the ability rather than a widget sitting next to it. The stage is named underneath it, the
 * moment full power becomes available is called out plainly, and holding past that says so too, because
 * overcharge buys drama and nothing else.
 *
 * <p>It sits below the crosshair so a planted caster reads it without looking away from their aim.
 */
public final class BranchMeter {
    private BranchMeter() {}

    private static final String[] STAGES={"FORMING","STABLE","PRESSURE RISING","CRITICAL","MAXIMUM CHARGE","OVERCHARGE"};

    public static void render(GuiGraphics g,int screenWidth,int screenHeight) {
        var mc=Minecraft.getInstance();
        if(mc.player==null)return;
        int held=ClientState.branchHeld(mc.player.getId(),mc.getFrameTime());
        if(held<0)return;
        float power=BranchCharge.power(held);
        int stage=BranchCharge.stage(held);
        float over=BranchCharge.overcharge(held);
        double time=ClientState.now()+mc.getFrameTime();

        int centre=screenWidth/2,top=screenHeight/2+16;
        int reach=(int)(46+34*power);

        // The trunk: two arms growing out from the middle, brightening as the charge builds.
        for(int side=-1;side<=1;side+=2) {
            for(int i=0;i<reach;i++) {
                float t=i/(float)reach;
                int colour=TemporalPalette.shade((float)(time*.03+t*.5+(side>0?.12:0)));
                int alpha=(int)(255*Mth.clamp(.35f+.55f*power,0,1)*(1-t*.25f));
                int wobble=(int)(Math.sin(t*9+time*.22*side)*(1+power*2.2));
                g.fill(centre+side*i,top+wobble,centre+side*i+1,top+wobble+1+(power>.6f?1:0),alpha<<24|colour);
            }
        }
        // One branch per stage reached, each leaving the trunk further out than the last.
        for(int b=0;b<Math.min(5,stage);b++) {
            float grow=Mth.clamp((held-thresholdOf(b))/12f,0,1);
            if(grow<=0)continue;
            int from=(int)(reach*(.24+b*.16));
            int side=b%2==0?-1:1;
            int rise=b%2==0?-1:1;
            int length=(int)((7+b*4)*grow);
            for(int i=0;i<length;i++) {
                float t=i/(float)Math.max(1,length);
                int colour=TemporalPalette.hot((float)(time*.045+b*.17),t*.4f);
                int alpha=(int)(220*(1-t*.6f)*grow);
                int x=centre+side*(from+i);
                int y=top+rise*(int)(i*(.55+b*.12)+Math.sin(t*6+time*.3)*1.4);
                g.fill(x,y,x+1,y+1,alpha<<24|colour);
            }
        }
        // Instability at the top end shows as the whole figure flickering, not as more of it.
        if(over>0&&Math.sin(time*.9)>.55-over*.5) {
            int colour=TemporalPalette.hot((float)(time*.2),.8f);
            g.fill(centre-reach,top-1,centre+reach,top,(int)(120*over)<<24|colour);
        }

        String label=STAGES[Mth.clamp(stage-1,0,STAGES.length-1)];
        int tint=stage>=5?0xffe9a8:stage>=4?0xf05cc8:stage>=3?0x53f0e6:0x8fe08a;
        g.drawCenteredString(mc.font,label,centre,top+12,tint);
        if(held>=BranchCharge.FULL) {
            // Called out once and then kept on screen: releasing now is the full move, holding is theatre.
            boolean blink=(ClientState.now()/4)%2==0||over>.25f;
            if(blink)g.drawCenteredString(mc.font,"FULL POWER AVAILABLE",centre,top+24,0xfff0c2);
        } else {
            int seconds=(int)Math.ceil((BranchCharge.FULL-held)/20f);
            g.drawCenteredString(mc.font,"full power in "+seconds+"s",centre,top+24,0x9cb6a6);
        }
        int remaining=(int)Math.ceil((BranchCharge.LIMIT-held)/20f);
        if(held>BranchCharge.FULL)
            g.drawCenteredString(mc.font,"containment fails in "+remaining+"s",centre,top+36,0xd2a07f);
    }

    /** The tick at which branch {@code index} is earned, matching the documented stage thresholds. */
    private static int thresholdOf(int index) {
        return switch(index) {
            case 0 -> BranchCharge.FORMATION;
            case 1 -> BranchCharge.STABLE;
            case 2 -> BranchCharge.PRESSURE;
            case 3 -> BranchCharge.CRITICAL;
            default -> BranchCharge.FULL;
        };
    }
}
