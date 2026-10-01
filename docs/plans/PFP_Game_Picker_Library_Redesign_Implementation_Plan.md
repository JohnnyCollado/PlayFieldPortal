# Play Field Portal — Game Picker Library Redesign: Implementation Plan

Rebuild "Add Games to Category" as a shelf-style art grid in the App Picker's visual language.
Mockup: `docs/mockup/Game_Picker_Library_Mockup.html` (interactive, every XMB preset).

**Status (2026-10-01): implemented.** Logic in `ui/GamePickerLogic.kt` (tests:
`GamePickerLogicTest`), screen and ViewModel rewritten, `GameIcon` takes `naturalArtHeight`, and
`XMBViewModel` forwards every action to the picker. The defaults chosen for §7: "In {Category}" lists
membership at open, and empty consoles stay hidden. Search is still out.

**Revised after device testing. These rules replace §3 where they differ:**
- **Two levels, as in the Artwork Studio.** The shelf list is a vertical tab list, so the D-pad
  steps through it. The picker opens on the list, A goes down into the grid, B comes back up, and
  B on the list cancels. LB/RB belong to horizontal tabs and do nothing here. Left/right never
  cross between the list and the grid.
- **One view for every tile.** The picker draws every game in one icon display mode, whatever
  each game or console uses in the XMB. A PS1 + GBA shelf in Physical Media shows discs and
  cartridges throughout. It starts on the user's global mode, and X steps through the four
  modes. There is no "Library" (mixed) option.
- **The cursor hugs the art.** The focus frame, tint and badge wrap the art box. That box takes
  the view's shape (144:80, the platform's box, or square for Physical Media) and stands on the
  ledge. The current shelf in the list gets the same focus chrome while the cursor is there.
- **Shelf names** drop the " Memory Card" suffix. Both pickers now play the user's Navigation sound (Interface ▸ Sound): a cursor move
is SCROLL, a toggle, whole-shelf or view change is SELECT, and a shelf change is SYSTEM_BROWSE. Inputs that
change nothing stay silent (`gamePickerSound`, `appPickerSound`).

---

## 1. Goal

| Surface | File | Today |
|---|---|---|
| Game picker screen | `feature-xmb/.../ui/GamePickerScreen.kt` (322 ln) | M3 `Checkbox` list of collapsed platform headers; hardcoded `0xFF574DDB` / `0xFFC9C7E8`; no artwork |
| Game picker state | `feature-xmb/.../ui/GamePickerViewModel.kt` (333 ln) | Flat `selectedItemId` list (`buildPickerItemIds`), up/down only, expand/collapse map |
| Input routing | `XMBViewModel.kt:6164` | Forwards only UP, DOWN, SELECT, HOME, BACK, OPEN_CONTEXT_MENU |

Target: the picker reads like a library. There is a shelf list on the left. The right side is a
6-column grid of the current shelf, and every game is drawn in its resolved `IconDisplayMode`.
Mixed shapes (ICON0 144:80, Box Art, 3D Box, disc and cartridge) sit on one ledge line per row.
All color comes from `deriveStorefrontColors()`, the same derivation as the App Picker and App
Drawer. Never use `LocalPFPColors.accentColor` directly, because presets resolve it to white.

**Scope:** layout, navigation and theming only. What confirming does stays the same:
`confirmGamePicker` (`XMBViewModel.kt:8222`) still adds new games, removes only games that
started checked and were then unchecked, and moves picked memory cards.

---

## 2. Layout

```
┌ ‹ Add Games · RPGs                         +6 adding · −1 removing   🔍 Search ┐
├──────────────────── chromeDivider ──────────────────────────────────────────────┤
│ In RPGs        10/10 │  PlayStation 2      14 games · 3 checked   View: Library │
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬ │  [box][box][3D ][box][box][box]                         │
│▌PlayStation 2   3/14 │ ─────────────── ledge ──────────────────                │
│ PSP             2/9  │  title  title  title  title  title  title               │
│ Game Boy Advance 1/8 │  [box][box] …                                           │
│ …                    │                                                         │
│ Memory Cards    0/3  │                                                         │
├──────────────────────────────────────────────────────────────────────────────────┤
│  ✛ Navigate  A Toggle  L1 R1 Shelf  Y Whole shelf  X View  ≡ Done  B Cancel     │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Shelves (order)
1. **In {Category}**: games in the category when the picker opened (`preselectedGameIds`).
2. **One per enabled memory card** that has at least one `GameContentType.GAME` game. This is
   the same filter as today's `loadData`, and empty consoles stay hidden.
3. **Memory Cards**: `visibleCollections` (movable game cards from other gaming categories),
   drawn as card tiles with "N games · in {source category}". Hidden when there are none.

A game appears on its console shelf **and** on "In {Category}". Both tiles share one checked
state, because selection is keyed by game id and not by tile.

### Tile
- Slot height is fixed. Art is **bottom-aligned** so every shape stands on the row's ledge.
- Focus chrome comes from `AppPickerTile` (glow, outer edge and inner hairline, alpha only, no
  scale) and uses the same 120ms tween.
- Checked: `PfpCheckBadge` in the top-right plus a 10% `tileSelectedInner` tint. Both are
  independent of focus.
- Pending removal (started checked, now unchecked): art at 45% alpha, a dashed `destructive`
  ring where the badge goes, and the label prefixed "Removing ·".
- Label: 11sp, 2 lines, `textSecondary`, changing to `textPrimary` when focused.

### Ledge
One `chromeDivider` line at 35% alpha under each row's art, plus an 8dp black-to-transparent
gradient beneath it. It is drawn once per row in the grid's `drawBehind`, not per tile.

### Shelf rail
196dp wide on a `railBackground` fill. Each entry has a name, a tabular `checked / total` count,
and a 2dp `tileSelectedInner` progress line. The current shelf uses `categorySelected` with a 2dp
right edge in `categorySelectedEdge`, matching `AppDrawerCategoryTabs`. When the cursor is in
the rail, the current entry also gets a 1dp edge outline.

### Color roles

| Element | `StorefrontColors` role |
|---|---|
| Screen gradient | `backgroundDeep` → `backgroundMid` |
| Header rule, ledge, focus outer border, check badge | `chromeDivider` / `tileSelectedEdge` |
| Focus inner hairline, rail progress, checked tint | `tileSelectedInner` |
| Focus wash | `selectionGlow` |
| Rail, current shelf | `railBackground`, `categorySelected`, `categorySelectedEdge` |
| Text | `textPrimary`, `textSecondary` |
| Removal mark | `destructive` |

The pale presets (Silver, Golden Amber, Sakura) flip `storefrontColorsFor` to its light
direction with dark text and darkened edges. Nothing in the picker should assume white text.

---

## 3. Controls

| Input | `GamepadAction` | Grid | Rail |
|---|---|---|---|
| D-pad | `NAVIGATE_*` | `gridMove(…, GAME_PICKER_GRID_COLUMNS)`, no wrap. LEFT returning `null` (column 0) enters the rail | UP/DOWN change shelf live; RIGHT returns to the grid |
| A | `SELECT` | Toggle game / card | Enter grid |
| L1 / R1 | `PREV_CATEGORY` / `NEXT_CATEGORY` | Previous / next shelf. The cursor returns to that shelf's remembered index | same |
| Y | `OPEN_CONTEXT_MENU` | Check the whole shelf; uncheck it if every item on it is checked. Replaces "Expand / Collapse" | same |
| X | `CHANGE_SORT` | Cycle the view override: Library → Custom Icon → Box Art → Physical Media → 3D Box | same |
| Start | `HOME` | Done | same |
| B | `BACK` | Cancel | same |

Touch: tap a tile to toggle, tap a rail entry to switch shelf, and use the header ‹ to cancel.
Drag-scroll uses `AppPickerGrid`'s reconciliation (drag-start parks the hidden cursor, and
scroll-settle moves it to the tile nearest the centre).

**Search is out of scope for the first pass.** `CHANGE_SORT` is search in the App Picker, but
here it is the view toggle. See §7.

---

## 4. Work items

### 4.1 State (`GamePickerViewModel.kt`)
Replace `selectedItemId`, `platformExpandedStates` and `buildPickerItemIds` with:

```kotlin
data class GamePickerState(
    val shelves: List<PickerShelf> = emptyList(),
    val shelfIndex: Int = 0,
    val focusZone: PickerZone = PickerZone.GRID,       // RAIL | GRID
    val focusByShelf: Map<String, Int> = emptyMap(),    // shelf key → grid index
    val viewOverride: IconDisplayMode? = null,          // picker-local, never persisted
    val selectedGameIds: Set<Long> = emptySet(),
    val selectedCollectionIds: Set<Long> = emptySet(),
    val preselectedGameIds: Set<Long> = emptySet(),
    val movableCollectionIds: Set<Long>? = null,
    val isLoading: Boolean = false,
    val usingTouch: Boolean = false,
)

sealed interface PickerShelf { val key: String; val title: String }
data class GameShelf(override val key: String, override val title: String, val games: List<Game>) : PickerShelf
data class CardShelf(override val key: String, override val title: String, val cards: List<GameCollection>) : PickerShelf
```

Keep `prepare()` and `clearSelection()` behaving as they do now: selections never carry over
between openings, and a library update keeps what is checked. `clearSelection()` also resets
`shelfIndex`, `focusByShelf`, `focusZone` and `viewOverride`.

### 4.2 Pure logic (`feature-xmb/.../viewmodel/GamePickerLogic.kt`, new)
This mirrors `AppPickerLogic.kt` and has no Android types:
- `buildShelves(cards, games, collections, preselected, movable, categoryName)`
- `GamePickerState.move(direction)`: grid uses `gridMove`; a `null` on LEFT moves to `RAIL`;
  rail UP/DOWN clamps `shelfIndex`.
- `GamePickerState.stepShelf(delta)`, `toggleFocused()`, `toggleWholeShelf()`,
  `cycleView()`
- `GamePickerState.pendingAdds()` and `pendingRemovals()` feed the header count and must match
  `confirmGamePicker`'s math exactly.
- Re-clamp the focused index whenever a shelf's size changes under the cursor (library update).
- `const val GAME_PICKER_GRID_COLUMNS = 6` lives next to the logic so the layout and the nav
  math can't drift (same rule as `PICKER_GRID_COLUMNS`).

### 4.3 Icon at picker size (`GameIconView.kt`)
`GameIcon` takes an `XMBItem` and sizes natural-aspect art with the private
`NATURAL_ART_HEIGHT = 84.dp`.
- Add `artHeight: Dp = NATURAL_ART_HEIGHT` to `GameIcon` / `NaturalArtSlot` and thread it
  through. The XMB call sites don't change.
- Add an internal `Game.toPickerItem(): XMBItem` adapter with title, platform, icon and box URIs,
  `iconDisplayModeOverride` and `accentColor`. That way `resolveIconDisplay` and every fallback
  (`BoxArtPlaceholderIcon`, `PhysicalMediaIcon`, `PspIcon0Icon` letter tile) are reused.
- View override: provide `LocalIconDisplayMode` with the chosen mode and an empty
  `LocalIconDisplayModeByPlatform`, and clear the per-game override in the adapter. Leave both
  untouched for "Library".
- ICON1 video snaps are **off** in the picker. Pass a flag or skip the focused-video path.

### 4.4 Screen (`GamePickerScreen.kt`, rewrite)
- `GamePickerHeader`: the App Picker header without search. Title "Add Games · {category}",
  with the right side showing `+N adding · −M removing`.
- `ShelfRail` is a `LazyColumn` of `ShelfRailEntry`.
- `ShelfGrid`: one `LazyVerticalGrid(GridCells.Fixed(GAME_PICKER_GRID_COLUMNS))` per shelf,
  keyed by game or collection id, with scroll-into-view as in `AppPickerGrid`.
- `GamePickerTile` reuses `AppPickerTile`'s focus and check layers. Consider extracting those
  layers into a shared `PickerTileChrome` in `apppicker/` rather than copying them.
- `MemoryCardTile` is a landscape card showing the game count and its source category.
- `ShelfLedge` uses `Modifier.drawBehind` per row (see §2).
- Footer: `ControllerPromptBar` with the §3 prompts and the App Picker's styling.
- `XMBShell.kt:1343` needs the category's display name. Pass it from `uiState.categories`.

### 4.5 Input wiring (`XMBViewModel.kt:6164`)
Add `NAVIGATE_LEFT`, `NAVIGATE_RIGHT`, `PREV_CATEGORY`, `NEXT_CATEGORY` and `CHANGE_SORT` to the
forwarded set. Play `menuSound` on shelf change (cursor move) and toggle (select), matching the
App Picker.

### 4.6 Performance
- Lay out only the visible shelf. Do not build one grid for the whole library, because ROM sets
  of 2,000+ entries are realistic.
- Shelf counts come from the selection sets at render time. Don't cache them in the shelf models
  (today's `updateGroupCounts` can go).
- Artwork goes through the existing Coil and `ArtworkRevisions` path, with no picker-specific
  cache.

---

## 5. Tests

`GamePickerLogicTest` (JVM, same style as `AppPickerLogicTest`):
- Grid edges: no wrap; LEFT at column 0 enters the rail; RIGHT from the rail returns to the
  remembered index.
- Rail UP/DOWN and L1/R1 clamp at both ends; the focus index is remembered per shelf.
- A shelf shrinking under the cursor re-clamps it.
- Whole-shelf toggle: partly checked → all checked → none checked.
- A game on both "In {Category}" and its console shelf toggles once.
- `pendingAdds` / `pendingRemovals` match `confirmGamePicker` for: new pick, untouched
  preselected, unchecked preselected, card move.
- `clearSelection()` resets cursor, zone, shelf and view override.

Screenshot pass (`SettingsScaffoldScreenshotTourTest` style): Classic Blue, Silver,
Golden Amber, with each view override.

---

## 6. Order

1. 4.2 logic + tests (no UI).
2. 4.1 state rewired onto the logic. The old screen still works through the adapter.
3. 4.3 icon `artHeight` + adapter.
4. 4.4 screen rewrite.
5. 4.5 input wiring + sounds.
6. Screenshot pass, then delete `buildPickerItemIds`, `PICKER_COLLECTIONS_HEADER` and the
   `picker*Id` helpers.

---

## 7. Open questions

- Should **In {Category}** list only the games present at open, or also games checked during
  this visit?
- Should consoles with no games show as greyed-out shelves, or stay hidden as now?
- Should the X view choice be remembered for the rest of the session?
- Search: should it be header-only for touch, use a long-press on Y, or take another button?
