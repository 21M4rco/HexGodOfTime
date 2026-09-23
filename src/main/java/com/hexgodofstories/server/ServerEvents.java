package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.entity.ConjuredWeapon;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageTypes;
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
    @SubscribeEvent public static void abyssNaturalSpawn(MobSpawnEvent.PositionCheck event) {
        if (com.hexgodofstories.warping.Destination.from(event.getEntity().level())
                != com.hexgodofstories.warping.Destination.VOID_SEA) return;
        var reason = event.getSpawnType();
        if (reason == net.minecraft.world.entity.MobSpawnType.NATURAL
                || reason == net.minecraft.world.entity.MobSpawnType.CHUNK_GENERATION
                || reason == net.minecraft.world.entity.MobSpawnType.PATROL) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }

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
    @SubscribeEvent public static void player(TickEvent.PlayerTickEvent e) {if(e.phase==TickEvent.Phase.END&&e.player instanceof ServerPlayer p){com.hexgodofstories.warping.CandyCorruption.tick(p);HexServer.tick(p);}}
    @SubscribeEvent public static void level(TickEvent.LevelTickEvent e) {
        if(!(e.level instanceof ServerLevel s))return;
        if(e.phase==TickEvent.Phase.START){
            // Install/refresh destination residency tickets before any realm AI or environmental
            // rule runs. That way a realm never has to already be simulating its victims in order
            // to discover that it should stay simulated.
            com.hexgodofstories.warping.WarpResidency.tick(s);
            TemporalEngine.tick(s);HexServer.tickLevel(s);
        }
        // Turning is eased after the creatures have turned, which is the only point it can be done.
        else {
            TemporalEngine.afterTick(s);
            if (com.hexgodofstories.warping.Destination.from(s)
                    == com.hexgodofstories.warping.Destination.VOID_SEA)
                com.hexgodofstories.warping.leviathan.PilgrimWarden.catchUp(s);
        }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        com.hexgodofstories.warping.CandyCorruption.sync(p,-1);
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
        if (e.getTarget() instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity pilgrim)
            HexNetwork.to(p, new HexNetwork.Message(HexNetwork.PILGRIM_PATH, pilgrim.getId(), pilgrim.segments().snapshot()));
        TemporalEngine.track(p,e.getTarget());
        Frostbite.track(p,e.getTarget());
        Erasure.track(p,e.getTarget());
        if(!(e.getTarget() instanceof ServerPlayer q))return;
        HexNetwork.syncTo(p,q);
        com.hexgodofstories.warping.CandyCorruption.syncTo(p,q);
        // A borrowed shape is sent once, not every second, so a new viewer has to be told separately.
        Masquerade.resend(p,q);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {if(e.getEntity() instanceof ServerPlayer p){PersonalRewind.clear(p);HexServer.clear(p,false);}}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {if(e.getEntity() instanceof ServerPlayer p){HexServer.clear(p,false);HexNetwork.sync(p);com.hexgodofstories.warping.WarpRealms.greet(p,e.getTo());}}
    @SubscribeEvent public static void leaving(net.minecraftforge.event.entity.EntityLeaveLevelEvent e){
        if(e.getLevel() instanceof ServerLevel level){
            Frostbite.clear(e.getEntity());
            com.hexgodofstories.warping.WarpEmergence.cancel(e.getEntity());
            var reason=e.getEntity().getRemovalReason();
            // Unloaded chunks still own their saved residents. Confirmed removal or transfer does
            // not need the ten-second missing-entity grace used during asynchronous chunk loads.
            if(com.hexgodofstories.warping.Destination.from(level)!=null&&reason!=null
                    &&reason!=net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)
                com.hexgodofstories.warping.WarpResidency.untrack(level,e.getEntity().getUUID());
        }
    }
    @SubscribeEvent public static void travelling(net.minecraftforge.event.entity.EntityTravelToDimensionEvent e){
        if(!e.getEntity().level().isClientSide)com.hexgodofstories.warping.WarpEmergence.cancel(e.getEntity());
    }
    @SubscribeEvent public static void death(LivingDeathEvent e) {
        // A kill feeds the Pilgrim's patience back, which is what makes it willing to play again.
        if(e.getSource().getEntity() instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity pilgrim&&pilgrim.ai()!=null)pilgrim.ai().noteKill();
        // Whatever it had decided about is dead. Anything that comes back gets its thirty seconds.
        com.hexgodofstories.warping.leviathan.EnoughIsEnough.forget(e.getEntity().getUUID());
        com.hexgodofstories.warping.WarpEmergence.cancel(e.getEntity());
        Erasure.forget(e.getEntity());
        Bleed.clear(e.getEntity());
        Frostbite.clear(e.getEntity());
        Threat.forget(e.getEntity());
        Decoy.release(e.getEntity());
        Starfall.forget(e.getEntity());
        SanctumWard.forget(e.getEntity());
        if(e.getEntity() instanceof ServerPlayer p){PersonalRewind.clear(p);com.hexgodofstories.warping.CandyCorruption.reset(p);HexServer.clear(p,true);}
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone e) {e.getEntity().getPersistentData().put(HexData.TAG,HexData.get(e.getOriginal()).copy());HexData.clearTransient(e.getEntity(),e.isWasDeath());}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e) {if(e.getEntity() instanceof ServerPlayer p){HexNetwork.sync(p);com.hexgodofstories.warping.CandyCorruption.sync(p,-1);}}
    /**
     * The sanctum's owner cannot be struck inside it. Refusing the attack here, before any damage is
     * worked out, is what makes it a dodge rather than a cancelled hit: by the time anything could
     * have landed, they are standing somewhere else.
     */
    @SubscribeEvent public static void ward(net.minecraftforge.event.entity.living.LivingAttackEvent e) {
        // Only something attacking them. A fall, the void or drowning is the island's business and
        // the owner's own problem; blinking away from gravity would be nonsense.
        // Administrative death must bypass every mod ward, including erasure and stopped time.
        if(e.getSource().is(DamageTypes.GENERIC_KILL))return;
        var realm=com.hexgodofstories.warping.Destination.from(e.getEntity().level());
        if(realm!=null&&realm!=com.hexgodofstories.warping.Destination.SUN&&com.hexgodofstories.warping.Warping.sovereign(e.getEntity())&&e.getSource().getEntity()==null){e.setCanceled(true);return;}
        if(Erasure.erasing(e.getEntity())){e.setCanceled(true);return;}
        if(e.getSource().getEntity()==null&&e.getSource().getDirectEntity()==null)return;
        if(SanctumWard.evade(e.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent public static void wardProjectile(net.minecraftforge.event.entity.ProjectileImpactEvent e) {
        if(!(e.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit))return;
        if(SanctumWard.evade(hit.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent public static void attack(AttackEntityEvent e) {
        if(com.hexgodofstories.warping.CandyCorruption.handMissing(e.getEntity(),net.minecraft.world.InteractionHand.MAIN_HAND)){e.setCanceled(true);return;}
        if(TemporalEngine.frozen(e.getEntity())){e.setCanceled(true);return;}
        // Nothing swings while it is being erased, and nothing swings while it is holding the torrent.
        if(Erasure.erasing(e.getEntity())||TimeBranch.planted(e.getEntity())){e.setCanceled(true);return;}
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        if(e.getTarget() instanceof LivingEntity victim)HexServer.engaged(p,victim);
        if(p.getMainHandItem().getItem() instanceof ConjuredWeapon weapon&&weapon.kind!=1){e.setCanceled(true);HexServer.weapon(p,false);}
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent e) {
        if(e.getLevel().isClientSide||!e.isCancelable())return;
        if(com.hexgodofstories.warping.CandyCorruption.handMissing(e.getEntity(),e.getHand())){e.setCanceled(true);return;}
        if(TemporalEngine.frozen(e.getEntity())||Erasure.erasing(e.getEntity())||TimeBranch.planted(e.getEntity()))e.setCanceled(true);
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void branchPunch(LivingDamageEvent e) {BranchFist.damage(e);}

    /** A real vanilla sword hit opens the dagger wound; a direct blow cracks the ice on any mob. */
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void swordDamage(LivingDamageEvent e) {
        if(e.getAmount()<=0)return;
        LivingEntity victim=e.getEntity();
        if(e.getSource().getDirectEntity()!=null)e.setAmount(e.getAmount()+Frostbite.shatter(victim,e.getSource().getDirectEntity()));
        if(e.getSource().getDirectEntity() instanceof ServerPlayer player
            &&e.getSource().is(DamageTypes.PLAYER_ATTACK)
            &&player.getMainHandItem().getItem() instanceof ConjuredWeapon sword&&sword.kind==1)
            Bleed.apply(player,victim,1,160);
    }

    @SubscribeEvent public static void hurt(LivingHurtEvent e) {
        if(e.getSource().is(DamageTypes.GENERIC_KILL))return;
        if(Erasure.erasing(e.getSource().getEntity())){e.setCanceled(true);return;}
        // A body being taken out of the timeline cannot be hurt out of it. Without this, anything else
        // landing a hit mid-sequence would flash it red or kill it outright, and the slow coming-apart the
        // ability exists for would be replaced by an ordinary death.
        if(Erasure.erasing(e.getEntity())){e.setCanceled(true);return;}
        if(e.getSource().getEntity()!=null&&TemporalEngine.frozen(e.getSource().getEntity())){e.setCanceled(true);return;}
        // A suspended body cannot be wounded in a moment that is not passing; the harm waits for time to resume.
        if(TemporalEngine.bank(e.getEntity(),e.getAmount(),e.getSource().getEntity())){e.setCanceled(true);return;}
        if(e.getEntity() instanceof ServerPlayer p&&HexData.get(p).getLong("wardUntil")>HexData.now(p))e.setAmount(e.getAmount()*.25f);
        // Being hit is the only thing that takes a fracture away from the caster holding it open.
        // Turning the camera away deliberately does not; concentration is not aim.
        if(e.getEntity() instanceof ServerPlayer struck&&e.getAmount()>0)com.hexgodofstories.warping.Warping.interrupt(struck);
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
    @SubscribeEvent public static void breakBlock(net.minecraftforge.event.level.BlockEvent.BreakEvent e) {
        if(com.hexgodofstories.warping.CandyCorruption.handMissing(e.getPlayer(),net.minecraft.world.InteractionHand.MAIN_HAND)||TemporalEngine.frozen(e.getPlayer()))e.setCanceled(true);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent e) {
        for(ServerPlayer p:e.getServer().getPlayerList().getPlayers())HexServer.clear(p,false);
        // Put the world back while its chunks are still loaded. The record is saved either way, but a world
        // that is never opened again should not be left with a black tunnel through it.
        for(ServerLevel level:e.getServer().getAllLevels())Nothingness.restoreAll(level);
        HexServer.reset();
    }
}
