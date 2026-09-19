package com.loki.client;

/** Skin-space weaving masks: the reveal front travels from the chest into sleeves and down to boots. */
public final class OutfitPattern {
    public static final OutfitPattern CLASSIC=new OutfitPattern(false),SLIM=new OutfitPattern(true);
    private final int[] colors=new int[4096];
    private final float[] threshold=new float[4096];
    private final boolean[] erase=new boolean[4096];
    private OutfitPattern(boolean slim) {
        part(16,16,16,32,8,4,12,0,.48f,false);
        part(40,16,40,32,slim?3:4,4,10,.15f,.60f,true);
        part(32,48,48,48,slim?3:4,4,10,.15f,.60f,true);
        part(0,16,0,32,4,4,12,.4f,.9f,false);
        part(16,48,0,48,4,4,12,.4f,.9f,false);
    }
    private void part(int ox,int oy,int ex,int ey,int width,int depth,int height,float start,float end,boolean arm) {
        int perimeter=2*(width+depth);
        for(int y=0;y<height;y++)for(int x=0;x<perimeter;x++) {
            boolean torso=width==8;
            int noise=Math.floorMod(x*17+y*31+x*y,9)-4;
            int color=torso?abgr(19+noise,35+noise,28+noise):abgr(14+noise,25+noise,21+noise);
            if(y>8&&!torso&&!arm)color=abgr(12+noise/2,15+noise/2,14+noise/2);
            if(torso&&((x-depth>=0&&x-depth<width&&y<5&&Math.abs(x-depth-width/2)==y/2)||(y==9&&x>=depth&&x<depth+width)))color=abgr(100+noise,86+noise,48+noise);
            if(torso&&x%4==0&&y<9)color=abgr(28+noise,42+noise,30+noise);
            if(arm&&y==8)color=abgr(84,76,45);
            paint(ox+x,oy+depth+y,ex+x,ey+depth+y,color,start+(end-start)*y/height+Math.abs(x-perimeter/2f)*.003f);
        }
        for(int y=0;y<depth;y++)for(int x=0;x<width*2;x++)paint(ox+depth+x,oy+y,ex+depth+x,ey+y,abgr(19,32,24),start+(x>=width?end-start:0));
    }
    private void paint(int x,int y,int ex,int ey,int c,float t) {if(x<64&&y<64){colors[y*64+x]=c;threshold[y*64+x]=t;}if(ex<64&&ey<64){erase[ey*64+ex]=true;threshold[ey*64+ex]=t;}}
    public int pixel(int base,int x,int y,float progress,boolean ignoredA,boolean ignoredB) {
        int i=y*64+x;if(colors[i]==0&&!erase[i])return base;
        float a=Math.max(0,Math.min(1,(progress-threshold[i])/.1f));a=a*a*(3-2*a);
        int target=erase[i]?base&0xffffff:colors[i];int out=0;
        for(int shift=0;shift<32;shift+=8){int c=base>>>shift&255,d=target>>>shift&255;out|=Math.round(c+(d-c)*a)<<shift;}
        if(a>.05f&&a<.95f&&!erase[i]){int glow=Math.round((float)Math.sin(a*Math.PI)*70);out=abgr(Math.min(255,(out&255)+glow/2),Math.min(255,(out>>>8&255)+glow),Math.min(255,(out>>>16&255)+glow/3));}
        return out;
    }
    public static int abgr(int r,int g,int b){return 0xff000000|b<<16|g<<8|r;}
}
