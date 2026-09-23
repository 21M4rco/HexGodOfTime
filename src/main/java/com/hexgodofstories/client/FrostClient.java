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
        if(data.getBoolean("frozen"))ICE.put(id,new Ice(new Vec3(data.getDouble("x"),data.getDouble("y"),data.getDouble("z")),
            data.getFloat("yaw"),data.getFloat("pitch"),data.getInt("age"),data.getLong("until")));
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
        long age=ClientState.now()-aim.start;
        return age<0||age>16?0:age<5?Mth.clamp(age/5f,0,1):Mth.clamp((16-age)/10f,0,1);
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
        AIM.values().removeIf(aim->now-aim.start>18);
        for(var entry:ICE.entrySet()) {
            Entity e=world.getEntity(entry.getKey());Ice ice=entry.getValue();
            if(e==null)continue;
            e.setPos(ice.position);e.setYRot(ice.yaw);e.setXRot(ice.pitch);
            e.setDeltaMovement(Vec3.ZERO);e.tickCount=ice.age;
            e.xo=e.getX();e.yo=e.getY();e.zo=e.getZ();
            e.yRotO=e.getYRot();e.xRotO=e.getXRot();
            if(e instanceof LivingEntity living){living.yHeadRot=ice.yaw;living.yBodyRot=ice.yaw;}
        }
        if(now%3==0)for(int id:SHIVER.keySet()) {
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
