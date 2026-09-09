# active/ -- decided sounds

**Integrated.** This is the shipped set: every file here is copied **verbatim**
(byte-for-byte, names included) into `core/core-ui/src/main/res/raw/`, and that
is the last integration step — the `res/raw` folder holds exactly these seven
files and nothing else. `MenuSoundPlayer` / `UiMediaDefaults.bundledDefaultRes`
are the only files that map slot -> sample.

**Provenance changed on 2026-09-08.** Four slots (`sfx_cursor`, `sfx_back`,
`sfx_launch`, `sfx_notification`) are now built from royalty-free Pixabay audio,
edited for the launcher, replacing the authored samples that held those slots
earlier the same day. `sfx_confirm` and `sfx_error` are still the authored cues
and `sfx_opening` is unchanged. Contributor links live in
`../resources/original/credits.txt` and render in-app under Settings ▸ Credits ▸
Menu Sounds. **No Sony audio is used, shipped, or tracked** — that rule is
unchanged and still holds for the whole set. Reference material lives in
`../alt/`, `../official/` and `../sony_firmware/`, all gitignored. Targets are
in `../REFERENCE.md`.

Admission test: cross-correlate against every reference file and score **below
0.30**. One file no longer passes — see the flag under `sfx_notification`.

## The set

Measured from the shipped files on 2026-09-08. Duration, attack and T40 are
measured over the -60 dBFS-trimmed region, so they read shorter than the
container length wherever a file has a quiet tail (`sfx_notification` is a
1.056 s file with a 442 ms audible body; `sfx_launch` is a 5.000 s file with a
4.615 s body).

| file | slot | dur | peak | attack | T40 | dominant | centroid | max xcorr |
|---|---|---|---|---|---|---|---|---|
| `sfx_cursor.mp3` | `sound_scroll` (Navigation: `SCROLL`, `SELECT`, `SYSTEM_BROWSE`) | 147 ms | -11.6 dBFS | 46.1 ms | 55 ms | 2440 Hz | 1924 Hz | 0.071 |
| `sfx_back.mp3` | `sound_back` (`BACK`) | 156 ms | -11.6 dBFS | 49.3 ms | 58 ms | 2303 Hz | 1816 Hz | 0.037 |
| `sfx_confirm.wav` | `sound_confirm` (`CONFIRM`) | 288 ms | -10.0 dBFS | 0.88 ms | 138 ms | 3307 Hz | 4664 Hz | 0.094 |
| `sfx_error.wav` | `sound_error` (`ERROR`) | 231 ms | -10.0 dBFS | 24.5 ms | 79 ms | 976 Hz | 1064 Hz | 0.185 |
| `sfx_launch.wav` | `sound_launch` (`LAUNCH`) | 4.62 s | -1.1 dBFS | 2535 ms | 1995 ms | 65 Hz | 1133 Hz | 0.085 |
| `sfx_notification.mp3` | `sound_notification` (`NOTIFICATION`) | 442 ms | +0.5 dBFS | 16.8 ms | 48 ms | 2034 Hz | 2344 Hz | **0.396** |
| `sfx_opening.mp3` | `boot_audio` (Boot Sound) | 2.98 s | -9.6 dBFS | -- | -- | 261 Hz | 1521 Hz | 0.202 |

The **max xcorr** column is the admission score — peak-normalised
cross-correlation against the closest reference file in `alt/`, `official/`,
`sony_firmware/` or `current/` (the old set).

`res/raw` resolves by resource NAME, not extension, so `R.raw.sfx_cursor`
resolves the `.mp3` exactly as it resolved the `.wav`. No Kotlin change was
needed for the format switch, and `SoundPool.load` decodes mp3 as readily as
wav. The old `.wav` files had to be deleted in the same pass — two files
sharing one resource name is a duplicate-resource build failure, not a
fallback. Note that mp3 carries encoder delay/padding that wav does not; if the
short cues ever feel late, that is the first thing to measure.

## Levels are not matched across the set

The set spans 12 dB of peak level, which is audible when cues fire back to back:

| file | peak |
|---|---|
| `sfx_notification.mp3` | +0.5 dBFS |
| `sfx_launch.wav` | -1.1 dBFS |
| `sfx_confirm.wav` / `sfx_error.wav` | -10.0 dBFS |
| `sfx_cursor.mp3` / `sfx_back.mp3` | -11.6 dBFS |

Navigation and Back are the two most frequently fired cues and they are the two
quietest, ~11 dB under Launch and Notification. That follows from taking the
quieted cursor/back renditions; it is not a measurement error. If the set gets
levelled, `sfx_cursor` and `sfx_back` are the two to raise.

`sfx_notification` peaks at +0.5 dBFS in the mono downmix — L and R are each
under full scale but their sum is not, so a mono-summing output can clip it.

## sfx_cursor and sfx_back

Both derive from one Pixabay source. `sfx_back` is that source **pitched down
exactly one semitone** (resampled x0.943874), which is precisely the
relationship Sony's own `snd_cursor` -> `snd_cancel` pair has: every partial of
`snd_cancel` scales off `snd_cursor` by 0.9437-0.9439, and resampling
`snd_cursor` by that factor cross-correlates at 0.915 against `snd_cancel`
(0.275 unshifted). The relationship was copied; none of the audio was.

Sony's pair also drops 8.7 dB from cursor to cancel. That half was **not**
carried — both files sit at the same level, so Back is not quieter than
Navigation. Both were then trimmed 8.7 dB together, which is where the level
gap in the table above comes from.

One cue covers `SCROLL`, `SELECT` and `SYSTEM_BROWSE`, matching Sony's own
behaviour: `snd_category_decide` is one of four identical samples, so a separate
category-change sound is not something the PSP ever had.

Consequence: `SYSTEM_BROWSE` is audibly identical to `SCROLL`. That is
deliberate, not the bug it was when `res/raw/sfx_scroll.wav` and
`res/raw/sfx_systembrowse.wav` happened to be the same Sony-derived file.

## sfx_launch

Cut from a Pixabay intro-logo cue (SoundReality). The source runs 14.160 s and
fades to inaudibility rather than stopping; it was cut to 5.000 s with a 150 ms
fade ending at 4.85-5.00 s, which lands in a natural decay valley (RMS drops
from -19 to -30 dBFS there) just before a new bass phrase starts at 5.35 s.
Converted 48 kHz mp3 -> 44.1 kHz 16-bit stereo WAV to match the rest of
`res/raw`.

This replaces the 2.613 s sample that previously held the slot. The old README
row for this file claimed 4.84 s and T40 2937 ms; those numbers did not describe
the file that was actually shipping (2.613 s), and are gone.

## sfx_notification -- ABOVE THE ADMISSION BAR

**0.396 against Sony's `SE13_System_NG`**, where the bar is 0.30. The score is
stable under silence-trimming (0.396 either way) and repeats against all three
copies of that cue in the reference folders (`sony_firmware/SE13_System_NG`,
`alt/snd_system_ng`, and `official/02. Cancel.mp3`, that last one being the
misnamed duplicate). It also scores 0.373 against our own `sfx_error`, which is
itself modelled on `System_NG`.

This is **not** a rip: the file is Pixabay stock with no Sony lineage. What the
number says is that a short two-tone buzz correlates with another short two-tone
buzz — the same construction-not-content effect the pitch-offset rule below
exists to avoid. But the documented rule is a number, and this file exceeds it.

**Open decision.** It ships today because it was chosen for the slot. Either the
bar gets an explicit documented exception for this file, or the cue gets
re-pitched clear of `System_NG`'s 974-1039 Hz region and re-scored. Do not let
this sit undocumented — that is how the old Sony-derived set survived as long as
it did.

## Pitch layout

Applies to the two remaining authored cues. Sony frequencies are given for
comparison:

| cue | ours | Sony equivalent | offset |
|---|---|---|---|
| `sfx_confirm` | 3307 Hz | 3136 Hz (`snd_system_ok`) | +5.5% |
| `sfx_error` | 976 Hz | 1039 Hz (`snd_system_ng`) | -6.1% |

**Offset is not optional** for anything authored. Matching a Sony frequency
exactly drives correlation to 0.39-0.45 regardless of how independently the cue
is built -- two tones at the same frequency with the same envelope correlate by
construction. An early `sfx_back` trial at 6350 Hz (1.6% off the Sony cancel)
scored 0.284, nearly failing. Stay at least ~3.5% clear in either direction and
re-run the check after any re-pitch. The Pixabay-sourced cues land far from
Sony's frequencies on their own, with `sfx_notification` the exception noted
above.

## sfx_confirm

Authored from `snd_system_ok`: a two-strike chime, second strike at 54 ms and
20% level. Main partials are phase-aligned at t=0 so the attack stays sharp
(0.88 ms) -- randomised phases made the cluster ramp in over ~9 ms.

Fires on: confirm/yes/okay in modals, and save/add/apply/confirm in pickers
(app picker, custom icon picker, game picker in game categories).

Levelled to -10 dBFS, not Sony's -31 dBFS, which would be inaudible next to the
rest of the set.

## sfx_error

Authored from `snd_system_ng`: a pulsing buzz, not a single decay -- three
partials beating ~45 Hz apart, slow attack. Sony's own `snd_error` is digital
silence (peak -240 dBFS, all-zero samples), so `ERROR` had no reference and
`System_NG` was used instead per decision.

## sfx_opening

Unchanged in this pass. The owner-designated 256 kbps mp3 rendition of the
authored cue: 2.98 s audible body, peak -9.6 dBFS, dominant 261 Hz, **xcorr
0.202 against the Sony reference** — under the admission bar, verified directly
against the rip (extracted from git history, MD5-matched to the documented Sony
`snd_opening`) at integration time.

An earlier authored WAV variant (the rewritten-D-quartal cue: decoded 3.80 s,
peak -8 dBFS, dominant 440 Hz, xcorr 0.088 — the set's best score) and the
owner-supplied `pfp_launcher_boot_original.wav` (2.80 s) were kept in
`../resources/original/`. The mp3 is a **different rendition, not a re-encode
of that WAV** (lagged xcorr between them is 0.079; durations and dominant
frequencies differ) — do not treat their numbers as interchangeable.

**Shipment history.** The Sony `snd_opening` rip (firmware 5.70, xcorr 1.000)
shipped as the bundled Boot Sound for one day by explicit owner decision
(2026-09-07), which suspended the admission rule for this one slot. Reversed on
2026-09-08: the rip was removed and this mp3 became the bundled Boot Sound.

It ships as the bundled default for Boot Sound (`boot_audio`, an `AUDIO_TRACK`
slot): `UiMediaSlot.bundledDefaultUri()` resolves it as an `android.resource://`
URI that ExoPlayer opens when the user has no custom boot video. There is no
`MenuSound` for it -- it plays through the boot overlay, not SoundPool.

## Credits

`../resources/original/credits.txt` holds the contributor links. The four
Pixabay creators are listed there and rendered in-app under Settings ▸ Credits ▸
Menu Sounds:

- pixabay.com/users/lucadialessandro-25927643
- pixabay.com/users/soundreality-31074404
- pixabay.com/users/musheran-40634446
- pixabay.com/users/universfield-28281460

**Per-file attribution is not recorded.** Only `sfx_launch` is traceable to a
specific creator (SoundReality, from the source filename
`soundreality-intro-logo-best-in-town-409631.mp3`); the other sources carry no
artist metadata. The in-app credit therefore names the four creators as a group
rather than mapping one to each file. If the mapping matters, it has to come
from whoever downloaded them — it cannot be recovered from the files.

The Pixabay Content License does not require attribution; it is given anyway.

## Still open

- `sfx_notification` is above the 0.30 admission bar at 0.396 (see above).
  Needs either a documented exception or a re-pitch.
- Navigation and Back sit ~11 dB under Launch and Notification (see Levels).
- Per-file Pixabay attribution is unknown for three of the four creators.
- `sfx_ambience_future.mp3` sits in `../resources/original/` and was
  deliberately excluded from this set — it has no slot.
- The drawer mappings are live: **open** -> `SELECT`, **close** -> `BACK`,
  `SYSTEM_BROWSE` on filter changes -- all three resolve to one Navigation
  sample by design.

## Integration -- final `res/raw` mapping

| `res/raw` file | source |
|---|---|
| `sfx_cursor.mp3` | this folder, verbatim |
| `sfx_back.mp3` | this folder, verbatim |
| `sfx_confirm.wav` | this folder, verbatim |
| `sfx_error.wav` | this folder, verbatim |
| `sfx_launch.wav` | this folder, verbatim |
| `sfx_notification.mp3` | this folder, verbatim |
| `sfx_opening.mp3` | this folder, verbatim |

Removed from `res/raw` in this pass, superseded by the `.mp3` of the same
resource name: `sfx_cursor.wav`, `sfx_back.wav`, `sfx_notification.wav`.
Removed in the earlier Sony-replacement pass: `sfx_scroll.wav`,
`sfx_select.wav`, `sfx_systembrowse.wav`, `sfx_quicksysselect.wav`.
`sfx_favorite.wav` was renamed to `sfx_notification.wav` (same clean sample,
correct label) before that slot was replaced here.
