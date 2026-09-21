package com.hexgodofstories.warping;

public final class CosmicPhysicsTest {
    public static void main(String[] args) {
        for(CosmicPhysics.V axis:new CosmicPhysics.V[]{new CosmicPhysics.V(1,0,0),new CosmicPhysics.V(0,1,0),new CosmicPhysics.V(0,-1,0),new CosmicPhysics.V(1,2,3).unit()}) {
            CosmicPhysics.V p=axis.scale(64);double angle=0;int ticks=0;
            while(p.length()>CosmicPhysics.HORIZON&&ticks++<1000) {
                CosmicPhysics.V next=CosmicPhysics.orbit(p);
                check(Double.isFinite(next.length()),"finite orbit at poles");
                check(next.length()<p.length(),"radius decreases on every step");
                check(p.length()-next.length()<.33,"infall remains gradual");
                angle+=Math.acos(Math.max(-1,Math.min(1,p.unit().dot(next.unit()))));p=next;
            }
            check(ticks>200&&ticks<600,"arrival spirals for seconds before reaching horizon");
            check(angle>Math.PI*4,"at least two complete turns before death");
            check(p.length()<=CosmicPhysics.HORIZON,"every orbit reaches the lethal core");
        }
        double low=100,high=0;
        for(int i=0;i<10000;i++) {
            double y=1-2*(i+.5)/10000,a=i*2.399963229728653;
            CosmicPhysics.V p=new CosmicPhysics.V(Math.cos(a)*Math.sqrt(1-y*y),y,Math.sin(a)*Math.sqrt(1-y*y));
            double r=CosmicPhysics.moonRadius(p);low=Math.min(low,r);high=Math.max(high,r);
            check(r>44&&r<50,"entire moon fits the collision shell");
            check(CosmicPhysics.MOON_Y-r>0,"underside stays above the world's kill plane");
            check(Math.abs(r-CosmicPhysics.moonRadius(p.scale(37)))<1e-9,"surface is independent of height");
        }
        check(high-low>1.5,"moon has real crater depth");
        check(CosmicPhysics.MOON_GRAVITY>=3*.08,"heavy gravity is at least three times vanilla");
        System.out.println("Cosmic physics: polar orbits converge, death boundary is reached, cratered moon is continuous.");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
