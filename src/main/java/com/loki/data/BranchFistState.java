package com.loki.data;

/** Keys in the existing Loki player-NBT state/cooldown ledger; no second cooldown service. */
public final class BranchFistState {
    private BranchFistState() {}
    public static final String UNTIL="branchFistUntil",START="branchFistStart",IMPACT="branchFistImpact";
    public static final String COOLDOWN="cd_TIME_BRANCH_FIST";
    public static final int WINDOW=200,RECOVERY=160,IMPLOSION=18,COST=25;
}
