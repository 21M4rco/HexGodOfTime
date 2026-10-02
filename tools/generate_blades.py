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


def dagger():
    """
    A fighting dagger rather than a leaf: a long double-edged blade with a slight swell a third of the way up and a
    clean taper to the point, a fullered flat with runes in it, horned gold quillons sweeping toward the blade, a
    gold ecusson set with an emerald, a waisted green leather grip bound in gold wire, and a faceted gold pommel
    capped with a second stone. About a fifth longer than the old one.
    """
    m = Mesh()
    y0, y1 = .100, .660
    rings = []
    for i in range(15):
        t = i / 14
        y = y0 + (y1 - y0) * t
        swell = .040 + .007 * math.sin(min(1, t / .45) * math.pi / 2) - .047 * max(0, t - .3) ** 1.35 / .7 ** 1.35
        w = max(.0015, swell if t < 1 else .0015)
        rings.append((y, w, thickness(y, y0, y1, .0135, .0025), .5, 0.))
    m.blade(rings)
    # The ricasso: the unsharpened square shoulder between guard and edge.
    m.blade([(.072, .034, .015, .2, 0), (.100, .040, .0135, .5, 0)], 'ricasso', BEVEL_OUT)
    m.strip([(y, .0068 * (1 - (y - .13) / .45 * .55)) for y in [.13 + .45 * i / 8 for i in range(9)]],
            lambda y: thickness(y, y0, y1, .0135, .0025) + .0009, 'glow', RUNES, (.06, .74))
    # Guard: the ecusson, and two quillons that sweep out and curl up toward the blade like a pair of horns.
    m.lathe([(.040, .010), (.046, .030), (.058, .033), (.070, .026), (.074, .012)], GOLD, 'guard', 10, (1.5, .8))
    for side in (-1, 1):
        path, radii = [], []
        for i in range(13):
            t = i / 12
            path.append((side * (.02 + .115 * t), .056 + .07 * t ** 2.2, .004 * math.sin(t * math.pi)))
            radii.append(.0135 * (1 - t) ** .7 + .0035)
        m.tube(path, radii, GOLD, 'guard', 8)
        m.orb((side * .137, .128, 0), .0085, DARK_GOLD, 'guard')
    for face in (1, -1):
        m.orb((0, .058, face * .026), .0095, EMERALD, 'glow', (0, 0, face), .5)
    # Grip: ferrules, a waisted oval leather grip and a gold wire wound round it.
    m.lathe([(.022, .025), (.028, .028), (.040, .025)], GOLD, 'guard', 12, (1.15, .95))
    grip = [(-.150, .024), (-.120, .026), (-.065, .022), (-.010, .025), (.026, .024)]
    m.lathe(grip, LEATHER, 'grip', 12, (1.15, .92))
    m.helix(-.140, .018, grip, 6.5, .0028, GOLD, 'wire', (1.15, .92))
    m.lathe([(-.162, .021), (-.155, .028), (-.146, .026)], GOLD, 'guard', 12, (1.15, .95))
    # Pommel: a faceted gold knob, and its stone set in the end.
    m.lathe([(-.205, .012), (-.198, .026), (-.182, .031), (-.170, .028), (-.160, .019)], GOLD, 'pommel', 8)
    m.orb((0, -.207, 0), .011, EMERALD, 'glow', (0, -1, 0), .55)
    return m


def deceiver():
    """
    The Deceiver: a long, thin, fine sword. Not a rapier and nothing swept or caged about the hilt: a slender
    straight blade, barely wider than a finger, with a long fuller and runes down it, a slim cross guard whose
    quillons dip and then curl up, a long hand-and-a-half grip bound in gold wire, and a teardrop pommel.
    """
    m = Mesh()
    y0, y1 = .095, 1.050
    rings = []
    for i in range(21):
        t = i / 20
        y = y0 + (y1 - y0) * t
        w = .026 - .009 * t if t < .86 else .0137 * max(0., (1 - t) / .14) ** .8
        rings.append((y, max(.0012, w), thickness(y, y0, y1, .0095, .0025), .42, 0.))
    m.blade(rings)
    m.blade([(.066, .022, .011, .2, 0), (.095, .026, .0095, .42, 0)], 'ricasso', BEVEL_OUT)
    m.strip([(y, .0046 * (1 - (y - .13) / .72 * .45)) for y in [.13 + .72 * i / 12 for i in range(13)]],
            lambda y: thickness(y, y0, y1, .0095, .0025) + .0008, 'glow', RUNES, (.05, .80))
    m.lathe([(.030, .008), (.036, .024), (.050, .026), (.062, .020), (.066, .010)], GOLD, 'guard', 10, (1.6, .75))
    for side in (-1, 1):
        path, radii = [], []
        for i in range(17):
            t = i / 16
            path.append((side * (.02 + .16 * t), .048 - .022 * math.sin(t * math.pi * .8) + .05 * t ** 3, 0))
            radii.append(.0115 * (1 - t) ** .6 + .003)
        m.tube(path, radii, GOLD, 'guard', 8)
        m.orb((side * .182, .078, 0), .0075, DARK_GOLD, 'guard')
    for face in (1, -1):
        m.orb((0, .048, face * .0195), .0085, EMERALD, 'glow', (0, 0, face), .5)
    m.lathe([(.012, .021), (.018, .024), (.030, .021)], GOLD, 'guard', 12, (1.1, .95))
    grip = [(-.245, .021), (-.200, .023), (-.110, .025), (-.020, .022), (.014, .021)]
    m.lathe(grip, LEATHER, 'grip', 12, (1.1, .92))
    m.helix(-.236, .008, grip, 10, .0025, GOLD, 'wire', (1.1, .92))
    m.lathe([(-.258, .019), (-.251, .025), (-.242, .023)], GOLD, 'guard', 12, (1.1, .95))
    m.lathe([(-.300, .006), (-.294, .016), (-.282, .024), (-.268, .022), (-.258, .014)], GOLD, 'pommel', 10)
    m.orb((0, -.300, 0), .0085, EMERALD, 'glow', (0, -1, 0), .55)
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
# Each move: tick, right arm, left arm, torso (pitch, yaw, roll in degrees), and a right leg for the kick.
# Positive yaw turns a limb (or the torso) toward the player's right; negative pitch raises a limb forward.

NEUTRAL = (0, 0, 0)
GUARD_L = (-66, 32, 10)


def animation(name, moves, end):
    out = []
    for tick, right, left, torso, *leg in moves:
        move = {'tick': tick, 'easing': 'inoutquad',
                'rightArm': dict(zip(['pitch', 'yaw', 'roll'], right)),
                'leftArm': dict(zip(['pitch', 'yaw', 'roll'], left)),
                'torso': dict(zip(['pitch', 'yaw', 'roll'], torso))}
        if leg:
            move['rightLeg'] = dict(zip(['pitch', 'yaw', 'roll'], leg[0]))
        out.append(move)
    content = {'version': 3, 'name': name, 'author': 'HexGodOfStories',
               'description': 'A blade combo starter move: upper body only (the kick takes the right leg for its length); '
                              'locomotion stays free.',
               'emote': {'beginTick': 0, 'endTick': end, 'stopTick': end, 'isLoop': False, 'returnTick': end,
                         'degrees': True, 'moves': out}}
    (ROOT / f'player_animation/{name}.json').write_text(json.dumps(content, indent=2))


def slash(name, wind, contact, follow, settle, torso, left=None, hit=3, end=14):
    """wind -> contact on tick `hit` -> follow two ticks later -> settle; left arm in guard unless given."""
    left = left or (GUARD_L, GUARD_L, GUARD_L, GUARD_L)
    animation(name, [(0, wind, left[0], torso[0]), (hit - 1, wind, left[0], torso[0]), (hit, contact, left[1], torso[1]),
                     (hit + 2, follow, left[2], torso[2]), (hit + 5, settle, left[3], torso[3]),
                     (end, NEUTRAL, NEUTRAL, NEUTRAL)], end)


# The poses each move passes through, by name, for the check below and the files above.
POSES = {
    # The Flurry: forehand, backhand, a cut down from high on the right, a rising backhand, then the kick.
    'blade_dagger_0': [(-60, 8, -10), (-60, -22, -10), (-48, -44, -10), (-58, -12, -10)],
    'blade_dagger_1': [(-54, -42, -10), (-60, -2, -10), (-68, 14, -10), (-60, 0, -10)],
    'blade_dagger_2': [(-106, -10, -10), (-48, -22, -10), (-28, -42, -10), (-55, -12, -10)],
    'blade_dagger_3': [(-26, -38, -10), (-70, -14, -30), (-100, -24, -30), (-70, -10, -15)],
    # The Master Cuts, after Liechtenauer: Zornhau (the wrath cut, from the right shoulder down through the left),
    # Zwerchhau (the thwart cut, flat across at the head from the left), Zornort (the wrath's point, a thrust out of
    # the bind) and an Unterhau (a cut rising from below) that lifts the body off its feet.
    'blade_sword_0': [(-124, 0, -4), (-38, -24, -4), (-12, -34, -4), (-50, -10, -4)],
    'blade_sword_1': [(-58, -38, 0), (-58, -6, 0), (-72, -6, -72), (-60, -6, -30)],
    'blade_sword_2': [(-28, -20, 0), (-60, -2, 0), (-60, -2, 0), (-48, -8, 0)],
    'blade_sword_3': [(2, 0, -40), (-58, -18, -40), (-84, -48, -40), (-70, -30, -20)],
}
TORSO = {
    'blade_dagger_0': [(0, 20, 0), (2, 0, 0), (3, -22, 0), (0, -6, 0)],
    'blade_dagger_1': [(0, -22, 0), (2, 0, 0), (0, 20, 0), (0, 6, 0)],
    'blade_dagger_2': [(-5, 16, 0), (4, 0, 0), (6, -18, 0), (2, -6, 0)],
    'blade_dagger_3': [(6, -18, 0), (0, 0, 0), (-5, 16, 0), (0, 6, 0)],
    'blade_sword_0': [(-4, 22, 0), (4, 0, 0), (6, -22, 0), (2, -8, 0)],
    'blade_sword_1': [(-2, -25, 0), (0, 0, 0), (0, 25, 0), (0, 8, 0)],
    'blade_sword_2': [(0, 18, 0), (16, -8, 0), (16, -8, 0), (4, 0, 0)],
    'blade_sword_3': [(6, -20, 0), (0, 0, 0), (-6, 18, 0), (-2, 8, 0)],
}
# Two hands on the long grip: the left hand as near the right as the arms let it get.
SWORD_LEFT = {
    'blade_sword_0': [(-92, 58, 10), (-48, 62, 10), (-44, 82, 10), (-60, 60, 10)],
    'blade_sword_1': [(-52, 54, 10), (-52, 54, 14), (-74, 46, 14), (-70, 50, 10)],
    'blade_sword_2': [(-48, 60, 14), (-48, 60, 14), (-48, 60, 14), (-60, 56, 10)],
    'blade_sword_3': [(-48, 104, -8), (-74, 52, -8), (-106, 46, -8), (-80, 40, 0)],
}
# Where each cut sends the blood, in the caster's own right, up and forward; BladeCombo throws it the same way.
SWINGS = {
    'blade_dagger_0': (-1, 0, 0), 'blade_dagger_1': (1, .1, 0), 'blade_dagger_2': (-.7, -.7, 0), 'blade_dagger_3': (.7, .7, 0),
    'blade_sword_0': (-.7, -.7, 0), 'blade_sword_1': (1, 0, 0), 'blade_sword_2': (0, 0, 1), 'blade_sword_3': (.3, 1, 0),
}
HITS = {'blade_dagger_0': 3, 'blade_dagger_1': 3, 'blade_dagger_2': 3, 'blade_dagger_3': 3,
        'blade_sword_0': 5, 'blade_sword_1': 4, 'blade_sword_2': 4, 'blade_sword_3': 5}
ENDS = {'blade_dagger': 14, 'blade_sword': 18}


def animations():
    animation('blade_draw', [(0, (-15, 0, -5), (-15, 0, 5), NEUTRAL), (3, (-85, 20, -40), (-30, 10, 8), (0, 8, 0)),
                             (6, (-60, -10, -10), GUARD_L, NEUTRAL), (12, NEUTRAL, NEUTRAL, NEUTRAL)], 12)
    for name, (wind, contact, follow, settle) in POSES.items():
        sword = name.startswith('blade_sword')
        left = SWORD_LEFT[name] if sword else None
        slash(name, wind, contact, follow, settle, TORSO[name], left, HITS[name], ENDS['blade_sword' if sword else 'blade_dagger'])
    # The kick: a push kick off the right leg, leaning back from it, blade held back and the left hand up.
    guard = (-54, -10, -10)
    animation('blade_dagger_kick', [(0, guard, (-70, 20, 10), NEUTRAL, NEUTRAL),
                                    (3, guard, (-74, 22, 12), (-8, 0, 0), (-65, 0, 0)),
                                    (5, (-50, -14, -14), (-76, 24, 12), (-12, 0, 0), (-95, 0, 0)),
                                    (8, guard, (-70, 20, 10), (-6, 0, 0), (-60, 0, 0)),
                                    (12, (-40, -5, -8), (-50, 12, 8), (-2, 0, 0), (-10, 0, 0)),
                                    (16, NEUTRAL, NEUTRAL, NEUTRAL, NEUTRAL)], 16)


# ------------------------------------------------------------------ check ---
# The held-item chain, as the game runs it, in plain Python (no numpy): enough to find where a blade's point is.

def mat(rows): return [list(r) for r in rows]
def mm(a, b): return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def rx(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return mat([(1, 0, 0, 0), (0, c, -s, 0), (0, s, c, 0), (0, 0, 0, 1)])
def ry(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return mat([(c, 0, s, 0), (0, 1, 0, 0), (-s, 0, c, 0), (0, 0, 0, 1)])
def rz(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return mat([(c, -s, 0, 0), (s, c, 0, 0), (0, 0, 1, 0), (0, 0, 0, 1)])
def tr(x, y, z): return mat([(1, 0, 0, x), (0, 1, 0, y), (0, 0, 1, z), (0, 0, 0, 1)])
def sc(k): return mat([(k, 0, 0, 0), (0, k, 0, 0), (0, 0, k, 0), (0, 0, 0, 1)])


def point(arm, torso, length):
    """The blade's point in the caster's (right, up, forward), for a right arm and torso pose."""
    display = json.loads((ROOT / 'models/item/dagger.json').read_text())['display']['thirdperson_righthand']
    m = tr(0, 12 / 16, 0)
    for f in (rz(torso[2]), ry(torso[1]), rx(torso[0]), tr(0, -12 / 16, 0), tr(-5 / 16, 2 / 16, 0),
              rz(arm[2]), ry(arm[1]), rx(arm[0]), rx(-90), ry(180), tr(1 / 16, .125, -.625),
              tr(*[v / 16 for v in display['translation']]), rx(display['rotation'][0]), ry(display['rotation'][1]),
              rz(display['rotation'][2]), sc(display['scale'][0]), tr(-.5, -.5, -.5), tr(.24, .24, .5), rz(-45)):
        m = mm(m, f)
    p = [m[i][1] * length + m[i][3] for i in range(3)]
    return (-p[0], -p[1], -p[2])


def check():
    for name, poses in POSES.items():
        length = 1.05 if 'sword' in name else .66
        wind, contact, follow = (point(poses[i], TORSO[name][i], length) for i in range(3))
        travel = sub(follow, wind)
        swing = SWINGS[name]
        lateral = (travel[0], travel[1], 0) if swing[2] == 0 else travel
        agreement = dot(norm(lateral), norm(swing))
        assert agreement > .6, f'{name}: the point travels {travel}, the blood is thrown {swing}'
        assert contact[2] > .7, f'{name}: at the contact the point is only {contact[2]:.2f} in front'
        print(f'{name:16} point travels ({travel[0]:+.2f} right, {travel[1]:+.2f} up, {travel[2]:+.2f} fwd), '
              f'agrees {agreement:.2f}; at contact {contact[2]:.2f} in front')


if __name__ == '__main__':
    print('dagger faces', dagger().save('dagger'))
    print('deceiver faces', deceiver().save('deceiver'))
    texture()
    animations()
    check()
