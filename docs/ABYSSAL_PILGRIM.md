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

## 0.5.8 — Hexor: commitment, voice, the kill line and the water

Base: successful Actions run 35624047134, commit 32549e05c741cc3630c6ebbec1f15806dec94056.
The model, texture, animation clips, keyframes, joint count, spacing and silhouette are untouched.
One animation *state* change is described under "Jaws" and is a timing fix, not a re-pose.

### Name

Display name only. `entity.hexgodofstories.abyssal_pilgrim` now reads **Hexor**, and the two player
facing strings that named the creature — the roar subtitle and the `/hgos pilgrim spawn` reply —
say Hexor. The registry id stays `hexgodofstories:abyssal_pilgrim`: it is what saved worlds, the
sounds, geometry, animation and texture paths, and `VoidSeaShapeTest` are all keyed on, and
renaming it would orphan every existing Void Sea rather than rename anything a player can see.

### Why it circled and never bit

Five separate faults, all of which had to be fixed for the creature to land anything.

**The jaws are thirteen blocks ahead of the body.** `mouthPosition()` is
`position + look * 13`, and the head hitbox is placed there, but every close pattern steered the
*body* at the victim. Driving the body onto someone puts thirteen blocks of skull past them. The
mouth only ever crossed the prey by accident, on the way in, if the pattern happened to start at
the right distance. Strikes now aim through the prey; station keeping patterns aim
`mouthOnto(...)`, which subtracts the jaw reach so the mouth is what arrives.

**A point at the prey is a point the steering refuses to turn toward.** `LeviathanMoveControl`
blends the desired heading back toward the current one inside `TURN_RADIUS * 0.6` — thirty blocks —
because a body this long cannot turn onto something it is nearly on top of. Every approach point
sat inside that radius, so the creature carved past and came round. That *is* the circling. Aiming
forty to seventy blocks beyond the prey keeps the aim point outside the radius, so the heading
converges the whole way in and the body passes through instead of around. Nothing about the turn
rate, radius or speed scale was changed; the mass and the wide arcs read exactly as before.

**One frame of overlap is the wrong hit test for a head moving several blocks a tick.** The head
box is elsewhere the tick before and elsewhere again the tick after. `headSweep` tests the jaws'
actual travel between ticks, so a pass at strike speed registers.

**Play had no clock.** Every non-committing state could be chosen again the moment the last ended,
and could run up to thirty seconds. `pressure` counts ticks with prey inside 150 blocks and nothing
landed on it; `commitment()` is that over 360 ticks. It bleeds the toy, stalk and ambush shares
into hunting, tightens the ceiling on how long any of them may run, closes the stalk orbit, drops
the approach's blind angle offsets, and collapses the moment a blow lands. A fresh hunt is still
about half circling. A hunt that has gone twenty seconds without a hit is not.

**Standing still was a perfect defence.** `AMBUSH` would only strike while unseen, and the one
posture that never breaks line of sight is a player standing still watching the water. It still
prefers to be unseen and still waits for it, but the wait is bounded and a patient starer is taken
anyway. Its station also stopped being re-rolled from scratch every tick, which had it steering at
a point that jumped tens of blocks twenty times a second and made the in-position test a coin flip.

Alongside: the feint dropped from 22% of picks to 8%, never twice running and never past a third of
the commit clock; `PREDATORY_BITE` went from ten active ticks to twenty two, with a range no longer
shorter than the creature's own jaw reach; the range gate now accounts for the jaw reach and the
ground the windup covers, and falls back to a closing pattern instead of silently picking nothing;
post-attack cooldowns roughly halved; and every path that starts a pattern goes through one
`commit()` that also moves the state machine, so the creature can no longer play an attack while
its navigation is still running a circling state.

### Jaws

`PREDATORY_BITE` closed its mouth five ticks into the active phase and then went on dealing damage
for the rest of it with the jaws shut. They now hold open across the whole pass, close as the head
comes off the prey, and ease back to idle over the recovery. No keyframe, bone or pose was changed;
this is the envelope `AbyssalPilgrimModel.jawOpen` drives them with.

### Voice

The supplied clip ships as `hexor_ambient.ogg`, downmixed to mono because Minecraft's sound engine
can only position a mono buffer — a stereo clip plays flat inside the player's head at the same
volume from any distance. It replaces all three ambient branches; attack, grab, breach, impact and
roar cues are untouched. Two rules keep six and a half seconds of loud from becoming wallpaper:
nothing starts while the last call is still sounding, and the gap after it finishes is 55 to 180
seconds, randomised. `VoidSeaShapeTest` fails the build if the file is not mono or if
`HEXOR_AMBIENT_TICKS` no longer covers its length.

### The kill line

`data/hexgodofstories/damage_type/hexor.json` carries the message id `hexgodofstories.hexor`, whose
translation is `%1$s has been devoured by Hexor`. Players killed by Hexor take that damage type;
everything else keeps the generic mob attack, so an ocean of drowned cannot put the line in chat.
The announcement is vanilla's, produced once, obeying `showDeathMessages` and appearing on the
death screen — nothing sends a second copy. `HexorDeathMessageMixin` styles that one component red
at its single exit point, which is the only part a language file cannot express.

### Seeing it through the surface

The creature was legible in full detail through the waterline at any depth. That is not a model or
texture fault. Water is a surface, not a volume: only the faces bordering air are drawn, so nine
hundred blocks of ocean cost one translucent quad and dim nothing behind them. What normally does
the dimming is skylight, which loses a level per block of water — and this realm has
`has_skylight: false` with a flat `ambient_light` of 0.18 under it, so every block of the column is
lit identically and depth costs the creature nothing.

Lowering the realm's ambient light would blind the realm rather than hide the creature, and fog
belongs to the camera rather than to what it is looking at. `LeviathanWaterVeil` puts back the
attenuation the water should have been applying: depth divided by how steeply the ray to the camera
climbs, which is the distance the light actually spends in water, turned into a survival fraction.
The renderer applies it per spine joint — a body this long can have its head under a boat and its
tail a hundred blocks below — as both alpha and a blend toward the biome's water fog colour, since
alpha alone leaves a crisp ghost and tint alone leaves a flat cut-out. GeckoLib passes a bone's
colour to its children, and the emissive layer re-renders through the same path, so the
bioluminescence fades with the body instead of punching through the surface at full brightness.

Deliberate limits. The first seven blocks under the surface hide nothing, so a breach, a back
crossing the waterline or a head coming up under a boat all read at full strength. A camera that is
itself in the water returns full visibility and gets ordinary fog, light and line of sight. The
cutout pass is kept while nothing is being dimmed, so a fully exposed creature draws exactly as it
did before any of this existed; only a faded one switches to a blended pass.

## 0.5.9 — a sea that moves

Base: successful Actions run 35632793742, commit 5ac87716547bcbe3f9faa2c8fc07ffa4245c66d8.
An addition to the Void Sea and nothing else. Hexor's model, textures, animations, AI, navigation,
combat, naming, death message and the underwater concealment of 0.5.8 are all untouched; the only
edits outside the three new files are three wiring lines and the sound table.

### The field

The ocean is a formula, not blocks. `VoidSeaWaves` answers "how far is the surface above the still
waterline here, now" as a pure function of position and game time, so the server can ask what the
water is doing under a swimmer and every client can ask what it looks like to the horizon, and the
two agree with no packet between them. Game time is already synchronised; that is the whole of the
shared state. Nothing ticks, nothing is stored, nothing is sent.

Waves are not spawned. Each of four size classes bins the axis it travels along, and a hash of the
bin index decides whether that bin carries a wave and what shape it has. A wave's amplitude,
wavelength, crest profile and bend are therefore properties of its bin rather than of the moment it
was asked about, so nothing can flicker or mutate while it runs; randomness only ever decides what
exists and how far apart. Resolving every wave that can reach a 224 block patch returns at most
sixteen of them, and a frame resolves them once and then reads them thousands of times.

One heading for the realm, and every class runs along it. Crests bend — a wavefront that is a ruler
reads as a wall rather than as water — but the bend is a shape across the front, not a change of
course, so nothing crosses anything. The classes travel at different speeds, bigger faster, which
is both how real water disperses and what stops the composite ever repeating. The crest profile is
steep on the face it is running into and long behind, with a smaller crest following, so a swell
rises, breaks over and draws out rather than passing as a hill.

At one point in the sea that produces roughly sixty crests over seven minutes: median height under
two blocks, the largest over seven, gaps from one second to thirty-five.

### What it does to people

Reading the field and its rate of change under a swimmer gives a flow along the heading and a rise
and fall, and the swimmer's own motion is drawn toward both a fraction at a time. It is momentum,
not a shove — no damage, no knockback, no events. Set against Minecraft's water drag it comes out
at about a third of swim speed under the standing chop, twice swim speed under an ordinary wave
where holding a position stops being possible, and near four times it under one of the big swells.

Waves are felt less with depth, per wave, at that wave's own scale: about a third of the surface at
fifteen blocks down, a twentieth at forty, nothing by eighty. The surface is the dangerous place
and the deep is calm, which is the half of this the hunter lives in.

**Hexor is exempt, and exempt by construction.** The apply method's first line returns for anything
that is not a player. That is the whole of it — a guard rather than a test against the creature's
class, so there is no list it could be added back to and no future edit that quietly puts it on
one. It swims a sea that, as far as its own movement is concerned, is still flat. Nothing about it
was buffed; everything it gains, it gains because the water has hold of the person it is hunting.

### Drawing it

A heightfield around the camera, rebuilt each frame at five block samples out to the render
distance, laid over the water the realm already has. No block is moved and no chunk is remeshed.
The formula takes a fractional tick, so the motion is as smooth as the frame rate however rarely
anything on the server runs. The mesh tapers to nothing at its rim and meets the flat water beyond
it; it fades out in the few blocks nearest the camera, so swimming through a crest does not fill
the screen.

### Why the waves cannot uncover the creature

Three reasons, each sufficient alone.

**The surface only ever rises.** Every term of the field is non-negative, so the drawn surface sits
at or above the still waterline everywhere, always. A trough is the absence of a crest, never a dip
below the water that is already there. There is no geometry anywhere in this that can thin the
column between the sky and what is under it — waves put water in front of the hunter and can never
take any away. `VoidSeaWaveTest` fails the build if that ever stops being true.

**It draws after the entities do.** `AFTER_TRANSLUCENT_BLOCKS` comes after everything alive is
already on the screen, so the surface blends over the creature and never under it. All a crest
passing overhead can do to a submerged body is hide more of it.

**It has no say in the matter regardless.** Concealment is decided in `LeviathanWaterVeil` from the
still waterline and the camera. The wave height is not an input to it, and neither the field nor
the renderer is on its path, so a swell towering over a swimmer and a dead calm hand that code
exactly the same numbers. Nothing in 0.5.8 was modified, bypassed or re-entered.

### Voice

Everything Hexor made a noise with is now the water reacting to it — jaws, tendrils, the tail, the
charge, the breach, the scream, being struck. Only the roar is still its own, and it is the
supplied clip. Both places that can call for it, the scheduled ambient and the moment it tips into
frenzy, go through one gate that refuses while the clip is already sounding and counts the next
silence from the end of it, so the one voice it has can never become two. Call sites, volumes and
ranges in the Java are unchanged; only what comes out of the speaker is different.

## 0.5.10 — the jaws

Base: successful Actions run 35635814686, commit 1e05f948548abe56effd2268e4996f50b01e2c8e.
Audio and three call sites; nothing else.

The supplied bone-crushing recording is eighteen seconds of crunching in loose clusters. Rather
than trim it to one clip, the three strongest passages were located by onset strength and sustained
energy — 5.09s, 9.48s and 13.77s, a little over a second each — and cut out with a four millisecond
fade in, so there is no click at the edit, and a seventy millisecond fade out, so each one dies
away rather than stopping. Each is normalised and downmixed to mono, because Minecraft's sound
engine can only place a mono buffer in the world and not being able to hear which direction the
chewing is coming from is the opposite of the point.

All three live under one sound event, so the game draws a different one on every play and a long
meal never turns into a loop. Every play is pitched between 0.45 and 0.62 — well under where the
recording sits, because the thing doing the chewing is a hundred and fifty blocks long and bone at
its recorded pitch reads as a dog with a biscuit. At that pitch each clip runs about two seconds.

It sounds whenever Hexor eats, which is three moments: the jaws closing on something, which insists
and layers over the water it displaced; the chewing while something is held in them, which is
throttled to a mouthful every second and a half to two and a half so it reads as a rhythm rather
than a drone; and the kill, which insists. The water effects of 0.5.9 are unchanged and the roar
still has its own gate; this is a third voice, and the only one that is neither.

`VoidSeaShapeTest` fails the build if any of the three stops being mono, if any of them grows long
enough to still be sounding when the next mouthful starts, or if two of them ever become the same
recording — which would pass every other check here while quietly undoing the reason for splitting
the source up at all.

## 0.5.11 — waves you can actually see, and teeth that leave a mark

Base: successful Actions run 35635814686 plus the jaws of 0.5.10.

### The swell was drawing and could not be seen

Reported as "I feel the waves but don't see anything happening in the water", which is exactly the
shape of the bug: the physics was right and the surface was in the buffer.

The realm has `has_skylight: false` over a flat `ambient_light` of 0.18, so every block of the water
column is lit to the same near nothing. The surface was drawn through that lightmap and tinted with
the biome's own water colour, which meant it came out at about `(0.013, 0.044, 0.074)` — against
water sitting at roughly `(0.04, 0.12, 0.22)`. Blended at a third opacity, the difference between a
crest and a trough was under two percent of the screen value. Present, correct, invisible.

Two changes. The lightmap is out of the argument: what a swell shows you is light coming back off
it, which is not the block's light, and putting a surface through a 0.18 ambient crushes it to
black. And the shading now runs off the slope rather than the body colour — flat water is left very
nearly alone at seven percent opacity, so a calm sea looks as it always did, while a face tipped
against the light goes to fifty-five percent and lerps toward a pale cool blue. Measured across the
render grid at its real sampling rate, that puts calm water about a third brighter than the scene,
busy water at plus one hundred and thirty percent, and the face of a swell at plus one hundred and
seventy-five. The wave field itself was not touched, so the physics tuning stands.

This makes concealment strictly better, not worse: brighter and more opaque crests mean more water
blended over anything below, the surface still never drops beneath the still waterline, and the
veil still reads only the waterline and the camera.

### Bleeding

Hexor's jaws now leave the same wound a conjured dagger does — one stack of five, the same tick
rate, the same duration. Only the head does it: the bite, the lunge, the deep charge, the surface
ram, both breaches, the throw, and the chewing of whatever is held in the mouth. A tail sweep, a
body crush, the scream and the pressure of being dragged deep stay blunt, and blunt does not bleed.

`Bleed` was keyed on a player, because every wound in it had been a blade's. It now also takes a
plain UUID, and when the owner of a wound is not a player but is Hexor, the ticking damage is dealt
with Hexor's own damage type — so bleeding out from a bite is still a kill by Hexor and still says
so in red, rather than being reported as plain magic.
