# The Abyssal Pilgrim

Replaces the Void Sea's previous hunter (`AbyssalLeviathan`, 58 lines, 650 health, nearest-target
then swim-straight then bite). Both that entity and its immediate-mode renderer are deleted.

Target: Minecraft 1.20.1, Forge 47.4.10, Java 17, GeckoLib 4.4.

## What it is

A single articulated creature roughly 160 blocks nose to tail, built from 22 logical joints, that
owns the Void Sea. It cannot be damaged, knocked back, pushed, stunned, mounted, leashed, charmed,
frozen or affected by potions. It does not need provocation and it has no passive or neutral state.

## The realm was rebuilt for it

The Void Sea was 135 blocks of water, which is less than the creature is long. It is now:

| | before | after |
|---|---|---|
| `min_y` / `height` | 0 / 384 | −768 / 1280 |
| water column | 135 | **935** |
| clear sky above the waterline | ~245 | 262 |
| waterline | y 135 | y 249 |

`VoidSea.java` holds these as constants and `VoidSeaShapeTest` fails the build if the constants and
the shipped dimension JSON ever disagree. Arrival height and the sovereign's out-of-world rescue
were both keyed to the old floor of y=0 and have been corrected.

Warping partitions each realm into 1024-block cells along X, one per opened portal. The Pilgrim is
bound to its own cell, so two parties warping at once never share a hunter and never meet a
neighbour's.

## Structure

| Concern | Class |
|---|---|
| Entity, immortality, multipart, GeckoLib | `warping/leviathan/AbyssalPilgrimEntity` |
| State machine, toying repertoire, mood | `warping/leviathan/AbyssalPilgrimAI` |
| Target choice, stalking geometry | `warping/leviathan/LeviathanHuntController` |
| The thirteen attack patterns | `warping/leviathan/LeviathanCombatController` |
| Body reconstruction from movement history | `warping/leviathan/LeviathanSegmentController` |
| 3D steering, turn-rate limits, ballistic air | `warping/leviathan/LeviathanMoveControl` |
| Open-water navigation | `warping/leviathan/LeviathanNavigation` |
| Per-joint collision volumes | `warping/leviathan/LeviathanMultipartHitbox` |
| One creature per cell, no wildlife | `warping/leviathan/PilgrimWarden` |
| Posing, jaw, glow, appendages | `client/leviathan/AbyssalPilgrimModel` |
| Renderer and emissive pass | `client/leviathan/AbyssalPilgrimRenderer`, `AbyssalPilgrimGlowLayer` |
| Secondary motion, LOD, head tracking | `client/leviathan/LeviathanSegmentRenderController` |
| Shake, spray, the standing tremor | `client/leviathan/LeviathanEffects` |

## Body

Joints are resolved by walking back along the creature's own recorded path, sampled by distance
rather than by tick. Spacing therefore stays constant whether it is drifting at a tenth of a block
per tick or breaching at three, and it does not bunch up when stationary. S-curves, coils, spirals,
vertical loops and full-body turns all fall out of the head's motion; nothing about them is
authored.

The model is a 22-bone parent chain, so only the angle between a joint and the one in front of it is
ever written. Keyframed clips supply undulation and character; the procedural pass is **additive**
on top of them, not a replacement.

Joint spacing appears twice — `LeviathanSegmentController.SPACING` (6.0 blocks) and the model's
pivot step (96 units). If those two ever drift apart every hitbox silently detaches from the visible
body, so the build test asserts they match.

## Networking

**No body data is sent.** Every client runs the same segment controller against the entity's own
interpolated position, so a 160-block creature costs what a squid costs. The only leviathan packets
are rare presentation events (breach, impact, scream) and a handful of synced bytes: state, attack,
glow, frenzy, held target, look target. Glow is quantised to 1/16 and the visual pulse is derived
client-side from state and time, so a smoothly brightening creature sends nothing.

## Deliberate decisions

- **Immortality vs. a death sequence.** "Immortal to everything" and "has a death animation" only
  reconcile if death is administrative. `hurt()` always returns false; blows land, play a dull
  refusal cue and change nothing. `beginDeath()` is the sinking sequence and nothing in normal play
  can reach it.
- **It keeps its toys alive.** Anything it grabs or deliberately releases is given water breathing.
  Drowning would rob it of the rest of the hunt. A dragged victim is never taken more than 150
  blocks below the surface for the same reason.
- **Collision is manual.** A 160-block body is never run through vanilla collision. Contact forces
  are applied from the segment volumes, which is what makes per-section damage honest: a tail sweep
  can only hurt with the tail.
- **Glow is geometry.** The emissive mask covers only the glow-organ bones, and the model hides and
  scales those bones from behaviour, so "dim while stalking" and "extinguished while ambushing" are
  real changes rather than a colour multiply. The model is never buried under particles.

## Not yet verified

Everything below needs a running game and is **not** established by a successful compile:

- In-game appearance, proportions and whether the body reads at 160 blocks.
- Bone rotation sign conventions. `AbyssalPilgrimModel.YAW_SIGN` / `PITCH_SIGN` / `ROLL_SIGN` exist
  precisely so an inverted spine is a one-character fix; which signs are correct can only be seen.
- Breach arc timing, water-crossing feel and camera shake magnitude.
- Whether 935 blocks of water costs measurable client frame time at high render distance.
- Multiplayer behaviour with several players in one cell, and target switching.
- GeckoLib 4.4.9 resolving and its exact API surface.
