# Menu SFX authoring reference

Design targets for PlayFieldPortal's own menu sounds. Every number here was
measured from Sony's PSP 5.70 firmware cues; **no Sony audio is used, shipped,
or tracked.** Measurements are facts about a recording, not the recording --
this file is safe to commit. The WAVs under `sony_firmware/` are gitignored and
exist only for local A/B listening.

## Status of what we ship today

**Six of the seven shipped samples are original; the seventh is a deliberate
exception.** The old Sony-derived set was replaced wholesale by the authored
set in `assets/SFX/active/` (copied verbatim into `core/core-ui/src/main/res/raw/`),
and every sample except one scores below the 0.30 admission bar. The exception:
**`sfx_opening` is Sony's `snd_opening` (5.70 rip) shipped as the bundled Boot
Sound by explicit project-owner decision on 2026-09-07** (xcorr 1.000 — it is
the reference itself; see `assets/SFX/active/README.md`). The current `res/raw`
roster is pinned by `UiMediaDefaults.bundledDefaultRes` and tested in
`UiMediaDefaultsTest`:

| slot | `res/raw` file | max xcorr vs references |
|---|---|---|
| `sound_scroll` (Navigation) | `sfx_cursor.wav` | 0.226 |
| `sound_back` | `sfx_back.wav` | 0.190 |
| `sound_confirm` | `sfx_confirm.wav` | 0.094 |
| `sound_error` | `sfx_error.wav` | 0.185 |
| `sound_launch` | `sfx_launch.wav` | 0.130 |
| `sound_notification` | `sfx_notification.wav` | 0.047 |
| `boot_audio` (Boot Sound) | `sfx_opening.mp3` | **1.000 — Sony `snd_opening`, by owner decision** |

This overrides the standing rule that firmware audio must never reach a build
or a commit, for this one slot only. If the decision is revisited, the authored
`sfx_opening.wav` variant remains in git history.

### Historical: what used to ship

The six pre-replacement samples were compared against the firmware by
peak-normalised cross-correlation over all lags (1.000 = same audio).
Silence-trimming did not change any score, so these were real content matches,
not alignment artifacts. They are tracked and remain in git history since
`507ac29`; replacing the files did not remove them from history.

| old `res/raw` file | closest Sony cue | xcorr | outcome |
|---|---|---|---|
| `sfx_error.wav` | `SE13_System_NG` | **0.988** | replaced |
| `sfx_scroll.wav` | `SE4_Cansel` | **0.878** | deleted |
| `sfx_systembrowse.wav` | `SE4_Cansel` | **0.878** | deleted |
| `sfx_select.wav` | `SE2_Cursor` | **0.747** | deleted |
| `sfx_launch.wav` | best 0.154 | -- | kept (clean) |
| `sfx_notification.wav` | best 0.095 | -- | kept (clean), re-labelled |

`sfx_scroll.wav` and `sfx_systembrowse.wav` were byte-identical to each other,
so two slots played one sound. **The mapping was also shifted by one:**
`sfx_scroll` / `sfx_systembrowse` matched Sony's *cancel* cue (0.880), and
`sfx_select` matched Sony's *cursor* cue (0.747) -- moving the cursor played a
cancel sound and selecting played a cursor sound. That UX bug is fixed by the
current set (`sfx_cursor` covers all three navigation events, matching Sony's
own four-way sample collision).

## Sony's slots, and how ours map to them

| Sony cue | fires on | our `MenuSound` |
|---|---|---|
| `SE2_Cursor` / `snd_cursor` | cursor moves within a list | `SCROLL` |
| `SE5_Category_OK` / `snd_category_decide` | deciding on a category | `SYSTEM_BROWSE` |
| `SE3_Normal_OK` / `snd_decide` | confirming an item | `SELECT` |
| `SE4_Cansel` | backing out | `BACK` |
| `SE8_Option` | options / triangle menu | -- (no slot yet) |
| `SE13_System_NG` | refused action | `ERROR` |
| `SE12_System_OK` | confirming a modal, adding in a picker, save/apply in a menu | `CONFIRM` |

`snd_category_decide` -> `SYSTEM_BROWSE` and `snd_decide` -> `SELECT`
supersedes an earlier reading in which `Category_OK` was mapped to `SELECT`.
"Deciding on a category" is the column/section change, which is what
`SYSTEM_BROWSE` fires on; a plain "decide" is `SELECT`.

This does not give either slot a usable reference, because the two samples are
identical (see below) and identical in turn to `SE2_Cursor`. Both slots are
therefore designed free and deliberately separated from each other.

`SE4_Cansel` is confirmed independently: the same sound ships in PSP **1.50**
as `system_plugin.rco / snd_cancel` (xcorr 0.995 against the 5.70 slot,
dominant partial identical to 0.1 Hz). Sony's own 1.50 resource name settles
that this cue is cancel/back, and that it went unchanged across seven firmware
generations. It also confirms `official/02. Cancel.mp3` is misnamed -- that
file is the 800 ms `SE13_System_NG`, not this 39 ms tick.

Note that `SE2_Cursor`, `SE3_Normal_OK`, `SE5_Category_OK` and `SE8_Option` are
one identical sample (SHA-1 `15dd749dd3a2d8eb`) in 5.70. We do not have to
inherit that -- giving `SELECT` its own colour is an improvement, not a
deviation.

### Prefer 1.50 as the reference, not 5.70

Sounds carry two names: the filename the RCO assigns (`snd_cancel`) and the
internal name in the VAGp stream header (`SE4_Cansel.aif`). Both refer to one
sound; this document uses the VAGp names because that is what our extraction
reads.

TCRF's PSP documentation states that early firmware had considerably more
variety in these cues, and that by later firmware most had become identical,
some were left empty, and some were repurposed onto different files. Our own
measurements of 5.70 match that description exactly -- the four-way sample
collision above, and `SE09_Error` being a 64-byte silent stub.

**This means 5.70 is the degraded copy.** For any slot where 5.70 shows a
collision (`SCROLL`, `SELECT`, and the unused `Normal_OK` / `Option`), the 1.50
rip is the better reference, because those cues were probably still distinct
there. The 1.50 `snd_cancel` we have is unchanged from 5.70, so `BACK` is
unaffected either way.

Not yet verified: we have only the one 1.50 file (`snd_cancel`). Sourcing the
rest of the 1.50 set would confirm whether `Cursor` and `Category_OK` were
originally different sounds -- the open question behind giving `SELECT` its own
character.

**Reported, unmeasured:** 1.50's `snd_decide` and `snd_category_decide` appear
to be the same sound. If that holds, the collision is not a late-firmware
regression at all -- `SELECT` (`Category_OK`) and plain confirm were one cue
from the start, and 1.50 offers no distinct reference for `SELECT` either. That
would put `SELECT` in the same position as `SYSTEM_BROWSE`: designed free
rather than matched. Drop the 1.50 files in and this can be settled by
measurement.

## Targets

`attack` is 10%->90% of peak. `T20`/`T40` are decay to -20/-40 dB below peak,
measured from the peak. `band` is the 10th-90th percentile of spectral energy.

| Slot | ref | dur | peak | attack | T20 | T40 | dominant | centroid | band |
|---|---|---|---|---|---|---|---|---|---|
| `SCROLL` | `SE2_Cursor` | 41 ms | -4 dBFS | 1.1 ms | 21 ms | 32 ms | 6838 Hz | 8316 Hz | 2.1-15.5 kHz |
| `SELECT` | `SE5_Category_OK` | 41 ms | -4 dBFS | 1.1 ms | 21 ms | 32 ms | 6838 Hz | 8316 Hz | 2.1-15.5 kHz |
| `BACK` | `SE4_Cansel` | 41 ms | -13 dBFS | 1.1 ms | 22 ms | 34 ms | 6455 Hz | 7672 Hz | 1.9-14.4 kHz |
| `BACK` | *1.50 `snd_cancel`* | 39 ms | -16 dBFS | 1.1 ms | 22 ms | 34 ms | 6455 Hz | 7336 Hz | 1.9-13.1 kHz |
| `SYSTEM_BROWSE` | *ours* | 41 ms | -13 dBFS | ~1 ms | ~21 ms | ~32 ms | ~6450 Hz | -- | high, narrow |
| `CONFIRM` | `SE12_System_OK` | 303 ms | -28 dBFS | 1.3 ms | 57 ms | 107 ms | 3136 Hz | 4902 Hz | 3.1-12.0 kHz |
| `ERROR` | `SE13_System_NG` | 802 ms | -10 dBFS | 21 ms | 44 ms | 79 ms | 1039 Hz | 1248 Hz | 1.02-1.08 kHz |
| `LAUNCH` | *ours, clean* | 2613 ms | -2 dBFS | 49 ms | 188 ms | 1238 ms | 88 Hz | 1481 Hz | 0.09-4.4 kHz |
| `NOTIFICATION` | *ours, clean* | 1000 ms | -6 dBFS | 102 ms | 276 ms | 621 ms | 785 Hz | 2718 Hz | 0.5-7.3 kHz |

`SCROLL` and `SELECT` share a row because Sony shares the sample. Ours should
not: keep the envelope, move `SELECT` up in pitch or level so entering an item
reads as a step forward rather than another cursor move.

**`SELECT` vs `CONFIRM`.** These are different actions and should sound
different. `SELECT` is navigation -- you moved into an item and can back out of
it. `CONFIRM` is commitment -- a modal accepted, a picker entry added, a
setting saved. Sony marks that difference structurally, not just tonally:
`CONFIRM` is 7x longer (303 ms vs 41 ms) and drops an octave and a half
(3136 Hz vs 6838 Hz). It is the only short-ish cue that is allowed to ring.

**Watch the level on `CONFIRM`.** -28 dBFS is Sony's own mastering, 24 dB below
their cursor tick. Reproduced literally it will be inaudible next to our other
cues. Match the *character* -- the 303 ms length, the 3.1 kHz centre, the
1.3 ms attack -- but normalise to roughly -8 dBFS so it sits with the rest of
the set.

`LAUNCH` and `NOTIFICATION` are measured from our own clean samples, listed so
new cues stay coherent with the two we keep.

## What actually makes these read as "XMB"

1. **Near-instant attack.** ~1 ms on every short cue. Anything slower reads as
   a soft chime, not a UI tick.
2. **Very short.** 41 ms total; inaudible by 32 ms. They never overlap fast
   navigation, which is why held-direction scrolling does not turn to mush.
3. **Bright and narrow.** Energy centred at 7-8 kHz with essentially nothing
   below 800 Hz. This is the single biggest factor -- a tick with low-end
   sounds like a click, not the XMB.
4. **A single resonant partial** over a broadband transient. One clear peak,
   everything else 15+ dB down.
5. **Failure inverts every rule.** `ERROR` is long (800 ms), low (1039 Hz),
   slow to attack (21 ms), and almost a pure tone (10-90% band is only 58 Hz
   wide). Contrast, not volume, is what marks it as a refusal.

## Slot separation

Distinguish the short cues by **pitch and level**, not by length -- they should
sound like one family differing in one dimension at a time. The `Cursor` ->
`Cansel` delta is the reference for how much separation is enough: **~6% in
pitch and ~9 dB in level** is clearly distinct without sounding like a
different instrument.

Apply that same delta to split `SCROLL` from `SYSTEM_BROWSE`, and to lift
`SELECT` clear of `SCROLL`. `sfx_scroll` and `sfx_systembrowse` being identical
today is the bug this table fixes.

## Authoring recipe

A cue matching the short-tick spec, from scratch:

1. 1-2 ms of shaped noise as the transient.
2. One decaying sine at the target dominant frequency, T60 ~= 2.4x the T40
   above (e.g. 77 ms for `SCROLL`).
3. Optional partials 20-25 dB down for colour.
4. High-pass at ~700 Hz; the low end contributes nothing but mud.
5. 0.4 ms fade-in, 4 ms fade-out, normalise to the target peak.

Verify with the same measurement pass; then cross-correlate the result against
`sony_firmware/` and confirm it scores **below 0.3**. Above that, it is a
derivative work, not an original.

## Reproducing the check

The comparison decodes everything to mono 44.1 kHz PCM and takes the max
normalised cross-correlation over all lags, plus mean absolute distance across
1/3-octave bands. Interpretation: >0.99 same audio, >0.85 derived, 0.6-0.85
likely derived with processing, <0.3 independent.
