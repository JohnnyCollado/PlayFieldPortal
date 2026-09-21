# Play Field Portal — Photo Viewer Touch Gestures & Helper: Implementation Plan

First of the per-section touch redesigns. Photo goes first; Music and Video follow in their
own passes and reuse the primitives this plan builds in `core-ui`.

> **Amendment (post-implementation).** Swipe-down-to-back and hold-for-Options were built, then
> cut. The photo viewer's touch vocabulary is now **tap to hide controls, swipe left/right to
> step, pinch/drag to zoom and pan**, with Options on the kebab and Back on the pill. The
> `LONG_PRESS` / `SWIPE_DOWN` glyphs and enum entries stay in `core-domain`/`core-ui` for the
> Music and Video passes; only the photo viewer's bindings are gone. Sections below are kept as
> written for history — read them against this note.

---

## 1. Goal

Teach gestures instead of stacking touch pills on top of a controller footer.

In touch mode the photo viewer should:

- Navigate by **swipe**, not by Prev/Next pills — those are deleted.
- **Replace** the controller helper bar with a touch helper bar drawn from the
  `assets/ui/touch_icons` glyph family. One input family on screen at a time, never both.
- Keep **Options** as a vertical kebab (⋮) button, and **Back** as the existing pill.
- Obey the same arbitration rule the D-pad already obeys: *zoomed = pan, at fit = navigate.*

What stays the same: the wallpaper flow, rotate/zoom/info/remove actions, the Options menu
contents, the controller path in every respect, and how `XMBShell` opens and closes the viewer.

---

## 2. What exists today

| Piece | Where | Keep / change |
|---|---|---|
| 7 touch glyph PNGs, unwired source art | `assets/ui/touch_icons/` | Copy the 5 used into `core-ui` res |
| `gen_touch_icons.py` + style contract | same folder | Keep; no pinch glyph is being added |
| `ControllerIcon` (physical controller positions) | `core-domain/.../model/ControllerIcon.kt` | **Do not extend** — see §3 |
| `ControllerIconGlyph`, per-family tables | `core-ui/.../components/ControllerButtonGlyph.kt` | Unchanged |
| `ControllerPromptGlyphs` / `ControllerPromptBar` | `core-ui/.../components/ControllerPrompt.kt` | Extract shared row; API unchanged |
| `XmbTouchButton` / `XmbGlyphTouchButton` (ring, **no background fill**) | `core-ui/.../components/XmbTouchButton.kt` | Add `background` param |
| `XmbHeaderPill` (already has `background`) | same file | Reuse for Back |
| `"⋯"` as the Options glyph | `XmbTouchButton.kt:207`, `StudioTouchControls.kt:203` | Convention moves to `"⋮"` |
| `Modifier.xmbNavGestures` — axis-lock, commit-on-release, pure helpers | `feature-xmb/.../ui/XmbNavGestures.kt` | **Model for the new detector** |
| `XmbNavGesturesTest` | `feature-xmb/src/test/.../ui/` | Model for the new test |
| `TouchSensitivity.stepScale` | `core-domain/.../model/TouchSensitivity.kt` | Reuse |
| `uiState.touchSensitivity` reaching `XMBShell:691` | `XMBShell.kt` | Pass to the viewer too |
| `PhotoViewerScreen` `.transformable()` + `.clickable()` | `feature-xmb/.../ui/photo/PhotoViewerScreen.kt` | Replace with one detector |
| `handleGamepadAction`'s `if (s.zoomed) pan(...) else step(...)` | `PhotoViewerViewModel.kt` | Keep — it is the rule touch adopts |
| `titleFlashVisible` auto-fade pattern | `PhotoViewerScreen.kt` | Model for the helper-bar flash |

### Known-good facts worth not rediscovering

- `detectTransformGestures` (what `transformable` uses) **already fires on a single pointer**.
  A one-finger drag calls `onGesture` today; it is invisible only because `clampPan`'s limit is
  `(zoom - 1f) * 1200f`, i.e. `0` at fit. Adding a second drag detector alongside `transformable`
  means two detectors competing for the same events — hence one unified `pointerInput`.
- `combinedClickable` beside a drag detector has the same conflict, so tap and long-press are
  folded into that same block rather than layered on.

---

## 3. Touch glyphs do not go through `ControllerIcon`

`ControllerIcon` is documented in `core-domain` as *"A physical position on a controller"*, and
`drawableForOrNull(family)` is keyed by `ControllerDisplayType`. A swipe has no family, so adding
`TOUCH_TAP` would make `TOUCH_TAP.drawableForOrNull(PLAYSTATION)` a call that looks meaningful and
is not. Touch gets a parallel type instead.

### 3.1 `core-domain` — `TouchGesture`

```kotlin
enum class TouchGesture(val label: String) {
    TAP("Tap"),
    LONG_PRESS("Hold"),
    SWIPE_LEFT("Swipe left"),
    SWIPE_RIGHT("Swipe right"),
    SWIPE_DOWN("Swipe down"),
}
```

- `label` is the text fallback and the TalkBack reading — the touch counterpart of
  `printedLabelFor`. Unlike the controller path there is no per-family variation, so it lives
  on the entry.
- **Only entries with shipped art exist.** `SWIPE_UP` is unassigned for now and `SWIPE_ANY`
  has no consumer, so neither is declared — an enum entry that cannot be drawn is a trap.
  Add them when a section claims them.

### 3.2 `core-ui` — glyph, bar, shared row

New `core-ui/.../components/TouchGestureGlyph.kt`:

- `TouchGesture.drawableFor(): Int` — one table, no family argument.
- `TouchGestureGlyph(gesture, size)` — mirrors `ControllerIconGlyph`, including
  `clearAndSetSemantics { }` since the paired label carries the meaning.
- `TouchPromptItem(gestures: List<TouchGesture>, label: String)` and `TouchPromptBar(...)`.

In `ControllerPrompt.kt`, extract the body of `ControllerPromptGlyphs` — the glyph row, label,
`spacing`, `glyphSpacing`, `labelStyle` — into a private `PromptRow(glyphs, label, ...)` that
both bars call. The two bars are then identical **by construction**, not by copy. The public
controller API does not change and nothing is renamed.

### 3.3 Resources

Copy into `core/core-ui/src/main/res/drawable-nodpi/`:

`ctl_touch_tap.png`, `ctl_touch_long_press.png`, `ctl_touch_swipe_left.png`,
`ctl_touch_swipe_right.png`, `ctl_touch_swipe_down.png`

`ctl_touch_swipe_up.png` and `ctl_touch_swipe_all.png` stay source-art-only — nothing references
them yet.

### 3.4 README amendment

`assets/ui/touch_icons/README.md` records the tap / `ctl_ps_face_east` collision as *"harmless
while the footer shows one input family at a time"*. Replacing the bar makes that an **enforced
invariant** rather than a coincidence. Update the "Known collision" section to say so, and note
that any future screen mixing both families owes the tap glyph a redesign first.

---

## 4. The gesture layer

New `feature-xmb/.../ui/photo/PhotoViewerGestures.kt`, modelled directly on `XmbNavGestures.kt`:
one `pointerInput`, axis-locked, commit-on-release, decision logic in pure functions.

```kotlin
fun Modifier.photoViewerGestures(
    zoomed: Boolean,
    stepScale: Float,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
    onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit,
): Modifier
```

Keyed on `pointerInput(zoomed, stepScale)` so crossing the zoom boundary or changing the
preference re-arms the detector.

### 4.1 Routing

| Pointers | `zoomed` | Behaviour |
|---|---|---|
| 2+ | any | `onTransform` — pinch zoom and pan, exactly as `transformable` does today |
| 1 | true | `onTransform(1f, dx, dy)` — pan only, no navigation |
| 1 | false | axis-locked navigation (below) |
| 1, no slop exceeded | any | release → `onTap` |

This mirrors `handleGamepadAction`'s `if (s.zoomed) pan(...) else step(...)` so one sentence
governs both input families.

### 4.2 Navigation axis, at fit only

- **Horizontal:** commit on release. One photo per gesture — a photo viewer does not scrub
  mid-drag the way the category bar does, so there is deliberately no live stepping and no
  `consumeWholeSteps` loop. Commits on distance **or** fling velocity; direction from the sign.
  Drag left → next (`+1`): content follows the finger, matching `xmbNavGestures`' documented
  direction convention.
- **Vertical:** a deliberate dead end. The axis still locks so a vertical drag cannot step
  photos, but neither direction commits anything.
- **Scaling:** `stepScale` multiplies the horizontal commit distance.

### 4.3 Pure helpers (unit-tested, no Compose)

```kotlin
fun photoStepFromSwipe(accumulatedX: Float, commitPx: Float, velocityPxPerS: Float, flingPx: Float): Int
```

`photoStepFromSwipe` returns `-1`, `0` or `+1` — never more, so a long drag cannot skip photos.

### 4.4 Tuning constants

Start from the XMB's numbers and adjust on device:

- `PHOTO_STEP_COMMIT_DP = 72.dp` (same as `SWIPE_BACK_COMMIT_DP` — a gesture that may start
  anywhere has to out-argue an idle finger)
- `FLING_DP_PER_S = 420f` (reuse the XMB value)

---

## 5. Screen changes — `PhotoViewerScreen.kt`

1. **Modifier chain:** drop `.transformable(transformState)` and `.clickable { }`; add
   `.photoViewerGestures(...)`. `rememberTransformableState` goes away.
2. **Bottom bar:** `if (showTouchControls) TouchPromptBar(...) else ControllerPromptBar(...)`.
   Touch items: `SWIPE_LEFT + SWIPE_RIGHT → "Prev / Next"`, `TAP → "Hide Controls"`.
3. **Delete** both Prev/Next `XmbHeaderPill`s and the `hasPrev` / `hasNext` block.
4. **Options** becomes a kebab in the top-right: `XmbGlyphTouchButton`-style frame with three
   drawn `Canvas` dots rather than the `"⋮"` character — U+22EE is a math glyph with uneven font
   coverage and vertical metrics, and at 22sp bold in a 52dp box it renders undersized beside the
   `"◀"` it sits across from. Pass `background = MediaPillBg`.
5. **Back pill** stays as-is — with no dismiss gesture it is the only touch way out.
6. **Discoverability flash:** the helper bar lives inside `if (state.controlsVisible)`, which
   defaults to `false`. With the Prev/Next pills gone, a first-time touch user opens to a bare
   image with no hint. Flash the touch bar on open and fade it, reusing the `titleFlashVisible`
   pattern already in this file. Teach once, then get out of the way.
7. **Wallpaper preview:** its `ControllerPromptBar` (Apply / Cancel) is redundant in touch mode —
   real `TextButton`s already say it. Hide the bar when `showTouchControls`.
8. **`XMBShell`:** pass `touchSensitivity = uiState.touchSensitivity.stepScale` to the viewer at
   the existing `showTouchControls = uiState.resolvedShowTouchButton` call site.

---

## 6. Kebab convention (app-wide)

`"⋮"` replaces `"⋯"` as the Options signal everywhere. Photo adopts it in this pass; the
following are **out of scope here** and get picked up by their own section passes:

- `StudioTouchControls.kt:203`
- the `XmbHeaderPill` preview at `XmbTouchButton.kt:207`

Record the decision in `ARCHITECTURE.md` (or alongside the touch-icon README) so later sections
do not drift back to `⋯`.

---

## 7. Phases

Each phase compiles and is independently reviewable.

### Phase 1 — Touch prompt primitive (`core-domain`, `core-ui`)
`TouchGesture`, `TouchGestureGlyph`, `TouchPromptItem`/`TouchPromptBar`, the extracted
`PromptRow`, the 5 drawables, the README amendment. No feature module touched.

Tests: every `TouchGesture` resolves to a non-zero drawable; the `PromptRow` extraction leaves
`ControllerPromptBar` behaviour unchanged (the existing controller tests must still pass
untouched).

### Phase 2 — Kebab button (`core-ui`)
`background` param on `XmbTouchButton` / `XmbGlyphTouchButton` (default `Color.Transparent`, so
no existing caller changes); drawn three-dot kebab composable; preview.

### Phase 3 — Gesture detector (`feature-xmb`)
`PhotoViewerGestures.kt` plus `PhotoViewerGesturesTest.kt`. **Write the pure-function tests
first** — they are the whole reason the logic is factored out.

Test cases:
- `photoStepFromSwipe` returns `0` below commit distance with no fling
- returns `-1` / `+1` by sign at exactly and beyond commit distance
- never returns a magnitude above 1, however far the drag
- a fast fling under the commit distance still commits; a slow drag under it does not

### Phase 4 — Screen wiring (`feature-xmb`)
All of §5, plus the `XMBShell` plumbing.

### Phase 5 — On-device pass
Tuning constants confirmed or adjusted on the Thor.

---

## 8. Commands

```bash
./gradlew :core:core-ui:testDebugUnitTest
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

```bash
./gradlew :core:core-domain:testDebugUnitTest :core:core-ui:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```

```bash
./gradlew :app:installFullDebug
```

---

## 9. On-device checklist (Thor)

- [ ] Open a photo by touch → helper bar flashes, then fades
- [ ] Swipe left / right steps photos; at the first and last photo it does not wrap or jitter
- [ ] Vertical swipes do nothing — they never step a photo and never close the viewer
- [ ] Tap toggles controls and does not fire during a swipe; the kebab opens Options
- [ ] Pinch to zoom, then one-finger drag pans and does **not** change photo
- [ ] Zoom back to fit → swipe navigates again (detector re-armed)
- [ ] Kebab is legible over a bright photo and over a dark one
- [ ] Press a controller button → touch bar swaps to the controller bar, pills disappear
- [ ] Controller path unchanged: L1/R1, D-pad pan when zoomed, Y opens Options
- [ ] Settings ▸ Display ▸ Touch Sensitivity changes swipe distance
- [ ] Wallpaper preview in touch mode shows Apply/Cancel buttons and no prompt bar

---

## 10. Open questions

1. **Swipe up** is parked. Candidates when it is claimed: View Information, or Set as Wallpaper.
2. **Pinch is untaught** — no glyph, no bar entry. Acceptable because pinch-to-zoom is near
   universal, but it means the bar does not name every gesture the viewer supports. Revisit if
   the same gap shows up in Music/Video.
3. **Vertical is unclaimed.** Swipe-down-to-close was cut, so both vertical directions are free.
   Anything claiming one owes the bar a glyph and a label.
4. **Does the helper bar belong in `DetailScaffold`'s footer** for Music/Video, or stay a
   free-floating bar as the photo viewer needs? Decide at the Music pass, not now.
