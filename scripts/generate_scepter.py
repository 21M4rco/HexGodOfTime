"""Generate the Loki Scepter: a solid, bevelled 3D model and its material textures.

Every piece is built as real volume rather than a flat cut-out, so the staff reads correctly from any
side, including the oblique angle first person sees it from:

* flat metal (blade, fork, cage, frame) is an outline "inflated" into a bevelled slab: a thick flat
  centre, ground bevels running to a sharp cutting edge or a blunt back, and normals taken from the
  slab's own thickness gradient at each triangle corner, so bevel creases stay crisp while rounded
  surfaces stay smooth;
* the shaft, pommel, coil spring and gold cradle are swept along curved spines;
* the stone is an egg-shaped ellipsoid with a separate inner core for its glow.

Outlines are traced in the pixel space of the reference render (1000x2000, tip at the top) and mapped
into model units with the grip at the origin and +Y running toward the head. The model scale and the
grip height are the previous Scepter's, so size in the hand and reach are unchanged.

The output keeps the legacy laevateinn.obj slot so registry ids and saves do not change. Vertex lines
carry a baked colour (material tint times ambient occlusion) as "v x y z r g b". Faces are triangles
with v/vt/vn indices; the group name picks the material and render pass in ScepterModel.

Standard library only: CI regenerates these assets before every build.
"""
from pathlib import Path
import math
import random
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "src/main/resources/assets/hexgodofstories"
MODEL = ASSET / "models/laevateinn.obj"
TEXTURES = ASSET / "textures/scepter"

# Reference pixels to model units: the previous Scepter's scale and grip height.
S = 1.10 / 1870
GRIP_Y = 842.0

SHAFT_SPINE = [(600, 470), (655, 463), (700, 455), (760, 445), (820, 437), (880, 430), (940, 425),
               (1000, 421), (1080, 418), (1160, 416), (1240, 415), (1320, 415), (1400, 417),
               (1460, 421), (1520, 426), (1580, 433), (1640, 441), (1700, 449), (1760, 457),
               (1820, 464), (1880, 471), (1915, 480)]


def interp(table, y):
    if y <= table[0][0]:
        return table[0][1]
    for (y0, a), (y1, b) in zip(table, table[1:]):
        if y <= y1:
            return a + (b - a) * (y - y0) / (y1 - y0)
    return table[-1][1]


X0 = interp(SHAFT_SPINE, GRIP_Y)


def to_model(x, y, z=0.0):
    return ((x - X0) * S, (GRIP_Y - y) * S, z * S)


# ---------------------------------------------------------------------------------------------
# Vector helpers
# ---------------------------------------------------------------------------------------------

def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def length(a): return math.sqrt(dot(a, a))


def norm(a):
    l = length(a)
    return (0.0, 0.0, 1.0) if l < 1e-12 else (a[0] / l, a[1] / l, a[2] / l)


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------------------------------------
# Parts
# ---------------------------------------------------------------------------------------------

class Part:
    """One named piece with its own vertices, so its tint and occlusion never leak into another."""

    def __init__(self, group, tint):
        self.group = group
        self.tint = tint
        self.positions = []
        self.normals = []
        self.uvs = []
        self.tris = []
        self._p = {}
        self._n = {}
        self._t = {}

    @staticmethod
    def _index(table, store, value, digits):
        key = tuple(round(c, digits) for c in value)
        found = table.get(key)
        if found is None:
            found = len(store)
            table[key] = found
            store.append(value)
        return found

    def corner(self, position, normal, uv):
        return (self._index(self._p, self.positions, position, 7),
                self._index(self._n, self.normals, norm(normal), 3),
                self._index(self._t, self.uvs, uv, 4))

    def tri(self, a, b, c):
        if a[0] != b[0] and b[0] != c[0] and a[0] != c[0]:
            self.tris.append((a, b, c))

    def quad(self, a, b, c, d):
        self.tri(a, b, c)
        self.tri(a, c, d)


PARTS = []


def part(group, tint):
    p = Part(group, tint)
    PARTS.append(p)
    return p


# ---------------------------------------------------------------------------------------------
# 2D outline utilities (reference pixels, y down)
# ---------------------------------------------------------------------------------------------

def signed_area(poly):
    return 0.5 * sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
                     for i in range(len(poly)))


def inside(pt, poly):
    x, y = pt
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def resample(poly, spacing, closed=True):
    """Even spacing along the outline. Original corners are kept so silhouettes stay sharp."""
    out = []
    n = len(poly)
    for i in range(n if closed else n - 1):
        a = poly[i]
        b = poly[(i + 1) % n]
        steps = max(1, int(math.ceil(math.hypot(b[0] - a[0], b[1] - a[1]) / spacing)))
        for k in range(steps):
            t = k / steps
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    if not closed:
        out.append(poly[-1])
    return out


class Outline:
    """A closed outline with a bucketed segment index for fast nearest-point queries."""

    CELL = 12.0

    def __init__(self, poly):
        self.poly = poly
        self.n = len(poly)
        self.grid = {}
        for i in range(self.n):
            a, b = poly[i], poly[(i + 1) % self.n]
            x0, x1 = sorted((a[0], b[0]))
            y0, y1 = sorted((a[1], b[1]))
            for gx in range(int(math.floor(x0 / self.CELL)), int(math.floor(x1 / self.CELL)) + 1):
                for gy in range(int(math.floor(y0 / self.CELL)), int(math.floor(y1 / self.CELL)) + 1):
                    self.grid.setdefault((gx, gy), []).append(i)

    def nearest(self, pt):
        """(distance, nearest point, fractional segment index)."""
        x, y = pt
        cx, cy = int(math.floor(x / self.CELL)), int(math.floor(y / self.CELL))
        best = (1e18, None, 0.0)
        ring = 0
        seen = set()
        while True:
            for gx in range(cx - ring, cx + ring + 1):
                for gy in range(cy - ring, cy + ring + 1):
                    if max(abs(gx - cx), abs(gy - cy)) != ring:
                        continue
                    for i in self.grid.get((gx, gy), ()):
                        if i in seen:
                            continue
                        seen.add(i)
                        ax, ay = self.poly[i]
                        bx, by = self.poly[(i + 1) % self.n]
                        dx, dy = bx - ax, by - ay
                        l2 = dx * dx + dy * dy
                        t = 0.0 if l2 < 1e-12 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / l2))
                        qx, qy = ax + dx * t, ay + dy * t
                        d2 = (x - qx) ** 2 + (y - qy) ** 2
                        if d2 < best[0]:
                            best = (d2, (qx, qy), i + t)
            # Anything in a further ring is at least ring*CELL away.
            if best[1] is not None and math.sqrt(best[0]) <= ring * self.CELL:
                break
            ring += 1
            if ring > 400:
                break
        return math.sqrt(best[0]), best[1], best[2]


def polyline_distance(pt, line):
    """Distance to an open polyline and the normalised position along it."""
    best = 1e18
    along = 0.0
    total = 0.0
    x, y = pt
    for a, b in zip(line, line[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        seg = math.hypot(dx, dy)
        t = 0.0 if seg < 1e-9 else max(0.0, min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / (seg * seg)))
        d = math.hypot(x - (a[0] + dx * t), y - (a[1] + dy * t))
        if d < best:
            best = d
            along = total + seg * t
        total += seg
    return best, along / max(total, 1e-9)


def delaunay(points):
    """Bowyer-Watson triangulation; every piece here is at most a few hundred points."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    span = max(max(xs) - min(xs), max(ys) - min(ys)) * 20 + 10
    pts = list(points) + [(cx - span, cy - span), (cx + span, cy - span), (cx, cy + span)]
    n = len(points)

    def circum(i, j, k):
        ax, ay = pts[i]
        bx, by = pts[j]
        qx, qy = pts[k]
        d = 2 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by))
        if abs(d) < 1e-12:
            return (0.0, 0.0, 1e30)
        a2, b2, q2 = ax * ax + ay * ay, bx * bx + by * by, qx * qx + qy * qy
        ux = (a2 * (by - qy) + b2 * (qy - ay) + q2 * (ay - by)) / d
        uy = (a2 * (qx - bx) + b2 * (ax - qx) + q2 * (bx - ax)) / d
        return (ux, uy, (ax - ux) ** 2 + (ay - uy) ** 2)

    tris = {(n, n + 1, n + 2): circum(n, n + 1, n + 2)}
    for i in range(n):
        px, py = pts[i]
        bad = [t for t, (ux, uy, r2) in tris.items() if (px - ux) ** 2 + (py - uy) ** 2 < r2 - 1e-9]
        edges = {}
        for t in bad:
            for e in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])):
                key = (min(e), max(e))
                edges[key] = edges.get(key, 0) + 1
            del tris[t]
        for (a, b), count in edges.items():
            if count == 1:
                tris[(a, b, i)] = circum(a, b, i)
    return [t for t in tris if max(t) < n]


def inset(outline, distance):
    """Points one bevel width inside the outline; dropped wherever the offset would fold over."""
    out = []
    n = len(outline)
    index = Outline(outline)
    ccw = signed_area(outline) > 0
    for i in range(n):
        a, b, c = outline[i - 1], outline[i], outline[(i + 1) % n]
        l1 = math.hypot(b[0] - a[0], b[1] - a[1]) or 1
        l2 = math.hypot(c[0] - b[0], c[1] - b[1]) or 1
        e1 = ((b[0] - a[0]) / l1, (b[1] - a[1]) / l1)
        e2 = ((c[0] - b[0]) / l2, (c[1] - b[1]) / l2)
        n1 = (-e1[1], e1[0]) if ccw else (e1[1], -e1[0])
        n2 = (-e2[1], e2[0]) if ccw else (e2[1], -e2[0])
        m = (n1[0] + n2[0], n1[1] + n2[1])
        ml = math.hypot(*m)
        if ml < 1e-6:
            continue
        m = (m[0] / ml, m[1] / ml)
        cosine = m[0] * n1[0] + m[1] * n1[1]
        if cosine < 0.4:
            continue
        p = (b[0] + m[0] * distance / cosine, b[1] + m[1] * distance / cosine)
        if inside(p, outline) and abs(index.nearest(p)[0] - distance) < distance * 0.2:
            out.append(p)
    return out


def mesh_polygon(outline, spacing, extra=()):
    """Triangulate an outline's interior: boundary samples, an even grid and feature points."""
    boundary = resample(outline, spacing * 0.55)
    index = Outline(boundary)
    points = list(boundary)
    features = []
    for p in extra:
        if inside(p, outline) and index.nearest(p)[0] > spacing * 0.28:
            if all((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 > (spacing * 0.3) ** 2 for q in features):
                features.append(p)
    points += features
    xs = [p[0] for p in outline]
    ys = [p[1] for p in outline]
    y = min(ys) + spacing * 0.5
    row = 0
    while y < max(ys):
        x = min(xs) + spacing * (0.5 if row % 2 else 0.0)
        while x < max(xs):
            p = (x, y)
            if inside(p, outline) and index.nearest(p)[0] > spacing * 0.45:
                if all((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 > (spacing * 0.42) ** 2 for q in features):
                    points.append(p)
            x += spacing
        y += spacing * 0.866
        row += 1
    tris = []
    for a, b, c in delaunay(points):
        pa, pb, pc = points[a], points[b], points[c]
        centre = ((pa[0] + pb[0] + pc[0]) / 3, (pa[1] + pb[1] + pc[1]) / 3)
        ok = inside(centre, outline)
        if ok:
            for u, v in ((pa, pb), (pb, pc), (pc, pa)):
                m = ((u[0] + v[0]) / 2, (u[1] + v[1]) / 2)
                if not inside(m, outline) and index.nearest(m)[0] > 0.05:
                    ok = False
                    break
        if ok:
            if (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0]) < 0:
                b, c = c, b
            tris.append((a, b, c))
    return points, tris


# ---------------------------------------------------------------------------------------------
# Bevelled slab from an outline
# ---------------------------------------------------------------------------------------------

def slab(p, outline, half, bevel, edge=0.0, spacing=8.0, z=0.0, edge_at=None, bevel_at=None,
         grooves=(), uv_scale=1 / 110.0, profile="flat", taper=None):
    """Bevelled solid from a 2D outline, in reference pixels.

    half     half-thickness of the flat centre
    bevel    width of the ground bevel from the outline inward
    edge     half-thickness left at the outline itself (0 for a cutting edge)
    edge_at, bevel_at   optional overrides called with (x, y) of the nearest outline point
    grooves  (polyline, half width, depth) channels cut into both faces
    taper    optional factor on the whole thickness, called with (x, y)
    """
    if signed_area(outline) < 0:
        outline = list(reversed(outline))
    dense = resample(outline, 1.5)
    index = Outline(dense)
    features = []
    if bevel > spacing * 0.55:
        features += inset(resample(outline, spacing * 0.5), bevel)
    for line, width, depth in grooves:
        pts = resample(line, spacing * 0.45, closed=False)
        for i, q in enumerate(pts):
            features.append(q)
            a = pts[max(0, i - 1)]
            b = pts[min(len(pts) - 1, i + 1)]
            tx, ty = b[0] - a[0], b[1] - a[1]
            tl = math.hypot(tx, ty) or 1
            for s in (-1, 1):
                features.append((q[0] - ty / tl * width * s, q[1] + tx / tl * width * s))
    points, tris = mesh_polygon(outline, spacing, features)

    def thickness(x, y):
        d, q, _ = index.nearest((x, y))
        e = edge_at(*q) if edge_at else edge
        w = bevel_at(*q) if bevel_at else bevel
        h = half
        f = min(1.0, d / max(w, 1e-6))
        if profile == "round":
            f = math.sin(f * math.pi / 2)
        t = min(e, h) + (h - min(e, h)) * f
        for line, width, depth in grooves:
            gd, ga = polyline_distance((x, y), line)
            if gd < width:
                ends = smooth(ga / 0.1) * smooth((1 - ga) / 0.1)
                t -= depth * ends * (0.5 + 0.5 * math.cos(math.pi * gd / width))
        if taper:
            t *= taper(x, y)
        return max(t, 0.0)

    heights = [thickness(*q) for q in points]
    grad = {}

    def gradient(x, y):
        eps = 0.3
        return ((thickness(x + eps, y) - thickness(x - eps, y)) / (2 * eps),
                (thickness(x, y + eps) - thickness(x, y - eps)) / (2 * eps))

    for a, b, c in tris:
        corners = (a, b, c)
        cx = (points[a][0] + points[b][0] + points[c][0]) / 3
        cy = (points[a][1] + points[b][1] + points[c][1]) / 3
        slopes = []
        for i in corners:
            q = points[i]
            # Sample the slope a little toward this triangle's middle: a vertex on a bevel crease
            # then takes the normal of the face it belongs to, which is what keeps creases sharp.
            key = (i, round(cx, 2), round(cy, 2))
            if key not in grad:
                grad[key] = gradient(q[0] + (cx - q[0]) * 0.2, q[1] + (cy - q[1]) * 0.2)
            slopes.append(grad[key])
        for side in (1, -1):
            out = []
            for i, (gx, gy) in zip(corners, slopes):
                q = points[i]
                out.append(p.corner(to_model(q[0], q[1], z + side * heights[i]), (-gx, gy, side),
                                    (q[0] * uv_scale, q[1] * uv_scale)))
            if side > 0:
                p.tri(out[0], out[2], out[1])
            else:
                p.tri(out[0], out[1], out[2])

    # Side wall wherever the outline keeps any thickness.
    n = len(dense)
    step = 3
    ring = [dense[i] for i in range(0, n, step)]
    m = len(ring)
    for i in range(m):
        q0, q1 = ring[i], ring[(i + 1) % m]
        e0 = thickness(*q0)
        e1 = thickness(*q1)
        if e0 < 0.08 and e1 < 0.08:
            continue
        dx, dy = q1[0] - q0[0], q1[1] - q0[1]
        l = math.hypot(dx, dy) or 1
        normal = (dy / l, dx / l, 0.0)
        u0, u1 = i * 3 * uv_scale, (i + 1) * 3 * uv_scale
        a = p.corner(to_model(q0[0], q0[1], z + e0), normal, (u0, 0.0))
        b = p.corner(to_model(q1[0], q1[1], z + e1), normal, (u1, 0.0))
        c = p.corner(to_model(q1[0], q1[1], z - e1), normal, (u1, (e1 * 2) * uv_scale))
        d = p.corner(to_model(q0[0], q0[1], z - e0), normal, (u0, (e0 * 2) * uv_scale))
        p.quad(a, b, c, d)
    return p


# ---------------------------------------------------------------------------------------------
# Swept solids
# ---------------------------------------------------------------------------------------------

def frames(spine):
    """Rotation-minimising frames along a 3D polyline (model units)."""
    tangents = []
    for i in range(len(spine)):
        a = spine[max(0, i - 1)]
        b = spine[min(len(spine) - 1, i + 1)]
        tangents.append(norm(sub(b, a)))
    t0 = tangents[0]
    ref = (0.0, 0.0, 1.0) if abs(t0[2]) < 0.9 else (1.0, 0.0, 0.0)
    n0 = norm(cross(ref, t0))
    out = [(t0, n0, cross(t0, n0))]
    for i in range(1, len(spine)):
        t_prev, n_prev, _ = out[-1]
        t = tangents[i]
        axis = cross(t_prev, t)
        s = length(axis)
        if s > 1e-9:
            axis = mul(axis, 1 / s)
            angle = math.atan2(s, dot(t_prev, t))
            n = rotate(n_prev, axis, angle)
        else:
            n = n_prev
        n = norm(sub(n, mul(t, dot(n, t))))
        out.append((t, n, cross(t, n)))
    return out


def rotate(v, axis, angle):
    c, s = math.cos(angle), math.sin(angle)
    return add(add(mul(v, c), mul(cross(axis, v), s)), mul(axis, dot(axis, v) * (1 - c)))


def sweep(p, spine, section, segments, uv_v=None, cap_start=False, cap_end=False, closed=True,
          smooth_normals=True, uv_u=1.0):
    """Sweep a cross-section along a spine.

    section(i, a) -> (sx, sy) offsets along the frame's normal and binormal at station i, angle a.
    uv_v(i) -> texture v at station i. Normals are computed from the swept surface itself.
    """
    fr = frames(spine)
    rings = []
    for i, (c, (t, n, b)) in enumerate(zip(spine, fr)):
        ring = []
        for k in range(segments + (0 if closed else 1)):
            a = 2 * math.pi * k / segments if closed else math.pi * 2 * k / segments
            sx, sy = section(i, a)
            ring.append(add(c, add(mul(n, sx), mul(b, sy))))
        rings.append(ring)
    count = len(rings[0])
    # Surface normals from neighbouring points (central differences along both directions).
    def normal_at(i, k):
        r = rings[i]
        along = sub(rings[min(len(rings) - 1, i + 1)][k], rings[max(0, i - 1)][k])
        if closed:
            around = sub(r[(k + 1) % count], r[(k - 1) % count])
        else:
            around = sub(r[min(count - 1, k + 1)], r[max(0, k - 1)])
        nrm = cross(around, along)
        # Point away from the spine.
        if dot(nrm, sub(r[k], spine[i])) < 0:
            nrm = mul(nrm, -1)
        return norm(nrm)

    vs = [uv_v(i) if uv_v else i / (len(rings) - 1) for i in range(len(rings))]
    idx = []
    for i in range(len(rings)):
        row = []
        for k in range(count + (1 if closed else 0)):
            kk = k % count
            u = uv_u * k / segments
            row.append(p.corner(rings[i][kk], normal_at(i, kk), (u, vs[i])))
        idx.append(row)
    for i in range(len(rings) - 1):
        for k in range(len(idx[i]) - 1):
            p.quad(idx[i][k], idx[i][k + 1], idx[i + 1][k + 1], idx[i + 1][k])
    for cap, i, sign in ((cap_start, 0, -1), (cap_end, len(rings) - 1, 1)):
        if not cap:
            continue
        t = fr[i][0]
        centre = spine[i]
        normal = mul(t, sign)
        c = p.corner(centre, normal, (0.5, vs[i]))
        ring = [p.corner(rings[i][k], normal, (0.5 + 0.5 * math.cos(2 * math.pi * k / count),
                                                  vs[i] + 0.02 * math.sin(2 * math.pi * k / count)))
                for k in range(count)]
        for k in range(count):
            if sign > 0:
                p.tri(c, ring[k], ring[(k + 1) % count])
            else:
                p.tri(c, ring[(k + 1) % count], ring[k])
    return p


def box(p, centre, size, bevel=0.0, axes=None, uv_scale=1 / 110.0):
    """A bevelled block in reference pixels; axes rotate it (three unit vectors in pixel space)."""
    hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
    ax = axes or ((1, 0, 0), (0, 1, 0), (0, 0, 1))
    b = min(bevel, hx * 0.45, hy * 0.45, hz * 0.45)
    # A chamfered box: each face inset by the bevel, joined by chamfer strips.
    def world(x, y, z):
        v = add(add(mul(ax[0], x), mul(ax[1], y)), mul(ax[2], z))
        return to_model(centre[0] + v[0], centre[1] + v[1], centre[2] + v[2])

    def world_dir(x, y, z):
        v = add(add(mul(ax[0], x), mul(ax[1], y)), mul(ax[2], z))
        return (v[0], -v[1], v[2])

    faces = []
    for axis in range(3):
        for sign in (-1, 1):
            n = [0, 0, 0]
            n[axis] = sign
            u_axis, v_axis = [a for a in range(3) if a != axis]
            h = (hx, hy, hz)
            corners = []
            for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                c = [0.0, 0.0, 0.0]
                c[axis] = sign * h[axis]
                c[u_axis] = su * (h[u_axis] - b)
                c[v_axis] = sv * (h[v_axis] - b)
                corners.append(c)
            if sign * (1 if (axis != 1) else -1) < 0:
                corners.reverse()
            normal = world_dir(*n)
            ids = [p.corner(world(*c), normal, ((c[u_axis] + centre[0]) * uv_scale, (c[v_axis] + centre[1]) * uv_scale))
                   for c in corners]
            p.quad(*ids)
    if b > 0:
        # Chamfer strips along the 12 edges and triangles at the 8 corners.
        for sx in (-1, 1):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    pts = [world(sx * hx, sy * (hy - b), sz * (hz - b)),
                           world(sx * (hx - b), sy * hy, sz * (hz - b)),
                           world(sx * (hx - b), sy * (hy - b), sz * hz)]
                    normal = world_dir(sx, sy, sz)
                    ids = [p.corner(q, normal, (0.1, 0.1)) for q in pts]
                    p.tri(*ids)
        for axis in range(3):
            others = [a for a in range(3) if a != axis]
            h = (hx, hy, hz)
            for s1 in (-1, 1):
                for s2 in (-1, 1):
                    quad = []
                    for along in (-1, 1):
                        for which in (0, 1):
                            c = [0.0, 0.0, 0.0]
                            c[axis] = along * (h[axis] - b)
                            c[others[0]] = s1 * (h[others[0]] - (b if which else 0))
                            c[others[1]] = s2 * (h[others[1]] - (0 if which else b))
                            quad.append(c)
                    n = [0, 0, 0]
                    n[others[0]] = s1
                    n[others[1]] = s2
                    normal = world_dir(*n)
                    ids = [p.corner(world(*c), normal, (0.2, 0.2)) for c in (quad[0], quad[1], quad[3], quad[2])]
                    p.quad(*ids)
    return p


# ---------------------------------------------------------------------------------------------
# The Scepter
# ---------------------------------------------------------------------------------------------

STEEL = (0.93, 0.95, 0.98)
STEEL_SHADE = (0.80, 0.83, 0.87)
GOLD = (1.0, 1.0, 1.0)
DARK = (0.20, 0.21, 0.23)
GEM = (1.0, 1.0, 1.0)

# Main blade: the curved blade and the plate that runs down the left of the head behind the gold.
BLADE = [
    (709, 67), (695, 74), (686, 80), (669, 90), (652, 100), (635, 110), (618, 120), (602, 130), (588, 140),
    (575, 150), (564, 160), (553, 170), (544, 180), (535, 190), (526, 200), (519, 210), (512, 220),
    (506, 230), (499, 240), (489, 260), (479, 280), (471, 300), (463, 320), (455, 340), (450, 360),
    (445, 380), (438, 400), (431, 420), (425, 440), (420, 460), (414, 480), (409, 495), (403, 510),
    (397, 526), (406, 537), (409, 549), (403, 561), (393, 574), (386, 586), (381, 600), (379, 620),
    (377, 640), (376, 660), (375, 680), (376, 700), (379, 716), (386, 728), (393, 716), (399, 700),
    (405, 680), (411, 660), (416, 640), (421, 620), (427, 600), (434, 580), (441, 562), (446, 545),
    (447, 528), (446, 510), (445, 492), (446, 472), (449, 452), (453, 432), (459, 414), (468, 404),
    (490, 399), (514, 397), (527, 395), (520, 390), (516, 383), (515, 374), (515, 364), (518, 356),
    (524, 351), (533, 349), (541, 350), (546, 340), (555, 320), (565, 300), (575, 280), (585, 260),
    (595, 240), (606, 220), (617, 200), (629, 180), (642, 160), (656, 140), (671, 120), (686, 100),
    (695, 90), (703, 79)]
CUTTING_EDGE = [(709, 67), (703, 79), (695, 90), (686, 100), (671, 120), (656, 140), (642, 160), (629, 180),
                (617, 200), (606, 220), (595, 240), (585, 260), (575, 280), (565, 300), (555, 320),
                (546, 340), (541, 350)]
FULLER = [(618, 145), (592, 167), (562, 195), (534, 225), (509, 255)]

# Right fork: two prongs over the stone, running down its right side.
FORK = [
    (622, 322), (619, 340), (617, 360), (616, 375), (617, 388), (621, 376), (625, 360), (630, 342),
    (636, 327), (641, 318), (644, 330), (646, 350), (647, 375), (647, 400), (646, 425), (644, 445),
    (641, 462), (636, 480), (629, 500), (621, 520), (612, 540), (603, 558), (594, 575), (586, 590),
    (589, 570), (595, 550), (600, 530), (602, 510), (600, 490), (596, 470), (592, 450), (590, 430),
    (592, 415), (596, 400), (599, 390), (602, 380), (605, 368), (608, 352), (611, 340), (615, 330)]

# Cage bracket arched over the stone, hooking down on the right.
BRACKET = [
    (444, 506), (443, 470), (444, 440), (447, 420), (457, 407), (472, 401), (515, 400), (562, 401),
    (578, 408), (591, 422), (601, 438), (609, 456), (614, 474), (613, 486), (603, 490), (597, 476),
    (590, 458), (581, 443), (569, 431), (555, 423), (515, 421), (478, 421), (467, 427), (463, 440),
    (463, 470), (464, 506)]

# The folded brow block at the stone's upper left.
BROW = [(457, 431), (470, 423), (490, 424), (505, 436), (512, 455), (505, 472), (490, 480), (466, 481),
        (458, 468)]

# The claw that rises under the stone's right and the strut that runs down to the shaft. Control
# points, corner-cut into a smooth outline; the claw tip and the strut's foot stay sharp.
FRAME_POINTS = [
    (579, 523), (589, 532), (592, 552), (591, 574), (593, 588), (583, 602), (568, 618), (563, 640),
    (570, 662), (578, 682), (568, 706), (548, 726), (524, 752), (504, 780), (487, 812), (474, 845),
    (465, 878), (459, 915), (458, 955), (461, 1004), (453, 985), (451, 945), (453, 905), (459, 868),
    (468, 834), (479, 802), (492, 770), (506, 740), (518, 712), (527, 690), (531, 668), (533, 648),
    (540, 628), (553, 610), (566, 595), (571, 570), (573, 545)]
FRAME_SHARP = {0, 19}


def chaikin(points, rounds=2, sharp=()):
    """Corner cutting on a closed outline, leaving the listed corners untouched."""
    pts = [(p, i in sharp) for i, p in enumerate(points)]
    for _ in range(rounds):
        out = []
        n = len(pts)
        for i in range(n):
            (p, keep), (q, _) = pts[i], pts[(i + 1) % n]
            if keep:
                out.append((p, True))
            else:
                out.append(((p[0] * 0.75 + q[0] * 0.25, p[1] * 0.75 + q[1] * 0.25), False))
            (a, _), (b, keep_b) = pts[i], pts[(i + 1) % n]
            if not keep_b:
                out.append(((a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75), False))
        pts = out
    return [p for p, _ in pts]


FRAME = chaikin(FRAME_POINTS, 2, FRAME_SHARP)
FRAME_SLOT = [(560, 668), (548, 690), (531, 690)]

# A thin thorn curling up from the gold on the left.
THORN = [(386, 828), (380, 812), (373, 790), (368, 770), (366, 751), (371, 765), (377, 786), (383, 806),
         (390, 824)]

GEM_BOTTOM = (517.0, 541.0)
GEM_TOP = (549.0, 414.0)
GEM_WIDTH = 40.0
GEM_DEPTH = 27.0


def near_line(q, line, reach):
    return polyline_distance(q, line)[0] < reach


def build_blade():
    p = part("steel_blade", STEEL)

    def edge_at(x, y):
        # Blunt back and lower plate, a honed cutting edge on the inside curve.
        d = polyline_distance((x, y), CUTTING_EDGE)[0]
        return 0.35 + 3.2 * smooth(d / 7.0)

    def bevel_at(x, y):
        d, along = polyline_distance((x, y), CUTTING_EDGE)
        if d < 7:
            # Long grind along the inside curve, shorter toward the point.
            return 9 + 17 * smooth(along / 0.35)
        return 6.0

    def taper(x, y):
        tip = math.hypot(x - 709, y - 67)
        return 0.45 + 0.55 * smooth(tip / 170.0)

    slab(p, BLADE, half=6.2, bevel=6.0, edge_at=edge_at, bevel_at=bevel_at, spacing=12.0,
         grooves=[(FULLER, 5.5, 2.6)], taper=taper)


def build_fork():
    p = part("steel_fork", STEEL)

    def edge_at(x, y):
        tip = min(math.hypot(x - 622, y - 322), math.hypot(x - 641, y - 318), math.hypot(x - 586, y - 590))
        return 0.4 + 2.2 * smooth((tip - 4) / 26.0)

    slab(p, FORK, half=5.2, bevel=5.0, edge_at=edge_at, spacing=9.5, z=-1.5)


def build_bracket():
    p = part("steel_bracket", STEEL)
    slab(p, BRACKET, half=15.0, bevel=5.0, edge=9.5, spacing=7.0, z=1.0, profile="round")


def build_brow():
    p = part("steel_brow", STEEL_SHADE)
    slab(p, BROW, half=21.0, bevel=13.0, edge=9.0, spacing=6.0, z=2.0)


def build_frame():
    p = part("steel_frame", STEEL)

    def edge_at(x, y):
        if y < 540:
            return 1.2
        return 2.8

    slab(p, FRAME, half=6.4, bevel=5.0, edge_at=edge_at, spacing=10.0, z=-1.5, grooves=[(FRAME_SLOT, 3.2, 2.2)])


def build_thorn():
    p = part("steel_thorn", STEEL)
    slab(p, THORN, half=2.6, bevel=3.0, edge=0.4, spacing=3.5, z=-6.0)


def build_blocks():
    p = part("steel_block", STEEL_SHADE)
    c, s = math.cos(math.radians(-12)), math.sin(math.radians(-12))
    box(p, (497, 499, 6), (20, 22, 24), bevel=2.6, axes=((c, s, 0), (-s, c, 0), (0, 0, 1)))
    box(p, (455, 515, 0), (22, 18, 26), bevel=3.0)
    box(p, (453, 547, 2), (11, 14, 18), bevel=2.0)
    # Rungs between the frame and the shaft.
    for (x0, y0), (x1, y1), w in (((443, 726), (536, 688), 8.5), ((438, 752), (522, 727), 7.5),
                                  ((436, 826), (488, 810), 6.5)):
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        dx, dy = x1 - x0, y1 - y0
        l = math.hypot(dx, dy)
        ux, uy = dx / l, dy / l
        box(p, (cx, cy, -1.5), (l, w, 11), bevel=1.8, axes=((ux, uy, 0), (-uy, ux, 0), (0, 0, 1)))


def gem_frame():
    ax, ay = GEM_TOP[0] - GEM_BOTTOM[0], GEM_TOP[1] - GEM_BOTTOM[1]
    half = math.hypot(ax, ay) / 2
    axis = (ax / (2 * half), ay / (2 * half))
    centre = ((GEM_TOP[0] + GEM_BOTTOM[0]) / 2, (GEM_TOP[1] + GEM_BOTTOM[1]) / 2)
    side = (-axis[1], axis[0])
    return centre, axis, side, half


def egg_point(t, a, scale, centre, axis, side, half):
    """Point on the stone: t along the axis (-1 bottom, 1 top), a around it."""
    r = math.sqrt(max(0.0, 1 - t * t)) * (1 + 0.14 * t) * scale
    x = centre[0] + axis[0] * t * half * scale + side[0] * math.cos(a) * GEM_WIDTH * r
    y = centre[1] + axis[1] * t * half * scale + side[1] * math.cos(a) * GEM_WIDTH * r
    z = math.sin(a) * GEM_DEPTH * r
    return (x, y, z)


def build_gem():
    centre, axis, side, half = gem_frame()
    for group, scale, rings, segs in (("gem_shell", 1.0, 16, 22), ("gem_core", 0.58, 9, 12)):
        p = part(group, GEM)
        grid = []
        for i in range(rings + 1):
            t = -math.cos(math.pi * i / rings)
            row = []
            for k in range(segs + 1):
                a = 2 * math.pi * k / segs
                x, y, z = egg_point(t, a, scale, centre, axis, side, half)
                # Normal from the implicit egg: finite differences of neighbouring points.
                e = 1e-3
                t0, t1 = max(-1.0, t - e), min(1.0, t + e)
                pa = egg_point(t0, a, scale, centre, axis, side, half)
                pb = egg_point(t1, a, scale, centre, axis, side, half)
                qa = egg_point(t, a - e, scale, centre, axis, side, half)
                qb = egg_point(t, a + e, scale, centre, axis, side, half)
                du = (pb[0] - pa[0], -(pb[1] - pa[1]), pb[2] - pa[2])
                dv = (qb[0] - qa[0], -(qb[1] - qa[1]), qb[2] - qa[2])
                n = cross(dv, du)
                radial = (x - centre[0], -(y - centre[1]), z)
                if length(n) < 1e-9:
                    n = (axis[0] * t, -axis[1] * t, 0)
                if dot(n, radial) < 0:
                    n = mul(n, -1)
                row.append(p.corner(to_model(x, y, z), norm(n), (k / segs * 2.0, (t + 1) * 1.4)))
            grid.append(row)
        for i in range(rings):
            for k in range(segs):
                p.quad(grid[i][k], grid[i][k + 1], grid[i + 1][k + 1], grid[i + 1][k])


def build_cradle():
    p = part("gold_cradle", GOLD)
    pts = [(426, 530), (440, 532), (456, 534), (472, 536), (488, 538), (502, 541), (512, 545)]
    spine = [to_model(x, y, 0) for x, y in pts]
    sweep(p, spine, lambda i, a: (4.6 * S * math.cos(a), 11.0 * S * math.sin(a)), 10,
          uv_v=lambda i: i * 0.12, cap_start=True, cap_end=True)
    # The cup the stone sits in.
    centre, axis, side, half = gem_frame()
    cup = []
    for i, t in enumerate((-1.18, -1.05, -0.92, -0.84)):
        x = centre[0] + axis[0] * t * half
        y = centre[1] + axis[1] * t * half
        cup.append(to_model(x, y, 0))
    radii = (8.0, 13.5, 16.5, 15.0)
    q = part("gold_cup", GOLD)
    sweep(q, cup, lambda i, a: (radii[i] * S * math.cos(a), radii[i] * 0.8 * S * math.sin(a)), 16,
          uv_v=lambda i: i * 0.08, cap_start=True)


def build_coil():
    a = (514.0, 551.0)
    b = (466.0, 641.0)
    dx, dy = b[0] - a[0], b[1] - a[1]
    l = math.hypot(dx, dy)
    d = (dx / l, dy / l)
    u = (-d[1], d[0])
    turns = 9.5
    steps = int(turns * 10)
    helix = []
    for i in range(steps + 1):
        s = i / steps
        theta = 2 * math.pi * turns * s
        cx, cy = a[0] + dx * s, a[1] + dy * s
        helix.append(to_model(cx + u[0] * math.cos(theta) * 14.0, cy + u[1] * math.cos(theta) * 14.0,
                              math.sin(theta) * 14.0))
    p = part("dark_coil", DARK)
    sweep(p, helix, lambda i, ang: (3.3 * S * math.cos(ang), 3.3 * S * math.sin(ang)), 5,
          uv_v=lambda i: i * 0.05, cap_start=True, cap_end=True)
    core = part("dark_core", (0.12, 0.12, 0.13))
    rod = [to_model(a[0] + dx * s, a[1] + dy * s, 0) for s in (-0.02, 0.5, 1.04)]
    sweep(core, rod, lambda i, ang: (9.5 * S * math.cos(ang), 9.5 * S * math.sin(ang)), 12,
          uv_v=lambda i: i * 0.3)


SHAFT_RADIUS = [(600, 25.0), (1000, 27.0), (1200, 27.5), (1300, 30.0), (1400, 33.0), (1500, 38.0),
                (1600, 41.5), (1700, 46.0), (1780, 48.5), (1850, 48.5), (1880, 47.0)]
# The foot curls to a rounded toe on the right.
FOOT = [(1880, 471.0, 47.0), (1896, 478.0, 43.0), (1910, 486.0, 36.0), (1922, 494.0, 27.0),
        (1932, 500.0, 18.0), (1939, 505.0, 8.0)]
SHAFT_TOP = 630.0
SHAFT_BOTTOM = 1942.0


def build_shaft():
    stations = []
    y = SHAFT_TOP
    while y < 1880:
        stations.append((y, interp(SHAFT_SPINE, y), interp(SHAFT_RADIUS, y)))
        y += 34 if 1050 < y < 1480 else 22
    stations += FOOT
    spine = [to_model(x, y, 0) for y, x, r in stations]

    def flatten(y):
        return 1.0 - 0.28 * smooth((y - 1480) / 260.0)

    def section(i, a):
        y, x, r = stations[i]
        a -= math.pi / 2
        return (r * S * math.cos(a), r * flatten(y) * S * math.sin(a))

    p = part("shaft_gold", GOLD)
    sweep(p, spine, section, 16, uv_v=lambda i: (stations[i][0] - SHAFT_TOP) / (SHAFT_BOTTOM - SHAFT_TOP),
          cap_start=True, cap_end=True)


PLATES = [
    # top, lower edge at the right end, lower edge at the left end, (tongue angle, drop), radius offset,
    # angular span. Angles: 0 right, 90 front, 180 left, 270 back. The lower edges run diagonally
    # across the front like wrapped sheaths, each ending in a point.
    (548, 640, 700, (262, 18), 8.5, (95, 300)),
    (648, 712, 796, (206, 26), 7.0, (-62, 250)),
    (742, 804, 886, (200, 30), 5.5, (-58, 252)),
    (832, 896, 972, (196, 24), 4.0, (-54, 250)),
    (918, 1030, 990, (38, 22), 2.5, (-50, 248)),
]
FACET = 44.0
PLATE_THICKNESS = 2.4


def build_plates():
    """Overlapping gold armour: folded, faceted plates with tongues on their lower edges."""
    for n, (top, right, left, (tongue, drop), offset, (a0, a1)) in enumerate(PLATES):
        p = part(f"gold_plate_{n}", GOLD)
        facets = max(3, int(round((a1 - a0) / FACET)))
        # Snap the tongue to a facet boundary so its point is a sharp fold.
        step = (a1 - a0) / facets
        tongue = a0 + round((tongue - a0) / step) * step
        bottom = max(right, left)

        def lower(angle):
            f = (angle - a0) / (a1 - a0)
            edge = right + (left - right) * smooth(f)
            return edge + drop * max(0.0, 1 - abs(angle - tongue) / (step * 1.2))

        def upper(angle):
            return top + 6 * math.sin(math.radians(angle) * 1.3)

        def point(angle, y, inward=0.0):
            cx = interp(SHAFT_SPINE, y)
            flare = 1 + 0.08 * (y - top) / (bottom - top)
            # The armour stands proud on the left, where its tongues fan out in the reference.
            left_flare = 11.0 * max(0.0, math.cos(math.radians(angle - 185))) ** 2 * (y - top) / (bottom - top)
            r = (interp(SHAFT_RADIUS, y) + offset + left_flare) * flare - inward
            rad = math.radians(angle)
            return (cx + math.cos(rad) * r, y, math.sin(rad) * r)

        def model(q):
            return to_model(*q)

        angles = [a0 + step * k for k in range(facets + 1)]
        for k in range(facets):
            al, ar = angles[k], angles[k + 1]
            rows = 2
            cols = []
            for ang in (al, ar):
                yt, yb = upper(ang), lower(ang)
                cols.append([point(ang, yt + (yb - yt) * i / rows) for i in range(rows + 1)])
            # Flat facet: one normal from the facet's diagonals.
            d1 = sub(model(cols[1][rows]), model(cols[0][0]))
            d2 = sub(model(cols[0][rows]), model(cols[1][0]))
            normal = norm(cross(d1, d2))
            mid = model(point((al + ar) / 2, (top + bottom) / 2))
            centre = model((interp(SHAFT_SPINE, (top + bottom) / 2), (top + bottom) / 2, 0))
            if dot(normal, sub(mid, centre)) < 0:
                normal = mul(normal, -1)
            uv = lambda q: (q[0] / 60.0 + q[2] / 60.0, q[1] / 60.0)
            for i in range(rows):
                ids = [p.corner(model(q), normal, uv(q)) for q in (cols[0][i], cols[1][i], cols[1][i + 1], cols[0][i + 1])]
                p.quad(*ids)
            # Inner face, darker by occlusion, seen only under the lower edge.
            inner = [point(al, upper(al), PLATE_THICKNESS), point(ar, upper(ar), PLATE_THICKNESS),
                     point(ar, lower(ar), PLATE_THICKNESS), point(al, lower(al), PLATE_THICKNESS)]
            ids = [p.corner(model(q), mul(normal, -1), uv(q)) for q in inner]
            p.quad(ids[0], ids[3], ids[2], ids[1])
            # Bright ground bevel along the lower edge.
            e0, e1 = cols[0][rows], cols[1][rows]
            i0 = point(al, lower(al), PLATE_THICKNESS)
            i1 = point(ar, lower(ar), PLATE_THICKNESS)
            bevel = norm(add(normal, (0, -0.9, 0)))
            ids = [p.corner(model(q), bevel, uv(q)) for q in (e0, e1, i1, i0)]
            p.quad(*ids)
        # Side edges.
        for ang, sign in ((a0, -1), (a1, 1)):
            rad = math.radians(ang)
            normal = (-math.sin(rad) * sign, 0.0, math.cos(rad) * sign)
            q = [point(ang, upper(ang)), point(ang, lower(ang)), point(ang, lower(ang), PLATE_THICKNESS),
                 point(ang, upper(ang), PLATE_THICKNESS)]
            ids = [p.corner(model(x), normal, (0.1, x[1] / 60.0)) for x in q]
            p.quad(*ids)


# ---------------------------------------------------------------------------------------------
# Ambient occlusion: short rays through a coarse occupancy grid of every part
# ---------------------------------------------------------------------------------------------

def bake_occlusion():
    cell = 4.0 * S
    occupied = set()

    def key(q):
        return (int(math.floor(q[0] / cell)), int(math.floor(q[1] / cell)), int(math.floor(q[2] / cell)))

    for p in PARTS:
        for q in p.positions:
            occupied.add(key(q))
        for a, b, c in p.tris:
            pa, pb, pc = p.positions[a[0]], p.positions[b[0]], p.positions[c[0]]
            for w in ((1 / 3, 1 / 3, 1 / 3), (.5, .5, 0), (0, .5, .5), (.5, 0, .5)):
                occupied.add(key(tuple(pa[i] * w[0] + pb[i] * w[1] + pc[i] * w[2] for i in range(3))))
    rng = random.Random(7)
    dirs = []
    while len(dirs) < 18:
        v = (rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(-1, 1))
        if 0.2 < length(v) <= 1:
            dirs.append(norm(v))
    start, reach, step = 7.0 * S, 46.0 * S, 3.0 * S
    result = []
    for p in PARTS:
        # Average the corner normals of each position.
        sums = [(0.0, 0.0, 0.0)] * len(p.positions)
        for tri in p.tris:
            for (pi, ni, _) in tri:
                sums[pi] = add(sums[pi], p.normals[ni])
        shade = []
        for q, n in zip(p.positions, sums):
            n = norm(n)
            hits = 0.0
            total = 0.0
            for d in dirs:
                c = dot(d, n)
                if c <= 0.05:
                    d = sub(d, mul(n, 2 * c))
                    c = -c
                    if c <= 0.05:
                        continue
                total += c
                t = start
                while t < reach:
                    if key(add(q, mul(d, t))) in occupied:
                        hits += c * (1 - t / reach) ** 0.5
                        break
                    t += step
            ao = 1 - 0.85 * (hits / total if total else 0)
            shade.append(max(0.25, min(1.0, ao)))
        result.append(shade)
    return result


# ---------------------------------------------------------------------------------------------
# Textures (tileable except the shaft, which is mapped once along its length)
# ---------------------------------------------------------------------------------------------

def value_noise(size_x, size_y, cells_x, cells_y, seed):
    rng = random.Random(seed)
    grid = [[rng.random() for _ in range(cells_x)] for _ in range(cells_y)]

    def sample(x, y):
        fx = x / size_x * cells_x
        fy = y / size_y * cells_y
        ix, iy = int(math.floor(fx)), int(math.floor(fy))
        tx, ty = fx - ix, fy - iy
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = grid[iy % cells_y][ix % cells_x]
        b = grid[iy % cells_y][(ix + 1) % cells_x]
        c = grid[(iy + 1) % cells_y][ix % cells_x]
        d = grid[(iy + 1) % cells_y][(ix + 1) % cells_x]
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    return sample


def write_png(path, width, height, pixel):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            r, g, b, a = pixel(x, y)
            raw += bytes((max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))),
                          max(0, min(255, int(a)))))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    # Smooth sampling up close; the staff fills much of the screen in first person.
    Path(str(path) + ".mcmeta").write_text('{"texture": {"blur": true}}\n', encoding="utf-8")


def steel_texture():
    fine = value_noise(256, 256, 128, 128, 11)
    mid = value_noise(256, 256, 24, 24, 12)
    low = value_noise(256, 256, 6, 6, 13)
    rng = random.Random(14)
    pits = {(rng.randrange(256), rng.randrange(256)) for _ in range(420)}

    def px(x, y):
        v = 182 + 16 * (fine(x, y) - .5) + 14 * (mid(x, y) - .5) + 12 * (low(x, y) - .5)
        if (x, y) in pits:
            v -= 38
        return (v * 0.96, v * 0.99, v * 1.04, 255)
    write_png(TEXTURES / "steel.png", 256, 256, px)


def gold_texture(name, width, height, seed, lines=()):
    brush = value_noise(width, height, max(4, width // 2), max(4, height // 32), seed)
    fine = value_noise(width, height, width // 2, height // 2, seed + 1)
    grime = value_noise(width, height, max(2, width // 40), max(2, height // 40), seed + 2)

    def px(x, y):
        v = 1 + 0.07 * (brush(x, y) - .5) + 0.05 * (fine(x, y) - .5) - 0.10 * max(0.0, grime(x, y) - .45)
        r, g, b = 226 * v, 170 * v, 76 * v
        for draw in lines:
            shade = draw(x, y)
            if shade:
                r, g, b = r * shade, g * shade, b * shade
        return (r, g, b, 255)
    write_png(TEXTURES / name, width, height, px)


def shaft_lines():
    """Engraved panel lines of the reference, in shaft texture space (u around, v along)."""
    W, H = 128, 1024

    def v_of(y):
        return (y - SHAFT_TOP) / (SHAFT_BOTTOM - SHAFT_TOP) * H

    # u: 0 back, 0.25 right, 0.5 front, 0.75 left.
    paths = [
        [(0.62, 1045), (0.60, 1120), (0.66, 1150), (0.64, 1235), (0.58, 1270), (0.60, 1480), (0.70, 1560)],
        [(0.40, 1060), (0.43, 1210), (0.38, 1300), (0.40, 1420), (0.47, 1520), (0.40, 1600)],
        # The shield panel on the pommel and the sole line.
        [(0.30, 1560), (0.36, 1640), (0.42, 1760), (0.47, 1860), (0.55, 1905), (0.68, 1880),
         (0.74, 1780), (0.72, 1640), (0.66, 1540)],
        [(0.20, 1890), (0.45, 1912), (0.75, 1905)],
    ]
    segs = []
    for path in paths:
        pts = [(u * W, v_of(y)) for u, y in path]
        segs += list(zip(pts, pts[1:]))

    def draw(x, y):
        best = 9.0
        for (ax, ay), (bx, by) in segs:
            if not (min(ay, by) - 3 <= y <= max(ay, by) + 3):
                continue
            dx, dy = bx - ax, by - ay
            l2 = dx * dx + dy * dy
            t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / l2)) if l2 else 0.0
            d = math.hypot(x - (ax + dx * t), y - (ay + dy * t))
            best = min(best, d)
        if best < 1.0:
            return 0.45
        if best < 1.9:
            return 1.18
        return None
    return draw


def gem_texture():
    rng = random.Random(21)
    cols, rows = 16, 16
    centres = []
    for j in range(rows):
        for i in range(cols):
            centres.append(((i + (0.5 if j % 2 else 0.0) + rng.uniform(-.12, .12)) * 16,
                            (j + rng.uniform(-.12, .12)) * 16, rng.random()))
    glow = value_noise(256, 256, 8, 8, 22)

    def px(x, y):
        best, second, energy = 1e9, 1e9, 0.0
        for cx, cy, e in centres:
            dx = (x - cx + 128) % 256 - 128
            dy = (y - cy + 128) % 256 - 128
            d = dx * dx + dy * dy
            if d < best:
                second, best, energy = best, d, e
            elif d < second:
                second = d
        edge = math.sqrt(second) - math.sqrt(best)
        rim = max(0.0, 1 - edge / 3.2)
        body = 0.45 + 0.55 * energy
        wave = glow(x, y)
        r = 18 + 70 * rim + 30 * wave * body
        g = 96 + 120 * rim + 70 * wave * body
        b = 215 + 40 * rim + 20 * body
        return (r, g, b, 255)
    write_png(TEXTURES / "gem.png", 256, 256, px)


def glow_texture():
    """The stone's halo: a soft radial bloom with a faint four-point glint."""
    size = 64

    def px(x, y):
        dx, dy = (x + .5) / size * 2 - 1, (y + .5) / size * 2 - 1
        r2 = dx * dx + dy * dy
        body = math.exp(-r2 * 5.5)
        star = math.exp(-abs(dx) * 22 - dy * dy * 3.5) + math.exp(-abs(dy) * 22 - dx * dx * 3.5)
        edge = max(0.0, 1 - math.sqrt(r2)) ** 1.5
        v = min(1.0, body + .35 * star * edge) * edge
        return (255 * v, 255 * v, 255 * v, 255 * v)
    write_png(TEXTURES / "glow.png", size, size, px)


# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------

def write_obj(shades):
    lines = ["# Loki Scepter - solid bevelled model generated by scripts/generate_scepter.py",
             "# grip=(0,0,0); +Y runs from the hand toward the stone; v carries tint x occlusion"]
    vbase = tbase = nbase = 1
    for p, shade in zip(PARTS, shades):
        lines.append("g " + p.group)
        for q, s in zip(p.positions, shade):
            r, g, b = (c * s for c in p.tint)
            lines.append(f"v {q[0]:.6f} {q[1]:.6f} {q[2]:.6f} {r:.3f} {g:.3f} {b:.3f}")
        for u, v in p.uvs:
            lines.append(f"vt {u:.4f} {v:.4f}")
        for n in p.normals:
            lines.append(f"vn {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}")
        for tri in p.tris:
            lines.append("f " + " ".join(f"{pi + vbase}/{ti + tbase}/{ni + nbase}" for pi, ni, ti in tri))
        vbase += len(p.positions)
        tbase += len(p.uvs)
        nbase += len(p.normals)
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    MODEL.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    build_blade()
    build_fork()
    build_bracket()
    build_brow()
    build_frame()
    build_thorn()
    build_blocks()
    build_gem()
    build_cradle()
    build_coil()
    build_shaft()
    build_plates()
    shades = bake_occlusion()
    write_obj(shades)
    steel_texture()
    gold_texture("gold.png", 256, 256, 31)
    gold_texture("shaft.png", 128, 1024, 41, lines=[shaft_lines()])
    gem_texture()
    glow_texture()
    tris = sum(len(p.tris) for p in PARTS)
    ys = [q[1] for p in PARTS for q in p.positions]
    print(f"Scepter: {len(PARTS)} parts, {tris} triangles, y={min(ys):.3f}..{max(ys):.3f}")
    for p in PARTS:
        print(f"  {p.group:16s} {len(p.tris):5d} tris")


if __name__ == "__main__":
    main()
