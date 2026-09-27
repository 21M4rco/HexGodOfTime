"""Fetch and prepare the Crown of Barrels' guns, rocket, casings, muzzle flash and sounds from TACZ.

Everything here comes from TACZ's default gun pack ("Timeless and Classics Guns Zero", TACZ Dev Team; artists
NekoCrane, Receke and Pos_2333), whose assets are licensed CC BY-NC-ND 4.0: they may be shared, credited and
non-commercially, but never altered. Nothing is altered. The textures and the sounds are copied byte for byte.
The models are only translated out of the Bedrock geometry TACZ draws them from into the plain list of quads
the mod draws them from, which the licence counts as a technical change of format and never as adaptation
(CC BY-NC-ND 4.0, section 2(a)(4)): every cube where TACZ puts it, every face with TACZ's own texture
coordinates, nothing added, moved or removed. Each gun is shown as TACZ shows it out of the box, with no
attachment fitted, so the parts TACZ keeps for attachments and animation are left out as TACZ leaves them
out; the RPK, whose stock TACZ fits as an attachment, carries TACZ's own heavy factory stock, the one its
icon shows. See third_party/LICENSE_TACZ_assets and ARSENAL_CREDITS.md. TACZ itself is never a dependency.

The geometry follows TACZ's own conventions, which are vanilla Java's: a bone sits at its pivot relative to its
parent's, y counted downward (a root bone from 24 pixels up), and turns about Z, then Y, then X; a cube without
face UVs takes vanilla's box layout. The whole model is then turned half a turn about Z, as TACZ turns it to
draw it, so that in the file +y is up, the muzzle points along -z and +x is the gun's right.

Mesh file (gzip of little-endian binary), read by client/ArsenalMeshes.java:
    'HXAM', int version = 1, int quad count,
    float[3] muzzle, float[3] shell ejection port, float[3] bounds min, float[3] bounds max  (blocks)
    then four corners a quad, each: float x, y, z, float u, v, byte nx, ny, nz, byte flags (1 = lit from within)

Requires numpy. Run from the repository root:
    python tools/import_arsenal_assets.py
"""
from pathlib import Path
import gzip
import json
import math
import re
import struct
import urllib.request

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/hexgodofstories"
COMMIT = "b43eb84c38e9768d8e73c8b14f0b845669704b38"
PACK = f"https://raw.githubusercontent.com/MCModderAnchor/TACZ/{COMMIT}/src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/"

# name -> (Bedrock model, texture, parts shown that TACZ only shows with an attachment fitted)
MODELS = {
    "m249": ("geo_models/gun/m249_geo.json", "textures/gun/uv/m249.png", set()),
    "rpk": ("geo_models/gun/rpk_geo.json", "textures/gun/uv/rpk.png", {"oem_stock_heavy"}),
    "fn_evolys": ("geo_models/gun/fn_evolys_geo.json", "textures/gun/uv/fn_evolys.png", set()),
    "rpg_rocket": ("geo_models/ammo_entity/rpg_rocket.json", "textures/ammo_entity/rpg_rocket.png", set()),
    "shell_556x45": ("geo_models/shell/556x45_shell.json", "textures/shell/556x45_shell.png", set()),
    "shell_762x39": ("geo_models/shell/762x39_shell.json", "textures/shell/762x39_shell.png", set()),
    "shell_308": ("geo_models/shell/308_shell.json", "textures/shell/308_shell.png", set()),
}
TEXTURES = {"muzzle_flash": "textures/flash/common_muzzle_flash.png"}
# Mono, so the game places them in the world, except the two RPG sounds, which are stereo and played as such.
SOUNDS = {
    "m249_shoot": "tacz_sounds/m249/m249_shoot_3p.ogg",
    "rpk_shoot": "tacz_sounds/rpk/rpk_shoot_3p.ogg",
    "evolys_shoot": "tacz_sounds/fn_evolys/evolys_shoot_3p.ogg",
    "evolys_draw": "tacz_sounds/fn_evolys/evolys_draw.ogg",
    "m249_charge_pull": "tacz_sounds/m249/m249_reload_empty_fast_charge_pull.ogg",
    "m249_charge_push": "tacz_sounds/m249/m249_reload_empty_fast_charge_push.ogg",
    "flesh_hit": "tacz_sounds/flesh_hit.ogg",
    "rpg7_shoot": "tacz_sounds/rpg7/rpg7_shoot.ogg",
    "rpg7_draw": "tacz_sounds/rpg7/rpg7_draw.ogg",
}


def fetch(path):
    with urllib.request.urlopen(PACK + path, timeout=60) as response:
        return response.read()


def hidden(name, adapters, shown):
    """What TACZ leaves out of a gun with nothing attached and a round chambered (see its BedrockGunModel)."""
    if name in shown:
        return False
    if name in adapters:  # every attachment adapter, unless the attachment it adapts is fitted
        return True
    # Attachment and hand positions, the muzzle flash and ejection locators, and the parts for fitted attachments.
    if name.endswith("_pos") or name in ("muzzle_flash", "mount", "sight_folded", "handguard_tactical", "additional_magazine"):
        return True
    return name.startswith("mag_extended_") or name == "shell" or name.startswith("shell_")


def rotation(rx, ry, rz):
    """Z, then Y, then X, as vanilla's ModelPart turns."""
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    x = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    y = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    z = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return z @ y @ x


def placed(position, rotation_deg):
    """A part at {@code position} (pixels), turned Z then Y then X, as a 4x4 in blocks."""
    m = np.eye(4)
    m[:3, 3] = np.array(position, float) / 16
    m[:3, :3] = rotation(*[math.radians(v) for v in rotation_deg])
    return m


FACES = {"east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1)}
# A face's own UVs, for a cube given them face by face, are read from the opposite side's entry on the x and y
# axes: TACZ's model space has both turned over, so its east is the file's west and its up the file's down.
FACE_UV = {"east": "west", "west": "east", "up": "down", "down": "up", "north": "north", "south": "south"}


def cube(origin, size, inflate, mirror, uv, tw, th):
    """A cube's faces in its part's space: (corners, uvs, normal) each, in vanilla's order and layout."""
    x, y, z = origin
    w, h, d = size
    x0, y0, z0, x1, y1, z1 = x - inflate, y - inflate, z - inflate, x + w + inflate, y + h + inflate, z + d + inflate
    per_face = isinstance(uv, dict)
    if mirror and not per_face:
        x0, x1 = x1, x0
    c = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    corners = {"down": (5, 4, 0, 1), "up": (2, 3, 7, 6), "west": (0, 4, 7, 3), "north": (1, 0, 3, 2),
               "east": (5, 1, 2, 6), "south": (4, 5, 6, 7)}
    if per_face:
        rects = {}
        for name, source in FACE_UV.items():
            f = uv.get(source)
            if f is not None:
                rects[name] = (f["uv"][0], f["uv"][1], f["uv"][0] + f["uv_size"][0], f["uv"][1] + f["uv_size"][1])
    else:
        u, v = uv
        dx, dy, dz = int(w), int(h), int(d)
        rects = {"down": (u + dz, v, u + dz + dx, v + dz), "up": (u + dz + dx, v + dz, u + dz + 2 * dx, v),
                 "west": (u, v + dz, u + dz, v + dz + dy), "north": (u + dz, v + dz, u + dz + dx, v + dz + dy),
                 "east": (u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy), "south": (u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy)}
    for name in ("down", "up", "west", "north", "east", "south"):
        if name not in rects:
            continue
        a1, b1, a2, b2 = rects[name]
        verts = [c[i] for i in corners[name]]
        uvs = [(a2 / tw, b1 / th), (a1 / tw, b1 / th), (a1 / tw, b2 / th), (a2 / tw, b2 / th)]
        normal = FACES[name]
        if mirror and not per_face:
            verts, uvs, normal = verts[::-1], uvs[::-1], (-normal[0], normal[1], normal[2])
        yield verts, uvs, normal


def bake(geometry, shown=frozenset()):
    """Every quad TACZ would draw: (corners in blocks, uvs, normal, lit from within), and every bone's pivot in blocks."""
    geo = geometry["minecraft:geometry"][0]
    tw, th = geo["description"]["texture_width"], geo["description"]["texture_height"]
    bones = {b["name"]: b for b in geo["bones"]}
    children = {}
    for b in geo["bones"]:
        children.setdefault(b.get("parent"), []).append(b["name"])
    adapters = set(children.get("attachment_adapter", []))
    turn = np.diag([-1.0, -1.0, 1.0, 1.0])  # half a turn about Z: +y up, and not mirrored
    quads, pivots = [], {}

    def emit(faces, m, lit):
        for verts, uvs, normal in faces:
            if len(set(verts)) < 3:
                continue
            world = (turn @ m @ np.array([list(v) + [16.0] for v in verts]).T / 16).T[:, :3]
            n = turn[:3, :3] @ m[:3, :3] @ np.array(normal, float)
            quads.append((world, uvs, n / np.linalg.norm(n), lit))

    def walk(name, parent, lit):
        b = bones[name]
        p = b.get("pivot", [0, 0, 0])
        q = bones[b["parent"]].get("pivot", [0, 0, 0]) if b.get("parent") else [0, 24, 0]
        position = (p[0] - q[0], q[1] - p[1], p[2] - q[2]) if b.get("parent") else (p[0], 24 - p[1], p[2])
        m = parent @ placed(position, b.get("rotation", [0, 0, 0]))
        pivots[name] = (turn @ m @ np.array([0, 0, 0, 1.0]))[:3]
        if hidden(name, adapters, shown):
            return
        lit = lit or name.endswith("_illuminated")
        for c in b.get("cubes", []):
            size, origin = c["size"], c["origin"]
            inflate, mirror = c.get("inflate", 0), c.get("mirror", b.get("mirror", False))
            if "rotation" in c:
                cp = c.get("pivot", [0, 0, 0])
                cm = m @ placed((cp[0] - p[0], p[1] - cp[1], cp[2] - p[2]), c["rotation"])
                emit(cube((origin[0] - cp[0], cp[1] - origin[1] - size[1], origin[2] - cp[2]), size, inflate, mirror, c["uv"], tw, th), cm, lit)
            else:
                emit(cube((origin[0] - p[0], p[1] - origin[1] - size[1], origin[2] - p[2]), size, inflate, mirror, c["uv"], tw, th), m, lit)
        for child in children.get(name, []):
            walk(child, m, lit)

    for root in children.get(None, []):
        walk(root, np.eye(4), False)
    return quads, pivots


def write_mesh(path, quads, pivots):
    points = np.array([q[0] for q in quads]).reshape(-1, 3)
    low, high = points.min(axis=0), points.max(axis=0)
    # Where rounds leave and casings fly: TACZ's own locators (its muzzle flash's, at the very end of the barrel),
    # or for a model without them its nose and middle.
    nose = np.array([(low[0] + high[0]) / 2, (low[1] + high[1]) / 2, low[2]])
    muzzle = pivots.get("muzzle_flash", pivots.get("muzzle_pos", nose))
    shell = pivots.get("shell", (low + high) / 2)
    out = bytearray(b"HXAM")
    out += struct.pack("<ii", 1, len(quads))
    out += struct.pack("<12f", *muzzle, *shell, *low, *high)
    for corners, uvs, normal, lit in quads:
        n = [max(-127, min(127, int(round(v * 127)))) for v in normal]
        for (x, y, z), (u, v) in zip(corners, uvs):
            out += struct.pack("<5f3bB", x, y, z, u, v, *n, 1 if lit else 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    # A fixed timestamp, so the same model always writes the same file.
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as f:
        f.write(bytes(out))
    return muzzle, shell, low, high


def main():
    for name, (model, texture, shown) in MODELS.items():
        geometry = json.loads(re.sub(r"(?m)^\s*//[^\n]*", "", fetch(model).decode("utf-8")))
        quads, pivots = bake(geometry, shown)
        muzzle, shell, low, high = write_mesh(ASSETS / f"arsenal/{name}.mesh", quads, pivots)
        (ASSETS / f"textures/arsenal/{name}.png").parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / f"textures/arsenal/{name}.png").write_bytes(fetch(texture))
        print(f"{name}: {len(quads)} quads, {high[2] - low[2]:.2f} blocks long as authored, muzzle {np.round(muzzle, 3)}")
    for name, texture in TEXTURES.items():
        (ASSETS / f"textures/arsenal/{name}.png").write_bytes(fetch(texture))
        print(f"textures/arsenal/{name}.png")
    for name, sound in SOUNDS.items():
        target = ASSETS / f"sounds/arsenal/{name}.ogg"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(fetch(sound))
        print(f"sounds/arsenal/{name}.ogg")


if __name__ == "__main__":
    main()
