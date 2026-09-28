package com.hexgodofstories.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.*;

/**
 * Telekinesis, seen: a green glow of motes wound round whatever is held, rising round it in two strands and
 * drawn in toward it, with now and then a brighter spark. Particles only, so it costs what a few torches do, and
 * the caster's arm, held out toward it, does the rest (the telekinesis animation).
 */
public final class GripRenderer {
    private GripRenderer() {}

    private record Grasp(int[] targets,double distance,long since) {}
    private static final Map<Integer,Grasp> GRASPS=new HashMap<>();
    private static final double VISIBLE=64*64;
    /** Two greens, a bright one and a deep one, so the glow has some depth to it. */
    private static final DustParticleOptions BRIGHT=new DustParticleOptions(new Vector3f(.42f,1f,.52f),1.1f),
        DEEP=new DustParticleOptions(new Vector3f(.12f,.78f,.34f),.9f);

    public static void clear() {GRASPS.clear();}

    public static void set(int caster,CompoundTag n) {
        if(n.getInt("count")<=0){GRASPS.remove(caster);return;}
        if(GRASPS.size()>24)GRASPS.clear();
        Grasp prior=GRASPS.get(caster);
        GRASPS.put(caster,new Grasp(n.getIntArray("targets"),n.getDouble("distance"),
            prior==null?ClientState.now():prior.since()));
    }

    /** True for a body currently held: its own movement input is taken away while it is. */
    public static boolean gripped(int entity) {
        for(Grasp g:GRASPS.values())for(int id:g.targets())if(id==entity)return true;
        return false;
    }

    public static void tick(long now) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null){GRASPS.clear();return;}
        GRASPS.keySet().removeIf(id->mc.level.getEntity(id)==null);
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        for(Grasp grasp:GRASPS.values())
            for(int id:grasp.targets()) {
                Entity held=mc.level.getEntity(id);
                if(held==null||held.position().distanceToSqr(eye)>VISIBLE)continue;
                double radius=held.getBbWidth()*.5+.45,height=held.getBbHeight();
                // Two strands winding up round the body, each mote drifting in toward it.
                for(int strand=0;strand<2;strand++)
                    for(int i=0;i<3;i++) {
                        double angle=now*.35+strand*Math.PI+i*.7,up=((now*.06+i*.33+strand*.5)%1)*(height+.3)-.15;
                        double x=held.getX()+Math.cos(angle)*radius,z=held.getZ()+Math.sin(angle)*radius,y=held.getY()+up;
                        mc.level.addParticle(i==0?BRIGHT:DEEP,x,y,z,(held.getX()-x)*.04,.01,(held.getZ()-z)*.04);
                    }
                if(now%4==0)mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER,held.getX()+(random.nextDouble()-.5)*radius*2,
                    held.getY()+random.nextDouble()*height,held.getZ()+(random.nextDouble()-.5)*radius*2,0,0,0);
            }
    }
}
