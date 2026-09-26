"""Check the Scepter's poses against each other: animations, renderer, preview and beam origin.

* Third person: every held frame of the aim, shot and fire clips points the staff horizontally
  forward through the full vanilla chain (renderer, PlayerAnimator item, item layer, arm, model flip),
  in both hands, and nothing ever animates the other arm.
* First person: WeaponRenderer's rest and aim poses match tools/preview_scepter.py, and the stone
  position ScepterFx starts the caster's beam from is where the generated model's stone lands in the
  raised pose.

Standard library only for the third-person checks; the first-person check imports the generator.
    python tools/verify_scepter_pose.py
"""
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'src/main/resources/assets/hexgodofstories'
JAVA = ROOT / 'src/main/java/com/hexgodofstories/client'


def rotate(v, axis, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    if axis == 'x':
        return (x, y * c - z * s, y * s + z * c)
    if axis == 'y':
        return (x * c + z * s, y, -x * s + z * c)
    return (x * c - y * s, x * s + y * c, z)


def third_person(arm, item):
    v = (0, 1, 0)
    # From the authored mesh outward, including vanilla's easy-to-miss Y=180.
    for axis, angle in [('y', 90), ('x', -20), ('x', item), ('y', 180), ('x', -90), ('x', arm), ('x', 180)]:
        v = rotate(v, axis, angle)
    return v


for clip in ('scepter_aim', 'scepter_shot', 'scepter_fire'):
    for suffix, arm, item in (('', 'rightArm', 'rightItem'), ('_left', 'leftArm', 'leftItem')):
        data = json.loads((ASSET / f'player_animation/{clip}{suffix}.json').read_text())
        other = 'leftArm' if arm == 'rightArm' else 'rightArm'
        for frame in data['emote']['moves']:
            assert other not in frame, (clip, suffix, frame)
            # The item layer's half turn reverses the item's pitch: the staff is level when
            # arm - item is -20, whatever the two are individually.
            level = frame[arm]['pitch'] - frame[item]['pitch']
            d = third_person(frame[arm]['pitch'], frame[item]['pitch'])
            if abs(level + 20) < 1e-6:
                assert abs(d[1]) < 1e-6 and d[2] > .999999, (clip, suffix, frame, d)
            elif frame[arm]['pitch'] < -45:
                # Recoil frames climb, but no more than a sixth of a right angle off the line of fire.
                assert d[1] > 0 and d[2] > .96, (clip, suffix, frame, d)
model = json.loads((ASSET / 'models/item/scepter.json').read_text())
for side in ('righthand', 'lefthand'):
    assert model['display']['firstperson_' + side] == model['display']['thirdperson_' + side]
print('PASS: aim, shot and fire hold the staff level and forward in both hands.')

renderer = (JAVA / 'WeaponRenderer.java').read_text()
preview = (ROOT / 'tools/preview_scepter.py').read_text()


def floats(text):
    return [float(x.rstrip('f')) for x in re.findall(r'-?\.?\d+\.?\d*f?', text)]


rest = floats(re.search(r'REST=euler\(([^)]*)\)', renderer).group(1))
aim = floats(re.search(r'AIM=euler\(([^)]*)\)', renderer).group(1))
offset = floats(re.search(r'AIM_X=([^,]*),AIM_Y=([^,]*),AIM_Z=([^,]*),AIM_SLIDE=([^;]*);', renderer).group(0).replace('AIM_', ' '))
assert rest == floats(re.search(r'\nREST = \(([^)]*)\)', preview).group(1)), rest
assert aim == floats(re.search(r'\nAIM = \(([^)]*)\)', preview).group(1)), aim
assert offset[:3] == floats(re.search(r'\nAIM_OFFSET = \(([^)]*)\)', preview).group(1)), offset
assert abs(offset[3] - float(re.search(r'\nAIM_SLIDE = ([\d.]+)', preview).group(1))) < 1e-9
print('PASS: renderer and preview agree on the first-person rest and aim poses.')

sys.path.insert(0, str(ROOT / 'scripts'))
import generate_scepter as gen  # noqa: E402

centre, _, _, _ = gen.gem_frame()
stone = gen.to_model(centre[0], centre[1], 0)
m = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def apply(v, angles):
    x, y, z = angles
    for axis, a in (('z', z), ('y', y), ('x', x)):
        v = rotate(v, axis, a)
    return v


local = (stone[0] * 2.3, (stone[1] - offset[3]) * 2.3, stone[2] * 2.3)
view = apply(local, aim)
view = (view[0] + .56 + offset[0], view[1] - .52 + offset[1], view[2] - .72 + offset[2])
fx = floats(re.search(r'STONE_X=([^,]*),STONE_Y=([^,]*),STONE_Z=([^;]*);', (JAVA / 'ScepterFx.java').read_text())
            .group(0).replace('STONE_', ' '))
assert all(abs(a - b) < .004 for a, b in zip(view, fx)), (view, fx)
print(f'PASS: the caster\'s beam leaves the drawn stone at {tuple(round(c, 3) for c in view)}.')
