# Warping — the break

Base: successful Actions run 35646580893.

Scope: the portal the Warping ability opens. Destinations, timing, teleport behaviour, sounds,
cooldowns, the realms themselves and everything the portal shows through it are unchanged except
where this document says otherwise.

## The silhouette is the fracture

The portal was a thirty-two sided polygon with a jag applied to each radius, with cracks and facets
drawn on top of it. That is a rough circle, and the eye believes the silhouette: a rough circle with
decoration on it reads as a decorated circle, not as broken glass. Nothing in `WarpFracture` has a
radius. The outline is the union of what broke.

- **The impact.** Nine to thirteen vertices at uneven angular steps and deliberately extreme radii —
  roughly a quarter of them are deep notches and a quarter are long spikes, so the hole itself is
  angular rather than round. It is the largest single opening and the most usable part of the
  portal, and it is the only part that grows in every direction with the charge.
- **Cracks.** Six to ten, anchored on the impact's own vertices, each a chain of three to six
  straight segments that changes heading at every joint and occasionally turns violently. Lengths
  come from a heavily skewed draw, so one side of a break routinely carries a fracture several times
  longer than anything opposite it. Every chain narrows linearly to nothing, which is what makes the
  far end a point rather than a cap, and each carries up to two branches that split off part way
  along and taper to points of their own.
- **Shards and splinters.** Wedges opening on the impact's rim between two fractures, cut in two
  along their length, and thin free-standing slivers further out.

Measured across seeds at full charge: the outline's longest direction is five to eight times its
shortest, forty or so pieces end in a geometric point, and no two seeds agree. `verifyWarping` holds
all of that, including that the same seed is the same break on the server and on every client.

## It develops, it does not scale

Every feature is drawn once from the portal's seed in an order that does not depend on the charge,
so the pattern has an identity from the first tick and keeps it. The charge decides which features
have been born and how far each has run: the impact widens, cracks already there extend along their
own paths, branches appear part way out, wedges open between fractures, and loose slivers arrive
late. Nothing is re-rolled, so nothing flickers or re-shapes. The build checks that the outline at
full charge is not the outline at a third charge scaled up, and that nothing which had already
cracked has closed again.

## It lies on the ground, not on a plane

Every point of the fracture asks `WarpSurface` what it is lying on and gets the top of the actual
collision shape in that column, so a slab is half a block and a stair's lower half is half a block.
Two rules keep it from becoming square decals: the answer is per block column, so a piece spanning a
step is one slanted surface between two heights rather than two tiles; and the vertical window
widens with distance from the impact, so a fracture may climb a slope as it travels without jumping
onto a cliff that happens to be nearby. A column with no surface in its window — a wall, a hole, a
cliff face — is not part of the break, and the piece is dropped rather than floated.

The heights are sampled per column, cached, and re-asked a couple of times a second, so a floor
changed under an open portal is followed.

## What the portal shows, and what it takes

The stencil sequence is untouched: mask, Nothingness backing, depth reset inside the opening, the
destination scene and its entities, then the floor depth restored. Only the geometry handed to it
changed, so the destination is now seen through the fracture itself — the impact and the wedges as
wide openings, the cracks as the same dimensional light, with the rim glow, glass facets, peeling
slivers and opening sweep all hung off the fracture's own edges instead of a ring.

The way through matches what can be seen to be open: the impact, the open half of each wedge, and
the roots of fractures that are still genuinely wide. Width is judged at a piece's narrow end and
against the size of the break, so a hairline on a twenty-eight block tear is judged as a hairline.
No teleport reaches the tip of a crack; the build checks that the farthest way through is well
inside the outline and that the middle of the break is where it is concentrated.

## The point is locked when the hold begins

The crosshair chooses the point on the tick the hold starts, and that is the last time it is asked.
Nothing re-casts a ray while charging, nothing moves the break to wherever the camera went, and
losing sight of the floor, turning around or looking at the sky does not interrupt it. Being hit
does: any damage to the caster collapses the charge and cleans up the locked point, the shape, the
charge clock and everything the watching clients were drawing.

## Size is bought

A full hold is twenty-eight blocks across rather than ten, and the whole fracture spends it: an
impact hole of a few blocks and cracks running fifteen to twenty blocks out from the centre. Size is
paid for — half the ability's cost for the smallest usable tear, double it for the largest — and a
caster who holds past what their energy covers opens the largest break that energy buys instead of
being told it did not stabilize. The charge readout shows the width and the energy it will cost.

## Flight

Separately: the mantle now carries flight into every dimension rather than only the fracture world.
Nothing else grants it, so an untransformed player is still on foot everywhere, and the realms no
longer strip it from a transformed keeper on arrival or while the gravity well is pulling them in.
