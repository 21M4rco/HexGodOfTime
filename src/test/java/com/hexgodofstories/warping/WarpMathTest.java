package com.hexgodofstories.warping;

/** Boundaries that protect multiplayer area limits and prevent an inescapable owner state. */
public final class WarpMathTest {
    public static void main(String[] args){
        for(int t=-100;t<10000;t++){
            double width=WarpMath.width(t);check(width>=2&&width<=10,"area cap at "+t);
            check(!WarpMath.inside(5,0,t)&&!WarpMath.inside(0,-5,t),"outside the ten-block footprint");
        }
        check(WarpMath.MIN_CHARGE>0&&WarpMath.MIN_CHARGE<WarpMath.FULL_CHARGE,"telegraph before full opening");
        check(WarpMath.inside(4.99,-4.99,100),"full square includes corners");
        check(!WarpMath.inside(4,0,24),"short hold does not get full footprint");
        check(WarpMath.pull(10)>WarpMath.pull(40)&&WarpMath.pull(40)>WarpMath.pull(100),"inward pull intensifies");
        check(WarpMath.ceiling(80)>160,"visible warning before closing");
        check(WarpMath.ceiling(500)>WarpMath.ceiling(1000),"ceiling descends gradually");
        check(WarpMath.ceiling(2000)-WarpMath.floor(2000)<1.8,"final gap really crushes a standing victim");
        check(WarpMath.cellX(1024+200)==1024&&WarpMath.cellX(2048-100)==2048,"separate instances remain separate");
        for(int t=0;t<100000;t+=13)check(WarpMath.fallingY(175,t)>=48&&WarpMath.fallingY(175,t)<240,"endless fall has bounded coordinates");
        System.out.println("Warping geometry and timing checks passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
