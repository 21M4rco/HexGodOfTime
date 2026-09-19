"""Reproducible authored mesh, texture, animation and original sound assets. Python + Pillow + ffmpeg."""
from pathlib import Path
import math, json, random, wave, struct, subprocess
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]/'src/main/resources/assets/loki'
for directory in ['models','textures','sounds','player_animation']:(ROOT/directory).mkdir(parents=True,exist_ok=True)

class Mesh:
    def __init__(self):self.v=[];self.uv=[];self.faces=[]
    def quad(self,points,group='metal',band=0,uv=None):
        index=len(self.v)+1;self.v.extend(points)
        self.uv.extend(uv or [(band/8+.005,.03),((band+1)/8-.005,.03),((band+1)/8-.005,.97),(band/8+.005,.97)])
        self.faces.append((group,[index+i for i in range(4)]))
    def tube(self,centers,radii,group='metal',band=0,sides=12):
        rings=[]
        for i,(x,y,z) in enumerate(centers):
            prev=centers[max(0,i-1)];nxt=centers[min(len(centers)-1,i+1)];dy=nxt[1]-prev[1];dz=nxt[2]-prev[2];d=math.hypot(dy,dz) or 1
            rings.append([(x+math.cos(a*math.tau/sides)*radii[i],y-math.sin(a*math.tau/sides)*radii[i]*dz/d,z+math.sin(a*math.tau/sides)*radii[i]*dy/d) for a in range(sides)])
        for i in range(len(rings)-1):
            for a in range(sides):self.quad([rings[i][a],rings[i][(a+1)%sides],rings[i+1][(a+1)%sides],rings[i+1][a]],group,band)
    def blade(self,profile,group='blade',band=0):
        # Diamond cross section gives a real central ridge and two sharpened bevels.
        rings=[[(x-w,y,z),(x,y,z+th),(x+w,y,z),(x,y,z-th)] for y,w,th,x,z in profile]
        for i in range(len(rings)-1):
            for a in range(4):self.quad([rings[i][a],rings[i][(a+1)%4],rings[i+1][(a+1)%4],rings[i+1][a]],group,band if a%2 else min(7,band+1))
    def save(self,name):
        lines=['# Loki authored geometry: meters; grip at origin; no borrowed character meshes.']
        lines += ['v %.6f %.6f %.6f'%p for p in self.v]
        lines += ['vt %.6f %.6f'%p for p in self.uv]
        group=None
        for g,face in self.faces:
            if g!=group:lines.append('g '+g);group=g
            lines.append('f '+' '.join(f'{i}/{i}' for i in face))
        (ROOT/f'models/{name}.obj').write_text('\n'.join(lines)+'\n')

dagger=Mesh()
dagger.blade([(.12,.048,.022,0,0),(.19,.061,.021,0,0),(.3,.049,.018,.012,0),(.47,.037,.012,.017,0),(.65,.001,.001,0,0)])
dagger.tube([(0,y,0) for y in [-.17,-.15,-.12,0,.07,.10]],[.022,.035,.027,.028,.03,.024],'grip',3)
dagger.tube([(-.12,.08,.025),(-.09,.11,.015),(-.04,.125,0),(0,.115,0),(.04,.125,0),(.09,.11,.015),(.12,.08,.025)],[.005,.012,.017,.018,.017,.012,.005],'guard',2)
for i in range(8):
 y=-.12+i*.025;dagger.tube([(math.cos(a*math.tau/12)*.029,y,math.sin(a*math.tau/12)*.029) for a in range(13)],[.002]*13,'grip_wire',2,6)
dagger.blade([(.17,.005,.022,0,0),(.39,.003,.014,.012,0),(.46,.001,.012,.01,0)],'engraving',2)
dagger.save('dagger')

sword=Mesh()
sword.blade([(.2,.073,.032,0,0),(.32,.086,.028,0,0),(.52,.067,.023,.006,0),(.9,.052,.019,.008,0),(1.21,.029,.012,.006,0),(1.4,.001,.001,0,0)],band=0)
sword.tube([(0,y,0) for y in [-.28,-.24,-.2,.05,.16]],[.035,.057,.036,.034,.044],'leather',4)
sword.tube([(-.23,.25,.01),(-.2,.19,.01),(-.12,.13,0),(0,.15,0),(.12,.13,0),(.2,.19,.01),(.23,.25,.01)],[.003,.015,.025,.035,.025,.015,.003],'guard',2)
for i in range(11):
 y=-.2+i*.03;sword.tube([(math.cos(a*math.tau/12)*.037,y,math.sin(a*math.tau/12)*.037) for a in range(13)],[.003]*13,'grip',2,6)
for sign in [-1,1]:
 for i in range(10):
  y=.32+i*.075;sword.tube([(sign*.008,y,.03),(sign*.025,y+.025,.028),(sign*.008,y+.05,.028)],[.002]*3,'runes',2,5)
sword.save('laevateinn')

stick=Mesh()
stick.tube([(0,y,0) for y in [-.5,-.47,-.3,.0,.12,.5,.65,.71]],[.018,.03,.028,.028,.023,.023,.065,.012],'shaft',5,16)
stick.tube([(0,y,0) for y in [.56,.62,.69,.73]],[.04,.055,.055,.013],'temporal_core',6,16)
for i in range(9):
 y=-.32+i*.028;stick.tube([(math.cos(a*math.tau/12)*.031,y,math.sin(a*math.tau/12)*.031) for a in range(13)],[.004]*13,'grip',3,6)
stick.save('time_stick')

crown=Mesh()
# Sculpted dark headband curves around the head. Both horns taper into slightly asymmetric tips.
for row in range(4):
 for i in range(56):
  points=[]
  for a,r in [(i,row),(i+1,row),(i+1,row+1),(i,row+1)]:
   t=a*math.tau/56;u=r/4;points.append((math.sin(t)*(.27+.012*math.sin(u*math.pi)),-.40+u*.11-math.cos(t)*.025,math.cos(t)*.27))
  crown.quad(points,'crown',7)
for sign in [-1,1]:
 centers=[];radii=[]
 for i in range(33):
  t=i/32;centers.append((sign*(.22+.14*math.sin(t*math.pi*.85)),-.42-.82*t,-.13-.30*math.sin(t*math.pi*.88)+.18*t*t));radii.append(.068*(1-t)**.8+.001)
 crown.tube(centers,radii,'horn_'+str(sign),7,16)
 for ridge in range(3):
  path=[(x+sign*.025*math.cos(ridge*2.1),y,z+.035*math.sin(ridge*2.1)*(1-i/32)) for i,(x,y,z) in enumerate(centers)]
  crown.tube(path,[.004*(1-i/32)+.001 for i in range(33)],'horn_ridge',5,5)
crown.save('crown')

collar=Mesh()
for row in range(12):
 for i in range(40):
  points=[];uv=[]
  for a,r in [(i,row),(i+1,row),(i+1,row+1),(i,row+1)]:
   angle=a*math.tau/40;t=r/12;width=.19+.27*math.sin(t*math.pi/2);points.append((math.sin(angle)*width,-.07+t*.25+math.cos(angle)*.035,math.cos(angle)*(.19+.045*t)));uv.append((a/40,t))
  collar.quad(points,'mantle',0,uv)
collar.save('collar')

rng=random.Random(41091)
palette=[(157,177,164),(220,229,213),(139,118,62),(24,33,26),(36,45,29),(42,43,39),(239,161,62),(32,27,21)]
im=Image.new('RGB',(256,256))
for y in range(256):
 for x in range(256):
  band=x//32;c=palette[band];variation=rng.randrange(-5,6)+(3 if (x+y)%7==0 else 0);sheen=int(10*math.sin(x%32/31*math.pi)) if band<3 else 0
  im.putpixel((x,y),tuple(max(0,min(255,v+variation+sheen)) for v in c))
im.save(ROOT/'textures/material.png')
cloth=Image.new('RGB',(256,256))
for y in range(256):
 for x in range(256):
  fold=math.cos(x*.13)*3;weave=(x%3==0)-(y%3==0);noise=rng.randrange(-2,3);base=(18,34,26)
  c=tuple(max(0,int(v+fold+weave+noise)) for v in base)
  if x<3 or x>252 or y>250:c=(83,76,44)
  cloth.putpixel((x,y),c)
cloth.save(ROOT/'textures/cloth.png');Image.new('RGBA',(2,2),(255,255,255,255)).save(ROOT/'textures/white.png')

def animation(name,frames,end=30,loop=False):
 moves=[]
 for tick,right,left,body in frames:
  move={'tick':tick,'easing':'inoutquad','rightArm':dict(zip(['pitch','yaw','roll'],right)),'leftArm':dict(zip(['pitch','yaw','roll'],left))}
  if body is not None:move['torso']={'pitch':body[0],'yaw':body[1],'roll':body[2]}
  moves.append(move)
 content={'version':3,'name':name,'author':'LokiGPT','description':'Layered upper-body gesture; locomotion remains available.','emote':{'beginTick':0,'endTick':end,'stopTick':end,'isLoop':loop,'returnTick':10,'degrees':True,'moves':moves}}
 (ROOT/f'player_animation/{name}.json').write_text(json.dumps(content,indent=2))
zero=(0,0,0)
poses={'illusion':((-72,-35,-23),(-50,28,18)),'bolt':((-92,-8,2),(-12,8,-8)),'push':((-104,-12,-12),(-82,14,12)),'blink':((-34,-25,-12),(-42,20,14)),'ward':((-88,-32,-20),(-88,32,20)),'enchant':((-99,-4,-8),(-25,18,8)),'conjure':((-54,-14,-28),(-52,14,28)),'threads':((-90,-23,-16),(-96,22,16)),'time_stop':((-94,-6,-6),(-12,12,6)),'telekinesis':((-95,-9,-6),(-20,14,4))}
for name,(r,l) in poses.items():
 animation(name,[(0,zero,zero,zero),(5,(-25,-20,-12),(-15,15,10),(0,-4,0)),(10,r,l,(2,-7,0)),(20,r,l,(1,-4,0)),(30,zero,zero,zero)],30,name=='telekinesis')
animation('ascend',[(0,zero,zero,zero),(25,(-10,-7,-8),(-9,7,8),(-2,0,0)),(60,(-18,-10,-15),(-18,10,15),(-2,0,0)),(100,(-15,-8,-10),(-14,8,10),(0,0,0)),(140,zero,zero,zero)],140)
animation('time_slip',[(0,zero,zero,zero),(3,(-110,30,-32),(-34,-23,46),(-18,21,-12)),(6,(-42,-36,-55),(-98,32,18),(15,-32,8)),(10,(-122,22,-13),(-22,-24,33),(-12,16,-5)),(18,zero,zero,zero)],18)
animation('dagger_throw',[(0,zero,zero,zero),(5,(-172,-22,28),(-20,12,6),(0,-23,0)),(9,(-93,7,-4),(-16,13,8),(7,18,0)),(16,(-34,12,-6),(-10,6,3),(0,8,0)),(23,zero,zero,zero)],23)
for kind in ['dagger','twin','sword']:
 for combo in range(4):
  sign=-1 if combo%2==0 else 1;wind=8 if kind=='sword' else 4;end=20 if kind=='sword' else 12
  r=(-110,sign*65,-sign*30);l=(-40,-sign*22,12) if kind=='twin' else (-14,10,5)
  strike=(-60,-sign*50,sign*35);off=(-108,sign*40,-22) if kind=='twin' else (-20,13,8)
  animation(kind+'_'+str(combo),[(0,(-25,0,-8),(-15,0,8),zero),(wind-1,r,l,(-3,sign*22,0)),(wind+1,strike,off,(4,-sign*16,0)),(end,(-25,0,-8),(-15,0,8),zero)],end)

# Original procedural audio, no copied film tracks. Layered resonances distinguish magical and temporal sounds.
specs={'sorcery':(.6,620),'illusion':(.8,920),'teleport':(1.0,470),'time_slip':(1.3,130),'time_stop':(1.8,84),'time_resume':(.9,180),'ascend':(7.0,110),'conjure':(.7,1100)}
for name,(duration,base) in specs.items():
 rate=22050;samples=[]
 for i in range(int(rate*duration)):
  t=i/rate;u=t/duration;env=min(1,t*12)*(1-u)**1.5
  phase=math.tau*(base*t+base*.22*t*t/duration)
  sample=(math.sin(phase)*.35+math.sin(phase*1.502)*.16+math.sin(phase*2.003)*.09)*env
  sample+=rng.uniform(-1,1)*.018*env*(1+math.sin(t*70))
  if name=='time_slip':sample*=math.sin(t*31+math.sin(t*7)*4)
  if name=='ascend':sample*=.55+.45*math.sin(math.pi*u)
  samples.append(struct.pack('<h',int(max(-1,min(1,sample)) * 24000)))
 wav=ROOT/f'sounds/{name}.wav'
 with wave.open(str(wav),'wb') as out:out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate);out.writeframes(b''.join(samples))
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(wav),'-c:a','libvorbis','-q:a','4',str(ROOT/f'sounds/{name}.ogg')],check=True);wav.unlink()
(ROOT/'sounds.json').write_text(json.dumps({name:{'subtitle':'subtitles.loki.'+name,'sounds':[{'name':'loki:'+name,'stream':name=='ascend'}]} for name in specs},indent=2))
langpath=ROOT/'lang/en_us.json';lang=json.loads(langpath.read_text());lang.update({'subtitles.loki.'+n:n.replace('_',' ').capitalize() for n in specs});langpath.write_text(json.dumps(lang,indent=2))
print('Generated six 3D meshes, material textures, 25 layered animations and eight original sound events.')
