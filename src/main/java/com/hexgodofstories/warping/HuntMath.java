package com.hexgodofstories.warping;

/**
 * The hunter's numbers, kept free of Minecraft so the headless checks can hold
 * them to their promises: a bite that is always telegraphed, always lethal, and
 * still able to land on something that is swimming away.
 */
public final class HuntMath {
    /** Authored bite clip, in ticks, and the tick the jaws close on. */
    public static final int BITE_TICKS=28,BITE_STRIKE=12;
    /** Ticks of recovery after a bite finishes. Slow is the point. */
    public static final int RECOVERY=70;
    /** Fifty hearts, through armour. */
    public static final float BITE_DAMAGE=100F;
    /** Mouth offset from the entity position, maw radius, and the gap it commits at. */
    public static final double MOUTH_REACH=7.5,MOUTH_RADIUS=4.0,STRIKE_RANGE=9.0;
    /** Patrol, pursuit and lunge speeds in blocks per tick. */
    public static final double CRUISE=.115,HUNT=.225,LUNGE=.62;
    /** Velocity blend per tick: heavy while cruising, sharper once committed. */
    public static final double DRIFT_BLEND=.1,LUNGE_BLEND=.25;
    /** Water column it keeps to, above the bedrock and below the surface. */
    public static final double BOTTOM=5,SURFACE=133;

    /** Distance covered by a committed lunge after n ticks, using the entity's own smoothing. */
    public static double closing(int ticks) {
        double v=HUNT,distance=0;
        for(int i=0;i<ticks;i++){v=v*(1-LUNGE_BLEND)+LUNGE*LUNGE_BLEND;distance+=v;}
        return distance;
    }

    /** True while the jaw is still opening: the window a victim has to be elsewhere. */
    public static boolean telegraph(int clipTick){return clipTick<BITE_STRIKE;}

    private HuntMath(){}
}
