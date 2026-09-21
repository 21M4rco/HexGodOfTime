package com.hexgodofstories.warping;

/** Pure geometry/time rules shared by authority, rendering and regression tests. */
public final class WarpMath {
    public static final int MIN_CHARGE=24,FULL_CHARGE=100,MAX_HOLD=160,OPEN_TICKS=200;
    public static final int EDGE_COUNT=32;
    public static final double SUN_Y=94,SUN_RADIUS=35,SUN_CORONA=42;
    public static float solarDamage(double distance){return distance<SUN_RADIUS?36:distance<SUN_CORONA?8:0;}
    public static double width(int ticks){return 2+8*Math.min(1,Math.max(0,ticks)/(double)FULL_CHARGE);}
    /** Fixed, angular mirror outline. The renderer and server use the same polygon at every size. */
    public static double edgeX(int i,double half){return edge(i,half,true);}
    public static double edgeZ(int i,double half){return edge(i,half,false);}
    private static double edge(int i,double half,boolean x){
        i=Math.floorMod(i,EDGE_COUNT);double a=i*Math.PI*2/EDGE_COUNT,c=Math.cos(a),s=Math.sin(a);
        double jag= .72+Math.floorMod(i*17+i*i*7,29)/100.0;
        return (x?c:s)*half*jag/Math.max(Math.abs(c),Math.abs(s));
    }
    public static boolean inside(double x,double z,int ticks){
        double half=width(ticks)*.5;if(Math.abs(x)>=half||Math.abs(z)>=half)return false;
        boolean in=false;
        for(int i=0,j=EDGE_COUNT-1;i<EDGE_COUNT;j=i++){
            double ax=edgeX(i,half),az=edgeZ(i,half),bx=edgeX(j,half),bz=edgeZ(j,half);
            if((az>z)!=(bz>z)&&x<(bx-ax)*(z-az)/(bz-az)+ax)in=!in;
        }
        return in;
    }
    public static boolean openAt(long opened,long now){return opened>=0&&now>=opened&&now-opened<OPEN_TICKS;}
    /** Horizontal leash around an instance centre, and the distance past it that recalls. */
    public static final double LEASH=240,LEASH_HARD=380;
    /** Speed ceiling for anything a realm field accelerates, well under the movement checks. */
    public static final double FIELD_SPEED=1.8;
    /**
     * The well, in the same numbers the accretion disk is drawn with: a black
     * core of radius 10, a ring of debris from 12 to 35 tilted 0.28 radians about
     * the X axis, and anything further out than CAPTURE still falling toward it.
     */
    public static final double WELL_Y=96,EVENT_HORIZON=10,DISK_INNER=12,DISK_OUTER=35,DISK_TILT=.28,CAPTURE=95;
    /**
     * Inward acceleration per tick for the far capture, before anything is on the
     * ring. Inverse-square close in, linear far out, capped. Strictly decreasing.
     */
    public static double pull(double distance){
        double r=Math.max(EVENT_HORIZON,distance);
        return Math.min(.26,3.4/(r*r*.06+r));
    }
    /**
     * Orbital speed on the ring, in blocks per tick: a Keplerian shape, so the
     * closer a victim is dragged the faster they are carried around. Capped under
     * the field speed so the whole motion stays inside the movement checks.
     */
    public static double orbitSpeed(double radius){
        return Math.min(1.45,.8*Math.sqrt(DISK_OUTER/Math.max(EVENT_HORIZON,radius)));
    }
    /** Inward decay per tick: a fall onto the ring from outside, a slow spiral once on it. */
    public static double inwardDrift(double radius){
        return radius>DISK_OUTER?.35:.04+.10*(1-radius/DISK_OUTER);
    }
    /**
     * The press. One plane descends onto a real block floor that never moves:
     * a rising invisible floor above a visible slab was the part that made no
     * sense. Five seconds of warning, then sixty-odd to close.
     */
    public static final double PRESS_FLOOR=99,PRESS_TOP=164,PRESS_RATE=.045,PRESS_GAP=1.5;
    public static double ceiling(long age){return Math.max(PRESS_FLOOR+PRESS_GAP,PRESS_TOP-Math.max(0,age-100)*PRESS_RATE);}
    public static double floor(long age){return PRESS_FLOOR;}
    /** Lowest block level the descending plane has already ground away. */
    public static int pressGround(long age){return (int)Math.floor(ceiling(age));}
    public static double fallingY(double initial,double time){return 48+Math.floorMod((long)((initial-48-time*.19)*1000),192000)/1000.0;}
    public static double cellX(double x){return Math.floor((x+512)/1024)*1024;}
    private WarpMath(){}
}
