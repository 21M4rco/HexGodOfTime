package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Grants vanilla server-authorized flight, and the mantle is the whole of the permission.
 *
 * <p>0.5.6 confined this to the fracture world, on the argument that a realm you can rise out of
 * is scenery; later it was carried into every dimension. It now flies everywhere but the Overworld —
 * the realms, the sea, the Nether, the End — because in the world everyone else lives in, a mantle
 * that also rules the sky left nothing able to stand against its wearer. In the Overworld it keeps its
 * armour and its strength and walks. Flying costs the mantle more, too: see {@link Transformation#FLYING_DRAIN}.
 * Take the mantle off and the ground is exactly as dangerous as it was.
 *
 * <p>Nothing else grants it. Sovereignty does not, a destination does not, and a player who has
 * never transformed is on foot everywhere, which is what keeps the hazards meaning something for
 * everyone who is not wearing it.
 *
 * <p>Creative and spectator mode are untouched. Those are the operator's own flight, not the
 * mod's, and taking them away would break a great deal more than it fixed.
 */
public final class CosmicFlight {
    private CosmicFlight() {}
    /** The mantle, and nothing else, is what this mod hands flight to. */
    public static boolean mantled(ServerPlayer p){return HexData.access(p)&&HexData.get(p).getBoolean("ascended");}
    /** Every dimension but the Overworld. */
    public static boolean skyOpen(ServerPlayer p){return !p.level().dimension().equals(Level.OVERWORLD);}
    public static void tick(ServerPlayer p) {
        var d=HexData.get(p);var a=p.getAbilities();
        boolean allowed=HexData.access(p)&&p.isAlive()&&d.getBoolean("ascended")&&!p.isSpectator()&&skyOpen(p);
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
        if(!HexData.access(p)||!HexData.get(p).getBoolean("ascended")||p.isSpectator()||p.isPassenger()||TemporalEngine.frozen(p))return;
        if(!skyOpen(p)){p.displayClientMessage(Component.literal("The mantle does not fly in the Overworld."),true);return;}
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
