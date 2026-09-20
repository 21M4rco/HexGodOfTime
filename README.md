## 0.3.0 — Fracture and world-tree island

- Fracture inside the realm opens a return portal without an energy/mastery/cooldown requirement. Walk through to return to your own saved dimension, position and facing. A short crossing grace period prevents bouncing back into the entry portal. The arrival sigil remains a crouch-to-exit fallback.
- Dropped conjured daggers, Laevateinn and time sticks dissolve before spawning or being picked up, including inventory tosses and death drops. Transferred weapons cannot be used by another player. Thrown attack daggers still embed and dissolve as before.
- Recorded Minecraft glass/weapon/material sounds replace the old synthetic audio. The portal's duplicate opening sound is removed. Resource-pack sample overrides are respected.
- Black metal horns, charcoal/gray outfit and a broader, flaring charcoal cape; shoulder attachment stays on the posed torso.
- Larger seeded mirror fractures with jagged apertures, uneven splinters and persistent branching cracks.
- A roughly 150-block-wide irregular island with more than twice the old land area, a deeply tapered rocky underside, rolling edges, sprawling/forked surface roots, hanging roots, luminous flower groves and a much larger branching tree.
- A thick carved throne, solid seat, wide stair approach, armrests and horned root crown. Right-click the seat to sit; sneak to stand.
- Existing plot coordinates and return sigils stay in place. V3 generation checkpoints resume after restart. The upgrade replaces matching old generated states and leaves other occupied blocks alone. Blocks a player placed that exactly match the original generated block at the same position cannot be distinguished from the original.

Install the normal `loki-0.3.0.jar` from **Build Loki**; `loki-0.3.0-sources.jar` contains source code. The mod still targets Forge 1.20.1 and retains its existing Player Animator dependency. Client/server playtesting remains necessary; compilation does not certify visual or multiplayer behavior.

# Loki — Glorious Purpose

Minecraft **1.20.1**, Forge **47.4.10**, Java **17**. This is a development build, not a certified final release.

## Installation and build

Install Player Animator **1.0.2-rc1+1.20** (CurseForge file 4587214) on clients. Put the Loki JAR on the server and each client. Run `gradle build` with Gradle 8.8, or download the mod artifact from the **Build Loki** GitHub Action. The server does not need a graphics context.

## Controls

| Input | Action |
|---|---|
| K | Mastery archive |
| V *(hold)* | Quick bar — wheel to choose, release to select |
| R | Cast the selected spell; **hold** for abilities that shape while held |
| G | Alternate contextual action |
| H | Glorious Purpose transformation |
| X | Release held targets / resume your time fields / seal your fracture |
| Wheel *(while gripping)* | Push or pull what telekinesis is holding |
| Attack / Use with a conjured weapon | Combination / dagger throw or artifact action |

Key mappings are configurable. Free your hands before conjuring. Successful spell use trains its discipline; training is rate limited. Temporal progression opens after 600 combined mastery in the four magical disciplines. Glorious Purpose opens after 800 Temporal Mastery.

The quick bar holds eight shortcuts. It fills itself as abilities unlock; to place one deliberately, open the archive, click a quick slot at the bottom, then click the ability you want bound there.

## Notable abilities

- **Living Projection** — a decoy that looks, moves and fights like you. Its secondary sends every projection at whatever you are aiming at, or dismisses them all if you aim at nothing. Mobs target projections; a struck projection dies in green magic with no marker and no name tag.
- **Invisible Hand** — hold entities and dropped items on a damped spring, several at once with mastery. The wheel pushes and pulls; the secondary hurls, and whatever you hurl takes the impact it was carrying.
- **Borrowed Reality** — hold the cast key and a false building grows where you aim. It has no collision and is never placed in the world: viewers are handed a design and build the geometry locally, so it can be shown to one chosen pair of eyes.
- **Fracture** — shatters the air where you look. The break holds for seven seconds and opens onto a private hundred-by-hundred sanctum in its own dimension. Casting it again inside opens the way back.
- **Twin Deceivers** — two daggers, the off hand reversed. Thrown blades fly point-first, bury themselves in what they hit and open bleeding wounds before dissolving.
- **Stillness** — local suspension that decelerates into and out of a stop rather than snapping. Harm you deal to a suspended body is banked and lands the instant time resumes.

## Development commands

All commands require operator permission level 2 and use `/loki <player> ...`:

- `set <discipline> <0..1000>` / `add <discipline> <0..1000>`
- `unlock <ability>` / `unlock all`
- `energy <amount>`
- `transform true` / `transform false`
- `clear_illusions` / `clear_time` / `clear_bleed` / `clear_quick`
- `realm enter` / `realm exit`
- `status`
- `reset`

Example: `/loki @s unlock all`, then `/loki @s transform true`.

## Architecture

The server owns mastery, cooldowns, spells, held targets, projection navigation and aggression, weapon hit timing, bleeding, temporal fields, sanctum plots and transformation state. Client presentation covers layered Player Animator gestures, composited player skins, cloth simulation, authored meshes, illusory architecture geometry, additive particles and a dedicated post-processing chain.

**Cloak.** The cape is solved in world space, not in model space. Row zero of a 16×9 Verlet grid is written directly to the shoulder line every tick, so the cloak is attached by construction; every other row is a particle that lags behind. Trailing while running, lift on a fall, the sideways throw of a sharp turn and settling on landing come out of that lag rather than from a wave function. It collides against a capsule around the wearer and against the ground, carries itself through teleports rather than snapping taut, and is drawn in world space so it never inherits the mirrored model transform. Nothing about it is networked.

**Weapons.** Meshes are authored in Minecraft item-model units with the grip at the origin and the blade running up +Y. The item renderer hands a custom renderer the corner of the item's unit cube — the same space a vanilla sprite occupies — so correct hand alignment only requires putting the grip where a vanilla hilt sits and laying the blade along the diagonal that the inherited `item/handheld` transforms are built around. Off-hand twins apply the same placement rotated a half turn for a reverse grip.

**Telekinesis.** Targets ride a damped spring toward a smoothed aim point. Held players receive motion packets and are only repositioned when they have genuinely escaped, so ordinary struggling stays smooth instead of rubber-banding.

**Time.** Fields query bounded local volumes, cap their entity count and retain independent ownership. World tick rate never changes. The world, inventories, blocks and other players' health are never rewound. Temporal history is limited to 50 position/rotation/health samples per player. Players receive at most three seconds of suspension followed by a five-second protection window. Creative and spectator players are exempt.

**Sanctum.** `loki:pocket` is a void dimension defined by datapack. Plots are allocated once per player and remembered in that dimension's saved data; the platform around the arrival point is laid immediately and the remainder is built across the following ticks, budgeted per tick. The way home is stored outside the transient state block so a dimension change cannot erase it.

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

While transformed, press **J** to toggle Cosmic Flight. **Space** rises and **crouch** descends; vanilla double-jump flight controls also work. Change the binding in Minecraft's Controls menu. A green spatial nebula and star filaments surround flying players.

The HUD shows eight named ability cards with recovery/ready status, selection and energy. Hold the select key and scroll to switch. Existing realms receive the larger island, organic tree/roots and rebuilt throne through a resumable upgrade; no world reset is needed.
