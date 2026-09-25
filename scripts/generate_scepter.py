"""Generate the accepted V4 MCU Loki Scepter for the Forge 1.20.1 custom renderer.

The silhouette and proportions are traced from the approved V4 prop model.  The grip is authored at
world-space Y=0 so WeaponRenderer can put the hand on the upper-middle shaft.  The output stays in the
legacy laevateinn.obj slot so registry ids and saves do not change.
"""
from pathlib import Path
import math, struct, zlib

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/"src/main/resources/assets/hexgodofstories"
MODEL=ASSET/"models/laevateinn.obj"
TEXTURE=ASSET/"textures/scepter.png"

PX_SCALE=1.10/(1930-60)
X_ORIGIN=430.0
Y_BOTTOM=1930.0
GRIP_Y=0.64

def pxy(px,py):
    return ((px-X_ORIGIN)*PX_SCALE,(Y_BOTTOM-py)*PX_SCALE-GRIP_Y)

def pts(raw):
    return [pxy(x,y) for x,y in raw]

# Atlas names match the material bands used by WeaponRenderer's single scepter texture.
ATLAS=["gold","gold_edge","gold_dark","gold_worn","steel","steel_edge","steel_dark","recess","grip","gem_blue","gem_cyan","gem_white"]
MAT={
    "silver":"steel",
    "silver_light":"steel_edge",
    "steel_dark":"steel_dark",
    "gunmetal":"recess",
    "gold":"gold",
    "gold_light":"gold_edge",
    "bronze":"gold_worn",
    "black":"grip",
    "crystal":"gem_blue",
    "crystal_core":"gem_white",
}
COLORS={
    "gold":(175,117,28),"gold_edge":(238,180,58),"gold_dark":(76,43,12),"gold_worn":(94,55,15),
    "steel":(158,165,172),"steel_edge":(231,239,246),"steel_dark":(52,57,60),"recess":(23,26,27),
    "grip":(8,8,9),"gem_blue":(7,102,242),"gem_cyan":(47,190,255),"gem_white":(181,245,255),
}

vertices=[]
faces=[]  # (group, material, [vertex ids], [uv ids])
_vmap={}

def vertex(x,y,z):
    key=(round(x,6),round(y,6),round(z,6))
    if key not in _vmap:
        _vmap[key]=len(vertices)+1
        vertices.append(key)
    return _vmap[key]

def area2(poly):
    return sum(poly[i][0]*poly[(i+1)%len(poly)][1]-poly[(i+1)%len(poly)][0]*poly[i][1] for i in range(len(poly)))

def cross(a,b,c):
    return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])

def inside_tri(p,a,b,c):
    c1=cross(a,b,p); c2=cross(b,c,p); c3=cross(c,a,p)
    eps=1e-12
    return (c1>=-eps and c2>=-eps and c3>=-eps) or (c1<=eps and c2<=eps and c3<=eps)

def triangulate(poly):
    # Ear clipping for the traced simple concave outlines.  Fallback fan is only a guard for a
    # numerically-degenerate outline and is never expected for the V4 source.
    n=len(poly)
    if n<3:return []
    orient=1 if area2(poly)>0 else -1
    idx=list(range(n)); out=[]; guard=0
    while len(idx)>3 and guard<n*n*4:
        guard+=1; cut=False
        for k in range(len(idx)):
            ia=idx[k-1]; ib=idx[k]; ic=idx[(k+1)%len(idx)]
            a,b,c=poly[ia],poly[ib],poly[ic]
            if orient*cross(a,b,c)<=1e-12: continue
            if any(j not in (ia,ib,ic) and inside_tri(poly[j],a,b,c) for j in idx): continue
            out.append((ia,ib,ic)); del idx[k]; cut=True; break
        if not cut: break
    if len(idx)==3: out.append(tuple(idx))
    if len(out)!=n-2:
        out=[(0,i,i+1) for i in range(1,n-1)]
    return out

def group_name(name):
    if name=="crystal": return "gem_blue_crystal"
    if name=="crystal_core": return "gem_blue_core"
    if name.startswith("crystal_facet_"): return "gem_blue_"+name
    return name

def add_face(group,material,ids):
    faces.append((group_name(group),MAT.get(material,material),ids))

def extrude_poly(name,raw_points,depth,material,zc=0.0):
    poly=pts(raw_points) if raw_points and max(abs(raw_points[0][0]),abs(raw_points[0][1]))>5 else raw_points
    # Drop a repeated closing point if present.
    if len(poly)>2 and poly[0]==poly[-1]: poly=poly[:-1]
    zf=zc+depth/2; zb=zc-depth/2
    vf=[vertex(x,y,zf) for x,y in poly]
    vb=[vertex(x,y,zb) for x,y in poly]
    tris=triangulate(poly)
    orient=1 if area2(poly)>0 else -1
    for a,b,c in tris:
        if orient>0:
            add_face(name,material,[vf[a],vf[b],vf[c],vf[c]])
            add_face(name,material,[vb[c],vb[b],vb[a],vb[a]])
        else:
            add_face(name,material,[vf[c],vf[b],vf[a],vf[a]])
            add_face(name,material,[vb[a],vb[b],vb[c],vb[c]])
    for i in range(len(poly)):
        j=(i+1)%len(poly)
        add_face(name,material,[vb[i],vb[j],vf[j],vf[i]])

def box(name,center,size,material):
    cx,cy,cz=center; sx,sy,sz=size
    x0,x1=cx-sx/2,cx+sx/2; y0,y1=cy-sy/2,cy+sy/2; z0,z1=cz-sz/2,cz+sz/2
    p=[vertex(x0,y0,z0),vertex(x1,y0,z0),vertex(x1,y1,z0),vertex(x0,y1,z0),
       vertex(x0,y0,z1),vertex(x1,y0,z1),vertex(x1,y1,z1),vertex(x0,y1,z1)]
    for q in ((0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)):
        add_face(name,material,[p[i] for i in q])

# MAIN BLADE — traced as a long, thin scimitar-like asymmetrical blade, not a chunky crescent.
main_blade=[
 (696,62),(660,88),(625,116),(595,145),(568,177),(543,210),(520,246),(500,284),
 (482,322),(466,358),(452,393),(446,422),(445,454),(448,482),(442,504),(428,523),
 (416,548),(404,585),(393,626),(387,668),(386,697),(399,704),(414,680),(422,646),
 (427,609),(433,577),(442,552),(455,533),(467,519),(475,498),(477,469),(474,443),
 (476,420),(485,399),(496,385),(510,380),(520,368),(522,350),(518,335),(523,322),
 (536,317),(543,304),(550,283),(559,254),(572,219),(590,181),(612,145),(638,112),(668,83)
]
extrude_poly('main_blade', main_blade, 0.0085, 'silver', zc=0)
main_ridge=[
 (686,71),(647,103),(610,140),(580,181),(555,226),(535,271),(519,315),(510,350),
 (504,366),(514,369),(527,356),(533,334),(541,313),(552,286),(567,249),(585,208),(609,168),(637,127)
]
extrude_poly('main_blade_ridge', main_ridge, 0.0025, 'silver_light', zc=0.0050)
extrude_poly('main_blade_recess', [(445,456),(463,414),(485,392),(507,386),(496,409),(488,438),(488,467),(472,487),(455,493)], 0.002, 'steel_dark', zc=0.0052)

# SMALL OPPOSITE FORK — narrow, tall twin prongs and long lower side armor.
small_fork=[
 (549,321),(557,306),(563,303),(561,337),(555,365),(558,379),(565,362),(574,322),(581,302),
 (584,327),(584,363),(585,402),(585,442),(581,478),(573,514),(561,548),(550,570),(544,589),
 (548,598),(563,599),(573,610),(572,629),(561,647),(548,660),(536,677),(526,701),(516,725),
 (505,747),(491,760),(477,762),(483,742),(494,721),(504,698),(511,673),(519,646),(527,617),
 (533,590),(537,559),(544,532),(553,511),(562,487),(566,459),(566,431),(565,405),(562,389),
 (554,386),(546,391),(544,375)
]
extrude_poly('small_fork_blade', small_fork, 0.0090, 'silver', zc=0.001)
extrude_poly('small_fork_edge', [(576,307),(583,303),(584,327),(584,365),(584,404),(582,442),(578,479),(571,513),(564,532),(559,523),(564,489),(568,450),(568,410),(568,375)], 0.0022,'silver_light',zc=0.006)

# CRYSTAL — elongated irregular teardrop, sunk between the metal planes.
crystal=[(465,433),(478,420),(499,416),(519,423),(531,441),(534,466),(529,493),(518,520),(505,542),(490,548),(475,535),(466,511),(461,482),(460,455)]
extrude_poly('crystal', crystal, 0.034, 'crystal', zc=0.001)
crystal_core=[(482,433),(501,426),(517,438),(524,463),(519,490),(507,515),(492,531),(478,514),(470,487),(470,458)]
extrude_poly('crystal_core', crystal_core, 0.036, 'crystal_core', zc=0.004)
for i, poly in enumerate([
 [(467,449),(483,432),(487,463),(472,475)],
 [(487,432),(505,426),(501,460),(487,463)],
 [(505,426),(519,440),(514,466),(501,460)],
 [(472,475),(487,463),(488,500),(474,507)],
 [(488,463),(514,466),(503,502),(488,500)],
 [(474,507),(488,500),(492,529),(481,519)],
 [(503,502),(519,491),(507,515),(492,529)]
]):
    extrude_poly(f'crystal_facet_{i}', poly, 0.0018, 'crystal_core', zc=0.023)

# SILVER CAGE / CLAWS around crystal — separate pieces with visible gaps.
extrude_poly('cage_top', [(445,424),(461,408),(489,400),(518,406),(541,420),(553,440),(551,459),(543,465),(536,442),(518,425),(493,416),(468,421),(453,438)], 0.012,'silver_light',zc=0.012)
extrude_poly('cage_left_claw', [(446,424),(430,440),(420,461),(422,481),(435,494),(450,487),(444,468),(447,451),(458,438)], 0.014,'silver',zc=0.012)
extrude_poly('cage_left_lower', [(422,481),(414,507),(420,525),(439,531),(457,521),(449,505),(435,494)],0.014,'silver',zc=0.011)
extrude_poly('cage_right_claw', [(542,423),(555,433),(565,451),(566,474),(558,493),(550,500),(551,478),(547,457),(536,442)],0.013,'silver_light',zc=0.012)
extrude_poly('rear_support_left', [(451,430),(458,421),(470,420),(466,452),(463,481),(468,512),(456,524),(448,501),(447,467)],0.010,'steel_dark',zc=-0.018)
extrude_poly('rear_support_right', [(526,427),(538,435),(545,456),(544,483),(536,509),(524,531),(518,518),(527,486),(530,456)],0.010,'steel_dark',zc=-0.018)

# BLACK RIBBED NECK under crystal
neck=[(451,529),(466,523),(488,529),(504,545),(503,575),(493,603),(478,628),(462,624),(450,606),(444,578),(445,550)]
extrude_poly('black_neck', neck, 0.020, 'black', zc=0.0)
for i, y in enumerate(range(542,608,9)):
    x1=449 + (y-542)*0.18
    x2=497 - (y-542)*0.07
    extrude_poly(f'neck_rib_{i}', [(x1,y),(x2,y-3),(x2-2,y+2),(x1+2,y+5)], 0.0025,'steel_dark',zc=0.012)

# GOLD TRANSITION ARMOR at head/shaft junction
extrude_poly('gold_upper_left', [(420,523),(445,516),(451,529),(444,550),(446,580),(454,611),(445,646),(433,672),(417,651),(409,610),(408,568)],0.017,'gold',zc=0.0)
extrude_poly('gold_upper_cap', [(419,524),(448,517),(476,524),(496,538),(489,547),(462,538),(438,537),(415,545)],0.014,'gold_light',zc=0.009)
extrude_poly('gold_upper_right', [(454,611),(464,626),(476,636),(466,661),(452,678),(437,681),(445,646)],0.015,'gold',zc=0.001)

# MAIN SHAFT — narrow, gently curved, with overlapping traced armor.
shaft=[
 (414,644),(433,665),(440,697),(438,733),(424,773),(411,816),(402,864),(397,921),(395,983),
 (397,1050),(401,1117),(404,1185),(406,1252),(407,1320),(408,1390),(408,1461),(406,1530),
 (402,1600),(398,1665),(398,1724),(402,1773),(413,1810),(428,1837),(446,1844),(456,1838),
 (458,1826),(451,1808),(445,1780),(444,1743),(447,1699),(453,1648),(457,1592),(459,1535),
 (459,1474),(458,1411),(457,1346),(456,1280),(454,1212),(453,1143),(452,1076),(452,1013),
 (454,953),(458,899),(464,849),(471,805),(479,766),(485,730),(482,699),(470,675),(447,650)
]
extrude_poly('shaft_core', shaft, 0.020, 'gold', zc=0.0)
extrude_poly('shaft_dark_edge', [(414,644),(423,665),(428,700),(425,745),(413,792),(404,844),(400,900),(400,965),(403,1035),(407,1105),(410,1175),(412,1240),(412,1310),(412,1380),(411,1450),(408,1520),(404,1590),(400,1650),(400,1720),(404,1770),(413,1810),(405,1792),(396,1751),(392,1699),(392,1635),(395,1570),(399,1500),(401,1430),(401,1360),(400,1290),(398,1220),(396,1150),(394,1080),(392,1012),(391,950),(393,892),(398,838),(404,788),(410,742)],0.004,'bronze',zc=0.011)
armor_polys=[
 [(414,647),(446,650),(472,678),(482,700),(467,706),(446,689),(429,672)],
 [(420,690),(438,705),(470,720),(480,738),(465,758),(438,747),(420,731)],
 [(411,747),(437,755),(465,772),(469,790),(452,817),(426,803),(406,783)],
 [(402,816),(425,821),(453,840),(459,860),(446,888),(418,873),(399,852)],
 [(397,890),(420,898),(446,918),(451,939),(437,967),(409,948),(396,927)],
 [(395,972),(420,982),(442,1002),(445,1024),(430,1050),(405,1030),(396,1007)],
 [(399,1060),(420,1072),(442,1092),(444,1117),(429,1140),(407,1120),(400,1090)],
 [(402,1150),(423,1162),(444,1185),(446,1207),(432,1230),(409,1210),(403,1180)],
 [(405,1245),(426,1257),(447,1280),(448,1301),(434,1327),(411,1306),(406,1274)],
 [(407,1340),(429,1352),(449,1374),(449,1398),(435,1422),(412,1402),(407,1370)],
 [(407,1440),(428,1452),(448,1475),(447,1500),(431,1524),(409,1504),(406,1470)],
 [(403,1540),(424,1550),(445,1574),(443,1598),(426,1621),(404,1602),(400,1570)],
]
for i,a in enumerate(armor_polys): extrude_poly(f'shaft_plate_{i}',a,0.006,'gold_light' if i%3==0 else 'gold',zc=0.013)
for i, seg in enumerate([
 [(427,684),(451,698),(448,704),(424,691)],[(422,769),(449,783),(445,790),(418,777)],
 [(414,843),(440,859),(436,866),(410,850)],[(410,925),(436,942),(432,949),(405,931)],
 [(408,1011),(432,1028),(429,1035),(404,1018)],[(411,1100),(435,1116),(431,1124),(406,1107)],
 [(414,1190),(437,1206),(433,1214),(410,1197)],[(415,1290),(439,1307),(435,1314),(411,1297)]
]): extrude_poly(f'shaft_seam_{i}',seg,0.002,'bronze',zc=0.017)

counter=[(397,1715),(405,1748),(414,1783),(427,1813),(446,1844),(458,1843),(467,1830),(468,1808),(464,1777),(459,1746),(457,1712),(456,1670),(457,1634),(453,1602),(446,1614),(437,1640),(427,1670),(416,1695)]
extrude_poly('counterweight',counter,0.026,'gold_light',zc=0.0)
extrude_poly('counterweight_shadow',[(397,1715),(405,1750),(414,1785),(426,1814),(445,1843),(432,1828),(418,1800),(407,1763),(399,1730)],0.004,'bronze',zc=0.015)
extrude_poly('bottom_cap',[(425,1810),(446,1844),(458,1843),(466,1832),(460,1825),(446,1832),(434,1824)],0.005,'gold',zc=0.016)
extrude_poly('left_lower_strut',[(386,690),(398,699),(406,727),(402,766),(394,785),(387,775),(390,744)],0.010,'silver',zc=0.002)
extrude_poly('right_lower_strut',[(490,700),(505,682),(519,660),(527,666),(520,692),(505,714),(492,733),(480,742)],0.010,'silver',zc=0.003)

# Genuine front/back depth around the crystal.
for i,(cx,cy,w,h) in enumerate([
    (*pxy(452,442),0.012,0.016),(*pxy(540,448),0.012,0.018),
    (*pxy(458,516),0.012,0.016),(*pxy(530,514),0.012,0.017)
]):
    box(f"cage_depth_connector_{i}",(cx,cy,0.0),(w,h,0.050),"steel_dark")

# Mirror raised metal relief onto the back face; preserve the asymmetric outline.
for group,material,ids in list(faces):
    if any(token in group for token in ('ridge','edge','recess','shaft_plate','shaft_seam','counterweight_shadow')):
        mirrored=[vertex(vertices[i-1][0],vertices[i-1][1],-vertices[i-1][2]) for i in reversed(ids)]
        faces.append((group+'_back',material,mirrored))

# Shared UVs sample the centre of each material stripe. Four values keep every authored face in
# the exact v/vt quad form AuthoredMesh expects.
uvbase={}
vt=[]
for mi,name in enumerate(ATLAS):
    v=(mi+0.5)/len(ATLAS)
    uvbase[name]=len(vt)+1
    vt.extend([(0.08,v),(0.36,v),(0.68,v),(0.94,v)])

lines=["# Loki Scepter V4 - accepted prop-traced model","# grip=(0,0,0); +Y runs from hand toward crystal/head"]
lines += [f"v {x:.6f} {y:.6f} {z:.6f}" for x,y,z in vertices]
lines += [f"vt {u:.6f} {v:.6f}" for u,v in vt]
last=None
for group,material,ids in faces:
    if group!=last:
        lines.append("g "+group); last=group
    uv=uvbase[material]
    lines.append("f "+" ".join(f"{vid}/{uv+i}" for i,vid in enumerate(ids)))
MODEL.parent.mkdir(parents=True,exist_ok=True)
MODEL.write_text("\n".join(lines)+"\n",encoding="utf-8")

# Dependency-free PNG atlas, so the V4 mesh/material mapping is reproducible in Actions.
tile=40; width=320; height=tile*len(ATLAS)
pixels=bytearray(width*height*4)
for mi,name in enumerate(ATLAS):
    base=COLORS[name]
    for y in range(mi*tile,(mi+1)*tile):
        yy=y-mi*tile
        for x in range(width):
            if name.startswith("gem_"):
                glow=14*math.sin(x*.10+yy*.22)+6*math.sin(x*.31-yy*.15)
                rgb=[max(0,min(255,round(c+glow))) for c in base]
                a=218 if name=="gem_blue" else 244
            else:
                brush=4*math.sin(x*.22)+2*math.sin(x*.73+yy*.31)
                edge=5*math.sin((x/(width-1))*math.pi)
                rgb=[max(0,min(255,round(c+brush+edge))) for c in base]
                a=255
            o=(y*width+x)*4
            pixels[o:o+4]=bytes((*rgb,a))
def png_chunk(kind,data):
    return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
raw=b"".join(b"\x00"+bytes(pixels[y*width*4:(y+1)*width*4]) for y in range(height))
png=(b"\x89PNG\r\n\x1a\n"+
     png_chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,6,0,0,0))+
     png_chunk(b"IDAT",zlib.compress(raw,9))+
     png_chunk(b"IEND",b""))
TEXTURE.parent.mkdir(parents=True,exist_ok=True)
TEXTURE.write_bytes(png)

ys=[v[1] for v in vertices]
print(f"Scepter V4: {len(vertices)} vertices, {len(faces)} authored quads, y={min(ys):.3f}..{max(ys):.3f}")
