package com.hexgodofstories.warping;

/** Pure geometry/time rules shared by authority, rendering and regression tests. */
public final class WarpMath {
    public static final int MIN_CHARGE=24,FULL_CHARGE=100,MAX_HOLD=160,OPEN_TICKS=24;
    public static double width(int ticks){return 2+8*Math.min(1,Math.max(0,ticks)/(double)FULL_CHARGE);}
    public static boolean inside(double x,double z,int ticks){double r=width(ticks)*.5;return Math.abs(x)<r&&Math.abs(z)<r;}
    public static double pull(double distance){return Math.min(.32,.025+2.8/Math.max(10,distance));}
    public static double ceiling(long age){return Math.max(99.8,164-Math.max(0,age-100)*.06);}
    public static double fallingY(double initial,double time){return 48+Math.floorMod((long)((initial-48-time*.19)*1000),192000)/1000.0;}
    public static double cellX(double x){return Math.floor((x+512)/1024)*1024;}
    private WarpMath(){}
}
