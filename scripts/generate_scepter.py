"""Generate a screen-proportioned 360-degree MCU Loki Scepter mesh.

The grip centre is (0, 0, 0). +Y runs toward the head. The proportions below were
blocked directly against the supplied prop reference: authored span is 1.55 units,
so WeaponRenderer's 0.72 held scale produces an approximately 1.12-block weapon.
All faces are authored quads because AuthoredMesh only accepts OBJ quads.
"""
from pathlib import Path
import math, struct, zlib

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "src/main/resources/assets/hexgodofstories"

# Vanilla's one-texture OBJ path cannot provide true metallic/roughness maps, so the atlas bakes
# restrained variation and wear into distinct warm/cold material bands. Crystal groups get a
# second full-bright pass in WeaponRenderer.
COLORS = {
    "gold":       (142, 96, 30, 255),
    "gold_edge":  (211, 163, 63, 255),
    "gold_dark":  (70, 45, 18, 255),
    "bronze":     (103, 66, 23, 255),
    "steel":      (128, 136, 139, 255),
    "steel_edge": (212, 218, 219, 255),
    "steel_dark": (67, 73, 76, 255),
    "gunmetal":   (38, 42, 45, 255),
    "black":      (20, 21, 23, 255),
    "gem_blue":   (8, 62, 198, 205),
    "gem_cyan":   (18, 145, 248, 215),
    "gem_white":  (184, 242, 255, 230),
}
MATERIALS = list(COLORS)
vertices, faces = [], []

def quad(name, pts, mat):
    if len(pts) == 3:
        pts = pts + [pts[-1]]
    base = len(vertices)
    vertices.extend(pts)
    faces.append((name, list(range(base + 1, base + 5)), mat))

def box(name, cx, cy, cz, sx, sy, sz, mat, bevel=0.0):
    x0,x1=cx-sx/2,cx+sx/2; y0,y1=cy-sy/2,cy+sy/2; z0,z1=cz-sz/2,cz+sz/2
    p=[(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),
       (x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]
    for ids in ((0,1,2,3),(4,7,6,5),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)):
        quad(name,[p[i] for i in ids],mat)

def _smooth(points, steps=5):
    out=[]
    for i in range(len(points)-1):
        a,b,c,d=points[max(0,i-1)],points[i],points[i+1],points[min(len(points)-1,i+2)]
        for j in range(steps):
            t=j/steps
            out.append(tuple(.5*((2*b[k])+(-a[k]+c[k])*t+
                                  (2*a[k]-5*b[k]+4*c[k]-d[k])*t*t+
                                  (-a[k]+3*b[k]-3*c[k]+d[k])*t*t*t)
                             for k in range(len(b))))
    out.append(points[-1])
    return out

def tube(name, centres, rx, rz, mat, sides=12, twist=0.0):
    if not isinstance(rx,(list,tuple)): rx=[rx]*len(centres)
    elif len(rx)!=len(centres):
        a,b=rx[0],rx[-1]; rx=[a+(b-a)*(i/max(1,len(centres)-1)) for i in range(len(centres))]
    if not isinstance(rz,(list,tuple)): rz=[rz]*len(centres)
    elif len(rz)!=len(centres):
        a,b=rz[0],rz[-1]; rz=[a+(b-a)*(i/max(1,len(centres)-1)) for i in range(len(centres))]
    rings=[]
    for i,(x,y,z) in enumerate(centres):
        a=centres[max(0,i-1)]; b=centres[min(len(centres)-1,i+1)]
        dx,dy,dz=b[0]-a[0],b[1]-a[1],b[2]-a[2]
        ln=max(1e-8,math.hypot(dx,dy)); nx,ny=dy/ln,-dx/ln
        ring=[]
        for s in range(sides):
            ang=s*2*math.pi/sides + twist*i
            lateral=rx[i]*math.cos(ang)
            depth=rz[i]*math.sin(ang)
            ring.append((x+nx*lateral,y+ny*lateral,z+depth))
        rings.append(ring)
    for a,b in zip(rings,rings[1:]):
        for j in range(sides):
            quad(name,[a[j],a[(j+1)%sides],b[(j+1)%sides],b[j]],mat)
    for ring,reverse in ((rings[0],True),(rings[-1],False)):
        c=tuple(sum(p[k] for p in ring)/len(ring) for k in range(3))
        for j in range(sides):
            tri=[c,ring[(j+1)%sides],ring[j]] if reverse else [c,ring[j],ring[(j+1)%sides]]
            quad(name,tri,mat)

def plate_strip(name, centres, widths, depths, mat, side=1, offset=0.0):
    rings=[]
    for i,(x,y,z) in enumerate(centres):
        a=centres[max(0,i-1)]; b=centres[min(len(centres)-1,i+1)]
        dx,dy=b[0]-a[0],b[1]-a[1]; ln=max(1e-8,math.hypot(dx,dy)); nx,ny=dy/ln,-dx/ln
        w=widths[i]; d=depths[i]
        lift=side*(w*.60+offset)
        cx=x+nx*lift
        cy=y+ny*lift
        rings.append([(cx-nx*w/2,cy-ny*w/2,z-d),(cx+nx*w/2,cy+ny*w/2,z-d),
                      (cx+nx*w/2,cy+ny*w/2,z+d),(cx-nx*w/2,cy-ny*w/2,z+d)])
    for a,b in zip(rings,rings[1:]):
        for j in range(4):
            quad(name,[a[j],a[(j+1)%4],b[(j+1)%4],b[j]],mat)

def blade(name, rows, side_mats=("steel_edge","steel","steel_dark"), sides=8):
    sr=_smooth(rows,4)
    rings=[]
    for outer,inner,y,depth in sr:
        w=inner-outer
        rings.append([
            (outer,y,0),
            (outer+w*.10,y, depth*.58),
            (outer+w*.28,y, depth),
            (outer+w*.62,y, depth*.84),
            (inner,y, depth*.28),
            (inner,y,-depth*.28),
            (outer+w*.62,y,-depth*.84),
            (outer+w*.28,y,-depth),
            (outer+w*.10,y,-depth*.58),
        ])
    for a,b in zip(rings,rings[1:]):
        n=len(a)
        for j in range(n):
            if j in (0,8): mat=side_mats[0]
            elif j in (3,4,5,6): mat=side_mats[2] if j in (4,5) else side_mats[1]
            else: mat=side_mats[1]
            quad(name,[a[j],a[(j+1)%n],b[(j+1)%n],b[j]],mat)
    for ring in (rings[0],rings[-1]):
        c=tuple(sum(p[k] for p in ring)/len(ring) for k in range(3))
        for j in range(len(ring)):
            quad(name,[c,ring[j],ring[(j+1)%len(ring)]],side_mats[1])

def rail(name, points, radius, mat, depth_scale=.72, sides=8):
    path=_smooth(points,4)
    tube(name,path,[radius]*len(path),[radius*depth_scale]*len(path),mat,sides)

# ---------------------------------------------------------------------------
# Reference-traced geometry.
#
# The supplied prop photograph is 1000x2000.  The visible prop bounds are
# approximately x=364..710 and y=69..1942.  The helper below converts traced
# front-view pixel coordinates into model space while keeping the hand grip at
# y ~= 0 (reference y ~= 890).  This avoids the previous procedural "spear"
# silhouette: the big crescent starts around reference y=400, NOT down at the
# grip.
# ---------------------------------------------------------------------------
REF_SCALE = 1.55 / (1942 - 69)
def ref_xy(px, py):
    return ((px - 430) * REF_SCALE, (1942 - py) * REF_SCALE - .87)

def _poly_area(poly):
    return .5 * sum(poly[i][0]*poly[(i+1)%len(poly)][1] -
                    poly[(i+1)%len(poly)][0]*poly[i][1] for i in range(len(poly)))

def _cross2(a,b,c):
    return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])

def _inside_tri(p,a,b,c):
    c1=_cross2(a,b,p); c2=_cross2(b,c,p); c3=_cross2(c,a,p)
    neg=(c1 < -1e-10) or (c2 < -1e-10) or (c3 < -1e-10)
    pos=(c1 >  1e-10) or (c2 >  1e-10) or (c3 >  1e-10)
    return not (neg and pos)

def _triangulate(poly):
    # Small pure-Python ear clipper so traced concave prop silhouettes stay
    # concave instead of being filled as generic wedges.
    idx=list(range(len(poly))); out=[]
    orient=1 if _poly_area(poly)>0 else -1
    guard=0
    while len(idx)>3 and guard<10000:
        guard+=1; found=False
        for k in range(len(idx)):
            i0=idx[k-1]; i1=idx[k]; i2=idx[(k+1)%len(idx)]
            a,b,c=poly[i0],poly[i1],poly[i2]
            if orient*_cross2(a,b,c) <= 1e-10:
                continue
            if any(_inside_tri(poly[j],a,b,c) for j in idx if j not in (i0,i1,i2)):
                continue
            out.append((i0,i1,i2)); idx.pop(k); found=True; break
        if not found:
            # Traces are hand-cleaned and should not hit this, but fall back to
            # a fan rather than breaking CI if a future point becomes collinear.
            return [(0,i,i+1) for i in range(1,len(poly)-1)]
    if len(idx)==3: out.append(tuple(idx))
    return out

def extrude_poly(name, pixel_points, depth, front_mat, side_mat=None, zcenter=0.0):
    side_mat = side_mat or front_mat
    poly=[ref_xy(x,y) for x,y in pixel_points]
    zf=zcenter+depth*.5; zb=zcenter-depth*.5
    tris=_triangulate(poly)
    # Front and back are both real surfaces; this is not a one-sided card.
    for a,b,c in tris:
        quad(name,[(poly[a][0],poly[a][1],zf),
                   (poly[b][0],poly[b][1],zf),
                   (poly[c][0],poly[c][1],zf)],front_mat)
        quad(name,[(poly[c][0],poly[c][1],zb),
                   (poly[b][0],poly[b][1],zb),
                   (poly[a][0],poly[a][1],zb)],side_mat)
    for i in range(len(poly)):
        j=(i+1)%len(poly)
        quad(name,[(poly[i][0],poly[i][1],zb),
                   (poly[j][0],poly[j][1],zb),
                   (poly[j][0],poly[j][1],zf),
                   (poly[i][0],poly[i][1],zf)],side_mat)

def ref_rail(name, pixel_points, z, radius, mat, depth_scale=.65):
    rail(name,[(ref_xy(x,y)[0],ref_xy(x,y)[1],z) for x,y in pixel_points],
         radius,mat,depth_scale,8)

# --- Main gold shaft and counterweight: traced from the actual prop silhouette.
# This replaces the old skinny round tube.  It is deliberately a flattened,
# thick solid with the same long curve and widening counterweight as the prop.
shaft_outline=[
    (398,850),(430,865),(445,885),(446,980),(447,1100),(452,1250),
    (460,1450),(468,1510),(485,1600),(500,1740),(515,1875),(512,1905),
    (495,1925),(470,1940),(445,1930),(420,1905),(405,1870),(395,1800),
    (390,1680),(388,1550),(389,1400),(390,1250),(392,1100),(395,970),(395,900)
]
extrude_poly("gold_shaft_body",shaft_outline,.055,"gold","bronze")

# Raised/front armour leaves and the diagonal overlaps visible on the reference.
extrude_poly("gold_upper_leaf",[
    (430,525),(470,530),(490,550),(500,620),(480,670),(460,725),
    (440,760),(420,735),(400,690),(397,620),(408,565)
],.035,"gold_edge","bronze",.032)
extrude_poly("gold_mid_leaf",[
    (405,700),(440,755),(446,842),(432,890),(405,878),(382,835),(378,780)
],.030,"gold","gold_dark",.030)
extrude_poly("gold_side_flange",[
    (438,875),(455,900),(465,980),(460,1030),(447,1025),(442,960)
],.028,"gold_edge","bronze",.026)

# Actual shaft panel lines.  These sit proud/recessed instead of being painted
# straight stripes on a cylinder.
for n,path in enumerate([
    [(403,852),(430,875),(444,890)],
    [(397,990),(420,1028),(438,1062)],
    [(395,1110),(420,1145),(438,1178)],
    [(398,1260),(430,1298),(452,1320)],
    [(405,1500),(440,1550),(468,1590)],
    [(428,1865),(470,1885),(510,1888)]
]):
    ref_rail("shaft_seam_"+str(n),path,.057,.0027,"gold_dark",.48)
ref_rail("shaft_highlight",[(441,930),(442,1130),(448,1360),(462,1530),(492,1780)],.059,.0020,"gold_edge",.42)

# --- Black ribbed throat directly beneath the crystal.
extrude_poly("black_throat",[
    (430,520),(470,522),(492,548),(493,592),(480,628),(457,650),
    (438,630),(428,590)
],.052,"black","gunmetal")
for yy in range(540,631,9):
    ref_rail("black_neck_rib",[(440,yy),(480,yy+7)],.030,.0028,"gunmetal",.50)

# --- Huge crescent blade.
# These points are traced against the supplied front prop image.  Notice that
# the blade root begins around y=400 and curves hard away from the crystal;
# this is the key silhouette the prior model got wrong.
main_crescent=[
    (710,69),(650,100),(590,140),(545,185),(510,235),(480,295),(458,350),(445,400),
    (490,400),(515,390),(525,372),(523,354),(535,347),(550,333),(565,305),
    (585,270),(610,220),(635,170),(660,125),(685,90)
]
extrude_poly("blade_main",main_crescent,.064,"steel","steel_dark")

# Bevel/cutting edge and the long recessed channel seen in the prop.
ref_rail("blade_main_edge",[(704,76),(650,104),(592,145),(548,190),(513,240),(483,300),(460,355),(448,397)],.036,.0048,"steel_edge",.52)
ref_rail("blade_main_channel",[(674,106),(627,142),(584,182),(548,228),(520,278),(496,327),(480,365)],.038,.0040,"steel_dark",.48)

# Left-side silver support that continues down from the crescent and wraps the
# crystal/upper gold armour instead of pretending the giant blade reaches the grip.
left_support=[
    (445,395),(472,398),(486,415),(480,440),(463,470),(450,510),(440,555),
    (429,610),(417,660),(407,700),(392,700),(385,673),(389,630),(395,585),
    (405,540),(416,500),(424,458),(430,420)
]
extrude_poly("left_silver_support",left_support,.056,"steel","steel_dark")

# Right silver frame, shaped around (not across) the crystal.
right_frame=[
    (555,398),(580,404),(590,430),(582,470),(570,510),(555,555),(540,595),
    (525,635),(525,655),(540,670),(535,700),(520,730),(505,770),(490,815),
    (485,840),(505,812),(530,775),(555,735),(580,705),(588,680),(570,655),
    (560,642),(575,605),(590,565),(608,520),(620,475),(628,430),(625,400),
    (615,382),(600,390),(590,408),(575,402)
]
extrude_poly("right_silver_frame",right_frame,.060,"steel","steel_dark")

# The two deliberately unequal prongs from the reference.  They are separate
# solids with a real V-shaped gap between them.
extrude_poly("fork_short",[
    (598,404),(594,384),(594,360),(600,336),(607,320),(614,318),
    (612,344),(609,370),(611,395)
],.050,"steel","steel_dark")
extrude_poly("fork_tall",[
    (611,402),(615,378),(621,354),(630,330),(639,314),(646,320),
    (647,350),(646,388),(642,420)
],.054,"steel","steel_dark")
ref_rail("fork_outer_edge",[(644,322),(646,360),(644,410),(636,458),(624,505),(610,548)],.033,.0038,"steel_edge",.50)

# Small lower steel keel and the three open rear/side braces shown between the
# upper gold armour and the long right frame.
extrude_poly("lower_silver_keel",[
    (447,650),(470,646),(490,660),(492,692),(478,720),(465,746),(450,760),
    (443,742),(448,710)
],.045,"steel","steel_dark")
for path in [[(448,688),(505,666)],[(444,720),(500,695)],[(438,752),(493,724)]]:
    ref_rail("silver_open_brace",path,.034,.0040,"steel_edge",.50)

# --- Crystal: larger, elongated, irregular and physically deep in the cage.
cx,cy=ref_xy(520,470)
rings=[]
lat_steps=16; lon_steps=20
for i in range(lat_steps+1):
    lat=-math.pi/2 + math.pi*i/lat_steps
    rr=math.cos(lat)
    taper=.82 + .18*((math.sin(lat)+1)/2)
    ring=[]
    for j in range(lon_steps):
        lon=j*2*math.pi/lon_steps
        # 134x150-ish reference footprint, with a subtle asymmetrical lean.
        x=cx + .055*taper*rr*math.cos(lon) + .004*math.sin(lat*1.7)
        y=cy + .064*math.sin(lat)
        z=.052*rr*math.sin(lon)
        ring.append((x,y,z))
    rings.append(ring)
for i in range(lat_steps):
    for j in range(lon_steps):
        sig=(i*19+j*13)%47
        mat="gem_white" if sig in (0,1) else "gem_cyan" if (i*7+j*5)%10 in (0,1,2) else "gem_blue"
        quad("gem_blue_facets",[rings[i][j],rings[i][(j+1)%lon_steps],
                                rings[i+1][(j+1)%lon_steps],rings[i+1][j]],mat)
for path in [
    [(488,430),(514,458),(538,492)],
    [(505,414),(525,452),(548,510)],
    [(535,426),(526,470),(516,520)]
]:
    ref_rail("gem_glint_fracture",path,.054,.0018,"gem_white",.45)

# Front and rear claw cage.  Gaps are intentional so the stone reads as a
# mounted object, not a blue texture pasted into a silver spear.
for z,mat in ((.070,"steel_edge"),(-.070,"steel_dark")):
    ref_rail("gem_claw_left",[(454,430),(445,470),(450,515),(470,540)],z,.0075,mat,.62)
    ref_rail("gem_claw_top",[(458,405),(500,398),(545,410),(570,435)],z,.0075,mat,.62)
    ref_rail("gem_claw_right",[(574,430),(586,465),(580,505),(565,535)],z,.0070,mat,.62)
    ref_rail("gem_claw_bottom",[(455,535),(490,548),(530,548)],z,.0065,mat,.62)

# Gold socket lip around the lower crystal and explicit rear supports.
ref_rail("gold_crystal_socket",[(430,530),(468,525),(505,535),(535,548)],.040,.0075,"gold_edge",.55)
for path in [[(430,420),(420,500),(410,590)],[(570,420),(600,500),(580,600)]]:
    ref_rail("rear_support",path,-.083,.0065,"steel_dark",.70)

# Extra depth plates visible from 3/4 and side views.
extrude_poly("rear_gold_spine",[(410,690),(430,745),(435,835),(420,875),(402,850)],.026,"bronze","gold_dark",-.050)
extrude_poly("rear_frame_tab",[(520,650),(548,660),(565,688),(548,715),(526,700)],.028,"steel_dark","gunmetal",-.052)

tile=48
width,height=512,tile*len(MATERIALS)
pixels=bytearray(width*height*4)
def set_pixel(x,y,rgba):
    i=(y*width+x)*4
    pixels[i:i+4]=bytes(rgba)
for row,key in enumerate(MATERIALS):
    base=COLORS[key]
    for y in range(row*tile,(row+1)*tile):
        yy=y-row*tile
        for x in range(width):
            brushed=4.8*math.sin(x*.19)+2.2*math.sin(x*.73+yy*.21)
            broad=6.2*math.sin((x/(width-1))*math.pi)
            grain=1.8*math.sin(x*1.83+yy*2.31)
            if key.startswith("gem_"):
                glow=16*math.sin(x*.065+yy*.14)+9*math.sin(x*.25-yy*.13)
                vals=[max(0,min(255,round(base[k]+glow))) for k in range(3)]
            else:
                nick=-9 if ((x*13+yy*7+row*17)%181)<4 else 0
                vals=[max(0,min(255,round(base[k]+brushed+broad+grain+nick))) for k in range(3)]
            set_pixel(x,y,(vals[0],vals[1],vals[2],base[3]))
def chunk(kind,data):
    return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
raw=b"".join(b"\x00"+bytes(pixels[y*width*4:(y+1)*width*4]) for y in range(height))
png=(b"\x89PNG\r\n\x1a\n"+
     chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,6,0,0,0))+
     chunk(b"IDAT",zlib.compress(raw,9))+
     chunk(b"IEND",b""))
(ASSET/"textures/scepter.png").write_bytes(png)

lines=[
    "# MCU Loki Scepter - authored asymmetrical solid mesh",
    "# grip origin=(0,0,0), +Y points toward crystal/blades",
]
unique={}; remap={}
for i,p in enumerate(vertices,1):
    p=tuple(round(v,6) for v in p)
    if p not in unique: unique[p]=len(unique)+1
    remap[i]=unique[p]
lines += ["v %.6f %.6f %.6f"%p for p in unique]
for key in MATERIALS:
    v=(MATERIALS.index(key)*tile+tile*.5)/(tile*len(MATERIALS))
    lines += ["vt %.6f %.6f"%(u,v) for u in (.08,.36,.68,.94)]
for name,ids,mat in faces:
    uv=MATERIALS.index(mat)*4+1
    lines.append("g "+name)
    lines.append("f "+" ".join(f"{remap[i]}/{uv+j}" for j,i in enumerate(ids)))
(ASSET/"models/laevateinn.obj").write_text("\n".join(lines)+"\n",encoding="utf-8")
print(f"{len(unique)} unique vertices; {len(faces)} quad faces; "
      f"x={min(v[0] for v in unique):.3f}..{max(v[0] for v in unique):.3f}; "
      f"y={min(v[1] for v in unique):.3f}..{max(v[1] for v in unique):.3f}; "
      f"z={min(v[2] for v in unique):.3f}..{max(v[2] for v in unique):.3f}")
