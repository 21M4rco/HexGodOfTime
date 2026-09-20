package com.loki.server;

/** Broad, asymmetric floating continent, anchored to the existing throne and return point. */
public final class RealmShape {
    public static final int MIN=-94,SIZE=288,MAX=MIN+SIZE-1,DEPTH=60,TOP=12;
    private RealmShape() {}
    public static double edge(double a) {
        return 108+13*Math.sin(a*3+.7)+7*Math.sin(a*5-1.1)+4*Math.cos(a*9)+3*Math.sin(a*13);
    }
    public static double fraction(int x,int z) {
        double dx=x-50,dz=(z-50)*1.06;
        return Math.hypot(dx,dz)/edge(Math.atan2(dz,dx));
    }
    public static boolean contains(int x,int z) {return fraction(x,z)<=1;}
    public static int surface(int x,int z) {
        double r=Math.hypot(x-50,z-50),rise=Math.max(0,Math.min(1,(r-30)/42));
        double hills=5+3.1*Math.sin(x*.043+.5)*Math.cos(z*.038)+1.7*Math.sin((x+z)*.072);
        return (int)Math.round(rise*hills);
    }
    public static int depth(int x,int z) {
        double t=Math.max(0,1-fraction(x,z));
        double ridges=5*Math.sin(x*.09+z*.035)*Math.cos(z*.08)+3*Math.sin((x-z)*.055);
        return Math.max(3,Math.min(DEPTH,(int)Math.round(5+51*Math.pow(t,.73)+ridges)));
    }
}
