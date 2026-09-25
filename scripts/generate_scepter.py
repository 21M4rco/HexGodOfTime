"""Build the Scepter's quadded OBJ and material atlas from its authored silhouette.

Run from the repository root: python scripts/generate_scepter.py
The old laevateinn registry/model path is retained so existing saves keep their item.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import math

root = Path(__file__).resolve().parents[1] / "src/main/resources/assets/hexgodofstories"
model = root / "models/laevateinn.obj"
texture = root / "textures/scepter.png"

# A long tapered gold handle, wrapped in offset plates, holds a silver dark-metal
# asymmetric twin-prong crown. The left horn sweeps forward into a single blade;
# the short right horn cradles an exposed six-sided blue gem.
parts = [
    ("shaft", "gold", [(-.08,-1.03),(-.065,-.25),(-.058,.29),(-.085,.60),(.045,.60),(.065,.26),(.068,-.25),(.11,-1.03)]),
    ("butt", "gold_light", [(-.13,-1.04),(-.12,-.80),(-.075,-.74),(.10,-.80),(.155,-1.05),(.08,-1.13),(-.05,-1.14)]),
    ("wrap_low", "gold_light", [(-.09,-.56),(.055,-.47),(.07,-.34),(-.078,-.43)]),
    ("wrap_mid", "gold_light", [(-.07,-.13),(.065,-.035),(.063,.095),(-.07,0)]),
    ("wrap_high", "gold_light", [(-.075,.32),(.065,.43),(.09,.54),(-.083,.47)]),
    ("socket", "dark", [(-.17,.49),(.14,.49),(.25,.68),(.19,.93),(-.04,1.04),(-.23,.86)]),
    ("back_plate", "steel_shadow", [(-.25,.57),(-.17,.88),(-.21,1.11),(-.06,1.35),(.18,1.55),(.42,1.70),(.21,1.36),(.10,1.10),(.22,.88),(.13,.55)]),
    ("long_blade", "steel", [(-.23,.70),(-.32,.70),(-.25,1.04),(-.10,1.31),(.14,1.55),(.44,1.73),(.30,1.48),(.08,1.25),(-.09,.98)]),
    ("blade_edge", "steel_light", [(-.32,.70),(-.25,1.04),(-.10,1.31),(.14,1.55),(.44,1.73),(.29,1.51),(.055,1.29),(-.12,1.02)]),
    ("short_horn", "steel", [(.18,.68),(.27,.78),(.36,1.01),(.32,1.16),(.28,1.03),(.19,1.04),(.18,.86)]),
    ("horn_edge", "steel_light", [(.31,.97),(.35,1.14),(.36,1.01),(.32,.90)]),
    ("collar", "gold_light", [(-.18,.64),(-.15,.72),(.19,.72),(.22,.64)]),
    ("neck", "dark", [(-.075,.52),(.10,.52),(.15,.83),(.065,.84)]),
    ("gem_iron", "steel_shadow", [(-.14,.78),(-.11,1.02),(.08,1.13),(.24,1.05),(.27,.86),(.09,.73)]),
    ("gem_blue", "blue", [(-.105,.79),(-.105,.97),(.015,1.08),(.18,1.02),(.21,.87),(.07,.76)]),
    ("gem_glint", "blue_hot", [(-.05,.87),(-.02,1.025),(.09,1.05),(.16,.96),(.05,.94)]),
    ("gem_spark", "white", [(.08,.91),(.09,.98),(.13,1.01),(.14,.91)]),
    ("gem_claw_left", "steel", [(-.17,.83),(-.14,1.08),(-.04,1.12),(.01,1.06),(-.09,1.03),(-.12,.82)]),
    ("gem_claw_right", "steel", [(.17,.77),(.25,.84),(.26,1.01),(.18,1.09),(.20,.94),(.12,.82)]),
    ("spine_fillet", "gold_light", [(.09,.56),(.15,.58),(.16,.70),(.10,.73)]),
]

atlas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
colours = {
    "gold": (153, 101, 25), "gold_light": (215, 163, 55),
    "dark": (24, 30, 39), "steel_shadow": (52, 64, 78),
    "steel": (139, 154, 164), "steel_light": (218, 229, 227),
    "blue": (10, 109, 242), "blue_hot": (54, 215, 255), "white": (239, 254, 255),
}
keys = list(colours)
for row, key in enumerate(keys):
    colour = colours[key]
    for y in range(row*28, min(256, row*28+28)):
        for x in range(256):
            grain = 0 if key.startswith("blue") or key == "white" else int(5*math.sin(x*0.17+y*0.47))
            shine = int(12*math.sin(x/256*math.pi))
            atlas.putpixel((x,y), tuple(min(255,max(0,v+grain+shine)) for v in colour)+(255,))
texture.parent.mkdir(parents=True, exist_ok=True)
atlas.save(texture)

lines=["# MCU Chitauri Scepter: swept silver horns, gold plated staff and exposed blue core."]
vertices={};uvs={};faces=[]
def face(name,points,material):
    row=keys.index(material);v=(row*28+14)/256
    refs=[]
    for (x,y,z),u in zip(points,(.25,.38,.62,.75)):
        vertex=(round(x,6),round(y,6),round(z,6));tex=(u,v)
        if vertex not in vertices:vertices[vertex]=len(vertices)+1
        if tex not in uvs:uvs[tex]=len(uvs)+1
        refs.append(f"{vertices[vertex]}/{uvs[tex]}")
    faces.append((name,refs))

for name,material,shape in parts:
    # The parser accepts quads. Three-sided surfaces have a repeated fourth
    # vertex; the normal still comes from the first three corners.
    z=.087 if name.startswith("gem") else .052 if "edge" in name else .070
    centre=(sum(p[0] for p in shape)/len(shape),sum(p[1] for p in shape)/len(shape))
    for i,a in enumerate(shape):
        b=shape[(i+1)%len(shape)]
        face(name+"_front",[(centre[0],centre[1],z),(a[0],a[1],z),(b[0],b[1],z),(b[0],b[1],z)],material)
        face(name+"_back",[(centre[0],centre[1],-z),(b[0],b[1],-z),(a[0],a[1],-z),(a[0],a[1],-z)],material)
        face(name+"_rim",[(a[0],a[1],-z),(b[0],b[1],-z),(b[0],b[1],z),(a[0],a[1],z)],"steel_shadow" if material.startswith("steel") else material)

model.parent.mkdir(parents=True,exist_ok=True)
for x,y,z in vertices:lines.append(f"v {x:.6f} {y:.6f} {z:.6f}")
for u,v in uvs:lines.append(f"vt {u:.6f} {v:.6f}")
for name,refs in faces:lines.extend(("g "+name,"f "+" ".join(refs)))
model.write_text("\n".join(lines)+"\n")
print(f"Wrote {model} ({len(vertices)} vertices, {len(faces)} quads) and {texture}")
