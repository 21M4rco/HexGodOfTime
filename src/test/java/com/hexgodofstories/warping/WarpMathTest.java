package com.hexgodofstories.warping;

/** Boundaries that protect multiplayer area limits and prevent an inescapable owner state. */
public final class WarpMathTest {
    public static void main(String[] args){
        for(int t=-100;t<10000;t++){
            double width=WarpMath.width(t);check(width>=2&&width<=28,"area cap at "+t);
        }
        check(WarpMath.width(WarpMath.FULL_CHARGE)==28&&WarpMath.width(0)==2,"a full hold is a massive tear and no hold is a crack");
        check(WarpMath.MIN_CHARGE>0&&WarpMath.MIN_CHARGE<WarpMath.FULL_CHARGE,"telegraph before full opening");
        check(WarpMath.OPEN_TICKS==20*10,"portal lasts ten seconds at normal tick rate");
        check(!WarpMath.openAt(-1,0)&&!WarpMath.openAt(500,499),"unreleased portal cannot accept entry");
        for(int age=0;age<200;age++)check(WarpMath.openAt(500,500+age),"entry remains open through tick "+age);
        check(!WarpMath.openAt(500,700)&&!WarpMath.openAt(500,701),"entry stops exactly at expiration");
        shatters();
        growsRatherThanScaling();
        sizeIsPaidFor();
        check(WarpMath.pull(10)>WarpMath.pull(40)&&WarpMath.pull(40)>WarpMath.pull(100),"inward pull intensifies");
        check(WarpMath.cellX(1024+200)==1024&&WarpMath.cellX(2048-100)==2048,"separate instances remain separate");
        for(int t=0;t<100000;t+=13)check(WarpMath.fallingY(175,t)>=48&&WarpMath.fallingY(175,t)<240,"endless fall has bounded coordinates");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA)==0,"outside corona is safe");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA-.01)>0,"corona inflicts heat");
        check(WarpMath.solarDamage(WarpMath.SUN_RADIUS-.01)>WarpMath.solarDamage(WarpMath.SUN_RADIUS+.01),"photosphere is lethal faster than corona");
        check(WarpMath.SUN_RADIUS>33+Math.sqrt(3),"photosphere encloses every corner of the existing magma shell");
        System.out.println("Warping geometry, fracture and cost checks passed");
    }

    /**
     * The silhouette is the fracture, and a fracture is not a circle with cracks drawn on it.
     *
     * <p>Every property here is one the old thirty-two sided outline failed. An even radius, a
     * rounded end, a way through that reaches the tip of a hairline and a shape that is the same
     * every time are all things that would pass a build and read as a decorated disc in play.
     */
    private static void shatters(){
        double reach=WarpMath.reach(WarpMath.FULL_CHARGE);
        double worstRatio=Double.MAX_VALUE;int leastPointed=Integer.MAX_VALUE;
        for(long seed=1;seed<=16;seed++){
            var pieces=WarpFracture.build(seed,reach,1);
            double extent=WarpFracture.extent(pieces);
            check(extent>8&&extent<32,"a full break is large but bounded ("+extent+")");
            check(WarpFracture.inside(pieces,0,0),"the impact itself is always a way through");

            double[] rim=profile(pieces,32,false),through=profile(pieces,32,true);
            double max=0,min=Double.MAX_VALUE;
            for(double v:rim){max=Math.max(max,v);min=Math.min(min,v);}
            check(min>0,"the break closes around its own centre");
            worstRatio=Math.min(worstRatio,max/min);
            check(max/min>2.2,"the outline is violently uneven, not a radius ("+(max/min)+")");

            // Sharp ends: a piece whose far edge has collapsed to a single point.
            int pointed=0,solid=0;double farthestThrough=0,meanThrough=0;int throughDirections=0;
            for(var piece:pieces){
                if(piece.solid)solid++;
                for(int i=0,j=3;i<4;j=i++)if(Math.hypot(piece.x[i]-piece.x[j],piece.z[i]-piece.z[j])<1.0E-9){pointed++;break;}
            }
            leastPointed=Math.min(leastPointed,pointed);
            check(pointed>=8,"fractures end in points rather than caps ("+pointed+")");
            check(solid>=4,"and enough of the break is open to be walked into ("+solid+")");
            for(double v:through){farthestThrough=Math.max(farthestThrough,v);if(v>0){meanThrough+=v;throughDirections++;}}
            check(farthestThrough<extent*0.85,"no hairline tip carries a teleport ("+farthestThrough+" of "+extent+")");
            check(meanThrough/Math.max(1,throughDirections)<extent*0.6,"the way through is the middle of the break");

            // Area: enough of the break to be a portal, nowhere near enough to be an invisible disc.
            int inside=0,through2=0,samples=0;
            for(double x=-extent;x<=extent;x+=extent/60)for(double z=-extent;z<=extent;z+=extent/60){
                samples++;boolean any=false,open=false;
                for(var piece:pieces){if(piece.contains(x,z)){any=true;if(piece.solid){open=true;break;}}}
                if(any)inside++;
                if(open)through2++;
            }
            check(inside*20>samples,"the break covers a real share of its own footprint");
            check(through2<inside,"not every crack you can see is a way through");
            check(through2*3<samples,"the teleport is nothing like the box it lives in");
        }
        check(worstRatio>2.2&&leastPointed>=8,"every seed shatters ("+worstRatio+", "+leastPointed+" points)");

        // Two breaks are not the same break.
        double[] a=profile(WarpFracture.build(11,reach,1),32,false),b=profile(WarpFracture.build(12,reach,1),32,false);
        double apart=0;for(int i=0;i<32;i++)apart+=Math.abs(a[i]-b[i]);
        check(apart/32>1.0,"two breaks differ by more than a block in the average direction ("+apart/32+")");
        double[] again=profile(WarpFracture.build(11,reach,1),32,false);
        for(int i=0;i<32;i++)check(again[i]==a[i],"the same seed is the same break, on both sides of the wire");
    }

    /**
     * Charging develops the break rather than enlarging one shape.
     *
     * <p>A pattern that only scaled would keep its outline exactly and simply get bigger, and a
     * pattern that re-rolled would lose the cracks it already had. Neither is what a mirror does.
     */
    private static void growsRatherThanScaling(){
        for(long seed=20;seed<26;seed++){
            var early=WarpFracture.build(seed,WarpMath.reach(35),WarpMath.charge(35));
            var late=WarpFracture.build(seed,WarpMath.reach(WarpMath.FULL_CHARGE),1);
            check(late.size()>early.size(),"more of the glass has broken by full charge");
            double e1=WarpFracture.extent(early),e2=WarpFracture.extent(late);
            check(e2>e1*1.5,"and the fractures have travelled ("+e1+" to "+e2+")");
            double[] a=profile(early,32,false),b=profile(late,32,false);
            double shape=0,kept=0;
            for(int i=0;i<32;i++){
                shape+=Math.abs(a[i]/e1-b[i]/e2);
                if(a[i]<=b[i]+1.0E-9)kept++;
            }
            check(shape/32>0.02,"the outline is not the same outline scaled up ("+shape/32+")");
            check(kept>=30,"nothing that had already cracked has closed up again ("+kept+" of 32)");
        }
    }

    /** A wider tear costs more, and what cannot be paid for is not opened. */
    private static void sizeIsPaidFor(){
        check(WarpMath.costScale(0)==.5&&WarpMath.costScale(WarpMath.FULL_CHARGE)==2,"half price at nothing, double at full");
        double previous=-1;
        for(int held=0;held<=WarpMath.FULL_CHARGE;held++){
            double scale=WarpMath.costScale(held);
            check(scale>previous,"every extra tick of hold costs more than the last");
            previous=scale;
        }
        check(WarpMath.costScale(500)==WarpMath.costScale(WarpMath.FULL_CHARGE),"holding past full buys nothing further");
        double cost=80;
        check(WarpMath.affordable(0,cost)==0&&WarpMath.affordable(cost*2,cost)==WarpMath.FULL_CHARGE,"an empty pool opens nothing and a full one opens everything");
        for(int held=0;held<=WarpMath.FULL_CHARGE;held+=7){
            int afforded=WarpMath.affordable(cost*WarpMath.costScale(held),cost);
            check(Math.abs(afforded-held)<=1,"energy converts back to the charge it pays for ("+held+" -> "+afforded+")");
            check(cost*WarpMath.costScale(afforded)<=cost*WarpMath.costScale(held)+1.0E-9,"and never sells a break it cannot pay for");
        }
    }

    /** How far the break reaches in each direction, by marching inward until something is there. */
    private static double[] profile(java.util.List<WarpFracture.Piece> pieces,int directions,boolean openOnly){
        double extent=WarpFracture.extent(pieces);
        double[] out=new double[directions];
        for(int i=0;i<directions;i++){
            double angle=i*Math.PI*2/directions;
            for(double d=extent;d>0;d-=extent/150){
                double x=Math.cos(angle)*d,z=Math.sin(angle)*d;
                boolean hit=false;
                for(var piece:pieces)if((!openOnly||piece.solid)&&piece.contains(x,z)){hit=true;break;}
                if(hit){out[i]=d;break;}
            }
        }
        return out;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
