"""Abyssal leviathan: box geometry, box-UV atlases and the baked Java LayerDefinition.

Run from the repository root:  python tools/generate_leviathan.py

Authoring space here is +X left, +Y up, +Z forward (the nose), one unit per
Minecraft pixel. Minecraft entity model space is the same volume rotated a half
turn about X - +Y down and -Z forward - so the emitter writes (x, -y, -z) and
negates the Y and Z rotations. Every rotated detail is its own bone, which is
what lets the jaw, gills, barbels and each individual tooth move on their own.
"""
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXTURES = ROOT / 'src/main/resources/assets/hexgodofstories/textures/entity'
JAVA = ROOT / 'src/main/java/com/hexgodofstories/client/LeviathanParts.java'

# --------------------------------------------------------------- geometry ---
BONES = []
BY_NAME = {}


def bone(name, parent=None, pivot=(0, 0, 0), rot=(0, 0, 0)):
    b = {"name": name, "parent": parent, "pivot": [float(v) for v in pivot],
         "rot": [float(v) for v in rot], "cubes": []}
    BONES.append(b)
    BY_NAME[name] = b
    return b


def box(bone_name, pos, size, mat, faces=None, inflate=0.0):
    """pos = min corner relative to the bone pivot; size = (w,h,d)."""
    BY_NAME[bone_name]["cubes"].append({
        "pos": [float(v) for v in pos], "size": [float(v) for v in size],
        "mat": mat, "faces": faces or {}, "inflate": float(inflate)})


def part(name, parent, pivot, rot, pos, size, mat, faces=None, inflate=0.0):
    bone(name, parent, pivot, rot)
    box(name, pos, size, mat, faces, inflate)
    return name


def mirror_part(name, parent, pivot, rot, pos, size, mat, faces=None, inflate=0.0):
    """Creates <name>_l on +X and <name>_r on -X (facing +Z, +X is the left flank)."""
    part(name + "_l", parent, pivot, rot, pos, size, mat, faces, inflate)
    px, py, pz = pivot
    rx, ry, rz = rot
    x, y, z = pos
    w = size[0]
    part(name + "_r", parent, (-px, py, pz), (rx, -ry, -rz),
         (-x - w, y, z), size, mat, faces, inflate)


# --------------------------------------------------------------------------
# spine: pivots sit at the FRONT face of each segment, cubes run backwards,
# so a rotation on any link sweeps everything behind it.
# --------------------------------------------------------------------------
SPINE = [
    # name,      pivot dz, depth, width, height
    ("spine_0",   0.0, 22, 36, 38),
    ("spine_1", -22.0, 20, 34, 36),
    ("spine_2", -20.0, 20, 31, 33),
    ("spine_3", -20.0, 18, 27, 29),
    ("spine_4", -18.0, 18, 22, 24),
    ("spine_5", -18.0, 16, 17, 19),
    ("spine_6", -16.0, 16, 12, 14),
    ("spine_7", -16.0, 18,  8, 10),
    ("tail_tip", -18.0, 16,  5,  7),
]
prev = None
for name, dz, d, w, h in SPINE:
    bone(name, prev, (0, 0, dz))
    box(name, (-w / 2, -h / 2, -d), (w, h, d), "hide")
    prev = name

# neck runs forward off the shoulder block
bone("neck_0", "spine_0", (0, 0, 0))
box("neck_0", (-17, -18, 0), (34, 36, 22), "hide")
bone("neck_1", "neck_0", (0, 0, 22))
box("neck_1", (-15.5, -16.5, 0), (31, 33, 20), "hide")
bone("head", "neck_1", (0, 0, 20))
box("head", (-17, -11, 0), (34, 25, 28), "hide")
# heavy jaw muscle behind the hinge — the silhouette that reads as "bite force"
mirror_part("cheek", "head", (17, -2, 4), (0, 0, 0), (0, -9, 0), (4, 16, 18), "hide")
# armoured skull plate
box("head", (-13, 12, 3), (26, 4, 22), "bone")

# --------------------------------------------------------------------------
# jaws
# --------------------------------------------------------------------------
# The jaws occlude on one plane at head y = -12. The snout is narrower than the
# mandible's tooth line and vice versa, so the two rows interlock OUTSIDE each
# other when the mouth shuts instead of driving through the opposing jaw.
bone("upper_jaw", "head", (0, -2, 26))
box("upper_jaw", (-12, -10, 0), (24, 15, 23), "hide", {"down": "mouth"})
box("upper_jaw", (-9, 4, 1), (18, 4, 18), "bone")          # nasal ridge
mirror_part("nostril", "upper_jaw", (6, 5, 17), (0, 0, 0), (-2, 0, 0), (4, 3, 4), "bone")
box("upper_jaw", (-10, -8, 21), (20, 11, 3), "hide")       # blunt premaxilla

bone("lower_jaw", "head", (0, -13, 24))
box("lower_jaw", (-11, -9, 0), (22, 10, 29), "hide", {"up": "mouth"})
box("lower_jaw", (-10, -15, 2), (20, 7, 21), "hide")        # throat pouch
box("lower_jaw", (-6, -1, 3), (12, 3, 19), "tongue")
mirror_part("jaw_spike", "lower_jaw", (11, -7, 6), (0, 0, -28), (0, 0, -3), (3, 9, 7), "bone")

# gullet at the back of the throat, seen only when the jaw drops
box("head", (-10, -13, 20), (20, 7, 9), "mouth")

# --------------------------------------------------------------------------
# teeth — staggered rows, front pair oversized, each canted outward
# --------------------------------------------------------------------------
def tooth_pair(name, parent, pos, rot, w, ln, down=True):
    """Two stacked cubes per tooth: a wide root and a narrow tip, which is how a
    box model gets a point on it. Mirrored to both jaw lines."""
    for sign, tag in ((1, "l"), (-1, "r")):
        bn = "%s_%s" % (name, tag)
        bone(bn, parent, (sign * pos[0], pos[1], pos[2]), (rot[0], sign * rot[1], sign * rot[2]))
        if down:
            box(bn, (-w / 2, -ln * 0.55, -w / 2), (w, ln * 0.55, w), "tooth")
            box(bn, (-w * 0.3, -ln, -w * 0.3), (w * 0.6, ln * 0.5, w * 0.6), "tooth")
        else:
            box(bn, (-w / 2, 0, -w / 2), (w, ln * 0.55, w), "tooth")
            box(bn, (-w * 0.3, ln * 0.45, -w * 0.3), (w * 0.6, ln * 0.5, w * 0.6), "tooth")


# upper row hangs outside the mandible (x 12.6 > mandible half-width 11)
UPPER_TEETH = [(2, 10), (6, 12), (10, 11), (14, 13), (17.5, 12), (21, 16)]
# lower row rides outside the snout (x 13.2 > snout half-width 12)
LOWER_TEETH = [(4, 11), (8, 10), (12, 13), (16, 11), (19.5, 15), (23, 12)]
for i, (z, ln) in enumerate(UPPER_TEETH):
    tooth_pair("tooth_u%d" % i, "upper_jaw", (12.6 - (z / 23.0) * 1.2, -9.5, z),
               (-6 + i * 1.5, 0, 7), 4.5 if ln >= 13 else 3.5, ln, True)
for i, (z, ln) in enumerate(LOWER_TEETH):
    tooth_pair("tooth_l%d" % i, "lower_jaw", (13.2 - (z / 25.0) * 1.4, 0.5, z),
               (5 - i * 1.5, 0, -7), 4.5 if ln >= 13 else 3.5, ln, False)

# --------------------------------------------------------------------------
# head furniture
# --------------------------------------------------------------------------
mirror_part("eye", "head", (16, 6, 17), (0, 0, 0), (0, -3.5, -3), (4, 7, 6), "eye")
mirror_part("brow", "head", (11, 11, 11), (-7, 0, 11), (-6, 0, -7), (12, 5, 20), "bone")
# backswept horns, three tapering links each
for sign, tag in ((1, "l"), (-1, "r")):
    bone("horn_a_" + tag, "head", (sign * 11, 13, 4), (30, sign * -10, sign * 15))
    box("horn_a_" + tag, (-3, 0, -3), (6, 16, 6), "bone")
    bone("horn_b_" + tag, "horn_a_" + tag, (0, 16, 0), (22, 0, sign * 7))
    box("horn_b_" + tag, (-2.5, 0, -2.5), (5, 14, 5), "bone")
    bone("horn_c_" + tag, "horn_b_" + tag, (0, 14, 0), (20, 0, sign * 5))
    box("horn_c_" + tag, (-2, 0, -2), (4, 12, 4), "bone")
mirror_part("crown_spike", "head", (6, 14, -1), (48, 0, 10), (-2, 0, -2), (4, 9, 4), "bone")

# gill rakes, three a side, they flare on a roar
for i in range(3):
    mirror_part("gill_%d" % i, "neck_1", (15.5, 2, 4 + i * 5), (0, 14 - i * 4, 0),
                (0, -10, 0), (2, 20, 4), "gill")

# chin barbels — two chained pairs that drift with the current
for sign, tag in ((1, "l"), (-1, "r")):
    for j in range(2):
        a, b, c = ("barb%d_a_%s" % (j, tag), "barb%d_b_%s" % (j, tag), "barb%d_c_%s" % (j, tag))
        bone(a, "lower_jaw", (sign * (9 - j * 3), -13, 19 - j * 7), (24 + j * 8, sign * (10 + j * 7), 0))
        box(a, (-1, -7, -1), (2, 7, 2), "fin")
        bone(b, a, (0, -7, 0), (17, 0, sign * 5))
        box(b, (-0.75, -6, -0.75), (1.5, 6, 1.5), "fin")
        bone(c, b, (0, -6, 0), (15, 0, sign * 4))
        box(c, (-0.5, -5, -0.5), (1, 5, 1), "fin")

# --------------------------------------------------------------------------
# fins
# --------------------------------------------------------------------------
for sign, tag in ((1, "l"), (-1, "r")):
    bone("pec_" + tag, "spine_1", (sign * 16, -8, -5), (0, sign * -16, sign * -26))
    box("pec_" + tag, (0, -2, -11) if sign > 0 else (-26, -2, -11), (26, 4, 22), "fin")
    bone("pec_tip_" + tag, "pec_" + tag, (sign * 26, 0, 0), (0, 0, sign * -20))
    box("pec_tip_" + tag, (0, -1.5, -8) if sign > 0 else (-16, -1.5, -8), (16, 3, 16), "fin")
    bone("pelvic_" + tag, "spine_4", (sign * 10, -9, -8), (0, sign * -12, sign * -30))
    box("pelvic_" + tag, (0, -1.5, -7) if sign > 0 else (-14, -1.5, -7), (14, 3, 14), "fin")
    bone("pelvic_tip_" + tag, "pelvic_" + tag, (sign * 14, 0, 0), (0, 0, sign * -18))
    box("pelvic_tip_" + tag, (0, -1, -5) if sign > 0 else (-9, -1, -5), (9, 2, 10), "fin")

# dorsal ridge: a sail that runs the length of the animal
RIDGE = [("neck_0", 22, 36, 11), ("spine_0", 22, 38, 17), ("spine_1", 20, 36, 16),
         ("spine_2", 20, 33, 14), ("spine_3", 18, 29, 12.5), ("spine_4", 18, 24, 11),
         ("spine_5", 16, 19, 9), ("spine_6", 16, 14, 7)]
for seg, d, h, rh in RIDGE:
    z0 = 0 if seg == "neck_0" else -d
    part("ridge_" + seg, seg, (0, h / 2 - 1.5, 0), (0, 0, 0),
         (-1.5, 0, z0), (3, rh, d), "fin")
# neural spikes standing proud of the sail over the shoulders
for i, (seg, y, z, ln) in enumerate((("spine_0", 18, -6, 11), ("spine_0", 18, -16, 10), ("spine_1", 17, -12, 8))):
    part("neural_%d" % i, seg, (0, y, z), (18, 0, 0), (-2, 0, -2), (4, ln, 4), "bone")

# belly plating
for seg, w, h, d in (("neck_0", 34, 36, 22), ("spine_0", 36, 38, 22), ("spine_1", 34, 36, 20),
                     ("spine_2", 31, 33, 20), ("spine_3", 27, 29, 18)):
    z0 = 1 if seg == "neck_0" else -d + 2
    box(seg, (-w * 0.3, -h / 2 - 0.5, z0), (w * 0.6, 1.5, d - 3), "belly_plate")

# tail fluke — tall eel lobes, the upper one oversized
bone("fluke_up", "tail_tip", (0, 3, -14), (-14, 0, 0))
box("fluke_up", (-1.5, 0, -18), (3, 30, 20), "fin")
bone("fluke_down", "tail_tip", (0, -3, -14), (12, 0, 0))
box("fluke_down", (-1.5, -22, -14), (3, 22, 16), "fin")
part("fluke_web", "tail_tip", (0, 0, -16), (0, 0, 0), (-1, -6, -4), (2, 12, 8), "fin")


# ---------------------------------------------------------------- texture ---

FACES = ("up", "down", "east", "north", "west", "south")


def footprint(w, h, d):
    return 2 * (d + w), d + h


def face_rects(u, v, w, h, d):
    return {
        "up":    (u + d,         v,     w, d),
        "down":  (u + d + w,     v,     w, d),
        "west":  (u,             v + d, d, h),
        "north": (u + d,         v + d, w, h),
        "east":  (u + d + w,     v + d, d, h),
        "south": (u + 2 * d + w, v + d, w, h),
    }


def pack(items, sizes=(128, 256, 512, 1024)):
    """items: list of (key, fw, fh). Shelf packer, smallest square that fits."""
    order = sorted(items, key=lambda it: -it[2])
    for size in sizes:
        placed, x, y, shelf = {}, 0, 0, 0
        ok = True
        for key, fw, fh in order:
            if fw > size:
                ok = False
                break
            if x + fw > size:
                x, y, shelf = 0, y + shelf, 0
            if y + fh > size:
                ok = False
                break
            placed[key] = (x, y)
            x += fw
            shelf = max(shelf, fh)
        if ok and y + shelf <= size:
            return size, placed
    raise SystemExit("atlas overflow")


# ---------------------------------------------------------------- noise ---
def hashf(x, y, seed=0):
    h = (int(x) * 374761393 + int(y) * 668265263 + seed * 1013904223) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, seed=0):
    xi, yi = math.floor(x), math.floor(y)
    fx, fy = x - xi, y - yi
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    a = hashf(xi, yi, seed); b = hashf(xi + 1, yi, seed)
    c = hashf(xi, yi + 1, seed); d = hashf(xi + 1, yi + 1, seed)
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(c1[i] + (c2[i] - c1[i]) * t for i in range(3))


def shade(c, amount):
    return tuple(max(0, min(255, v + amount)) for v in c)


# -------------------------------------------------------------- palette ---
DORSAL_D = (20, 30, 36)
DORSAL_L = (38, 57, 62)
FLANK_D = (30, 50, 57)
FLANK_L = (61, 92, 95)
BELLY_D = (104, 114, 96)
BELLY_L = (152, 161, 138)
FIN_D = (28, 48, 55)
FIN_L = (86, 124, 119)
TOOTH_D = (176, 170, 150)
TOOTH_L = (240, 238, 222)
BONE_D = (96, 102, 88)
BONE_L = (158, 163, 142)
MOUTH_D = (58, 24, 34)
MOUTH_L = (122, 52, 63)
GILL_D = (52, 16, 24)
GILL_L = (129, 40, 49)
GLOW_TEAL = (124, 247, 214)
GLOW_DEEP = (36, 214, 190)


def paint(mat, face, i, j, fw, fh, world, cube_seed):
    """Returns ((r,g,b,a), (gr,gg,gb,ga)) for one texel."""
    wx, wy, wz = world
    glow = (0, 0, 0, 0)

    # --- hide: countershaded scales, the signature of the whole animal -----
    if mat in ("hide", "belly_plate"):
        if mat == "belly_plate":
            band = (int(wz) // 6) % 2
            c = mix(BELLY_D, BELLY_L, 0.18 + 0.30 * band)
            c = shade(c, int(vnoise(wz * 0.6, wx * 0.6, 7) * 14) - 7)
            if int(wz) % 6 == 0:
                c = shade(c, -26)
            return tuple(int(v) for v in c) + (255,), glow
        if face == "up":
            t = vnoise(wz * 0.22, wx * 0.3, 3)
            c = mix(DORSAL_D, DORSAL_L, 0.25 + t * 0.5)
        elif face == "down":
            t = vnoise(wz * 0.25, wx * 0.35, 4)
            c = mix(BELLY_D, BELLY_L, 0.22 + t * 0.45)
            if int(wz) % 7 < 1:
                c = shade(c, -22)
        else:
            v = j / max(1.0, fh - 1.0)              # 0 at the back/top, 1 low
            line = 0.72 + 0.05 * math.sin(wz * 0.12)
            if v < line:
                k = v / line
                c = mix(DORSAL_D, FLANK_L, k * k * 0.95)
                c = mix(c, FLANK_D, 0.35 * vnoise(wz * 0.3, wy * 0.4, 5))
            else:
                k = (v - line) / max(0.001, 1 - line)
                c = mix(BELLY_D, BELLY_L, min(1.0, 0.10 + k * 0.95))
            if abs(v - line) < 0.035:               # hard countershade seam
                c = shade(c, -30)
            # photophore row riding the seam
            if abs(v - line) < 0.08 and int(wz) % 11 == 3 and fh > 8:
                c = mix(c, GLOW_TEAL, 0.75)
                glow = tuple(int(x) for x in GLOW_DEEP) + (210,)
        # scale cells: 3 wide, 2 tall, staggered rows
        row = j // 2
        off = (row % 2) * 1.5
        cx = (i + off) % 3
        if j % 2 == 0:
            c = shade(c, 7)
        if cx < 0.9:
            c = shade(c, -9)
        c = shade(c, int(vnoise(i * 0.9, j * 0.9, cube_seed) * 10) - 5)
        # old scars: a few long diagonals, keyed off the cube so they repeat
        if fw > 14 and fh > 10:
            for s in range(2):
                if hashf(cube_seed, s, 33) > 0.55:
                    continue
                sy = hashf(cube_seed, s, 21) * fh
                sx = hashf(cube_seed, s, 27) * fw * 0.7
                if abs(i - sx) < fw * 0.22 and abs((j - sy) - (i - sx) * 0.4) < 0.9:
                    c = shade(c, 26)
        return tuple(int(v) for v in c) + (255,), glow

    if mat == "fin":
        v = j / max(1.0, fh - 1.0)
        c = mix(FIN_D, FIN_L, 0.15 + 0.5 * (1 - v))
        if (i + int(wz)) % 4 == 0:                  # fin rays
            c = mix(c, FIN_L, 0.75)
        if v > 0.86:                                # frayed translucent edge
            c = mix(c, (16, 28, 34), (v - 0.86) / 0.14)
        c = shade(c, int(vnoise(i * 1.1, j * 1.1, cube_seed) * 12) - 6)
        if (i * 5 + j * 3) % 47 == 0:
            c = mix(c, GLOW_TEAL, 0.5)
            glow = tuple(int(x) for x in GLOW_DEEP) + (120,)
        return tuple(int(v) for v in c) + (255,), glow

    if mat == "tooth":
        v = j / max(1.0, fh - 1.0)
        if face in ("up", "down"):
            c = mix(TOOTH_D, TOOTH_L, 0.6)
        else:
            c = mix(TOOTH_D, TOOTH_L, min(1.0, v * 1.5))   # dark root, bright tip
            if v > 0.82:
                c = mix(c, (255, 253, 246), (v - 0.82) / 0.18)
        c = shade(c, int(vnoise(i * 2.0, j * 1.2, cube_seed) * 10) - 5)
        return tuple(int(v) for v in c) + (255,), glow

    if mat == "bone":
        v = j / max(1.0, fh - 1.0)
        c = mix(BONE_D, BONE_L, 0.35 + 0.5 * (1 - v))
        if j % 5 == 0:                               # growth rings
            c = shade(c, -16)
        c = shade(c, int(vnoise(i * 1.3, j * 1.3, cube_seed) * 14) - 7)
        return tuple(int(v) for v in c) + (255,), glow

    if mat in ("mouth", "tongue"):
        base_d, base_l = (MOUTH_D, MOUTH_L) if mat == "mouth" else ((84, 38, 47), (150, 74, 84))
        t = vnoise(i * 0.5, j * 0.5, cube_seed)
        c = mix(base_d, base_l, 0.25 + t * 0.6)
        if mat == "mouth" and (i * 3 + j * 7) % 29 == 0:
            c = mix(c, GLOW_TEAL, 0.35)
            glow = (30, 150, 140, 90)               # bioluminescent gullet
        return tuple(int(v) for v in c) + (255,), glow

    if mat == "gill":
        t = j / max(1.0, fh - 1.0)
        c = mix(GILL_D, GILL_L, 0.2 + 0.7 * t)
        if i % 2 == 0:
            c = shade(c, -24)
        return tuple(int(v) for v in c) + (255,), glow

    if mat == "eye":
        c = (10, 13, 16)
        if face in ("east", "west"):
            u = i / max(1.0, fw - 1.0)
            v = j / max(1.0, fh - 1.0)
            r = math.hypot((u - 0.5) * 1.35, v - 0.5)
            if r < 0.42:
                c = mix(GLOW_DEEP, GLOW_TEAL, 0.3 + 0.7 * (1 - r / 0.42))
                glow = tuple(int(x) for x in GLOW_TEAL) + (255,)
            if abs(u - 0.5) < 0.16 and r < 0.44:     # vertical slit pupil
                c = (6, 8, 10)
                glow = (0, 0, 0, 0)
        return tuple(int(v) for v in c) + (255,), glow

    return (255, 0, 220, 255), glow                  # unmapped material


# ------------------------------------------------------------------ png ---
def write_png(path, size, pixels):
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        row = pixels[y]
        for px in row:
            raw += bytes(px)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    open(path, "wb").write(png)


# ------------------------------------------------------------------ build ---
def pixel_world(face, i, j, box):
    """World-space coordinate for a texel, so scales and bands run along the body."""
    x0, y0, z0, x1, y1, z1 = box
    if face in ("east", "west"):
        wz = z1 - i if face == "east" else z0 + i
        return ((x1 if face == "east" else x0), y1 - j, wz)
    if face in ("north", "south"):
        wx = x1 - i if face == "north" else x0 + i
        return (wx, y1 - j, (z1 if face == "north" else z0))
    wx = x1 - i if face == "up" else x0 + i
    return (wx, (y1 if face == "up" else y0), z1 - j)


def build():
    by_name = {b["name"]: b for b in BONES}
    absolute = {}
    for b in BONES:
        p = absolute[b["parent"]] if b["parent"] else (0.0, 0.0, 0.0)
        absolute[b["name"]] = tuple(p[i] + b["pivot"][i] for i in range(3))

    items, index = [], []
    for b in BONES:
        for ci, c in enumerate(b["cubes"]):
            w, h, d = (int(math.ceil(v)) for v in c["size"])
            key = "%s#%d" % (b["name"], ci)
            items.append((key, footprint(w, h, d)[0], footprint(w, h, d)[1]))
            index.append((key, b, c, w, h, d))
    size, placed = pack(items)

    pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]
    glow = [[(0, 0, 0, 0)] * size for _ in range(size)]
    bounds = [1e9, 1e9, 1e9, -1e9, -1e9, -1e9]
    for seed, (key, b, c, w, h, d) in enumerate(index):
        u, v = placed[key]
        ap = absolute[b["name"]]
        box = (ap[0] + c["pos"][0], ap[1] + c["pos"][1], ap[2] + c["pos"][2],
               ap[0] + c["pos"][0] + c["size"][0], ap[1] + c["pos"][1] + c["size"][1],
               ap[2] + c["pos"][2] + c["size"][2])
        for k in range(3):
            bounds[k] = min(bounds[k], box[k])
            bounds[k + 3] = max(bounds[k + 3], box[k + 3])
        for face, (ru, rv, rw, rh) in face_rects(u, v, w, h, d).items():
            material = c["faces"].get(face, c["mat"])
            for j in range(rh):
                for i in range(rw):
                    col, gl = paint(material, face, i, j, rw, rh, pixel_world(face, i, j, box), seed)
                    pixels[rv + j][ru + i] = col
                    if gl[3]:
                        glow[rv + j][ru + i] = gl
    TEXTURES.mkdir(parents=True, exist_ok=True)
    write_png(TEXTURES / 'abyssal_leviathan.png', size, pixels)
    write_png(TEXTURES / 'abyssal_leviathan_glow.png', size, glow)
    return size, placed, index, by_name, bounds


def f(value):
    return ('%.4f' % value).rstrip('0').rstrip('.') + 'F' if value else '0F'


def emit_java(size, placed, index, bounds):
    """Minecraft model space is this authoring space rotated a half turn about X."""
    cubes = {}
    for key, b, c, w, h, d in index:
        u, v = placed[key]
        x0, y0, z0 = c["pos"]
        cw, ch, cd = c["size"]
        # (x, -y, -z): the min corner in Minecraft space is the max corner here.
        cubes.setdefault(b["name"], []).append((u, v, x0, -(y0 + ch), -(z0 + cd), cw, ch, cd))

    lines = []
    lines.append('package com.hexgodofstories.client;')
    lines.append('')
    lines.append('import net.minecraft.client.model.geom.PartPose;')
    lines.append('import net.minecraft.client.model.geom.ModelPart;')
    lines.append('import net.minecraft.client.model.geom.builders.*;')
    lines.append('import java.util.HashMap;')
    lines.append('import java.util.Map;')
    lines.append('')
    lines.append('/**')
    lines.append(' * Generated by tools/generate_leviathan.py - do not edit by hand.')
    lines.append(' *')
    lines.append(' * %d bones and %d cubes on the 16-pixel grid, %.1f blocks nose to fluke.' %
                 (len(BONES), len(index), (bounds[5] - bounds[2]) / 16.0))
    lines.append(' * Box UVs index a %dx%d atlas. Animation lives in LeviathanModel.' % (size, size))
    lines.append(' */')
    lines.append('public final class LeviathanParts {')
    lines.append('    public static final int TEXTURE = %d;' % size)
    lines.append('    public static final float LENGTH = %sF;' % round((bounds[5] - bounds[2]) / 16.0, 2))
    lines.append('    private LeviathanParts() {}')
    lines.append('')
    lines.append('    public static LayerDefinition createBodyLayer() {')
    lines.append('        MeshDefinition mesh = new MeshDefinition();')
    lines.append('        PartDefinition root = mesh.getRoot();')
    lines.append('        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);')
    for b in BONES:
        parent = 'body' if b["parent"] is None else b["parent"]
        builder = 'CubeListBuilder.create()'
        for (u, v, x, y, z, cw, ch, cd) in cubes.get(b["name"], []):
            builder += '.texOffs(%d, %d).addBox(%s, %s, %s, %s, %s, %s)' % (
                u, v, f(x), f(y), f(z), f(cw), f(ch), f(cd))
        px, py, pz = b["pivot"]
        rx, ry, rz = b["rot"]
        if rx or ry or rz:
            # Y and Z rotations mirror with the half turn; X keeps its sign.
            pose = 'PartPose.offsetAndRotation(%s, %s, %s, %s, %s, %s)' % (
                f(px), f(-py), f(-pz), f(math.radians(rx)), f(math.radians(-ry)), f(math.radians(-rz)))
        else:
            pose = 'PartPose.offset(%s, %s, %s)' % (f(px), f(-py), f(-pz))
        lines.append('        PartDefinition %s = %s.addOrReplaceChild("%s", %s, %s);' %
                     (b["name"], parent, b["name"], builder, pose))
    lines.append('        return LayerDefinition.create(mesh, %d, %d);' % (size, size))
    lines.append('    }')
    lines.append('')
    lines.append('    /** Flat index so the animator can address any bone by name without walking the tree. */')
    lines.append('    public static Map<String, ModelPart> index(ModelPart root) {')
    lines.append('        Map<String, ModelPart> parts = new HashMap<>();')
    lines.append('        ModelPart body = root.getChild("body");')
    lines.append('        parts.put("body", body);')
    for b in BONES:
        parent = 'body' if b["parent"] is None else b["parent"]
        lines.append('        ModelPart %s = %s.getChild("%s"); parts.put("%s", %s);' %
                     (b["name"], parent, b["name"], b["name"], b["name"]))
    lines.append('        return parts;')
    lines.append('    }')
    lines.append('}')
    JAVA.parent.mkdir(parents=True, exist_ok=True)
    JAVA.write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    size, placed, index, by_name, bounds = build()
    emit_java(size, placed, index, bounds)
    print('Leviathan: %d bones, %d cubes, %.1f blocks long, %dx%d atlas -> %s, %s'
          % (len(BONES), len(index), (bounds[5] - bounds[2]) / 16.0, size, size,
             TEXTURES.name + '/abyssal_leviathan.png', JAVA.name))
