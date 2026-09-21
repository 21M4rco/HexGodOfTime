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
        System.out.println("WARPING_SERVER_REGRESSIONS_PASSED");
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
