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
    private static net.minecraft.world.phys.Vec3 initial;
    private static int seaTicks;
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
        var chunk=new net.minecraft.world.level.ChunkPos(prey.blockPosition());
        com.hexgodofstories.warping.leviathan.PilgrimWarden.hold(sea,chunk);sea.getChunk(chunk.x,chunk.z);
        check(sea.addFreshEntity(prey),"imported prey remains in sea");
        var duplicate=HexGodOfStories.PILGRIM.get().create(sea);
        duplicate.moveTo(initial.x+5,initial.y,initial.z,0,0);sea.addFreshEntity(duplicate);
        System.out.println("WARPING_SERVER_REGRESSIONS_PASSED");
    }
    @SubscribeEvent public static void seaTick(net.minecraftforge.event.TickEvent.LevelTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END||pilgrim==null||seaTicks>=180
                ||event.level!=pilgrim.level())return;
        seaTicks++;
        prey.setAirSupply(300);
        if(seaTicks==20)prey.moveTo(initial.x+24,VoidSea.SURFACE-3,initial.z);
        greatestPitch=Math.max(greatestPitch,Math.abs(pilgrim.getXRot()));
        if(seaTicks==180){
            ServerLevel sea=(ServerLevel)event.level;
            check(pilgrim.tickCount>80,"Pilgrim ticks with no connected players");
            check(pilgrim.position().distanceTo(initial)>10,"Pilgrim actually swims");
            check(greatestPitch>5,"3D steering retains vertical pitch");
            check(pilgrim.ai().hunt().target()==prey,"Pilgrim detects imported water prey");
            int count=0;for(var entity:sea.getAllEntities())if(entity instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity)count++;
            check(count==1,"one loaded Pilgrim after duplicate recovery, found "+count);
            System.out.println("PILGRIM_SERVER_REGRESSIONS_PASSED ticks="+pilgrim.tickCount+" pitch="+greatestPitch);
        }
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
