package com.hexgodofstories.warping;

import com.hexgodofstories.warping.leviathan.HexorBlow;
import com.hexgodofstories.warping.leviathan.TrillOfTheHunt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * How hard Hexor hits, and how much the state of its ocean changes that.
 *
 * <p>Both of the things checked here are arithmetic that only shows up as a feeling in play: a
 * blow that is proportional to the pool it lands on cannot be seen to be wrong until somebody
 * spends two minutes chewing on a warden, and a passive that reads the realm's population is
 * invisible until the sea is full. Neither needs a Minecraft world to be asked, so the build asks.
 *
 * <p>The last check is a different kind: it reads the creature's own source and insists that every
 * blow it lands still goes through the scale. A pattern added later that calls {@code hurt} with a
 * raw number would be a silent hole in all of this, and would fail no other test.
 */
public final class HexorTest {
    /** A player, a warden, a wither, the dragon, an iron golem, and something modded and huge. */
    private static final float PLAYER = 20f, WARDEN = 500f, WITHER = 300f, DRAGON = 200f, GOLEM = 100f, MODDED = 10000f;

    /** The blows the attack patterns actually deal, as they are written in the combat controller. */
    private static final float[] PATTERN_BLOWS = { 16f, 15f, 14f, 11f, 10f, 9f, 18f, 6f, 5f, 3f, 2.5f, 2f };

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        peopleAreUntouched();
        blowsAreAShareOfThePool();
        aWardenIsAToy();
        absurdPoolsStayFinite();
        aloneItHuntsAsItAlwaysDid();
        aFullSeaWindsItUp();
        theFrenzyThresholdIsActuallyCrossed(root);
        everyBlowGoesThroughTheScale(root);
        System.out.println("HexorTest: blows are weighed against what they land on, and the ocean's crowd is felt.");
    }

    // ------------------------------------------------------------------ the blow

    /**
     * The hunt as written stays as written.
     *
     * <p>Every number in the combat controller was balanced against a swimmer, and a swimmer is
     * what this creature is mostly pointed at. If any of them moved for a player the change would
     * have quietly rebalanced the whole encounter rather than only the boss fight it is for.
     */
    private static void peopleAreUntouched() {
        for (float blow : PATTERN_BLOWS) {
            check(HexorBlow.against(blow, PLAYER) == blow, "a player takes " + blow + " exactly as written");
            check(HexorBlow.against(blow, HexorBlow.PIVOT) == blow, "so does anything up to the pivot");
            check(HexorBlow.against(blow, 1f) == blow, "and so does something with one point left");
        }
        check(HexorBlow.blowsToKill(14f, PLAYER) == 2, "a player is still two bites");
    }

    private static void blowsAreAShareOfThePool() {
        float previous = 0f;
        for (float pool : new float[] { HexorBlow.PIVOT, GOLEM, DRAGON, WITHER, WARDEN, MODDED }) {
            float dealt = HexorBlow.against(10f, pool);
            check(dealt >= previous, "a bigger pool never takes a smaller blow (" + pool + " took " + dealt + ")");
            check(dealt >= 10f, "and never less than the blow as written");
            previous = dealt;
        }
        check(HexorBlow.against(10f, 80f) == 20f, "twice the pivot is twice the blow");
        check(HexorBlow.against(10f, WARDEN) == 125f, "a warden takes a quarter of its pool from a bite");

        // The property the whole design rests on: above the pivot, time to kill stops depending on
        // the size of the health bar at all. Every one of these is the same handful of blows.
        for (float blow : new float[] { 18f, 16f, 14f, 10f }) {
            int expected = (int) Math.ceil(HexorBlow.PIVOT / blow);
            for (float pool : new float[] { GOLEM, DRAGON, WITHER, WARDEN, MODDED }) {
                int blows = HexorBlow.blowsToKill(blow, pool);
                check(blows == expected, "a " + blow + " blow finishes a " + pool + " point pool in " + blows
                    + " blows, the same as every other pool");
            }
        }
    }

    /** The point of the exercise, stated as the thing that was actually wrong. */
    private static void aWardenIsAToy() {
        int before = (int) Math.ceil(WARDEN / 14f);
        int now = HexorBlow.blowsToKill(14f, WARDEN);
        check(before >= 35, "a warden used to be " + before + " bites");
        check(now <= 4, "and is now " + now);
        check(HexorBlow.blowsToKill(16f, WARDEN) <= 3, "a lunge finishes one in three");
        // At a full sea it is worse again, because the passive multiplies the blow before the pool
        // is weighed against it.
        float furious = 14f * TrillOfTheHunt.violence(1f);
        check(HexorBlow.blowsToKill(furious, WARDEN) <= 2, "and two, with the water full");
    }

    private static void absurdPoolsStayFinite() {
        check(Float.isFinite(HexorBlow.against(18f, Float.POSITIVE_INFINITY)), "an infinite pool still deals a number");
        check(HexorBlow.against(18f, Float.NaN) == 18f, "a pool that is not a number takes the blow as written");
        check(HexorBlow.against(18f, -5f) == 18f, "so does a negative one");
        check(HexorBlow.against(0f, WARDEN) == 0f, "a blow of nothing stays nothing");
        check(HexorBlow.against(18f, Float.MAX_VALUE) == 18f * HexorBlow.CEILING, "the ceiling is what holds it");
    }

    // ------------------------------------------------------------------ Trill of the Hunt

    private static void aloneItHuntsAsItAlwaysDid() {
        for (int occupants : new int[] { 0, 1 }) {
            float thrill = TrillOfTheHunt.thrill(occupants);
            check(thrill == 0f, "with " + occupants + " in the realm the passive is dormant");
            check(TrillOfTheHunt.urgency(thrill) == 1.0, "it swims at the speed it was written with");
            check(TrillOfTheHunt.violence(thrill) == 1f, "it hits as hard as it was written to");
            check(TrillOfTheHunt.patienceCeiling(thrill) == 1f, "nothing caps its patience");
            check(TrillOfTheHunt.frenzyFloor(thrill) == 0f, "and nothing floors its frenzy");
            check(TrillOfTheHunt.pressureStep(thrill) == 1, "the commit clock runs at one tick a tick");
        }
    }

    private static void aFullSeaWindsItUp() {
        float previousThrill = -1f, previousPlay = Float.MAX_VALUE;
        double previousSpeed = 0;
        for (int occupants = 0; occupants <= TrillOfTheHunt.CROWD + 6; occupants++) {
            float thrill = TrillOfTheHunt.thrill(occupants);
            check(thrill >= previousThrill, "the thrill never falls as the water fills (" + occupants + ")");
            check(thrill <= 1f, "and never passes one");
            double speed = TrillOfTheHunt.urgency(thrill);
            check(speed >= previousSpeed, "nor does the speed it asks for");
            // The state roll's own arithmetic, at the start of a hunt: patience against frenzy.
            float play = TrillOfTheHunt.patienceCeiling(thrill) * (1f - TrillOfTheHunt.frenzyFloor(thrill));
            check(play <= previousPlay + 1.0E-6f, "and the share of the hunt spent playing only ever falls");
            previousThrill = thrill; previousSpeed = speed; previousPlay = play;
        }
        check(TrillOfTheHunt.thrill(TrillOfTheHunt.CROWD) == 1f, "a full sea is the top of the curve");
        check(TrillOfTheHunt.thrill(TrillOfTheHunt.CROWD * 4) == 1f, "and nothing past it gives any more");

        float full = TrillOfTheHunt.thrill(TrillOfTheHunt.CROWD);
        float alonePlay = TrillOfTheHunt.patienceCeiling(0f) * (1f - TrillOfTheHunt.frenzyFloor(0f));
        float crowdPlay = TrillOfTheHunt.patienceCeiling(full) * (1f - TrillOfTheHunt.frenzyFloor(full));
        check(crowdPlay < alonePlay * 0.1f, "a full sea leaves under a tenth of the playing (" + crowdPlay + ")");
        check(TrillOfTheHunt.urgency(full) > 1.3 && TrillOfTheHunt.urgency(full) < 1.6, "it swims half again as fast");
        check(TrillOfTheHunt.violence(full) > 1.4f && TrillOfTheHunt.violence(full) < 2f, "and hits half again as hard");
        check(TrillOfTheHunt.pressureStep(full) == 3, "the commit clock runs three times over");
    }

    /**
     * The floor has to be high enough to actually cross the line the AI draws.
     *
     * <p>"It stops playing" is not a mood, it is a branch: past a frenzy threshold the state roll
     * stops offering the toying states at all. The threshold lives in the AI and the floor lives in
     * the passive, so this reads the AI's own number rather than restating it here.
     */
    private static void theFrenzyThresholdIsActuallyCrossed(Path root) throws Exception {
        String ai = Files.readString(root.resolve("src/main/java/com/hexgodofstories/warping/leviathan/AbyssalPilgrimAI.java"));
        Matcher m = Pattern.compile("frenzy\\s*>\\s*([0-9.]+)f").matcher(ai);
        check(m.find(), "the AI still has a frenzy threshold for abandoning the repertoire");
        float threshold = Float.parseFloat(m.group(1));
        float floor = TrillOfTheHunt.frenzyFloor(TrillOfTheHunt.thrill(TrillOfTheHunt.CROWD));
        check(floor > threshold, "a full sea floors frenzy at " + floor + ", past the AI's " + threshold);
        check(TrillOfTheHunt.frenzyFloor(TrillOfTheHunt.thrill(2)) < threshold,
            "one extra swimmer is not enough to get there on its own");
    }

    // ------------------------------------------------------------------ the source itself

    /**
     * Every blow the creature lands is weighed before it is dealt.
     *
     * <p>{@code attackDamage} names who did it; {@code attackAmount} decides how much. They are
     * separate calls, so a new pattern can reach for one and forget the other, and the result
     * would be a strike that silently ignores both the victim's health pool and the passive.
     */
    private static void everyBlowGoesThroughTheScale(Path root) throws Exception {
        Path leviathan = root.resolve("src/main/java/com/hexgodofstories/warping/leviathan");
        int checked = 0;
        try (Stream<Path> files = Files.walk(leviathan)) {
            List<Path> java = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
            for (Path file : java) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains(".hurt(") || !line.contains("attackDamage(")) continue;
                    checked++;
                    check(line.contains("attackAmount("), file.getFileName() + ":" + (i + 1)
                        + " weighs its blow before dealing it");
                }
            }
        }
        check(checked >= 2, "found the creature's blows to check (" + checked + ")");
    }

    // ------------------------------------------------------------------ plumbing

    private static Path projectRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && here != null; i++) {
            if (Files.isDirectory(here.resolve("src/main/java/com/hexgodofstories/warping/leviathan"))) return here;
            here = here.getParent();
        }
        throw new IllegalStateException("run this from the project root");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        System.out.println("  ok: " + what);
    }
}
