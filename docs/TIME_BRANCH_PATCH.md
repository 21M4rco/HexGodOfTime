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
