"""Draws every blade move on the player model (tools/blade_rig.py) as contact sheets: `python3 tools/preview_blades.py OUT_DIR [names...]`."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import json
import blade_moves, blade_rig as rig
from PIL import Image, ImageDraw

ANIM = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/hexgodofstories/player_animation'


def load(name):
    """A move as the game will play it: read back from the generated player_animation file."""
    e = json.loads((ANIM / f'{name}.json').read_text())['emote']
    keys = [(m['tick'], m['easing'], {k: v for k, v in m.items() if k not in ('tick', 'easing')}) for m in e['moves']]
    return {'end': e['endTick'], 'keys': keys}

def sheet(name, out, size=230):
    m = load(name)
    model = 'deceiver' if any(k in name for k in ('sword', 'guard', 'run', 'deflect')) else 'dagger'
    ticks = sorted({t for t, _, _ in m['keys']} | ({blade_moves.CONTACT[name]} if name in blade_moves.CONTACT else set()))
    if name in blade_moves.CONTACT:
        c = blade_moves.CONTACT[name]
        ticks = sorted(set(ticks) | {c - 1, c + 1})
    ticks = [t for t in ticks if t <= m['end']][:8]
    views = ((215, 10), (110, 4))
    W = Image.new('RGB', (size * len(ticks), size * len(views) + 14), (18, 20, 24))
    for i, t in enumerate(ticks):
        f = rig.keyed(m, t)
        for j, (yaw, pitch) in enumerate(views):
            W.paste(rig.render(f, model, yaw, pitch, size, scale=size * .33), (i * size, j * size))
        c = blade_moves.CONTACT.get(name)
        ImageDraw.Draw(W).text((i * size + 4, size * len(views) + 1), f't{t}' + (' HIT' if t == c else ''), fill=(255, 200, 80) if t == c else (220, 220, 220))
    W.save(out)

if __name__ == '__main__':
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    for name in sys.argv[2:] or blade_moves.MOVES:
        sheet(name, out / f'{name}.png')
