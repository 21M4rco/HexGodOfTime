# Liquid portal and multiplayer audit — 0.6.1

Base: afbd99f8df16ea94a8bef3461928050a1b90813b, branch claude/adoring-planck-dmfswl.
The base build succeeded; its dedicated-server run 35862373432 failed at
`Paradise: removed items release tickets`.

## Changes

- Clip the pool into horizontal collision-top tiles before drawing. Terrain height changes no
  longer interpolate diagonal polygons through blocks. Stair treads and slabs use their actual
  collision tops. Refresh terrain samples every five ticks instead of forty.
- Replace the thick raised rim and mound geometry with a shallow meniscus, travelling surface
  waves and broad muted highlights. All effects are position-based rather than per-face colours.
- Opaque portals use ordinary depth testing/writing. Remove stencil clearing, forced far-depth
  writes and GL_ALWAYS overlays; foreground blocks remain occluders.
- Expand crossing detection vertically to include terrain the puddle can reach. Release passages
  when entities leave the query volume or become ineligible. End player passages on lifecycle
  changes and revoke client phasing immediately on abort.
- Accept struggle input from trapped players without requiring unlocked powers. The active
  server-owned passage still determines whether the input has any effect.
- Restore borrowed emergence gravity, collision and mob AI on interruption/removal/shutdown.
- Release residency tickets immediately on confirmed entity removal/transfer, retaining saved
  residency across ordinary chunk unloads. This fixes the baseline Paradise test failure.

Clients and server must both update: protocol version 6 rejects older peers, and the mod-list
version now matches the 0.6.1-liquid artifact. The living movement hook honours both entry and exit
phasing, so vanilla player movement cannot clear the exit animation's no-collision grant.

## Verification gates

`build` includes area-conservation tests across 24 outlines and three spread stages at fractional
block alignment, in addition to existing geometry/gameplay regressions.

Dedicated-server smoke additionally requires real collision-shape tests (flat ground, two-step
hill, slabs, both stair treads, trunks, walls, changed blocks) and lifecycle tests (out-of-volume
entity, changed eligibility, ordinary-player struggle input, restored emergence flags).
The existing Paradise, solar damage, administrative kill, realm loading and unattended Pilgrim
regressions remain mandatory.

These tests are not a two-client play session. In-game visual quality, shader/Fabulous compatibility,
latency behaviour and all abilities end-to-end require client playtesting; no blanket claim of
complete multiplayer correctness is made. The floor renderer leaves buildings and trunks physically
intact and occluding the liquid. It does not paint over foreground walls or remove vegetation.
