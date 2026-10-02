# Play Field Portal — Item List Step Animation: Implementation Plan

The XMB item list stops snapping a whole row per press. It glides one row on the category bar's
spring, and the drill-in flyout does the same. At rest the column looks exactly as it does today.

**Status: not started (planned 2026-10-01).** Work the tasks in §7 in order. Write each task's tests
(§6) before its production code.

**Mockup (approved):** section `id="p3"` of
`C:\Users\johnn\AppData\Local\Temp\claude\D--NEXTJJEN-repos-PlayFieldPortal\c3567c5d-ba86-4cec-af9c-f4fd81179210\scratchpad\overnight-mockups.html`.
Its `yFor(d)` gives the intended shape of the motion. It does **not** give the at-rest geometry,
which comes from today's code (see §3.2, R1).

> **Working rules for the implementing session**
> - Tests first. Write the task's tests against the API that does not exist yet, then implement.
> - Do not run Gradle. Give the user the commands in §9, one per block.
> - Done means the task's tests pass and the build adds no new Kotlin warnings.
> - Match the surrounding comment density and voice. These files explain *why* in full sentences.
>   Keep that.
> - Do not commit.

---

## 1. Behaviour

| Situation | Today | After |
| --- | --- | --- |
| Up/Down press, swipe step, tap on another row | column re-laid out at the new index in one frame | column glides on the bar's spring, `spring(stiffness = Spring.StiffnessMediumLow)` with no bounce |
| Held repeat | one snap per repeat | the same spring, retargeted mid-flight. No special case. |
| Jump of more than 3 rows (tap, restore after rescan) | snap | snap to one row short of the target, then glide the last row |
| Column first composes, category switch, the outgoing `AnimatedContent` copy (`selectedIndex = -1`) | snap | **snap** |
| Sort or search (`scrollToTopToken` bump) | snap to 0 | **snap** |
| Level change inside a category (Settings section, Music/Video/Photo/Social views, list cleared then refilled) | snap | **snap** |
| Move (row lifted, `LocalXmbRowDecor.movingLabel != null`) | snap | **snap**, matching the bar's `if (moving) snap()` |
| Resume, recomposition | no motion | no motion. The position starts at its target, like the bar's `animateDpAsState`. |
| Drill flyout: game column (`XmbGameColumn`) | snap | same rules as the list |
| Drill flyout: left memory-card column (an `XMBItemList`) | snap | same rules as the list. In practice it does not move while drilled. |

A row stepping up leaves the "below" slot, passes behind the category bar and settles in the
half-clipped "previous" slot. A row stepping down does the reverse.

**At rest every pixel matches today.** That means the selected row directly under the bar, the
previous row in its half-row window, row scale and alpha, the UMD row growth, and the lifted row's
move outline and the marked badge, both of which reach slightly above the row.

There is no setting to turn the motion off.

## 2. What exists today

All paths are under `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/`.

- **`ui/XMBCategoryBar.kt:149-153`.** `slide` is `animateDpAsState(..., animationSpec = if (moving) snap() else spring(stiffness = Spring.StiffnessMediumLow))`.
  The doc above it (`:127-148`) explains why a position that is computed rather than scrolled cannot
  drift, and why `animate*AsState` starting at its target makes "slide in on resume" impossible.
  The item list should follow the same reasoning.
- **`ui/XMBItemList.kt` `XMBItemList` (`:504-632`).** The column is drawn in two pieces inside
  `BoxWithConstraints(... .clip(ClipAllButLeft))`:
  - **Below.** A `Column` at `offset(y = belowTopY)` holding rows `sel until min(size, sel + rowsBelow)`.
    It contains only whole rows, so nothing partial is composed at the bottom. Rows are composed
    **positionally, without `key`**, so slot 0 is always the selected row and the row scale and
    alpha springs never actually run on a step.
  - **Previous.** It is shown only when `selectedIndex in 1..items.lastIndex`. A `Box` of height
    `ROW_HEIGHT / 2` sits at `offset(y = barTopY - ROW_HEIGHT * previousRiseRows)` with
    `clipToBounds()`, and the row inside it is `requiredHeight(ROW_HEIGHT)`.
  - **UMD.** `umdRowHeight` tweens the selected row from `ROW_HEIGHT` to `UMD_ROW_HEIGHT` over
    200 ms once the disc is "read" (`rememberUmdRead`, `ui/UmdSlotRead.kt:31`). The `Column` pushes
    the rows under it down.
  - **`scrollToTopToken`** is accepted (`:514`) but **never read**. It is left over from the
    LazyColumn era.
- **`ui/XMBItemList.kt` `XmbGameColumn` (`:279-322`).** Each game row is placed independently at
  `offset(y = belowTopY + ROW_HEIGHT * (i - sel))`, inside a `clipToBounds` box, over a window of
  `sel - rowsAbove .. sel + rowsBelow` with a 2-row buffer. It also has no `key`.
- **`ui/XMBItemList.kt` `XmbDrillFlyout` (`:215-273`).** The left column is `XMBItemList` with
  `LocalXmbRowDecor provides XmbRowDecor()`, so it never sees a Move. The right column is
  `XmbGameColumn`. The flyout does **not** receive `scrollToTopToken`.
- **`ui/XMBItemList.kt` `CenterLockedColumn` (`:431-502`).** Unused (nothing references it). It is
  the only user of `LazyColumn`, `items`, `rememberLazyListState`, `PaddingValues`,
  `animateScrollBy`, `FastOutSlowInEasing`, `isUnspecified`, `LaunchedEffect` and
  `android.os.SystemClock` in this file.
- **`ui/XMBShell.kt`.**
  - `:829-843` builds `LocalXmbRowDecor`. `movingLabel` is non-null exactly while
    `uiState.moveSession != null`. **This composition local is how Move mode reaches the list.**
  - `:883-901` is `XmbDrillFlyout`.
  - `:903-937` is `AnimatedContent(targetState = selectedCategoryIndex)`. The outgoing copy gets
    `selectedIndex = -1` (`:920-921`), and **both copies read `uiState.currentItems`**.
  - `:955` is the bar's `moving = uiState.categoryMoveSession != null`.
- **`viewmodel/XMBViewModel.kt`.**
  - `viewCursorKey` (`:3500-3514`) is the existing "which list is on screen" key used by
    `navigateRememberingCursor` (`:3519-3533`). It uses five private, pure helpers: `musicNavKey`
    `:3535`, `videoNavKey` `:3543`, `socialNavKey` `:3559`, `photoNavKey` `:3937` and
    `achievementsNavKey` `:5910`.
  - Level changes inside one category (`settingsSectionNav`, `musicNav`, `videoNav`, `photoNav`,
    `socialNav`, `achievementsNav`) replace `currentItems` and `selectedItemIndex` **inside the
    same `XMBItemList` composition**, because the `AnimatedContent` target does not change.
  - `publishAppSectionItems` (`:2544`) clears the list to empty first.
  - Sort and search set `selectedItemIndex = 0` and bump `scrollToTopToken` in one update
    (`:5080`, `:5161`, `:5262`).
- **Held repeat** (`gamepad/GamepadInputHandler.kt:56-60`). The fastest interval is 35 ms (FAST),
  about 28 rows/s. A critically damped spring at stiffness 400 (ω = 20/s) trails a constant-rate
  target by 2v/ω ≈ 2.8 rows at that speed.
- **Tests.** JVM unit tests use JUnit4 with `kotlin.test` (`ui/XmbCategoryBarVisibilityTest.kt`).
  Robolectric Compose tests use `createComposeRule()` under `@RunWith(RobolectricTestRunner::class)`
  with `@Config(sdk = [34], qualifiers = ...)` (`ui/GameSearchFieldKeyboardTest.kt`).
- **Device density.** One dp is 2.306 px on the Thor and Odin 3 (Virtual Keyboard plan §4).
  `ROW_HEIGHT` is therefore 202.9 px, which is **not an integer**.

## 3. Design

### 3.1 Pieces

| Piece | File | Kind |
| --- | --- | --- |
| `XmbStepSpring` (the shared spring: stiffness, damping and a factory) | new `ui/XmbStepMotion.kt` | value, used by `XMBCategoryBar` and the lists |
| `xmbStepMotion(prev, next)`: Snap, Glide or SnapThenGlide | `ui/XmbStepMotion.kt` | pure function |
| `itemRowTopPx(...)`, `itemRowWindow(...)`, `itemRowClip(...)` | `ui/XmbStepMotion.kt` | pure functions, all in px |
| `XMBUiState.viewCursorKey()` (relocated, no behaviour change) | `viewmodel/XmbLists.kt` | pure extension |
| Animated `XMBItemList` / `XmbGameColumn` | `ui/XMBItemList.kt` | Compose |
| `columnKey` and `scrollToTopToken` threading | `ui/XMBShell.kt` | call sites |

### 3.2 Architectural decisions

1. **One spring value, shared.** `XmbStepSpring` lives in `ui/XmbStepMotion.kt`. It defines the
   stiffness (`Spring.StiffnessMediumLow`), the damping (`Spring.DampingRatioNoBouncy`) and a generic
   `SpringSpec` factory that optionally takes a `visibilityThreshold`. `XMBCategoryBar`'s `slide`
   and both lists use it.
   *Rules out:* re-typing `spring(stiffness = ...)` at any step-motion site.
   *Not in scope:* the row scale and alpha springs (`XmbVerticalListRow` `:660-674`) and the bar's
   icon and label springs. Those are a different motion, even though some of their numbers match.

2. **The list is positioned, never scrolled.** It follows the bar's own reasoning. A single
   animated float `p`, the selected index in rows, drives every row's offset. At rest `p` equals
   the target exactly, and offsets are pure arithmetic. `p` is held in a remembered `Animatable`
   created **at the current target**, so first composition and resume never glide. Its
   settle threshold must be at least as fine as the bar's 0.1 dp (`0.1.dp / ROW_HEIGHT` in rows),
   so the tail does not visibly snap.
   *Rules out:* LazyColumn, `animateScrollBy`, and a scroll state that could drift.

3. **Snap decisions apply in the frame they happen.** `xmbStepMotion` runs on the transition from
   one set of inputs to the next. When it says Snap or SnapThenGlide, the frame that first shows
   the new selection must already draw the snapped position. Without that, a Move draws the lifted
   row one row off for a frame. (`animate*AsState` with `snap()` applies the change a frame late.)
   *Rules out:* `animateFloatAsState(if (snap) snap() else spring)` for the list.

4. **Pixel identity is computed in px, mirroring today's layout.** The rest geometry is not the
   mockup's. Today's `Column` stacks rows at `round(belowTopY) + k × round(ROW_HEIGHT)` (plus
   `round(umdRowHeight) - round(ROW_HEIGHT)` for rows after the UMD row). Plain dp arithmetic
   drifts by up to half a px per row at 2.306 px/dp. The previous row is `requiredHeight(ROW)`
   inside a half-row box. Compose reports an oversized child at the box's size and **centres** it,
   so its top sits at `winTopPx + (winPx - rowPx) / 2` (integer division). That shows the row's
   **middle** half, not its bottom half as the comment at `:595-611` says.
   `itemRowTopPx` takes pre-rounded integer anchors (`belowTopPx`, `winTopPx`, `winPx`, `rowPx`,
   `umdIndex`, `umdExtraPx`) and a float `d = i - p`:
   - `d >= 0`: `belowTopPx + d × rowPx`, plus `umdExtraPx` if `i > umdIndex`
   - `-1 <= d < 0`: linear from the below slot (`d = 0`) to the previous slot (`d = -1`), as in the
     mockup's `yFor`
   - `d < -1`: `prevTopPx + (d + 1) × rowPx`

   The **P0 characterization test (task 1.1) is the referee.** It runs against today's code before
   anything changes, and if it disagrees with this formula, the formula is wrong.
   *Rules out:* placing rows with `offset(y = Dp)` in `XMBItemList`. `XmbGameColumn` already places
   rows by dp offset, so its rest identity holds by construction and it keeps the dp formula with
   `p` in place of `sel`.

5. **One composition per row, keyed by item.** Each visible row is composed once and keyed by
   `XMBItem.id`. Its clip depends on where it is (`itemRowClip(d)`):
   - **None**, for rows at or below the selected slot (`d >= 0`). Keeping these unclipped preserves
     the lifted row's move outline and the marked badge, which reach about 2.4 dp above the row at
     scale 1.06.
   - **Window**, the exact half-row window today uses, for `d <= -1`.
   - **BelowOrWindow**, the union of "below `belowTopPx`" and the window, for `-1 < d < 0`.
   - **Bottom limit**, for rows past the last whole slot while in transit (see decision 7).

   Keying by item means the lifted row keeps its composition across a Move, so it does not pulse
   0.9 → 1.06. It also means the row scale spring now runs on a normal step: the outgoing row
   shrinks as it rises and the incoming row grows. That is the mockup's behaviour. The spring
   constants are unchanged.
   *Rules out:* the mockup's two-layer approach, which draws every row twice. Its two copies would
   hold separate scale springs and disagree as a row crosses the bar, and each row's GIF and image
   state would be duplicated.

6. **Snap rules are one pure function.** The inputs are `(rawSelectedIndex, itemCount,
   scrollToTopToken, columnKey, moving)`, compared against the previous inputs. The target is
   `raw.coerceIn(0, max(count - 1, 0))`. Rules, in order:
   1. no previous inputs → Snap
   2. either raw index `< 0` → Snap (this covers the `AnimatedContent` outgoing copy and re-entry)
   3. either count is 0 → Snap (cleared-then-refilled lists)
   4. token changed → Snap
   5. `columnKey` changed → Snap
   6. `moving` either side → Snap
   7. `|newTarget - prevTarget| > 3` → SnapThenGlide(from = target ∓ 1)
   8. otherwise → Glide

   The jump length is measured between **targets**, not from the animated value. That keeps held
   repeat (always ±1) on the spring even when it trails by about 2.8 rows.

7. **Composed window.** At rest, `itemRowWindow` returns exactly today's set: `sel - 1` only when
   the raw index is in `1..lastIndex`, plus `sel until min(count, sel + rowsBelow)`. In transit it
   covers `floor(p) - 1 .. ceil(p) + rowsBelow - 1`, clamped to the list. **Default:** in transit,
   rows beyond the last whole slot are clipped at the line under that slot. No partial row ever
   rests and none pops out at the end. This needs the user's confirmation (§10 Q1).

8. **Column identity reuses `viewCursorKey`.** The relocated `XMBUiState.viewCursorKey()` is the
   only definition of "which list is on screen". The ViewModel's cursor memory and the list's snap
   rule both read it, so they cannot disagree.
   *Rules out:* a second hand-written key in `XMBShell`.

### 3.3 Rejected alternatives

- **The mockup's two-layer rendering** (every row in a "below" layer and in a "previous" window).
  It duplicates composition, splits scale state, and needs a hard top clip at `belowTopY` that
  would cut the lifted row's top chevron at rest. Decision 5 avoids all three.
- **`animateFloatAsState` + `snap()`, as the bar does.** It applies snaps a frame late (decision 3).
  The bar gets away with this. The list would not, because a Move reorders rows in that same frame.
- **Translating today's `Column` with `graphicsLayer`.** It keeps rest identity by construction,
  but it cannot move a row into the previous window, so the two-layer duplication comes back.
- **Measuring the long jump from the animated value.** Fast held repeat (lag about 2.8 rows) would
  get close to the threshold and start snapping. That contradicts "no special case for held repeat".
- **A user motion setting.** Rejected by the user.
- **Keeping `CenterLockedColumn`.** It is dead code with its own 70–240 ms tween, a second motion
  model. The user approved deleting it.

## 4. Non-goals

- `XMBCategoryBar` layout, its `anchor` channel, its icon, alpha and label springs, and its
  behaviour, except that `slide` now reads `XmbStepSpring`.
- `XmbVerticalListRow` visuals: scale and alpha targets and springs, text, decor, icon gating.
- The `AnimatedContent` category transition (`XMBShell.kt:903-910`) and its `-1` rule.
- `MusicBrowserScreen` (it uses its own `scrollToTopToken` and LazyColumn), the settings scaffold,
  pickers and every other list.
- The UMD read timing (`UmdSlotRead.kt`) and the 200 ms `umdRowHeight` tween.
- ViewModel navigation and cursor behaviour. Task 2.1 moves code only.
- `SiblingIcon` and `selectedIconBloom` (also unused). They are not part of the approved deletion.

## 5. Compatibility

No persistence, settings or theme-format changes. `previousRiseRows` is still theme-tunable, and
the formula uses whatever value the layout spec supplies. With a low rise (for example 0), the
previous window overlaps the bar band less, so a rising row can be partly visible in both regions
at once. It is one composition, so it is still one coherent row.

## 6. Test cases (write before the code they cover)

Module: `:feature:feature-xmb`. JVM tests go in `src/test/kotlin/com/playfieldportal/feature/xmb/...`.

### P0, must have

| # | Test file | Case | Assertion |
| --- | --- | --- | --- |
| 1 | `ui/ItemListRestLayoutTest.kt` (Robolectric, `qualifiers = "w833dp-h468dp-369dpi"` and a second class or parameter at `tvdpi`) | Today's rest layout, run on the **unmodified** list | For `selectedIndex` 0, 1, 3 and last, each tagged row's unclipped top in px is `belowTopPx + k × rowPx` for below rows and `winTopPx + (winPx - rowPx) / 2` for the previous row. Rows outside today's window are absent. Must pass **before and after** tasks 2.2 and 3.1. |
| 2 | `ui/XmbStepMotionTest.kt` | Rest offsets equal today's for every index | For count 12, rowsBelow 3, every sel, and every i in the window, `itemRowTopPx(d = i - sel)` matches the formulas in #1 exactly (integer-valued), at rowPx 203 / winPx 101 (2.306) and 117 / 59 (tvdpi) |
| 3 | `XmbStepMotionTest` | `d` in (-1, 0) | Strictly monotonic between `belowTopPx` (d=0) and `prevTopPx` (d=-1). d=-0.5 is the midpoint. Continuous at both ends. |
| 4 | `XmbStepMotionTest` | `d < -1` and `d > 0` | Linear at `rowPx` per row, continuous with the middle segment |
| 5 | `XmbStepMotionTest` | Snap: first composition | `xmbStepMotion(null, x)` is Snap(target) |
| 6 | `XmbStepMotionTest` | Snap: outgoing copy | raw 5 → -1 is Snap. -1 → 5 is Snap (re-entry). |
| 7 | `XmbStepMotionTest` | Snap: token, columnKey, moving, empty list | Each change alone, with Δ=1, is Snap. Count 0 → 9 is Snap. |
| 8 | `XmbStepMotionTest` | Long-jump boundary | Δ = 1 and Δ = 3 are Glide. Δ = 4 is SnapThenGlide(from = target - 1). Δ = -4 is SnapThenGlide(from = target + 1). |
| 9 | `XmbStepMotionTest` | Held repeat never snaps | A sequence of 20 successive +1 targets is Glide every time (the function never sees the animated value) |
| 10 | `XmbStepMotionTest` | Shared spring | `XmbStepSpring`'s stiffness is `Spring.StiffnessMediumLow`, its damping is `Spring.DampingRatioNoBouncy`, and the factory returns a `SpringSpec` with those values |

### P1, should have

| # | Test file | Case | Assertion |
| --- | --- | --- | --- |
| 11 | `ui/ItemListStepAnimationTest.kt` (Robolectric, `mainClock.autoAdvance = false`) | Step down glides | One frame after `selectedIndex` 2 → 3, row 3's top is strictly between its old and new rest tops. After `waitForIdle`, every row equals P0 #1's rest values. |
| 12 | `ItemListStepAnimationTest` | Snap is same-frame | With `LocalXmbRowDecor provides XmbRowDecor(movingLabel = "x")`, the **first** frame after 2 → 3 shows rest positions. The same applies to a `scrollToTopToken` bump and to a `columnKey` change. |
| 13 | `ItemListStepAnimationTest` | Outgoing copy | `selectedIndex` 4 → -1: the first frame shows today's `-1` layout (row 0 below the bar, no previous row) |
| 14 | `ItemListStepAnimationTest` | Long jump | 0 → 8: the first frame shows row 7 at the selected slot, then it glides to 8 |
| 15 | `ItemListStepAnimationTest` | Flyout game column | `XmbDrillFlyout` with games, `selectedIndex` 2 → 3: mid-flight between, and at rest `belowTopY + ROW_HEIGHT × (i - sel)` exactly as today |
| 16 | `viewmodel/XmbListsTest.kt` (existing) | `viewCursorKey` relocated | Same strings as before for root, each nav kind, platform and collection. Different nav gives a different key. |

### P2, nice to have

| # | Test file | Case | Assertion |
| --- | --- | --- | --- |
| 17 | `XmbStepMotionTest` | UMD extra | Rows `i > umdIndex` get `+umdExtraPx` and rows `i <= umdIndex` do not. With extra = 0 this equals #2. |
| 18 | `XmbStepMotionTest` | Window | At rest it equals today's set. Raw index past `lastIndex` has no previous row. In transit it covers `floor(p) - 1 .. ceil(p) + rowsBelow - 1`. |
| 19 | `XmbStepMotionTest` | Clip choice | `itemRowClip(0)` and `itemRowClip(2)` are None, `(-1)` and `(-1.5)` are Window, and `(-0.5)` is BelowOrWindow |

Rows in the Robolectric tests are found by `Modifier.testTag("xmbRow:${item.id}")`, placed on the
row's outer modifier **before** its `graphicsLayer`, so the bounds are unscaled. Task 1.1 adds that
tag.

## 7. Tasks

Each task is self-contained. Stop at its acceptance criteria.

### Task 1.1: Characterize today's rest layout
- **Objective.** Lock today's at-rest pixel geometry in a test that passes on the unmodified code.
- **Scope.** P0 #1. Add `testTag("xmbRow:<id>")` to the row modifiers in `XMBItemList`'s below
  `Column` and previous `Box`. Do not change layout or anything else.
- **Do not change.** Any offset, clip or size. `XmbVerticalListRow`. `XMBShell`.
- **Acceptance.** `ItemListRestLayoutTest` passes on current code at both densities, with no new
  warnings. If the previous-row top is not `winTopPx + (winPx - rowPx) / 2`, **stop and report the
  measured value**. Decision 4's formula then needs correcting before task 1.4.
- **Budget.** 1 production file (tag only) and 1 new test file.
- **Stop.** Do not start the math or the animation.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*ItemListRestLayoutTest*"`.

### Task 1.2: Shared step spring
- **Objective.** Add `XmbStepSpring` in `ui/XmbStepMotion.kt` and make `XMBCategoryBar.slide` use it.
- **Scope.** P0 #10. The bar keeps `if (moving) snap() else <shared spring>`. Note in the
  `XmbStepSpring` doc that the bar and the lists share it so they cannot drift.
- **Do not change.** The bar's other springs, its `anchor`, and any list code.
- **Acceptance.** P0 #10 passes, the bar compiles unchanged in behaviour, and there are no new
  warnings.
- **Budget.** 1 new file, 1 modified file, 1 new test file.
- **Stop.** Do not add the position math.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*XmbStepMotionTest*"`.

### Task 1.3: Delete `CenterLockedColumn`
- **Objective.** Remove the dead `CenterLockedColumn` (`XMBItemList.kt:431-502`) and the imports
  only it used.
- **Scope.** The function, its doc comment, and the now-unused imports: `LazyColumn`, `items`,
  `rememberLazyListState`, `PaddingValues`, `animateScrollBy`, `FastOutSlowInEasing`,
  `isUnspecified`, `LaunchedEffect`. Check each one with a search first. `tween`, `LocalDensity`
  and `BoxWithConstraints` are still used.
- **Do not change.** Anything else in the file, including `SiblingIcon` and `selectedIconBloom`.
- **Acceptance.** No reference to `CenterLockedColumn` remains, the module compiles with no new
  warnings, and P0 #1 still passes.
- **Budget.** 1 file.
- **Stop.** Do not clean up other dead code.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (b), then §9 (a) with `"*ItemListRestLayoutTest*"`.

### Task 1.4: Position math, window, clip and snap rules (pure)
- **Objective.** Add `itemRowTopPx`, `itemRowWindow`, `itemRowClip` and `xmbStepMotion` to
  `ui/XmbStepMotion.kt`, following decisions 4–7.
- **Scope.** P0 #2–#9 and P2 #17–#19. Pure Kotlin only, with no Compose runtime.
  `LONG_JUMP_ROWS = 3` is a named constant.
- **Do not change.** `XMBItemList.kt`, `XMBShell.kt`.
- **Acceptance.** All listed tests pass with no new warnings.
- **Budget.** 1 modified file (from 1.2) and 1 test file.
- **Stop.** Do not wire the functions into Compose.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*XmbStepMotionTest*"`.

### Task 2.1: Expose the column key
- **Objective.** Move `viewCursorKey` and its five nav-key helpers out of `XMBViewModel` into
  `viewmodel/XmbLists.kt` as `internal fun XMBUiState.viewCursorKey(): String`, with no behaviour
  change.
- **Scope.** P1 #16. The ViewModel's call sites (`:3521`, `:3524`, `:3528`) call the extension.
- **Do not change.** `navigateRememberingCursor` logic, the key strings, other ViewModel code.
- **Acceptance.** P1 #16 and the existing `XmbListsTest` and `CursorAfterRefreshTest` pass, with no
  new warnings.
- **Budget.** `XMBViewModel.kt`, `XmbLists.kt`, `XmbListsTest.kt`.
- **Stop.** Do not touch the shell.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*XmbListsTest*"`, then `"*CursorAfterRefreshTest*"`.

### Task 2.2: Animate `XMBItemList`
- **Objective.** Replace the two-piece snap layout with single-composition, keyed rows placed by
  `itemRowTopPx` from one `Animatable` position, following decisions 2–7.
- **Scope.** P1 #11–#14.
  - Add a `columnKey: Any? = null` parameter. `XMBShell` passes `uiState.viewCursorKey()` at `:922`.
  - Start reading `scrollToTopToken` and update its parameter comment.
  - Read `moving` from `LocalXmbRowDecor.current.movingLabel != null`.
  - The UMD extra belongs to the UMD row, not to the selected slot.
  - Read `p` in the layout and placement phase (`offset { }`). The composition should change only
    when the window changes.
  - Rewrite the cross diagram comment (`:542-552`) and the previous-row comment (`:595-611`) to
    describe the new mechanism truthfully, including the centred middle half.
- **Do not change.** `XmbVerticalListRow`, `XmbGameColumn`, `XmbDrillFlyout`, `XMBCategoryBar`, and
  the `AnimatedContent` spec and `-1` rule.
- **Acceptance.** P0 #1 still passes at both densities, P1 #11–#14 pass, and there are no new
  warnings.
- **Budget.** `XMBItemList.kt`, `XMBShell.kt`, 1 new test file.
- **Stop.** Do not animate the flyout game column.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*ItemListStepAnimationTest*"`, then `"*ItemListRestLayoutTest*"`.

### Task 3.1: Animate the drill flyout
- **Objective.** `XmbGameColumn` places rows at `belowTopY + ROW_HEIGHT × (i - p)` from the same
  kind of `Animatable` and the same `xmbStepMotion` rules.
- **Scope.** P1 #15.
  - Key the rows by item.
  - Build the window around `floor(p)` and `ceil(p)`.
  - Add `scrollToTopToken` and `columnKey` parameters to `XmbDrillFlyout` and pass them down to
    `XmbGameColumn`. `XMBShell` passes them at `:883`.
  - The left memory-card column is `XMBItemList` and inherits task 2.2. Verify only that it still
    looks the same.
- **Do not change.** `XMBItemList`'s internals, the flyout's horizontal layout and `◀` cursor, and
  the PIC0 logo overlay.
- **Acceptance.** P1 #15 passes, P0 #1 and P1 #11–#14 still pass, and there are no new warnings.
- **Budget.** `XMBItemList.kt`, `XMBShell.kt`, and the test file from 2.2.
- **Stop.** Stop here. Device check next.
- **If blocked.** Stop and report what you tried, what blocked you, which file is responsible, and
  what decision you need.
- **Test command.** §9 (a) with `"*ItemListStepAnimationTest*"`.

### Task 4.1: Device check (user)
The checklist is §8. The user drives the device.

## 8. Device checklist (Thor or Odin 3, `:app:installFullDebug`)

1. Games, Network, Music, Video and Settings: a single step glides. At rest it looks identical to
   the build before the change (compare screenshots).
2. Hold ▼ at FAST scroll speed: motion is continuous, with no stutter or snap.
3. Switch category ◀ ▶: the new column appears already seated. The outgoing column does not slide.
4. Sort (Square) and search: the list snaps to the top.
5. Settings ▸ a section ▸ Back, and Music ▸ All Music ▸ Back: snap, and the cursor is restored.
6. Arrange ▸ Move a row: the lifted row holds still, and its outline chevron is intact at rest.
7. Insert a UMD and focus it: the row grows as before. Step off it and nothing jumps.
8. Drill into a platform: the game cards glide. The memory-card column and `◀` stay put.
9. Resume from a game: nothing slides in.
10. Bottom edge in transit (§10 Q1): does the clip line read well?

## 9. Commands for the user

(a) One test class:
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*XmbStepMotionTest*"
```
(b) Compile and look for new warnings:
```bash
./gradlew :feature:feature-xmb:compileDebugKotlin 2>&1 | grep "^w:"
```

## 10. Open questions for the user

1. **The bottom edge in transit (decision 7).** The default clips rows at the line under the last
   whole slot, so no partial row shows and none pops when the list settles. The alternative lets
   rows slide to the screen edge and then disappear when the list settles. Confirm on the device.
2. **The row scale spring now runs on every step (decision 5).** Today it never runs, because slot
   0 is always "selected". The mockup shows the outgoing row shrinking and the incoming row
   growing. Confirm that this is wanted and is not counted as "scale unchanged".

## 11. Risks found in the code

- **R1. The previous row shows its middle half, not its bottom half.** `requiredHeight` overflow is
  centred by Compose, so the comment at `XMBItemList.kt:595-611` is wrong. The mockup's `PREV_TOP`
  (bottom half) would therefore shift the previous row by a quarter row. The rest geometry must
  come from the code, and task 1.1 confirms it.
- **R2. Non-integer px rows.** At 2.306 px/dp, dp-arithmetic placement drifts from the `Column`'s
  integer stacking (decision 4).
- **R3. A hard top clip at `belowTopY` would shave the lifted row's move chevron** and the marked
  badge at rest (about 2.4 dp overflow at scale 1.06). Decision 5 clips only rows above their slot.
- **R4. `scrollToTopToken` is dead today** and never reaches the flyout. Sort or search while
  drilled would glide or long-jump without task 3.1's threading.
- **R5. Level changes reuse the same composition.** Settings and the Music, Video, Photo, Social
  and Achievements views swap `currentItems` without re-keying `AnimatedContent`. Without
  `columnKey` they would glide or long-jump into a restored cursor.
- **R6. Both `AnimatedContent` copies read `uiState.currentItems`**, so the outgoing copy already
  shows the new category's rows at index 0. Snapping on `-1` keeps that existing behaviour. It
  does not fix it.
- **R7. Duplicate `XMBItem.id` values in one column would make `key` fall back to order.** Nothing
  in the code read proves ids are unique per column. If that turns up, key on index + id and report.
- **R8. Held-repeat lag of about 2.8 rows at FAST speed** keeps more rows in transit. The window
  must follow `p`, not the target.

## Execution Task Index

| ID | Task | Depends On | Status |
| --- | --- | --- | --- |
| 1.1 | Characterize today's rest layout (Robolectric, 2 densities, row test tags) | None | DONE |
| 1.2 | Shared `XmbStepSpring`, used by the category bar | None | DONE |
| 1.3 | Delete `CenterLockedColumn` and its orphaned imports | 1.1 | DONE |
| 1.4 | Pure position, window, clip and snap-rule functions with tests | 1.1, 1.2 | DONE |
| 2.1 | Relocate `viewCursorKey` to an `XMBUiState` extension | None | DONE |
| 2.2 | Animate `XMBItemList` (keyed rows, `Animatable`, snap rules, `columnKey` and token) | 1.3, 1.4, 2.1 | DONE |
| 3.1 | Animate the flyout game column, thread token and key into `XmbDrillFlyout` | 2.2 | DONE |
| 4.1 | Device check (§8) | 3.1 | READY |

## Resolved Decisions (2026-10-01, approved direction)
- Bottom edge: clip at the last whole slot while moving (no pop on settle).
- Row scale during a step: keying rows by item so the outgoing row shrinks and the incoming one grows is accepted — it matches the approved mockup.
- R1: the previous row's at-rest position is taken from the CODE (characterization test 1.1), not the mockup formula.
- Device check (4.1) is deferred to the user's end-of-run device pass; it does not block DONE.
