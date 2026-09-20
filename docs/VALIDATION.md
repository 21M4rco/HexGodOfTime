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
