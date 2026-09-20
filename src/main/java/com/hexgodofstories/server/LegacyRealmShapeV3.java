package com.hexgodofstories.server;

/** Pure deterministic island profile. Coordinates stay anchored to the old plot, including its return sigil. */
public final class LegacyRealmShapeV3 {
    public static final int MIN=-38,SIZE=176,MAX=MIN+SIZE-1,DEPTH=46,TOP=6;
    private LegacyRealmShapeV3() {}
    public static double edge(double angle) {
        return 72+6*Math.sin(angle*3+.7)+3.5*Math.sin(angle*7-1.1)+2*Math.cos(angle*11);
    }
    public static double fraction(int x,int z) {
        double dx=x-50,dz=(z-50)*1.07;
        return Math.hypot(dx,dz)/edge(Math.atan2(dz,dx));
    }
    public static boolean contains(int x,int z) {return fraction(x,z)<=1;}
    public static int surface(int x,int z) {
        double r=Math.hypot(x-50,z-50),rise=Math.max(0,Math.min(1,(r-29)/30));
        return (int)Math.round(rise*(2+1.5*Math.sin(x*.12)*Math.cos(z*.10)+.9*Math.sin((x+z)*.065)));
    }
    public static int depth(int x,int z) {
        double t=Math.max(0,1-fraction(x,z));
        double ridges=3*Math.sin(x*.18+z*.04)*Math.cos(z*.15)+2*Math.sin((x-z)*.11);
        return Math.max(3,Math.min(DEPTH,(int)Math.round(5+34*Math.pow(t,.62)+ridges)));
    }
}
