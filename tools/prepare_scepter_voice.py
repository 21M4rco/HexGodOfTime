"""Prepare the Scepter's charge, fire and sizzle sounds from the recordings supplied for them.

Sources, as supplied (tools/audio_sources/scepter/):
* charge.mp3: the stone filling, ending in a low drop, the "final sound".
* fire.mp3: the shot.
* sizzle.mp3 (supplied as dragon-studio-fire-sounds-405444.mp3): a fire crackling, heard from every hot hole
  for as long as it glows.

The charge takes ten seconds, and the stone fires itself the moment it is full: the drop has to land on
that moment. In the recording it begins 6.12 s in, after 0.3 s of silence, so the build-up before it is
lengthened, keeping its pitch, until the drop falls exactly FULL seconds after the sound starts. The
lengthening is WSOLA (waveform-similarity overlap-add): the build is re-read more slowly in overlapping
frames, each one taken where it best continues the one before, so nothing is resampled and the drone keeps
its pitch and texture. The opening swell and the drop itself are left exactly as recorded.

Both are otherwise only prepared for Minecraft, as tools/import_scepter_audio.py does: mono (OpenAL only
positions mono sources), leading silence trimmed, peak-normalised to -1 dBFS, a short tail fade, Ogg Vorbis.

The sizzle plays for as long as a hole glows, up to seventeen seconds, so it is a loop, and it is its
recording's own: from 16 s on, the recording is one twelve-second stretch and then that stretch again from
its start. One period of it is taken, from a moment in, and the recording's own continuation past the period
(the stretch's start again) is crossfaded into the loop's head, so the loop runs out of its end into its start
exactly as the recording runs on. As recorded it is a quiet fizz under sharp crackles, over 20 dB quieter than
the Scepter's other sounds for all that its crackles reach full scale: it is brought up to SIZZLE_LOUDNESS,
the crackles held under the ceiling by a look-ahead limiter fast enough to take only them. Loudness is
measured as BS.1770 does (K-weighted, 400 ms at a time), round the loop.

Requires numpy and soundfile (pip install numpy soundfile). Run from the repository root:
    python tools/prepare_scepter_voice.py          (every sound)
    python tools/prepare_scepter_voice.py sizzle   (only the named ones)
"""
from pathlib import Path
import sys

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


# Where in the recording the sizzle's loop is taken from, in seconds, and about how long its period is.
LOOP_FROM, PERIOD = 16.2, 12.0
# How loud the sizzle is: the median of its momentary loudness, in LUFS. A few dB under the impacts, and
# heard through the whole of a hole's cooling.
SIZZLE_LOUDNESS = -18
# The ceiling its crackles are held under, and how soon before a crackle and how long after it the limiter acts.
CEILING, ATTACK, RELEASE = 10 ** (-1.5 / 20), .0015, .012


def period(x, rate):
    """The recording's period of repetition near PERIOD, to the sample: where a second from LOOP_FROM + PERIOD on is found again."""
    a = x[int((LOOP_FROM + PERIOD) * rate):][:rate]
    lo = int((LOOP_FROM - .5) * rate)
    b = x[lo:lo + 2 * rate]
    n = len(a) + len(b)
    c = np.fft.irfft(np.fft.rfft(b, n) * np.conj(np.fft.rfft(a, n)), n)[:len(b) - len(a) + 1]
    energy = np.sqrt(np.convolve(b ** 2, np.ones(len(a)), mode="valid") * np.sum(a ** 2))
    best = int(np.argmax(c / np.maximum(energy, 1e-12)))
    match = c[best] / energy[best]
    assert match > .9, f"the recording does not repeat near {PERIOD} s (best match {match:.2f})"
    return int((LOOP_FROM + PERIOD) * rate) - (lo + best)


def k_weighted(x, rate):
    """{@code x} through BS.1770's K-weighting (its high shelf and its high-pass), round the loop.

    Applied as its magnitude only, which leaves the power, all loudness measures, exactly as the filters would.
    """
    z = np.exp(-2j * np.pi * np.fft.rfftfreq(len(x), 1 / rate) / rate)

    def biquad(b, a):
        return np.abs((b[0] + b[1] * z + b[2] * z * z) / (a[0] + a[1] * z + a[2] * z * z))

    k = np.tan(np.pi * 1681.974450955533 / rate)
    q, vh = .7071752369554196, 10 ** (3.999843853973347 / 20)
    vb, a0 = vh ** .4996667741545416, 1 + k / q + k * k
    shelf = biquad([(vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0],
                   [1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0])
    k, q = np.tan(np.pi * 38.13547087602444 / rate), .5003270373238773
    a0 = 1 + k / q + k * k
    highpass = biquad([1, -2, 1], [1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0])
    return np.fft.irfft(np.fft.rfft(x) * shelf * highpass, len(x))


def momentary(x, rate):
    """The loop's momentary loudness (BS.1770: 400 ms at a time, every 100 ms, round the loop), in LUFS."""
    k = k_weighted(x, rate) ** 2
    w, hop = int(.4 * rate), int(.1 * rate)
    ring = np.concatenate([k, k[:w]])
    sums = np.cumsum(np.concatenate([[0], ring]))
    starts = np.arange(0, len(x), hop)
    return -.691 + 10 * np.log10((sums[starts + w] - sums[starts]) / w + 1e-20)


def limit(x, rate):
    """{@code x}, a loop, held under CEILING: the gain each crackle needs is reached over ATTACK before it and let
    go over RELEASE after it, round the loop, so the loop stays seamless."""
    n, span = len(x), max(1, int(ATTACK * rate))
    need = np.minimum(1, CEILING / np.maximum(np.abs(x), 1e-12))
    windows = np.lib.stride_tricks.sliding_window_view
    # The least gain needed over the attack ahead, then ramped down over the attack: never more than is needed.
    ahead = windows(np.concatenate([need, need[:span - 1]]), span).min(axis=1)
    ramp = windows(np.concatenate([ahead[n - span + 1:], ahead]), span).mean(axis=1)
    alpha, gain, g = np.exp(-1 / (RELEASE * rate)), np.empty(n), 1.0
    for _ in range(2):  # twice round, so the release carries on across the loop's end into its start
        for i in range(n):
            g = min(ramp[i], 1 - (1 - g) * alpha)
            gain[i] = g
    return x * gain


def sizzle():
    x, rate = mono(SOURCES / "sizzle.mp3")
    n, start, seam = period(x, rate), int(LOOP_FROM * rate), int(rate * SEAM)
    loop = x[start:start + n].copy()
    # Its end runs on, in the recording, into the stretch's start again: that is crossfaded into the loop's head.
    fade = .5 - .5 * np.cos(np.pi * np.arange(seam) / seam)
    loop[:seam] = x[start + n:start + n + seam] * (1 - fade) + loop[:seam] * fade
    # Brought up by the gain that puts its median momentary loudness at SIZZLE_LOUDNESS, found by halving.
    lo, hi = 0., 48.
    for _ in range(16):
        mid = (lo + hi) / 2
        if np.median(momentary(limit(loop * 10 ** (mid / 20), rate), rate)) < SIZZLE_LOUDNESS:
            lo = mid
        else:
            hi = mid
    out = limit(loop * 10 ** (hi / 20), rate).astype(np.float32)
    sf.write(OUT / "sizzle.ogg", out, rate, format="OGG", subtype="VORBIS")
    # As the game will hear it: decoded, and looped.
    heard, _ = sf.read(OUT / "sizzle.ogg")
    steps = np.abs(np.diff(heard))
    wrap, loudness = abs(heard[0] - heard[-1]), np.median(momentary(heard, rate))
    print(f"sizzle.ogg: {len(heard) / rate:.3f}s loop (the recording repeats every {n / rate:.4f}s), brought up "
          f"{hi:.1f} dB to {loudness:.1f} LUFS, peak {20 * np.log10(np.abs(heard).max()):.2f} dBFS; its end runs into "
          f"its start by a step of {wrap:.4f}, where one sample runs into the next by {np.median(steps):.4f} at the median")
    assert len(heard) == n, "the loop must decode to exactly one period"
    assert wrap <= np.percentile(steps, 99), "the loop's end must run into its start as smoothly as the rest of it runs on"
    assert abs(loudness - SIZZLE_LOUDNESS) < .5 and np.abs(heard).max() < 1, "the sizzle must be as loud as meant, and never clip"


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[1:])
    for name, make in (("charge", charge), ("fire", fire), ("sizzle", sizzle)):
        if not only or name in only:
            make()
