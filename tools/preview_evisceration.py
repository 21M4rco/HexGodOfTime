"""Complete Evisceration, simulated and drawn: `python3 tools/preview_evisceration.py OUT_DIR [zombie] [player]`.

The bearer is the player model through the game's own transform chain (tools/blade_rig.py), playing the generated
moves (player_animation/blade_sword_evis_*.json) with the dash and the step back moving them as server/Evisceration
does, tick for tick. The body is a zombie or a player, cut along the plane the server sends, by the same means
client/Halving draws it: every face clipped to one side of the plane, the lines the faces are cut along closed into
loops and filled as the cut face, red-hot and cooling (data/HoleHeat), each half thrown, turned over and landed with
Halving's own numbers. The blood is Blood.slash's sprays and the cut faces' pouring, as particles with the blood
particle's own gravity and drag, and the pools they leave.

It is a picture of the move, not the game: no lighting but a sun, a flat floor, and the blood's sprites drawn as
squares. Requires numpy and Pillow, and ffmpeg for the videos. Writes per body: <body>.mp4 (real time), <body>_slow.mp4
(a quarter speed), <body>.gif (half speed, smaller) and <body>_sheet.png (key moments).
"""
from pathlib import Path
import json
import math
import random
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import blade_rig as rig  # noqa: E402

ANIM = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories/player_animation'

# server/Evisceration
SPEED, REACH, DASH, THRUST, DOOMED_HOLD, CUT_HIT = 1.05, .9, 8, 2, 4, 6
GUT, ANGLE, BLOOD = .58, 50, 2.5
# client/Halving
GRAVITY, MOST_SPIN, COOLING, RISE = .075, .5, 160, 5
FLESH, CHAR = (.5, .05, .045), (.16, .03, .02)
# The blood particle (HexParticles.Drip): gravity .75 of vanilla's .04, drag .98, life 26 to 43 ticks.
DRIP_FALL, DRIP_DRAG = .03, .98
STOPS = [0, .92, .84, .42, .2, .96, .76, .26, .45, 1, .5, .1, .7, 1, .26, .04, 1, 1, .1, .02]

BODIES = {
    # Zombie: green skin, cyan shirt, purple trousers, arms held out in front. Its box is 1.95 high.
    'zombie': dict(height=1.95, width=.6, arms=-90,
                   colors={'head': (86, 132, 66), 'torso': (40, 150, 160), 'rightArm': (86, 132, 66), 'leftArm': (80, 124, 62),
                           'rightLeg': (72, 60, 140), 'leftLeg': (64, 54, 128)}, face=(40, 64, 34)),
    # A player in the default skin: arms down. Its box is 1.8 high.
    'player': dict(height=1.8, width=.6, arms=0,
                   colors={'head': (176, 124, 92), 'torso': (40, 168, 190), 'rightArm': (176, 124, 92), 'leftArm': (166, 116, 86),
                           'rightLeg': (64, 64, 160), 'leftLeg': (56, 56, 146)}, face=(70, 40, 30)),
}

UP = np.array([0., 1, 0])
# The bearer faces +z; their right is -x (blade_rig's world).
FORWARD, RIGHT = np.array([0., 0, 1]), np.array([-1., 0, 0])


def unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


def load(name):
    e = json.loads((ANIM / f'{name}.json').read_text())['emote']
    keys = [(m['tick'], m['easing'], {k: v for k, v in m.items() if k not in ('tick', 'easing')}) for m in e['moves']]
    return {'end': e['endTick'], 'keys': keys}


def heat(age):
    t = age + RISE
    cooled = (t - RISE) / COOLING
    return 0. if cooled >= 1 else 1 - cooled


def glow(h):
    if h <= 0: return np.zeros(3)
    i = 0
    while i + 8 < len(STOPS) and STOPS[i + 4] < h: i += 4
    f = (h - STOPS[i]) / (STOPS[i + 4] - STOPS[i])
    bright = h ** .6
    return np.array([(STOPS[i + 1 + c] + (STOPS[i + 5 + c] - STOPS[i + 1 + c]) * f) * bright for c in range(3)])


def axis_angle(axis, angle):
    x, y, z = unit(axis)
    c, s, C = math.cos(angle), math.sin(angle), 1 - math.cos(angle)
    return np.array([[c + x * x * C, x * y * C - z * s, x * z * C + y * s],
                     [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
                     [z * x * C - y * s, z * y * C + x * s, c + z * z * C]])


# ------------------------------------------------------------------ the body, and cutting it

def body_faces(kind):
    """The body's faces at rest, from its feet, facing the bearer (-z): (corners, colour) each."""
    spec = BODIES[kind]
    frame = {'rightArm': {'pitch': spec['arms']}, 'leftArm': {'pitch': spec['arms']}}
    R = rig.root(frame)
    up, tor = rig.upper(frame)
    turn = np.diag([-1., 1, -1])
    faces = []
    for name, (lo, hi, _) in rig.BOXES.items():
        M = R @ tor if name == 'torso' else R @ (up if name in ('head', 'rightArm', 'leftArm') else np.eye(4)) @ rig.partm(frame, name)
        for i, quad in enumerate(rig.box_faces(lo, hi, M)):
            colour = spec['face'] if name == 'head' and i == 0 else spec['colors'][name]
            faces.append(([turn @ np.array(p) for p in quad], np.array(colour) / 255))
    return faces


def clip(polygon, point, keep):
    """What of a polygon is on the kept side of the plane, and the line it was cut along (Halving.Clipper.clip)."""
    out, crossed = [], []
    n = len(polygon)
    for i in range(n):
        a, b = polygon[i], polygon[(i + 1) % n]
        da, db = float((a - point) @ keep), float((b - point) @ keep)
        da = 1e-6 if abs(da) < 1e-6 else da
        db = 1e-6 if abs(db) < 1e-6 else db
        if da >= 0: out.append(a)
        if (da >= 0) != (db >= 0):
            t = da / (da - db)
            c = a + (b - a) * t
            out.append(c)
            crossed.append(c)
    return out, (crossed if len(crossed) == 2 else None)


def loops(lines, join=1e-3):
    """The cut lines closed into loops, as Halving.Clipper.faces does; open ones are dropped."""
    done = [False] * len(lines)
    found = []
    for first in range(len(lines)):
        if done[first]: continue
        done[first] = True
        loop = [lines[first][0]]
        end = lines[first][1]
        closed = False
        while len(loop) < 512:
            if np.linalg.norm(end - loop[0]) < join: closed = True; break
            loop.append(end)
            nxt = None
            for j, (a, b) in enumerate(lines):
                if done[j]: continue
                if np.linalg.norm(a - end) < join: nxt, far = j, b; break
                if np.linalg.norm(b - end) < join: nxt, far = j, a; break
            if nxt is None: break
            done[nxt] = True
            end = far
        if closed and len(loop) >= 3: found.append(loop)
    return found


class Piece:
    """One half, as Halving.Piece: what it keeps, where it turns about, and how it moves."""

    def __init__(self, side, faces, caps, pivot, hull, vel, axis, topple, spin, accel, delay):
        self.side, self.faces, self.caps, self.pivot, self.hull = side, faces, caps, pivot, hull
        self.vel, self.axis, self.topple, self.spin, self.accel, self.delay = vel, unit(axis), topple, spin, accel, delay
        self.pos = np.zeros(3)
        self.prev_pos = np.zeros(3)
        self.rot = np.eye(3)
        self.prev_rot = np.eye(3)
        self.angle = self.prev_angle = 0.
        self.grounded = False

    def place(self, local, partial=1.):
        angle = self.prev_angle + (self.angle - self.prev_angle) * partial
        pos = self.prev_pos + (self.pos - self.prev_pos) * partial
        return pos + self.pivot + axis_angle(self.axis, angle) @ (np.asarray(local) - self.pivot)

    def step(self, age, landed):
        self.prev_pos, self.prev_angle = self.pos.copy(), self.angle
        if age >= self.delay and self.angle < self.topple:
            self.spin = min(self.spin + self.accel, MOST_SPIN)
            self.angle = min(self.topple, self.angle + self.spin)
        self.vel = self.vel + np.array([0, -GRAVITY, 0])
        self.pos = self.pos + self.vel
        low = min(self.place(c)[1] for c in self.hull)
        if low < 0:
            self.pos = self.pos + np.array([0, -low, 0])
            if not self.grounded and self.vel[1] < -.1: landed(self)
            if self.vel[1] < 0: self.vel = np.array([self.vel[0] * .5, -self.vel[1] * .15, self.vel[2] * .5])
            self.grounded = True
        if self.grounded: self.vel = np.array([self.vel[0] * .78, self.vel[1], self.vel[2] * .78])


def halve(kind, rng):
    """The two pieces, as Halving.begin makes them."""
    spec = BODIES[kind]
    h, w = spec['height'], spec['width']
    a = math.radians(ANGLE)
    point = np.array([0, h * GUT, 0])
    normal = RIGHT * math.sin(a) + UP * math.cos(a)
    line = RIGHT * math.cos(a) - UP * math.sin(a)
    faces = body_faces(kind)
    size = math.sqrt(max(.5, max(h, w)) / 1.8)
    slow = 1 / math.sqrt(size)
    samples = [np.array([w * (i / 6 - .5), h * j / 6, w * (k / 6 - .5)]) for i in range(7) for j in range(7) for k in range(7)]
    up = [p for p in samples if (p - point) @ normal >= 0]
    down = [p for p in samples if (p - point) @ normal < 0]
    falls = unit(FORWARD + RIGHT * -.35)
    pieces = []
    for side in (1, -1):
        keep = normal * side
        kept, lines = [], []
        for quad, colour in faces:
            left, crossed = clip(quad, point, keep)
            if len(left) >= 3: kept.append((left, colour))
            if crossed: lines.append(crossed)
        caps = loops(lines)
        if side > 0:
            pieces.append(Piece(1, kept, caps, np.mean(up, axis=0), up,
                                (FORWARD * .22 + line * .17 + UP * .15) * math.sqrt(size), FORWARD + RIGHT * -.6,
                                math.radians(100 + rng.random() * 25), .1 * slow, .035 * slow, 0))
        else:
            pieces.append(Piece(-1, kept, caps, falls * w * .5, down,
                                (FORWARD * .14 + line * -.04 + UP * .05) * math.sqrt(size), -RIGHT + FORWARD * -.45,
                                math.radians(84 + rng.random() * 10), 0., .028 * slow, 3))
    return pieces, point, normal, line


# ------------------------------------------------------------------ blood

class Blood:
    """The blood particles and the pools they leave, stepped as the game steps them."""

    def __init__(self, rng):
        self.rng = rng
        self.p = np.zeros((0, 3))
        self.v = np.zeros((0, 3))
        self.age = np.zeros(0)
        self.life = np.zeros(0)
        self.size = np.zeros(0)
        self.pools = []  # (x, z, size, born, spread)
        self.spurts = []  # (at, swing, power, born)

    def add(self, at, vel, n=1):
        at, vel = np.atleast_2d(at), np.atleast_2d(vel)
        self.p = np.vstack([self.p, at])
        self.v = np.vstack([self.v, vel])
        self.age = np.concatenate([self.age, np.zeros(len(at))])
        self.life = np.concatenate([self.life, 26 + self.rng.integers(0, 18, len(at))])
        self.size = np.concatenate([self.size, .09 + self.rng.random(len(at)) * .06])

    def pool(self, at, size, now, spread=18):
        self.pools.append((at[0], at[2], size, now, spread))

    def slash(self, at, swing, power, now):
        """Blood.slash: the sheet off the edge, the gobs, the fine spray, the pools along the way, and three spurts."""
        r = self.rng
        swing = unit(np.asarray(swing, float))
        power = max(.3, min(2.5, power))
        across = np.cross(swing, UP)
        across = unit(across if np.linalg.norm(across) > 1e-2 else np.cross(swing, [1, 0, 0]))
        n = round(120 * power)
        u = r.random((n, 5))
        start = at + across * (u[:, :1] - .5) * .55 + swing * u[:, 1:2] * .15
        speed = (.12 + u[:, 2:3] * .46) * (.65 + .35 * power)
        self.add(start, swing * speed + np.c_[(u[:, 3] - .5) * .12, .03 + u[:, 4] * .14, (r.random(n) - .5) * .12])
        n = round(26 * power)
        u = r.random((n, 5))
        self.add(at + across * (u[:, :1] - .5) * .3, swing * (.06 + u[:, 1:2] * .2) + np.c_[(u[:, 2] - .5) * .1, .12 + u[:, 3] * .2, (u[:, 4] - .5) * .1])
        n = round(36 * power)
        u = r.random((n, 3))
        self.add(np.repeat(np.atleast_2d(at), n, 0), np.c_[(u[:, 0] - .5) * .3, (u[:, 1] - .3) * .22, (u[:, 2] - .5) * .3])
        for _ in range(round(7 * power) + 3):
            self.pool(at + swing * (.3 + r.random() * 2.4 * power) + np.array([(r.random() - .5) * .8, 0, (r.random() - .5) * .8]),
                      r.random() * .3 + .16, now)
        self.spurts.append((np.asarray(at, float), swing, power, now))

    def step(self, now):
        r = self.rng
        for at, swing, power, born in self.spurts:
            age = now - born
            if age in (5, 12, 19):
                left = 1 - age / 22 * .6
                n = round(22 * power * left)
                u = r.random((n, 4))
                self.add(np.repeat(np.atleast_2d(at), n, 0), swing * ((.08 + u[:, :1] * .2) * left) + np.c_[(u[:, 1] - .5) * .08, .05 + u[:, 2] * .12, (u[:, 3] - .5) * .08])
                self.pool(at + swing * (.4 + r.random() * .9), r.random() * .22 + .18, now, 24)
        self.spurts = [s for s in self.spurts if now - s[3] < 22]
        if not len(self.p): return
        self.v[:, 1] -= DRIP_FALL
        self.p += self.v
        self.v *= DRIP_DRAG
        floor = self.p[:, 1] < .02
        self.p[floor, 1] = .02
        self.v[floor, 1] = 0
        self.v[floor, 0] *= .7
        self.v[floor, 2] *= .7
        self.age += 1
        alive = self.age < self.life
        self.p, self.v, self.age, self.life, self.size = self.p[alive], self.v[alive], self.age[alive], self.life[alive], self.size[alive]


# ------------------------------------------------------------------ drawing

class Camera:
    def __init__(self, eye, target, width, height, fov=52):
        self.eye = np.array(eye, float)
        f = unit(np.array(target, float) - self.eye)
        r = unit(np.cross(f, UP))
        self.basis = np.array([r, np.cross(r, f), f])
        self.width, self.height = width, height
        self.focal = height / 2 / math.tan(math.radians(fov) / 2)

    def project(self, points):
        c = (np.atleast_2d(points) - self.eye) @ self.basis.T
        z = np.maximum(c[:, 2], 1e-3)
        return np.c_[self.width / 2 + self.focal * c[:, 0] / z, self.height / 2 - self.focal * c[:, 1] / z, c[:, 2]]


SUN = unit(np.array([.35, .85, -.4]))
SKY = np.array([.62, .74, .88])


def raster(camera, polygons, blood, pools, now):
    """Polygons (corners, colours per corner or one, lit) into a picture with a depth buffer; then the blood over it."""
    W, H = camera.width, camera.height
    image = np.empty((H, W, 3))
    image[:] = SKY * np.linspace(.85, 1.05, H)[:, None, None]
    depth = np.full((H, W), np.inf)
    for corners, colours, lit in polygons:
        corners = np.asarray(corners, float)
        if len(corners) < 3: continue
        normal = np.cross(corners[1] - corners[0], corners[2] - corners[0])
        for k in range(3, len(corners)):
            if np.linalg.norm(normal) > 1e-9: break
            normal = np.cross(corners[1] - corners[0], corners[k] - corners[0])
        shade = 1. if not lit else .52 + .48 * abs(unit(normal) @ SUN)
        colours = np.asarray(colours, float)
        if colours.ndim == 1: colours = np.repeat(colours[None], len(corners), 0)
        screen = camera.project(corners)
        if np.any(screen[:, 2] < .05): continue
        for k in range(1, len(corners) - 1):
            tri = screen[[0, k, k + 1]]
            col = colours[[0, k, k + 1]] * shade
            x0, x1 = max(0, int(tri[:, 0].min())), min(W, int(tri[:, 0].max()) + 2)
            y0, y1 = max(0, int(tri[:, 1].min())), min(H, int(tri[:, 1].max()) + 2)
            if x0 >= x1 or y0 >= y1: continue
            area = (tri[1, 0] - tri[0, 0]) * (tri[2, 1] - tri[0, 1]) - (tri[2, 0] - tri[0, 0]) * (tri[1, 1] - tri[0, 1])
            if abs(area) < 1e-9: continue
            X, Y = np.meshgrid(np.arange(x0, x1) + .5, np.arange(y0, y1) + .5)
            w0 = ((tri[1, 0] - X) * (tri[2, 1] - Y) - (tri[2, 0] - X) * (tri[1, 1] - Y)) / area
            w1 = ((tri[2, 0] - X) * (tri[0, 1] - Y) - (tri[0, 0] - X) * (tri[2, 1] - Y)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
            if not inside.any(): continue
            z = w0 * tri[0, 2] + w1 * tri[1, 2] + w2 * tri[2, 2]
            region = depth[y0:y1, x0:x1]
            nearer = inside & (z < region)
            region[nearer] = z[nearer]
            colour = w0[..., None] * col[0] + w1[..., None] * col[1] + w2[..., None] * col[2]
            image[y0:y1, x0:x1][nearer] = colour[nearer]
    # The blood: squares the size of its sprites, behind nothing nearer, fading as the particle does (HexParticles.Drip).
    # Nothing within a few blocks of the camera, which would only cover the picture.
    if blood is not None and len(blood.p):
        s = camera.project(blood.p)
        px = np.clip((blood.size * camera.focal / np.maximum(s[:, 2], .1)).astype(int), 1, max(2, H // 50))
        alpha = np.minimum(1, (blood.age + 1) / 2) * (1 - blood.age / blood.life * .8)
        red = np.array([.62, .09, .11])
        for (x, y, z), size, a in zip(s, px, alpha):
            xi, yi = int(x), int(y)
            if z < 2.5 or not (0 <= xi < W and 0 <= yi < H) or z > depth[yi, xi] + .08: continue
            spot = image[max(0, yi - size // 2):yi + (size + 1) // 2, max(0, xi - size // 2):xi + (size + 1) // 2]
            spot[:] = spot * (1 - a) + red * a
    return image, depth


def floor_polygons():
    polygons = []
    for x in range(-7, 7):
        for z in range(-9, 7):
            c = np.array([96, 132, 70]) / 255 if (x + z) % 2 == 0 else np.array([88, 122, 63]) / 255
            polygons.append(([np.array([x, 0, z]), np.array([x + 1, 0, z]), np.array([x + 1, 0, z + 1]), np.array([x, 0, z + 1])], c, True))
    return polygons


def pool_polygons(pools, now):
    out = []
    for i, (x, z, size, born, spread) in enumerate(pools):
        age = now - born
        if age < 0: continue
        s = size * (.55 + .45 * min(1, age / spread))
        y = .004 + (i % 7) * .0006
        ring = [np.array([x + math.cos(a) * s, y, z + math.sin(a) * s]) for a in np.linspace(0, math.tau, 9)[:-1]]
        out.append((ring, np.array([.36, .02, .03]), False))
    return out


# ------------------------------------------------------------------ the move, tick by tick

def simulate(kind, seed=7):
    """Every tick from a moment before G until the halves have lain a while: what to draw each frame."""
    rng = np.random.default_rng(seed)
    pyrng = random.Random(seed)
    spec = BODIES[kind]
    h, w = spec['height'], spec['width']
    moves = {n: load(n) for n in ('blade_sword_evis_dash', 'blade_sword_evis_thrust', 'blade_sword_evis_cut')}
    blood = Blood(rng)
    standing = body_faces(kind)
    gut = np.array([0, h * .55, 0])
    front, back = gut - FORWARD * w / 2, gut + FORWARD * w / 2
    z, back_step = -5.4, 0.
    press, thrust, impale, cut_start, cut = 6, None, None, None, None
    pieces = None
    states = []
    for now in range(0, 110):
        # The server's tick: the dash, the thrust, the impale, the cut.
        if now >= press and thrust is None:
            gap = -w / 2 - z
            if gap <= REACH or now - press >= DASH:
                thrust = now
            else:
                z += max(.25, min(SPEED, gap - REACH + .15))
        if thrust is not None and impale is None and now >= thrust + THRUST:
            impale = now
            blood.slash(front, -FORWARD + UP * .3, BLOOD, now)
            blood.slash(back, FORWARD + UP * .15, BLOOD, now)
            blood.slash(back, FORWARD - UP * .3, BLOOD, now)
        if impale is not None and cut_start is None and now - impale <= 9:
            # Blood.impale: welling round the blade, a gush as it goes in, a spurt on each beat and push.
            age = now - impale
            u = rng.random((12, 5))
            blood.add(front + np.c_[(u[:, 0] - .5) * .16, (u[:, 1] - .5) * .12, (u[:, 2] - .5) * .16],
                      np.c_[(u[:, 3] - .5) * .04, -.04 - u[:, 4] * .06, -.025 + (rng.random(12) - .5) * .04])
            if age <= 1 or age == 3 or age % 4 == 0:
                n = 90 if age <= 1 else 150 if age == 3 else 50
                u = rng.random((n, 3))
                aim = np.c_[(u[:, 0] - .5) * 1.6, np.zeros(n), -np.ones(n)]
                aim /= np.linalg.norm(aim, axis=1, keepdims=True)
                blood.add(np.repeat(front[None], n, 0), aim * (.08 + u[:, 1:2] * .3) + np.c_[np.zeros(n), .03 + u[:, 2] * .14, np.zeros(n)])
            blood.pool(np.array([(pyrng.random() - .5) * .5, 0, -.35 - pyrng.random() * .8]), .3 + pyrng.random() * .3, now)
        if impale is not None and cut_start is None and now >= impale + DOOMED_HOLD:
            cut_start = now
            back_step = -.32
            blood.slash(front, -FORWARD + UP * .2, BLOOD, now)
        if cut_start is not None and cut is None and now >= cut_start + CUT_HIT:
            cut = now
            pieces, point, normal, line = halve(kind, pyrng)
            blood.slash(gut, line, BLOOD, now)
            blood.slash(gut, unit(-line + UP * .6), BLOOD, now)
            blood.slash(gut, unit(normal + FORWARD * .5), BLOOD, now)
            blood.slash(gut - UP * h * .2, unit(FORWARD - UP * .2), BLOOD, now)
            for _ in range(80):
                along = (pyrng.random() - .5) * max(w, h * .8)
                at = np.array([0, h * GUT, 0]) + line * along + FORWARD * (pyrng.random() - .5) * w
                out = normal * (1 if pyrng.random() < .5 else -1) * (.05 + pyrng.random() * .12)
                blood.add(at, out + line * (.12 + pyrng.random() * .2) + UP * .05)
        if back_step:
            z += back_step
            back_step = back_step * .546 if abs(back_step) > .01 else 0.
        # The halves: Halving.tick.
        if pieces is not None and now > cut:
            age = now - cut

            def landed(piece, age=age):
                at = piece.place(piece.pivot)
                for _ in range(4):
                    blood.pool(np.array([at[0] + (pyrng.random() - .5) * h * .5, 0, at[2] + (pyrng.random() - .5) * h * .5]),
                               .3 + pyrng.random() * .4, now)
                u = rng.random((40, 3))
                blood.add(np.repeat(np.array([[at[0], .05, at[2]]]), 40, 0), np.c_[(u[:, 0] - .5) * .3, .05 + u[:, 1] * .15, (u[:, 2] - .5) * .3])

            for piece in pieces:
                piece.step(age, landed)
                if age <= 120:
                    face = piece.place(point)
                    out = axis_angle(piece.axis, piece.angle) @ (normal * -piece.side)
                    flow = 1 - age / 120
                    n = round(14 * flow * flow) + 1
                    u = rng.random((n, 5))
                    spots = face + np.c_[(u[:, 0] - .5) * h * .25, (u[:, 1] - .5) * .1, (u[:, 2] - .5) * h * .25]
                    blood.add(spots, np.c_[out[0] * (.04 + u[:, 3] * .1), np.full(n, out[1] * .06 - .02), out[2] * (.04 + u[:, 4] * .1)])
                    if age % 3 == 0:
                        blood.pool(face + np.array([(pyrng.random() - .5) * .6, 0, (pyrng.random() - .5) * .6]), .25 + pyrng.random() * .35 * flow, now)
        blood.step(now)
        # The bearer's move this tick.
        if cut_start is not None: move, local = 'blade_sword_evis_cut', now - cut_start
        elif thrust is not None: move, local = 'blade_sword_evis_thrust', now - thrust
        elif now >= press: move, local = 'blade_sword_evis_dash', now - press
        else: move, local = None, 0
        if move is not None and local > moves[move]['end']: move = None
        phase = ('stands' if now < press else 'dash' if thrust is None else 'thrust' if impale is None else
                 'impaled' if cut_start is None else 'cut' if cut is None else 'halved')
        states.append(dict(now=now, z=z, move=move, local=local, phase=phase, pieces=pieces and [
            (p.prev_pos.copy(), p.pos.copy(), p.prev_angle, p.angle) for p in pieces],
            blood=(blood.p.copy(), blood.v.copy(), blood.age.copy(), blood.life.copy(), blood.size.copy()), pools=list(blood.pools),
            cut=cut, look=math.degrees(math.atan2(1.62 - h * .55, max(.5, -z))) if move in ('blade_sword_evis_dash', 'blade_sword_evis_thrust') else 0,
            dashing=thrust is None and now >= press))
    return dict(kind=kind, states=states, standing=standing, halves=pieces, moves=moves)


def bearer_polygons(sim, i, partial):
    s0, s1 = sim['states'][i], sim['states'][min(i + 1, len(sim['states']) - 1)]
    z = s0['z'] + (s1['z'] - s0['z']) * partial
    frame = {}
    if s1['move'] is not None and s1['move'] == s0['move']:
        frame = rig.keyed(sim['moves'][s1['move']], s0['local'] + partial)
    elif s0['move'] is not None:
        frame = rig.keyed(sim['moves'][s0['move']], s0['local'] + partial)
    frame = {k: dict(v) for k, v in frame.items()}
    if s0['look']:
        # HexAnimations.FollowLook: the sword arm turned by the look's pitch.
        frame.setdefault('rightArm', {})
        frame['rightArm']['pitch'] = frame['rightArm'].get('pitch', 0) + s0['look']
    if s0['dashing']:
        swing = 50 * math.sin((s0['now'] + partial) * 1.7)
        frame.setdefault('rightLeg', {})['pitch'] = swing
        frame.setdefault('leftLeg', {})['pitch'] = -swing
    out = []
    for corners, colour in rig.world_faces(frame, 'deceiver'):
        out.append(([np.array(c) + np.array([0, 0, z]) for c in corners], np.array(colour) / 255, True))
    return out


def body_polygons(sim, i, partial):
    s = sim['states'][i]
    if s['pieces'] is None:
        return [(corners, colour, True) for corners, colour in sim['standing']]
    out = []
    age = s['now'] - s['cut'] + partial
    hot = heat(age)
    g = glow(hot)
    rim = np.minimum(1, np.array(CHAR) + g)
    middle = np.minimum(1, np.array(FLESH) + g * .55)
    for piece, (p0, p1, a0, a1) in zip(sim['halves'], s['pieces']):
        piece.prev_pos, piece.pos, piece.prev_angle, piece.angle = p0, p1, a0, a1
        for corners, colour in piece.faces:
            out.append(([piece.place(c, partial) for c in corners], colour, True))
        for loop in piece.caps:
            centre = np.mean(loop, axis=0)
            for k in range(len(loop)):
                out.append(([piece.place(centre, partial), piece.place(loop[k], partial), piece.place(loop[(k + 1) % len(loop)], partial)],
                            np.array([middle, rim, rim]), hot < .05))
    return out


def blood_at(sim, i, partial):
    p, v, age, life, size = sim['states'][i]['blood']

    class B: pass
    b = B()
    b.p = p + v * partial
    b.age, b.life, b.size = age, life, size
    return b


def frame_image(sim, i, partial, cameras, scale):
    s = sim['states'][i]
    polygons = floor_polygons() + pool_polygons(s['pools'], s['now'] + partial) + bearer_polygons(sim, i, partial) + body_polygons(sim, i, partial)
    blood = blood_at(sim, i, partial)
    panels = []
    for title, camera, particles in cameras:
        image, _ = raster(camera, polygons, blood if particles else None, s['pools'], s['now'])
        picture = Image.fromarray((np.clip(image, 0, 1) * 255).astype(np.uint8)).resize((camera.width // scale, camera.height // scale), Image.LANCZOS)
        d = ImageDraw.Draw(picture)
        d.text((8, 6), title, fill=(20, 24, 30))
        d.text((8, picture.height - 16), f"tick {s['now'] + partial:5.1f}   {s['phase']}", fill=(20, 24, 30))
        panels.append(picture)
    out = Image.new('RGB', (sum(p.width for p in panels), panels[0].height))
    x = 0
    for p in panels:
        out.paste(p, (x, 0))
        x += p.width
    return out


def render_body(kind, out, scale=2, width=640, height=400):
    sim = simulate(kind)
    W, H = width * scale, height * scale
    cameras = [('Behind the bearer', Camera((1.1, 2.5, -8.2), (-.1, 1.0, -.4), W, H), True),
               ('From the side, the blood in the air left out to show the halves', Camera((6.4, 2.1, -2.2), (-.3, .9, -.4), W, H), False)]
    frames = out / f'{kind}_frames'
    frames.mkdir(parents=True, exist_ok=True)
    from concurrent.futures import ProcessPoolExecutor
    jobs = [(i, half / 2) for i in range(len(sim['states']) - 1) for half in (0, 1)]
    with ProcessPoolExecutor() as pool:
        for n, image in enumerate(pool.map(_draw, [(kind, i, p, scale, width, height) for i, p in jobs], chunksize=4)):
            image.save(frames / f'{n:04d}.png')
    total = len(jobs)
    encode = ['ffmpeg', '-y', '-loglevel', 'error']
    subprocess.run(encode + ['-framerate', '40', '-i', str(frames / '%04d.png'), '-pix_fmt', 'yuv420p', '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', str(out / f'{kind}.mp4')], check=True)
    subprocess.run(encode + ['-framerate', '10', '-i', str(frames / '%04d.png'), '-pix_fmt', 'yuv420p', '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', str(out / f'{kind}_slow.mp4')], check=True)
    subprocess.run(encode + ['-framerate', '20', '-i', str(frames / '%04d.png'), '-vf',
                             'scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer', str(out / f'{kind}.gif')], check=True)
    # Key moments: the dash, the thrust going in, impaled, the wind-up, the cut, and the halves falling and lying.
    s = sim['states']
    first = {phase: next(st['now'] for st in s if st['phase'] == phase) for phase in ('dash', 'thrust', 'impaled', 'cut', 'halved')}
    picks = [first['dash'] + 1, first['thrust'] + 1, first['impaled'] + 1, first['cut'] + 3, first['halved'],
             first['halved'] + 4, first['halved'] + 10, first['halved'] + 40]
    tiles = [Image.open(frames / f'{min(total - 1, t * 2):04d}.png') for t in picks]
    sheet = Image.new('RGB', (tiles[0].width * 2, tiles[0].height * 4), (0, 0, 0))
    for k, tile in enumerate(tiles):
        sheet.paste(tile, ((k % 2) * tile.width, (k // 2) * tile.height))
    sheet.save(out / f'{kind}_sheet.png')
    print(f'{kind}: {total} frames; dash from tick {first["dash"]}, in on {first["impaled"]}, cut on {first["halved"]}; '
          f'upper half {len(sim["halves"][0].caps)} cut faces, lower {len(sim["halves"][1].caps)}')


_SIMS = {}


def _draw(job):
    kind, i, partial, scale, width, height = job
    if kind not in _SIMS: _SIMS[kind] = simulate(kind)
    sim = _SIMS[kind]
    W, H = width * scale, height * scale
    cameras = [('Behind the bearer', Camera((1.1, 2.5, -8.2), (-.1, 1.0, -.4), W, H), True),
               ('From the side, the blood in the air left out to show the halves', Camera((6.4, 2.1, -2.2), (-.3, .9, -.4), W, H), False)]
    return frame_image(sim, i, partial, cameras, scale)


if __name__ == '__main__':
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for kind in sys.argv[2:] or ('zombie', 'player'):
        render_body(kind, out)
