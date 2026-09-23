package com.hexgodofstories.client;

import net.minecraft.util.Mth;

/** Private black/green palette for Time Branch Unleashing. */
public final class TimeBranchPalette {
    private TimeBranchPalette(){}
    private static final int[] FLOW={
        0x000000,0x020805,0x04150b,0x073019,0x0a5e2d,0x10a94c,0x16e866,0x0b7a39,0x031a0d,0x000000
    };
    public static int shade(float phase){
        float t=(phase%1+1)%1*(FLOW.length-1);
        int i=(int)t;
        return blend(FLOW[i],FLOW[Math.min(FLOW.length-1,i+1)],t-i);
    }
    public static int hot(float phase,float heat){
        return blend(shade(phase),0x59ff8e,Mth.clamp(heat,0,1));
    }
    public static int shadow(float phase){
        float pulse=.18f+.22f*(float)(.5+.5*Math.sin(phase*Math.PI*2));
        return blend(0x000000,0x0a4a25,pulse);
    }
    public static int blend(int a,int b,float t){
        float k=Mth.clamp(t,0,1);
        int r=Mth.lerpInt(k,a>>16&255,b>>16&255);
        int g=Mth.lerpInt(k,a>>8&255,b>>8&255);
        int bl=Mth.lerpInt(k,a&255,b&255);
        return r<<16|g<<8|bl;
    }
}
