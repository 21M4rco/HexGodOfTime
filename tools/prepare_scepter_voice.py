"""Prepare the Scepter's charge and fire sounds from the recordings supplied for them.

Sources, as supplied (tools/audio_sources/scepter/):
* charge.mp3: the stone filling, ending in a low drop, the "final sound".
* fire.mp3: the shot.

The charge takes ten seconds, and the stone fires itself the moment it is full: the drop has to land on
that moment. In the recording it begins 6.12 s in, after 0.3 s of silence, so the build-up before it is
lengthened, keeping its pitch, until the drop falls exactly FULL seconds after the sound starts. The
lengthening is WSOLA (waveform-similarity overlap-add): the build is re-read more slowly in overlapping
frames, each one taken where it best continues the one before, so nothing is resampled and the drone keeps
its pitch and texture. The opening swell and the drop itself are left exactly as recorded.

Both are otherwise only prepared for Minecraft, as tools/import_scepter_audio.py does: mono (OpenAL only
positions mono sources), leading silence trimmed, peak-normalised to -1 dBFS, a short tail fade, Ogg Vorbis.

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/prepare_scepter_voice.py
"""
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "tools/audio_sources/scepter"
OUT = ROOT / "src/main/resources/assets/hexgodofstories/sounds/scepter"

# Seconds from the press to a full stone: ScepterBlast.FULL_HOLD, in ticks of 1/20 s.
FULL = 200 / 20
# Where, in the recording, the build-up is stretched: after the opening swell, up to just before the drop.
BUILD_FROM, BUILD_TO = 1.00, 6.08
# Frames of WSOLA, and how far each may slide to find its best continuation.
FRAME, TOLERANCE = 2048, 512


def mono(path):
    data, rate = sf.read(path, always_2d=True)
    return data.mean(axis=1).astype(np.float64), rate


def trim(x, rate):
    """From the first sample within 50 dB of the peak, backed off 3 ms, as the import tool does."""
    threshold = np.abs(x).max() * 10 ** (-50 / 20)
    start = int(np.argmax(np.abs(x) > threshold))
    return x[max(0, start - int(rate * .003)):], max(0, start - int(rate * .003))


def finish(x, rate):
    peak = np.abs(x).max()
    if peak > 0:
        x = x * (10 ** (-1 / 20) / peak)
    fade = min(len(x), int(rate * .012))
    x[-fade:] *= np.linspace(1, 0, fade)
    return x.astype(np.float32)


def wsola(x, length):
    """{@code x} re-read to {@code length} samples at its own pitch."""
    hop = FRAME // 2
    window = np.hanning(FRAME + 1)[:FRAME]  # periodic Hann: halves overlapped at hop sum to one
    frames = int(np.ceil(length / hop)) + 1
    analysis = (len(x) - FRAME - 2 * TOLERANCE) / max(1, frames - 1)
    padded = np.concatenate([np.zeros(TOLERANCE), x, np.zeros(FRAME + 2 * TOLERANCE)])
    out = np.zeros(frames * hop + FRAME)
    previous = None
    for k in range(frames):
        ideal = int(round(k * analysis)) + TOLERANCE
        if previous is None:
            best = ideal
        else:
            # Where the last frame would naturally have gone on to, and the candidate that sounds most like it.
            natural = padded[previous + hop:previous + hop + FRAME]
            region = padded[ideal - TOLERANCE:ideal + TOLERANCE + FRAME]
            n = len(region) + FRAME
            corr = np.fft.irfft(np.fft.rfft(region, n) * np.conj(np.fft.rfft(natural, n)), n)[:2 * TOLERANCE + 1]
            best = ideal - TOLERANCE + int(np.argmax(corr))
        out[k * hop:k * hop + FRAME] += window * padded[best:best + FRAME]
        previous = best
    return out[:length]


SEAM = .02


def splice(a, b, rate):
    """{@code a} then {@code b}, crossfaded over SEAM so no seam clicks; the result is that much shorter."""
    n = min(int(rate * SEAM), len(a), len(b))
    fade = np.linspace(0, 1, n)
    return np.concatenate([a[:-n], a[-n:] * (1 - fade) + b[:n] * fade, b[n:]])


def drop_onset(x, rate, after):
    """The drop: where, past {@code after} seconds, three quarters of the sound first falls below 250 Hz.

    Nothing in the build is that deep for a moment together: at most three fifths of it; the drop is all bass.
    """
    w = int(rate * .02)
    for i in range(int(after * rate), len(x) - w, w // 2):
        p = np.abs(np.fft.rfft(x[i:i + w] * np.hanning(w))) ** 2
        f = np.fft.rfftfreq(w, 1 / rate)
        if p.sum() > 1e-9 and p[f < 250].sum() / p.sum() > .75 and np.sqrt(np.mean(x[i:i + w] ** 2)) > .05:
            return i / rate
    return None


def charge():
    x, rate = mono(SOURCES / "charge.mp3")
    source_drop = drop_onset(x, rate, BUILD_TO - .3)
    x, start = trim(x, rate)
    head_end, build_end = int(BUILD_FROM * rate) - start, int(BUILD_TO * rate) - start
    drop = int(source_drop * rate) - start
    # The build is stretched until the drop lands at FULL, the two crossfaded seams made good.
    length = int(round(FULL * rate)) - head_end - (drop - build_end) + 2 * int(rate * SEAM)
    head, build, tail = x[:head_end], x[head_end:build_end], x[build_end:]
    stretched = wsola(build, length)
    out = splice(splice(head, stretched, rate), tail, rate)
    # The recording ends in seconds of silence; the prepared sound stops a moment after the drop dies away.
    loud = np.nonzero(np.abs(out) > np.abs(out).max() * 10 ** (-60 / 20))[0]
    out = finish(out[:loud[-1] + int(rate * .05)], rate)
    landed = drop_onset(out.astype(np.float64), rate, FULL - 1)
    sf.write(OUT / "charge.ogg", out, rate, format="OGG", subtype="VORBIS")
    print(f"charge.ogg: {len(out) / rate:.2f}s, build {len(build) / rate:.2f}s -> {len(stretched) / rate:.2f}s "
          f"(x{len(stretched) / len(build):.2f}); drop at {source_drop:.3f}s in the recording, {landed:.3f}s in the sound")
    assert abs(landed - FULL) < .03, f"the drop must land on the full stone, not at {landed:.3f}s"


def fire():
    x, rate = mono(SOURCES / "fire.mp3")
    x, _ = trim(x, rate)
    out = finish(x, rate)
    sf.write(OUT / "fire.ogg", out, rate, format="OGG", subtype="VORBIS")
    print(f"fire.ogg: {len(out) / rate:.2f}s")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    charge()
    fire()
