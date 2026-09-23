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
        spreads();
        spreadsRatherThanScaling();
        sizeIsPaidFor();
        theEdgeIsAnEdge();
        quicksand();
        check(WarpMath.pull(10)>WarpMath.pull(40)&&WarpMath.pull(40)>WarpMath.pull(100),"inward pull intensifies");
        check(WarpMath.cellX(1024+200)==1024&&WarpMath.cellX(2048-100)==2048,"separate instances remain separate");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA)==0,"outside corona is safe");
        check(WarpMath.solarDamage(WarpMath.SUN_CORONA-.01)>0,"corona inflicts heat");
        check(WarpMath.solarDamage(WarpMath.SUN_RADIUS-.01)>WarpMath.solarDamage(WarpMath.SUN_RADIUS+.01),"photosphere is lethal faster than corona");
        check(WarpMath.SUN_RADIUS>33+Math.sqrt(3),"photosphere encloses every corner of the existing magma shell");
        System.out.println("Warping geometry, pool, sink and cost checks passed");
    }

    /**
     * The silhouette is the fracture, and a fracture is not a circle with cracks drawn on it.
     *
     * <p>Every property here is one the old thirty-two sided outline failed. An even radius, a
     * rounded end, a way through that reaches the tip of a hairline and a shape that is the same
     * every time are all things that would pass a build and read as a decorated disc in play.
     */
    /**
     * A pool, and not a circle.
     *
     * <p>The portal was a shattered mirror and is now a spreading liquid, which moves every property
     * worth checking. A break had to be violently uneven and end in points; a pool has to be the
     * opposite — closed, smooth, and nowhere spiked — while still never settling on a radius, because
     * a perfect circle of liquid reads as a decal and a spiked one reads as glass again.
     */
    private static void spreads(){
        double reach=WarpMath.reach(WarpMath.FULL_CHARGE);
        double[] first=null;
        for(long seed=1;seed<=16;seed++){
            double[] rim=WarpPool.rim(seed,reach,1);
            double extent=WarpPool.extent(rim),least=WarpPool.narrowest(rim);
            check(extent>8&&extent<=reach+1.0E-9,"a full pool runs to what it was paid for and no further ("+extent+")");
            check(least>extent*.35,"it is a pool everywhere rather than a spur off one side");
            check(extent/least>1.25,"and it never settles on a radius ("+(extent/least)+")");
            double worst=0;
            for(int i=0;i<rim.length;i++)worst=Math.max(worst,Math.abs(rim[i]-rim[(i+1)%rim.length]));
            check(worst<extent*.07,"the outline is smooth: no direction disagrees with its neighbour ("+worst+")");
            check(WarpPool.inside(rim,0,0),"the middle of a pool is liquid");
            check(!WarpPool.inside(rim,extent+.5,0)&&!WarpPool.inside(rim,0,extent+.5),"and past its rim is floor");
            if(first==null){first=rim;continue;}
            double difference=0;
            for(int i=0;i<rim.length;i++)difference+=Math.abs(rim[i]-first[i]);
            check(difference/rim.length>extent*.02,"two seeds are two different pools");
        }
    }

    /**
     * Holding the key pours more, rather than resizing what is there.
     *
     * <p>Two things, and the first is the one a player feels: no direction may ever come back in
     * while the charge is being held, so the liquid only ever gains ground. The second is what stops
     * that from reading as a circle being inflated — the directions run at different times, so the
     * shape at full charge is not the shape at a third of it with a bigger number in front.
     */
    private static void spreadsRatherThanScaling(){
        double reach=WarpMath.reach(WarpMath.FULL_CHARGE);
        for(long seed=1;seed<=12;seed++){
            double[] early=WarpPool.rim(seed,reach,.35),late=WarpPool.rim(seed,reach,1);
            double thinnest=Double.MAX_VALUE,widest=0;
            for(int i=0;i<early.length;i++){
                double grown=late[i]/Math.max(1.0E-6,early[i]);
                thinnest=Math.min(thinnest,grown);widest=Math.max(widest,grown);
            }
            check(widest/thinnest>1.3,"the pool spreads unevenly rather than scaling up ("+(widest/thinnest)+")");
        }
        for(long seed=1;seed<=6;seed++){
            double[] before=null;
            for(int held=0;held<=WarpMath.FULL_CHARGE;held+=4){
                double[] rim=WarpPool.rim(seed,WarpMath.reach(held),WarpMath.charge(held));
                if(before!=null)for(int i=0;i<rim.length;i++)
                    check(rim[i]>=before[i]-1.0E-9,"holding longer only ever adds liquid (direction "+i+" at "+held+")");
                before=rim;
                // Smooth at every moment of the pour, not only once it has finished: a pool that is
                // ragged while it is still running is a pool that reads as cracking.
                double extent=WarpPool.extent(rim),worst=0;
                for(int i=0;i<rim.length;i++)worst=Math.max(worst,Math.abs(rim[i]-rim[(i+1)%rim.length]));
                check(extent<=0||worst<extent*.11,"the outline stays smooth part way through the pour ("+worst/extent+")");
            }
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


    /**
     * The rim is an edge, not a trigger.
     *
     * <p>A body now sinks through the pool rather than being teleported by touching it, which makes
     * {@link WarpPool#footing} the thing that decides where the floor stops. Every property here is
     * one that, if it stopped holding, would put the invisible square trigger back: a footprint that
     * goes through dry floor beside the pool, a rule that ignores how wide the body asking is, or an
     * edge with no band at all — which is what "you may stand with one foot in it" means in numbers.
     */
    private static void theEdgeIsAnEdge(){
        double reach=WarpMath.reach(WarpMath.FULL_CHARGE);
        for(long seed=1;seed<=12;seed++){
            double[] rim=WarpPool.rim(seed,reach,1);
            double extent=WarpPool.extent(rim);
            check(WarpPool.footing(rim,0,0,.6),"a body standing in the middle of a pool goes through it");
            check(!WarpPool.footing(rim,extent+2,0,.6),"floor beside the pool stays floor");
            int within=0,player=0,wide=0;
            for(double x=-extent;x<=extent;x+=.12)for(double z=-extent;z<=extent;z+=.12){
                boolean liquid=WarpPool.inside(rim,x,z);
                boolean small=WarpPool.footing(rim,x,z,.6),large=WarpPool.footing(rim,x,z,2.0);
                // Both sizes need liquid under their middle. What differs is how much of the rest
                // of them has to be over it, and on a lobed outline that is not a strict subset
                // point by point — a wide footprint can reach across a notch that a narrow one sits
                // in. It is a subset in the aggregate, which is the claim worth making.
                check(!small||liquid,"nothing narrow goes through where there is no pool");
                check(!large||liquid,"nothing wide goes through where there is no pool");
                if(liquid)within++;
                if(small)player++;
                if(large)wide++;
            }
            check(within>0&&player>0,"the pool has somewhere to go through");
            check(player<=within&&wide<=player,"what carries a body is never more than the pool itself");
            // Standing at the rim with part of you over the liquid is standing on the floor. This
            // is the whole of "you may put one foot in", and the thing that stops the outline from
            // behaving like a trigger a little wider than it looks.
            int overhanging=0;
            for(int i=0;i<rim.length;i+=3){
                double angle=i*Math.PI*2/rim.length,r=WarpPool.radius(rim,angle);
                for(double out=.25;out<=.71;out+=.22){
                    overhanging++;
                    check(!WarpPool.footing(rim,Math.cos(angle)*(r+out),Math.sin(angle)*(r+out),2.0),
                        "a body whose middle is outside the rim stands on the floor, however much of it overhangs");
                }
            }
            check(overhanging>0,"the rim was actually walked");
        }
    }

    /**
     * The pool is quicksand, and getting out of it is a fight you can lose.
     *
     * <p>Two feelings are being defended here and they pull against each other. The sink has to be
     * slow enough to be a thing that happens to you rather than a thing that has happened — the
     * other world comes up around you while you are still standing in this one — and the way out has
     * to be genuinely hard, because a pool you can casually step back out of is not a pool, it is a
     * button with a delay on it. Both are decided by two constants and the arithmetic between them,
     * so both are checkable without a world.
     */
    private static void quicksand(){
        double min=WarpMath.gooStrength(WarpMath.MIN_CHARGE);
        double full=WarpMath.gooStrength(WarpMath.FULL_CHARGE);
        check(WarpMath.sinkRate(min)>.042,"small goo is slightly faster than the old sink");
        check(WarpMath.sinkRate(full)>WarpMath.sinkRate(min),"holding longer makes the sink stronger");
        check(WarpMath.sinkRate(full)<.06,"full charge is still a slow sink rather than a drop");

        double slow=WarpMath.sinkTicks(1.62,WarpMath.MIN_CHARGE);
        double hard=WarpMath.sinkTicks(1.62,WarpMath.FULL_CHARGE);
        check(slow>30&&slow<50,"small pool swallows a player's view slowly ("+slow/20+"s)");
        check(hard>28&&hard<slow,"full pool is faster but still visible ("+hard/20+"s)");
        check(slow>freeFall(1.62)*4,"goo is nothing like free-fall");
        check(WarpMath.sinkDrag(full)<WarpMath.sinkDrag(min),"more charge makes sideways movement heavier");
        check(WarpMath.sinkDrag(full)>.65,"full goo drags rather than freezing movement");
        check(WarpMath.gooPull(full)>WarpMath.gooPull(min),"more charge pulls harder toward the middle");

        double rate=WarpMath.struggleRate(WarpMath.MIN_CHARGE);
        check(rate>5&&rate<8,"small pools are a frantic but possible escape ("+rate+" presses/s)");
        check(!Double.isFinite(WarpMath.struggleRate(WarpMath.FULL_CHARGE)),
            "max charge cannot be jump-spammed out of");
        check(WarpMath.inescapable(full)&&WarpMath.struggleLift(full)==0,
            "full charge is the point of no return");
        check(WarpMath.struggleLift(.8)<WarpMath.struggleLift(.3),
            "escape leverage worsens with charge");

        check(sunk(80,0,WarpMath.MIN_CHARGE)<-2,"doing nothing sinks through a small pool");
        check(sunk(80,0,WarpMath.FULL_CHARGE)<sunk(80,0,WarpMath.MIN_CHARGE),
            "max charge sinks further over the same time");
        check(sunk(160,rate*1.45,WarpMath.MIN_CHARGE)>0,
            "frantic thrashing can still climb from a small pool");
        check(sunk(160,30,WarpMath.FULL_CHARGE)<0,
            "absurd jump input still cannot reverse max charge");
    }

    private static double sunk(int ticks,double perSecond,int held){
        double strength=WarpMath.gooStrength(held),y=0,carry=0;
        for(int t=0;t<ticks;t++){
            carry+=perSecond/20;
            int presses=0;
            while(carry>=1){carry-=1;presses++;}
            double lift=WarpMath.struggleLift(strength);
            y+=-WarpMath.sinkRate(strength)+Math.min(presses*lift,lift*3);
        }
        return y;
    }

    /** Ticks vanilla gravity takes to drop a body this far, for the comparison that matters. */
    private static double freeFall(double blocks){
        double v=0,fallen=0;
        for(int t=1;t<=400;t++){v=(v-.08)*.98;fallen-=v;if(fallen>=blocks)return t;}
        return 400;
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
