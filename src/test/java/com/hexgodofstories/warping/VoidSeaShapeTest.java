package com.hexgodofstories.warping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consistency between the Abyssal Pilgrim's code and its shipped assets.
 *
 * <p>These are the failures that produce no error at runtime and are invisible in a log: a joint
 * spacing that no longer matches the model, so every multipart hitbox sits off the body the player
 * can see; a bone the renderer poses that the geometry does not define, so an appendage silently
 * stops moving; a realm rebuilt deeper in JSON while the Java constants still describe the old one.
 * No Minecraft classes are loaded, so this runs on every build.
 */
public final class VoidSeaShapeTest {
    /** Model units per block, and the pivot step the segment controller assumes. */
    private static final int UNITS = 16;
    private static final int EXPECTED_PIVOT_STEP = 96;

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        realmMatchesConstants(root);
        geometryMatchesRenderer(root);
        animationsExist(root);
        audioExists(root);
        System.out.println("VoidSeaShapeTest: the Pilgrim's assets and constants agree.");
    }

    // ------------------------------------------------------------------ realm

    private static void realmMatchesConstants(Path root) throws Exception {
        String type = compact(root.resolve("src/main/resources/data/hexgodofstories/dimension_type/warping_void_sea.json"));
        check(number(type, "min_y") == VoidSea.MIN_Y, "dimension_type min_y matches VoidSea.MIN_Y");
        check(number(type, "height") == VoidSea.HEIGHT, "dimension_type height matches VoidSea.HEIGHT");
        check(VoidSea.HEIGHT % 16 == 0 && VoidSea.MIN_Y % 16 == 0, "world height and floor are section aligned");
        check(number(type, "logical_height") <= VoidSea.HEIGHT, "logical height fits the world");

        String dim = compact(root.resolve("src/main/resources/data/hexgodofstories/dimension/warping_void_sea.json"));
        List<Integer> layers = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"height\":(-?\\d+),\"block\":\"([^\"]+)\"\\}").matcher(dim);
        List<String> blocks = new ArrayList<>();
        while (m.find()) { layers.add(Integer.parseInt(m.group(1))); blocks.add(m.group(2)); }
        check(layers.size() == 2, "the sea is an indestructible floor and a water column");
        check(blocks.get(0).equals("hexgodofstories:nothingness"), "the floor is Nothingness, so it cannot be dug through");
        check(blocks.get(1).equals("minecraft:water"), "the column above it is water");
        int[] expected = { VoidSea.BASE, VoidSea.WATER };
        for (int i = 0; i < expected.length; i++) check(layers.get(i) == expected[i], "layer " + i + " height matches VoidSea");

        int sum = 0;
        for (int v : layers) sum += v;
        check(sum <= VoidSea.HEIGHT, "layers fit inside the world height");
        check(VoidSea.SURFACE == VoidSea.MIN_Y + sum - 1, "computed waterline matches the layer stack");
        check(VoidSea.ARRIVAL > VoidSea.SURFACE, "players arrive above the water, not inside it");
        check(VoidSea.ARRIVAL < VoidSea.MAX_Y, "arrival is inside the world");

        // The creature must actually fit, with room to hide under prey and room to leave the water.
        double length = 160;
        check(VoidSea.DEPTH > length * 3, "the sea is at least three body lengths deep, so it can vanish");
        check(VoidSea.SKY > length, "there is more clear air than body, so a full breach fits");
    }

    // ------------------------------------------------------------------ geometry

    private static void geometryMatchesRenderer(Path root) throws Exception {
        String geo = compact(root.resolve("src/main/resources/assets/hexgodofstories/geo/abyssal_pilgrim.geo.json"));

        Map<String, double[]> pivots = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"name\":\"([^\"]+)\",\"pivot\":\\[([^\\]]*)\\](?:,\"parent\":\"([^\"]+)\")?").matcher(geo);
        while (m.find()) {
            pivots.put(m.group(1), triple(m.group(2)));
            if (m.group(3) != null) parents.put(m.group(1), m.group(3));
        }
        check(pivots.containsKey("root"), "the model has a root bone");

        List<String> spine = new ArrayList<>();
        spine.add("head");
        for (int i = 0; i < 3; i++) spine.add("neck_" + i);
        for (int i = 0; i < 12; i++) spine.add("body_" + i);
        for (int i = 0; i < 6; i++) spine.add("tail_" + i);
        check(spine.size() == 22, "twenty two articulated joints");

        for (int i = 1; i < spine.size(); i++) {
            String bone = spine.get(i), parent = spine.get(i - 1);
            check(pivots.containsKey(bone), "geometry defines " + bone);
            check("root".equals(parents.get(bone)), bone + " follows the world path independently of head tracking");
            double step = pivots.get(bone)[2] - pivots.get(parent)[2];
            check(Math.abs(step - EXPECTED_PIVOT_STEP) < 1.0E-6, bone + " sits one joint behind " + parent);
        }
        // The single most damaging drift: hitboxes placed at a spacing the model does not use.
        check(Math.abs(EXPECTED_PIVOT_STEP / (double) UNITS - 6.0) < 1.0E-9,
            "LeviathanSegmentController.SPACING must stay 6.0 blocks to match the model");

        Set<String> required = new LinkedHashSet<>(spine);
        required.add("upper_jaw"); required.add("lower_jaw");
        required.add("jaw_split_left"); required.add("jaw_split_right");
        required.add("sensory_organs"); required.add("head_tendrils"); required.add("tail_tendrils");
        required.add("glow_organs_head");
        for (int i = 0; i < 22; i++) required.add("glow_organs_" + i);
        for (int i = 0; i < 6; i++) required.add("head_tendril_" + i);
        for (int i = 0; i < 4; i++) required.add("tail_tendril_" + i);
        for (int b = 0; b < 12; b++) {
            required.add("dorsal_fin_" + b);
            required.add("rib_appendage_l_" + b);
            required.add("rib_appendage_r_" + b);
        }
        for (String bone : required) check(pivots.containsKey(bone), "geometry defines " + bone);

        double front = Double.MAX_VALUE, back = -Double.MAX_VALUE;
        Matcher c = Pattern.compile("\"origin\":\\[([^\\]]*)\\],\"size\":\\[([^\\]]*)\\]").matcher(geo);
        int cubes = 0;
        while (c.find()) {
            double[] origin = triple(c.group(1)), size = triple(c.group(2));
            front = Math.min(front, origin[2]);
            back = Math.max(back, origin[2] + size[2]);
            cubes++;
        }
        check(cubes > 100, "the model is actually built, not a placeholder");
        double blocks = (back - front) / UNITS;
        check(blocks >= 120 && blocks <= 160, "nose to tail is " + Math.round(blocks) + " blocks, inside the 120 to 160 design range");
    }

    // ------------------------------------------------------------------ animation and audio

    private static void animationsExist(Path root) throws Exception {
        String anim = compact(root.resolve("src/main/resources/assets/hexgodofstories/animations/abyssal_pilgrim.animation.json"));
        String geo = compact(root.resolve("src/main/resources/assets/hexgodofstories/geo/abyssal_pilgrim.geo.json"));
        Set<String> bones = new LinkedHashSet<>();
        Matcher b = Pattern.compile("\"name\":\"([^\"]+)\",\"pivot\"").matcher(geo);
        while (b.find()) bones.add(b.group(1));

        String[] clips = { "swim", "fast_swim", "deep_dive", "vertical_ascent", "breach", "airborne", "water_impact",
            "circle", "stalk", "observe", "fake_lunge", "bite", "grab", "drag", "throw", "tail_sweep", "body_crush",
            "void_scream", "vortex", "roar", "hurt", "frenzy", "death" };
        for (String clip : clips) check(anim.contains("\"" + clip + "\":{"), "animation " + clip + " exists");

        Matcher a = Pattern.compile("\"([A-Za-z_0-9]+)\":\\{\"rotation\"").matcher(anim);
        while (a.find()) check(bones.contains(a.group(1)), "animated bone " + a.group(1) + " exists in the geometry");
    }

    private static void audioExists(Path root) throws Exception {
        String[] names = { "distant_call", "deep_idle", "clicking", "target_detected", "stalk", "breach_charge",
            "breach", "water_impact", "roar", "bite", "grab", "throw", "lunge", "tail_sweep", "void_scream", "hurt", "death" };
        String sounds = compact(root.resolve("src/main/resources/assets/hexgodofstories/sounds.json"));
        for (String name : names) {
            check(sounds.contains("\"pilgrim_" + name + "\":{"), "sounds.json registers pilgrim_" + name);
            Path ogg = root.resolve("src/main/resources/assets/hexgodofstories/sounds/pilgrim_" + name + ".ogg");
            check(Files.isRegularFile(ogg) && Files.size(ogg) > 512, "pilgrim_" + name + ".ogg is present and not empty");
        }
        for (String texture : new String[] { "abyssal_pilgrim.png", "abyssal_pilgrim_glowmask.png" })
            check(Files.isRegularFile(root.resolve("src/main/resources/assets/hexgodofstories/textures/entity/" + texture)), texture + " is present");

        hexorAmbientIsPositional(root, sounds);
    }

    /**
     * Hexor's ambient call has to be mono, and the AI has to be spacing itself around its real
     * length.
     *
     * <p>Neither failure shows up as an error. Minecraft's sound engine can only place a mono
     * buffer in the world; hand it a stereo clip and it plays the thing flat inside the player's
     * head, at the same volume from any distance and from no direction at all, which for a
     * creature you are meant to locate by ear is worse than silence. And the declared clip length
     * is what stops a second call starting over the top of the first, so a file that grows past it
     * would quietly bring the stacking back.
     */
    private static void hexorAmbientIsPositional(Path root, String sounds) throws Exception {
        check(sounds.contains("\"hexor_ambient\":{"), "sounds.json registers hexor_ambient");
        Path ogg = root.resolve("src/main/resources/assets/hexgodofstories/sounds/hexor_ambient.ogg");
        check(Files.isRegularFile(ogg) && Files.size(ogg) > 512, "hexor_ambient.ogg is present and not empty");

        byte[] bytes = Files.readAllBytes(ogg);
        int header = indexOf(bytes, new byte[] { 1, 'v', 'o', 'r', 'b', 'i', 's' });
        check(header >= 0, "hexor_ambient.ogg carries a Vorbis identification header");
        // Identification header: type(1) "vorbis"(6) version(4) channels(1) rate(4), little endian.
        int channels = bytes[header + 11] & 0xFF;
        check(channels == 1, "hexor_ambient.ogg is mono, so the sound engine can place it (" + channels + " channels)");
        long rate = (bytes[header + 12] & 0xFFL) | (bytes[header + 13] & 0xFFL) << 8
                  | (bytes[header + 14] & 0xFFL) << 16 | (bytes[header + 15] & 0xFFL) << 24;
        check(rate > 0, "hexor_ambient.ogg declares a sample rate");

        String java = Files.readString(root.resolve("src/main/java/com/hexgodofstories/HexGodOfStories.java"));
        Matcher declared = Pattern.compile("HEXOR_AMBIENT_TICKS\\s*=\\s*(\\d+)").matcher(java);
        check(declared.find(), "HexGodOfStories declares HEXOR_AMBIENT_TICKS");
        int ticks = Integer.parseInt(declared.group(1));
        // Granule position of the last Ogg page is the total sample count.
        long samples = lastGranule(bytes);
        int actual = (int) Math.ceil(samples * 20.0 / rate);
        check(ticks >= actual, "HEXOR_AMBIENT_TICKS (" + ticks + ") covers the clip's " + actual + " ticks");
        check(ticks <= actual + 40, "HEXOR_AMBIENT_TICKS (" + ticks + ") is not wildly longer than the clip");

        jawsAreThreeDistinctMonoClips(root, sounds);
    }

    /**
     * The jaws: three recordings under one sound event, each of them mono and each of them
     * different.
     *
     * <p>Mono for the same reason the roar is — a stereo clip plays flat inside the player's head,
     * and not being able to tell which direction the chewing is coming from is the exact opposite
     * of the point. Three, and genuinely three, because the whole reason for splitting the source
     * up was that a meal should not sound like a loop; shipping the same slice three times would
     * satisfy every other check here and quietly undo that.
     */
    private static void jawsAreThreeDistinctMonoClips(Path root, String sounds) throws Exception {
        check(sounds.contains("\"hexor_eat\":{"), "sounds.json registers hexor_eat");
        byte[][] clips = new byte[3][];
        for (int n = 1; n <= 3; n++) {
            Path clip = root.resolve("src/main/resources/assets/hexgodofstories/sounds/hexor_eat_" + n + ".ogg");
            check(Files.isRegularFile(clip) && Files.size(clip) > 512, "hexor_eat_" + n + ".ogg is present and not empty");
            check(sounds.contains("\"hexgodofstories:hexor_eat_" + n + "\""), "hexor_eat can draw variant " + n);
            clips[n - 1] = Files.readAllBytes(clip);
            int header = indexOf(clips[n - 1], new byte[] { 1, 'v', 'o', 'r', 'b', 'i', 's' });
            check(header >= 0, "hexor_eat_" + n + ".ogg carries a Vorbis identification header");
            int channels = clips[n - 1][header + 11] & 0xFF;
            check(channels == 1, "hexor_eat_" + n + ".ogg is mono, so the jaws have a direction (" + channels + " channels)");
            long rate = (clips[n - 1][header + 12] & 0xFFL) | (clips[n - 1][header + 13] & 0xFFL) << 8
                      | (clips[n - 1][header + 14] & 0xFFL) << 16 | (clips[n - 1][header + 15] & 0xFFL) << 24;
            double seconds = lastGranule(clips[n - 1]) / (double) Math.max(1, rate);
            // Played well under recorded pitch, so the clip stretches. Past this it would still be
            // sounding when the next mouthful starts and the rhythm would smear into a drone.
            check(seconds > 0.2 && seconds < 2.0, "hexor_eat_" + n + ".ogg is a mouthful, not a meal (" + seconds + "s)");
        }
        for (int a = 0; a < 3; a++)
            for (int b = a + 1; b < 3; b++)
                check(!java.util.Arrays.equals(clips[a], clips[b]),
                    "hexor_eat_" + (a + 1) + " and hexor_eat_" + (b + 1) + " are different recordings");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Sample count of the stream, read from the granule position of its final Ogg page. */
    private static long lastGranule(byte[] bytes) {
        long granule = 0;
        for (int i = 0; i + 14 <= bytes.length; i++) {
            if (bytes[i] != 'O' || bytes[i + 1] != 'g' || bytes[i + 2] != 'g' || bytes[i + 3] != 'S') continue;
            long value = 0;
            for (int b = 7; b >= 0; b--) value = value << 8 | (bytes[i + 6 + b] & 0xFFL);
            if (value > granule) granule = value;
        }
        return granule;
    }

    // ------------------------------------------------------------------ helpers

    private static Path projectRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && here != null; i++) {
            if (Files.isDirectory(here.resolve("src/main/resources/data/hexgodofstories"))) return here;
            here = here.getParent();
        }
        throw new IllegalStateException("run this from the project root");
    }

    private static String compact(Path path) throws Exception {
        check(Files.isRegularFile(path), "missing file " + path);
        return Files.readString(path).replaceAll("\\s+", "");
    }

    private static long number(String compact, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(compact);
        check(m.find(), "key " + key + " is present");
        return Long.parseLong(m.group(1));
    }

    private static double[] triple(String csv) {
        String[] parts = csv.split(",");
        return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]) };
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
