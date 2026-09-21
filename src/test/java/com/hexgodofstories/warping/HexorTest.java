package com.hexgodofstories.warping;

import com.hexgodofstories.warping.leviathan.EnoughIsEnough;
import com.hexgodofstories.warping.leviathan.HexorBlow;
import com.hexgodofstories.warping.leviathan.LeviathanAttack;
import com.hexgodofstories.warping.leviathan.TrillOfTheHunt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        thirtySecondsEach();
        theClockCountsPresence();
        aDecidedTargetIsOnlyEverKilled();
        everyBlowGoesThroughTheScale(root);
        theHuntDoesNotNeedAnAudience(root);
        System.out.println("HexorTest: blows are weighed against what they land on, the crowd is felt, and the clock runs out.");
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

    // ------------------------------------------------------------------ Enough is Enough

    /**
     * The clock is thirty seconds, and it belongs to one thing rather than to the sea.
     */
    private static void thirtySecondsEach() {
        EnoughIsEnough.reset();
        check(EnoughIsEnough.PATIENCE_TICKS == 600, "thirty seconds at twenty ticks a second");
        UUID swimmer = UUID.randomUUID(), other = UUID.randomUUID();
        check(!EnoughIsEnough.marked(swimmer), "something the sea has never met is not decided about");
        check(EnoughIsEnough.remaining(swimmer) == EnoughIsEnough.PATIENCE_TICKS, "and has its whole thirty seconds");

        long now = 0;
        for (int tick = 10; tick < EnoughIsEnough.PATIENCE_TICKS; tick += 10) {
            now = tick;
            EnoughIsEnough.present(swimmer, now, 10);
            check(!EnoughIsEnough.marked(swimmer), "at " + tick + " ticks it is still being played with");
        }
        EnoughIsEnough.present(swimmer, now += 10, 10);
        check(EnoughIsEnough.marked(swimmer), "at six hundred ticks it is not");
        check(!EnoughIsEnough.marked(other), "and the thing that arrived later still is");

        // A second arrival, thirty seconds behind the first, is on its own clock the whole way.
        for (int tick = 0; tick < EnoughIsEnough.PATIENCE_TICKS; tick += 10) EnoughIsEnough.present(other, now += 10, 10);
        check(EnoughIsEnough.marked(other), "which runs out in its own time");

        EnoughIsEnough.forget(swimmer);
        check(!EnoughIsEnough.marked(swimmer), "being killed hands back a full thirty seconds");
        EnoughIsEnough.reset();
    }

    /**
     * Presence, not wall time.
     *
     * <p>Nothing can be hunted while its chunk is unloaded, so nothing should be spending its
     * patience there either: an animal that sat in an unloaded chunk for an hour has used none of
     * its thirty seconds, and something gone long enough is forgotten and starts again.
     */
    private static void theClockCountsPresence() {
        EnoughIsEnough.reset();
        UUID drifter = UUID.randomUUID();
        long now = 0;
        for (int tick = 0; tick < 300; tick += 10) EnoughIsEnough.present(drifter, now += 10, 10);
        check(EnoughIsEnough.remaining(drifter) == 300, "half spent after fifteen seconds in the water");

        // Gone, but not long enough to be forgotten: the clock is where it was left.
        now += EnoughIsEnough.FORGET_TICKS - 40;
        EnoughIsEnough.sweep(now);
        check(EnoughIsEnough.remaining(drifter) == 300, "an hour in an unloaded chunk costs it nothing");
        for (int tick = 0; tick < 300; tick += 10) EnoughIsEnough.present(drifter, now += 10, 10);
        check(EnoughIsEnough.marked(drifter), "and the rest of the thirty seconds finishes it");

        // Gone for good.
        EnoughIsEnough.sweep(now + EnoughIsEnough.FORGET_TICKS);
        check(!EnoughIsEnough.marked(drifter), "something the sea has not seen for a minute is forgotten");
        check(EnoughIsEnough.tracked() == 0, "and its clock is not kept");

        for (int i = 0; i < EnoughIsEnough.MAX_TRACKED + 50; i++) EnoughIsEnough.present(UUID.randomUUID(), 1, 10);
        check(EnoughIsEnough.tracked() <= EnoughIsEnough.MAX_TRACKED, "the table is bounded (" + EnoughIsEnough.tracked() + ")");
        EnoughIsEnough.reset();
    }

    /**
     * Past zero there is nothing in the repertoire that is not a kill.
     *
     * <p>The scream and the vortex are area denial, the coil is a set piece with a way out of it
     * and the feint is a lie: all four are things the creature does while it is still enjoying
     * itself. What is left has to be the jaws, the two patterns that run the body through the prey,
     * the grab that leads to being dragged under, and the leaps.
     */
    private static void aDecidedTargetIsOnlyEverKilled() {
        Set<LeviathanAttack> playing = EnumSet.of(LeviathanAttack.FAKE_ATTACK, LeviathanAttack.VOID_SCREAM,
            LeviathanAttack.WATER_VORTEX, LeviathanAttack.BODY_CRUSH, LeviathanAttack.TAIL_SWEEP,
            LeviathanAttack.SURFACE_RAM);
        Set<LeviathanAttack> seen = EnumSet.noneOf(LeviathanAttack.class);
        for (int step = 0; step <= 100; step++) {
            float roll = step / 100f;
            for (double distance : new double[] { 4, 20, 43, 44, 45, 80, 140 }) {
                for (boolean holding : new boolean[] { false, true }) {
                    for (boolean airborne : new boolean[] { false, true }) {
                        for (boolean canLeap : new boolean[] { false, true }) {
                            LeviathanAttack pick = EnoughIsEnough.strike(roll, distance, holding, airborne, canLeap);
                            if (pick.lethal() && !playing.contains(pick)) { seen.add(pick); continue; }
                            throw new AssertionError("a decided target was offered " + pick
                                + " at roll " + roll + ", " + distance + " blocks, holding=" + holding);
                        }
                    }
                }
            }
        }
        check(true, "no roll, range or posture answers a decided target with anything but a kill");
        check(seen.contains(LeviathanAttack.PREDATORY_BITE) && seen.contains(LeviathanAttack.DRAG_BELOW),
            "it bites and it drags");
        check(seen.contains(LeviathanAttack.SKY_LEAP), "and it leaves the water after anything that does");

        check(EnoughIsEnough.strike(0.9f, 5, true, false, false) == LeviathanAttack.AIR_THROW
            && EnoughIsEnough.strike(0.1f, 5, true, false, false) == LeviathanAttack.DRAG_BELOW,
            "something already in the jaws is taken down or thrown, never let go of");
        check(EnoughIsEnough.strike(0.5f, 5, false, false, true) == LeviathanAttack.SKY_LEAP,
            "an available leap is always taken");
        for (float roll : new float[] { 0f, 0.5f, 0.99f })
            check(EnoughIsEnough.strike(roll, 200, false, false, false).range >= 40,
                "a distant target is answered by something whose run covers the distance");
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

    /**
     * The hunt is not gated on anybody being in the dimension to see it.
     *
     * <p>This is a property of one line: the upkeep that holds the hunted thing's chunk, and pulls
     * a remembered one back in when there is nothing loaded, must not sit behind the player check
     * that the repositioning does. There is no way to ask that of a running world from here, so it
     * is asked of the source.
     */
    private static void theHuntDoesNotNeedAnAudience(Path root) throws Exception {
        List<String> lines = Files.readAllLines(root.resolve(
            "src/main/java/com/hexgodofstories/warping/leviathan/PilgrimWarden.java"));
        String call = null;
        for (String line : lines) if (line.contains("keepHunting(level")) { call = line; break; }
        check(call != null, "the realm's upkeep still keeps the hunt going");
        check(!call.contains("players"), "and does it whether or not anybody is in the dimension");
        String source = String.join("\n", lines);
        check(source.contains("rememberQuarry("), "the realm remembers what was left in it");
        check(source.contains("EnoughIsEnough.present("), "and spends every occupant's clock as it sweeps");
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
