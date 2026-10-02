"""
The player model as Player Animator poses it, with the held blade, through the game's own transform chain: used by
tools/generate_blades.py to check every cut against its blood, and by tools/preview_blades.py to draw the moves.
Requires numpy and Pillow.


Conventions, from the sources (PlayerRendererMixin, AnimationApplier, HeldItemMixin, bendy-lib):
- model space: +x left? no: +x is the model's own +x; y down; -z forward. World (entity facing +z): world = Ry(180) . body . S(-1,-1,1) . S(.9375) . T(0,-1.501,0) . part
- body: T(x, y+.7, z) Rz(roll) Ry(yaw) Rx(pitch) T(0,-.7,0), in the entity frame after Ry(180), blocks
- parts: translate(pos/16) then Rz(roll) Ry(yaw) Rx(pitch), pixels
- limb bend: lower half rotated about axis (cos(-a), 0, sin(-a)) by bend, through the limb's middle
- torso bend: the upper half (and head and arms with it) about the torso's middle, same axis rule
- held item: arm transform, bend (at .25 down), Rx(-90) Ry(180) T(1/16,.125,-.625), rightItem T(pos/16) Rz Ry Rx,
  display transform, T(-.5,-.5,-.5), BEWLR T(g,g,.5) Rz(-45) S(s), mesh (blade +Y)
"""
import json, math
import numpy as np
from PIL import Image, ImageDraw

from pathlib import Path
ROOT = str(Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories') + '/'


def Rx(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1.]])
def Ry(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1.]])
def Rz(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1.]])
def T(x, y, z):
    m = np.eye(4); m[:3, 3] = [x, y, z]; return m
def S(x, y=None, z=None):
    m = np.eye(4); m[0, 0] = x; m[1, 1] = x if y is None else y; m[2, 2] = x if z is None else z; return m
def Raxis(deg, axis):
    a = math.radians(deg); x, y, z = axis; c, s = math.cos(a), math.sin(a); C = 1 - c
    return np.array([[c + x*x*C, x*y*C - z*s, x*z*C + y*s, 0], [y*x*C + z*s, c + y*y*C, y*z*C - x*s, 0],
                     [z*x*C - y*s, z*y*C + x*s, c + z*z*C, 0], [0, 0, 0, 1.]])
def bendm(bend, axisdeg, at):
    """Rotation of a limb's lower half (or torso's upper) about its middle, in part pixels."""
    a = -math.radians(axisdeg)
    ax = (math.cos(a), 0, math.sin(a))
    return T(0, at, 0) @ Raxis(bend, ax) @ T(0, -at, 0)

DEFAULT = {'rightArm': (-5, 2, 0), 'leftArm': (5, 2, 0), 'rightLeg': (-1.9, 12, .1), 'leftLeg': (1.9, 12, .1), 'head': (0, 0, 0), 'torso': (0, 0, 0)}

def part(frame, name):
    p = frame.get(name, {})
    x, y, z = DEFAULT[name]
    return (p.get('x', x), p.get('y', y), p.get('z', z), p.get('pitch', 0), p.get('yaw', 0), p.get('roll', 0), p.get('bend', 0), p.get('axis', 0))

def root(frame):
    b = frame.get('body', {})
    body = T(b.get('x', 0), b.get('y', 0) + .7, b.get('z', 0)) @ Rz(b.get('roll', 0)) @ Ry(b.get('yaw', 0)) @ Rx(b.get('pitch', 0)) @ T(0, -.7, 0)
    return Ry(180) @ body @ S(-1, -1, 1) @ S(.9375) @ T(0, -1.501, 0)

def partm(frame, name):
    x, y, z, p, yw, r, b, a = part(frame, name)
    return T(x / 16, y / 16, z / 16) @ Rz(r) @ Ry(yw) @ Rx(p)

def upper(frame):
    """The torso's bend, carried by the head and arms (pixels to blocks: the bend centre is 6px down)."""
    x, y, z, p, yw, r, b, a = part(frame, 'torso')
    tor = T(x / 16, y / 16, z / 16) @ Rz(r) @ Ry(yw) @ Rx(p)
    if b == 0: return np.eye(4), tor
    bm = T(0, 6 / 16, 0) @ Raxis(-b, ((math.cos(-math.radians(a))), 0, math.sin(-math.radians(a)))) @ T(0, -6 / 16, 0)
    return tor @ bm @ np.linalg.inv(tor), tor

DISPLAY = {k: json.load(open(ROOT + f'models/item/{k}.json'))['display']['thirdperson_righthand'] for k in ['dagger', 'deceiver']}
FIT = {'dagger': (.24, 1.0), 'deceiver': (.24, 1.0)}

def item_matrix(frame, model, hand='right'):
    M = root(frame)
    up, _ = upper(frame)
    arm = 'rightArm' if hand == 'right' else 'leftArm'
    M = M @ up @ partm(frame, arm)
    x, y, z, p, yw, r, b, a = part(frame, arm)
    if b:
        ax = -math.radians(a)
        M = M @ T(0, .25, 0) @ Raxis(b, (math.cos(ax), 0, math.sin(ax))) @ T(0, -.25, 0)
    M = M @ Rx(-90) @ Ry(180) @ T((1 if hand == 'right' else -1) / 16, .125, -.625)
    it = frame.get('rightItem' if hand == 'right' else 'leftItem', {})
    M = M @ T(it.get('x', 0) / 16, it.get('y', 0) / 16, it.get('z', 0) / 16) @ Rz(it.get('roll', 0)) @ Ry(it.get('yaw', 0)) @ Rx(it.get('pitch', 0))
    d = DISPLAY[model]
    M = M @ T(*[v / 16 for v in d['translation']]) @ Rx(d['rotation'][0]) @ Ry(d['rotation'][1]) @ Rz(d['rotation'][2]) @ S(d['scale'][0])
    g, s = FIT[model]
    M = M @ T(-.5, -.5, -.5) @ T(g, g, .5) @ Rz(-45) @ S(s)
    return M

def tip(frame, model, length):
    """World (x right? no: x is world x; the entity faces +z, its right is -x) point of the blade, as (right, up, forward)."""
    M = item_matrix(frame, model)
    p = (M @ np.array([0, length, 0, 1]))[:3]
    g = (M @ np.array([0, 0, 0, 1]))[:3]
    return np.array([-p[0], p[1], p[2]]), np.array([-g[0], g[1], g[2]])

# ------------------------------------------------------------------ drawing
BOXES = {
    'head': ((-4, -8, -4), (4, 0, 4), (196, 150, 120)),
    'torso': ((-4, 0, -2), (4, 12, 2), (40, 70, 52)),
    'rightArm': ((-3, -2, -2), (1, 10, 2), (196, 150, 120)),
    'leftArm': ((-1, -2, -2), (3, 10, 2), (176, 132, 104)),
    'rightLeg': ((-2, 0, -2), (2, 12, 2), (60, 62, 90)),
    'leftLeg': ((-2, 0, -2), (2, 12, 2), (50, 52, 78)),
}
MESH = {}
def mesh(model):
    if model not in MESH:
        v = []; f = []
        for line in open(ROOT + f'models/{model}.obj'):
            s = line.split()
            if s and s[0] == 'v': v.append([float(t) for t in s[1:4]] + [1])
            if s and s[0] == 'f': f.append([int(t.split('/')[0]) - 1 for t in s[1:]])
        MESH[model] = (np.array(v), f)
    return MESH[model]

def box_faces(lo, hi, M_top, M_bottom=None, split=None):
    """Quads of a box in world space; with a bend, the half below `split` uses M_bottom."""
    out = []
    xs, zs = (lo[0], hi[0]), (lo[2], hi[2])
    segs = [(lo[1], hi[1], M_top)] if M_bottom is None else [(lo[1], split, M_top), (split, hi[1], M_bottom)]
    for y0, y1, M in segs:
        c = lambda x, y, z: (M @ np.array([x / 16, y / 16, z / 16, 1]))[:3]
        P = {(i, j, k): c((xs[i]), (y0, y1)[j], (zs[k])) for i in (0, 1) for j in (0, 1) for k in (0, 1)}
        for q in [((0,0,0),(1,0,0),(1,1,0),(0,1,0)), ((0,0,1),(1,0,1),(1,1,1),(0,1,1)), ((0,0,0),(0,1,0),(0,1,1),(0,0,1)),
                  ((1,0,0),(1,1,0),(1,1,1),(1,0,1)), ((0,0,0),(1,0,0),(1,0,1),(0,0,1)), ((0,1,0),(1,1,0),(1,1,1),(0,1,1))]:
            out.append([P[k] for k in q])
    return out

def world_faces(frame, model=None):
    faces = []
    R = root(frame)
    up, tor = upper(frame)
    for name, (lo, hi, col) in BOXES.items():
        x, y, z, p, yw, r, b, a = part(frame, name)
        if name == 'torso':
            M = R @ tor
            if b:
                # upper half bends about the middle: draw the top half bent, bottom straight
                Mb = R @ up @ tor
                fs = box_faces(lo, hi, Mb, M, 6)
            else:
                fs = box_faces(lo, hi, M)
        else:
            base = R @ (up if name in ('head', 'rightArm', 'leftArm') else np.eye(4)) @ partm(frame, name)
            if name == 'head' or b == 0:
                fs = box_faces(lo, hi, base)
            else:
                at = 4 if 'Arm' in name else 6
                fs = box_faces(lo, hi, base, base @ bendm(b, a, at / 16), at)
        # The front of the head (the face) and of the torso are marked, so which way the body faces is never in doubt.
        marks = {'head': (240, 210, 90), 'torso': (90, 150, 110)}
        faces += [(f, marks[name] if name in marks and i % 6 == 0 else col) for i, f in enumerate(fs)]
    if model:
        M = item_matrix(frame, model)
        v, f = mesh(model)
        W = (M @ v.T).T[:, :3]
        for face in f:
            faces.append(([W[i] for i in face], (205, 210, 220)))
    return faces

def render(frame, model, cam_yaw=35, cam_pitch=12, size=220, scale=78):
    img = Image.new('RGB', (size, size), (34, 38, 44))
    d = ImageDraw.Draw(img)
    a, b = math.radians(cam_yaw), math.radians(cam_pitch)
    V = Rx(-cam_pitch)[:3, :3] @ Ry(-cam_yaw)[:3, :3]
    light = np.array([.3, .8, .5]); light /= np.linalg.norm(light)
    polys = []
    for pts, col in world_faces(frame, model):
        P = [V @ (np.array(p) - np.array([0, .95, 0])) for p in pts]
        n = np.cross(P[1] - P[0], P[2] - P[0]); ln = np.linalg.norm(n)
        if ln < 1e-12: continue
        n /= ln
        sh = .45 + .55 * abs(n @ (V @ light))
        depth = sum(p[2] for p in P) / len(P)
        polys.append((depth, [(size / 2 - p[0] * scale, size / 2 - p[1] * scale) for p in P], tuple(int(c * sh) for c in col)))
    for depth, pts, col in sorted(polys, key=lambda t: -t[0]):
        d.polygon(pts, fill=col)
    d.line([(0, size / 2 + .95 * scale * math.cos(b)), (size, size / 2 + .95 * scale * math.cos(b))], fill=(70, 74, 80))
    return img

def interp(frames, t):
    """A frame at tick t, linear between keyframes (enough to judge a move)."""
    ks = sorted(frames)
    if t <= ks[0]: return frames[ks[0]]
    for k0, k1 in zip(ks, ks[1:]):
        if k0 <= t <= k1:
            u = (t - k0) / (k1 - k0)
            u = u * u * (3 - 2 * u)
            out = {}
            for name in set(frames[k0]) | set(frames[k1]):
                A, B = frames[k0].get(name, {}), frames[k1].get(name, {})
                out[name] = {key: A.get(key, 0) + (B.get(key, 0) - A.get(key, 0)) * u for key in set(A) | set(B)}
            return out
    return frames[ks[-1]]

def sheet(name, frames, model, ticks, out, views=((35, 12), (-60, 10)), size=200):
    tiles = []
    for t in ticks:
        f = interp(frames, t)
        col = Image.new('RGB', (size, size * len(views) + 14), (20, 22, 26))
        for i, (yw, pt) in enumerate(views):
            col.paste(render(f, model, yw, pt, size), (0, i * size))
        ImageDraw.Draw(col).text((4, size * len(views) + 1), f'{name} t{t}', fill=(220, 220, 220))
        tiles.append(col)
    W = Image.new('RGB', (size * len(tiles), tiles[0].height), (0, 0, 0))
    for i, tl in enumerate(tiles): W.paste(tl, (i * size, 0))
    W.save(out)


def fist(frame, model):
    """The middle of the right fist, in the blade mesh's own coordinates (the grip is centred on its y)."""
    R = root(frame); up, tor = upper(frame)
    A = R @ up @ partm(frame, 'rightArm')
    x, y, z, p, yw, r, b, a = part(frame, 'rightArm')
    lower = A @ bendm(b, a, 4 / 16) if b else A
    return (np.linalg.inv(item_matrix(frame, model)) @ (lower @ np.array([-1 / 16, 8.5 / 16, 0, 1])))[:3]


EASES = {'inquad': lambda u: u * u, 'outquad': lambda u: 1 - (1 - u) ** 2, 'inexpo': lambda u: 0 if u == 0 else 2 ** (10 * u - 10),
         'outback': lambda u: 1 + 2.70158 * (u - 1) ** 3 + 1.70158 * (u - 1) ** 2, 'linear': lambda u: u,
         'inoutsine': lambda u: -(math.cos(math.pi * u) - 1) / 2}


def keyed(move, t):
    """
    A move's pose at tick t, as Player Animator plays it: every part's every axis keyed on its own (a key may carry
    only some parts), each eased on the way into its next key.
    """
    tracks = {}
    for tick, ease, pose in move['keys']:
        for part, values in pose.items():
            for axis, v in values.items():
                tracks.setdefault((part, axis), []).append((tick, ease, v))
    out = {}
    for (part, axis), keys in tracks.items():
        v = keys[-1][2]
        if t <= keys[0][0]: v = keys[0][2]
        for (t0, _, a), (t1, ease, b) in zip(keys, keys[1:]):
            if t0 <= t <= t1:
                u = (t - t0) / (t1 - t0) if t1 > t0 else 1
                v = a + (b - a) * EASES.get(ease, lambda x: x * x * (3 - 2 * x))(u)
                break
        out.setdefault(part, {})[axis] = v
    return out


# ------------------------------------------------------------------ solving the arm and wrist for a grip and a point

def _nelder(f, x0, step, iters=600):
    n = len(x0)
    pts = [np.array(x0, float)] + [np.array(x0, float) + np.eye(n)[i] * step[i] for i in range(n)]
    vals = [f(p) for p in pts]
    for _ in range(iters):
        order = np.argsort(vals); pts = [pts[i] for i in order]; vals = [vals[i] for i in order]
        c = np.mean(pts[:-1], axis=0)
        r = c + (c - pts[-1]); fr = f(r)
        if fr < vals[0]:
            e = c + 2 * (c - pts[-1]); fe = f(e)
            pts[-1], vals[-1] = (e, fe) if fe < fr else (r, fr)
        elif fr < vals[-2]:
            pts[-1], vals[-1] = r, fr
        else:
            k = c + .5 * (pts[-1] - c); fk = f(k)
            if fk < vals[-1]: pts[-1], vals[-1] = k, fk
            else:
                pts = [pts[0]] + [pts[0] + .5 * (p - pts[0]) for p in pts[1:]]
                vals = [vals[0]] + [f(p) for p in pts[1:]]
    i = int(np.argmin(vals)); return pts[i], vals[i]


LIMITS = {'pitch': (-200, 60), 'yaw': (-100, 100), 'roll': (-70, 70), 'bend': (-125, 0), 'ipitch': (-200, 200), 'iroll': (-120, 120)}


def solve_right(frame, model, length, grip, direction, guess=(-70, 0, 0, -30, 0, 0)):
    """
    The right arm (pitch, yaw, roll, bend) and the wrist (the held item's pitch and roll) that put the grip at `grip`
    and point the blade along `direction`, both in the caster's (right, up, forward) with up from the feet. Kept near
    `guess` and inside a body's limits. @return the pose with them, and the miss in blocks.
    """
    grip = np.array(grip, float); d = np.array(direction, float); d /= np.linalg.norm(d)
    t0, g0 = tip(frame, model, length)
    want = grip + d * float(np.linalg.norm(t0 - g0))  # the blade's length as drawn (display scale and all)
    keys = ('pitch', 'yaw', 'roll', 'bend', 'ipitch', 'iroll')

    def build(x):
        f = {k: dict(v) for k, v in frame.items()}
        f['rightArm'] = dict(f.get('rightArm', {}), pitch=x[0], yaw=x[1], roll=x[2], bend=x[3])
        # Turned about the fist, as it will be drawn (pivot_on_fist): the handle never leaves the hand to reach a target.
        f['rightItem'] = compensate({'pitch': x[4], 'roll': x[5], 'yaw': 0})
        return f

    def cost(x):
        pen = 0
        for v, k in zip(x, keys):
            lo, hi = LIMITS[k]
            pen += max(0, lo - v) ** 2 + max(0, v - hi) ** 2
        t, g = tip(build(x), model, length)
        return np.linalg.norm(g - grip) * 3 + np.linalg.norm(t - want) + 1e-4 * np.sum((x - np.array(guess)) ** 2) + pen * .01

    best = None
    g = np.array(guess, float)
    starts = [g] + [g + np.array(o, float) for o in [(30, 0, 0, 0, -60, 0), (-30, 0, 0, -30, 60, 0), (0, 30, 0, 0, 0, 70),
                                                         (0, -30, 0, 0, 0, -70), (0, 0, 0, -40, -90, 0), (0, 0, 0, 0, 0, 120), (0, 0, 0, 0, 0, -120)]]
    for start in starts:
        x, v = _nelder(cost, start, (25, 25, 15, 25, 40, 40))
        x, v = _nelder(cost, x, (6, 6, 4, 6, 10, 10))
        if best is None or v < best[1]: best = (x, v)
    x = best[0]
    t, g = tip(build(x), model, length)
    rounded = [round(float(v), 1) for v in x]
    return build(rounded), float(np.linalg.norm(g - grip) + np.linalg.norm(t - want))


def hand(frame, side='left'):
    """A hand's middle in the caster's (right, up, forward)."""
    R = root(frame); up, tor = upper(frame)
    arm = 'leftArm' if side == 'left' else 'rightArm'
    A = R @ up @ partm(frame, arm)
    x, y, z, p, yw, r, b, a = part(frame, arm)
    lower = A @ bendm(b, a, 4 / 16) if b else A
    w = lower @ np.array([(1 if side == 'left' else -1) / 16, 8.5 / 16, 0, 1])
    return np.array([-w[0], w[1], w[2]])


def intrusion(frame):
    """How far (in pixels) either forearm's middle line gets into the chest or the head: 0 when clear of both."""
    worst = 0
    up, _ = upper(frame)
    for side, sx in (('rightArm', -1), ('leftArm', 1)):
        A = up @ partm(frame, side)
        x, y, z, p, yw, r, b, a = part(frame, side)
        lower = A @ bendm(b, a, 4 / 16) if b else A
        for yy in np.linspace(4, 10, 7):
            px, py, pz = ((lower if yy > 4 else A) @ np.array([sx / 16, yy / 16, 0, 1]))[:3] * 16
            chest = min(4 - abs(px), py, 12 - py, 2 - abs(pz))
            head = min(4 - abs(px), py + 8, -py, 4 - abs(pz))
            worst = max(worst, chest, head)
    return worst


def solve_left(frame, target, guess=(-60, 40, 0, -40)):
    """The left arm that puts the left hand at `target` (for both hands on a long grip). @return pose, miss."""
    target = np.array(target, float)

    def build(x):
        f = {k: dict(v) for k, v in frame.items()}
        f['leftArm'] = dict(f.get('leftArm', {}), pitch=x[0], yaw=x[1], roll=x[2], bend=x[3])
        return f

    def cost(x):
        pen = sum(max(0, lo - v) ** 2 + max(0, v - hi) ** 2 for v, (lo, hi) in zip(x, (LIMITS['pitch'], LIMITS['yaw'], LIMITS['roll'], LIMITS['bend'])))
        return np.linalg.norm(hand(build(x)) - target) + 1e-4 * np.sum((x - np.array(guess)) ** 2) + pen * .01

    best = None
    for start in [guess, (guess[0] + 40, guess[1], guess[2], guess[3]), (guess[0] - 40, guess[1] - 30, guess[2], guess[3] - 30)]:
        x, v = _nelder(cost, start, (25, 25, 15, 25))
        x, v = _nelder(cost, x, (6, 6, 4, 6))
        if best is None or v < best[1]: best = (x, v)
    f = build([round(float(v), 1) for v in best[0]])
    return f, float(np.linalg.norm(hand(f) - target))


# ------------------------------------------------------------------ turning the moves' aims into arms and wrists

def to_world(body, v, point=True):
    """A body-frame (right, up, forward) point or direction, in the caster's frame: the body's turn and offset applied."""
    th = math.radians(body.get('yaw', 0))
    r, u, f = v
    R = r * math.cos(th) - f * math.sin(th)
    F = r * math.sin(th) + f * math.cos(th)
    if point: return (R + body.get('x', 0), u, F - body.get('z', 0))
    return (R, u, F)


def resolve_move(item):
    """One move's keys with every aim solved, in order (each solve starting from the last): (name, keys, worst miss)."""
    name, move, canon = item if len(item) == 3 else (*item, frozenset())
    model = 'deceiver' if any(k in name for k in ('sword', 'guard', 'run', 'deflect')) else 'dagger'
    length = 1.55 if model == 'deceiver' else .80
    DEFAULT_RIGHT = right = (-55, 6, 0, -60, 0, 0); left = (-60, 40, 0, -40)
    out, worst = [], 0
    # A pose the move comes back to is drawn the way it was the first time: the arm has more than one way to put the
    # grip in one place, and a return by another (the same guard, the elbow somewhere else) jumps where the stance or
    # the next move takes over from it.
    seen = {}
    for tick, ease, pose in move['keys']:
        known = seen.get(repr(pose))
        if known is not None:
            out.append((tick, ease, {k: dict(v) for k, v in known.items()}))
            if 'rightArm' in known and 'rightItem' in known:
                a, it = known['rightArm'], known['rightItem']
                right = (a['pitch'], a['yaw'], a['roll'], a['bend'], it['pitch'], it['roll'])
            continue
        same = repr(pose)
        pose = {k: dict(v) for k, v in pose.items()}
        aim = pose.pop('aim', None)
        if aim:
            body = pose.get('body', {})
            grip = to_world(body, aim['grip']); d = to_world(body, aim['dir'], False)
            start = DEFAULT_RIGHT if same in canon else right
            if aim.get('hint'): start = tuple(aim['hint']) + start[4:]
            if aim.get('rev') and abs(((start[4] + 180) % 360) - 180) < 90:
                start = (start[0], start[1], start[2], start[3], 180, start[5])
            pose, miss = solve_right(pose, model, length, grip, d, start)
            worst = max(worst, miss)
            a, it = pose['rightArm'], pose['rightItem']
            right = (a['pitch'], a['yaw'], a['roll'], a['bend'], it['pitch'], it['roll'])
            if aim['two'] or aim.get('lhand'):
                dn = np.array(d) / np.linalg.norm(d)
                target = np.array(to_world(body, aim['lhand'])) if aim.get('lhand') else np.array(grip) - dn * .17
                pose, lmiss = solve_left(pose, target, left)
                worst = max(worst, lmiss)
                l = pose['leftArm']; left = (l['pitch'], l['yaw'], l['roll'], l['bend'])
            if aim['spin']:
                pose['rightItem']['pitch'] = round(pose['rightItem']['pitch'] + 360 * aim['spin'], 1)
        seen[same] = pose
        out.append((tick, ease, pose))
    return name, {**move, 'keys': pivot_on_fist(out)}, worst


def item_rotation(it):
    return Rz(it.get('roll', 0)) @ Ry(it.get('yaw', 0)) @ Rx(it.get('pitch', 0))


def _fist_in_item_frame():
    """The fist's middle in the frame Player Animator turns the held item about (fixed to the forearm, whatever the pose)."""
    f = {}
    A = root(f) @ partm(f, 'rightArm')
    F = A @ Rx(-90) @ Ry(180) @ T(1 / 16, .125, -.625)
    return (np.linalg.inv(F) @ (A @ np.array([-1 / 16, 8.5 / 16, 0, 1])))[:3]


FIST = _fist_in_item_frame()
#: Most the wrist may turn between two keys before an extra key is put between them (each sub-step's chord
#: then stays within a hair of the circle the grip should follow).
STEP = 45


def compensate(it):
    """The held item's offset that makes its turn pivot on the fist rather than on Player Animator's point (pixels)."""
    R = item_rotation(it)[:3, :3]
    d = (FIST - R @ FIST) * 16
    return {**it, 'x': round(float(d[0]), 3), 'y': round(float(d[1]), 3), 'z': round(float(d[2]), 3)}


def pivot_on_fist(keys):
    """
    Every key's held item turned about the fist, and an extra item-only key wherever the wrist turns further than STEP
    between two keys, so the handle stays in the hand all the way through a flip or a twirl.
    """
    out = []
    for i, (tick, ease, pose) in enumerate(keys):
        if i:
            t0, _, prev = keys[i - 1]
            a, b = prev.get('rightItem'), pose.get('rightItem')
            if a and b:
                turn = max(abs(b.get(k, 0) - a.get(k, 0)) for k in ('pitch', 'yaw', 'roll'))
                f = EASES.get(ease, lambda x: x * x * (3 - 2 * x))
                # A big turn gets a key on every tick between (eased as the move eases), each pivoted on the fist.
                for tm in (range(t0 + 1, tick) if turn > STEP else ()):
                    if any(k[0] == tm for k in out): continue
                    u = f((tm - t0) / (tick - t0))
                    mid = {k: a.get(k, 0) + (b.get(k, 0) - a.get(k, 0)) * u for k in ('pitch', 'yaw', 'roll')}
                    out.append((tm, 'linear', {'rightItem': compensate(mid)}))
        pose = {k: dict(v) for k, v in pose.items()}
        if 'rightItem' in pose:
            pose['rightItem'] = compensate(pose['rightItem'])
        out.append((tick, ease, pose))
    return sorted(out, key=lambda k: k[0])


def resolve(moves, report=None):
    """Every move solved, in parallel. @return the moves with plain arms and wrists in place of aims."""
    from concurrent.futures import ProcessPoolExecutor
    # The poses moves start from (the ready stances, the rest, the guard) are solved the same way wherever they come,
    # from the arm's default rather than from whatever key came before: so one move ends in the very arm the next
    # starts in, and the fade between them has nothing to swing.
    canon = frozenset(repr(m['keys'][0][2]) for m in moves.values())
    with ProcessPoolExecutor() as pool:
        done = list(pool.map(resolve_move, [(name, move, canon) for name, move in moves.items()]))
    if report:
        for name, _, worst in done: report(name, worst)
    return {name: move for name, move, _ in done}
