package com.hexgodofstories.data;
import static com.hexgodofstories.data.Discipline.*;

/**
 * One catalogue entry per castable action. {@code hold} marks abilities that charge while the cast key is
 * held; {@code dedicated} marks the core time controls, which own a permanent key each and are therefore
 * never placed in — nor reachable from — the quick bar.
 */
public enum Ability {
    DUPLICATE(MISCHIEF,0,0,80,false,"Living Projection","Create a convincing decoy. Secondary: aim at a foe to send every projection at it, or aim at nothing to dismiss them."),
    PROJECTION_SWAP(MISCHIEF,120,0,100,false,"Sleight of Place","Swap with your nearest projection. Secondary: place a projection at your aim."),
    MASQUERADE(MISCHIEF,240,0,100,false,"Masquerade","Borrow a humanoid appearance. Secondary: remove your disguise."),
    MIRAGE(MISCHIEF,430,0,260,false,"Court of Lies","Scatter independent decoys and briefly vanish."),
    ARCHITECTURE(MISCHIEF,650,0,220,true,"Borrowed Reality","Hold to raise a false wall where you aim; it grows Small, Medium, Big, Massive while you hold. Creatures believe it \u2014 they path around it and lose sight of you behind it \u2014 while players walk straight through. Secondary: dismiss it."),
    BOLT(SORCERY,0,0,20,false,"Emerald Throw","Hurl a fistful of seidr. Secondary: a charged throw that bursts where it lands."),
    PUSH(SORCERY,70,0,70,false,"Sovereign Push","Repel nearby threats without destroying the landscape."),
    TELEKINESIS(SORCERY,140,0,25,false,"Invisible Hand","Aim to hold an entity; cast again to add another. Scroll to push or pull. Secondary: throw. Utility: release."),
    BLINK(SORCERY,220,0,70,false,"Veilstep","Dissolve and reform at a safe position in your sightline."),
    WARD(SORCERY,330,0,220,false,"Runic Ward","Raise a brief defensive veil while remaining mobile."),
    // Save-compatible tombstone. Fracture's dimension now lives under Warping; never reorder/remove.
    RIFT(SORCERY,520,0,0,false,true,"Fracture (legacy)","The World Tree is now a Warping destination."),
    DAGGERS(CONJURATION,0,0,35,false,"Conjure Daggers","Manifest a dagger into an empty hand. Secondary: dismiss conjurations."),
    TWIN_DAGGERS(CONJURATION,160,0,55,false,"Twin Deceivers","Manifest two daggers, the off hand reversed. Attack: combinations. Use: throw."),
    LAEVATEINN(CONJURATION,380,0,70,false,"Laevateinn","Manifest the Void sword with its own choreography."),
    ENCHANT(ENCHANTMENT,0,0,100,false,"Whispered Allegiance","Charm a creature into following you. Secondary: direct it at your aim."),
    MEMORY(ENCHANTMENT,160,0,120,false,"Memory Echo","Reveal the recent footsteps of a nearby entity."),
    TIME_SLIP(TEMPORAL,0,15,160,false,"Time Slipping","Early slips return to an unstable recent moment. Mastery grants control."),
    REWIND(TEMPORAL,180,35,360,false,true,"Personal Rewind","Return to your recent safe position and recover limited health. Permanent key; never in the quick bar."),
    SLOW_FIELD(TEMPORAL,320,30,260,false,true,"Dilation","Slow nearby entities and projectiles smoothly, without changing server time. Permanent key; never in the quick bar."),
    TIME_STOP(TEMPORAL,560,70,600,false,true,"Stillness","Suspend a local battlefield \u2014 bodies, shots, weather and all. Harm dealt to the suspended lands the instant time resumes. Permanent key, with its own key to resume."),
    SELECTIVE_STOP(TEMPORAL,740,30,160,false,"Chosen Moment","Suspend one target. Secondary: exempt one ally from your field."),
    THREADS(PURPOSE,0,25,80,false,"Temporal Threads","Bind a target in time. Secondary: pull it along the strand."),
    ASCENSION(PURPOSE,700,100,400,false,"Glorious Purpose","Weave the final mantle, living cloak and dark crown. Inside your own fracture world, toggle Cosmic Flight with its key; Space rises and crouch descends. No other dimension grants flight."),
    TIME_BRANCH(PURPOSE,900,120,700,true,"Time Branch Unleashing","Transformed only. Hold to plant yourself and compress the Time Branches between both hands; release to open them through reality. Ten seconds is the hard limit, full power at five and a half. A hundred blocks of torrent that nothing stops: everything it passes through becomes Nothingness and is put back about half a minute later, exactly as it was. Anything living caught inside is erased from the timeline."),
    WARPING(SORCERY,800,80,400,true,"Warping","Hold R on solid ground to spread a black Warping pool, up to 16 blocks across. G chooses a destination, including your World Tree sanctum. Release R to open it, then sink through. Y reaches into normal Warping realms. Inside the World Tree, G opens the preserved exit selector and R leaves through that saved route. Every Warping arrival rises slowly out of a black puddle.");
    public final Discipline discipline; public final int level,cost,cooldown; public final boolean hold,dedicated; public final String title,description;
    Ability(Discipline d,int l,int cost,int cd,boolean hold,String title,String description) {this(d,l,cost,cd,hold,false,title,description);}
    Ability(Discipline d,int l,int cost,int cd,boolean hold,boolean dedicated,String title,String description) {
        this.discipline=d;this.level=l;this.cost=cost;this.cooldown=cd;this.hold=hold;this.dedicated=dedicated;this.title=title;this.description=description;
    }
    public static Ability at(int id) {return values()[Math.floorMod(id,values().length)];}
    /** @return the ability for an ordinal, or null when the slot is empty or out of range. */
    public static Ability slot(int id) {return id<0||id>=values().length?null:values()[id];}
}
