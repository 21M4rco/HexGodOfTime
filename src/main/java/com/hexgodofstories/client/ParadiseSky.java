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
        for(int i=0;i<6;i++) {
            double phase=(time+i*153)%940;
            if(phase>48)continue;
            Vec3 at=DOME.add(-190+phase*2.2,150-i*14-phase*.35,-175+i*52);
            float fade=(float)Math.sin(phase/48*Math.PI);
            WarpMesh.ribbon(b,pose.last().pose(),at,at.add(-19,3,0),.15,0xffe7fb,fade);
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static VertexBuffer bake() {
        BufferBuilder b = new BufferBuilder(2 * 1024 * 1024);
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = new Matrix4f();
        Random r = new Random(0x9A11CE);

        // Vertex-coloured, continuous nebula dome: no floating sphere/puff silhouettes.
        final int around = 192, rows = 96;
        for (int row=0; row<rows; row++) for (int col=0; col<around; col++) {
            for (int[] corner : new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
                double longitude=(col+corner[0])*Math.PI*2/around;
                double latitude=(row+corner[1])*Math.PI/rows;
                Vec3 n=new Vec3(Math.sin(latitude)*Math.cos(longitude),Math.cos(latitude),Math.sin(latitude)*Math.sin(longitude));
                double cloud=RealmSky.fbm(n.x*4.6+13,n.y*4.6,n.z*4.6);
                double dust=RealmSky.fbm(n.x*16,n.y*16+9,n.z*16);
                double ribbon=Math.exp(-Math.pow((n.y-.28-Math.sin(longitude*2.0)*.24)*3.2,2));
                double glow=Math.max(0,Math.min(1,(cloud-.26)*1.7*ribbon));
                int base=mix(0xf5a8d6,0x302044,Math.max(0,Math.min(1,(n.y+.18)*1.3)));
                int color=mix(base,0xed58c6,glow);
                color=mix(color,0xffc4f0,Math.max(0,(dust-.52)*glow*2.8));
                WarpMesh.vertex(b,m,DOME.add(n.scale(SHELL)),color,1);
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

        // A large face-on spiral over the castle, plus three smaller distant galaxies.
        galaxy(b,m,DOME.add(40,148,-225),66,0x91A7);
        galaxy(b,m,DOME.add(-215,132,90),30,0x711B);
        galaxy(b,m,DOME.add(180,184,132),35,0x432A);
        galaxy(b,m,DOME.add(-100,223,-120),22,0x854F);

        // The bows. Five of them, at five orientations, two of them carrying a second.
        bow(b, m, new Vec3(0.20, 0.95, 0.24), 196, -0.95, 0.95, 3.0, 0.62f, true);
        bow(b, m, new Vec3(-0.78, 0.55, 0.30), 168, -1.25, 0.72, 2.4, 0.50f, false);
        bow(b, m, new Vec3(0.62, 0.42, -0.66), 212, -0.80, 1.15, 2.8, 0.44f, true);
        bow(b, m, new Vec3(-0.25, 0.30, -0.92), 150, -1.05, 0.45, 2.1, 0.38f, false);
        bow(b, m, new Vec3(0.88, 0.22, 0.42), 240, -0.55, 0.60, 3.4, 0.30f, false);

        VertexBuffer mesh = new VertexBuffer(VertexBuffer.Usage.STATIC);
        mesh.bind();
        mesh.upload(b.end());
        VertexBuffer.unbind();
        return mesh;
    }

    private static int mix(int a,int c,double f) {
        f=Math.max(0,Math.min(1,f));
        return ((int)((a>>16&255)*(1-f)+(c>>16&255)*f)<<16)
            |((int)((a>>8&255)*(1-f)+(c>>8&255)*f)<<8)
            |(int)((a&255)*(1-f)+(c&255)*f);
    }

    private static void galaxy(BufferBuilder b,Matrix4f m,Vec3 centre,double radius,long seed) {
        Random r=new Random(seed);
        Vec3 normal=DOME.subtract(centre).normalize();
        Vec3 right=normal.cross(new Vec3(0,1,0)).normalize(), up=right.cross(normal).normalize();
        for(int i=0;i<7200;i++) {
            double t=Math.pow(r.nextDouble(),.68), arm=i%4*Math.PI*.5;
            double angle=arm+t*6.4+r.nextGaussian()*(.085+t*.1);
            double reach=t*radius+r.nextGaussian()*radius*.022;
            Vec3 at=centre.add(right.scale(Math.cos(angle)*reach)).add(up.scale(Math.sin(angle)*reach*.63));
            double size=.14+r.nextDouble()*.42+(1-t)*.34;
            int color=t<.17?0xfff5dc:i%3==0?0xd9b4ff:0xff8cd7;
            float alpha=(float)(.25+(1-t)*.4);
            WarpMesh.quad(b,m,at.subtract(right.scale(size)).subtract(up.scale(size)),
                at.add(right.scale(size)).subtract(up.scale(size)),at.add(right.scale(size)).add(up.scale(size)),
                at.subtract(right.scale(size)).add(up.scale(size)),color,alpha);
        }
        // Soft concentric core, all on the same plane facing the observer.
        for(int ring=14;ring>=1;ring--) {
            double outer=radius*.17*ring/14.0;
            for(int k=0;k<48;k++) {
                double a=k*Math.PI/24,c=(k+1)*Math.PI/24;
                WarpMesh.quad(b,m,centre,centre.add(right.scale(Math.cos(a)*outer)).add(up.scale(Math.sin(a)*outer*.65)),
                    centre.add(right.scale(Math.cos(c)*outer)).add(up.scale(Math.sin(c)*outer*.65)),centre,
                    ring<5?0xffffe8:0xffb8e8,.075f);
            }
        }
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
        // Keep the one unmistakable candy-planet motif, then use a small hand-composed set of
        // readable sweets. No procedural "mystery satellites" or winged orange blobs.
        Vec3 planet=DOME.add(-175,142,-136);
        WarpMesh.sphere(b,m,planet,22,22,22,0xe6a0db,1,32,0,false);
        WarpMesh.ring(b,m,planet,29,5,0xffd5f0,.70f,.35,time*.0004);
        WarpMesh.ring(b,m,planet,36,1.2,0xc28eed,.65f,.35,time*.0004);

        double[][] candy={
            {.32,214,94,7.5,.00013,0},{1.10,230,38,6.0,-.00010,1},
            {1.92,205,132,8.0,.00011,2},{2.72,238,70,6.6,-.00014,3},
            {3.55,218,18,7.2,.00009,4},{4.32,226,116,5.8,-.00012,5},
            {5.06,208,58,7.8,.00010,6},{5.72,236,148,6.2,-.00008,7}
        };
        for(int i=0;i<candy.length;i++) {
            double[] c=candy[i];
            double a=c[0]+time*c[4];
            Vec3 at=DOME.add(Math.cos(a)*c[1],c[2]-DOME.y,Math.sin(a)*c[1]);
            sweet(b,m,at,c[3],i,time);
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
        switch (Math.floorMod(seed, 4)) {
            case 0 -> {   // unmistakable swirled lollipop
                WarpMesh.sphere(b, m, p, s, s, s * 0.20, a, 1, 16, 0, false);
                WarpMesh.sphere(b, m, p.add(0, 0, s * 0.13), s * 0.66, s * 0.66, s * 0.16, 0xfff7f0, 1, 14, 0, false);
                WarpMesh.sphere(b, m, p.add(0, 0, s * 0.23), s * 0.31, s * 0.31, s * 0.12, c, 1, 12, 0, false);
                WarpMesh.box(b, m, p.x - s * 0.08, p.y - s * 2.55, p.z - s * 0.08, s * 0.16, s * 1.65, s * 0.16, 0xfff6e6, 1);
            }
            case 1 -> {   // candy cane: tall striped stem and a clean hook
                Vec3 foot = p.add(0, -s * 1.9, 0);
                for (int i = 0; i < 8; i++) {
                    Vec3 next = foot.add(0, s * 0.48, 0);
                    WarpMesh.ribbon(b, m, foot, next, s * 0.19, i % 2 == 0 ? 0xfffaf4 : 0xff4f62, 1);
                    foot = next;
                }
                for (int i = 0; i < 8; i++) {
                    double t0 = Math.PI + i * Math.PI / 8, t1 = Math.PI + (i + 1) * Math.PI / 8;
                    Vec3 hub = foot.add(s * 0.58, 0, 0);
                    WarpMesh.ribbon(b, m, hub.add(Math.cos(t0) * s * 0.58, Math.sin(t0) * s * 0.58, 0),
                        hub.add(Math.cos(t1) * s * 0.58, Math.sin(t1) * s * 0.58, 0),
                        s * 0.19, i % 2 == 0 ? 0xff4f62 : 0xfffaf4, 1);
                }
            }
            case 2 -> {   // donut / candy ring, deliberately simple from a distance
                WarpMesh.ring(b,m,p,s*.82,s*.25,a,1,.18,time*.00025+seed*.4);
                WarpMesh.ring(b,m,p,s*.52,s*.08,0xfff1dc,.82f,.18,time*.00025+seed*.4);
            }
            default -> {  // macaron, two obvious shells and a cream layer
                WarpMesh.sphere(b, m, p.add(0, s * 0.34, 0), s, s * 0.36, s, a, 1, 14, 0, false);
                WarpMesh.sphere(b, m, p.add(0, -s * 0.34, 0), s, s * 0.36, s, a, 1, 14, 0, false);
                WarpMesh.sphere(b, m, p, s * 0.92, s * 0.22, s * 0.92, 0xfff6ec, 1, 12, 0, false);
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
            double ax=Math.cos(fall.angle()),az=Math.sin(fall.angle());
            double length=fall.length()+20;
            // One broad translucent ribbon laid directly over the real water. It widens the
            // Minecraft sheet visually and fades into the cloud sea instead of ending as a rod.
            for(int i=0;i<56;i++) {
                double down=i*length/56,next=(i+1)*length/56;
                double fade=Math.min(1,(length-down)/18);
                double width=3.35-down/length*.85;
                double sway=Math.sin(time*.018-down*.08)*.10;
                Vec3 from=new Vec3(fall.x()+ax*.35+ax*sway,fall.y()+.65-down,fall.z()+az*.35+az*sway);
                Vec3 to=new Vec3(fall.x()+ax*.35+ax*Math.sin(time*.018-next*.08)*.10,fall.y()+.65-next,
                    fall.z()+az*.35+az*Math.sin(time*.018-next*.08)*.10);
                Vec3 side=new Vec3(-az*width,0,ax*width);
                WarpMesh.quad(b,m,from.subtract(side),from.add(side),to.add(side.scale(.99)),to.subtract(side.scale(.99)),
                    i%4==0?0xffa1e1:0xf66bc8,(float)(.36*fade));
            }
            // Fine mist at the lip and the cloud-end only; no long strings of waterfall particles.
            for(int mist=0;mist<9;mist++) {
                double side=(mist-4)*.55;
                double pulse=.45+.20*Math.sin(time*.025+mist);
                Vec3 top=new Vec3(fall.x()-az*side,fall.y()+.4+Math.sin(mist)*.22,fall.z()+ax*side);
                WarpMesh.sphere(b,m,top,1.15+pulse,.22,1.15+pulse,0xffd8f3,.13f,8,0,false);
                double age=(time*.018+mist*.127)%1;
                Vec3 foot=new Vec3(fall.x()-az*side*(1-age*.35),fall.y()-length+age*3.5,
                    fall.z()+ax*side*(1-age*.35));
                WarpMesh.sphere(b,m,foot,1.35+age*2.2,.28,1.35+age*2.2,0xffc9ed,(float)((1-age)*.14),8,0,false);
            }
        }
        // Low cloud banks under the archipelago, leaving the bridges and castle unobstructed.
        for(int i=0;i<32;i++) {
            double angle=i*2.399, radius=45+i%8*12;
            Vec3 at=new Vec3(Math.cos(angle)*radius,39+Math.sin(i*1.7+time*.002)*4,Math.sin(angle)*radius);
            WarpMesh.sphere(b,m,at,23,5,18,i%2==0?0xfbc9ec:0xe4b0e7,.16f,12,0,false);
        }

        // A few close, readable sweets. Their locations are composed rather than randomized so
        // the skyline stays clean and the objects never turn into unexplained clutter.
        int[] closeTypes={0,1,3,2,0,3,1,2};
        for (int i = 0; i < closeTypes.length; i++) {
            double a = i * 2.399 + .35 + time * (i%2==0?.00022:-.00018);
            double radius = 100 + (i%4) * 9;
            double y = Paradise.SURFACE - 8 + (i%5) * 11 + Math.sin(time * 0.008 + i * 1.7) * 2.2;
            sweet(b, m, new Vec3(Math.cos(a) * radius, y, Math.sin(a) * radius), 1.8 + (i%3)*.45, closeTypes[i], time);
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
