package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

/**
 * The sound of a blade going into a body: one of three recordings (sounds/blade/pierce, pierce_2, pierce_3;
 * tools/prepare_blade_audio.py), a different one from the stab before, every time. Gravity Grasp's stab into the neck and
 * Complete Evisceration's blade through the gut both take theirs from here.
 */
final class StabSound {
    private StabSound() {}

    private static int last = -1;

    static SoundEvent next(RandomSource random) {
        int i;
        do i = random.nextInt(3); while (i == last);
        last = i;
        return switch (i) {
            case 0 -> HexGodOfStories.BLADE_PIERCE.get();
            case 1 -> HexGodOfStories.BLADE_PIERCE_2.get();
            default -> HexGodOfStories.BLADE_PIERCE_3.get();
        };
    }
}
