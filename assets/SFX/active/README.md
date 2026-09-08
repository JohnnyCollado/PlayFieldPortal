# active/ -- decided sounds

**Integrated.** This is the shipped set: every file here is copied **verbatim**
(byte-for-byte, names included) into `core/core-ui/src/main/res/raw/`, and that
is the last integration step — the `res/raw` folder holds exactly these seven
files and nothing else. `MenuSoundPlayer` / `UiMediaDefaults.bundledDefaultRes`
are the only files that map slot -> sample.

**One deliberate exception:** `sfx_opening.mp3` is Sony's `snd_opening`
(firmware 5.70), shipped as the bundled Boot Sound by explicit project-owner
decision on 2026-09-07 after the admission result was shown. It fails the
admission bar by design — see its section below. Everything else here is
authored by us or verified clean. Reference material lives in `../alt/`,
`../official/` and `../sony_firmware/`, all gitignored. Targets are in
`../REFERENCE.md`.

Admission test: cross-correlate against every reference file and score **below
0.30**.

## The set

| file | slot | dur | peak | attack | T40 | dominant | centroid | max xcorr |
|---|---|---|---|---|---|---|---|---|
| `sfx_cursor.wav` | `sound_scroll` (Navigation: `SCROLL`, `SELECT`, `SYSTEM_BROWSE`) | 38 ms | -10 dBFS | 1.07 ms | 32 ms | 7179 Hz | 7317 Hz | 0.226 |
| `sfx_back.wav` | `sound_back` (`BACK`) | 38 ms | -10 dBFS | 0.98 ms | 31 ms | 6050 Hz | 7234 Hz | 0.190 |
| `sfx_confirm.wav` | `sound_confirm` (`CONFIRM`) | 288 ms | -10 dBFS | 0.77 ms | 138 ms | 3360 Hz | 4249 Hz | 0.094 |
| `sfx_error.wav` | `sound_error` (`ERROR`) | 231 ms | -10 dBFS | 19.1 ms | 79 ms | 974 Hz | 992 Hz | 0.185 |
| `sfx_opening.mp3` | `boot_audio` (Boot Sound) | 3.58 s | -6.8 dBFS | -- | -- | -- | -- | **1.000** *(Sony, by decision)* |
| `sfx_launch.wav` | `sound_launch` (`LAUNCH`) | 4.84 s | -0.3 dBFS | 94 ms | 2937 ms | 44 Hz | 8372 Hz | 0.130 |
| `sfx_notification.wav` | `sound_notification` (`NOTIFICATION`) | 1.04 s | -5.9 dBFS | 111 ms | 672 ms | 721 Hz | 2711 Hz | 0.047 |

`sfx_launch` and `sfx_notification` are the two pre-existing samples that tested
clean; the other five are authored.

The **max xcorr** column is the admission score — peak-normalised
cross-correlation against the closest reference file in `alt/`,
`official/`, `sony_firmware/` or `current/` (the old set). Six files score
below the 0.30 admission bar (values measured at authoring time against the
reference rips, re-verified when the `res/raw` copy shipped).
**`sfx_opening` is the exception: it scores 1.000 — it IS the Sony opening —
and ships by explicit owner decision (2026-09-07), not by admission.**

## Pitch layout

Every short cue is a detuned two-partial tone over a filtered noise bed. They
are separated by pitch, all held clear of Sony's frequencies:

| cue | ours | Sony equivalent | offset |
|---|---|---|---|
| `sfx_back` | 6050 Hz | 6455 Hz (`snd_cancel`) | -6.3% |
| `sfx_cursor` | 7179 Hz | 6838 Hz (the 4-identical sample) | +5.0% |
| `sfx_confirm` | 3360 Hz | 3136 Hz (`snd_system_ok`) | +7.1% |
| `sfx_error` | 974 Hz | 1039 Hz (`snd_system_ng`) | -6.2% |

**Offset is not optional.** Matching a Sony frequency exactly drives
correlation to 0.39-0.45 regardless of how independently the cue is built -- two
tones at the same frequency with the same envelope correlate by construction. A
`sfx_back` trial at 6350 Hz (1.6% off) scored 0.284, nearly failing. Stay at
least ~3.5% clear in either direction and re-run the check after any re-pitch.

## sfx_cursor

Replaces the four Sony slots that share one sample (`snd_cursor`,
`snd_decide`, `snd_category_decide`, `snd_option` -- MD5
`2277c8a6b5c7e0e3435c739041a7cae4`, byte-identical).

One cue covers `SCROLL`, `SELECT` and `SYSTEM_BROWSE`, matching Sony's own
behaviour: `snd_category_decide` is one of the four identical samples, so a
separate category-change sound is not something the PSP ever had. A distinct
`sfx_systembrowse` cue was authored and then dropped in favour of this.

Consequence: `SYSTEM_BROWSE` is audibly identical to `SCROLL`. That is now
deliberate, not the bug it was when `res/raw/sfx_scroll.wav` and
`res/raw/sfx_systembrowse.wav` happened to be the same Sony-derived file.

## sfx_error

Authored from `snd_system_ng`: a pulsing buzz, not a single decay -- three
partials beating ~45 Hz apart, slow 19 ms attack. Sony's own `snd_error` is
digital silence (peak -240 dBFS, all-zero samples), so `ERROR` had no reference
and `System_NG` was used instead per decision.

## sfx_confirm

Authored from `snd_system_ok`: a two-strike chime, second strike at 54 ms and
20% level. Main partials are phase-aligned at t=0 so the attack stays sharp
(0.77 ms) -- randomised phases made the cluster ramp in over ~9 ms.

Fires on: confirm/yes/okay in modals, and save/add/apply/confirm in pickers
(app picker, custom icon picker, game picker in game categories).

Levelled to -10 dBFS, not Sony's -31 dBFS, which would be inaudible next to the
rest of the set.

## sfx_opening -- exception, by owner decision

**This file is Sony's `snd_opening` (firmware 5.70, `flash0:/vsh/resource/...`),
byte-identical to `../official/01. Opening.mp3` (MD5
`9fd358ef8bda3d16d5b876fd139a1259`) and xcorr 0.940 against the `alt/`
re-encode.** The original authored `sfx_opening.wav` (the rewritten-D-quartal
version, 3.02 s, xcorr 0.088) was replaced with this rip on 2026-09-07 by
explicit project-owner decision after the admission result was shown to them.

Decoded: 3.58 s, 44.1 kHz stereo mp3, peak -6.8 dBFS, spectral 10th/50th/90th
percentiles 132 / 392 / 1323 Hz -- the Sony source's low end, which is why it
was previously flagged as "thin" against the reference.

Consequences, tracked honestly:
- It violates the folder's admission rule (1.000 >= 0.30) and the plan's
  "nothing Sony-derived in the build" invariant -- for this one slot only.
- It ships as the bundled default for Boot Sound (`boot_audio`, an
  `AUDIO_TRACK` slot): `UiMediaSlot.bundledDefaultUri()` resolves it as an
  `android.resource://` URI that ExoPlayer opens when the user has no custom
  boot video. There is no `MenuSound` for it -- it plays through the boot
  overlay, not SoundPool.
- If this decision is ever reversed, the authored `sfx_opening.wav` variant
  exists in git history at the previous commit.

## Still open

- `sfx_opening` is now the Sony original by decision (not an authored cue), so
  the "another pass" item is closed. Reverting to the authored WAV is possible
  from git history if the decision is revisited.
- The drawer mappings are live: **open** -> `SELECT`, **close** -> `BACK`,
  `SYSTEM_BROWSE` on filter changes -- all three now resolve to one Navigation
  sample by design.

## Integration -- final `res/raw` mapping

| `res/raw` file | source |
|---|---|
| `sfx_cursor.wav` | this folder, verbatim |
| `sfx_back.wav` | this folder, verbatim |
| `sfx_confirm.wav` | this folder, verbatim |
| `sfx_error.wav` | this folder, verbatim |
| `sfx_launch.wav` | this folder, verbatim |
| `sfx_notification.wav` | this folder, verbatim |
| `sfx_opening.mp3` | this folder, verbatim (Sony `snd_opening`, by owner decision) |

Old `res/raw` files removed in the same pass (Sony-derived): `sfx_scroll.wav`,
`sfx_select.wav`, `sfx_systembrowse.wav`, `sfx_quicksysselect.wav`.
`sfx_favorite.wav` was renamed to `sfx_notification.wav` (same clean sample,
correct label).
