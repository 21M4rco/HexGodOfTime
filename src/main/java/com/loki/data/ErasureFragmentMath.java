package com.loki.data;

/** Deterministic fracture timing, shared by all surfaces of a victim on every viewer. */
public final class ErasureFragmentMath {
    private ErasureFragmentMath() {}
    public static float clamp(float v){return Math.max(0,Math.min(1,v));}
    public static float noise(int seed) {
        int x=seed;x^=x>>>16;x*=0x7feb352d;x^=x>>>15;x*=0x846ca68b;x^=x>>>16;
        return (x&0xffffff)/(float)0x1000000;
    }
    public static float release(float along,float radial,int seed,boolean implosion) {
        return implosion?.22f+.12f*clamp(radial)+.07f*noise(seed)
            :.03f+.63f*clamp(along)+.09f*noise(seed);
    }
    public static float age(float phase,float release,boolean implosion) {
        return (phase-release)/(implosion?.58f:.24f);
    }
    public static float alpha(float age) {return age<=0?1:clamp((1-age)*1.5f);}
    public static float travel(float age,float power,boolean implosion) {
        float t=clamp(age);
        return implosion?(float)Math.pow(t,.65)*(2.2f+power*1.6f)
            :(t*.25f+t*t)*(5+power*9);
    }
}
