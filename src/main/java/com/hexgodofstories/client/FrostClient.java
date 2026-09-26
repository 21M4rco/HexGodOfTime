package com.hexgodofstories.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The wire state is cosmetic; server-side Frostbite owns all targeting, stun and damage. */
public final class FrostClient {
    private FrostClient() { }
    public static final DustParticleOptions PALE=new DustParticleOptions(new Vector3f(.60f,.87f,1f),1.1f);
    public static final DustParticleOptions BLUE=new DustParticleOptions(new Vector3f(.12f,.49f,1f),1.25f);
    private record Ice(Vec3 position,float yaw,float pitch,int age,long until) { }
    private record Aim(int entity,long start) { }
    private static final Map<Integer,Ice> ICE=new HashMap<>();
    private static final Map<Integer,Long> SHIVER=new HashMap<>();
    private static final Map<UUID,Aim> AIM=new HashMap<>();

    public static void receive(int id,CompoundTag data) {
        if(data.getBoolean("frozen")) {
            SHIVER.remove(id);
            ICE.put(id,new Ice(new Vec3(data.getDouble("x"),data.getDouble("y"),data.getDouble("z")),
                data.getFloat("yaw"),data.getFloat("pitch"),data.getInt("age"),data.getLong("until")));
        }
        else if(data.getBoolean("shiver"))SHIVER.put(id,data.getLong("until"));
        else ICE.remove(id);
    }
    public static boolean frozen(int entity){return ICE.containsKey(entity);}
    public static void cast(int entity) {
        var world=Minecraft.getInstance().level;
        if(world!=null&&world.getEntity(entity)!=null)AIM.put(world.getEntity(entity).getUUID(),new Aim(entity,ClientState.now()));
    }
    /** Blends the sword from its resting diagonal into a tip-first, forward-pointing beam pose. */
    public static float aim(ItemStack sword) {
        if(!sword.hasTag()||!sword.getTag().hasUUID("conjurer"))return 0;
        Aim aim=AIM.get(sword.getTag().getUUID("conjurer"));
        if(aim==null)return 0;
        float age=ClientState.now()+Minecraft.getInstance().getFrameTime()-aim.start;
        return age<0||age>26?0:age<6?Mth.clamp(age/6f,0,1):age<=12?1:Mth.clamp((26-age)/14f,0,1);
    }
    public static float pitch(ItemStack sword) {
        if(!sword.hasTag()||!sword.getTag().hasUUID("conjurer"))return 0;
        Aim aim=AIM.get(sword.getTag().getUUID("conjurer"));
        Entity e=aim==null||Minecraft.getInstance().level==null?null:Minecraft.getInstance().level.getEntity(aim.entity);
        return e==null?0:e.getXRot();
    }
    public static void tick() {
        var world=Minecraft.getInstance().level;
        if(world==null)return;
        long now=ClientState.now();
        ICE.entrySet().removeIf(entry->entry.getValue().until<=now||world.getEntity(entry.getKey())==null);
        SHIVER.entrySet().removeIf(entry->entry.getValue()<=now||world.getEntity(entry.getKey())==null);
        AIM.values().removeIf(aim->now-aim.start>28);
        for(var entry:ICE.entrySet()) {
            Entity e=world.getEntity(entry.getKey());Ice ice=entry.getValue();
            if(e==null)continue;
            e.tickCount=ice.age;
            if(e instanceof LivingEntity living){living.yHeadRot=ice.yaw;living.yBodyRot=ice.yaw;}
            ClientState.pin(e,ice.position.x,ice.position.y,ice.position.z,ice.yaw,ice.pitch);
        }
        if(now%3==0)for(int id:SHIVER.keySet()) {
            if(ICE.containsKey(id))continue;
            Entity e=world.getEntity(id);
            if(!(e instanceof LivingEntity living))continue;
            // A small tremor stays in the render state; it never changes server-side movement.
            living.yBodyRot+=(float)Math.sin(now*.95)*2.3f;
            var random=world.random;
            world.addParticle(PALE,e.getX()+(random.nextDouble()-.5)*e.getBbWidth(),
                e.getY()+random.nextDouble()*e.getBbHeight(),e.getZ()+(random.nextDouble()-.5)*e.getBbWidth(),0,.012,0);
        }
    }
    public static void clear(){ICE.clear();SHIVER.clear();AIM.clear();}
}
