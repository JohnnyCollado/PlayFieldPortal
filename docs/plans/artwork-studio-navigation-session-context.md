# C17 session context — Artwork Studio spatial navigation

Written 2026-09-16 on branch `artwork-revisions`, for a session starting
[`artwork-studio-navigation-plan.md`](artwork-studio-navigation-plan.md) cold.

The plan itself is sound and still the spec. This file carries what the plan cannot: **its own
line references have drifted**, its gate has cleared, and two of its factual claims about the tree
are now stale. Read the plan first, then this, and trust this file where they disagree about the
current tree — but trust the plan over both for intent.

---

## 1. The gate is open

C17 task `1.1` depends on **C16 `L.6`**, which the user closed on 2026-09-11. Everything C16 can
deliver without C17 is now done and device-verified:

- Phases 0–3, `5.0`–`5.4`, `6.1`–`6.3`, `6.5`–`6.8`, `D.1`–`D.4b`, `L.1`–`L.6`, `M.0`–`M.6`.
- **Nothing in C16 is left except what C17 gates**: tasks `4.3` (touch targets) and `4.4`
  (pending-change / pending-exit prompts), plus `6.4` (session Undo Last Apply), which is unspecced
  and sequenced after this plan. `7.1` is blocked on plan B2, unrelated.

So C17 is the only thing standing between C16 and done. That is the reason to start it.

---

## 2. Line-reference drift

The plan was written against `49ae052`. The Studio has roughly doubled since. Every reference below
was re-verified on 2026-09-16; **re-check before quoting any other line number in the plan.**

| Plan says | Actually now |
|---|---|
| `ArtworkStudioViewModel.kt` 1,545 lines | **2,872** |
| `ArtworkStudioScreen.kt` 1,402 lines | **1,895** |
| `enum class StudioZone` at `:56` | `:76` |
| Paging clamp at `:1507-1514` | moved; find via `GamepadAction.NAVIGATE_UP -> if (s.zone == StudioZone.GRID` |
| Match row FORGET / CHANGE MATCH at `ArtworkStudioScreen.kt:450-477` | `:576-607` |
| `ControllerNavigationState` at `ControllerNavigation.kt:39` | `:52` |
| `rememberControllerRowRegistration` at `ControllerRowRegistration.kt:63` | `:62` |
| `sourceIndex` reset at `:603` | `:1198` |

Unchanged and still correct: `core-navigation` is 613 main lines with six test files, and
`feature-xmb` already depends on it (`build.gradle.kts:47`). No new dependency is needed.

`StudioZone`'s footprint is still exactly what the plan describes, which is the encouraging part:
**six `when (s.zone)` blocks** in the ViewModel (`:2814`, `:2823`, `:2829`, `:2843`, `:2848`,
`:2853`) and **four `state.zone` reads** in the screen (`:315`, `:447`, `:703`, `:817`). Task `2.2`'s
scope has not grown.

---

## 3. Two stale claims — read these before planning tasks 3.1 and C16 4.3

### a. There are now **nine** overlay early-return blocks, not six

The plan's AD-3 and task `3.1` both say "the six hand-rolled early-return blocks". In
`handleGamepadAction` today, in dispatch order:

1. `searchOpen`
2. `changeMatchOpen` (owns its own edit state, `changeMatchEditing`)
3. `cropOptionsOpen` — **new**, added by C16 task 6.3 (2026-09-16)
4. `cropEditorPath != null`
5. `confirmPrompt`
6. `leavePromptOpen`
7. `managerOpen` — C16 task 5.4's stored-assets manager
8. `actionsOpen`
9. `candidate != null`

Three of these landed after the plan was written. This makes task `3.1` **larger** than specced,
and it strengthens the plan's own argument — AD-3 says this is "the part of the change that removes
code rather than adding it", and there is now half again as much to remove. Note that `cropOptionsOpen`
and `cropEditorPath` nest: the options menu opens *over* the editor and must be dispatched first.

### b. `showTouchControls` **is** already forwarded into the Studio

The plan's "Current Behavior" says `GameDetailScreen` "has the parameter and does not forward it at
the call site". That was true at `49ae052`. It is not true now — C16's `L.6` pulled touch pills
forward from task `4.3` and wired it through:

- `GameDetailScreen.kt:118` declares it, `:212` forwards it to `ArtworkStudioScreen`.
- The Studio consumes it at `ArtworkStudioScreen.kt:119, 159, 175, 417, 798, 869, 1318`.

**C16 task `4.3` is therefore partly done already.** Whoever picks it up after C17 should re-scope
it against the tree rather than the plan's description; what remains is touch-sized targets and
hit-target separation, not the threading.

---

## 4. What to read, in order

1. **`core-navigation`'s six test files** — the plan's hand-off notes insist on this before task
   `1.1`, and they are right: `NavigationEngineEditModeTest`, `…GeometryTest`, `…ListBehaviorTest`,
   `…ModalTest`, `…ReadinessTest`, `…TouchTest`. They are the specification of the behaviour being
   inherited, not re-tested.
2. **`feature-settings/ui/ControllerNavigation.kt`** (the adapter to copy the *shape* of) and
   **`ControllerRowRegistration.kt`** (the Compose seam). AD-4 is explicit that the code is copied
   in shape only and stays Studio-local — do not promote the Settings plumbing into `core-ui`.
3. **`handleGamepadAction`** in `ArtworkStudioViewModel.kt`, whole. It is the thing being replaced.
4. `docs/mockups/artwork_studio_layout.html` — the layout C17 navigates. `L.1`–`L.6` already built
   it; C17 does not change it.

---

## 5. Constraints that bit in C16 and will bite here

- **An open IME receives key events before `MainActivity.dispatchKeyEvent`.** This killed the first
  Change Match picker and is still live for the Studio's own search overlay (recorded as a C16
  follow-up). The fix pattern is the picker's: a cursor stop that opens the keyboard only when
  editing starts. Task `2.3` promotes the search field to a real region and inherits this problem —
  the core's edit mode is the intended answer.
- **Square is search, everywhere.** `CHANGE_SORT` opens search in the Studio's main branch and
  starts query editing in the Change Match picker; the class KDoc states it ("X opens search, Y
  opens the per-slot options"). START (`HOME`) is Apply Changes. Task 6.3 briefly got this wrong and
  had to be reworked — **there is no free button in the crop editor**, which is why its context menu
  now holds the live-preview switch. Any new control needs a node, not a binding.
- **The grid's column count is measured, not constant** (C16 AD-17). `gridColumns` comes from the
  measured grid slot; AD-2's `gridMove` call must read it from state, never a literal.
- **`ArtworkStudioViewModelTest` is the regression net.** The plan says it must keep passing
  untouched, and that if a test there needs editing, something outside scope moved. It has grown a
  lot since the plan was written — it now also covers multi-asset selection, the download queue,
  duplicate detection and the crop options menu.

---

## 6. Repository state

- Branch `artwork-revisions`, 18+ commits ahead of `main`, no PR open.
- The 6.5 + 6.3 work is **staged but not committed** as of this writing — check `git status` first.
- Unit tests green across `core-data`, `feature-artwork`, `feature-settings` and `feature-xmb`.
- Windows note: `transformDebugClassesWithAsm` intermittently fails packing its build-cache entry
  ("Could not get file mode"). It is not a compile error. Re-run with `--no-build-cache`.
- The user runs all Gradle commands — hand over exact command lines rather than running them.

---

## 7. Suggested first move

Task `1.1` then `1.2`, exactly as the plan says: they ship inert, change nothing on screen, and are
reviewable on their own. Phase A is the reversible half; Phase B is where behaviour moves.

Before writing any of it, re-verify the line numbers in §2 — this file will drift too.

---

## 8. Decisions taken 2026-09-16 (user), before task 1.1

Three places where the tree contradicts the plan were reported and decided. They bind tasks 1.2,
2.1 and 2.5.

1. **The rail is reached by LEFT, not by UP/DOWN.** The core's geometry is one Y per key, but the
   Current rail sits *beside* the sources / match / grid / page column and overlaps all of them in Y.
   LEFT at the left edge of any right-column region moves to the rail; only LEFT at the rail (a
   region with nothing further left) falls through to back-out under `controller_left_backs_out`.
   This refines AD-5 rather than breaking it: "left edge" means the left edge of the *screen's*
   regions, not of the focused one. Adapter-side; no core change. The rail must therefore be kept out
   of the Y-sorted vertical order (1.2 decides how — its Y otherwise sorts it into the middle of the
   right column).
2. **Tabs and sources select as the cursor moves.** Today LEFT/RIGHT on a chip row changes the
   selection directly. The core's children are cursor stops that do not select, and entering a row
   lands on the row, not on the selected chip. The adapter keeps today's behaviour: entering the row
   focuses the *selected* child, focusing a child selects it, and LEFT past the first child (the core
   returning focus to the row) is that row's left edge.
3. **Fix the core's edit-mode fall-through (task 2.1).** In `NavigationEngine.dispatch`, when an
   `EditModeHandler` declines a direction the engine moves focus but never clears the handler, so the
   *next* press is still delegated to the component the cursor just left. The core test
   `unconsumed direction inside edit mode falls through to traversal` asserts only `focusedKey` and
   misses it. Fix it in `core-navigation` (clear the handler when focus actually moved) and add the
   `isEditing` assertion there, rather than working around it in the Studio adapter.

Also found: `zone` has more footprint than §2's "six `when` blocks + four screen reads". The
ViewModel also reads it in `canPreviewFocused` (`:390`) and at `:508`, and writes it at `:701`,
`:1198`, `:1224`. Task 2.2 must cover those too.

The context doc's §6 note that 6.5 + 6.3 are staged-but-uncommitted is stale: they are committed.

---

## 9. Deferred 2026-09-16

The user deferred C17 after task `1.1`. Nothing it would fix is unreachable today (Square searches,
LB/RB page, the Triangle menu holds Change Match / Forget Match), so the remaining seven tasks were a
large rework of the Studio's input for polish.

- **Task 1.1 was built and deleted.** `StudioNavigationState` (a thin adapter over
  `NavigationEngine("artwork-studio")` taking `NavigationNode`s directly and exposing `dispatch`) and
  `Modifier.studioRegion` (position-only seam: Settings' `FocusRequester` half does not carry over,
  since Compose focus is this plan's rejected alternative) passed 13 JVM tests but had no caller.
  Rebuilding them from this description is under an hour.
- **Task 1.2 was designed, not written.** The rail as a *side region* held by the adapter outside
  the engine's vertical list (a `focusable = false` node cannot be confirmed by the core); chip rows
  that separate "focus selects" from "Confirm acts" (focusing Local File must not open the picker);
  retry/remove page-line children only in touch mode, since controller mode does not draw them; and
  an open question for 2.2 of whether Confirm on a tab or source still descends.
- **The core edit-mode bug in §8.3 is still unfixed** and latent: no production code sets
  `onEditStart`. Fix it before anything adopts edit mode.
- **C16 re-scoped the same day:** `4.3`'s remaining touch-sized targets no longer depend on C17, and
  `4.4` is closed as covered by `5.2`'s leave prompt.
