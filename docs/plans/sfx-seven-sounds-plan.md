# Seven customizable sounds — implementation plan

> **Deviation (owner decision, 2026-09-07):** the bundled Boot Sound is now
> `sfx_opening.mp3` — Sony's `snd_opening` firmware rip, byte-identical to the
> `official/` reference (xcorr 1.000) — not the authored `sfx_opening.wav` this
> plan's Phase 0 ships. It was accepted deliberately after the admission result
> was shown. Do not "re-fix" it back without the owner; the authored WAV
> remains in git history if the decision is revisited. All other sections of
> this plan stand as written.

Handoff for a helper. Written 2026-09-07. Read
[`sfx-decoupling-context.md`](sfx-decoupling-context.md) first — it explains why the
current `res/raw` set must go and how the replacement samples were authored. This
document is the *code* half of that work.

## Goal

Seven sounds, no more and no fewer. Each one:

1. ships an **original** bundled default (from `assets/SFX/active/`),
2. is **customizable** by the user from **Settings ▸ Interface ▸ Sound**, and
3. **actually fires** somewhere in the launcher.

Today none of the three is fully true: four of the shipped defaults are Sony-derived,
one slot (Boot) has no bundled-default mechanism at all, and three events
(`CONFIRM`, `ERROR`, `NOTIFICATION`) have zero call sites.

## The roster

| # | Row label | Default sample (`assets/SFX/active/`) | Storage slot | Fires on |
|---|---|---|---|---|
| 1 | Navigation | `sfx_cursor.wav` | `sound_scroll` | `SCROLL`, `SELECT`, `SYSTEM_BROWSE` |
| 2 | Back / Cancel | `sfx_back.wav` | `sound_back` | `BACK` |
| 3 | Confirm / Apply | `sfx_confirm.wav` | `sound_confirm` | `CONFIRM` |
| 4 | Error / Invalid | `sfx_error.wav` | `sound_error` | `ERROR` |
| 5 | App Launch | `sfx_launch.wav` | `sound_launch` | `LAUNCH` |
| 6 | Notification | `sfx_notification.wav` | `sound_notification` | `NOTIFICATION` |
| 7 | Boot | `sfx_opening.wav` | `boot_audio` | Boot Sequence overlay |

Row 1 collapses three of today's eight `SOUND` slots into one. That is deliberate and
follows the decision already recorded in the context doc: `sfx_cursor` covers all three
events, so three rows that all read "PFP Default" and all play the same sample is a
customization surface pretending to be three choices. One row, one sample, one label.

Row 7 moves Boot Sound onto this screen. It stays reachable from Display ▸ Boot
Sequence as well — same slot, two entry points, which is fine.

## Current state — what already works

Do not rebuild any of this:

- [`UiMediaStore`](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/UiMediaStore.kt)
  — staged import, gate, path-escape guard, stamp-bump. Complete.
- [`UiMediaLimits`](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/UiMediaLimits.kt)
  — per-slot caps and rejection messages. Complete.
- [`MenuSoundPlayer`](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/sound/MenuSoundPlayer.kt)
  — custom-over-default resolution, stamp reload, mute flag. **The only file that knows
  a menu sound can come from anywhere but `R.raw`.** Keep it that way.
- [`AudioSettingsScreen`](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/AudioSettingsScreen.kt)
  — per-row pick / preview / use-default, reset-all, rejection dialog. Complete, and it
  auto-lists whatever `AudioSettingsViewModel.SOUND_SLOTS` contains.

---

## Phase 0 — Ship the original samples

This is the phase that removes Sony-derived audio from the build. Do it first and alone.

**Start by reconciling the working tree.** `git status` currently shows staged renames
inside `core/core-ui/src/main/res/raw/` (`sfx_back.wav` → `sfx_error.wav`,
`sfx_favorite.wav` → `sfx_notification.wav`, `sfx_quicksysselect.wav` deleted) from the
previous session. Confirm what is actually on disk before copying anything over it.

1. Copy from `assets/SFX/active/` into `core/core-ui/src/main/res/raw/`, keeping the
   `active/` names verbatim: `sfx_cursor.wav`, `sfx_back.wav`, `sfx_confirm.wav`,
   `sfx_error.wav`, `sfx_launch.wav`, `sfx_notification.wav`, `sfx_opening.wav`.
2. `git rm` `sfx_scroll.wav`, `sfx_select.wav`, `sfx_systembrowse.wav`. After this,
   **every file in `res/raw/` must have come from `active/`** — that is the check.
3. Re-run the admission test from the context doc (§ "The admission test") with
   `res/raw/` as the candidate set against `alt/`, `official/`, `sony_firmware/` and
   `current/`. Nothing may score ≥ 0.30. Record the numbers in
   `assets/SFX/active/README.md`.

Note that replacing the files does not remove the old ones from git history. That is a
separate decision and out of scope here.

## Phase 1 — Collapse the roster to seven

### `UiMediaSlot.kt`

- Delete `SOUND_SYSTEM_BROWSE` and `SOUND_SELECT`.
- `SOUND_SCROLL` keeps its key `"sound_scroll"` (no migration for the survivor) and its
  display name becomes `"Navigation"`.
- Reorder the remaining `SOUND` constants to the table's order — the settings screen
  iterates enum order.

### Orphaned files from the removed slots

A user on the current build may have `ui-media/sound_select.*` and
`ui-media/sound_systembrowse.*` on disk, plus their `displayName` prefs keys.
`assignments()` already drops unknown keys (`fromKey` returns null), so they are inert —
but they leak disk and stale prefs, and a restored *old* backup re-creates them
(`BackupManager.kt:520` iterates `UiMediaSlot.entries`, which will no longer include
them, but the archive still carries the files).

Add a `pruneOrphans()` to `UiMediaStore`: delete any file in `ui-media/` whose
`nameWithoutExtension` fails `UiMediaSlot.isValidKey`, and drop the matching
`displayNameKey` prefs entries. Call it once at startup **and** after a backup restore.

**Do not migrate `sound_select` → `sound_scroll`.** A user who assigned three different
sounds to three now-merged rows has no single correct answer; the merged row keeps
whatever `sound_scroll` already had, and the rest are dropped. Silent is right here —
this affects only users who customized a pre-release build.

### `MenuSoundPlayer.kt`

- `MenuSound.slot`: `SCROLL`, `SELECT` and `SYSTEM_BROWSE` all return
  `UiMediaSlot.SOUND_SCROLL`. Leave the `MenuSound` enum itself alone — 75+ call sites
  distinguish these events, and the fact that they currently resolve to one sample is a
  default, not a law.
- `loadDefault`: `SCROLL` / `SELECT` / `SYSTEM_BROWSE` → `R.raw.sfx_cursor`;
  `BACK` → `R.raw.sfx_back`. **This kills the documented placeholder at line 135**
  where `BACK` borrows the error sample — delete that comment block with it.
- `reload()` currently iterates `MenuSound.entries` and calls `pool.load(path, 1)` per
  event. With three events on one slot it will load the same file into SoundPool three
  times. Re-key `customIds` by `UiMediaSlot` (load once per distinct slot, look up
  `sound.slot` in `play`). Small change, worth doing while you are in here.

### Tests touched

`UiMediaStoreTest` references `SOUND_SELECT` at lines 112, 142, 145, 148, 149 — swap for
another surviving slot. `UiMediaLimitsTest`'s drift pin (every slot has a spec) stays
true after the removals.

## Phase 2 — Boot Sound as the seventh row

Three problems, all small, but they interact — read the whole phase before starting.

### 2a. Bundled default for an `AUDIO_TRACK` slot

`AUDIO_TRACK` has no bundled-default mechanism: `bootAudioPath == null` means silence
today. The cheap fix is that `OneShotAudioLayer` takes a `String` path and ExoPlayer
resolves `android.resource://<pkg>/raw/sfx_opening`.

Add to core-ui:

```kotlin
/** The bundled default for slots that have one, as a URI string ExoPlayer can open. */
fun UiMediaSlot.bundledDefaultUri(context: Context): String?
```

returning the resource URI for `BOOT_AUDIO` and null for everything else. Resist adding
a general default-asset registry — one slot needs this.

Then `XMBViewModel.kt:8059`:
`pathFor(BOOT_AUDIO) ?: BOOT_AUDIO.bundledDefaultUri(context)`.

### 2b. The `muted` interaction — do not miss this

`BootSequenceOverlay.kt:136` passes `muted = bootAudioPath != null` to the video layer,
on the reasoning that a custom boot sound replaces a custom clip's own audio track.
Once `bootAudioPath` is never null, **every custom boot video gets silently muted** and
plays under the PFP opening chime.

The right behavior: a custom boot video keeps its own audio unless the user explicitly
assigned a boot sound. Simplest resolution is at the source — in `XMBViewModel`, fall
back to the bundled default **only when there is no custom boot video**:

```
audio = pathFor(BOOT_AUDIO) ?: if (video == null) BOOT_AUDIO.bundledDefaultUri(ctx) else null
```

Whichever way you take it, update the `BootSequenceOverlay` KDoc at line 71 — the
"`bootAudioPath` null means silence" sentence becomes wrong. The "no bundled boot .mp4
and none should be added" half stays true; the video has no default and should not get
one.

### 2c. The Sound screen

- `AudioSettingsViewModel.SOUND_SLOTS` = `ofKind(SOUND) + BOOT_AUDIO`.
- `assignedSlots` (viewmodel, ~line 85) filters `kind == UiMediaKind.SOUND` — include
  `BOOT_AUDIO` or the "Use Default" action never appears on that row.
- `preview(slot)` maps slot → `MenuSound` and plays through SoundPool. Boot has no
  `MenuSound` and is a 3-second ExoPlayer track. **Omit the Preview action on the Boot
  row for this pass** — Display ▸ Boot Sequence already has a full boot preview
  (`onPreviewBootSequence`). Make the row's `actions` list conditional rather than
  building a second preview player.
- `confirmReset()` calls `store.clearAll(UiMediaKind.SOUND)`. Boot Sound is now a row on
  this screen, so reset must clear it too — but must still never touch `BOOT_VIDEO`.
  This contradicts `UiMediaStoreTest:206` ("reset audio must never touch Boot media")
  and the reset dialog's copy. Change all three deliberately: reset clears the seven
  sounds including `boot_audio`, and leaves `boot_video`, `gameboot_video` and
  `gameboot_audio` alone.

## Phase 3 — Rename Audio → Sound

- `XMBViewModel.kt:427` — `title = "Sound"`, subtitle `"Menu & boot sounds"`.
- **Keep the id `settings_audio`.** The route, `SETTINGS_SCREEN_ROUTES`, the row focus
  keys (`audio_${slot.key}`) and `SettingsHierarchyTest:145` all key off it; renaming
  buys nothing and breaks cursor restore.
- `AudioSettingsScreen` — scaffold subtitle `"Audio"` → `"Sound"`; group headers stay
  ("Menu Sounds", "Sound Assignments").
- `SettingsHierarchyTest:145` — assertion message string only.

## Phase 4 — Wire the three dead events

`CONFIRM`, `ERROR` and `NOTIFICATION` have exactly one reference each today, all inside
`MenuSoundPlayer`. A customizable sound nobody can hear is not customizable.

**The rule that must hold:** every call site is `menuSound.play(MenuSound.X)`. No
feature screen learns about custom-vs-default — that decision lives in `MenuSoundPlayer`
and nowhere else.

- **`CONFIRM`** — a committed action, as distinct from `SELECT`'s navigation.
  `confirmButton` lambdas in settings `AlertDialog`s, and Save / Add / Apply in the app
  picker, the custom icon picker, and the game picker in game categories. Watch for
  double-fire: a confirm that also navigates must not play `CONFIRM` and then `SELECT`.
- **`ERROR`** — a refused action. `LaunchDispatcher` failure paths; the `!result.ok`
  branch in `AudioSettingsViewModel.onSoundPicked` and its `DisplaySettingsViewModel`
  equivalents (a rejected import already surfaces a dialog — give it a sound); an
  invalid pick.
- **`NOTIFICATION`** — a background task finished. Library rescan complete, artwork
  scrape complete, backup complete, achievement sync complete. Fire it on completion,
  not on progress.

Audit the list before implementing — these are the intended homes, not an exhaustive
survey, and you are closer to the code than this document.

## Phase 5 — Tests and docs

- Update `UiMediaStoreTest` (Phase 1 slot refs, Phase 2c reset assertion) and
  `SettingsHierarchyTest` (Phase 3 string).
- New drift test worth adding: every `MenuSound` resolves to a slot that still exists,
  and every slot the Sound screen lists has a bundled default. That pins the exact
  invariant this work establishes.
- `docs/plans/ui-audio-boot-gameboot-plan.md:243` still says "Confirm / Select" — stale
  since the `CONFIRM` slot landed.
- `assets/SFX/active/README.md` and `REFERENCE.md` — record the final `res/raw` mapping
  and the Phase 0 correlation numbers.

## Manual verification (on device, when the user asks for a build)

- Each of the seven rows: pick a file, preview it, Use Default, then Reset All.
- Boot with nothing custom → hears `sfx_opening`.
- Boot with a custom video and no custom boot sound → the clip's own audio, not doubled
  and not muted.
- Boot with a custom video *and* a custom boot sound → the custom sound, clip muted.
- Menu Sounds off → silence everywhere except the Sound screen's Preview button.
- Navigate, back out, confirm a dialog, trigger a rejected import, launch a game — five
  distinct sounds.

## Invariants — do not change these

- Storage key `sound_scroll` survives the rename to "Navigation". No migration.
- SoundPool for the short cues; ExoPlayer for boot. Do not unify them.
- `MenuSoundPlayer` stays the only file that knows about custom samples.
- No bundled boot **video**, ever.
- Reset on the Sound screen never touches GameBoot or the boot video.

## Process notes for the helper

- **Do not run Gradle builds** unless the user explicitly asks. Diagnose from the files.
- **Do not commit.** Stage the changes and propose a message; the user triggers every
  commit.
- The user's Android Studio checkout may differ from your worktree — check
  `git worktree list` before assuming file state.
