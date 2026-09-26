package com.hexgodofstories.server;

import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hole a Scepter beam leaves in a body.
 *
 * <p>The server only says where the beam went through and when: the ray, and the body's own position
 * and facing at that instant, so every client can put the wound on the same place on the same limb
 * however the body moves afterwards. The hole itself — see-through, cauterised, closing — is drawn
 * by {@code BeamWounds} on the client. Bleeding is the ordinary {@link Bleed} the beam also applies.
 */
public final class BeamWound {
    private BeamWound() { }

    private static final int MAX_PER_BODY = 4, MAX_BODIES = 256;
    private record Open(CompoundTag data, long until) { }
    private static final Map<UUID, List<Open>> OPEN = new HashMap<>();

    public static void open(LivingEntity victim, Vec3 at, Vec3 direction, float radius, int life) {
        long now = victim.level().getGameTime();
        CompoundTag n = new CompoundTag();
        n.putDouble("x", at.x); n.putDouble("y", at.y); n.putDouble("z", at.z);
        n.putDouble("dx", direction.x); n.putDouble("dy", direction.y); n.putDouble("dz", direction.z);
        n.putDouble("ex", victim.getX()); n.putDouble("ey", victim.getY()); n.putDouble("ez", victim.getZ());
        n.putFloat("yaw", victim.yBodyRot);
        n.putFloat("r", radius);
        n.putLong("start", now);
        n.putInt("life", life);
        List<Open> list = OPEN.get(victim.getUUID());
        if (list == null) {
            if (OPEN.size() >= MAX_BODIES) OPEN.entrySet().removeIf(e -> e.getValue().stream().allMatch(o -> o.until <= now));
            if (OPEN.size() >= MAX_BODIES) return;
            list = new ArrayList<>();
            OPEN.put(victim.getUUID(), list);
        }
        list.removeIf(o -> o.until <= now);
        if (list.size() >= MAX_PER_BODY) list.remove(0);
        list.add(new Open(n, now + life));
        HexNetwork.tracking(victim, new HexNetwork.Message(HexNetwork.WOUND, victim.getId(), n));
    }

    /** A player who starts tracking a wounded body sees the holes it already has. */
    public static void track(ServerPlayer viewer, LivingEntity body) {
        List<Open> list = OPEN.get(body.getUUID());
        if (list == null) return;
        long now = body.level().getGameTime();
        list.removeIf(o -> o.until <= now);
        if (list.isEmpty()) {OPEN.remove(body.getUUID()); return;}
        for (Open o : list) HexNetwork.to(viewer, new HexNetwork.Message(HexNetwork.WOUND, body.getId(), o.data));
    }

    public static void forget(LivingEntity body) {OPEN.remove(body.getUUID());}

    public static void reset() {OPEN.clear();}
}
