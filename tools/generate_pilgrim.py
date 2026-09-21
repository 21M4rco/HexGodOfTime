"""Reproducible Abyssal Pilgrim assets: geometry, animations, textures and original audio.

Run with: python3 tools/generate_pilgrim.py
Requires Pillow. ffmpeg is located via imageio-ffmpeg if it is not on PATH.
Every byte this writes is synthesised here; nothing is copied from another mod.
"""
from pathlib import Path
import json, math, random, struct, subprocess, shutil, wave, os

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories'
for d in ['geo', 'animations', 'textures/entity', 'sounds']:
    (ROOT / d).mkdir(parents=True, exist_ok=True)

RNG = random.Random(0x5EA9A9)

# ----------------------------------------------------------------------- shared layout
U = 16                       # model units per block
SPACING = 96                 # 6 blocks between joints; must equal LeviathanSegmentController.SPACING
SEGMENTS = 22
NECK_START, BODY_START, TAIL_START = 1, 4, 16
PROFILE = [5.0, 4.3, 4.7, 5.1, 5.4, 5.6, 5.6, 5.4, 5.1, 4.8, 4.4,
           4.0, 3.6, 3.2, 2.8, 2.4, 1.9, 1.5, 1.15, 0.85, 0.6, 0.4]

def spine_name(i):
    if i == 0: return 'head'
    if i < BODY_START: return 'neck_%d' % (i - NECK_START)
    if i < TAIL_START: return 'body_%d' % (i - BODY_START)
    return 'tail_%d' % (i - TAIL_START)

def radius(i): return PROFILE[i] * U
def zpos(i): return SPACING * i

# texture tiles, 64x64 each in a 256x256 sheet
TILE = {'armor': (0, 0), 'bone': (64, 0), 'glow': (128, 0), 'under': (192, 0),
        'tendril': (0, 64), 'spine': (64, 64), 'maw': (128, 64), 'rib': (192, 64)}
FACES = ['north', 'east', 'south', 'west', 'up', 'down']

def cube(origin, size, tile, down=None, inflate=0.0):
    """One box with per-face UVs. Per-face mapping keeps a 150 block creature on one 256px sheet."""
    uv = {}
    for f in FACES:
        t = down if (f == 'down' and down) else tile
        x, y = TILE[t]
        uv[f] = {'uv': [x, y], 'uv_size': [64, 64]}
    c = {'origin': [round(v, 2) for v in origin], 'size': [round(v, 2) for v in size], 'uv': uv}
    if inflate: c['inflate'] = inflate
    return c

BONES = []
def bone(name, parent, pivot, cubes=None, rotation=None):
    b = {'name': name, 'pivot': [round(v, 2) for v in pivot]}
    if parent: b['parent'] = parent
    if rotation: b['rotation'] = [round(v, 2) for v in rotation]
    if cubes: b['cubes'] = cubes
    BONES.append(b)
    return b

# ----------------------------------------------------------------------- geometry
def build_geometry():
    BONES.clear()
    bone('root', None, [0, 0, 0])

    # --- skull: elongated, extraterrestrial, no normal eye sockets
    r0 = radius(0)
    head_cubes = [
        cube([-r0 * 0.96, -r0 * 0.66, -150], [r0 * 1.92, r0 * 1.32, 206], 'armor', down='under'),
        cube([-r0 * 0.74, r0 * 0.52, -128], [r0 * 1.48, 24, 168], 'spine'),
        cube([-r0 * 0.58, -r0 * 0.46, -238], [r0 * 1.16, r0 * 0.94, 92], 'armor', down='under'),
        cube([-r0 * 0.30, r0 * 0.60, -196], [r0 * 0.60, 18, 74], 'spine'),
    ]
    bone('head', 'root', [0, 0, 0], head_cubes)

    # --- multi part jaw. Upper hinges, lower drops, and the front splits outward.
    upper = [cube([-r0 * 0.62, -30, -344], [r0 * 1.24, 34, 200], 'armor')]
    for t in range(7):
        x = -r0 * 0.52 + t * (r0 * 1.04 / 6)
        upper.append(cube([x - 5, -46, -330 + RNG.randint(-8, 8)], [10, 20 + RNG.randint(0, 16), 14], 'bone'))
    bone('upper_jaw', 'head', [0, -12, -150], upper)

    lower = [cube([-r0 * 0.56, -34, -334], [r0 * 1.12, 32, 192], 'armor', down='under'),
             cube([-r0 * 0.44, -18, -318], [r0 * 0.88, 14, 160], 'maw')]
    for t in range(7):
        x = -r0 * 0.46 + t * (r0 * 0.92 / 6)
        lower.append(cube([x - 5, -6, -322 + RNG.randint(-8, 8)], [10, 18 + RNG.randint(0, 18), 14], 'bone'))
    bone('lower_jaw', 'head', [0, -44, -150], lower)

    for side, sign in (('left', -1), ('right', 1)):
        mand = [cube([sign * r0 * 0.48 - (24 if sign < 0 else 0), -34, -412], [24, 30, 140], 'armor')]
        for t in range(4):
            mand.append(cube([sign * r0 * 0.48 - (14 if sign < 0 else -4), -14, -400 + t * 32], [12, 22, 12], 'bone'))
        bone('jaw_split_%s' % side, 'upper_jaw', [sign * r0 * 0.48, -20, -318], mand)

    # --- sensory organs replace eyes: clustered emissive nodes down both temples
    sensory = []
    for s in (-1, 1):
        for k in range(3):
            sensory.append(cube([s * (r0 * 0.60) - (10 if s < 0 else 0), 6 + k * 13, -176 + k * 26], [10, 11, 13], 'glow'))
    bone('sensory_organs', 'head', [0, 24, -150], sensory)
    bone('glow_organs_head', 'head', [0, 18, -90],
         [cube([-r0 * 0.30, r0 * 0.34, -110], [r0 * 0.60, 10, 120], 'glow'),
          cube([-r0 * 0.86, -8, -70], [12, 12, 90], 'glow'),
          cube([r0 * 0.74, -8, -70], [12, 12, 90], 'glow')])

    # --- head tendrils
    bone('head_tendrils', 'head', [0, -24, -300])
    for t in range(6):
        ang = (t / 5.0 - 0.5) * 2.1
        px, py = math.sin(ang) * r0 * 0.7, -20 - math.cos(ang) * 22
        bone('head_tendril_%d' % t, 'head_tendrils', [px, py, -300],
             [cube([px - 6, py - 6, -300], [12, 12, 104], 'tendril'),
              cube([px - 4, py - 4, -344], [8, 8, 54], 'tendril')])

    # --- spine chain. Each joint parents the next, so only relative angles are ever written.
    for i in range(1, SEGMENTS):
        r = radius(i)
        z = zpos(i)
        parent = spine_name(i - 1)
        cubes = [cube([-r, -r * 0.85, z - 56], [r * 2, r * 1.7, 112], 'armor', down='under')]
        if i < TAIL_START:
            cubes.append(cube([-r * 0.24, r * 0.80, z - 44], [r * 0.48, 16, 88], 'spine'))
        bone(spine_name(i), parent, [0, 0, z], cubes)

    # --- emissive strips, one group per joint, hidden individually by the renderer
    for i in range(SEGMENTS):
        r = radius(i)
        z = zpos(i)
        if i == 0:
            continue
        bone('glow_organs_%d' % i, spine_name(i), [0, 0, z],
             [cube([-r - 3, -r * 0.16, z - 40], [7, 9, 80], 'glow'),
              cube([r - 4, -r * 0.16, z - 40], [7, 9, 80], 'glow'),
              cube([-r * 0.20, r * 0.66, z - 30], [r * 0.40, 8, 60], 'glow')])
    bone('glow_organs_0', 'head', [0, 0, 0],
         [cube([-radius(0) - 3, -10, -60], [7, 9, 110], 'glow'),
          cube([radius(0) - 4, -10, -60], [7, 9, 110], 'glow')])

    # --- skeletal dorsal structures and rib like appendages along the body
    body_count = TAIL_START - BODY_START
    for b in range(body_count):
        i = BODY_START + b
        r = radius(i)
        z = zpos(i)
        taper = 1.0 - b / (body_count + 2.0)
        bone('dorsal_fin_%d' % b, spine_name(i), [0, r * 0.82, z],
             [cube([-7, r * 0.82, z - 34], [14, 92 * taper, 66], 'spine'),
              cube([-5, r * 0.82 + 72 * taper, z - 10], [10, 54 * taper, 20], 'bone')])
        for side, sign in (('l', -1), ('r', 1)):
            base = sign * r * 0.88
            bone('rib_appendage_%s_%d' % (side, b), spine_name(i), [base, -r * 0.18, z],
                 [cube([base - (108 * taper if sign < 0 else 0), -r * 0.18 - 8, z - 9], [108 * taper, 16, 18], 'rib'),
                  cube([base - (188 * taper if sign < 0 else -108 * taper), -r * 0.18 - 6, z - 7], [80 * taper, 12, 14], 'rib')])

    # --- thin whip tail ending in tendrils
    tail_end = zpos(SEGMENTS - 1)
    bone('tail_tendrils', spine_name(SEGMENTS - 1), [0, 0, tail_end + 50])
    for t in range(4):
        px = (t - 1.5) * 9
        bone('tail_tendril_%d' % t, 'tail_tendrils', [px, 0, tail_end + 50],
             [cube([px - 3, -3, tail_end + 44], [6, 6, 60], 'tendril'),
              cube([px - 2, -2, tail_end + 92], [4, 4, 34], 'tendril')])

    geo = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.abyssal_pilgrim', 'texture_width': 256, 'texture_height': 256,
                        'visible_bounds_width': 260, 'visible_bounds_height': 120, 'visible_bounds_offset': [0, 0, 74]},
        'bones': list(BONES)}]}
    (ROOT / 'geo/abyssal_pilgrim.geo.json').write_text(json.dumps(geo, indent=1) + '\n')
    cubes = sum(len(b.get('cubes', [])) for b in BONES)
    return len(BONES), cubes

# ----------------------------------------------------------------------- animations
def keys(pairs):
    return {('%.4f' % t): [round(v, 3) for v in vals] for t, vals in pairs}

def swell(length, amp, cycles, phase_step, bones, axis=1, bias=None, ramp=False, steps=8):
    """A travelling wave down a bone list: each joint lags the one in front of it."""
    out = {}
    for n, name in enumerate(bones):
        pairs = []
        for k in range(steps + 1):
            t = length * k / steps
            grow = (k / steps) if ramp else 1.0
            a = amp * math.sin(2 * math.pi * cycles * k / steps - n * phase_step) * grow
            v = [0.0, 0.0, 0.0]
            v[axis] = a
            if bias:
                for i in range(3): v[i] += bias[i]
            pairs.append((t, v))
        out[name] = {'rotation': keys(pairs)}
    return out

SPINE_BONES = [spine_name(i) for i in range(1, SEGMENTS)]
TAIL_BONES = [spine_name(i) for i in range(TAIL_START, SEGMENTS)]
FIN_BONES = ['dorsal_fin_%d' % b for b in range(TAIL_START - BODY_START)]

def jaw(length, opening, hold=0.5):
    """Upper lifts, lower drops, front mandibles split outward."""
    return {
        'upper_jaw': {'rotation': keys([(0, [0, 0, 0]), (length * hold, [-opening * 26, 0, 0]), (length, [0, 0, 0])])},
        'lower_jaw': {'rotation': keys([(0, [0, 0, 0]), (length * hold, [opening * 58, 0, 0]), (length, [0, 0, 0])])},
        'jaw_split_left': {'rotation': keys([(0, [0, 0, 0]), (length * hold, [0, opening * 32, opening * 15]), (length, [0, 0, 0])])},
        'jaw_split_right': {'rotation': keys([(0, [0, 0, 0]), (length * hold, [0, -opening * 32, -opening * 15]), (length, [0, 0, 0])])},
    }

def fins(length, angle, hold=0.5):
    return {name: {'rotation': keys([(0, [0, 0, 0]), (length * hold, [angle, 0, 0]), (length, [0, 0, 0])])} for name in FIN_BONES}

def merge(*parts):
    out = {}
    for p in parts:
        for k, v in p.items():
            if k in out: out[k].setdefault('rotation', {}).update(v.get('rotation', {}))
            else: out[k] = json.loads(json.dumps(v))
    return out

def build_animations():
    A = {}
    def add(name, length, loop, bones):
        A[name] = {'loop': bool(loop), 'animation_length': round(length, 3), 'bones': bones}

    # Locomotion: the spine carries a travelling wave, everything else is procedural.
    add('swim', 4.0, True, merge(swell(4.0, 3.4, 1, 0.55, SPINE_BONES), fins(4.0, 4, 0.5)))
    add('fast_swim', 1.8, True, merge(swell(1.8, 6.2, 1, 0.62, SPINE_BONES), fins(1.8, 9, 0.5)))
    add('deep_dive', 3.0, True, merge(swell(3.0, 2.8, 1, 0.48, SPINE_BONES), fins(3.0, -12, 0.5)))
    add('vertical_ascent', 2.4, True, merge(swell(2.4, 2.1, 1, 0.40, SPINE_BONES), fins(2.4, -20, 0.5)))
    add('breach', 3.0, False, merge(swell(3.0, 5.0, 1, 0.70, SPINE_BONES, ramp=True), jaw(3.0, 0.85, 0.35), fins(3.0, -26, 0.35)))
    add('airborne', 2.0, True, merge(swell(2.0, 1.4, 1, 0.30, SPINE_BONES), fins(2.0, -30, 0.5)))
    add('water_impact', 1.2, False, merge(swell(1.2, 11.0, 2, 1.05, SPINE_BONES), fins(1.2, 22, 0.25)))
    add('circle', 3.2, True, merge(swell(3.2, 4.0, 1, 0.58, SPINE_BONES, bias=[0, 2.4, 0]), fins(3.2, 6, 0.5)))
    add('stalk', 6.0, True, merge(swell(6.0, 1.5, 1, 0.34, SPINE_BONES), fins(6.0, 2, 0.5)))
    add('observe', 8.0, True, merge(swell(8.0, 0.9, 1, 0.22, SPINE_BONES)))

    # Attacks.
    add('fake_lunge', 1.3, False, merge(swell(1.3, 4.5, 1, 0.9, SPINE_BONES, ramp=True), jaw(1.3, 0.75, 0.55), fins(1.3, -18, 0.4)))
    add('bite', 1.5, False, merge(jaw(1.5, 1.0, 0.42), swell(1.5, 6.5, 1, 0.85, SPINE_BONES[:6])))
    add('grab', 1.6, False, merge(jaw(1.6, 0.55, 0.45), swell(1.6, 4.0, 1, 0.7, SPINE_BONES[:8])))
    add('drag', 2.0, True, merge(jaw(2.0, 0.45, 0.5), swell(2.0, 3.0, 1, 0.5, SPINE_BONES)))
    add('throw', 1.2, False, merge(jaw(1.2, 0.9, 0.3), swell(1.2, 9.0, 1, 1.0, SPINE_BONES[:10])))
    add('tail_sweep', 1.6, False, merge(swell(1.6, 22.0, 1, 0.9, TAIL_BONES, ramp=True), swell(1.6, 5.0, 1, 0.6, SPINE_BONES[:10])))
    add('body_crush', 4.0, True, merge(swell(4.0, 9.0, 1, 0.42, SPINE_BONES, bias=[0, 6.0, 0])))
    add('void_scream', 2.0, False, merge(jaw(2.0, 1.0, 0.55), fins(2.0, -42, 0.55), swell(2.0, 2.0, 1, 0.3, SPINE_BONES)))
    add('vortex', 3.0, True, merge(swell(3.0, 7.0, 1, 0.5, SPINE_BONES, bias=[0, 5.0, 0]), fins(3.0, 14, 0.5)))
    add('roar', 2.5, False, merge(jaw(2.5, 0.95, 0.45), fins(2.5, -34, 0.45), swell(2.5, 3.5, 1, 0.4, SPINE_BONES[:8])))
    add('hurt', 0.6, False, merge(swell(0.6, 7.0, 1, 1.2, SPINE_BONES[:6])))
    add('frenzy', 1.2, True, merge(swell(1.2, 8.5, 1, 0.75, SPINE_BONES), jaw(1.2, 0.4, 0.5), fins(1.2, 12, 0.5)))

    # Death: coordination fails, then the body simply stops holding itself up.
    death = merge(swell(6.0, 2.0, 1, 0.9, SPINE_BONES), jaw(6.0, 0.35, 0.25))
    for name in FIN_BONES:
        death[name] = {'rotation': keys([(0, [0, 0, 0]), (2.0, [18, 0, 0]), (6.0, [46, 0, 0])])}
    A['death'] = {'loop': 'hold_on_last_frame', 'animation_length': 6.0, 'bones': death}

    out = {'format_version': '1.8.0', 'animations': A}
    (ROOT / 'animations/abyssal_pilgrim.animation.json').write_text(json.dumps(out, indent=1) + '\n')
    return len(A)

# ----------------------------------------------------------------------- textures
def build_textures():
    from PIL import Image
    size = 256
    sheet = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    glow = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px, gx = sheet.load(), glow.load()
    rnd = random.Random(7741)

    def plate(ox, oy, base, streak, seam, plate_size=16):
        for y in range(64):
            for x in range(64):
                n = rnd.random()
                r, g, b = base
                # subtle mottling plus horizontal plate seams for the armoured read
                v = 0.86 + n * 0.28
                if x % plate_size == 0 or y % plate_size == 0: v *= seam
                if (x + y) % 23 == 0: v *= 1.0 + streak
                px[ox + x, oy + y] = (min(255, int(r * v)), min(255, int(g * v)), min(255, int(b * v)), 255)

    plate(*TILE['armor'], (17, 19, 30), 0.35, 0.62)
    plate(*TILE['under'], (34, 35, 45), 0.20, 0.74)
    plate(*TILE['bone'], (196, 188, 168), 0.10, 0.88, 8)
    plate(*TILE['spine'], (118, 116, 126), 0.18, 0.72, 8)
    plate(*TILE['rib'], (156, 150, 140), 0.14, 0.80, 8)
    plate(*TILE['maw'], (74, 44, 58), 0.22, 0.80)
    plate(*TILE['tendril'], (26, 24, 42), 0.30, 0.70, 8)

    # the emissive tile, and the only region present in the glow mask
    ox, oy = TILE['glow']
    for y in range(64):
        for x in range(64):
            d = math.hypot(x - 32, y - 32) / 32.0
            core = max(0.0, 1.0 - d * 0.85) ** 1.6
            flick = 0.82 + rnd.random() * 0.32
            r = int(min(255, (80 + 150 * core) * flick))
            g = int(min(255, (40 + 90 * core) * flick))
            b = int(min(255, (150 + 105 * core) * flick))
            px[ox + x, oy + y] = (r, g, b, 255)
            gx[ox + x, oy + y] = (r, g, b, int(min(255, 90 + 165 * core)))

    sheet.save(ROOT / 'textures/entity/abyssal_pilgrim.png')
    glow.save(ROOT / 'textures/entity/abyssal_pilgrim_glowmask.png')
    return 2

# ----------------------------------------------------------------------- audio
RATE = 44100
def ffmpeg_bin():
    found = shutil.which('ffmpeg')
    if found: return found
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()

def lowpass(buf, cut):
    a = math.exp(-2 * math.pi * cut / RATE)
    out, prev = [], 0.0
    for v in buf:
        prev = v * (1 - a) + prev * a
        out.append(prev)
    return out

def reverb(buf, taps=((1873, .38), (3301, .27), (5407, .19), (8219, .12))):
    out = list(buf)
    for delay, gain in taps:
        for i in range(delay, len(out)):
            out[i] += out[i - delay] * gain
    return out

def render(name, seconds, fn, cut=None, wet=True, gain=0.9):
    n = int(RATE * seconds)
    rnd = random.Random(hash(name) & 0xffff)
    buf = [fn(i / RATE, i, rnd) for i in range(n)]
    if cut: buf = lowpass(buf, cut)
    if wet: buf = reverb(buf)
    # short fades so nothing clicks, then normalise
    fade = int(RATE * 0.02)
    for i in range(fade):
        buf[i] *= i / fade
        buf[-1 - i] *= i / fade
    peak = max(1e-6, max(abs(v) for v in buf))
    scale = gain / peak
    frames = b''.join(struct.pack('<h', max(-32767, min(32767, int(v * scale * 32767)))) for v in buf)
    wav = ROOT / ('sounds/%s.wav' % name)
    with wave.open(str(wav), 'wb') as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(RATE); out.writeframes(frames)
    subprocess.run([ffmpeg_bin(), '-v', 'error', '-y', '-i', str(wav), '-c:a', 'libvorbis', '-q:a', '4',
                    str(ROOT / ('sounds/%s.ogg' % name))], check=True)
    wav.unlink()

def build_sounds():
    S = math.sin
    T = 2 * math.pi
    def body(f, t, h=(1, .5, .28, .16)):
        return sum(a * S(T * f * k * t) for k, a in enumerate(h, start=1))

    # Long, low and slow: the call of something that does not need to be quick.
    render('pilgrim_distant_call', 7.0, lambda t, i, r: body(38 - 10 * t / 7, t) * math.exp(-t * .22) * (.8 + .2 * S(T * .7 * t)), cut=420)
    render('pilgrim_deep_idle', 5.5, lambda t, i, r: body(31, t, (1, .42, .2)) * (.55 + .3 * S(T * .35 * t)) * math.exp(-t * .1), cut=300)
    render('pilgrim_clicking', 2.2, lambda t, i, r: (S(T * 1400 * t) + r.uniform(-.5, .5)) * (1 if (i % 5100) < 260 else 0) * math.exp(-(i % 5100) / 900), cut=5200)
    render('pilgrim_target_detected', 2.4, lambda t, i, r: body(46 + 30 * t / 2.4, t, (1, .6, .3)) * min(1, t * 3) * math.exp(-t * .5), cut=900)
    render('pilgrim_stalk', 4.5, lambda t, i, r: body(26, t, (1, .3)) * (.4 + .35 * S(T * .22 * t)) + r.uniform(-.02, .02), cut=240)
    render('pilgrim_breach_charge', 3.4, lambda t, i, r: (body(28 + 62 * (t / 3.4) ** 2, t, (1, .55, .3, .18)) + r.uniform(-.18, .18) * (t / 3.4)) * min(1, t * 1.6), cut=1600)
    render('pilgrim_breach', 2.8, lambda t, i, r: (r.uniform(-1, 1) * math.exp(-t * 2.6) * 1.2 + body(52, t, (1, .5)) * math.exp(-t * 1.3)), cut=4200)
    render('pilgrim_water_impact', 3.4, lambda t, i, r: (r.uniform(-1, 1) * math.exp(-t * 1.5) + body(33, t, (1, .65, .3)) * math.exp(-t * .8) * 1.3), cut=3400)
    render('pilgrim_roar', 4.2, lambda t, i, r: body(42, t, (1, .7, .45, .3, .2)) * (.6 + .4 * S(T * 7.5 * t)) * min(1, t * 2.4) * math.exp(-t * .42), cut=1500)
    render('pilgrim_bite', 0.7, lambda t, i, r: (r.uniform(-1, 1) * math.exp(-t * 34) + body(70, t, (1, .6)) * math.exp(-t * 11)), cut=5000, wet=False)
    render('pilgrim_grab', 0.6, lambda t, i, r: (r.uniform(-1, 1) * math.exp(-t * 20) * .7 + body(95, t) * math.exp(-t * 14)), cut=2600, wet=False)
    render('pilgrim_throw', 0.9, lambda t, i, r: r.uniform(-1, 1) * math.sin(math.pi * min(1, t / .9)) * (.4 + .6 * t / .9), cut=2200)
    render('pilgrim_lunge', 1.1, lambda t, i, r: (r.uniform(-1, 1) * .6 + body(40 + 55 * t, t, (1, .4))) * math.sin(math.pi * min(1, t / 1.1)), cut=2000)
    render('pilgrim_tail_sweep', 1.4, lambda t, i, r: (r.uniform(-1, 1) * .8 + body(30, t)) * math.sin(math.pi * min(1, t / 1.4)) ** 2, cut=1500)
    # Dissonant: two close low partials beating against each other is the pressure pulse.
    render('pilgrim_void_scream', 5.0, lambda t, i, r: (body(36, t, (1, .6, .4, .25)) + body(38.7, t, (1, .55, .35)) + r.uniform(-.12, .12)) * min(1, t * 1.2) * math.exp(-t * .28), cut=2600)
    render('pilgrim_hurt', 0.8, lambda t, i, r: body(24, t, (1, .3)) * math.exp(-t * 7) + r.uniform(-.1, .1) * math.exp(-t * 16), cut=700, wet=False)
    render('pilgrim_death', 8.5, lambda t, i, r: body(48 - 34 * t / 8.5, t, (1, .65, .4, .25, .15)) * (.7 + .3 * S(T * 1.4 * t)) * math.exp(-t * .17), cut=1100)
    return 17

def register_sounds():
    names = ['distant_call', 'deep_idle', 'clicking', 'target_detected', 'stalk', 'breach_charge', 'breach',
             'water_impact', 'roar', 'bite', 'grab', 'throw', 'lunge', 'tail_sweep', 'void_scream', 'hurt', 'death']
    path = ROOT / 'sounds.json'
    data = json.loads(path.read_text()) if path.exists() else {}
    for n in names:
        key = 'pilgrim_' + n
        data[key] = {'subtitle': 'subtitles.hexgodofstories.' + key,
                     'sounds': [{'name': 'hexgodofstories:' + key, 'stream': n in ('distant_call', 'death', 'void_scream')}]}
    path.write_text(json.dumps(dict(sorted(data.items())), indent=2) + '\n')

    lang = ROOT / 'lang/en_us.json'
    entries = json.loads(lang.read_text()) if lang.exists() else {}
    entries['entity.hexgodofstories.abyssal_pilgrim'] = 'The Abyssal Pilgrim'
    pretty = {'distant_call': 'A call from far below', 'deep_idle': 'Something breathes in the dark',
              'clicking': 'Clicking', 'target_detected': 'You have been noticed', 'stalk': 'Something is keeping pace',
              'breach_charge': 'The water begins to move', 'breach': 'The ocean opens',
              'water_impact': 'Impact', 'roar': 'The Pilgrim roars', 'bite': 'Jaws close',
              'grab': 'Tendrils take hold', 'throw': 'Thrown', 'lunge': 'Something rushes past',
              'tail_sweep': 'The tail whips through', 'void_scream': 'Void scream',
              'hurt': 'The blow does not land', 'death': 'A final enormous call'}
    for n in names:
        entries['subtitles.hexgodofstories.pilgrim_' + n] = pretty[n]
    lang.write_text(json.dumps(entries, indent=2, ensure_ascii=False) + '\n')

if __name__ == '__main__':
    b, c = build_geometry()
    a = build_animations()
    t = build_textures()
    s = build_sounds()
    register_sounds()
    print('geometry: %d bones, %d cubes' % (b, c))
    print('animations: %d clips' % a)
    print('textures: %d' % t)
    print('sounds: %d' % s)
