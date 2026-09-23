package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.server.PocketRealm;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Stable IDs: append destinations, never reorder saved selections. */
public enum Destination {
    SUN("Sun", "A stellar furnace. Captives fall fifty-six blocks into its corona.",0xffb34b, new Vec3(0,184,0)),
    VOID_SEA("Void Sea", "An endless abyssal ocean nearly a thousand blocks deep. A cosmic sea god already hunts it. It cannot be killed, it cannot be escaped by leaving the water, and it is always there.",0xd23b2f,new Vec3(0,VoidSea.ARRIVAL,0)),
    GRAVITY_WELL("Gravity Well", "Empty space. An irresistible spiral drags everything into a lethal black center.",0xb273ff,new Vec3(64,110,0)),
    SHATTERED_WORLD("Shattered World", "Broken forests and ruins. Unstable gravity between islands.",0x92b5b1,new Vec3(0,143,0)),
    TIME_STORM("Time Storm", "A storm of branching time. Your enemies' recent steps unravel.",0xca82ff,new Vec3(0,132,0)),
    PARADISE("Paradise", "A small, perfect pocket world of floating islands under a candy sky. Weak gravity, rainbows, drifting sweets, and a hot spring that pays you for swimming in it. Break it and it grows back.",0xff9ad8,Paradise.ARRIVAL),
    FROZEN_MOMENT("Frozen Moment", "A catastrophe held still. Press X here to release nearby suspended hazards.",0xa3e5f1,new Vec3(0,132,0)),
    CRUSHING_REALM("Cosmic Prison", "A cratered moon with crushing radial gravity. Walk around every side, even upside down. Escape is pulled back to the surface.",0xbe83ce,new Vec3(0,CosmicPhysics.MOON_Y+CosmicPhysics.MOON_RADIUS+5,0)),
    END_OF_TIME("End of Time", "The exhausted remains of a universe. Living strength fades here.",0x998d9e,new Vec3(0,132,0)),
    SANCTUM("World Tree", "Your existing Fracture sanctum, now reached through Warping. Inside it, G chooses where R sends you back out.",0x111315,new Vec3(0,65,0),PocketRealm.KEY,false);
    /** The one destination whose danger is a creature rather than the environment. */
    public boolean lethal(){return this==VOID_SEA;}
    public final String title,description; public final int color; public final Vec3 arrival;
    public final ResourceKey<Level> key;
    public final boolean managed;
    Destination(String title,String description,int color,Vec3 arrival) {
        this(title,description,color,arrival,ResourceKey.create(Registries.DIMENSION,HexGodOfStories.id("warping_"+name().toLowerCase(java.util.Locale.ROOT))),true);
    }
    Destination(String title,String description,int color,Vec3 arrival,ResourceKey<Level> key,boolean managed) {
        this.title=title;this.description=description;this.color=color;this.arrival=arrival;this.key=key;this.managed=managed;
    }
    public static Destination from(Level level){for(var d:values())if(d.managed&&d.key.equals(level.dimension()))return d;return null;}
    public static Destination at(int id){return id>=0&&id<values().length?values()[id]:SUN;}
}
