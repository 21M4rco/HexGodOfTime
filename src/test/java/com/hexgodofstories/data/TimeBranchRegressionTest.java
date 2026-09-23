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
        require(BranchCharge.SWEEP>=.55&&BranchCharge.SWEEP<=.75,
            "held Time Branch must advance as a genuinely slow visible wave");
        require(BranchCharge.front(BranchCharge.RANGE,100)<70,
            "five seconds must not already fill most of the hundred-block beam");
        require(BranchCharge.FULL>=240,"true lethal Time Branch must require at least twelve uninterrupted seconds");
        require(BranchCharge.LIMIT>BranchCharge.FULL,"hard limit must leave a tiny grace window after maximum charge");
        require(BranchCharge.FOCUS>=2.7,"held focus ball must stay clearly detached from the caster");
        require(BranchCharge.sphere(BranchCharge.LIMIT)<1.2,"held focus ball must not engulf the caster");
        require(BranchCharge.SAFE<=1.0,"beam must visually leave the detached focus ball without a large dead gap");
        for(float power:new float[]{0,.25f,.5f,.75f,1}) {
            double outer=BranchCharge.eraseRadius(power),inner=BranchCharge.nothingnessInnerRadius(power);
            require(inner>0&&inner<outer,"Nothingness must be a shell around a hollow beam core");
            require(outer-inner>=.6&&outer-inner<=1.05,"Nothingness shell must stay about one block thick");
        }
        require(BranchCharge.RESTORE>=60&&BranchCharge.RESTORE<=120,
            "terrain should restore only a few seconds after the beam passes");
        System.out.println("Time Branch input, slow sweep, hollow terrain shell and fragment invariants passed.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
