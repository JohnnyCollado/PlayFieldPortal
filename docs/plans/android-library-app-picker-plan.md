# Android Library & App Picker

Source: `assets/UI/ui samples/PFP_Android_Library_App_Picker_Design_Doc.md` (direct request).
Effort: M. Branch base: `ui-helper-buttons`.

This is an implementation handoff. It is written to be executed without the conversation that
produced it. Every line reference was verified against the working tree on 2026-09-04; re-check any
that has drifted, but do not assume a helper exists that is not named here.

---

## Problem

The design doc asks for three things: restore the Android Memory Card in the Library Manager, keep
Android and Windows out of the custom-emulator platform picker, and redesign the installed-app
picker as a controller-first grid. Investigation found the ground truth differs from the doc's
assumptions in two important ways.

**There is no Android Memory Card, and one cannot be created.**
`LibraryManagerViewModel.kt:335` filters Android out of the Add Console picker, and nothing else in
the codebase creates the card. Consequently:

- the Android branch of `onPlatformChosen` (`LibraryManagerViewModel.kt:359-373`) is dead code;
- the entire `isAndroid` detail branch in `LibraryManagerScreen.kt:489-506` — the Apps group, Add
  Apps row and per-app Remove rows — is dead code;
- the only route to Android app import is the XMB card context menu's "Find Games"
  (`XMBViewModel.kt:5058`), which itself requires a card that cannot exist.

So this is not "restore visibility". It is "make the feature reachable for the first time".

**The custom-emulator rule is already correct, and already separate.** There is no custom-emulator
*platform picker*: `EmulatorProfileEditorScreen.kt:146-151` is a free-text comma-separated
`Supported Platforms` field. The per-platform assignment screen that does have an exclusion,
`EmulatorAssignmentViewModel.kt:25`, keeps its own private `NON_EMULATOR_PLATFORMS = setOf("android", "windows")`
and shares nothing with the Library Manager. Doc §1.2 is satisfied as written.

**The picker itself** (`InstalledAppPicker.kt`) is a `LazyColumn` with a Confirm row at index 0,
hardcoded non-themed colors, additive-only commit, no pre-checking from existing membership, and no
search. All of its logic lives inside the ~7,200-line `XMBViewModel` and none of it is tested.

## Goal

Android is a first-class, always-present library platform whose Memory Card is the management entry
point. The installed-app picker is a controller-first grid that reads as a simplified App Drawer,
opens with current membership already checked, and commits an explicit add/remove diff on Apply.

## Decisions already made

These were settled with the project owner. Do not re-litigate them; if one turns out to be
impractical, stop and report rather than substituting your own.

1. **Apply is a full sync.** It adds newly-checked apps and removes newly-unchecked ones. Whenever
   removals are pending, a confirmation step runs first, so Apply can never silently delete a
   library entry.
2. **All four picker flows get the grid.** The overlay is shared by the Android library and by the
   Video / Music / Photo "Add Apps" flows. Pre-checking is target-aware.
3. **The Android card is auto-created** on Library Manager load *and* remains selectable in Add
   Console, so a user who removes it can get it back.

## Already correct — verify, then leave alone

These are acceptance criteria in the doc that already pass. Touching them is a regression risk.

| Requirement | Where it already happens |
|---|---|
| Windows hidden from the Consoles list | `LibraryManagerScreen.kt:273` — `state.cards.filterNot { it.platformId == "windows" }` |
| Windows/Android excluded from emulator assignment | `EmulatorAssignmentViewModel.kt:25,209` |
| The two filtering rules stay separate | Three independent call sites today. **Do not consolidate them into one shared "unsupported platform" helper** — doc §1.2 forbids it explicitly |
| Android card exposes no ROM/emulator/extension/scan UI | `LibraryManagerScreen.kt:489-506` already omits all of it |
| Selection is staged, not persisted on toggle | `XMBViewModel.kt:5483-5496` mutates only the in-memory `selected` set |
| Stale-index scroll clamp | `InstalledAppPicker.kt:57-61`, added in `63559a5` — **preserve** |
| Duplicate-launcher-activity dedupe | `InstalledAppRepository.kt:72-73`, added in `25490ea` — preserve |

---

## Task 1 — Make the Android Memory Card exist

**File:** `feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/LibraryManagerViewModel.kt`

**1a. Auto-create the card.** Add a private `ensureAndroidCard()`:

```kotlin
private suspend fun ensureAndroidCard() {
    if (memoryCardRepository.unconfiguredPlatforms().none { it.id == ANDROID_PLATFORM_ID }) return
    memoryCardRepository.addCard(
        platformId  = ANDROID_PLATFORM_ID,
        displayName = defaultDisplayName("Android"),
        romDirectory = null,
        emulatorId  = null,
    )
}
```

`unconfiguredPlatforms()` (`core-data/.../repository/MemoryCardRepository.kt:43`) returns catalog
platforms with no card, so this is idempotent. Call it once from the ViewModel's `init`, before/
alongside the first `observeAll()` collection, so the card is present in the first rendered
emission. The Windows card's self-heal in `openWindowsGamesRoot()` (`:240-249`) is the local
precedent for this pattern.

**1b. Fix the Add Console filter.** `LibraryManagerViewModel.kt:334-336` currently reads:

```kotlin
val options = memoryCardRepository.unconfiguredPlatforms()
    .filter { it.id != ANDROID_PLATFORM_ID }
```

Invert it — exclude **Windows**, not Android:

```kotlin
    .filter { it.id != WINDOWS_PLATFORM_ID }
```

Windows must not be selectable because `LibraryManagerScreen.kt:273` hides the Windows card from the
Consoles list; adding one would create an entry the user can never open.

**1c. Move the ROM-root gate off the Android path.** `startAddConsole()` (`:326-332`) returns early
with *"Set up a ROM Root first…"* when `romRootRepository.getAll()` is empty. Android needs no ROM
root, so with 1b applied this gate would make the Android card unaddable on a fresh install.

Move the check out of `startAddConsole()` and into the non-Android branch of `onPlatformChosen`. The
ROM-root check already exists there (`:377-382`) — so in practice this is a deletion from
`startAddConsole()`, but confirm the message still surfaces for a ROM console before removing it.

The dead Android branch at `:359-373` already does exactly the right thing (create card with no
directory or emulator, reset to list, show a pointer message) and becomes live with no edit.

**Leave untouched:** `:914` (ES-DE folder creation skips `android`) and `RomRootScanRunner.kt:164`
(scan loop skips `windows`). Both are correct.

**Done when:** on a fresh install the Library Manager shows an Android Memory Card row under
Consoles and no Windows row; Add Console lists Android and not Windows; opening the Android card
shows only Apps / Add Apps / (app rows) / Rename / Show In Games / Pin To Top / Move Up / Move Down /
Remove Memory Card — no ROM Directory, Emulator, Supported Files or Scan rows.

---

## Task 2 — Extract the picker logic (do this before any UI work)

Do **not** move the picker into its own `@HiltViewModel`. The overlay is gated by
`XMBUiState.appPicker`, counted as a modal at `XMBViewModel.kt:727`, and its gamepad routing at
`:4179-4192` depends on that ownership. Rewiring it is out of scope and destabilizes the modal/input
model for no gain.

Instead extract the **pure** logic so it can be unit-tested, and keep `XMBViewModel` as the owner.
This is the "smallest clean refactor" doc §18 asks for.

**2a. Extend `AppPickerState`** (`XMBViewModel.kt:227-239`):

```kotlin
data class AppPickerState(
    val title: String,
    val target: AppPickerTarget,
    val apps: List<AppPickerEntry>,
    val selected: Set<String> = emptySet(),
    /** Membership at open time — the baseline Apply diffs against. */
    val initialSelected: Set<String> = emptySet(),
    /** Index into visibleApps(), NOT into apps. */
    val focusedIndex: Int = 0,
    val query: String = "",
    val searchActive: Boolean = false,
    val confirmingRemovals: Boolean = false,
    /** Mirrors AppDrawerUiState.usingTouch — hides the cursor and suppresses auto-scroll. */
    val usingTouch: Boolean = false,
)
```

`focusedIndex` replaces `selectedIndex`, and **the Confirm row at index 0 goes away** — Apply moves
to the footer action, so index 0 is now a real app. Update every reference; the compiler will find
them.

**2b. New file:** `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/AppPickerLogic.kt`

Top-level pure functions, no ViewModel and no coroutines — same shape as the existing testable
predicates `shouldShowContextMenuHint` / `shouldShowAppDrawerHint` (`XMBViewModel.kt:936,959`):

```kotlin
internal fun AppPickerState.visibleApps(): List<AppPickerEntry>
internal fun AppPickerState.toggle(pkg: String): AppPickerState
internal fun AppPickerState.move(action: GamepadAction): AppPickerState
internal fun AppPickerState.clampFocus(): AppPickerState
internal fun AppPickerState.pendingAdds(): Set<String>       // selected - initialSelected
internal fun AppPickerState.pendingRemovals(): Set<String>   // initialSelected - selected
```

Rules that matter:

- `visibleApps()` filters on `label.lowercase().contains(query.lowercase())` when `query` is
  non-blank — mirroring `AppDrawerViewModel.kt:311-333`. It must never touch `selected`.
- `toggle` changes `selected` **only**. It must not move `focusedIndex` and must not close anything
  (doc §9).
- `clampFocus()` must return a valid index for every input, including an empty `visibleApps()`.
- `move` does not wrap in any direction, matching `AppDrawerViewModel.kt:284-299`.

**2c. Shared grid-move helper.** `AppDrawerViewModel.kt:284-299` and
`ArtworkStudioViewModel.kt:921-927` already carry two copies of the same modulo math. Add a pure
helper to `core/core-navigation` rather than a third:

```kotlin
fun gridMove(current: Int, action: GamepadAction, columns: Int, size: Int): Int?
```

Returns `null` when the move is illegal (edge of row, past the first/last row, empty list) so the
caller can no-op. `AppPickerLogic.move` must use it. Retrofitting the two existing call sites is
optional — if you do it, make it a separate commit.

---

## Task 3 — The grid picker UI

**Rewrite:** `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/InstalledAppPicker.kt`

Keep it a stateless composable driven by `AppPickerState` plus callbacks, so `XMBShell.kt:942-950`
changes only by gaining new callback parameters. Split into private composables — either in the same
file or a small `ui/apppicker/` package: `AppPickerHeader`, `AppPickerGrid`, `AppPickerTile`,
`AppPickerSearch`, `AppPickerFooter`.

### Theming (doc §14) — this is the change that stops it being permanently blue

Delete the hardcoded `PickerScrim` / `PickerText` / `PickerSubtext` / `PickerCheck` constants
(`InstalledAppPicker.kt:40-43`). Use `deriveStorefrontColors()`
(`core-ui/.../theme/StorefrontColors.kt:173`), which is what the redesigned App Drawer uses. Root
background: `Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))`, matching
`AppDrawerScreen.kt:214-223`.

> **Do not** read `LocalPFPColors.current.accentColor` directly for the accent. Preset XMB schemes
> resolve it to white, which repaints every theme identically — the gotcha is documented at
> `StorefrontColors.kt:149`. `deriveStorefrontColors()` handles the hue-source fallback
> (vivid accent → vivid wave → `backgroundBottom`).

### Header (doc §5)

Model on `AppDrawerHeader.kt:52`: 56.dp tall, `horizontal = 24.dp`, no background of its own so the
root gradient shows through. Left is `‹` plus the picker title, both tappable as Back
(`AppDrawerHeader.kt:75-100`). Right is `"${state.selected.size} Selected"` in `sf.textSecondary`,
updating on the same frame as a toggle.

### Search (doc §12)

Copy `AppDrawerHeader.kt:105-131`: an `AnimatedVisibility(fadeIn/fadeOut)`-wrapped `BasicTextField`,
220.dp wide, `ImeAction.Search`, `cursorBrush = SolidColor(sf.searchBorder)`, placeholder via
`decorationBox`. Copy the two-frame focus idiom from `AppDrawerScreen.kt:199-212` — a
`LaunchedEffect(searchActive)` that burns two `withFrameNanos {}` frames before
`runCatching { searchFocus.requestFocus() }` then `keyboard?.show()`. The magnifier is hand-drawn on
a `Canvas(Modifier.size(18.dp))` (`AppDrawerHeader.kt:142-170`), not an icon vector.

Selections must survive filtering, and clearing the query must restore them. Because `toggle` only
ever touches `selected` and `visibleApps()` only ever filters, this falls out for free — but it is
an acceptance criterion, so test it.

### Grid (doc §6)

Model on `AppDrawerGrid.kt:37`. `GridCells.Fixed(PICKER_GRID_COLUMNS)` with
`const val PICKER_GRID_COLUMNS = 7` — one denser than the drawer's `GRID_COLUMNS = 6`
(`AppDrawerViewModel.kt:16`), per doc §6. Declare the constant next to the code doing the modulo math
so the layout and the navigation can never drift apart. Key the items:
`itemsIndexed(visible, key = { _, app -> app.packageName })`.

Copy both touch-reconciliation `LaunchedEffect`s verbatim from `AppDrawerGrid.kt:55-78`:
drag-start → `onTouchBrowse(gridState.firstVisibleItemIndex)`, and scroll-settle → the visible item
nearest the viewport center.

Scroll-into-view, extending the clamp at `AppDrawerGrid.kt:49-52`:

```kotlin
LaunchedEffect(focusedIndex, usingTouch, visible.size) {
    if (!usingTouch && visible.isNotEmpty()) {
        gridState.animateScrollToItem(focusedIndex.coerceIn(0, visible.lastIndex))
    }
}
```

Keying on `visible.size` closes a real gap: the current picker's effect
(`InstalledAppPicker.kt:57-61`) keys only on the index, so it never re-clamps when the list shrinks
under a stationary cursor. Doc §16 requires exactly that case to be safe.

### Tile — focus and selection must be visually distinct (doc §7)

**Focus** — copy `AppDrawerGridItem.kt:47-92` exactly: three `matchParentSize()` boxes inside a
`Modifier.size(ARTWORK_SIZE + FRAME_ROOM)` — a glow plate, an outer `sf.tileSelectedEdge` border, and
a 2.dp-inset hairline — all alpha-driven by one `animateFloatAsState(tween(120))`. Geometry is
constant; no scale, no bounce, no elevation (doc §15).

**Selection** — an independent layer that does not depend on focus:

- a small check badge in the tile's upper-right, `sf.accentEdge` fill with a high-contrast glyph,
  drawn whenever `packageName in selected`;
- optionally a persistent low-alpha tint or border from `sf.tileSelectedInner` so selection reads
  from across the grid;
- appearance animates alpha only, ~100ms. **Never gate input on the animation.**

A focused *and* selected tile shows both treatments. The check mark must stay visible when the
cursor moves away — that is the single most-repeated requirement in §7.

The whole tile is the touch target: `clickable { onToggleAt(index) }`. No separate hit area on the
check mark (doc §9).

### Icons

`PickerAppIcon` (`InstalledAppPicker.kt:162-177`) calls `packageManager.getApplicationIcon()` on the
main thread inside `remember`. In a 7-wide grid that runs for many more visible items than the old
list. `AppCategoryRepository.allInstalledApps()` (`AppCategoryRepository.kt:59`) already holds a
loaded `Drawable` on each `InstalledApp`, resolved on `Dispatchers.IO`
(`InstalledAppRepository.kt:83-84`) and cached. Carry it through — add the `Drawable` to
`AppPickerEntry`, or carry `InstalledApp` directly — so the binder call happens once, off the main
thread, in `openAppPicker`. Keep `rememberDrawablePainter` and the `Spacer(Modifier.size(...))`
fallback for a null icon.

### Footer (doc §13)

`ControllerPromptBar` (`core-ui/.../components/ControllerPrompt.kt:201`) with `ControllerPromptItem`s
only. **No hardcoded PS/Xbox/Nintendo glyphs** — the family resolves from
`LocalControllerPromptStyle`.

| Label | Item |
|---|---|
| Navigate | `ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate")` |
| Toggle | `ControllerPromptItem(GamepadAction.SELECT, "Toggle")` |
| Search | `ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search")` |
| Apply | `ControllerPromptItem(GamepadAction.HOME, "Apply")` |
| Cancel | `ControllerPromptItem(GamepadAction.BACK, "Cancel")` |

`CHANGE_SORT` for search matches the App Drawer's existing convention
(`AppDrawerScreen.kt:96-118`); `HOME` for Apply preserves the picker's current binding
(`XMBViewModel.kt:4186`), so both muscle memory and the existing routing survive.

The footer is an **overlay child of the root `Box`** aligned `Alignment.BottomCenter`, never a row in
the content `Column` — that is precisely how `AppDrawerScreen.kt:291-304` keeps grid geometry stable
when the helper fades. If it fades, use `enter = fadeIn(tween(200)), exit = ExitTransition.None`.

### Empty state (doc §16)

When `visibleApps()` is empty, render `No installed apps match "<query>"` centered in
`sf.textSecondary`. Back and Search/Clear stay reachable — the footer is an overlay, so it already
is. Never call `animateScrollToItem` with an empty list.

### Remove the whole-background dismiss

`InstalledAppPicker.kt:73` puts `.clickable(onClick = onDismiss)` on the full-screen background. With
a grid and a search field that becomes an easy accidental cancel. Delete it; Back and the header's
`‹` are the exits.

---

## Task 4 — Pre-checking and full-sync Apply

**File:** `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt`

### 4a. Seed membership in `openAppPicker` (`:5466-5474`)

Resolve current membership per target and seed **both** `selected` and `initialSelected` (doc §8):

- `AppPickerTarget.AndroidGames(platformId)` → `gameRepository.observeByPlatform(platformId).first()`
  (declared `core-domain/.../repository/GameRepository.kt:26`), collecting the non-null
  `packageName`s.
- `AppPickerTarget.CategoryShortcuts(categoryId)` → the packages already in that category. Add
  `suspend fun packagesIn(categoryId: String): Set<String>` to `AppCategoryRepository` (it already
  has `getCategoriesForApp` / `getAppItems` at `:117` / `:68`) rather than reaching into
  `CategoryDao` from the ViewModel.

### 4b. Diff-based commit, replacing `confirmAppPicker` (`:5510-5527`)

```
adds     = pendingAdds()
removals = pendingRemovals()
if (adds.isEmpty() && removals.isEmpty())            -> close, done
if (removals.isNotEmpty() && !confirmingRemovals)    -> set confirmingRemovals = true; return
```

On the confirmed pass:

- **AndroidGames** — adds go through the existing `importAndroidGames(platformId, adds)`
  (`:5591-5623`) **unchanged**; it is already idempotent. Removals reuse the exact path the Library
  Manager's Remove row uses (`LibraryManagerViewModel.kt:230-235`): `gameRepository.getAppEntry(pkg)`
  then `delete(id)`. Call `memoryCardRepository.recountGames(platformId)` **once** after the whole
  batch, not per app — note `importAndroidGames` already calls it at its end, so restructure so it
  runs a single time after both halves.
- **CategoryShortcuts** — `appCategoryRepository.addToCategory(pkg, categoryId)` (`:103`) and
  `removeFromCategory(pkg, categoryId)` (`:109`).

Close the picker only after the work is queued.

### 4c. Removal confirmation panel

A centered panel over the grid. Copy the construction of `UninstallConfirmDialog`
(`AppDrawerOptions.kt:120`) — a hand-built `Box` scrim plus a `PANEL_CORNER = 2.dp` /
`PANEL_BORDER = 1.dp` panel. **Not** a Material `AlertDialog`; the redesign deliberately removed
those. Copy: `Remove N app(s) from this library?` with the affected labels listed. `SELECT` confirms,
`BACK` returns to the grid with the selection intact.

### 4d. Input routing (`:4179-4192`)

```kotlin
NAVIGATE_UP / DOWN / LEFT / RIGHT -> moveAppPicker(action)      // AppPickerLogic.move
SELECT      -> if (confirmingRemovals) commitAppPicker() else toggleFocusedApp()
HOME        -> requestApply()
CHANGE_SORT -> toggleSearch()
BACK, OPEN_CONTEXT_MENU ->
    if (searchActive)        closeSearch()
    else if (confirmingRemovals) cancelConfirm()
    else                     closeAppPicker()
```

Back unwinds one layer at a time — search, then the confirmation, then the picker. This is the
"Back does not accidentally corrupt the Android library" criterion (doc §11).

### 4e. Touch / controller reconciliation (doc §17)

`usingTouch` (added in Task 2a) mirrors `AppDrawerUiState.usingTouch`
(`AppDrawerViewModel.kt:45-48`): set `true` in the touch entry points (`onAppPickerActivatedAt`,
tile taps, `onTouchBrowse`), cleared on the first gamepad action the way `AppDrawerViewModel.kt:279`
does it. Render focus chrome only when `!usingTouch`, and suppress auto-scroll while it is true.
Also call `markTouchInput()` (`XMBViewModel.kt:5886`) from the picker's touch callbacks so the
app-wide idle-hint tracker stays consistent — `AppDrawerScreen.kt:141-165` is the precedent for
wrapping every touch-originated callback that way.

---

## Task 5 — Tests

There are currently zero tests for the picker and no `XMBViewModelTest`. Task 2's extraction exists
so that can be fixed without one.

**New:** `feature/feature-xmb/src/test/kotlin/com/playfieldportal/feature/xmb/viewmodel/AppPickerLogicTest.kt`
— plain JUnit, no Robolectric, no coroutine test dispatcher. Shape it like
`feature-achievements/.../localsteam/LocalSteamConvertPickerControllerTest.kt`.

Required cases:

- `toggle` adds then removes the same package; `focusedIndex` is unchanged both times.
- `pendingAdds` / `pendingRemovals` diff correctly against `initialSelected`, including the
  toggled-off-then-back-on case, which must produce two empty sets.
- Search: filtering hides a selected package, `selected` still contains it, clearing the query
  brings it back checked.
- `clampFocus()` on an empty list, on a list that shrank under a high index, and after a filter
  removed the focused item — never returns an out-of-range index.
- `move` refuses to wrap left off column 0 and right off the last column, refuses to leave the grid
  vertically, and is a no-op on an empty list.

If the `feature-settings` test setup makes it cheap, also assert the Add Console option list contains
`android` and excludes `windows`; otherwise verify it by hand in step 2 below.

---

## Files touched

| File | Change |
|---|---|
| `feature-settings/.../viewmodel/LibraryManagerViewModel.kt` | `ensureAndroidCard()`; Add Console excludes Windows not Android; ROM-root gate off the Android path |
| `feature-xmb/.../viewmodel/AppPickerLogic.kt` | **new** — pure selection / search / grid-move / diff |
| `feature-xmb/.../viewmodel/XMBViewModel.kt` | `AppPickerState` fields; pre-check in `openAppPicker`; diff commit; grid input routing; `usingTouch` |
| `feature-xmb/.../ui/InstalledAppPicker.kt` | **rewrite** — themed grid, header, search, footer, removal confirm |
| `feature-xmb/.../ui/XMBShell.kt` | new callbacks at the `InstalledAppPicker` call site (`:942-950`) and their params |
| `feature-appbar/.../AppCategoryRepository.kt` | add `packagesIn(categoryId): Set<String>` |
| `core-navigation/.../` | **new** pure `gridMove(...)` |
| `feature-xmb/src/test/.../AppPickerLogicTest.kt` | **new** |

Unchanged on purpose: `EmulatorAssignmentViewModel.kt`, `EmulatorProfileEditorScreen.kt`,
`InstalledAppRepository.kt`, `RomRootScanRunner.kt`, and both the console-list filter and the
`isAndroid` detail branch in `LibraryManagerScreen.kt`.

## Non-goals (doc §19)

Do not rewrite the Android app repository, change how packages are discovered, turn Android into a
ROM-scanned platform, add Android to emulator assignment, add Windows to the Library Manager list,
redesign the rest of the Library Manager, duplicate the App Drawer wholesale, introduce Sony
trademarks or assets, hardcode controller glyphs, introduce Material cards, remove existing bounds
protections, or break touch.

---

## Verification

Ask the project owner before running Gradle — builds are not run unprompted in this repo.

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest :feature:feature-settings:testDebugUnitTest :core:core-navigation:test
```

On device, the owner drives navigation; request screenshots only when they say they are ready.

1. **Library Manager** — Android Memory Card row present, Windows absent. Open Android: Apps / Add
   Apps only; no ROM Directory, Emulator, Supported Files or Scan rows.
2. **Add Console** — Android listed, Windows not. Adding Android with no ROM Root configured
   succeeds and shows no ROM Root warning.
3. **Pre-checked open** — add three apps, reopen the picker: those three show check marks with the
   cursor parked elsewhere.
4. **Focus vs. selection** — move the cursor off a checked tile; the check mark stays and the focus
   border moves. A focused-and-checked tile shows both.
5. **Toggle** — Confirm toggles without closing the picker and without moving the cursor; the header
   count updates immediately.
6. **Search** — filter to a substring, clear it; previously checked apps that were hidden are still
   checked. Type a nonsense query: empty-state copy renders, Back and Clear both work, no crash.
7. **Bounds** — hold Down to the last row; hold Right on the last column; search down to a single
   result with the cursor previously on the last item. No crash, no stranded cursor.
8. **Apply / Back** — uncheck an existing member, press Apply: the removal confirmation appears.
   Cancel leaves the library untouched. Confirm removes it and the Memory Card's game count updates.
   Back out of a dirty picker: nothing changed.
9. **Touch** — finger-scroll, tap a tile to toggle, then press a d-pad direction. The cursor must
   reappear on a visible tile, never off-screen or on an unrelated tile.
10. **Accents** — switch XMB themes, at least three including a preset whose accent resolves to
    white. Background, focus border, check badge and footer must all change.
11. **Footer stability** — let the helper fade in and out. The grid must not shift vertically.
12. **Category flows** — Video / Music / Photo "Add Apps" open pre-checked from existing category
    membership, and unchecking removes from that category only.
