"""Reproducible authored meshes, textures, particle sprites, animations and recorded-Foley mappings.

The nebula-family sprites and the ground-blood decal live in `generate_vfx_sprites.py` instead, which
needs no third-party imaging library; this module needs Pillow.

Requires Pillow. Audio references samples from the installed Minecraft assets; no audio
is redistributed or synthesized.

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

# Time Branch Unleashing. Deliberately simple: the power is in the sphere and in the world reacting to
# it, not in the body. Both arms go forward and stay there, with only enough tremble to read as strain.
# The loop opens already extended so the three-tick fade carries the raise and the arms never drop.
animation('unleash', [
    (0, (-93, -13, -6), (-93, 13, 6), (-4, 0, 0)),
    (10, (-96, -11, -9), (-96, 11, 9), (-5, 0, 0)),
    (20, (-90, -15, -3), (-90, 15, 3), (-3, 0, 0)),
    (30, (-95, -12, -8), (-95, 12, 8), (-5, 0, 0)),
    (40, (-93, -13, -6), (-93, 13, 6), (-4, 0, 0)),
], 40, loop=True)

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
# Reference the installed game's recorded Foley. Do not regenerate the old oscillators/noise beds.
# These are event references, preserving sample variation and user resource-pack overrides.
sound_events = {'rift_open': ('block.glass.break', 0.95, 0.92), 'rift_close': ('block.glass.break', 0.48, 1.18), 'blade_swing': ('entity.player.attack.sweep', 0.8, 1.0), 'blade_throw': ('item.trident.throw', 0.7, 1.16), 'blade_hit': ('item.trident.hit', 0.75, 1.08), 'blade_embed': ('item.trident.hit_ground', 0.7, 0.92), 'conjure': ('item.armor.equip_iron', 0.65, 1.16), 'illusion': ('entity.player.attack.sweep', 0.42, 0.72), 'sorcery': ('entity.evoker.cast_spell', 0.4, 0.92), 'teleport': ('item.chorus_fruit.teleport', 0.38, 1.0), 'time_stop': ('block.beacon.deactivate', 0.36, 0.78), 'time_resume': ('block.beacon.activate', 0.32, 0.92), 'time_slip': ('item.chorus_fruit.teleport', 0.36, 0.8), 'ascend': ('item.armor.equip_netherite', 0.75, 0.82), 'branch_hum': ('block.beacon.ambient', 0.9, 0.55), 'branch_resonance': ('block.conduit.ambient', 0.9, 0.7), 'branch_shimmer': ('block.amethyst_block.resonate', 0.8, 1.35), 'branch_pressure': ('entity.warden.heartbeat', 1.0, 0.6), 'branch_crackle': ('block.amethyst_cluster.hit', 0.7, 1.4), 'branch_ready': ('block.beacon.power_select', 1.0, 1.25), 'branch_open': ('block.end_portal.spawn', 1.0, 0.8), 'branch_release': ('entity.ender_dragon.growl', 1.0, 0.85), 'branch_roar': ('block.portal.ambient', 1.0, 0.5), 'branch_erase': ('entity.elder_guardian.curse', 0.8, 1.35), 'meteor_burn': ('entity.blaze.burn', 1.0, 0.55), 'meteor_roar': ('entity.lightning_bolt.thunder', 1.0, 0.45), 'meteor_impact': ('entity.generic.explode', 1.0, 0.6), 'grip_hold': ('block.beacon.ambient', 0.55, 1.5), 'emerald_cast': ('entity.illusioner.cast_spell', 0.7, 1.1)}
(ROOT / 'sounds.json').write_text(json.dumps({
    name: {'subtitle': 'subtitles.loki.' + name, 'sounds': [{
        'name': 'minecraft:' + event, 'type': 'event', 'volume': volume, 'pitch': pitch}]}
    for name, (event, volume, pitch) in sound_events.items()
}, indent=2) + '\n')

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
      f'and {len(sound_events)} recorded-Foley event mappings.')
