package com.hexgodofstories.client;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The client's mirror of the server's authoritative power state. Nothing here decides anything; it only remembers. */
public final class ClientState {
    public static final Map<Integer,CompoundTag> PLAYERS=new HashMap<>();
    public static final Set<Integer> SLOWED=new HashSet<>();
    public static final Map<Integer,CompoundTag> FROZEN=new HashMap<>();
    public static final Map<Integer,Long> STUNNED=new HashMap<>();
    public static final Map<Integer,ThreadLink> THREADS=new HashMap<>();
    /** Borrowed-shape snapshots, kept apart from the per-second state packet because of their size. */
    public static final Map<Integer,CompoundTag> DISGUISES=new HashMap<>();
    public record ThreadLink(int target,long until) {}
    private static Object world;

    public static long now(){return Minecraft.getInstance().level==null?0:Minecraft.getInstance().level.getGameTime();}
    public static CompoundTag data(int id){return PLAYERS.getOrDefault(id,new CompoundTag());}
    public static CompoundTag self(){var p=Minecraft.getInstance().player;return p==null?new CompoundTag():data(p.getId());}
    public static boolean hidden(Entity entity) {
        return entity.isInvisible()||data(entity.getId()).getLong("vanishUntil")>now();
    }
    public static boolean frozen(int id){return FROZEN.containsKey(id);}
    /** Ticks this player has been holding Time Branch Unleashing, or -1 when they are not holding it. */
    public static int branchHeld(int id,float partial){return TimeBranchRenderer.held(id,partial);}
    /** Planted or being erased: either way this body takes no movement input of its own. */
    public static boolean immobile(net.minecraft.world.entity.Entity e) {
        return e!=null&&(frozen(e.getId())||STUNNED.getOrDefault(e.getId(),0L)>now()||TimeBranchRenderer.charging(e.getId())
            ||ErasureRenderer.erasing(e)||GripRenderer.gripped(e.getId())||WarpEmergenceClient.emerging(e.getId()));
    }
    public static float progress(int id,float partial) {
        CompoundTag d=data(id);
        if(!d.getBoolean("ascended"))return 0;
        if(!d.contains("transformStart"))return 1;
        return Math.max(0,Math.min(1,(now()+partial-d.getLong("transformStart"))/140f));
    }

    public static void receive(HexNetwork.Message m) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        if(world!=mc.level)tick();
        switch(m.kind()) {
            case HexNetwork.MOON_FRAME -> {
                Entity entity=mc.level.getEntity(m.entity());
                if(entity!=null&&entity!=mc.player)com.hexgodofstories.warping.MoonGravity.frame(entity,
                    new Vec3(m.data().getDouble("x"),m.data().getDouble("y"),m.data().getDouble("z")));
            }
            case HexNetwork.WARP -> WarpRenderer.receive(m.entity(),m.data());
            case HexNetwork.WARP_REALM -> WarpRenderer.realm(m.data());
            case HexNetwork.WARP_PHASE -> WarpCrossingClient.receive(m.entity(),m.data());
            case HexNetwork.WARP_SHADOWS -> WarpShadows.receive(m.entity(),m.data());
            case HexNetwork.WARP_EMERGE -> WarpEmergenceClient.receive(m.entity(),m.data());
            case HexNetwork.CANDY_BODY -> CandyCorruptionClient.receive(m.entity(),m.data());
            case HexNetwork.FROST -> FrostClient.receive(m.entity(),m.data());
            case HexNetwork.STUN -> {long until=m.data().getLong("until");if(until>now())STUNNED.put(m.entity(),until);else STUNNED.remove(m.entity());}
            case HexNetwork.PILGRIM_PATH -> {
                var world = net.minecraft.client.Minecraft.getInstance().level;
                if (world != null && world.getEntity(m.entity()) instanceof com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity pilgrim)
                    pilgrim.segments().acceptSnapshot(m.data(), pilgrim.position());
            }
            case HexNetwork.PILGRIM -> com.hexgodofstories.client.leviathan.LeviathanEffects.receive(m.data());
            case HexNetwork.SYNC -> PLAYERS.put(m.entity(),m.data());
            case HexNetwork.ANIMATE -> HexAnimations.playRemote(m.entity(),m.data().getString("animation"),3);
            case HexNetwork.FX -> WorldEffects.add(m.entity(),m.data());
            case HexNetwork.FROZEN -> {
                if(m.data().getBoolean("frozen"))FROZEN.put(m.entity(),m.data());
                else {
                    FROZEN.remove(m.entity());
                    Entity e=mc.level.getEntity(m.entity());
                    if(e!=null)e.setDeltaMovement(m.data().getDouble("vx"),m.data().getDouble("vy"),m.data().getDouble("vz"));
                }
            }
            case HexNetwork.GRIP -> WorldEffects.grip(m.entity(),m.data());
            case HexNetwork.MEMORY -> WorldEffects.memory(m.entity(),m.data());
            case HexNetwork.THREADS -> THREADS.put(m.entity(),new ThreadLink(m.data().getInt("target"),m.data().getLong("until")));
            case HexNetwork.SLOWED -> {if(m.data().getBoolean("slowed"))SLOWED.add(m.entity());else SLOWED.remove(m.entity());}
            case HexNetwork.ARCHITECTURE -> WorldEffects.architecture(m.entity(),m.data());
            case HexNetwork.BLEED -> WorldEffects.bleeding(m.entity(),m.data().getInt("stacks"));
            case HexNetwork.FIELD -> WorldEffects.field(m.entity(),m.data());
            case HexNetwork.BRANCH -> TimeBranchRenderer.charge(m.entity(),m.data());
            case HexNetwork.TORRENT -> TimeBranchRenderer.torrent(m.entity(),m.data());
            case HexNetwork.ERASURE -> ErasureRenderer.begin(m.entity(),m.data());
            case HexNetwork.DISGUISE -> {
                if(m.data().getBoolean("clear"))DISGUISES.remove(m.entity());
                else {
                    if(DISGUISES.size()>32)DISGUISES.clear();
                    DISGUISES.put(m.entity(),m.data());
                }
            }
            default -> {}
        }
    }

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level!=world) {
            WarpRenderer.clear();PLAYERS.clear();FROZEN.clear();STUNNED.clear();SLOWED.clear();THREADS.clear();DISGUISES.clear();WarpCrossingClient.clear();WarpEmergenceClient.clear();CandyCorruptionClient.clear();
            WorldEffects.clear();HexSkin.clear();HexLayer.clear();DisguiseRenderer.clear();TemporalScreen.close();TimeStopScreen.close();FrostClient.clear();
            com.hexgodofstories.client.leviathan.LeviathanEffects.clear();
            TimeBranchRenderer.clear();ErasureRenderer.clear();BranchAudio.clear();MeteorAudio.clear();GripRenderer.clear();
            HexClient.ForgeBus.releaseHeldCast();
            world=mc.level;
        }
        com.hexgodofstories.client.leviathan.LeviathanEffects.tick();
        WarpCrossingClient.tick();
        if(mc.level==null)return;
        FrostClient.tick();
        HexAnimations.tickScepterView();
        FROZEN.forEach((id,n)->{
            Entity e=mc.level.getEntity(id);
            if(e==null)return;
            e.setPos(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
            e.setYRot(n.getFloat("yaw"));e.setXRot(n.getFloat("pitch"));
            e.setDeltaMovement(Vec3.ZERO);
            e.xo=e.getX();e.yo=e.getY();e.zo=e.getZ();
            e.yRotO=e.getYRot();e.xRotO=e.getXRot();
        });
        if(now()%100==0) {
            PLAYERS.keySet().removeIf(id->mc.level.getEntity(id)==null);
            FROZEN.keySet().removeIf(id->mc.level.getEntity(id)==null);
            DISGUISES.keySet().removeIf(id->mc.level.getEntity(id)==null);
        }
        THREADS.entrySet().removeIf(e->e.getValue().until<now());
        STUNNED.entrySet().removeIf(e->e.getValue()<=now()||mc.level.getEntity(e.getKey())==null);
        WorldEffects.tick();
        TimeBranchRenderer.tick();
        ErasureRenderer.tick();
        BranchAudio.tick();
        MeteorAudio.tick();
        RealmAmbience.tick();
    }
}
