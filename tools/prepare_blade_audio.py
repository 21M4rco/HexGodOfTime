"""Prepare the blades' recorded sounds from the recordings supplied for them.

Sources, as supplied (tools/audio_sources/blade/):
* pierce.mp3 (supplied as universfield-blade-piercing-body-352462.mp3): a blade driven into a body. Gravity Grasp's
  stab plays it on the tick the knife goes into the neck (server/GravityGrasp, STAB_IN).

Only prepared for Minecraft, as tools/import_scepter_audio.py does: mono (OpenAL only positions mono sources),
leading silence trimmed so it sounds on the tick the blade goes in, peak-normalised to -1 dBFS, a 12 ms tail fade,
and Ogg Vorbis, the only format Minecraft's sound engine reads.

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/prepare_blade_audio.py
"""
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "tools/audio_sources/blade"
OUT = ROOT / "src/main/resources/assets/hexgodofstories/sounds/blade"

# output name -> source file
FILES = {"pierce": "pierce.mp3"}


def prepare(data, rate):
    mono = data.mean(axis=1).astype(np.float64)
    # First sample within 50 dB of the peak, backed off 3 ms so the attack is not clipped.
    threshold = np.abs(mono).max() * 10 ** (-50 / 20)
    start = int(np.argmax(np.abs(mono) > threshold))
    mono = mono[max(0, start - int(rate * .003)):]
    # The recording's tail is silence: cut where it falls 60 dB under the peak for good, then fade.
    loud = np.nonzero(np.abs(mono) > np.abs(mono).max() * 10 ** (-60 / 20))[0]
    mono = mono[:loud[-1] + int(rate * .02)]
    mono *= 10 ** (-1 / 20) / np.abs(mono).max()
    fade = min(len(mono), int(rate * .012))
    mono[-fade:] *= np.linspace(1, 0, fade)
    return mono.astype(np.float32)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, source in FILES.items():
        data, rate = sf.read(SOURCES / source, always_2d=True)
        mono = prepare(data, rate)
        target = OUT / f"{name}.ogg"
        sf.write(target, mono, rate, format="OGG", subtype="VORBIS")
        print(f"{target.relative_to(ROOT)}: {len(mono) / rate:.2f}s mono at {rate} Hz from {source}")


if __name__ == "__main__":
    main()
