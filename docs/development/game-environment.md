# Steam game environment

Open **Games → Game environment**, directly below **FEX preset**, in Steam
settings or the in-session drawer. The page lists one variable per line with
its value; the chip at the top picks the profile: all Proton games, an installed
game, or another Steam app ID. For a non-Steam shortcut, use its numeric
`compatdata` directory ID (unsigned 32-bit). Changes apply on the next game
launch, including when Steam is already running. A running game must be
restarted. Native Linux games and Steam itself are outside this editor's scope.

Tapping a value opens its menu: the known values (several can be ticked for
multi-value variables), **Edit value…**, **Unset** to leave the variable out of
the game's environment, and **Restore inherited value** (or **Delete** for a
variable with nothing to fall back to) to drop the profile's own entry. Rows
tagged **Default** come from the built-in settings and rows tagged **All games**
from the shared profile. **Reset profile** clears only that profile's entries.
Values are literal strings, with no shell expansion; quotes are only needed when
the consuming program expects them.

## Order

1. Built-in defaults and the selected FEX preset, applied only where the
   inherited environment (Steam launch options, the session, `droiddeck-env`)
   leaves the variable unset.
2. Shared edits.
3. Game-specific edits.

Edits therefore win over matching Steam launch-option variables, while the
built-in defaults give way to them, as WinNative's container defaults do.

## Defaults and available suggestions

The defaults match the setup Final Fantasy VII Rebirth was validated with on
WinNative's Linux mode: `MESA_SHADER_CACHE_DISABLE=false`, `TU_DEBUG=sysmem`,
`VKD3D_FEATURE_LEVEL=12_2` and `VKD3D_SHADER_MODEL=6_6`, plus the selected FEX
preset. `sysmem` makes Turnip render straight to memory instead of in tiles,
including the feedback-loop, fragment-density and multisampled-to-single-sampled
passes it otherwise always tiles. Without it the game lost the GPU at its first
cutscene here; WinNative, which sets it for every game, runs it through. 6_6 is
the highest shader model Turnip supports natively; 6_9 advertised capabilities
the GPU cannot run. FEX's own default remains the
default preset, and Performance + TSO sets `FEX_SMCCHECKS=mtrack` as WinNative's
does.

The Add menu offers the predefined variables not already listed and a custom
entry. Known variables
offer their values in the menu; the Turnip, Zink and Adreno lists follow the
bundled driver (`noconform` and `deck_emu` no longer exist, and
`ZINK_DESCRIPTORS` takes `auto`, `lazy` or `db`). `FD_DEV_FEATURES` entries are
joined with `:`, which is the separator Mesa splits on.

| Variable | Use |
| --- | --- |
| `TU_DEBUG` | Turnip switches; defaults to `sysmem`. |
| `VKD3D_FEATURE_LEVEL` | D3D12 capability override; defaults to `12_2`. |
| `VKD3D_SHADER_MODEL` | Shader-model override; defaults to `6_6`. |
| `VKD3D_CONFIG` | Per-game workarounds such as `nodxr`; not a universal performance preset. |
| `MESA_SHADER_CACHE_MAX_SIZE` | Storage budget for Mesa's shader cache, for example `1G`. |
| `mesa_glthread` | OpenGL threading; test with the affected game. |
| `DXVK_CONFIG` | DXVK configuration options; recent builds accept `dxvk.maxFrameRate = 60`. |
| `VKD3D_FRAME_RATE` | D3D12 frame-rate limit. |
| `DXVK_HUD`, `PROTON_LOG` | Optional diagnostics; leave unset for ordinary play. |
| `PROTON_USE_WINED3D` | D3D9–11 OpenGL fallback for compatibility testing. |
| `PROTON_USE_XALIA` | Proton's gamepad-navigation helper toggle. |

Forcing `12_2` or a shader model changes reported capabilities; it cannot
implement a missing Vulkan feature. Unset either entry to use automatic
detection for that capability. Older or custom components can support a
different set of options. Android Wine wrapper, ALSA-server, Box64 and patched
async-DXVK options are identified in the editor as requiring a different/custom
component; custom entries remain allowed. The old `DXVK_FRAME_RATE` environment
variable was removed from current DXVK, so it is not offered as a suggestion.
Prefer the session frame limiter for ordinary play.

## Launch path and references

WinNative's Linux session filters Android-only options, merges user variables
over the FEX preset, and passes the result to `env -i` before starting Steam. Its
Windows/Wine graphics helper also maps the selected feature level to
`VKD3D_FEATURE_LEVEL`. Reviewed source: [WinNative Linux session](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/runtime/display/XServerDisplayActivity.java),
[variable editor](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/shared/ui/widget/EnvVarsView.java),
[graphics configuration](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/feature/settings/drivers/DXVKConfigUtils.java).

DroidDeck instead publishes an atomic JSON snapshot at
`/root/.config/droiddeck/game-environment.json`, with the built-in defaults under
their own `defaults` key. Both its Valve ARM64 Proton wrapper and adopted
third-party Proton wrappers execute `bannerlator-game-env`, which reads that
snapshot for each real game launch, writes one line with the resulting values to
the session log and uses `execvpe` to start Proton. Probe prefix `compatdata/0`
and non-launch verbs are unchanged. Malformed configuration falls back to the
inherited environment without evaluating its contents. Signed non-Steam prefix
IDs are normalized to unsigned IDs. With `PROTON_LOG` on and no
`PROTON_LOG_DIR`, Proton's log is written to the session's log folder, so it is
shared with the session's logs.

As WinNative's `winnative-proton-launch` does, both wrappers also drop the Steam
client's `steamrtarm64` directories from `LD_LIBRARY_PATH`, so Wine resolves
Vulkan and its other libraries against the runtime, and default
`PROTON_USE_PIPEWIRE=0`, since the session runs PulseAudio and no PipeWire
server. They print the Proton build they start.

Upstream references: [VKD3D capability parsing](https://github.com/HansKristian-Work/vkd3d-proton/blob/master/libs/vkd3d/device.c),
[VKD3D options](https://github.com/HansKristian-Work/vkd3d-proton#environment-variables),
[Proton runtime options](https://github.com/ValveSoftware/Proton/tree/proton_11.0#runtime-config-options),
[Mesa variables](https://docs.mesa3d.org/envvars.html),
[DXVK variables](https://github.com/doitsujin/dxvk#environment-variables),
[DXVK frame-limiter changes](https://github.com/doitsujin/dxvk/releases).

Validation: `./gradlew testDebugUnitTest` and
`python3 -m unittest discover -s tools/tests -p test_game_environment.py`.
Actual game compatibility depends on the installed Proton, VKD3D and Vulkan driver;
these checks do not establish that every device supports feature level 12_2 or SM 6_6.
