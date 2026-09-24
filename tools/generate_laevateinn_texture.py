"""Recolor the sword's existing material bands without changing the daggers' shared atlas."""
from pathlib import Path
from PIL import Image

assets=Path(__file__).resolve().parents[1]/"src/main/resources/assets/hexgodofstories/textures"
source=Image.open(assets/"material.png").convert("RGBA")
out=Image.new("RGBA",source.size)
palette=[(75,169,252),(75,169,252),(217,239,255),(217,239,255),
         (75,140,224),(75,140,224),(17,36,73),(17,36,73),
         (28,48,95),(28,48,95),(36,65,110),(36,65,110),
         (122,183,255),(122,183,255),(24,36,64),(24,36,64)]
averages=[]
for band in range(16):
    left=band*source.width//16
    right=(band+1)*source.width//16
    samples=[source.getpixel((x,y)) for x in range(left,right) for y in range(0,source.height,8)]
    averages.append(sum((r+g+b)/3 for r,g,b,_ in samples)/len(samples))
for y in range(source.height):
    for x in range(source.width):
        r,g,b,a=source.getpixel((x,y))
        band=x*16//source.width
        brightness=((r+g+b)/3)/averages[band]
        out.putpixel((x,y),(*[min(255,int(c*brightness)) for c in palette[band]],a))
out.save(assets/"laevateinn_frost.png")
