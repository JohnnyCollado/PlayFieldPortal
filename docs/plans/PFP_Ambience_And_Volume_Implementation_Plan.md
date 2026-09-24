# Play Field Portal — Ambience & Volume: Implementation Plan

Two coupled features. **Ambience** is an assignable looping background track for the launcher —
Wii-menu BGM. **Volume** replaces the single Menu Sounds mute with a master level plus one
sub-level per system sound, including the Boot Sequence, GameBoot and Ambience itself.

**Status: implemented.** T1–T8 are in the tree. §5.5's format decision shipped as written: the
ambience row accepts every container `AUDIO_MIME` allows and names the MP3 seam in its helper text.

> **Shape.** Ambience is *one clip*, not a playlist — the same bargain Boot Sequence and GameBoot
> already strike: the user replaces the whole thing with a file of their own, and that file brings
> its own audio. Volume governs **launcher chrome only**. The music player and the video player
> play the user's own media at system volume and are never scaled by master, though both still
> *suppress* ambience — those are two different questions and this plan answers them differently.

---

## 1. Why these ship together

Ambience is the first **continuous** sound the launcher has ever produced. Every existing sound is
a one-shot: a menu tick, a boot chime, a five-second GameBoot sting. One-shots need an on/off
switch, which is what `sound_menu_enabled` is. A background loop needs a *level* — the first sound
in this app you would plausibly want quieter rather than absent.

Building volume first gives ambience somewhere to plug in. Building ambience first means
retrofitting a gain path through a player that already exists. The Execution Task Index below
sequences accordingly.

---

## 2. What exists today

### 2.1 Three audio owners, none aware of the others

| Owner | Backing | Lifetime | Gain today |
|---|---|---|---|
| [`MenuSoundPlayer`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/sound/MenuSoundPlayer.kt) | `SoundPool` | app singleton | `pool.play(id, 1f, 1f, …)` — hardcoded |
| [`UiMediaAudioPlayer`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/media/UiMediaAudioPlayer.kt) | ExoPlayer, one-shot | app singleton | never set (implicit `1f`) |
| [`MusicPlayerController`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/music/MusicPlayerController.kt) | `MediaPlayer`, queue | app singleton | never set |

They coexist only because none of them is continuous. That property is what this plan removes.

**There is no volume concept anywhere in the app.** A repo-wide search finds exactly three gain
expressions: the hardcoded `1f, 1f` above, `volume = 0f` in
[`MotionWallpaperBackground`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/motion/MotionWallpaperBackground.kt),
and the ICON1 overlay's matching mute. This is new infrastructure, not a refactor.

### 2.2 The mute pref is defined in three places

`"sound_menu_enabled"` is declared independently as:

- `KEY_MENU_SOUNDS_ENABLED` — [UiMediaStore.kt:314](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/UiMediaStore.kt#L314)
- `KEY_MENU_SOUND` — [AudioSettingsViewModel.kt:32](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/AudioSettingsViewModel.kt#L32)
- a bare string literal — [BackupManager.kt:679](../../feature/feature-backup/src/main/kotlin/com/playfieldportal/feature/backup/BackupManager.kt#L679)

Three declarations, one pref, no compiler relationship between them. Replacing mute with nine
float keys would multiply that by nine. **The keys get declared once** (T1) and every consumer
reads them from there.

### 2.3 Discord already writes the system stream

[`DiscordVoiceController`](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/discord/DiscordVoiceController.kt#L189)
captures `STREAM_MUSIC` on join and restores it on leave. If master volume also wrote the system
stream, the two would fight and Discord's baseline capture would record whatever ambience last
set. **Master is an in-app multiplier and never touches `AudioManager` stream volume.**

### 2.4 The slider row carries two accents

[`SettingsSliderRow`](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/SettingsSliderRow.kt)
paints its focus background with `menuCursorFill()` — live theme, reading
`LocalPFPColors.current.accentColor`. It paints the thumb, active track and value text with
`SettingsAccent`, which is [`PfpPalette.Accent`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/theme/PFPTheme.kt#L53),
a fixed `0xFF4A90D9`.

`LocalPFPColors.accentColor` defaults to **white** and resolves to white for every preset XMB
scheme (noted at [StorefrontColors.kt:111](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/theme/StorefrontColors.kt#L111)).
So a focused slider today shows a white-tinted cursor with a stock Material blue thumb sitting in
it. That is the "weird accent." It is invisible on a screen with one slider and becomes the
dominant visual once this screen has nine — hence its inclusion here rather than as separate work.

### 2.5 Seams this plan reuses rather than invents

| Need | Existing seam |
|---|---|
| App foregrounded / backgrounded | `MainActivity.onResume` / `onStop` → [`onHostResumed` / `onHostStopped`](../../app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt#L155) |
| Boot sequence finished | `XMBUiState.showBootSequence` clearing |
| Looping media player | `MotionWallpaperBackground`'s `REPEAT_MODE_ALL` + release-never-pause discipline |
| Slot storage, import gate, prune | [`UiMediaStore`](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/UiMediaStore.kt) + `UiMediaSlot` + `UiMediaLimits` |
| Interface-layer flow contract | [`UiMediaPaths`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/media/UiMediaPaths.kt) (core-ui interface, core-data impl) |
| Settings route registration | `SettingsNavHost` — `"settings_audio"` exists |

---

## 3. Decisions already made

Locked in conversation; recorded so a helper does not relitigate them.

| Question | Decision |
|---|---|
| Ambience source | **One assignable looping clip**, a `UiMediaSlot` — not a library playlist |
| Music player starts a track | **Ambience stops**, resumes when the queue ends |
| Other apps' audio | **Full audio-focus handling** — request on start, honor every loss type |
| Sub-volume granularity | **Per-sound, matching the roster** — 8 channels |
| Music player volume | **Excluded** — user's own media, system volume |
| Video player volume | **Excluded** — same reasoning |
| Mute toggle | **Retired.** Master at 0 is the only mute |
| Ambience audio formats | **Accept everything** `AUDIO_MIME` allows; helper text names the MP3 seam |

### 3.1 The consequence nobody asked for

`UiMediaKind.AUDIO_TRACK` was emptied in the Launch Sound removal, with a KDoc stating no member
was expected back. Ambience is exactly that member — an assignable audio file that is not a
SoundPool sample. **That comment is now wrong and must be rewritten, not worked around.**

Equally, Interface ▸ Sound currently pins the invariant *"every row on this screen is a menu sound
and every menu sound is a row"*, enforced by `AudioSettingsViewModelTest`. This plan adds volume
rows for Boot Sequence, GameBoot and Ambience to that screen. The invariant survives only if it is
restated as being about the **assignment list** specifically — the levels list is a different list
with different membership. T7 restates it; do not silently delete the test.

---

## 4. The volume model

### 4.1 Channels

Eight, mapping to the **roster** rather than to `MenuSound` events — Navigation is one channel
covering `SCROLL`, `SELECT` and `SYSTEM_BROWSE`, exactly as `SOUND_SCROLL` is one slot covering
those three events.

```
AudioChannel:  NAVIGATION  BACK  CONFIRM  ERROR  NOTIFICATION  BOOT  GAMEBOOT  AMBIENCE
```

`MenuSound.channel` mirrors the existing `MenuSound.slot` accessor. The three presentation
channels have no `MenuSound` and are addressed directly by their players.

### 4.2 Storage

`volume_master` and `volume_<channel>`, all `floatPreferencesKey`, all defaulting to `1.0`. Nine
keys, declared once (§2.2), added to `BackupManager.BACKED_UP_FLOAT_KEYS` and asserted in
`BackupKeyCoverageTest` — that test exists precisely to catch a key that silently fails to restore.

`sound_menu_enabled` is **removed** from all three declaration sites and from the backup list.

### 4.3 Taper

A linear slider mapped straight to amplitude sounds wrong: half travel reads as roughly
three-quarters as loud. Apply a square-law taper at the point of resolution:

```
gain(channel) = (master_pct × channel_pct)²
```

Monotonic, `0 → 0`, `1 → 1`, no special cases, and cheap enough to evaluate per `play()`. The
percentage is what the user sees and what is stored; the square is an implementation detail of the
resolver and must live in exactly one function so the eight call sites cannot drift.

### 4.4 Interface

`AudioLevels` in core-ui, implemented in core-data — the same split `UiMediaPaths` /
`UiMediaStore` already uses, so the Hilt binding pattern is copy-paste.

```
interface AudioLevels {
    fun gainFor(channel: AudioChannel): Flow<Float>   // master × channel, tapered
    val masterPercent: Flow<Float>
    fun percentFor(channel: AudioChannel): Flow<Float>
}
```

### 4.5 Where gain is applied

| Player | Mechanism | Live update needed? |
|---|---|---|
| `MenuSoundPlayer` | `pool.play(id, g, g, …)` from a `@Volatile` snapshot | **No** — one-shot; a collector keeps the snapshot fresh exactly as `enabled` works today |
| `UiMediaAudioPlayer` | `player.volume` at `play()`, plus a collector while playing | **Yes** — a Boot/GameBoot preview must respond mid-clip |
| `AmbienceController` | collector for the player's whole lifetime | **Yes** — this is the one that makes or breaks the feature |

**A dragged slider must be audible immediately.** If a player snapshots its level at start, the
slider does nothing until the next playback and the screen feels broken. This is the single
requirement most likely to be missed; T3's stop condition tests it directly.

---

## 5. Ambience

### 5.1 Storage

New slot `AMBIENCE_AUDIO`, key `"ambience_audio"`, kind `AUDIO_TRACK`, with a new
`UiMediaLimits.AMBIENCE` spec. **No assignment means ambience is off** — the assignment is the
switch, so there is no separate enable flag to drift out of sync with it, and Use Default turns
the feature off as a natural consequence rather than a special case.

Suggested spec: recommended 30 s – 3 min, hard cap 5 min, `AUDIO_STAGE_MAX_BYTES`. The hard cap
exists to bound the staging copy, not to express taste.

### 5.2 The three gates

Ambience sounds only when **all** hold:

1. **Assigned** — a file exists for the slot, and resolved gain is above zero.
2. **Foreground** — driven by `MainActivity.onStop` / `onResume`. **Release the player, do not
   pause it.** A paused ExoPlayer still holds a codec, a surface and buffers; the wallpaper made
   this decision already and ambience should not diverge from it.
3. **Not suppressed** — see below.

Plus a start condition: ambience waits for `showBootSequence` to clear, so the boot chime finishes
first. Chime, then menu music — the Wii order.

### 5.3 Suppression and the dependency direction

`MusicPlayerController` and `VideoPlayerScreen` both live in **feature-xmb**. An ambience player
naturally lives in **core-ui**. core-ui cannot see feature-xmb, so ambience cannot observe them.

The direction that compiles is the inverse: `AmbienceController` exposes a suppression handle, and
the music and video players **push** to it — feature-xmb → core-ui, the way the dependency already
runs. Resist an event bus; there are two producers and one consumer, and a bus here buys
indirection rather than flexibility.

**Audio focus does not solve this.** Focus is granted per-application, so your own music player
requesting focus will not cause your own ambience to yield — same UID. Suppression must be
explicit even with §5.4 fully implemented.

Note the asymmetry deliberately: the video player is **excluded from volume** (it plays the user's
media) but **included in suppression** (its audio would collide). Those are separate questions and
a helper must not "tidy" them into one rule.

### 5.4 Audio focus

`minSdk = 29`, so `AudioFocusRequest` is available unconditionally — no legacy
`requestAudioFocus(listener, …)` path.

| Event | Response |
|---|---|
| Start playing | `requestAudioFocus(GAIN)`; do not start if refused |
| `AUDIOFOCUS_LOSS` | Stop and abandon — another app owns audio now |
| `AUDIOFOCUS_LOSS_TRANSIENT` | Pause, keep the request; resume on `GAIN` |
| `..._LOSS_TRANSIENT_CAN_DUCK` | Lower gain, keep playing |
| `AUDIOFOCUS_GAIN` | Restore gain / resume |
| Stop for any reason | Abandon the request |

This is the difference between ambience and a stuck noise that talks over Spotify forever.

### 5.5 Gapless looping — DECIDED

`REPEAT_MODE_ALL` on an MP3 clicks at the seam: MP3 carries encoder delay and padding, and a
launcher loop repeats every couple of minutes indefinitely, so the artefact is heard constantly.
OGG/Opus loops clean. `UiMediaLimits.AUDIO_MIME` already accepts `audio/ogg`.

**Decision: accept the full `AUDIO_MIME` set; name the trade-off in helper text.** Rejecting a
user's MP3 outright is a worse first experience than a faint seam, and the helper text turns the
artefact into an informed choice rather than a mystery.

`AMBIENCE` therefore takes `AUDIO_MIME` verbatim — **no ambience-specific MIME subset**, so the
import gate stays one rule for every audio slot. The Ambience assignment row's sublabel carries
the note; §6 shows the wording:

> Loops while you browse. OGG loops seamlessly; MP3 may click.

A helper who "improves" this by filtering the picker to OGG is reverting a decision, not fixing
an oversight.

---

## 6. The Sound screen after the update

Three sections on the existing `"settings_audio"` route. See the rendered mockup accompanying this
plan.

```
SOUND
├─ MASTER
│    Master Volume                              80%   ────────●──
├─ LEVELS
│    Navigation · Back / Cancel · Confirm / Apply · Error / Invalid
│    Notification · Boot Sequence · GameBoot · Ambience          (8 sliders)
└─ SOUNDS
     Navigation · Back / Cancel · Confirm / Apply · Error / Invalid
     Notification                                    (5 assignment rows)
     Ambience Track                                  (1 assignment row)
```

### 6.1 Why levels and assignments are separate lists

The obvious design — one row per sound carrying both its file and its slider — **cannot work on a
controller.** `SettingsRow` claims SELECT to open the file picker;
`SettingsSliderRow` claims SELECT to enter adjust mode. One row cannot own both, and inventing a
modifier chord for a settings screen would be a worse answer than two lists.

If the combined screen proves too long in practice, the fallback is a separate
`"settings_volume"` route reached from a Sound row — not a redesign of the row.

### 6.2 Ambience's assignment row

Ambience has a **level** (in LEVELS) and a **file** (in SOUNDS). It is the only entry appearing in
both lists, which is correct: it is the only sound that is both assignable and continuous. Its
sublabel carries the gapless note from §5.5.

---

## 7. The slider accent fix

Replace `SettingsAccent` in `SettingsSliderRow` with the live theme. The proven derivation already
exists: `menuCursorEdge()` is `lerp(accentColor, White, 0.55f)` at 95% alpha — built precisely to
stay legible against the cursor fill it sits inside, which is the exact problem a slider thumb has.

| Element | Today | After |
|---|---|---|
| Thumb (adjusting) | `SettingsAccent` — fixed blue | `menuCursorEdge()` |
| Active track (adjusting) | `SettingsAccent` | `menuCursorEdge()` |
| Value text (adjusting) | `SettingsAccent` | `menuCursorEdge()` |
| Thumb / track (idle) | `SettingsSubtext` / `SettingsDivider` | unchanged |

Scope note: `SettingsAccent` is used by other screens too. **This task changes `SettingsSliderRow`
only.** A repo-wide accent audit is worth doing and is not this plan's job; widening it here would
put unrelated screens in a volume change's diff.

---

## 8. Execution Task Index

One bounded task per helper, ordered by dependency. No task may exceed its change budget without
stopping and reporting.

| # | Task | Files | Stop condition |
|---|---|---|---|
| **T1** | `AudioChannel` + the nine keys, declared once | new `core-domain/…/model/AudioChannel.kt`; new `core-ui/…/sound/AudioLevels.kt`; `UiMediaStore.kt` (impl + binding); `BackupManager.kt`; `BackupKeyCoverageTest` | Keys exist in exactly one declaration site; coverage test green; taper unit-tested at 0 / 0.5 / 1 |
| **T2** | Retire `sound_menu_enabled` | `UiMediaStore.kt`, `UiMediaPaths.kt`, `MenuSoundPlayer.kt`, `AudioSettingsViewModel.kt`, `BackupManager.kt`, `DisplaySettingsScreen.kt` comment | No reference to the pref remains; `MenuSoundPlayer.enabled` gone; `ignoreMute` preview path still audible at master 0 |
| **T3** | Apply gain in the two existing players | `MenuSoundPlayer.kt`, `UiMediaAudioPlayer.kt` + tests | SoundPool reads the live snapshot; a level change mid-clip is audible on the ExoPlayer path (test asserts `volume` updates without a restart) |
| **T4** | Slider accent → live theme | `SettingsSliderRow.kt` | Thumb, active track and value text track `LocalPFPColors`; no other screen touched |
| **T5** | `AMBIENCE_AUDIO` slot + limits + import | `UiMediaSlot.kt` (+ rewrite the stale `AUDIO_TRACK` KDoc), `UiMediaLimits.kt`, `UiMediaDefaults.kt`, their three tests | Slot imports and prunes like any other; `AMBIENCE` accepts `AUDIO_MIME` unrestricted (§5.5) |
| **T6** | `AmbienceController` — playback, gates, focus | new `core-ui/…/sound/AmbienceController.kt`; `MainActivity.kt` (2 lifecycle lines); `XMBViewModel.kt` (boot-done signal); `MusicPlayerController.kt` + `VideoPlayerScreen.kt` (suppression push) | Loops; releases on background; waits for boot; stops under music **and** video; yields and resumes on focus loss/gain |
| **T7** | Sound screen — three sections | `AudioSettingsScreen.kt`, `AudioSettingsViewModel.kt`, `AudioSettingsViewModelTest` | Master + 8 levels + 6 assignments render; the §3.1 invariant is **restated**, not deleted |
| **T8** | Docs | `ARCHITECTURE.md`, `CHANGELOG.md`, this file's status line | Volume model and the suppression direction named in Conventions |

T4 is independent of everything else and can land first or in parallel — it is a self-contained
visual fix that happens to be motivated by this screen.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Slider drag inaudible until next playback | T3 stop condition tests live update explicitly; §4.5 calls it the likeliest miss |
| Ambience keeps playing over Spotify | T6 requires full focus handling, not a foreground gate alone |
| Ambience and music both sound at once | Explicit suppression (§5.3); focus will not do this for you |
| A volume key silently fails to restore | `BackupKeyCoverageTest` is the existing tripwire; T1 extends it |
| Nine keys drift across declaration sites | T1's stop condition is *one* declaration site, inherited from the §2.2 finding |
| MP3 seam click | Accepted by decision (§5.5); helper text names OGG as the gapless option |
| A helper filters the picker to OGG | §5.5 marks the unrestricted MIME set as a decision, not an oversight |
| Helper "tidies" the volume/suppression asymmetry | §5.3 states the asymmetry is deliberate |
