package com.hexgodofstories.server;

import com.hexgodofstories.data.Discipline;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/** Stacking wounds from embedded blades. Damage is credited to the caster so kills read as theirs. */
public final class Bleed {
    private static final class Wound {
        final UUID owner;int stacks;long expires,next;
        Wound(UUID owner,int stacks,long expires,long next) {this.owner=owner;this.stacks=stacks;this.expires=expires;this.next=next;}
    }
    private static final int MAX_STACKS=5,INTERVAL=20,MAX_TRACKED=192;
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
            WOUNDS.put(victim.getUUID(),new Wound(owner,Math.min(MAX_STACKS,stacks),now+duration,now+INTERVAL));
        } else {
            wound.stacks=Math.min(MAX_STACKS,wound.stacks+stacks);
            wound.expires=Math.max(wound.expires,now+duration);
        }
        notifyClients(victim,stacks(victim));
    }

    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        // Snapshot the keys: a wound that finishes its victim fires a death event, and that clears
        // entries from this very map while we are still walking it.
        for(UUID id:new ArrayList<>(WOUNDS.keySet())) {
            Wound wound=WOUNDS.get(id);
            if(wound==null)continue;
            if(!(level.getEntity(id) instanceof LivingEntity victim))continue;
            if(!victim.isAlive()||now>=wound.expires){WOUNDS.remove(id);notifyClients(victim,0);continue;}
            if(now<wound.next)continue;
            wound.next=now+INTERVAL;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(wound.owner);
            // Bleeding out from a bite is still a kill by the thing that bit you, and still says so:
            // without this the last tick of a wound Hexor opened would be reported as plain magic.
            net.minecraft.world.damagesource.DamageSource source;
            if(owner!=null)source=owner.damageSources().indirectMagic(owner,owner);
            else if(level.getEntity(wound.owner) instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity hexor)
                source=hexor.attackDamage(victim);
            else source=level.damageSources().magic();
            victim.hurt(source,.8f*wound.stacks);
            if(owner!=null)HexServer.reward(owner,Discipline.CONJURATION,20);
            if(WOUNDS.containsKey(id))notifyClients(victim,wound.stacks);
        }
    }

    private static void notifyClients(LivingEntity victim,int stacks) {
        CompoundTag n=new CompoundTag();n.putInt("stacks",stacks);
        HexNetwork.tracking(victim,new HexNetwork.Message(HexNetwork.BLEED,victim.getId(),n));
    }
    public static void clear(LivingEntity e) {if(WOUNDS.remove(e.getUUID())!=null)notifyClients(e,0);}
    public static void reset() {WOUNDS.clear();}
}
