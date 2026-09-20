package com.loki.client;

import net.minecraft.util.Mth;

/**
 * The colour of a timeline.
 *
 * <p>Time Branch Unleashing is not one colour with a hue rotation over it. It is several temporal
 * currents sharing a space they should not be able to share, so the spectrum is authored as a ring of
 * named stops — temporal green through emerald, gold, cyan, pale blue, violet, purple, magenta and
 * white — and every layer of the effect reads that ring at its own rate and its own offset. Two strands
 * a hand apart are therefore never the same colour and never in step, which is what keeps the sphere
 * from looking like a rainbow texture spinning on a ball.
 */
public final class TemporalPalette {
    private TemporalPalette() {}

    /** The ring, in order. The last stop returns to the first so the cycle closes without a seam. */
    private static final int[] STOPS={
        0x2bd97a, // temporal green
        0x0fbf6a, // emerald
        0x8fe08a, // pale leaf
        0xf2d06a, // gold
        0xffe9a8, // hot gold
        0x53f0e6, // cyan
        0xa8d8ff, // pale blue
        0x9a6cf0, // violet
        0x7a3cd6, // purple
        0xf05cc8, // magenta
        0xffe4f6, // near white
        0x2bd97a,
    };

    /** A colour anywhere on the ring. {@code phase} wraps, so callers can feed it raw time. */
    public static int shade(float phase) {
        float t=(phase%1+1)%1*(STOPS.length-1);
        int i=(int)t;
        return blend(STOPS[i],STOPS[Math.min(STOPS.length-1,i+1)],t-i);
    }

    /** Pushed toward white: for a core, or for the places where several branches intersect. */
    public static int hot(float phase,float heat) {return blend(shade(phase),0xffffff,Mth.clamp(heat,0,1));}

    public static int blend(int a,int b,float t) {
        float k=Mth.clamp(t,0,1);
        int r=Mth.lerpInt(k,a>>16&255,b>>16&255);
        int g=Mth.lerpInt(k,a>>8&255,b>>8&255);
        int bl=Mth.lerpInt(k,a&255,b&255);
        return r<<16|g<<8|bl;
    }

    /** Loki's ordinary sorcery: the same ring narrowed to its greens and golds, no violets or magentas. */
    private static final int[] SEIDR={0x2bd97a,0x0fbf6a,0x8fe08a,0xd7e59a,0xf2d06a,0xffe9a8,0x9ff0c6,0x2bd97a};
    /** A colour for sorcery rather than for raw temporal radiation. */
    public static int seidr(float phase) {
        float t=(phase%1+1)%1*(SEIDR.length-1);
        int i=(int)t;
        return blend(SEIDR[i],SEIDR[Math.min(SEIDR.length-1,i+1)],t-i);
    }

    /**
     * A stable per-strand offset. Strands need to sit at different points on the ring for the whole of
     * their life, not flicker, so the offset comes from the index rather than from a random source.
     */
    public static float offset(int index) {return (index*.61803398875f)%1;}
}
