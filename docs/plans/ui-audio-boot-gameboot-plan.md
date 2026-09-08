# UI Audio, Boot Sequence, and GameBoot Customization

Source: `PFP_UI_AUDIO_BOOT_GAMEBOOT_DESIGN.md` (project owner, 2026-09-07). Effort: **L** — ships in four independently-releasable phases.
Branch base: `more-customization` (has staged uncommitted work — commit or stash before starting).

This is an implementation handoff. It is written to be executed without the conversation that produced it. Every line reference was verified against the working tree on 2026-09-07. Add a row to `docs/plans/README.md` on completion, and copy this file to `docs/plans/ui-audio-boot-gameboot-plan.md`.

---

## Context

PFP's sound and startup presentation are fixed. Seven `.wav` files are compiled into `core-ui/src/main/res/raw/` and there is exactly one knob — a single **Menu Sounds** on/off toggle buried under Display ▸ Sound. The boot sequence is a hardcoded 2.8 s Compose animation of `pfp_boot_logo` over the wave. There is no transition at all between confirming a game and the emulator taking the screen.

The owner wants three things: a real **Interface ▸ Audio** screen where every menu sound can be replaced; a **Boot Sequence** that can play the user's own video and audio; and a new **GameBoot** — the short PS3-style presentation between "Play" and the emulator.

**The good news is that most of the hard parts already exist.** The semantic audio layer the design doc asks for is already there — `MenuSoundPlayer.play(MenuSound.X)` is called from ~90 sites across three ViewModels and none of them know where the file comes from. The import gate, the ExoPlayer release discipline, and the SAF pick-validate-copy ceremony all have working templates. This work is mostly *plumbing custom sources into layers that already have the right shape*.

**Three pre-existing defects fall inside this feature's blast radius and must be fixed as part of it:**

| Defect | Evidence |
|---|---|
| **The "Show Boot Sequence" toggle does nothing.** `display_show_boot` and `display_boot_on_resume` are written by `DisplaySettingsViewModel` and backed up by `BackupManager`, but nothing reads them. `XMBUiState.showBootSequence` is hardcoded `true`. | `XMBViewModel.kt:587`; grep for `display_show_boot` in feature-xmb returns nothing |
| **`MenuSoundPlayer.enabled` is only pushed by `XMBViewModel`.** `AppDrawerViewModel` and `GameDetailViewModel` play sounds through a singleton whose mute flag depends on another ViewModel being alive. True today, enforced by nothing. | `XMBViewModel.kt:1444-1451` is the only writer |
| **`sfx_quicksysselect.wav` ships in the APK and is never loaded.** | `core-ui/src/main/res/raw/`, absent from `MenuSoundPlayer.init` |

---

## Decisions already made

Settled with the project owner. If one turns out to be impractical, **stop and report** rather than substituting your own.

### 1. Storage: copy into `filesDir`, not `content://` URIs

The design doc says "store content URIs, not raw filesystem paths." **Override it.** Every user-media customization in this repo already copies the picked file into `filesDir` and persists an absolute path — `DisplaySettingsViewModel.importMotionWallpaper` (`:272`) and `CustomIconStore.import` are the two templates. The doc itself permits this ("unless the current project architecture requires it").

Why it is the right call here, not just the consistent one:

- **`SoundPool` needs a stable, re-openable source.** The pool loads samples once at construction and holds them for the app's lifetime. A URI whose grant can be revoked is the wrong input for that.
- **The doc's entire "unavailable asset" state machine evaporates.** Rules 2, 3 and the *"Custom file unavailable — using PFP Default"* row state exist only because a URI can rot. A copied file cannot be renamed, moved, or deleted out from under us. Keep the fallback path in the resolver (a file can still fail to decode) but the UI never needs an "unavailable" row state.
- **It rides the existing backup.** `BackupManager.BACKED_UP_DIRECTORIES` already carries `custom-icons`; adding `ui-media` is one line.
- **It leaves the theme door open.** `.pfptheme` schema v3 could carry a sound pack later; it cannot carry a URI.

Record this deviation explicitly in the completion report.

### 2. Boot may play custom video — with hard caps and a watchdog

`BootSequenceOverlay.kt:66-69` currently carries an explicit warning:

> *Boot is the single most contended moment on the device — "boot plays the user's video" is a tempting-but-wrong future edit.*

The owner has decided to reverse this. **Update that comment to record the reversal and the guard rails that replace it** — do not silently delete it. The reversal is only safe because of the watchdog in §Phase 3; if you cannot make the watchdog work, the reversal is off.

### 3. GameBoot covers game launches only

Game launches funnel through `LaunchDispatcher.launch()` — two call sites, `GameDetailViewModel.kt:793` and `XMBViewModel.kt:7409`, both *after* full preflight. That single seam is where GameBoot goes.

Plain app launches (`appCategoryRepository.launch(pkg)`) are scattered across ~7 unfunneled sites in `XMBViewModel`, `AppDetailViewModel`, and `AppDrawerViewModel`. They keep the regular App Launch sound. Funnelling them is a separate refactor and explicitly **out of scope**.

### 4. Six events, no Error/Invalid in v1

`MenuSound` already has six events with bundled defaults. Expose all six. The doc's **Error / Invalid** event has no bundled `.wav` in the repo and rule 5 of the doc forbids shipping an event that resolves to silence — so it is **deferred**, not implemented-and-silent. The resolver and the validator are built generically over the enum, so adding `ERROR` later is: drop in `sfx_error.wav`, add the enum constant, add a row, add a `UiMediaLimits` entry. No rework.

### 4a. AMENDMENT (owner, during implementation) — the event set is seven, not six

Auditing `assets/SFX` against the shipped `res/raw` showed two bundled samples were registered
under the wrong event, and one was a duplicate:

- `sfx_favorite.wav` is a **notification** chime → renamed `sfx_notification.wav`, now the default
  for a new `NOTIFICATION` event (reserved for the planned notification drawer: task done, alert).
- `sfx_back.wav` is the **error** chime → renamed `sfx_error.wav`, now the default for `ERROR`.
  This un-blocks the deferral above: the missing `sfx_error.wav` exists, it was just misfiled.
- `sfx_quicksysselect.wav`, `sfx_scroll.wav` and `sfx_systembrowse.wav` were byte-identical
  (md5 `90d1fbc05a`). The unmapped third copy was deleted; Navigation and Category Change still
  share one sample and need a distinct one.
- **Favorite is now silent by decision.** Favouriting is an operational toggle, not an event worth
  sonifying, so `MenuSound.FAVORITE`, `UiMediaSlot.SOUND_FAVORITE` and both call sites are removed
  rather than left resolving to nothing.

Net: `SCROLL, SYSTEM_BROWSE, SELECT, BACK, LAUNCH, NOTIFICATION, ERROR`. `NOTIFICATION` and `ERROR`
have bundled defaults and customizable rows but **no call site yet** — they fire when the
notification drawer and the error paths land. `BACK` currently shares `sfx_error.wav` as a
documented placeholder and is the one remaining gap alongside `SYSTEM_BROWSE`.

### 5. Keep "Menu Sounds" enable polarity — do not migrate to "Mute Menu SFX"

The doc names the setting **Mute Menu SFX** (default off). The repo has **Menu Sounds** (`sound_menu_enabled`, default `true`). The doc's own §13 says *"Prefer reusing its existing storage key and changing only its screen placement."*

Inverting the polarity means either a migration (a restored old backup would arrive with inverted meaning — `BackupManager` restores raw booleans) or a UI-layer inversion that leaves the key's name lying about its value. Neither buys anything. **Keep the key, keep the polarity, keep the label "Menu Sounds", and move the row to the top of the new Audio screen.** Note the naming deviation in the report.

---

## What already exists — reuse, do not rebuild

| Need | Already in the tree |
|---|---|
| Semantic audio event bus | `core-ui/.../sound/MenuSoundPlayer.kt` — `enum MenuSound`, `@Singleton`, `SoundPool`, `USAGE_GAME`, `enabled` gate. ~90 call sites already speak in events. |
| Import gate object shape | `core/theme-kit/.../MotionLimits.kt` — `Probe`, `validate(): String?`, `MSG_*` constants, boundary tests |
| SAF pick → validate → copy ceremony | `DisplaySettingsViewModel.importMotionWallpaper` (`:272-345`): descriptor size pre-check, copy, probe the *copy*, delete on reject, atomic pref write, prune dir |
| Slot-keyed file store with staging + path-escape guard | `core-data/.../repository/CustomIconStore.kt` |
| Bounded read of untrusted media | `core-data/.../repository/SafeMedia.kt` — `readCapped`, `decodeFileCapped` |
| Duration/resolution probing | `DisplaySettingsViewModel.probeMotionFile` (`:353`) — `MediaMetadataRetriever` |
| One-shot, non-looping video with release discipline | `feature-xmb/.../ui/Icon1VideoOverlay.kt` — the closest analogue to a GameBoot player. `TextureView`, `ClippingConfiguration`, `REPEAT_MODE_OFF`, released not paused on dispose. |
| Looping video + poster + policy object | `core-ui/.../motion/MotionWallpaperBackground.kt`, `MotionWallpaperPolicy.kt`, `MotionFormat.kt` |
| Settings row family + controller focus | `feature-settings/.../ui/SettingsScaffold.kt` — `SettingsScaffold`, `SettingsGroup`, `SettingsRow`, `SettingsToggleRow`, `SettingsValueRow`, `SettingsFocusable`; `SettingsSliderRow.kt` |
| Controller helper-footer glyphs | `core-ui/.../components/ControllerPrompt.kt` (`ControllerPromptItem`), `ControllerHintBar.kt` |
| Theme sound-pack plumbing (unused, but present) | `PFPTheme.soundPackUri` / `ThemeSoundEvent` (`core-domain/.../model/PFPTheme.kt:18,23-31`); `XmbThemeLoader.extractSoundPack` already unpacks to `filesDir/themes/{id}/sounds/` |

### The trap

`MenuSoundPlayer` is a `@Singleton` with **no `release()`** and samples loaded once in `init` from `R.raw`. It is tempting to add a second player for custom sounds and branch at the call site. **Do not.** All ~90 call sites must keep calling `play(MenuSound.X)` unchanged — that is the design doc's §14 requirement ("feature screens must not contain custom/default URI branching") and it is already satisfied. **The only file that changes is `MenuSoundPlayer` itself.** Everything else in Phase 2 is settings UI.

### The second trap

`ThemeSoundEvent` (6 constants) and `MenuSound` (6 constants) **are not the same set** and do not map cleanly (`NAVIGATE_HORIZONTAL`/`NAVIGATE_VERTICAL` vs `SCROLL`/`SYSTEM_BROWSE`; `ThemeSoundEvent` has `BOOT`, `MenuSound` has `FAVORITE`). Do not try to unify them in this work. `ThemeSoundEvent` is dead code with no consumer; leave it alone.

---

## Architecture

```text
core/theme-kit/UiMediaLimits.kt        pure JVM — per-event caps, Probe, validate()
        │
core/core-data/repository/UiMediaStore.kt
        │   filesDir/ui-media/<slot>.<ext>, staged import, probe, prune
        │   slots: sound_scroll … sound_favorite, boot_video, boot_audio,
        │          gameboot_video, gameboot_audio
        ├──► MenuSoundPlayer          (core-ui)  resolves custom file → default R.raw
        ├──► BootSequenceOverlay      (feature-xmb)
        └──► GameBootOverlay          (feature-xmb)
                    ▲
            GameBootGate (feature-launcher, @Singleton)
                    ▲
            LaunchDispatcher.launch() — awaits the gate before startActivity
```

`UiMediaStore` is the single owner of resolution and fallback. Nothing else opens a custom media file.

### `core/theme-kit/.../UiMediaLimits.kt` (new)

Pure JVM, beside `MotionLimits`, for the same reason `MotionLimits` lives there: a future `.pfptheme` sound pack must validate against the same numbers on the desktop side. Model it directly on `MotionLimits` — same `Probe`/`validate` shape, same `MSG_*` constant style.

Per-slot caps, from design doc §7:

| Slot | Recommended | Hard max |
|---|---:|---:|
| `sound_scroll` (Navigation) | 0.05–0.25 s | **0.50 s** |
| `sound_systembrowse` | 0.05–0.25 s | **0.50 s** |
| `sound_select` (Confirm) | 0.10–0.50 s | **1.00 s** |
| `sound_back` | 0.10–0.50 s | **1.00 s** |
| `sound_favorite` | 0.10–0.50 s | **1.00 s** |
| `sound_launch` (App Launch) | 0.30–2.00 s | **3.00 s** |
| `gameboot_video` / `gameboot_audio` | 1.00–5.00 s | **5.00 s** |
| `boot_video` / `boot_audio` | 1.00–8.00 s | **10.00 s** |

Audio MIME: `audio/mpeg`, `audio/wav`, `audio/x-wav`, `audio/ogg`, `audio/mp4`, `audio/mp4a-latm`.
Video MIME: reuse `MotionLimits.SUPPORTED_MIME` minus the animated-image entries — `video/mp4`, `video/webm` only. GIF is explicitly a non-goal for boot animation (doc §3).

Byte caps: 2 MB per sound, 25 MB per video. These are new judgment calls — put them in this file with a comment saying so, exactly as `MotionLimits` does.

**Duration is mandatory for audio.** Doc §7: *"If duration cannot be read reliably, reject the assignment."* `MediaMetadataRetriever` returns null `METADATA_KEY_DURATION` for some malformed files — that is a rejection, not a pass. This differs from `MotionLimits`, which tolerates `durationMs = 0` for GIFs; do not copy that leniency here.

**Fallback before rejecting (device-observed).** Some devices return a null duration for otherwise-playable files — in practice, short VBR MP3s (a handful of frames) encoded by ffmpeg's native encoder, and some WAVs. Before the gate rejects, it falls back to `MediaDurationFallback` (`core-data/.../repository/MediaDurationFallback.kt`), which reads the length deterministically from the container headers: MP3 Xing/Info frame count (frames × samples-per-frame ÷ sample-rate, the same math Android's extractor uses) or CBR bytes ÷ bitrate; PCM WAV data-chunk bytes ÷ byte-rate. Only a file neither probe can time is rejected, so the rescue narrows when the gate rejects without ever widening what passes the caps.

### `core/core-data/.../repository/UiMediaStore.kt` (new)

Copy the structure of `CustomIconStore` almost line for line. It already solves staging, the path-escape guard, the descriptor-can-lie problem, and the stamp-bump-to-invalidate-observers problem.

```kotlin
@Singleton
class UiMediaStore @Inject constructor(@ApplicationContext private val context: Context) {

    data class ImportResult(val ok: Boolean, val message: String)

    /** Absolute path of the user's file for [slot], or null when the slot is on the PFP default. */
    fun pathFor(slot: UiMediaSlot): String?

    /** Every assigned slot, for the settings summaries. Cheap: one dir listing. */
    fun assignments(): Map<UiMediaSlot, String>

    suspend fun import(slot: UiMediaSlot, uri: Uri): ImportResult
    suspend fun clear(slot: UiMediaSlot)
    suspend fun clearAll(kind: UiMediaSlot.Kind)   // "Reset Audio to Defaults" / per-screen reset
}
```

Rules, each of which has a precedent in `CustomIconStore`:

- Directory is the source of truth. `filesDir/ui-media/<slot.key>.<ext>`, one file per slot, extension derived from the *validated* MIME so the suffix is the MIME by construction.
- Import is staged (`staging_<millis>.<ext>`) and only committed by `renameTo` after `validate` passes. This is what implements the doc's **"failed replacement rule"** — a rejected pick cannot disturb a working assignment, structurally rather than by care.
- Descriptor size pre-check via `openAssetFileDescriptor(uri,"r")?.use { it.length }` before transferring a byte; then `SafeMedia.readCapped` as the real backstop.
- One `longPreferencesKey("ui_media_stamp")`, bumped on every commit/clear. `MenuSoundPlayer` observes it to reload; without it a replaced sound keeps playing the old sample — the same bug `CustomIconStore`'s stamp exists to prevent.
- **No DataStore key per slot.** The file's presence is the assignment. This is deliberately unlike the wallpaper (which needs a path in prefs because two files form a poster/motion pair) and matches `custom-icons`.

`UiMediaSlot` is an enum in `core-domain` carrying `key`, `kind` (`SOUND` / `VIDEO` / `AUDIO_TRACK`), display name, and its `UiMediaLimits` entry. Slot keys are used verbatim as filenames — keep an `isValidKey`-style guard even though the enum makes escape impossible today, mirroring `CustomizableIcons.isValidKey`.

### Display names

There is **no `OpenableColumns.DISPLAY_NAME` helper anywhere in this repo.** Add one — `queryDisplayName(context, uri): String?` beside `SafeMedia` — and cache the result. Because we copy the file, the name is cosmetic; if the query returns null, fall back to `"Custom sound"` / `"Custom video"` per doc §12. Never render a raw `content://` string.

---

# Phase 1 — Foundation

No user-visible change. Ships on its own.

1. `core/theme-kit/.../UiMediaLimits.kt` + `UiMediaLimitsTest` (boundary at each cap, plus a drift test that every `UiMediaSlot` has an entry — model on `MotionLimitsTest`).
2. `core-domain/.../model/UiMediaSlot.kt`.
3. `core-data/.../repository/UiMediaStore.kt` + `UiMediaStoreTest` (model on `CustomIconStoreTest`).
4. `queryDisplayName` helper beside `SafeMedia.kt`.
5. `BackupManager`: add `"ui-media"` to `BACKED_UP_DIRECTORIES` and `longPreferencesKey("ui_media_stamp")` to `BACKED_UP_LONG_KEYS`. **Do not skip this** — without the stamp the files restore but nothing reloads them, which is the exact bug the `custom_icons_stamp` comment documents.

# Phase 2 — Interface ▸ Audio

### 2a. `MenuSoundPlayer` — the only playback change in the whole feature

`core-ui/.../sound/MenuSoundPlayer.kt` gains a `UiMediaStore` dependency and a reload path. `play(MenuSound)` and every one of its ~90 call sites are untouched.

- Keep the `R.raw` samples loaded permanently — they are the fallback and must never be evicted (doc rule 11).
- Add a second `HashMap<MenuSound, Int>` for custom sample ids, loaded with `pool.load(path, 1)` (the `String` overload — `SoundPool` takes a file path directly).
- `play` resolves: `custom[sound]?.takeIf { it in loaded } ?: default[sound]`. A custom sample that failed to load falls through to the default silently, which *is* doc rule 3.
- `reload()` unloads previous custom ids (`pool.unload`) and reloads from `UiMediaStore`. Called on the `ui_media_stamp` change.
- Rapid-navigation behavior is already correct — `maxStreams=4` with `SoundPool`'s own oldest-stream eviction gives the doc's "interrupt rather than queue" for free. Verify, don't rebuild.

**Also fix the two defects here:** move the `sound_menu_enabled` observation out of `XMBViewModel.observeSoundSetting()` (`:1444`) and into `MenuSoundPlayer` itself, so the mute flag no longer depends on which ViewModel happens to be alive. Delete the observer from `XMBViewModel`; keep the pref key (add a `// moved to MenuSoundPlayer` note at `:8361` or remove the now-unused constant).

### 2b. The Audio screen

New route `settings_audio`. This is a **four-file operation** — the checklist is non-negotiable, `SettingsHierarchyTest` and `SettingsScaffoldNavigationTest` fail otherwise:

| File | Change |
|---|---|
| `feature-settings/.../ui/SettingsNavHost.kt` | add `"settings_audio"` to `SETTINGS_SCREEN_ROUTES` **and** to the `when` |
| `feature-xmb/.../viewmodel/XMBViewModel.kt` `settingsSectionItems(INTERFACE)` (~`:424`) | insert `XMBItem(id = "settings_audio", title = "Audio", subtitle = "Menu sounds")` |
| new `feature-settings/.../ui/AudioSettingsScreen.kt` | the screen |
| new `feature-settings/.../viewmodel/AudioSettingsViewModel.kt` | state + actions |

Screen shape — follow `DisplaySettingsScreen` exactly (`SettingsScaffold` + `rememberScrollState` + `LocalSettingsScrollStateRegistrar.current(scrollState)`):

```text
SettingsGroup("Menu Sounds")
  SettingsToggleRow  "Menu Sounds"          ← the moved sound_menu_enabled row, same key/polarity
SettingsGroup("Sound Assignments")
  SettingsValueRow   "Navigation"           value = "PFP Default" | "cursor.wav"
  SettingsValueRow   "Category Change"
  SettingsValueRow   "Confirm / Apply"
  SettingsValueRow   "Back / Cancel"
  SettingsValueRow   "App Launch"
  SettingsValueRow   "Favorite"
SettingsGroup("")
  SettingsRow        "Reset Audio to Defaults"
```

- One `ActivityResultContracts.OpenDocument()` picker, MIME array from `UiMediaLimits.AUDIO_MIME`, launched with the pending slot held on the ViewModel — the `DisplaySettingsScreen.kt:45` pattern.
- **No editor sub-screen.** The doc's §9 editor mock is a separate destination; this repo has no such convention and `SettingsValueRow` + picker is how every other assignment works here. Selecting a row opens the picker directly; a per-row `SettingsRowAction` (the `actions =` parameter on `SettingsRow`) gives controller-reachable **Preview** and **Use Default** buttons on the row itself. Cheaper, and it matches the app.
- Preview plays through `MenuSoundPlayer` with an explicit ignore-mute path, so a user can audition while muted (doc §9). Add `play(sound, ignoreMute = true)` — a defaulted parameter, so no call site changes.
- Validation failures surface as `MutableStateFlow<String?>` + `AlertDialog`, the `wallpaperMessage` pattern. Strings are hardcoded literals — **this repo does not use string resources** (`grep "R.string"` in feature-settings returns zero).
- Reset requires an `AlertDialog` confirmation. It clears files only; it never touches Boot or GameBoot, and it resets `sound_menu_enabled` to `true`.

### 2c. Remove the old row

Delete the `SettingsGroup("Sound")` block from `DisplaySettingsScreen.kt:270-277` and the now-unused `menuSoundEnabled` field/reader/setter from `DisplaySettingsViewModel`. Doc §19: no duplicate row may remain.

# Phase 3 — Boot Sequence

### 3a. Fix the dead toggle first

Before adding anything: make `display_show_boot` and `display_boot_on_resume` real. `XMBViewModel` must seed `XMBUiState.showBootSequence` from the pref (`:587` currently hardcodes `true`), and honour `display_boot_on_resume` on the resume path. Ship this as its own commit — it is a standalone bug fix and it is what makes doc rule 7 ("Disabling Boot Sequence skips both of its media components") achievable.

### 3b. Custom boot media

`BootSequenceOverlay.kt` gains `bootVideoPath: String?` and `bootAudioPath: String?`, resolved by the caller in `XMBShell.kt:892` from `UiMediaStore`. Four combinations must all work (doc §10): default/default, custom video + default audio, default video + custom audio, custom/custom. "Default video" means the existing logo animation; there is no bundled boot `.mp4` and none should be added.

Player: model on `Icon1VideoOverlay.kt`, **not** `MotionWallpaperBackground` — boot is one-shot, not a loop.

- `ExoPlayer`, `REPEAT_MODE_OFF`, `TextureView`, released (not paused) in `DisposableEffect.onDispose`.
- Audio **enabled** here, unlike every other player in this repo. That is the one deliberate divergence from the `setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)` house rule — note it in the KDoc so a future reader doesn't "fix" it.
- `MediaItem.ClippingConfiguration.setEndPositionMs(10_000)` as a second belt behind the import gate.
- Custom boot audio is a separate one-shot `ExoPlayer` (not `SoundPool` — up to 10 s is not a UI sonification), started in the same `LaunchedEffect` as the video. Two independent components, per doc §10: one failing does not stop the other.

### 3c. The watchdog — this is what makes decision 2 safe

The reversal of the "boot never plays user video" rule stands or falls on this. Boot must complete on a timer that does not depend on the player:

- `LaunchedEffect` starts a `withTimeoutOrNull(HARD_CAP_MS)` around the whole presentation, `HARD_CAP_MS = 12_000` (the 10 s cap plus fade). On timeout, `onComplete()` fires regardless of player state.
- `Player.Listener.onPlayerError` → `onComplete()` immediately, logged via Timber.
- If the video path is set but the file is gone or won't decode, fall back to the built-in logo animation rather than a black screen.
- `onComplete()` must be idempotent — it is already (`_uiState.update { copy(showBootSequence = false) }`), but the overlay must guarantee it fires **exactly once** across timeout / error / natural end. A `remember { AtomicBoolean() }` guard.
- Skip: boot currently swallows all gamepad input (`XMBViewModel.kt:4560`). Add a Confirm/Start → skip path that calls the same `onComplete()`.
- Boot still must not play the user's **motion wallpaper** — that restriction is unrelated and stays. Keep `XmbBackground` without wallpaper args.

### 3d. Settings

Extend the existing `SettingsGroup("Boot Sequence")` in `DisplaySettingsScreen.kt:192` — do not create a new screen. Add `Boot Animation`, `Boot Sound`, `Preview Boot Sequence`, `Reset to Default` rows beneath the two existing toggles. Preview composes the real `BootSequenceOverlay` over the settings screen with `onComplete` returning to settings.

# Phase 4 — GameBoot

### 4a. The gate

New `feature-launcher/.../GameBootGate.kt`, `@Singleton`:

```kotlin
@Singleton
class GameBootGate @Inject constructor(private val store: UiMediaStore, /* enabled pref */) {
    private val _active = MutableStateFlow<GameBootRequest?>(null)
    val active: StateFlow<GameBootRequest?> = _active.asStateFlow()

    /** Suspends until the presentation finishes, is skipped, or times out. No-op when disabled. */
    suspend fun awaitPresentation(gameTitle: String)

    fun onPresentationFinished()
}
```

Insert **one** `await` inside `LaunchDispatcher.launch()`, immediately before `context.startActivity(...)` — after every preflight, so a game that cannot launch never shows a GameBoot (doc §11). Both game paths get it with zero call-site churn.

`XMBShell` observes `gameBootGate.active` and composes `GameBootOverlay` above everything (same z-order slot as `BootSequenceOverlay`).

Watchdog: `withTimeout(7_000)` inside `awaitPresentation`, wrapped so a timeout **proceeds with the launch** rather than throwing. Doc rule 13 — the user must never be trapped on the transition screen.

### 4b. Precedence over the App Launch sound

Doc §11: when GameBoot is enabled the regular `MenuSound.LAUNCH` must not play, and mute must not silence GameBoot audio.

The launch sound fires early, at the *confirm* moment, well before the dispatcher — `XMBViewModel.kt:7054` and `GameDetailViewModel.kt:662`. So this is not something the gate can suppress after the fact. Add a `gameBootEnabled` check at those two sites:

```kotlin
// XMBViewModel.kt:7054 today: if (!silentRow) menuSound.play(if (launches) LAUNCH else SELECT)
val event = when {
    silentRow -> null
    launches && gameBootEnabled -> null   // GameBoot's own audio replaces it
    launches -> MenuSound.LAUNCH
    else -> MenuSound.SELECT
}
event?.let { menuSound.play(it) }
```

(`play` keeps its non-null signature — the nullability is local to the decision, not pushed into
the player. `GameDetailViewModel.kt:662` gets the same guard around its unconditional
`play(LAUNCH)`.)

Read the flag from the same source the gate uses so the two can never disagree. **These are the only two call sites in the entire feature that gain a conditional** — everywhere else keeps calling `play()` blind.

GameBoot audio plays through its own `ExoPlayer`, not `MenuSoundPlayer`, so `sound_menu_enabled` cannot reach it. That satisfies doc rule 6 structurally.

### 4c. Player and settings

`GameBootOverlay` is `Icon1VideoOverlay`'s shape again — one-shot, audio enabled, 5 s clip cap, released on dispose. **Stop playback in `onDispose` before the emulator takes the screen** (doc §16) — releasing the player on the same frame the overlay leaves composition already does this.

Debounce: `LaunchDispatcher` already holds `pending` state; while a GameBoot is active, a second launch request must be dropped, not queued.

Settings: a new `SettingsGroup("GameBoot")` in `DisplaySettingsScreen`, defaulting **off** (doc §11 — enabling it by default would add launch latency for existing users). Preview must never launch anything: it composes the overlay directly, never touching the gate.

New prefs: `display_gameboot_enabled` (boolean, default `false`). Add to `BACKED_UP_BOOLEAN_KEYS`.

---

## Tests

Follow the repo's split: pure logic in JVM unit tests, no instrumentation tests exist for settings and none should be added here.

| Test | Covers |
|---|---|
| `UiMediaLimitsTest` (theme-kit) | boundary at each per-slot cap; unknown MIME; **null duration ⇒ reject**; every `UiMediaSlot` has an entry (drift pin) |
| `UiMediaStoreTest` (core-data, Robolectric) | staged import commits only on pass; **a failing import leaves the previous assignment intact**; clear removes the file; stamp bumps on every mutation; path-escape guard |
| `MenuSoundPlayerTest` (core-ui, Robolectric) | custom resolves over default; a custom sample that fails to load falls back; mute silences; `ignoreMute = true` does not |
| `AudioSettingsViewModelTest` | row summaries (`PFP Default` vs display name); reset clears assignments and restores `sound_menu_enabled = true`; reset does not touch boot/gameboot slots |
| `GameBootGateTest` | disabled ⇒ `awaitPresentation` returns immediately; timeout proceeds with launch rather than throwing; duplicate request while active is dropped |
| `LaunchDispatcherTest` (extend) | `startActivity` is called exactly once after the gate resolves; a gate timeout still dispatches |
| `XMBViewModelTest` (extend) | `display_show_boot = false` ⇒ `showBootSequence` starts false; GameBoot enabled ⇒ `MenuSound.LAUNCH` is not played on confirm |
| `SettingsHierarchyTest` (extend) | `settings_audio` present in both the route set and the Interface section |

The boot watchdog and the overlay players are Compose + ExoPlayer and are **not unit-testable here** — verify them on device and say so in the report rather than writing a test that proves nothing.

---

## Verification

Per repo memory: **do not run Gradle builds unless the owner asks**, and **do not drive the device** — ask the owner to navigate and reproduce.

Commands the owner (or you, when asked) should run:

```bash
./gradlew :core:theme-kit:test :core:core-data:testDebugUnitTest :core:core-ui:testDebugUnitTest
```

```bash
./gradlew :feature:feature-settings:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest :feature:feature-launcher:testDebugUnitTest
```

```bash
./gradlew assembleFullDebug
```

On-device checks to request, in order:

1. **Audio** — Interface ▸ Audio exists; Display ▸ Sound is gone; the mute value survived the update; assign a custom Navigation sound and scroll fast (no backlog, no input lag); preview while muted still plays; reset restores every row.
2. **Rejection** — pick a 3-second file for Navigation: it is refused, the message names the 0.50 s cap, and **the previous assignment still plays**.
3. **Boot** — toggle Show Boot Sequence off and confirm boot is actually skipped (this never worked before). Assign a custom video + sound; confirm all four default/custom combinations; then rename the file out from under it via a file manager and confirm PFP still reaches the XMB.
4. **GameBoot** — off: launch sound plays as today. On: launch sound is suppressed, GameBoot plays, emulator takes over, no audio bleeds into the game. Mute menu sounds and confirm GameBoot is still audible. Mash Confirm during the presentation and confirm one launch, not several.
5. **Backup** — back up, wipe, restore: sounds and boot/gameboot media come back and are actually in use (the stamp).

---

## Report on completion (doc §23)

- Repo areas inspected before editing
- Files changed and why
- **The two deliberate deviations**: `filesDir` copies instead of content URIs (§Decisions 1), and "Menu Sounds" enable polarity retained instead of "Mute Menu SFX" (§Decisions 5)
- **The deferred requirement**: Error / Invalid sound, blocked on a bundled `sfx_error.wav`
- The three pre-existing defects fixed along the way
- Commands run and their results — **do not claim completion without a green build and green tests**
- Any device- or provider-specific limitation found

Do not silently weaken a duration cap, the fallback guarantee, or the boot watchdog to make something pass.
