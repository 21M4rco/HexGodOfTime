# Time Branch Unleashing — 0.4.2

Based on `a2e334de619926cb1815fdca5207a46d4e75ebfd`, the exact revision built by
GitHub Actions run 35524169474.

- Tap the assigned primary key for less than 200 ms to charge the right fist.
  Requires the same transformation and unlock as the full move. Costs 25 energy;
  separate 8-second cooldown in the existing player-NBT cooldown ledger.
- The charge lasts 10 seconds and allows normal movement. An accepted empty
  right-main-hand melee hit consumes it once. A left-arm swing, held weapon,
  missed swing, block hit, shielded/cancelled hit or another spell does not.
- The punched living target destabilizes and loses body fragments gradually over 12 seconds.
  Existing erasure target protections and server death/removal logic apply.
  Multipart hits resolve to their parent; no nearby-target query or explosion.
- Hold past the tap window to enter the original hold-and-release beam path.
  `TimeBranch.java`, `BranchCharge.java`, `Ability.java`, `Nothingness.java` and
  `TimeBranchRenderer.java` are unchanged. Full charge stages, damage/removal,
  range, terrain, beam animation and 35-second cooldown keep their original values.
- Beam disintegration subdivides the registered renderer's textured surfaces.
  Surviving surfaces stay opaque; detached surface pieces accelerate along the beam,
  shrink and dissolve. Complex meshes have a bounded subdivision budget. Foreign
  rendering that bypasses the supplied vertex buffers cannot be intercepted here.
- First- and third-person energy reuse the beam's glow, strand sheets and temporal
  palette. No charged idle animation overrides movement. The contact animation
  affects only the right arm.
- Client/server protocol is 3: install the new JAR on the server and every client.
  Charged state uses normal player synchronization, including new observers;
  erasure state is resent to players who start tracking an affected entity.

Automated `gradle build` includes the existing realm checks plus tap-boundary,
cancellation, duplicate-release and fragment-lifetime/directional checks.

Runtime acceptance checks: tap and hold the remapped key; walk/jump while charged;
miss, hit blocks, hit a protected player and then a valid target; punch into a
crowd (one victim); expire or cancel the charge; die/relog/change dimension;
observe from a second client; compare vanilla players, small mobs and custom
modded models under the beam. Visual quality and third-party renderer behavior
require this in-game check and are not proven by compilation.

Follow-up presentation patch:

- Punch fragments now leave at staggered times across a 12-second sequence and
  individually fade; the contact flash stays brief. Full beam timing is untouched.
- All arrival locations receive only nebular particles, fixed at the landing point,
  without portal rings, shards, body echoes or a forced reforming body pose.
  The source entrance portal remains. The cast-key interruption latch now reads
  the physical key/button so terrain loading cannot reopen a portal on arrival.
- Transformation and de-transformation no longer dispatch the ascend body animation.
  Skin changes, cape, crown/horns, particles, sounds and transformation mechanics remain.


## Black/green slow-wave rework

- This update is scoped to Time Branch Unleashing.
- The held charge and beam no longer use the temporal hue wheel. Their mass is black with emerald
  pressure edges, and the branch-specific post effect no longer separates the frame into RGB channels.
- First person uses a compact hand/focus aura instead of enclosing the camera in the large containment
  membrane. Third person keeps the full charge and adds the same cloud language used by Cosmic Flight
  around the hands and head.
- The tap move's mechanics are unchanged; its arm and implosion presentation is now black/green too.
- The beam front advances at 2.25 blocks/tick instead of 6.5. Damage, erasure, Nothingness conversion,
  client rendering and restore scheduling continue to use the same authoritative front.


### Visibility/speed correction
- The first black/green pass still dimmed the released local beam to 18%, which made it almost invisible.
  Held charge remains restrained for aiming, but the fired beam now renders at 86% local visibility.
- The black body now uses a dedicated near-solid alpha texture rather than the noisy nebula sheet, so it
  reads as a dense mass instead of transparent smoke.
- Sweep speed is now 0.65 blocks/tick; the 100-block front takes roughly 7.7 seconds to travel.


### Detached focus ball correction
- Charge centre moved from 1.15 to 2.85 blocks in front of the eyes.
- Maximum charge radius is now about 1.12 blocks, keeping a distinct energy bubble in front of the player.
- Body-covering charge clouds were reduced to small hand wisps, thin tethers into the bubble, and only
  three subtle head accents in third person.
- The beam's safe throat is now 0.8 blocks because its authoritative origin is already detached from
  the caster; this keeps the fired wave visually connected to the energy ball.


### Hollow Nothingness / exact restoration correction
- The terrain effect is now a hollow bore. Solid blocks in the beam core are temporarily replaced with
  AIR; only the roughly one-block-thick outer ring is replaced with Nothingness.
- Free-standing water, lava and modded fluid cells are ignored by terrain carving, so the beam never leaves
  black Nothingness cubes floating through a body of water. Waterlogged solid blocks are still treated as
  terrain and are restored with their waterlogged state.
- Restoration is now local to the travelling front: every changed position comes back 80 ticks (~4 s)
  after that part of the beam passed instead of waiting for the entire slow beam plus a 30-second delay.
- Beam-owned wounds force their original snapshot back even if fluid moved into the cavity or a temporary
  block appeared during the window. Block entities are snapshotted with full metadata before removal, so
  chests, inventories, signs, machines, orientation/properties and other saved block data are restored.


### Lethal maximum / Warping banishment split
- FULL charge is now 240 ticks (12 seconds), with a 260-tick hard limit.
- Only a held torrent released at FULL or later uses true Erasure death. Any earlier release keeps the exact
  disappearance animation but reconstructs the victim in one randomly selected destination: Sun,
  Gravity Well, or Void Sea.
- The tap/right-fist move is also nonlethal now: its implosion sequence ends in the same three-way random
  Warping banishment.
- Destination choice is made server-side once when the erasure starts. The target realm is prepared during
  the disappearance animation; if generation is not ready by the last fragment, the victim remains held
  out of time until it is safe to transfer rather than spawning into an unbuilt void.
- Interrupting a held charge now applies the full Time Branch cooldown instead of the old 40-tick recovery.
- The full-charge kill path explicitly assigns the caster as last-hurt player before the kill source lands,
  improving kill/advancement attribution for entities that previously failed to credit the caster.
