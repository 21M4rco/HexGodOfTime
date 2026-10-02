package com.hexgodofstories.server;

import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The Deceiver's guard: the use key held with the sword in hand. The bearer stands easy (Malenia's stance: the sword
 * arm loose, the long blade angled down and out), walks freely, and turns aside whatever comes at them from the front:
 * a shot is slapped back the way it came and becomes theirs; a blow is parried, the blades clashing in sparks and the
 * attacker thrown back a step. Each costs a little Temporal Energy, enough to make it a choice and little enough to make
 * it a habit; with none left the guard is only a stance.
 */
public final class SwordGuard {
    private SwordGuard() {}

    /** Synced: whether the bearer is holding the guard (clients draw the stance from it). */
    public static final String GUARD = "swordGuard";
    /** Energy a turned shot costs, and a parried blow. */
    static final float SHOT_COST = 1.5f, BLOW_COST = 2.5f;

    private static final Set<UUID> GUARDS = new HashSet<>();

    public static void begin(ServerPlayer p) {
        // Burning (transformed), the key looses the blade's fire instead: no guard while it burns.
        if (BladeFire.lit(p)) {FlameStream.begin(p); return;}
        if (!armed(p) || BladeCombo.running(p) || Evisceration.running(p)) return;
        if (GUARDS.add(p.getUUID())) {HexData.get(p).putBoolean(GUARD, true); HexNetwork.sync(p);}
    }

    /** The use key let go: the guard drops, and so does a burning blade's fire. */
    public static void end(ServerPlayer p) {
        FlameStream.end(p);
        lower(p);
    }

    private static void lower(ServerPlayer p) {
        if (GUARDS.remove(p.getUUID())) {HexData.get(p).remove(GUARD); HexNetwork.sync(p);}
    }

    /** Every tick of a bearer's: the guard drops with the sword. */
    public static void tick(ServerPlayer p) {
        if (GUARDS.contains(p.getUUID()) && (!armed(p) || BladeFire.lit(p))) lower(p);
    }

    public static void forget(ServerPlayer p) {GUARDS.remove(p.getUUID()); HexData.get(p).remove(GUARD);}
    public static void reset() {GUARDS.clear();}

    public static boolean guarding(Entity e) {return e instanceof ServerPlayer p && GUARDS.contains(p.getUUID()) && armed(p);}

    private static boolean armed(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        return p.isAlive() && !p.isSpectator() && HexData.access(p) && !TemporalEngine.frozen(p) && !ScepterBlast.stunned(p)
            && held.getItem() instanceof ConjuredWeapon w && w.kind == 3 && ConjuredWeapon.belongsTo(held, p);
    }

    /** From in front: whatever comes at the guard from behind it goes unseen. */
    private static boolean facing(ServerPlayer p, Vec3 from) {
        Vec3 to = from.subtract(p.getEyePosition()), look = p.getLookAngle();
        Vec3 a = new Vec3(to.x, 0, to.z), b = new Vec3(look.x, 0, look.z);
        if (a.lengthSqr() < 1e-6 || b.lengthSqr() < 1e-6) return true;
        return a.normalize().dot(b.normalize()) >= -.05;
    }

    /** A shot about to strike a guarding bearer, slapped back the way it came. @return true if it was turned. */
    public static boolean deflect(ServerPlayer p, Projectile shot) {
        if (!guarding(p) || shot.getOwner() == p || !facing(p, shot.position()) || HexData.energy(p) < SHOT_COST) return false;
        HexData.spend(p, SHOT_COST);
        Vec3 v = shot.getDeltaMovement();
        Vec3 back = v.lengthSqr() < 1e-6 ? p.getLookAngle() : v.normalize().scale(-1);
        Vec3 aside = right(p).scale((p.getRandom().nextDouble() - .5) * .6);
        shot.setDeltaMovement(back.add(aside).add(0, .12, 0).normalize().scale(Math.max(.6, v.length() * .85)));
        shot.setOwner(p);
        shot.hurtMarked = true;
        clash(p, shot.position(), shot.position(), .8f);
        return true;
    }

    /** A blow about to land on a guarding bearer, parried and its striker thrown back. @return true if it was. */
    public static boolean parry(ServerPlayer p, DamageSource source) {
        if (!guarding(p) || !(source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)))
            return false;
        if (!(source.getEntity() instanceof LivingEntity attacker) || source.getDirectEntity() != attacker || attacker == p) return false;
        if (!facing(p, attacker.getEyePosition()) || HexData.energy(p) < BLOW_COST) return false;
        HexData.spend(p, BLOW_COST);
        Vec3 away = attacker.position().subtract(p.position());
        away = new Vec3(away.x, 0, away.z);
        away = away.lengthSqr() < 1e-6 ? right(p).cross(new Vec3(0, -1, 0)) : away.normalize();
        attacker.setDeltaMovement(attacker.getDeltaMovement().add(away.x * .9, .28, away.z * .9));
        attacker.hurtMarked = true;
        Vec3 eye = p.getEyePosition();
        clash(p, eye.add(attacker.getEyePosition().subtract(eye).scale(.45)).add(0, -.3, 0), attacker.getEyePosition(), 1);
        return true;
    }

    /** Steel on steel: sparks where they met, the ring of it, and the bearer's slap toward the side it came from. */
    private static void clash(ServerPlayer p, Vec3 at, Vec3 from, float volume) {
        ServerLevel level = p.serverLevel();
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 14, .12, .12, .12, .5);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 12, .08, .08, .08, .4);
        BlockPos pos = BlockPos.containing(at);
        level.playSound(null, pos, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, volume, 1.45f + p.getRandom().nextFloat() * .15f);
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, .22f * volume, 1.9f + p.getRandom().nextFloat() * .1f);
        boolean right = from.subtract(p.position()).dot(right(p)) > 0;
        HexNetwork.animate(p, right ? "blade_deflect_r" : "blade_deflect_l");
        HexNetwork.sync(p);
    }

    /** The bearer's right, level with the ground. */
    private static Vec3 right(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 f = new Vec3(look.x, 0, look.z);
        f = f.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : f.normalize();
        return new Vec3(-f.z, 0, f.x);
    }
}
