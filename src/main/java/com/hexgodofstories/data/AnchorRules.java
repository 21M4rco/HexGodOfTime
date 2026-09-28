package com.hexgodofstories.data;

/** Tick/damage contract for Anchor Being, independent of world age and client frame rate. */
public final class AnchorRules {
    private AnchorRules() {}
    public static final int VANISH_TICKS=200,LAUGH_TICKS=40,RAISE_TICKS=6,HOLD_TICKS=200,LEASE_TICKS=30,
        NAUSEA_TICKS=400,BLEED_TICKS=200;
    public static final float BLAST_DAMAGE=40,SLASH_DAMAGE=10;
    public static final double BLAST_RADIUS=12;
    public static double strength(long age){return Math.max(0,Math.min(1,age/(double)HOLD_TICKS));}
    public static double speed(long age){return .12+.68*strength(age);}
    public static double range(long age){return 16+8*strength(age);}
    public static boolean expired(long age,long sinceHeartbeat){return age<0||age>=HOLD_TICKS||sinceHeartbeat>LEASE_TICKS;}
}
