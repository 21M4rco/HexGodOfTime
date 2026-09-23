package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.warping.Destination;
import com.hexgodofstories.warping.WarpRealms;
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
 * navigation and its aggression stopped, its own controls refused, its voice taken and every other source
 * of harm refused on its behalf — and stays visible for the length of the sequence while the client draws
 * the destruction travelling through it. Only when that has run its course does the death land, and the
 * server is the only thing that decides it.
 *
 * <p>It takes its time. The sequence runs for the better part of ten seconds and longer at full charge,
 * because the whole point is watching a body be taken apart rather than watching it fall over.
 *
 * <p>And nothing falls over. Once the death lands, the shell is removed in the same tick — drops, experience
 * and advancements have all already been handed out by that death, so what is discarded is only the corpse
 * and the twenty ticks of tipping that would have gone with it. A player's body cannot be discarded, so it
 * is the client that keeps it hidden for as long as it lies there. Either way the last thing anybody sees is
 * the last fragment going, which is the only ending this ability has.
 *
 * <p>Every hold here is reversible and leased. A victim whose caster vanishes, whose world changes, or
 * whose sequence is interrupted has its gravity and its AI handed straight back, and the whole register
 * is dropped on shutdown, so an interrupted ultimate cannot leave anything permanently frozen.
 */
public final class Erasure {
    private Erasure() {}

    private static final class Fading {
        final LivingEntity victim;final UUID caster;final Vec3 direction;
        final long start;final int duration;final boolean gravity,noAi,silent,implosion;final float power;
        /** null means true erasure/death; otherwise the body is reconstructed in this Warping realm. */
        final Destination banishTo;
        Fading(LivingEntity victim,UUID caster,Vec3 direction,long start,int duration,
               boolean gravity,boolean noAi,boolean silent,boolean implosion,float power,Destination banishTo) {
            this.victim=victim;this.caster=caster;this.direction=direction;
            this.start=start;this.duration=duration;this.gravity=gravity;this.noAi=noAi;this.silent=silent;
            this.implosion=implosion;this.power=power;this.banishTo=banishTo;
        }
    }
    /**
     * How long a body takes to go. Deliberately long: a tap still spends seven and a half seconds coming
     * apart and a full charge the better part of twelve, because the erasure is the spectacle and hurrying
     * it would make it a damage number with particles on top. A player gets longer again, since theirs is
     * the death somebody is watching happen to them.
     */
    private static final int MOB_TICKS=150,PLAYER_TICKS=180,CHARGE_TICKS=90;
    private static final Destination[] BANISHMENTS={Destination.SUN,Destination.GRAVITY_WELL,Destination.VOID_SEA};
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
    /** Fully charged held torrent: the only Time Branch outcome that is allowed to kill. */
    public static boolean beginFatal(ServerPlayer caster,LivingEntity victim,Vec3 direction,float power) {
        return begin(caster,victim,direction,power,false,null);
    }
    /** Partial held torrent: same disappearance animation, but the victim wakes in one danger realm. */
    public static boolean banish(ServerPlayer caster,LivingEntity victim,Vec3 direction,float power) {
        Destination destination=BANISHMENTS[caster.getRandom().nextInt(BANISHMENTS.length)];
        return begin(caster,victim,direction,power,false,destination);
    }
    /** Tap fist is never lethal now; it banishes after the same implosion animation. */
    public static boolean implode(ServerPlayer caster,LivingEntity victim,Vec3 direction) {
        Destination destination=BANISHMENTS[caster.getRandom().nextInt(BANISHMENTS.length)];
        return begin(caster,victim,direction,.8f,true,destination);
    }
    private static boolean begin(ServerPlayer caster,LivingEntity victim,Vec3 direction,float power,boolean implosion,Destination banishTo) {
        if(victim==null||!victim.isAlive()||erasing(victim)||FADING.size()>=MAX)return false;
        if(victim==caster||!HexServer.validTarget(caster,victim))return false;
        // Somebody's own animal is not what this is for.
        if(victim instanceof TamableAnimal pet&&caster.getUUID().equals(pet.getOwnerUUID()))return false;
        int duration=implosion?com.hexgodofstories.data.BranchFistState.IMPLOSION
            :(victim instanceof Player?PLAYER_TICKS:MOB_TICKS)+(int)(power*CHARGE_TICKS);
        Fading fading=new Fading(victim,caster.getUUID(),direction.normalize(),victim.level().getGameTime(),
            duration,victim.isNoGravity(),victim instanceof Mob m&&m.isNoAi(),victim.isSilent(),implosion,power,banishTo);
        FADING.put(victim.getUUID(),fading);
        if(banishTo!=null) {
            ServerLevel destination=caster.server.getLevel(banishTo.key);
            if(destination!=null)WarpRealms.prepare(destination,banishTo,WarpRealms.CELL);
        }
        hold(fading);
        HexNetwork.tracking(victim,message(fading));
        return true;
    }
    private static HexNetwork.Message message(Fading fading) {
        CompoundTag n=new CompoundTag();
        n.putDouble("dx",fading.direction.x);n.putDouble("dy",fading.direction.y);n.putDouble("dz",fading.direction.z);
        n.putInt("duration",fading.duration);n.putFloat("power",fading.power);n.putLong("start",fading.start);
        n.putBoolean("implosion",fading.implosion);
        return new HexNetwork.Message(HexNetwork.ERASURE,fading.victim.getId(),n);
    }
    public static void track(ServerPlayer viewer,Entity victim) {
        Fading fading=FADING.get(victim.getUUID());
        if(fading!=null)HexNetwork.to(viewer,message(fading));
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
            if(now-f.start>=f.duration) {
                if(f.banishTo!=null) {
                    ServerLevel destination=level.getServer().getLevel(f.banishTo.key);
                    if(destination==null){release(f);it.remove();continue;}
                    WarpRealms.prepare(destination,f.banishTo,WarpRealms.CELL);
                    // Keep the already-vanished body held out of time until its destination blueprint is ready.
                    if(!WarpRealms.ready(destination,WarpRealms.CELL)){hold(f);continue;}
                }
                done.add(f);it.remove();continue;
            }
            hold(f);
        }
        for(Fading f:done)finish(f,level);
    }

    /** Held out of time: no fall, no drift, no navigation, no aggression, no swing, and no voice. */
    private static void hold(Fading f) {
        LivingEntity v=f.victim;
        // Silenced for the whole sequence, which is also what keeps the death sound from arriving at the
        // end of it. A thing being unmade does not grunt.
        if(!v.isSilent())v.setSilent(true);
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

    /** Hands everything back exactly as it was found, for a sequence that was interrupted. */
    private static void release(Fading f) {
        unhold(f);
        f.victim.setSilent(f.silent);
    }
    /** The mechanical holds only. Silence is kept through a death so the death itself stays quiet. */
    private static void unhold(Fading f) {
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
        unhold(f);
        LivingEntity v=f.victim;
        if(!v.isAlive()||v.isRemoved()){v.setSilent(f.silent);return;}

        // Every non-maximum Time Branch result ends here: same erasure animation, no death. The body is
        // reconstructed in exactly one of the three hostile Warping destinations.
        if(f.banishTo!=null) {
            v.setSilent(f.silent);
            ServerLevel destination=level.getServer().getLevel(f.banishTo.key);
            if(destination==null)return;
            WarpRealms.start(destination,WarpRealms.CELL);
            WarpRealms.transfer(v,f.banishTo,WarpRealms.CELL,false);
            return;
        }

        // Fully charged uninterrupted torrent only: true erasure/death.
        ServerPlayer caster=level.getServer().getPlayerList().getPlayer(f.caster);
        if(caster!=null)v.setLastHurtByPlayer(caster);
        DamageSource source=erasure(level,v,caster);
        v.invulnerableTime=0;
        v.hurt(source,v.getMaxHealth()*4+1000);
        if(v.isAlive()){v.invulnerableTime=0;v.kill();}
        if(v instanceof Player)return;
        v.discard();
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
        if(f!=null) {
            release(f);
            CompoundTag n=new CompoundTag();n.putBoolean("clear",true);
            HexNetwork.tracking(f.victim,new HexNetwork.Message(HexNetwork.ERASURE,f.victim.getId(),n));
        }
    }
    public static void reset() {FADING.values().forEach(Erasure::release);FADING.clear();}
}
