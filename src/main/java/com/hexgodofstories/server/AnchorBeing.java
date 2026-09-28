package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.IllusionEntity;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Anchor Being, the first move of Glorious Purpose.
 *
 * <p>Cast, and the caster is simply gone for {@link #VANISH} ticks: body, armour, what they hold, cloak, name and
 * shadow, from every eye (the existing vanish, {@code vanishUntil}). In the same tick a copy of them takes their
 * place, standing exactly where they stood, looking where they looked, holding what they held and, if they were
 * walking, walking on (see {@link IllusionEntity#anchor}). No sound, no flash, no gesture: nothing on screen says a
 * swap happened. Whatever was hunting the caster turns to the copy.
 *
 * <p>The copy never fights. It watches anything hostile near it and otherwise wanders, for up to {@link #CLONE_LIFE}
 * ticks. Struck by anything, it laughs for two seconds, throws both arms up and bursts in a great green blast: no
 * block is broken, the ground shakes forty blocks across, and everything the caster could harm within {@link
 * #BLAST_REACH} blocks takes twenty hearts (less toward the edge) and twenty seconds of nausea. No shield stops it.
 */
public final class AnchorBeing {
    private AnchorBeing() { }

    /** How long the caster is gone, and how long the copy stands in for them at most. */
    public static final int VANISH = 200, CLONE_LIFE = 400;
    /** The burst: twenty hearts within {@link #BLAST_CORE} blocks, down to half that at {@link #BLAST_REACH}; nausea after. */
    static final float BLAST = 40;
    static final double BLAST_CORE = 5, BLAST_REACH = 9;
    static final int NAUSEA = 400;

    /** Returns whether the swap happened, for the cast to be paid for. */
    public static boolean cast(ServerPlayer p) {
        if (p.isPassenger() || p.isSpectator() || !(p.level() instanceof ServerLevel level)) return false;
        IllusionEntity copy = new IllusionEntity(HexGodOfStories.ILLUSION.get(), level);
        copy.moveTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        // Configured before the caster turns invisible, so the copy does not take the invisibility with it.
        copy.configure(p, new IllusionEntity.Spec(CLONE_LIFE, IllusionEntity.Behavior.WATCH, true, true, false), IllusionEntity.DAGGER);
        copy.anchor(p);
        copy.setDeltaMovement(p.getDeltaMovement());
        if (!level.addFreshEntity(copy)) return false;
        long now = HexData.now(p);
        HexData.get(p).putLong("vanishUntil", now + VANISH);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, VANISH, 0, false, false));
        for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32), m -> m.getTarget() == p)) {
            mob.setTarget(copy);
            Decoy.observe(mob, copy);
        }
        HexNetwork.sync(p);
        return true;
    }

    /** The copy's burst, at the end of its laugh. */
    public static void burst(IllusionEntity copy, ServerPlayer caster) {
        if (!(copy.level() instanceof ServerLevel level)) return;
        Vec3 at = copy.position().add(0, 1, 0);
        // Credited to the caster, from nowhere a shield could face.
        DamageSource source = new DamageSource(level.damageSources().explosion(caster, caster).typeHolder(), null, caster);
        for (Entity e : level.getEntities(copy, new AABB(at, at).inflate(BLAST_REACH), e -> e instanceof LivingEntity && HexServer.validTarget(caster, e))) {
            LivingEntity body = (LivingEntity) e;
            Vec3 middle = body.getBoundingBox().getCenter();
            double distance = Math.max(0, middle.distanceTo(at) - body.getBbWidth() / 2);
            if (distance > BLAST_REACH) continue;
            float strength = distance <= BLAST_CORE ? 1 : (float) (1 - .5 * (distance - BLAST_CORE) / (BLAST_REACH - BLAST_CORE));
            body.invulnerableTime = 0;
            body.hurt(source, BLAST * strength);
            if (!body.isAlive()) continue;
            body.addEffect(new MobEffectInstance(MobEffects.CONFUSION, NAUSEA, 0));
            Vec3 away = middle.subtract(at);
            away = away.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : away.normalize();
            double push = 1.4 * (1 - distance / BLAST_REACH);
            body.setDeltaMovement(body.getDeltaMovement().add(away.x * push, .3 + away.y * push * .5, away.z * push));
            body.hurtMarked = true;
        }
        // Seen, heard and felt from here by everyone within reach of the message: see ArsenalClient.
        CompoundTag n = new CompoundTag();
        n.putString("state", "anchor_burst");
        n.putDouble("x", at.x); n.putDouble("y", copy.getY()); n.putDouble("z", at.z);
        HexNetwork.near(level, at, 256, new HexNetwork.Message(HexNetwork.ARSENAL, copy.getId(), n));
    }
}
