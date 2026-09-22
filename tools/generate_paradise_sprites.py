"""Paradise's two new textures: the sugar particle and the Candy Rush status icon.

Dependency free, in the same spirit as `generate_vfx_sprites.py` — no Pillow, no ffmpeg, just
zlib and struct — so the whole of Paradise's art can be regenerated on any machine with Python.

    python tools/generate_paradise_sprites.py
"""
import math
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/assets/hexgodofstories'
(ROOT / 'textures/particle').mkdir(parents=True, exist_ok=True)
(ROOT / 'textures/mob_effect').mkdir(parents=True, exist_ok=True)


def write_png(path, width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(tag, payload):
        body = tag + payload
        return struct.pack('>I', len(payload)) + body + struct.pack('>I', zlib.crc32(body) & 0xffffffff)

    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    path.write_bytes(b'\x89PNG\r\n\x1a\n'
                     + chunk(b'IHDR', header)
                     + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
                     + chunk(b'IEND', b''))
    print('wrote', path.relative_to(ROOT.parent.parent.parent.parent), width, 'x', height)


def sugar(size=16):
    """A four-pointed sparkle with a soft core.

    Drawn white, because the particle tints it: every mote carries its own place on a candy
    spectrum, and a sprite with colour already in it would fight that instead of carrying it.
    """
    out = []
    half = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            dx, dy = (x - half) / half, (y - half) / half
            r = math.hypot(dx, dy)
            core = max(0.0, 1.0 - r * 1.75) ** 2.1
            # Two crossed needles, thin along one axis and long along the other.
            spike = max(0.0, 1.0 - abs(dy) * 7.5) * max(0.0, 1.0 - abs(dx) * 1.05)
            spike += max(0.0, 1.0 - abs(dx) * 7.5) * max(0.0, 1.0 - abs(dy) * 1.05)
            # And a fainter diagonal pair, so it reads as glitter rather than as a plus sign.
            u, v = (dx + dy) * 0.7071, (dx - dy) * 0.7071
            spike += 0.45 * max(0.0, 1.0 - abs(v) * 9.0) * max(0.0, 1.0 - abs(u) * 1.35)
            spike += 0.45 * max(0.0, 1.0 - abs(u) * 9.0) * max(0.0, 1.0 - abs(v) * 1.35)
            a = min(1.0, core + spike * 0.62)
            a *= max(0.0, 1.0 - max(0.0, r - 0.92) * 9)
            out.append((255, 255, 255, int(round(a * 255))))
    return out


def candy_rush(size=18):
    """The status icon: a lollipop, swirled, on a short stick."""
    out = []
    cx, cy, radius = size * 0.5, size * 0.42, size * 0.36
    stick = ((size * 0.47, size * 0.66), (size * 0.62, size * 0.96))
    for y in range(size):
        for x in range(size):
            px, py = x + 0.5, y + 0.5
            a = 0
            colour = (0, 0, 0)
            # The stick first, so the sweet is drawn over the top of it.
            sx0, sy0 = stick[0]
            sx1, sy1 = stick[1]
            t = (py - sy0) / max(1e-6, sy1 - sy0)
            if 0 <= t <= 1 and abs(px - (sx0 + (sx1 - sx0) * t)) < size * 0.075:
                colour, a = (247, 236, 214), 255
            r = math.hypot(px - cx, py - cy)
            if r <= radius:
                # An Archimedean spiral: the band a point lands on decides its colour, which is
                # what makes this a swirl rather than a disc with a line on it.
                ang = math.atan2(py - cy, px - cx)
                band = (ang / (2 * math.pi) - r / radius * 1.9) % 1.0
                if band < 0.5:
                    colour = (255, 112, 186)
                else:
                    colour = (255, 244, 250)
                # A rim and a highlight, so it is not flat.
                if r > radius - 1.1:
                    colour = tuple(int(c * 0.72) for c in colour)
                gloss = max(0.0, 1.0 - math.hypot(px - (cx - radius * 0.34), py - (cy - radius * 0.38)) / (radius * 0.42))
                colour = tuple(min(255, int(c + gloss * 90)) for c in colour)
                a = 255 if r <= radius - 0.4 else int(round((radius - r) / 0.4 * 255))
            out.append((colour[0], colour[1], colour[2], max(0, min(255, a))))
    return out


write_png(ROOT / 'textures/particle/candy.png', 16, 16, sugar())
write_png(ROOT / 'textures/mob_effect/candy_rush.png', 18, 18, candy_rush())
