# Artwork Studio — spatial navigation on the shared navigation core

> Implementation handoff, written 2026-09-09. Indexed as `C17` in [the plan index](README.md).
> Split out of `C16` (Artwork Manager hardening), whose task `4.1` this replaces in full.
>
> Every file, symbol and line reference below was verified read-only against the working tree on
> `artwork-revisions` after C16's Phases 0–1 landed. Nothing here is assumed.

## Context

`ArtworkStudioScreen` + `ArtworkStudioViewModel` (1,115 + 1,142 lines) are navigated by a
three-rung ladder, not by focus:

```kotlin
enum class StudioZone { TABS, SOURCES, GRID }
```

[`ArtworkStudioViewModel.kt:48`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ArtworkStudioViewModel.kt:48)

"Where the cursor is" is `zone` plus an index into it (`tabIndex` / `sourceIndex` / `gridIndex`).
Confirm descends a rung, Back ascends one and closes from the top, Left/Right act only on the
current rung. The screen reads `zone` to pick breadcrumb segments, to paint the accent ring, and
to choose which controller prompts to show.

C16 stopped at this boundary deliberately. Its Phase 4 wants seven interactive regions —
breadcrumb, search, categories, sources, grid, paging, actions — and a three-rung ladder has
nowhere to put a control that is not a rung on it. C16's own Phase 1 already hit the wall: the
search field shipped as a **modal overlay bound to Square** rather than a focusable row, and the
SteamGridDB mature filter went into the context menu, both because there was no rung to give them.

## Problem

**A ladder has ordering but no geometry.** It can express "sources are below categories"; it
cannot express "the paging pills sit beside the grid" or "the search field is above the tabs".
Adding a region means a new enum case *plus* a new branch in all six `when (s.zone)` blocks in the
ViewModel and all four `state.zone` reads in the screen — and the result still cannot be navigated
spatially, only up and down the same ladder.

Three concrete consequences today:

1. **Search is unreachable except by a dedicated button.** It is a modal overlay on Square.
2. **Paging is unreachable by D-pad.** Up/Down are clamped inside the grid; paging is LB/RB or the
   on-screen pills only ([`:1107-1123`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ArtworkStudioViewModel.kt:1107)).
3. **The prompt bar is hand-maintained per zone, and it has drifted once already.** After C16
   task 1.3 rebound `CHANGE_SORT` from the mature filter to search, two of the three per-zone
   lists still advertised it as "NSFW". Fixed in place — the shared prompts are appended from one
   `buildList` now, and mature moved to `HOME` (START) — but the structure that hid it is intact:
   three hand-written lists, one per zone, none of which knows what the focused control does.

## Current Behavior

Verified against the tree.

- **No Compose focus in the interactive chrome.** The screen's only `FocusRequester` is the one
  C16 Phase 1 added for the search field's `BasicTextField`. Tabs, sources and grid tiles are not
  `focusable()`; there is no `onKeyEvent` anywhere in the file.
- **Input is hoisted.** `pendingGamepadAction` is forwarded from
  [`GameDetailScreen.kt:186-206`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt:186)
  and dispatched by `handleGamepadAction`, which branches overlay-first (search → crop → actions →
  candidate) before reaching the zone ladder.
- **Five overlays already behave like modal contexts** — search, crop editor, actions menu,
  file-info panel, candidate preview — each with its own early-return input block.
- **`showTouchControls` is not passed in.** `GameDetailScreen` has the parameter
  ([`:117`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt:117))
  and does not forward it at the call site
  ([`:206`](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt:206)).

## Root Cause

The Studio was written before this repository had a navigation core, and was never migrated to it.

**`core-navigation` already exists, is already a dependency of `feature-xmb`, and already solves
most of this.** It is a 613-line pure-JVM module with 670 lines of tests:

| Piece | What it already does |
|---|---|
| `NavigationNode` | stable key, focusable/selectable/enabled, `onSelect`, `onLongPress`, `children` (LEFT/RIGHT inline actions), `onEditStart` |
| `NavigationContext` | node registry in registration order, optional Y geometry, focus preservation across recomposition, nearest-survivor recovery, readiness gating |
| `NavigationEngine` | context **stack** with `pushModal`/`popContext`, `dispatch(NavigationCommand)`, component-owned edit mode where **Back always exits edit mode before screen navigation**, touch re-anchoring, no-wrap clamping |
| `gridMove(current, direction, columns, size)` | pure 2D grid-cursor math, no wrap, short-last-row safe |

`feature-settings` is an *adapter* onto this core (`ControllerNavigationState`,
[`ControllerNavigation.kt:39`](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/ControllerNavigation.kt:39)),
with `rememberControllerRowRegistration` as its Compose-side registration seam. `feature-xmb`
already imports `gridMove` in `AppPickerLogic.kt`.

So this is not "build a focus system". It is **write the Studio's adapter onto a tested core that
another surface already proved out** — which is a materially smaller and better-understood job.

## Goals

1. Every interactive region reachable by D-pad, in the direction it visually lies.
2. Search becomes a focusable field in the layout, not a button-summoned modal.
3. Paging reachable without LB/RB.
4. One source of truth for the controller prompt bar, derived from the focused node.
5. Overlays become modal contexts on the engine's stack instead of five hand-rolled early returns.
6. Touch stays a first-class peer, including `showTouchControls`.
7. `StudioZone` deleted.

## Non-Goals

- **Generalizing `feature-settings`' registration plumbing into `core-ui`.** Tempting, and wrong
  for this plan (AD-4).
- Changing what any control *does* — this plan moves how controls are reached, not their behavior.
- Redesigning the layout. Regions keep their current positions.
- The crop editor's internal pan/zoom semantics. It becomes an edit-mode/modal owner, unchanged.
- Migrating any other `feature-xmb` screen onto the core.
- C16's Phase 4 tasks `4.3` (touch) and `4.4` (pending-change prompts) — they depend on this plan
  but stay in C16.

## Existing Systems to Reuse

| Need | Reuse | Location |
|---|---|---|
| Node model, focus preservation, modal stack, edit mode | `NavigationEngine` / `NavigationContext` / `NavigationNode` | `core/core-navigation/` |
| 2D grid cursor with no wrap | `gridMove` | `core-navigation/GridMove.kt` |
| An adapter to copy the shape of | `ControllerNavigationState` | `feature-settings/ui/ControllerNavigation.kt:39` |
| Compose registration seam to copy the shape of | `rememberControllerRowRegistration` | `feature-settings/ui/ControllerRowRegistration.kt:63` |
| Keeping the focused element framed | `BringIntoViewRequester` (geometry, not scroll math) | `GameDetailScreen.kt`, `VideoDetailScreen.kt` |
| Prompt glyphs that follow the user's controller | `ControllerPromptBar` / `ControllerPromptItem` | `core-ui/components/` |
| LEFT-backs-out fallthrough | `controller_left_backs_out` pref (plan C15) | core-ui |
| Controller-vs-touch presentation | `TouchNavButtonMode` resolved against `lastInputWasTouch` | `core-domain/model/TouchNavButtonMode.kt` |
| Compose tests on the JVM | Robolectric, already wired | `feature-xmb/build.gradle.kts:62-66` |

## Architectural Decisions

**AD-1. The Studio gets an adapter, not a new engine.**
`StudioNavigationState` wraps a `NavigationEngine("artwork-studio")` and translates Studio regions
into `NavigationNode`s, exactly as `ControllerNavigationState` does for Settings. Focus
preservation, nearest-survivor recovery, clamping and modal stacking are inherited, not rewritten.

**AD-2. The results grid is an edit-mode node, not a region of nodes.**
Registering twenty tiles as twenty nodes would put twenty entries into a vertical list model whose
geometry is a single Y float per key — it cannot express rows and columns. Instead the grid is
**one** node whose `onEditStart` returns an `EditModeHandler` driving `gridMove(gridIndex,
direction, STUDIO_GRID_COLUMNS, results.size)`.

This is what the core's edit mode is for, and it lands three behaviours for free: Confirm enters
the grid, **Back always exits the grid before any screen navigation** (the engine guarantees it),
and directional input inside the grid is the component's own business. `gridMove` returning null at
an edge is the seam where LEFT falls through to back-out and RIGHT/UP/DOWN move between regions.

**AD-3. Overlays become modal contexts.**
Search, crop, actions, file-info and candidate preview each `pushModal(...)` and `popContext()`.
The five hand-rolled early-return blocks in `handleGamepadAction` collapse into the engine's stack,
which already guarantees only the top context receives input and that paused contexts restore their
focused node. This is the part of the change that removes code rather than adding it.

**AD-4. The Studio's registration seam is Studio-local, and that is deliberate.**
`feature-settings`' registration plumbing (`LocalSettingsFocusRegistry`,
`LocalSettingsNavigationOrder`, `LocalSettingsRowPositions`, …) is `internal` to that module and
shaped around a settings row list. Promoting it to `core-ui` would mean touching ~22 settings
screens and the first-run wizard to prove nothing about this screen.

So: copy the *shape*, not the code, into a small Studio-local seam. If a third surface later wants
it, the generalization is then driven by two real consumers instead of one plus a guess. Recorded
as a follow-up, not smuggled in here.

**AD-5. LEFT keeps C15's fallthrough-never-override rule.**
LEFT moves spatially first, and only falls through to back-out at the left edge of a region, under
`controller_left_backs_out`. Mirrors C16's AD-10 so both behaviours survive.

**AD-6. The prompt bar is derived, never hand-written.**
One `promptsFor(focusedKey)` replaces the three hand-maintained `when (state.zone)` lists — which
is what let the stale "NSFW" label survive C16 task 1.3.

## Rejected Alternatives

- **Compose's built-in 2D focus search (`focusable()` + `focusManager.moveFocus`).** Rejected: the
  repo's entire controller model is a hoisted `GamepadAction` reduced in a ViewModel, tested on the
  JVM. Adopting Compose focus traversal here would put navigation behaviour in the composition, off
  the JVM test path, and inconsistent with Settings, the App Drawer and the XMB.
- **Registering each grid tile as a node.** Rejected: `NavigationContext`'s geometry is one Y float
  per key, so a 4×5 grid collapses to a 20-item list and Left/Right would walk rows (AD-2).
- **Promoting the Settings registration plumbing to `core-ui` first.** Rejected as this plan's
  scope (AD-4).
- **Keeping `StudioZone` and adding cases for the new regions.** Rejected: this is what the plan
  exists to stop. Seven regions × six `when` blocks, still with no geometry.
- **Doing this inside C16.** Rejected: it is the single largest task in that plan, nothing in C16's
  Phases 2, 3, 5 or 6 depends on it, and bundling it would gate five shippable merges behind one
  large refactor.

## Data / Persistence

None. No schema change, no new preference, no new dependency — `feature-xmb` already depends on
`core-navigation` ([`build.gradle.kts:47`](../../feature/feature-xmb/build.gradle.kts:47)).

## Implementation Phases

**Phase A — Adapter, behind the existing behaviour.** `StudioNavigationState` and the region node
model, driven by the current `zone` state so nothing changes on screen yet. Ships inert.

**Phase B — Regions take over.** Nodes become the source of truth for focus; `StudioZone` is
deleted; search and paging join the layout as real regions; the prompt bar derives.

**Phase C — Overlays become modal contexts.** The five early-return blocks collapse onto the stack.

Phase A is separately reviewable and reversible. Phase B is the behavioural change.

## Verification Strategy

- **Unit (JVM), the bulk of it** — because the adapter is a ViewModel-side reducer, as Settings'
  is: region traversal in every direction; grid edges via `gridMove` (no wrap, short last row);
  LEFT falling through to back-out only at the left edge and only under the pref; Back exiting the
  grid's edit mode before closing the screen; modal push/pop ordering; focus surviving a results
  refresh; focus recovering when the focused region disappears (a source list that shrinks, a tab
  with no sources).
- **Compose (Robolectric, already wired)** — every control reachable by D-pad alone; the search
  field focusable in the layout without Square; paging reachable without LB/RB; Back closing the
  top overlay then exiting; touch targets still hit the right nodes.
- **Regression** — C16 Phase 1's `ArtworkStudioViewModelTest` must keep passing untouched. It
  asserts race safety, caching and paging, none of which this plan may disturb; if a test there
  needs editing, that is the signal something outside this plan's scope moved.
- **Manual, on device** — D-pad from the breadcrumb to the grid and back without touching LB/RB;
  the prompt bar showing correct labels in every region.

## Execution Task Index

| ID | Task | Depends On | Status |
|---|---|---|---|
| 1.1 | `StudioNavigationState` adapter over `NavigationEngine("artwork-studio")`, mirroring `ControllerNavigationState`'s shape, plus the Studio-local registration seam (AD-4) | None | READY |
| 1.2 | Region node model: breadcrumb, search, categories, sources, grid, paging, actions — with categories/sources/paging exposing their items as `children` (LEFT/RIGHT), driven by the existing `zone` state so behaviour is unchanged | 1.1 | READY |
| 2.1 | Grid as a single edit-mode node over `gridMove`, with `Back` exiting to the region stack and a null move at an edge as the region-exit seam (AD-2) | 1.2 | READY |
| 2.2 | Cut focus over to the nodes and **delete `StudioZone`**, its six `when` blocks and its four screen reads | 2.1 | READY |
| 2.3 | Promote search and paging to real focusable regions in the layout; keep Square as a shortcut to the search field | 2.2 | READY |
| 2.4 | Derive the controller prompt bar from the focused node, replacing the three hand-written per-zone lists so a rebinding cannot silently leave a stale label again | 2.2 | READY |
| 2.5 | LEFT falls through to back-out only at a region's left edge, under `controller_left_backs_out` (AD-5) | 2.2 | READY |
| 2.6 | Keep the focused region framed with `BringIntoViewRequester` rather than scroll math | 2.2 | READY |
| 3.1 | Search, crop, actions, file-info and candidate preview become modal contexts; delete the five early-return blocks in `handleGamepadAction` (AD-3) | 2.2 | READY |

**Start here:** `1.1` → `1.2`, which change no on-screen behaviour and are reviewable on their own.

## Follow-ups (documented, not implemented)

- **Generalize the registration seam into `core-ui`** once a third surface wants it, driven by two
  real consumers rather than one plus a guess (AD-4).
- **`sourceIndex` is reset on `selectTab` but never re-validated against the new source list's
  length** (`ArtworkStudioViewModel.kt:505`) — carried from C16's follow-ups; task 2.2's
  nearest-survivor recovery is the natural place it stops mattering.
- `AppPickerScreen` is a second `feature-xmb` grid navigated by index-in-state. If this adapter
  works out, it is the obvious next candidate.

## Hand-off notes

- Repository is the source of truth, above this plan. If implementation contradicts something
  written here, stop and report rather than inventing architecture (`PLANNING_WORKFLOW.md` §6, §12).
- Work **one bounded task per helper**, in dependency order, with `PLANNING_WORKFLOW.md` §4's
  change budget: 2–4 existing files modified, 1–2 new files, 1 test file, no new dependencies.
- **Read `core-navigation`'s six test files before task 1.1.** They are the specification of the
  behaviour being inherited — readiness, geometry, list behaviour, modal, edit mode and touch — and
  they document guarantees this plan leans on rather than re-testing.
- `C16` task `4.1` is retired in favour of this plan; `C16` tasks `4.3` and `4.4` depend on it.
- This plan is indexed as `C17` in `docs/plans/README.md`. Keep that row current as phases land.
