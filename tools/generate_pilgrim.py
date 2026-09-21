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

    def ribbon(points, width, tile):
        # Overlapping stepped plates follow a sculpted curve, rather than rigid straight bars.
        out = []
        for k, (a, b) in enumerate(zip(points, points[1:])):
            dist = math.dist(a, b)
            steps = max(1, math.ceil(dist / (width * 0.75)))
            for n in range(steps):
                t = n / steps
                v = [a[j] + (b[j] - a[j]) * t for j in range(3)]
                w = width * (1 - 0.55 * (k + t) / (len(points) - 1))
                out.append(cube([v[0]-w/2, v[1]-w/2, v[2]-w/2], [w,w,w*1.35], tile))
        return out

    # Flattened, layered cranial shield; the silhouette is a long wedge, not a rectangular snout.
    skull = []
    for j in range(9):
        z = 40 - j * 26
        w = 73 - j * 4.2
        h = 44 - j * 2.2
        skull.append(cube([-w, -h*.55, z-28], [2*w,h,32], 'armor', down='under'))
        skull.append(cube([-w*.70,h*.45,z-30], [w*1.4,12,36], 'spine'))
        for sign in (-1,1):
            skull.append(cube([sign*w-8,-h*.4,z-25], [14,h*.85,27], 'armor'))
    bone('head', 'root', [0,0,0], skull)

    # Open channel between jaws. Teeth alternate along their LENGTH, never a comb on the nose.
    for name, y, upper in [('upper_jaw',-9,True),('lower_jaw',-44,False)]:
        jaw_cubes=[]
        for j in range(7):
            z=-130-j*19; w=43-j*2.1
            jaw_cubes.append(cube([-w,y,z-22],[w*2,13,25],'armor'))
            jaw_cubes.append(cube([-w*.74,y+(-2 if upper else 11),z-20],[w*1.48,4,21],'maw'))
            for sign in (-1,1):
                height=11+(j*7%17)
                jaw_cubes.append(cube([sign*(w-6)-4,y-height+2 if upper else y+12,z-14],[8,height,7],'bone'))
        bone(name,'head',[0,y,-110],jaw_cubes)
    for side, sign in [('left',-1),('right',1)]:
        points=[(sign*42,-38,-118),(sign*53,-48,-168),(sign*48,-54,-218),(sign*32,-59,-275)]
        mand=ribbon(points,16,'rib')
        for k in range(6):
            mand.append(cube([sign*(49-k*2)-3,-43-k*2,-155-k*19],[6,12+k%3*5,6],'bone'))
        bone('jaw_split_'+side,'head',[sign*42,-38,-118],mand)

    sensory=[]
    for sign in (-1,1):
        for k in range(5):
            # Nodes sit OUTSIDE the skull's armor. Old nodes were buried inside solid cubes.
            z=-65-k*24; w=61-k*4
            sensory.append(cube([sign*w-5,9+(k%2)*8,z],[10,10+(k%3)*2,12],'glow'))
    bone('sensory_organs','head',[0,0,-100])
    bone('glow_organs_head','sensory_organs',[0,0,-100],sensory)
    bone('glow_organs_0','head',[0,0,0],[cube([-5,39,-75],[10,5,18],'glow')])

    # Six trailing cheek feelers: long, tapered and swept BACK along the neck.
    bone('head_tendrils','head',[0,-15,-60])
    for t in range(6):
        sign=-1 if t%2==0 else 1; k=t//2
        points=[(sign*62,-12-k*12,-60),(sign*(96+k*16),-30-k*22,20),
                (sign*(124+k*12),-42-k*32,130),(sign*(140+k*15),-60-k*24,270+k*45)]
        bone('head_tendril_'+str(t),'head_tendrils',points[0],ribbon(points,10-k,'tendril'))
        bone('head_tendril_tip_'+str(t),'head_tendril_'+str(t),points[-1],
             [cube([points[-1][0]-3,points[-1][1]-3,points[-1][2]],[6,6,10],'glow')])

    # Independent world-path joints: head tracking cannot rotate the entire spine.
    for i in range(1, SEGMENTS):
        r=radius(i); z=zpos(i)
        plates=[cube([-r*.80,-r*.57,z-49],[r*1.6,r*1.14,102],'under')]
        for j in range(3):
            q=z-52+j*34; w=r*(1.0-.055*j)
            plates.append(cube([-w,-r*.32,q],[w*2,r*.80,39],'armor'))
            plates.append(cube([-w*.64,r*.44,q-2],[w*1.28,r*.22,40],'spine'))
            for sign in (-1,1):
                plates.append(cube([sign*w-w*.10,-r*.44,q],[w*.20,r*.60,31],'armor'))
        bone(spine_name(i),'root',[0,0,z],plates)
        organs=[]
        for sign in (-1,1):
            organs.append(cube([sign*r*.75-4,r*.62,z-20],[8,6,12],'glow'))
        bone('glow_organs_'+str(i),spine_name(i),[0,0,z],organs)

    # Sparse, swept-back dorsal sails and paired hanging rib feelers define the reference.
    for b in range(TAIL_START-BODY_START):
        i=BODY_START+b; r=radius(i); z=zpos(i); taper=1-b/15
        fin=[]
        height=(135 if b in (0,3,6) else 45)*taper
        for k in range(6):
            y=r*.60+k*height/6
            fin.append(cube([-7+k*.6,y,z-27+k*13],[14-k*1.2,height/6+5,70-k*7],'spine'))
        bone('dorsal_fin_'+str(b),spine_name(i),[0,r*.60,z],fin)
        for side,sign in [('l',-1),('r',1)]:
            points=[(sign*r*.82,-r*.1,z),(sign*(r+20*taper),-r*.7-36*taper,z+15),
                    (sign*(r+35*taper),-r*.7-95*taper,z+40),
                    (sign*(r+58*taper),-r*.7-147*taper,z+75),
                    (sign*(r+89*taper),-r*.7-157*taper,z+100)]
            bone('rib_appendage_'+side+'_'+str(b),spine_name(i),points[0],ribbon(points,14*taper+4,'rib'))
    end=zpos(SEGMENTS-1)
    bone('tail_tendrils',spine_name(SEGMENTS-1),[0,0,end+35])
    for t in range(4):
        sign=-1 if t%2==0 else 1; k=t//2
        pts=[(sign*5,0,end+30),(sign*(23+k*10),k*12,end+68),
             (sign*(39+k*15),k*19,end+106),(sign*(43+k*17),k*10,end+137)]
        bone('tail_tendril_'+str(t),'tail_tendrils',pts[0],ribbon(pts,9-k*2,'tendril'))

    geo={'format_version':'1.12.0','minecraft:geometry':[{
        'description':{'identifier':'geometry.abyssal_pilgrim','texture_width':256,'texture_height':256,
            'visible_bounds_width':340,'visible_bounds_height':340,'visible_bounds_offset':[0,0,0]},
        'bones':list(BONES)}]}
    (ROOT/'geo/abyssal_pilgrim.geo.json').write_text(json.dumps(geo,indent=1)+'\n')
    return len(BONES),sum(len(b.get('cubes',[])) for b in BONES)

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
