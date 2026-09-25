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
# Shaft: measured against the reference silhouette. Grip is exactly at y=0.
# ---------------------------------------------------------------------------
shaft=_smooth([
    (.031,-.870,0.000), (.020,-.805,0.000), (.011,-.730,0.001),
    (.014,-.655,-.001), (.026,-.575,0.000), (-.001,-.490,0.001),
    (-.005,-.405,-.001), (.003,-.325,0.000), (.001,-.240,0.000),
    (.005,-.160,0.000), (.006,-.080,0.000), (-.006,0.000,0.000),
    (.003,.082,0.000), (.019,.165,0.000),
],6)
rx=[];rz=[]
for x,y,z in shaft:
    lower=max(0.0,min(1.0,(-y-.48)/.39))
    shoulder=max(0.0,min(1.0,(y-.05)/.115))
    tip=max(0.0,min(1.0,(-y-.82)/.05))
    rx.append((.023 + .029*lower + .010*shoulder)*(1-.55*tip))
    rz.append((.019 + .023*lower + .007*shoulder)*(1-.52*tip))
tube("gold_shaft",shaft,rx,rz,"gold",14)

rail("counterweight_seam",[(.061,-.842,.018),(.051,-.807,.020),(.031,-.774,.018)],.0032,"gold_dark",.50)
rail("counterweight_edge",[(.052,-.860,.020),(.058,-.840,.021),(.050,-.818,.021)],.0025,"gold_edge",.45)

for idx,(y0,y1,tilt) in enumerate([
    (-.72,-.64,1),(-.59,-.51,-1),(-.46,-.38,1),(-.33,-.25,-1),
    (-.20,-.12,1),(-.07,.01,-1),(.045,.115,1)
]):
    ym=(y0+y1)*.5
    x=min(shaft,key=lambda p:abs(p[1]-ym))[0]
    z=.028
    dx=.016
    rail(f"gold_plate_seam_{idx}",[(x-dx*tilt,y0,z),(x+dx*tilt,y1,z+.001)],.0028,"gold_dark",.45,6)
    rail(f"gold_plate_edge_{idx}",[(x-dx*tilt+.004,y0+.006,z+.003),(x+dx*tilt+.004,y1-.006,z+.004)],.0019,"gold_edge",.40,6)
rail("shaft_recess",[(.000,-.61,-.031),(.002,-.42,-.029),(.006,-.22,-.027),(-.002,-.02,-.025)],.0028,"gold_dark",.48)

blade("gold_shoulder_left",[(-.045,.012,.085,.013),(-.055,.030,.135,.018),(-.046,.052,.195,.020)],("gold_edge","gold","bronze"))
blade("gold_shoulder_right",[(.010,.052,.090,.012),(.022,.080,.150,.017),(.038,.096,.210,.018)],("gold_edge","gold","bronze"))
rail("gold_shoulder_ridge",[(-.024,.105,.030),(-.020,.160,.032),(.010,.215,.030)],.007,"gold_edge")

neck=_smooth([(.020,.145,0),(.026,.190,0),(.042,.235,0),(.055,.278,0)],4)
tube("black_neck",neck,[.028,.028,.026,.023],[.023,.023,.021,.019],"black",12)
for i in range(12):
    t=i/11
    x=.020*(1-t)+.055*t; y=.151*(1-t)+.274*t
    tube("black_neck_rib",[(x,y,0),(x+.001,y+.006,0)],[.030,.030],[.024,.024],"gunmetal",10)

blade("blade_main",[
    (-.050,.010,.112,.027),
    (-.046,.018,.165,.032),
    (-.038,.026,.220,.036),
    (-.028,.037,.280,.040),
    (-.016,.054,.345,.042),
    (.008,.083,.420,.041),
    (.045,.118,.500,.036),
    (.088,.153,.565,.030),
    (.137,.187,.625,.020),
    (.225,.225,.680,.0025),
],("steel_edge","steel","steel"))

rail("blade_main_spine",[(-.020,.150,.022),(-.005,.235,.025),(.020,.330,.026),(.060,.445,.023),(.112,.565,.017),(.176,.645,.008)],.0062,"steel_edge",.55)
rail("blade_main_channel",[(-.030,.150,-.052),(-.016,.245,-.055),(.010,.345,-.057),(.050,.455,-.053),(.102,.565,-.041),(.160,.635,-.026)],.0044,"steel_dark",.55)

blade("opposing_frame",[
    (.050,.096,.108,.020),(.060,.122,.165,.024),(.078,.148,.230,.027),
    (.098,.171,.295,.025),(.116,.184,.350,.020)
],("steel_edge","steel","steel"))

blade("fork_tall",[
    (.138,.184,.330,.017),(.150,.190,.385,.016),(.164,.190,.438,.011),(.188,.188,.495,.0025)
],("steel_edge","steel","steel"))
blade("fork_short",[
    (.112,.144,.338,.014),(.119,.149,.375,.013),(.129,.142,.422,.0025)
],("steel_edge","steel","steel"))

blade("lower_silver_overlay",[
    (-.030,-.002,.098,.012),(-.041,.018,.145,.016),(-.038,.030,.195,.015),(-.026,.041,.235,.009)
],("steel_edge","steel","steel_dark"))
blade("lower_silver_keel",[
    (.050,.080,.095,.011),(.063,.096,.138,.015),(.066,.101,.182,.015),(.058,.090,.222,.009)
],("steel_edge","steel","steel_dark"))

cx,cy=.086,.335
rings=[]
lat_steps=14; lon_steps=18
for i in range(lat_steps+1):
    lat=-math.pi/2 + math.pi*i/lat_steps
    rr=math.cos(lat)
    taper=.78+.22*((math.sin(lat)+1)/2)
    ring=[]
    for j in range(lon_steps):
        lon=j*2*math.pi/lon_steps
        x=cx + .043*taper*rr*math.cos(lon) + .003*math.sin(lat*2)
        y=cy + .058*math.sin(lat)
        z=.047*rr*math.sin(lon)
        ring.append((x,y,z))
    rings.append(ring)
for i in range(lat_steps):
    for j in range(lon_steps):
        sig=(i*17+j*11)%41
        mat="gem_white" if sig in (0,1) else "gem_cyan" if (i*7+j*3)%9 in (0,1) else "gem_blue"
        quad("gem_blue_facets",[rings[i][j],rings[i][(j+1)%lon_steps],
                                rings[i+1][(j+1)%lon_steps],rings[i+1][j]],mat)

for dx in (-.018,-.004,.014):
    rail("gem_glint_fracture",[(cx+dx,cy-.035,-.006),(cx+dx*.6,cy,.005),(cx-dx*.15,cy+.035,-.004)],.0021,"gem_white",.55,6)

for z in (-.062,.062):
    rail("gem_claw_side",[(-.002,.285,z),(-.018,.330,z),(-.012,.375,z),(.018,.408,z)],.0085,"steel",.70)
    rail("gem_claw_upper",[(.020,.408,z),(.068,.420,z),(.112,.399,z)],.0080,"steel_edge",.70)
    rail("gem_claw_lower",[(.120,.392,z),(.135,.354,z),(.132,.320,z)],.0075,"steel_dark",.70)
rail("gem_hook",[(-.004,.300,.074),(-.014,.350,.076),(.012,.400,.072),(.060,.426,.066),(.112,.398,.056)],.0068,"steel_edge")
rail("gem_hook",[(-.010,.298,-.076),(-.022,.350,-.078),(.004,.402,-.073),(.052,.429,-.066),(.106,.401,-.057)],.0068,"steel_dark")

rail("gold_crystal_socket",[(.024,.257,0),(.058,.246,0),(.096,.251,0),(.120,.270,0)],.0085,"gold_edge")
box("gold_socket_plate",.070,.250,0,.066,.014,.038,"bronze")
for n,(x,y,z) in enumerate([
    (.000,.294,.052),(-.004,.370,.052),(.129,.300,.050),(.130,.370,.048),
    (.026,.417,-.050),(.106,.414,-.048)
]):
    box(f"cage_tab_{n}",x,y,z,.015,.022,.023,"steel_edge" if n%2==0 else "steel")

rail("rear_support",[(.010,.255,-.079),(-.010,.315,-.083),(.002,.382,-.078),(.030,.414,-.068)],.0064,"steel_dark")
rail("rear_support",[(.116,.260,-.073),(.142,.318,-.075),(.134,.382,-.066)],.0060,"steel")

rail("gold_inlay",[(.025,.215,-.046),(.060,.244,-.049),(.105,.260,-.044)],.0045,"bronze")
rail("gold_inlay",[(.022,.215,.046),(.060,.244,.049),(.106,.262,.043)],.0045,"gold_edge")

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
