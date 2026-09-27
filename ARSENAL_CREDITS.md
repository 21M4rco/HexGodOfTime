## Crown of Barrels — models, textures and sounds

The Crown of Barrels' machine guns, its missiles' rocket, the casings the guns throw, their muzzle flash and the
sounds of the guns and missiles are TACZ's, from the default gun pack of **Timeless and Classics Guns Zero (TACZ)**
by the **TACZ Dev Team** — artists NekoCrane, Receke and Pos_2333; programmers 286799714, TartaricAcid, F1zeiL,
xjqsh and ClumsyAlien.

- Source: https://github.com/MCModderAnchor/TACZ, at commit `b43eb84c38e9768d8e73c8b14f0b845669704b38`; the
  TACZ paths below are under `src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/`.
- Licence of these assets: **Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International**
  (CC BY-NC-ND 4.0), https://creativecommons.org/licenses/by-nc-nd/4.0/ — see
  `third_party/LICENSE_TACZ_assets`. TACZ's code is GPL-3.0; none of it is used.
- **Non-commercial.** Because of that licence, these assets — and so any build of this mod that carries
  them — may not be sold or otherwise used for commercial advantage.
- **Unaltered.** The textures and sounds are copied byte for byte. The models are only translated from the
  Bedrock geometry JSON TACZ draws them from into the plain list of textured quads this mod draws them from
  (`tools/import_arsenal_assets.py`): every cube where TACZ puts it, every face with TACZ's own texture
  coordinates, nothing added, moved or removed. The licence counts such a change of format as a technical
  modification, never as adaptation (section 2(a)(4)). Each gun is shown as TACZ shows it with no attachment
  fitted: parts TACZ keeps only for attachments, locators and animation are left out as TACZ leaves them out,
  and the RPK carries the heavy factory stock TACZ fits to it by default.
- **Not a dependency.** TACZ does not need to be installed, and nothing of TACZ's is loaded at run time.

| Mod file | TACZ file |
|---|---|
| `arsenal/m249.mesh`, `textures/arsenal/m249.png` | `geo_models/gun/m249_geo.json`, `textures/gun/uv/m249.png` |
| `arsenal/rpk.mesh`, `textures/arsenal/rpk.png` | `geo_models/gun/rpk_geo.json`, `textures/gun/uv/rpk.png` |
| `arsenal/fn_evolys.mesh`, `textures/arsenal/fn_evolys.png` | `geo_models/gun/fn_evolys_geo.json`, `textures/gun/uv/fn_evolys.png` |
| `arsenal/rpg_rocket.mesh`, `textures/arsenal/rpg_rocket.png` | `geo_models/ammo_entity/rpg_rocket.json`, `textures/ammo_entity/rpg_rocket.png` |
| `arsenal/shell_556x45.mesh`, `…_762x39`, `…_308` and their textures | `geo_models/shell/556x45_shell.json`, `762x39_shell.json`, `308_shell.json` and `textures/shell/` |
| `textures/arsenal/muzzle_flash.png` | `textures/flash/common_muzzle_flash.png` |
| `sounds/arsenal/m249_shoot.ogg`, `rpk_shoot.ogg`, `evolys_shoot.ogg` | `tacz_sounds/m249/m249_shoot_3p.ogg`, `rpk/rpk_shoot_3p.ogg`, `fn_evolys/evolys_shoot_3p.ogg` |
| `sounds/arsenal/evolys_draw.ogg` | `tacz_sounds/fn_evolys/evolys_draw.ogg` |
| `sounds/arsenal/m249_charge_pull.ogg`, `m249_charge_push.ogg` | `tacz_sounds/m249/m249_reload_empty_fast_charge_pull.ogg`, `…_charge_push.ogg` |
| `sounds/arsenal/flesh_hit.ogg` | `tacz_sounds/flesh_hit.ogg` |
| `sounds/arsenal/rpg7_shoot.ogg`, `rpg7_draw.ogg` | `tacz_sounds/rpg7/rpg7_shoot.ogg`, `rpg7/rpg7_draw.ogg` |

How the game presents them is its own: the guns are drawn at a set size, lit by the world, and formed and
dissolved behind a burning edge by the mod's shader; the sounds are played at varied pitch and volume. None of
that changes a file.

The rest of the Crown's sound is the Scepter's (see `SCEPTER_AUDIO_CREDITS.md`): a missile's motor is the
supplied fire recording (`scepter/sizzle`) played lower, and its blast is Warfork's `rocket_strong_explosion`
(`scepter/blast`, CC0) near and Kenney's low-frequency explosions (`scepter/boom_0..1`, CC0) far off.
