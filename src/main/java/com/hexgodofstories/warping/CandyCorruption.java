package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Paradise sugar eventually turns limbs into brittle bubblegum. Death clears the condition. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class CandyCorruption {
    private CandyCorruption(){}

    public static final int RIGHT_ARM=0,LEFT_ARM=1,RIGHT_LEG=2,LEFT_LEG=3;
    public static final int DOSES_PER_LIMB=5,BREAK_TICKS=36;
    public static final String DOSES="candyDoses",MASK="candyLimbs",BREAKING="candyBreaking",BREAK_START="candyBreakStart";
    private static final UUID LEG_SLOW_UUID=UUID.fromString("e4c0303d-3c50-4c8c-9534-1f8d4a40611e");

    public static void consume(Player player,int doses){
        if(!(player instanceof ServerPlayer p)||doses<=0)return;
        CompoundTag d=HexData.get(p);
        d.putInt(DOSES,Math.min(999,d.getInt(DOSES)+doses));
        if(!d.contains(BREAKING))startNext(p);
    }

    private static void startNext(ServerPlayer p){
        CompoundTag d=HexData.get(p);
        if(d.getInt(DOSES)<DOSES_PER_LIMB||Integer.bitCount(mask(p)&15)>=4)return;
        List<Integer> choices=new ArrayList<>(3);
        // The right arm is deliberately the last limb Paradise may take. It is the player's final
        // feeding/interaction arm, so they can keep consuming candy until every other limb is gone.
        for(int part:new int[]{LEFT_ARM,RIGHT_LEG,LEFT_LEG})if(!missing(p,part))choices.add(part);
        int part;
        if(!choices.isEmpty())part=choices.get(p.getRandom().nextInt(choices.size()));
        else if(!missing(p,RIGHT_ARM))part=RIGHT_ARM;
        else return;
        d.putInt(DOSES,d.getInt(DOSES)-DOSES_PER_LIMB);
        d.putInt(BREAKING,part);
        d.putLong(BREAK_START,p.level().getGameTime());
        sync(p,-1);
    }

    public static void tick(ServerPlayer p){
        mobility(p);
        CompoundTag d=HexData.get(p);
        if(!d.contains(BREAKING)){if(d.getInt(DOSES)>=DOSES_PER_LIMB)startNext(p);return;}
        if(p.level().getGameTime()-d.getLong(BREAK_START)<BREAK_TICKS)return;
        int part=d.getInt(BREAKING);
        d.putInt(MASK,mask(p)|(1<<part));
        d.remove(BREAKING);d.remove(BREAK_START);
        mobility(p);
        breakEffect(p,part);
        sync(p,part);
        if(d.getInt(DOSES)>=DOSES_PER_LIMB)startNext(p);
    }

    private static void breakEffect(ServerPlayer p,int part){
        Vec3 at=partPoint(p,part);
        p.serverLevel().sendParticles(HexGodOfStories.CANDY.get(),at.x,at.y,at.z,18,.18,.24,.18,.055);
        p.level().playSound(null,p.blockPosition(),SoundEvents.SLIME_BLOCK_BREAK,SoundSource.PLAYERS,.85f,.82f+p.getRandom().nextFloat()*.18f);
    }

    private static Vec3 partPoint(Player p,int part){
        double yaw=Math.toRadians(p.getYRot());
        Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));
        boolean rightSide=part==RIGHT_ARM||part==RIGHT_LEG;
        double side=(part<=LEFT_ARM?.34:.14)*(rightSide?1:-1);
        double y=part<=LEFT_ARM?1.30:.56;
        return p.position().add(right.scale(side)).add(0,y,0);
    }

    private static void mobility(ServerPlayer p){
        AttributeInstance speed=p.getAttribute(Attributes.MOVEMENT_SPEED);
        if(speed==null)return;
        int legs=lostLegs(p);
        double amount=legs==0?0:legs==1?-.72:-1.0;
        AttributeModifier old=speed.getModifier(LEG_SLOW_UUID);
        if(amount==0){if(old!=null)speed.removeModifier(LEG_SLOW_UUID);return;}
        if(old!=null&&Math.abs(old.getAmount()-amount)<1.0E-6)return;
        if(old!=null)speed.removeModifier(LEG_SLOW_UUID);
        speed.addTransientModifier(new AttributeModifier(LEG_SLOW_UUID,"Paradise candy limb loss",amount,AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    @SubscribeEvent public static void drank(LivingEntityUseItemEvent.Finish e){
        if(e.getEntity() instanceof ServerPlayer p&&ParadiseWaters.isParadiseWaters(e.getItem()))consume(p,1);
    }

    @SubscribeEvent public static void jump(LivingEvent.LivingJumpEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p)||!noLegs(p))return;
        Vec3 v=p.getDeltaMovement();
        p.setDeltaMovement(v.x,Math.min(0,v.y),v.z);
        p.setOnGround(true);p.hasImpulse=true;
    }

    public static int mask(Player p){return HexData.get(p).getInt(MASK)&15;}
    public static boolean missing(Player p,int part){return (mask(p)&1<<part)!=0;}
    public static int breaking(Player p){return HexData.get(p).contains(BREAKING)?HexData.get(p).getInt(BREAKING):-1;}
    public static int lostLegs(Player p){return (missing(p,RIGHT_LEG)?1:0)+(missing(p,LEFT_LEG)?1:0);}
    public static boolean noLegs(Player p){return lostLegs(p)==2;}
    public static boolean noArms(Player p){return missing(p,RIGHT_ARM)&&missing(p,LEFT_ARM);}

    public static boolean handMissing(Player p,InteractionHand hand){
        if(hand==null)return noArms(p);
        HumanoidArm arm=hand==InteractionHand.MAIN_HAND?p.getMainArm():p.getMainArm().getOpposite();
        return missing(p,arm==HumanoidArm.RIGHT?RIGHT_ARM:LEFT_ARM);
    }

    public static void reset(ServerPlayer p){
        CompoundTag d=HexData.get(p);
        d.remove(DOSES);d.remove(MASK);d.remove(BREAKING);d.remove(BREAK_START);
        AttributeInstance speed=p.getAttribute(Attributes.MOVEMENT_SPEED);
        if(speed!=null&&speed.getModifier(LEG_SLOW_UUID)!=null)speed.removeModifier(LEG_SLOW_UUID);
        sync(p,-1);
    }

    public static void sync(ServerPlayer p,int burst){
        CompoundTag n=state(p);if(burst>=0)n.putInt("burst",burst);
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.CANDY_BODY,p.getId(),n));
    }
    public static void syncTo(ServerPlayer viewer,ServerPlayer subject){
        HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.CANDY_BODY,subject.getId(),state(subject)));
    }
    private static CompoundTag state(ServerPlayer p){
        CompoundTag d=HexData.get(p),n=new CompoundTag();
        n.putInt("mask",mask(p));
        n.putInt("breaking",d.contains(BREAKING)?d.getInt(BREAKING):-1);
        n.putLong("start",d.getLong(BREAK_START));
        n.putInt("breakTicks",BREAK_TICKS);
        return n;
    }
}
