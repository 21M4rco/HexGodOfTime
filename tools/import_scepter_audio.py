"""Fetch and prepare the Scepter's public-domain (CC0) sound effects.

Nothing here is synthesised. Every file is a published CC0 recording, downloaded from a pinned
commit so the result is reproducible, then only prepared for Minecraft:

* down-mixed to mono, because OpenAL only positions and attenuates mono sources in the world;
* leading silence trimmed, so a shot sounds on the tick it is fired;
* peak-normalised to -1 dBFS and given a 12 ms tail fade, so no layer clips or clicks;
* written as Ogg Vorbis, the only format Minecraft's sound engine reads.

Sources (both CC0 1.0 Universal, see SCEPTER_AUDIO_CREDITS.md):

* Kenney, "Sci-fi Sounds" (https://kenney.nl/assets/sci-fi-sounds), via the Mcamento8/open-game-sfx-index mirror.
* Team Forbidden, Warfork weapon sounds (warfork_assets_cc0.txt), via lavenderdotpet/CC0-Public-Domain-Sounds.

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/import_scepter_audio.py
"""
from pathlib import Path
import io
import urllib.request

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/hexgodofstories/sounds/scepter"

KENNEY = "https://raw.githubusercontent.com/Mcamento8/open-game-sfx-index/34bbe8b525b5ceff3804915ea745532af11f6055/audio/sci-fi-sounds/"
WARFORK = "https://raw.githubusercontent.com/lavenderdotpet/CC0-Public-Domain-Sounds/f2b6264f9ab89fabc266914c3654685d68c5a39b/warfork-cc0/sounds/weapons/"

# output name -> (source url, keep leading silence, loop)
FILES = {
    # Tapped shots: five takes of the same large laser, picked at random per shot.
    **{f"shot_{i}": (KENNEY + f"laserLarge_00{i}.ogg", False, False) for i in range(5)},
    # A charged release: the Electrobolt's full discharge.
    "beam": (WARFORK + "electrobolt_strong.ogg", False, False),
    # Held charge: the lasergun's sustained hum. Authored as a seamless loop, so it is kept whole.
    "charge_loop": (WARFORK + "laser_strong_hum.ogg", True, True),
    # Contact: crunching energy detonations, plus a sub-bass body for charged impacts.
    **{f"impact_{i}": (KENNEY + f"explosionCrunch_00{i}.ogg", False, False) for i in range(5)},
    **{f"boom_{i}": (KENNEY + f"lowFrequency_explosion_00{i}.ogg", False, False) for i in range(2)},
    "blast": (WARFORK + "rocket_strong_explosion.ogg", False, False),
    # A beam burning into a body.
    **{f"burn_{i}": (WARFORK + f"laser_hit{i}.ogg", False, False) for i in range(3)},
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def prepare(data, rate, keep_head, loop):
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
    if not loop:
        fade = min(len(mono), int(rate * .012))
        mono[-fade:] *= np.linspace(1, 0, fade)
    return mono.astype(np.float32)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (url, keep_head, loop) in FILES.items():
        data, rate = sf.read(io.BytesIO(fetch(url)), always_2d=True)
        mono = prepare(data, rate, keep_head, loop)
        target = OUT / f"{name}.ogg"
        sf.write(target, mono, rate, format="OGG", subtype="VORBIS")
        print(f"{target.relative_to(ROOT)}: {len(mono) / rate:.2f}s from {url.rsplit('/', 1)[1]}")


if __name__ == "__main__":
    main()
