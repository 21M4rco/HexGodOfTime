package com.hexgodofstories.warping;

/** Shared, deterministic geometry for the two radial-gravity realms. No Minecraft bootstrap. */
public final class CosmicPhysics {
    public static final double WELL_Y = 96, HORIZON = 10;
    public static final double MOON_Y = 128, MOON_RADIUS = 48, MOON_GRAVITY = .24;
    public record V(double x, double y, double z) {
        public V add(V b) { return new V(x+b.x,y+b.y,z+b.z); }
        public V scale(double s) { return new V(x*s,y*s,z*s); }
        public double dot(V b) { return x*b.x+y*b.y+z*b.z; }
        public double length() { return Math.sqrt(dot(this)); }
        public V unit() { double l=length(); return l<1e-9?new V(0,1,0):scale(1/l); }
        public V cross(V b) { return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
    }
    /** Exact shrinking orbit: fast tangential travel, slow guaranteed inward progress, no escape momentum. */
    public static V orbit(V relative) {
        double r=relative.length(); if(r<=HORIZON)return relative;
        V up=relative.unit(), tangent=new V(0,1,0).cross(up);
        if(tangent.length()<.05)tangent=new V(1,0,0).cross(up);
        tangent=tangent.unit();
        double speed=Math.min(2.8,1.35+18/Math.max(10,r));
        double inward=Math.min(.32,.10+2.1/Math.max(10,r));
        double angle=speed/r;
        return up.scale(Math.cos(angle)).add(tangent.scale(Math.sin(angle))).scale(Math.max(0,r-inward));
    }
    /** Low rolling regolith and shallow impact craters, sampled identically by collision and rendering. */
    public static double moonRadius(V direction) {
        V n=direction.unit();
        double h=.28*Math.sin(n.x*31+n.z*13)*Math.sin(n.y*27-n.z*19);
        for(int i=0;i<18;i++) {
            double y=1-2*(i+.5)/18, a=i*2.399963229728653;
            V c=new V(Math.cos(a)*Math.sqrt(1-y*y),y,Math.sin(a)*Math.sqrt(1-y*y));
            double chord=Math.sqrt(Math.max(0,2-2*n.dot(c)));
            double width=.09+(i%4)*.023, q=chord/width;
            if(q<1.45)h+=-1.8*Math.exp(-q*q*3)+.5*Math.exp(-Math.pow((q-1)*5,2));
        }
        return MOON_RADIUS+h;
    }
    private CosmicPhysics() {}
}
