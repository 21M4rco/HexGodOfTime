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
- The sanctum is a bare platform. It is somewhere to build, not somewhere furnished.
- Compatibility with third-party shader packs is untested.
