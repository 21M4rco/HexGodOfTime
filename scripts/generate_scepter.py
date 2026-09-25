"""Generate the screen-proportioned MCU Loki Scepter used by the custom item renderer.

The authored mesh keeps the hand grip centered at world-space origin and the blade assembly on +Y.
All geometry is explicit quads because AuthoredMesh intentionally accepts only authored quad faces.
The legacy OBJ filename is preserved so existing saves/registry IDs remain compatible.
"""
from pathlib import Path
import math, struct, zlib

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "src/main/resources/assets/hexgodofstories"

# Vanilla's one-texture OBJ path cannot provide true metallic/roughness maps, so the atlas bakes
# restrained variation and wear into distinct warm/cold material bands. Crystal groups get a
# second full-bright pass in WeaponRenderer.
COLORS = {
    "gold":       (146, 101, 35),
    "gold_edge":  (205, 157, 65),
    "gold_dark":  (76, 49, 21),
    "gold_worn":  (178, 128, 44),
    "steel":      (132, 143, 148),
    "steel_edge": (202, 211, 214),
    "steel_dark": (72, 79, 83),
    "recess":     (39, 44, 49),
    "grip":       (22, 25, 28),
    "gem_blue":   (10, 72, 212),
    "gem_cyan":   (30, 151, 247),
    "gem_white":  (184, 243, 255),
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

def rail(name, points, radius, mat):
    path=_smooth(points,4)
    tube(name,path,[radius]*len(path),[radius*.72]*len(path),mat,8)

# Authored span ~1.615; WeaponRenderer's held scale .72 produces ~1.16 blocks.
shaft=_smooth([
    ( .035,-.570,0.000),
    ( .028,-.470,0.003),
    (-.005,-.290,-.002),
    (-.025,-.105,0.000),
    ( 0.000, 0.000,0.000),
    ( .018, 0.115,0.000),
    ( .005, 0.225,0.000),
    (-.018, 0.305,0.000),
],7)
rx=[];rz=[]
for x,y,z in shaft:
    shoulder=max(0,(y-.10)/.205); lower=max(0,(-y-.33)/.24)
    rx.append(.026 + .008*shoulder + .009*lower)
    rz.append(.021 + .006*shoulder + .006*lower)
tube("gold_shaft",shaft,rx,rz,"gold",12)

for idx,(ystart,yend,side) in enumerate([
    (-.505,-.365,1),(-.390,-.245,-1),(-.275,-.120,1),(-.155,-.005,-1),
    (-.040,.105,1),(.075,.205,-1),(.165,.290,1)
]):
    seg=[p for p in shaft if ystart-.02 <= p[1] <= yend+.02]
    if len(seg)<2: continue
    widths=[.010 + .022*math.sin(math.pi*(i/max(1,len(seg)-1))) for i in range(len(seg))]
    depths=[.021 + .004*math.sin(math.pi*(i/max(1,len(seg)-1))) for i in range(len(seg))]
    plate_strip(f"gold_plate_{idx}",seg,widths,depths,"gold_edge" if idx%2==0 else "gold_worn",side,offset=.002)

for side in (-1,1):
    rail("shaft_recess",[(side*.012,-.40,-.019),(side*.006,-.22,-.021),(side*.015,-.04,-.020),(side*.010,.14,-.020)],.0035,"gold_dark")

tube("pommel_core",[(.035,-.605,0),(.039,-.580,0),(.033,-.545,0)],[.009,.033,.030],[.007,.024,.022],"gold_dark",10)
tube("pommel_shell",[(.035,-.612,0),(.041,-.584,0),(.033,-.548,0)],[.004,.028,.026],[.004,.020,.019],"gold_edge",10)
blade("pommel_blade",[(-.010,.010,-.625,.006),(-.022,.026,-.590,.010),(-.023,.031,-.548,.012)],("gold_edge","gold","gold_dark"))

blade("gold_shoulder_outer",[(-.060,.038,.180,.012),(-.072,.058,.245,.018),(-.050,.075,.330,.020)],("gold_edge","gold","gold_dark"))
blade("gold_shoulder_inner",[(-.020,.075,.215,.010),(-.010,.102,.285,.016),(.010,.115,.350,.016)],("gold_edge","gold_worn","gold_dark"))
rail("gold_upper_rail",[(-.040,.220,.018),(-.055,.300,.020),(-.035,.370,.018)],.009,"gold_edge")

neck=_smooth([(-.014,.275,0),(-.010,.325,0),(.012,.385,0),(.020,.430,0)],4)
tube("black_neck",neck,[.026,.025,.023,.021],[.020,.020,.018,.017],"grip",12)
for i in range(11):
    t=i/10
    x=-.014*(1-t)+.020*t; y=.286*(1-t)+.423*t
    tube("black_neck_rib",[(x,y,0),(x+.001,y+.006,0)],[.027,.027],[.021,.021],"recess",10)

blade("blade_main",[
    (-.105,-.035,.315,.024),
    (-.170,-.108,.405,.029),
    (-.232,-.158,.515,.032),
    (-.264,-.178,.635,.034),
    (-.245,-.166,.755,.031),
    (-.205,-.142,.855,.026),
    (-.151,-.108,.930,.019),
    (-.088,-.058,.972,.011),
    (-.036,-.029,.990,.0025),
],("steel_edge","steel","steel_dark"))

rail("blade_main_spine",[(-.092,.342,.021),(-.145,.470,.025),(-.185,.635,.027),(-.171,.800,.021),(-.118,.925,.011)],.0065,"steel_edge")
rail("blade_main_channel",[(-.082,.345,-.024),(-.130,.485,-.029),(-.165,.645,-.030),(-.150,.805,-.022),(-.098,.920,-.011)],.0045,"steel_dark")

blade("opposing_frame",[
    (.018,.115,.332,.021),
    (.050,.145,.405,.025),
    (.100,.165,.485,.027),
    (.132,.162,.565,.022),
],("steel_edge","steel","steel_dark"))

blade("fork_tall",[
    (.091,.126,.525,.016),
    (.110,.142,.610,.018),
    (.128,.146,.690,.013),
    (.137,.132,.756,.0025),
],("steel_edge","steel","steel_dark"))
blade("fork_short",[
    (.050,.088,.535,.014),
    (.066,.098,.598,.015),
    (.078,.091,.657,.0025),
],("steel_edge","steel","steel_dark"))

blade("lower_silver_overlay",[
    (-.025,.095,.245,.015),
    (-.050,.118,.315,.020),
    (-.062,.105,.392,.017),
],("steel_edge","steel","steel_dark"))
blade("lower_silver_keel",[
    (.060,.104,.275,.014),
    (.080,.120,.340,.018),
    (.068,.105,.405,.012),
],("steel_edge","steel","steel_dark"))

cx,cy=.012,.522
rings=[]
lat_steps=14; lon_steps=18
for i in range(lat_steps+1):
    lat=-math.pi/2 + math.pi*i/lat_steps
    bias=.84 + .14*((math.sin(lat)+1)/2)
    rr=math.cos(lat)
    ring=[]
    for j in range(lon_steps):
        lon=j*2*math.pi/lon_steps
        x=cx + (.057*bias*rr*math.cos(lon)) + .004*math.sin(lat*2)
        y=cy + .096*math.sin(lat)
        z=.050*rr*math.sin(lon)
        ring.append((x,y,z))
    rings.append(ring)
for i in range(lat_steps):
    for j in range(lon_steps):
        sig=(i*17+j*11)%41
        mat="gem_white" if sig in (0,1) else "gem_cyan" if (i*7+j*3)%9 in (0,1) else "gem_blue"
        quad("gem_blue_facets",[rings[i][j],rings[i][(j+1)%lon_steps],
                                rings[i+1][(j+1)%lon_steps],rings[i+1][j]],mat)

for dx in (-.018,.0,.020):
    rail("gem_glint_fracture",[(cx+dx,cy-.050,-.006),(cx+dx*.4,cy-.005,.006),(cx-dx*.2,cy+.038,-.004)],.0025,"gem_white")

for z in (-.052,.052):
    rail("gem_claw_side",[(-.062,.445,z),(-.080,.520,z),(-.060,.595,z),(-.018,.626,z)],.010,"steel")
    rail("gem_claw_upper",[(.000,.620,z),(.060,.615,z),(.093,.575,z)],.010,"steel_edge")
    rail("gem_claw_lower",[(-.050,.430,z),(-.005,.405,z),(.052,.420,z)],.009,"steel_dark")
for z,mat in ((-.066,"steel_dark"),(.066,"steel_edge")):
    rail("gem_hook",[(-.075,.487,z),(-.086,.545,z),(-.052,.612,z),(.015,.638,z),(.076,.598,z)],.008,mat)

rail("silver_crossbrace_low",[(-.070,.425,-.030),(.070,.438,-.030)],.007,"steel")
rail("silver_crossbrace_high",[(-.080,.600,.028),(.070,.614,.028)],.006,"steel_edge")
rail("gold_crystal_socket",[(-.050,.397,.000),(-.010,.384,.000),(.038,.392,.000),(.068,.420,.000)],.010,"gold_edge")
box("gold_socket_plate",-0.010,.389,0,.060,.014,.036,"gold_dark")
for n,(x,y) in enumerate([(-.074,.455),(-.083,.565),(.079,.456),(.087,.548),(-.048,.625)]):
    box(f"cage_tab_{n}",x,y,0,.018,.025,.078,"steel_edge" if n%2==0 else "steel")

rail("rear_support",[(-.050,.410,-.075),(-.092,.500,-.079),(-.070,.598,-.072),(.025,.650,-.060)],.008,"steel_dark")
rail("rear_support",[(.065,.424,-.070),(.105,.500,-.072),(.095,.585,-.065)],.007,"steel")

rail("gold_inlay",[(-.022,.330,.043),(.014,.372,.046),(.055,.397,.040)],.0055,"gold_worn")
rail("gold_inlay",[(-.030,.330,-.043),(.010,.375,-.046),(.052,.402,-.040)],.0055,"gold_dark")

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
            brushed=5.2*math.sin(x*.19)+2.8*math.sin(x*.71+yy*.22)
            broad=7.0*math.sin((x/(width-1))*math.pi)
            grain=2.3*math.sin(x*1.91+yy*2.37)
            if key.startswith("gem_"):
                glow=15*math.sin(x*.07+yy*.16)+8*math.sin(x*.27-yy*.11)
                vals=[max(0,min(255,round(v+glow))) for v in base]
            else:
                weather=-8 if ((x*13+yy*7+row*19)%173)<4 else 0
                vals=[max(0,min(255,round(v+brushed+broad+grain+weather))) for v in base]
            set_pixel(x,y,tuple(vals)+(255,))
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
print(f"{len(unique)} unique vertices; {len(faces)} quad faces; y={min(v[1] for v in unique):.3f}..{max(v[1] for v in unique):.3f}")
