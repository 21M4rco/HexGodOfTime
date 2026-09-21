"""Regenerate the Abyssal Pilgrim's skin and emissive mask.

The geometry is untouched by this script and must stay untouched: every cube face in
abyssal_pilgrim.geo.json stretches exactly one 64x64 tile of a fixed 4x2 atlas, so the tile
positions and their meanings are a contract with the model.

    (0,0) hide        (1,0) jaw keratin   (2,0) light organ   (3,0) spine plate
    (0,1) tendril     (1,1) fin membrane  (2,1) maw           (3,1) appendage bone

Two rules follow from a whole tile being stretched over a face, and they are the difference
between a creature and a pile of boxes:

* Every tile has to wrap seamlessly. A tile with edges has those edges drawn around the outline
  of all 1550 cubes, which is precisely what made the body read as loose squares.
* Contrast has to stay low and detail has to stay fine. High contrast marks the boundary of every
  cube it lands on; fine, low contrast detail lets neighbouring cubes fuse into one surface, and
  lets the silhouette and the lighting do the describing instead.

Run: python tools/generate_pilgrim_textures.py
"""
from pathlib import Path
import math
import random

from PIL import Image

TILE = 64
ATLAS = 256
OUT = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/hexgodofstories/textures/entity'


# ----------------------------------------------------------------------------- noise

class Wrapping:
    """Seamless value noise on a torus, so a tile has no edge anywhere."""

    def __init__(self, period, seed):
        self.period = period
        rng = random.Random(seed)
        self.grid = [[rng.random() for _ in range(period)] for _ in range(period)]

    def at(self, x, y):
        p = self.period
        x0, y0 = int(math.floor(x)) % p, int(math.floor(y)) % p
        x1, y1 = (x0 + 1) % p, (y0 + 1) % p
        fx, fy = x - math.floor(x), y - math.floor(y)
        sx, sy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        top = self.grid[y0][x0] * (1 - sx) + self.grid[y0][x1] * sx
        bottom = self.grid[y1][x0] * (1 - sx) + self.grid[y1][x1] * sx
        return top * (1 - sy) + bottom * sy


def fractal(seed, octaves=4, base=4):
    """Returns f(u, v) in 0..1 for u, v in 0..1, seamless at the tile border."""
    layers = [(Wrapping(base * 2 ** i, seed + i * 977), 0.5 ** i) for i in range(octaves)]
    total = sum(weight for _, weight in layers)

    def sample(u, v):
        value = 0.0
        for noise, weight in layers:
            value += noise.at(u * noise.period, v * noise.period) * weight
        return value / total
    return sample


def mix(a, b, t):
    t = 0.0 if t < 0 else 1.0 if t > 1 else t
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(colour, amount):
    return tuple(max(0, min(255, round(c * (1 + amount)))) for c in colour)


def paint(painter):
    tile = Image.new('RGBA', (TILE, TILE))
    pixels = tile.load()
    for y in range(TILE):
        for x in range(TILE):
            pixels[x, y] = painter(x, y)
    return tile


# ----------------------------------------------------------------------------- materials

def hide():
    """Deep water hide: cold, almost black, with fine scale rows that only read up close."""
    mottle = fractal(11, octaves=4, base=4)
    grain = fractal(29, octaves=2, base=16)
    deep = (13, 18, 30)
    lift = (32, 43, 62)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        base = mix(deep, lift, mottle(u, v) * 0.85)
        # Scale rows, offset every other row so the pattern never lines up into a grid.
        row = y // 4
        offset = 2 if row % 2 else 0
        sx = ((x + offset) % 4) / 4.0
        sy = (y % 4) / 4.0
        scale = 1 - abs(sx - 0.5) * 2
        edge = 0.055 * scale * (1 - sy)
        base = shade(base, edge - 0.03 * sy + (grain(u, v) - 0.5) * 0.10)
        return base + (255,)
    return paint(painter)


def spine_plate():
    """
    Armour along the back. Nearly black, with a cold sheen running along the spine.

    Deliberately without plate seams. A seam is a hard line, a hard line lands on the border of
    every cube that carries this material, and a hundred cubes with borders drawn on them is the
    box heap the creature is not supposed to look like.
    """
    mottle = fractal(53, octaves=3, base=8)
    dark = (9, 12, 21)
    sheen = (40, 52, 74)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        ridge = 0.5 + 0.5 * math.sin((x + mottle(u, v) * 10) * math.pi / 16.0)
        base = mix(dark, sheen, 0.18 + ridge * 0.30 + mottle(u, v) * 0.34)
        return shade(base, (mottle(u * 2, v * 2) - 0.5) * 0.10) + (255,)
    return paint(painter)


def keratin():
    """Jaw plate. Hard, pale, striated along the bite rather than speckled."""
    grain = fractal(71, octaves=3, base=6)
    fleck = fractal(97, octaves=2, base=24)
    pale = (158, 163, 165)
    bright = (204, 208, 206)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        lamella = 0.5 + 0.5 * math.sin((x + grain(u, v) * 6) * math.pi / 4.0)
        base = mix(pale, bright, lamella * 0.5 + grain(u, v) * 0.45)
        # No top-to-bottom gradient: it would not wrap, and a tile that does not wrap
        # draws its own border around every cube that carries it.
        base = shade(base, (fleck(u, v) - 0.5) * 0.07)
        return base + (255,)
    return paint(painter)


def appendage_bone():
    """
    The rib blades and jaw spines, and by count more than half of everything drawn.

    It used to be a bright, evenly speckled grey, which is why a coiled body read as a heap of
    pale tiles: at that brightness every cube in the fan is a separate highlight. Cooler, darker
    and lamellar keeps the fan a fan.
    """
    grain = fractal(131, octaves=3, base=5)
    fibre = fractal(149, octaves=2, base=20)
    shadowed = (82, 90, 101)
    lit = (136, 145, 156)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        # Lengthwise fibres, bent slightly by the noise so they never look printed.
        wave = math.sin((y + grain(u, v) * 9) * math.pi / 4.0)
        base = mix(shadowed, lit, 0.45 + wave * 0.16 + grain(u, v) * 0.42)
        base = shade(base, (fibre(u, v) - 0.5) * 0.08)
        return base + (255,)
    return paint(painter)


def fin_membrane():
    """Dorsal and rib membrane: thin, cold, ribbed along its length rather than speckled."""
    grain = fractal(181, octaves=3, base=6)
    thin = (68, 81, 99)
    thick = (112, 126, 145)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        rib = 0.5 + 0.5 * math.sin((y + grain(u, v) * 7) * math.pi / 4.0)
        base = mix(thin, thick, 0.32 + rib * 0.26 + grain(u, v) * 0.38)
        return shade(base, (grain(u * 3, v * 3) - 0.5) * 0.09) + (255,)
    return paint(painter)


def tendril():
    """Head and tail filaments. The highest face count on the model, so the flattest material."""
    grain = fractal(211, octaves=3, base=7)
    dark = (17, 21, 33)
    soft = (44, 51, 70)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        strand = 0.5 + 0.5 * math.sin((x + grain(u, v) * 7) * math.pi / 2.0)
        base = mix(dark, soft, 0.30 + strand * 0.22 + grain(u, v) * 0.34)
        return base + (255,)
    return paint(painter)


def maw():
    """Inside the jaws. Wet, dark and red, with folds running down the throat."""
    grain = fractal(239, octaves=3, base=5)
    deep = (34, 12, 20)
    flesh = (96, 33, 45)

    def painter(x, y):
        u, v = x / TILE, y / TILE
        fold = 0.5 + 0.5 * math.sin((x + grain(u, v) * 11) * math.pi / 4.0)
        base = mix(deep, flesh, 0.25 + fold * 0.35 + grain(u, v) * 0.40)
        wet = max(0.0, fold - 0.85) * 0.5
        return shade(base, wet) + (255,)
    return paint(painter)


def organ(alpha=False):
    """
    Bioluminescence, and the one emissive material.

    Drawn as a lamp rather than a stain: a small hot core, a falling halo, and filaments running
    out of it. The same art is written into the base skin and into the glow mask, so the emissive
    pass lines up with the lit pixels exactly instead of haloing beside them.
    """
    filament = fractal(307, octaves=3, base=6)
    core = (243, 232, 255)
    mid = (176, 116, 255)
    edge = (58, 26, 108)

    def painter(x, y):
        u, v = (x + 0.5) / TILE - 0.5, (y + 0.5) / TILE - 0.5
        radius = math.sqrt(u * u + v * v) / 0.72
        strands = filament(x / TILE, y / TILE)
        glow = max(0.0, 1.0 - radius) ** 1.5
        glow = min(1.0, glow * (0.72 + strands * 0.55))
        colour = mix(edge, mid, min(1.0, glow * 1.6))
        colour = mix(colour, core, max(0.0, glow - 0.66) / 0.34)
        if alpha:
            return colour + (round(255 * min(1.0, glow * 1.35)),)
        return mix((11, 14, 24), colour, min(1.0, 0.25 + glow * 1.5)) + (255,)
    return paint(painter)


# ----------------------------------------------------------------------------- atlas

def build():
    skin = Image.new('RGBA', (ATLAS, ATLAS), (0, 0, 0, 0))
    glow = Image.new('RGBA', (ATLAS, ATLAS), (0, 0, 0, 0))

    skin.paste(hide(), (0, 0))
    skin.paste(keratin(), (64, 0))
    skin.paste(organ(), (128, 0))
    skin.paste(spine_plate(), (192, 0))
    skin.paste(tendril(), (0, 64))
    skin.paste(fin_membrane(), (64, 64))
    skin.paste(maw(), (128, 64))
    skin.paste(appendage_bone(), (192, 64))

    glow.paste(organ(alpha=True), (128, 0))

    OUT.mkdir(parents=True, exist_ok=True)
    skin.save(OUT / 'abyssal_pilgrim.png')
    glow.save(OUT / 'abyssal_pilgrim_glowmask.png')
    print('wrote', OUT / 'abyssal_pilgrim.png')
    print('wrote', OUT / 'abyssal_pilgrim_glowmask.png')


if __name__ == '__main__':
    build()
