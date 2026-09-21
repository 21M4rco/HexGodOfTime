package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants vanilla server-authorized flight, and now only inside the fracture world.
 *
 * <p>Flight used to follow the mantle into every dimension, and sovereignty granted it again in
 * eight of the nine Warping realms on top of that. Between them there was almost nowhere the mod
 * could put a player that could not be answered by rising above it: a corona you can climb out of,
 * planes that cannot close on you, a collapse you do not fall with, and an ocean whose hunter
 * cannot reach you are all the same non-event. Every one of those places is now survived from
 * inside it.
 *
 * <p>The exception is the fracture world — the keeper's own pocket realm. It is an open island
 * over a void with no hazard in it but the drop, so flight there takes nothing away and walking
 * off the edge is the only thing it prevents.
 *
 * <p>Creative and spectator mode are untouched. Those are the operator's own flight, not the
 * mod's, and taking them away would break a great deal more than it fixed.
 */
public final class CosmicFlight {
    private CosmicFlight() {}
    /** The one dimension in which this mod will hand out flight. */
    public static boolean fractureWorld(ServerPlayer p){return p.level().dimension().equals(com.hexgodofstories.server.PocketRealm.KEY);}
    public static void tick(ServerPlayer p) {
        var d=HexData.get(p);var a=p.getAbilities();
        boolean allowed=HexData.access(p)&&p.isAlive()&&d.getBoolean("ascended")&&fractureWorld(p)&&!p.isSpectator();
        if(!allowed){revoke(p);return;}
        if(!d.getBoolean("flightGranted")) {
            d.putBoolean("flightHadMayfly",a.mayfly&&!p.isCreative());
            d.putBoolean("flightGranted",true);
            a.mayfly=true;p.onUpdateAbilities();
        }
        if(!a.mayfly){a.mayfly=true;p.onUpdateAbilities();}
        boolean flying=a.flying&&!p.onGround()&&!p.isPassenger();
        if(a.flying)p.resetFallDistance();
        if(d.getBoolean("cosmicFlying")!=flying) {
            d.putBoolean("cosmicFlying",flying);HexNetwork.sync(p);
        }
    }
    public static void toggle(ServerPlayer p) {
        if(!HexData.access(p)||!HexData.get(p).getBoolean("ascended")||!fractureWorld(p)||p.isSpectator()||p.isPassenger()||TemporalEngine.frozen(p))return;
        tick(p);
        p.getAbilities().flying=!p.getAbilities().flying;
        p.resetFallDistance();p.onUpdateAbilities();
        if(!p.getAbilities().flying)HexData.get(p).putLong("flightLandingGrace",HexData.now(p)+100);
        HexData.get(p).putBoolean("cosmicFlying",p.getAbilities().flying);HexNetwork.sync(p);
    }
    public static void revoke(ServerPlayer p) {
        var d=HexData.get(p);var a=p.getAbilities();
        if(d.getBoolean("flightGranted")) {
            boolean keep=p.isCreative()||p.isSpectator()||d.getBoolean("flightHadMayfly");
            a.mayfly=keep;
            if(!keep)a.flying=false;
            d.remove("flightGranted");d.remove("flightHadMayfly");
            d.putLong("flightLandingGrace",HexData.now(p)+100);
            p.resetFallDistance();p.onUpdateAbilities();
        }
        if(d.getBoolean("cosmicFlying")){d.putBoolean("cosmicFlying",false);HexNetwork.sync(p);}
    }
}
