package com.hexgodofstories.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Local prediction for the server-authoritative slow rise out of a Warping exit puddle. */
public final class WarpEmergenceClient {
    private WarpEmergenceClient(){}
    private record Rise(double x,double z,double topY,double startY,long start,int duration){}
    private static final Map<Integer,Rise> ACTIVE=new HashMap<>();

    public static void receive(int id,CompoundTag n){
        if(n.getBoolean("clear")){ACTIVE.remove(id);return;}
        ACTIVE.put(id,new Rise(n.getDouble("x"),n.getDouble("z"),n.getDouble("topY"),n.getDouble("startY"),
            n.getLong("start"),Math.max(1,n.getInt("duration"))));
    }
    public static boolean emerging(int id){return ACTIVE.containsKey(id);}

    public static boolean tickLocal(LocalPlayer p){
        if(p==null)return false;
        Rise s=ACTIVE.get(p.getId());if(s==null)return false;
        double t=Math.max(0,Math.min(1,(ClientState.now()-s.start())/(double)s.duration()));
        double eased=t*t*(3-2*t);
        double y=s.startY()+(s.topY()-s.startY())*eased;
        p.noPhysics=true;p.setPos(s.x(),y,s.z());
        p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.setSprinting(false);
        if(t>=1){p.noPhysics=false;ACTIVE.remove(p.getId());}
        return true;
    }

    public static void clear(){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null&&ACTIVE.containsKey(mc.player.getId()))mc.player.noPhysics=false;
        ACTIVE.clear();
    }
}
