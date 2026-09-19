package com.loki.data;
import static com.loki.data.Discipline.*;
public enum Ability {
    DUPLICATE(MISCHIEF,0,0,80,"Living Projection","Create a convincing decoy. Secondary: dismiss projections."),
    PROJECTION_SWAP(MISCHIEF,120,0,100,"Sleight of Place","Swap with your nearest projection. Secondary: place a projection at your aim."),
    MASQUERADE(MISCHIEF,240,0,100,"Masquerade","Borrow a humanoid appearance. Secondary: remove your disguise."),
    MIRAGE(MISCHIEF,430,0,260,"Court of Lies","Scatter independent decoys and briefly vanish."),
    FALSE_TERRAIN(MISCHIEF,650,0,240,"Borrowed Reality","Place false stonework in chosen viewers' sight. It has no physical collision."),
    BOLT(SORCERY,0,0,16,"Emerald Spark","A precise magical bolt. Secondary: a charged impact."),
    PUSH(SORCERY,70,0,70,"Sovereign Push","Repel nearby threats without destroying the landscape."),
    TELEKINESIS(SORCERY,140,0,35,"Invisible Hand","Aim to hold an entity. Secondary: throw. Utility: release."),
    BLINK(SORCERY,220,0,70,"Veilstep","Dissolve and reform at a safe position in your sightline."),
    WARD(SORCERY,330,0,220,"Runic Ward","Raise a brief defensive veil while remaining mobile."),
    DAGGERS(CONJURATION,0,0,35,"Conjure Daggers","Manifest a dagger into an empty hand. Secondary: dismiss conjurations."),
    TWIN_DAGGERS(CONJURATION,160,0,55,"Twin Deceivers","Manifest two daggers. Attack: combinations. Use: throw."),
    LAEVATEINN(CONJURATION,380,0,70,"Laevateinn","Manifest the Void sword with its own choreography."),
    ENCHANT(ENCHANTMENT,0,0,100,"Whispered Allegiance","Charm a creature into following you. Secondary: direct it at your aim."),
    MEMORY(ENCHANTMENT,160,0,120,"Memory Echo","Reveal the recent footsteps of a nearby entity."),
    TIME_SLIP(TEMPORAL,0,15,160,"Time Slipping","Early slips return to an unstable recent moment. Mastery grants control."),
    REWIND(TEMPORAL,180,35,360,"Personal Rewind","Return to your recent safe position and recover limited health."),
    SLOW_FIELD(TEMPORAL,320,30,260,"Dilation","Slow nearby entities and projectiles without changing server time."),
    TIME_STOP(TEMPORAL,560,70,600,"Stillness","Suspend a local battlefield. Secondary or utility: resume time."),
    SELECTIVE_STOP(TEMPORAL,740,30,160,"Chosen Moment","Suspend one target. Secondary: exempt one ally from your field."),
    THREADS(PURPOSE,0,25,80,"Temporal Threads","Bind a target in time. Secondary: pull it along the strand."),
    ASCENSION(PURPOSE,700,100,400,"Glorious Purpose","Weave the final mantle, living cloak and dark crown while walking.");
    public final Discipline discipline; public final int level,cost,cooldown; public final String title,description;
    Ability(Discipline d,int l,int cost,int cd,String title,String description) {this.discipline=d;this.level=l;this.cost=cost;this.cooldown=cd;this.title=title;this.description=description;}
    public static Ability at(int id) {return values()[Math.floorMod(id,values().length)];}
}
