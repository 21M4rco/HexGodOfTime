"""Nebula-family particle sprites and ground decals, written without third-party imaging.

The existing `generate_assets.py` needs Pillow; these sheets are deliberately dependency free so the
soft, layered look the mod is built around can be regenerated anywhere. Every sprite here derives from
the same value-noise field the cosmic nebula uses, so an ability effect and the flight cloud read as
one material rather than as separate mods bolted together.
"""
import json
import math
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/assets/loki'
(ROOT / 'textures/particle').mkdir(parents=True, exist_ok=True)
(ROOT / 'particles').mkdir(parents=True, exist_ok=True)


def write_png(path, size, pixels):
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(pixels[y * size + x])

    def chunk(tag, payload):
        body = tag + payload
        return struct.pack('>I', len(payload)) + body + struct.pack('>I', zlib.crc32(body) & 0xffffffff)

    header = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    path.write_bytes(b'\x89PNG\r\n\x1a\n'
                     + chunk(b'IHDR', header)
                     + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
                     + chunk(b'IEND', b''))


def hash2(x, y, seed):
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xffffffff
    h = (h ^ (h >> 13)) * 1274126177 & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 0xffff


def value_noise(x, y, seed):
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    sx = fx * fx * (3 - 2 * fx)
    sy = fy * fy * (3 - 2 * fy)
    a = hash2(ix, iy, seed)
    b = hash2(ix + 1, iy, seed)
    c = hash2(ix, iy + 1, seed)
    d = hash2(ix + 1, iy + 1, seed)
    return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy


def fbm(x, y, octaves, seed=7):
    total, amplitude, frequency, norm = 0.0, 1.0, 1.0, 0.0
    for _ in range(octaves):
        total += value_noise(x * frequency, y * frequency, seed) * amplitude
        norm += amplitude
        amplitude *= .52
        frequency *= 2.07
    return total / norm


def sprite(name, size, painter, register=True):
    pixels = []
    for y in range(size):
        for x in range(size):
            px = painter((x + .5) / size * 2 - 1, (y + .5) / size * 2 - 1)
            pixels.append(bytes(px) if px else b'\x00\x00\x00\x00')
    # Only sheets named by a particle definition are stitched into the particle atlas; a decal has to
    # stay a standalone texture so a render type can bind it directly.
    write_png(ROOT / (f'textures/particle/{name}.png' if register else f'textures/{name}.png'), size, pixels)
    if register:
        (ROOT / f'particles/{name}.json').write_text(json.dumps({'textures': [f'loki:{name}']}, indent=2) + '\n')


def clamp8(v):
    return max(0, min(255, int(v)))


def nebula_paint(u, v):
    """A cloud puff: soft round falloff carved by two noise octave sets, exactly the recipe the
    flight nebula bakes at volume size, so a single mote and the whole cloud share a silhouette."""
    d = math.hypot(u, v)
    if d >= 1:
        return None
    body = (1 - d * d) ** 1.9
    shape = fbm(u * 2.6 + 11, v * 2.6 - 4, 3, 21)
    detail = fbm(u * 7.5 + shape * 2, v * 7.5, 5, 33)
    density = body * min(1.0, max(0.0, (shape - .18) * 3.1)) * (.55 + .65 * detail)
    density = min(1.0, density * 1.55)
    if density < .012:
        return None
    hot = density ** 2.1
    return (clamp8(46 + 190 * hot), clamp8(214 + 41 * hot), clamp8(140 + 105 * hot), clamp8(238 * density))


def veil_paint(u, v):
    """A wide, almost structureless haze. Layered underneath sharper motes it is what gives an
    effect depth instead of a flat ring of dots."""
    d = math.hypot(u, v)
    if d >= 1:
        return None
    falloff = (1 - d) ** 2.8
    wisp = .72 + .5 * fbm(u * 1.9 + 3, v * 1.9 + 9, 3, 57)
    a = falloff * wisp * .78
    if a < .008:
        return None
    return (clamp8(120 + 95 * falloff), clamp8(228 + 27 * falloff), clamp8(178 + 60 * falloff), clamp8(210 * a))


def star_paint(u, v):
    """A four-point flare for the instant a spell resolves; the diffraction spikes keep an impact
    legible against bright terrain where a round mote disappears."""
    d = math.hypot(u, v)
    if d >= 1:
        return None
    core = math.exp(-(d / .17) ** 2)
    spike = math.exp(-(abs(v) / .055) ** 2) * (1 - abs(u)) + math.exp(-(abs(u) / .055) ** 2) * (1 - abs(v))
    diagonal = (math.exp(-(abs(u - v) / .085) ** 2) + math.exp(-(abs(u + v) / .085) ** 2)) * (1 - d) * .38
    a = min(1.0, core + spike * .62 + diagonal)
    if a < .02:
        return None
    return (clamp8(170 + 85 * a), clamp8(236 + 19 * a), clamp8(196 + 59 * a), clamp8(250 * a))


def smoke_paint(u, v):
    """Dark, slow drifting volume for temporal collapse and dispersal. Same silhouette as the
    nebula puff with the light pulled out of it."""
    d = math.hypot(u, v)
    if d >= 1:
        return None
    body = (1 - d * d) ** 2.2
    grain = fbm(u * 3.4 - 6, v * 3.4 + 2, 4, 91)
    density = min(1.0, body * (.42 + .95 * grain))
    if density < .015:
        return None
    return (clamp8(24 + 54 * density), clamp8(44 + 86 * density), clamp8(38 + 66 * density), clamp8(214 * density))


def pool_paint(u, v):
    """Ground blood: an irregular blob with a darker rim, drawn as a decal quad rather than a block."""
    d = math.hypot(u, v)
    wobble = .62 + .30 * fbm(u * 2.1 + 13, v * 2.1 - 7, 4, 131)
    if d >= wobble:
        return None
    edge = 1 - d / wobble
    a = min(1.0, edge ** .68 * 1.25)
    rim = math.exp(-((edge - .12) / .16) ** 2) * .55
    r = 96 - 52 * rim
    g = 12 + 6 * edge
    b = 14 + 8 * edge
    return (clamp8(r), clamp8(g), clamp8(b), clamp8(232 * a))


sprite('nebula', 32, nebula_paint)
sprite('veil', 32, veil_paint)
sprite('star', 16, star_paint)
sprite('smoke', 32, smoke_paint)
# Decals are drawn by hand in world space, so they need no particle definition.
sprite('blood_pool', 32, pool_paint, register=False)

print('Wrote nebula, veil, star, smoke particle sheets and the blood_pool decal.')
