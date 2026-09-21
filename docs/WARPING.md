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
| Void Sea | Procedurally infinite 135-block-deep dark ocean with a bright blue Fracture-style nebula and spiral galaxies | Exactly one abyssal leviathan per instance. Depth crushes below 70 blocks and the dark never lifts |
| Gravity Well | Black singularity, tilted spinning accretion disk and inward debris trails | Victims are flattened into the drawn ring and carried around it, faster the further in they are dragged, decaying into the core over about half a minute. Touching the middle kills |
| Shattered World | Broken and inverted islands, ruined towers, forest fragments and hanging water | Periodic gravity surges; combat across separated terrain |
| Time Storm | Broken platforms and branching temporal structures | Recent-position rewinds and intermittent movement disruption |
| Falling World | Moving cohesive terrain fragments, trees and tower sections | Endless falling loop, moving platforms and debris impact |
| Frozen Moment | Ruined catastrophe, stationary dust, suspended spears and fragments | Loki can release the suspended hazards |
| Crushing Realm | A solid floor, twelve columns and one descending cosmic plane | Five-second warning, then the plane comes down for about a minute, grinding the columns away as it arrives, until the gap is fatal |
| End of Time | Dead fragments, stripped trunks, sparse stars and exhausted timeline remnants | Weakness, fatigue, slow movement and gradual decay |

## The abyssal leviathan

One hunter to a Void Sea instance and no more. Extras are removed on sight, and a slain one is replaced
only after ninety seconds of silence, so killing it buys real time rather than nothing.

It is hostile to everything alive: players, other creatures, and the caster who opened the trap. Only
creative and spectator players are invisible to it. It is deliberately slow — 4.5 blocks a second in
pursuit, 2.3 while it has nothing to chase — and it never stops, never despawns and never loses interest
past its 192-block follow range.

The bite is a telegraph. The jaw starts opening twelve ticks before the strike lands, the animal commits
to the lunge at nine blocks and drives through the whole wind-up, and the jaws then close on a four-block
volume at the model's nose rather than on a selected target: whatever is in the mouth is bitten. What
lands is 100 damage — fifty hearts — through armour, which nothing survives. The regression check proves
both halves of that bargain: the warning is always at least half a second, and a committed lunge still
covers enough ground to reach something swimming away.

It has 650 health and 16 armour, and can be killed.

Its model is a 96-bone, 131-cube box model on the 16-pixel grid, 17.2 blocks nose to fluke, with a
countershaded hide, individually boned teeth, gills, barbels and fins, and a separate emissive pass for
its eyes, photophores and gullet. Swimming is a travelling wave running nose to tail; the bite is an
authored curve driven by the entity's own synchronized countdown, so the animation and the hit are the
same event. Geometry, both atlases and the Java `LayerDefinition` are regenerated by
`python tools/generate_leviathan.py`.

## The well and the press

**The well** is flown the way it is drawn. The accretion disk is a ring from 12 to 35 blocks, tilted
0.28 radians about the X axis, around a black core of radius 10 — and those are now the numbers the
physics uses. Anything that enters is pulled into the ring's plane, carried around it at a Keplerian
speed that rises as it is dragged inward, and decays through the ring over roughly half a minute. Only
the middle kills, and it kills on contact with its own armour-bypassing damage type.

Velocity is set toward a target rather than accumulated as force. A force a player's own movement
fights reads as a stutter; a swept orbit reads as being carried, which is what the realm is for. The
regression check simulates the decay from the capture radius and proves every orbit reaches the middle,
inside three minutes, without the combined orbital and inward speed ever exceeding the field cap.

**The press** was rebuilt around the part that made no sense: an invisible floor rising away from a
visible slab, with victims teleported onto it every tick. The floor is now real blocks and never moves,
so standing in the press is ordinary standing. One plane descends onto it — five seconds of warning,
then about a minute of travel — and twelve stone columns stand between them, ground away level by level
as the plane arrives. The columns are the only clock in the room: how much of them is left is how much
room is left. When the remaining gap is smaller than a victim is tall the crush escalates, and at the
final 1.5 blocks it is not survivable.

## Bounds, fields and limits

Three rules now hold in every destination, because each of them was broken somewhere:

- **Nothing leaves the world.** Every entity in a realm is caught by a floor, a ceiling six blocks under
  the build limit, and a horizontal leash around its own instance: an inward push past 240 blocks and a
  recall to the arrival point past 380, which is well inside the 1024-block instance spacing. The Sun and
  the Falling World answer a fall with another fall; everywhere else the victim is put back where it
  arrived.
- **A field reaches players too.** A server-side velocity change only reaches a player as a correction
  packet, so realm fields push players on a three-tick stride with a proportionally larger impulse, and
  cap the result at 1.8 blocks per tick. The Falling World's extra gravity, which previously did nothing
  at all to players, now applies to them.
- **Nothing holds a victim in a state it cannot leave or die from.** The well's horizon kills instead of
  pinning, the crushing floor catches an entity at any height rather than only above y 90, and falling
  debris is solid so victims are carried by ordinary collision instead of being repositioned every tick.

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
