# Loki — Glorious Purpose

Minecraft **1.20.1**, Forge **47.4.10**, Java **17**. This is a development build, not a certified final release.

## Installation and build

Install Player Animator **1.0.2-rc1+1.20** (CurseForge file 4587214) on clients. Put the Loki JAR on the server and each client. Run `gradle build` with Gradle 8.8, or download the mod artifact from the **Build Loki** GitHub Action. The server does not need a graphics context.

## Controls

| Input | Action |
|---|---|
| K | Mastery archive |
| V | Select a spell |
| R | Cast selected spell |
| G | Alternate contextual action |
| H | Glorious Purpose transformation |
| X | Release held targets / resume your time fields |
| Attack / Use with conjured weapon | Combination / dagger throw or artifact action |

Key mappings are configurable. Free your hands before conjuring. Successful spell use trains its discipline; training is rate limited. Temporal progression opens after 600 combined mastery in the four magical disciplines. Glorious Purpose opens after 800 Temporal Mastery. Use the archive's tooltips for individual actions.

## Development commands

All commands require operator permission level 2 and use `/loki <player> ...`:

- `set <discipline> <0..1000>` / `add <discipline> <0..1000>`
- `unlock <ability>` / `unlock all`
- `energy <amount>`
- `transform true` / `transform false`
- `clear_illusions` / `clear_time`
- `reset`

Example: `/loki @s unlock all`, then `/loki @s transform true`.

## Architecture

The server owns mastery, cooldowns, spells, held targets, projection navigation, weapon hit timing, temporal fields and transformation state. Client presentation includes layered Player Animator gestures, composited player skins, independently simulated cloth, authored quad meshes and a dedicated post-processing chain.

Temporal history is limited to 50 position/rotation/health samples per player. Time fields query bounded local volumes, cap their entity count, and retain independent ownership. World tick rate never changes. The world, inventories, blocks and other players' health are never rewound. Players receive at most three seconds of suspension followed by a five-second protection window. Creative and spectator players are exempt.

The cloth uses a 14×7 Verlet grid with constraint passes, shoulder anchors, body/floor limits and teleport reset. Meshes, textures, animation keyframes and original synthesized audio are reproducible through `python tools/generate_assets.py` (Pillow and ffmpeg required only for regenerating assets).

## Validation status

Build and validation status is recorded in `docs/VALIDATION.md`. A successful Java build does not verify in-game appearance, shader compatibility, multiplayer illusion believability or model alignment. This project must not be described as having passed those checks unless a recorded game test actually establishes them.
