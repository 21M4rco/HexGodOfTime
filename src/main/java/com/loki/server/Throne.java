package com.loki.server;

import com.loki.Loki;
import com.loki.entity.ThroneSeat;
import com.loki.network.LokiNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * The seat belongs to one person.
 *
 * <p>Nothing else sits on it, and nothing else gets near it. A visitor who reaches for the throne — or a
 * creature that simply wanders onto it — is thrown clear, hard enough that it reads as the island's answer
 * rather than as a push. The owner sitting in their own hall is mended while they do.
 *
 * <p>The seat's position is derived the same way the click handler derives it, off the world coordinate
 * rather than off a plot index, because the realm's two grids do not share an origin and combining them
 * would put the guard somewhere the throne is not.
 */
public final class Throne {
    private Throne() {}

    /** How far from the seat counts as reaching for it. */
    private static final double GUARD=2.6;
    /** Thrown clear, not nudged. */
    private static final double SHOVE=2.65,LIFT=.62;
    /** The mending, renewed on a short lease so standing up ends it within moments. */
    private static final int REGEN_LEASE=80,REGEN_RENEW=40;
    private static final int CADENCE=5;
    private static final Map<UUID,Long> TOLD=new HashMap<>();

    /** The seat's exact centre for whichever island a world position falls on. */
    public static Vec3 seat(double x,double z) {
        int baseX=Mth.floor(x)-Math.floorMod(Mth.floor(x),PocketRealm.SPACING);
        int baseZ=Mth.floor(z)-Math.floorMod(Mth.floor(z),PocketRealm.SPACING);
        return new Vec3(baseX+50.5,PocketRealm.FLOOR_Y+6,baseZ+53.5);
    }

    /**
     * Keeps everything that is not the island's owner off the seat. Runs only where a player is already
     * standing, on a slow cadence, over one small box — so an empty realm costs nothing.
     */
    public static void guard(ServerLevel level) {
        if(level.getGameTime()%CADENCE!=0||level.players().isEmpty())return;
        Set<Long> done=new HashSet<>();
        for(ServerPlayer player:level.players()) {
            Vec3 seat=seat(player.getX(),player.getZ());
            if(!done.add(net.minecraft.core.BlockPos.containing(seat).asLong()))continue;
            AABB box=new AABB(seat,seat).inflate(GUARD);
            for(LivingEntity trespasser:level.getEntitiesOfClass(LivingEntity.class,box,LivingEntity::isAlive)) {
                if(trespasser instanceof ServerPlayer p&&PocketRealm.ownsHere(p))continue;
                repel(level,trespasser,seat);
            }
        }
    }

    /** Throws one body clear of the seat, upward and outward, and says so once in a while. */
    public static void repel(ServerLevel level,Entity trespasser,Vec3 seat) {
        if(trespasser instanceof ThroneSeat)return;
        Vec3 away=trespasser.position().subtract(seat);
        // Straight up is not "away", so a body sitting exactly on the seat is given a direction to go.
        Vec3 flat=new Vec3(away.x,0,away.z);
        if(flat.lengthSqr()<1e-4)flat=new Vec3(level.getRandom().nextDouble()-.5,0,level.getRandom().nextDouble()-.5);
        if(flat.lengthSqr()<1e-6)flat=new Vec3(0,0,1);
        flat=flat.normalize();
        trespasser.stopRiding();
        trespasser.setDeltaMovement(flat.x*SHOVE,LIFT,flat.z*SHOVE);
        trespasser.hurtMarked=true;
        trespasser.fallDistance=0;
        if(trespasser instanceof ServerPlayer p) {
            p.connection.send(new ClientboundSetEntityMotionPacket(p));
            p.resetFallDistance();
            // Said sparingly: being thrown across the hall is its own explanation.
            long now=level.getGameTime();
            if(now-TOLD.getOrDefault(p.getUUID(),-200L)>100) {
                TOLD.put(p.getUUID(),now);
                p.displayClientMessage(Component.literal("The seat is not yours."),true);
            }
        }
        LokiNetwork.fx(trespasser,"push");
        level.playSound(null,trespasser.blockPosition(),Loki.ASCEND.get(),SoundSource.PLAYERS,.8f,.72f);
    }

    /**
     * Refused, and thrown clear for asking. Returns true so the interaction is consumed either way and the
     * click never falls through to ordinary block use.
     */
    public static boolean refuse(ServerPlayer player,Vec3 seat) {
        if(player.level() instanceof ServerLevel level)repel(level,player,seat);
        return true;
    }

    /** The owner in their own seat is mended. A short lease, so it ends moments after they stand. */
    public static void crown(ThroneSeat seat) {
        if(seat.level().isClientSide||seat.tickCount%REGEN_RENEW!=0)return;
        for(Entity passenger:seat.getPassengers()) {
            if(!(passenger instanceof ServerPlayer player)||!PocketRealm.ownsHere(player))continue;
            MobEffectInstance held=player.getEffect(MobEffects.REGENERATION);
            if(held!=null&&held.getAmplifier()>1)continue;
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,REGEN_LEASE,1,true,false,true));
        }
    }

    public static void reset() {TOLD.clear();}
}
