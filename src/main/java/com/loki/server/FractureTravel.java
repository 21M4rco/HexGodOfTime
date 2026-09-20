package com.loki.server;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.loki.entity.RiftEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * What the cast key does with the Fracture, and where the break leads.
 *
 * <p>The selector only ever stores a mode id and, where a mode needs one, a target. Everything that
 * turns that into a real place happens here, on the server, when the cast actually runs — which is
 * both the safety property (a client cannot name its own destination) and the performance one
 * (nothing is searched for until somebody asks for it, and "nearest player" means nearest *now*
 * rather than nearest when the menu was open).
 *
 * <p>A mode is a {@link Resolver} in the table below. Adding the End, a saved anchor or somebody
 * else's dimension is one entry here and one in {@link FractureModes}.
 */
public final class FractureTravel {
    private FractureTravel() {}

    /** @return where this mode leads, or null after telling the owner why it cannot. */
    public interface Resolver { FractureAnchor resolve(ServerPlayer owner); }

    private static final Map<String,Resolver> RESOLVERS=new HashMap<>();
    /** How far from a target player their side of the break opens. */
    private static final double NEAR_MIN=8,NEAR_MAX=12;
    private static final int SEARCH_RINGS=6;

    static {
        RESOLVERS.put(FractureModes.NEAR_PLAYER.id,FractureTravel::towardChosen);
        RESOLVERS.put(FractureModes.NEAREST_PLAYER.id,FractureTravel::towardNearest);
        RESOLVERS.put(FractureModes.NETHER.id,FractureTravel::towardNether);
        RESOLVERS.put(FractureModes.RESPAWN.id,FractureTravel::towardRespawn);
        RESOLVERS.put(FractureModes.OVERWORLD.id,FractureTravel::towardReturn);
    }

    public static FractureMode mode(ServerPlayer owner) {return LokiData.fractureMode(owner);}

    /**
     * Records a deliberate choice. Server-side validation of both the mode and the target is the
     * whole point: the client picks from a list, it does not get to invent a destination.
     */
    public static void choose(ServerPlayer owner,int index,UUID target) {
        FractureMode mode=FractureModes.byIndex(index);
        if(mode==null)return;
        // The panel is shut outside the sanctum; the server does not take its word for that.
        if(!selectable(owner))return;
        if(mode.needsTarget) {
            ServerPlayer chosen=target==null?null:owner.server.getPlayerList().getPlayer(target);
            if(chosen==null||chosen==owner) {
                owner.displayClientMessage(Component.literal("That one is no longer within reach."),true);
                return;
            }
            LokiData.fractureTarget(owner,chosen.getUUID(),chosen.getGameProfile().getName());
        } else LokiData.fractureTarget(owner,null,null);
        LokiData.fractureMode(owner,mode);
        LokiNetwork.sync(owner);
        owner.displayClientMessage(Component.literal("Fracture set to "+mode.label(LokiData.fractureTargetName(owner))+"."),true);
    }

    /**
     * @param stage {@link RiftEntity#TAP}, {@link RiftEntity#HOLD} or {@link RiftEntity#RELEASE}
     * @return true when the cast happened and should be charged for
     */
    public static boolean act(ServerPlayer owner,int stage) {
        // Out in the world the break leads one place: in. Whatever is saved describes the way out
        // and waits until the owner is standing in the sanctum to mean anything, so a cast here
        // always takes the caster — and, held, everything within five blocks of them — inside.
        if(!PocketRealm.inside(owner.level()))return LokiServer.pullFracture(owner,stage);
        FractureMode mode=mode(owner);
        // The pull keeps its own tap/hold distinction untouched.
        if(mode==FractureModes.PULL)return LokiServer.pullFracture(owner,stage);
        if(stage==RiftEntity.RELEASE)return false;
        FractureAnchor anchor=resolve(owner);
        if(anchor==null)return false;
        return LokiServer.openFracture(owner,anchor);
    }

    /** Resolves the saved mode into a real place, or null with the owner already told why. */
    public static FractureAnchor resolve(ServerPlayer owner) {
        Resolver resolver=RESOLVERS.get(mode(owner).id);
        return resolver==null?null:resolver.resolve(owner);
    }

    /**
     * Where a break out of the sanctum comes out. This is the anchor the owner *and* everything
     * travelling with them surfaces at, so a creature dragged in at one place and released at
     * another follows its captor rather than its own memory.
     */
    public static FractureAnchor exit(ServerPlayer owner) {
        FractureMode mode=mode(owner);
        FractureAnchor anchor=mode==FractureModes.PULL?towardReturn(owner):resolve(owner);
        return anchor!=null?anchor:towardReturn(owner);
    }

    /** Whether the owner may choose a destination right now: only from inside the sanctum. */
    public static boolean selectable(ServerPlayer owner) {return PocketRealm.inside(owner.level());}

    // ------------------------------------------------------------------ resolvers ---

    private static FractureAnchor towardChosen(ServerPlayer owner) {
        UUID id=LokiData.fractureTarget(owner);
        ServerPlayer target=id==null?null:owner.server.getPlayerList().getPlayer(id);
        if(target==null||target==owner||!target.isAlive()) {
            // A stale coordinate from somebody who logged out is exactly what must never be used.
            LokiData.fractureTarget(owner,null,null);
            LokiNetwork.sync(owner);
            notice(owner,"That one has left. Choose another with the Fracture menu.");
            return null;
        }
        FractureAnchor anchor=beside(target);
        if(anchor==null)notice(owner,"There is nowhere safe to surface near "+target.getGameProfile().getName()+".");
        return anchor;
    }

    private static FractureAnchor towardNearest(ServerPlayer owner) {
        // Resolved now, not when the menu closed: the mode is "nearest", not a name.
        ServerPlayer best=null;
        double nearest=Double.MAX_VALUE;
        for(ServerPlayer other:owner.server.getPlayerList().getPlayers()) {
            if(other==owner||!other.isAlive()||other.isSpectator())continue;
            double distance=other.level()==owner.level()?other.distanceToSqr(owner):Double.MAX_VALUE/2;
            if(distance<nearest){nearest=distance;best=other;}
        }
        if(best==null){notice(owner,"There is nobody else abroad to open beside.");return null;}
        FractureAnchor anchor=beside(best);
        if(anchor==null)notice(owner,"There is nowhere safe to surface near "+best.getGameProfile().getName()+".");
        return anchor;
    }

    private static FractureAnchor towardNether(ServerPlayer owner) {
        ServerLevel nether=owner.server.getLevel(Level.NETHER);
        if(nether==null){notice(owner,"The burning world will not answer.");return null;}
        // Scale by the usual factor so the break lands under the caster, not eight times away.
        double scale=owner.level().dimensionType().coordinateScale()/nether.dimensionType().coordinateScale();
        Vec3 near=new Vec3(owner.getX()*scale,owner.getY(),owner.getZ()*scale);
        FractureAnchor anchor=safeIn(nether,near,owner.getYRot());
        if(anchor==null)notice(owner,"Nothing but stone and fire waits on the other side.");
        return anchor;
    }

    private static FractureAnchor towardRespawn(ServerPlayer owner) {
        ServerLevel home=owner.server.getLevel(owner.getRespawnDimension());
        BlockPos bed=owner.getRespawnPosition();
        if(home!=null&&bed!=null) {
            // Asking the bed or anchor itself keeps modded respawn blocks working through the same path.
            Optional<Vec3> found=net.minecraft.world.entity.player.Player.findRespawnPositionAndUseSpawnBlock(
                home,bed,owner.getRespawnAngle(),owner.isRespawnForced(),false);
            if(found.isPresent()) {
                FractureAnchor anchor=safeIn(home,found.get(),owner.getRespawnAngle());
                if(anchor!=null)return anchor;
            }
        }
        ServerLevel overworld=owner.server.overworld();
        BlockPos spawn=overworld.getSharedSpawnPos();
        FractureAnchor anchor=safeIn(overworld,Vec3.atBottomCenterOf(spawn),overworld.getSharedSpawnAngle());
        if(anchor==null)notice(owner,"Neither your bed nor the world's heart will hold the break.");
        else if(bed==null)notice(owner,"You have no bed; the break opens at the world's heart.");
        return anchor;
    }

    /** The place the owner last left to come here, which is also the fallback for every other mode. */
    private static FractureAnchor towardReturn(ServerPlayer owner) {
        CompoundTag d=LokiData.get(owner);
        ResourceLocation id=ResourceLocation.tryParse(d.getString("returnDim"));
        ServerLevel destination=id==null?null:owner.server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
        boolean saved=destination!=null&&!PocketRealm.inside(destination)
            &&d.contains("returnX",Tag.TAG_ANY_NUMERIC)&&d.contains("returnY",Tag.TAG_ANY_NUMERIC)&&d.contains("returnZ",Tag.TAG_ANY_NUMERIC);
        if(destination==null||PocketRealm.inside(destination))destination=owner.server.overworld();
        Vec3 home=saved?new Vec3(d.getDouble("returnX"),d.getDouble("returnY"),d.getDouble("returnZ"))
            :Vec3.atBottomCenterOf(destination.getSharedSpawnPos());
        if(!Double.isFinite(home.x)||!Double.isFinite(home.y)||!Double.isFinite(home.z))
            home=Vec3.atBottomCenterOf(destination.getSharedSpawnPos());
        FractureAnchor anchor=safeIn(destination,home,saved?d.getFloat("returnYaw"):destination.getSharedSpawnAngle());
        if(anchor!=null)return anchor;
        BlockPos spawn=destination.getSharedSpawnPos();
        destination.getChunk(spawn.getX()>>4,spawn.getZ()>>4);
        int y=destination.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,spawn.getX(),spawn.getZ());
        return safeIn(destination,new Vec3(spawn.getX()+.5,y,spawn.getZ()+.5),destination.getSharedSpawnAngle());
    }

    // -------------------------------------------------------------------- searching ---

    /** A clear spot a short walk from somebody, never on top of them. */
    private static FractureAnchor beside(ServerPlayer target) {
        if(!(target.level() instanceof ServerLevel level))return null;
        var random=target.getRandom();
        for(int attempt=0;attempt<12;attempt++) {
            double angle=random.nextDouble()*Math.PI*2;
            double distance=NEAR_MIN+random.nextDouble()*(NEAR_MAX-NEAR_MIN);
            Vec3 want=target.position().add(Math.cos(angle)*distance,0,Math.sin(angle)*distance);
            BlockPos column=BlockPos.containing(want);
            level.getChunk(column.getX()>>4,column.getZ()>>4);
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ());
            // Stay near the target's own level when they are underground rather than surfacing above them.
            double preferred=Math.abs(y-target.getY())>12?target.getY():y;
            FractureAnchor anchor=safeIn(level,new Vec3(want.x,preferred,want.z),lookAt(want,target.position()));
            if(anchor!=null)return anchor;
        }
        return null;
    }
    private static float lookAt(Vec3 from,Vec3 to) {
        double dx=to.x-from.x,dz=to.z-from.z;
        return (float)(Math.atan2(dz,dx)*180/Math.PI)-90;
    }

    /**
     * A bounded, chunk-aware search for somewhere a player can actually stand: no suffocation, no
     * liquid, solid ground beneath, inside the world's build range and border.
     */
    public static FractureAnchor safeIn(ServerLevel level,Vec3 around,float yaw) {
        int base=Math.max(level.getMinBuildHeight()+1,Math.min(level.getMaxBuildHeight()-3,(int)Math.floor(around.y)));
        for(int ring=0;ring<=SEARCH_RINGS;ring++) {
            for(int x=-ring;x<=ring;x++)for(int z=-ring;z<=ring;z++) {
                if(Math.max(Math.abs(x),Math.abs(z))!=ring)continue;
                for(int step=0;step<=12;step++) {
                    // Prefer standing at the same height, then look up, then down.
                    int y=base+(step%2==0?step/2:-(step/2+1));
                    Vec3 at=new Vec3(Math.floor(around.x+x)+.5,y,Math.floor(around.z+z)+.5);
                    if(standable(level,at))return new FractureAnchor(level.dimension(),at,yaw,0);
                }
            }
        }
        return null;
    }

    private static boolean standable(ServerLevel level,Vec3 at) {
        BlockPos pos=BlockPos.containing(at);
        if(at.y<level.getMinBuildHeight()+1||at.y+2>=level.getMaxBuildHeight())return false;
        if(!level.getWorldBorder().isWithinBounds(pos))return false;
        level.getChunk(pos.getX()>>4,pos.getZ()>>4);
        if(!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty())return false;
        if(!level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty())return false;
        if(!level.getFluidState(pos).isEmpty()||!level.getFluidState(pos.above()).isEmpty())return false;
        BlockPos under=pos.below();
        var floor=level.getBlockState(under);
        if(!level.getFluidState(under).isEmpty())return false;
        // Something to land on, and nothing that lands on you.
        return !floor.getCollisionShape(level,under).isEmpty();
    }

    private static void notice(ServerPlayer p,String text) {p.displayClientMessage(Component.literal(text),true);}
}
