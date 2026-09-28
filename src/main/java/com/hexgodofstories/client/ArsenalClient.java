package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.ArsenalLayout;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Crown of Barrels, seen and heard: the guns forming in their arch over the caster and swinging onto the mark,
 * every round they fire, the brass they throw, the two missiles forming beside the head and flying their crooked
 * way to the mark, and the blast where they land.
 *
 * <p>The server says only when a hold began and how it ended, and sends each missile once when it leaves and once
 * where it bursts. Everything else is drawn from {@link ArsenalLayout}'s numbers, the same the server fires by: which
 * gun has formed how far, when each one fires, where every muzzle is, the path each missile flies. So a crown costs
 * the network nothing while it fires, and every client draws the same crown.
 *
 * <p>Drawn, not spawned. A gun, a casing and a missile are TACZ's models on the GPU ({@link ArsenalMeshes}), each a
 * single draw with {@link ArsenalShaders}' shader, which gives them their forming edge; flashes, tracers, flames and
 * rings are a few quads each in one light pass. Seen from its own caster in first person, the arch hangs in front of
 * the eye instead, along the top of the view, where the caster can watch it form and fire; everyone else, and the
 * caster in third person, sees it over the caster's shoulders.
 *
 * <p>Bounded everywhere: a few hundred tracers, a hundred casings, one shot sound a tick for a whole crown and only
 * so many ringing at once, a handful of landing rounds shown a tick, and one look along the mark a tick per crown.
 */
public final class ArsenalClient {
    private ArsenalClient() { }

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far the crown looks for its mark: the server's own reach. */
    private static final double REACH = 128;
    /** Ticks the guns trail the mark by as it moves: enough to give them weight, too few to miss by. */
    private static final double AIM_LAG = 1.2;
    /** A round in flight: its speed and the length of its streak, in blocks a tick and blocks. */
    private static final double TRACER_SPEED = 16, TRACER_TRAIL = 4.5;
    private static final int MOST_TRACERS = 320;
    /** Every how many rounds a gun throws a casing, the ticks one lasts, and how many may be about at once. */
    private static final int CASING_EVERY = 3, CASING_LIFE = 24, MOST_CASINGS = 96;
    /** Model units to blocks for a casing: a shade over life size, so that a stream of them reads. */
    private static final float CASING_SCALE = (float) (ArsenalLayout.GUN_SCALE * 1.6);
    /** Rounds seen to land in a tick, at most, over every crown: dust, a spark or blood for each. */
    private static final int MOST_LANDINGS = 6;
    /** Shot sounds ringing at once, at most, over every crown: each is a channel of the sound engine's while it rings. */
    private static final int MOST_SHOTS = 28;
    /** How far off a crown is drawn at all; and within which its casings and the finer parts of its flashes are. */
    private static final double DRAW_RANGE = 192, DETAIL_RANGE = 48;
    /**
     * The two missiles forming beside the head, seen in first person: where (in the camera's frame, x out to their
     * own side), which way they point, and how big; and which way they point over the shoulders in third person.
     */
    private static final double[] FIRST_MISSILE = {.72, .16, .95}, FIRST_MISSILE_LEAN = {.30, 1, .35}, MISSILE_LEAN = {.40, 1, .30};
    private static final double FIRST_MISSILE_SCALE = .45;
    /** Ticks a missile takes to settle from where it was seen forming onto its own path. */
    private static final int SETTLE = 10;
    /** Ticks a blast's rings take to spread and fade. */
    private static final int WAVE_LIFE = 14;
    /** The glow along a part's silhouette, as colour and strength: red on a gun, fire-orange on a missile, none on a casing. */
    private static final float[] GUN_RIM = {1, .2f, .25f, .3f}, MISSILE_RIM = {1, .5f, .22f, .25f}, NO_RIM = {0, 0, 0, 0};
    private static final ResourceLocation FLASH = HexGodOfStories.id("textures/arsenal/muzzle_flash.png"),
        FLARE = HexGodOfStories.id("textures/particle/scepter_flare.png");

    // ------------------------------------------------------------------ state

    private static final class Crown {
        final int caster;
        final long start;
        /** The tick the hold ended, and how ("cancel" or "launched"); -1 while it is held. */
        long end = -1;
        String how = "";
        /** Rounds each gun has been seen to fire, and the last tick of the hold whose sounds have been played. */
        final long[] counted = new long[ArsenalLayout.GUNS];
        int cued;
        /** The mark as the latest tick found it: how far along the look, and what it is (0 nothing, 1 a block, 2 a body). */
        double reach = 32;
        int kind;
        BlockState block;
        Direction face = Direction.UP;
        /** The body the mark is on, as the rounds meet it; null when it is on none. */
        AABB box;
        long lastHit = Long.MIN_VALUE / 2;
        /** The point the guns aim at, trailing the mark, and the render time it was last moved at. */
        Vec3 aim;
        double aimedAt;
        /** This frame's guns and forming missiles, as drawn: for the light pass, the sparks and the launch. */
        final Gun[] guns = new Gun[ArsenalLayout.GUNS];
        final Rocket[] heads = new Rocket[2];
        long drawn = Long.MIN_VALUE / 2;

        Crown(int caster, long start, long now) {
            this.caster = caster;
            this.start = start;
            // Seen late (come into view mid-hold): nothing already past is played or fired again.
            cued = (int) (now - start);
            for (int g = 0; g < ArsenalLayout.GUNS; g++) counted[g] = (long) Math.floor(ArsenalLayout.rounds(g, now - start));
        }

        boolean held() {return end < 0;}
        boolean cancelled() {return end >= 0 && how.equals("cancel");}
        /** The tick of the hold it ended at, or never. */
        double stopped() {return end < 0 ? Double.MAX_VALUE : end - start;}
    }

    /** One gun as this frame draws it: its middle, its axes, its muzzle, its size, how much of it there is, its flash. */
    private record Gun(Vec3 middle, Vec3 right, Vec3 up, Vec3 forward, Vec3 muzzle, float scale, float reveal, float flash,
                       int type, long round) { }

    /** A missile as drawn: its middle, the way its nose points, its right and top, its size and how much of it there is. */
    private record Rocket(Vec3 middle, Vec3 forward, Vec3 right, Vec3 up, float scale, float reveal) { }

    /**
     * A crown's frame in the world: its origin and its right, up and forward, left-handed as ArsenalLayout's are; in
     * first person the camera's, from the eye, and {@code k} the spread that keeps the arch where it is on the screen
     * whatever the field of view.
     */
    private record Frame(Vec3 origin, Vec3 right, Vec3 up, Vec3 forward, boolean first, double k) {
        Vec3 axis(double[] v) {
            return new Vec3(right.x * v[0] + up.x * v[1] + forward.x * v[2], right.y * v[0] + up.y * v[1] + forward.y * v[2],
                right.z * v[0] + up.z * v[1] + forward.z * v[2]);
        }

        Vec3 point(double[] v) {return origin.add(axis(v));}

        double[] local(Vec3 w) {
            Vec3 d = w.subtract(origin);
            return new double[]{d.dot(right), d.dot(up), d.dot(forward)};
        }

        double[] slot(int gun) {
            if (!first) return ArsenalLayout.slot(gun);
            double[] s = ArsenalLayout.firstPersonSlot(gun);
            return new double[]{s[0] * k, s[1] * k, s[2]};
        }

        float scale() {return (float) (first ? ArsenalLayout.FIRST_PERSON_SCALE * k : ArsenalLayout.GUN_SCALE);}
    }

    private static final class Missile {
        final int id, caster, side;
        final ArsenalLayout.Flight flight;
        final long launch;
        final Vec3 start;
        /** How it was last seen forming, if that was somewhere else than where it starts: it settles from there. */
        final Rocket seen;
        boolean gone;
        /** Where its trail was last laid. */
        Vec3 trail;
        FlightSound sound;

        Missile(int id, int caster, int side, ArsenalLayout.Flight flight, long launch, Rocket seen) {
            this.id = id;
            this.caster = caster;
            this.side = side;
            this.flight = flight;
            this.launch = launch;
            this.start = vec(flight.at(0));
            this.seen = seen;
        }

        /** Where it is and which way it points, {@code ticks} after it left. */
        Rocket pose(double ticks) {
            ticks = Math.max(0, ticks);
            Vec3 at = vec(flight.at(ticks)), heading = vec(flight.heading(ticks));
            float scale = (float) ArsenalLayout.MISSILE_SCALE;
            if (seen != null && ticks < SETTLE) {
                double settle = smooth(ticks / SETTLE);
                at = at.add(seen.middle().subtract(start).scale(1 - settle));
                Vec3 blend = seen.forward().scale(1 - settle).add(heading.scale(settle));
                if (blend.lengthSqr() > 1e-6) heading = blend.normalize();
                scale = (float) (seen.scale() + (scale - seen.scale()) * settle);
            }
            // Spinning on its own length as it goes, as a rocket does, each the other way.
            Vec3[] axes = axes(heading, ticks * .14 * side);
            return new Rocket(at, heading, axes[0], axes[1], scale, 1);
        }
    }

    private static final class Casing {
        final int type;
        final float scale;
        final Vec3 right, up, forward, axis;
        final double spin;
        Vec3 at, last, velocity;
        int age;

        Casing(int type, float scale, Vec3 at, Vec3 velocity, Vec3 right, Vec3 up, Vec3 forward, Vec3 axis, double spin) {
            this.type = type;
            this.scale = scale;
            this.at = this.last = at;
            this.velocity = velocity;
            this.right = right;
            this.up = up;
            this.forward = forward;
            this.axis = axis;
            this.spin = spin;
        }
    }

    private static final class Tracer {
        final Vec3 from, direction;
        final double length, born;
        final boolean bright;
        final int kind;
        final BlockState block;
        final Direction face;
        boolean landed;

        Tracer(Vec3 from, Vec3 direction, double length, double born, boolean bright, int kind, BlockState block, Direction face) {
            this.from = from;
            this.direction = direction;
            this.length = length;
            this.born = born;
            this.bright = bright;
            this.kind = kind;
            this.block = block;
            this.face = face;
        }
    }

    /** A blast's rings: a missile's are fire-coloured, an Anchor copy's green. */
    private record Wave(Vec3 at, long born, boolean green, double scale) { }

    /** Gotcha!'s gun: where it hangs, the body it is for, where that body's head was when it began, where it points now, and this frame's gun. */
    private static final class Sneak {
        final int target;
        final long start;
        final Vec3 at, from;
        Vec3 aim;
        Gun gun;
        long drawn = Long.MIN_VALUE / 2;
        boolean fired;

        Sneak(int target, long start, Vec3 at, Vec3 aim) {
            this.target = target;
            this.start = start;
            this.at = at;
            this.from = aim;
            this.aim = aim;
        }
    }

    private static final Map<Integer, Crown> CROWNS = new HashMap<>();
    private static final Map<Integer, Missile> MISSILES = new HashMap<>();
    private static final List<Tracer> TRACERS = new ArrayList<>();
    private static final List<Casing> CASINGS = new ArrayList<>();
    private static final List<Wave> WAVES = new ArrayList<>();
    private static final List<SoundInstance> SHOTS = new ArrayList<>();
    private static final Map<Integer, Sneak> SNEAKS = new HashMap<>();

    /**
     * A missile's blast shakes the ground forty blocks across: a wave runs out through it from the crater's edge at
     * {@link #QUAKE_SPEED} blocks a tick, throwing each block of ground up to {@link #QUAKE_THROW} of a block and
     * letting it settle over {@link #QUAKE_BUMP} ticks, the least of it at the far edge, and kicking up dust.
     */
    private static final double QUAKE_REACH = 20, QUAKE_INNER = 5.5, QUAKE_SPEED = 1.25, QUAKE_THROW = .55;
    private static final int QUAKE_BUMP = 4;
    /** Blocks of ground drawn thrown up in a frame, at most, over every blast; and blasts shaking the ground at once. */
    private static final int MOST_THROWN = 500, MOST_QUAKES = 4;
    /** Beyond this from the eye a blast's ground is not read or drawn shaking at all. */
    private static final double QUAKE_SEEN = 96;

    /** A block of ground a blast's wave passes under: where, what it was, how far out, and whether it throws up dust. */
    private record Column(BlockPos pos, BlockState state, double reach, boolean dust) { }

    private static final class Quake {
        final Vec3 at;
        final long born;
        final List<Column> columns;
        final double reach;
        /** Whether this client's own player has been reached by it yet. */
        boolean felt;

        Quake(Vec3 at, long born, List<Column> columns, double reach) {
            this.reach = reach;
            this.at = at;
            this.born = born;
            this.columns = columns;
        }
    }

    private static final List<Quake> QUAKES = new ArrayList<>();
    /** The ground's own buffer, so drawing it never flushes or disturbs what the world has waiting in its own. */
    private static final BufferBuilder GROUND = new BufferBuilder(1 << 16);
    /** Frames drawn, counted by the solid pass: what "drawn this frame" is measured against. */
    private static long frame;
    private static boolean failed;

    public static void clear() {
        var manager = Minecraft.getInstance().getSoundManager();
        for (Missile m : MISSILES.values()) if (m.sound != null) manager.stop(m.sound);
        CROWNS.clear();
        MISSILES.clear();
        TRACERS.clear();
        CASINGS.clear();
        WAVES.clear();
        SHOTS.clear();
        SNEAKS.clear();
        QUAKES.clear();
        ArsenalFx.clear();
    }

    /** Holding the crown up: the caster walks slowly and keeps their feet on the ground meanwhile. */
    public static boolean channeling(Entity e) {
        Crown c = e == null ? null : CROWNS.get(e.getId());
        return c != null && c.held();
    }

    // ------------------------------------------------------------------ the server's word

    public static void receive(int entity, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        switch (data.getString("state")) {
            case "begin" -> {
                ArsenalMeshes.preload(FLASH, FLARE);
                CROWNS.put(entity, new Crown(entity, data.getLong("start"), ClientState.now()));
            }
            case "cancel", "launched" -> {
                Crown c = CROWNS.get(entity);
                if (c != null && c.held() && c.start == data.getLong("start")) ended(c, data.getLong("at"), data.getString("state"));
            }
            case "launch" -> launch(entity, data);
            case "impact" -> impact(data);
            case "anchor_burst" -> anchorBurst(data);
            case "anchor_flash" -> anchorFlash(data);
            case "gotcha" -> {
                ArsenalMeshes.preload(FLASH, FLARE);
                SNEAKS.put(data.getInt("id"), new Sneak(data.getInt("target"), data.getLong("start"),
                    new Vec3(data.getDouble("x"), data.getDouble("y"), data.getDouble("z")),
                    new Vec3(data.getDouble("tx"), data.getDouble("ty"), data.getDouble("tz"))));
                while (SNEAKS.size() > 32) SNEAKS.remove(SNEAKS.keySet().iterator().next());
            }
            default -> { }
        }
    }

    private static void ended(Crown c, long at, String how) {
        c.end = at;
        c.how = how;
        // Let go early: whatever had formed comes apart with a turn of metal.
        Entity caster = Minecraft.getInstance().level.getEntity(c.caster);
        if (c.cancelled() && caster != null && at - c.start > ArsenalLayout.FIRST_GUN && at - c.start < ArsenalLayout.FIRE_END)
            play(HexGodOfStories.ARSENAL_FORM.get(), caster.getEyePosition(), .45f, .74f);
    }

    private static void launch(int caster, CompoundTag n) {
        Minecraft mc = Minecraft.getInstance();
        if (!(n.get("missiles") instanceof ListTag list)) return;
        long at = n.getLong("at");
        Crown crown = CROWNS.get(caster);
        Vec3 heard = null;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag m = list.getCompound(i);
            int side = m.getInt("side") < 0 ? -1 : 1;
            ArsenalLayout.Flight flight = new ArsenalLayout.Flight(
                new double[]{m.getDouble("sx"), m.getDouble("sy"), m.getDouble("sz")},
                new double[]{m.getDouble("tx"), m.getDouble("ty"), m.getDouble("tz")},
                new double[]{m.getDouble("rx"), m.getDouble("ry"), m.getDouble("rz")}, side, m.getLong("seed"));
            Rocket seen = crown != null && frame - crown.drawn <= 2 ? crown.heads[side < 0 ? 0 : 1] : null;
            Missile missile = new Missile(m.getInt("id"), caster, side, flight, at, seen);
            Missile old = MISSILES.put(missile.id, missile);
            if (old != null && old.sound != null) old.gone = true;
            missile.sound = new FlightSound(missile);
            mc.getSoundManager().play(missile.sound);
            Rocket r = missile.pose(0);
            ArsenalFx.backblast(tail(r), r.forward().scale(-1));
            heard = missile.start;
        }
        if (heard != null) stereo(HexGodOfStories.ARSENAL_MISSILE_LAUNCH.get(), heard, 1, .96f + mc.level.random.nextFloat() * .08f, 96);
    }

    /** A grand Anchor copy's flash, whiting out the view of whoever was looking at it; fades out over a second. */
    private static float flash;

    /** Anchor Being's copy bursting: the missile's blast in green, rings, the ground shaking, the boom. Breaks nothing. */
    private static void anchorBurst(CompoundTag n) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        boolean grand = n.getBoolean("grand");
        double size = grand ? 30 : 9;
        Vec3 at = new Vec3(n.getDouble("x"), n.getDouble("y"), n.getDouble("z")), core = at.add(0, 1, 0);
        // A slow green blast that swells, rolls and smokes: a small nuke for a grand copy.
        ArsenalFx.anchorBlast(at.add(0, .6, 0), grand ? 3.2f : 1.6f);
        // The boom, and a second, deeper one rolling in after it.
        mc.getSoundManager().playDelayed(new SimpleSoundInstance(HexGodOfStories.ARSENAL_EXPLOSION_FAR.get(), SoundSource.PLAYERS, grand ? 8 : 3,
            grand ? .45f : .55f, SoundInstance.createUnseededRandom(), at.x, at.y, at.z), grand ? 16 : 10);
        quake(at, level, grand ? 30 : QUAKE_REACH);
        WAVES.add(new Wave(at, ClientState.now(), true, grand ? 3.2 : 1.4));
        while (WAVES.size() > 8) WAVES.remove(0);
        play(HexGodOfStories.ARSENAL_EXPLOSION.get(), at, grand ? 8 : 2, grand ? .7f : .82f);
        play(HexGodOfStories.ARSENAL_EXPLOSION_FAR.get(), at, grand ? 8 : 2, grand ? .66f : .78f);
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at), felt = grand ? 110 : 48;
        if (d < felt) {
            com.hexgodofstories.client.leviathan.LeviathanEffects.scepterRecoil((float) ((grand ? 2.6 : 1.5) * (1 - d / felt) * (1 - d / felt)));
            com.hexgodofstories.client.leviathan.LeviathanEffects.quake((float) ((grand ? 2.2 : 1.1) * (1 - d / felt)));
        }
    }

    /** A grand copy about to burst: anyone looking toward it, with nothing in between, is blinded white. */
    private static void anchorFlash(CompoundTag n) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition(), at = new Vec3(n.getDouble("x"), n.getDouble("y"), n.getDouble("z")), to = at.subtract(eye);
        double d = to.length();
        if (d > 160) return;
        if (d > 3) {
            Vector3f look = camera.getLookVector();
            if (new Vec3(look.x(), look.y(), look.z()).dot(to.scale(1 / d)) < .35) return;
            if (mc.level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS) return;
        }
        flash = 1;
    }

    /** The flash, over everything on screen. */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = HexGodOfStories.ID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class Overlay {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void gui(net.minecraftforge.client.event.RenderGuiEvent.Post e) {
            if (flash <= .01f) return;
            var g = e.getGuiGraphics();
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (Mth.clamp(flash, 0, 1) * 255) << 24 | 0xF2FFF2);
        }
    }

    private static void impact(CompoundTag n) {
        Minecraft mc = Minecraft.getInstance();
        Missile m = MISSILES.remove(n.getInt("id"));
        if (m != null) m.gone = true;
        Vec3 at = new Vec3(n.getDouble("x"), n.getDouble("y"), n.getDouble("z"));
        ArsenalFx.explode(at);
        quake(at, mc.level, QUAKE_REACH);
        WAVES.add(new Wave(at, ClientState.now(), false, 1));
        while (WAVES.size() > 8) WAVES.remove(0);
        float pitch = .92f + mc.level.random.nextFloat() * .1f;
        play(HexGodOfStories.ARSENAL_EXPLOSION.get(), at, 1, pitch);
        play(HexGodOfStories.ARSENAL_EXPLOSION_FAR.get(), at, 1, pitch * .92f);
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        if (d < 56) com.hexgodofstories.client.leviathan.LeviathanEffects.scepterRecoil((float) (1.7 * (1 - d / 56) * (1 - d / 56)));
    }

    // ------------------------------------------------------------------ every tick

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {clear(); return;}
        long now = ClientState.now();
        if (now % 10 == 0) adopt(level, now);
        var manager = mc.getSoundManager();
        SHOTS.removeIf(s -> !manager.isActive(s));
        for (Iterator<Crown> it = CROWNS.values().iterator(); it.hasNext(); ) {
            Crown c = it.next();
            Entity caster = level.getEntity(c.caster);
            if (caster == null || finished(c, now)) {it.remove(); continue;}
            reconcile(c);
            int t = (int) (now - c.start);
            if (c.held() && t >= ArsenalLayout.FIRE_START - 16 && t < ArsenalLayout.FIRE_END) mark(c, caster, level);
            for (int tick = Math.max(c.cued + 1, t - 8); tick <= t; tick++) cue(c, caster, tick);
            c.cued = Math.max(c.cued, t);
            if (frame - c.drawn <= 3) {
                sparks(c, level.random);
                smoke(c, t, level);
            }
        }
        for (Iterator<Sneak> it = SNEAKS.values().iterator(); it.hasNext(); ) {
            Sneak s = it.next();
            double t = now - s.start;
            if (t > ArsenalLayout.SNEAK_GONE + ArsenalLayout.UNFORM + 2 || t < -40) {it.remove(); continue;}
            if (!s.fired && t >= ArsenalLayout.SNEAK_FIRE) {s.fired = true; gotcha(s, level);}
            if (frame - s.drawn <= 3 && s.gun != null && s.gun.reveal() < .99f && level.random.nextFloat() < .55f) {
                ArsenalMeshes.Mesh mesh = ArsenalMeshes.get(ArsenalMeshes.GUNS[s.gun.type()]);
                if (mesh != null) spark(mesh, s.gun.middle(), s.gun.right(), s.gun.up(), s.gun.forward(), s.gun.scale(), s.gun.reveal(), level.random);
            }
        }
        flash = Math.max(0, flash - .05f);
        for (Iterator<Quake> it = QUAKES.iterator(); it.hasNext(); ) {
            Quake q = it.next();
            long age = now - q.born;
            if (age > q.reach / QUAKE_SPEED + QUAKE_BUMP + 2 || age < -20) {it.remove(); continue;}
            rumble(q, age, level);
        }
        for (Missile m : MISSILES.values()) trail(m, now, level.random);
        MISSILES.values().removeIf(m -> {
            if (now - m.launch <= m.flight.duration + 60) return false;
            m.gone = true;
            return true;
        });
        casings(level);
        int landings = MOST_LANDINGS;
        for (Tracer tr : TRACERS) {
            if (tr.landed || now < tr.born + tr.length / TRACER_SPEED) continue;
            tr.landed = true;
            if (landings-- > 0) land(tr, level);
        }
        TRACERS.removeIf(tr -> now > tr.born + (tr.length + TRACER_TRAIL) / TRACER_SPEED + 1);
        WAVES.removeIf(w -> now - w.born() > WAVE_LIFE);
    }

    /** A crown whose begin was never seen here (its caster came into view mid-hold) is taken from their synced data. */
    private static void adopt(ClientLevel level, long now) {
        for (Player p : level.players()) {
            if (CROWNS.containsKey(p.getId())) continue;
            CompoundTag d = ClientState.data(p.getId());
            long start = d.getLong("arsenalStart");
            if (start <= 0 || d.getLong("arsenalEnd") >= start || now < start || now - start >= ArsenalLayout.LAUNCH) continue;
            ArsenalMeshes.preload(FLASH, FLARE);
            CROWNS.put(p.getId(), new Crown(p.getId(), start, now));
        }
    }

    /** The synced data says this hold ended, though its own word never came. */
    private static void reconcile(Crown c) {
        if (!c.held()) return;
        CompoundTag d = ClientState.data(c.caster);
        if (d.getLong("arsenalStart") == c.start && d.getLong("arsenalEnd") >= c.start) {
            String how = d.getString("arsenalEnding");
            ended(c, d.getLong("arsenalEnd"), how.equals("launched") ? how : "cancel");
        }
    }

    private static boolean finished(Crown c, long now) {
        // Never told it ended: let it go once it could not still be running.
        if (c.held()) return now - c.start > ArsenalLayout.LAUNCH + 40;
        return now - c.end > ArsenalLayout.UNFORM + 4;
    }

    /** Where the look meets a body or a block, as the server finds its mark: once a tick, along the look. */
    private static void mark(Crown c, Entity caster, ClientLevel level) {
        Vec3 eye = caster.getEyePosition(), look = caster.getViewVector(1);
        Vec3 end = eye.add(look.scale(REACH));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean wall = block.getType() != HitResult.Type.MISS;
        Vec3 stop = wall ? block.getLocation() : end;
        int kind = wall ? 1 : 0;
        double nearest = eye.distanceToSqr(stop);
        AABB along = new AABB(eye, stop).inflate(1), box = null;
        for (Entity body : level.getEntities(caster, along, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            Optional<Vec3> hit = body.getBoundingBox().inflate(.3).clip(eye, stop);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < nearest) {
                nearest = eye.distanceToSqr(hit.get());
                kind = 2;
                box = body.getBoundingBox().inflate(.1);
            }
        }
        c.reach = Math.max(1, Math.sqrt(nearest));
        c.kind = kind;
        c.box = box;
        c.block = kind == 1 ? level.getBlockState(block.getBlockPos()) : null;
        c.face = block.getDirection();
    }

    /** What is heard at {@code tick} of a hold: each ring of guns forming, the guns readied, the firing, the finale. */
    private static void cue(Crown c, Entity caster, int tick) {
        if (tick > c.stopped()) return;
        Vec3 at = caster.getEyePosition();
        for (int ring = 0; ring <= ArsenalLayout.GUNS / 2; ring++)
            if (tick == ArsenalLayout.FIRST_GUN + ring * ArsenalLayout.STAGGER) play(HexGodOfStories.ARSENAL_FORM.get(), at, .42f, 1.08f - .03f * ring);
        int fire = ArsenalLayout.FIRE_START;
        // Every gun formed: their charging handles, pulled and let go, a beat before the first round.
        if (tick == fire - 11) play(HexGodOfStories.ARSENAL_READY.get(), at, .75f, 1);
        if (tick == fire - 9) play(HexGodOfStories.ARSENAL_READY.get(), at, .6f, .92f);
        if (tick == fire - 5) play(HexGodOfStories.ARSENAL_READY_CLOSE.get(), at, .8f, 1);
        if (tick == fire - 3) play(HexGodOfStories.ARSENAL_READY_CLOSE.get(), at, .65f, .93f);
        if (tick >= fire && tick < ArsenalLayout.FIRE_END) {
            shot(c, at, tick);
            if (c.kind == 2 && tick - c.lastHit >= 3) {
                c.lastHit = tick;
                Minecraft mc = Minecraft.getInstance();
                play(HexGodOfStories.ARSENAL_HIT.get(), at.add(caster.getViewVector(1).scale(c.reach)), .7f, .9f + mc.level.random.nextFloat() * .2f);
            }
        }
        if (tick == ArsenalLayout.FIRE_END) {
            // The guns come apart as the two missiles take shape.
            play(HexGodOfStories.ARSENAL_FORM.get(), at, .5f, .72f);
            stereo(HexGodOfStories.ARSENAL_MISSILE_FORM.get(), at, 1, 1, 64);
        }
    }

    /** One shot heard a tick for a whole crown, from one of the guns that fired in it, and never too many at once. */
    private static void shot(Crown c, Vec3 at, int tick) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource random = mc.level.random;
        int fired = 0, pick = -1;
        for (int g = 0; g < ArsenalLayout.GUNS; g++)
            if (ArsenalLayout.shots(g, tick) > 0 && random.nextInt(++fired) == 0) pick = g;
        if (pick < 0 || SHOTS.size() >= MOST_SHOTS) return;
        SoundEvent sound = switch (ArsenalLayout.type(pick)) {
            case 0 -> HexGodOfStories.ARSENAL_M249.get();
            case 1 -> HexGodOfStories.ARSENAL_RPK.get();
            default -> HexGodOfStories.ARSENAL_EVOLYS.get();
        };
        boolean self = mc.player != null && mc.player.getId() == c.caster;
        SimpleSoundInstance s = new SimpleSoundInstance(sound, SoundSource.PLAYERS, (self ? .42f : .8f) * (.9f + random.nextFloat() * .2f),
            .94f + random.nextFloat() * .12f, SoundInstance.createUnseededRandom(), at.x, at.y, at.z);
        mc.getSoundManager().play(s);
        SHOTS.add(s);
    }

    /** Sparks shed by every gun and missile still forming or coming apart, from its burning edge. */
    private static void sparks(Crown c, RandomSource random) {
        for (int g = 0; g < ArsenalLayout.GUNS; g++) {
            Gun gun = c.guns[g];
            if (gun == null || gun.reveal() >= .99f || random.nextFloat() > .55f) continue;
            ArsenalMeshes.Mesh mesh = ArsenalMeshes.get(ArsenalMeshes.GUNS[gun.type()]);
            if (mesh != null) spark(mesh, gun.middle(), gun.right(), gun.up(), gun.forward(), gun.scale(), gun.reveal(), random);
        }
        ArsenalMeshes.Mesh rocket = ArsenalMeshes.get(ArsenalMeshes.ROCKET);
        for (Rocket r : c.heads)
            if (r != null && rocket != null && r.reveal() < .99f)
                for (int i = 0; i < 2; i++) spark(rocket, r.middle(), r.right(), r.up(), r.forward(), r.scale(), r.reveal(), random);
    }

    private static void spark(ArsenalMeshes.Mesh mesh, Vec3 middle, Vec3 right, Vec3 up, Vec3 forward, float scale, float reveal, RandomSource random) {
        float along = Mth.clamp(reveal * 1.3f - .12f, 0, 1);
        float z = -(mesh.back() + (mesh.front() - mesh.back()) * along);
        float x = Mth.lerp(random.nextFloat(), mesh.low()[0], mesh.high()[0]) * .7f, y = Mth.lerp(random.nextFloat(), mesh.low()[1], mesh.high()[1]) * .7f;
        Vec3 at = middle.add(right.scale(x * scale)).add(up.scale(y * scale)).subtract(forward.scale(z * scale));
        ArsenalFx.create(HexGodOfStories.CINDER.get(), at, random.nextGaussian() * .015, .01 + random.nextDouble() * .02, random.nextGaussian() * .015);
    }

    /** A little smoke off the muzzles while they fire, thicker the longer they have. */
    private static void smoke(Crown c, int t, ClientLevel level) {
        if (t < ArsenalLayout.FIRE_START || t >= ArsenalLayout.FIRE_END || t > c.stopped()) return;
        RandomSource random = level.random;
        int puffs = t - ArsenalLayout.FIRE_START > 100 ? 2 : 1;
        for (int i = 0; i < puffs; i++) {
            Gun gun = c.guns[random.nextInt(ArsenalLayout.GUNS)];
            if (gun == null || gun.flash() <= 0) continue;
            Vec3 at = gun.muzzle().add(gun.forward().scale(.1));
            level.addParticle(ParticleTypes.SMOKE, at.x, at.y, at.z, gun.forward().x * .02, .012 + gun.forward().y * .02, gun.forward().z * .02);
        }
    }

    /** A missile's fire and smoke, laid along the stretch it flew this tick so the trail has no gaps at any speed. */
    private static void trail(Missile m, long now, RandomSource random) {
        double ticks = now - m.launch;
        if (ticks < 0 || ticks > m.flight.duration) return;
        Rocket r = m.pose(ticks);
        Vec3 tail = tail(r), from = m.trail == null ? tail : m.trail;
        m.trail = tail;
        Vec3 back = r.forward().scale(-1);
        for (int i = 0; i < 3; i++) {
            Vec3 at = from.lerp(tail, (i + random.nextDouble()) / 3);
            Particle flame = ArsenalFx.create(HexGodOfStories.METEOR_FIRE.get(), at, back.x * .06 + random.nextGaussian() * .015,
                back.y * .06 + random.nextGaussian() * .015, back.z * .06 + random.nextGaussian() * .015);
            if (flame != null) flame.setLifetime(5 + random.nextInt(4));
        }
        for (int i = 0; i < 2; i++) {
            Vec3 at = from.lerp(tail, (i + random.nextDouble()) / 2);
            Particle puff = ArsenalFx.create(HexGodOfStories.ASH.get(), at, random.nextGaussian() * .008, .004 + random.nextDouble() * .01,
                random.nextGaussian() * .008);
            if (puff == null) continue;
            float grey = .78f + random.nextFloat() * .12f;
            puff.setColor(grey, grey, grey * .97f);
            puff.setLifetime(70 + random.nextInt(50));
        }
    }

    /** Casings fall, tumble, and skip off whatever they land on until they burn away. */
    private static void casings(ClientLevel level) {
        for (Iterator<Casing> it = CASINGS.iterator(); it.hasNext(); ) {
            Casing k = it.next();
            if (++k.age > CASING_LIFE) {it.remove(); continue;}
            k.last = k.at;
            k.velocity = k.velocity.scale(.98).add(0, -.045, 0);
            Vec3 next = k.at.add(k.velocity);
            BlockPos pos = BlockPos.containing(next);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                k.velocity = new Vec3(k.velocity.x * .45, Math.abs(k.velocity.y) * .3, k.velocity.z * .45);
                next = new Vec3(next.x, k.at.y, next.z);
            }
            k.at = next;
        }
    }

    /** A round arriving: dust and a spark off a block, blood from a body, nothing off the sky. */
    private static void land(Tracer tr, ClientLevel level) {
        Vec3 end = tr.from.add(tr.direction.scale(tr.length));
        RandomSource random = level.random;
        if (tr.kind == 1 && tr.block != null && !tr.block.isAir()) {
            Vec3 n = new Vec3(tr.face.getStepX(), tr.face.getStepY(), tr.face.getStepZ());
            BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, tr.block);
            for (int i = 0; i < 2; i++)
                level.addParticle(dust, end.x, end.y, end.z, n.x * .12 + random.nextGaussian() * .05, n.y * .12 + .06 + random.nextGaussian() * .05,
                    n.z * .12 + random.nextGaussian() * .05);
            if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, end.x, end.y, end.z, n.x * .02, n.y * .02 + .01, n.z * .02);
            ArsenalFx.create(HexGodOfStories.CINDER.get(), end, n.x * .15 + random.nextGaussian() * .08, n.y * .15 + .08 + random.nextGaussian() * .08,
                n.z * .15 + random.nextGaussian() * .08);
        } else if (tr.kind == 2) {
            Vfx.spark(HexGodOfStories.BLOOD.get(), end, tr.direction.scale(.12).add(random.nextGaussian() * .05, random.nextGaussian() * .05,
                random.nextGaussian() * .05));
        }
    }

    // ------------------------------------------------------------------ the solid pass: guns, missiles, casings

    public static void render(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !QUAKES.isEmpty()) ground(e, mc.level);
        if (mc.level == null || failed || CROWNS.isEmpty() && MISSILES.isEmpty() && CASINGS.isEmpty() && SNEAKS.isEmpty()) return;
        frame++;
        float partial = e.getPartialTick();
        double time = ClientState.time(partial);
        Camera camera = e.getCamera();
        Vec3 eye = camera.getPosition();
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(e.getPoseStack().last().pose());
        Matrix4f projection = e.getProjectionMatrix();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        // Drawn from both sides: a gun half formed is open along its burning edge, and its inside shows through it.
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        try {
            ArsenalMeshes.Mesh rocket = ArsenalMeshes.get(ArsenalMeshes.ROCKET);
            for (Crown c : CROWNS.values()) {
                Entity caster = mc.level.getEntity(c.caster);
                if (caster == null || caster.distanceToSqr(eye) > DRAW_RANGE * DRAW_RANGE) continue;
                pose(c, caster, partial, camera, time);
                c.drawn = frame;
                int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(caster.getEyePosition(partial)));
                for (Gun g : c.guns) {
                    ArsenalMeshes.Mesh mesh = g == null ? null : ArsenalMeshes.get(ArsenalMeshes.GUNS[g.type()]);
                    if (mesh != null) draw(mesh, view, projection, eye, g.middle(), g.right(), g.up(), g.forward(), g.scale(), g.reveal(), g.flash(),
                        light, GUN_RIM);
                }
                if (rocket != null) for (Rocket r : c.heads)
                    if (r != null) draw(rocket, view, projection, eye, r.middle(), r.right(), r.up(), r.forward(), r.scale(), r.reveal(), 0, lit(light),
                        MISSILE_RIM);
                rounds(c, ClientState.since(c.start, partial));
            }
            for (Sneak s : SNEAKS.values()) {
                if (s.at.distanceToSqr(eye) > DRAW_RANGE * DRAW_RANGE) {s.gun = null; continue;}
                double t = ClientState.since(s.start, partial);
                // Formed facing where the head was, then turned onto where it is as it fires, and held there as it comes apart.
                if (t < ArsenalLayout.SNEAK_FIRE) s.aim = sneakAim(s, mc.level.getEntity(s.target), t, partial);
                s.gun = sneak(s, t);
                s.drawn = frame;
                ArsenalMeshes.Mesh mesh = s.gun == null ? null : ArsenalMeshes.get(ArsenalMeshes.GUNS[s.gun.type()]);
                if (mesh != null) draw(mesh, view, projection, eye, s.gun.middle(), s.gun.right(), s.gun.up(), s.gun.forward(), s.gun.scale(),
                    s.gun.reveal(), s.gun.flash(), LevelRenderer.getLightColor(mc.level, BlockPos.containing(s.at)), GUN_RIM);
            }
            if (rocket != null) for (Missile m : MISSILES.values()) {
                Rocket r = m.pose(ClientState.since(m.launch, partial));
                int light = lit(LevelRenderer.getLightColor(mc.level, BlockPos.containing(r.middle())));
                draw(rocket, view, projection, eye, r.middle(), r.right(), r.up(), r.forward(), r.scale(), 1, 0, light, MISSILE_RIM);
            }
            for (Casing k : CASINGS) {
                ArsenalMeshes.Mesh mesh = ArsenalMeshes.get(ArsenalMeshes.SHELLS[k.type]);
                Vec3 at = k.last.lerp(k.at, partial);
                if (mesh == null || at.distanceToSqr(eye) > DETAIL_RANGE * DETAIL_RANGE) continue;
                double angle = k.spin * (k.age + partial);
                float reveal = 1 - Mth.clamp((k.age + partial - (CASING_LIFE - 8)) / 8f, 0, 1);
                draw(mesh, view, projection, eye, at, turn(k.right, k.axis, angle), turn(k.up, k.axis, angle), turn(k.forward, k.axis, angle),
                    CASING_SCALE * k.scale, reveal, 0, LevelRenderer.getLightColor(mc.level, BlockPos.containing(at)), NO_RIM);
            }
        } catch (RuntimeException failure) {
            // Drawing is never worth the session: the crown goes unseen from here on, and the log says why.
            failed = true;
            LOGGER.error("The Crown of Barrels could not be drawn; it will go unseen until the game restarts", failure);
        } finally {
            VertexBuffer.unbind();
            mc.gameRenderer.overlayTexture().teardownOverlayColor();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            RenderSystem.enableCull();
        }
    }

    /** Where every gun and forming missile of a crown is this frame, and so where the mark is for it. */
    private static void pose(Crown c, Entity caster, float partial, Camera camera, double time) {
        Frame f = frame(caster, partial, camera);
        double t = ClientState.since(c.start, partial), stop = c.stopped();
        // The aim: the mark along this frame's own look, trailed a little so the guns have weight.
        Vec3 eye = f.first() ? f.origin() : caster.getEyePosition(partial), look = f.first() ? f.forward() : caster.getViewVector(partial);
        Vec3 target = eye.add(look.scale(c.reach));
        if (c.aim == null || time < c.aimedAt || time - c.aimedAt > 5) c.aim = target;
        else c.aim = c.aim.add(target.subtract(c.aim).scale(1 - Math.exp(-(time - c.aimedAt) / AIM_LAG)));
        c.aimedAt = time;
        double[] aim = f.local(c.aim);
        float aiming = (float) smooth((t - (ArsenalLayout.FIRE_START - 12)) / 12);
        double fire = Math.min(t, stop);
        for (int g = 0; g < ArsenalLayout.GUNS; g++) {
            float reveal = reveal(c, g, t);
            if (reveal <= .001f) {c.guns[g] = null; continue;}
            // However it comes apart, it comes apart where it is: its pose holds from the moment its fire stopped.
            float formed = ArsenalLayout.formed(g, (float) Math.min(t, Math.min(stop, ArsenalLayout.FIRE_END)));
            // Each gun sprays about the mark its own way, as the server's do.
            ArsenalLayout.Pose p = ArsenalLayout.pose(f.slot(g), ArsenalLayout.out(g), ArsenalLayout.aimOf(g, aim, fire), formed, aiming);
            Vec3 right = f.axis(p.right()), up = f.axis(p.up()), forward = f.axis(p.forward());
            double since = t > stop + 1 ? -1 : ArsenalLayout.sinceShot(g, fire);
            float flash = since < 0 ? 0 : (float) Math.max(0, 1 - since / 1.2), kick = since < 0 ? 0 : (float) Math.max(0, 1 - since / 1.8);
            float scale = f.scale() * (.9f + .1f * reveal);
            Vec3 middle = f.point(p.at()).subtract(forward.scale(.055 * kick * kick * (f.first() ? f.k() : 1)));
            double[] m = ArsenalLayout.MUZZLE[ArsenalLayout.type(g)];
            Vec3 muzzle = middle.add(right.scale(m[0] * scale)).add(up.scale(m[1] * scale)).subtract(forward.scale(m[2] * scale));
            c.guns[g] = new Gun(middle, right, up, forward, muzzle, scale, reveal, flash * flash, ArsenalLayout.type(g),
                (long) Math.floor(ArsenalLayout.rounds(g, fire)));
        }
        for (int s = 0; s < 2; s++) c.heads[s] = head(c, f, s == 0 ? -1 : 1, t);
    }

    /** How much of a gun there is: formed, less whatever the end of the fire, or a hold let go, has taken apart since. */
    private static float reveal(Crown c, int gun, double t) {
        float r = ArsenalLayout.formed(gun, (float) t);
        if (t >= ArsenalLayout.FIRE_END) r = Math.min(r, ArsenalLayout.unformed((float) (t - ArsenalLayout.FIRE_END)));
        if (c.cancelled()) {
            double stop = c.stopped();
            r = Math.min(r, ArsenalLayout.formed(gun, (float) stop) * ArsenalLayout.unformed((float) (t - stop)));
        }
        return r;
    }

    /** A missile forming beside the head, from the end of the fire until it leaves: rising a little into place, turning. */
    private static Rocket head(Crown c, Frame f, int side, double t) {
        if (t < ArsenalLayout.FIRE_END || c.how.equals("launched")) return null;
        float reveal = (float) Mth.clamp((t - ArsenalLayout.FIRE_END) / ArsenalLayout.MISSILE_REVEAL, 0, 1);
        if (c.cancelled()) {
            double stop = c.stopped();
            reveal = Math.min(reveal, (float) Mth.clamp((stop - ArsenalLayout.FIRE_END) / ArsenalLayout.MISSILE_REVEAL, 0, 1)
                * ArsenalLayout.unformed((float) (t - stop)));
        }
        if (reveal <= .001f) return null;
        double[] spot = f.first() ? new double[]{side * FIRST_MISSILE[0] * f.k(), FIRST_MISSILE[1] * f.k(), FIRST_MISSILE[2]}
            : new double[]{side * ArsenalLayout.MISSILE_SIDE, ArsenalLayout.MISSILE_HEIGHT, .05};
        double[] lean = f.first() ? FIRST_MISSILE_LEAN : MISSILE_LEAN;
        Vec3 forward = f.axis(new double[]{side * lean[0], lean[1], lean[2]}).normalize();
        Vec3[] axes = axes(forward, (t - ArsenalLayout.FIRE_END) * .09 * side);
        float settle = 1 - reveal;
        Vec3 middle = f.point(spot).subtract(f.up().scale(.25 * settle * settle * (f.first() ? f.k() : 1)));
        float scale = (float) (ArsenalLayout.MISSILE_SCALE * (f.first() ? FIRST_MISSILE_SCALE * f.k() : 1) * (.9 + .1 * reveal));
        return new Rocket(middle, forward, axes[0], axes[1], scale, reveal);
    }

    /** Each round a crown's guns have fired since the last frame: a tracer from its muzzle, and now and then a casing. */
    private static void rounds(Crown c, double t) {
        double fire = Math.min(t, c.stopped());
        ClientLevel level = Minecraft.getInstance().level;
        RandomSource random = level.random;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double spread = ArsenalLayout.SPREAD;
        for (int g = 0; g < ArsenalLayout.GUNS; g++) {
            long now = (long) Math.floor(ArsenalLayout.rounds(g, fire)), from = c.counted[g] + 1;
            if (now < from) continue;
            c.counted[g] = now;
            Gun gun = c.guns[g];
            if (gun == null || c.aim == null) continue;
            boolean near = gun.muzzle().distanceToSqr(eye) < DETAIL_RANGE * DETAIL_RANGE;
            double distance = c.aim.distanceTo(gun.muzzle());
            if (distance < .75) continue;
            // A hitch drops the rounds it missed rather than firing them all at once.
            for (long k = Math.max(from, now - 2); k <= now; k++) {
                // Down its own gun, which points at its own spot about the mark, and straying from that.
                Vec3 direction = gun.forward().add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread)
                    .normalize();
                TRACERS.add(tracer(c, level, gun.muzzle(), direction, distance, c.start + ArsenalLayout.shotTime(g, k), k % 2 == 0));
                if (near && k % CASING_EVERY == 0) casing(gun, random);
            }
        }
        while (TRACERS.size() > MOST_TRACERS) TRACERS.remove(0);
        while (CASINGS.size() > MOST_CASINGS) CASINGS.remove(0);
    }

    /**
     * A round's streak, to where it lands: into the body the mark is on if it meets it; if it misses, on past it, as
     * the server's rounds do, to whatever block stops it or nowhere.
     */
    private static Tracer tracer(Crown c, ClientLevel level, Vec3 from, Vec3 direction, double distance, double born, boolean bright) {
        if (c.kind != 2 || c.box == null) return new Tracer(from, direction, distance, born, bright, c.kind, c.block, c.face);
        Vec3 end = from.add(direction.scale(distance + ArsenalLayout.PAST));
        Optional<Vec3> in = c.box.clip(from, end);
        if (in.isPresent()) return new Tracer(from, direction, from.distanceTo(in.get()), born, bright, 2, null, c.face);
        BlockHitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        if (wall.getType() == HitResult.Type.MISS) return new Tracer(from, direction, distance + ArsenalLayout.PAST, born, bright, 0, null, c.face);
        return new Tracer(from, direction, from.distanceTo(wall.getLocation()), born, bright, 1, level.getBlockState(wall.getBlockPos()),
            wall.getDirection());
    }

    /** Gotcha!'s gun {@code t} ticks after it began: forming, turned on the back it will shoot, its one flash and kick, coming apart. */
    private static Gun sneak(Sneak s, double t) {
        float reveal = (float) Mth.clamp(t / ArsenalLayout.SNEAK_FORM, 0, 1);
        if (t >= ArsenalLayout.SNEAK_GONE) reveal = Math.min(reveal, ArsenalLayout.unformed((float) (t - ArsenalLayout.SNEAK_GONE)));
        if (reveal <= .001f) return null;
        ArsenalLayout.Pose p = ArsenalLayout.aimed(new double[]{s.at.x, s.at.y, s.at.z}, new double[]{s.aim.x, s.aim.y, s.aim.z});
        Vec3 right = vec(p.right()), up = vec(p.up()), forward = vec(p.forward());
        double since = t - ArsenalLayout.SNEAK_FIRE;
        float flash = since < 0 ? 0 : (float) Math.max(0, 1 - since / 1.2), kick = since < 0 ? 0 : (float) Math.max(0, 1 - since / 1.8);
        float scale = (float) ArsenalLayout.GUN_SCALE * (.9f + .1f * reveal);
        Vec3 middle = s.at.subtract(forward.scale(.055 * kick * kick));
        double[] m = ArsenalLayout.MUZZLE[ArsenalLayout.SNEAK_TYPE];
        Vec3 muzzle = middle.add(right.scale(m[0] * scale)).add(up.scale(m[1] * scale)).subtract(forward.scale(m[2] * scale));
        return new Gun(middle, right, up, forward, muzzle, scale, reveal, flash * flash, ArsenalLayout.SNEAK_TYPE, s.start);
    }

    /**
     * Where Gotcha!'s gun points {@code t} ticks after it began: where the body's head was then, while it forms; then
     * turning onto the head as it is now, the moment it fires.
     */
    private static Vec3 sneakAim(Sneak s, Entity target, double t, float partial) {
        if (t <= ArsenalLayout.SNEAK_FORM) return s.from;
        if (target == null) return s.aim;
        Vec3 head = target.getPosition(partial).add(0, ArsenalLayout.head(target.getBbHeight()), 0);
        return s.from.lerp(head, smooth(Mth.clamp((t - ArsenalLayout.SNEAK_FORM) / (ArsenalLayout.SNEAK_FIRE - ArsenalLayout.SNEAK_FORM), 0, 1)));
    }

    /** Gotcha!'s one round, dead on the head: the crack of it, its tracer (or the block in the way), the thud, and a casing. */
    private static void gotcha(Sneak s, ClientLevel level) {
        Entity target = level.getEntity(s.target);
        if (target != null) s.aim = target.position().add(0, ArsenalLayout.head(target.getBbHeight()), 0);
        Gun gun = sneak(s, ArsenalLayout.SNEAK_FIRE);
        if (gun == null) return;
        Vec3 to = s.aim.subtract(gun.muzzle());
        double distance = to.length();
        if (distance < .3) return;
        Vec3 direction = to.scale(1 / distance), end = s.aim.add(direction.scale(2));
        double born = s.start + ArsenalLayout.SNEAK_FIRE;
        BlockHitResult wall = level.clip(new ClipContext(gun.muzzle(), end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
        Optional<Vec3> in = target == null ? Optional.empty() : target.getBoundingBox().inflate(.1).clip(gun.muzzle(), stop);
        if (in.isPresent()) TRACERS.add(new Tracer(gun.muzzle(), direction, gun.muzzle().distanceTo(in.get()), born, true, 2, null, Direction.UP));
        else if (wall.getType() != HitResult.Type.MISS)
            TRACERS.add(new Tracer(gun.muzzle(), direction, gun.muzzle().distanceTo(stop), born, true, 1, level.getBlockState(wall.getBlockPos()),
                wall.getDirection()));
        else TRACERS.add(new Tracer(gun.muzzle(), direction, distance + 2, born, true, 0, null, Direction.UP));
        play(HexGodOfStories.ARSENAL_RPK.get(), gun.muzzle(), 1, .96f + level.random.nextFloat() * .08f);
        if (in.isPresent()) play(HexGodOfStories.ARSENAL_HIT.get(), in.get(), .9f, .9f + level.random.nextFloat() * .2f);
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (gun.muzzle().distanceToSqr(eye) < DETAIL_RANGE * DETAIL_RANGE) casing(gun, level.random);
    }

    // ------------------------------------------------------------------ the ground shaking under a blast

    /**
     * The ground round a blast: the top block of every column from the crater's edge out to {@link #QUAKE_REACH},
     * near the blast's own height (a floor with open air, or nothing solid, above it), read once as the blast lands.
     */
    private static void quake(Vec3 at, ClientLevel level, double shaken) {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (eye.distanceToSqr(at) > QUAKE_SEEN * QUAKE_SEEN) return;
        // The second missile lands where the first did: one wave through that ground, not two drawn over each other.
        for (Quake q : QUAKES) if (q.at.distanceToSqr(at) < 64 && ClientState.now() - q.born < 20) return;
        List<Column> columns = new ArrayList<>();
        int reach = (int) shaken, cx = Mth.floor(at.x), cz = Mth.floor(at.z), top = Mth.floor(at.y) + 4, bottom = Mth.floor(at.y) - 14;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), above = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++) {
                double r = Math.hypot(cx + dx + .5 - at.x, cz + dz + .5 - at.z);
                if (r > shaken || r < QUAKE_INNER || !level.hasChunkAt(pos.set(cx + dx, top, cz + dz))) continue;
                for (int y = top; y >= bottom; y--) {
                    BlockState state = level.getBlockState(pos.set(cx + dx, y, cz + dz));
                    if (state.getCollisionShape(level, pos).isEmpty()) continue;
                    above.set(cx + dx, y + 1, cz + dz);
                    if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) continue;
                    if (state.getRenderShape() == RenderShape.MODEL) columns.add(new Column(pos.immutable(), state, r, level.random.nextInt(4) == 0));
                    break;
                }
            }
        if (columns.isEmpty()) return;
        QUAKES.add(new Quake(at, ClientState.now(), columns, shaken));
        while (QUAKES.size() > MOST_QUAKES) QUAKES.remove(0);
    }

    /** How far a block of ground {@code reach} out from a blast is thrown: most at the crater's edge, a little at the far one. */
    private static double thrown(double reach, double shaken) {
        double out = Mth.clamp((reach - QUAKE_INNER) / (shaken - QUAKE_INNER), 0, 1);
        return .08 + (QUAKE_THROW - .08) * Math.pow(1 - out, 1.3);
    }

    /** The dust the wave throws off the ground it is under this tick, and the shake of it for whoever it passes under. */
    private static void rumble(Quake q, long age, ClientLevel level) {
        RandomSource random = level.random;
        for (Column c : q.columns) {
            if (!c.dust() || (long) (c.reach() / QUAKE_SPEED) != age || level.getBlockState(c.pos()) != c.state()) continue;
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, c.state()), c.pos().getX() + random.nextDouble(), c.pos().getY() + 1.05,
                c.pos().getZ() + random.nextDouble(), random.nextGaussian() * .04, .12 + random.nextDouble() * .18 * thrown(c.reach(), q.reach) / QUAKE_THROW,
                random.nextGaussian() * .04);
        }
        Player me = Minecraft.getInstance().player;
        if (q.felt || me == null) return;
        double d = Math.hypot(me.getX() - q.at.x, me.getZ() - q.at.z);
        if (age < d / QUAKE_SPEED) return;
        q.felt = true;
        // Felt only standing on it, within reach of it and near its height.
        if (d <= q.reach && me.onGround() && Math.abs(me.getY() - q.at.y) < 12)
            com.hexgodofstories.client.leviathan.LeviathanEffects.quake((float) (.5 + 1.6 * (1 - d / q.reach)));
    }

    /** Every block of ground a blast's wave is under this frame, thrown up and settling: drawn over itself, a little raised. */
    private static void ground(RenderLevelStageEvent e, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        var frustum = e.getFrustum();
        MultiBufferSource.BufferSource source = MultiBufferSource.immediate(GROUND);
        var blocks = mc.getBlockRenderer();
        int drawn = 0;
        try {
            for (Quake q : QUAKES) {
                if (q.at.distanceToSqr(camera) > QUAKE_SEEN * QUAKE_SEEN) continue;
                double age = ClientState.since(q.born, e.getPartialTick());
                for (Column c : q.columns) {
                    double local = age - c.reach() / QUAKE_SPEED;
                    if (local <= 0 || local >= QUAKE_BUMP || drawn >= MOST_THROWN) continue;
                    double lift = thrown(c.reach(), q.reach) * Math.sin(Math.PI * local / QUAKE_BUMP);
                    // Gone since (the crater, a player), or out of sight: nothing to throw.
                    if (lift < .015 || level.getBlockState(c.pos()) != c.state()) continue;
                    if (frustum != null && !frustum.isVisible(new AABB(c.pos()).expandTowards(0, lift, 0))) continue;
                    drawn++;
                    BlockPos at = c.pos();
                    pose.pushPose();
                    // A hair narrower than the block itself, so its sides never fight the ground's own where they meet.
                    pose.translate(at.getX() + .5 - camera.x, at.getY() + lift - camera.y, at.getZ() + .5 - camera.z);
                    pose.scale(.996f, 1, .996f);
                    pose.translate(-.5, 0, -.5);
                    blocks.renderSingleBlock(c.state(), pose, source, LevelRenderer.getLightColor(level, at.above()), OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, null);
                    pose.popPose();
                }
            }
            source.endBatch();
        } catch (RuntimeException failure) {
            // The ground simply stops shaking; nothing else of the crown is lost with it.
            QUAKES.clear();
            LOGGER.error("The ground under a Crown of Barrels blast could not be drawn shaking", failure);
        }
    }

    /** A spent case out of a gun's ejection port: out to its right and up (the M249 drops its own), tumbling. */
    private static void casing(Gun gun, RandomSource random) {
        ArsenalMeshes.Mesh body = ArsenalMeshes.get(ArsenalMeshes.GUNS[gun.type()]);
        if (body == null) return;
        float[] port = body.shell();
        float s = gun.scale();
        Vec3 at = gun.middle().add(gun.right().scale(port[0] * s)).add(gun.up().scale(port[1] * s)).subtract(gun.forward().scale(port[2] * s));
        Vec3 out = gun.type() == 0 ? gun.up().scale(-.05).add(gun.right().scale(.03))
            : gun.right().scale(.09 + random.nextDouble() * .05).add(gun.up().scale(.05 + random.nextDouble() * .04));
        Vec3 velocity = out.add(gun.forward().scale(-.02 + random.nextDouble() * .03))
            .add(random.nextGaussian() * .012, random.nextGaussian() * .012, random.nextGaussian() * .012);
        Vec3 axis = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        axis = axis.lengthSqr() < 1e-6 ? gun.up() : axis.normalize();
        CASINGS.add(new Casing(gun.type(), s / (float) ArsenalLayout.GUN_SCALE, at, velocity, gun.right(), gun.up(), gun.forward(), axis,
            .5 + random.nextDouble() * .7));
    }

    /** One model, drawn once: at {@code middle}, its right, top and muzzle end along the given axes, in model units times {@code scale}. */
    private static void draw(ArsenalMeshes.Mesh mesh, Matrix4f view, Matrix4f projection, Vec3 camera, Vec3 middle, Vec3 right, Vec3 up,
                             Vec3 forward, float scale, float reveal, float flash, int light, float[] rim) {
        ShaderInstance shader = ArsenalShaders.gun();
        if (shader != null) {
            shader.safeGetUniform("Reveal").set(reveal);
            shader.safeGetUniform("RevealSpan").set(mesh.back(), mesh.front());
            shader.safeGetUniform("Flash").set(flash);
            shader.safeGetUniform("WorldLight").set((float) (LightTexture.block(light) * 16), (float) (LightTexture.sky(light) * 16));
            shader.safeGetUniform("EdgeColor").set(1f, .27f, .2f);
            shader.safeGetUniform("RimColor").set(rim[0], rim[1], rim[2], rim[3]);
        } else if (reveal < .5f) {
            // Drawn plain there is no edge to form behind: it simply appears half way through.
            return;
        }
        Matrix4f model = new Matrix4f(
            (float) right.x, (float) right.y, (float) right.z, 0,
            (float) up.x, (float) up.y, (float) up.z, 0,
            (float) -forward.x, (float) -forward.y, (float) -forward.z, 0,
            (float) (middle.x - camera.x), (float) (middle.y - camera.y), (float) (middle.z - camera.z), 1).scale(scale);
        RenderSystem.setShaderTexture(0, mesh.texture());
        mesh.buffer().bind();
        mesh.buffer().drawWithShader(new Matrix4f(view).mul(model), projection,
            shader != null ? shader : GameRenderer.getRendertypeEntityTranslucentEmissiveShader());
    }

    /** A missile's own light: whatever the world gives it, and never less than its motor's glow. */
    private static int lit(int light) {
        return LightTexture.pack(Math.max(LightTexture.block(light), 10), LightTexture.sky(light));
    }

    private static Frame frame(Entity caster, float partial, Camera camera) {
        if (firstPerson(caster)) {
            Vec3 eye, look, up, right;
            if (camera != null) {
                Vector3f l = camera.getLookVector(), u = camera.getUpVector(), left = camera.getLeftVector();
                eye = camera.getPosition();
                look = new Vec3(l.x(), l.y(), l.z());
                up = new Vec3(u.x(), u.y(), u.z());
                right = new Vec3(-left.x(), -left.y(), -left.z());
            } else {
                float yaw = caster.getViewYRot(partial) * Mth.DEG_TO_RAD;
                eye = caster.getEyePosition(partial);
                look = caster.getViewVector(partial);
                right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
                up = right.cross(look).normalize();
            }
            double fov = ScepterFx.worldFov();
            double k = Math.tan(Math.toRadians(fov) / 2) / Math.tan(Math.toRadians(70) / 2);
            return new Frame(eye, right, up, look, true, Mth.clamp(k, .25, 3));
        }
        float yaw = (caster instanceof LivingEntity living ? Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot) : caster.getViewYRot(partial))
            * Mth.DEG_TO_RAD;
        // Facing south a right hand points west: forward (-sin, 0, cos), right (-cos, 0, -sin).
        return new Frame(caster.getPosition(partial), new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw)), new Vec3(0, 1, 0),
            new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), false, 1);
    }

    private static boolean firstPerson(Entity caster) {
        Minecraft mc = Minecraft.getInstance();
        return caster == mc.player && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == caster;
    }

    // ------------------------------------------------------------------ the light pass: flashes, tracers, flames, rings

    public static void renderLight(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed || CROWNS.isEmpty() && MISSILES.isEmpty() && TRACERS.isEmpty() && WAVES.isEmpty() && SNEAKS.isEmpty()) return;
        float partial = e.getPartialTick();
        double time = ClientState.time(partial);
        Vec3 camera = e.getCamera().getPosition();
        Matrix4f view = e.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        RenderType type = ArsenalRenderTypes.light(FLASH);
        VertexConsumer out = buffers.getBuffer(type);
        for (Crown c : CROWNS.values()) {
            if (c.drawn != frame) continue;
            for (Gun g : c.guns) if (g != null && g.flash() > .01f) flash(out, view, camera, g);
        }
        for (Sneak s : SNEAKS.values()) if (s.drawn == frame && s.gun != null && s.gun.flash() > .01f) flash(out, view, camera, s.gun);
        buffers.endBatch(type);

        type = ArsenalRenderTypes.light(FLARE);
        out = buffers.getBuffer(type);
        for (Crown c : CROWNS.values()) {
            if (c.drawn != frame) continue;
            for (Gun g : c.guns)
                if (g != null && g.flash() > .01f)
                    billboard(out, at(view, camera, g.muzzle()), .5f * g.scale() / (float) ArsenalLayout.GUN_SCALE, 0, 1, .62f, .28f, .55f * g.flash());
            // A forming missile is wrapped in its own light until it is whole.
            for (Rocket r : c.heads)
                if (r != null && r.reveal() < 1)
                    billboard(out, at(view, camera, r.middle()), 1.3f * r.scale(), 0, 1, .55f, .25f, .5f * (1 - r.reveal()));
        }
        for (Sneak s : SNEAKS.values())
            if (s.drawn == frame && s.gun != null && s.gun.flash() > .01f)
                billboard(out, at(view, camera, s.gun.muzzle()), .5f, 0, 1, .62f, .28f, .55f * s.gun.flash());
        for (Tracer tr : TRACERS) {
            double s = (time - tr.born) * TRACER_SPEED;
            if (s <= 0) continue;
            double head = Math.min(s, tr.length), tail = Math.max(0, s - TRACER_TRAIL);
            if (head - tail < .05) continue;
            float bright = tr.bright ? 1 : .55f;
            streak(out, at(view, camera, tr.from.add(tr.direction.scale(tail))), at(view, camera, tr.from.add(tr.direction.scale(head))),
                tr.bright ? .055f : .035f, 1, .5f, .15f, 0, 1, .92f, .7f, bright, 0, .5f);
        }
        for (Missile m : MISSILES.values()) flame(out, view, camera, m, ClientState.since(m.launch, partial), time);
        buffers.endBatch(type);

        if (!WAVES.isEmpty()) {
            type = ArsenalRenderTypes.light(WorldEffects.WHITE);
            out = buffers.getBuffer(type);
            for (Wave w : WAVES) wave(out, view, camera, w, ClientState.since(w.born(), partial));
            buffers.endBatch(type);
        }
    }

    /** A gun's muzzle flash: TACZ's star, turned anew for every round, and the burst it throws out of the barrel. */
    private static void flash(VertexConsumer out, Matrix4f view, Vec3 camera, Gun g) {
        float k = g.scale() / (float) ArsenalLayout.GUN_SCALE, power = g.flash();
        Vec3 tip = g.muzzle().add(g.forward().scale(.06 * k));
        float spin = (float) (hash(g.round() * 31 + g.type()) * Math.PI * 2);
        billboard(out, at(view, camera, tip), (.22f + .12f * power) * k, spin, 1, .86f, .62f, power);
        if (camera.distanceToSqr(tip) > DETAIL_RANGE * DETAIL_RANGE) return;
        Vec3 reach = g.muzzle().add(g.forward().scale(.55 * k * (.7 + .3 * power)));
        streak(out, at(view, camera, g.muzzle()), at(view, camera, reach), .3f * k, 1, .86f, .62f, power, 1, .7f, .4f, .8f * power, 0, 1);
    }

    /** The fire pushing a missile: a long outer flame, a white core, and the glow of the nozzle. */
    private static void flame(VertexConsumer out, Matrix4f view, Vec3 camera, Missile m, double ticks, double time) {
        if (ticks < 0) return;
        Rocket r = m.pose(ticks);
        Vec3 tail = tail(r);
        float power = (float) Math.min(1, .35 + ticks / 5), flicker = .86f + .1f * Mth.sin((float) (time * 2.9 + m.id * 1.7))
            + .06f * Mth.sin((float) (time * 7.3 + m.id));
        float k = r.scale() / (float) ArsenalLayout.MISSILE_SCALE;
        Vector3f nozzle = at(view, camera, tail);
        streak(out, nozzle, at(view, camera, tail.subtract(r.forward().scale(2.1 * flicker * power * k))), .7f * power * k,
            1, .55f, .18f, .9f, 1, .22f, .04f, 0, .5f, 0);
        streak(out, nozzle, at(view, camera, tail.subtract(r.forward().scale(1.05 * flicker * power * k))), .3f * power * k,
            1, .97f, .8f, 1, 1, .62f, .25f, 0, .5f, 0);
        billboard(out, nozzle, .8f * power * k, 0, 1, .62f, .26f, .85f);
    }

    /** A blast's rings: one along the ground, one square to the eye, both spreading and fading as they go. */
    private static void wave(VertexConsumer out, Matrix4f view, Vec3 camera, Wave w, double age) {
        if (age < 0 || age > WAVE_LIFE) return;
        double u = age / WAVE_LIFE, eased = 1 - (1 - u) * (1 - u);
        float fade = (float) ((1 - u) * (1 - u));
        float[] c = w.green() ? GREEN_RING : FIRE_RING;
        ring(out, view, camera, w.at().add(0, .2, 0), (1 + 15 * eased) * w.scale(), 1.8 * (1 - .5 * u) * w.scale(), fade, true, c);
        double v = Math.min(1, age / 8);
        if (v < 1) ring(out, view, camera, w.at(), (1.5 + 9 * (1 - (1 - v) * (1 - v))) * w.scale(), 1.2 * w.scale(), (float) ((1 - v) * (1 - v)) * .8f, false, c);
    }

    private static final float[] FIRE_RING = {1, .8f, .55f}, GREEN_RING = {.45f, 1, .55f};

    private static void ring(VertexConsumer out, Matrix4f view, Vec3 camera, Vec3 centre, double radius, double width, float fade, boolean level,
                             float[] colour) {
        final int segments = 56;
        Vector3f middle = at(view, camera, centre);
        double[] radii = {Math.max(0, radius - width), radius, radius + width};
        float[] alpha = {0, fade, 0};
        for (int band = 0; band < 2; band++)
            for (int i = 0; i < segments; i++) {
                Vector3f[] p = new Vector3f[4];
                double[] a = {i * Math.PI * 2 / segments, (i + 1) * Math.PI * 2 / segments};
                for (int corner = 0; corner < 4; corner++) {
                    double angle = a[corner == 1 || corner == 2 ? 1 : 0], r = radii[band + (corner >= 2 ? 1 : 0)];
                    p[corner] = level ? at(view, camera, centre.add(Math.cos(angle) * r, 0, Math.sin(angle) * r))
                        : new Vector3f(middle.x() + (float) (Math.cos(angle) * r), middle.y() + (float) (Math.sin(angle) * r), middle.z());
                }
                float inner = alpha[band], outer = alpha[band + 1];
                vertex(out, p[0], colour[0], colour[1], colour[2], inner, 0, 0);
                vertex(out, p[1], colour[0], colour[1], colour[2], inner, 1, 0);
                vertex(out, p[2], colour[0], colour[1], colour[2], outer, 1, 1);
                vertex(out, p[0], colour[0], colour[1], colour[2], inner, 0, 0);
                vertex(out, p[2], colour[0], colour[1], colour[2], outer, 1, 1);
                vertex(out, p[3], colour[0], colour[1], colour[2], outer, 0, 1);
            }
    }

    /** A square facing the eye, turned {@code spin} about the line of sight, {@code size} from its middle to each side. */
    private static void billboard(VertexConsumer out, Vector3f c, float size, float spin, float r, float g, float b, float a) {
        float cs = Mth.cos(spin) * size, sn = Mth.sin(spin) * size;
        float x0 = c.x() - cs + sn, y0 = c.y() - sn - cs, x1 = c.x() + cs + sn, y1 = c.y() + sn - cs;
        float x2 = c.x() + cs - sn, y2 = c.y() + sn + cs, x3 = c.x() - cs - sn, y3 = c.y() - sn + cs;
        vertex(out, x0, y0, c.z(), r, g, b, a, 0, 1);
        vertex(out, x1, y1, c.z(), r, g, b, a, 1, 1);
        vertex(out, x2, y2, c.z(), r, g, b, a, 1, 0);
        vertex(out, x0, y0, c.z(), r, g, b, a, 0, 1);
        vertex(out, x2, y2, c.z(), r, g, b, a, 1, 0);
        vertex(out, x3, y3, c.z(), r, g, b, a, 0, 0);
    }

    /**
     * A band from {@code a} to {@code b}, turned about its own length to face the eye, coloured from one end to the
     * other; the texture runs across it, and along it from {@code va} to {@code vb}.
     */
    private static void streak(VertexConsumer out, Vector3f a, Vector3f b, float width, float ra, float ga, float ba, float aa,
                               float rb, float gb, float bb, float ab, float va, float vb) {
        float dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        float mx = (a.x() + b.x()) * .5f, my = (a.y() + b.y()) * .5f, mz = (a.z() + b.z()) * .5f;
        // Square both to the band and to the line from the eye (the origin of view space) to it.
        float wx = dy * mz - dz * my, wy = dz * mx - dx * mz, wz = dx * my - dy * mx;
        float l = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (l < 1e-7f) return;
        float s = width * .5f / l;
        wx *= s;
        wy *= s;
        wz *= s;
        vertex(out, a.x() - wx, a.y() - wy, a.z() - wz, ra, ga, ba, aa, 0, va);
        vertex(out, a.x() + wx, a.y() + wy, a.z() + wz, ra, ga, ba, aa, 1, va);
        vertex(out, b.x() + wx, b.y() + wy, b.z() + wz, rb, gb, bb, ab, 1, vb);
        vertex(out, a.x() - wx, a.y() - wy, a.z() - wz, ra, ga, ba, aa, 0, va);
        vertex(out, b.x() + wx, b.y() + wy, b.z() + wz, rb, gb, bb, ab, 1, vb);
        vertex(out, b.x() - wx, b.y() - wy, b.z() - wz, rb, gb, bb, ab, 0, vb);
    }

    private static void vertex(VertexConsumer out, Vector3f p, float r, float g, float b, float a, float u, float v) {
        vertex(out, p.x(), p.y(), p.z(), r, g, b, a, u, v);
    }

    private static void vertex(VertexConsumer out, float x, float y, float z, float r, float g, float b, float a, float u, float v) {
        out.vertex(x, y, z, r, g, b, a, u, v, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, 0, 1, 0);
    }

    /** A point of the world in view space: from the camera, turned as the view is turned. */
    private static Vector3f at(Matrix4f view, Vec3 camera, Vec3 p) {
        return view.transformPosition((float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z), new Vector3f());
    }

    // ------------------------------------------------------------------ sound

    private static void play(SoundEvent sound, Vec3 at, float volume, float pitch) {
        ScepterClient.play(sound, at.x, at.y, at.z, volume, pitch);
    }

    /**
     * A stereo recording (the RPG's), which the sound engine will not place in the world: played as it is, as loud
     * as the listener's distance from {@code at} allows, fading to nothing at {@code range}.
     */
    private static void stereo(SoundEvent sound, Vec3 at, float volume, float pitch, double range) {
        Minecraft mc = Minecraft.getInstance();
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        float v = (float) (volume * Math.max(0, 1 - d / range));
        if (v <= .01f) return;
        mc.getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.PLAYERS, v, pitch, SoundInstance.createUnseededRandom(),
            false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    /** A missile's motor, heard from the missile for as long as it flies, faded in as it lights and out as it bursts. */
    private static final class FlightSound extends AbstractTickableSoundInstance {
        private static final float LOUD = 1.6f;
        private static final int FADE = 5;
        private final Missile missile;
        private int age, fading = -1;

        FlightSound(Missile missile) {
            super(HexGodOfStories.ARSENAL_MISSILE_FLIGHT.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.missile = missile;
            looping = true;
            delay = 0;
            volume = .05f;
            pitch = .94f + random.nextFloat() * .12f;
            attenuation = SoundInstance.Attenuation.LINEAR;
            follow();
        }

        private void follow() {
            Vec3 at = missile.pose(ClientState.now() - missile.launch).middle();
            x = at.x;
            y = at.y;
            z = at.z;
        }

        @Override public void tick() {
            if (fading < 0 && (missile.gone || MISSILES.get(missile.id) != missile)) fading = 0;
            if (fading < 0) follow();
            if (fading >= 0) {
                if (++fading >= FADE) {volume = 0; stop(); return;}
                volume = LOUD * (1 - fading / (float) FADE);
                return;
            }
            volume = LOUD * Math.min(1, ++age / 6f);
        }
    }

    // ------------------------------------------------------------------ small things

    /** The back of a missile, where its fire comes from. */
    private static Vec3 tail(Rocket r) {
        ArsenalMeshes.Mesh rocket = ArsenalMeshes.get(ArsenalMeshes.ROCKET);
        double back = rocket == null ? .9 : rocket.high()[2];
        return r.middle().subtract(r.forward().scale(back * r.scale()));
    }

    /** A model's right and top for a nose pointing along {@code forward}, turned {@code roll} about it. */
    private static Vec3[] axes(Vec3 forward, double roll) {
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = forward.cross(new Vec3(1, 0, 0));
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        double c = Math.cos(roll), s = Math.sin(roll);
        return new Vec3[]{right.scale(c).add(up.scale(s)), up.scale(c).subtract(right.scale(s))};
    }

    /** {@code v} turned {@code angle} about the unit {@code axis}. */
    private static Vec3 turn(Vec3 v, Vec3 axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return v.scale(c).add(axis.cross(v).scale(s)).add(axis.scale(axis.dot(v) * (1 - c)));
    }

    private static double smooth(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double hash(long x) {
        x = (x ^ (x >>> 33)) * 0xff51afd7ed558ccdL;
        x = (x ^ (x >>> 33)) * 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static Vec3 vec(double[] v) {return new Vec3(v[0], v[1], v[2]);}
}
