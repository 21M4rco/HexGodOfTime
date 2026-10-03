"""Blood, seen up close: the drop sprites and the ground stains (client Blood, BloodStains).

Every texture is grey and white with an alpha shape: the game tints it, so one set serves fresh blood and dried,
lit and in the dark. Shapes are drawn at eight times their size and averaged down, so their edges are smooth at any
angle, and every stain keeps a transparent border, because a stain is drawn clipped to the blocks under it with
texture coordinates that run past its own edge (the textures clamp, so past the edge is the border).

  pool_N.png        a puddle: thin and bright at its rim, thick and dark in its middle, a crisp meniscus all round,
                    lobes and a few satellite drops where it spread unevenly.
  pool_N_sheen.png  where that puddle shines while it is wet: the sky's reflection, kept off its rim.
  spatter_N.png     a drop's mark: 0 round with a splashed crown (one that fell), the rest elongated along +V, the
                    way the drop was travelling, with a tail and a fling of droplets past it.
  particle/blood_N  the drops themselves, round and oval, white enough that the tint is the colour.

Requires numpy and Pillow. Re-running rewrites the same images (seeded).
"""
from pathlib import Path
import json

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories'
STAINS = ROOT / 'textures/blood'
PARTICLES = ROOT / 'textures/particle'
SIZE = 64
SS = 8  # supersampling
POOLS, SPATTERS, DROPS = 6, 4, 4


def grid(size, ss):
    """Sample centres over [-1, 1]^2 at size*ss resolution; v grows downward in the image (texture V)."""
    n = size * ss
    c = (np.arange(n) + .5) / n * 2 - 1
    return np.meshgrid(c, c)


def down(a, ss):
    n = a.shape[0] // ss
    return a.reshape(n, ss, n, ss).mean(axis=(1, 3))


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def value_noise(u, v, scale, rng):
    """Smooth value noise over the sample grid, from a small random lattice."""
    cells = rng.random((scale + 3, scale + 3))
    x = (u + 1) / 2 * scale + 1
    y = (v + 1) / 2 * scale + 1
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = smooth(0, 1, x - x0), smooth(0, 1, y - y0)
    a = cells[y0, x0] * (1 - fx) + cells[y0, x0 + 1] * fx
    b = cells[y0 + 1, x0] * (1 - fx) + cells[y0 + 1, x0 + 1] * fx
    return a * (1 - fy) + b * fy


def fbm(u, v, rng, octaves=4, base=3):
    total, amp, norm = 0, 1, 0
    for o in range(octaves):
        total = total + value_noise(u, v, base * 2 ** o, rng) * amp
        norm += amp
        amp *= .5
    return total / norm


def outline(theta, rng, radius, wobble, harmonics=9):
    """A puddle's edge: a circle pushed out into lobes by a few decaying harmonics."""
    r = np.ones_like(theta)
    for k in range(2, harmonics + 1):
        r = r + wobble * rng.uniform(.35, 1) / k ** 1.15 * np.cos(k * theta + rng.uniform(0, 2 * np.pi))
    return radius * r


def save(path, lum, alpha):
    rgb = np.clip(lum * 255 + .5, 0, 255).astype(np.uint8)
    a = np.clip(alpha * 255 + .5, 0, 255).astype(np.uint8)
    # The border is kept clear whatever the shape: a stain's texture coordinates run past its edge.
    a[:2, :] = a[-2:, :] = 0
    a[:, :2] = a[:, -2:] = 0
    rgb[a == 0] = 0
    image = np.dstack([rgb, rgb, rgb, a])
    Image.fromarray(image, 'RGBA').save(path)


def mcmeta(path):
    """Linear filtering, and clamped: past a stain's edge is its clear border, never the far side of the image."""
    path.with_name(path.name + '.mcmeta').write_text(json.dumps({'texture': {'blur': True, 'clamp': True}}, indent=2) + '\n')


def pool(index):
    rng = np.random.default_rng(1700 + index)
    u, v = grid(SIZE, SS)
    warp = fbm(u, v, rng, 3, 2) - .5
    r = np.hypot(u, v) + warp * .10
    theta = np.arctan2(v, u)
    edge = outline(theta, rng, rng.uniform(.62, .70), rng.uniform(.16, .24))
    inside = edge - r  # positive inside, in texture half-widths
    cover = (inside > 0).astype(float)
    # Satellite drops where it spread unevenly: a few near the rim, some joined to it, some just clear of it.
    drops = np.zeros_like(u)
    for _ in range(rng.integers(3, 7)):
        angle = rng.uniform(0, 2 * np.pi)
        reach = rng.uniform(.72, .9)
        size = rng.uniform(.035, .075)
        cx, cy = np.cos(angle) * reach, np.sin(angle) * reach
        drops = np.maximum(drops, (np.hypot(u - cx, v - cy) < size).astype(float))
    shape = np.maximum(cover, drops)
    thick = smooth(0, .12, inside) * cover
    grain = fbm(u, v, rng, 4, 4) - .5
    # Deep and even all through, as pooled blood is, so pools that run together read as one; only a hair of thinner,
    # brighter blood at the very rim, and a crisp darker meniscus right on the edge.
    lum = .80 - .10 * thick + .05 * grain
    lum = lum - .10 * np.exp(-(inside / .02) ** 2) * cover
    lum = np.where(drops > cover, .76, lum)
    alpha = shape * np.where(drops > cover, .94, .90 + .08 * smooth(0, .06, inside))
    save(STAINS / f'pool_{index}.png', down(lum * shape + (1 - shape) * .8, SS), down(alpha, SS))
    mcmeta(STAINS / f'pool_{index}.png')

    # Where it shines: the sky caught in it as a few small, bright, sharp-edged patches and streaks (a wet surface's
    # highlights are crisp, not a haze), kept off the rim; the rest of it barely at all.
    shine = np.zeros_like(u)
    for _ in range(rng.integers(2, 4)):
        cx, cy = rng.uniform(-.3, .3), rng.uniform(-.35, .2)
        tilt = rng.uniform(-.6, .6)
        long_, wide = rng.uniform(.14, .34), rng.uniform(.025, .06)
        a = ((u - cx) * np.cos(tilt) + (v - cy) * np.sin(tilt)) / long_
        b = (-(u - cx) * np.sin(tilt) + (v - cy) * np.cos(tilt)) / wide
        shine = np.maximum(shine, rng.uniform(.6, 1) * smooth(1, .55, np.hypot(a, b)))
    shine = np.clip(.06 + shine + .08 * (fbm(u, v, rng, 3, 4) - .5), 0, 1)
    kept = smooth(.03, .12, inside) * cover
    save(STAINS / f'pool_{index}_sheen.png', down(np.ones_like(u), SS), down(shine * kept, SS))
    mcmeta(STAINS / f'pool_{index}_sheen.png')


def spatter(index):
    rng = np.random.default_rng(2900 + index)
    u, v = grid(SIZE, SS)
    shape = np.zeros_like(u)
    if index == 0:
        # A drop that fell straight down: a round mark with a splashed crown of spikes and flung specks.
        theta = np.arctan2(v, u)
        r = np.hypot(u, v)
        crown = .34 + .05 * np.cos(5 * theta + 1) + .04 * np.cos(9 * theta + 2)
        spikes = np.zeros_like(u)
        for k in range(rng.integers(9, 14)):
            a = rng.uniform(-np.pi, np.pi)
            length = rng.uniform(.06, .20)
            width = rng.uniform(.035, .06)
            d = np.angle(np.exp(1j * (theta - a)))
            spikes = np.maximum(spikes, ((np.abs(d) * r < width * (1 - (r - .3) / length)) & (r < .3 + length)).astype(float))
        shape = np.maximum((r < crown).astype(float), spikes)
        for _ in range(rng.integers(5, 9)):
            a = rng.uniform(-np.pi, np.pi)
            reach = rng.uniform(.5, .85)
            shape = np.maximum(shape, (np.hypot(u - np.cos(a) * reach, v - np.sin(a) * reach) < rng.uniform(.025, .05)).astype(float))
        inside = np.clip(crown - r, 0, None)
    else:
        # A drop thrown along +V: an elongated body, its tail running on ahead, a droplet flung off the end of it.
        width = rng.uniform(.17, .24)
        body_v = rng.uniform(-.45, -.25)
        length = rng.uniform(.30, .40)
        body = ((u / width) ** 2 + ((v - body_v) / length) ** 2) < 1
        tail_end = rng.uniform(.38, .58)
        t = np.clip((v - body_v) / (tail_end - body_v), 0, 1)
        tail = (np.abs(u - .03 * np.sin(t * 3)) < width * .55 * (1 - t) ** 1.4) & (v > body_v) & (v < tail_end)
        flung = np.hypot(u, v - (tail_end + rng.uniform(.08, .16))) < rng.uniform(.035, .06)
        shape = (body | tail | flung).astype(float)
        for _ in range(rng.integers(2, 5)):
            sv = rng.uniform(body_v, tail_end)
            su = rng.choice([-1, 1]) * rng.uniform(width * 1.1, width * 1.9)
            shape = np.maximum(shape, (np.hypot(u - su, v - sv) < rng.uniform(.018, .04)).astype(float))
        inside = np.clip(1 - ((u / width) ** 2 + ((v - body_v) / length) ** 2), 0, None) * .2
    lum = .90 - .30 * smooth(0, .12, inside) + .05 * (fbm(u, v, rng, 3, 4) - .5)
    lum = lum - .12 * shape * (1 - smooth(0, .03, inside))
    save(STAINS / f'spatter_{index}.png', down(lum * shape + (1 - shape) * .8, SS), down(shape * .93, SS))
    mcmeta(STAINS / f'spatter_{index}.png')


def drop(index):
    """A drop in the air, 8 by 8: white enough that the particle's tint is its colour, darker at its edge."""
    rng = np.random.default_rng(4100 + index)
    ss = 16
    u, v = grid(8, ss)
    if index == 2:
        r = np.hypot(u / .62, v / .86)
    elif index == 3:
        r = np.minimum(np.hypot((u + .22) / .5, (v + .1) / .5), np.hypot((u - .38) / .26, (v - .4) / .26))
    else:
        r = np.hypot(u, v) / (.86 if index == 0 else .72)
    shape = (r < 1).astype(float)
    lum = .78 + .22 * np.clip(1 - r, 0, 1) ** .5
    lum = lum + .12 * np.exp(-(((u + .25) / .2) ** 2 + ((v + .3) / .2) ** 2))
    alpha = shape * np.clip(.97 - .1 * r ** 4, 0, 1)
    rgb = np.clip(down(np.clip(lum, 0, 1) * shape + (1 - shape), ss) * 255 + .5, 0, 255).astype(np.uint8)
    a = np.clip(down(alpha, ss) * 255 + .5, 0, 255).astype(np.uint8)
    Image.fromarray(np.dstack([rgb, rgb, rgb, a]), 'RGBA').save(PARTICLES / f'blood_{index}.png')


def main():
    STAINS.mkdir(parents=True, exist_ok=True)
    for i in range(POOLS):
        pool(i)
    for i in range(SPATTERS):
        spatter(i)
    for i in range(DROPS):
        drop(i)
    (ROOT / 'particles/blood.json').write_text(json.dumps(
        {'textures': [f'hexgodofstories:blood_{i}' for i in range(DROPS)]}, indent=2) + '\n')
    print(f'Wrote {POOLS} pools with their sheens, {SPATTERS} spatters and {DROPS} drop sprites.')


if __name__ == '__main__':
    main()
