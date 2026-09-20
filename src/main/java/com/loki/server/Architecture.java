package com.loki.server;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Borrowed Reality. The caster holds the cast key and a structure grows where they aim; releasing commits it.
 * Nothing is ever placed in the world: viewers are handed a compact description and draw the geometry themselves,
 * so the projection has no collision, costs the server nothing and can be shown to chosen eyes only.
 */
public final class Architecture {
    public static final int DESIGNS=4,MAX_SCALE=4,GROW_TICKS=22;
    private static final class Cast {
        long started;int design;Vec3 anchor;float yaw;int seed;long lastPreview;
        Cast(long started,int design,int seed) {this.started=started;this.design=design;this.seed=seed;}
    }
    private static final Map<UUID,Cast> CASTING=new HashMap<>();
    private static final Map<UUID,Integer> CHOICE=new HashMap<>();

    public static boolean casting(ServerPlayer p) {return CASTING.containsKey(p.getUUID());}
    public static int design(ServerPlayer p) {return CHOICE.getOrDefault(p.getUUID(),0);}

    public static void cycle(ServerPlayer p) {
        int next=(design(p)+1)%DESIGNS;
        CHOICE.put(p.getUUID(),next);
        p.displayClientMessage(Component.literal("Design: "+name(next)),true);
    }
    public static String name(int design) {
        return switch(Math.floorMod(design,DESIGNS)){case 0->"Rampart";case 1->"Gatehouse";case 2->"Great hall";default->"Ruin";};
    }

    public static void begin(ServerPlayer p) {
        if(CASTING.containsKey(p.getUUID()))return;
        Cast cast=new Cast(LokiData.now(p),design(p),p.getRandom().nextInt(1<<16));
        cast.anchor=aim(p);cast.yaw=p.getYRot();
        if(cast.anchor==null)return;
        CASTING.put(p.getUUID(),cast);
        LokiNetwork.animate(p,"illusion");
        p.level().playSound(null,p.blockPosition(),Loki.ILLUSION_SOUND.get(),SoundSource.PLAYERS,.6f,.8f);
    }

    public static void tick(ServerPlayer p) {
        Cast cast=CASTING.get(p.getUUID());
        if(cast==null)return;
        long now=LokiData.now(p);
        if(TemporalEngine.frozen(p)||!p.isAlive()){CASTING.remove(p.getUUID());return;}
        if(now-cast.started>GROW_TICKS*MAX_SCALE+60){commit(p);return;}
        Vec3 target=aim(p);
        if(target!=null){cast.anchor=target;cast.yaw=p.getYRot();}
        if(now-cast.lastPreview<3)return;
        cast.lastPreview=now;
        LokiNetwork.to(p,new LokiNetwork.Message(LokiNetwork.ARCHITECTURE,p.getId(),describe(cast,scale(cast,now),now+8,true)));
    }

    public static void commit(ServerPlayer p) {
        Cast cast=CASTING.remove(p.getUUID());
        if(cast==null)return;
        long now=LokiData.now(p);
        int scale=scale(cast,now);
        int duration=260+LokiData.mastery(p,Discipline.MISCHIEF);
        CompoundTag payload=describe(cast,scale,now+duration,false);
        Entity aimed=LokiServer.target(p,26);
        if(aimed instanceof ServerPlayer only) {
            LokiNetwork.to(only,new LokiNetwork.Message(LokiNetwork.ARCHITECTURE,p.getId(),payload));
            LokiNetwork.to(p,new LokiNetwork.Message(LokiNetwork.ARCHITECTURE,p.getId(),payload));
            p.displayClientMessage(Component.literal("Only "+only.getGameProfile().getName()+" will see it."),true);
        } else {
            for(ServerPlayer viewer:p.serverLevel().players())
                if(viewer.distanceToSqr(cast.anchor)<6400)LokiNetwork.to(viewer,new LokiNetwork.Message(LokiNetwork.ARCHITECTURE,p.getId(),payload));
        }
        p.level().playSound(null,net.minecraft.core.BlockPos.containing(cast.anchor),Loki.ILLUSION_SOUND.get(),SoundSource.PLAYERS,1.1f,.72f);
        LokiServer.reward(p,Discipline.MISCHIEF,110);
        LokiData.get(p).putLong("cd_"+Ability.ARCHITECTURE.name(),now+Ability.ARCHITECTURE.cooldown);
        LokiNetwork.animate(p,"__clear__");
        LokiNetwork.sync(p);
    }

    private static int scale(Cast cast,long now) {return (int)Math.max(1,Math.min(MAX_SCALE,1+(now-cast.started)/GROW_TICKS));}

    private static CompoundTag describe(Cast cast,int scale,long until,boolean preview) {
        CompoundTag n=new CompoundTag();
        n.putDouble("x",Math.floor(cast.anchor.x));n.putDouble("y",Math.floor(cast.anchor.y));n.putDouble("z",Math.floor(cast.anchor.z));
        n.putInt("design",cast.design);n.putInt("scale",scale);n.putInt("seed",cast.seed);
        n.putFloat("yaw",cast.yaw);n.putLong("until",until);n.putBoolean("preview",preview);
        return n;
    }

    /** Snaps to the ground the caster is looking at so a projected building sits on the terrain. */
    private static Vec3 aim(ServerPlayer p) {
        Vec3 from=p.getEyePosition();
        BlockHitResult hit=p.level().clip(new ClipContext(from,from.add(p.getLookAngle().scale(26)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if(hit.getType()==HitResult.Type.MISS)return null;
        Vec3 point=hit.getLocation();
        return new Vec3(point.x,hit.getDirection()==net.minecraft.core.Direction.UP?point.y:Math.floor(point.y),point.z);
    }

    public static void forget(ServerPlayer p) {CASTING.remove(p.getUUID());}
    public static void reset() {CASTING.clear();CHOICE.clear();}
}
