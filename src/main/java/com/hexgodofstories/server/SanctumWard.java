package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Inside their own sanctum, the owner cannot be struck.
 *
 * <p>This is not damage cancellation dressed up as a dodge. The attack is refused at the point it is
 * declared — before any damage is calculated, before invulnerability frames, before knockback — and
 * in the same instant the owner is somewhere else. What connects is empty air, and the only thing
 * left where they were standing is a bank of nebula.
 *
 * <p>The displacement is deliberately short and always lands back on the island: this reads as
 * impossible reflexes, not as being thrown across the world. A brief lockout keeps a shotgun of
 * simultaneous hit events in one tick from becoming twenty teleports, while still leaving every one
 * of those attacks with nothing to hit.
 */
public final class SanctumWard {
    private SanctumWard() {}

    /** Ticks before the same owner will slip again; long enough to be one dodge, short enough to be reliable. */
    private static final int LOCKOUT=4;
    private static final double MIN_STEP=3.5,MAX_STEP=6.5;
    private static final int ATTEMPTS=14;
    private static final Map<UUID,Long> SLIPPED=new HashMap<>();

    /** True when this player is standing in the sanctum that belongs to them. */
    public static boolean owner(Entity e) {
        return e instanceof ServerPlayer p&&HexData.access(p)&&PocketRealm.inside(p.level())&&PocketRealm.ownsHere(p);
    }

    /**
     * @return true when the attack has been refused and the owner has already gone. Callers cancel.
     */
    public static boolean evade(Entity victim) {
        if(!(victim instanceof ServerPlayer p)||!owner(p))return false;
        if(p.isCreative()||p.isSpectator())return false;
        long now=p.level().getGameTime();
        Long until=SLIPPED.get(p.getUUID());
        // Already gone this instant: still refuse the blow, just do not move again.
        if(until!=null&&until>now)return true;
        Vec3 from=p.position();
        Vec3 to=slipTo(p);
        SLIPPED.put(p.getUUID(),now+LOCKOUT);
        if(SLIPPED.size()>64)SLIPPED.entrySet().removeIf(e->e.getValue()<now);
        if(to==null)return true;
        // The move happens now. The presentation is sent alongside it and never gates it.
        HexNetwork.fx(p,"demanifest",from.x,from.y,from.z);
        p.connection.teleport(to.x,to.y,to.z,p.getYRot(),p.getXRot());
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        HexNetwork.fx(p,"remanifest",to.x,to.y,to.z);
        return true;
    }

    /**
     * Somewhere a few blocks away, still on the island and still standable. Candidates are drawn
     * around the owner and tested; the search is short and bounded because it runs inside a hit.
     */
    private static Vec3 slipTo(ServerPlayer p) {
        ServerLevel level=p.serverLevel();
        var random=p.getRandom();
        double facing=Math.toRadians(p.getYRot());
        for(int attempt=0;attempt<ATTEMPTS;attempt++) {
            // Favour slipping behind and to the side of whatever is swinging.
            double angle=facing+Math.PI+(random.nextDouble()-.5)*Math.PI*1.4;
            double distance=MIN_STEP+random.nextDouble()*(MAX_STEP-MIN_STEP);
            double x=p.getX()+Math.cos(angle)*distance,z=p.getZ()+Math.sin(angle)*distance;
            if(!PocketRealm.onIsland(x,z))continue;
            for(int step=0;step<=6;step++) {
                double y=p.getY()+(step%2==0?step/2:-(step/2+1));
                Vec3 at=new Vec3(Math.floor(x)+.5,y,Math.floor(z)+.5);
                if(!clear(level,p,at))continue;
                return at;
            }
        }
        return null;
    }

    private static boolean clear(ServerLevel level,ServerPlayer p,Vec3 at) {
        BlockPos pos=BlockPos.containing(at);
        if(at.y<level.getMinBuildHeight()+1||at.y+2>=level.getMaxBuildHeight())return false;
        var box=p.getBoundingBox().move(at.subtract(p.position()));
        if(!level.noCollision(p,box)||level.containsAnyLiquid(box))return false;
        // Never leave the owner standing on nothing over the island's underside.
        for(int drop=1;drop<=4;drop++)
            if(!level.getBlockState(pos.below(drop)).getCollisionShape(level,pos.below(drop)).isEmpty())return true;
        return false;
    }

    public static void forget(Entity e) {SLIPPED.remove(e.getUUID());}
    public static void reset() {SLIPPED.clear();}
}
