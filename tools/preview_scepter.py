"""Offline preview of the Scepter model, shaded the way the game shades it.

Reproduces the parts of Minecraft's entity pipeline that decide how the staff looks: the two fixed
directional lights with 0.4 ambient from the entity shader, per-vertex (Gouraud) colour, texture
multiply, and ScepterModel's per-vertex metal sheen. The first-person view uses the same transform
chain as the game (ItemInHandRenderer arm offset, the item's display transform, WeaponRenderer's
pose and scale) and the 70 degree hand field of view.

Development aid only; needs numpy and Pillow (pip install numpy pillow).
    python tools/preview_scepter.py out_dir
"""
from pathlib import Path
import math
import sys

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "src/main/resources/assets/hexgodofstories"

# Studio lighting in view space; must match ScepterModel. The game draws the metal with an unlit
# shader (the lightmap still applies), so these numbers are the whole shading model.
KEY = np.array([-0.45, 0.70, 0.55]) / np.linalg.norm([-0.45, 0.70, 0.55])
FILL = np.array([0.75, 0.10, 0.55]) / np.linalg.norm([0.75, 0.10, 0.55])
AMBIENT, KEY_DIFFUSE, FILL_DIFFUSE, SKY_DIFFUSE = 0.26, 0.60, 0.22, 0.14
# diffuse weight, environment weight, floor, sky, highlight strength, shininess, rim, highlight tint
MATERIALS = {
    "steel": (0.46, 0.48, 0.18, 0.86, 1.00, 34.0, 0.35, (1.00, 1.00, 1.05)),
    "gold": (0.50, 0.55, 0.25, 1.00, 0.90, 24.0, 0.30, (1.00, 0.86, 0.55)),
    "shaft": (0.50, 0.55, 0.25, 1.00, 0.90, 24.0, 0.30, (1.00, 0.86, 0.55)),
    "dark": (0.60, 0.35, 0.30, 1.00, 0.55, 40.0, 0.25, (0.80, 0.86, 0.95)),
}


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def metal(material, pv, nv):
    """Per-vertex base multiplier and additive highlight, exactly as ScepterModel computes them."""
    kd, ke, floor, sky, ks, shin, kr, tint = MATERIALS[material]
    v = -pv / np.linalg.norm(pv, axis=-1, keepdims=True)
    ndv = np.sum(nv * v, axis=-1, keepdims=True)
    nv = np.where(ndv < 0, -nv, nv)
    ndv = np.abs(ndv)
    r = 2 * ndv * nv - v
    diffuse = (AMBIENT + KEY_DIFFUSE * np.clip(nv @ KEY, 0, None) + FILL_DIFFUSE * np.clip(nv @ FILL, 0, None)
               + SKY_DIFFUSE * (0.5 + 0.5 * nv[:, 1]))
    env = floor + (sky - floor) * smoothstep(-0.25, 0.75, r[:, 1])
    base = kd * diffuse + ke * env
    fres = (1 - ndv[:, 0]) ** 4
    spec = ks * np.clip(r @ KEY, 0, 1) ** shin + kr * fres * env
    return base, spec[:, None] * np.array(tint)


def load_obj(path):
    vs, cs, vts, vns = [], [], [], []
    groups = {}
    group = "root"
    for line in open(path, encoding="utf-8"):
        s = line.split()
        if not s:
            continue
        if s[0] == "v":
            vs.append([float(x) for x in s[1:4]])
            cs.append([float(x) for x in s[4:7]] if len(s) >= 7 else [1, 1, 1])
        elif s[0] == "vt":
            vts.append([float(s[1]), float(s[2])])
        elif s[0] == "vn":
            vns.append([float(x) for x in s[1:4]])
        elif s[0] == "g":
            group = s[1]
        elif s[0] == "f":
            corner = [[int(i) - 1 for i in c.split("/")] for c in s[1:]]
            groups.setdefault(group, []).append(corner)
    return np.array(vs), np.array(cs), np.array(vts), np.array(vns), groups


def texture(name):
    img = np.asarray(Image.open(ASSET / "textures/scepter" / name).convert("RGB")).astype(np.float32) / 255
    return img


def material_of(group):
    if group.startswith("steel"):
        return "steel", "steel.png"
    if group.startswith("gold"):
        return "gold", "gold.png"
    if group.startswith("shaft"):
        return "shaft", "shaft.png"
    if group.startswith("dark"):
        return "dark", "steel.png"
    return "gem", "gem.png"


def sample(tex, uv):
    h, w, _ = tex.shape
    x = (uv[..., 0] % 1.0) * w - 0.5
    y = (uv[..., 1] % 1.0) * h - 0.5
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    x0 %= w
    y0 %= h
    x1 = (x0 + 1) % w
    y1 = (y0 + 1) % h
    return ((tex[y0, x0] * (1 - fx) + tex[y0, x1] * fx) * (1 - fy)
            + (tex[y1, x0] * (1 - fx) + tex[y1, x1] * fx) * fy)


def sheen(material, pv, nv):
    base, env, ks, kf, kr, shin = MATERIALS[material]
    v = -pv / np.linalg.norm(pv, axis=-1, keepdims=True)
    ndv = np.sum(nv * v, axis=-1, keepdims=True)
    flip = np.where(ndv < 0, -1.0, 1.0)
    nv = nv * flip
    ndv = np.abs(ndv)
    r = 2 * ndv * nv - v
    key = np.clip(r @ KEY, 0, 1) ** shin
    fill = np.clip(r @ FILL, 0, 1) ** (shin / 3)
    sky = 0.5 + 0.5 * r[:, 1]
    fres = (1 - ndv[:, 0]) ** 4
    return np.clip(base + env * sky + ks * key + kf * fill + kr * fres, 0, 1)


def render(model_matrix, width, height, fov, background=(18, 18, 24), ortho=None,
           gem_glow=1.0):
    vs, cs, vts, vns, groups = load_obj(ASSET / "models/laevateinn.obj")
    color = np.zeros((height, width, 3), np.float32)
    color[:] = np.array(background, np.float32) / 255
    depth = np.full((height, width), np.inf, np.float32)
    glow = np.zeros((height, width, 3), np.float32)
    rot = model_matrix[:3, :3]
    normal_matrix = np.linalg.inv(rot).T
    pos = vs @ rot.T + model_matrix[:3, 3]
    if ortho:
        scale = ortho
    f = 1 / math.tan(math.radians(fov or 40) / 2)

    def project(p):
        if ortho:
            return np.stack([width / 2 + p[:, 0] * scale, height / 2 - p[:, 1] * scale, -p[:, 2]], -1)
        z = -p[:, 2]
        x = width / 2 + p[:, 0] / z * f * height / 2
        y = height / 2 - p[:, 1] / z * f * height / 2
        return np.stack([x, y, z], -1)

    for group, faces in groups.items():
        material, tex_name = material_of(group)
        tex = texture(tex_name)
        faces = np.array(faces)
        pi, ti, ni = faces[..., 0], faces[..., 1], faces[..., 2]
        p = pos[pi.reshape(-1)]
        n = vns[ni.reshape(-1)] @ normal_matrix.T
        n /= np.linalg.norm(n, axis=-1, keepdims=True)
        base = cs[pi.reshape(-1)]
        uv = vts[ti.reshape(-1)]
        if material == "gem":
            vcol = base
            spec = np.zeros_like(base)
        else:
            shade, spec = metal(material, p, n)
            vcol = base * shade[:, None]
        scr = project(p).reshape(-1, 3, 3)
        vcol = vcol.reshape(-1, 3, 3)
        spec = spec.reshape(-1, 3, 3)
        uv = uv.reshape(-1, 3, 2)
        for k in range(len(scr)):
            a, b, c = scr[k]
            if not ortho and min(a[2], b[2], c[2]) <= 0.01:
                continue
            x0 = max(int(math.floor(min(a[0], b[0], c[0]))), 0)
            x1 = min(int(math.ceil(max(a[0], b[0], c[0]))), width - 1)
            y0 = max(int(math.floor(min(a[1], b[1], c[1]))), 0)
            y1 = min(int(math.ceil(max(a[1], b[1], c[1]))), height - 1)
            if x0 > x1 or y0 > y1:
                continue
            area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if abs(area) < 1e-9:
                continue
            ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
            px, py = xs + 0.5, ys + 0.5
            w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area
            w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
            if not inside.any():
                continue
            if ortho:
                z = w0 * a[2] + w1 * b[2] + w2 * c[2]
                pw = np.stack([w0, w1, w2], -1)
            else:
                iz = w0 / a[2] + w1 / b[2] + w2 / c[2]
                z = 1 / iz
                pw = np.stack([w0 / a[2], w1 / b[2], w2 / c[2]], -1) * z[..., None]
            region = depth[y0:y1 + 1, x0:x1 + 1]
            if material == "gem":
                col = pw @ vcol[k]
                t = sample(tex, pw @ uv[k])
                if group == "gem_core":
                    emit = np.array([0.55, 0.9, 1.0]) * 1.4 * gem_glow
                    add = (col * emit)[None] if False else emit * np.ones_like(t)
                    mask = inside & (z < region)
                    glow[y0:y1 + 1, x0:x1 + 1][mask] += add[mask] * 0.55
                    continue
                shade_col = t * col * 1.15 * gem_glow
                mask = inside & (z < region)
                target = color[y0:y1 + 1, x0:x1 + 1]
                target[mask] = target[mask] * 0.15 + shade_col[mask] * 0.95
                region[mask] = z[mask]
                continue
            mask = inside & (z < region)
            if not mask.any():
                continue
            col = pw @ vcol[k]
            t = sample(tex, pw @ uv[k])
            target = color[y0:y1 + 1, x0:x1 + 1]
            target[mask] = np.clip(t * col, 0, 1)[mask] + (pw @ spec[k])[mask]
            region[mask] = z[mask]
    out = np.clip(color + glow, 0, 1)
    return Image.fromarray((out * 255).astype(np.uint8))


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def matrix(rotation, translation, scale=1.0):
    m = np.eye(4)
    m[:3, :3] = rotation * scale
    m[:3, 3] = translation
    return m


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    # Side-on, as in the reference render.
    render(matrix(np.eye(3), [0, 0.1, 0]), 700, 1400, 0, ortho=1150).save(out / "front.png")
    # Three-quarter close-up of the head.
    head = matrix(rot_y(-35) @ rot_x(8), [-0.05, -0.24, -0.62])
    render(head, 900, 900, 40).save(out / "head.png")
    back = matrix(rot_y(150), [0.05, -0.24, -0.62])
    render(back, 900, 900, 40).save(out / "back.png")
    first_person(pose(REST)).save(out / "first_person_rest.png")
    first_person(pose(AIM), offset=AIM_OFFSET, slide=AIM_SLIDE).save(out / "first_person_aim.png")



# WeaponRenderer's first-person poses: rest, and raised to fire (grip offset and slide up the shaft).
REST = (-15, -35, 18)
AIM = (-82, -50, 6)
AIM_OFFSET = (-0.34, 0.38, -0.05)
AIM_SLIDE = 0.20


def pose(angles):
    x, y, z = angles
    return rot_x(x) @ rot_y(y) @ rot_z(z)


def first_person(rotation, scale=2.3, width=1280, height=720, equip=0.0, offset=(0, 0, 0), slide=0.0):
    """The in-hand view: ItemInHandRenderer's right-arm offset, then WeaponRenderer's pose."""
    arm = np.array([0.56, -0.52 + equip * -0.6, -0.72]) + np.array(offset)
    m = matrix(rotation, arm, scale)
    m[:3, 3] += (rotation * scale) @ np.array([0, -slide, 0])
    return render(m, width, height, 70.0, background=(118, 152, 196))


if __name__ == "__main__":
    main()
