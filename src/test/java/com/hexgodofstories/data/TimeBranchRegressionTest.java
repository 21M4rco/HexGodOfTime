package com.hexgodofstories.data;

/** Regressions in input arbitration can accidentally unleash a world-altering beam. */
public final class TimeBranchRegressionTest {
    public static void main(String[] args) {
        for(int duration:new int[]{0,1,49,50,100,199}) {
            BranchTapGesture g=new BranchTapGesture();g.press(1000);
            require(g.tick(1000+duration)==BranchTapGesture.Action.NONE,"tap began full attack");
            require(g.release(1000+duration)==BranchTapGesture.Action.TAP,"short tap was lost");
            require(g.release(1500)==BranchTapGesture.Action.NONE,"duplicate release activated");
        }
        for(int duration:new int[]{200,201,500,10000}) {
            BranchTapGesture g=new BranchTapGesture();g.press(1000);
            require(g.tick(1000+duration)==BranchTapGesture.Action.BEGIN,"hold never promoted");
            require(g.tick(1000+duration)==BranchTapGesture.Action.NONE,"hold promoted twice");
            require(g.release(1000+duration)==BranchTapGesture.Action.END,"hold became tap");
        }
        BranchTapGesture cancelled=new BranchTapGesture();cancelled.press(1000);cancelled.cancel();
        require(cancelled.release(1050)==BranchTapGesture.Action.NONE,"GUI cancellation fired");
        require(cancelled.tick(2000)==BranchTapGesture.Action.NONE,"world change left pending hold");
        cancelled.press(3000);require(cancelled.tick(3200)==BranchTapGesture.Action.BEGIN,"next hold failed");
        cancelled.cancel();require(cancelled.release(3400)==BranchTapGesture.Action.NONE,"cancelled full hold fired again");

        for(boolean implosion:new boolean[]{false,true})for(int seed=0;seed<1000;seed++) {
            float front=ErasureFragmentMath.release(0,0,seed,implosion);
            float back=ErasureFragmentMath.release(1,1,seed,implosion);
            require(back>front,"destruction did not progress through the body");
            for(float release:new float[]{front,back}) {
                require(ErasureFragmentMath.alpha(ErasureFragmentMath.age(release-.001f,release,implosion))==1,
                    "intact body faded before breaking");
                require(ErasureFragmentMath.alpha(ErasureFragmentMath.age(1,release,implosion))==0,
                    "fragment survived after entity removal");
            }
        }
        float t1=ErasureFragmentMath.travel(.2f,1,false),t2=ErasureFragmentMath.travel(.4f,1,false),t3=ErasureFragmentMath.travel(.6f,1,false);
        require(t3-t2>t2-t1,"beam fragments failed to accelerate downstream");
        require(BranchFistState.WINDOW==200,"charge is no longer ten seconds");
        require(BranchFistState.RECOVERY<700/3,"tap recovery is not substantially shorter");
        require(BranchFistState.IMPLOSION==240,"punch must disintegrate gradually over twelve seconds");
        require(BranchCharge.SWEEP>=2.0&&BranchCharge.SWEEP<=2.5,
            "held Time Branch must advance as a slow visible wave, not an instant laser");
        require(BranchCharge.front(BranchCharge.RANGE,20)<50,
            "twenty ticks must not already fill most of the hundred-block beam");
        System.out.println("Time Branch input boundaries, slow sweep and fragment invariants passed.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
