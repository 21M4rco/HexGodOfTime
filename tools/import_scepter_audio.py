"""Fetch and prepare the Scepter's public-domain (CC0) sound effects.

The shot and the charge are not among them: they are recordings supplied for the mod, prepared by
tools/prepare_scepter_voice.py.

Nothing here is synthesised. Every file is a published CC0 recording, downloaded from a pinned
commit so the result is reproducible, then only prepared for Minecraft:

* down-mixed to mono, because OpenAL only positions and attenuates mono sources in the world;
* leading silence trimmed, so a shot sounds on the tick it is fired;
* peak-normalised to -1 dBFS and given a 12 ms tail fade, so no layer clips or clicks;
* a sizzle, besides, gently soft-limited up to SIZZLE: a hiss is noise, whose rare crackles peak far above
  the rest of it, so peak-normalised alone it sits well under the impacts it is heard with, and is lost;
* written as Ogg Vorbis, the only format Minecraft's sound engine reads.

One file is made from recordings rather than taken whole: the sizzle a hot hole keeps up while it cools,
which has to loop for as long as that, seamlessly. The recordings only hold a steady hiss for a third of a
second or so at a time, too short to loop without the repeat being heard, so the loop is re-assembled from
those steady stretches: short grains of them, each from a random point, overlap-added round a circle four
seconds long. Laid round a circle it has no seam; drawn at random it has no pattern; and every grain is
the recording's own sound, levelled to the same loudness.

Sources (all CC0 1.0 Universal, see SCEPTER_AUDIO_CREDITS.md):

* Kenney, "Sci-fi Sounds" (https://kenney.nl/assets/sci-fi-sounds), via the Mcamento8/open-game-sfx-index mirror.
* Team Forbidden, Warfork weapon sounds (warfork_assets_cc0.txt), via lavenderdotpet/CC0-Public-Domain-Sounds.
* "80 CC0 RPG SFX" (fire spells), from the same collection, which is CC0 as a whole (its LICENSE).

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/import_scepter_audio.py            (every file)
    python tools/import_scepter_audio.py sizzle_0   (only the named ones)
"""
from pathlib import Path
import io
import sys
import urllib.request

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/hexgodofstories/sounds/scepter"

KENNEY = "https://raw.githubusercontent.com/Mcamento8/open-game-sfx-index/34bbe8b525b5ceff3804915ea745532af11f6055/audio/sci-fi-sounds/"
WARFORK = "https://raw.githubusercontent.com/lavenderdotpet/CC0-Public-Domain-Sounds/f2b6264f9ab89fabc266914c3654685d68c5a39b/warfork-cc0/sounds/weapons/"
RPG = "https://raw.githubusercontent.com/lavenderdotpet/CC0-Public-Domain-Sounds/f2b6264f9ab89fabc266914c3654685d68c5a39b/80-CC0-RPG-SFX/"

# How loud a sizzle's loudest second is, in dBFS: a few dB under the impacts, where it is heard with them and
# after them. The soft limit that brings it there only touches its rarest peaks (a fifth of a percent of it).
SIZZLE = -16

# output name -> (source url, keep leading silence, loudness in dBFS, or None for peak-normalised only)
FILES = {
    # Contact: crunching energy detonations, plus a sub-bass body for charged impacts.
    **{f"impact_{i}": (KENNEY + f"explosionCrunch_00{i}.ogg", False, None) for i in range(5)},
    **{f"boom_{i}": (KENNEY + f"lowFrequency_explosion_00{i}.ogg", False, None) for i in range(2)},
    "blast": (WARFORK + "rocket_strong_explosion.ogg", False, None),
    # A beam burning into a body.
    **{f"burn_{i}": (WARFORK + f"laser_hit{i}.ogg", False, None) for i in range(3)},
    # What it hit hissing hot: the three steadiest of the fire spells, a long "tshhh" each.
    **{f"sizzle_{i}": (RPG + f"spell_fire_0{n}.ogg", False, SIZZLE) for i, n in enumerate((2, 3, 4))},
}


# A loop made from grains of the steady stretches (url, from, to, in seconds) of recordings; see above.
LOOPS = {
    "sizzle_loop": ([(RPG + "spell_fire_03.ogg", .18, .72), (RPG + "spell_fire_02.ogg", .06, .40),
                     (RPG + "spell_fire_04.ogg", .18, .36)], 4.0),
}
GRAIN, SEED = .06, 7


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def prepare(data, rate, keep_head):
    mono = data.mean(axis=1) if data.ndim == 2 else data
    mono = mono.astype(np.float64)
    if not keep_head:
        # First sample within 50 dB of the peak, backed off 3 ms so the attack is not clipped.
        threshold = np.abs(mono).max() * 10 ** (-50 / 20)
        start = int(np.argmax(np.abs(mono) > threshold))
        mono = mono[max(0, start - int(rate * .003)):]
    peak = np.abs(mono).max()
    if peak > 0:
        mono *= 10 ** (-1 / 20) / peak
    fade = min(len(mono), int(rate * .012))
    mono[-fade:] *= np.linspace(1, 0, fade)
    return mono


def loudest(x, rate):
    """How loud {@code x}'s loudest second is (the whole of it, if shorter), in dBFS."""
    n = int(rate)
    if len(x) <= n:
        return 20 * np.log10(np.sqrt(np.mean(x ** 2)))
    return 20 * np.log10(max(np.sqrt(np.mean(x[i:i + n] ** 2)) for i in range(0, len(x) - n + 1, n // 8)))


def soft_limit(x, rate, loudness):
    """{@code x} soft-limited (tanh) just hard enough that its loudest second reaches {@code loudness}, peaking at -1 dBFS.

    tanh has no memory, so a loop stays seamless, and it passes silence as silence, so a tail fade stays faded.
    """
    x = x / np.abs(x).max()
    for drive in np.geomspace(.5, 50, 2000):
        out = np.tanh(drive * x)
        out *= 10 ** (-1 / 20) / np.abs(out).max()
        if loudest(out, rate) >= loudness:
            return out
    raise ValueError(f"no soft limit brings it to {loudness} dBFS")


def granular(stretches, rate, length):
    """Grains of {@code stretches}, each levelled to one loudness, overlap-added round a circle {@code length} s long.

    Grains overlap by half under a sine window, whose squares sum to one: the grains are unrelated noise, so it
    is their power that must add up evenly, not their amplitude.
    """
    rng = np.random.default_rng(SEED)
    n, g = int(length * rate), int(GRAIN * rate)
    hop = g // 2
    n -= n % hop
    window = np.sin(np.pi * (np.arange(g) + .5) / g)
    levelled = [s / np.sqrt(np.mean(s ** 2)) for s in stretches]
    out = np.zeros(n)
    for k in range(n // hop):
        s = levelled[rng.integers(len(levelled))]
        start = int(rng.integers(0, len(s) - g))
        np.add.at(out, (np.arange(g) + k * hop) % n, s[start:start + g] * window)
    return out


def main(only):
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (sources, length) in LOOPS.items():
        if only and name not in only:
            continue
        stretches, rate = [], None
        for url, a, b in sources:
            data, rate = sf.read(io.BytesIO(fetch(url)), always_2d=True)
            mono = data.mean(axis=1)
            stretches.append(mono[int(a * rate):int(b * rate)])
        loop = soft_limit(granular(stretches, rate, length), rate, SIZZLE).astype(np.float32)
        target = OUT / f"{name}.ogg"
        sf.write(target, loop, rate, format="OGG", subtype="VORBIS")
        print(f"{target.relative_to(ROOT)}: {len(loop) / rate:.2f}s loop from {len(sources)} recordings, {loudest(loop, rate):.1f} dBFS")
    for name, (url, keep_head, loudness) in FILES.items():
        if only and name not in only:
            continue
        data, rate = sf.read(io.BytesIO(fetch(url)), always_2d=True)
        mono = prepare(data, rate, keep_head)
        if loudness is not None:
            mono = soft_limit(mono, rate, loudness)
        mono = mono.astype(np.float32)
        target = OUT / f"{name}.ogg"
        sf.write(target, mono, rate, format="OGG", subtype="VORBIS")
        print(f"{target.relative_to(ROOT)}: {len(mono) / rate:.2f}s from {url.rsplit('/', 1)[1]}, {loudest(mono, rate):.1f} dBFS")


if __name__ == "__main__":
    main(set(sys.argv[1:]))
