# SFX de-Sony-fication — session handoff

Context for picking this work up in a fresh session. Written 2026-09-07.

## The goal

PlayFieldPortal's menu sounds were sourced from PSP firmware rips. The goal is
an **original** sound set that keeps the XMB *feel* without shipping Sony audio.
Firmware files are reference-only and must never reach a build or a commit.

## Why this was not optional

Four of the six samples shipping in `core/core-ui/src/main/res/raw/` are
Sony-derived. Measured by peak-normalised cross-correlation over all lags
(1.000 = same audio); silence-trimming moved none of these, so they are content
matches, not alignment artifacts:

| `res/raw` file | closest Sony cue | xcorr |
|---|---|---|
| `sfx_error.wav` | `SE13_System_NG` | 0.988 |
| `sfx_scroll.wav` | `SE4_Cansel` | 0.878 |
| `sfx_systembrowse.wav` | `SE4_Cansel` | 0.878 |
| `sfx_select.wav` | `SE2_Cursor` | 0.747 |
| `sfx_launch.wav` | best 0.154 | clean |
| `sfx_notification.wav` | best 0.095 | clean |

They are tracked and have been in history since `507ac29`. Replacing the files
does not remove them from history.

**The mapping was also shifted by one:** `sfx_scroll` / `sfx_systembrowse` are
Sony's *cancel* cue and `sfx_select` is Sony's *cursor* cue. So cursor movement
played a cancel sound and selecting played a cursor sound. `sfx_scroll` and
`sfx_systembrowse` are byte-identical to each other.

## Where things live

| path | tracked? | what |
|---|---|---|
| `assets/SFX/active/` | **yes** | the decided, original set. Only clean audio may enter. |
| `assets/SFX/REFERENCE.md` | yes | measured targets per slot + what makes a cue read as "XMB" |
| `assets/SFX/active/README.md` | yes | per-file provenance, pitch layout, open items |
| `assets/SFX/sony_firmware/` | gitignored | 5.70 firmware rips (extracted by us) |
| `assets/SFX/alt/` | gitignored | TCRF rips, Sony's own `snd_*` names |
| `assets/SFX/official/` | gitignored | earlier mixed rips |
| `assets/SFX/resources/` | gitignored | scratch reference audio |
| `assets/SFX/current/` | untracked | the old Sony-derived working set |

`.gitignore` uses `path/*` (not `path/`) so the `!assets/SFX/**/*.md`
negation still re-includes the docs. Do not change those to directory form.

## What Sony actually ships (measured, not assumed)

From `flash0:/vsh/resource/system_plugin.rco`, firmware 5.70, corroborated
against TCRF's named rips in `alt/`:

- **Four names, one sample.** `snd_cursor`, `snd_decide`, `snd_category_decide`
  and `snd_option` are byte-identical (MD5 `2277c8a6b5c7e0e3435c739041a7cae4`;
  the 5.70 VAGp equivalents share SHA-1 `15dd749dd3a2d8eb`). There is no
  distinct category-change or confirm sound on a PSP.
- **`snd_error` is digital silence** — all-zero samples, peak -240 dBFS. The
  5.70 `SE09_Error` is a 64-byte stub. There is no error sound to reference;
  `System_NG` was used for `ERROR` by decision.
- **`snd_cancel` is unchanged from 1.50 to 5.70** (xcorr 0.995, dominant
  partial identical to 0.1 Hz).
- `official/02. Cancel.mp3` is misnamed — it is `SE13_System_NG` (800 ms), not
  the 39 ms cancel tick.

## Decisions taken

- `sfx_cursor` covers `SCROLL`, `SELECT` **and** `SYSTEM_BROWSE`, matching
  Sony's four-way collision. A separate `sfx_systembrowse` was authored and then
  deliberately dropped. `SYSTEM_BROWSE` sounding identical to `SCROLL` is now
  intentional, not the defect it was when both were the same Sony-derived file.
- `System_NG` to `ERROR`. `System_OK` to a new `CONFIRM` slot.
- `snd_opening` to the Boot Sequence.
- App drawer **open** to `SELECT`, **close** to `BACK`. Open and filter-change
  sounding the same was accepted.

## The current set (`assets/SFX/active/`)

| file | slot | dur | peak | attack | T40 | dominant | max xcorr |
|---|---|---|---|---|---|---|---|
| `sfx_cursor.wav` | `SCROLL`, `SELECT`, `SYSTEM_BROWSE` | 38 ms | -10 dBFS | 1.07 ms | 32 ms | 7179 Hz | 0.226 |
| `sfx_back.wav` | `BACK` | 38 ms | -10 dBFS | 0.98 ms | 31 ms | 6050 Hz | 0.190 |
| `sfx_confirm.wav` | `CONFIRM` | 288 ms | -10 dBFS | 0.77 ms | 138 ms | 3360 Hz | 0.094 |
| `sfx_error.wav` | `ERROR` | 231 ms | -10 dBFS | 19.1 ms | 79 ms | 974 Hz | 0.185 |
| `sfx_opening.wav` | Boot Sequence | 3.02 s | -8 dBFS | 31 ms | 1819 ms | 440 Hz | 0.088 |
| `sfx_launch.wav` | `LAUNCH` | 4.84 s | -0.3 dBFS | 94 ms | 2937 ms | 44 Hz | 0.130 |
| `sfx_notification.wav` | `NOTIFICATION` | 1.04 s | -5.9 dBFS | 111 ms | 672 ms | 721 Hz | 0.047 |

`sfx_launch` and `sfx_notification` are the two pre-existing samples that tested
clean. The other five are authored.

## How the originals were made

Short cues are **two detuned partials over a band-limited noise bed**,
synthesised from measured parameters only — no spectral data copied from the
source. Fit to the reference's attack / T20 / T40 / centroid / 10-90% band, then
pitch-offset.

**Pitch offset is load-bearing, not cosmetic.** Matching a Sony frequency
exactly drives correlation to 0.39-0.45 no matter how independently the cue is
built — two tones at one frequency with one envelope correlate by construction.
A `sfx_back` trial at 6350 Hz (1.6% from Sony's 6455 Hz) scored 0.284, nearly
failing. **Stay at least ~3.5% clear of the source frequency in either
direction, and re-run the check after any re-pitch.** Note you cannot simply
"lower the pitch" past a Sony frequency without passing through the danger zone.

Other lessons:

- A pure tone plus a short click collapses the spectrum to a single partial. The
  noise bed is what produces the real 2-13 kHz spread.
- Randomised partial phases make a cluster ramp in over ~9 ms. Phase-align the
  main partials at t=0 for a sharp attack (`sfx_confirm`).
- `sfx_opening` was handled differently. A 4 s musical phrase's **melody** is the
  protectable part, so transposing it would still be the same tune. The arc
  (bell attack, low sustain, slow decay) was kept as generic shape; the harmony
  was rewritten — source is C major leaning on the perfect fifth, ours is D with
  quartal/sus2 voicing.

## The admission test

Nothing enters `active/` scoring **0.30 or above** against any file in `alt/`,
`official/`, `sony_firmware/`, `current/`, or `res/raw/`.

```python
import wave, numpy as np

def rd(p):
    w = wave.open(p, "rb")
    s = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    w.close()
    return s.astype(np.float64) / 32768.0

def trim(s):
    e = np.abs(s)
    i = np.where(e > max(1e-5, e.max() * 10 ** (-60 / 20)))[0]
    return s[i[0]:i[-1] + 1] if len(i) else s

def xcorr(a, b):                    # max normalised cross-correlation over all lags
    a = a - a.mean()
    b = b - b.mean()
    n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
    c = np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)
    return float(np.abs(c).max() / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
```

Decode everything to mono 44.1 kHz PCM first. Interpretation: above 0.99 same
audio, above 0.85 derived, 0.6-0.85 derived with processing, below 0.3
independent.

## Code changed this session

- `UiMediaSlot.kt` — added `SOUND_CONFIRM("sound_confirm", SOUND,
  "Confirm / Apply", UiMediaLimits.CONFIRM)`. Renamed `SOUND_SELECT`'s display
  name from "Confirm / Select" to **"Select / Open"**, because two rows both
  reading "Confirm" would be unusable. Display string only — the storage key is
  unchanged, so no migration.
- `MenuSoundPlayer.kt` — `MenuSound.CONFIRM` added to the enum, the `slot`
  mapping, and `loadDefault(CONFIRM, R.raw.sfx_confirm)`.
- `core/core-ui/src/main/res/raw/sfx_confirm.wav` — new.
- `.gitignore` — reference folders.

No settings-screen change was needed: `AudioSettingsViewModel.SOUND_SLOTS` is
`UiMediaSlot.ofKind(SOUND)`, so the customization row appears automatically.

**Not built or tested.** `MenuSound` has ~90 call sites but all go through
`play(...)`; the only exhaustive `when` is the `slot` mapping, which was
updated. Expected to compile, unverified.

## Open items

1. **Integrate the rest of `active/`.** Only `sfx_confirm.wav` has been copied
   into `res/raw/`. Still to do: `sfx_cursor` into `sfx_scroll`, `sfx_select`
   and `sfx_systembrowse`; `sfx_back` as a new `sfx_back.wav`; `sfx_error` over
   `sfx_error.wav`. This is the step that removes Sony-derived audio from the
   build.
2. **`BACK` currently plays the error sample.** `MenuSoundPlayer` carries a
   documented placeholder: `loadDefault(MenuSound.BACK, R.raw.sfx_error)`.
   `active/sfx_back.wav` is the intended replacement.
3. **Boot sound needs a delivery decision.** `BOOT_AUDIO` already exists and is
   fully wired (Display ▸ Boot Sequence picker, read by `XMBViewModel` around
   line 8059, played by `BootSequenceOverlay` via `OneShotAudioLayer`). But it is
   `AUDIO_TRACK` kind, which has **no bundled-default mechanism** —
   `bootAudioPath == null` means silence. Either the user imports
   `sfx_opening.wav` through the picker (works today, zero code), or add
   bundled-default support for `AUDIO_TRACK` slots (also benefits GameBoot).
4. **`sfx_opening` deserves another pass** — 3.02 s against the source's 3.57 s,
   and thin in the low end (10th percentile 442 Hz vs 135 Hz).
5. **Stale doc:** `docs/plans/ui-audio-boot-gameboot-plan.md:243` still says
   "Confirm / Select".
6. **Wire the `CONFIRM` call sites** — nothing plays it yet. Intended:
   confirm/yes/OK in modals, and save/add/apply in pickers (app picker, custom
   icon picker, game picker in game categories).
7. **`ERROR` and `NOTIFICATION` still have no call sites.**

## Environment notes (this machine)

Set up for the `/watch` skill and the audio work; all persistent:

- `ffmpeg` / `ffprobe`:
  `C:\Users\johnn\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.1-full_build\bin`
  (not on PATH in the Bash tool's shell)
- `yt-dlp`:
  `C:\Users\johnn\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe`
- **Avast Web Shield MITMs TLS.** Its root is in the Windows store but not in
  certifi, so tools bundling certifi fail verification.
  `%APPDATA%\yt-dlp\config` carries `--compat-options no-certifi` to use the
  system store. `pip` needs `--cert` pointed at an exported Windows root bundle.
- YouTube needs a JS runtime and a working player client; that same config has
  `--js-runtimes node` and
  `--extractor-args youtube:player_client=mweb,android_vr`.
- ffmpeg 9 removed `-vsync`. The `/watch` skill's `scripts/frames.py` was patched
  to `-fps_mode` (backup at `frames.py.bak`); it reverts on plugin update.
- **tcrf.net is unreachable from tooling** — 403 to WebFetch, Cloudflare
  challenge in the in-app browser. Paste page content in, or connect the Claude
  in Chrome extension.
