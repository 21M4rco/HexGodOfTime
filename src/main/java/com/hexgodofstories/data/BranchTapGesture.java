package com.hexgodofstories.data;

/** Only the Time Branch key uses this arbiter. No spell starts during the tap window. */
public final class BranchTapGesture {
    public static final long TAP_MILLIS=200;
    public enum Action { NONE, TAP, BEGIN, END }
    private boolean pressed,holding;
    private long start;

    public void press(long now) {if(!pressed){pressed=true;holding=false;start=now;}}
    public Action tick(long now) {
        if(pressed&&!holding&&now-start>=TAP_MILLIS){holding=true;return Action.BEGIN;}
        return Action.NONE;
    }
    public Action release(long now) {
        if(!pressed)return Action.NONE;
        pressed=false;
        if(holding){holding=false;return Action.END;}
        // A stalled client may observe release before its next tick. It is still a hold, never a tap.
        return now-start<TAP_MILLIS?Action.TAP:Action.END;
    }
    public boolean holding(){return holding;}
    public boolean pressed(){return pressed;}
    public void cancel(){pressed=false;holding=false;}
}
