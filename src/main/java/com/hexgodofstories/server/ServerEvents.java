package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class ServerEvents {
    /** Cancel the spawn itself, before pickup, hoppers or another mod can collect a dropped illusion. */
    @SubscribeEvent public static void conjuredDrop(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if(!e.getLevel().isClientSide&&e.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
            &&item.getItem().getItem() instanceof ConjuredWeapon) {e.setCanceled(true);item.discard();}
    }
    @SubscribeEvent public static void conjuredToss(ItemTossEvent e) {
        if(e.getEntity().getItem().getItem() instanceof ConjuredWeapon) {
            e.setCanceled(true);e.getEntity().discard();
            if(e.getPlayer() instanceof ServerPlayer p)HexNetwork.fx(p,"dispel");
        }
    }
    @SubscribeEvent public static void conjuredPickup(EntityItemPickupEvent e) {
        if(e.getItem().getItem().getItem() instanceof ConjuredWeapon){e.setCanceled(true);e.getItem().discard();}
    }
    @SubscribeEvent public static void conjuredDeathDrops(LivingDropsEvent e) {
        e.getDrops().removeIf(item->item.getItem().getItem() instanceof ConjuredWeapon);
    }
    @SubscribeEvent public static void throne(PlayerInteractEvent.RightClickBlock e) {
        if(e.getEntity() instanceof ServerPlayer p&&PocketRealm.sit(p,e.getPos())) {
            e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }
    @SubscribeEvent public static void player(TickEvent.PlayerTickEvent e) {if(e.phase==TickEvent.Phase.END&&e.player instanceof ServerPlayer p)HexServer.tick(p);}
    @SubscribeEvent public static void level(TickEvent.LevelTickEvent e) {
        if(!(e.level instanceof ServerLevel s))return;
        if(e.phase==TickEvent.Phase.START){TemporalEngine.tick(s);HexServer.tickLevel(s);}
        // Turning is eased after the creatures have turned, which is the only point it can be done.
        else TemporalEngine.afterTick(s);
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        if(!HexData.access(p)){HexServer.access(p,false);return;}
        HexData.get(p).remove("transformStart");
        HexData.get(p).remove("branchStart");
        HexData.get(p).remove(BranchFistState.UNTIL);
        HexData.get(p).remove(BranchFistState.START);
        HexData.get(p).remove(BranchFistState.IMPACT);
        // Attribute modifiers are saved with the player, so a session that ended mid-transformation would
        // otherwise hand the armour back for free. Re-derived from the mantle, never inherited.
        Transformation.strip(p);
        Transformation.sustain(p);
        HexNetwork.sync(p);
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking e) {
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        TemporalEngine.track(p,e.getTarget());
        Erasure.track(p,e.getTarget());
        if(!(e.getTarget() instanceof ServerPlayer q))return;
        HexNetwork.syncTo(p,q);
        // A borrowed shape is sent once, not every second, so a new viewer has to be told separately.
        Masquerade.resend(p,q);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {if(e.getEntity() instanceof ServerPlayer p)HexServer.clear(p,false);}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {if(e.getEntity() instanceof ServerPlayer p){HexServer.clear(p,false);HexNetwork.sync(p);}}
    @SubscribeEvent public static void death(LivingDeathEvent e) {
        Erasure.forget(e.getEntity());
        Bleed.clear(e.getEntity());
        Threat.forget(e.getEntity());
        Decoy.release(e.getEntity());
        Starfall.forget(e.getEntity());
        SanctumWard.forget(e.getEntity());
        if(e.getEntity() instanceof ServerPlayer p)HexServer.clear(p,true);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone e) {e.getEntity().getPersistentData().put(HexData.TAG,HexData.get(e.getOriginal()).copy());HexData.clearTransient(e.getEntity(),e.isWasDeath());}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e) {if(e.getEntity() instanceof ServerPlayer p)HexNetwork.sync(p);}
    /**
     * The sanctum's owner cannot be struck inside it. Refusing the attack here, before any damage is
     * worked out, is what makes it a dodge rather than a cancelled hit: by the time anything could
     * have landed, they are standing somewhere else.
     */
    @SubscribeEvent public static void ward(net.minecraftforge.event.entity.living.LivingAttackEvent e) {
        // Only something attacking them. A fall, the void or drowning is the island's business and
        // the owner's own problem; blinking away from gravity would be nonsense.
        if(Erasure.erasing(e.getEntity())){e.setCanceled(true);return;}
        if(e.getSource().getEntity()==null&&e.getSource().getDirectEntity()==null)return;
        if(SanctumWard.evade(e.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent public static void wardProjectile(net.minecraftforge.event.entity.ProjectileImpactEvent e) {
        if(!(e.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit))return;
        if(SanctumWard.evade(hit.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent public static void attack(AttackEntityEvent e) {
        if(TemporalEngine.frozen(e.getEntity())){e.setCanceled(true);return;}
        // Nothing swings while it is being erased, and nothing swings while it is holding the torrent.
        if(Erasure.erasing(e.getEntity())||TimeBranch.planted(e.getEntity())){e.setCanceled(true);return;}
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        if(e.getTarget() instanceof LivingEntity victim)HexServer.engaged(p,victim);
        if(p.getMainHandItem().getItem() instanceof ConjuredWeapon){e.setCanceled(true);HexServer.weapon(p,false);}
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent e) {
        if(e.getLevel().isClientSide||!e.isCancelable())return;
        if(TemporalEngine.frozen(e.getEntity())||Erasure.erasing(e.getEntity())||TimeBranch.planted(e.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void branchPunch(LivingDamageEvent e) {BranchFist.damage(e);}

    @SubscribeEvent public static void hurt(LivingHurtEvent e) {
        if(Erasure.erasing(e.getSource().getEntity())){e.setCanceled(true);return;}
        // A body being taken out of the timeline cannot be hurt out of it. Without this, anything else
        // landing a hit mid-sequence would flash it red or kill it outright, and the slow coming-apart the
        // ability exists for would be replaced by an ordinary death.
        if(Erasure.erasing(e.getEntity())){e.setCanceled(true);return;}
        if(e.getSource().getEntity()!=null&&TemporalEngine.frozen(e.getSource().getEntity())){e.setCanceled(true);return;}
        // A suspended body cannot be wounded in a moment that is not passing; the harm waits for time to resume.
        if(TemporalEngine.bank(e.getEntity(),e.getAmount(),e.getSource().getEntity())){e.setCanceled(true);return;}
        if(e.getEntity() instanceof ServerPlayer p&&HexData.get(p).getLong("wardUntil")>HexData.now(p))e.setAmount(e.getAmount()*.25f);
        // One ledger serves both deceptions: it tells a projection who has earned a fight, and it tells
        // a creature choosing between identical figures which of them has actually been cutting it.
        Threat.record(e.getEntity(),e.getSource().getEntity(),e.getAmount());
    }
    /**
     * Where the deception actually lives. A creature that decides to hunt a keeper has that decision
     * re-opened across the keeper and every copy of them, and a keeper wearing a borrowed shape is not
     * recognised at all. Nothing else about vanilla or modded targeting is touched.
     */
    @SubscribeEvent public static void target(LivingChangeTargetEvent e) {
        if(!(e.getEntity() instanceof Mob mob)||mob.level().isClientSide)return;
        LivingEntity wanted=e.getNewTarget();
        if(HexServer.charmedAgainst(mob,wanted)){e.setCanceled(true);return;}
        if(wanted==null)return;
        if(wanted instanceof ServerPlayer worn&&Masquerade.deceives(mob,worn)){e.setCanceled(true);return;}
        if(wanted instanceof com.hexgodofstories.entity.IllusionEntity){Decoy.observe(mob,wanted);return;}
        LivingEntity chosen=Decoy.resolve(mob,wanted);
        if(chosen!=null&&chosen!=wanted)e.setNewTarget(chosen);
    }
    @SubscribeEvent public static void fall(LivingFallEvent e) {if(e.getEntity() instanceof ServerPlayer p) {
        if(p.getAbilities().flying&&HexData.get(p).getBoolean("ascended")||HexData.get(p).getLong("flightLandingGrace")>HexData.now(p)){e.setCanceled(true);return;}
        if(HexData.access(p)&&HexData.mastery(p,Discipline.SORCERY)>0)e.setDistance(Math.max(0,e.getDistance()-3));
    }}
    @SubscribeEvent public static void breakBlock(net.minecraftforge.event.level.BlockEvent.BreakEvent e) {if(TemporalEngine.frozen(e.getPlayer()))e.setCanceled(true);}
    @SubscribeEvent public static void stopping(ServerStoppingEvent e) {
        for(ServerPlayer p:e.getServer().getPlayerList().getPlayers())HexServer.clear(p,false);
        // Put the world back while its chunks are still loaded. The record is saved either way, but a world
        // that is never opened again should not be left with a black tunnel through it.
        for(ServerLevel level:e.getServer().getAllLevels())Nothingness.restoreAll(level);
        HexServer.reset();
    }
}
