package com.hexgodofstories.client.leviathan;

import com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * How much of a submerged body the water above it actually lets through.
 *
 * <p>The creature was legible through the surface from any height, in full detail, at any depth —
 * a hundred and fifty blocks of clearly readable silhouette lying under an ocean that was supposed
 * to be hiding it. That is not a quirk of this model or of its textures: it falls out of how the
 * realm and the entity pass are put together.
 *
 * <p>Water in Minecraft is not a volume you see through, it is a surface you see past. Only the
 * faces bordering air are drawn, so nine hundred blocks of ocean cost exactly one translucent
 * quad, and nothing between the camera and a body below it dims that body at all. The usual thing
 * that does the dimming instead is light: skylight loses a level per block of water, so anything
 * deep is black. This realm has no skylight to lose — {@code has_skylight} is false — and a flat
 * {@code ambient_light} of 0.18 underneath it, which means every block of the water column is lit
 * to exactly the same value as every other. Depth costs the creature nothing. It is as evenly
 * lit, and as sharply drawn, two hundred blocks down as it is at the surface.
 *
 * <p>Lowering the realm's ambient light would blind the realm rather than hide the creature, and
 * fog belongs to the camera, not to the thing being looked at, so neither is the lever. What is
 * missing is the attenuation the water should have been applying, so that is what this puts back:
 * the depth of water a ray has to climb through to reach the eye, turned into how much of the body
 * survives the trip. It applies to the entity's own draw and to nothing else in the world.
 */
public final class LeviathanWaterVeil {
    /** Blocks of water that cut what gets through by about a factor of e. */
    private static final double ATTENUATION = 19.0;
    /**
     * Water this shallow hides nothing.
     *
     * <p>Deliberately generous. A breach, a back breaking the surface, a head coming up underneath
     * a boat — the moments the creature is supposed to be seen — all happen inside this band, and
     * every one of them has to read at full strength or the fix has cost more than the bug did.
     */
    private static final double CLEAR = 7.0;
    /** Below this nothing is drawn at all; it would be a smear of water colour over water. */
    public static final float CUTOFF = 0.02f;

    private LeviathanWaterVeil() { }

    /** True when the viewer's own head is under water, where none of this applies. */
    public static boolean viewerSubmerged() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
    }

    /**
     * What fraction of the body at {@code point} reaches a camera at {@code camera}.
     *
     * <p>One is untouched, nought is gone. Sharing the water with the creature returns one on
     * purpose: down there ordinary fog, light and line of sight already decide what can be seen,
     * and overriding them would hide the creature from the one person who has earned the sight of
     * it. The problem being solved is looking in from outside, through the surface.
     */
    public static float visibility(AbyssalPilgrimEntity entity, Vec3 point, Vec3 camera, boolean viewerSubmerged) {
        if (viewerSubmerged) return 1f;
        double depth = entity.surfaceY() - point.y;
        if (depth <= 0) return 1f;                       // this part of the body is out of the water
        Vec3 toCamera = camera.subtract(point);
        double length = toCamera.length();
        if (length < 1.0E-4) return 1f;
        // Light leaving the body only travels through water as far as the surface, so the distance
        // it spends in water is the depth divided by how steeply the ray climbs. Straight down is
        // the shortest that path ever gets; a glancing look across the sea crosses far more water
        // for the same depth, which is why something submerged is hardest to make out from the
        // shore and easiest from directly overhead. The floor stops a horizontal view dividing by
        // nothing and returning an infinite path.
        double climb = Math.max(0.12, toCamera.y / length);
        double travelled = Math.max(0.0, depth / climb - CLEAR);
        return (float) Math.exp(-travelled / ATTENUATION);
    }

    /** The water's own fog colour where the creature is, which is what replaces it as it fades. */
    public static int waterColour(AbyssalPilgrimEntity entity) {
        return entity.level().getBiome(BlockPos.containing(entity.position())).value().getWaterFogColor();
    }
}
