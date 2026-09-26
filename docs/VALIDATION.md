# Validation

## Verified

- Source foundation pinned and inspected before any change.
- Runtime source and registration scan for obsolete gameplay identity.
- Procedural asset generation executed successfully: 5 authored meshes, 6 particle sprites, 25 animations, 14 original sounds.
- Java/Forge compilation through the **Build HexGodOfStories** GitHub Action, which is the only compiler available to this project's working environment.
- Abyssal Pilgrim: compiles against Forge 47.4.10 and GeckoLib 4.4.9, and `verifyVoidSea` passes in the same Action, so the shipped geometry, animation, audio and the Void Sea's Java constants are proven to agree with the dimension JSON. Its body reconstruction was additionally exercised offline against straight, drifting, stationary, tight S-curve, spiral, vertical and post-teleport paths: joint spacing is now exactly 6.0 in every case, no two non-neighbouring joints come within 17.2 blocks of each other on any of them, and the worst joint angle is 21 degrees. The same offline harness reproduced the reported knot on the previous code first (126 blocks of body inside a 0.5 block span, joints 0.1 blocks apart), which is what the joint limits were written against.
- **Dedicated server startup, datapack load and the Warping/Pilgrim regressions**, through the **Warping dedicated server smoke** Action on this branch. A real Forge dedicated server boots, all nine realm dimensions load, and the in-process regression listener passes: the Pilgrim is present in the sea's entity manager the instant it is added, repeated lookups return the same UUID, it ticks and swims with no players connected, it retains vertical pitch under the new steering, it detects prey imported into the water, exactly one remains after a deliberate duplicate is introduced, and a Warping arrival no longer leaves automatic flight switched on.
- Pilgrim skin and emissive mask regenerate reproducibly from `tools/generate_pilgrim_textures.py`, and every tile was measured for wrap continuity at its own borders.
- **Paradise's geometry, offline.** `verifyParadise` runs against the realm's own constants with no Minecraft world: it simulates a running jump tick by tick on exactly the lift, clamp, gravity and drag the realm uses (4.6 blocks up, 14.3 along), and then requires the layout to fit inside that answer. On the shipped table the worst crossing between two spiral islands is 8.9 blocks, no two islands merge or share an outline, every outline runs at least 1.6 times further one way than the other, all three cascade shelves catch their water inside their own rims and at their own surface level, no cascade is driven down through an island, the hot spring keeps a shore of at least five blocks all the way round, the catch is below the deepest keel and the ceiling above everything, and the realm places about 27,000 blocks — roughly seven ticks of the realm builder's own budget. The same harness was run locally during development and found four real faults in earlier drafts: a central island bitten in to a third of its radius, rings too far apart to cross, ponds whose clearance test could never pass, and a first layout whose jump fell four blocks short of its own spacing.
- **The portal's shape, offline.** `verifyWarping` was rewritten with the geometry it checks: the shattered fracture is gone and `WarpPool` is checked instead. Across sixty four seeds and every moment of a pour it holds that the outline is smooth — no direction disagrees with its neighbour by more than six percent of the pool's reach at full charge or eleven percent part way through — that the pool never settles on a radius (its widest direction is 1.9 to 2.2 times its narrowest), that it never runs further than the reach the charge paid for, that no direction ever retreats while the key is held, that the shape at full charge is not the shape at a third of it scaled up, and that two seeds are two pools. The footing rule is checked the same way: a body standing in the middle goes through, floor beside the pool stays floor, nothing goes through where there is no liquid, and across 1,152 sampled rim positions no body whose middle is outside the rim goes through however much of it overhangs.
- **Sinking and struggling, offline.** `verifyWarping` also checks the feel the sink was specified to have, on the two constants that decide it and the arithmetic between them. A crossing at a player's eye height takes between a second and a half and three seconds, and more than four times as long as vanilla gravity takes to fall the same 1.62 blocks — so it is demonstrably not a fall with extra steps. The drag leaves a body that runs into a pool with under five percent of its speed a second later, without setting solid. The escape is checked as a rate by simulating the exact per-tick arithmetic both sides of the wire run: at the break-even rate of six presses a second a body holds its depth to within one press over ten seconds, at six tenths of that it is still a metre and a half further down after ten seconds, at twice it climbs clear of a player's own height, and hitting it faster is worth something at every rate from a standstill to twelve a second. It also pins the fact the server's stuck-body watchdog depends on: a body doing nothing is under in under three seconds, while one struggling at nine tenths of break-even holds out for more than three hundred ticks — so any deadline short enough to catch a client that never let go of the floor would hand the second body a free escape, and the watchdog measures stillness instead.

- **Hexor's exclusion from the recall, structurally.** `verifyHexor` reads `Warping.java` and fails the build unless there is exactly one server-side filter, it refuses the creature by class and by registered type, the queue is built through it, and the transfer asks it again at the moment it moves something. It also fails if Warping ever gains a line that spawns, discards, repositions or casts to the creature.

- **The Scepter's model, offline.** `scripts/generate_scepter.py` regenerates it from the standard library alone, and its silhouette was overlaid on the reference render pixel for pixel. `tools/preview_scepter.py` renders it through the game's own first-person chain (the arm offset, the display transform, WeaponRenderer's pose, the 70 degree hand field of view) with the same per-vertex shading ScepterModel runs, which is how both poses were chosen. `tools/verify_scepter_pose.py` holds the third-person clips level through the full arm, item-layer and renderer chain in both hands, keeps the renderer's poses and the preview's identical, and checks that the caster's beam starts where the generated stone is drawn.
- **Scepter sounds are all public domain.** `tools/import_scepter_audio.py` fetches each one from a pinned commit; every source and its CC0 notice is listed in `SCEPTER_AUDIO_CREDITS.md`.

## Not verified

Everything below needs a recorded in-game session and **has not had one**. Nothing here should be described as working.

- **The whole of Paradise, as a place to look at and be in.** Whether the sky reads as the brief's candy cosmos rather than as a purple fog; whether the five rainbows, four galaxies and drifting confectionery compose or clutter; whether the islands look like land from below and from another island; whether the hot spring glows the way it is meant to; whether the cascades read as waterfalls given that their water is a standing column of source blocks rather than a live flow. The geometry is proven; none of the appearance is.
- **How a fifth of gravity actually feels**, and whether the client's own copy of it and the server's stay in step over a long fall, on a slope, in water, and while another player is watching. The arithmetic agrees by construction; the feel and the prediction have not been played.
- **Candy Rush in play**: whether the freezing tremble reads as a sugar high rather than as damage, whether the icon is legible at 18 pixels, and whether Speed II with Haste III is pleasant or overwhelming.
- **Self-repair under real use**: a player mining a wall out and watching it knit, a build placed in a crater and what the ten seconds of patience feel like, and the restore resuming correctly after a restart inside the window.
- **Eating the terrain**: whether crouch-and-use is discoverable from the tooltip alone, and whether the five tiers are worth telling apart.
- **The pool as a thing to look at.** Whether a spreading liquid reads as liquid rather than as a growing decal; whether the meniscus, the rings and the beads compose at a twenty-eight block pool as well as at a three block one; whether a pool running across a staircase or over a roof looks poured or looks stretched; and what the sheet costs per frame at full charge with several portals open.
- **Falling through, end to end.** Partly played now, and the first session found two faults that no amount of arithmetic would have: players could not enter a pool at all, because `Player.aiStep` clears `noPhysics` every tick on both sides and the grant was only being set from a tick event; and the Void Sea seen through an opening was a flat coloured puddle, because its preview was still drawn against the waterline of 136 the realm had before it was rebuilt to 935 deep, which put the whole ocean a hundred and sixty blocks below the hole. Both are fixed. What is still unplayed is everything after entry. Whether sinking at the shipped rate reads as quicksand rather than as being stuck; whether six presses a second is the right price for getting out, or whether it is so hard that the pool reads as a trap or so easy that it reads as a suggestion; whether the client reading the jump key directly stays in step with the server's copy under latency; whether the shipped sink rate is the rate a body actually descends at, which rests on `move()` running before gravity is subtracted in `LivingEntity.travel` so that a velocity set at the start of a tick is the displacement of that tick rather than that displacement plus 0.08 — the reading is from the source and has not been measured in a running game; whether the sink reads as going under rather than as clipping; whether a player's own client and the server stay in step through the pass-through grant, over a slope, at a rim, and with a second player watching; whether the crossing lands without a rubber-band, a rotation snap or a frozen tick; whether the few frames of refraction cover the level swap or merely decorate it; whether the "downloading terrain" overlay is reliably gone rather than flickering; and whether a mob sinking through one is convincing from the outside. None of it has been played.
- **Watching the far side.** Whether a figure falling away down a drop in another dimension reads as the creature that just went through, and whether the handful of ticks around the transfer are covered well enough that nothing is ever seen to pop.
- **The recall, end to end.** Whether 49 chunks and 44 ticks are enough to wake an unattended realm reliably; whether creatures emerging read as climbing out of the ground; whether ten of them at once is a spectacle or a pile; and what a recall from the Void Sea brings back now that the one thing in it is refused.

- **The Scepter in game.** How the solid model, its per-vertex metal shading and the stone's glow actually look under real daylight, at night and in a cave; the first-person rest, aim, recoil and charge tremble at speed; the third-person clips; the GUI icon; and what the model costs per frame with several decoys holding it. The offline preview reproduces the shading model, not the game.
- **The beam wound.** The see-through hole relies on writing the openings into the depth buffer before the body's batch is drawn. Armour is drawn in a later batch than the body, so a wound pins only to the body's own model (bracketed by `WoundBodyMixin` around `LivingEntityRenderer`'s model draw) and, on anything wearing armour, cuts from just outside the armour's surface so the plate is holed with the body. That bracket is optional: if another mod redirects the same call, wounds fall back to pinning on the first part the beam met, which on an armoured body is the armour. None of it has been seen in game, on armoured bodies, on mobs with unusual models, with Fabulous graphics or with a shader pack.
- **Stopped time.** The pinning and the fixed render instant are argued from the renderer's own interpolation; a stop full of walking mobs, dropped items and flying arrows has not been watched since.
- Client shader loading and visual review.
- Cape behaviour during sprinting, jumping, falling, landing, crouching, rapid rotation and teleportation, viewed from front, rear and sides.
- First- and third-person weapon alignment, including the reverse off-hand grip and the GUI silhouette.
- Thrown daggers embedding correctly in moving bodies and riding them as they turn.
- Telekinesis feel, including a held player fighting the grip in multiplayer.
- The fracture's appearance, the sanctum's construction budget under load, and the return journey.
- Illusory architecture geometry and its per-frame cost at the block cap.
- Two-player transformation, illusion combat, overlapping time fields and projectiles entering a stop.
- Logout/reconnect, death/respawn and dimension changes while transformed or holding state.
- **The Abyssal Pilgrim in the water.** Nothing about how it looks or feels is established. Specifically: whether a 160-block body reads at that scale; the bone rotation sign conventions, which `AbyssalPilgrimModel.YAW_SIGN`, `PITCH_SIGN` and `ROLL_SIGN` exist to make a one-character fix if the spine is inverted; breach and leap arc timing and the feel of crossing the waterline; camera shake magnitude; whether 935 blocks of water costs measurable frame time at high render distance; multiplayer target switching with several players in one Warping cell; and whether the emissive mask suffix GeckoLib expects matches the generated `_glowmask.png`.
- **Whether it now swims smoothly.** Three concrete defects were found by reading and fixed by
  construction — a checkpoint anchored to the wrong position, two under-damped springs whose poles
  were measured, and a threshold with no hysteresis — and the speed scale is arithmetic. None of
  that is the same as watching it swim. Whether 0.35 is the right weight, in particular, is a feel
  judgement that only play settles.
- **The fifty block arrival drop.** Water negates the fall in vanilla and the constant is inside
  the world height, both of which the build checks. How it reads on the way down does not.
- **How the new skin reads in game.** The tiles were measured for seamlessness and designed for low contrast at cube scale, which is an argument, not an observation. Whether the hull now reads as one surface rather than as stacked boxes needs eyes on it.
- **Whether the joint limits are the right ones.** They are provably sufficient to prevent self-intersection and they hold the model's exact pivot spacing, but 7 to 21 degrees per joint is a judgement about how a leviathan should bend, and only play establishes that.
- **What the new damage scale feels like.** The arithmetic is checked by `verifyHexor` and the
  consequence — a fixed handful of blows whatever the health bar says — is arithmetic too. Whether
  four bites is the right number of bites for a warden is not.
- **Trill of the Hunt in a crowded sea.** The curve is checked and the frenzy floor is proven to
  cross the threshold the AI branches on. Whether a sea with eight things in it reads as a creature
  that has stopped playing, rather than merely as a faster one, needs several players in the water
  at once, which has not happened.
- **The broken portal in play.** The fracture's shape, growth, variation, pointed ends and the
  match between the way through and the visible openings are all checked by `verifyWarping`, which
  is geometry rather than appearance. Whether it reads as shattered glass on real ground, whether a
  twenty-eight block tear is the right maximum, and how the fracture looks crossing stairs, slabs
  and a one block step have not been seen.
- **A locked target through a fight.** That the charge survives turning the camera and dies to a hit
  is source-level. The feel of it, and that nothing is left drawn on a client when a caster is
  killed mid-charge, needs two players.
- **Flight in every dimension.** The mantle granting flight everywhere is one condition and is
  exercised by the same tick that always granted it. What it does to the Sun, Paradise and
  the gravity well — all of which were rebalanced in 0.5.6 around not being able to rise out of
  them — has not been played.
- **Thirty seconds of theatre.** The clock, its per-entity ownership and the decided attack table
  are checked by `verifyHexor`. Whether thirty seconds is the right amount of stalking before the
  creature stops performing, and whether the switch reads as a decision rather than as the AI
  breaking, are play judgements.
- **The hunt with nobody in the dimension.** The chunk the hunt holds, the remembered occupants and
  the relocation are source-level and bounded by construction. That something left in the water is
  actually dead when a player returns has not been observed, and what one held chunk ticket costs
  an idle server over hours has not been measured.
- **The Void Sea's arrival title.** That it is sent on every entry is source-level; that the colour
  is legible against the realm's sky during the fifty block fall has not been seen.
- **Flight removal in play.** That no dimension but the fracture world grants flight is enforced in one place and exercised by the server regression for the Void Sea. Whether the Sun and the Crushing Realm are now fair without it has not been played.

## Known limitations

- Illusory walls re-render their blocks each frame rather than baking a buffer. A Massive wall is under 200 blocks and they are drawn within 80 blocks of the camera; that is a deliberate trade for a short-lived effect, not a finished optimisation.
- Compatibility with third-party shader packs is untested.

## 0.5.0 rebrand and Borrowed Reality rework

Source and build checks performed here: whole-tree rename to `hexgodofstories` with no `loki` identifier left outside the two legacy-migration constants; class names matching file names across the tree; compilation, mixin annotation processing (which resolved both new injection targets) and the three assertion suites through the **Build HexGodOfStories** Action; a new `verifyIllusoryWall` suite covering the size table and quarter-turn facing.

Required game checks, none of which have been performed:

- A fresh world with an unlocked account: no HUD, archive, quick bar, key response or mastery gain, and `/hgos unlock <player> on` granting them.
- An existing 0.4.x world loading under the new id, with progression, quick bars, sanctum plots and pending Nothingness restores carried over rather than reset.
- Borrowed Reality raised at each of the four sizes, watched from a turning camera at range, against flat ground and a slope, in daylight and at night.
- Mobs meeting a wall: pathing around it, losing a hunted player behind it and forgetting them, and being turned back when they walk into one. Whether the shove reads as a wall or as a shove is exactly the sort of thing only play can answer.
- The quick slots in the archive and the in-game bar at several GUI scales and window sizes.
- A crossing in both directions with no break left standing at either end, and none opening on arrival while the cast key is still held.

## 0.2.1 patch

Based on commit `9e17eb0716517e855fd107d0b7ca8db8b5294585` (latest successful push build at the start of this patch).

Source checks: exact torso-pose cape anchors; invisibility and Court of Lies guards; separate projection destinations; Fracture cooldown 160 ticks; matching custom sky ID and dimension type; deterministic tree/throne plan; legacy wall removal and persisted build progress. The asset generator only regenerated crown geometry.

Required game checks: sprint/turn/crouch/fall/teleport cape clearance, Court of Lies while wearing armor, stopped-player projection spacing, fresh and existing sanctum entry, sky under normal/Fabulous graphics, throne access, interrupted construction/restart, return portal and crouch exit. These have not been performed in this environment.


## 0.3.0 patch

Base: `b613213476ce543ad954fb31066bf880a3eeaad1`, latest successful build at the start of this task.

Validation added: `verifyRealmShape` checks island bounds, land area, non-cylindrical shoreline, underside taper, a flat throne/arrival approach, and flood-fill connectivity. It runs as part of Gradle `check` and `build`. All 14 sound references were checked against the Minecraft 1.20.1 client `sounds.json`.

Runtime checks still required: two players enter/exit from different dimensions and positions; exit immediately after entry with zero energy; saved return after reconnect/restart; blocked/missing return dimension fallback; Q/CTRL-Q/inventory/death/hopper weapon drops; transferred inventory weapons; throne sit/dismount/dimension change; new and existing island upgrades/restart; cloth in motion; portal mirror appearance and sound balance.

No Minecraft client or dedicated-server playtest was available in the editing environment.

## 0.3.1 — Fracture, cosmos, flight and HUD

Base: `0019138521132de36e47f37624155b17a8233d27` (Actions run `35511132447`).

- Island geometry regression passes: 34,920 connected land columns, over twice the linked build's footprint, coast radius 87–128, underside depth 3–57. Arrival and throne approach remain flat.
- v2 and v3 garden plans are frozen for migration. v4 checkpoints are separate so completed older gardens rebuild once. Only air or matching generated states are replaced; block entities are protected.
- Fracture tap opens a regular doorway. Hold for 12 server ticks captures eligible entities within a five-block sphere, pulls for ten ticks, transfers the captured group and closes. Entrants share the caster's plot; return locations persist per visitor. Transfer failures leave entities at the source.
- J toggles flight while ascended; Space/crouch rise and descend. The binding is configurable. Flight also uses vanilla double-jump controls. Grants are removed on detransformation/death/logout/dimension changes, preserving creative/spectator or previously held flight permission. Disabling flight grants a short landing grace.
- Eight named cards expose readiness and cooldowns. Hold the selection key and scroll to choose; primary/energy/flight hints display beside the cards.
- The cape retains its original collar anchors, with a smooth wider lower profile. Sky galaxies, meteors and wisps are client-only, and the flight nebula uses depth-sorted emissive cloud layers in world space.

A Minecraft visual or multiplayer playtest has not been performed in this environment. In-game acceptance checks: fresh and existing realms (including interrupted upgrades); full crown and throne silhouette; tap/held entry and exit with players/mobs/items, mounted entities, blocked returns and different source dimensions; normal/Fabulous/shader graphics; flight relog/death/detransformation; cape during turns, stairs and flight; HUD at different GUI scales.

## 0.3.2 — Compact HUD and removal of decorative effect wires

Replaces the eight-card overlay with one 204×96 logical-pixel bottom-left readout rendered at 0.8 scale (about 163×77 GUI pixels). Each selected/bound ability has explicit primary/alternate instructions, recovery, cost and energy. Holding V previews three neighboring slots in place; all eight remain scrollable. On narrow viewports the readout sits above vanilla survival bars.

Removes the shared decorative ring/strand mesh passes from spell, transformation, grip, bind and time-field effects, plus all orbital filaments/cross wires from flight. Existing particles and the spatial cloud nebula remain. Projectile bodies, mirror fractures and shooting-star trails retain their functional silhouettes.

Validation: source diff/remaining caller audit; CI build required. In-game screenshot and multiplayer checks have not been performed here.

## 0.4.0 — Cloak, deception, universal shapes and time control

Base: `f747e44d518200504785e7400bca49722646f390` (Actions run `35513523267`, the latest successful push build when this patch started).

### Verified in this environment

- Java/Forge compilation through the **Build HexGodOfStories** GitHub Action, which remains the only compiler reachable from here: the Forge and CurseForge Maven hosts are blocked by this environment's network policy, so no local Gradle build is possible.
- `verifyRealmShape` still passes as part of `check`; realm generation was not touched.
- The four new particle sheets and the ground-blood decal regenerate deterministically from `tools/generate_vfx_sprites.py`, which needs no third-party imaging library, and every emitted PNG was decoded and checked for a correct header, CRC and pixel count.
- Cloth invariants are enforced in code rather than asserted by eye: `TemporalCloth.contain` clamps every node against both its parent and the shoulder seam after each solver pass, `node` re-applies the seam clamp to the interpolated grid, and `BodyFrame` refuses a torso matrix that is non-finite, near-singular or scaled outside a sane range. A cloak longer than its own length is therefore unreachable, not merely unlikely.

### Not verified — needs a recorded game session

Nothing below should be described as working.

- **Cloak:** sprint, jump, fall, land, crouch, rapid turn, teleport, Fracture crossing, flight, transformation mid-motion, and a cloak dragged across stairs, slabs and ledges, viewed from every side and at several GUI scales. The bug this patch targets was reproducible; the fix has not been watched.
- **Projections:** automatic engagement against vanilla hostiles, modded hostiles and a player who struck first; correctly ignoring tame wolves, villagers and unprovoked neutrals; loadout spread across a full court; retaliation landing on the copy that struck.
- **Deception:** whether a mob's target genuinely spreads across the court over a long fight, whether the commitment window feels right or reads as indecision, and whether another player can pick out the original by watching.
- **Masquerade:** a broad sample of vanilla mobs, at least one GeckoLib creature and at least one other modded animated creature; variant preservation (sheep colour, horse markings, axolotl, cat, villager profession, modded variants); bosses and very large or very small bodies; multiplayer synchronisation; the graceful fallback when a renderer throws.
- **Daggers:** chest, head, arm, leg and back hits on walking, running, turning, falling and animating bodies of several sizes; wound spatter and ground pools at a distance and under load.
- **Time:** rain visibly suspended and resuming in phase; arrows and thrown weapons holding position and orientation; Dilation watched for any remaining stutter; a stopped player in multiplayer; overlapping fields from two casters.
- **Performance:** a large battle with several courts, many wounds and an active hold, measured rather than estimated.
- **Regression:** logout/reconnect, death/respawn and dimension changes while transformed, disguised, holding a field or carrying embedded blades.

### Known limitations

- The weather and particle freezes are client-side presentation over a server-authoritative hold. They are applied with optional mixin injectors, so a mapping change disables the effect rather than the game.
- Dilation scales rates rather than time itself. A creature's decisions still run at the normal cadence even though its movement and attacks do not, and gravity is countered by approximation per entity class rather than read from each entity.
- A mimic snapshot is capped; a creature that saves more than about seven kilobytes degrades to its scalar state and then to its plain form.
- A projection does not wear its caster's Mojang cape. Vanilla's cape layer is written against a client player and its swing is driven by fields a projection does not have, so reproducing it needs a layer of its own. This is only visible on a caster who owns a cape and is **not** transformed; the mantle replaces it in the transformed state, where projections matter most. It remains a genuine way to pick out the original in that one case.
- A projection lasts a few seconds and the caster does not. Watching long enough will always separate them; the goal is that a glance in a fight does not.

## 0.4.1 — Fracture modes, exits and sanctum defence

Base: the 0.4.0 branch head, Actions run `35516710856`.

### Verified in this environment

- Compilation through the **Build HexGodOfStories** Action, again the only compiler reachable here.
- The return bug is fixed at its cause rather than patched at its symptom: `PocketRealm.cross` no longer reads a travelling entity's own saved capture point on the way out. A break carries one `FractureAnchor`, resolved from the owner's mode when it was struck, and owner and cargo both use it. There is no code path left that can send a transported entity to where it was seized.
- Mode state is player NBT and the packet carries an index plus an optional UUID; the server resolves and validates every destination, so a client cannot name one.
- Starfall contains no explosion call and no block write of any kind, so terrain damage is structurally impossible rather than merely configured off.

### Not verified — needs a recorded game session

- The selector at several GUI scales; the player sub-list with 0, 1 and many players online; scrolling; the ACTIVE marker after each kind of selection.
- Each mode's destination search in practice: a Nether ceiling, a sealed room, an ocean, a target underground, a target on a roof, a respawn anchor, a destroyed bed.
- Persistence across relog, death, dimension change and a server restart.
- Dragging a mixed group in and out, including other players and modded entities, and confirming placement fans out safely at the new exit.
- The ward under a real volley: melee, arrows, modded projectiles and several attackers at once; the slip never leaving the island; whether the lockout feels like one dodge.
- Starfall against several hostiles at once for readability and for TPS, and confirming the owner takes nothing from it.
- Ambient and rim density at low, normal and high particle settings, and the frame cost of the rim while standing on the coast.

### Known limitations

- The ward answers anything with a source entity. Damage with no attacker at all — falling, the void, drowning — is deliberately left alone.
- `Nearest Player` prefers someone in the same dimension and otherwise takes whoever it can find; it does not rank across dimensions by real distance, because there is no such distance.
- A break opened by a travel mode is still a walk-through door with the normal seven-second life. Holding the cast key inside the sanctum arms the vacuum for every mode, so cargo can be dragged out to any destination; outside, holding always means the inbound pull.
- The selector is refused outside the sanctum on both sides: the panel will not open, and the server drops a selection packet from a player who is not inside one. The saved mode itself is untouched by leaving and re-entering.


## 0.4.0 — Time Branch Unleashing, transformed defence, grasp, throw and meteors

Base: `e943d5e` (`Offer the Fracture selector only where the break has a choice to make`), the latest push at
the start of this task.

### Compilation

**Verified through the GitHub Action, not locally.** The network policy in the editing environment refuses
`maven.minecraftforge.net` and `repo.spongepowered.org` (the proxy answers `403` to `CONNECT`), so
ForgeGradle cannot resolve there and `gradle compileJava` fails before reaching javac. The **Build HexGodOfStories**
Action is this project's only compiler.

`gradle --no-daemon build` passes on the Action: `compileJava`, the mixin annotation processor and refmap,
`verifyRealmShape` under `check`, and the jar artifact. Three errors were found and fixed by that route —
a missing `BlockPos` import in the meteor, and two calls to `DamageSources.source(...)`, whose typed
factory is private in 1.20.1, replaced with the public `DamageSource` constructor over a registry holder.
Remaining output is pre-existing deprecation warnings only.

Source-level checks also performed in the editing environment: brace/paren balance across all sources;
every `HexGodOfStories.*` registry reference resolved against `HexGodOfStories.java`; call-site name and arity cross-check
against the classes added by this patch; and a review of each Minecraft and Forge symbol used against
1.20.1 signatures.

Compiling is not playing, so everything under **Not verified** below still stands.

### Design decisions worth knowing

- **One source of geometry.** `com.hexgodofstories.data.BranchCharge` holds the charge stages, focus point, radii,
  travel speed and timings. Both the server's hit volume and the client's picture are derived from it, so
  nothing can be erased outside the drawn torrent and nothing inside it can survive. `SoftTerrain.cylinder`
  is likewise shared, so the blocks the server removes are the same list, in the same order, that the client
  dissolves — with no packet per block.
- **Two packets per cast.** `BRANCH` on charge, `TORRENT` on release, plus one `ERASURE` per victim. Every
  sphere, strand, arc, dissolve and sound is derived client-side from those. The release packet is also what
  ends the charge on the client, so two packets can never race and leave a sphere hanging.
- **Batched geometry, not particle mass.** `BranchVfx.Painter` gathers quads per render type and emits one
  `getBuffer`/`endBatch` pair each. This is required, not merely faster: Minecraft's shared buffer source
  ends one type's batch as soon as another is requested, so drawing in reading order without gathering would
  both flush a draw call per layer and write into closed builders.
- **A diagonal beam's bounding box is not its volume.** A sixty-block beam pointed diagonally has an ~80,000
  position bounding box; the erasure scan walks axial slices instead, so cost is flat in every direction and
  a hit cap truncates the beam's far end rather than a world-axis wedge of it.
- **Transformed armour is real armour.** Attribute modifiers on `ARMOR` (20) and `ARMOR_TOUGHNESS` (8) feed
  Minecraft's own reduction curve — the same numbers a full diamond set feeds — with no separate damage hook
  and no armour slot touched. Resistance II and Fire Resistance III are renewed on a five-second lease every
  second, so a missed teardown lapses in seconds instead of leaving somebody permanently armoured; login
  strips and re-derives, because attribute modifiers are saved with the player.
- **Erasure is reversible until it is not.** Every hold (gravity, AI, navigation, input) is recorded and
  handed back on interruption, death, dimension change, logout or shutdown. The death only lands after the
  visual sequence finishes, and non-players that survive a bypassing damage source are discarded; players
  never are.
- **The caster's own view is not the audience's.** Held at arm's length the sphere grows well past the
  distance from a caster's eyes to their own hands, so in first person their charge is dimmed and its
  centre pushed out ahead of them — otherwise the screen whites out at exactly the moment they most need
  to aim. Everyone else sees it in full.
- **Directional dissolve without touching foreign renderers.** True geometry clipping is not available
  across arbitrary modded renderers, so past 18% of the sequence the ordinary renderer stands down and the
  body is rebuilt as cuboid fragments sized to its own bounding box and textured from whatever sheet its
  renderer reports. Every lookup is guarded; an unknown renderer costs that body its fragments, never the
  frame.

### Not verified — needs a recorded in-game session

Nothing below has had one. None of it should be described as working.

- Time Branch Unleashing end to end: charge stages at the documented 1.0/2.5/4.0/5.5/7.0/10.0s thresholds,
  the forced release at the hard limit, the compression beat, and the torrent's appearance at low, medium
  and maximum charge.
- That an untransformed press does nothing at all — no pose, no sphere, no energy, no packet.
- The plant: walking, sprinting, strafing, jumping and Cosmic Flight all refused while aiming stays free.
- Agreement between the visible torrent and what it hits, at 60 blocks and at every charge level, including
  a body clipped by the very edge of the volume.
- Soft cover dissolving with no item drops, and hard structural blocks and builds surviving a direct hit.
- Erasure of: vanilla mobs, passive mobs, villagers, a second player, a vanilla boss, and at least one
  modded creature with a non-standard renderer.
- That a caster is never caught by their own torrent, their own arcs or their own dissolve.
- Interruption paths: detransforming, dying, being frozen, logging out and changing dimension mid-charge and
  mid-erasure, and that nothing is left stunned, floating or invisible afterwards.
- Frame cost of a maximum-charge torrent at close range with several bodies erasing at once, and the same at
  distance, under normal, Fabulous and third-party shader graphics.
- The layered charge audio, the full-power cue at 5.5s, the discharge and the sustained roar, in multiplayer
  from a second client.
- Transformed defence: measured damage reduction against a known hit, the two effects present and invisible
  on the model, and both gone after detransforming, dying, relogging and crossing dimensions.
- Telekinesis: a held player in multiplayer with no rubber-banding, mass limits at low and high mastery,
  scroll push/pull, the throw and its slam damage, and the grasp's appearance from several angles.
- Emerald Throw: quick and charged forms, the charged burst's radius, and the projectile's look in flight.
- Meteors: entry from ~190 blocks up on a long diagonal, the burn audio through the descent, the arrival, the
  chance gate actually gating, and confirmation that the island still takes no terrain damage.


## 0.4.1 — Full penetration, Nothingness and restoration

Base: `6dd90e8`. The beam no longer collides with the world at all, its reach is 100 blocks, everything it
passes through is temporarily replaced with `hexgodofstories:nothingness`, and the world is restored about thirty
seconds after the torrent ends.

### What the restoration guarantees, and why

- **Recorded before replaced, completely.** `NbtUtils.writeBlockState` captures the block state with every
  property it carries — orientation, half, shape, waterlogging, a fluid's own level, and whatever a mod
  added — and `saveWithFullMetadata` captures the block entity. A chest returns with its inventory, a
  furnace with its burn time and contents, a sign with its text, a modded machine with whatever it saves.
- **Saved with the world, not held in memory.** The record is a `SavedData`, so a crash, a restart or a
  server stopped inside the window resumes overdue instead of stranding a black tunnel. `ServerStoppingEvent`
  also restores everything outright while chunks are still loaded.
- **Block entities are detached before their block goes.** A chest's own `onRemove` throws its entire
  inventory onto the floor; the restore would then hand the same items back a second time. Removing the
  block entity first leaves that hook nothing to empty. **This is the duplication bug this design exists to
  avoid** and is the single most important line in `Nothingness.take`.
- **Nothing cascades.** Both the carve and the restore use `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` — clients
  are told, nothing else is. No door loses its other half, no torch pops off a wall that briefly stopped
  existing, and nothing outside the recorded volume can be disturbed.
- **The window cannot be interfered with.** Nothingness is unbreakable, has no loot table and no item form,
  so nothing can be mined, placed, pushed or blown up where a record is waiting. That makes the restore
  conflict-free by construction rather than by care. A second torrent through the same wall extends the
  existing record's wait instead of recording the void as the thing to put back.
- **It refuses to clobber.** A position is only restored if it still holds Nothingness or air. If something
  else is there, the world has moved on and the record is dropped — overwriting a player's new block would
  be worse than the hole.
- **Unloaded chunks wait rather than fail.** A position whose chunk is not loaded is deferred and retried;
  after a long patience it is forced through, because loading a chunk is cheaper than leaving a hole.

### Deliberate choices worth challenging

- **Air is skipped.** Only positions that hold a block become Nothingness. Filling the open part of the beam
  would wall the world off with a solid black tube rather than show a hole through it, and would encase the
  caster and anything else standing in the open.
- **Nothingness is solid.** A hole you could walk into is a hole you could be standing inside when the wall
  comes back. It neither suffocates nor blocks the view, so anything that does end up inside one is
  inconvenienced rather than killed.
- **The soft/hard distinction is gone from gameplay.** Everything is taken and everything comes back, so
  there is nothing left to permit or refuse. `SoftTerrain` survives only to decide whether a block sheds
  dust or fragments on screen.

### Erasure, and the absence of a death animation

Sequences now run 150–240 ticks (7.5–12s). A mob's shell is discarded in the same tick its death lands —
after that death has already handed out drops, experience and advancements synchronously — so only the
corpse and its twenty ticks of tipping are thrown away. A player's body cannot be discarded, so the client
keeps the ordinary renderer stood down for as long as it lies there dead, up to a two-minute backstop.
Victims are silenced for the whole sequence and every other source of harm is refused on their behalf.

The body smears rather than crumbles: each piece the front reaches is drawn downstream into fine parallel
filaments, near half in the victim's own colours and far half already light, matching the reference's
directional streaking. Cuboids spinning off in all directions read as rubble and were wrong.

### Not verified — needs a recorded in-game session

**This patch has not been run.** In addition to everything under 0.4.0:

- A beam straight through a furnished house: chests, furnaces, signs, torches, beds, doors and decorations
  all present and identical after the delay, with nothing on the floor and nothing duplicated.
- The same through a modded storage or machine room.
- Overlapping casts through the same wall, and a cast through a wall already carved.
- A server stopped, and separately killed, inside the thirty-second window; and a beam fired across a chunk
  border that is then unloaded.
- Measured tick cost of carving and restoring ~2,400 positions, since an opaque block going in and out
  drives a light-engine recalculation over the whole volume. **This is the most likely performance problem
  in the patch.**
- That Nothingness cannot be obtained, mined, exploded, pushed by a piston, or picked in creative.
- Erasure pacing at 7.5s and at 12s — whether it is now too slow to play against rather than only to watch.
- That no mob tips over and no player corpse is ever visible, from a second client.


## 0.4.2 — Meteor size, a tree that stops nothing, and a seat with an owner

- **Meteors vary.** Each picks a width from one to five blocks, weighted low (`roll^2.3`) so most are
  boulders and a five-block mass is an event. Size drives the renderer's lump cluster, the tail's length
  and density, the entry and impact volume and pitch, the harm (18 + 7×size) and the splash (2.4 + 0.95×size).
- **Bigger falls slower.** Terminal speed drops from 3.6 to about 1.5 blocks per tick across the range, and
  the pull that gets it there drops with it, so a large stone is ponderous rather than merely late.
  Physically backwards, cinematically right; that is the trade being made on purpose.
- **The tree stops nothing.** The fall is stepped by hand rather than handed to the level's own clip,
  because a clip cannot be told to ignore anything. Logs, leaves, the froglight in the boughs, amethyst,
  flowers and moss are passed straight through; the first genuinely solid thing that is not the tree ends
  the journey. A meteor that thumps to a halt in the canopy is a meteor that never arrives.
- **The throne has an owner.** A non-owner clicking it is thrown clear instead of seated; anything living
  that comes within 2.6 blocks of the seat is thrown clear too, on a five-tick cadence over one small box,
  only where a player is already standing. The owner seated in their own hall keeps Regeneration II on a
  four-second lease, so it lapses moments after they stand.

### Not verified — needs a recorded in-game session

- Meteors of each size arriving, and that a large one is visibly slower and visibly heavier.
- A meteor passing cleanly through the canopy and the trunk with no interaction at all.
- That a meteor still cannot damage the island, at five-block size.
- A second player, and a mob, being thrown off the throne; the owner still seated normally; Regeneration
  present while seated and gone shortly after standing.
- That the guard does not throw the owner, their mount, or anything they care about that merely walks past.
