package com.hexgodofstories.warping;

/** Boundaries that protect multiplayer area limits and prevent an inescapable owner state. */
public final class WarpMathTest {
    public static void main(String[] args){
        for(int t=-100;t<10000;t++){
            double width=WarpMath.width(t);check(width>=2&&width<=10,"area cap at "+t);
            check(!WarpMath.inside(5,0,t)&&!WarpMath.inside(0,-5,t),"outside the ten-block footprint");
        }
        check(WarpMath.MIN_CHARGE>0&&WarpMath.MIN_CHARGE<WarpMath.FULL_CHARGE,"telegraph before full opening");
        check(!WarpMath.inside(4.99,-4.99,100),"broken mirror does not turn into a filled square");
        check(WarpMath.inside(0,0,24),"short charge keeps a usable center");
        check(WarpMath.OPEN_TICKS==20*10,"portal lasts ten seconds at normal tick rate");
        check(!WarpMath.openAt(-1,0)&&!WarpMath.openAt(500,499),"unreleased portal cannot accept entry");
        for(int age=0;age<200;age++)check(WarpMath.openAt(500,500+age),"entry remains open through tick "+age);
        check(!WarpMath.openAt(500,700)&&!WarpMath.openAt(500,701),"entry stops exactly at expiration");
        // Independent polygon implementation verifies collision against the actual rendered triangles.
        for(int held:new int[]{24,50,100}){
            double half=WarpMath.width(held)*.5;
            java.awt.geom.Path2D.Double polygon=new java.awt.geom.Path2D.Double();
            polygon.moveTo(WarpMath.edgeX(0,half),WarpMath.edgeZ(0,half));
            for(int i=1;i<WarpMath.EDGE_COUNT;i++)polygon.lineTo(WarpMath.edgeX(i,half),WarpMath.edgeZ(i,half));
            polygon.closePath();
            for(double x=-half+.017;x<half;x+=.137)for(double z=-half+.031;z<half;z+=.149)
                check(WarpMath.inside(x,z,held)==polygon.contains(x,z),"entry matches mirror silhouette");
            for(int i=0;i<WarpMath.EDGE_COUNT;i++){
                double x=(WarpMath.edgeX(i,half)+WarpMath.edgeX(i+1,half))*.5;
                double z=(WarpMath.edgeZ(i,half)+WarpMath.edgeZ(i+1,half))*.5;
                check(WarpMath.inside(x*.999,z*.999,held),"just inside each rendered edge accepts entry");
                check(!WarpMath.inside(x*1.001,z*1.001,held),"outside each rendered edge cannot teleport");
            }
            check(WarpMath.edgeX(0,half)==WarpMath.edgeX(WarpMath.EDGE_COUNT,half)&&
                WarpMath.edgeZ(0,half)==WarpMath.edgeZ(WarpMath.EDGE_COUNT,half),"mirror outline has a closed seam");
        }
        check(!WarpMath.inside(4,0,24),"short hold does not get full footprint");
        check(WarpMath.pull(10)>WarpMath.pull(40)&&WarpMath.pull(40)>WarpMath.pull(100),"inward pull intensifies");
        check(WarpMath.ceiling(80)>160,"visible warning before closing");
        check(WarpMath.ceiling(500)>WarpMath.ceiling(1000),"ceiling descends gradually");
        check(WarpMath.ceiling(2000)-WarpMath.floor(2000)<1.8,"final gap really crushes a standing victim");
        check(WarpMath.cellX(1024+200)==1024&&WarpMath.cellX(2048-100)==2048,"separate instances remain separate");
        for(int t=0;t<100000;t+=13)check(WarpMath.fallingY(175,t)>=48&&WarpMath.fallingY(175,t)<240,"endless fall has bounded coordinates");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA)==0,"outside corona is safe");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA-.01)>0,"corona inflicts heat");
        check(WarpMath.solarDamage(WarpMath.SUN_RADIUS-.01)>WarpMath.solarDamage(WarpMath.SUN_RADIUS+.01),"photosphere is lethal faster than corona");
        check(WarpMath.SUN_RADIUS>33+Math.sqrt(3),"photosphere encloses every corner of the existing magma shell");
        System.out.println("Warping geometry and timing checks passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
