"""Reproducible authored meshes, textures, particle sprites, animations and original audio.

Requires Pillow and ffmpeg, and nothing else: the synthesis below is plain Python so the
asset pipeline stays as portable as the rest of the project.

Weapon geometry is authored directly in Minecraft item-model units, where 1.0 is one block and
the grip sits at the origin with the blade running up +Y. WeaponRenderer only has to place that
origin where a vanilla hilt sits, which is what keeps conjured blades correctly in the hand.
"""
from pathlib import Path
import math, json, random, wave, struct, subprocess
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/loki'
for directory in ['models', 'models/item', 'textures', 'textures/particle', 'sounds', 'player_animation', 'particles']:
    (ROOT / directory).mkdir(parents=True, exist_ok=True)

TAU = math.tau


# ---------------------------------------------------------------- geometry ---

class Mesh:
    def __init__(self):
        self.v = []
        self.uv = []
        self.faces = []

    def quad(self, points, group='metal', band=0, uv=None):
        index = len(self.v) + 1
        self.v.extend(points)
        self.uv.extend(uv or [(band / 8 + .005, .03), ((band + 1) / 8 - .005, .03),
                              ((band + 1) / 8 - .005, .97), (band / 8 + .005, .97)])
        self.faces.append((group, [index + i for i in range(4)]))

    def tube(self, centers, radii, group='metal', band=0, sides=12):
        rings = []
        for i, (x, y, z) in enumerate(centers):
            prev = centers[max(0, i - 1)]
            nxt = centers[min(len(centers) - 1, i + 1)]
            dy, dz = nxt[1] - prev[1], nxt[2] - prev[2]
            d = math.hypot(dy, dz) or 1
            rings.append([(x + math.cos(a * TAU / sides) * radii[i],
                           y - math.sin(a * TAU / sides) * radii[i] * dz / d,
                           z + math.sin(a * TAU / sides) * radii[i] * dy / d) for a in range(sides)])
        for i in range(len(rings) - 1):
            for a in range(sides):
                self.quad([rings[i][a], rings[i][(a + 1) % sides],
                           rings[i + 1][(a + 1) % sides], rings[i + 1][a]], group, band)

    def blade(self, profile, group='blade', band=0):
        """Diamond cross section: a real central ridge with two sharpened bevels."""
        rings = [[(x - w, y, z), (x, y, z + th), (x + w, y, z), (x, y, z - th)]
                 for y, w, th, x, z in profile]
        for i in range(len(rings) - 1):
            for a in range(4):
                self.quad([rings[i][a], rings[i][(a + 1) % 4], rings[i + 1][(a + 1) % 4], rings[i + 1][a]],
                          group, band if a % 2 else min(7, band + 1))

    def facet(self, centre, radius, height, group='metal', band=7, sides=8):
        """A small faceted knob, used for pommels and cores."""
        cx, cy, cz = centre
        self.tube([(cx, cy - height / 2, cz), (cx, cy - height / 4, cz),
                   (cx, cy + height / 4, cz), (cx, cy + height / 2, cz)],
                  [radius * .25, radius, radius, radius * .3], group, band, sides)

    def save(self, name):
        lines = ['# Loki authored geometry: item-model units, grip at origin, blade toward +Y.']
        lines += ['v %.6f %.6f %.6f' % p for p in self.v]
        lines += ['vt %.6f %.6f' % p for p in self.uv]
        group = None
        for g, face in self.faces:
            if g != group:
                lines.append('g ' + g)
                group = g
            lines.append('f ' + ' '.join(f'{i}/{i}' for i in face))
        (ROOT / f'models/{name}.obj').write_text('\n'.join(lines) + '\n')


def grip_wrap(mesh, low, high, radius, group='grip_wire', band=2, turns=9):
    for i in range(turns):
        y = low + (high - low) * i / (turns - 1)
        mesh.tube([(math.cos(a * TAU / 12) * radius, y, math.sin(a * TAU / 12) * radius) for a in range(13)],
                  [radius * .17] * 13, group, band, 6)


# Loki's dagger: a broad leaf blade, swept quillons and a wrapped grip.
dagger = Mesh()
dagger.blade([(.060, .050, .048, 0, 0),
              (.115, .073, .046, 0, 0),
              (.225, .066, .037, .006, 0),
              (.360, .048, .025, .010, 0),
              (.445, .027, .015, .010, 0),
              (.490, .003, .004, .006, 0)])
# Fuller: a shallow raised rib down the centre of the blade.
dagger.blade([(.10, .010, .052, 0, 0), (.34, .007, .030, .008, 0), (.42, .003, .018, .009, 0)], 'engraving', 2)
dagger.tube([(-.115, .036, .030), (-.086, .052, .018), (-.040, .060, 0), (0, .052, 0),
             (.040, .060, 0), (.086, .052, .018), (.115, .036, .030)],
            [.007, .016, .022, .024, .022, .016, .007], 'guard', 2)
dagger.tube([(0, y, 0) for y in [-.150, -.132, -.100, -.040, .005, .030]],
            [.020, .031, .026, .026, .029, .022], 'grip', 3)
grip_wrap(dagger, -.125, .010, .029)
dagger.facet((0, -.168, 0), .034, .050, 'pommel', 7)
dagger.save('dagger')

# Laevateinn: longer, heavier, rune-etched along both flats.
sword = Mesh()
sword.blade([(.115, .070, .055, 0, 0),
             (.205, .097, .052, 0, 0),
             (.420, .089, .042, .005, 0),
             (.680, .073, .031, .008, 0),
             (.860, .048, .021, .007, 0),
             (.950, .004, .005, .004, 0)])
sword.blade([(.19, .013, .058, 0, 0), (.62, .010, .034, .007, 0), (.82, .004, .020, .006, 0)], 'engraving', 2)
sword.tube([(-.215, .190, .014), (-.180, .140, .012), (-.100, .100, 0), (0, .118, 0),
            (.100, .100, 0), (.180, .140, .012), (.215, .190, .014)],
           [.005, .019, .030, .040, .030, .019, .005], 'guard', 2)
sword.tube([(0, y, 0) for y in [-.250, -.220, -.170, -.040, .060, .095]],
           [.026, .042, .034, .032, .036, .040], 'leather', 4)
grip_wrap(sword, -.210, .070, .037, 'grip', 2, 11)
sword.facet((0, -.275, 0), .046, .062, 'pommel', 7)
for sign in (-1, 1):
    for i in range(9):
        y = .26 + i * .070
        sword.tube([(sign * .010, y, .034), (sign * .028, y + .022, .031), (sign * .010, y + .044, .031)],
                   [.0025] * 3, 'runes', 2, 5)
sword.save('laevateinn')

# TVA time stick: machined baton with a warm temporal core near the head.
stick = Mesh()
stick.tube([(0, y, 0) for y in [-.340, -.315, -.200, .000, .080, .560, .700, .780]],
           [.014, .024, .023, .023, .019, .019, .052, .010], 'shaft', 5, 16)
stick.tube([(0, y, 0) for y in [.600, .660, .730, .770]], [.033, .045, .045, .011], 'temporal_core', 6, 16)
grip_wrap(stick, -.290, -.030, .026, 'grip', 3)
stick.facet((0, -.360, 0), .028, .038, 'shaft', 5)
stick.save('time_stick')

# The crown and mantle stay in player-model units: they ride the head and body bones directly.
crown = Mesh()
# Full forehead band sits outside the skin/hat cuboid, with solid top and bottom rims.
for i in range(64):
    def rim(a, y, radius):
        angle = a * TAU / 64
        sx, sz = math.sin(angle), math.cos(angle)
        return (math.copysign(abs(sx) ** .12, sx) * radius, y,
                math.copysign(abs(sz) ** .12, sz) * radius)
    for lo, hi, radius in [(-.445, -.315, .302), (-.445, -.315, .290)]:
        crown.quad([rim(i, lo, radius), rim(i+1, lo, radius),
                    rim(i+1, hi, radius), rim(i, hi, radius)], 'forehead_band', 7)
    for y in (-.445, -.315):
        crown.quad([rim(i, y, .290), rim(i+1, y, .290),
                    rim(i+1, y, .302), rim(i, y, .302)], 'forehead_band', 7)
for sign in (-1, 1):
    centers, radii = [], []
    for i in range(33):
        t = i / 32
        centers.append((sign * (.22 + .14 * math.sin(t * math.pi * .85)),
                        -.42 - .82 * t,
                        -.13 - .30 * math.sin(t * math.pi * .88) + .18 * t * t))
        radii.append(.068 * (1 - t) ** .8 + .001)
    crown.tube(centers, radii, 'horn_' + str(sign), 7, 16)
    for ridge in range(3):
        path = [(x + sign * .025 * math.cos(ridge * 2.1), y, z + .035 * math.sin(ridge * 2.1) * (1 - i / 32))
                for i, (x, y, z) in enumerate(centers)]
        crown.tube(path, [.004 * (1 - i / 32) + .001 for i in range(33)], 'horn_ridge', 5, 5)
crown.save('crown')

collar = Mesh()
for row in range(12):
    for i in range(40):
        points, uv = [], []
        for a, r in [(i, row), (i + 1, row), (i + 1, row + 1), (i, row + 1)]:
            angle = a * TAU / 40
            t = r / 12
            width = .19 + .27 * math.sin(t * math.pi / 2)
            points.append((math.sin(angle) * width, -.07 + t * .25 + math.cos(angle) * .035,
                           math.cos(angle) * (.19 + .045 * t)))
            uv.append((a / 40, t))
        collar.quad(points, 'mantle', 0, uv)
collar.save('collar')


# ---------------------------------------------------------------- textures ---

rng = random.Random(41091)
palette = [(157, 177, 164), (220, 229, 213), (139, 118, 62), (24, 33, 26),
           (36, 45, 29), (42, 43, 39), (239, 161, 62), (32, 27, 21)]
material = Image.new('RGB', (256, 256))
for y in range(256):
    for x in range(256):
        band = x // 32
        c = palette[band]
        variation = rng.randrange(-5, 6) + (3 if (x + y) % 7 == 0 else 0)
        sheen = int(10 * math.sin(x % 32 / 31 * math.pi)) if band < 3 else 0
        material.putpixel((x, y), tuple(max(0, min(255, v + variation + sheen)) for v in c))
material.save(ROOT / 'textures/material.png')

cloth = Image.new('RGB', (256, 256))
for y in range(256):
    for x in range(256):
        fold = math.cos(x * .13) * 3
        weave = (x % 3 == 0) - (y % 3 == 0)
        noise = rng.randrange(-2, 3)
        c = tuple(max(0, int(v + fold + weave + noise)) for v in (18, 34, 26))
        if x < 3 or x > 252 or y > 250:
            c = (83, 76, 44)
        cloth.putpixel((x, y), c)
cloth.save(ROOT / 'textures/cloth.png')
Image.new('RGBA', (2, 2), (255, 255, 255, 255)).save(ROOT / 'textures/white.png')


def sprite(name, size, painter):
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            px = painter((x + .5) / size * 2 - 1, (y + .5) / size * 2 - 1)
            if px:
                im.putpixel((x, y), px)
    im.save(ROOT / f'textures/particle/{name}.png')
    (ROOT / f'particles/{name}.json').write_text(json.dumps({'textures': [f'loki:{name}']}, indent=2) + '\n')


def soft(colour, power=2.4, core=1.0):
    r, g, b = colour
    def paint(u, v):
        d = math.hypot(u, v)
        if d >= 1:
            return None
        falloff = (1 - d) ** power
        hot = min(1.0, falloff * core * 1.8)
        return (min(255, int(r + (255 - r) * hot * .55)),
                min(255, int(g + (255 - g) * hot * .55)),
                min(255, int(b + (255 - b) * hot * .55)),
                int(255 * falloff))
    return paint


sprite('ember', 16, soft((60, 220, 130), 2.2, 1.1))
sprite('gold_ember', 16, soft((235, 185, 92), 2.2, 1.1))
sprite('mote', 8, soft((205, 240, 215), 1.5, 1.4))
sprite('blood', 8, soft((150, 26, 30), 1.9, .5))


def rune_paint(u, v):
    d = math.hypot(u, v)
    if d >= 1:
        return None
    ring = math.exp(-((d - .74) / .10) ** 2)
    spokes = math.exp(-((d - .45) / .07) ** 2) * (1 if (math.atan2(v, u) * 6 / math.pi) % 2 < .55 else 0)
    core = math.exp(-(d / .16) ** 2)
    a = min(1.0, ring + spokes * .9 + core)
    if a < .05:
        return None
    return (int(120 + 135 * a), int(230 - 30 * a), int(150 + 40 * a), int(235 * a))


sprite('rune', 32, rune_paint)


def shard_paint(u, v):
    # A narrow sliver of mirrored glass with a bright leading edge.
    if abs(u) > .30 - .24 * abs(v) or abs(v) > .96:
        return None
    edge = 1 - abs(u) / max(1e-3, .30 - .24 * abs(v))
    a = .30 + .70 * edge ** 1.6
    return (int(190 + 60 * edge), int(225 + 25 * edge), int(205 + 40 * edge), int(245 * a))


sprite('shard', 16, shard_paint)


# -------------------------------------------------------------- animations ---

def animation(name, frames, end=30, loop=False):
    moves = []
    for tick, right, left, body in frames:
        move = {'tick': tick, 'easing': 'inoutquad',
                'rightArm': dict(zip(['pitch', 'yaw', 'roll'], right)),
                'leftArm': dict(zip(['pitch', 'yaw', 'roll'], left))}
        if body is not None:
            move['torso'] = {'pitch': body[0], 'yaw': body[1], 'roll': body[2]}
        moves.append(move)
    content = {'version': 3, 'name': name, 'author': 'LokiGPT',
               'description': 'Layered upper-body gesture; locomotion remains available.',
               'emote': {'beginTick': 0, 'endTick': end, 'stopTick': end, 'isLoop': loop,
                         'returnTick': 10, 'degrees': True, 'moves': moves}}
    (ROOT / f'player_animation/{name}.json').write_text(json.dumps(content, indent=2))


zero = (0, 0, 0)
poses = {
    'illusion': ((-72, -35, -23), (-50, 28, 18)),
    'bolt': ((-92, -8, 2), (-12, 8, -8)),
    'push': ((-104, -12, -12), (-82, 14, 12)),
    'blink': ((-34, -25, -12), (-42, 20, 14)),
    'ward': ((-88, -32, -20), (-88, 32, 20)),
    'enchant': ((-99, -4, -8), (-25, 18, 8)),
    'conjure': ((-54, -14, -28), (-52, 14, 28)),
    'threads': ((-90, -23, -16), (-96, 22, 16)),
    'time_stop': ((-94, -6, -6), (-12, 12, 6)),
}
for name, (r, l) in poses.items():
    animation(name, [(0, zero, zero, zero), (5, (-25, -20, -12), (-15, 15, 10), (0, -4, 0)),
                     (10, r, l, (2, -7, 0)), (20, r, l, (1, -4, 0)), (30, zero, zero, zero)], 30)

# Telekinesis holds: the arm stays out, fingers working, for as long as the grip lasts.
animation('telekinesis', [
    (0, (-20, -10, -6), (-10, 10, 4), zero),
    (6, (-96, -12, -10), (-26, 16, 6), (3, -9, 0)),
    (20, (-101, -7, -14), (-24, 13, 7), (4, -11, 2)),
    (34, (-93, -14, -7), (-29, 18, 4), (2, -7, -1)),
    (48, (-96, -12, -10), (-26, 16, 6), (3, -9, 0)),
], 48, loop=True)

animation('ascend', [(0, zero, zero, zero), (25, (-10, -7, -8), (-9, 7, 8), (-2, 0, 0)),
                     (60, (-18, -10, -15), (-18, 10, 15), (-2, 0, 0)),
                     (100, (-15, -8, -10), (-14, 8, 10), (0, 0, 0)), (140, zero, zero, zero)], 140)
animation('time_slip', [(0, zero, zero, zero), (3, (-110, 30, -32), (-34, -23, 46), (-18, 21, -12)),
                        (6, (-42, -36, -55), (-98, 32, 18), (15, -32, 8)),
                        (10, (-122, 22, -13), (-22, -24, 33), (-12, 16, -5)), (18, zero, zero, zero)], 18)
animation('dagger_throw', [(0, zero, zero, zero), (4, (-178, -24, 30), (-22, 12, 6), (0, -26, 0)),
                           (8, (-86, 9, -6), (-18, 14, 8), (8, 21, 0)),
                           (14, (-30, 13, -6), (-10, 6, 3), (2, 9, 0)), (21, zero, zero, zero)], 21)
for kind in ['dagger', 'twin', 'sword']:
    for combo in range(4):
        sign = -1 if combo % 2 == 0 else 1
        wind = 8 if kind == 'sword' else 4
        end = 20 if kind == 'sword' else 12
        r = (-110, sign * 65, -sign * 30)
        l = (-40, -sign * 22, 12) if kind == 'twin' else (-14, 10, 5)
        strike = (-60, -sign * 50, sign * 35)
        off = (-108, sign * 40, -22) if kind == 'twin' else (-20, 13, 8)
        animation(kind + '_' + str(combo),
                  [(0, (-25, 0, -8), (-15, 0, 8), zero), (wind - 1, r, l, (-3, sign * 22, 0)),
                   (wind + 1, strike, off, (4, -sign * 16, 0)), (end, (-25, 0, -8), (-15, 0, 8), zero)], end)


# ------------------------------------------------------------------- audio ---
# Original synthesis only; nothing is sampled from film or game sources. Each sound is built
# from its own recipe rather than one shared formula, so steel reads as steel and time does not.

RATE = 22050


def silence(duration):
    return [0.0] * int(RATE * duration)


def add(buffer, start, samples, gain=1.0):
    offset = int(start * RATE)
    for i, s in enumerate(samples):
        j = offset + i
        if 0 <= j < len(buffer):
            buffer[j] += s * gain


def noise(duration, seed):
    r = random.Random(seed)
    return [r.uniform(-1, 1) for _ in range(int(RATE * duration))]


def bandpass(samples, centre, q, sweep=0.0):
    """One resonant biquad, optionally sweeping its centre across the buffer."""
    out = [0.0] * len(samples)
    x1 = x2 = y1 = y2 = 0.0
    n = max(1, len(samples) - 1)
    for i, x in enumerate(samples):
        f = centre * (1 + sweep * i / n)
        w = TAU * min(f, RATE * .45) / RATE
        alpha = math.sin(w) / (2 * q)
        cosw = math.cos(w)
        b0, b2 = alpha, -alpha
        a0, a1, a2 = 1 + alpha, -2 * cosw, 1 - alpha
        y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1 = x1, x
        y2, y1 = y1, y
        out[i] = y
    return out


def envelope(samples, attack, decay, power=1.6):
    n = len(samples)
    a = max(1, int(attack * RATE))
    out = [0.0] * n
    for i, s in enumerate(samples):
        rise = min(1.0, i / a)
        fall = math.exp(-i / RATE / max(1e-4, decay)) ** power
        out[i] = s * rise * fall
    return out


def partials(duration, base, ratios, decays, gains, detune=0.0, seed=0):
    r = random.Random(seed)
    n = int(RATE * duration)
    out = [0.0] * n
    for ratio, decay, gain in zip(ratios, decays, gains):
        f = base * ratio * (1 + r.uniform(-detune, detune))
        w = TAU * f / RATE
        for i in range(n):
            out[i] += math.sin(w * i) * gain * math.exp(-i / RATE / decay)
    return out


def sweep(duration, start, end, gain=1.0, shape=1.0):
    n = int(RATE * duration)
    out = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = (i / n) ** shape
        f = start + (end - start) * t
        phase += TAU * f / RATE
        out[i] = math.sin(phase) * gain
    return out


def comb(samples, delay, feedback, mix=.35):
    """A cheap tail so impacts sit in a space instead of stopping dead."""
    d = int(delay * RATE)
    out = list(samples)
    for i in range(d, len(out)):
        out[i] += out[i - d] * feedback
    return [s * (1 - mix) + o * mix for s, o in zip(samples, out)]


def swing(seed, duration=.42, centre=1500, sweep_amount=1.5, gain=.62):
    body = bandpass(noise(duration, seed), centre, 1.5, sweep_amount)
    body = envelope(body, .035, duration * .28, 1.3)
    ring = envelope(partials(duration, 2400, [1, 2.37, 3.61], [.10, .07, .05], [.20, .10, .06], .01, seed), .004, .09)
    out = silence(duration)
    add(out, 0, body, gain)
    add(out, duration * .30, ring, .35)
    return out


def blade_hit(seed):
    duration = .58
    out = silence(duration)
    # Transient: the instant of contact.
    add(out, 0, envelope(noise(.04, seed), .0006, .012, 1.0), .95)
    # Steel: inharmonic partials that ring and die unevenly.
    add(out, .002, partials(.5, 1180, [1, 2.71, 4.13, 5.87, 7.94], [.22, .16, .11, .07, .05],
                            [.34, .22, .15, .09, .05], .012, seed), .8)
    # Body: a low thud so it lands on something rather than in the air.
    add(out, .004, envelope(bandpass(noise(.2, seed + 7), 180, 1.1), .002, .07, 1.2), .55)
    return comb(out, .031, .26, .22)


def rift(opening):
    duration = 1.5 if opening else 1.1
    out = silence(duration)
    r = random.Random(9901 if opening else 9902)
    # A spray of glass fractures, packed toward the moment the surface gives way.
    for i in range(46):
        t = (i / 46) ** (.6 if opening else 2.0) * duration * .62
        seed = r.randrange(1 << 20)
        piece = envelope(partials(.20, r.uniform(1500, 5200), [1, 2.44, 3.93], [.07, .05, .03],
                                  [.3, .18, .1], .02, seed), .0008, .05)
        add(out, t, piece, r.uniform(.12, .38))
    if opening:
        add(out, 0, envelope(sweep(.95, 60, 520, 1.0, 2.2), .55, .5, .8), .40)   # reverse-style swell
        add(out, .55, envelope(sweep(.9, 240, 44, 1.0, .6), .004, .35), .42)     # the drop through
    else:
        add(out, 0, envelope(sweep(.8, 420, 70, 1.0, 1.4), .02, .28), .45)
    return comb(out, .057, .34, .30)


def stillness():
    duration = 2.6
    out = silence(duration)
    # A long inhale that arrives at a single glassy stop, then a low bed that simply holds.
    add(out, 0, envelope(sweep(1.05, 38, 300, 1.0, 2.6), .85, .6, .7), .34)
    add(out, .90, envelope(partials(.7, 2050, [1, 2.83, 5.41], [.30, .18, .10], [.30, .16, .08], .008, 5), .0009, .22), .55)
    add(out, .92, envelope(noise(.05, 31), .0005, .014), .30)
    drone = partials(1.7, 63, [1, 1.5, 2.01, 3.02], [2.0, 1.6, 1.3, .9], [.26, .13, .10, .05], .004, 12)
    add(out, .92, envelope(drone, .18, 1.1, .8), .40)
    return comb(out, .083, .40, .34)


def resume():
    duration = 1.0
    out = silence(duration)
    add(out, 0, envelope(bandpass(noise(.6, 77), 500, .9, 4.0), .02, .2, 1.1), .5)
    add(out, .22, envelope(partials(.6, 320, [1, 2.02, 3.05], [.28, .2, .14], [.3, .16, .09], .01, 78), .006, .22), .45)
    return comb(out, .041, .25, .2)


def shimmer(duration, base, bright, wobble=0.0, seed=1):
    """Loki's own sorcery: clean, sharp, a little uncanny."""
    out = partials(duration, base, [1, 1.5, 2.0, 3.0, 4.5], [duration * .5] * 5,
                   [.30, .16, .12, .07, .04], .004, seed)
    air = envelope(bandpass(noise(duration, seed + 3), bright, 2.2, .6), .01, duration * .3)
    out = [a + b * .28 for a, b in zip(out, air)]
    if wobble:
        out = [s * (1 + wobble * math.sin(TAU * 6.5 * i / RATE)) for i, s in enumerate(out)]
    return envelope(out, .008, duration * .38)


def unstable(duration=1.35):
    """Time slipping: the sound should not sit still or resolve."""
    out = silence(duration)
    r = random.Random(4242)
    for i in range(7):
        t = r.uniform(0, duration * .6)
        seg = sweep(r.uniform(.18, .4), r.uniform(90, 900), r.uniform(60, 1400), 1.0, r.uniform(.4, 2.2))
        add(out, t, envelope(seg, .003, .12), r.uniform(.22, .5))
    add(out, 0, envelope(bandpass(noise(duration, 55), 240, .8, 3.0), .01, .35, 1.1), .38)
    return [s * (1 + .5 * math.sin(TAU * (3 + 9 * i / len(out)) * i / RATE)) for i, s in enumerate(out)]


def ascension():
    duration = 7.0
    out = silence(duration)
    for i, (ratio, delay) in enumerate([(1, 0), (1.5, .8), (2, 1.9), (3, 3.1), (4.5, 4.2)]):
        voice = partials(duration - delay, 82 * ratio, [1, 2, 3], [4.0, 3.0, 2.2], [.24, .10, .05], .003, 100 + i)
        add(out, delay, envelope(voice, 1.2, 3.4, .7), .55)
    add(out, 2.4, envelope(bandpass(noise(4.0, 66), 900, 1.2, 1.4), 1.6, 1.8, .8), .18)
    add(out, 5.4, envelope(partials(1.5, 1240, [1, 2.71, 4.2], [.6, .4, .3], [.26, .14, .08], .01, 9), .3, .55), .35)
    return comb(out, .127, .32, .3)


def embed():
    """Steel into stone or bone: a dull thunk with a short, choked metal ring."""
    duration = .34
    out = silence(duration)
    add(out, 0, envelope(bandpass(noise(duration, 404), 145, 1.0), .0009, .05, 1.1), .85)
    add(out, .003, partials(duration, 640, [1, 3.11, 5.2], [.16, .10, .06], [.30, .15, .07], .015, 404), .55)
    return comb(out, .027, .22, .2)


designs = {
    'blade_swing': lambda: swing(101),
    'blade_throw': lambda: swing(202, .30, 2100, 2.4, .70),
    'blade_hit': lambda: blade_hit(303),
    'blade_embed': embed,
    'rift_open': lambda: rift(True),
    'rift_close': lambda: rift(False),
    'time_stop': stillness,
    'time_resume': resume,
    'time_slip': unstable,
    'sorcery': lambda: shimmer(.60, 660, 3400, 0, 11),
    'illusion': lambda: shimmer(.85, 880, 4200, .12, 22),
    'teleport': lambda: shimmer(1.00, 440, 2600, .06, 33),
    'conjure': lambda: shimmer(.70, 1180, 5200, 0, 44),
    'ascend': ascension,
}

for name, build in designs.items():
    samples = build()
    peak = max(1e-6, max(abs(s) for s in samples))
    gain = .92 / peak
    frames = b''.join(struct.pack('<h', int(max(-1, min(1, s * gain)) * 32000)) for s in samples)
    wav = ROOT / f'sounds/{name}.wav'
    with wave.open(str(wav), 'wb') as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(frames)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-c:a', 'libvorbis', '-q:a', '4',
                    str(ROOT / f'sounds/{name}.ogg')], check=True)
    wav.unlink()

(ROOT / 'sounds.json').write_text(json.dumps(
    {name: {'subtitle': 'subtitles.loki.' + name, 'sounds': [{'name': 'loki:' + name, 'stream': name == 'ascend'}]}
     for name in sorted(designs)}, indent=2) + '\n')

subtitles = {
    'blade_swing': 'Blade cuts air', 'blade_throw': 'Dagger thrown', 'blade_hit': 'Blade strikes',
    'blade_embed': 'Blade buries itself', 'rift_open': 'Reality fractures', 'rift_close': 'Fracture seals',
    'time_stop': 'Time stops', 'time_resume': 'Time resumes', 'time_slip': 'Time slips',
    'sorcery': 'Sorcery', 'illusion': 'Illusion', 'teleport': 'Teleport', 'conjure': 'Conjuring',
    'ascend': 'Glorious purpose',
}
langpath = ROOT / 'lang/en_us.json'
lang = json.loads(langpath.read_text())
lang = {k: v for k, v in lang.items() if not k.startswith('subtitles.loki.')}
lang.update({'subtitles.loki.' + k: v for k, v in subtitles.items()})
langpath.write_text(json.dumps(lang, indent=2, sort_keys=True) + '\n')

print(f'Generated 5 authored meshes, 6 particle sprites, {len(list((ROOT/"player_animation").glob("*.json")))} animations '
      f'and {len(designs)} original sounds.')
