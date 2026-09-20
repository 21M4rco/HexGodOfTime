package com.hexgodofstories.data;

import net.minecraft.world.item.Item;

/**
 * One thing the Fracture can currently be pointed at.
 *
 * <p>This is metadata only, and deliberately common to both sides: the selector, the HUD and the
 * server all read the same ordered catalogue, so an index on the wire means the same thing
 * everywhere. What a mode actually *does* lives beside it on the server, keyed by {@link #id}, which
 * keeps destination resolution, validation and safe placement out of the client entirely.
 *
 * <p>Adding a mode is adding one entry to {@link FractureModes} and one resolver on the server. The
 * selector, the saved state, the HUD readout and the wire format all pick it up unchanged.
 */
public final class FractureMode {
    /** Stable save key. Never renamed once shipped: it is what a player's chosen mode persists as. */
    public final String id;
    public final String title;
    public final String description;
    /** Drawn inside the selector's nebula disc. */
    public final Item icon;
    /** A mode that has to be pointed at somebody before it can run. */
    public final boolean needsTarget;
    /** A mode where holding the cast key means something beyond tapping it. */
    public final boolean usesHold;

    public FractureMode(String id,String title,String description,Item icon,boolean needsTarget,boolean usesHold) {
        this.id=id;this.title=title;this.description=description;this.icon=icon;
        this.needsTarget=needsTarget;this.usesHold=usesHold;
    }

    /** What the HUD prints after the cast key, including the chosen target when there is one. */
    public String label(String target) {
        return needsTarget&&target!=null&&!target.isEmpty()?title+" — "+target:title;
    }
}
