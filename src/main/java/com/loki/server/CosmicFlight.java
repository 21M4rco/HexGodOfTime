package com.loki.server;

import com.loki.data.LokiData;
import com.loki.network.LokiNetwork;
import net.minecraft.server.level.ServerPlayer;

/** Grants vanilla server-authorized flight only while the final mantle is active. */
public final class CosmicFlight {
    private CosmicFlight() {}
    public static void tick(ServerPlayer p) {
        var d=LokiData.get(p);var a=p.getAbilities();
        boolean allowed=LokiData.access(p)&&p.isAlive()&&d.getBoolean("ascended")&&!p.isSpectator();
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
            d.putBoolean("cosmicFlying",flying);LokiNetwork.sync(p);
        }
    }
    public static void toggle(ServerPlayer p) {
        if(!LokiData.access(p)||!LokiData.get(p).getBoolean("ascended")||p.isSpectator()||p.isPassenger()||TemporalEngine.frozen(p))return;
        tick(p);
        p.getAbilities().flying=!p.getAbilities().flying;
        p.resetFallDistance();p.onUpdateAbilities();
        if(!p.getAbilities().flying)LokiData.get(p).putLong("flightLandingGrace",LokiData.now(p)+100);
        LokiData.get(p).putBoolean("cosmicFlying",p.getAbilities().flying);LokiNetwork.sync(p);
    }
    public static void revoke(ServerPlayer p) {
        var d=LokiData.get(p);var a=p.getAbilities();
        if(d.getBoolean("flightGranted")) {
            boolean keep=p.isCreative()||p.isSpectator()||d.getBoolean("flightHadMayfly");
            a.mayfly=keep;
            if(!keep)a.flying=false;
            d.remove("flightGranted");d.remove("flightHadMayfly");
            d.putLong("flightLandingGrace",LokiData.now(p)+100);
            p.resetFallDistance();p.onUpdateAbilities();
        }
        if(d.getBoolean("cosmicFlying")){d.putBoolean("cosmicFlying",false);LokiNetwork.sync(p);}
    }
}
