package com.loki.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A resolved place a Fracture leads to.
 *
 * <p>Once a break is open this is the single authority on where everything that walks through ends
 * up — the owner and every entity travelling with them. That is deliberate: a transported creature
 * has no say in where it surfaces, so it can never be sent back to wherever it was originally
 * seized from.
 */
public record FractureAnchor(ResourceKey<Level> dimension,Vec3 at,float yaw,float pitch) {

    public ServerLevel level(MinecraftServer server) {return server.getLevel(dimension);}

    public CompoundTag save() {
        CompoundTag n=new CompoundTag();
        n.putString("dim",dimension.location().toString());
        n.putDouble("x",at.x);n.putDouble("y",at.y);n.putDouble("z",at.z);
        n.putFloat("yaw",yaw);n.putFloat("pitch",pitch);
        return n;
    }

    public static FractureAnchor load(CompoundTag n) {
        if(n==null||n.isEmpty())return null;
        ResourceLocation id=ResourceLocation.tryParse(n.getString("dim"));
        if(id==null)return null;
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(!Double.isFinite(at.x)||!Double.isFinite(at.y)||!Double.isFinite(at.z))return null;
        return new FractureAnchor(ResourceKey.create(Registries.DIMENSION,id),at,n.getFloat("yaw"),n.getFloat("pitch"));
    }
}
