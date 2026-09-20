# Validation

## Verified

- Source foundation pinned and inspected before any change.
- Runtime source and registration scan for obsolete gameplay identity.
- Procedural asset generation executed successfully: 5 authored meshes, 6 particle sprites, 25 animations, 14 original sounds.
- Java/Forge compilation through the **Build Loki** GitHub Action, which is the only compiler available to this project's working environment.

## Not verified

Everything below needs a recorded in-game session and **has not had one**. Nothing here should be described as working.

- Dedicated server startup and mixin application.
- Client shader loading and visual review.
- Cape behaviour during sprinting, jumping, falling, landing, crouching, rapid rotation and teleportation, viewed from front, rear and sides.
- First- and third-person weapon alignment, including the reverse off-hand grip and the GUI silhouette.
- Thrown daggers embedding correctly in moving bodies and riding them as they turn.
- Telekinesis feel, including a held player fighting the grip in multiplayer.
- The fracture's appearance, the sanctum's construction budget under load, and the return journey.
- Illusory architecture geometry and its per-frame cost at the block cap.
- Two-player transformation, illusion combat, overlapping time fields and projectiles entering a stop.
- Logout/reconnect, death/respawn and dimension changes while transformed or holding state.

## Known limitations

- Illusory architecture re-renders its blocks each frame rather than baking a buffer. It is capped at 620 blocks and 64 blocks of view distance; that is a deliberate trade for a short-lived effect, not a finished optimisation.
- Compatibility with third-party shader packs is untested.

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

- Java/Forge compilation through the **Build Loki** GitHub Action, which remains the only compiler reachable from here: the Forge and CurseForge Maven hosts are blocked by this environment's network policy, so no local Gradle build is possible.
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

- Compilation through the **Build Loki** Action, again the only compiler reachable here.
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
ForgeGradle cannot resolve there and `gradle compileJava` fails before reaching javac. The **Build Loki**
Action is this project's only compiler.

`gradle --no-daemon build` passes on the Action: `compileJava`, the mixin annotation processor and refmap,
`verifyRealmShape` under `check`, and the jar artifact. Three errors were found and fixed by that route —
a missing `BlockPos` import in the meteor, and two calls to `DamageSources.source(...)`, whose typed
factory is private in 1.20.1, replaced with the public `DamageSource` constructor over a registry holder.
Remaining output is pre-existing deprecation warnings only.

Source-level checks also performed in the editing environment: brace/paren balance across all sources;
every `Loki.*` registry reference resolved against `Loki.java`; call-site name and arity cross-check
against the classes added by this patch; and a review of each Minecraft and Forge symbol used against
1.20.1 signatures.

Compiling is not playing, so everything under **Not verified** below still stands.

### Design decisions worth knowing

- **One source of geometry.** `com.loki.data.BranchCharge` holds the charge stages, focus point, radii,
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
