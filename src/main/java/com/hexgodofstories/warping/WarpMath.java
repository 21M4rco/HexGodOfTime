package com.hexgodofstories.warping;

/** Pure geometry/time rules shared by authority, rendering and regression tests. */
public final class WarpMath {
    public static final int MIN_CHARGE=24,FULL_CHARGE=100,MAX_HOLD=160,OPEN_TICKS=200;
    public static final double SUN_Y=94,SUN_RADIUS=35,SUN_CORONA=42;
    public static float solarDamage(double distance){return distance<SUN_RADIUS?36:distance<SUN_CORONA?8:0;}
    /**
     * How far the break reaches across the floor, corner to corner, at this charge.
     *
     * <p>A held charge now buys a genuinely large tear rather than a ten block one: a full charge
     * is twenty eight blocks across, and what that width means is the span of the whole fracture,
     * not a radius — {@link WarpFracture} spends it on an impact hole and the cracks leaving it.
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
    public static boolean openAt(long opened,long now){return opened>=0&&now>=opened&&now-opened<OPEN_TICKS;}
    public static double pull(double distance){return Math.min(.32,.025+2.8/Math.max(10,distance));}
    public static double fallingY(double initial,double time){return 48+Math.floorMod((long)((initial-48-time*.19)*1000),192000)/1000.0;}
    public static double cellX(double x){return Math.floor((x+512)/1024)*1024;}
    private WarpMath(){}
}
