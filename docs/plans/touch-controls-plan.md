# Touch controls — dead scroll zones, and backing out with LEFT

> Implementation handoff, approved 2026-09-09; all seven tasks implemented 2026-09-09, pending the
> owner's on-device pass and a build. Indexed as `C15` in
> [the plan index](README.md). Work the Execution Task Index in dependency order, one bounded task
> per helper.

## Context

Two touch/controller complaints on the same surfaces (Settings screens and the Setup Wizard):

1. **Dragging in certain places does not scroll.** Headers are the reported case. The layout
   confirms it: every settings screen and every wizard page draws its header, its divider and its
   footer *outside* the scrolling column, so a drag that starts there has nothing to scroll.
2. **There is no cheap way back out of a flyout/drill-in.** Back is a button press (or, on the XMB
   home screen only, a left-edge pull). The user wants D-pad **LEFT** and a **leftward swipe** to
   mean "back out" — the direction the XMB's own drill-in metaphor already implies.

Both are input-model gaps, not rendering bugs, and both land in code that already exists: one
scroll-owner registry (`LocalSettingsScrollStateRegistrar`), one gesture layer
(`Modifier.xmbNavGestures`), one canonical drill-out (`XMBViewModel.onHomeBack`), and one action
vocabulary (`GamepadAction`).

## Problem

### Problem 1 — drag-dead zones

`SettingsScaffold` (`feature/feature-settings/.../ui/SettingsScaffold.kt:739-872`) lays out:

```
Box(root)                              ← .pointerInput consumes every moved change (690-707)
└ Column(fillMaxSize)
  ├ header() / ◀ breadcrumb Row        (743-793)   ← OUTSIDE the scroll
  ├ HorizontalDivider                  (795)       ← OUTSIDE
  ├ 0dp focus-bootstrap Box            (801)
  ├ Box(weight(1f)) { content() }                  ← the verticalScroll lives INSIDE content()
  └ footer() / SettingsHelperFooter    (859-871)   ← OUTSIDE
```

`WizardScaffold` (`.../ui/wizard/WizardScaffold.kt:86-121`) is the same shape with the PSP skin:
`WizardHeader` and `WizardFooter` are scaffold chrome; only `WizardHeading` + the page body sit
inside `verticalScroll(scrollState)` (line 111).

The same header-outside-the-list shape repeats in `AppDrawerScreen`, `StorefrontAppDrawer`,
`AppPickerScreen`, `GamePickerScreen`, `MusicBrowserScreen`, `ShibaLibraryScreen`, and the three
overlay menus (`DetailContextMenu`, `PspContextMenu`, `ColorSchemePickerOverlay`). Two screens
already get it right and are the local precedent: `ShibaCoinsScreen.kt:127` puts the header in an
`item {}` of its `LazyColumn`, and `GameDetailScreen.kt:263` puts `DetailBreadcrumb` inside the
scrolling `Column`.

Aggravating factor: the scaffold's root `Box` runs a `while(true) { awaitPointerEvent() }` loop
that calls `change.consume()` on **every** moved pointer change (`SettingsScaffold.kt:700-704`).
It only wants to know "touch was used". The repo already has a documented non-consuming form of
exactly that probe (`GameDetailScreen.kt:226`, `VideoDetailScreen.kt:153`, `AppDetailScreen.kt:156`).

There is **no** `Modifier.nestedScroll`, `Modifier.scrollable`, or `Modifier.draggable` anywhere in
the repository, so there is nothing today that could forward a drag from chrome into content.

### Problem 2 — no LEFT / swipe-left back-out

- **XMB, D-pad:** `XMBViewModel.kt:4752-4759` — `NAVIGATE_LEFT` while `isInSubItem` is an explicit
  hard no-op (`cancelRepeat(); return`). The direction is free.
- **XMB, touch:** `xmbNavGestures` maps a horizontal drag to `onStepCategory`, which routes to
  `stepCategory()` (`XMBViewModel.kt:6282-6291`) — also an early `return` while `isInSubItem`. So a
  leftward drag inside a flyout does nothing today either.
- **Settings/Wizard, D-pad:** `SettingsScaffold.kt:617-619` — `NAVIGATE_LEFT` calls
  `navigationState.moveHorizontal(-1)`, which returns `null` when the focused row has no inline
  children (`NavigationContext.kt:180-185`). The `?.let` swallows that null: LEFT on an ordinary
  row is a silent no-op.
- **Settings/Wizard, touch:** no gesture layer at all.

## Root cause

1. Scroll ownership is **per content body**, while the chrome that frames it belongs to the
   scaffold. Nothing connects the two — even though the scaffold *already holds the content's
   `ScrollState`* in `contentScrollState` (`SettingsScaffold.kt:299`, fed by
   `LocalSettingsScrollStateRegistrar` at 142/686, which all ~22 settings screens and the wizard
   call). The wiring exists for controller keep-in-view; touch was never given the same reach.
2. "Back out one level" is bound to exactly one input (BACK), plus a single hard-coded left-edge
   band on one screen. The abstraction to bind it more widely (`GamepadAction`, `onHomeBack`)
   exists; the bindings do not.

## Goals

- A drag anywhere on a Settings or Wizard surface scrolls that screen's content — headers, footers,
  gutters, and the empty area below the last row included.
- D-pad **LEFT** backs out of a flyout / drill-in / wizard step **when LEFT is not already doing
  something on the focused element**, behind a user-facing on/off setting.
- A **leftward drag** backs out of an XMB flyout / drill-in in touch mode.
- The existing left-edge pull, category stepping, slider adjust, and inline row actions keep
  working unchanged.

## Non-goals

- No free Android scrolling on the XMB home screen. `XMBItemList`/`XMBCategoryBar` keep
  `userScrollEnabled = false`; the XMB stays a discrete cursor.
- No `nestedScroll` architecture, no scroll-behaviour framework, no collapsing toolbars.
- Not restructuring the ~22 settings screens to move their headers inside their scroll bodies.
- No change to the controller mapping table (`gamepadMappingsFor`) — LEFT keeps meaning
  `NAVIGATE_LEFT`; only what an unused `NAVIGATE_LEFT` *falls through to* changes.

## Existing systems to reuse

| System | Path | Why |
|---|---|---|
| `LocalSettingsScrollStateRegistrar` / `contentScrollState` | `SettingsScaffold.kt:142, 299, 686` | The scaffold already holds the live content `ScrollState` for every screen and the wizard. Phase 1 forwards drags into it — no new plumbing. |
| Non-consuming touch probe | `GameDetailScreen.kt:226` (also `VideoDetailScreen.kt:153`, `AppDetailScreen.kt:156`) | The documented "notice touch without consuming" form the scaffold's root loop should have used. |
| `Modifier.xmbNavGestures` | `feature-xmb/.../ui/XmbNavGestures.kt` | Axis-locked single-pointer detector with a commit-on-release branch (`fromEdge`) that swipe-back mirrors exactly. |
| The drill-out ladder | `XMBViewModel.kt:4768-4786` (gamepad BACK) **and** `:6387-6406` (`onHomeBack`, touch) | The one-level-up cascade — currently written out twice, identically. Its when-branches are the exact complement of `XMBUiState.isInSubItem` (`:787-795`). |
| `GamepadInputHandler` | `feature-xmb/.../gamepad/GamepadInputHandler.kt` | The single input entry point: `MainActivity.dispatchKeyEvent:217` → keycode → `GamepadAction` → one `SharedFlow`. There is no Compose key handling and no `BackHandler` anywhere, so every binding decision lands in `dispatchGamepadAction`. |
| `NavigationContext.moveHorizontal` | `core-navigation/.../NavigationContext.kt:180-185` | Returns `null` precisely when LEFT is unused on the focused row — the signal the fallthrough needs. |
| `ControllerLayoutRepository` / `ControllerLayoutPrefs` | `core-data/.../ControllerLayoutRepository.kt` | The pattern for a new controller preference: key, default, setter, `resetAllPrefs`. |
| `TouchSensitivity` | `core-domain/.../TouchSensitivity.kt` | `stepScale` already tunes gesture distances; the swipe-back commit threshold reuses it or is deliberately exempted (as edge-Back already is). |
| `BackupManager` pref allowlist | `feature-backup/.../BackupManager.kt:521-526` | Every controller pref rides backup; the new one must join the list. |

## Architectural decisions

**D1 — One shared `Modifier.dragToScroll(ScrollableState)` in `core:core-ui`.**
New file `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/gesture/DragToScroll.kt`, built
on `Modifier.scrollable(state, Orientation.Vertical, reverseDirection = true)` from
`compose.foundation`. Taking `ScrollableState` (not `ScrollState`) means the one modifier serves
`ScrollState`, `LazyListState` and `LazyGridState`, so the later phases can reach the drawer/picker
grids without a second implementation. No new dependency — `scrollable` ships in the foundation
artifact the project already uses. The repo has zero prior `scrollable` usage, so the modifier
carries a KDoc explaining why it exists and why it is not `nestedScroll`.

**D2 — Fix the scaffold's root pointer loop to stop consuming.**
`SettingsScaffold.kt:690-707` becomes the documented `awaitEachGesture { awaitFirstDown(
requireUnconsumed = false); … }` probe. It keeps setting `cursorVisible=false` / `touchScrolled` /
`markTouchInput` / `notifyTouchInput`, but no longer consumes motion. Without this, modifier
ordering around the new `dragToScroll` becomes load-bearing and fragile.

**D3 — Chrome drags scroll the content; content keeps its own scroll.**
`dragToScroll` is attached to the scaffold's header slot and footer slot, keyed on
`contentScrollState.value`. Those are **siblings** of the scrolling body, which is what makes
sharing its `ScrollState` safe.

> **Corrected during implementation.** This decision originally also attached the modifier to the
> content `Box`, on the reasoning that the body's own `verticalScroll` would win the drag child-first
> on the Main pass. That reasoning was wrong: the content `Box` is an *ancestor* of the body, and
> `Modifier.scrollable` takes part in nested scrolling, so it became a second owner of the same
> `ScrollState`. The two then contend for that state's `MutatorMutex`, and the owner reported it on
> device: swipe to scroll, then tap-and-drag while it is still flinging, and the second drag is
> swallowed instead of catching the fling. Instrumentation confirmed it — 92 `scrollBy` events
> attributed to the content band that should have been zero. The content attachment is removed; it
> was never load-bearing, since the body fills that box. The constraint (*attach to siblings, never
> to an ancestor of the scroll container*) is recorded in `DragToScroll`'s KDoc.

**D4 — `LEFT = back` is a fallthrough, never an override.**
Two insertion points, both after every existing LEFT consumer:
- `SettingsScaffold.kt:617` — `moveHorizontal(-1)` returning `null` (no inline children) falls
  through to `onBack()`. Slider adjust mode (`:570-586`) and `onInterceptAction` (`:554`) both run
  *earlier* and already consume LEFT, so sliders, Themes and Sound are untouched.
- `XMBViewModel.kt:4752` — the `isInSubItem` early-return becomes the drill-out call. At the
  category root LEFT still steps the bar.

**D5 — One new preference, `controller_left_backs_out`, default ON.**
Lives in `ControllerLayoutRepository` beside `KEY_SCROLL_SPEED`, surfaced as a row on
Settings ▸ Controller, cleared by `resetAllPrefs()`, and added to the `BackupManager` allowlist.
Default ON because this is a requested feature, and every LEFT it claims is a documented no-op
today. It gates **only** the D-pad behaviour (D4), not the touch swipe.

**D6 — Swipe-back mirrors the existing edge-Back branch, it does not add a second detector.**
`xmbNavGestures` gains `swipeBackEnabled: Boolean` and `onSwipeBack: () -> Unit`. When enabled
(caller passes `uiState.isInSubItem`), a non-edge horizontal drag suppresses live category
stepping — exactly as `fromEdge` already does at `XmbNavGestures.kt:90` — and commits
`onSwipeBack()` on release past a leftward threshold. `consumeWholeSteps` / `flingBonusSteps` stay
untouched; the vertical axis is untouched.

**D7 — Extract the drill-out ladder once, and have all three callers use it.**
The twelve-branch cascade is currently written out **twice** — `dispatchGamepadAction`'s home BACK
branch (`XMBViewModel.kt:4768-4786`) and `onHomeBack()` (`:6387-6406`) — and a third caller
(D-pad LEFT) is about to want it. Extract `backOutOfDrill(): Boolean`, returning whether it
actually unwound a level. Then:
- gamepad BACK = `backOutOfDrill()` else `onOpenAppDrawer()` (unchanged behaviour),
- `onHomeBack()` = `markTouchInput()` + the same (unchanged behaviour),
- D-pad LEFT = `backOutOfDrill()` only, guarded by `isInSubItem`, with **no** App Drawer fallback
  and **no** `markTouchInput()` — marking touch would flip the contextual App Drawer button on a
  controller press.

De-duplicating is the point, not a side effect: three callers of one hand-written precedence list
is exactly where the two copies would drift. The extraction is behaviour-preserving and is pinned
by the `isInSubItem` invariant test.

## Rejected alternatives

- **Move every header inside its scroll container.** ~22 settings screens plus the wizard, and it
  breaks the sticky breadcrumb, the focus-bootstrap element's position, and the wizard's
  `contentKey` reset-to-top. Worse behaviour for far more churn.
- **`Modifier.nestedScroll` with a custom connection.** The chrome is a *sibling* of the scroll
  container, not an ancestor of a nested one; nested scroll solves a problem this layout does not
  have, and would be the repo's first nested-scroll plumbing.
- **Make LEFT unconditionally Back inside any drill-in.** Breaks slider adjustment and every
  inline row action reached horizontally. Rejected in favour of D4's fallthrough.
- **A separate `Modifier.swipeBack` layer on the XMB.** Two pointer detectors on one surface race
  for the same drag. D6 extends the single existing detector instead.

## Data / persistence / compatibility

One new DataStore boolean, `controller_left_backs_out`. Absent key reads as the default (ON) —
no migration. It joins `BackupManager`'s controller-pref allowlist so it survives backup/restore,
and `ControllerLayoutRepository.resetAllPrefs()` removes it. No Room schema change, no new
dependency, no change to the persisted controller mapping JSON.

## Implementation phases

**Phase 0 — Audit.** Enumerate every drag-dead zone across Screens and the Setup Wizard, on
device, and write the findings into the plan. The fix scope for Phase 1 is decided from that list.

**Phase 1 — Drag-to-scroll.** The shared modifier, the non-consuming probe fix, and the
scaffold/wizard wiring.

**Phase 2 — XMB back-out.** `backOutOfDrill()` extraction, D-pad LEFT, and the swipe-back branch
in `xmbNavGestures`.

**Phase 3 — Settings/Wizard LEFT + the preference.** The `moveHorizontal` fallthrough, the new
pref end-to-end, and the docs/README touch-controls table.

## Discovered, deliberately out of scope

Recorded as follow-ups, not implemented (workflow §5):

- **The same header-outside-the-list shape** in `AppDrawerScreen`, `StorefrontAppDrawer`,
  `AppPickerScreen`, `GamePickerScreen`, `MusicBrowserScreen`, `ShibaLibraryScreen`, and the three
  overlay menus (`DetailContextMenu`, `PspContextMenu`, `ColorSchemePickerOverlay`). `dragToScroll`
  takes `ScrollableState` (D1) specifically so these are a wiring job later, not a rewrite. Task 0.1
  may promote one of these if the owner hits it, but the default is: not now.
- **`GamepadInputHandler.bypassToComposeFocus`** (`:138`) is never assigned in production — a dead
  flag set only by its own test. Leave it; do not build on it.
- **Context-menu "submenus" have no back stack** — opening one replaces `activeContextMenu`
  wholesale, so BACK dismisses the entire menu instead of returning to the parent. Real, unrelated.
- **Dead imports** of `detectTapGestures` / `pointerInput` in `ThemesSettingsScreen.kt:47,55`.

## Audit record (Task 0.1) — where a drag is dead

Source-derived, 2026-09-09. Every Settings surface routes through `SettingsScaffold`; the Setup
Wizard routes through `WizardScaffold`, which is itself a `SettingsScaffold` with the PSP chrome in
the `header`/`footer` slots. `SettingsScaffold` is not used outside `feature-settings` (only its two
test files), so the two scaffolds really are the whole Phase 1 surface.

### Finding A — the dead zones are structural, not consumed

The scaffold's root `Box` pointer loop (`SettingsScaffold.kt:690-707`) reads `awaitPointerEvent()`
with the default `PointerEventPass.Main`, and Main dispatches **child-first**. Every scrolling body
therefore sees and consumes the drag before the root loop ever gets it, so today's blanket
`change.consume()` is **not** the cause of any dead zone. It is still worth removing per D2 — once
`dragToScroll` is attached to sibling chrome, "who consumed what on which pass" stops being
academic — but Task 1.1 should not expect removing it to change any observed behaviour, and Task
1.2's fix list is decided entirely by Finding B.

Every reported dead zone is structural: chrome laid out as a **sibling** of the scroll container.

### Finding B — chrome outside the scroll container

Per surface, the chrome outside the scroll owner is identical, because it is all the same scaffold:

| Region | `SettingsScaffold.kt` | Dead today |
|---|---|---|
| Header (breadcrumb `Row`, or the injected `header()` slot) | 743-793 | yes |
| `HorizontalDivider` | 795 | yes |
| 0dp focus-bootstrap `Box` | 801 | yes (0dp — no drag area in practice) |
| Content `Box(weight(1f))` **not** covered by the body | 807-857 | yes — see Finding C |
| Footer (`SettingsHelperFooter`, or the injected `footer()` slot) | 859-871 | yes |

Side gutters are **not** dead: every screen body is a `Column(Modifier.fillMaxSize()
.verticalScroll(...))`, so it fills the content `Box` edge to edge and the row padding lives inside
the scrollable. A drag in the gutter already scrolls.

### Finding C — screens whose scroll state is never registered

`dragToScroll` will be keyed on `contentScrollState`, which is only set by
`LocalSettingsScrollStateRegistrar`. These bodies own a `rememberScrollState()` that they never
register, so on them the scaffold holds **null** and chrome drags would stay dead even after 1.2 —
and controller keep-in-view is already degraded there for the same reason:

| Screen / sub-screen | Line |
|---|---|
| `CreditsSettingsScreen` (the whole screen) | `:48` |
| `EmulatorsSettingsScreen` ▸ `WizardPickAppStep` | `:284` |
| `EmulatorsSettingsScreen` ▸ `TestLaunchFlow` (pick ROM / result) | `:315`, `:337` |
| `EmulatorsSettingsScreen` ▸ "Detecting…" | `:109` (no scroll container at all) |
| `LibraryManagerScreen` ▸ `PickPlatformContent` | `:349` |
| `LibraryManagerScreen` ▸ `PickEmulatorContent` | `:372` |
| `LibraryManagerScreen` ▸ `ScanPromptContent` | `:394` |
| `LibraryManagerScreen` ▸ `CardDetailContent` | `:452` |
| `LibraryManagerScreen` ▸ `ImportPcGamesContent` | `:659` |
| `CategoryManagerScreen` ▸ `PickIconContent` | `:123` |
| `CategoryManagerScreen` ▸ `PickTypeContent` | `:146` |
| `CategoryManagerScreen` ▸ `CategoryDetailContent` | `:177` |

Each is a **one-line** fix (`LocalSettingsScrollStateRegistrar.current(scrollState)` next to the
existing `rememberScrollState()`), not a restructure, so they are folded into Task 1.2 rather than
deferred. `CardDetailContent` is the one the owner is most likely to hit day to day.

The registered screens — About, Achievements, App Visibility, Artwork Import, Artwork, Audio,
Backup, Category Manager (list), Collections (list + detail), Controller, Display, Emulator
Assignment (list + detail), Emulator Profile Editor, Emulators (list), Library Manager (list), Logs,
Music, Photo, Themes, Video — need nothing beyond the scaffold change.

### Finding D — the Setup Wizard needs no `WizardScaffold` edit

`WizardScaffold.kt:96-98` registers one `ScrollState` shared by all eleven `SetupStep` pages, and no
page body opens a scrollable of its own (`InitialSetupScreen.kt`). Its header and footer reach the
layout through the scaffold's own `header`/`footer` slots, so wiring `dragToScroll` into those slots
in `SettingsScaffold` covers the wizard for free. Task 1.2's "2 modified files" budget is therefore
likely to be 1 file plus the Finding C one-liners.

### Deferred (unchanged from *Discovered, deliberately out of scope*)

The picker/drawer/overlay surfaces (`AppDrawerScreen`, `StorefrontAppDrawer`, `AppPickerScreen`,
`GamePickerScreen`, `MusicBrowserScreen`, `ShibaLibraryScreen`, `DetailContextMenu`,
`PspContextMenu`, `ColorSchemePickerOverlay`) plus the settings dialogs
(`CollectionCategoryPickerDialog:282`, `CollectionIconPickerDialog:328`, `EmulatorPickerDialog`,
`AddPcGameDialog`, `ClearOverridesDialog`) keep the same shape and stay out of Phase 1.

## Verification strategy

- **Unit (JVM):** the `isInSubItem` ⟺ `onHomeBack`-branch invariant; the swipe-back commit
  threshold as a pure function (following `consumeWholeSteps`/`flingBonusSteps` in
  `XmbNavGesturesTest`); the LEFT fallthrough in `ControllerNavigationStateTest`'s style; the new
  pref's default/round-trip/reset in the `ControllerLayoutRepository` tests; the
  `BackupManager` allowlist drift pin.
- **Existing tests that must stay green:** `AppPickerThreeRowsTest` and `AppDrawerThreeRowsTest`
  both assert their grid is "the only vertically scrollable node in the content" — Phase 1 must not
  break that assertion (or must update it deliberately, with the reason recorded).
- **On device (owner-driven — I do not drive the device):** the owner walks Settings ▸ Sound and
  the Wizard and reports whether a drag on the header/footer/gutter scrolls; then drills into a
  Games folder and a Settings L1 section and tries LEFT and a leftward swipe; then the same with
  the new setting OFF.
- **Builds:** no Gradle run without being asked.

## Execution Task Index

| ID | Task | Depends On | Status |
|---|---|---|---|
| 0.1 | Audit and record every touch dead zone in Screens and the Wizard | None | DONE — see *Audit record* above |
| 1.1 | Add the shared `dragToScroll` modifier and make the scaffold's touch probe non-consuming | 0.1 | DONE |
| 1.2 | Wire `dragToScroll` into `SettingsScaffold` + `WizardScaffold` chrome | 1.1 | DONE |
| 2.1 | Extract `backOutOfDrill()` and bind D-pad LEFT to it on the XMB | None | DONE |
| 2.2 | Add the swipe-back branch to `xmbNavGestures` and wire it in `XMBShell` | 2.1 | DONE |
| 3.1 | Add the `controller_left_backs_out` preference end-to-end (repo, prefs model, Settings row, reset, backup) | None | DONE |
| 3.2 | Make LEFT fall through to Back in `SettingsScaffold`, gated on 3.1, and update the docs | 3.1, 2.1 | DONE |

### Deviations from the plan as written

Each is a deliberate choice made while implementing, recorded so the diff reads as decisions:

1. **`isInSubItem` is now *defined* as the ladder, not tested against it.** D7 asked for a
   `backOutOfDrill()` extraction pinned by an `isInSubItem` invariant test. The stronger form was
   available: `XMBUiState.drillOutStep` picks the rung, `isInSubItem` is `drillOutStep != null`, and
   `backOutOfDrill()` performs it. The two lists cannot drift because there is only one list, and
   the ladder's *precedence* — the part a test can still get wrong — is pinned by
   `DrillOutLadderTest` with no ViewModel to build.
2. **`WizardScaffold` needed no edit** (Finding D). Its chrome reaches the layout through
   `SettingsScaffold`'s own `header`/`footer` slots, so the header/footer bands cover it already.
3. **Twelve one-line scroll-state registrations** were folded into 1.2 rather than deferred
   (Finding C) — without them `dragToScroll` is a no-op on those sub-screens.
4. **`core-ui` gained Compose test dependencies** (over 1.1's "1 new file, 1 modified, 1 test"
   budget). `dragToScroll`'s direction sign is only observable by dragging a composed node, and the
   test belongs with the modifier; the module had no Compose test infrastructure.
5. **The preference gates the XMB's LEFT too**, not only the Settings one — D5 says it gates the
   D-pad behaviour, and D4's D-pad behaviour has two insertion points. It is mirrored into
   `XMBUiState.leftBacksOut` (fed by the existing `controllerLayoutRepository.prefs` collector) and
   handed to Settings through `SettingsNavHost` → `LocalSettingsLeftBacksOut`. Task 3.2's file list
   grew by `XMBViewModel`, `XMBShell` and `SettingsNavHost` accordingly.
6. **The content-`Box` attachment from D3 was removed** after on-device testing — see the
   correction note under D3. Header and footer only.
7. **`cursorVisible` is now seeded from the host's `lastInputWasTouch`** (`SettingsNavHost` →
   `LocalSettingsLastInputWasTouch`). Every scaffold previously started its cursor visible and only
   cleared it on the next touch-down, so a touch-only user saw the controller cursor flash onto
   every screen they opened. Pre-existing, found by the owner while testing Phase 1.
8. **Removing the root pointer-loop consume changes one small behaviour** (1.1's stop condition
   asked for this to be reported): the old loop also fired on *hover* movement with no button
   pressed, so a mouse moving across a settings screen hid the controller cursor. The documented
   probe reacts to a real touch-down only. Nothing else changed — see Finding A for why the consume
   was never load-bearing.

### Still outstanding

- **The owner's on-device pass** (the verification strategy's third bullet) — nothing here has been
  run on hardware.
- **No Gradle build has been run** (house rule). The code is unverified by the compiler.

---

### Task 0.1 — Audit the touch dead zones

- **Objective.** Produce the definitive list of places where a drag does not scroll, across
  Settings screens, the Setup Wizard, and the picker/drawer surfaces.
- **Scope.** Investigation and a written record only.
- **Relevant code.** `SettingsScaffold.kt:688-872`, `WizardScaffold.kt:86-121`, plus the
  header-outside-the-list screens named under *Problem 1*.
- **Requirements.** For each surface record: the scroll owner, what lies outside it, and whether a
  drag there is dead. Separate *structural* dead zones (chrome outside the container) from
  *consumed* ones (`SettingsScaffold.kt:700-704`).
- **Do not change.** No production code in this task.
- **Expected files.** The plan document only.
- **Acceptance criteria.** Every Settings screen and every wizard step is accounted for; Phase 1's
  fix list is decided from the record.
- **Change budget.** Documentation only.
- **Verification.** Owner confirms the list matches what they feel on device.
- **Stop condition.** Stop when the list is written. Do not begin fixing.
- **If blocked.** If a surface cannot be classified from the source, name it and ask.

### Task 1.1 — `dragToScroll` + the non-consuming probe

- **Objective.** Provide the shared modifier and remove the scaffold's blanket pointer consume.
- **Scope.** New `core/core-ui/.../ui/gesture/DragToScroll.kt`; edit `SettingsScaffold.kt:688-707`.
- **Relevant code.** Reuse the probe shape at `GameDetailScreen.kt:226`. `Modifier.scrollable` from
  `androidx.compose.foundation.gestures` — the repo's first use, so document why (D1).
- **Requirements.** `Modifier.dragToScroll(state: ScrollableState?)` is a no-op when `state` is
  null. Vertical only. Drag direction must match `verticalScroll` (finger down ⇒ content down) —
  verify the `reverseDirection` sign rather than assuming it. The probe keeps every side effect it
  has today and consumes nothing.
- **Do not change.** No layout, no focus engine, no scroll-state registration, no other screen.
- **Acceptance criteria.** Existing settings scrolling and controller keep-in-view are unchanged;
  the `AppPicker`/`AppDrawer` three-rows tests still pass.
- **Change budget.** 1 new file, 1 modified file, 1 test file.
- **Verification.** Unit test for the null-state no-op and the direction sign. Owner smoke test.
- **Stop condition.** Do not attach the modifier to any screen — that is 1.2.
- **If blocked.** If removing the consume changes any observed behaviour, stop and report which.

### Task 1.2 — Wire the chrome into the content scroll

- **Objective.** Make a drag on the header, footer and gutters scroll the registered content.
- **Scope.** `SettingsScaffold.kt` (header slot, footer slot, content `Box`) and
  `WizardScaffold.kt`, driven by the 0.1 record.
- **Relevant code.** `contentScrollState` (`SettingsScaffold.kt:299`), the layout at `739-872`,
  `WizardScaffold.kt:96-119`.
- **Requirements.** Chrome drags scroll the content; taps on the ◀ breadcrumb and on rows still
  fire; touch-slop still separates a tap from a drag; the wizard's `contentKey` reset-to-top and
  the scaffold's first-item clamp are unaffected.
- **Do not change.** The ~22 settings screens' own bodies. The XMB. The drawer/picker grids —
  those are follow-up work if 0.1 flags them.
- **Acceptance criteria.** On device: dragging on a Settings header, on the helper footer, and in
  the side gutter all scroll the list; no row activates by accident.
- **Change budget.** 2 modified files.
- **Verification.** Owner walks Settings ▸ Sound and two wizard steps.
- **Stop condition.** Stop at the two scaffolds. Do not generalise to the drawer/pickers.
- **If blocked.** If a drag on chrome steals taps from the breadcrumb, stop and report.

### Task 2.1 — `backOutOfDrill()` and D-pad LEFT on the XMB

- **Objective.** LEFT backs out of an XMB flyout / drill-in instead of doing nothing.
- **Scope.** `XMBViewModel.kt` only.
- **Relevant code.** The two identical ladders at `:4768-4786` (gamepad BACK) and `:6387-6406`
  (`onHomeBack`), `isInSubItem` (`:787-795`), `dispatchGamepadAction`'s `NAVIGATE_LEFT`
  (`:4752-4759`).
- **Requirements.** Extract the duplicated ladder into one `backOutOfDrill(): Boolean` per D7 and
  re-point both existing callers at it with **no observable change**; then bind LEFT to it. LEFT
  fires only while `isInSubItem`, plays the BACK sound, marks controller (not touch) input, and has
  no App Drawer fallback. At the root LEFT still steps the category bar. Overlay guards unchanged —
  the `hasBlockingOverlay` early return at `:4745` still precedes everything.
- **Do not change.** `onHomeBack`'s observable behaviour, the BACK button, `stepCategory`, the
  navigation engine.
- **Acceptance criteria.** LEFT exits a Games folder, a Settings L1 flyout, and a Music/Video/Photo
  drill; LEFT at the root still changes category; the App Drawer never opens from a LEFT press.
- **Change budget.** 1 modified file, 1 test file.
- **Verification.** Unit test pinning `isInSubItem == true` ⟺ `backOutOfDrill()` returns true, that
  gamepad BACK at the root still opens the App Drawer, and that LEFT at the root does not drill out
  and does not open the drawer. Owner on-device pass.
- **Stop condition.** Do not add the preference gate (3.1) or touch gestures (2.2) here.
- **If blocked.** If a drill state exists that `isInSubItem` does not cover, stop and report it.

### Task 2.2 — Leftward swipe backs out

- **Objective.** In touch mode, a leftward drag inside a flyout / drill-in backs out one level.
- **Scope.** `XmbNavGestures.kt` and the call site in `XMBShell.kt:693-699`.
- **Relevant code.** The `fromEdge` branch (`XmbNavGestures.kt:90, 115`) is the shape to mirror;
  `XMBUiState.isInSubItem`; the new `backOutOfDrill()` path via `onHomeBack()`.
- **Requirements.** Add `swipeBackEnabled: Boolean` and `onSwipeBack: () -> Unit`. When enabled, a
  non-edge horizontal drag suppresses live category stepping and commits `onSwipeBack()` on release
  past a leftward threshold; when disabled, behaviour is byte-for-byte today's. Vertical stepping,
  fling bonus, edge-Back and multi-touch abandonment are untouched. Express the threshold as a pure,
  testable function alongside `consumeWholeSteps` / `flingBonusSteps`, and state explicitly whether
  it scales with `TouchSensitivity` (edge-Back does not).
- **Do not change.** `consumeWholeSteps`, `flingBonusSteps`, the vertical axis, `userScrollEnabled`
  on `XMBItemList` / `XMBCategoryBar`.
- **Acceptance criteria.** Leftward swipe inside a Games folder / Settings L1 flyout backs out; a
  rightward swipe there does not; at the category root both still step the bar; the left-edge pull
  still works.
- **Change budget.** 2 modified files, 1 test file.
- **Verification.** Unit tests for the threshold function and the enabled/disabled branch. Owner
  on-device pass on both a flyout and the root.
- **Stop condition.** XMB only — Settings/Wizard get no touch swipe in this plan.
- **If blocked.** If suppressing live stepping regresses the root-screen feel, stop and report.

### Task 3.1 — The `controller_left_backs_out` preference

- **Objective.** Ship the on/off setting end-to-end.
- **Scope.** `ControllerLayoutRepository.kt`, `ControllerLayoutPrefs` (core-domain),
  `ControllerSettingsScreen.kt`, `ControllerSettingsViewModel`, `BackupManager.kt`.
- **Relevant code.** `KEY_SCROLL_SPEED` and `resetAllPrefs()` in `ControllerLayoutRepository.kt`;
  the pref allowlist at `BackupManager.kt:521-526`; the existing row at
  `ControllerSettingsScreen.kt:114`.
- **Requirements.** `booleanPreferencesKey("controller_left_backs_out")`, default **true**, exposed
  on `ControllerLayoutPrefs`, a setter, removal in `resetAllPrefs()`, a Settings ▸ Controller row
  whose sublabel says plainly that LEFT backs out only where it is otherwise unused, and the key in
  the backup allowlist.
- **Do not change.** The mapping table, `gamepadMappingsFor`, other controller prefs.
- **Acceptance criteria.** Toggling persists across a restart and survives backup/restore; reset
  returns it to ON.
- **Change budget.** 4–5 modified files, 1 test file — one over the usual budget because the
  backup allowlist and the domain prefs model are separate modules by design.
- **Verification.** Repository default/round-trip/reset test; allowlist drift pin.
- **Stop condition.** The pref must not change behaviour yet beyond gating — 3.2 consumes it.
- **If blocked.** If `ControllerLayoutPrefs` cannot carry a boolean without a serializer change,
  stop and report.

### Task 3.2 — LEFT falls through to Back in Settings and the Wizard

- **Objective.** LEFT backs out of a settings screen / wizard step when LEFT is otherwise unused.
- **Scope.** `SettingsScaffold.kt` (the `NAVIGATE_LEFT` branch), plus the README/ARCHITECTURE
  touch-controls table.
- **Relevant code.** `SettingsScaffold.kt:617-619`; `NavigationContext.moveHorizontal`
  (`:180-185`) returning `null`; slider adjust (`:570-586`) and `onInterceptAction` (`:554`), both
  of which already consume LEFT earlier; the wizard's `onBack` → `previousStep()`
  (`InitialSetupScreen.kt:119`).
- **Requirements.** When `moveHorizontal(-1)` returns `null` and the 3.1 preference is on, call
  `onBack()`. When it returns a key, move focus as today. Slider mode, remap capture, and screens
  that intercept LEFT are unaffected because they run first.
- **Do not change.** The focus engine, `moveHorizontal` itself, `NAVIGATE_RIGHT`, the BACK branch.
- **Acceptance criteria.** LEFT on an ordinary settings row closes the screen; LEFT on a row with
  inline actions still steps into them; LEFT while adjusting a slider still decrements it; LEFT in
  the wizard goes to the previous step and is inert on WELCOME; with the pref off, LEFT is the
  no-op it is today. README's Navigation & controls table (`README.md:225-231`) reflects the new
  bindings.
- **Change budget.** 1–2 modified files, 1 test file, plus docs.
- **Verification.** Unit test over the fallthrough decision. Owner walks Settings and the Wizard
  with the pref on and off.
- **Stop condition.** No touch gesture work here.
- **If blocked.** If any settings screen relies on LEFT being a silent no-op, stop and name it.
