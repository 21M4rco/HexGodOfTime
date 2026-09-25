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

# The blade bends toward authored +X. It must stay BELOW the shaft when firing.
blade=(1,0,0)
for axis,angle in [('y',90),('x',-20),('x',-70),('y',180),('x',-90),('x',-90),('x',180)]:
    blade=rotate(blade,axis,angle)
assert blade[1]<-.999999, blade
renderer=(ROOT/'src/main/java/com/hexgodofstories/client/WeaponRenderer.java').read_text()
held=renderer[renderer.index('private static void renderScepter'):]
assert '90-180*' not in held
assert held.count('Axis.YP.rotationDegrees(90)')==2
animations=(ROOT/'src/main/java/com/hexgodofstories/client/HexAnimations.java').read_text()
assert 'tickScepterView' not in animations
assert 'name.startsWith("scepter_")' in animations
print('PASS: blade stays down without axial spin; persistent first-person override removed.')
