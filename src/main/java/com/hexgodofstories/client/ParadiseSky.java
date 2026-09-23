package com.hexgodofstories.client;

import com.hexgodofstories.warping.Paradise;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.Random;

/**
 * What Paradise looks like from inside it: the sky over the islands, and the things drifting
 * between them.
 *
 * <p>The sky is deliberately not a recolour of the other realms'. Those are a dark shell with
 * stars and a nebula on it, which is what deep space looks like; this is what a confectioner's
 * idea of deep space looks like. A lilac shell, banded nebulae in rose and magenta wound into a
 * spiral, four galaxies with visible arms, a dense bright starfield, and rainbows — five of them
 * across different parts of the dome, two carrying a second bow outside the first with its colours
 * in the reverse order, the way a real secondary bow runs.
 *
 * <p><b>The static half is baked and the moving half is not.</b> Everything that never changes —
 * shell, nebulae, galaxies, stars, rainbows, the coloured wash near the horizon — is uploaded to
 * the GPU once and drawn with a slow rotation applied to the whole dome. That is about twenty
 * thousand quads that cost one draw call rather than being rebuilt every frame. The confectionery
 * is drifting, so it is built per frame, and there is deliberately not very much of it: a few
 * dozen large, clearly readable pieces beats a thousand specks, and the brief was that the sweets
 * should be things you can see rather than particles you have to look for.
 *
 * <p>The same two halves serve the realm itself and the view through a Warping portal, because
 * both call the same two entry points with different transforms.
 */
public final class ParadiseSky {
    private ParadiseSky() { }

    /** The middle of the sky dome, in the local space every realm's sky is drawn in. */
    private static final Vec3 DOME = new Vec3(0, 110, 0);
    private static final double SHELL = 310;

    /** A bow, from the inside out. The order matters: a secondary bow runs the other way. */
    private static final int[] BOW = {0xff5f6b, 0xffab52, 0xffe86a, 0x77e07b, 0x54c6ff, 0x6f66ff, 0xc072ff};
    /** Confectionery colours: the wrappers, shells and swirls every sweet is painted from. */
    private static final int[] SUGAR = {0xff6fae, 0xff9ecd, 0xffd1e8, 0xfff0a8, 0xa8e9ff, 0xb98cff, 0xff8f7a, 0xfff6ec};

    private static VertexBuffer dome;

    public static void clear() {
        if (dome != null) { dome.close(); dome = null; }
    }

    // ------------------------------------------------------------------ the sky

    /** The whole dome: the baked half drawn once, then the sweets drifting in front of it. */
    public static void sky(PoseStack pose, double time) {
        if (dome == null) dome = bake();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        pose.pushPose();
        try {
            pose.mulPose(Axis.YP.rotationDegrees((float) (time * 0.0022)));
            dome.bind();
            dome.drawWithShader(pose.last().pose(), RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
            VertexBuffer.unbind();
        } finally {
            pose.popPose();
        }
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drifting(b, pose.last().pose(), time);
        BufferUploader.drawWithShader(b.end());
    }

    private static VertexBuffer bake() {
        BufferBuilder b = new BufferBuilder(2 * 1024 * 1024);
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = new Matrix4f();
        Random r = new Random(0x9A11CE);

        // The shell. Lilac rather than black: nothing in this realm is allowed to be dark.
        WarpMesh.sphere(b, m, DOME, SHELL, SHELL, SHELL, 0x54155f, 1, 32, 0, false);

        // Nebulae, wound into a few broad bands rather than scattered. A scatter is fog; a band
        // that climbs across the dome and thins at its ends is a structure the eye can follow.
        for (int band = 0; band < 5; band++) {
            double tilt = band * 0.7 + 0.35, twist = band * 2.1;
            int colour = switch (band) {
                case 0 -> 0xff5fb4;
                case 1 -> 0xc061ff;
                case 2 -> 0xff9ad8;
                case 3 -> 0x7fb4ff;
                default -> 0xffc2ea;
            };
            for (int i = 0; i < 46; i++) {
                double t = i / 45.0, a = twist + t * Math.PI * 1.9;
                double radius = SHELL * (0.72 + 0.13 * Math.sin(t * 5 + band));
                Vec3 p = DOME.add(Math.cos(a) * radius * Math.cos(tilt),
                    Math.sin(tilt) * radius * 0.55 + Math.sin(t * 3.1 + band) * 38,
                    Math.sin(a) * radius * Math.cos(tilt));
                double size = 16 + r.nextDouble() * 34;
                // Two coats: a wide soft one for the colour, a tighter bright one for the core.
                WarpMesh.sphere(b, m, p, size, size * 0.62, size, colour, 0.045f, 10, 0, false);
                if (i % 3 == 0) WarpMesh.sphere(b, m, p, size * 0.45, size * 0.3, size * 0.45, 0xffffff, 0.035f, 8, 0, false);
            }
        }

        // Stars. Dense, small, and mostly warm, so the dome glitters instead of speckling.
        for (int i = 0; i < 1400; i++) {
            Vec3 n = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            Vec3 p = DOME.add(n.scale(SHELL * 0.95));
            double size = 0.11 + r.nextDouble() * 0.30;
            Vec3 side = n.cross(new Vec3(0, 1, 0)).normalize().scale(size), up = n.cross(side);
            int colour = i % 7 == 0 ? 0xffd9f2 : i % 5 == 0 ? 0xcfe6ff : 0xffffff;
            WarpMesh.quad(b, m, p.subtract(side).subtract(up), p.add(side).subtract(up),
                p.add(side).add(up), p.subtract(side).add(up), colour, 0.9f);
            // A handful are bright enough to throw a cross of light.
            if (i % 37 == 0) {
                WarpMesh.quad(b, m, p.subtract(side.scale(4)).subtract(up.scale(.35)), p.add(side.scale(4)).subtract(up.scale(.35)),
                    p.add(side.scale(4)).add(up.scale(.35)), p.subtract(side.scale(4)).add(up.scale(.35)), colour, 0.35f);
                WarpMesh.quad(b, m, p.subtract(side.scale(.35)).subtract(up.scale(4)), p.add(side.scale(.35)).subtract(up.scale(4)),
                    p.add(side.scale(.35)).add(up.scale(4)), p.subtract(side.scale(.35)).add(up.scale(4)), colour, 0.35f);
            }
        }

        // Galaxies, with arms that wind and a core that is brighter than they are.
        for (int g = 0; g < 4; g++) {
            double gx = Math.cos(g * 1.7 + 0.4), gz = Math.sin(g * 1.7 + 0.4);
            Vec3 centre = DOME.add(gx * SHELL * 0.74, 60 + g * 46 - (g == 3 ? 190 : 0), gz * SHELL * 0.74);
            double lean = 0.32 + g * 0.16;
            int arm = g % 2 == 0 ? 0xff8ed6 : 0x9bb4ff, core = 0xfff0ff;
            for (int i = 0; i < 300; i++) {
                double a = i * 0.155 + g * 2.3, radius = Math.pow(i / 300.0, 0.62) * (20 + g * 4);
                Vec3 p = centre.add(Math.cos(a) * radius, Math.sin(a) * radius * lean, Math.sin(a) * radius * 0.72);
                double size = i < 34 ? 0.9 : 0.34;
                WarpMesh.box(b, m, p.x, p.y, p.z, size, size, size, i < 34 ? core : arm, i < 34 ? 0.85f : 0.55f);
            }
            WarpMesh.sphere(b, m, centre, 5.5, 2.2, 4, core, 0.10f, 12, 0, false);
        }

        // One large readable spiral, deliberately much bigger than the background galaxies. It is
        // the visual anchor above the heart island: four luminous arms, a white core, and a pink
        // halo instead of another small knot of stars.
        Vec3 hero = DOME.add(0, 126, -SHELL * 0.70);
        for (int armIndex = 0; armIndex < 4; armIndex++) {
            double offset = armIndex * Math.PI * 0.5;
            for (int i = 0; i < 280; i++) {
                double t = i / 279.0;
                double a = offset + t * Math.PI * 4.9;
                double radius = 3.0 + Math.pow(t, 0.72) * 58.0;
                Vec3 p = hero.add(Math.cos(a) * radius,
                    Math.sin(a * 1.65 + armIndex) * radius * 0.10,
                    Math.sin(a) * radius * 0.43);
                double size = 0.28 + (1.0 - t) * 0.72;
                int colour = (armIndex & 1) == 0 ? 0xff80d8 : 0xc49dff;
                WarpMesh.box(b, m, p.x, p.y, p.z, size, size, size, colour, 0.58f);
            }
        }
        WarpMesh.sphere(b, m, hero, 12, 4.6, 9, 0xffffff, 0.18f, 16, 0, false);
        WarpMesh.sphere(b, m, hero, 24, 8.5, 18, 0xff8fd8, 0.055f, 16, 0, false);

        // The bows. Five of them, at five orientations, two of them carrying a second.
        bow(b, m, new Vec3(0.20, 0.95, 0.24), 196, -0.95, 0.95, 3.0, 0.62f, true);
        bow(b, m, new Vec3(-0.78, 0.55, 0.30), 168, -1.25, 0.72, 2.4, 0.50f, false);
        bow(b, m, new Vec3(0.62, 0.42, -0.66), 212, -0.80, 1.15, 2.8, 0.44f, true);
        bow(b, m, new Vec3(-0.25, 0.30, -0.92), 150, -1.05, 0.45, 2.1, 0.38f, false);
        bow(b, m, new Vec3(0.88, 0.22, 0.42), 240, -0.55, 0.60, 3.4, 0.30f, false);

        // A rainbow wash lying low around the whole dome, so the light itself has colour in it.
        for (int i = 0; i < 7; i++) {
            double a = i * Math.PI * 2 / 7;
            Vec3 p = DOME.add(Math.cos(a) * SHELL * 0.66, -46 + Math.sin(a * 2) * 26, Math.sin(a) * SHELL * 0.66);
            WarpMesh.sphere(b, m, p, 108, 52, 108, BOW[i], 0.030f, 12, 0, false);
        }

        VertexBuffer mesh = new VertexBuffer(VertexBuffer.Usage.STATIC);
        mesh.bind();
        mesh.upload(b.end());
        VertexBuffer.unbind();
        return mesh;
    }

    /**
     * One rainbow, and optionally the secondary bow outside it.
     *
     * <p>The arc is drawn on the plane whose normal is {@code axis}, which is the only part of
     * this that needs saying: five bows all drawn on the same plane would be five concentric
     * rings, and what makes a sky full of rainbows read as a sky rather than as a target is that
     * each one is leaning a different way.
     */
    private static void bow(BufferBuilder b, Matrix4f m, Vec3 axis, double radius, double from, double to,
                            double band, float alpha, boolean doubled) {
        Vec3 n = axis.normalize();
        Vec3 u = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : n.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 v = n.cross(u).normalize();
        arc(b, m, u, v, radius, from, to, band, alpha, false);
        if (doubled) arc(b, m, u, v, radius + BOW.length * band * 2.1, from * 0.94, to * 0.94, band * 0.86, alpha * 0.42f, true);
    }

    private static void arc(BufferBuilder b, Matrix4f m, Vec3 u, Vec3 v, double radius, double from, double to,
                            double band, float alpha, boolean reversed) {
        int steps = 40;
        for (int k = 0; k < BOW.length; k++) {
            int colour = BOW[reversed ? BOW.length - 1 - k : k];
            double r0 = radius + k * band, r1 = radius + (k + 1) * band;
            for (int i = 0; i < steps; i++) {
                double t0 = from + (to - from) * i / steps, t1 = from + (to - from) * (i + 1) / steps;
                // The ends thin out rather than stopping dead, which is what keeps a band of colour
                // from looking like a painted stripe with two cut ends.
                float fade = alpha * (float) (Math.sin(Math.PI * (i + 0.5) / steps) * 0.75 + 0.25);
                WarpMesh.quad(b, m, on(u, v, r0, t0), on(u, v, r0, t1), on(u, v, r1, t1), on(u, v, r1, t0), colour, fade);
            }
        }
    }

    private static Vec3 on(Vec3 u, Vec3 v, double radius, double t) {
        return DOME.add(u.scale(Math.cos(t) * radius)).add(v.scale(Math.sin(t) * radius));
    }

    // ------------------------------------------------------------------ the confectionery

    /** Sweets drifting through the far sky, large enough to be read as sweets from an island. */
    private static void drifting(BufferBuilder b, Matrix4f m, double time) {
        Random r = new Random(0x5EE7);
        for (int i = 0; i < 32; i++) {
            double a = r.nextDouble() * Math.PI * 2 + time * (0.00012 + r.nextDouble() * 0.00022);
            double radius = SHELL * (0.42 + r.nextDouble() * 0.36);
            double lift = r.nextDouble() * 210 - 70 + Math.sin(time * 0.006 + i) * 9;
            Vec3 at = DOME.add(Math.cos(a) * radius, lift, Math.sin(a) * radius);
            sweet(b, m, at, 7 + r.nextDouble() * 13, i, time);
        }
    }

    /**
     * One piece of confectionery, at whatever size it is being seen from.
     *
     * <p>Six kinds, all built from the same handful of primitives the rest of the mod draws with,
     * so nothing here needs a texture or a model file. They are solid shapes rather than billboards
     * because a lollipop that turns to face you is a sticker, and these are meant to be objects
     * hanging in the air with the islands.
     */
    static void sweet(BufferBuilder b, Matrix4f m, Vec3 at, double s, int seed, double time) {
        int a = SUGAR[Math.floorMod(seed * 7 + 1, SUGAR.length)], c = SUGAR[Math.floorMod(seed * 5 + 4, SUGAR.length)];
        double bob = Math.sin(time * 0.013 + seed * 1.7) * s * 0.16;
        Vec3 p = at.add(0, bob, 0);
        switch (Math.floorMod(seed, 6)) {
            case 0 -> {   // lollipop: a swirled disc on a paper stick
                WarpMesh.sphere(b, m, p, s, s, s * 0.22, a, 1, 14, 0, false);
                WarpMesh.sphere(b, m, p.add(0, 0, s * 0.14), s * 0.66, s * 0.66, s * 0.17, c, 1, 12, 0, false);
                WarpMesh.sphere(b, m, p.add(0, 0, s * 0.24), s * 0.30, s * 0.30, s * 0.14, a, 1, 10, 0, false);
                WarpMesh.box(b, m, p.x - s * 0.09, p.y - s * 2.6, p.z - s * 0.09, s * 0.18, s * 1.7, s * 0.18, 0xfff6e6, 1);
            }
            case 1 -> {   // a wrapped sweet, pinched and twisted at both ends
                WarpMesh.sphere(b, m, p, s * 0.92, s * 0.66, s * 0.66, a, 1, 12, 0, false);
                for (int side = -1; side <= 1; side += 2) {
                    WarpMesh.box(b, m, p.x + side * s * 0.85 - (side < 0 ? s * 0.5 : 0), p.y - s * 0.30, p.z - s * 0.30,
                        s * 0.5, s * 0.6, s * 0.6, c, 1);
                    WarpMesh.box(b, m, p.x + side * s * 1.35 - (side < 0 ? s * 0.42 : 0), p.y - s * 0.46, p.z - s * 0.46,
                        s * 0.42, s * 0.92, s * 0.92, c, 0.92f);
                }
            }
            case 2 -> {   // a candy cane, striped up its length and hooked over at the top
                Vec3 foot = p.add(0, -s * 1.9, 0);
                for (int i = 0; i < 7; i++) {
                    Vec3 next = foot.add(0, s * 0.54, 0);
                    WarpMesh.ribbon(b, m, foot, next, s * 0.2, i % 2 == 0 ? 0xfffaf4 : 0xff4f62, 1);
                    foot = next;
                }
                for (int i = 0; i < 7; i++) {
                    double t0 = Math.PI + i * Math.PI / 7, t1 = Math.PI + (i + 1) * Math.PI / 7;
                    Vec3 hub = foot.add(s * 0.6, 0, 0);
                    WarpMesh.ribbon(b, m, hub.add(Math.cos(t0) * s * 0.6, Math.sin(t0) * s * 0.6, 0),
                        hub.add(Math.cos(t1) * s * 0.6, Math.sin(t1) * s * 0.6, 0),
                        s * 0.2, i % 2 == 0 ? 0xff4f62 : 0xfffaf4, 1);
                }
            }
            case 3 -> {   // a gumdrop, sugared on top
                WarpMesh.sphere(b, m, p, s * 0.95, s * 0.82, s * 0.95, a, 1, 12, 0, false);
                WarpMesh.sphere(b, m, p.add(0, s * 0.62, 0), s * 0.34, s * 0.24, s * 0.34, 0xffffff, 0.85f, 8, 0, false);
            }
            case 4 -> {   // a sugar star
                for (int i = 0; i < 5; i++) {
                    double t = i * Math.PI * 2 / 5 + 0.35;
                    WarpMesh.ribbon(b, m, p, p.add(Math.cos(t) * s * 1.45, Math.sin(t) * s * 1.45, 0), s * 0.32, a, 1);
                }
                WarpMesh.sphere(b, m, p, s * 0.58, s * 0.58, s * 0.34, c, 1, 10, 0, false);
            }
            default -> {  // a macaron: two shells with the cream showing between them
                WarpMesh.sphere(b, m, p.add(0, s * 0.34, 0), s, s * 0.36, s, a, 1, 12, 0, false);
                WarpMesh.sphere(b, m, p.add(0, -s * 0.34, 0), s, s * 0.36, s, a, 1, 12, 0, false);
                WarpMesh.sphere(b, m, p, s * 0.92, s * 0.26, s * 0.92, c, 1, 10, 0, false);
            }
        }
    }

    // ------------------------------------------------------------------ between the islands

    /**
     * Everything Paradise has that is not a block: the water below each cascade, the mist at its
     * head, and the sweets hanging in the air close enough to jump at.
     *
     * <p>The falls are the reason this exists. Their water is real for the first stretch — a
     * standing column of source blocks that swims and drinks like water — and then it has to stop,
     * because a column of blocks reaching the bottom of the world is a column of blocks nobody
     * will ever see the end of. What carries on below is drawn: narrowing sheets that fade out
     * over the next eighty blocks, so from an island above the water falls away into the void and
     * from the void below it comes out of the sky.
     */
    public static void scene(BufferBuilder b, Matrix4f m, double time, boolean preview) {
        for (Paradise.Fall fall : Paradise.falls()) {
            double headY = fall.y() - fall.length();
            double ax = Math.cos(fall.angle()), az = Math.sin(fall.angle());
            if (fall.onto() < 0) {
                // The drawn continuation, narrowing and fading as it goes.
                for (int i = 0; i < 26; i++) {
                    double y0 = headY - i * 3.1, y1 = y0 - 3.1;
                    float alpha = (float) (0.5 * (1 - i / 26.0) * (1 - i / 26.0));
                    double width = 0.46 * (1 - i / 34.0);
                    // A slight lean, so the water is falling through moving air rather than down a pipe.
                    double drift = i * i * 0.012;
                    Vec3 from = new Vec3(fall.x() + ax * drift, y0, fall.z() + az * drift);
                    Vec3 to = new Vec3(fall.x() + ax * (drift + 0.35), y1, fall.z() + az * (drift + 0.35));
                    WarpMesh.ribbon(b, m, from, to, width, 0xff69c8, alpha);
                    // A narrow white-hot strand gives the sheet a glossy liquid centre without the
                    // old floating puff/sphere particles.
                    if (i % 2 == 0) WarpMesh.ribbon(b, m,
                        from.add(-az * 0.08, 0, ax * 0.08), to.add(-az * 0.08, 0, ax * 0.08),
                        width * 0.24, 0xfffff8, alpha * 0.48f);
                }
            }
            // The old waterfall puffs were disconnected translucent spheres. Replace them with
            // short animated liquid streaks that visibly peel from the stream and fan out on impact.
            for (int i = 0; i < 8; i++) {
                double phase = (time * 0.038 + i * 0.79) % (Math.PI * 2);
                double side = Math.sin(phase * 1.7 + i) * 1.15;
                double lift = 0.7 + Math.abs(Math.sin(phase)) * 1.25;
                Vec3 head = new Vec3(fall.x() + ax * 0.55 - az * side, fall.y() + 0.15,
                    fall.z() + az * 0.55 + ax * side);
                Vec3 headEnd = head.add(ax * (0.45 + (i % 3) * 0.12), lift, az * (0.45 + (i % 3) * 0.12));
                WarpMesh.ribbon(b, m, head, headEnd, 0.065 + (i % 3) * 0.018,
                    (i & 1) == 0 ? 0xffd7f1 : 0xffffff, 0.23f);

                if (fall.onto() >= 0) {
                    double burst = phase * 2.2 + i;
                    Vec3 land = new Vec3(fall.x(), headY + 0.18, fall.z());
                    Vec3 landEnd = land.add(Math.cos(burst) * (1.25 + (i % 3) * 0.35),
                        0.65 + Math.abs(Math.sin(burst)) * 0.85,
                        Math.sin(burst) * (1.25 + (i % 3) * 0.35));
                    WarpMesh.ribbon(b, m, land, landEnd, 0.075 + (i % 2) * 0.025,
                        (i & 1) == 0 ? 0xffb4e5 : 0xffffff, 0.20f);
                }
            }
        }

        // Steam standing over the hot spring, which is the one place in the realm the eye returns to.
        Paradise.Isle heart = Paradise.heart();
        for (int i = 0; i < 16; i++) {
            double a = i * 2.399 + time * 0.0016;
            double reach = Paradise.springRim(a) * (0.25 + (i % 5) * 0.16);
            double rise = ((time * 0.045 + i * 7) % 26);
            WarpMesh.sphere(b, m, new Vec3(heart.x() + Math.cos(a) * reach, Paradise.SURFACE + 1 + rise, heart.z() + Math.sin(a) * reach),
                1.8 + rise * 0.16, 1.1 + rise * 0.1, 1.8 + rise * 0.16, 0xffffff, (float) (0.085 * (1 - rise / 26.0)), 8, 0, false);
        }

        // Sweets close in, drifting between the islands rather than across the far sky.
        Random r = new Random(0x0A11E);
        for (int i = 0; i < 20; i++) {
            double a = r.nextDouble() * Math.PI * 2 + time * 0.00035;
            double radius = 34 + r.nextDouble() * 70;
            double y = Paradise.SURFACE - 26 + r.nextDouble() * 58 + Math.sin(time * 0.009 + i * 2.1) * 3.5;
            sweet(b, m, new Vec3(Math.cos(a) * radius, y, Math.sin(a) * radius), 1.5 + r.nextDouble() * 2.3, i + 3, time);
        }

        // Glitter hanging over the whole composition. Cheap, and it is what sells "dreamlike".
        for (int i = 0; i < 150; i++) {
            double a = i * 2.399, radius = 12 + (i * 13) % 94;
            double y = Paradise.SURFACE - 34 + ((i * 29 + time * 0.24) % 92);
            double size = 0.10 + (i % 5) * 0.045;
            Vec3 p = new Vec3(Math.cos(a) * radius, y, Math.sin(a) * radius);
            float alpha = (float) (0.45 + 0.35 * Math.sin(time * 0.07 + i));
            WarpMesh.box(b, m, p.x, p.y, p.z, size, size, size, i % 4 == 0 ? 0xfff0a8 : i % 3 == 0 ? 0xffb2e0 : 0xffffff, alpha);
        }
    }
}
