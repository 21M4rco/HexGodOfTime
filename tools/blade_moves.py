"""
Every move the dagger and The Deceiver make, as Player Animator key poses: the whole body, not an arm.

Read by tools/generate_blades.py, which writes them out as player_animation/blade_*.json, and by
tools/preview_blades.py, which draws them on the player model (through the game's own transform chain) so a move is
looked at before it ships.

Directions, as Player Animator applies them (its PlayerRendererMixin, AnimationApplier, HeldItemMixin):
  body   yaw + turns the whole body left; pitch + leans it back; roll + leans it left; z - moves it forward; y - lowers it
  head   yaw + looks right (so a head keeping its eyes ahead turns by the body's own yaw, same sign); pitch + looks down
  arms   pitch - raises forward; yaw + swings to the player's right; roll + lifts the right arm out (the left arm in)
  bend   arms: negative folds the elbow (forearm forward and up); legs: positive folds the knee (shin back)
  item   pitch 180 turns the blade over in the fist: the reverse (icepick) grip
The legs are keyed only where the move has footwork of its own; elsewhere they are left to walk and run.

References: the Winter Soldier's knife fight (reverse-grip flips, hammer stabs, a flip back into a lunging thrust,
kicks between cuts), Vinland Saga (low sweeping cuts off bent knees), and the longsword masters (the Zornhau from the
shoulder, the Zwerchhau across at the head, the thrust from the plough guard, the rising Unterhau); and for the
guard, Malenia: upright and easy, the sword arm hanging loose, the long blade angled down and out to the side.
"""

# ------------------------------------------------------------------ poses
# A pose is the body, head, legs and left arm as given; the sword arm and wrist are found (tools/blade_rig.py) to put
# the grip at `aim`'s point and the blade along its direction, both in the body's own frame (right, up from the
# feet, forward). `two` puts the left hand on the grip too, a fist below the right; `spin` adds whole turns of the
# blade in the hand (end over end) on the way into the key.

def P(body=(0, 0, 0, 0, 0, 0), head=(0, 0, 0), la=(0, 0, 0, 0), rl=None, ll=None, aim=None, two=False, spin=0, ra=None, item=None, rev=False,
      hint=None, lhand=None):
    """body (x, y, z, pitch, yaw, roll); head (pitch, yaw, roll); arms and legs (pitch, yaw, roll, bend)."""
    f = {'body': dict(zip(('x', 'y', 'z', 'pitch', 'yaw', 'roll'), body)),
         'leftArm': dict(zip(('pitch', 'yaw', 'roll', 'bend'), la))}
    # head=None leaves the head to the player: it goes on looking wherever they look (the guard and its slaps).
    if head is not None: f['head'] = dict(zip(('pitch', 'yaw', 'roll'), head))
    if ra is not None: f['rightArm'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), ra))
    if item is not None: f['rightItem'] = dict(zip(('pitch', 'yaw', 'roll'), item))
    if rl is not None: f['rightLeg'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), rl))
    if ll is not None: f['leftLeg'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), ll))
    # rev: the knife turned over in the fist (the icepick grip), so the wrist is solved from a reversed hold.
    # hint: the arm (pitch, yaw, roll, bend) the solve starts from, where the last key's would let it settle on a
    # reach no body makes (the sword arm swung up and back over the shoulder to point the blade across).
    # lhand: where the free hand goes (right, up, forward in the body's frame), its arm solved to reach it: a palm laid on
    # the blade, say.
    if aim is not None: f['aim'] = {'grip': aim[0], 'dir': aim[1], 'two': two, 'spin': spin, 'rev': rev, 'hint': hint, 'lhand': lhand}
    return f


def turned(frame, yaw):
    """The same pose with the whole body (and the eyes with it) turned a further `yaw`."""
    out = {k: dict(v) for k, v in frame.items()}
    out['body']['yaw'] = out['body'].get('yaw', 0) + yaw
    if 'head' in out: out['head']['yaw'] = out['head'].get('yaw', 0) + yaw
    return out


STILL = P(ra=(0, 0, 0, 0), item=(0, 0, 0))
NEUTRAL = P(ra=(0, 0, 0, 0), item=(0, 0, 0), rl=(0, 0, 0, 0), ll=(0, 0, 0, 0))
LEGS_READY = dict(rl=(16, 0, 0, 18), ll=(-18, 0, 0, 22))
GUARD_HAND = (-70, 26, 0, -78)

# The knife fighter's ready: low, left foot forward, right shoulder back, knife up at the chest, left hand guarding.
READY = P(body=(0, -.05, 0, -5, -12, 0), head=(0, -12, 0), la=GUARD_HAND, aim=((.2, 1.18, .42), (.05, .8, .6)), **LEGS_READY)
# The same with the knife turned over in the fist: the icepick grip, blade down along the forearm's line.
READY_REV = P(body=(0, -.05, 0, -5, -12, 0), head=(0, -12, 0), la=GUARD_HAND, aim=((.2, 1.18, .42), (.1, -.9, .35)), rev=True, **LEGS_READY)

# ------------------------------------------------------------------ moves
# Each: end tick, loop-from tick (None for a one-shot), and keys (tick, easing into it, pose).

MOVES = {}

def move(name, end, keys, loop=None):
    MOVES[name] = {'end': end, 'loop': loop, 'keys': keys}


# The blade forming in an empty hand: a flick out to the side, the knife spinning end over end into the grip.
move('blade_draw', 15, [
    (0, 'linear', P(ra=(0, 0, 0, 0), item=(360, 0, 0))),
    (6, 'linear', P(body=(0, -.03, 0, -3, -10, 0), head=(0, -10, 0), la=(-30, 10, 0, -30), aim=((.58, 1.0, .25), (.4, .3, .85)))),
    (10, 'outback', READY),
    (15, 'inoutsine', READY)])

# --- The Flurry (Conjure Daggers, held)
# 1. The flip: the knife turns over into an icepick grip as the arm coils to the left shoulder, then a backhand
#    slash across the throat, out to the right.
move('blade_dagger_0', 15, [
    (0, 'linear', READY),
    (3, 'inoutsine', P(body=(0, -.06, 0, -4, 26, 0), head=(0, 26, 0), la=(-52, 30, 0, -62), rl=(14, 0, 0, 20), ll=(-16, 0, 0, 24),
                     rev=True, aim=((-.18, 1.36, .28), (.75, -.5, -.2)))),
    (4, 'inquad', P(body=(0, -.08, -.08, -9, -10, 0), head=(0, -10, 0), la=(-56, 24, 0, -70), rl=(16, 0, 0, 24), ll=(-24, 0, 0, 30),
                    rev=True, aim=((.3, 1.42, .68), (.2, -.75, .6)))),
    (6, 'outquad', P(body=(0, -.08, -.08, -8, -38, 0), head=(0, -24, 0), la=(-40, 14, -10, -60), rl=(16, 0, 0, 24), ll=(-24, 0, 0, 30),
                     rev=True, aim=((.84, 1.38, .36), (.7, -.6, .3)))),
    (10, 'inoutsine', READY_REV),
    (15, 'inoutsine', READY_REV)])
# 2. The hammer: up over the head and driven down at the collarbone, the whole body dropping behind it.
move('blade_dagger_1', 14, [
    (0, 'linear', READY_REV),
    (2, 'outquad', P(body=(0, -.02, .02, 6, 10, 0), head=(-8, 10, 0), la=(-86, 16, 0, -22), rl=(14, 0, 0, 14), ll=(-16, 0, 0, 18),
                     rev=True, aim=((.2, 1.95, .15), (0, -.25, 1)))),
    (3, 'inquad', P(body=(0, -.13, -.06, -20, 0, 0), head=(10, 0, 0), la=(-30, 20, 0, -62), rl=(22, 0, 0, 38), ll=(-30, 0, 0, 42),
                    rev=True, aim=((.15, 1.12, .58), (0, -.55, .85)))),
    (5, 'outquad', P(body=(0, -.14, -.07, -22, 0, 0), head=(12, 0, 0), la=(-26, 20, 0, -60), rl=(22, 0, 0, 40), ll=(-30, 0, 0, 44),
                     rev=True, aim=((.13, .92, .55), (0, -.8, .6)))),
    (9, 'inoutsine', READY_REV),
    (14, 'inoutsine', READY_REV)])
# 3. The flip back: the knife turns over again into a forward grip as it is drawn to the hip, then a lunge and a
#    straight thrust, right shoulder driving through.
move('blade_dagger_2', 17, [
    (0, 'linear', READY_REV),
    (3, 'inoutsine', P(body=(0, -.06, .04, -2, -26, 0), head=(0, -26, 0), la=(-70, 22, 0, -72), rl=(18, 0, 0, 30), ll=(-14, 0, 0, 16),
                     aim=((.3, .98, .12), (0, .12, 1)))),
    (5, 'inexpo', P(body=(0, -.11, -.26, -12, 18, 0), head=(-4, 18, 0), la=(18, 6, -10, -12), rl=(32, 0, 0, 6), ll=(-46, 0, 0, 46),
                    aim=((.08, 1.22, .72), (0, .05, 1)))),
    (8, 'outquad', P(body=(0, -.11, -.27, -12, 20, 0), head=(-4, 20, 0), la=(20, 6, -10, -12), rl=(32, 0, 0, 6), ll=(-46, 0, 0, 46),
                     aim=((.08, 1.22, .74), (0, .05, 1)))),
    (12, 'inoutsine', READY),
    (17, 'inoutsine', READY)])
# 4. The sweep: wound up to the left low on bent knees, the knife cocked back across the body, then whipped out flat
#    to the right at the waist, the hips turning into it. No full turn: the feet stay where they are.
move('blade_dagger_3', 16, [
    (0, 'linear', READY),
    (2, 'outquad', P(body=(0, -.12, 0, -6, 30, 0), head=(0, 26, 0), la=(-60, 30, 0, -60), rl=(20, 0, 0, 40), ll=(-24, 0, 0, 44),
                     aim=((-.18, 1.0, .42), (-.7, -.1, .7)), hint=(-60, -30, 0, -40))),
    (4, 'inquad', P(body=(0, -.15, -.06, -9, 10, 0), head=(0, 8, 0), la=(-56, 28, 0, -62), rl=(22, 0, 0, 44), ll=(-28, 0, 0, 48),
                    aim=((.05, 1.0, .62), (-.15, -.05, 1)))),
    (6, 'outquad', P(body=(0, -.15, -.08, -10, -12, 0), head=(0, -10, 0), la=(-40, 14, -10, -60), rl=(22, 0, 0, 44), ll=(-28, 0, 0, 48),
                     aim=((.35, 1.05, .6), (.8, 0, .6)))),
    (8, 'outquad', P(body=(0, -.13, -.07, -8, -26, 0), head=(0, -18, 0), la=(-36, 10, -12, -58), rl=(20, 0, 0, 40), ll=(-26, 0, 0, 44),
                     aim=((.7, 1.08, .3), (1, .05, -.05)))),
    (12, 'inoutsine', READY),
    (16, 'inoutsine', READY)])
# 5. The kick: knee up, a push kick off the right leg into the body, leaning back from it, knife held back and low.
KNIFE_BACK = ((.45, .98, -.12), (.1, .35, .95))
move('blade_dagger_kick', 20, [
    (0, 'linear', READY),
    (3, 'outquad', P(body=(0, -.02, .03, 8, 0, 0), head=(-6, 0, 0), la=(-72, 14, 0, -40), rl=(-76, 0, 0, 86), ll=(0, 0, 0, 18),
                     aim=KNIFE_BACK)),
    (5, 'inexpo', P(body=(0, 0, .06, 16, 0, 0), head=(-10, 0, 0), la=(-78, 10, 0, -30), rl=(-96, 0, 0, 2), ll=(4, 0, 0, 22),
                    aim=KNIFE_BACK)),
    (8, 'outquad', P(body=(0, -.02, .03, 8, 0, 0), head=(-6, 0, 0), la=(-72, 14, 0, -44), rl=(-54, 0, 0, 62), ll=(0, 0, 0, 18),
                     aim=KNIFE_BACK)),
    (12, 'inoutsine', READY),
    # The knife twirled once in the fingers as the hand drops.
    (20, 'linear', P(ra=(0, 0, 0, 0), item=(360, 0, 0), rl=(0, 0, 0, 0), ll=(0, 0, 0, 0)))])

# --- The Master Cuts (The Deceiver, held). One hand: the free arm points the way in, is thrown back to balance a lunge,
#     rides out wide through a turn.
LA_GUIDE = (-82, 16, 0, -8)
LA_CHEST = (-58, 38, 0, -78)
LA_BACK = (40, 0, -40, -58)
LA_OUT = (-10, 0, -78, -8)
SWORD_REST = P(body=(0, -.03, 0, -2, -10, 0), head=(0, -10, 0), la=LA_CHEST, rl=(12, 0, 0, 12), ll=(-12, 0, 0, 14),
               aim=((.26, 1.05, .36), (.1, .55, .82)))
# 1. A stepping cut from over the right shoulder: the sword high and back, the free hand pointing the way, then a
#    step in and a cut down through the left hip, the body turning with it and the free arm swinging back.
move('blade_sword_0', 18, [
    (0, 'linear', SWORD_REST),
    (3, 'outquad', P(body=(0, -.04, .04, 5, -32, 0), head=(0, -32, 0), la=LA_GUIDE, rl=(18, 0, 0, 26), ll=(-10, 0, 0, 10),
                     aim=((.42, 1.72, -.04), (.15, .75, -.65)))),
    (5, 'inquad', P(body=(0, -.11, -.16, -14, 26, 0), head=(4, 26, 0), la=(25, -10, -30, -30), rl=(26, 0, 0, 10), ll=(-40, 0, 0, 40),
                    aim=((0, 1.2, .62), (-.5, -.35, .8)))),
    (7, 'outquad', P(body=(0, -.12, -.17, -18, 40, 0), head=(6, 36, 0), la=(40, -10, -35, -40), rl=(26, 0, 0, 10), ll=(-40, 0, 0, 42),
                     aim=((-.35, .85, .42), (-.7, -.6, .4)))),
    (12, 'inoutsine', SWORD_REST),
    (18, 'inoutsine', SWORD_REST)])
# 2. The cross cut (the Zwerchhau): the blade carried back past the left shoulder, then flat across at head height
#    and out to the right, the hips turning into it and the free arm flung the other way. The feet stay planted.
move('blade_sword_1', 18, [
    (0, 'linear', SWORD_REST),
    (3, 'outquad', P(body=(0, -.04, .02, 0, 32, 0), head=(0, 26, 0), la=(-30, 20, 0, -70), rl=(14, 0, 0, 18), ll=(-12, 0, 0, 16),
                     aim=((-.18, 1.32, .36), (-.65, .45, -.6)), hint=(-80, -30, 0, -60))),
    (5, 'inquad', P(body=(0, -.07, -.04, -4, 14, 0), head=(0, 10, 0), la=(-10, 0, -40, -20), rl=(18, 0, 0, 16), ll=(-22, 0, 0, 22),
                    aim=((-.02, 1.4, .58), (-.45, .05, .9)))),
    (7, 'linear', P(body=(0, -.08, -.06, -6, -8, 0), head=(0, -6, 0), la=(-4, 0, -64, -12), rl=(18, 0, 0, 16), ll=(-24, 0, 0, 24),
                    aim=((.32, 1.42, .56), (.65, 0, .76)))),
    (9, 'outquad', P(body=(0, -.08, -.06, -6, -24, 0), head=(0, -16, 0), la=(0, 0, -78, -8), rl=(18, 0, 0, 16), ll=(-24, 0, 0, 24),
                     aim=((.66, 1.42, .24), (.98, 0, .05)))),
    (13, 'inoutsine', SWORD_REST),
    (18, 'inoutsine', SWORD_REST)])
# 3. A fencer's lunge: the point drawn back beside the chest, the free hand guiding, then the front foot shooting
#    out, the point driven through and the free arm thrown up behind for balance.
move('blade_sword_2', 18, [
    (0, 'linear', SWORD_REST),
    (3, 'outquad', P(body=(0, -.06, .05, 2, -22, 0), head=(0, -22, 0), la=LA_GUIDE, rl=(20, 0, 0, 30), ll=(-12, 0, 0, 14),
                     aim=((.32, 1.15, .05), (0, .12, 1)))),
    (5, 'inexpo', P(body=(0, -.15, -.36, -14, 10, 0), head=(-6, 10, 0), la=LA_BACK, rl=(42, 0, 0, 2), ll=(-62, 0, 0, 62),
                    aim=((.12, 1.28, .85), (0, .02, 1)))),
    (8, 'outquad', P(body=(0, -.15, -.37, -14, 11, 0), head=(-6, 11, 0), la=LA_BACK, rl=(42, 0, 0, 2), ll=(-62, 0, 0, 62),
                     aim=((.12, 1.28, .87), (0, .02, 1)))),
    (13, 'inoutsine', SWORD_REST),
    (18, 'inoutsine', SWORD_REST)])
# 4. The launcher: dropping low with the blade trailing behind on the right, then exploding up off both feet, the cut
#    rising from the ground to high overhead and the free arm flung out.
move('blade_sword_3', 22, [
    (0, 'linear', SWORD_REST),
    (3, 'outquad', P(body=(0, -.28, .02, -16, -20, 0), head=(-10, -20, 0), la=(-40, 15, 0, -20), rl=(14, 0, 0, 82), ll=(-40, 0, 0, 84),
                     aim=((.45, .45, -.2), (.4, -.5, -.75)))),
    (6, 'inexpo', P(body=(0, .08, -.06, 10, 10, 0), head=(-14, 10, 0), la=(30, 0, -60, -20), rl=(10, 0, 0, 0), ll=(-10, 0, 0, 2),
                    aim=((.25, 1.95, .3), (.05, 1, .15)))),
    (8, 'outquad', P(body=(0, .05, -.06, 12, 14, 0), head=(-18, 14, 0), la=(36, 0, -64, -20), rl=(8, 0, 0, 0), ll=(-8, 0, 0, 0),
                     aim=((.2, 2.08, .05), (0, .9, -.4)))),
    (14, 'inoutsine', SWORD_REST),
    (22, 'inoutsine', NEUTRAL)])

# --- Ordinary attacks: each its own whole little move, beginning and ending at rest. Contact on the hit tick.
# Daggers (contact 4): a forehand cut, a backhand, the icepick hook (flip and flip back), and a stab.
STEP = dict(rl=(16, 0, 0, 14), ll=(-22, 0, 0, 22))
move('blade_m_dagger_0', 12, [
    (0, 'linear', STILL),
    (2, 'outquad', P(body=(0, -.03, .02, 2, -22, 0), head=(0, -22, 0), la=(-56, 20, 0, -60), aim=((.52, 1.6, .2), (.4, .8, .4)))),
    (4, 'inquad', P(body=(0, -.06, -.08, -8, 16, 0), head=(0, 16, 0), la=(-40, 24, 0, -60), aim=((.05, 1.22, .6), (-.4, -.2, .9)), **STEP)),
    (6, 'outquad', P(body=(0, -.06, -.08, -9, 26, 0), head=(0, 22, 0), la=(-36, 24, 0, -58), aim=((-.35, 1.0, .4), (-.8, -.5, .3)), **STEP)),
    (12, 'inoutsine', STILL)])
move('blade_m_dagger_1', 12, [
    (0, 'linear', STILL),
    (2, 'outquad', P(body=(0, -.03, .02, 0, 26, 0), head=(0, 26, 0), la=(-60, 30, 0, -62), aim=((-.2, 1.35, .3), (-.9, .2, .3)))),
    (4, 'inquad', P(body=(0, -.06, -.08, -6, -10, 0), head=(0, -10, 0), la=(-46, 26, 0, -62), aim=((.25, 1.3, .6), (.4, 0, .9)), **STEP)),
    (6, 'outquad', P(body=(0, -.06, -.08, -6, -26, 0), head=(0, -20, 0), la=(-46, 24, 0, -62), aim=((.7, 1.3, .25), (.95, .1, .2)), **STEP)),
    (12, 'inoutsine', STILL)])
move('blade_m_dagger_2', 14, [
    (0, 'linear', STILL),
    (3, 'inoutsine', P(body=(0, -.03, .02, 3, -16, 0), head=(0, -16, 0), la=(-60, 24, 0, -62), rev=True, aim=((.45, 1.68, .2), (.1, -.6, .8)))),
    (4, 'inquad', P(body=(0, -.08, -.08, -12, 16, 0), head=(4, 16, 0), la=(-40, 24, 0, -60), rev=True, aim=((.1, 1.15, .6), (-.3, -.7, .6)),
                    rl=(18, 0, 0, 18), ll=(-24, 0, 0, 26))),
    (6, 'outquad', P(body=(0, -.08, -.08, -12, 22, 0), head=(4, 20, 0), la=(-36, 24, 0, -58), rev=True, aim=((-.2, .95, .45), (-.5, -.8, .2)),
                     rl=(18, 0, 0, 18), ll=(-24, 0, 0, 26))),
    (10, 'inoutsine', P(ra=(0, 0, 0, 0), item=(360, 0, 0))),
    (14, 'linear', P(ra=(0, 0, 0, 0), item=(360, 0, 0)))])
move('blade_m_dagger_3', 12, [
    (0, 'linear', STILL),
    (2, 'outquad', P(body=(0, -.04, .04, 0, -20, 0), head=(0, -20, 0), la=(-62, 24, 0, -66), aim=((.3, 1.0, .15), (0, .1, 1)))),
    (4, 'inexpo', P(body=(0, -.07, -.16, -9, 14, 0), head=(-3, 14, 0), la=(12, 6, -8, -12), aim=((.15, 1.24, .68), (0, .05, 1)),
                    rl=(22, 0, 0, 6), ll=(-30, 0, 0, 30))),
    (6, 'outquad', P(body=(0, -.07, -.16, -9, 15, 0), head=(-3, 15, 0), la=(12, 6, -8, -12), aim=((.15, 1.24, .7), (0, .05, 1)),
                     rl=(22, 0, 0, 6), ll=(-30, 0, 0, 30))),
    (12, 'inoutsine', STILL)])
# The Deceiver, one hand (contact 6): the cut from over the shoulder, a rising backhand, a forehand flat across, and the
# parting cut from overhead, the free arm working against each.
SSTEP = dict(rl=(20, 0, 0, 12), ll=(-30, 0, 0, 30))
move('blade_m_sword_0', 18, [
    (0, 'linear', STILL),
    (4, 'outquad', P(body=(0, -.03, .03, 4, -28, 0), head=(0, -28, 0), la=(-75, 15, 0, -10), aim=((.45, 1.7, -.02), (.15, .75, -.65)))),
    (6, 'inquad', P(body=(0, -.08, -.1, -12, 22, 0), head=(4, 22, 0), la=(20, -10, -25, -30), aim=((.02, 1.2, .6), (-.5, -.35, .8)), **SSTEP)),
    (8, 'outquad', P(body=(0, -.09, -.1, -14, 34, 0), head=(5, 30, 0), la=(34, -10, -30, -36), aim=((-.33, .85, .42), (-.7, -.6, .4)), **SSTEP)),
    (18, 'inoutsine', STILL)])
move('blade_m_sword_1', 18, [
    (0, 'linear', STILL),
    (4, 'outquad', P(body=(0, -.08, .02, -6, 26, 0), head=(0, 26, 0), la=(10, 0, -30, -20), aim=((-.35, .75, .3), (-.5, -.6, .6)),
                     rl=(10, 0, 0, 30), ll=(-18, 0, 0, 34))),
    (6, 'inquad', P(body=(0, -.04, -.06, -4, 0, 0), head=(-4, 0, 0), la=(-20, 0, -40, -24), aim=((.15, 1.25, .62), (.4, .5, .75)),
                    rl=(16, 0, 0, 14), ll=(-24, 0, 0, 18))),
    (8, 'outquad', P(body=(0, 0, -.06, 6, -20, 0), head=(-10, -20, 0), la=(-40, 0, -40, -30), aim=((.55, 1.75, .2), (.4, .85, -.1)),
                     rl=(16, 0, 0, 8), ll=(-24, 0, 0, 10))),
    (18, 'inoutsine', STILL)])
move('blade_m_sword_2', 18, [
    (0, 'linear', STILL),
    (4, 'outquad', P(body=(0, -.04, .03, 0, -32, 0), head=(0, -32, 0), la=(-70, 20, 0, -20), aim=((.62, 1.32, .18), (.9, .05, .3)))),
    (6, 'inquad', P(body=(0, -.07, -.08, -8, 10, 0), head=(0, 10, 0), la=(0, 0, -50, -20), aim=((.05, 1.32, .62), (-.2, 0, 1)), **SSTEP)),
    (8, 'outquad', P(body=(0, -.07, -.08, -8, 32, 0), head=(0, 28, 0), la=(20, 0, -60, -20), aim=((-.5, 1.25, .32), (-.95, 0, .3)), **SSTEP)),
    (18, 'inoutsine', STILL)])
move('blade_m_sword_3', 18, [
    (0, 'linear', STILL),
    (4, 'outquad', P(body=(0, -.01, .04, 9, 0, 0), head=(-10, 0, 0), la=(-80, 10, 0, -10), aim=((.35, 2.0, -.05), (0, .6, -.8)))),
    (6, 'inquad', P(body=(0, -.09, -.13, -15, 0, 0), head=(8, 0, 0), la=(30, 0, -30, -40), aim=((.18, 1.32, .66), (0, .1, 1)),
                    rl=(22, 0, 0, 12), ll=(-34, 0, 0, 34))),
    (8, 'outquad', P(body=(0, -.10, -.13, -18, 0, 0), head=(10, 0, 0), la=(36, 0, -30, -44), aim=((.18, .9, .56), (0, -.7, .7)),
                     rl=(22, 0, 0, 12), ll=(-34, 0, 0, 34))),
    (18, 'inoutsine', STILL)])

# --- The Deceiver's guard (the use key held): Malenia's ease, upright, the sword arm loose at the side and the long
#     blade angled down and out with its point near the ground ahead. Its walk, the charge, and the slaps.
#     The head is never keyed here: it goes on looking wherever the player looks. Nor is the body turned (Player
#     Animator's body turn carries the head with it), only leaned and swayed; the arm and the legs carry the rest.
GUARD = P(body=(0, 0, 0, 0, 0, 1), head=None, la=(4, 0, -6, -8), aim=((.46, .8, 0), (.72, -.5, .48)))
GUARD_BREATH = P(body=(0, -.008, 0, 0, 0, 2), head=None, la=(6, 0, -8, -10), aim=((.46, .79, .01), (.72, -.52, .46)))
# Into the guard: from the ordinary carry, the sword swept up and out to the side, then let fall into the stance.
CARRY = P(head=None, la=(0, 0, 0, 0), aim=((.32, .95, .25), (0, .5, .85)))
move('blade_guard_enter', 12, [
    (0, 'linear', CARRY),
    (4, 'outquad', P(body=(0, -.02, 0, 2, 0, 3), head=None, la=(-12, 0, -14, -10), aim=((.52, 1.25, .12), (.65, .7, .3)))),
    (9, 'outback', GUARD),
    (12, 'inoutsine', GUARD)])
move('blade_guard', 44, [(0, 'inoutsine', GUARD), (4, 'inoutsine', GUARD), (24, 'inoutsine', GUARD_BREATH), (44, 'inoutsine', GUARD)], loop=4)
WALK_A = P(body=(0, 0, 0, -2, 0, 3), head=None, la=(-22, 0, -6, -14), aim=((.46, .82, .04), (.72, -.48, .5)))
WALK_B = P(body=(0, 0, 0, -2, 0, -1), head=None, la=(22, 0, -6, -6), aim=((.46, .8, -.03), (.72, -.52, .46)))
move('blade_guard_walk', 22, [(0, 'inoutsine', GUARD), (2, 'inoutsine', WALK_A), (12, 'inoutsine', WALK_B), (22, 'inoutsine', WALK_A)], loop=2)
RUN_A = P(body=(0, 0, 0, -16, 0, 0), head=(-14, 0, 0), la=(-58, 0, 0, -70), aim=((.4, .82, -.3), (.2, -.35, -.9)))
RUN_B = P(body=(0, 0, 0, -16, 0, 0), head=(-14, 0, 0), la=(46, 0, 0, -60), aim=((.42, .86, -.38), (.2, -.3, -.92)))
move('blade_run', 14, [(0, 'inoutsine', RUN_A), (2, 'inoutsine', RUN_A), (8, 'inoutsine', RUN_B), (14, 'inoutsine', RUN_A)], loop=2)
# The slap that turns a shot or a blow aside: violent and wide. From the low guard the whole arm whips out to full
# stretch across the line of the attack, the body twisting and stepping into it, overshoots past the strike, and
# only then sinks back into the guard.
move('blade_deflect_r', 16, [
    (0, 'linear', GUARD),
    (1, 'outquad', P(body=(0, -.02, .02, 2, 0, -2), head=None, la=(-20, 10, 0, -30), aim=((.4, .95, .05), (.5, -.2, .6)),
                     rl=(6, 0, 0, 8), ll=(-6, 0, 0, 8))),
    (3, 'inexpo', P(body=(0, -.07, -.06, -7, -8, 6), head=None, la=(-40, 30, 0, -50), aim=((.8, 1.5, .42), (.75, .62, .2)),
                    rl=(20, 0, 0, 18), ll=(-24, 0, 0, 24))),
    (5, 'outquad', P(body=(0, -.08, -.07, -7, -10, 8), head=None, la=(-36, 30, 0, -50), aim=((.88, 1.62, .05), (.6, .7, -.4)),
                     rl=(20, 0, 0, 18), ll=(-24, 0, 0, 24))),
    (8, 'outquad', P(body=(0, -.05, -.04, -4, -6, 5), head=None, la=(-30, 20, 0, -40), aim=((.82, 1.5, .1), (.7, .6, -.3)),
                     rl=(14, 0, 0, 12), ll=(-16, 0, 0, 16))),
    (16, 'inoutsine', GUARD)])
# To the left the sword arm reaches across the front of the chest, the blade upright, never up and back over the shoulder.
move('blade_deflect_l', 16, [
    (0, 'linear', GUARD),
    (1, 'outquad', P(body=(0, -.02, .02, 2, 0, 2), head=None, la=(-10, 0, -20, -20), aim=((.5, .95, .08), (.7, -.2, .5)),
                     rl=(6, 0, 0, 8), ll=(-6, 0, 0, 8))),
    (3, 'inexpo', P(body=(0, -.07, -.06, -7, 10, -6), head=None, la=(10, 0, -50, -30), aim=((-.06, 1.3, .55), (-.55, .75, .35)),
                    rl=(20, 0, 0, 18), ll=(-24, 0, 0, 24), hint=(-75, -25, 10, -30))),
    (5, 'outquad', P(body=(0, -.08, -.07, -7, 12, -8), head=None, la=(16, 0, -56, -30), aim=((-.22, 1.36, .5), (-.85, .5, .15)),
                     rl=(20, 0, 0, 18), ll=(-24, 0, 0, 24), hint=(-80, -35, 10, -20))),
    (8, 'outquad', P(body=(0, -.05, -.04, -4, 6, -5), head=None, la=(10, 0, -46, -26), aim=((-.14, 1.28, .5), (-.75, .6, .25)),
                     rl=(14, 0, 0, 12), ll=(-16, 0, 0, 16), hint=(-75, -30, 10, -25))),
    (16, 'inoutsine', GUARD)])

# --- The charge's cut (sprinting, the attack key): both hands on the grip, the sword swung up overhead in the stride
#     and slammed straight down through whatever is ahead, the body folding over it into a deep lunge.
RUNNING = P(body=(0, 0, 0, -16, 0, 0), head=(-14, 0, 0), la=(-58, 0, 0, -70), aim=((.4, .82, -.3), (.2, -.35, -.9)))
move('blade_sword_dash', 20, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, .03, .02, 8, 0, 0), head=(-12, 0, 0), rl=(-46, 0, 0, 64), ll=(22, 0, 0, 30),
                     aim=((.08, 1.98, .14), (0, .55, -.83)), two=True, hint=(-160, 10, 0, -20))),
    (6, 'inexpo', P(body=(0, -.22, -.3, -28, 0, 0), head=(12, 0, 0), rl=(40, 0, 0, 4), ll=(-60, 0, 0, 64),
                    aim=((.06, 1.08, .72), (0, -.5, .87)), two=True)),
    (8, 'outquad', P(body=(0, -.25, -.33, -32, 0, 0), head=(14, 0, 0), rl=(42, 0, 0, 4), ll=(-62, 0, 0, 66),
                     aim=((.05, .8, .74), (0, -.82, .57)), two=True)),
    (11, 'inoutsine', P(body=(0, -.24, -.32, -30, 0, 0), head=(12, 0, 0), rl=(42, 0, 0, 4), ll=(-62, 0, 0, 66),
                        aim=((.06, .84, .72), (0, -.8, .6)), two=True)),
    (20, 'inoutsine', NEUTRAL)])

# --- The running cuts (sprinting, the attack key, one after another): one hand, the body leaning into the run and the
#     legs left to it, each cut out of the run and back into it. Every one a different line, and each starts on the
#     side the one before ended (server/HexServer, RUN_CUTS): flat across to the left and back across to the right,
#     down from either shoulder, rising from either hip, and the rising cut up the middle that the slam comes down out of.
# 1. Flat across to the left: the sword drawn out wide on the right and whipped across at the chest, the hips turning in.
move('blade_sword_run_0', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.02, .02, -10, -34, 0), head=(-8, -34, 0), la=(-70, 20, 0, -20), aim=((.62, 1.3, .16), (.9, .08, .25)))),
    (5, 'inquad', P(body=(0, -.06, -.1, -16, 8, 0), head=(-12, 8, 0), la=(0, 0, -50, -20), aim=((.05, 1.3, .66), (-.2, 0, 1)))),
    (7, 'outquad', P(body=(0, -.06, -.1, -16, 30, 0), head=(-12, 26, 0), la=(20, 0, -60, -20), aim=((-.4, 1.22, .46), (-.95, -.05, .25)))),
    (14, 'inoutsine', RUNNING)])
# 2. Flat back across to the right at the head: the blade carried back past the left shoulder, then out to the right.
move('blade_sword_run_1', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.02, .02, -10, 30, 0), head=(-8, 26, 0), la=(-30, 20, 0, -70), aim=((-.18, 1.34, .38), (-.65, .45, -.6)),
                     hint=(-80, -30, 0, -60))),
    (5, 'inquad', P(body=(0, -.06, -.08, -16, -4, 0), head=(-12, -4, 0), la=(-4, 0, -64, -12), aim=((.1, 1.42, .64), (.05, 0, 1)))),
    (7, 'outquad', P(body=(0, -.06, -.08, -15, -28, 0), head=(-11, -22, 0), la=(0, 0, -78, -8), aim=((.66, 1.4, .28), (.97, 0, .12)))),
    (14, 'inoutsine', RUNNING)])
# 3. The rising cut up the middle: the blade trailing low on the right, then brought up through the body to high
#    overhead, the bearer coming up out of the lean with it.
move('blade_sword_run_2', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.08, .02, -20, -22, 0), head=(-14, -22, 0), la=(-40, 15, 0, -20), aim=((.46, .74, .12), (.35, -.6, -.7)))),
    (5, 'inexpo', P(body=(0, 0, -.06, -6, 4, 0), head=(-8, 4, 0), la=(20, 0, -50, -20), aim=((.2, 1.45, .58), (0, .75, .65)))),
    (7, 'outquad', P(body=(0, .03, -.06, 6, 10, 0), head=(-14, 10, 0), la=(30, 0, -60, -20), aim=((.18, 1.95, .3), (0, 1, -.1)))),
    (14, 'inoutsine', RUNNING)])
# 4. Down from over the right shoulder through the left hip, the body folding over the cut.
move('blade_sword_run_3', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.02, .02, -8, -28, 0), head=(-6, -28, 0), la=(-75, 15, 0, -10), aim=((.45, 1.68, -.02), (.15, .75, -.65)))),
    (5, 'inquad', P(body=(0, -.08, -.1, -20, 20, 0), head=(-10, 20, 0), la=(20, -10, -25, -30), aim=((.02, 1.18, .62), (-.5, -.35, .8)))),
    (7, 'outquad', P(body=(0, -.09, -.1, -22, 32, 0), head=(-10, 28, 0), la=(34, -10, -30, -36), aim=((-.33, .85, .45), (-.7, -.6, .4)))),
    (14, 'inoutsine', RUNNING)])
# 5. Rising from the left hip, a backhand up and out past the right shoulder.
move('blade_sword_run_4', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.08, .02, -16, 26, 0), head=(-10, 26, 0), la=(10, 0, -30, -20), aim=((-.28, .82, .44), (-.5, -.6, .6)))),
    (5, 'inquad', P(body=(0, -.04, -.06, -12, 0, 0), head=(-10, 0, 0), la=(-20, 0, -40, -24), aim=((.15, 1.25, .64), (.4, .5, .75)))),
    (7, 'outquad', P(body=(0, 0, -.06, -4, -20, 0), head=(-14, -20, 0), la=(-40, 0, -40, -30), aim=((.55, 1.72, .22), (.4, .85, -.1)))),
    (14, 'inoutsine', RUNNING)])
# 6. Down from high on the left to the right hip: the arm reaching across in front of the face, the blade up and out to
#    the left (never up and back over the shoulder), and a backhand down through the body.
move('blade_sword_run_5', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.01, .02, -8, 24, 0), head=(-6, 22, 0), la=(10, 0, -30, -20), aim=((-.15, 1.65, .34), (-.7, .55, .3)),
                     hint=(-120, -30, 0, -40))),
    (5, 'inquad', P(body=(0, -.07, -.1, -18, -6, 0), head=(-10, -6, 0), la=(-10, 0, -50, -20), aim=((.2, 1.2, .64), (.45, -.4, .8)))),
    (7, 'outquad', P(body=(0, -.08, -.1, -20, -26, 0), head=(-10, -22, 0), la=(0, 0, -70, -10), aim=((.55, .85, .42), (.7, -.6, .35)))),
    (14, 'inoutsine', RUNNING)])
# 7. Rising from the right hip up across the body and out past the left shoulder.
move('blade_sword_run_6', 14, [
    (0, 'linear', RUNNING),
    (3, 'outquad', P(body=(0, -.08, .02, -16, -26, 0), head=(-10, -26, 0), la=(-60, 20, 0, -40), aim=((.55, .78, .22), (.55, -.6, .55)))),
    (5, 'inquad', P(body=(0, -.04, -.06, -12, 4, 0), head=(-10, 4, 0), la=(0, 0, -50, -20), aim=((.05, 1.3, .64), (-.35, .5, .8)))),
    (7, 'outquad', P(body=(0, 0, -.06, -4, 26, 0), head=(-14, 22, 0), la=(20, 0, -60, -20), aim=((-.28, 1.66, .46), (-.45, .85, .15)))),
    (14, 'inoutsine', RUNNING)])

# --- Complete Evisceration (G with The Deceiver in hand). The dash: the sword arm cocked back at the hip, the point
#     straight ahead, the free hand reaching out at the body, the bearer leaning into it; the legs are left to the run.
#     Held until the body is reached (server/Evisceration), then the thrust: the arm thrown out to its full length
#     in a deep lunge, the blade driven in at the gut and out of the back, held there and leaned on. A body it would not
#     kill is let off it (the thrust's pull-out); one it would is finished by the cut: the blade torn out, a step back,
#     and the sword brought down from high on the left through the body to the right hip, fast and with everything
#     behind it, cutting it in two along that line.
EVIS_COCKED = P(body=(0, -.06, 0, -16, -14, 0), head=(-12, -14, 0), la=(-85, 15, 0, -6), aim=((.32, 1.14, .06), (0, .08, 1)), hint=(-20, 10, 0, -80))
# The thrust at full stretch: the lunge deeper, the right shoulder driven through after the arm (the body turned a
# little to the left), so the point comes well out of the back of the body.
EVIS_IN = P(body=(0, -.18, -.5, -18, 16, 0), head=(-8, 16, 0), la=(44, 0, -44, -60), rl=(50, 0, 0, 2), ll=(-70, 0, 0, 66),
            aim=((.3, 1.24, .82), (.2, -.05, 1)), hint=(-85, 0, 0, -5))
EVIS_DEEP = P(body=(0, -.19, -.53, -19, 17, 0), head=(-8, 17, 0), la=(46, 0, -46, -62), rl=(52, 0, 0, 2), ll=(-72, 0, 0, 68),
              aim=((.3, 1.23, .86), (.2, -.05, 1)), hint=(-85, 0, 0, -5))
move('blade_sword_evis_dash', 16, [
    (0, 'linear', STILL),
    (2, 'outquad', EVIS_COCKED),
    (12, 'linear', EVIS_COCKED),
    (16, 'inoutsine', STILL)])
move('blade_sword_evis_thrust', 20, [
    (0, 'linear', EVIS_COCKED),
    (2, 'inexpo', EVIS_IN),
    (5, 'outquad', EVIS_DEEP),
    (9, 'linear', EVIS_DEEP),
    # Torn back out of it, the bearer rising out of the lunge.
    (12, 'inquad', P(body=(0, -.06, -.1, -6, -10, 0), head=(-4, -10, 0), la=(-30, 10, -20, -40), rl=(20, 0, 0, 10), ll=(-24, 0, 0, 24),
                     aim=((.32, 1.14, .12), (0, .06, 1)), hint=(-20, 10, 0, -80))),
    (20, 'inoutsine', STILL)])
move('blade_sword_evis_cut', 20, [
    (0, 'linear', EVIS_DEEP),
    # Out, and a step back off it.
    (2, 'outquad', P(body=(0, -.04, .12, -4, -6, 0), head=(-2, -6, 0), la=(-40, 10, -20, -40), rl=(30, 0, 0, 8), ll=(-20, 0, 0, 22),
                     aim=((.3, 1.14, .3), (0, .1, 1)), hint=(-20, 10, 0, -80))),
    # Up high on the left, the arm across the front of the face, the blade up and out to the left.
    (4, 'outquad', P(body=(0, -.01, .1, -4, 26, 0), head=(-6, 22, 0), la=(10, 0, -30, -20), rl=(30, 0, 0, 8), ll=(-20, 0, 0, 22),
                     aim=((-.15, 1.68, .32), (-.7, .55, .3)), hint=(-120, -30, 0, -40))),
    # Down through it, everything behind it: the weight thrown onto the front foot.
    (6, 'inquad', P(body=(0, -.1, -.08, -18, -6, 0), head=(-6, -6, 0), la=(-10, 0, -50, -20), rl=(24, 0, 0, 14), ll=(-34, 0, 0, 36),
                    aim=((.2, 1.16, .66), (.6, -.6, .55)))),
    (8, 'outquad', P(body=(0, -.13, -.1, -22, -30, 0), head=(-6, -26, 0), la=(0, 0, -70, -10), rl=(26, 0, 0, 16), ll=(-36, 0, 0, 40),
                     aim=((.6, .74, .4), (.7, -.62, .35)))),
    # Held there a moment, the blade low and out to the right, before it comes up again.
    (12, 'inoutsine', P(body=(0, -.12, -.09, -20, -28, 0), head=(-6, -24, 0), la=(4, 0, -66, -12), rl=(24, 0, 0, 14), ll=(-34, 0, 0, 36),
                        aim=((.6, .76, .38), (.7, -.6, .36)))),
    (20, 'inoutsine', STILL)])

# --- The Deceiver at rest (in hand, the bearer standing still a while): weight on one leg, the sword hanging from a
#     loose wrist a little ahead, its point resting on the ground in front, the free hand on the hip. The head is the
#     player's own.
IDLE = P(body=(0, -.01, 0, -1, 0, -2), head=None, la=(20, 65, -40, -100), rl=(-2, 0, 2, 0), ll=(-8, 0, -4, 14),
         aim=((.38, .8, .2), (.22, -.58, .78)))
IDLE_BREATH = P(body=(0, -.018, 0, -1, 0, -2.5), head=None, la=(22, 65, -42, -102), rl=(-2, 0, 2, 0), ll=(-8, 0, -4, 15),
                aim=((.38, .79, .21), (.22, -.6, .77)))
move('blade_sword_idle', 54, [
    (0, 'linear', CARRY),
    # A lazy flick of the wrist, the point swung out and let fall to the ground.
    (6, 'inoutsine', P(head=None, la=(10, 30, -20, -50), aim=((.42, .92, .2), (.55, .35, .75)))),
    (14, 'outquad', IDLE),
    (34, 'inoutsine', IDLE_BREATH),
    (54, 'inoutsine', IDLE)], loop=14)

# --- Gravity Grasp's stab (the dagger's G held and let go, the body hauled in): the dagger turns over in the hand
#     already in an icepick grip, and the arm goes up with it; the free hand seizes the body by the shoulder; then the
#     knife is driven down into the side of its neck and left there, leaned on, pushed deeper, for a second, before it
#     is torn out across the throat and away. GravityGrasp's timings: in on the sixth tick, out on the twenty-seventh.
GRAB = (-80, 18, 0, -16)
STAB_IN = P(body=(0, -.06, -.12, -10, 12, 0), head=(8, 12, 0), la=(-74, 24, 0, -34), rl=(26, 0, 0, 14), ll=(-34, 0, 0, 36),
            rev=True, aim=((.12, 1.56, .74), (-.35, -.5, .8)))
STAB_DEEP = P(body=(0, -.09, -.15, -13, 14, 0), head=(10, 14, 0), la=(-70, 26, 0, -44), rl=(28, 0, 0, 16), ll=(-36, 0, 0, 40),
              rev=True, aim=((.08, 1.52, .8), (-.38, -.52, .77)))
move('blade_grasp_stab', 40, [
    # It forms already turned over in the hand that held the hole open.
    (0, 'linear', P(la=(0, 0, 0, 0), rev=True, aim=((.32, 1.36, .6), (0, -.55, .83)))),
    (3, 'outquad', P(body=(0, .02, .03, 6, -14, 0), head=(-6, -14, 0), la=GRAB, rl=(14, 0, 0, 18), ll=(-18, 0, 0, 22),
                     rev=True, aim=((.4, 1.95, .2), (-.1, -.62, .78)))),
    (6, 'inexpo', STAB_IN),
    (9, 'outquad', STAB_DEEP),
    (15, 'inoutsine', STAB_IN),
    (19, 'inoutsine', STAB_DEEP),
    (24, 'inoutsine', P(body=(0, -.07, -.12, -9, 10, 0), head=(6, 10, 0), la=(-72, 24, 0, -30), rl=(26, 0, 0, 14), ll=(-34, 0, 0, 36),
                        rev=True, aim=((.14, 1.58, .72), (-.32, -.48, .82)))),
    # Torn out across the throat and flung away to the right, the body turning with it.
    (27, 'inexpo', P(body=(0, -.04, -.06, -4, -22, 0), head=(0, -12, 0), la=(-40, 20, 0, -50), rl=(20, 0, 0, 16), ll=(-24, 0, 0, 24),
                     rev=True, aim=((.72, 1.62, .38), (.8, .1, .55)))),
    (30, 'outquad', P(body=(0, -.03, -.04, -2, -28, 0), head=(0, -16, 0), la=(-30, 14, 0, -50), rl=(18, 0, 0, 14), ll=(-22, 0, 0, 22),
                      rev=True, aim=((.85, 1.45, .12), (.75, -.25, -.6)))),
    (40, 'inoutsine', P(ra=(0, 0, 0, 0), item=(180, 0, 0), rl=(0, 0, 0, 0), ll=(0, 0, 0, 0)))])

# --- The burning Deceiver (transformed). Drawn: the fire is lit by hand, a palm run up the flat of the blade as it is
#     held upright before the face, the flame chasing the hand to the point; the hand flung away, and the sword whipped
#     down and out, back up over the shoulder and down again in a figure of eight, shaking the fire out along it, into
#     a wide low hold. The head is the player's own throughout, and the legs are left to walk.
SALUTE = ((.04, 1.02, .46), (-.04, 1, .1))
move('blade_sword_ignite', 44, [
    (0, 'linear', SWORD_REST),
    (4, 'outquad', P(body=(0, 0, 0, 2, -8, 0), head=None, aim=SALUTE, lhand=(-.02, 1.36, .55))),
    (9, 'inoutsine', P(body=(0, 0, 0, 3, -8, 0), head=None, aim=SALUTE, lhand=(-.03, 1.66, .56))),
    (13, 'inoutsine', P(body=(0, .01, 0, 5, -8, 0), head=None, aim=SALUTE, lhand=(-.04, 1.95, .56))),
    (15, 'outquad', P(body=(0, .01, 0, 4, -6, 0), head=None, la=(-125, -20, -75, -10), aim=SALUTE)),
    (19, 'inexpo', P(body=(0, -.04, -.02, -4, -22, 0), head=None, la=(-20, 0, -60, -20), aim=((.7, 1.15, .32), (.75, -.55, .35)))),
    (24, 'outquad', P(body=(0, -.02, 0, 2, 18, 0), head=None, la=(10, 0, -50, -20), aim=((-.15, 1.65, .32), (-.7, .55, .35)),
                     hint=(-120, -30, 0, -40))),
    (28, 'inexpo', P(body=(0, -.08, -.06, -8, -12, 0), head=None, la=(0, 0, -55, -20), aim=((.55, .9, .5), (.45, -.75, .5)))),
    (33, 'outquad', P(body=(0, -.02, 0, -2, -8, 2), head=None, la=(10, 0, -25, -20), aim=((.55, .85, .05), (.75, -.45, -.3)))),
    (44, 'inoutsine', SWORD_REST)])
# Its fire loosed (the use key held): the sword arm thrust straight out, the blade along it, and a stream of fire out of
# the point. Played with the arm turned to the player's look up and down (client HexAnimations), so the blade goes
# wherever they look; the body is kept square to the look, and the free arm braced back. A shiver in it as it pours.
AIMED = ((.3, 1.38, .66), (-.06, 0, 1))
move('blade_sword_flame', 25, [
    (0, 'linear', SWORD_REST),
    (3, 'outquad', P(body=(0, 0, 0, -4, 0, 0), head=None, la=(-20, 0, -25, -35), aim=AIMED, hint=(-90, 0, 0, 0))),
    (5, 'inoutsine', P(body=(0, 0, 0, -4, 0, 0), head=None, la=(-20, 0, -25, -35), aim=AIMED, hint=(-90, 0, 0, 0))),
    (15, 'inoutsine', P(body=(0, 0, .01, -3, 0, 0), head=None, la=(-22, 0, -26, -38), aim=((.3, 1.39, .63), (-.06, .01, 1)),
                        hint=(-90, 0, 0, 0))),
    (25, 'inoutsine', P(body=(0, 0, 0, -4, 0, 0), head=None, la=(-20, 0, -25, -35), aim=AIMED, hint=(-90, 0, 0, 0)))], loop=5)

# ------------------------------------------------------------------ what the server must agree with
# The tick of each cut's contact, and where its blood goes (the caster's right, up, forward). server/BladeCombo and
# HexServer's ordinary attacks use these same numbers; check() in generate_blades.py holds each cut's point to its swing.
CONTACT = {'blade_dagger_0': 4, 'blade_dagger_1': 3, 'blade_dagger_2': 5, 'blade_dagger_3': 6, 'blade_dagger_kick': 5,
           'blade_sword_0': 5, 'blade_sword_1': 7, 'blade_sword_2': 5, 'blade_sword_3': 6,
           'blade_m_dagger_0': 4, 'blade_m_dagger_1': 4, 'blade_m_dagger_2': 4, 'blade_m_dagger_3': 4,
           'blade_m_sword_0': 6, 'blade_m_sword_1': 6, 'blade_m_sword_2': 6, 'blade_m_sword_3': 6, 'blade_sword_dash': 6,
           'blade_grasp_stab': 6, **{f'blade_sword_run_{i}': 5 for i in range(7)},
           'blade_sword_evis_thrust': 2, 'blade_sword_evis_cut': 6}
SWINGS = {'blade_dagger_0': (1, 0, 0), 'blade_dagger_1': (0, -.8, .6), 'blade_dagger_2': (0, 0, 1), 'blade_dagger_3': (1, 0, 0),
          'blade_sword_0': (-.7, -.7, 0), 'blade_sword_1': (1, 0, 0), 'blade_sword_2': (0, 0, 1), 'blade_sword_3': (0, 1, 0),
          'blade_m_dagger_0': (-.7, -.7, 0), 'blade_m_dagger_1': (1, 0, 0), 'blade_m_dagger_2': (-.7, -.7, 0), 'blade_m_dagger_3': (0, 0, 1),
          'blade_m_sword_0': (-.7, -.7, 0), 'blade_m_sword_1': (.6, .8, 0), 'blade_m_sword_2': (-1, 0, 0), 'blade_m_sword_3': (0, -.8, .6),
          'blade_sword_dash': (0, -.8, .6), 'blade_grasp_stab': (-.2, -.5, .85),
          'blade_sword_run_0': (-1, 0, 0), 'blade_sword_run_1': (1, 0, 0), 'blade_sword_run_2': (0, 1, 0), 'blade_sword_run_3': (-.7, -.7, 0),
          'blade_sword_run_4': (.7, .7, 0), 'blade_sword_run_5': (.7, -.7, 0), 'blade_sword_run_6': (-.7, .7, 0),
          # The thrust goes straight in; the cut comes down along the line it parts the body on (server/Evisceration, CUT).
          'blade_sword_evis_thrust': (0, 0, 1), 'blade_sword_evis_cut': (.64, -.77, 0)}
