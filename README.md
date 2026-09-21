## 0.5.6 — The Pilgrim stops knotting itself, and starts leaving the water

- **A spine, not a rope.** The body was reconstructed by replaying the head's own recorded path, so
  any curve the head could describe the body would reproduce — including curves far tighter than
  its own joint spacing. A hover, a tight orbit or a hard turn at low speed therefore folded a
  hundred and twenty six blocks of creature into a knot a metre across. Joints are now rigid six
  block links with a bend limit that widens toward the tail, so the path is followed as closely as
  a spine allows and no closer.
- **Turning is bounded by a radius.** Degrees per tick is the wrong limit for something this long:
  the same allowance is a graceful arc at attack speed and a pirouette while drifting. A target
  inside the turning circle is now carved past rather than spun at, and a reversal keeps more than
  half its speed, so it reads as a wide banked arc.
- **The attack geometry stopped asking for the impossible.** Stalk orbits start at forty six blocks,
  the crushing ring closes to thirty rather than four and a half, and the vortex holds thirty four,
  each turning at a rate its radius can actually be swum at.
- **It jumps.** Prey that leaves the water is answered by leaving the water: the creature reads
  where the target will be, solves the launch that meets it there, lines up underneath and throws
  itself, with one tail flick of drift permitted mid-air and nothing else. The existing deep breach
  uses the same solved impulse rather than trying to steer at a point in the sky.
- **New skin.** Every cube face stretches one tile of a fixed atlas, so a tile with edges draws
  those edges around all 1,550 cubes. The tiles are now seamless, low contrast and finely detailed
  — cold hide, lamellar bone, ribbed membrane, a luminous organ — and the rib blades stay swept
  along the hull instead of splaying out. Same geometry, same UV layout, reproducible through
  `python tools/generate_pilgrim_textures.py`.
- **Flight is confined to the fracture world.** It used to follow the mantle into every dimension,
  and sovereignty granted it again in eight of the nine Warping realms, which meant a corona you
  can climb out of, planes that cannot close on you, a collapse you do not fall with and an ocean
  whose hunter cannot reach you. Every realm is now survived from inside it. Your own pocket realm
  keeps flight, because falling off the edge is the only hazard there. Creative and spectator mode
  are untouched.

## 0.5.0 — HexGodOfStories

- **The mod is now HexGodOfStories.** Mod id, package, assets, keybinding category, creative tab and the command root all move: every command is `/hgos ...`. Existing saves are carried across — progression, unlocks, quick bars, sanctum plots and pending world restores are read from their old names once and rewritten under the new one, so nothing is lost and nothing is regenerated.
- **A new world grants nothing.** No HUD, no archive, no quick bar, no keys, no progression. Powers exist only after an operator runs `/hgos unlock <player> on`, and a player who has not been granted them accrues no mastery at all, not even silently. `/hgos unlock <player> off` takes everything back down again.
- **Borrowed Reality is a wall, in four sizes.** Hold the cast key and it grows Small → Medium → Big → Massive; release to commit whichever size it reached. The four previous building designs are gone.
- **Illusory walls stopped drifting.** The courses are drawn into their own buffer and flushed inside the camera transform that positioned them, instead of being left for Minecraft to flush later under a different matrix — which is what made them slide out of place as you turned. Each block is also lit from where it stands rather than full-bright, so borrowed stone sits in the scene's own light.
- **Creatures believe the wall.** Pathfinding treats its columns — and two courses of clearance above them — as blocked, so mobs walk around it and never try to cross the parapet. Sight is stopped at its face, so a hunter that loses you behind one forgets you the way it would behind real masonry. A mob that walks into one is turned back. Players still pass straight through: it is your lie, and it has no collision.
- **The quick slots say what is bound to them.** The archive's eight slots are two rows of four, each printing its number and the ability's full name, with the discipline's colour on its edge and a marker on the one currently selected. The in-game bar lists all eight by name with their recovery, and the readout names the slot the shown ability answers to.
- **No portal is left standing after an arrival.** A break now closes behind the traveller who opened it, a break is searched for across every level rather than only the one its caster is standing in — which is how one used to survive the crossing — and nothing can open a break in the tick or two after an arrival. An arrival is particles around where you land, and nothing else.

## 0.4.1 — Fracture modes, honest exits and a sanctum that fights back

- **The alternate key now configures the Fracture; the cast key runs it.** With the Fracture selected **and standing in your sanctum**, the alternate key opens a mode selector. Out in the world there is nothing to choose — the break leads one place, in — so the selector stays shut and the cast key is the whole control. What you pick there is saved and stays saved — through casts, dimension changes, death and a server restart — until you deliberately open the selector and pick something else. The cast key just does whatever is currently saved, as often as you press it. Every other spell keeps its ordinary alternate action.
- **Outside the sanctum, the cast key always takes you in.** Tap for a doorway to walk through, hold to drag everything within five blocks through with you, whatever mode is saved. The saved mode describes the way *out*, and waits until you are inside to mean anything. Holding the cast key on the way out now drags your cargo along too, to wherever you are going.
- **Six modes.** *Pull Into Fracture* is the original behaviour and keeps its tap-for-a-doorway, hold-for-a-vacuum distinction. *Travel Near Player* saves the person you chose and opens 8–12 blocks from them; if they log out the choice is dropped rather than leaving a stale coordinate. *Nearest Player* is resolved when you cast, not when you chose. *Nether*, *Respawn Point* (bed, anchor, or world spawn when neither stands) and *Normal Return* complete the set.
- **The live mode is never a guess.** The selector names it at the top and marks its row with a lit border and an ACTIVE marker; the bottom-left readout prints it beside the cast key.
- **Fixed: transported entities no longer return to where they were captured.** A break now carries the destination your saved mode resolved to when it was struck, and everything that walks through obeys it — you and every creature travelling with you. Drag a mob in at one place, leave somewhere else, and it surfaces beside you. Groups fan out around the arrival instead of stacking into one column.
- **Inside your own island you cannot be struck.** An attack is refused as it is declared, before any damage is worked out, and in the same instant you are several blocks away: thick nebula closes over where you stood for the blow to pass through, and a smaller bloom marks where you reform. Falls and the void still apply — this answers attackers, not gravity.
- **The island hunts whoever is troubling you.** Anything hunting you, angry at you, or that has recently drawn your blood draws a green celestial star: high, off-axis, wobbling as it closes, landing for about ten hearts. It calls no explosion, so the throne, the tree and the ground are never touched, and you are immune to every part of it.
- **Atmosphere, not redesign.** The island itself is untouched. Around it: a few drifting motes and a sparse nebula rim that flows slowly around the coast, sampling only the sections near you and idling entirely outside the dimension.

## 0.4.0 — Cloak, deception, universal shapes and time control

- **The cloak no longer stretches across the world.** Its solver now enforces two hard geometric limits after every pass and again on the frame the renderer draws, so no part of it can leave the shoulders by more than the fabric hanging above it, or leave the node above it by more than one stretched segment. Torso transforms that cannot be inverted are rejected rather than propagated, per-tick motion is capped, anything non-finite re-seeds the grid from the body, and a hem resting on the ground is dragged along instead of welded to the block it touched.
- **Projections fight on their own.** They pick out hostile creatures — vanilla or modded — anything already hunting their caster, and anyone who has recently drawn the caster's blood, including another player. Friendly, tame and neutral creatures are left alone unless they attack first.
- **A court carries mixed arms.** Copies spawn with a single dagger, twin daggers or the Void sword, and one always mirrors whatever the caster is actually holding. Swordsmen use the plain swing; dagger pairs alternate hands on a faster cadence.
- **Creatures cannot tell a copy from the original.** When something decides to hunt a keeper who has projections out, that decision is reopened across the keeper and every nearby copy and settled by a weighted draw on distance, line of sight and how much each body has recently hurt it. Being the real player counts for nothing. Existing aggression is redistributed the moment copies appear, and a copy that lands a blow draws retaliation onto itself.
- **Copies are hard to pick out by eye.** They carry the caster's skin, armour, worn head gear, elytra, name tag, harmless potion effects and cloak, mirror the caster's crouch and visible flourishes, and glance around instead of staring at their target.
- **Masquerade wears anything alive.** Any living entity in the game can be copied — vanilla or modded, passive, hostile, aquatic, flying, humanoid or not. The target's own saved state is captured, so a specific sheep keeps its colour and a specific modded creature keeps its variant instead of reverting to a default model. The borrowed body is driven from the player's movement, rotation, pose, swing and hurt state, and the player's own body is replaced rather than drawn underneath. Creatures respond to the costume: monsters ignore a monster shape and nothing hunts its own species, until the wearer attacks.
- **Thrown daggers stay where they land.** A chest hit rides the chest, an arm hit swings with the arm and a head hit turns with the skull, at any body size, and wounds bleed from the blade itself and leave drying pools on the ground behind a moving body.
- **Effects gather rather than switch on.** Every particle opens over its first ticks, ability effects release across a stretch of them, and the flight nebula condenses and disperses over about a second and a half. A nebula family built from the same noise field as that cloud gives the whole mod one material.
- **Time control has permanent keys.** Stillness, Resume, Rewind and Dilation each own a key, are drawn permanently at the bottom of the screen beside those keys, and can no longer be scrolled to or bound to a quick slot.
- **Stillness holds the world, not just the mobs.** Bodies, arrows, thrown weapons, loose items, falling blocks, primed charges and orbs all stop, and weather and loose particles freeze where they stand — rain hangs in the air and resumes from the same phase.
- **Dilation is smooth.** Nothing has its ticks withheld any more; rates are scaled instead, so slow motion interpolates like ordinary movement rather than teleporting.

## 0.3.0 — Fracture and world-tree island

- Fracture inside the realm opens a return portal without an energy/mastery/cooldown requirement. Walk through to return to your own saved dimension, position and facing. A short crossing grace period prevents bouncing back into the entry portal. The arrival sigil remains a crouch-to-exit fallback.
- Dropped conjured daggers, Laevateinn and time sticks dissolve before spawning or being picked up, including inventory tosses and death drops. Transferred weapons cannot be used by another player. Thrown attack daggers still embed and dissolve as before.
- Recorded Minecraft glass/weapon/material sounds replace the old synthetic audio. The portal's duplicate opening sound is removed. Resource-pack sample overrides are respected.
- Black metal horns, charcoal/gray outfit and a broader, flaring charcoal cape; shoulder attachment stays on the posed torso.
- Larger seeded mirror fractures with jagged apertures, uneven splinters and persistent branching cracks.
- A roughly 150-block-wide irregular island with more than twice the old land area, a deeply tapered rocky underside, rolling edges, sprawling/forked surface roots, hanging roots, luminous flower groves and a much larger branching tree.
- A thick carved throne, solid seat, wide stair approach, armrests and horned root crown. Right-click the seat to sit; sneak to stand.
- Existing plot coordinates and return sigils stay in place. V3 generation checkpoints resume after restart. The upgrade replaces matching old generated states and leaves other occupied blocks alone. Blocks a player placed that exactly match the original generated block at the same position cannot be distinguished from the original.

Install the normal `hexgodofstories-0.3.0.jar` from **Build HexGodOfStories**; `hexgodofstories-0.3.0-sources.jar` contains source code. The mod still targets Forge 1.20.1 and retains its existing Player Animator dependency. Client/server playtesting remains necessary; compilation does not certify visual or multiplayer behavior.

# HexGodOfStories

Minecraft **1.20.1**, Forge **47.4.10**, Java **17**. This is a development build, not a certified final release.

## Installation and build

Install Player Animator **1.0.2-rc1+1.20** (CurseForge file 4587214) on clients. Put the HexGodOfStories JAR on the server and each client. Run `gradle build` with Gradle 8.8, or download the mod artifact from the **Build HexGodOfStories** GitHub Action. The server does not need a graphics context.

## Controls

| Input | Action |
|---|---|
| K | Mastery archive |
| V *(hold)* | Quick bar — wheel to choose, release to select |
| R | Cast the selected spell; **hold** for abilities that shape while held |
| G | Alternate contextual action — **Fracture mode selector**, while Fracture is selected and you are inside your sanctum |
| H | Glorious Purpose transformation |
| X | Release held targets / seal your fracture / also resumes your time fields |
| Z | **Stillness** — suspend the local battlefield |
| B | **Resume Time** — release everything you are holding |
| N | **Personal Rewind** |
| M | **Dilation** — slow the local battlefield |
| Wheel *(while gripping)* | Push or pull what telekinesis is holding |
| Attack / Use with a conjured weapon | Combination / dagger throw or artifact action |

Nobody has powers until an operator grants them: `/hgos unlock <player> on`. Until then the mod shows no HUD, opens no screen, answers no key and records no progression. Key mappings are configurable. Free your hands before conjuring. Successful spell use trains its discipline; training is rate limited. Temporal progression opens after 600 combined mastery in the four magical disciplines. Glorious Purpose opens after 800 Temporal Mastery.

The quick bar holds eight shortcuts. It fills itself as abilities unlock; to place one deliberately, open the archive, click a quick slot at the bottom, then click the ability you want bound there.

The four time controls are not shortcuts and never enter the quick bar. They are permanent commands on the four keys above, shown as a fixed row at the bottom of the screen with their keys, readiness and recovery. All bindings are configurable in Minecraft's Controls menu; the defaults are unbound in vanilla.

## Notable abilities

- **Living Projection** — a decoy that looks, moves and fights like you, down to the name tag. It hunts hostile creatures and anyone who has attacked you, without being told. Creatures choosing between you and your copies cannot tell which is which: the choice is made on distance, sight and who has been hurting them, and never on which one is breathing. Its secondary sends every projection at whatever you are aiming at, or dismisses them all if you aim at nothing.
- **Invisible Hand** — hold entities and dropped items on a damped spring, several at once with mastery. The wheel pushes and pulls; the secondary hurls, and whatever you hurl takes the impact it was carrying.
- **Borrowed Reality** — hold the cast key and a wall grows where you aim, through Small, Medium, Big and Massive. No block is ever placed: viewers are handed an origin, a size and a seed and build the courses locally, while the server keeps the same columns in memory so that everything which is not a player treats the wall as masonry — mobs path around it, lose sight of you behind it and are turned back when they walk into it. You walk through your own lie; the alternate key dismisses it.
- **Fracture** — shatters the air where you look. From the outside it leads one place: in, taking anything beside you with it if you hold the key. From inside, where the break leads is chosen in the selector on the alternate key and stays chosen: a named player, whoever is nearest, the Nether, your bed, or the place you left. The break holds for seven seconds, and anything that walks through it follows your destination rather than its own history. Inside your sanctum you cannot be hit, and the island throws falling stars at anyone who tries.
- **Twin Deceivers** — two daggers, the off hand reversed. Thrown blades fly point-first, bury themselves in what they hit and open bleeding wounds before dissolving.
- **Masquerade** — wear any living thing in the game, vanilla or modded, keeping that individual creature's variant, colour, size and carried gear rather than its species' default. Creatures read the shape and mostly ignore it, until you attack one.
- **Stillness** — local suspension that decelerates into and out of a stop rather than snapping, and holds bodies, shots, loose items, falling blocks and the weather alike. Harm you deal to a suspended body is banked and lands the instant time resumes.
- **Dilation** — everything nearby runs at roughly a third speed, smoothly. Movement, attacks and arcing shots slow together; nothing stutters.

## Development commands

All commands require operator permission level 2.

Granting and revoking powers:

- `/hgos unlock <player> on` — grant this player their powers
- `/hgos unlock <player> off` — take them back, tearing down anything they had standing

Everything else takes the player first, as `/hgos <player> ...`:

- `set <discipline> <0..1000>` / `add <discipline> <0..1000>`
- `unlock <ability>` / `unlock all`
- `energy <amount>`
- `transform true` / `transform false`
- `clear_illusions` / `clear_time` / `clear_bleed` / `clear_quick`
- `realm enter` / `realm exit`
- `status`
- `reset`

Example: `/hgos @s unlock all`, then `/hgos @s transform true`.

## Architecture

The server owns mastery, cooldowns, spells, held targets, projection navigation and aggression, weapon hit timing, bleeding, temporal fields, sanctum plots and transformation state. Client presentation covers layered Player Animator gestures, composited player skins, cloth simulation, authored meshes, illusory architecture geometry, additive particles and a dedicated post-processing chain.

**Cloak.** The cape is solved in world space, not in model space. Row zero of a 16×9 Verlet grid is written directly to the shoulder line every tick, so the cloak is attached by construction; every other row is a particle that lags behind. Trailing while running, lift on a fall, the sideways throw of a sharp turn and settling on landing come out of that lag rather than from a wave function. It collides against a capsule around the wearer and against the ground, carries itself through teleports rather than snapping taut, and is drawn in world space so it never inherits the mirrored model transform. Nothing about it is networked.

**Weapons.** Meshes are authored in Minecraft item-model units with the grip at the origin and the blade running up +Y. The item renderer hands a custom renderer the corner of the item's unit cube — the same space a vanilla sprite occupies — so correct hand alignment only requires putting the grip where a vanilla hilt sits and laying the blade along the diagonal that the inherited `item/handheld` transforms are built around. Off-hand twins apply the same placement rotated a half turn for a reverse grip.

**Telekinesis.** Targets ride a damped spring toward a smoothed aim point. Held players receive motion packets and are only repositioned when they have genuinely escaped, so ordinary struggling stays smooth instead of rubber-banding.

**Time.** Fields query bounded local volumes, cap their entity count and retain independent ownership. World tick rate never changes. The world, inventories, blocks and other players' health are never rewound. Temporal history is limited to 50 position/rotation/health samples per player. Players receive at most three seconds of suspension followed by a five-second protection window. Creative and spectator players are exempt.

**Sanctum.** `hexgodofstories:pocket` is a void dimension defined by datapack. Plots are allocated once per player and remembered in that dimension's saved data; the platform around the arrival point is laid immediately and the remainder is built across the following ticks, budgeted per tick. The way home is stored outside the transient state block so a dimension change cannot erase it.

Meshes, textures, particle sprites, animation keyframes and original synthesised audio are reproducible through `python tools/generate_assets.py` (Pillow and ffmpeg required only for regenerating assets). No audio is sampled from film or game sources.

## Validation status

Build and validation status is recorded in `docs/VALIDATION.md`. A successful Java build does not verify in-game appearance, shader compatibility, multiplayer illusion believability or model alignment. This project must not be described as having passed those checks unless a recorded game test actually establishes them.

## 0.2.1 — World tree and mantle fixes

- The cape seam follows the rendered torso, including Player Animator, crouching and body rotation. The longer hem drags on collision surfaces.
- A solid black forehead band fits around the Minecraft head and joins both horns.
- Court of Lies hides the caster completely; projections roam in separate sectors and stop stale paths instead of converging on the caster.
- Fracture has an eight-second cooldown. Each private realm is an open floating island beneath an emerald galaxy, with a large luminous world tree and a blackstone/gold throne at its heart.
- Existing sanctums upgrade when their owner enters. Generated walls and matching original floors are replaced; other placed blocks are retained. Construction is budgeted across ticks and its progress survives restarts.
- Crouch for three seconds on the green/gold arrival sigil to return without spending energy. Fracture also opens the normal return portal.

Client appearance and movement still require in-game verification; compilation alone cannot establish those results.

### 0.3.1 controls and realm update

Fracture: tap the primary key for a doorway, or hold for 0.6 seconds to gather all eligible entities within five blocks of the portal. After a brief inward pull, the group crosses and the portal closes. The same hold works inside the pocket realm; returning costs no energy and ignores entry cooldown. Visitors arrive in the caster's realm and keep their own way home.

While transformed **and inside your own fracture world**, press **J** to toggle Cosmic Flight. **Space** rises and **crouch** descends; vanilla double-jump flight controls also work. Change the binding in Minecraft's Controls menu. A green spatial nebula and star filaments surround flying players. Since 0.5.6 no other dimension grants flight, the Warping realms included.

The HUD shows eight named ability cards with recovery/ready status, selection and energy. Hold the select key and scroll to switch. Existing realms receive the larger island, organic tree/roots and rebuilt throne through a resumable upgrade; no world reset is needed.

### 0.3.2 HUD correction

The HUD is now a compact bottom-left readout. It describes the selected ability's primary and alternate actions, readiness/recovery, energy cost and remaining energy. Hold V and scroll to inspect another bound ability; release to select it. The large eight-card overlay is removed. Decorative effect rings and orbiting wire strands are removed, including those around Cosmic Flight; its nebula clouds remain.
