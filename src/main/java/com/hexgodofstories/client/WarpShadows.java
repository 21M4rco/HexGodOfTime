package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;

/**
 * What is on the other side, still moving.
 *
 * <p>A creature that falls through a break does not stop existing when the server changes which
 * dimension it is in — but as far as the client standing beside the hole is concerned it does,
 * because Minecraft renders one level at a time and the creature is now in the other one. That is
 * the single most damaging moment for the illusion this portal is built on: everything up to it
 * says "a hole in the floor", and then the thing that fell through it winks out.
 *
 * <p>So the server sends the transforms of whatever is near the destination's entry point to the
 * clients that can see the portal, and they are drawn inside the aperture at their real coordinates
 * in the destination. Nothing about them is an entity: there is no second body on any server, no
 * collision, no health, no AI, no inventory and nothing to interact with. They are marks on the far
 * side of a window, and they move because the real creature moves.
 *
 * <p>They are drawn as figures rather than as models on purpose. Everything else visible through a
 * Warping break — the islands, the ruins, the sea, the moon — is flat-shaded geometry, and a fully
 * textured vanilla model dropped into that would be the one photographic object in a diorama. A
 * figure built from the same primitives reads as part of the same place, and at the distances this
 * matters at — a body falling twenty, fifty, ninety blocks away — a silhouette is what a body looks
 * like anyway.
 */
public final class WarpShadows {
    private WarpShadows() { }

    /** One body on the far side, and where it was the last two times anybody said. */
    private static final class Shade {
        double x, y, z, px, py, pz;
        float yaw, width, height;
        long at, before;
        Shade(double x, double y, double z, float yaw, float width, float height, long now) {
            this.x = px = x; this.y = py = y; this.z = pz = z;
            this.yaw = yaw; this.width = width; this.height = height;
            this.at = this.before = now;
        }
        void moved(double nx, double ny, double nz, float nyaw, long now) {
            px = x; py = y; pz = z; before = at;
            x = nx; y = ny; z = nz; yaw = nyaw; at = now;
        }
        /** Between the last two reports, so a body reported every few ticks still falls smoothly. */
        Vec3 where(double time) {
            double span = Math.max(1, at - before);
            double t = Math.max(0, Math.min(1.35, (time - before) / span));
            return new Vec3(px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t);
        }
    }

    /** Per portal, because two breaks open on two destinations show two different far sides. */
    private static final Map<Integer, Map<Integer, Shade>> BY_PORTAL = new HashMap<>();

    public static void receive(int portal, CompoundTag n) {
        long now = ClientState.now();
        Map<Integer, Shade> shades = BY_PORTAL.computeIfAbsent(portal, id -> new HashMap<>());
        ListTag list = n.getList("shades", Tag.TAG_COMPOUND);
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int id = t.getInt("id");
            present.add(id);
            Shade shade = shades.get(id);
            if (shade == null) shades.put(id, new Shade(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                t.getFloat("yaw"), t.getFloat("w"), t.getFloat("h"), now));
            else shade.moved(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getFloat("yaw"), now);
        }
        // Gone from the report means out of the far side's window, occluded, or dead. Either way it
        // stops being drawn, which is what "until it would naturally no longer be visible" means.
        shades.keySet().retainAll(present);
        if (shades.isEmpty()) BY_PORTAL.remove(portal);
    }

    /** Whether this break has anything on its far side worth opening a buffer for. */
    public static boolean any(int portal) {
        Map<Integer, Shade> shades = BY_PORTAL.get(portal);
        return shades != null && !shades.isEmpty();
    }

    public static void forget(int portal) { BY_PORTAL.remove(portal); }

    /** Drops the far sides of breaks this client is no longer being told about. */
    public static void retain(java.util.Set<Integer> open) { BY_PORTAL.keySet().retainAll(open); }

    public static void clear() { BY_PORTAL.clear(); }

    /**
     * Draws the far side's occupants into the aperture.
     *
     * <p>Called from inside the destination scene, so the pose is already the one that puts the
     * realm's entry point at the hole. Drawing each figure at its raw destination coordinates is
     * therefore all that is needed for a body twenty blocks down the drop to appear twenty blocks
     * down through the floor, and to keep shrinking as it goes.
     */
    public static void draw(BufferBuilder b, Matrix4f m, int portal, double time, int colour) {
        Map<Integer, Shade> shades = BY_PORTAL.get(portal);
        if (shades == null) return;
        for (Shade shade : shades.values()) figure(b, m, shade, shade.where(time), time, colour);
    }

    /**
     * One body: a head, a trunk and four limbs, proportioned off whatever the real thing's bounding
     * box is, so a rabbit reads as small and squat and a creeper reads as tall and thin.
     */
    private static void figure(BufferBuilder b, Matrix4f m, Shade shade, Vec3 at, double time, int colour) {
        double h = Math.max(.4, shade.height), w = Math.max(.25, shade.width);
        double head = Math.min(h * .3, w * .8);
        double trunk = h * .46, legs = h - trunk - head;
        double yaw = Math.toRadians(shade.yaw);
        double fx = Math.cos(yaw) * w * .22, fz = Math.sin(yaw) * w * .22;
        int dark = WarpMesh.shade(colour, .55), light = WarpMesh.shade(colour, 1.35);
        // Trunk.
        WarpMesh.box(b, m, at.x - w * .32, at.y + legs, at.z - w * .22, w * .64, trunk, w * .44, dark, 1);
        // Head, leaning with the body's heading so it reads as facing somewhere.
        WarpMesh.sphere(b, m, new Vec3(at.x + fx, at.y + legs + trunk + head * .5, at.z + fz),
            head * .55, head * .55, head * .55, light, 1, 10, 0, false);
        // Limbs.
        for (int side = -1; side <= 1; side += 2) {
            double ox = -Math.sin(yaw) * w * .3 * side, oz = Math.cos(yaw) * w * .3 * side;
            double swing = Math.sin(time * .17 + side) * legs * .12;
            WarpMesh.box(b, m, at.x + ox - w * .1, at.y, at.z + oz - w * .1, w * .2, legs + swing, w * .2, dark, 1);
            WarpMesh.box(b, m, at.x + ox * 1.55 - w * .09, at.y + legs + trunk * .15, at.z + oz * 1.55 - w * .09,
                w * .18, trunk * .8, w * .18, dark, 1);
        }
        // A soft glow around it, so a small figure a long way down is still findable in the opening.
        WarpMesh.sphere(b, m, new Vec3(at.x, at.y + h * .5, at.z), w * 1.3, h * .7, w * 1.3, light, .10f, 10, 0, false);
    }
}
