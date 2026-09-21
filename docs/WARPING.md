# Warping — 0.5.3-warping

Sun, Void Sea and portal silhouette patch based on `7e4f41b230538a6b0aecfd7c2610091aa0f9e5bb`.
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
| Sun | 70-block textured photosphere, contained lava core, anchored plasma flares and Fracture-style stellar galaxies | Escalating stellar heat damages survival casters and captives, including fire-resistant creatures |
| Void Sea | Procedurally infinite dark ocean, 935 blocks deep with 262 blocks of clear sky above it | **The Abyssal Pilgrim**: a ~160-block immortal apex predator. See `docs/ABYSSAL_PILGRIM.md`. |
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
Warping draws a continuous Nothingness surface clipped to the same polygon as the glass. It no longer replaces whole floor blocks. The original terrain, block entities and inventories remain intact. Time Branch Nothingness blocks still use their existing persistent restoration ledger.
The caster can enter their own portal, including in creative mode. Other Loki-capable and creative players remain excluded from trap targeting. Loki-capable players retain environmental protection in the other eight destinations; the Sun harms survival casters too. `/kill` bypasses all mod damage wards. Solar heat is a separate, armor-bypassing damage type, so normal fire resistance does not make a star harmless; creative and spectator modes retain their normal protections.
Released portals continue for 200 server ticks independently of caster movement, ability selection, death or logout. Each entity crosses a given portal only once, so returning with R does not immediately trap the caster again. The leviathan remains hostile to all survival players.
Flight permission is managed by the existing flight system and revoked on leaving as appropriate.

## Rendering

The floor window uses a depth-tested stencil aperture. Jagged fractures expose a spatial destination scene during charging and keep their irregular broken-mirror outline on release.
Branching glass cracks, layered edge glow, reflective tinted facets and a staggered burst of rising triangular shards frame the destination. The closing facets return during the final 0.7 seconds. Server collision uses the exact same outline as the open stencil window.
The black backing, destination stencil, glass facets and entry boundary share the exact polygon, including edges that cross fractional block coordinates. The ground needs no replacement or restoration when the ten-second window expires. Preview clocks track the prepared destination instance.
The aperture and destination share procedural celestial geometry and the deterministic terrain blueprint. Sun and Void Sea reuse the Fracture sky renderer with stellar-violet and ocean-blue palettes; the original emerald Fracture palette is preserved.
The photosphere is an opaque depth-tested surface. Its corona and anchored flares add light without writing depth, and realm geometry renders before particles with explicit depth state so foreground player bodies remain visible.
Preview architecture is an untextured geometric representation of that blueprint; it is not a second live Minecraft world renderer.
Previews show authored initial hazards rather than live remote entities or player-made terrain changes.
The feature requires a depth-stencil render target. Forge's standard render target is enabled through its stencil API.
Compatibility with third-party shader pipelines and alternate framebuffer implementations has not been visually tested.

## Verification

The normal GitHub build compiles and reobfuscates the JAR, includes a source JAR, and runs existing regression checks plus Warping's geometry/timing checks.
The dedicated-server smoke workflow boots a disposable server world to catch datapack and server-side class-loading failures, then checks the Sun caster damage gate, actual generic-kill damage in all nine destinations, fire-resistant/fire-immune solar victims and creative-mode exposure. Its test listener is loaded only with `-PwarpingSmoke` and is excluded from release JARs.
These checks do not replace an in-game visual and multiplayer playtest. In particular, aperture depth composition, moving-platform behavior, shader compatibility and leviathan animation require client testing.
