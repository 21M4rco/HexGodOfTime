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
| Paradise | Fifteen floating islands on a climbing spiral inside ~90 blocks, a hot spring on the central one, eleven cascades, candy-cosmic sky with five rainbows | None. Weak gravity (a fifth of normal), no fall damage, healing water, self-repairing terrain and edible ground |
| Frozen Moment | Ruined catastrophe, stationary dust, suspended spears and fragments | Loki can release the suspended hazards |
| Cosmic Prison (saved ID: Crushing Realm) | Cratered moon inside luminous restraint bands | Heavy radial gravity; walk around the entire sphere, including the underside; short jumps and slow movement |
| End of Time | Dead fragments, stripped trunks, sparse stars and exhausted timeline remnants | Weakness, fatigue, slow movement and gradual decay |

Each destination is a distinct registered dimension. Casters receive separated 1024-block-spaced instances.
Prepared instances are reused per caster/destination to prevent canceled charges generating unlimited terrain.
Generation is budgeted to 4096 block placements per realm tick. An unfinished destination cannot receive victims.
Warping draws a continuous Nothingness surface clipped to the same outline as the pool. It no longer replaces whole floor blocks. The original terrain, block entities and inventories remain intact. Time Branch Nothingness blocks still use their existing persistent restoration ledger.
The caster can enter their own portal, including in creative mode. Other Loki-capable and creative players remain excluded from trap targeting. Loki-capable players retain environmental protection in the other destinations; the Sun harms survival casters too. `/kill` bypasses all mod damage wards. Solar heat is a separate, armor-bypassing damage type, so normal fire resistance does not make a star harmless; creative and spectator modes retain their normal protections.
Released portals continue for 200 server ticks independently of caster movement, ability selection, death or logout. Each entity crosses a given portal only once, so returning with R does not immediately trap the caster again. The leviathan remains hostile to all survival players.
Standing over enough of a released pool sinks a body into it like quicksand rather than teleporting it: the descent runs at the pool's own slow rate, sideways movement is dragged rather than stopped, and the dimension change waits until the eye goes under. Hammering jump lifts a body against that at a rate set from the sink itself — six presses a second exactly cancels it, slower loses ground and faster climbs back out onto the floor. See `docs/WARPING_POOL.md`.
Flight permission is managed by the existing flight system and revoked on leaving as appropriate.

## Rendering

The floor window uses a depth-tested stencil aperture. The opening is a pool of liquid that spreads
across the ground while the charge is held, and the destination is a spatial scene seen through it.
The sheet conforms to the terrain column by column, sits a fraction above whatever it covers, and
carries a thin film with rings travelling outward, a meniscus that brightens on the advancing side,
and beads thrown up at that edge in the colour of the floor it is taking up. Server collision uses
the exact same outline as the stencil window. See `docs/WARPING_POOL.md`.
The black backing, destination stencil, liquid film and entry boundary share the exact polygon, including edges that cross fractional block coordinates. The ground needs no replacement or restoration when the ten-second window expires. Preview clocks track the prepared destination instance.
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


### Radial realms — 0.5.7

Gravity Well contains no blocks. Legacy terrain is removed as its chunks load, and new block/fluid placement is refused in this dimension. Entities follow a fast, inescapable orbit that steadily shrinks. Contact with the visible ten-block black center kills; the old damage outside the center has been removed. Spectator mode remains available for inspection.

Cosmic Prison keeps the existing `warping_crushing_realm` dimension ID and replaces both crushing planes with a 96-block-diameter moon. The rendered crater surface and ground collision sample the same deterministic radial height field. Gravity is three times vanilla acceleration; movement is deliberately heavy and jumps are low. Movement, eye position, view direction, camera roll, living-entity rotation and bounding boxes follow local radial up. Camera/heading frames are transported continuously across the poles and synchronized to other viewers. The surface cannot be mined; creatures and dropped objects also return to it. The old floor is removed from existing chunks. Creative flight and spectator mode are inspection modes.

The Leviathan change is restricted to its face: hollow forward skull, recessed throat, layered irregular tapered teeth on both jaws and split mandibles, wider resting gape, and asymmetric eye clusters embedded in the cheeks. Body, fins, tail, tendrils, textures and combat behavior are preserved.

Manual client acceptance: in survival, enter the moon, walk a complete circuit through the underside, jump at the top/equator/bottom, check first-person aiming and both third-person cameras, then leave and check normal gravity returns. Repeat with a second player. In the Gravity Well, import a player, creature and dropped item; confirm inward circulation, death only at the center, and that an existing save has no remaining terrain. Inspect the Leviathan from the front and sides during idle, bite and split-jaw attacks.

## Paradise

One deterministic table in `Paradise.java` is the single source for the realm: the server builds its
blocks from it, the client hangs its waterfalls, mist and drifting confectionery on it, and the
portal preview draws the same composition from ninety blocks away. Fifteen islands, each with its
own four-harmonic outline and a keel that tapers to a point, arranged so that every step outward on
the spiral is at most four blocks of climb. Three cascade shelves hang under the rims that feed
them and are reached by dropping.

Gravity hands back 0.064 of vanilla's 0.08 each tick, applied by the realm's own tick for everything
in it and separately by each client for the one player it owns — the same arrangement the Void Sea's
swell uses, so the server decides and the client predicts. Living bodies only: an item's gravity is
0.04 and the same lift would be flight, so dropped things are left to the terminal-speed clamp.
`verifyParadise` simulates a jump on those exact numbers and fails the build if the island spacing
stops matching what it buys.

Water anywhere in the realm grants Regeneration II, Health Boost II and Candy Rush for eleven
seconds, refreshed once a second while submerged. Candy Rush carries Speed II and Haste III —
movement and attack speed as attribute modifiers on the effect itself, mining speed through the
dig-speed event, because vanilla reads that one out of `MobEffects.DIG_SPEED` by name — and makes
the rendered body shake with Minecraft's own freezing tremble. It deals no damage and blocks no input.

Broken terrain is recorded and regrown after 8.5 to 18 seconds, exact state, with a sugar telegraph
before it lands. Only positions in the layout's blueprint currently holding the layout's own block
qualify; nothing is scanned and nothing player-placed is ever restored. Drops from those positions
are marked as they spawn and are edible from then on, anywhere, by crouching and using.

## Dimension entity recall

A second Warping input reaches into the destination chosen in the G menu instead of travelling to
it. The break forms on a locked point of ground for 44 ticks using the identical pool geometry,
then opens for 110 and lifts out up to ten living creatures, one every six ticks, each into a
collision-checked place around the opening and thrown up and outward as it arrives.

Everything is decided server-side from a bare keypress: the destination is read from the player's
own saved selection, the point from the server's own ray, and the eligible set from a filter that
runs again at the moment of each transfer. Before the break opens, 49 chunks around the realm's
arrival point are read once so an unattended realm has its inhabitants in memory to be found — no
ticket is taken and nothing is held. Creatures are carried across with `changeDimension`, so health,
equipment, names, AI state and modded data arrive intact and nothing is left behind as a copy.

Players, projections, armour stands, anything riding or ridden, and everything that is not a
`LivingEntity` are refused. **Hexor is refused twice**, by class and by registered type, and
`verifyHexor` reads the source to make sure it stays that way. A recall that finds nothing still
opens, hangs and closes, hands back half its cost and recovers in five seconds rather than thirty.
