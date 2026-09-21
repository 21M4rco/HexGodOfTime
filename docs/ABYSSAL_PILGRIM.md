# The Abyssal Pilgrim — 0.5.4

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

## Verification and limits

`verifyVoidSea` checks realm constants, geometry size, required bones, animation references and audio.
`verifyPilgrimMotion` exercises slow curved movement, joint spacing, late-viewer checkpoint recovery,
teleport history reset and banked 3D orientation. GitHub Actions builds the mod and runs these checks.

A successful build does not establish visual quality, breach timing, multiplayer feel, sound mixing
or performance in a populated modpack. These still require Minecraft client/server playtesting.
Existing stalk/toy/ambush/frenzy behavior, attack repertoire, sound bank and administrative sinking
sequence were retained. Normal gameplay cannot trigger the death sequence.
