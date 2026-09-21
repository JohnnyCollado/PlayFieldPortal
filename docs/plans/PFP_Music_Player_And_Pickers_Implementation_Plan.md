# Play Field Portal — Music Player & Fullscreen Pickers: Implementation Plan

Third and last of the per-section touch passes. Photo went first
(`PFP_Photo_Viewer_Touch_Gestures_Implementation_Plan.md`), Video followed in commit `6a0fb21`.
Music reuses the `core-ui` primitives both of those built and adds nothing new to `core-ui`
except one extraction (§6).

---

## 1. Goal

Bring the three Music surfaces up to the treatment the video player and photo viewer now have,
and fix the stale "Now Playing" row.

| Surface | File | Today |
|---|---|---|
| In-app player | `feature-xmb/.../ui/MusicPlayerScreen.kt` (197 ln) | 4 hardcoded colours, M3 `Slider`, no touch/controller split, tap-anywhere-to-close |
| Fullscreen browser | `feature-xmb/.../ui/MusicBrowserScreen.kt` (298 ln) | Private `HeaderPill` copy, `"Options"` text pill (not the kebab), hardcoded colours, both input families' bars drawn at once |
| Track picker | `feature-xmb/.../ui/MusicTrackPicker.kt` (199 ln) | No `showTouchControls` at all — controller bar always, no touch affordance for Add/Cancel |

**Scope is parity, not new playback features.** No shuffle, no repeat, no visible queue, no
Media3 migration. `MusicPlayerController` keeps its `MediaPlayer` (the one behavioural change to
it is in §3, and it is a bug fix).

---

## 2. Bug: the "Now Playing" XMB row does not follow the song

### Root cause — `XMBViewModel.kt:1564-1580`

```kotlin
val hasTrack = playback.track != null
if (hasTrack != lastHadPlayingTrack) {          // ← presence only
    lastHadPlayingTrack = hasTrack
    ... refreshMusicRootPreservingCursor()
}
```

The Music root is rebuilt only when playback **gains or loses** a track. Track A → track B leaves
`hasTrack == true` on both sides, so the guard never fires and the `NOW_PLAYING_ITEM_ID` row built
at `XMBViewModel.kt:2283` keeps the previous title, artist and cover art.

The guard exists for a good reason: `MusicPlayerController.emit()` re-publishes `MusicPlaybackState`
every 500 ms from the position ticker, and rebuilding the root on every tick would thrash the row
list. So the fix is to widen the key, not to drop the guard.

It reproduces whenever the track changes without a Music-root rebuild happening for some other
reason: auto-advance on completion (`setOnCompletionListener { next() }`), ◀/▶ in the player
followed by B, and prev/next from the notification or a headset. Entering the Music category
re-runs `musicRootItems()` (`XMBViewModel.kt:2065-2068`), which is why backing all the way out and
in again appears to "fix" it.

### Fix

Key the guard on what the row actually renders, not on presence:

```kotlin
// XMBViewModel.kt:1394
private var lastNowPlayingKey: String? = null

// in the musicPlayer.state collector
val key = playback.track?.let { "${it.id}|${it.displayTitle}|${it.artist}|${it.artUri}" }
if (key != lastNowPlayingKey) {
    lastNowPlayingKey = key
    if (currentCategory()?.id == BuiltInCategory.MUSIC && _uiState.value.musicNav == MusicNav.Root) {
        refreshMusicRootPreservingCursor()
    }
}
```

`track.id` alone would cover the reported bug; the extra fields make the row follow a metadata
re-scan of the same file too, and they are all stable across the 500 ms ticks, so the ticker still
rebuilds nothing.

The key derivation goes in a **pure top-level function** (`nowPlayingRowKey(state: MusicPlaybackState): String?`)
so it is unit-testable without touching the ViewModel — the *pure logic leaves Android* convention
in `ARCHITECTURE.md`.

`refreshMusicRootPreservingCursor()` already re-anchors the cursor by row id, so a rebuild behind
the open player or browser causes no visible snap when the XMB is revealed. It also calls
`clearMusicTrackCache()`, which is correct on the root (the root has no track rows) and unchanged
by this fix.

### Same-cause item, in scope

`MusicPlaybackService.update()` is driven off the identical flow and rebuilds + re-`notify()`s the
whole notification **every 500 ms**. Gate it on the same key plus `isPlaying` plus a coarse
position bucket, so the notification updates when something user-visible changes rather than twice
a second.

---

## 3. `MusicPlayerScreen` — rebuild

Modelled on `VideoPlayerScreen`'s `ControlsOverlay` (`video/VideoPlayerScreen.kt:337+`): three
bands, same bands for both input families, only the contents change.

**Top band** — `XmbHeaderPill("Back", "◀")` left and `XmbKebabTouchButton` right, both with
`background = XmbMediaPillScrim`, both only when `showTouchControls`; centred track title with a
96dp side reserve while the pills are up.

**Middle band** — album art tile (or the framed `MusicNote` fallback), title/artist/album,
`index+1 / queueSize`, then the transport row (`SkipPrevious` · `Replay10` · play/pause ·
`Forward10` · `SkipNext`) **only under `showTouchControls`** — a pad drives all five from the
face buttons and D-pad, and the prompt bar names them.

**Bottom band** — scrubber + `position / duration`, and the `ControllerPromptBar` **only when
`!showTouchControls`** (one input family on screen at a time, per `ARCHITECTURE.md` §Conventions).

Specific changes:

1. **Drop the M3 `Slider`.** `onValueChange = { onSeekTo(it.toInt()) }` fires on every pixel of
   travel, and each call is a `MediaPlayer.seekTo` — the same decoder-stutter problem the video
   player solved with commit-on-release. Use the shared `Scrubber` from §6, with the video
   player's tap-to-seek + `detectHorizontalDragGestures` block: local `scrubFraction` overrides
   the reported position during the drag, `onDragEnd` commits once.
2. **Remove `.clickable(onClick = onBack)` on the root Box.** With a transport row and a
   draggable bar in the middle of the screen, tap-anywhere-to-close is a trap; Back is the pill
   and B. (Matches the video player, which has no tap-to-exit.)
3. **Colours from the theme.** `Backdrop`/`Primary`/`Secondary`/`Accent` go; text stays white over
   the dim, and the played portion of the bar uses `deriveStorefrontColors().chromeDivider` — the
   same derivation the video player uses, which (unlike `menuCursorEdge()`) actually tracks the
   colour the XMB picker wrote.
4. **`showTouchControls: Boolean` + `onTouchInput: () -> Unit` params**, wired in `XMBShell.kt:974`
   from `uiState.resolvedShowTouchButton` / `onTouchInput` exactly as the video player is at
   `XMBShell.kt:1171`.
5. **`formatTime` deleted** in favour of the shared helper (§6).

### Controller bindings — aligned with the video player

The player used ◀/▶ = prev/next and ▲/▼ = seek ±10 s (`XMBViewModel.kt:4687-4697`), the opposite
hand from the video player. It moves to the video ladder: **A** play/pause · **◀/▶** seek ∓10 s ·
**L1/R1** prev/next · **Y** options · **B** close. The prompt bar names the same ladder.

---

## 4. `MusicBrowserScreen` — touch pass

1. Delete the private `HeaderPill` (`MusicBrowserScreen.kt:198`) — it is a verbatim copy of
   `XmbHeaderPill`'s body. Use the shared one.
2. `"Options"` text pill → `XmbKebabTouchButton`, per the kebab convention in `ARCHITECTURE.md`.
   Sort stays a labelled `XmbHeaderPill` with a `"⇵"` leading glyph (the label carries the active
   sort, so it cannot collapse to a glyph).
3. Keep the `◀ + title + breadcrumb` header as the back affordance — that is the full-screen-menu
   convention shared with the detail menus, and it is visible in both input modes.
4. `PrimaryText` / `SecondaryText` / `CoverPlaceholder` → `LocalPFPColors`-derived values.
5. **One input family at a time**: under `showTouchControls`, swap the footer `ControllerPromptBar`
   for a `TouchPromptBar` naming tap = Open and long-press = Options (`combinedClickable` already
   binds both at `MusicBrowserScreen.kt:229`); keep the controller bar otherwise. Today both the
   touch pills and the controller bar are on screen together.
6. **Focus scrolling**: replace `animateScrollToItem(selectedIndex - 1)` with the
   `BringIntoViewRequester` treatment — the current "-1" is scroll math that guesses at framing and
   misbehaves at the ends of the list.

Out of scope, noted: the search field is typed-only, so a controller user cannot enter a query.
That is a pre-existing gap and stays one.

## 5. `MusicTrackPicker` — touch pass

Same five points as §4, plus:

1. **Add `showTouchControls` and plumb it** — the picker does not take the flag at all today
   (`XMBShell.kt:1125`), so controller glyphs are shown to a user holding no controller.
2. **Header actions for touch**: `XmbHeaderPill("Add N", "✓")` and `XmbHeaderPill("Cancel", "◀")`
   in the header row. The index-0 "Done / Add N track(s)" row stays for the controller — reaching
   it on touch currently means scrolling back to the top of a list of every scanned song.
3. Same `BringIntoViewRequester` fix for `animateScrollToItem(selectedIndex)`.
4. `PickerRow` already uses `menuCursorFill()`/`menuCursorEdge()` — unchanged.

---

## 6. Shared extraction (the only `core-ui`-adjacent work)

`Scrubber`, its drag/tap seek modifier and `fmt`/`formatTime` currently live private inside
`VideoPlayerScreen.kt`. Move them to one file — `feature-xmb/.../ui/media/MediaTransport.kt` —
and have both players import them. The pure parts (fraction ↔ ms conversion, clamping) go in as
top-level functions so they are testable.

No new `core-ui` components. `XmbHeaderPill`, `XmbKebabTouchButton`, `XmbMediaPillScrim`,
`TouchPromptBar` and `TouchGesture` all already exist and already have what these screens need.

---

## 7. Execution Task Index

One bounded task per helper. T1 is independent of the rest and can land first.

| # | Task | Files | Stop condition |
|---|---|---|---|
| **T1** | Now Playing row follows the song | `XMBViewModel.kt` (collector + new pure `nowPlayingRowKey`), `MusicPlaybackService.kt`, new `NowPlayingRowKeyTest` | Key change rebuilds the root; 500 ms ticks do not; test green |
| **T2** | Extract shared transport pieces | new `ui/media/MediaTransport.kt`, `VideoPlayerScreen.kt` | Video player behaves identically, no other screen touched |
| **T3** | Rebuild `MusicPlayerScreen` | `MusicPlayerScreen.kt`, `XMBShell.kt` (1 call site), `XMBViewModel.kt` (bindings, if Q1 = align) | Three bands; touch and controller modes each show only their own family |
| **T4** | `MusicBrowserScreen` touch pass | `MusicBrowserScreen.kt` | Shared pill + kebab, themed colours, one prompt bar at a time, BringIntoView focus |
| **T5** | `MusicTrackPicker` touch pass | `MusicTrackPicker.kt`, `XMBShell.kt` (1 call site) | `showTouchControls` honoured; Add/Cancel reachable without scrolling |
| **T6** | Docs + tests | `ARCHITECTURE.md`, `CHANGELOG.md`, tests from T1/T2 | Music named in the Conventions touch-pass note |

Verification for each task (no device build unless asked):

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

---

## 8. Decisions (answered before implementation)

1. **Q1 — player controller bindings → align with video.** A play/pause · ◀/▶ seek ∓10 s ·
   L1/R1 prev/next · Y options · B close.
2. **Q2 — tap-to-close on the player → removed.** Back is the pill and B, in both input modes.
3. **Q3 — browser back affordance → keep the breadcrumb.** The Music browser stays with the
   full-screen-menu convention rather than adopting the media viewers' Back pill.

---

## 9. As built

All six tasks landed. Three judgement calls differ from the plan above, each made while writing
the screen:

1. **The player's backdrop is the themed gradient, not a fixed near-black**, and its pills keep
   `XmbHeaderPill`'s default white wash rather than `XmbMediaPillScrim`. The scrim variant exists
   for pills floating over *arbitrary imagery* — a photo, a video frame. This player floats over
   the XMB's own wave, like the Music browser beside it, so it takes the browser's treatment.
2. **No track title in the player's top band.** The video player puts one there because the video
   itself carries no title. Here the record below already prints title, artist and album; a second
   copy 200dp above it reads as a bug rather than as chrome.
3. **The track picker no longer dismisses on a tap outside.** It gained an explicit Cancel pill,
   and on a list of every scanned song the only "outside" left is the margin — a stray tap there
   would have thrown away a selection built one song at a time. Same reasoning as the player's
   tap-to-close (§8 Q2), applied to the surface where the cost of a mis-tap is higher.

Not addressed, and still true: the browser's search field is typed-only, so a controller user
cannot enter a query. It was a pre-existing gap and stays one (§4).
