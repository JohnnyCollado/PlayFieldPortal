# GameBoot: default PSP-style sequence, default sound, and a three-way mode

## Context

PlayFieldPortal already has a GameBoot seam — the short presentation between confirming a game and
the emulator taking the screen. It was built in plan C9 phase 4 and it works: `GameBootGate` is
awaited once inside `LaunchDispatcher.launch()` after every preflight, `GameBootOverlay` draws over
everything with its own ExoPlayer, and both confirm sites suppress the App Launch sfx so the two
never stack.

What it does **not** have is anything to show or play out of the box. `GAMEBOOT_AUDIO` and
`GAMEBOOT_VIDEO` both return `null` from `bundledDefaultRes()`, so with no user import the feature
is a fading white game title over black, in silence. It is also a plain on/off boolean, defaulted
off, so almost nobody ever sees it.

This change gives GameBoot a real default presentation — a PSP-style light sweep drawn in Compose,
timed to the bundled `sfx_launch` sound — and replaces the boolean with a three-way mode so the
user can pick the full sequence, sound only, or off.

**Decisions already made** (from the clarifying questions):

- Default sound is the existing bundled **`sfx_launch.wav`** (5.000 s container, ~4.62 s audible
  body, 2535 ms attack). No new asset, no new xcorr admission check.
- **Sound-only is fire-and-forget**: the sound starts and the intent fires immediately. Zero added
  launch latency; the emulator will cut the clip when it takes audio focus, and that is accepted.
- The **full sequence runs its whole ~5 s** and stays skippable via the existing Confirm/Back path.
- **New installs default to FULL; existing installs keep what they have.**

---

## What already exists (do not rebuild)

| Piece | File |
|---|---|
| Launch funnel + the one GameBoot seam | `feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/LaunchDispatcher.kt:89` |
| Gate, watchdog, `GameBootRequest` | `feature/feature-launcher/.../launcher/GameBootGate.kt` |
| Overlay, hard cap, skip, title card | `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/GameBootOverlay.kt` |
| One-shot ExoPlayer layers (audio enabled — mute must not silence GameBoot) | `feature/feature-xmb/.../xmb/ui/OneShotMedia.kt` |
| Slot registry and per-slot caps | `core/core-domain/.../model/UiMediaSlot.kt`, `core/theme-kit/.../UiMediaLimits.kt` |
| Slot → bundled `res/raw` mapping and the boot audio-resolution rule | `core/core-ui/.../core/ui/media/UiMediaDefaults.kt` |
| Singleton one-shot audition player | `core/core-ui/.../core/ui/media/BootSoundPreviewer.kt` |
| Frame-clock / Canvas / radial-bloom idioms to copy | `feature/feature-xmb/.../xmb/ui/XmbBackground.kt:172-212`, `:229-303` |
| Enum-setting house pattern (`SettingsValueRow` + `cycleX()`) | `DisplaySettingsScreen.kt:156`, `DisplaySettingsViewModel.kt:289` |

---

## 1. `GameBootMode` replaces the boolean

New file `core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/GameBootMode.kt`,
following the newer `IconLegibilityStyle` shape (label on the enum, tolerant `fromName`) rather
than the older `WaveStyle.valueOf` + `runCatching` form:

```kotlin
enum class GameBootMode(val label: String) {
    FULL("Full Sequence"),
    SOUND_ONLY("Sound Only"),
    OFF("Off");

    companion object {
        val DEFAULT = FULL
        fun fromName(value: String?): GameBootMode = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
```

Order matters: `entries` is the cycle order for the settings row, and `FULL → SOUND_ONLY → OFF`
reads as decreasing intensity.

**`core/core-data/.../repository/GameBootPreferences.kt`** — replace `gameBootEnabledFlow: Flow<Boolean>`
with `gameBootModeFlow: Flow<GameBootMode>` over a new `stringPreferencesKey("display_gameboot_mode")`,
and `setGameBootEnabled` with `setGameBootMode`. Keep the class in core-data for the reason its KDoc
already gives: three modules read this and must never disagree.

**Migration — read-time, no write.** Resolve in one `map` over the prefs snapshot:

1. `display_gameboot_mode` present → `GameBootMode.fromName(it)`.
2. else `display_gameboot_enabled` present → `FULL` if true, `OFF` if false.
3. else `initial_setup_seen` (the existing first-run marker, `InitialSetupViewModel.kt:130` /
   `XMBViewModel.kt:8515`) is `true` → this is an existing install that never touched GameBoot →
   `OFF`.
4. else → fresh install → `FULL`.

That is exactly "new installs get FULL, existing users keep what they have" with no migration pass
and no ordering hazard. `display_gameboot_enabled` is never written again but stays readable
forever, so an old backup restores correctly.

**`feature/feature-backup/.../BackupManager.kt:555`** — add `"display_gameboot_mode"` to
`BACKED_UP_STRING_KEYS`; leave the existing boolean key in its list so old archives still restore
and then migrate through rule 2.

---

## 2. A bundled default GameBoot sound

**`core/core-ui/.../core/ui/media/UiMediaDefaults.kt`**

- `GAMEBOOT_AUDIO -> R.raw.sfx_launch` in `bundledDefaultRes()`. `GAMEBOOT_VIDEO` and `BOOT_VIDEO`
  stay `null` — the "no bundled video, and none should be added" rule still holds; only the audio
  half of the sentence changes. Update the KDoc, which currently claims `BOOT_AUDIO` is the only
  `AUDIO_TRACK` slot with a default.
- Add `resolveGameBootAudio(customVideoPath, customAudioPath, bundledDefaultUri)` next to the
  existing `resolveBootAudio`, with the same three-part rule: a custom sound always wins; a custom
  video with no custom sound keeps its own track (`null`); nothing custom → the bundled default.
  Two named functions rather than one shared helper, because the two presentations may diverge and
  the existing one's KDoc is written specifically about boot.

Note the bundled sample is 4.62 s of audible body against `UiMediaLimits.GAMEBOOT.hardMaxMs = 5_000`,
so it fits its own slot cap with room. (Aside, not in scope: `UiMediaLimits.LAUNCH.hardMaxMs` is
3 000 ms, so a user could not import this same file as their Launch Sound. Worth a follow-up, not
this change.)

**`GameBootGate`** resolves the URI so the overlay never grows a fallback branch: inject
`@ApplicationContext Context`, and build the request as

```kotlin
val audio = resolveGameBootAudio(video, custom, UiMediaSlot.GAMEBOOT_AUDIO.bundledDefaultUri(context))
```

---

## 3. The PSP-style default visual

New file `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/GameBootFlash.kt`,
composed by `GameBootOverlay` in place of the bare title card when `videoPath == null`. The title
`Text` stays exactly as it is — it becomes the last beat of the sequence rather than the whole of it.

**Drive it off one `Animatable`, not the infinite frame clock.** `XmbBackground`'s
`produceState` + `withInfiniteAnimationFrameMillis` pattern is for a background that loops forever;
this is a one-shot timeline, so `Animatable(0f).animateTo(1f, tween(SEQUENCE_MS, LinearEasing))`
and read `.value` inside the `Canvas` lambda (reading it there is what schedules the redraw — same
mechanism `ShaderWave` relies on).

**Timeline, `SEQUENCE_MS = 5_000`**, aligned to the sound's 2535 ms attack so the flash peaks where
the swell does:

| ms | beat |
|---|---|
| 0 – 600 | pure black hold |
| 600 – 2 400 | radial bloom grows from just below centre — the `Brush.radialGradient` idiom at `XmbBackground.kt:204-212`, white at low alpha |
| 1 600 – 3 400 | the sweep: a light band crosses left→right, an `exp(-pow(...))` crest over a `smoothstep` sheet, the same construction as `drawFold` (`XmbBackground.kt:269-303`) |
| ~2 550 | peak brightness, on the sound's attack |
| 2 800 – 4 300 | game title fades in beneath the sweep (the existing `Text`) |
| 4 300 – 5 000 | decay to black, then `presentationDone = true` |

Draw white at low alpha over the theme gradient (`LocalPFPColors.current.backgroundTop` →
`backgroundBottom`) rather than tinting the light itself — that is the wave's own convention and
it makes the sweep pick up user themes for free. No AGSL variant is needed; this is a handful of
gradient rects per frame, well inside the Canvas fallback's budget.

**Honor the motion budget.** `BootSequenceOverlay` hardcodes `WaveStyle.ANIMATED` because it runs
once per app start. GameBoot runs on *every launch*, so plumb `uiState.waveStyle` (already computed
as `effectiveWaveStyle` in `XMBShell.kt:502-546`) into the overlay: when frozen or reduced, draw a
single static bloom + title and cut the sequence to ~1 400 ms. This is a deliberate deviation from
`BootSequenceOverlay`'s stance and should be noted in the new file's KDoc.

**Widen the two watchdogs.** The sequence is now 5 000 ms plus the existing 500 ms
`overlayAlpha` fade, and `GameBootOverlay.HARD_CAP_MS` is 6 000 with `GameBootGate.TIMEOUT_MS` at
7 000. That leaves 1 s and 1.5 s of margin respectively — too tight once a slow first frame is in
play. Raise them to **6 500** and **8 000**, keeping the invariant that the overlay's cap is the
shorter of the two so the gate's watchdog stays the last resort.

---

## 4. Wiring the three modes

**`GameBootGate.awaitPresentation(gameTitle)`** branches on the mode instead of a boolean:

- `OFF` → return immediately. Nothing plays; the ordinary `MenuSound.LAUNCH` sfx handles the launch
  as it does today, subject to the Menu Sounds mute.
- `SOUND_ONLY` → start the GameBoot audio and return immediately. No `_active` emission, so no
  overlay composes and no completion is awaited. This is what makes it fire-and-forget.
- `FULL` → today's path: emit the request, await completion under the timeout, clear in `finally`.

The `isActive` duplicate-drop guard only applies to `FULL`, since the other two never set `_active`.

**Fire-and-forget playback** reuses `BootSoundPreviewer` rather than adding a second player class.
Generalize it to take a slot: `fun play(slot: UiMediaSlot = UiMediaSlot.BOOT_AUDIO, customPath: String?)`,
clipping to `slot.limits.hardMaxMs` instead of the hardcoded `UiMediaLimits.BOOT.hardMaxMs`, and
rename it `UiMediaAudioPlayer` (its KDoc's "audition" framing is no longer the only use). It is
already a `@Singleton` that outlives any screen, which is exactly the property sound-only needs —
the clip keeps sounding after `startActivity` until the emulator takes audio focus. Update its two
existing call sites in `DisplaySettingsViewModel` / `AudioSettingsViewModel`.

**Launch-sfx suppression** at the two confirm sites — `XMBViewModel.kt:7096` and
`GameDetailViewModel.kt:676` — becomes `mode != GameBootMode.OFF`. Both `FULL` and `SOUND_ONLY`
produce their own audio, so the menu Launch Sound must not stack in either. Both sites already read
`GameBootPreferences` for exactly this reason; only the predicate changes. Rename the cached
`private var gameBootEnabled: Boolean` to `gameBootMode: GameBootMode` in both, and in
`XMBViewModel.observeGameBoot()` (`:8106`).

---

## 5. Settings

**`DisplaySettingsScreen.kt:253`** — swap the `SettingsToggleRow("GameBoot")` for the cycling row,
mirroring the Icon Legibility row at `:156`:

```kotlin
SettingsValueRow(
    label = "GameBoot",
    sublabel = "Presentation between confirming a game and the emulator opening.  " +
        "Full Sequence | Sound Only | Off.  Full Sequence adds about five seconds to every " +
        "launch and can be skipped with Confirm or Back.",
    value = state.gameBootMode.label,
    onClick = { viewModel.cycleGameBootMode() },
)
```

The two media rows below it keep their copy, but "GameBoot Sound" should note that leaving it
unset now plays the bundled launch sound rather than nothing.

**`DisplaySettingsViewModel.kt`** — `gameBootEnabled: Boolean` → `gameBootMode: GameBootMode` on
`DisplaySettingsUiState` (`:132`); read it in the `combine` block (`:208`) through
`GameBootMode.fromName`; replace `setGameBootEnabled` (`:269`) with the standard cycle:

```kotlin
fun cycleGameBootMode() {
    val modes = GameBootMode.entries
    val next = modes[(modes.indexOf(uiState.value.gameBootMode) + 1) % modes.size]
    viewModelScope.launch { gameBootPreferences.setGameBootMode(next) }
}
```

No labels map is needed — the label lives on the enum.

**Preview** (`XMBViewModel.previewGameBoot()`, `:8149`) always shows the **full** presentation
regardless of mode, so the user can audition it before switching on. Its "nothing is launched"
contract is unchanged.

---

## 6. Docs

- `assets/SFX/REFERENCE.md` — the shipped-roster table gains a `gameboot_audio` row pointing at
  `sfx_launch.wav`, noting one sample now serves two slots (the same one-sample-many-events
  construction Navigation already uses).
- `docs/plans/README.md` C9 row — append what phase 4 gained.
- `ARCHITECTURE.md:191` and `CONTEXT.md:81` — the slot inventory sentence.
- `CHANGELOG.md`.

---

## Verification

Unit tests to add or extend (I will not run Gradle without being asked):

- `feature/feature-launcher/src/test/.../GameBootGateTest.kt` — one case per mode: `OFF` returns
  immediately and never emits on `active`; `SOUND_ONLY` returns immediately, never emits, and does
  call the audio player; `FULL` emits and suspends until `onPresentationFinished()`. Keep the
  existing timeout-proceeds case.
- New `core/core-data/src/test/.../GameBootPreferencesTest.kt` — the four migration rules, table
  driven: mode key wins; boolean true → `FULL`; boolean false → `OFF`; neither, `initial_setup_seen`
  true → `OFF`; neither and no marker → `FULL`.
- `core/core-ui/.../UiMediaDefaultsTest.kt` — `GAMEBOOT_AUDIO` resolves to `sfx_launch`,
  `GAMEBOOT_VIDEO` still null, and `resolveGameBootAudio`'s three branches.
- `feature/feature-settings/src/test/.../DisplaySettingsViewModel*Test.kt` — copy the Legibility
  test's shape: cycling persists `enum.name`, and a garbage persisted value falls back to `DEFAULT`.
- `LaunchDispatcherTest` — the existing "gate is awaited before startActivity" case must still pass
  unchanged; that is the regression guard for the seam.

Manual pass on device (you drive; tell me when a screen is up and I will look):

1. Fresh install (clear app data) → Display ▸ GameBoot reads **Full Sequence**.
2. Launch a game → black, bloom, sweep peaking with the sound swell, title, fade, then the emulator.
   Confirm or Back mid-sequence skips straight to the launch.
3. Cycle to **Sound Only** → the sound starts and the emulator opens at once, with no overlay.
4. Cycle to **Off** → no sequence, and the ordinary Launch Sound plays (and goes silent if Menu
   Sounds are muted — the mode-`OFF` case is the only one that respects mute).
5. Import a custom GameBoot sound → it replaces the bundled one in both Full and Sound Only.
   Reset GameBoot to Default → the bundled launch sound comes back, not silence.
6. Set Wave Style to Static → the sequence is a single static frame and noticeably shorter.
7. Upgrade path: install the pre-change build, leave GameBoot untouched, then install this build →
   the row still reads **Off**.
