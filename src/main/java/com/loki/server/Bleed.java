package com.loki.server;

import com.loki.data.Discipline;
import com.loki.network.LokiNetwork;
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
        if(!victim.isAlive()||victim.getType().is(net.minecraft.tags.EntityTypeTags.SKELETONS))return;
        long now=victim.level().getGameTime();
        Wound wound=WOUNDS.get(victim.getUUID());
        if(wound==null) {
            if(WOUNDS.size()>=MAX_TRACKED)return;
            WOUNDS.put(victim.getUUID(),new Wound(owner.getUUID(),Math.min(MAX_STACKS,stacks),now+duration,now+INTERVAL));
        } else {
            wound.stacks=Math.min(MAX_STACKS,wound.stacks+stacks);
            wound.expires=Math.max(wound.expires,now+duration);
        }
        notifyClients(victim,stacks(victim));
    }

    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        Iterator<Map.Entry<UUID,Wound>> it=WOUNDS.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<UUID,Wound> entry=it.next();
            if(!(level.getEntity(entry.getKey()) instanceof LivingEntity victim))continue;
            Wound wound=entry.getValue();
            if(!victim.isAlive()||now>=wound.expires){it.remove();notifyClients(victim,0);continue;}
            if(now<wound.next)continue;
            wound.next=now+INTERVAL;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(wound.owner);
            var source=owner!=null?owner.damageSources().indirectMagic(owner,owner):level.damageSources().magic();
            victim.hurt(source,.8f*wound.stacks);
            if(owner!=null)LokiServer.reward(owner,Discipline.CONJURATION,20);
            notifyClients(victim,wound.stacks);
        }
    }

    private static void notifyClients(LivingEntity victim,int stacks) {
        CompoundTag n=new CompoundTag();n.putInt("stacks",stacks);
        LokiNetwork.tracking(victim,new LokiNetwork.Message(LokiNetwork.BLEED,victim.getId(),n));
    }
    public static void clear(LivingEntity e) {if(WOUNDS.remove(e.getUUID())!=null)notifyClients(e,0);}
    public static void reset() {WOUNDS.clear();}
}
