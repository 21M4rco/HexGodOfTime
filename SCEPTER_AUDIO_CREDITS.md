Time stop activation: "Time Slow" (time_stop.mp3) by MidFag, CC0 1.0.
Source: https://opengameart.org/content/time-slow
The mod includes the opening 3.5 seconds as Ogg Vorbis, with an end fade. The old time_stop.ogg was replaced.

## Scepter weapon sounds

The shot, the charge and the crackle of a hot hole are recordings supplied for the mod by its author,
kept as supplied in `tools/audio_sources/scepter/` and prepared by `tools/prepare_scepter_voice.py`: mono
down-mix and Ogg Vorbis for all three. The shot and the charge are also trimmed of leading silence,
peak-normalised to -1 dBFS and given a 12 ms tail fade, and the charge has its build-up lengthened at its
own pitch (WSOLA) so that its final drop lands on the tick the stone is full, ten seconds in. The crackle
is one period of the recording's own loop, raised to be heard, its crackles held under the ceiling by a
look-ahead limiter.

| Mod sound | Source file | Author | Note |
|---|---|---|---|
| `scepter/fire` | `fire.mp3` | supplied by the mod's author | the shot |
| `scepter/charge` | `charge.mp3` | supplied by the mod's author | build-up lengthened from 5.08 s to 9.34 s |
| `scepter/sizzle` | `sizzle.mp3`, supplied as `dragon-studio-fire-sounds-405444.mp3` | supplied by the mod's author | fire crackling in a hot hole: the 12 s the recording loops on, from 16.2 s, raised 24.7 dB |

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

Mirrors used (pinned in the import tool): Kenney files from
https://github.com/Mcamento8/open-game-sfx-index (commit 34bbe8b), Warfork files from
https://github.com/lavenderdotpet/CC0-Public-Domain-Sounds (commit f2b6264, folder `warfork-cc0`,
which carries Team Forbidden's CC0 notice). CC0 needs no attribution; it is given here anyway.

## Blade sounds

Gravity Grasp's stab, the dagger going into the neck, is a recording supplied for the mod by its author as
`universfield-blade-piercing-body-352462.mp3` (the name of a Universfield "blade piercing body" effect). It is kept
as supplied in `tools/audio_sources/blade/pierce.mp3` and prepared by `tools/prepare_blade_audio.py`: mono
down-mix, leading silence trimmed so it sounds on the tick the blade goes in, the silent tail cut, brought up 15 dB
to a loudest moment of -10.8 LUFS (from -16.7 at full scale) with its peaks held under -1.5 dBFS by a look-ahead
limiter, a 12 ms tail fade, and Ogg Vorbis.

| Mod sound | Source file | Author | Note |
|---|---|---|---|
| `blade/pierce` | `pierce.mp3` | supplied by the mod's author | 1.51 s, played alone (twice at once) as the stab goes in |
