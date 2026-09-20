package com.loki.server;

import com.loki.network.LokiNetwork;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Temporal erasure: the part of Time Branch Unleashing that removes a body from the timeline.
 *
 * <p>Nothing is killed on contact. A caught creature is first taken out of the fight — held still, its
 * navigation and its aggression stopped, its own controls refused — and stays visible for the length of
 * the sequence while the client draws the destruction travelling through it. Only when that has run its
 * course does the death land, and the server is the only thing that decides it.
 *
 * <p>Every hold here is reversible and leased. A victim whose caster vanishes, whose world changes, or
 * whose sequence is interrupted has its gravity and its AI handed straight back, and the whole register
 * is dropped on shutdown, so an interrupted ultimate cannot leave anything permanently frozen.
 */
public final class Erasure {
    private Erasure() {}

    private static final class Fading {
        final LivingEntity victim;final UUID caster;final Vec3 direction;
        final long start;final int duration;final boolean gravity,noAi;
        Fading(LivingEntity victim,UUID caster,Vec3 direction,long start,int duration,boolean gravity,boolean noAi) {
            this.victim=victim;this.caster=caster;this.direction=direction;
            this.start=start;this.duration=duration;this.gravity=gravity;this.noAi=noAi;
        }
    }
    private static final Map<UUID,Fading> FADING=new LinkedHashMap<>();
    /** A hard ceiling on bodies mid-erasure, so a crowded torrent cannot grow unbounded work. */
    private static final int MAX=48;

    /** A body already leaving the timeline cannot move, fight, cast or be caught a second time. */
    public static boolean erasing(Entity e) {return e!=null&&FADING.containsKey(e.getUUID());}

    /**
     * Takes a body out of the fight and starts its sequence.
     *
     * @return true when this call is what caught it, so the caster's sweep does not double up.
     */
    public static boolean begin(ServerPlayer caster,LivingEntity victim,Vec3 direction,float power) {
        if(victim==null||!victim.isAlive()||erasing(victim)||FADING.size()>=MAX)return false;
        if(victim==caster||!LokiServer.validTarget(caster,victim))return false;
        // Somebody's own animal is not what this is for.
        if(victim instanceof TamableAnimal pet&&caster.getUUID().equals(pet.getOwnerUUID()))return false;
        int duration=(victim instanceof Player?30:22)+(int)(power*14);
        Fading fading=new Fading(victim,caster.getUUID(),direction.normalize(),victim.level().getGameTime(),
            duration,victim.isNoGravity(),victim instanceof Mob m&&m.isNoAi());
        FADING.put(victim.getUUID(),fading);
        hold(fading);
        CompoundTag n=new CompoundTag();
        n.putDouble("dx",fading.direction.x);n.putDouble("dy",fading.direction.y);n.putDouble("dz",fading.direction.z);
        n.putInt("duration",duration);n.putFloat("power",power);n.putLong("start",fading.start);
        LokiNetwork.tracking(victim,new LokiNetwork.Message(LokiNetwork.ERASURE,victim.getId(),n));
        return true;
    }

    /** One pass per level tick: keep every caught body still, then finish the ones whose time is up. */
    public static void tickLevel(ServerLevel level) {
        if(FADING.isEmpty())return;
        long now=level.getGameTime();
        Iterator<Map.Entry<UUID,Fading>> it=FADING.entrySet().iterator();
        List<Fading> done=new ArrayList<>();
        while(it.hasNext()) {
            Fading f=it.next().getValue();
            if(f.victim.level()!=level)continue;
            if(!f.victim.isAlive()||f.victim.isRemoved()){release(f);it.remove();continue;}
            if(now-f.start>=f.duration){done.add(f);it.remove();continue;}
            hold(f);
        }
        for(Fading f:done)finish(f,level);
    }

    /** Held out of time: no fall, no drift, no navigation, no aggression, no swing. */
    private static void hold(Fading f) {
        LivingEntity v=f.victim;
        v.setNoGravity(true);
        v.setDeltaMovement(Vec3.ZERO);
        v.fallDistance=0;
        v.hurtMarked=true;
        if(v instanceof Mob mob) {
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setAggressive(false);
        }
        if(v instanceof ServerPlayer p) {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            p.resetFallDistance();
        }
    }

    /** Hands everything back exactly as it was found. */
    private static void release(Fading f) {
        LivingEntity v=f.victim;
        v.setNoGravity(f.gravity);
        v.fallDistance=0;
        if(v instanceof Mob mob)mob.setNoAi(f.noAi);
    }

    /**
     * The death, once the body has finished coming apart. The damage bypasses the ordinary reductions on
     * purpose — this is not a large hit, it is a removal — and the two fallbacks exist for the handful of
     * bosses and modded creatures that refuse a damage source outright.
     */
    private static void finish(Fading f,ServerLevel level) {
        release(f);
        LivingEntity v=f.victim;
        if(!v.isAlive()||v.isRemoved())return;
        ServerPlayer caster=level.getServer().getPlayerList().getPlayer(f.caster);
        DamageSource source=erasure(level,v,caster);
        v.invulnerableTime=0;
        v.hurt(source,v.getMaxHealth()*4+1000);
        if(v.isAlive()){v.invulnerableTime=0;v.kill();}
        // A creature that survives even that is not going to be killed by asking again; it is taken out
        // of the world instead. Players are never discarded — their death is the server's to resolve.
        if(v.isAlive()&&!(v instanceof Player))v.discard();
    }

    /**
     * The damage that lands at the end. Built from the registry rather than through
     * {@link DamageSources}, whose typed factory is private, so the kill can carry the caster for credit
     * while still bypassing armour, resistance and invulnerability frames — this is a removal, not a hit.
     * If the registry cannot answer, the untyped kill is used instead and the body still dies.
     */
    private static DamageSource erasure(ServerLevel level,LivingEntity victim,ServerPlayer caster) {
        try {
            Holder<DamageType> type=level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.GENERIC_KILL);
            return new DamageSource(type,caster,caster);
        } catch(Exception ignored) {
            return victim.damageSources().genericKill();
        }
    }

    public static void forget(Entity e) {
        Fading f=e==null?null:FADING.remove(e.getUUID());
        if(f!=null)release(f);
    }
    public static void reset() {FADING.values().forEach(Erasure::release);FADING.clear();}
}
