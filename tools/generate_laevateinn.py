"""Rebuild the authored Laevateinn OBJ from a single symmetrical greatsword profile."""
from pathlib import Path
import math

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/hexgodofstories/models/laevateinn.obj"
vertices = []
uvs = []
faces = []

# The mod's material atlas: silver, gold, charcoal, leather, and pale green steel.
UV = {"steel": .19, "edge": .145, "gold": .315, "dark": .68, "leather": .555,
      "jade": .065, "highlight": .205}

def quad(group, material, points):
    start = len(vertices) + 1
    vertices.extend(points)
    u = UV[material]
    uvs.extend([(u-.026,.08),(u+.026,.08),(u+.026,.92),(u-.026,.92)])
    faces.append((group, tuple(range(start,start+4))))

def box(group, material, x0,x1,y0,y1,z0,z1):
    a=(x0,y0,z0);b=(x1,y0,z0);c=(x1,y1,z0);d=(x0,y1,z0)
    e=(x0,y0,z1);f=(x1,y0,z1);g=(x1,y1,z1);h=(x0,y1,z1)
    for points in [(a,b,c,d),(f,e,h,g),(e,a,d,h),(b,f,g,c),(d,c,g,h),(e,f,b,a)]:quad(group,material,points)

def strip(group,material,a,b,width,z):
    x0,y0=a;x1,y1=b
    length=math.hypot(x1-x0,y1-y0);dx=(y1-y0)/length*width/2;dy=-(x1-x0)/length*width/2
    quad(group,material,[(x0+dx,y0+dy,z),(x0-dx,y0-dy,z),(x1-dx,y1-dy,z),(x1+dx,y1+dy,z)])

def diamond(group,material,cx,cy,rx,ry,z):
    quad(group,material,[(cx,cy-ry,z),(cx+rx,cy,z),(cx,cy+ry,z),(cx-rx,cy,z)])

# Full-length black core and the genuinely visible wrapped handle.
box("tang","dark",-.065,.065,-.56,.59,-.057,.057)
for i in range(13):
    y=-.55+i*.075
    box("grip_wrap","leather",-.083,.083,y,y+.047,-.076,.076)
    if i%3==0:
        box("grip_wire","gold",-.087,.087,y+.048,y+.059,-.079,.079)
box("lower_ferrule","gold",-.11,.11,-.59,-.52,-.094,.094)
box("upper_ferrule","gold",-.12,.12,.39,.46,-.095,.095)

# A weighted emerald pommel with gold shoulders and a four-sided dark-green gemstone.
box("pommel_cap","gold",-.13,.13,-.66,-.58,-.11,.11)
diamond("pommel_jewel","jade",0,-.75,.115,.14,.095)
diamond("pommel_jewel","jade",0,-.75,.115,.14,-.095)
box("pommel_tip","gold",-.07,.07,-.91,-.84,-.072,.072)

# Paired swept quillons. Thick golden metal remains readable from every angle.
for sign in (-1,1):
    for j in range(4):
        x0=sign*(.105+j*.112);x1=sign*(.217+j*.112)
        y0=.57+j*.042;y1=.62+j*.067
        lo,hi=sorted((x0,x1))
        box("guard_wing","gold",lo,hi,y0,y1,-.085,.085)
    strip("guard_engraving","jade",(sign*.16,.64),(sign*.51,.79),.032,.092)
    strip("guard_engraving","jade",(sign*.16,.64),(sign*.51,.79),.032,-.092)
box("guard_core","gold",-.19,.19,.53,.71,-.11,.11)
diamond("guard_gem","jade",0,.625,.14,.17,.119)
diamond("guard_gem","jade",0,.625,.14,.17,-.119)

# The broad blade is double-edged, tapered and sharply faceted, not a flat grey plane.
profile=[(.68,.23,.090),(.82,.37,.086),(1.05,.365,.082),(1.28,.335,.076),
         (1.52,.31,.068),(1.75,.275,.059),(1.98,.24,.052),(2.18,.18,.044),
         (2.36,.10,.030),(2.54,.008,.004)]
for side in (-1,1):
    for (y0,w0,z0),(y1,w1,z1) in zip(profile,profile[1:]):
        # Four tapering facets per segment, with the lighter, razor-thin outer facet.
        for sign in (-1,1):
            quad("blade_face","steel",[(0,y0,side*z0),(sign*w0*.77,y0,side*z0*.35),
                                         (sign*w1*.77,y1,side*z1*.35),(0,y1,side*z1)])
            quad("blade_edge","edge",[(sign*w0*.77,y0,side*z0*.35),(sign*w0,y0,0),
                                        (sign*w1,y1,0),(sign*w1*.77,y1,side*z1*.35)])
    # An inset channel in dark metal and thin luminous filigree, mirrored on both sides.
    for (y0,w0,z0),(y1,w1,z1) in zip(profile[1:7],profile[2:8]):
        quad("fuller","dark",[(-.032,y0,side*(z0+.008)),(.032,y0,side*(z0+.008)),
                               (.026,y1,side*(z1+.008)),(-.026,y1,side*(z1+.008))])
    for cy in (1.08,1.44,1.80):
        diamond("blade_rune","jade",0,cy,.058,.11,side*.105)
        strip("filigree","gold",(-.23,cy-.13),(-.085,cy-.035),.018,side*.065)
        strip("filigree","gold",(.23,cy-.13),(.085,cy-.035),.018,side*.065)

# Keep the player's hand at the middle of the wrapped grip (y=0). A greatsword
# needs most of its length above the guard; a one-unit hilt looked like a club.
# Scaling the finished profile preserves all of its facets, wraps and runes.
vertices=[(x*(1.12 if y>=.68 else 1),
           y*.52 if y<=.68 else .68*.52+(y-.68)*1.65,z) for x,y,z in vertices]
lines=["# Laevateinn: centered short grip, swept guard, long faceted greatsword blade."]
lines += ["v %.6f %.6f %.6f"%v for v in vertices]
lines += ["vt %.6f %.6f"%uv for uv in uvs]
group=None
for name,indices in faces:
    if name!=group:
        group=name;lines.append("g "+name)
    lines.append("f "+" ".join(f"{i}/{i}" for i in indices))
OUT.write_text("\n".join(lines)+"\n",encoding="utf-8")
print(f"{OUT}: {len(faces)} quads, {len(vertices)} vertices")
