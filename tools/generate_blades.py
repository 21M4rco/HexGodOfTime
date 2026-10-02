"""The conjured blades, reproducibly: the dagger, The Deceiver, the steel they share, and the combo starters' moves.

Writes
  models/dagger.obj, models/deceiver.obj   authored quads in item-model units, grip at the origin, blade up +Y
  textures/blade.png                       eight 32-pixel bands: two bevels, the fullered flat, gold, leather,
                                           emerald, dark gold, and the etched runes (transparent around them)
  player_animation/blade_*.json            the draw, the Flurry (four cuts and a kick), the Master Cuts (four)

Requires Pillow. Run from anywhere: `python3 tools/generate_blades.py`.

The meshes are real cross sections rather than flat leaves: a hexagonal blade (a flat down the middle of each face,
two bevels meeting at each edge), a fuller etched into the flat, a ricasso, guards and grips lathed and swept as
tubes with outward normals, a helical gold wire round each grip and cabochon emeralds. Texture coordinates run
along the blade (v from guard to point), so the fuller and its runes are one continuous etching from the ricasso
to where the blade narrows, and across each face (u), so every bevel brightens toward its edge.

The animations are posed, not guessed. Each key pose was fitted (by a small search over the arm's pitch, yaw and
roll) to where the grip and the point should be, through the same chain the game draws a held item with: the
arm's rotation, vanilla's hand layer, the item's display transform from models/item/*.json and WeaponRenderer's
grip. `check()` below runs that chain again on the shipped angles and fails the run unless every cut's point
really travels toward the side its blood is thrown to (server/BladeCombo's swings), so the two can never disagree.
"""
from pathlib import Path
import json
import math
import random

from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories'
TAU = math.tau
BANDS = 8
BEVEL_OUT, BEVEL_IN, FLAT, GOLD, LEATHER, EMERALD, DARK_GOLD, RUNES = range(8)


def band_u(band, t):
    """u across a band, kept a hair inside it so no sample bleeds into the next."""
    return band / BANDS + .004 + max(0., min(1., t)) * (1 / BANDS - .008)


def add(a, b): return tuple(x + y for x, y in zip(a, b))
def sub(a, b): return tuple(x - y for x, y in zip(a, b))
def mul(a, k): return tuple(x * k for x in a)
def dot(a, b): return sum(x * y for x, y in zip(a, b))
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def norm(a):
    length = math.sqrt(dot(a, a)) or 1
    return mul(a, 1 / length)


# ---------------------------------------------------------------- geometry ---

class Mesh:
    def __init__(self):
        self.v, self.uv, self.faces = [], [], []

    def quad(self, points, uvs, group):
        index = len(self.v) + 1
        self.v.extend(points)
        self.uv.extend(uvs)
        self.faces.append((group, [index + i for i in range(4)]))

    def blade(self, rings, group='blade', flat=FLAT):
        """
        rings: (y, half width, half thickness, bevel share, centre x) from the ricasso to the point. A hexagon each:
        the two edges, and between them a flat on either face bounded by the bevels' inner lines.
        """
        y0, y1 = rings[0][0], rings[-1][0]
        sections = []
        for y, w, t, bevel, cx in rings:
            b = w * bevel
            sections.append([(cx - w, y, 0), (cx - w + b, y, t), (cx + w - b, y, t),
                             (cx + w, y, 0), (cx + w - b, y, -t), (cx - w + b, y, -t)])
        # (face index) -> band, and whether u runs edge-to-inner (False) or inner-to-edge (True) along the face.
        faces = [(BEVEL_IN, 0, 1), (flat, 0, 1), (BEVEL_OUT, 0, 1), (BEVEL_IN, 0, 1), (flat, 0, 1), (BEVEL_OUT, 0, 1)]
        for i in range(len(sections) - 1):
            va, vb = (rings[i][0] - y0) / (y1 - y0), (rings[i + 1][0] - y0) / (y1 - y0)
            for k, (band, u0, u1) in enumerate(faces):
                a, b = sections[i], sections[i + 1]
                n = (k + 1) % 6
                self.quad([a[k], a[n], b[n], b[k]],
                          [(band_u(band, u0), va), (band_u(band, u1), va), (band_u(band, u1), vb), (band_u(band, u0), vb)],
                          group)

    def tube(self, path, radii, band, group, sides=12, squash=(1, 1), wrap=1.):
        """
        A tube along path with a rotation-minimising frame, so it never twists, and outward normals. u runs once
        round it (wrap times across the band), v along its length.
        """
        tangents = []
        for i in range(len(path)):
            a, b = path[max(0, i - 1)], path[min(len(path) - 1, i + 1)]
            tangents.append(norm(sub(b, a)))
        ref = (0, 0, 1) if abs(tangents[0][2]) < .9 else (1, 0, 0)
        n = norm(sub(ref, mul(tangents[0], dot(ref, tangents[0]))))
        rings, lengths, run = [], [], 0.
        for i, (c, t) in enumerate(zip(path, tangents)):
            n = norm(sub(n, mul(t, dot(n, t))))
            b = cross(t, n)
            if i: run += math.dist(path[i - 1], c)
            lengths.append(run)
            rings.append([add(c, add(mul(n, radii[i] * squash[0] * math.cos(a * TAU / sides)),
                                     mul(b, radii[i] * squash[1] * math.sin(a * TAU / sides)))) for a in range(sides)])
        total = run or 1
        for i in range(len(rings) - 1):
            va, vb = lengths[i] / total, lengths[i + 1] / total
            for a in range(sides):
                ua, ub = (a / sides * wrap) % 1, ((a + 1) / sides * wrap) % 1 or 1.
                if ub < ua: ub = 1.
                self.quad([rings[i][a], rings[i][(a + 1) % sides], rings[i + 1][(a + 1) % sides], rings[i + 1][a]],
                          [(band_u(band, ua), va), (band_u(band, ub), va), (band_u(band, ub), vb), (band_u(band, ua), vb)],
                          group)

    def lathe(self, profile, band, group, sides=12, squash=(1, 1)):
        """
        A body of revolution round the blade's own axis: profile is (y, radius) from bottom to top, squash its
        (across the blade, through the blade) proportions. (Along +Y the tube's frame starts on z, then x.)
        """
        self.tube([(0, y, 0) for y, _ in profile], [r for _, r in profile], band, group, sides, (squash[1], squash[0]))

    def orb(self, centre, radius, band, group, axis=(0, 1, 0), flat=1., rows=6, sides=10):
        """A cabochon or knob: a sphere squashed along axis by flat."""
        axis = norm(axis)
        path, radii = [], []
        for i in range(rows + 1):
            a = -math.pi / 2 + math.pi * i / rows
            path.append(add(centre, mul(axis, math.sin(a) * radius * flat)))
            radii.append(max(1e-4, math.cos(a) * radius))
        self.tube(path, radii, band, group, sides)

    def strip(self, rings, z_of, group, band, v_range):
        """The etched runes: a flat ribbon over the fuller on both faces, a hair proud of the steel."""
        y0, y1 = rings[0][0], rings[-1][0]
        for face in (1, -1):
            for i in range(len(rings) - 1):
                (ya, wa), (yb, wb) = rings[i], rings[i + 1]
                va = v_range[0] + (v_range[1] - v_range[0]) * (ya - y0) / (y1 - y0)
                vb = v_range[0] + (v_range[1] - v_range[0]) * (yb - y0) / (y1 - y0)
                za, zb = face * z_of(ya), face * z_of(yb)
                pts = [(-wa, ya, za), (wa, ya, za), (wb, yb, zb), (-wb, yb, zb)]
                uvs = [(band_u(band, 0), va), (band_u(band, 1), va), (band_u(band, 1), vb), (band_u(band, 0), vb)]
                if face < 0:
                    pts = [pts[1], pts[0], pts[3], pts[2]]
                    uvs = [uvs[1], uvs[0], uvs[3], uvs[2]]
                self.quad(pts, uvs, group)

    def helix(self, y0, y1, profile, turns, wire, band, group, squash=(1, 1)):
        """Wire wound round a lathed grip, riding its surface: profile is the grip's own (y, radius)."""
        def radius(y):
            for (ya, ra), (yb, rb) in zip(profile, profile[1:]):
                if ya <= y <= yb: return ra + (rb - ra) * (y - ya) / (yb - ya)
            return profile[0][1] if y < profile[0][0] else profile[-1][1]
        steps = int(turns * 16)
        path = []
        for i in range(steps + 1):
            y, a = y0 + (y1 - y0) * i / steps, i / steps * turns * TAU
            r = radius(y) + wire * .6
            path.append((math.cos(a) * r * squash[0], y, math.sin(a) * r * squash[1]))
        self.tube(path, [wire] * len(path), band, group, 5)

    def save(self, name):
        lines = ['# HexGodOfStories authored geometry (tools/generate_blades.py): item-model units, grip at origin, blade toward +Y.']
        lines += ['v %.6f %.6f %.6f' % p for p in self.v]
        lines += ['vt %.6f %.6f' % p for p in self.uv]
        group = None
        for g, face in self.faces:
            if g != group:
                lines.append('g ' + g)
                group = g
            lines.append('f ' + ' '.join(f'{i}/{i}' for i in face))
        (ROOT / f'models/{name}.obj').write_text('\n'.join(lines) + '\n')
        return len(self.faces)


def thickness(y, y0, y1, base, tip):
    t = (y - y0) / (y1 - y0)
    return base + (tip - base) * t ** 1.2


# Where the fist closes on a held blade, measured through the game's own chain (tools/blade_rig.py, fist()): its
# middle sits 0.08 below the mesh origin along the blade, and at the hand's size it covers about 0.3 of it. Each grip
# is centred there and made longer than the fist, so handle shows above and below the hand, and the pommel beneath.
FIST = -.08


def dagger():
    """
    A fighting dagger rather than a leaf: a long double-edged blade with a slight swell a third of the way up and a
    clean taper to the point, a fullered flat with runes in it, horned gold quillons sweeping toward the blade, a
    gold ecusson set with an emerald, a waisted green leather grip bound in gold wire, long enough to show either side
    of the fist, and a faceted gold pommel capped with a second stone. About a block long in the hand.
    """
    m = Mesh()
    y0, y1 = .200, .800
    rings = []
    for i in range(15):
        t = i / 14
        y = y0 + (y1 - y0) * t
        swell = .050 + .008 * math.sin(min(1, t / .45) * math.pi / 2) - .058 * max(0, t - .3) ** 1.35 / .7 ** 1.35
        rings.append((y, max(.0018, swell if t < 1 else .0018), thickness(y, y0, y1, .016, .003), .5, 0.))
    m.blade(rings)
    # The ricasso: the unsharpened square shoulder between guard and edge.
    m.blade([(.165, .042, .018, .2, 0), (.200, .050, .016, .5, 0)], 'ricasso', BEVEL_OUT)
    m.strip([(y, .0085 * (1 - (y - .24) / .46 * .55)) for y in [.24 + .46 * i / 8 for i in range(9)]],
            lambda y: thickness(y, y0, y1, .016, .003) + .0009, 'glow', RUNES, (.06, .74))
    # Guard: the ecusson, and two quillons that sweep out and curl up toward the blade like a pair of horns.
    m.lathe([(.128, .012), (.134, .036), (.148, .040), (.162, .031), (.168, .014)], GOLD, 'guard', 10, (1.5, .8))
    for side in (-1, 1):
        path, radii = [], []
        for i in range(13):
            t = i / 12
            path.append((side * (.024 + .14 * t), .146 + .085 * t ** 2.2, .005 * math.sin(t * math.pi)))
            radii.append(.016 * (1 - t) ** .7 + .0042)
        m.tube(path, radii, GOLD, 'guard', 8)
        m.orb((side * .167, .233, 0), .0105, DARK_GOLD, 'guard')
    for face in (1, -1):
        m.orb((0, .148, face * .031), .0115, EMERALD, 'glow', (0, 0, face), .5)
    # Grip: ferrules, a waisted oval leather grip centred on the fist, and a gold wire wound round it.
    grip = [(-.290, .031), (-.240, .034), (-.090, .029), (.060, .033), (.118, .031)]
    m.lathe([(.108, .032), (.116, .036), (.130, .032)], GOLD, 'guard', 12, (1.15, .95))
    m.lathe(grip, LEATHER, 'grip', 12, (1.15, .92))
    m.helix(-.280, .108, grip, 9, .0034, GOLD, 'wire', (1.15, .92))
    m.lathe([(-.305, .028), (-.297, .036), (-.286, .034)], GOLD, 'guard', 12, (1.15, .95))
    # Pommel: a faceted gold knob, and its stone set in the end.
    m.lathe([(-.355, .014), (-.347, .031), (-.330, .038), (-.316, .034), (-.303, .023)], GOLD, 'pommel', 8)
    m.orb((0, -.358, 0), .013, EMERALD, 'glow', (0, -1, 0), .55)
    return m


def deceiver():
    """
    The Deceiver: a long, slender, fine sword. Not a rapier and nothing swept or caged about the hilt: a straight
    blade with a long fuller and runes down it, a cross guard whose quillons dip and then curl up, a long
    hand-and-a-half grip centred on the fist and bound in gold wire, and a teardrop pommel. Slender beside the dagger,
    but a real sword's width in the game (about a pixel and a half) and some five feet long in the hand.
    """
    m = Mesh()
    y0, y1 = .270, 1.550
    rings = []
    for i in range(23):
        t = i / 22
        y = y0 + (y1 - y0) * t
        w = .055 - .017 * t if t < .86 else .0404 * max(0., (1 - t) / .14) ** .8
        rings.append((y, max(.0018, w), thickness(y, y0, y1, .017, .0035), .42, 0.))
    m.blade(rings)
    m.blade([(.225, .046, .019, .2, 0), (.270, .055, .017, .42, 0)], 'ricasso', BEVEL_OUT)
    m.strip([(y, .009 * (1 - (y - .32) / .92 * .45)) for y in [.32 + .92 * i / 14 for i in range(15)]],
            lambda y: thickness(y, y0, y1, .017, .0035) + .0010, 'glow', RUNES, (.05, .80))
    m.lathe([(.180, .013), (.188, .038), (.205, .041), (.220, .032), (.228, .016)], GOLD, 'guard', 10, (1.6, .75))
    for side in (-1, 1):
        path, radii = [], []
        for i in range(17):
            t = i / 16
            path.append((side * (.03 + .25 * t), .204 - .032 * math.sin(t * math.pi * .8) + .075 * t ** 3, 0))
            radii.append(.018 * (1 - t) ** .6 + .0048)
        m.tube(path, radii, GOLD, 'guard', 8)
        m.orb((side * .284, .281, 0), .0118, DARK_GOLD, 'guard')
    for face in (1, -1):
        m.orb((0, .205, face * .030), .0125, EMERALD, 'glow', (0, 0, face), .5)
    grip = [(-.330, .032), (-.270, .035), (-.080, .038), (.110, .034), (.172, .032)]
    m.lathe([(.164, .033), (.172, .037), (.186, .033)], GOLD, 'guard', 12, (1.1, .95))
    m.lathe(grip, LEATHER, 'grip', 12, (1.1, .92))
    m.helix(-.320, .164, grip, 13, .0036, GOLD, 'wire', (1.1, .92))
    m.lathe([(-.346, .029), (-.338, .038), (-.327, .035)], GOLD, 'guard', 12, (1.1, .95))
    m.lathe([(-.405, .010), (-.396, .026), (-.378, .039), (-.358, .035), (-.344, .022)], GOLD, 'pommel', 10)
    m.orb((0, -.408, 0), .013, EMERALD, 'glow', (0, -1, 0), .55)
    return m


# ---------------------------------------------------------------- texture ---

def texture():
    rng = random.Random(70413)
    image = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    lines = [rng.uniform(-1, 1) for _ in range(32)]        # brushing: one value per column, all down the blade
    grain = [[rng.uniform(-1, 1) for _ in range(256)] for _ in range(32)]

    def put(band, x, y, c):
        image.putpixel((band * 32 + x, y), tuple(max(0, min(255, int(round(v)))) for v in c))

    def mix(a, b, t): return tuple(x + (y - x) * t for x, y in zip(a, b))
    steel, bright, edge = (128, 138, 147), (204, 213, 221), (243, 247, 250)
    for x in range(32):
        u = (x + .5) / 32
        for y in range(256):
            brush = 7 * lines[x] + 2 * grain[x][y]
            # Bevels: brightening toward the edge, the last sliver nearly white. BEVEL_IN is the same, mirrored.
            c = mix(steel, bright, u ** 1.6)
            if u > .88: c = mix(c, edge, (u - .88) / .12)
            put(BEVEL_OUT, x, y, (*add(c, (brush,) * 3), 255))
            c = mix(steel, bright, (1 - u) ** 1.6)
            if u < .12: c = mix(c, edge, (.12 - u) / .12)
            put(BEVEL_IN, x, y, (*add(c, (brush,) * 3), 255))
            # The flat: a little darker than the bevels, with the fuller cut down its middle from the ricasso to
            # three quarters of the way up, its ends rounded, its walls catching the light.
            v = (y + .5) / 256
            c = mix((112, 121, 130), (150, 159, 167), .5 + .5 * math.cos((u - .5) * math.pi))
            half = .15 * min(1, max(0, (v - .02) / .05)) * min(1, max(0, (.74 - v) / .06)) ** .5
            d = abs(u - .5)
            if half > 0 and d < half:
                depth = d / half
                c = mix((88, 96, 105), (122, 130, 138), depth ** 2)
            elif half > 0 and d < half + .045:
                c = (184, 192, 200)
            put(FLAT, x, y, (*add(c, (brush * .8,) * 3), 255))
            # Gold: a polished band of light across each facet, darker toward its edges, finely chased.
            g = mix((150, 108, 40), (246, 213, 128), math.sin(u * math.pi) ** 1.5)
            chase = 10 * math.sin(y * .9 + x * .5) * (grain[x][y] > .55)
            put(GOLD, x, y, (*add(g, (chase + 4 * grain[x][y],) * 3), 255))
            # Leather: dark green, wound diagonally, each turn shaded into the next.
            turn = (u * 2 + v * 14) % 1
            l = mix((22, 38, 27), (52, 78, 56), math.sin(turn * math.pi) ** .7)
            put(LEATHER, x, y, (*add(l, (5 * grain[x][y],) * 3), 255))
            # Emerald: deep at the rim, clear and bright at the heart, one hard highlight.
            r = math.hypot(u - .42, v - .4)
            e = mix((150, 255, 190), (8, 92, 46), min(1, r * 1.7))
            if r < .1: e = mix((235, 255, 240), e, r / .1)
            put(EMERALD, x, y, (*e, 255))
            dg = mix((86, 62, 26), (164, 124, 56), math.sin(u * math.pi))
            put(DARK_GOLD, x, y, (*add(dg, (5 * grain[x][y],) * 3), 255))
    # The runes: angular strokes cut along the fuller, transparent everywhere else.
    runes = Image.new('RGBA', (32, 256), (0, 0, 0, 0))
    y = 6
    while y < 250:
        h = rng.randint(14, 20)
        strokes = rng.sample([((8, 0), (8, 1)), ((24, 0), (24, 1)), ((8, 0), (24, .5)), ((24, 0), (8, .5)),
                              ((8, .5), (24, 1)), ((24, .5), (8, 1)), ((8, .3), (24, .3)), ((16, 0), (16, 1))],
                             rng.randint(2, 4))
        for (xa, ta), (xb, tb) in strokes:
            for s in range(40):
                f = s / 39
                px, py = xa + (xb - xa) * f, y + (ta + (tb - ta) * f) * h
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        qx, qy = int(px + dx), int(py + dy)
                        if 0 <= qx < 32 and 0 <= qy < 256:
                            core = dx == 0 and dy == 0
                            runes.putpixel((qx, qy), (170, 255, 196, 255) if core else (66, 214, 120, 255))
        y += h + rng.randint(5, 9)
    image.paste(runes, (RUNES * 32, 0))
    image.save(ROOT / 'textures/blade.png')


# -------------------------------------------------------------- animations ---

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import blade_moves  # noqa: E402  (the moves themselves, and the contact and blood each cut must agree on)


def animations(resolved):
    """Each move, key by key, every part it poses, with the easing on the way into each key."""
    for name, m in resolved.items():
        moves = []
        for tick, ease, pose in m['keys']:
            move = {'tick': tick, 'easing': ease}
            for part, values in pose.items():
                move[part] = dict(values)
            moves.append(move)
        loop = m['loop']
        content = {'version': 3, 'name': name, 'author': 'HexGodOfStories',
                   'description': 'A blade move (tools/blade_moves.py): the whole body, never locking the player in place.',
                   'emote': {'beginTick': 0, 'endTick': m['end'], 'stopTick': m['end'], 'isLoop': loop is not None,
                             'returnTick': m['end'] if loop is None else loop, 'degrees': True, 'easeBeforeKeyframe': True,
                             'moves': moves}}
        (ROOT / f'player_animation/{name}.json').write_text(json.dumps(content, indent=1))


# ------------------------------------------------------------------ check ---

def check(resolved):
    """
    Through the game's own chain (blade_rig): every grip is in the fist, and every cut's point really travels toward
    the side its blood is thrown to, in front of the body, across its contact.
    """
    import numpy as np
    import blade_rig as rig
    for model in ('dagger', 'deceiver'):
        f = rig.fist({}, model)
        grip = {'dagger': (-.290, .118), 'deceiver': (-.330, .172)}[model]
        assert grip[0] + .1 < f[1] < grip[1] - .1 and abs(f[0]) < .02 and abs(f[2]) < .02, f'{model}: the fist is at {f}, not on the grip {grip}'
        print(f'{model:9} fist at {f[1]:+.3f} along the blade: on the grip {grip}, {f[1] - grip[0]:.2f} of handle below it, {grip[1] - f[1]:.2f} above')
    # The handle in the fist all the way through every move, not only at its keys: every half tick.
    worst = (0, ''); offs = {}
    for name, move in resolved.items():
        model = 'deceiver' if any(k in name for k in ('sword', 'guard', 'run', 'deflect')) else 'dagger'
        for half in range(0, move['end'] * 2 + 1):
            f = rig.fist(rig.keyed(move, half / 2), model)
            off = float(np.hypot(f[0], f[2]) + abs(f[1] + .08)) * .85
            if off > worst[0]: worst = (off, f'{name} t{half / 2}')
            if off > offs.get(name, (0, 0))[0]: offs[name] = (off, half / 2)
    for name in sorted(offs, key=lambda n: -offs[n][0])[:6]: print(f'  handle {name:18} worst {offs[name][0]:.3f} at t{offs[name][1]}')
    assert worst[0] < .065, f'the handle leaves the fist by {worst[0]:.3f} at {worst[1]}'
    print(f'handle in the fist through every move: worst {worst[0]:.3f} blocks off, at {worst[1]}')
    # No arm through the body: neither forearm's middle line may pass into the chest or the head (each arm is 4px
    # thick, so a line 1.5px in is an arm well buried) at any half tick, which an arm swung round the wrong way to
    # reach across (up and back over the shoulder, behind the back) always does somewhere on its way.
    worst = (0, '')
    for name, move in resolved.items():
        for half in range(0, move['end'] * 2 + 1):
            d = rig.intrusion(rig.keyed(move, half / 2))
            if d > worst[0]: worst = (d, f'{name} t{half / 2}')
    assert worst[0] < 1.5, f'an arm goes {worst[0]:.1f}px into the body at {worst[1]}'
    print(f'no arm through the body: deepest {worst[0]:.1f}px, at {worst[1]}')
    for name, swing in blade_moves.SWINGS.items():
        move = resolved[name]
        model, length = ('deceiver', 1.55) if 'sword' in name else ('dagger', .80)
        c = blade_moves.CONTACT[name]
        before, at, after = (rig.tip(rig.keyed(move, t), model, length)[0] for t in (c - 1, c, c + 1))
        travel = after - before
        s = np.array(swing, float)
        flat = travel if s[2] else np.array([travel[0], travel[1], 0])
        agreement = float(flat @ s / (np.linalg.norm(flat) * np.linalg.norm(s) + 1e-9))
        assert agreement > .55, f'{name}: the point travels {np.round(travel, 2)}, its blood goes {swing}'
        assert at[2] > .45, f'{name}: at contact the point is only {at[2]:.2f} in front'
        print(f'{name:18} contact t{c}: point {np.round(at, 2)} travelling {np.round(travel, 2)}, agrees {agreement:.2f}')


if __name__ == '__main__':
    print('dagger faces', dagger().save('dagger'))
    print('deceiver faces', deceiver().save('deceiver'))
    texture()
    import blade_rig
    resolved = blade_rig.resolve(blade_moves.MOVES, lambda name, miss: print(f'{name:18} solved, worst miss {miss:.3f}'))
    animations(resolved)
    check(resolved)
