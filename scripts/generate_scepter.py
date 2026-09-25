"""Authored solid MCU scepter, grip at (0,0,0). Run from any directory.
Swept cross sections, bevelled blade strips and a faceted gem; no polygon fans.
The legacy OBJ name preserves existing saves. Units are Minecraft blocks.
"""
from pathlib import Path
from PIL import Image
import math, json
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'src/main/resources/assets/hexgodofstories'
COLORS={'gold':(153,108,37),'gold_edge':(223,179,78),'gold_dark':(84,58,25),
        'steel':(140,155,162),'edge':(224,234,235),'recess':(57,69,77),
        'grip':(28,32,36),'gem_blue':(12,105,248),'gem_light':(39,164,255),'gem_white':(104,218,255)}
keys=list(COLORS); vertices=[]; faces=[]
def face(name,pts,mat):
    if len(pts)==3:pts=pts+[pts[-1]]
    base=len(vertices);vertices.extend(pts);faces.append((name,list(range(base+1,base+5)),mat))
def tube(name,centres,radii,mat,sides=16):
    rings=[]
    for i,((x,y,z),r) in enumerate(zip(centres,radii)):
        a=centres[max(0,i-1)];b=centres[min(len(centres)-1,i+1)]
        dx,dy=b[0]-a[0],b[1]-a[1];l=math.hypot(dx,dy);nx,ny=dy/l,-dx/l
        rings.append([(x+nx*r*math.cos(t*2*math.pi/sides),y+ny*r*math.cos(t*2*math.pi/sides),z+r*math.sin(t*2*math.pi/sides)) for t in range(sides)])
    for i in range(len(rings)-1):
        for j in range(sides):face(name,[rings[i][j],rings[i][(j+1)%sides],rings[i+1][(j+1)%sides],rings[i+1][j]],mat)
    for ring,rev in ((rings[0],True),(rings[-1],False)):
        c=tuple(sum(p[k] for p in ring)/sides for k in range(3))
        for j in range(sides):face(name,[c,ring[(j+1)%sides],ring[j]] if rev else [c,ring[j],ring[(j+1)%sides]],mat)
def smooth(points,steps=6):
    out=[]
    for i in range(len(points)-1):
        a,b,c,d=points[max(0,i-1)],points[i],points[i+1],points[min(len(points)-1,i+2)]
        for j in range(steps):
            t=j/steps
            out.append(tuple(.5*((2*b[k])+(-a[k]+c[k])*t+(2*a[k]-5*b[k]+4*c[k]-d[k])*t*t+(-a[k]+3*b[k]-3*c[k]+d[k])*t*t*t) for k in range(len(b))))
    return out+[points[-1]]
def blade(name,rows):
    # Rows: outer edge X, inner edge X, height, ridge depth. Continuous beveled strip.
    rings=[]
    for left,right,y,z in smooth(rows,5):
        w=right-left
        rings.append([(left,y,0),(left+w*.16,y,z*.75),(left+w*.52,y,z),(right,y,0),
                      (left+w*.52,y,-z),(left+w*.16,y,-z*.75)])
    for a,b in zip(rings,rings[1:]):
        for j in range(6):face(name,[a[j],b[j],b[(j+1)%6],a[(j+1)%6]],'edge' if j in (0,5) else 'steel' if j in (1,4) else 'recess')
    for r in (rings[0],rings[-1]):
        for j in range(1,5):face(name,[r[0],r[j],r[j+1]],'steel')
# Curved, round shaft; the centre of the narrow hand grip is exactly the origin.
path=smooth([(.10,-1.12,0),(.055,-.96,0),(-.045,-.58,0),(0,0,0),(-.022,.27,0),(-.052,.48,0),(.00,.67,0)],8)
radii=[.037+.025*max(0,(-y-.65)/.47)+.012*max(0,(y-.20)/.47) for x,y,z in path]
tube('curved_gold_shaft',path,radii,'gold')
tube('rounded_pommel',[(.10,-1.15,0),(.108,-1.12,0),(.095,-1.055,0),(.081,-1.02,0)],[.012,.065,.066,.053],'gold_edge')
# Overlapping helical armour ribbons follow the actual shaft surface.
for side in (0,math.pi):
    for k in range(len(path)-1):
        row=[]
        for q,offset in ((k,0),(k,.5),(k+1,.5),(k+1,0)):
            x,y,z=path[q];a=y*11+side+offset;r=radii[q]+.003
            row.append((x+r*math.cos(a),y,z+r*math.sin(a)))
        face('spiral_armour',row,'gold_edge' if side==0 else 'gold_dark')
# Dark ribbed neck, enclosed by the pierced silver support rails.
tube('neck',[(0,.44,0),(.04,.73,0)],[.045,.036],'grip')
for j in range(15):
    y=.46+j*.017;x=(y-.44)*.04/.29
    tube('neck_rib',[(x,y,0),(x+.001,y+.007,0)],[.046,.046],'recess',12)
blade('long_swept_blade',[(-.13,-.062,.34,.025),(-.20,-.108,.60,.032),(-.175,-.080,.83,.037),(-.112,.011,1.04,.04),(-.004,.139,1.24,.033),(.142,.25,1.43,.023),(.33,.333,1.62,.001)])
blade('short_fork_outer',[(.008,.063,.30,.023),(.043,.133,.47,.031),(.124,.197,.65,.038),(.188,.251,.82,.033),(.224,.27,.99,.022),(.233,.236,1.14,.001)])
blade('short_fork_inner',[(.196,.249,.86,.02),(.188,.217,.997,.014),(.203,.206,1.105,.001)])
# Two separated braces, leaving open negative space under the gem.
for y in (.44,.53):
    tube('silver_crossbrace',[(-.025,y,-.005),(.095,y+.035,-.005)],[.012,.012],'steel',8)
tube('gold_socket',[(-.09,.705,0),(-.035,.731,0),(.034,.736,0),(.103,.716,0)],[.014]*4,'gold_edge',12)
# Elongated faceted oval blue stone, with volume on both faces.
cx,cy=.054,.849
rings=[]
for i in range(13):
    lat=-math.pi/2+math.pi*i/12
    rings.append([(cx+.077*math.cos(lat)*math.cos(j*2*math.pi/16),cy+.119*math.sin(lat),.068*math.cos(lat)*math.sin(j*2*math.pi/16)) for j in range(16)])
for i in range(12):
    for j in range(16):
        mat='gem_white' if (i*7+j*3)%23==0 else 'gem_light' if (i+j*3)%5==0 else 'gem_blue'
        face('gem_blue_facets',[rings[i][j],rings[i][(j+1)%16],rings[i+1][(j+1)%16],rings[i+1][j]],mat)
# Claws curve around the outer edge, without a plate covering the stone.
for z in (-.043,.043):
    tube('gem_claw',smooth([(-.094,.79,z),(-.077,.927,z),(.018,.977,z),(.119,.949,z),(.15,.902,z)],4),[.013]*17,'steel',8)
# Small pointed overlapping gold plates at the shoulder.
for n in range(3):
    y=.15+n*.13
    blade('gold_shoulder_%d'%n,[(-.061,-.059,y-.10,.003),(-.073,.004,y,.048),(-.058,.025,y+.17,.041)])
    for k in range(len(faces)-1,-1,-1):
        name,idx,mat=faces[k]
        if name!='gold_shoulder_%d'%n:break
        faces[k]=(name,idx,'gold_edge' if mat=='edge' else 'gold')
atlas=Image.new('RGBA',(256,320))
for row,key in enumerate(keys):
    base=COLORS[key]
    for y in range(row*32,(row+1)*32):
        for x in range(256):
            noise=3*math.sin(x*.51+y*1.31)+2*math.sin(x*.17-y*.77)
            shade=12*math.sin(x/255*math.pi)+noise
            atlas.putpixel((x,y),tuple(max(0,min(255,round(v+shade))) for v in base)+(255,))
atlas.save(ASSET/'textures/scepter.png')
lines=['# Solid curved Chitauri scepter. Grip at origin. Authored quad mesh.']
unique={}; remap={}
for i,p in enumerate(vertices,1):
    p=tuple(round(v,6) for v in p)
    if p not in unique:unique[p]=len(unique)+1
    remap[i]=unique[p]
lines += ['v %.6f %.6f %.6f'%p for p in unique]
for key in keys:
    v=(keys.index(key)*32+16)/320
    lines += ['vt %.6f %.6f'%(u,v) for u in (.2,.4,.7,.8)]
for name,ids,mat in faces:
    uv=keys.index(mat)*4+1
    lines.extend(['g '+name,'f '+' '.join(f'{remap[i]}/{uv+j}' for j,i in enumerate(ids))])
(ASSET/'models/laevateinn.obj').write_text('\n'.join(lines)+'\n')
print(f'{len(vertices)} vertices; {len(faces)} quad faces')
