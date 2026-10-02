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
 *
 * <p>A bleeding body is also badly hampered, whatever opened the wound: slowness V (three quarters of its speed gone),
 * nausea, and no jumping at all, for as long as it bleeds and {@link #LINGER} ticks after. None of it shows a single
 * particle: the blood is all there is to see.
 */
public final class Bleed {
    /** True only while a bleed tick's damage is being dealt, so that it knocks nobody back (see ServerEvents). */
    private static boolean dealing;
    public static boolean dealing() {return dealing;}

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
    /** How long the hampering outlasts the bleeding, and how often it is renewed while the wound is open. */
    private static final int LINGER=40,RENEW=10,SLOWNESS=4,NAUSEA_LEAST=100;
    /** Bodies that may not jump, until when: bleeding, and a moment after. */
    private static final Map<UUID,Long> GROUNDED=new HashMap<>();

    /** Whether this body is still too hurt to jump. */
    public static boolean grounded(LivingEntity e) {
        Long until=GROUNDED.get(e.getUUID());
        return until!=null&&until>e.level().getGameTime();
    }

    /**
     * Slowness V and nausea, renewed to last out the wound and a little after; and the body kept off its feet as long.
     * Invisible (no swirl of particles) but for the icons on a player's screen.
     */
    private static void hamper(LivingEntity victim,Wound wound,long now) {
        long left=Math.max(Math.max(wound.expires,wound.flowing)-now,0);
        int slow=(int)Math.min(20*60,left+LINGER);
        victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,slow,SLOWNESS,false,false,true));
        // Vanilla only draws nausea past three seconds left, so it is always given at least five.
        victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.CONFUSION,(int)Math.max(NAUSEA_LEAST,slow+60),0,false,false,true));
        GROUNDED.put(victim.getUUID(),now+slow);
    }

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
        hamper(victim,wound,now);
        notifyClients(victim,wound,now);
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
        hamper(victim,wound,now);
        notifyClients(victim,wound,now);
    }

    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        if(now%20==0&&level.dimension()==ServerLevel.OVERWORLD)forgetGrounded(now);
        // Snapshot the keys: a wound that finishes its victim fires a death event, and that clears
        // entries from this very map while we are still walking it.
        for(UUID id:new ArrayList<>(WOUNDS.keySet())) {
            Wound wound=WOUNDS.get(id);
            if(wound==null)continue;
            if(!(level.getEntity(id) instanceof LivingEntity victim))continue;
            if(!victim.isAlive()||now>=wound.expires&&now>=wound.flowing){WOUNDS.remove(id);notifyClients(victim,0,false);continue;}
            if((now+victim.getId())%RENEW==0)hamper(victim,wound,now);
            if(now<wound.next)continue;
            wound.next=now+INTERVAL;
            boolean flowing=now<wound.flowing;
            // The blades have closed; a beam's hole goes on pouring by itself.
            if(now>=wound.expires&&wound.stacks>0){wound.stacks=0;notifyClients(victim,wound,now);}
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
            // Bleeding is a passive loss of health: credited to whoever opened the wound, but it never shoves.
            dealing=true;
            try{victim.hurt(source,amount);}finally{dealing=false;}
            if(owner!=null)HexServer.reward(owner,Discipline.CONJURATION,20);
            if(WOUNDS.containsKey(id))notifyClients(victim,wound,now);
        }
    }

    /** How a wound looks to clients, in stacks: its live blades, or a flowing hole's weight if that is more. */
    private static int shown(Wound wound,long now) {
        return Math.max(now<wound.expires?wound.stacks:0,now<wound.flowing?FLOW_SHOWN:0);
    }

    private static void notifyClients(LivingEntity victim,Wound wound,long now) {notifyClients(victim,shown(wound,now),now<wound.flowing);}

    /** Also whether a beam's hole is what is pouring: clients draw that far heavier than a blade's wound. */
    private static void notifyClients(LivingEntity victim,int stacks,boolean pouring) {
        CompoundTag n=new CompoundTag();n.putInt("stacks",stacks);n.putBoolean("pouring",pouring);
        HexNetwork.tracking(victim,new HexNetwork.Message(HexNetwork.BLEED,victim.getId(),n));
    }
    public static void clear(LivingEntity e) {GROUNDED.remove(e.getUUID());if(WOUNDS.remove(e.getUUID())!=null)notifyClients(e,0,false);}
    public static void reset() {WOUNDS.clear();GROUNDED.clear();}

    /** Once a second or so: forget the bodies that may jump again (or are gone). */
    public static void forgetGrounded(long now) {GROUNDED.values().removeIf(until->until<=now);}
}
