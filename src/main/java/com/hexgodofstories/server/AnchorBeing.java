package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.entity.IllusionEntity;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.warping.Warping;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Replacement for the THREADS slot. All targeting, timing and damage live on the server. */
public final class AnchorBeing {
    private AnchorBeing() {}
    private static final Map<UUID,IllusionEntity> ANCHORS=new HashMap<>();
    private static final Map<UUID,Hold> HOLDS=new HashMap<>();
    private static final class Hold {
        final long start;long heartbeat,nextSlash,resume;
        final Set<UUID> struck=new HashSet<>();
        Hold(long now){start=heartbeat=now;}
    }
    public static boolean active(ServerPlayer p){return HOLDS.containsKey(p.getUUID());}

    public static boolean cast(ServerPlayer p) {
        if(p.isPassenger()||p.isSleeping())return false;
        IllusionEntity copy=HexGodOfStories.ILLUSION.get().create(p.serverLevel());
        if(copy==null)return false;
        copy.moveTo(p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot());
        copy.configureAnchor(p);
        if(!p.serverLevel().addFreshEntity(copy))return false;
        IllusionEntity previous=ANCHORS.put(p.getUUID(),copy);
        if(previous!=null&&!previous.isRemoved())previous.dispel();
        long until=HexData.now(p)+AnchorRules.VANISH_TICKS;
        // The equipment is copied before invisibility is granted. No spawn cloud or casting pose gives it away.
        HexData.get(p).putLong("vanishUntil",Math.max(until,HexData.get(p).getLong("vanishUntil")));
        HexData.get(p).putLong("anchorVanishUntil",until);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,AnchorRules.VANISH_TICKS,0,false,false));
        HexNetwork.sync(p);
        for(Mob mob:p.serverLevel().getEntitiesOfClass(Mob.class,p.getBoundingBox().inflate(32),m->m.getTarget()==p)) {
            Decoy.release(mob);mob.setTarget(copy);
        }
        return true;
    }

    public static boolean begin(ServerPlayer p) {
        if(active(p)||Telekinesis.holding(p)||p.isPassenger()||p.isSleeping()||Warping.charging(p))return false;
        long now=HexData.now(p);
        HOLDS.put(p.getUUID(),new Hold(now));
        CompoundTag d=HexData.get(p);d.putLong("gravityStart",now);d.putLong("gravityUntil",now+AnchorRules.HOLD_TICKS);
        HexNetwork.animate(p,"gravity_grasp");HexNetwork.sync(p);
        p.level().playSound(null,p.blockPosition(),HexGodOfStories.GRIP_HOLD.get(),SoundSource.PLAYERS,.65f,.65f);
        return true;
    }
    public static void heartbeat(ServerPlayer p){Hold h=HOLDS.get(p.getUUID());if(h!=null)h.heartbeat=HexData.now(p);}
    public static void release(ServerPlayer p) {
        if(HOLDS.remove(p.getUUID())==null)return;
        CompoundTag d=HexData.get(p);d.remove("gravityStart");d.remove("gravityUntil");
        // Let an already landed slash finish its short recovery after key-up.
        if(d.getLong("gravitySlashUntil")<=HexData.now(p))HexNetwork.animate(p,"__clear__");
        HexNetwork.sync(p);
    }
    public static void tick(ServerPlayer p) {
        Hold h=HOLDS.get(p.getUUID());if(h==null)return;
        long now=HexData.now(p),age=now-h.start;
        if(AnchorRules.expired(age,now-h.heartbeat)||!p.isAlive()||!HexData.access(p)||p.isSpectator()
            ||HexData.selected(p)!=Ability.THREADS||TemporalEngine.frozen(p)||ScepterBlast.stunned(p)
            ||Erasure.erasing(p)||TimeBranch.charging(p)||Arsenal.active(p)||p.isPassenger()) {release(p);return;}
        if(h.resume>0&&now>=h.resume){h.resume=0;HexNetwork.animate(p,"gravity_grasp");}
        Vec3 look=p.getLookAngle(),hand=hand(p);
        double range=AnchorRules.range(age),speed=AnchorRules.speed(age);
        List<LivingEntity> targets=p.serverLevel().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(range),
            e->eligible(p,e)&&!h.struck.contains(e.getUUID())&&p.hasLineOfSight(e)
                &&e.getBoundingBox().getCenter().subtract(p.getEyePosition()).normalize().dot(look)>.35);
        targets.sort(Comparator.comparingDouble(p::distanceToSqr));
        for(LivingEntity e:targets.subList(0,Math.min(32,targets.size()))) {
            if(p.distanceToSqr(e)>range*range)continue;
            Vec3 delta=hand.subtract(e.getBoundingBox().getCenter());
            double reach=2.6+e.getBbWidth()*.5;
            if(p.distanceToSqr(e)<=reach*reach&&Math.abs(e.getEyeY()-p.getEyeY())<2.8&&now>=h.nextSlash) {
                h.struck.add(e.getUUID());h.nextSlash=now+12;h.resume=now+10;
                HexData.get(p).putLong("gravitySlashUntil",now+10);
                HexNetwork.animate(p,"gravity_slash");HexNetwork.sync(p);
                if(e.hurt(p.damageSources().playerAttack(p),AnchorRules.SLASH_DAMAGE))Bleed.apply(p,e,1,AnchorRules.BLEED_TICKS);
                e.knockback(.5,p.getX()-e.getX(),p.getZ()-e.getZ());
                HexNetwork.fx(e,"impact");
                p.level().playSound(null,e.blockPosition(),HexGodOfStories.BLADE_HIT.get(),SoundSource.PLAYERS,.9f,1);
                continue;
            }
            // Ordinary motion/collision; never teleport targets through walls or disable their AI permanently.
            Vec3 motion=e.getDeltaMovement().scale(.55).add(delta.normalize().scale(speed));
            double limit=Math.min(speed*1.5,delta.length());
            if(motion.length()>limit)motion=motion.normalize().scale(limit);
            e.setDeltaMovement(motion);e.hurtMarked=true;e.fallDistance=0;
            if(e instanceof ServerPlayer target)target.connection.send(new ClientboundSetEntityMotionPacket(target));
        }
    }
    private static boolean eligible(ServerPlayer p,LivingEntity e) {
        return HexServer.validTarget(p,e)&&!e.isInvulnerable()&&!TemporalEngine.frozen(e)&&!Erasure.erasing(e)
            &&!e.isPassenger()&&!e.isVehicle()&&!Telekinesis.heldBySomeone(e)
            &&!(e instanceof IllusionEntity copy&&p.getUUID().equals(copy.owner()))
            &&!(e instanceof TamableAnimal pet&&p.getUUID().equals(pet.getOwnerUUID()));
    }
    public static Vec3 hand(LivingEntity p) {
        Vec3 look=p.getLookAngle(),right=new Vec3(-look.z,0,look.x).normalize();
        return p.getEyePosition().add(look.scale(1.25)).add(right.scale(-.32)).add(0,-.25,0);
    }
    public static void explode(IllusionEntity copy,ServerPlayer owner) {
        ServerLevel level=owner.serverLevel();Vec3 centre=copy.position().add(0,.9,0);
        for(LivingEntity e:level.getEntitiesOfClass(LivingEntity.class,copy.getBoundingBox().inflate(AnchorRules.BLAST_RADIUS),
            e->e!=copy&&eligible(owner,e))) {
            if(e.getBoundingBox().getCenter().distanceToSqr(centre)>AnchorRules.BLAST_RADIUS*AnchorRules.BLAST_RADIUS||!copy.hasLineOfSight(e))continue;
            e.hurt(owner.damageSources().indirectMagic(copy,owner),AnchorRules.BLAST_DAMAGE);
            if(e.isAlive())e.addEffect(new MobEffectInstance(MobEffects.CONFUSION,AnchorRules.NAUSEA_TICKS,0));
            e.knockback(1.25,centre.x-e.getX(),centre.z-e.getZ());
        }
        CompoundTag n=new CompoundTag();n.putString("effect","anchor_explosion");
        n.putDouble("x",centre.x);n.putDouble("y",centre.y);n.putDouble("z",centre.z);
        HexNetwork.near(level,centre,64,new HexNetwork.Message(HexNetwork.FX,copy.getId(),n));
        level.playSound(null,copy.blockPosition(),SoundEvents.GENERIC_EXPLODE,SoundSource.PLAYERS,3,.65f);
        // Deliberately no Level.explode: this blast cannot destroy blocks, start fires or drop terrain.
        ANCHORS.remove(owner.getUUID(),copy);copy.dispel();
    }
    public static void clear(ServerPlayer p) {
        release(p);
        IllusionEntity copy=ANCHORS.remove(p.getUUID());if(copy!=null&&!copy.isRemoved())copy.dispel();
        clearState(HexData.get(p));
    }
    public static void clearState(CompoundTag n) {n.remove("gravityStart");n.remove("gravityUntil");n.remove("gravitySlashUntil");n.remove("anchorVanishUntil");}
    public static void reset(){HOLDS.clear();ANCHORS.clear();}
}
