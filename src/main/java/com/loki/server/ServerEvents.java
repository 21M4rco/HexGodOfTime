package com.loki.server;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.entity.ConjuredWeapon;
import com.loki.network.LokiNetwork;
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

@Mod.EventBusSubscriber(modid=Loki.ID)
public final class ServerEvents {
    /** Cancel the spawn itself, before pickup, hoppers or another mod can collect a dropped illusion. */
    @SubscribeEvent public static void conjuredDrop(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if(!e.getLevel().isClientSide&&e.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
            &&item.getItem().getItem() instanceof ConjuredWeapon) {e.setCanceled(true);item.discard();}
    }
    @SubscribeEvent public static void conjuredToss(ItemTossEvent e) {
        if(e.getEntity().getItem().getItem() instanceof ConjuredWeapon) {
            e.setCanceled(true);e.getEntity().discard();
            if(e.getPlayer() instanceof ServerPlayer p)LokiNetwork.fx(p,"dispel");
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
    @SubscribeEvent public static void player(TickEvent.PlayerTickEvent e) {if(e.phase==TickEvent.Phase.END&&e.player instanceof ServerPlayer p)LokiServer.tick(p);}
    @SubscribeEvent public static void level(TickEvent.LevelTickEvent e) {
        if(!(e.level instanceof ServerLevel s))return;
        if(e.phase==TickEvent.Phase.START){TemporalEngine.tick(s);LokiServer.tickLevel(s);}
        // Turning is eased after the creatures have turned, which is the only point it can be done.
        else TemporalEngine.afterTick(s);
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {if(e.getEntity() instanceof ServerPlayer p){LokiData.get(p).remove("transformStart");LokiNetwork.sync(p);}}
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking e) {
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        TemporalEngine.track(p,e.getTarget());
        if(!(e.getTarget() instanceof ServerPlayer q))return;
        LokiNetwork.syncTo(p,q);
        // A borrowed shape is sent once, not every second, so a new viewer has to be told separately.
        Masquerade.resend(p,q);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {if(e.getEntity() instanceof ServerPlayer p)LokiServer.clear(p,false);}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {if(e.getEntity() instanceof ServerPlayer p){LokiServer.clear(p,false);LokiNetwork.sync(p);}}
    @SubscribeEvent public static void death(LivingDeathEvent e) {
        Bleed.clear(e.getEntity());
        Threat.forget(e.getEntity());
        Decoy.release(e.getEntity());
        if(e.getEntity() instanceof ServerPlayer p)LokiServer.clear(p,true);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone e) {e.getEntity().getPersistentData().put("Loki",LokiData.get(e.getOriginal()).copy());LokiData.clearTransient(e.getEntity(),e.isWasDeath());}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e) {if(e.getEntity() instanceof ServerPlayer p)LokiNetwork.sync(p);}
    @SubscribeEvent public static void attack(AttackEntityEvent e) {
        if(TemporalEngine.frozen(e.getEntity())){e.setCanceled(true);return;}
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        if(e.getTarget() instanceof LivingEntity victim)LokiServer.engaged(p,victim);
        if(p.getMainHandItem().getItem() instanceof ConjuredWeapon){e.setCanceled(true);LokiServer.weapon(p,false);}
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent e) {if(!e.getLevel().isClientSide&&TemporalEngine.frozen(e.getEntity())&&e.isCancelable())e.setCanceled(true);}
    @SubscribeEvent public static void hurt(LivingHurtEvent e) {
        if(e.getSource().getEntity()!=null&&TemporalEngine.frozen(e.getSource().getEntity())){e.setCanceled(true);return;}
        // A suspended body cannot be wounded in a moment that is not passing; the harm waits for time to resume.
        if(TemporalEngine.bank(e.getEntity(),e.getAmount(),e.getSource().getEntity())){e.setCanceled(true);return;}
        if(e.getEntity() instanceof ServerPlayer p&&LokiData.get(p).getLong("wardUntil")>LokiData.now(p))e.setAmount(e.getAmount()*.25f);
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
        if(LokiServer.charmedAgainst(mob,wanted)){e.setCanceled(true);return;}
        if(wanted==null)return;
        if(wanted instanceof ServerPlayer worn&&Masquerade.deceives(mob,worn)){e.setCanceled(true);return;}
        if(wanted instanceof com.loki.entity.IllusionEntity){Decoy.observe(mob,wanted);return;}
        LivingEntity chosen=Decoy.resolve(mob,wanted);
        if(chosen!=null&&chosen!=wanted)e.setNewTarget(chosen);
    }
    @SubscribeEvent public static void fall(LivingFallEvent e) {if(e.getEntity() instanceof ServerPlayer p) {
        if(p.getAbilities().flying&&LokiData.get(p).getBoolean("ascended")||LokiData.get(p).getLong("flightLandingGrace")>LokiData.now(p)){e.setCanceled(true);return;}
        if(LokiData.mastery(p,Discipline.SORCERY)>0)e.setDistance(Math.max(0,e.getDistance()-3));
    }}
    @SubscribeEvent public static void breakBlock(net.minecraftforge.event.level.BlockEvent.BreakEvent e) {if(TemporalEngine.frozen(e.getPlayer()))e.setCanceled(true);}
    @SubscribeEvent public static void stopping(ServerStoppingEvent e) {for(ServerPlayer p:e.getServer().getPlayerList().getPlayers())LokiServer.clear(p,false);LokiServer.reset();}
}
