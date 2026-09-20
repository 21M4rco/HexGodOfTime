package com.loki.data;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The one definition of Time Branch Unleashing's shape, shared by the server that decides what the
 * torrent hits and the client that draws it.
 *
 * <p>Keeping the charge stages, the focus point, the radii and the travel speed in a single place is
 * not tidiness — it is the only way the picture and the hit volume can be guaranteed to agree. Nothing
 * may be erased outside the visible beam and nothing inside it may survive, so both sides compute the
 * volume from these numbers rather than each carrying its own idea of it.
 */
public final class BranchCharge {
    private BranchCharge() {}

    /** Stage thresholds in ticks: formation, stable, pressure, critical, maximum, overcharge. */
    public static final int FORMATION=20,STABLE=50,PRESSURE=80,CRITICAL=110,FULL=140,LIMIT=200;
    /** Reach, in blocks. Charge never extends it; charge widens the torrent instead. */
    public static final double RANGE=100;
    /** The caster's own hitbox ends well short of this, which is what keeps them out of their own beam. */
    public static final double SAFE=2.4;
    /** Blocks per tick the leading front travels, so the sweep and the render advance together. */
    public static final double SWEEP=6.5;
    /** Ticks the torrent stays open once the front has arrived, and the dissipation after it. */
    public static final int OPEN=16,FADE=16;
    /** Ticks a block spends coming apart before it is taken out of the world. */
    public static final int DISSOLVE=7;
    /** Ticks after the torrent has fully ended before the world is put back: about thirty seconds. */
    public static final int RESTORE=600;
    /** The held breath: the sphere collapses inward for this long before anything leaves the hands. */
    public static final double COMPRESS=1.5;

    /** 0 at a tap, 1 from five and a half seconds on. Gameplay strength saturates; drama does not. */
    public static float power(int held) {return Mth.clamp(held/(float)FULL,0,1);}
    /** 1 to 6, matching the documented presentation stages. */
    public static int stage(int held) {
        if(held<FORMATION)return 1;
        if(held<STABLE)return 2;
        if(held<PRESSURE)return 3;
        if(held<CRITICAL)return 4;
        if(held<FULL)return 5;
        return 6;
    }
    /** How far past full power the hold has gone, 0 to 1, for instability that no longer buys power. */
    public static float overcharge(int held) {return Mth.clamp((held-FULL)/(float)(LIMIT-FULL),0,1);}

    /** Where the sphere sits and where the torrent leaves: just past both extended hands. */
    public static Vec3 focus(Entity e,float partial) {
        Vec3 look=aim(e,partial);
        return e.getEyePosition(partial).add(look.scale(1.15)).add(0,-.2,0);
    }
    public static Vec3 aim(Entity e,float partial) {
        float yaw=e.getViewYRot(partial),pitch=e.getViewXRot(partial);
        return Vec3.directionFromRotation(pitch,yaw);
    }

    /** The contained sphere's radius. It keeps swelling through overcharge without buying reach. */
    public static double sphere(int held) {
        float t=power(held);
        return .28+1.62*t+.30*overcharge(held);
    }
    /** How far from the axis a body is still caught. */
    public static double catchRadius(float power) {return 1.15+2.35*power;}
    /** How far from the axis soft ground leaves the timeline. Always inside the visible torrent. */
    public static double eraseRadius(float power) {return .85+1.95*power;}
    /** The drawn core half-width, which the two radii above sit inside. */
    public static double beamRadius(float power) {return .55+1.55*power;}

    /** Ticks the whole torrent lasts for a given length. */
    public static int life(double length,float power) {
        return (int)Math.ceil(COMPRESS+length/SWEEP)+OPEN+(int)(power*8)+FADE;
    }
    /**
     * How far down the axis the leading front has reached after {@code age} ticks. Nothing leaves the
     * hands during the compression, which is why the pause reads as a pause on both sides at once.
     */
    public static double front(double length,double age) {return Math.min(length,Math.max(0,(age-COMPRESS)*SWEEP));}
    /** The tick, relative to the torrent opening, at which the front reaches {@code distance}. */
    public static double reaches(double distance) {return COMPRESS+Math.max(0,distance)/SWEEP;}
}
