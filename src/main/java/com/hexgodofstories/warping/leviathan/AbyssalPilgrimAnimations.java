package com.hexgodofstories.warping.leviathan;

import software.bernie.geckolib.core.animation.RawAnimation;

import javax.annotation.Nullable;

/** Named clips shared by the entity's controllers and by the renderer's procedural layer. */
public final class AbyssalPilgrimAnimations {
    private AbyssalPilgrimAnimations() { }

    private static RawAnimation loop(String name) { return RawAnimation.begin().thenLoop(name); }
    private static RawAnimation once(String name) { return RawAnimation.begin().thenPlay(name); }
    private static RawAnimation hold(String name) { return RawAnimation.begin().thenPlayAndHold(name); }

    public static final RawAnimation SWIM = loop("swim");
    public static final RawAnimation FAST_SWIM = loop("fast_swim");
    public static final RawAnimation DEEP_DIVE = loop("deep_dive");
    public static final RawAnimation VERTICAL_ASCENT = loop("vertical_ascent");
    public static final RawAnimation BREACH = once("breach");
    public static final RawAnimation AIRBORNE = loop("airborne");
    public static final RawAnimation WATER_IMPACT = once("water_impact");
    public static final RawAnimation CIRCLE = loop("circle");
    public static final RawAnimation STALK = loop("stalk");
    public static final RawAnimation OBSERVE = loop("observe");
    public static final RawAnimation FAKE_LUNGE = once("fake_lunge");
    public static final RawAnimation BITE = once("bite");
    public static final RawAnimation GRAB = once("grab");
    public static final RawAnimation DRAG = loop("drag");
    public static final RawAnimation THROW = once("throw");
    public static final RawAnimation TAIL_SWEEP = once("tail_sweep");
    public static final RawAnimation BODY_CRUSH = loop("body_crush");
    public static final RawAnimation VOID_SCREAM = once("void_scream");
    public static final RawAnimation VORTEX = loop("vortex");
    public static final RawAnimation ROAR = once("roar");
    public static final RawAnimation HURT = once("hurt");
    public static final RawAnimation FRENZY = loop("frenzy");
    public static final RawAnimation DEATH = hold("death");

    /** Maps a running attack to the clip that should be on the action controller right now. */
    @Nullable
    public static RawAnimation forAttack(LeviathanAttack attack, int tick) {
        return switch (attack) {
            case PREDATORY_BITE -> BITE;
            case ABYSSAL_LUNGE, DEEP_CHARGE, SURFACE_RAM -> tick < attack.windup ? FAKE_LUNGE : BITE;
            case TAIL_SWEEP -> TAIL_SWEEP;
            case TENDRIL_GRAB -> GRAB;
            case DRAG_BELOW -> tick < attack.windup ? GRAB : DRAG;
            case BODY_CRUSH -> BODY_CRUSH;
            case VOID_SCREAM -> VOID_SCREAM;
            case BREACH_BITE -> tick < attack.windup ? VERTICAL_ASCENT : BREACH;
            case AIR_THROW -> THROW;
            case FAKE_ATTACK -> FAKE_LUNGE;
            case WATER_VORTEX -> VORTEX;
        };
    }
}
