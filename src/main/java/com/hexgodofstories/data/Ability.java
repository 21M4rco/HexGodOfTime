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
    TELEKINESIS(SORCERY,140,0,25,false,"Telekinesis","Aim at a creature or player to lift it in a green glow, your arm held out toward it. Scroll to push it away or pull it closer. Secondary: throw it. Cast again (or Utility) to let it go."),
    BLINK(SORCERY,220,0,70,false,"Veilstep","Dissolve and reform at a safe position in your sightline."),
    WARD(SORCERY,330,0,220,false,"Runic Ward","Raise a brief defensive veil while remaining mobile."),
    // Save-compatible tombstone. Fracture's dimension now lives under Warping; never reorder/remove.
    RIFT(SORCERY,520,0,0,false,true,"Fracture (legacy)","The World Tree is now a Warping destination."),
    DAGGERS(CONJURATION,0,0,35,false,"Conjure Daggers","Manifest a dagger into an empty hand. Secondary: dismiss conjurations."),
    TWIN_DAGGERS(CONJURATION,160,0,55,false,"Twin Deceivers","Manifest two daggers, the off hand reversed. Attack: combinations. Use: throw."),
    LAEVATEINN(CONJURATION,380,0,70,false,"Scepter","Manifest the Chitauri Scepter. Hit for two hearts and one second of bleed. Hold right click at least a second to fill the stone, and release a beam that burns through up to six bodies; held the full ten seconds, it fires itself at full power. Every body it passes keeps a bleeding hole that slowly closes."),
    ENCHANT(ENCHANTMENT,0,0,100,false,"Whispered Allegiance","Charm a creature into following you. Secondary: direct it at your aim."),
    MEMORY(ENCHANTMENT,160,0,120,false,"Memory Echo","Reveal the recent footsteps of a nearby entity."),
    TIME_SLIP(TEMPORAL,0,15,160,false,"Time Slipping","Early slips return to an unstable recent moment. Mastery grants control."),
    REWIND(TEMPORAL,180,35,200,false,true,"Personal Rewind","Return to your own state ten seconds ago. Permanent key; never in the quick bar."),
    // Save-compatible tombstone: removing this ordinal would silently remap every saved ability.
    SLOW_FIELD(TEMPORAL,320,0,0,false,true,"Removed",""),
    TIME_STOP(TEMPORAL,560,0,0,false,true,"Stillness","Only while fully transformed (Glorious Purpose): raise your right arm, then bring it down to stop time. Costs five Temporal Energy per second until resumed, and ends if the transformation does."),
    SELECTIVE_STOP(TEMPORAL,740,30,160,false,"Chosen Moment","Suspend one target. Secondary: exempt one ally from your field."),
    THREADS(PURPOSE,0,25,200,false,"Anchor Being","Vanish for 10 seconds, leaving an exact double. When hit, it laughs for 2 seconds, raises both arms and erupts in a green blast: 20 hearts and 20 seconds of nausea, without breaking blocks. Hold secondary for Gravity Grasp: a growing pull for up to 10 seconds, automatically slashing arriving targets for 5 hearts and 10 seconds of bleed."),
    ASCENSION(PURPOSE,700,100,400,false,"Glorious Purpose","Weave the final mantle, living cloak and dark crown: armour, resistance and Strength I, for 2 Temporal Energy a second. Anywhere but the Overworld, toggle Cosmic Flight with its key (5 a second while flying); Space rises and crouch descends. Taking it off is free; worn to no energy, it falls away."),
    TIME_BRANCH(PURPOSE,900,120,700,true,true,"Time Branch Unleashing","M key, transformed only. Tap for the charged right fist; hold to plant yourself and compress the Time Branches between both hands. Maximum charge takes twelve uninterrupted seconds. Release early to banish living targets; only a fully charged torrent truly kills. Terrain restores exactly."),
    WARPING(SORCERY,800,80,400,true,"Warping","Hold R on solid ground to spread a black Warping pool, up to 16 blocks across. G chooses a destination, including your World Tree sanctum. Release R to open it, then sink through. Y reaches into normal Warping realms. Inside the World Tree, G opens the preserved exit selector and R leaves through that saved route. Every Warping arrival rises slowly out of a black puddle."),
    // Appended, never inserted: an ability's ordinal is what saves and quick bars remember it by.
    ARSENAL(CONJURATION,620,200,400,true,"Crown of Barrels","After the Scepter, Loki's last conjuration, and the only one that puts nothing in your hands. Hold the cast key: both arms rise and machine guns form one by one in an arch over you, then fire in ragged bursts wherever you look for as long as you hold. Their rounds barely sting: each one stuns for a second and every next one starts that second again, and they go through bodies, never blocks, leaving tiny bleeding holes; a shield raised toward you takes them, stun and all. Let go and the guns come apart. Hold for thirteen seconds and you throw your arms down: two missiles form beside your head and wander, slow and never straight, to where you aimed, forty hearts each to whatever they strike, which no shield stops, and blow a crater that knits itself shut behind them. Whatever your fire still holds stays held until they land. Or tap the alternate key while looking at a body: Gotcha! A single gun forms without a sound a little way behind it, turns onto its head and fires, never missing unless a block is in the way: ten hearts, a bigger hole and five seconds of bleeding, and its own seven-second recovery, apart from the crown's.");
    public final Discipline discipline; public final int level,cost,cooldown; public final boolean hold,dedicated; public final String title,description;
    Ability(Discipline d,int l,int cost,int cd,boolean hold,String title,String description) {this(d,l,cost,cd,hold,false,title,description);}
    Ability(Discipline d,int l,int cost,int cd,boolean hold,boolean dedicated,String title,String description) {
        this.discipline=d;this.level=l;this.cost=cost;this.cooldown=cd;this.hold=hold;this.dedicated=dedicated;this.title=title;this.description=description;
    }
    public static Ability at(int id) {return values()[Math.floorMod(id,values().length)];}
    /** @return the ability for an ordinal, or null when the slot is empty or out of range. */
    public static Ability slot(int id) {return id<0||id>=values().length?null:values()[id];}
}
