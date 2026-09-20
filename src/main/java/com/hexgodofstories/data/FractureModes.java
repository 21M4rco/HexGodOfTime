package com.hexgodofstories.data;

import net.minecraft.world.item.Items;
import java.util.*;

/**
 * The ordered catalogue of Fracture modes.
 *
 * <p>Order is the wire format, so entries are appended rather than inserted, and the string id is
 * what a player's choice is saved as. A new destination — the End, a saved anchor, a structure, a
 * team mate, somebody's mod's dimension — is one {@link #register} call here plus one resolver on
 * the server; nothing in the selector, the HUD, the save format or the packet needs touching.
 */
public final class FractureModes {
    private FractureModes() {}

    private static final List<FractureMode> ORDER=new ArrayList<>();
    private static final Map<String,FractureMode> BY_ID=new HashMap<>();

    private static FractureMode register(FractureMode mode) {
        ORDER.add(mode);BY_ID.put(mode.id,mode);return mode;
    }

    public static final FractureMode PULL=register(new FractureMode("pull","Pull Into Fracture",
        "Tap to open a doorway into your sanctum. Hold to drag everything within five blocks through with you.",
        Items.ENDER_EYE,false,true));
    public static final FractureMode NEAR_PLAYER=register(new FractureMode("near_player","Travel Near Player",
        "Open onto a safe spot a short walk from one chosen player. The choice is kept until you change it.",
        Items.PLAYER_HEAD,true,false));
    public static final FractureMode NEAREST_PLAYER=register(new FractureMode("nearest_player","Nearest Player",
        "Open beside whoever is nearest when you cast, rather than whoever happened to be nearest when you chose.",
        Items.COMPASS,false,false));
    public static final FractureMode NETHER=register(new FractureMode("nether","Nether",
        "Open a safe way through to the Nether.",
        Items.NETHERRACK,false,false));
    public static final FractureMode RESPAWN=register(new FractureMode("respawn","Respawn Point",
        "Open onto your bed or anchor, or your world spawn when neither still stands.",
        Items.RED_BED,false,false));
    public static final FractureMode OVERWORLD=register(new FractureMode("overworld","Normal Return",
        "Open onto the place you last left to enter your sanctum.",
        Items.GRASS_BLOCK,false,false));

    public static final FractureMode DEFAULT=PULL;

    public static List<FractureMode> all() {return Collections.unmodifiableList(ORDER);}
    public static int count() {return ORDER.size();}
    public static FractureMode byIndex(int index) {return index<0||index>=ORDER.size()?null:ORDER.get(index);}
    public static int indexOf(FractureMode mode) {return ORDER.indexOf(mode);}
    /** Unknown ids fall back to the default, so a save from a build with an extra mode still loads. */
    public static FractureMode byId(String id) {return BY_ID.getOrDefault(id,DEFAULT);}
}
