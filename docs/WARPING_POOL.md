# Warping — the pool, and falling through it

Scope: the shape of the portal the Warping ability opens, and what happens when something enters
it. Destinations, timing, cooldowns, dimension selection, the keybind, the realms themselves and
everything the portal shows through it are unchanged except where this document says otherwise.

## The portal is a pool

The shattered-mirror fracture is gone. It was a jagged union of an impact hole, branching cracks and
loose slivers, and it read as glass because that is what it was. The portal is now something poured:
a pool of liquid that is not water, spreading outward across the floor from where the caster put it,
and running further for as long as they keep holding.

`WarpPool` is the whole shape. Ninety six directions, each with its own distance from the middle,
interpolated between — so the outline is closed, has no corners in it and cannot grow a spike,
because everything driving it is a sum of low harmonics of the angle. Neighbouring directions
disagree by at most six percent of the pool's own reach at full charge and eleven percent at any
moment during the pour, and the build fails if either stops being true.

It is also cheap in a way the fracture never was: asking whether a point is in the portal is one
interpolation and one comparison, rather than a walk over two hundred polygons. The server does that
question several times per entity per tick.

## It spreads rather than scales

Each direction has its own moment of starting to move and its own pace, both smooth functions of the
angle. Early in a hold the pool is a lopsided bead; half way it is a broad lobed pool with one side
still creeping; at full charge it has run out to everything the charge bought in every direction it
was ever going to. Nothing ever retreats while the key is held — at any two moments of a pour, every
direction is at least as far out as it was — and the shape at full charge is demonstrably not the
shape at a third of it with a bigger number in front, which is what makes holding read as pouring
rather than as resizing.

The furthest the liquid can run is exactly the reach the charge paid for. The lobe profile is
normalised by the most its harmonics can sum to, so a pool cannot quietly overrun its own price.

## It lies on the ground, and covers it

No block is destroyed, moved or replaced. The pool is a ring-and-spoke sheet whose every vertex asks
`WarpSurface` how high the floor is in its own column, so it runs up a step, over a slab and down a
stair and lies on all of them; a vertex whose column has no floor — a cliff edge, a hole — is simply
not part of the sheet, so the liquid stops at the edge rather than hanging past it. It sits a
fraction above whatever it covers, which is the difference between a pool that is on the ground and
one that is fighting the ground for the same pixels.

What is drawn on it is deliberately thin, because the point of the portal is that another world is
visible through it: a few percent of alpha, coloured from the destination, with concentric rings
travelling outward, a bright meniscus following the outline exactly and brightening on whichever
side is currently advancing, and beads thrown up at that leading edge in the colour of the floor the
liquid is taking up.

## Falling through it

A portal used to teleport whatever touched it. It does not any more, and this is the change the rest
of the mechanic exists to serve.

- **The opening is open.** A body standing over enough of the pool is given block pass-through —
  on the server and on its own client at once, because a player's movement is simulated on their own
  machine and the server's copy would otherwise drag them back out with its "moved wrongly"
  correction. The grant lasts only while the body is still over the liquid and inside a short band
  around the floor it came through, it expires by deadline rather than by switch, and three seconds
  without a crossing gives the floor back.
- **"Enough of the pool" is a footprint, not a point.** Nine points of the body's own base are
  asked, and the middle plus over half the rest must be over liquid. So the rim behaves like the rim
  of a pool: stand beside it and nothing happens, stand with one foot in it and nothing happens, and
  go through when most of you is over it. A two-block body needs genuinely two blocks of liquid.
- **Crossing is the head, not the feet.** The dimension change waits until the eye — the camera, for
  a player — is under the local surface. Feet, legs and chest go through first, and the world
  changes at the moment the view does. A tall creature has a high eye and sinks further before it
  goes.
- **It is quicksand, not a hole.** A body in the liquid does not fall. Its descent is taken over by
  the pool's own rate — about two and a half blocks every three seconds — and its sideways movement
  is dragged rather than stopped, so wading toward the rim is slow but possible. A running jump into
  the middle does not carry anybody through: it stops them dead and starts them going down. A
  player's eye is 1.62 blocks up, so going under takes a little under two seconds, which is long
  enough to watch the other world rise around you and long enough to regret it.
- **And it can be fought.** Hammering the jump key lifts a body, and the arithmetic is set from the
  sink rate rather than guessed: six presses a second exactly cancels it, so anything slower loses
  ground and anything faster climbs. Six a second is fast — fast enough to be a thing done in a
  panic rather than casually — and the deeper somebody already is, the longer they have to keep it
  up. Presses are spent on the tick they arrive rather than saved, so there is no banking your way
  out. Rising back above the rim, by thrashing or by wading, gives the floor back and stands the
  body on top of it. Because a body with no collision is never on the ground, the client reads the
  key directly and sends its own press — and that one message is deliberately exempt from the input
  throttle, because a three-tick limiter would otherwise decide the contest itself.
- **The body clips itself.** Entities are drawn before the portal is, and the portal's backing sits
  at the floor's own height following the pool's exact outline, so the depth test paints out exactly
  the part of a body that has gone under and leaves the rest standing. Nothing extra is needed for
  it, and it works in first person and both third-person cameras.
- **Nothing is reset at the seam.** Whatever the body is doing on the tick its eye goes under is
  what it is doing on the far side: heading, pitch, the sideways movement that survived the liquid's
  drag, and the descent it had. A dimension change sends an absolute position packet, and an
  absolute position packet makes the receiving client zero its own velocity — so a motion packet
  goes out immediately behind it, on the same tick, before a frame is drawn without it. A body that
  waded in sideways comes out still travelling that way.
- **Where it comes out.** The realm's own entry point, offset by however far from the middle of the
  pool the body went in, bounded to six blocks. Two creatures that went through opposite sides of
  one pool come out on opposite sides of the entry.
- **No loading screen.** Minecraft puts a "downloading terrain" overlay up on every dimension
  change. A crossing takes it straight back down and puts a few frames of refraction over the seam
  instead — one ring travelling out from the middle of the view with a hair of chromatic split, no
  flash, no wash and nothing that hides the world being arrived in.
- **Sound and particles are restrained.** The muffled note of going under a surface, quietly, where
  the body was rather than where it is going; a ring of liquid closing over the place it went in;
  and a little of it trailing the body on the far side. No portal chime and no arrival nebula.
- **Each body is its own.** Several can be part way through one pool at once, each with its own
  progress. A pool that expires with somebody in it finishes the crossing for anybody past the point
  of no return and gives the floor back to anybody who had barely begun, standing them on top of it
  rather than leaving them inside it. A body that has just come out of one cannot be caught by
  another for a second.

## Seeing the far side

Anything that falls through does not vanish. The server sends the transforms of whatever is near the
destination's entry point — including anything falling away down the drop below it — to the clients
that can see the portal, every few ticks, and every tick while something is crossing. Those clients
draw them inside the aperture at their real coordinates in the destination, so an observer at the
edge of the pool watches a body sink through, keep falling, and shrink away into the other world
until it leaves the window or the terrain there hides it.

They are positions, not entities: nothing is created on any server, there is no collision, no health,
no AI, no inventory and nothing to interact with. They are drawn as figures rather than as models
because everything else seen through a Warping portal is flat-shaded geometry, and a fully textured
vanilla model dropped into that would be the one photographic object in a diorama.

Hexor is left out of this deliberately: a hundred and twenty six blocks of articulated body cannot be
honestly reported as one position and one bounding box, and the Void Sea's view already draws its own
silhouette of the creature.

## What is unchanged

The stencil machinery, the destination rendering, the terrain-adaptive surface sampling, the locked
targeting point, the cost curve, the charge and hold timings, the ten-second lifetime, the entry
permissions, dimension selection, every keybind, and the deliberate crouch-and-X entry — which is
still an explicit crossing rather than a fall, and still the way to follow a trap you set earlier.
