"""Prepare the blades' recorded sounds from the recordings supplied for them.

Sources, as supplied (tools/audio_sources/blade/):
* pierce.mp3 (supplied as universfield-blade-piercing-body-352462.mp3): a blade driven into a body.
* stab_flesh.mp3 (supplied as olivia_parker-sword-stab-flesh-demo-310504.mp3): a blade into flesh, and the wet pull.
* rips_apart.mp3 (supplied as ragecore29-htf-head-or-body-rips-apart-481466.mp3): a body torn open.
Every stab (Gravity Grasp's into the neck, Complete Evisceration's through the gut) plays one of the three, never the
same twice running (server/StabSound).

Prepared for Minecraft as tools/import_scepter_audio.py does: mono (OpenAL only positions mono sources), leading
silence trimmed so it sounds on the tick the blade goes in, the silent tail cut, a 12 ms tail fade, and Ogg Vorbis, the
only format Minecraft's sound engine reads.

It is also made loud. Minecraft never plays a sound above full scale (a play's volume over one only carries it further),
so the loudness has to be in the file: as recorded its loudest moment is some 3.5 dB under the Scepter's blast and
impacts, and it is brought up to PIERCE_LOUDNESS, a few dB over them, its peaks held under the ceiling by a look-ahead
limiter. That is about as far as it goes cleanly: the recording is nearly all sharp transients, and past it the
limiter starts to hold down its body as well as its peaks. The rest is in the game, which plays it twice at once
(server/GravityGrasp). Loudness is measured as BS.1770 does (K-weighted, 400 ms at a time), as
tools/prepare_scepter_voice.py measures the sizzle.

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/prepare_blade_audio.py
"""
from pathlib import Path
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_scepter_voice import k_weighted  # noqa: E402  (BS.1770's K-weighting)

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "tools/audio_sources/blade"
OUT = ROOT / "src/main/resources/assets/hexgodofstories/sounds/blade"

# output name -> source file
FILES = {"pierce": "pierce.mp3", "pierce_2": "stab_flesh.mp3", "pierce_3": "rips_apart.mp3"}
# The loudest momentary loudness the stab is brought up to, in LUFS. The Scepter's blast and impacts reach about -13.
PIERCE_LOUDNESS = -10.5
# The ceiling its peaks are held under, and how soon before a peak and how long after it the limiter acts: slow enough
# to let go that the low squelch under the peaks is not torn up by it.
CEILING, ATTACK, RELEASE = 10 ** (-1.5 / 20), .002, .05


def momentary(x, rate):
    """Momentary loudness (BS.1770: 400 ms at a time, every 100 ms), in LUFS."""
    w, hop = int(.4 * rate), int(.1 * rate)
    k = k_weighted(np.concatenate([x, np.zeros(w)]), rate) ** 2
    sums = np.cumsum(np.concatenate([[0], k]))
    starts = np.arange(0, len(x), hop)
    return -.691 + 10 * np.log10((sums[starts + w] - sums[starts]) / w + 1e-20)


def limit(x, rate):
    """{@code x} held under CEILING: the gain each peak needs is reached over ATTACK before it and let go over RELEASE
    after it, never less gain than is needed."""
    n, span = len(x), max(1, int(ATTACK * rate))
    need = np.minimum(1, CEILING / np.maximum(np.abs(x), 1e-12))
    windows = np.lib.stride_tricks.sliding_window_view
    # The least gain needed over the attack ahead, then ramped down over the attack.
    ahead = windows(np.concatenate([need, np.ones(span - 1)]), span).min(axis=1)
    ramp = windows(np.concatenate([np.ones(span - 1), ahead]), span).mean(axis=1)
    alpha, gain, g = np.exp(-1 / (RELEASE * rate)), np.empty(n), 1.0
    for i in range(n):
        g = min(ramp[i], 1 - (1 - g) * alpha)
        gain[i] = g
    return x * gain, gain


def prepare(data, rate):
    mono = data.mean(axis=1).astype(np.float64)
    # First sample within 50 dB of the peak, backed off 3 ms so the attack is not clipped.
    threshold = np.abs(mono).max() * 10 ** (-50 / 20)
    start = int(np.argmax(np.abs(mono) > threshold))
    mono = mono[max(0, start - int(rate * .003)):]
    # The recording's tail is silence: cut where it falls 60 dB under the peak for good.
    loud = np.nonzero(np.abs(mono) > np.abs(mono).max() * 10 ** (-60 / 20))[0]
    mono = mono[:loud[-1] + int(rate * .02)]
    before = momentary(mono, rate).max()
    # Brought up by the gain that puts its loudest moment at PIERCE_LOUDNESS, found by halving.
    lo, hi = -24., 36.
    for _ in range(24):
        mid = (lo + hi) / 2
        if momentary(limit(mono * 10 ** (mid / 20), rate)[0], rate).max() < PIERCE_LOUDNESS:
            lo = mid
        else:
            hi = mid
    out, gain = limit(mono * 10 ** (hi / 20), rate)
    fade = min(len(out), int(rate * .012))
    out[-fade:] *= np.linspace(1, 0, fade)
    return out.astype(np.float32), before, hi, gain


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, source in FILES.items():
        data, rate = sf.read(SOURCES / source, always_2d=True)
        out, before, raised, gain = prepare(data, rate)
        target = OUT / f"{name}.ogg"
        sf.write(target, out, rate, format="OGG", subtype="VORBIS")
        # As the game will hear it: decoded.
        heard, _ = sf.read(target)
        loudness, peak = momentary(heard, rate).max(), np.abs(heard).max()
        print(f"{target.relative_to(ROOT)}: {len(heard) / rate:.2f}s mono at {rate} Hz from {source}; loudest moment "
              f"{before:.1f} -> {loudness:.1f} LUFS (+{raised:.1f} dB), peak {20 * np.log10(peak):.2f} dBFS, the limiter "
              f"taking at most {-20 * np.log10(gain.min()):.1f} dB and acting on {np.mean(gain < .999) * 100:.0f}% of it")
        assert abs(loudness - PIERCE_LOUDNESS) < .5 and peak < 1, "it must be as loud as meant, and never clip"


if __name__ == "__main__":
    main()
