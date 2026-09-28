package com.hexgodofstories.data;

/** Boundary checks for a full hold, a lost key-up and a long-running server clock. Run by CI. */
public final class AnchorRulesTest {
    public static void main(String[] args) {
        require(Ability.THREADS.ordinal()==21,"existing THREADS quick-slot ordinal changed");
        require(Ability.at(21)==Ability.THREADS,"old selections must resolve to Anchor Being");
        require(AnchorRules.VANISH_TICKS==10*20&&AnchorRules.LAUGH_TICKS==2*20,"anchor timing");
        require(AnchorRules.BLAST_DAMAGE==20*2&&AnchorRules.SLASH_DAMAGE==5*2,"hearts to damage conversion");
        require(AnchorRules.NAUSEA_TICKS==20*20&&AnchorRules.BLEED_TICKS==10*20,"debuff duration");
        for(long start:new long[]{0,1L<<25,Long.MAX_VALUE-1000}) {
            for(int age=0;age<200;age++) {
                long now=start+age;
                require(!AnchorRules.expired(now-start,age%10),"heartbeat ended a valid hold");
                require(AnchorRules.speed(age+1)>AnchorRules.speed(age),"pull must grow with hold time");
            }
            require(AnchorRules.expired(start+200-start,0),"the ten-second cap must not wait for key-up");
        }
        require(!AnchorRules.expired(50,30)&&AnchorRules.expired(50,31),"missing heartbeat must release");
        require(AnchorRules.expired(-1,0),"clock/dimension reset must release");
        require(AnchorRules.speed(10000)==AnchorRules.speed(200),"pull must remain bounded");
        System.out.println("AnchorRulesTest: saved slot, damage, durations, hold cap and lease boundaries passed.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
