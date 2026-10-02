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
kicks between cuts), Vinland Saga (spinning cuts low to the ground), and the longsword masters (the Zornhau from the
shoulder, the Zwerchhau across at the head, the thrust from the plough guard, the rising Unterhau); and for the
guard, Malenia: upright and easy, the sword arm hanging loose, the long blade angled down and out to the side.
"""

# ------------------------------------------------------------------ poses
# A pose is the body, head, legs and left arm as given; the sword arm and wrist are found (tools/blade_rig.py) to put
# the grip at `aim`'s point and the blade along its direction, both in the body's own frame (right, up from the
# feet, forward). `two` puts the left hand on the grip too, a fist below the right; `spin` adds whole turns of the
# blade in the hand (end over end) on the way into the key.

def P(body=(0, 0, 0, 0, 0, 0), head=(0, 0, 0), la=(0, 0, 0, 0), rl=None, ll=None, aim=None, two=False, spin=0, ra=None, item=None, rev=False):
    """body (x, y, z, pitch, yaw, roll); head (pitch, yaw, roll); arms and legs (pitch, yaw, roll, bend)."""
    f = {'body': dict(zip(('x', 'y', 'z', 'pitch', 'yaw', 'roll'), body)),
         'head': dict(zip(('pitch', 'yaw', 'roll'), head)),
         'leftArm': dict(zip(('pitch', 'yaw', 'roll', 'bend'), la))}
    if ra is not None: f['rightArm'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), ra))
    if item is not None: f['rightItem'] = dict(zip(('pitch', 'yaw', 'roll'), item))
    if rl is not None: f['rightLeg'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), rl))
    if ll is not None: f['leftLeg'] = dict(zip(('pitch', 'yaw', 'roll', 'bend'), ll))
    # rev: the knife turned over in the fist (the icepick grip), so the wrist is solved from a reversed hold.
    if aim is not None: f['aim'] = {'grip': aim[0], 'dir': aim[1], 'two': two, 'spin': spin, 'rev': rev}
    return f


def turned(frame, yaw):
    """The same pose with the whole body (and the eyes with it) turned a further `yaw`: for moves after a full spin."""
    out = {k: dict(v) for k, v in frame.items()}
    out['body']['yaw'] = out['body'].get('yaw', 0) + yaw
    out['head']['yaw'] = out['head'].get('yaw', 0) + yaw
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
# 4. The spin: wound up to the left, a full turn to the right low on bent knees, the blade trailing, whipped out
#    flat at the end of the turn.
SPUN = turned(READY, -360)
move('blade_dagger_3', 16, [
    (0, 'linear', READY),
    (1, 'outquad', P(body=(0, -.06, 0, -4, 22, 0), head=(0, 22, 0), la=(-60, 30, 0, -60), rl=(14, 0, 0, 20), ll=(-16, 0, 0, 24),
                     aim=((-.4, 1.25, .35), (-.8, 0, .5)))),
    (4, 'linear', P(body=(0, -.12, 0, -6, -170, 0), head=(0, -150, 0), la=(-40, 30, -20, -50), rl=(0, 0, 0, 34), ll=(-6, 0, 0, 34),
                    aim=((-.48, 1.18, -.08), (-.9, 0, -.3)))),
    (6, 'inquad', P(body=(0, -.10, 0, -8, -338, 0), head=(0, -348, 0), la=(-50, 26, -10, -56), rl=(12, 0, 0, 26), ll=(-18, 0, 0, 28),
                    aim=((.12, 1.3, .58), (.3, 0, 1)))),
    (8, 'outquad', P(body=(0, -.09, 0, -6, -378, 0), head=(0, -372, 0), la=(-62, 20, 0, -66), rl=(14, 0, 0, 24), ll=(-18, 0, 0, 26),
                     aim=((.68, 1.3, .25), (1, 0, .2)))),
    (12, 'inoutsine', SPUN),
    (16, 'inoutsine', SPUN)])
# 5. The kick: knee up, a push kick off the right leg into the body, leaning back from it, knife held back and low.
KNIFE_BACK = ((.45, .98, -.12), (.1, .35, .95))
move('blade_dagger_kick', 20, [
    (0, 'linear', SPUN),
    (3, 'outquad', turned(P(body=(0, -.02, .03, 8, 0, 0), head=(-6, 0, 0), la=(-72, 14, 0, -40), rl=(-76, 0, 0, 86), ll=(0, 0, 0, 18),
                            aim=KNIFE_BACK), -360)),
    (5, 'inexpo', turned(P(body=(0, 0, .06, 16, 0, 0), head=(-10, 0, 0), la=(-78, 10, 0, -30), rl=(-96, 0, 0, 2), ll=(4, 0, 0, 22),
                           aim=KNIFE_BACK), -360)),
    (8, 'outquad', turned(P(body=(0, -.02, .03, 8, 0, 0), head=(-6, 0, 0), la=(-72, 14, 0, -44), rl=(-54, 0, 0, 62), ll=(0, 0, 0, 18),
                            aim=KNIFE_BACK), -360)),
    (12, 'inoutsine', SPUN),
    # The knife twirled once in the fingers as the hand drops: the move ends with it, so the turn never unwinds.
    (20, 'linear', turned(P(ra=(0, 0, 0, 0), item=(360, 0, 0), rl=(0, 0, 0, 0), ll=(0, 0, 0, 0)), -360))])

# --- The Master Cuts (The Deceiver, held). One hand: the free arm points the way in, is thrown back to balance a lunge,
#     rides out wide through a turn.
LA_GUIDE = (-82, 16, 0, -8)
LA_CHEST = (-58, 38, 0, -78)
LA_BACK = (40, 0, -40, -58)
LA_OUT = (-10, 0, -78, -8)
SWORD_REST = P(body=(0, -.03, 0, -2, -10, 0), head=(0, -10, 0), la=LA_CHEST, rl=(12, 0, 0, 12), ll=(-12, 0, 0, 14),
               aim=((.26, 1.05, .36), (.1, .55, .82)))
SWORD_SPUN = turned(SWORD_REST, -360)
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
# 2. A spinning cut: wound up to the left, a full turn to the right with the free arm out wide, and the blade flat
#    across at head height out of the turn.
move('blade_sword_1', 18, [
    (0, 'linear', SWORD_REST),
    (2, 'outquad', P(body=(0, -.04, 0, 0, 30, 0), head=(0, 30, 0), la=LA_OUT, rl=(14, 0, 0, 18), ll=(-12, 0, 0, 16),
                     aim=((-.3, 1.4, .15), (-.85, .15, -.4)))),
    (5, 'linear', P(body=(0, -.10, 0, -4, -150, 0), head=(0, -132, 0), la=(-10, 0, -82, -4), rl=(0, 0, 0, 30), ll=(-4, 0, 0, 30),
                    aim=((-.45, 1.35, -.1), (-.9, .05, -.4)))),
    (7, 'inquad', P(body=(0, -.08, 0, -4, -336, 0), head=(0, -346, 0), la=(-10, 0, -82, -4), rl=(12, 0, 0, 22), ll=(-14, 0, 0, 24),
                    aim=((.05, 1.45, .65), (.25, .02, 1)))),
    (9, 'outquad', P(body=(0, -.08, 0, -4, -380, 0), head=(0, -372, 0), la=(0, 0, -60, -20), rl=(12, 0, 0, 22), ll=(-14, 0, 0, 24),
                     aim=((.65, 1.42, .25), (.95, 0, .3)))),
    (13, 'inoutsine', SWORD_SPUN),
    (18, 'inoutsine', SWORD_SPUN)])
# 3. A fencer's lunge: the point drawn back beside the chest, the free hand guiding, then the front foot shooting
#    out, the point driven through and the free arm thrown up behind for balance.
move('blade_sword_2', 18, [
    (0, 'linear', SWORD_SPUN),
    (3, 'outquad', turned(P(body=(0, -.06, .05, 2, -22, 0), head=(0, -22, 0), la=LA_GUIDE, rl=(20, 0, 0, 30), ll=(-12, 0, 0, 14),
                            aim=((.32, 1.15, .05), (0, .12, 1))), -360)),
    (5, 'inexpo', turned(P(body=(0, -.15, -.36, -14, 10, 0), head=(-6, 10, 0), la=LA_BACK, rl=(42, 0, 0, 2), ll=(-62, 0, 0, 62),
                           aim=((.12, 1.28, .85), (0, .02, 1))), -360)),
    (8, 'outquad', turned(P(body=(0, -.15, -.37, -14, 11, 0), head=(-6, 11, 0), la=LA_BACK, rl=(42, 0, 0, 2), ll=(-62, 0, 0, 62),
                            aim=((.12, 1.28, .87), (0, .02, 1))), -360)),
    (13, 'inoutsine', SWORD_SPUN),
    (18, 'inoutsine', SWORD_SPUN)])
# 4. The launcher: dropping low with the blade trailing behind on the right, then exploding up off both feet, the cut
#    rising from the ground to high overhead and the free arm flung out.
move('blade_sword_3', 22, [
    (0, 'linear', SWORD_SPUN),
    (3, 'outquad', turned(P(body=(0, -.28, .02, -16, -20, 0), head=(-10, -20, 0), la=(-40, 15, 0, -20), rl=(14, 0, 0, 82), ll=(-40, 0, 0, 84),
                            aim=((.45, .45, -.2), (.4, -.5, -.75))), -360)),
    (6, 'inexpo', turned(P(body=(0, .08, -.06, 10, 10, 0), head=(-14, 10, 0), la=(30, 0, -60, -20), rl=(10, 0, 0, 0), ll=(-10, 0, 0, 2),
                           aim=((.25, 1.95, .3), (.05, 1, .15))), -360)),
    (8, 'outquad', turned(P(body=(0, .05, -.06, 12, 14, 0), head=(-18, 14, 0), la=(36, 0, -64, -20), rl=(8, 0, 0, 0), ll=(-8, 0, 0, 0),
                            aim=((.2, 2.08, .05), (0, .9, -.4))), -360)),
    (14, 'inoutsine', SWORD_SPUN),
    (22, 'inoutsine', turned(NEUTRAL, -360))])

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
GUARD = P(body=(0, 0, 0, 0, -8, 0), head=(0, -8, 0), la=(4, 0, -6, -8), aim=((.46, .8, 0), (.72, -.5, .48)))
GUARD_BREATH = P(body=(0, -.008, 0, 0, -9, 0), head=(-2, -9, 0), la=(5, 0, -7, -9), aim=((.46, .79, .01), (.72, -.52, .46)))
# Into the guard: from the ordinary carry, the sword swept up and out to the side, then let fall into the stance.
CARRY = P(la=(0, 0, 0, 0), aim=((.32, .95, .25), (0, .5, .85)))
move('blade_guard_enter', 12, [
    (0, 'linear', CARRY),
    (4, 'outquad', P(body=(0, -.02, 0, 2, -14, 0), head=(0, -12, 0), la=(-12, 0, -14, -10), aim=((.52, 1.25, .12), (.65, .7, .3)))),
    (9, 'outback', GUARD),
    (12, 'inoutsine', GUARD)])
move('blade_guard', 44, [(0, 'inoutsine', GUARD), (4, 'inoutsine', GUARD), (24, 'inoutsine', GUARD_BREATH), (44, 'inoutsine', GUARD)], loop=4)
WALK_A = P(body=(0, 0, 0, -2, -8, 2), head=(0, -8, -2), la=(-22, 0, -6, -14), aim=((.46, .82, .04), (.72, -.48, .5)))
WALK_B = P(body=(0, 0, 0, -2, -8, -2), head=(0, -8, 2), la=(22, 0, -6, -6), aim=((.46, .8, -.03), (.72, -.52, .46)))
move('blade_guard_walk', 22, [(0, 'inoutsine', GUARD), (2, 'inoutsine', WALK_A), (12, 'inoutsine', WALK_B), (22, 'inoutsine', WALK_A)], loop=2)
RUN_A = P(body=(0, 0, 0, -16, 0, 0), head=(-14, 0, 0), la=(-58, 0, 0, -70), aim=((.4, .82, -.3), (.2, -.35, -.9)))
RUN_B = P(body=(0, 0, 0, -16, 0, 0), head=(-14, 0, 0), la=(46, 0, 0, -60), aim=((.42, .86, -.38), (.2, -.3, -.92)))
move('blade_run', 14, [(0, 'inoutsine', RUN_A), (2, 'inoutsine', RUN_A), (8, 'inoutsine', RUN_B), (14, 'inoutsine', RUN_A)], loop=2)
# The slap that turns a shot or a blow aside: violent and wide. From the low guard the whole arm whips out to full
# stretch across the line of the attack, the body twisting and stepping into it, overshoots past the strike, and
# only then sinks back into the guard.
move('blade_deflect_r', 16, [
    (0, 'linear', GUARD),
    (1, 'outquad', P(body=(0, -.02, .02, 2, -6, 0), head=(0, -6, 0), la=(-20, 10, 0, -30), aim=((.4, .95, .05), (.5, -.2, .6)),
                     rl=(6, 0, 0, 8), ll=(-6, 0, 0, 8))),
    (3, 'inexpo', P(body=(0, -.06, -.06, -6, -36, 0), head=(0, -18, 0), la=(-40, 30, 0, -50), aim=((.78, 1.48, .42), (.75, .62, .2)),
                    rl=(18, 0, 0, 16), ll=(-22, 0, 0, 22))),
    (5, 'outquad', P(body=(0, -.07, -.07, -6, -44, 0), head=(0, -22, 0), la=(-36, 30, 0, -50), aim=((.86, 1.6, .05), (.6, .7, -.4)),
                     rl=(18, 0, 0, 16), ll=(-22, 0, 0, 22))),
    (8, 'outquad', P(body=(0, -.05, -.04, -4, -30, 0), head=(0, -16, 0), la=(-30, 20, 0, -40), aim=((.8, 1.5, .1), (.7, .6, -.3)),
                     rl=(14, 0, 0, 12), ll=(-16, 0, 0, 16))),
    (16, 'inoutsine', GUARD)])
move('blade_deflect_l', 16, [
    (0, 'linear', GUARD),
    (1, 'outquad', P(body=(0, -.02, .02, 2, -14, 0), head=(0, -12, 0), la=(-10, 0, -20, -20), aim=((.5, .95, .05), (.7, -.2, .5)),
                     rl=(6, 0, 0, 8), ll=(-6, 0, 0, 8))),
    (3, 'inexpo', P(body=(0, -.06, -.06, -6, 34, 0), head=(0, 18, 0), la=(10, 0, -50, -30), aim=((-.42, 1.45, .55), (-.8, .55, .25)),
                    rl=(18, 0, 0, 16), ll=(-22, 0, 0, 22))),
    (5, 'outquad', P(body=(0, -.07, -.07, -6, 44, 0), head=(0, 22, 0), la=(16, 0, -56, -30), aim=((-.62, 1.4, .25), (-.85, .45, -.25)),
                     rl=(18, 0, 0, 16), ll=(-22, 0, 0, 22))),
    (8, 'outquad', P(body=(0, -.05, -.04, -4, 30, 0), head=(0, 16, 0), la=(10, 0, -46, -26), aim=((-.5, 1.35, .3), (-.8, .5, -.2)),
                     rl=(14, 0, 0, 12), ll=(-16, 0, 0, 16))),
    (16, 'inoutsine', GUARD)])

# ------------------------------------------------------------------ what the server must agree with
# The tick of each cut's contact, and where its blood goes (the caster's right, up, forward). server/BladeCombo and
# HexServer's ordinary attacks use these same numbers; check() in generate_blades.py holds each cut's point to its swing.
CONTACT = {'blade_dagger_0': 4, 'blade_dagger_1': 3, 'blade_dagger_2': 5, 'blade_dagger_3': 6, 'blade_dagger_kick': 5,
           'blade_sword_0': 5, 'blade_sword_1': 7, 'blade_sword_2': 5, 'blade_sword_3': 6,
           'blade_m_dagger_0': 4, 'blade_m_dagger_1': 4, 'blade_m_dagger_2': 4, 'blade_m_dagger_3': 4,
           'blade_m_sword_0': 6, 'blade_m_sword_1': 6, 'blade_m_sword_2': 6, 'blade_m_sword_3': 6}
SWINGS = {'blade_dagger_0': (1, 0, 0), 'blade_dagger_1': (0, -.8, .6), 'blade_dagger_2': (0, 0, 1), 'blade_dagger_3': (1, 0, 0),
          'blade_sword_0': (-.7, -.7, 0), 'blade_sword_1': (1, 0, 0), 'blade_sword_2': (0, 0, 1), 'blade_sword_3': (0, 1, 0),
          'blade_m_dagger_0': (-.7, -.7, 0), 'blade_m_dagger_1': (1, 0, 0), 'blade_m_dagger_2': (-.7, -.7, 0), 'blade_m_dagger_3': (0, 0, 1),
          'blade_m_sword_0': (-.7, -.7, 0), 'blade_m_sword_1': (.6, .8, 0), 'blade_m_sword_2': (-1, 0, 0), 'blade_m_sword_3': (0, -.8, .6)}
