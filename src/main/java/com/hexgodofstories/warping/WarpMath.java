package com.hexgodofstories.warping;

/** Pure geometry/time rules shared by authority, rendering and regression tests. */
public final class WarpMath {
    public static final int MIN_CHARGE=24,FULL_CHARGE=100,MAX_HOLD=160,OPEN_TICKS=200;
    public static final double SUN_Y=94,SUN_RADIUS=35,SUN_CORONA=42;
    public static float solarDamage(double distance){return distance<SUN_RADIUS?36:distance<SUN_CORONA?8:0;}
    /**
     * How far the break reaches across the floor, corner to corner, at this charge.
     *
     * <p>A held charge buys a genuinely large opening rather than a ten block one: a full charge is
     * twenty eight blocks across, and what that width means is how far the pool may run corner to
     * corner — {@link WarpPool} spends it on a rim that reaches further one way than another.
     */
    public static double width(int ticks){return 2+26*charge(ticks);}
    /** Nought at the first tick of a hold, one once the charge is full. */
    public static double charge(int ticks){return Math.min(1,Math.max(0,ticks)/(double)FULL_CHARGE);}
    /** Blocks from the centre that the fracture is allowed to run to at this charge. */
    public static double reach(int ticks){return width(ticks)*.5;}
    /**
     * What a break of this charge costs, as a multiple of the ability's own cost.
     *
     * <p>Size is the thing being paid for: the smallest usable tear is half price and the full
     * twenty eight block one is twice it, so opening reality wide is a deliberate expense rather
     * than the same flat fee as cracking it.
     */
    public static double costScale(int held){return .5+1.5*charge(held);}
    /** The largest charge this much energy can actually pay for, in ticks. */
    public static int affordable(double energy,double cost){
        if(cost<=0)return FULL_CHARGE;
        double f=(energy/cost-.5)/1.5;
        return (int)Math.floor(Math.max(0,Math.min(1,f))*FULL_CHARGE);
    }
    /**
     * How fast a body sinks into an open pool, in blocks per tick.
     *
     * <p>Quicksand rather than a hole. Whatever a body arrives doing, its descent is taken over by
     * this the moment it is in the liquid: a running jump into the middle does not carry you
     * through, it stops you dead and starts you going down. A player's eye is 1.62 blocks up, so a
     * crossing takes a little under two seconds from the first step onto the surface, which is long
     * enough to watch the other world come up around you and long enough to regret it.
     */
    public static final double SINK_RATE=.042;
    /** What the liquid does to a body trying to wade across it. Sluggish, but not stuck. */
    public static final double SINK_DRAG=.82;
    /**
     * How far one frantic attempt to get out lifts a body, in blocks.
     *
     * <p>The number that decides whether the pool is escapable, and it is set from the sink rate
     * rather than guessed: at {@link #struggleRate()} presses a second the lift exactly cancels the
     * sink, so anything slower loses ground and anything faster climbs. Six a second is fast — fast
     * enough that it is a thing you do in a panic rather than a thing you do casually — and the
     * deeper somebody already is, the longer they have to keep it up.
     */
    public static final double STRUGGLE_LIFT=.14;
    /** Presses a second at which struggling exactly cancels sinking. */
    public static double struggleRate(){return SINK_RATE/STRUGGLE_LIFT*20;}
    /** How long a body of this eye height takes to sink far enough to cross, in ticks. */
    public static double sinkTicks(double eyeHeight){return eyeHeight/SINK_RATE;}
    public static boolean openAt(long opened,long now){return opened>=0&&now>=opened&&now-opened<OPEN_TICKS;}
    public static double pull(double distance){return Math.min(.32,.025+2.8/Math.max(10,distance));}
    public static double cellX(double x){return Math.floor((x+512)/1024)*1024;}
    private WarpMath(){}
}
