# Foundation inspection

Source pinned to 21M4rco/HexHaki commit `770385f9f3ae5b92a5c40213203afc928a27373c`, the latest successful Action inspected (run 35447255955).

The source tree was inspected before implementation. Its toolchain is Minecraft 1.20.1, Forge 47.4.10 and Java 17. Dependencies included Player Animator, BendyLib, GeckoLib, Photon, LDLib, Pehkui, Citadel and MixinExtras.

Reusable systems retained and adapted:

- XP-to-mastery curve and compact persistent player data pattern.
- Directional SimpleChannel networking and tracking-player synchronization pattern.
- Layered Player Animator registration, fade and first-person support.
- Dynamic skin texture composition, cache bounds, texture disposal and reload handling.
- Segmented cloth solver with constraints; movement-derived wind and teleport resets added.
- Authored quad OBJ loader, UVs, normals and deformation interface.

Gameplay controllers, ability definitions, commands, sounds, original models, packet registrations, cinematics, items, HUD and menus were not carried into the runtime. The replacement registers only `loki` content. No base-mod binary is required or bundled.

Player Animator remains because its tested integration supplies layered motion. The other libraries are omitted because the current Loki systems use the custom mesh, cloth and post-processing pipelines directly; there is no benefit in requiring unused dependencies.

The supplied Kagune archive is a sound-patch package with compiled JARs, rather than a complete normal Java source project. Its character models and sound content were not needed for this conversion.

Both supplied GIF files decoded as one-frame images in the provided copies. Their visible frames were used for the dark crown, deep green mantle and progressive-clothing direction. Full animation timing could not be measured from these copies.

## Second pass

A later iteration reworked the cape, weapon hand alignment, telekinesis, time stop and particles, and added
the fracture and its pocket dimension, commanded projections, thrown daggers with bleeding, hold-to-shape
illusory architecture and a quick bar. The reasoning behind the three bug fixes is recorded in the README's
architecture section, because in each case the defect was a wrong coordinate space or a wrong control law
rather than a missing feature, and that is the part worth remembering.

Player Animator remains the only third-party dependency. Nothing else was added, because the cloth, mesh,
particle and post-processing work in this project is done directly against Minecraft's own rendering and
would not be improved by wrapping it in a library.
