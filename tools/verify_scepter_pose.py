"""Check the full vanilla held-item rotation chain against the firing reference."""
import json, math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'src/main/resources/assets/hexgodofstories'
def rotate(v,axis,degrees):
    a=math.radians(degrees);c,s=math.cos(a),math.sin(a);x,y,z=v
    return (x,y*c-z*s,y*s+z*c) if axis=='x' else (x*c+z*s,y,-x*s+z*c)
def direction(arm,item):
    v=(0,1,0)
    # Apply from the authored mesh outward, including vanilla's easy-to-miss Y=180.
    for axis,angle in [('y',90),('x',-20),('x',item),('y',180),('x',-90),('x',arm),('x',180)]:
        v=rotate(v,axis,angle)
    return v
for suffix,arm,item in [('', 'rightArm','rightItem'),('_left','leftArm','leftItem')]:
    clip=json.loads((ASSET/f'player_animation/scepter_fire{suffix}.json').read_text())
    for frame in clip['emote']['moves']:
        d=direction(frame[arm]['pitch'],frame[item]['pitch'])
        assert abs(d[0])<1e-7
        if frame['tick'] in (6,12):
            assert abs(d[1])<1e-7 and d[2]>.999999, (suffix,frame,d)
        else:
            assert d[1]<0 and d[2]>0, (suffix,frame,d)
        assert ('leftArm' if arm=='rightArm' else 'rightArm') not in frame
model=json.loads((ASSET/'models/item/scepter.json').read_text())
for side in ['righthand','lefthand']:
    assert model['display']['firstperson_'+side]==model['display']['thirdperson_'+side]
print('PASS: both hands fire horizontally forward; carry remains tip-down; display transforms match.')
