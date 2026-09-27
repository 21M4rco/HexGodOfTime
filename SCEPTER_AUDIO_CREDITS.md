Time stop activation: "Time Slow" (time_stop.mp3) by MidFag, CC0 1.0.
Source: https://opengameart.org/content/time-slow
The mod includes the opening 3.5 seconds as Ogg Vorbis, with an end fade. The old time_stop.ogg was replaced.

## Scepter weapon sounds

The shot and the charge are recordings supplied for the mod by its author, kept as supplied in
`tools/audio_sources/scepter/` and prepared by `tools/prepare_scepter_voice.py`: mono down-mix,
leading-silence trim, peak normalisation to -1 dBFS, a 12 ms tail fade and Ogg Vorbis, and for the
charge only, its build-up lengthened at its own pitch (WSOLA) so that its final drop lands on the
tick the stone is full, ten seconds in.

| Mod sound | Source file | Author | Note |
|---|---|---|---|
| `scepter/fire` | `fire.mp3` | supplied by the mod's author | the shot |
| `scepter/charge` | `charge.mp3` | supplied by the mod's author | build-up lengthened from 5.08 s to 9.34 s |

Every other Scepter sound is a published public-domain (CC0 1.0 Universal) recording. None was synthesised
for the mod. `tools/import_scepter_audio.py` downloads each file from a pinned commit and only
prepares it: mono down-mix (so it is positioned in the world), leading-silence trim, peak
normalisation to -1 dBFS, a 12 ms tail fade, and Ogg Vorbis encoding.

| Mod sound | Source file | Author / pack | License |
|---|---|---|---|
| `scepter/impact_0..4` | `explosionCrunch_000..004.ogg` | Kenney, "Sci-fi Sounds" (https://kenney.nl/assets/sci-fi-sounds) | CC0 1.0 |
| `scepter/boom_0..1` | `lowFrequency_explosion_000..001.ogg` | Kenney, "Sci-fi Sounds" | CC0 1.0 |
| `scepter/blast` | `rocket_strong_explosion.ogg` | Team Forbidden, Warfork (warfork_assets_cc0.txt) | CC0 1.0 |
| `scepter/burn_0..2` | `laser_hit0..2.ogg` | Team Forbidden, Warfork | CC0 1.0 |
| `scepter/sizzle_0..2` | `spell_fire_02..04.ogg` | "80 CC0 RPG SFX", in lavenderdotpet/CC0-Public-Domain-Sounds (CC0 as a whole, see its LICENSE) | CC0 1.0 |

Mirrors used (pinned in the import tool): Kenney files from
https://github.com/Mcamento8/open-game-sfx-index (commit 34bbe8b), Warfork files from
https://github.com/lavenderdotpet/CC0-Public-Domain-Sounds (commit f2b6264, folder `warfork-cc0`,
which carries Team Forbidden's CC0 notice), and the sizzles from the same commit, folder `80-CC0-RPG-SFX`. CC0 needs no attribution; it is given here anyway.
