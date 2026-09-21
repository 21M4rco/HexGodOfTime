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
            check(parent.equals(parents.get(bone)), bone + " is parented to " + parent);
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
