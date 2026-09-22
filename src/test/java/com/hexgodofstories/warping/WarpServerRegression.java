package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.server.ServerEvents;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.UUID;

/** Loaded only by runServer -PwarpingSmoke, against actual Forge registries and event handlers. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class WarpServerRegression {
    private static com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity pilgrim;
    private static net.minecraft.world.entity.animal.Pig prey;
    private static net.minecraft.world.entity.animal.Pig sunResident;
    private static net.minecraft.world.phys.Vec3 initial;
    private static int seaTicks,sunTicks;
    private static int lastPilgrimTicks,stalledWhilePreyAlive;
    private static boolean enoughSeen;
    private static float greatestPitch;

    @SubscribeEvent public static void started(ServerStartedEvent event){
        ServerLevel sun=event.getServer().getLevel(Destination.SUN.key);
        check(sun!=null,"Sun dimension loaded");
        FakePlayer caster=caster(sun);
        LivingAttackEvent heat=new LivingAttackEvent(caster,sun.damageSources().onFire(),8);
        ServerEvents.ward(heat);check(!heat.isCanceled(),"Sun must not exempt its caster from environmental damage");
        // The command's actual damage path must still kill a Warping user in every destination.
        for(Destination destination:Destination.values()){
            ServerLevel level=event.getServer().getLevel(destination.key);check(level!=null,"dimension loaded: "+destination);
            FakePlayer target=caster(level);DamageSource kill=level.damageSources().genericKill();
            LivingAttackEvent attack=new LivingAttackEvent(target,kill,Float.MAX_VALUE);ServerEvents.ward(attack);
            LivingHurtEvent hurt=new LivingHurtEvent(target,kill,Float.MAX_VALUE);ServerEvents.hurt(hurt);
            check(!attack.isCanceled()&&!hurt.isCanceled(),"kill bypasses both mod damage gates in "+destination);
            target.hurt(kill,Float.MAX_VALUE);check(target.getHealth()<=0,"kill removes health in "+destination);
        }
        // Fire resistance and fire-immune species must not negate stellar heat.
        for(var type:new EntityType<?>[]{EntityType.PIG,EntityType.BLAZE}){
            var entity=type.create(sun);check(entity instanceof net.minecraft.world.entity.LivingEntity,"living heat subject");
            var victim=(net.minecraft.world.entity.LivingEntity)entity;
            victim.moveTo(0,WarpMath.SUN_Y+WarpMath.SUN_CORONA-1,0);
            victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,200,2));
            float before=victim.getHealth();WarpRealms.solarExposure(sun,victim,0,10);
            check(victim.getHealth()<before,"stellar heat damages fire-resistant "+type);
            victim.invulnerableTime=0;victim.moveTo(0,WarpMath.SUN_Y,0);WarpRealms.solarExposure(sun,victim,0,20);
            check(victim.getHealth()<=0,"solar core kills "+type);
            victim.discard();
        }
        FakePlayer creative=caster(sun);creative.setGameMode(GameType.CREATIVE);creative.moveTo(0,WarpMath.SUN_Y,0);
        float health=creative.getHealth();WarpRealms.solarExposure(sun,creative,0,10);
        check(creative.getHealth()==health,"creative mode still ignores ordinary solar exposure");

        // No connected player is required to keep a Warping victim alive and simulated. This pig is
        // left in the Sun and the normal realm tick, not a direct test call, must kill it.
        sunResident=EntityType.PIG.create(sun);check(sunResident!=null,"sun resident created");
        sunResident.moveTo(0,WarpMath.SUN_Y,0);
        check(sun.addFreshEntity(sunResident),"sun resident added");
        WarpResidency.track(sunResident,UUID.randomUUID());
        check(WarpResidency.active(sun),"Sun becomes active for an unattended resident");
        radialRealms(event);
        ServerLevel sea=event.getServer().getLevel(Destination.VOID_SEA.key);
        FakePlayer visitor=caster(sea);
        visitor.getAbilities().mayfly=true;visitor.getAbilities().flying=true;
        HexData.get(visitor).putBoolean("flightGranted",true);
        com.hexgodofstories.server.CosmicFlight.tick(visitor);
        check(!visitor.getAbilities().mayfly&&!visitor.getAbilities().flying,"sea membership revokes old automatic flight");
        pilgrim=com.hexgodofstories.warping.leviathan.PilgrimWarden.ensure(sea);
        check(pilgrim!=null&&sea.getEntity(pilgrim.getUUID())==pilgrim,"Pilgrim really added to sea entity manager");
        for(int i=0;i<8;i++)check(com.hexgodofstories.warping.leviathan.PilgrimWarden.ensure(sea)==pilgrim,"repeated ensure keeps one UUID");
        initial=pilgrim.position();
        prey=EntityType.PIG.create(sea);check(prey!=null,"prey created");
        prey.setNoAi(true);prey.setNoGravity(true);prey.setInvulnerable(true);
        prey.moveTo(initial.x+24,VoidSea.SURFACE+4,initial.z);
        check(sea.addFreshEntity(prey),"imported prey remains in sea");
        // This is the important regression: do NOT manually hold the prey or Hexor chunk. The same
        // residency contract real Warping uses has to keep both sides of the hunt simulation-active.
        WarpResidency.track(prey,UUID.randomUUID());
        check(WarpResidency.active(sea),"Void Sea becomes active for unattended prey");
        lastPilgrimTicks=pilgrim.tickCount;
        var duplicate=HexGodOfStories.PILGRIM.get().create(sea);
        duplicate.moveTo(initial.x+5,initial.y,initial.z,0,0);sea.addFreshEntity(duplicate);
        System.out.println("WARPING_SERVER_REGRESSIONS_PASSED");
    }
    @SubscribeEvent public static void seaTick(net.minecraftforge.event.TickEvent.LevelTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END||pilgrim==null||seaTicks>=1000
                ||event.level!=pilgrim.level())return;
        seaTicks++;

        // This is the regression the old smoke test missed. It only required "more than 80" Hexor
        // ticks in nine seconds, so a creature repeatedly freezing every time it crossed into the
        // next unticketed chunk still passed. While prey exists there must be no meaningful gaps:
        // player presence is not allowed to be what keeps Hexor's own entity tick alive.
        if(seaTicks>20&&prey.isAlive()&&pilgrim.tickCount<=lastPilgrimTicks)stalledWhilePreyAlive++;
        lastPilgrimTicks=pilgrim.tickCount;

        prey.setAirSupply(300);
        if(seaTicks==20)prey.moveTo(initial.x+24,VoidSea.SURFACE-3,initial.z);
        greatestPitch=Math.max(greatestPitch,Math.abs(pilgrim.getXRot()));

        if(seaTicks==180){
            ServerLevel sea=(ServerLevel)event.level;
            check(pilgrim.tickCount>150,"Pilgrim continuously ticks with no connected players");
            check(pilgrim.position().distanceTo(initial)>10,"Pilgrim actually swims");
            check(greatestPitch>5,"3D steering retains vertical pitch");
            check(pilgrim.ai().hunt().target()==prey,"Pilgrim detects imported water prey");
            check(sea.getEntity(prey.getUUID())==prey,"residency keeps prey entity-ticking with no player in the sea");
            check(WarpResidency.active(sea),"Void Sea stays active while prey remains");
            int count=0;for(var entity:sea.getAllEntities())if(entity instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)count++;
            check(count==1,"one loaded Pilgrim after duplicate recovery, found "+count);
        }

        // Enough Is Enough is fed by the realm sweep, not by a watching player. Keep the prey
        // invulnerable through the full patience clock so we can prove the passive really expires
        // off-screen, then remove that test shield and require the decided hunt to finish the kill.
        if(seaTicks==650){
            check(com.hexgodofstories.warping.leviathan.EnoughIsEnough.marked(prey.getUUID()),
                "Enough Is Enough expires for unattended prey");
            enoughSeen=true;
            prey.setInvulnerable(false);
        }

        if(seaTicks==1000){
            check(enoughSeen,"Enough Is Enough was observed before the kill phase");
            check(!prey.isAlive()||prey.getHealth()<=0,
                "Hexor kills unattended prey after Enough Is Enough instead of freezing until a player arrives");
            check(stalledWhilePreyAlive<=2,
                "Hexor keeps entity-ticking across chunk borders while prey exists; stalled ticks="+stalledWhilePreyAlive);
            System.out.println("PILGRIM_SERVER_REGRESSIONS_PASSED ticks="+pilgrim.tickCount
                +" pitch="+greatestPitch+" stalls="+stalledWhilePreyAlive);
        }
    }
    @SubscribeEvent public static void sunTick(net.minecraftforge.event.TickEvent.LevelTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END||sunResident==null
                ||event.level.dimension()!=Destination.SUN.key||sunTicks>=80)return;
        sunTicks++;
        if(sunTicks==40)
            check(!sunResident.isAlive()||sunResident.getHealth()<=0,"unattended Sun keeps ticking and kills its resident");
        if(sunTicks==80)
            check(!WarpResidency.active((ServerLevel)event.level),"Sun goes dormant after its last resident is gone");
    }

    private static void radialRealms(ServerStartedEvent event) {
        ServerLevel moon=event.getServer().getLevel(Destination.CRUSHING_REALM.key);
        var subject=EntityType.PIG.create(moon);check(subject!=null,"moon subject exists");
        subject.setNoAi(true);
        // Exercise the injected travel method and actual Entity.move bounding boxes at both poles
        // and the equator. A cosmetic-only rotation cannot pass this check.
        for(var normal:new net.minecraft.world.phys.Vec3[]{new net.minecraft.world.phys.Vec3(0,1,0),
                new net.minecraft.world.phys.Vec3(1,0,0),new net.minecraft.world.phys.Vec3(0,-1,0)}) {
            var at=MoonGravity.CENTER.add(normal.scale(MoonGravity.radius(normal)+.025));
            subject.setPos(at.x,at.y,at.z);subject.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            for(int i=0;i<12;i++)subject.travel(net.minecraft.world.phys.Vec3.ZERO);
            check(subject.position().distanceTo(at)<.1,"resting surface stable at "+normal);
            check(subject.getEyePosition().subtract(subject.position()).dot(normal)>0,"eye stays outside the moon at "+normal);
            MoonGravity.jump(subject);
            check(subject.getDeltaMovement().dot(normal)>.3,"jump points away from the core at "+normal);
            for(int i=0;i<8;i++)subject.travel(net.minecraft.world.phys.Vec3.ZERO);
            check(MoonGravity.grounded(subject),"heavy gravity returns jump to surface at "+normal);
        }
        // Follow a complete great-circle circuit through the south pole and back to the north.
        subject.setPos(0,CosmicPhysics.MOON_Y+CosmicPhysics.MOON_RADIUS+.025,0);
        subject.setYRot(0);subject.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        boolean underside=false;
        for(int i=0;i<3000;i++){
            subject.travel(new net.minecraft.world.phys.Vec3(0,0,1));
            underside|=subject.getY()<CosmicPhysics.MOON_Y-40;
            var n=MoonGravity.up(subject.position());
            check(Math.abs(subject.position().distanceTo(MoonGravity.CENTER)-MoonGravity.radius(n))<.15,"walking follows crater height");
        }
        check(underside,"walking can cross the underside");subject.discard();
        ServerLevel well=event.getServer().getLevel(Destination.GRAVITY_WELL.key);
        var pos=new net.minecraft.core.BlockPos(32,96,0);
        check(!well.setBlock(pos,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3),"well refuses new terrain");
        check(well.getBlockState(pos).isAir(),"well remains block-free");
        check(RealmLayout.blocks(Destination.GRAVITY_WELL).isEmpty(),"well layout has no blocks");
        check(RealmLayout.blocks(Destination.CRUSHING_REALM).isEmpty(),"old crushing floor removed from layout");
        System.out.println("RADIAL_REALMS_SERVER_REGRESSIONS_PASSED");
    }
    private static FakePlayer caster(ServerLevel level){
        // Forge's normal fake player is always invulnerable; exercise ServerPlayer damage instead.
        FakePlayer p=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"WarpCheck")){
            @Override public boolean isInvulnerableTo(DamageSource source){return false;}
        };
        HexData.access(p,true);HexData.get(p).putBoolean("unlock_WARPING",true);
        p.getAbilities().invulnerable=false;return p;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
