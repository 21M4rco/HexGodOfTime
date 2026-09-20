package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Borrowed Reality. The caster holds the cast key and a wall grows where they aim — Small, Medium,
 * Big, Massive — and releasing commits whichever size it reached.
 *
 * <p>No block is placed. Viewers are handed an origin, a size and a seed and build the courses
 * themselves, while {@link IllusoryWalls} keeps the same columns on the server so that everything
 * which is not a player treats the wall as masonry: mobs path around it, lose sight of what stands
 * behind it, and are turned back when they walk into it.
 */
public final class Architecture {
    public static final int MAX_SCALE=IllusoryWall.SIZES,GROW_TICKS=16;
    private static final class Cast {
        final long started;final int seed;
        BlockPos anchor;float yaw;int announced;long lastPreview;
        Cast(long started,int seed) {this.started=started;this.seed=seed;}
    }
    private static final Map<UUID,Cast> CASTING=new HashMap<>();

    public static boolean casting(ServerPlayer p) {return CASTING.containsKey(p.getUUID());}

    /** @return false when there is no ground in view to build on, so nothing is charged for a miss. */
    public static boolean begin(ServerPlayer p) {
        if(CASTING.containsKey(p.getUUID()))return false;
        Cast cast=new Cast(HexData.now(p),p.getRandom().nextInt(1<<16));
        cast.anchor=aim(p);cast.yaw=p.getYRot();
        if(cast.anchor==null){p.displayClientMessage(Component.literal("Look at the ground you would build on."),true);return false;}
        CASTING.put(p.getUUID(),cast);
        HexNetwork.animate(p,"illusion");
        p.level().playSound(null,p.blockPosition(),HexGodOfStories.ILLUSION_SOUND.get(),SoundSource.PLAYERS,.6f,.8f);
        return true;
    }

    public static void tick(ServerPlayer p) {
        Cast cast=CASTING.get(p.getUUID());
        if(cast==null)return;
        long now=HexData.now(p);
        if(TemporalEngine.frozen(p)||!p.isAlive()){CASTING.remove(p.getUUID());return;}
        if(now-cast.started>GROW_TICKS*MAX_SCALE+60){commit(p);return;}
        BlockPos target=aim(p);
        if(target!=null){cast.anchor=target;cast.yaw=p.getYRot();}
        int scale=scale(cast,now);
        if(scale!=cast.announced) {
            cast.announced=scale;
            p.displayClientMessage(Component.literal(IllusoryWall.name(scale)+" wall"+(scale<MAX_SCALE?" — keep holding":"")),true);
        }
        if(now-cast.lastPreview<3)return;
        cast.lastPreview=now;
        HexNetwork.to(p,new HexNetwork.Message(HexNetwork.ARCHITECTURE,p.getId(),describe(cast,scale,now+8,true)));
    }

    public static void commit(ServerPlayer p) {
        Cast cast=CASTING.remove(p.getUUID());
        if(cast==null)return;
        long now=HexData.now(p);
        int scale=scale(cast,now);
        int duration=Math.min(1200,260+HexData.mastery(p,Discipline.MISCHIEF));
        IllusoryWalls.dismiss(p.getUUID());
        if(IllusoryWalls.raise(p,cast.anchor,scale,cast.seed,cast.yaw,now+duration)==null) {
            p.displayClientMessage(Component.literal("There is nothing here to build against."),true);
            HexNetwork.animate(p,"__clear__");
            return;
        }
        broadcast(p,describe(cast,scale,now+duration,false),cast.anchor);
        p.level().playSound(null,cast.anchor,HexGodOfStories.ILLUSION_SOUND.get(),SoundSource.PLAYERS,1.1f,.72f);
        HexServer.reward(p,Discipline.MISCHIEF,110);
        // A bigger lie takes longer to tell again.
        HexData.get(p).putLong("cd_"+Ability.ARCHITECTURE.name(),now+100+scale*40L);
        HexNetwork.animate(p,"__clear__");
        HexNetwork.sync(p);
    }

    /** Secondary action: let the standing wall go, for everyone and for the mobs that believed it. */
    public static boolean dismiss(ServerPlayer p) {
        CASTING.remove(p.getUUID());
        if(!IllusoryWalls.dismiss(p.getUUID()))return false;
        CompoundTag payload=new CompoundTag();
        payload.putBoolean("remove",true);
        broadcast(p,payload,p.blockPosition());
        p.level().playSound(null,p.blockPosition(),HexGodOfStories.ILLUSION_SOUND.get(),SoundSource.PLAYERS,.7f,1.18f);
        return true;
    }

    private static void broadcast(ServerPlayer p,CompoundTag payload,BlockPos around) {
        for(ServerPlayer viewer:p.serverLevel().players())
            if(viewer.blockPosition().distSqr(around)<10000)HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.ARCHITECTURE,p.getId(),payload));
    }

    private static int scale(Cast cast,long now) {return (int)Math.max(1,Math.min(MAX_SCALE,1+(now-cast.started)/GROW_TICKS));}

    private static CompoundTag describe(Cast cast,int scale,long until,boolean preview) {
        CompoundTag n=new CompoundTag();
        n.putInt("x",cast.anchor.getX());n.putInt("y",cast.anchor.getY());n.putInt("z",cast.anchor.getZ());
        n.putInt("scale",scale);n.putInt("seed",cast.seed);
        n.putFloat("yaw",cast.yaw);n.putLong("until",until);n.putBoolean("preview",preview);
        return n;
    }

    /** Snaps to the block the caster is looking at, then settles it onto the ground beneath. */
    private static BlockPos aim(ServerPlayer p) {
        Vec3 from=p.getEyePosition();
        BlockHitResult hit=p.level().clip(new ClipContext(from,from.add(p.getLookAngle().scale(26)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if(hit.getType()==HitResult.Type.MISS)return null;
        BlockPos base=hit.getBlockPos().relative(hit.getDirection());
        for(int i=0;i<4&&p.level().getBlockState(base.below()).isAir();i++)base=base.below();
        return base;
    }

    public static void forget(ServerPlayer p) {CASTING.remove(p.getUUID());}
    public static void reset() {CASTING.clear();IllusoryWalls.reset();}
}
