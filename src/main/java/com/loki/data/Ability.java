package com.loki.data;
import static com.loki.data.Discipline.*;

/** One catalogue entry per castable action. {@code hold} marks abilities that charge while the cast key is held. */
public enum Ability {
    DUPLICATE(MISCHIEF,0,0,80,false,"Living Projection","Create a convincing decoy. Secondary: aim at a foe to send every projection at it, or aim at nothing to dismiss them."),
    PROJECTION_SWAP(MISCHIEF,120,0,100,false,"Sleight of Place","Swap with your nearest projection. Secondary: place a projection at your aim."),
    MASQUERADE(MISCHIEF,240,0,100,false,"Masquerade","Borrow a humanoid appearance. Secondary: remove your disguise."),
    MIRAGE(MISCHIEF,430,0,260,false,"Court of Lies","Scatter independent decoys and briefly vanish."),
    ARCHITECTURE(MISCHIEF,650,0,220,true,"Borrowed Reality","Hold to raise a false building where you aim. It has no collision. Secondary: change the design."),
    BOLT(SORCERY,0,0,16,false,"Emerald Spark","A precise magical bolt. Secondary: a charged impact."),
    PUSH(SORCERY,70,0,70,false,"Sovereign Push","Repel nearby threats without destroying the landscape."),
    TELEKINESIS(SORCERY,140,0,25,false,"Invisible Hand","Aim to hold an entity; cast again to add another. Scroll to push or pull. Secondary: throw. Utility: release."),
    BLINK(SORCERY,220,0,70,false,"Veilstep","Dissolve and reform at a safe position in your sightline."),
    WARD(SORCERY,330,0,220,false,"Runic Ward","Raise a brief defensive veil while remaining mobile."),
    RIFT(SORCERY,520,40,900,false,"Fracture","Shatter the surface of reality where you look. The break holds for seven seconds and opens onto a sanctum that answers to you alone."),
    DAGGERS(CONJURATION,0,0,35,false,"Conjure Daggers","Manifest a dagger into an empty hand. Secondary: dismiss conjurations."),
    TWIN_DAGGERS(CONJURATION,160,0,55,false,"Twin Deceivers","Manifest two daggers, the off hand reversed. Attack: combinations. Use: throw."),
    LAEVATEINN(CONJURATION,380,0,70,false,"Laevateinn","Manifest the Void sword with its own choreography."),
    ENCHANT(ENCHANTMENT,0,0,100,false,"Whispered Allegiance","Charm a creature into following you. Secondary: direct it at your aim."),
    MEMORY(ENCHANTMENT,160,0,120,false,"Memory Echo","Reveal the recent footsteps of a nearby entity."),
    TIME_SLIP(TEMPORAL,0,15,160,false,"Time Slipping","Early slips return to an unstable recent moment. Mastery grants control."),
    REWIND(TEMPORAL,180,35,360,false,"Personal Rewind","Return to your recent safe position and recover limited health."),
    SLOW_FIELD(TEMPORAL,320,30,260,false,"Dilation","Slow nearby entities and projectiles without changing server time."),
    TIME_STOP(TEMPORAL,560,70,600,false,"Stillness","Suspend a local battlefield. Harm dealt to the suspended lands the instant time resumes. Secondary or utility: resume."),
    SELECTIVE_STOP(TEMPORAL,740,30,160,false,"Chosen Moment","Suspend one target. Secondary: exempt one ally from your field."),
    THREADS(PURPOSE,0,25,80,false,"Temporal Threads","Bind a target in time. Secondary: pull it along the strand."),
    ASCENSION(PURPOSE,700,100,400,false,"Glorious Purpose","Weave the final mantle, living cloak and dark crown while walking.");
    public final Discipline discipline; public final int level,cost,cooldown; public final boolean hold; public final String title,description;
    Ability(Discipline d,int l,int cost,int cd,boolean hold,String title,String description) {this.discipline=d;this.level=l;this.cost=cost;this.cooldown=cd;this.hold=hold;this.title=title;this.description=description;}
    public static Ability at(int id) {return values()[Math.floorMod(id,values().length)];}
    /** @return the ability for an ordinal, or null when the slot is empty or out of range. */
    public static Ability slot(int id) {return id<0||id>=values().length?null:values()[id];}
}
