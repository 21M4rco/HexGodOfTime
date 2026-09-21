# Warping — 0.5.2-warping

Portal update based on the linked successful run at `296c0e9271c207e0fc46c7874471832ecc0d37e1`.
Existing ability ordinals are preserved. Warping is appended to the existing catalogue at Sorcery mastery 800.
No existing mastery thresholds, abilities, transformation stats or fracture destinations are rebalanced.

## Controls

Select Warping in the existing mastery/quick-slot system.

- **G:** dedicated compact destination selector, with descriptions on hover.
- **Hold R:** aim down at the upper face of solid terrain within 32 blocks. The location locks when charging starts; keep aiming there.
- **Release R:** minimum 1.2 seconds; full jagged footprint within 10 × 10 blocks at 5 seconds. Opening lasts 10 seconds.
- **Step onto your open portal:** travel to its destination with owner flight and return access. The portal stays open for its remaining lifetime.
- **X during charge:** cancel.
- **Crouch + X outside a destination:** follow your own last completed trap for the selected destination.
- **R inside a destination:** return to the saved entry position, regardless of energy and cooldown.
- **X in Frozen Moment:** release nearby suspended spears and debris in your look direction.
- **J in a destination:** toggle authorized flight. Space/crouch rise/descend.

Entry costs 80 energy on successful release and starts a 20-second recovery.
Charging into air, looking away, changing abilities, losing access, dying, disconnecting or leaving the dimension cancels the charge.
The 8-second maximum hold releases a stable charge. Access to the return action never depends on floor targeting.

## Destinations

| Destination | Geometry and environment | Threat |
| --- | --- | --- |
| Sun | 68-block luminous photosphere, contained lava core, violet cosmic sky and stellar streaks | Entry 56 blocks above the photosphere; escalating heat and fire damage |
| Void Sea | Procedurally infinite 135-block-deep black ocean with no land | Custom 650-health articulated abyssal leviathan, circling underwater and lunging for 60 base damage per bite |
| Gravity Well | Black singularity, tilted spinning accretion disk and inward debris trails | Directional gravity grows toward the center; increasing central damage |
| Shattered World | Broken and inverted islands, ruined towers, forest fragments and hanging water | Periodic gravity surges; combat across separated terrain |
| Time Storm | Broken platforms and branching temporal structures | Recent-position rewinds and intermittent movement disruption |
| Falling World | Moving cohesive terrain fragments, trees and tower sections | Endless falling loop, moving platforms and debris impact |
| Frozen Moment | Ruined catastrophe, stationary dust, suspended spears and fragments | Loki can release the suspended hazards |
| Crushing Realm | Upper and lower cosmic planes | Five-second warning, then both planes close gradually until the gap is fatal |
| End of Time | Dead fragments, stripped trunks, sparse stars and exhausted timeline remnants | Weakness, fatigue, slow movement and gradual decay |

Each destination is a distinct registered dimension. Casters receive separated 1024-block-spaced instances.
Prepared instances are reused per caster/destination to prevent canceled charges generating unlimited terrain.
Generation is budgeted to 4096 block placements per realm tick. An unfinished destination cannot receive victims.
Nothingness uses the existing persistent block restoration ledger; original block entities and inventories are retained.
The caster can enter their own portal, including in creative mode. Other Loki-capable and creative players remain excluded from trap targeting. Loki-capable players retain protection from realm environmental penalties.
Released portals continue for 200 server ticks independently of caster movement, ability selection, death or logout. Each entity crosses a given portal only once, so returning with R does not immediately trap the caster again. The leviathan remains hostile to all survival players.
Flight permission is managed by the existing flight system and revoked on leaving as appropriate.

## Rendering

The floor window uses a depth-tested stencil aperture. Jagged fractures expose a spatial destination scene during charging and keep their irregular broken-mirror outline on release.
Branching glass cracks, layered edge glow, reflective tinted facets and a staggered burst of rising triangular shards frame the destination. The closing facets return during the final 0.7 seconds. Server collision uses the exact same outline as the open stencil window.
Only whole interior floor cells are temporarily replaced, and the original floor is restored when the ten-second window expires. Preview clocks track the prepared destination instance.
The aperture and destination share procedural celestial geometry and the deterministic terrain blueprint.
Preview architecture is an untextured geometric representation of that blueprint; it is not a second live Minecraft world renderer.
Previews show authored initial hazards rather than live remote entities or player-made terrain changes.
The feature requires a depth-stencil render target. Forge's standard render target is enabled through its stencil API.
Compatibility with third-party shader pipelines and alternate framebuffer implementations has not been visually tested.

## Verification

The normal GitHub build compiles and reobfuscates the JAR, includes a source JAR, and runs existing regression checks plus Warping's geometry/timing checks.
The dedicated-server smoke workflow boots a disposable server world to catch datapack and server-side class-loading failures.
These checks do not replace an in-game visual and multiplayer playtest. In particular, aperture depth composition, moving-platform behavior, shader compatibility and leviathan animation require client testing.
