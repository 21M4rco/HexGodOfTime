# The Abyssal Pilgrim — 0.5.6

Base: successful Actions run 35602014851, commit 3d415465288b5231db5b369cc43d6b196b3ad050.
Forge 1.20.1, GeckoLib 4.4.9. Changes are scoped to the existing Void Sea Pilgrim and its integration.

## Reference rebuild

The authored geometry now contains 22 logical joints and 1,550 cuboids, approximately 152 blocks
nose to tail. The layered, flattened cranial shield has longitudinal irregular teeth, independent
upper/lower/split jaws, exposed violet sensory nodes, trailing cheek tendrils, three prominent swept
dorsal sails, paired curved hanging ribs, overlapping armor and a tapered forked whip tail.
`tools/generate_pilgrim.py` is the reproducible asset source. Existing texture/audio assets remain.
This is an interpretation of the supplied image, not a pixel-identical reconstruction or a claim
of an in-game visual match. A single image does not specify hidden surfaces.

## Movement and rendering fixes

The original Euler-difference parent chain accumulated incorrect 3D rotations; head tracking and
keyframed waves also moved the whole spine away from server contact volumes. Each logical joint is
now a direct child of root and placed on the interpolated historical world path. Heading, pitch
and local banking are composed as rotations before conversion to GeckoLib's Z-Y-X convention.
Decorative appendages still use the existing animation clips and procedural secondary motion.
Head tracking no longer transforms following joints. Jaw posing is applied once rather than added
to a second set of jaw keyframes; airborne bites retain an open mouth through their active phase.

The realm uses its actual fixed waterline/floor instead of surface heightmaps, which can include
player platforms. Breach preparation waits for descent and alignment, with bounded timeouts. The
longer active/recovery windows allow ascent, ballistic flight and re-entry. Impact detection can
outlive the attack clock. Full-body culling uses the reconstructed body bounds plus appendage margin.

## Hunting and contact

Target selection scans loaded entities throughout the dimension at most once per second while
acquiring, including mobs, summons, boats and moving nonliving entities. Players retain priority;
creative/spectator immunity remains. No chunks are loaded merely to find arbitrary prey.
Unloaded non-player entities cannot be detected until Minecraft loads them again.

Natural/chunk-generation/patrol spawns are denied in this realm. The former periodic category purge
was removed: imported animals must remain prey, not vanish. The singleton registry and chunk-ticket
lifecycle remain in place. Body contact includes boats/moving objects and is checked against actual
multipart volumes after segment reconstruction. The head contact volume and held victim position
are centered on the visible jaw chamber, thirteen blocks ahead of the skull pivot. Fake attacks do
not inflict contact damage. Parts are not pickable; the parent rejects normal damage, knockback and
riding. Arbitrary third-party code that forcibly deletes or rewrites entities is not controlled.

## Networking

The server controls AI, attacks, motion and collision. Clients reconstruct the body locally between
compact path checkpoints: 260 relative float triplets (3,120 bytes plus NBT headers) once every forty
ticks, plus a snapshot on tracking start. These restore the real curved body for new viewers and
limit accumulated client drift. Decorative bones are never networked. Existing state, glow, target
and attack metadata and presentation event packets remain.

## 0.5.6 — body limits, steering radius and the leap

### The knot

The body was resolved by sampling the head's recorded path at six block intervals of arc length.
That is faithful and wrong: arc length is not joint spacing, so a path that curls tighter than the
joint spacing produced joints two or three blocks apart in space while the model drew them six
apart, and every cube in the chain overlapped every other. Measured on the pathological inputs, a
0.25 block/tick drift with the old nine degree per tick yaw allowance collapsed the whole 126 block
body into a span of 0.5 blocks, with non-neighbouring joints 0.1 blocks apart and a worst joint
angle of 138 degrees. The 4.5 block crushing ring produced a span of 10 blocks and 1.7 block
separation.

Joints are now placed as rigid `SPACING` links along the path direction, and each link's angle
against the link in front of it is clamped — 9 degrees at the shoulder rising to 19 at the tail,
which is a turning circle of roughly 29 blocks at the thick end. The same inputs now give spans of
27 to 38 blocks, worst joint angles of 16 to 19 degrees, non-neighbouring separation of at least
17.4 blocks, and joint spacing of exactly 6.000 everywhere. `verifyPilgrimMotion` still passes; the
snapshot path a late-joining client reconstructs agrees with the server's to within 2 micro-blocks,
because both derive from the same node array and the limiter is deterministic.

### Steering

`LeviathanMoveControl` bounded turns by degrees per tick scaled by attack commitment. A radius is
what a spine can follow, so a radius is now what is enforced: the per-tick heading change is capped
at what a 30 block circle allows at the current speed, a target closer than 18 blocks is blended
into the current heading rather than turned toward, and the speed penalty for being misaligned
bottoms out at 55 percent rather than 12, so a reversal is a wide arc instead of a stall and spin.
The attack and hunt geometry was widened to match: stalk orbits 46 to 90 blocks with an angular
rate derived from the radius, the crushing ring closing 56 to 30, the vortex at 34.

### SKY_LEAP

A new pattern, and the only one whose purpose is to stop being in the water. The creature reads the
target's drift, iterates an interception point three times (flight time depends on height, height
depends on the lead), dives to about 52 blocks, lines up underneath it, and is then thrown along a
solved ballistic velocity by `LeviathanMoveControl.launch`. Ordinary swimming terms cannot produce
a launch — they bleed speed into a heading over many ticks and cap the result — so the launch
bypasses them and the climb through the remaining water is uncontrolled. In the air the only
correction permitted is 0.05 blocks per tick of horizontal drift; height is decided at the launch.

It is triggered directly rather than through the attack roll, on a 140 to 340 tick cooldown,
whenever a target is off the water, within 90 blocks horizontally and under 58 blocks up. The
existing `BREACH_BITE` keeps its long dive and its role as the ambush set piece, and now uses the
same solved impulse instead of steering at a point in the sky, which is what left it wallowing at
the surface with its nose in the air. Both aim at most 110 blocks above the waterline; higher than
that, flight has won.

### Smoothness, and why it twitched

Three independent causes, all of which read as the same symptom.

*The checkpoint lurch.* `AbyssalPilgrimEntity` broadcasts a path checkpoint every forty ticks and
`acceptSnapshot` set `leading` to the anchor the packet carried, which is the server's position at
send time. The renderer draws every joint as an offset from the *client's* interpolated entity
position, and a client entity is always a couple of ticks behind — `LivingEntity.aiStep` eases it
toward each movement packet. So twice a second the head was placed where the head was not, by
exactly the lag distance, and the next `push` put it back. Checkpoints are now re-anchored onto the
viewer's own position: only the shape was ever wanted from the server, and pinning that shape to
the local head makes the correction invisible. A checkpoint further than a teleport from the local
copy is still taken at face value, because that is a creature that has genuinely jumped.

*Two ringing springs.* The per-joint bank used damping 0.78 against stiffness 0.09, whose poles sit
at magnitude 0.88 with a twenty one tick period; the client's sway and lift solver used 0.74
against 0.22, magnitude 0.86 with a thirteen tick period. Both ring for one to two seconds after
any input, and a creature under continuous steering supplies continuous input, so neither ever
settled. The bank is now a first order ease and the appendage solver is near critically damped.

*Waterline flicker.* `isSubmerged` was a bare threshold recomputed by each of its five callers,
and it switches both the animation state and the appendage solver's drag terms. A body holding the
surface — which several toy behaviours do on purpose — flipped state on alternate ticks. It is now
resolved once per tick, before the AI and move control run, across a band from 1.0 below the line
to 0.6 above it.

### Self intersection

`bendLimit` now derives from `PROFILE[index]` rather than from the index. Girth is what limits a
bend: the hull is eleven blocks across at the shoulder and its joints are six apart, so it is wider
than the gap between them and every degree of bend there is solid geometry pushed through the
neighbouring segment. Seven degrees at the widest point, rising to twenty one at the tail tip.

That makes the stiffest part of the spine a forty nine block turning circle, so `TURN_RADIUS` went
from thirty to fifty. The two constants have to agree in this direction: steering tighter than the
spine can follow means the joint limiter clamps the body away from its own path every tick, which
is a creature sliding sideways through its own turn rather than swimming around it.

### Pace

`LeviathanMoveControl.SCALE` multiplies every commanded speed and every burst. The patterns were
written in blocks per tick without reference to the size of the body carrying them, so the lunge
ran at 4.7 blocks a tick before drag, settling near 6.3 — a hundred and twenty seven blocks a
second, twenty three times a sprinting player, for a creature a hundred and fifty blocks long. At
0.35 the cruise is 5.2 blocks a second, a committed hunt 14, and a lunge 29.

Scaling in one place keeps the relative pacing of every pattern exactly as written. Three things
had to be restated against it because they compare against an absolute speed rather than a
relative one: the locomotion clip thresholds, which would otherwise never select the dive or fast
swim animations again; the brushing contact damage and shove in `bodyContact`; and the undulation
term in the client appendage solver, which would otherwise hold the fins still at cruise. Leaps
are deliberately exempt — `launch` and `arcTo` solve a ballistic problem and have no business being
scaled by a swimming constant.

### Arrival

`VoidSea.ARRIVAL` moved from three blocks above the waterline to fifty. Three put the player in the
water before they had seen the realm at all. Fifty is about two and a half seconds of fall with an
empty horizon, and water negates the landing, so the whole cost of the drop is its duration.

### Skin

`tools/generate_pilgrim_textures.py` replaces the skin and emissive mask. The geometry, the 4x2
atlas layout and every UV are unchanged, because the model is the contract. Two properties matter
and neither held before: the tiles wrap seamlessly, since a tile with an edge has that edge drawn
around the outline of all 1,550 cubes; and contrast stays low with detail fine, since high contrast
marks the boundary of every cube it lands on. The appendage bone that covers more than half the
drawn faces was also cooled and darkened, and the rib blades' procedural splay was cut from roughly
60 degrees of swing to 23, because a fan of bright blades standing off the hull is the shape that
reads as scattered debris rather than as an animal.

## Verification and limits

`verifyVoidSea` checks realm constants, geometry size, required bones, animation references and audio.
`verifyPilgrimMotion` exercises slow curved movement, joint spacing, late-viewer checkpoint recovery,
teleport history reset and banked 3D orientation. GitHub Actions builds the mod and runs these checks.

A successful build does not establish visual quality, breach timing, multiplayer feel, sound mixing
or performance in a populated modpack. These still require Minecraft client/server playtesting.
Existing stalk/toy/ambush/frenzy behavior, attack repertoire, sound bank and administrative sinking
sequence were retained. Normal gameplay cannot trigger the death sequence.
