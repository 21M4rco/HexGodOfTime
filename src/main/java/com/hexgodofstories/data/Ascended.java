package com.hexgodofstories.data;

/**
 * What the full transformation (Glorious Purpose) makes of each ability. Worn, the mantle changes how most spells
 * are cast: the same key, the same slot, a different spell. The conjured weapons (the daggers and the Scepter) are
 * left as they are, and the time commands that only the mantle can use were never anything else.
 *
 * <p>Words and numbers only, read by the server (which casts the variant) and the client (which names it on the
 * strip, the panel and the archive alike), so the two can never describe different spells.
 */
public final class Ascended {
    private Ascended() {}

    /** Whether the mantle changes this ability at all. */
    public static boolean changes(Ability a) {return !title(a).isEmpty();}

    /** The variant's name, or empty when the mantle leaves the ability as it is. */
    public static String title(Ability a) {
        return switch(a) {
            case DUPLICATE -> "Living Legion";
            case PROJECTION_SWAP -> "Exchange";
            case MASQUERADE -> "Impostor";
            case MIRAGE -> "Mass Delusion";
            case ARCHITECTURE -> "Reality Made";
            case BOLT -> "Emerald Storm";
            case PUSH -> "Kneel";
            case TELEKINESIS -> "Many Hands";
            case BLINK -> "Behind You";
            case WARD -> "Mirror Ward";
            case ENCHANT -> "Silver Tongue";
            case MEMORY -> "Total Recall";
            case TIME_SLIP -> "Slipstream";
            case REWIND -> "Return to Sender";
            case SELECTIVE_STOP -> "Chosen Many";
            case WARPING -> "Wide Open";
            case ARSENAL -> "Gotcha! Swarm";
            case THREADS -> "Grand Anchor";
            default -> "";
        };
    }

    /** What the variant does, for the archive. */
    public static String text(Ability a) {
        return switch(a) {
            case DUPLICATE -> "Two projections at a time, up to two past your usual limit, and they strike twice as hard. Struck down, one bursts in seidr shards: two hearts, a moment's blindness and a slowing to everything around it.";
            case PROJECTION_SWAP -> "Trade places with the creature or player you are looking at, up to thirty blocks away. It is left where you stood, turned away from you and reeling. With nothing in your look you swap with your nearest projection, as ever.";
            case MASQUERADE -> "Take its shape and leave it the stranger: the one you copied glows for ten seconds and every creature near it turns on it. A player you copy is blinded for two seconds.";
            case MIRAGE -> "The court gathers and you vanish for five seconds, and every creature within sixteen blocks forgets you and turns on the nearest other creature for eight. Players there reel, blinded a moment.";
            case ARCHITECTURE -> "The wall grows twice as fast and stands half as long again, and it is real to everyone but you: other players are shoved back from it, and arrows and anything else thrown into it are swallowed.";
            case BOLT -> "Five bolts fanned out at once, each turning after the nearest creature ahead of it. The charged throw is unchanged.";
            case PUSH -> "\"I said... kneel.\" Everything you could harm within twelve blocks is hammered to the ground: three hearts, held there three seconds, and slow to rise.";
            case TELEKINESIS -> "Everything in front of you is lifted at once, up to five bodies, in the green glow. The alternate key throws them all; casting again lets them go.";
            case BLINK -> "Look at a creature or player within thirty two blocks and step out right behind it, facing its back. Look at nothing and the step goes twice as far.";
            case WARD -> "Six seconds of the veil, and whatever strikes you is struck back: three quarters of each blow returns to whoever dealt it, and projectiles turn round and fly back at whoever loosed them.";
            case ENCHANT -> "Every creature within ten blocks that you could charm is charmed at once, up to six, for twice as long; a player in your look drops what they are holding and reels.";
            case MEMORY -> "Everything living within forty eight blocks glows through walls for twelve seconds, and the one you look at shows its trail as before.";
            case TIME_SLIP -> "You slip back exactly three seconds, and every creature within ten blocks of you is dragged back three seconds along its own path.";
            case REWIND -> "Rewind as ever, and every blow you took in those ten seconds lands on whoever dealt it.";
            case SELECTIVE_STOP -> "The one you look at, and every creature within six blocks of it, up to six, is suspended together.";
            case WARPING -> "No hold: a tap of the cast key opens the pool at once at its full size where you look, for the full cost.";
            case ARSENAL -> "The alternate key's Gotcha! sends four small missiles instead of a gun. Slow and wandering, they hunt the body you looked at, five hearts and a small blast each, and can be outrun or dodged.";
            case THREADS -> "The copy's blast is sixty blocks across and whites out the eyes of anyone watching it; two minutes' recovery.";
            default -> "";
        };
    }

    /** One line for the panel while the mantle is worn. */
    public static String hud(Ability a) {
        return switch(a) {
            case DUPLICATE -> "Two copies that hit hard and burst.";
            case PROJECTION_SWAP -> "Swap places with what you aim at.";
            case MASQUERADE -> "Steal its face; its own turn on it.";
            case MIRAGE -> "Vanish; everything near fights itself.";
            case ARCHITECTURE -> "Hold: a wall real to all but you.";
            case BOLT -> "Five seeking bolts.";
            case PUSH -> "Everything near you must kneel.";
            case TELEKINESIS -> "Lift up to five at once.";
            case BLINK -> "Step behind whatever you aim at.";
            case WARD -> "Blows and projectiles rebound.";
            case ENCHANT -> "Charm them all; disarm a player.";
            case MEMORY -> "Everything near glows through walls.";
            case TIME_SLIP -> "Slip back; drag everyone near back.";
            case REWIND -> "Rewind; wounds go back to senders.";
            case SELECTIVE_STOP -> "Freeze the target and all around it.";
            case WARPING -> "Tap: the pool opens full size at once.";
            case ARSENAL -> "Tap: the swarm. Hold: the crown.";
            case THREADS -> "Tap: vanish (sixty wide). Hold: Grasp.";
            default -> "";
        };
    }

    /**
     * Recovery after the variant, in ticks, or -1 when it is the ability's own. Most cost more of it than the plain
     * spell: the mantle buys a bigger spell, not a faster one.
     */
    public static int recovery(Ability a) {
        return switch(a) {
            case PROJECTION_SWAP -> 160;
            case MASQUERADE -> 400;
            case MIRAGE -> 400;
            case BOLT -> 40;
            case PUSH -> 300;
            case TELEKINESIS -> 120;
            case BLINK -> 100;
            case WARD -> 300;
            case ENCHANT -> 300;
            case MEMORY -> 400;
            case TIME_SLIP -> 240;
            case SELECTIVE_STOP -> 300;
            default -> -1;
        };
    }
}
