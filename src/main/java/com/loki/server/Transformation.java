package com.loki.server;

import com.loki.data.LokiData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/**
 * What the final mantle is actually made of.
 *
 * <p>The transformed body is the armour, so the protection is granted as real attribute modifiers on
 * {@link Attributes#ARMOR} and {@link Attributes#ARMOR_TOUGHNESS} rather than as icons drawn over the
 * HUD. Minecraft's own damage reduction then does the work: the same curve that a full diamond set
 * feeds, from the same numbers, with no separate damage hook to disagree with it. Nothing needs to be
 * equipped and no armour slot is touched, so the transformation model is never covered.
 *
 * <p>Resistance and fire resistance are refreshed on a short duration rather than granted once for the
 * whole transformation. That is deliberate: a short lease that is renewed while the mantle is worn
 * cannot outlive it. Even if a crash, a dimension change or an unforeseen path skips the teardown
 * below, the effects lapse within a few seconds instead of leaving somebody permanently armoured.
 */
public final class Transformation {
    private Transformation() {}

    /** Fixed identities so a modifier is replaced rather than stacked, across relogs and respawns. */
    private static final UUID ARMOUR=UUID.fromString("7c1f0c2e-5a41-4d18-9b5e-0a7c39d5a111");
    private static final UUID TOUGHNESS=UUID.fromString("7c1f0c2e-5a41-4d18-9b5e-0a7c39d5a112");
    /** A full diamond set, to the point: twenty armour and eight toughness. */
    private static final double ARMOUR_POINTS=20,TOUGHNESS_POINTS=8;
    /** Renewed every second on a five-second lease, so the grant always trails the mantle. */
    private static final int LEASE=100,RENEW=20;

    public static boolean transformed(ServerPlayer p) {return LokiData.get(p).getBoolean("ascended");}

    /** Called every player tick. Grants on the first tick of the mantle and renews while it is worn. */
    public static void sustain(ServerPlayer p) {
        if(!p.isAlive()||p.isSpectator()||!transformed(p)){strip(p);return;}
        grant(p.getAttribute(Attributes.ARMOR),ARMOUR,"loki.mantle.armour",ARMOUR_POINTS);
        grant(p.getAttribute(Attributes.ARMOR_TOUGHNESS),TOUGHNESS,"loki.mantle.toughness",TOUGHNESS_POINTS);
        if(LokiData.now(p)%RENEW!=0)return;
        // Invisible but iconed: the swirl would sit on top of the crown and the cloak, the icon tells
        // the owner the protection is live without touching the model at all.
        renew(p,new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,LEASE,1,false,false,true));
        renew(p,new MobEffectInstance(MobEffects.FIRE_RESISTANCE,LEASE,2,false,false,true));
    }

    /** Removes everything the mantle granted. Safe to call on a player who never had it. */
    public static void strip(ServerPlayer p) {
        revoke(p.getAttribute(Attributes.ARMOR),ARMOUR);
        revoke(p.getAttribute(Attributes.ARMOR_TOUGHNESS),TOUGHNESS);
        clear(p,MobEffects.DAMAGE_RESISTANCE,1);
        clear(p,MobEffects.FIRE_RESISTANCE,2);
    }

    private static void grant(AttributeInstance attribute,UUID id,String name,double amount) {
        if(attribute==null)return;
        AttributeModifier existing=attribute.getModifier(id);
        if(existing!=null&&existing.getAmount()==amount)return;
        if(existing!=null)attribute.removeModifier(id);
        attribute.addPermanentModifier(new AttributeModifier(id,name,amount,AttributeModifier.Operation.ADDITION));
    }
    private static void revoke(AttributeInstance attribute,UUID id) {
        if(attribute!=null&&attribute.getModifier(id)!=null)attribute.removeModifier(id);
    }
    /** Re-leases without re-applying every tick, and never downgrades a stronger effect from elsewhere. */
    private static void renew(ServerPlayer p,MobEffectInstance wanted) {
        MobEffectInstance held=p.getEffect(wanted.getEffect());
        if(held!=null&&held.getAmplifier()>wanted.getAmplifier())return;
        if(held!=null&&held.getAmplifier()==wanted.getAmplifier()&&held.getDuration()>LEASE-RENEW*2)return;
        p.addEffect(wanted);
    }
    /** Only drops the lease this class granted; a potion the player drank is left alone. */
    private static void clear(ServerPlayer p,net.minecraft.world.effect.MobEffect effect,int amplifier) {
        MobEffectInstance held=p.getEffect(effect);
        if(held!=null&&held.getAmplifier()==amplifier&&held.getDuration()<=LEASE)p.removeEffect(effect);
    }
}
