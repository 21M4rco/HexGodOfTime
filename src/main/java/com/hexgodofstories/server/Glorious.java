package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.Ability;
import com.hexgodofstories.data.Discipline;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.entity.IllusionEntity;
import com.hexgodofstories.entity.SpellProjectile;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Glorious Purpose's spells: what each ability becomes while the full transformation is worn ({@link
 * com.hexgodofstories.data.Ascended} names and describes them). The same key, the same slot, the same cost; a
 * bigger spell, and most of them a longer recovery for it.
 *
 * <p>Everything here is the server's, as the plain spells are: who is moved, struck, charmed or frozen, and how
 * hard. Clients are only told what to show, through the ordinary effect messages.
 */
public final class Glorious {
    private Glorious() { }

    /**
     * The variant's cast, for an ability the mantle changes at its cast key, or null when it does not (or, for
     * Exchange, when there is nothing in the look and the ordinary swap should happen instead).
     */
    static Boolean cast(ServerPlayer p, Ability a, Entity aimed) {
        switch (a) {
            case PROJECTION_SWAP: return exchange(p);
            case MIRAGE: return delusion(p);
            case BOLT: return storm(p);
            case PUSH: return kneel(p);
            case TELEKINESIS: return manyHands(p, aimed);
            case BLINK: return behind(p);
            case WARD: return mirror(p);
            case ENCHANT: return silverTongue(p, aimed);
            case MEMORY: return totalRecall(p, aimed);
            case TIME_SLIP: return HexServer.slipstream(p);
            case REWIND: return sender(p);
            case SELECTIVE_STOP: return chosenMany(p, aimed);
            default: return null;
        }
    }

    /** Too big to be thrown about by a spell meant for people: the sea's god, and anything of its size. */
    static boolean huge(Entity e) {
        return e instanceof AbyssalPilgrimEntity || e.getType() == HexGodOfStories.PILGRIM.get() || e.getBbWidth() > 3.5f || e.getBbHeight() > 6;
    }

    /** The heading that looks along {@code d}, in Minecraft's degrees. */
    private static float yaw(Vec3 d) {return (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);}

    // ------------------------------------------------------------------ Exchange

    /** Trade places with what is in the look: it is left where the caster stood, turned away and reeling. */
    private static Boolean exchange(ServerPlayer p) {
        Entity t = HexServer.target(p, 32);
        if (!(t instanceof LivingEntity body) || !HexServer.foe(p, body)) return null;
        if (huge(body) || body.isPassenger() || body.isVehicle() || TemporalEngine.frozen(body) || Erasure.erasing(body)) {
            HexServer.notice(p, "That will not trade places with you.");
            return false;
        }
        ServerLevel level = p.serverLevel();
        Vec3 mine = p.position(), theirs = body.position();
        if (!HexServer.safe(p, theirs) || !level.noCollision(body, body.getBoundingBox().move(mine.subtract(theirs)))) {
            HexServer.notice(p, "There is no room for the exchange.");
            return false;
        }
        float away = yaw(mine.subtract(theirs));
        HexServer.gesture(p, "blink", "depart", HexGodOfStories.TELEPORT.get());
        HexNetwork.fx(body, "depart");
        HexServer.teleport(p, theirs);
        if (body instanceof ServerPlayer q) {
            q.stopRiding();
            q.teleportTo(q.serverLevel(), mine.x, mine.y, mine.z, away, q.getXRot());
        } else {
            body.teleportTo(mine.x, mine.y, mine.z);
            body.setYRot(away);
            body.setYHeadRot(away);
            body.setYBodyRot(away);
            if (body instanceof Mob mob) {mob.getNavigation().stop(); mob.setTarget(null);}
        }
        body.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false));
        body.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false));
        HexNetwork.arrival(p);
        HexNetwork.arrival(body);
        return true;
    }

    // ------------------------------------------------------------------ Impostor

    /** The one whose face was taken is left the stranger: it glows, and every creature near it turns on it. */
    static void impostor(ServerPlayer p, LivingEntity victim) {
        victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
        if (victim instanceof ServerPlayer q) {
            q.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
            q.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, false, false));
        }
        for (Mob mob : p.serverLevel().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(16),
            m -> m != victim && m.isAlive() && (m instanceof Enemy || m instanceof NeutralMob) && !(m instanceof IllusionEntity) && !huge(m)))
            Delusion.force(mob, victim, 200);
        HexNetwork.fx(victim, "marked");
    }

    // ------------------------------------------------------------------ Mass Delusion

    /** The court, a longer vanishing, and every creature near turned on the nearest other creature. */
    private static boolean delusion(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        HexServer.duplicate(p, true, false);
        long now = HexData.now(p);
        HexData.get(p).putLong("vanishUntil", now + 100);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0, false, false));
        List<LivingEntity> crowd = level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16),
            e -> e != p && e.isAlive() && !e.isSpectator() && !(e instanceof IllusionEntity) && !(e instanceof ArmorStand) && !huge(e));
        for (LivingEntity e : crowd) {
            if (e instanceof ServerPlayer q) {
                if (!HexServer.foe(p, q)) continue;
                q.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, false, false));
                q.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
                continue;
            }
            if (!(e instanceof Mob mob) || !HexServer.foe(p, mob)) continue;
            // Creatures turn on creatures: the nearest one to each that is not itself.
            LivingEntity other = null;
            double nearest = Double.MAX_VALUE;
            for (LivingEntity o : crowd) {
                if (o == mob || o instanceof Player) continue;
                double d = o.distanceToSqr(mob);
                if (d < nearest) {nearest = d; other = o;}
            }
            if (other != null) Delusion.force(mob, other, 160);
            else mob.setTarget(null);
        }
        HexServer.gesture(p, "illusion", "cast", HexGodOfStories.ILLUSION_SOUND.get());
        HexNetwork.fx(p, "delusion");
        return true;
    }

    // ------------------------------------------------------------------ Emerald Storm

    /** Five bolts fanned out, each turning after the nearest creature ahead of it. */
    private static boolean storm(ServerPlayer p) {
        Vec3 look = p.getLookAngle(), eye = p.getEyePosition();
        for (int i = -2; i <= 2; i++) {
            Vec3 dir = look.yRot((float) Math.toRadians(i * 10)).normalize();
            SpellProjectile.cast(p, eye.add(dir.scale(.5)), dir, 0, false, true);
        }
        HexServer.gesture(p, "bolt", "cast", HexGodOfStories.SORCERY.get());
        return true;
    }

    // ------------------------------------------------------------------ Kneel

    /** Everything near is hammered down: three hearts, held where it lies three seconds, and slow to rise. */
    private static boolean kneel(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        DamageSource source = p.damageSources().indirectMagic(p, p);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(12),
            e -> HexServer.foe(p, e) && e.distanceToSqr(p) <= 144 && !huge(e))) {
            e.invulnerableTime = 0;
            e.hurt(source, 6);
            if (!e.isAlive()) continue;
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.x * .2, Math.min(v.y, -1.1), v.z * .2);
            e.hurtMarked = true;
            if (e instanceof ServerPlayer q) q.connection.send(new ClientboundSetEntityMotionPacket(q));
            ScepterBlast.stun(e, 60);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 2, false, true));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 0, false, true));
            HexNetwork.fx(e, "kneel_hit");
        }
        HexNetwork.animate(p, "arsenal_throw");
        HexNetwork.fx(p, "kneel");
        level.playSound(null, p.blockPosition(), HexGodOfStories.ARSENAL_EXPLOSION_FAR.get(), SoundSource.PLAYERS, 1.4f, .55f);
        level.playSound(null, p.blockPosition(), HexGodOfStories.SORCERY.get(), SoundSource.PLAYERS, 1, .6f);
        return true;
    }

    // ------------------------------------------------------------------ Many Hands

    /** Everything in front of the caster lifted at once, up to five bodies, the one in the look first. */
    private static boolean manyHands(ServerPlayer p, Entity aimed) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        List<Entity> bodies = new ArrayList<>();
        List<Entity> rest = new ArrayList<>();
        if (aimed != null) bodies.add(aimed);
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(14), e -> e instanceof LivingEntity && e != aimed && HexServer.foe(p, e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d > 14 || d < 1e-3 || to.scale(1 / d).dot(look) < .82 || !p.hasLineOfSight(e)) continue;
            rest.add(e);
        }
        rest.sort(Comparator.comparingDouble(p::distanceToSqr));
        bodies.addAll(rest);
        int taken = 0;
        for (Entity e : bodies) {
            if (taken >= 5) break;
            if (Telekinesis.grab(p, e)) taken++;
        }
        if (taken == 0) return false;
        HexServer.gesture(p, "telekinesis", "hold", HexGodOfStories.SORCERY.get());
        return true;
    }

    // ------------------------------------------------------------------ Behind You

    /** Out of the air right behind what is in the look, facing its back; or, with nothing there, a step twice as far. */
    private static boolean behind(ServerPlayer p) {
        Entity t = HexServer.target(p, 32);
        if (t instanceof LivingEntity body && HexServer.foe(p, body) && !huge(body)) {
            Vec3 facing = Vec3.directionFromRotation(0, body.getYRot());
            double back = body.getBbWidth() / 2 + .9;
            for (int turn : new int[]{0, 30, -30, 60, -60, 100, -100})
                for (double lift : new double[]{0, .5, 1}) {
                    Vec3 dir = facing.yRot((float) Math.toRadians(turn));
                    Vec3 spot = body.position().subtract(dir.scale(back)).add(0, lift, 0);
                    if (!HexServer.safe(p, spot)) continue;
                    HexServer.gesture(p, "blink", "depart", HexGodOfStories.TELEPORT.get());
                    Vec3 toward = body.getEyePosition().subtract(spot.add(0, p.getEyeHeight(), 0));
                    float pitch = (float) (-Mth.atan2(toward.y, Math.sqrt(toward.x * toward.x + toward.z * toward.z)) * Mth.RAD_TO_DEG);
                    Telekinesis.release(p, false);
                    p.stopRiding();
                    p.connection.teleport(spot.x, spot.y, spot.z, yaw(toward), pitch);
                    p.setDeltaMovement(Vec3.ZERO);
                    p.fallDistance = 0;
                    HexNetwork.arrival(p);
                    return true;
                }
            HexServer.notice(p, "There is no room behind it.");
            return false;
        }
        Vec3 destination = HexServer.safeAim(p, 2 * (8 + HexData.mastery(p, Discipline.SORCERY) / 90.0));
        if (destination == null) return false;
        HexServer.gesture(p, "blink", "depart", HexGodOfStories.TELEPORT.get());
        HexServer.teleport(p, destination);
        HexNetwork.arrival(p);
        return true;
    }

    // ------------------------------------------------------------------ Mirror Ward

    /** True while a struck-back blow is being dealt, so two mirrors facing each other do not reflect forever. */
    private static boolean reflecting;

    private static boolean mirror(ServerPlayer p) {
        long now = HexData.now(p);
        HexData.get(p).putLong("wardUntil", now + 120);
        HexData.get(p).putLong("mirrorUntil", now + 120);
        HexServer.gesture(p, "ward", "ward", HexGodOfStories.SORCERY.get());
        HexNetwork.fx(p, "mirror");
        return true;
    }

    /** Whether a Mirror Ward is up on this body. */
    public static boolean mirrored(Entity e) {return e instanceof ServerPlayer p && HexData.get(p).getLong("mirrorUntil") > HexData.now(p);}

    /** A blow struck back at whoever dealt it. */
    public static void reflect(ServerPlayer warded, LivingEntity attacker, float amount) {
        if (reflecting || amount <= 0 || !attacker.isAlive() || attacker == warded) return;
        reflecting = true;
        try {
            attacker.hurt(warded.damageSources().indirectMagic(warded, warded), amount);
            HexNetwork.fx(attacker, "banked");
        } finally {
            reflecting = false;
        }
    }

    /** A projectile turned round in the air and sent back the way it came, now the warded caster's own. */
    public static void rebound(ServerPlayer warded, Projectile projectile) {
        projectile.setDeltaMovement(projectile.getDeltaMovement().scale(-1.15));
        projectile.setOwner(warded);
        projectile.hurtMarked = true;
        HexNetwork.fx(warded, "mirror");
    }

    // ------------------------------------------------------------------ Silver Tongue

    /** Every creature near that could be charmed is, at once; a player in the look lets go of what they hold. */
    private static boolean silverTongue(ServerPlayer p, Entity aimed) {
        boolean any = false;
        if (aimed instanceof ServerPlayer other && HexServer.foe(p, other)) {
            ItemStack held = other.getMainHandItem();
            if (!held.isEmpty()) {
                other.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                other.drop(held, true, false);
            }
            other.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false));
            HexNetwork.fx(other, "enchant");
            any = true;
        }
        double limit = 2 * (30 + HexData.mastery(p, Discipline.ENCHANTMENT) * .25);
        List<Mob> mobs = p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(10),
            m -> HexServer.foe(p, m) && m.getMaxHealth() <= limit && !(m instanceof IllusionEntity) && !huge(m) && !HexServer.charmed(m));
        mobs.sort(Comparator.comparingDouble(p::distanceToSqr));
        int ticks = 2 * (240 + HexData.mastery(p, Discipline.ENCHANTMENT) / 2);
        for (int i = 0; i < Math.min(6, mobs.size()); i++) {
            HexServer.charm(p, mobs.get(i), ticks);
            any = true;
        }
        if (any) HexServer.gesture(p, "enchant", "enchant", HexGodOfStories.ILLUSION_SOUND.get());
        return any;
    }

    // ------------------------------------------------------------------ Total Recall

    /** Everything living near glows through walls for twelve seconds, and the one in the look shows its trail. */
    private static boolean totalRecall(ServerPlayer p, Entity aimed) {
        for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(48),
            e -> e != p && e.isAlive() && !e.isSpectator() && !(e instanceof IllusionEntity) && !(e instanceof ArmorStand)))
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 240, 0, false, false));
        if (aimed != null) HexServer.memoryTrail(p, aimed);
        HexServer.gesture(p, "enchant", "memory", HexGodOfStories.ILLUSION_SOUND.get());
        HexNetwork.fx(p, "recall");
        return true;
    }

    // ------------------------------------------------------------------ Return to Sender

    /** A blow a keeper took: when, from whom, and how much. Ten seconds of them are kept, for Return to Sender. */
    private record Wound(long at, UUID by, float amount) { }
    private static final Map<UUID, ArrayDeque<Wound>> WOUNDS = new HashMap<>();
    private static final int REMEMBERED = 200;

    /** A keeper has been struck: kept ten seconds, for Return to Sender to give back. */
    public static void wounded(ServerPlayer p, Entity by, float amount) {
        if (by == null || by == p || amount <= 0 || !HexData.access(p)) return;
        ArrayDeque<Wound> list = WOUNDS.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        long now = HexData.now(p);
        list.addLast(new Wound(now, by.getUUID(), amount));
        while (!list.isEmpty() && (now - list.getFirst().at() > REMEMBERED || list.size() > 64)) list.removeFirst();
    }

    /** Rewind as ever, and every blow taken in those ten seconds lands on whoever dealt it. */
    private static boolean sender(ServerPlayer p) {
        ArrayDeque<Wound> list = WOUNDS.remove(p.getUUID());
        if (!PersonalRewind.rewind(p)) {
            if (list != null) WOUNDS.put(p.getUUID(), list);
            HexServer.notice(p, "Ten safe seconds of personal history and an unchanged inventory (apart from eaten food) are required.");
            return false;
        }
        HexServer.gesture(p, "time_slip", "slip", HexGodOfStories.SLIP.get());
        if (list == null) return true;
        long now = HexData.now(p);
        Map<UUID, Float> owed = new LinkedHashMap<>();
        for (Wound w : list) if (now - w.at() <= REMEMBERED + 20) owed.merge(w.by(), w.amount(), Float::sum);
        for (Map.Entry<UUID, Float> debt : owed.entrySet()) {
            Entity e = p.serverLevel().getEntity(debt.getKey());
            if (!(e instanceof LivingEntity dealt) || !dealt.isAlive() || !HexServer.foe(p, dealt) || dealt.distanceToSqr(p) > 128 * 128) continue;
            dealt.invulnerableTime = 0;
            dealt.hurt(p.damageSources().indirectMagic(p, p), Math.min(40, debt.getValue()));
            HexNetwork.fx(dealt, "slip");
        }
        return true;
    }

    // ------------------------------------------------------------------ Chosen Many

    /** The one in the look, and every creature within six blocks of it, up to six, suspended together. */
    private static boolean chosenMany(ServerPlayer p, Entity aimed) {
        if (aimed == null || !HexServer.foe(p, aimed)) return false;
        List<Entity> bodies = new ArrayList<>();
        bodies.add(aimed);
        List<Entity> near = p.level().getEntities(p, aimed.getBoundingBox().inflate(6),
            e -> e != aimed && e instanceof LivingEntity && HexServer.foe(p, e) && !(e instanceof IllusionEntity));
        near.sort(Comparator.comparingDouble(aimed::distanceToSqr));
        for (Entity e : near) {
            if (bodies.size() >= 6) break;
            bodies.add(e);
        }
        int held = 0;
        for (Entity e : bodies) if (!e.isPassenger() && TemporalEngine.field(p, true, e, e instanceof Player ? 40 : 100)) held++;
        if (held == 0) return false;
        HexServer.gesture(p, "time_stop", "bind", HexGodOfStories.STOP.get());
        return true;
    }

    public static void forget(ServerPlayer p) {WOUNDS.remove(p.getUUID());}
    public static void reset() {WOUNDS.clear();}
}
