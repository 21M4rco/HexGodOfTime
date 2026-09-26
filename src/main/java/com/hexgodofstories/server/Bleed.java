package com.hexgodofstories.server;

import com.hexgodofstories.data.Discipline;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/**
 * Stacking wounds from embedded blades, and the steady flow a Scepter beam's hole leaves. Damage is
 * credited to the caster so kills read as theirs.
 */
public final class Bleed {
    private static final class Wound {
        final UUID owner;int stacks;long expires,next;
        /** A Scepter beam's hole: pouring until this tick, credited to the caster whose beam opened it. */
        long flowing;UUID flowOwner;
        Wound(UUID owner,int stacks,long expires,long next) {this.owner=owner;this.stacks=stacks;this.expires=expires;this.next=next;}
    }
    private static final int MAX_STACKS=5,INTERVAL=20,MAX_TRACKED=192;
    /** Health lost a second: to each blade stack, and to a flowing hole — a heart, whatever else is in the body. */
    private static final float PER_STACK=.8f,FLOW=2;
    /** How hard a flowing hole bleeds to look at, in stacks: about what a heart a second would take. */
    private static final int FLOW_SHOWN=3;
    private static final Map<UUID,Wound> WOUNDS=new HashMap<>();

    public static int stacks(LivingEntity e) {Wound w=WOUNDS.get(e.getUUID());return w==null?0:w.stacks;}

    public static void apply(ServerPlayer owner,LivingEntity victim,int stacks,int duration) {
        apply(owner.getUUID(),victim,stacks,duration);
    }

    /**
     * The same wound, opened by something that is not a caster.
     *
     * <p>Every wound here was a blade's until now, so the owner was always a player and the whole
     * thing was keyed on one. Hexor's jaws leave the same wound, and it has a UUID like anything
     * else, so this is the same bookkeeping with the player requirement lifted off it.
     */
    public static void apply(UUID owner,LivingEntity victim,int stacks,int duration) {
        if(!victim.isAlive()||victim.getType().is(net.minecraft.tags.EntityTypeTags.SKELETONS))return;
        long now=victim.level().getGameTime();
        Wound wound=WOUNDS.get(victim.getUUID());
        if(wound==null) {
            if(WOUNDS.size()>=MAX_TRACKED)return;
            wound=new Wound(owner,Math.min(MAX_STACKS,stacks),now+duration,now+INTERVAL);
            WOUNDS.put(victim.getUUID(),wound);
        } else {
            // Blades that had already closed while a beam's hole kept the wound open count again from nothing.
            if(now>=wound.expires)wound.stacks=0;
            wound.stacks=Math.min(MAX_STACKS,wound.stacks+stacks);
            wound.expires=Math.max(wound.expires,now+duration);
        }
        notifyClients(victim,shown(wound,now));
    }

    /**
     * A Scepter beam's hole: a heart a second, through armour, for {@code duration} ticks. It is a flow,
     * not a blade: another hole keeps it pouring longer, never faster, and it runs beside whatever blades
     * are in the body without adding to their stacks.
     */
    public static void flow(ServerPlayer owner,LivingEntity victim,int duration) {
        if(!victim.isAlive()||victim.getType().is(net.minecraft.tags.EntityTypeTags.SKELETONS))return;
        long now=victim.level().getGameTime();
        Wound wound=WOUNDS.get(victim.getUUID());
        if(wound==null) {
            if(WOUNDS.size()>=MAX_TRACKED)return;
            wound=new Wound(owner.getUUID(),0,now,now+INTERVAL);
            WOUNDS.put(victim.getUUID(),wound);
        }
        // One tick past the end, so the last second's heart lands whatever beat the wound is already on.
        wound.flowing=Math.max(wound.flowing,now+duration+1);
        wound.flowOwner=owner.getUUID();
        notifyClients(victim,shown(wound,now));
    }

    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        // Snapshot the keys: a wound that finishes its victim fires a death event, and that clears
        // entries from this very map while we are still walking it.
        for(UUID id:new ArrayList<>(WOUNDS.keySet())) {
            Wound wound=WOUNDS.get(id);
            if(wound==null)continue;
            if(!(level.getEntity(id) instanceof LivingEntity victim))continue;
            if(!victim.isAlive()||now>=wound.expires&&now>=wound.flowing){WOUNDS.remove(id);notifyClients(victim,0);continue;}
            if(now<wound.next)continue;
            wound.next=now+INTERVAL;
            boolean flowing=now<wound.flowing;
            // The blades have closed; a beam's hole goes on pouring by itself.
            if(now>=wound.expires&&wound.stacks>0){wound.stacks=0;notifyClients(victim,shown(wound,now));}
            // A body on its last breath bleeds without losing more: how long it stands is LastMoments' clock.
            if(LastMoments.held(victim))continue;
            float amount=PER_STACK*wound.stacks+(flowing?FLOW:0);
            if(amount<=0)continue;
            // While a beam's hole pours, the whole wound is that caster's to finish.
            UUID credited=flowing&&wound.flowOwner!=null?wound.flowOwner:wound.owner;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(credited);
            // Bleeding out from a bite is still a kill by the thing that bit you, and still says so:
            // without this the last tick of a wound Hexor opened would be reported as plain magic.
            net.minecraft.world.damagesource.DamageSource source;
            if(owner!=null)source=owner.damageSources().indirectMagic(owner,owner);
            else if(level.getEntity(credited) instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity hexor)
                source=hexor.attackDamage(victim);
            else source=level.damageSources().magic();
            victim.hurt(source,amount);
            if(owner!=null)HexServer.reward(owner,Discipline.CONJURATION,20);
            if(WOUNDS.containsKey(id))notifyClients(victim,shown(wound,now));
        }
    }

    /** How a wound looks to clients, in stacks: its live blades, or a flowing hole's weight if that is more. */
    private static int shown(Wound wound,long now) {
        return Math.max(now<wound.expires?wound.stacks:0,now<wound.flowing?FLOW_SHOWN:0);
    }

    private static void notifyClients(LivingEntity victim,int stacks) {
        CompoundTag n=new CompoundTag();n.putInt("stacks",stacks);
        HexNetwork.tracking(victim,new HexNetwork.Message(HexNetwork.BLEED,victim.getId(),n));
    }
    public static void clear(LivingEntity e) {if(WOUNDS.remove(e.getUUID())!=null)notifyClients(e,0);}
    public static void reset() {WOUNDS.clear();}
}
