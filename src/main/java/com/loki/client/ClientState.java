package com.loki.client;

import com.loki.network.LokiNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The client's mirror of the server's authoritative Loki state. Nothing here decides anything; it only remembers. */
public final class ClientState {
    public static final Map<Integer,CompoundTag> PLAYERS=new HashMap<>();
    public static final Set<Integer> SLOWED=new HashSet<>();
    public static final Map<Integer,CompoundTag> FROZEN=new HashMap<>();
    public static final Map<Integer,ThreadLink> THREADS=new HashMap<>();
    public record ThreadLink(int target,long until) {}
    private static Object world;

    public static long now(){return Minecraft.getInstance().level==null?0:Minecraft.getInstance().level.getGameTime();}
    public static CompoundTag data(int id){return PLAYERS.getOrDefault(id,new CompoundTag());}
    public static CompoundTag self(){var p=Minecraft.getInstance().player;return p==null?new CompoundTag():data(p.getId());}
    public static boolean frozen(int id){return FROZEN.containsKey(id);}
    public static float progress(int id,float partial) {
        CompoundTag d=data(id);
        if(!d.getBoolean("ascended"))return 0;
        if(!d.contains("transformStart"))return 1;
        return Math.max(0,Math.min(1,(now()+partial-d.getLong("transformStart"))/140f));
    }

    public static void receive(LokiNetwork.Message m) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        if(world!=mc.level)tick();
        switch(m.kind()) {
            case LokiNetwork.SYNC -> PLAYERS.put(m.entity(),m.data());
            case LokiNetwork.ANIMATE -> LokiAnimations.playRemote(m.entity(),m.data().getString("animation"),3);
            case LokiNetwork.FX -> WorldEffects.add(m.entity(),m.data());
            case LokiNetwork.FROZEN -> {
                if(m.data().getBoolean("frozen"))FROZEN.put(m.entity(),m.data());
                else {
                    FROZEN.remove(m.entity());
                    Entity e=mc.level.getEntity(m.entity());
                    if(e!=null)e.setDeltaMovement(m.data().getDouble("vx"),m.data().getDouble("vy"),m.data().getDouble("vz"));
                }
            }
            case LokiNetwork.GRIP -> WorldEffects.grip(m.entity(),m.data());
            case LokiNetwork.MEMORY -> WorldEffects.memory(m.entity(),m.data());
            case LokiNetwork.THREADS -> THREADS.put(m.entity(),new ThreadLink(m.data().getInt("target"),m.data().getLong("until")));
            case LokiNetwork.SLOWED -> {if(m.data().getBoolean("slowed"))SLOWED.add(m.entity());else SLOWED.remove(m.entity());}
            case LokiNetwork.ARCHITECTURE -> WorldEffects.architecture(m.entity(),m.data());
            case LokiNetwork.BLEED -> WorldEffects.bleeding(m.entity(),m.data().getInt("stacks"));
            case LokiNetwork.FIELD -> WorldEffects.field(m.entity(),m.data());
            default -> {}
        }
    }

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level!=world) {
            PLAYERS.clear();FROZEN.clear();SLOWED.clear();THREADS.clear();
            WorldEffects.clear();LokiSkin.clear();LokiLayer.clear();TemporalScreen.close();
            world=mc.level;
        }
        if(mc.level==null)return;
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
        }
        THREADS.entrySet().removeIf(e->e.getValue().until<now());
        WorldEffects.tick();
    }
}
