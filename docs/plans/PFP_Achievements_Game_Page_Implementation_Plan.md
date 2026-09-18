# Play Field Portal — Per-Game Achievements Page: Implementation Plan

Mockup: [Per-Game Achievements Mockup](https://claude.ai/artifact/FF3A6hSQaJmYdP3s9NF46U)
(artboards "All view, hidden coin focused" and "Locked view, Options open").

Companion to `PFP_Achievements_Screen_Design.md`, which covers the Tracked and Untracked
browser. This plan rebuilds the page that opens when you confirm a tracked game
(`ShibaCoinsScreen`) so that it matches that browser.

---

## 1. Goal

Replace the current single-column Shiba Coins page (Progress bar, black Platinum banner, Sync
now button, Sort/Show chips, small coin rows) with a console-style page that shares the
library's look and behavior:

- The App Drawer-derived background and `DetailPalette`, with a darker header band.
- A larger two-line header: the game title and platform, then completion, earned count and
  earned/total by tier.
- A pinned row with Search on the left and **All / Earned / Locked** view tabs on the right,
  switched with L1/R1.
- Full-width, separator-divided 64dp coin rows. The first row is a Platinum Crown row.
- Sort, Sync Now and Change Match move into the Triangle Options menu.
- A state-driven helper footer, with pills instead of hints in touch mode.

What stays the same: the data sources, sync and match logic, the Auto-Match flow, hidden-coin
reveal, and how `XMBShell` opens and closes the page.

---

## 2. What exists today

| Piece | Where | Keep / change |
|---|---|---|
| `ShibaCoinsScreen` (498 lines) | `feature-xmb/.../ui/detail/ShibaCoinsScreen.kt` | Rewrite the layout |
| `ShibaCoinsViewModel` (455 lines) | same folder | Rework focus, input and options; keep the load, sync and match code |
| `CoinRow`, `CoinSort`, `CoinFilter`, `List<CoinRow>.arrange` | `ShibaCoinsViewModel.kt` | Keep; add search to `arrange` |
| Index-based focus (`FOCUS_ACTION/SORT/FILTER/COINS_START`) | `ShibaCoinsViewModel.kt` | Replace with stable-id focus, like the library |
| `ShibaCoinsArrangeTest` | `feature-xmb/src/test/.../detail/` | Extend |
| `GameCoins` (`earned`, `total`, `isMastered`; Platinum not counted in the tallies) | `core-domain/.../achievement/ShibaCoins.kt` | Add `lastSyncedAt` |
| `AccountAchievementSetEntity.lastSyncedAt` | `core-data/.../entity/` | Already stored; not yet exposed to the UI |
| Library building blocks: `SearchRow`, `ProgressLine`, `CoinCount`, `Separator`, `libraryFocus`, `headerShade` | `ShibaLibraryScreen.kt` (all `private`) | Extract to share |
| `PspContextMenuOverlay` / `PspMenuRow` (label, checked, isDestructive) | `core-ui/.../components/PspContextMenu.kt` | Reuse as-is |
| `PfpDetailBackground`, `PfpDetailHelperFooter`, `ControllerPromptItem` | `core-ui/.../detail/DetailScaffold.kt` | Reuse |
| `CoinArt(iconUrl, tier)` (badge image, falling back to the tier coin) | `PlayerStatusScreen.kt` (private) | Extract to share |
| `XMBShell` hosts `ShibaCoinsScreen` without `showTouchControls` | `XMBShell.kt:1179` | Pass it through |

---

## 3. Layout spec (dp, from the 1920×1080 mockup at ≈2.305 px/dp)

### 3.1 Header band (≈91dp tall, `headerShade(palette)` fill, bottom divider)

This header is a new `ShibaCoinsHeader` composable in feature-xmb. It does **not** change
`PfpDetailBreadcrumb` (20sp title), so the library and Game Detail keep their current headers.

- **Line 1:** ◀ (48dp touch target, taps close the page). Title in 25sp SemiBold, single line
  with ellipsis, taking the remaining width. On the right, muted 14sp:
  `"{platformLabel} · Tracked Games"`. Account entries show the provider label, e.g.
  "RetroAchievements".
- **Line 2** (indented to line up with the title, spaced out across the width):
  - `COMPLETION` label (11sp SemiBold, muted), value `NN%` (19sp Medium), and a 74dp × 4dp
    `ProgressLine`. The value comes from `GameCoins.progress`.
  - `EARNED` label and value `earned.total / total.total`.
  - Four tier cells in the order Platinum, Gold, Silver, Bronze: a 26dp coin icon, then
    `earned` over a muted `/total`. Platinum shows `1/1` when `isMastered`, otherwise `0/1`.
- With no summary yet (never synced), line 2 shows `COMPLETION —` and `EARNED —`, and the tier
  cells are hidden.

### 3.2 Pinned row (48dp, `SearchRowHeight`)

- **Left:** the library's `SearchRow`, extracted and given a `placeholder` parameter
  ("Search coins…"). It keeps the same edit-mode and keyboard behavior.
- **Right:** `ShibaCoinsViewTabs`. An L1 key-cap, three tabs (`All 42`, `Earned 16`,
  `Locked 26`), then an R1 key-cap.
  - The selected tab gets a pill with `palette.focus` at 28% alpha and a 1dp `palette.focus`
    border.
  - Unselected tabs use muted text.
  - Tabs are tappable. Key-caps show only when `!showTouchControls`.
  - Counts come from `coins`, not `displayed`, so they don't change while you search.
- The row is focus position 0, sharing `libraryFocus` with the library.
- A separator follows.

### 3.3 Coin list (LazyColumn, 64dp rows, `Separator` between rows)

Each row, left to right:

1. **Art, 46dp square, 4dp corners:** `CoinArt(iconUrl, tier)`. Locked coins get a greyscale
   `ColorMatrix` and 0.6 alpha. Redacted hidden coins always show the tier coin, never the
   badge, because a badge can give the coin away.
2. **Text column (weight 1):**
   - Title: 16sp SemiBold, one line. A redacted coin shows "Hidden Coin" in italic.
   - Description: 12sp muted, one line. The existing redaction and Steam-secret strings stay.
3. **Metric column (74dp, end-aligned):**
   - Value in 16sp: rarity `%.1f%%`, or `—` when the provider gives no rarity.
   - A 64dp `ProgressLine` showing rarity as a fraction.
   - A 9sp muted "of players" label.
4. **Status column (108dp):**
   - Earned: a check glyph plus "Earned" in the success color, with the date (`MMM d, yyyy`)
     underneath.
   - Locked: a lock glyph plus "Locked" in muted text.
5. **Tier column (48dp, centered):** a 24dp tier coin with the tier name in 9sp.

Focus uses `libraryFocus`, the same as library rows.

**Platinum Crown row:** a synthetic first row with id `"platinum"`. It shows in the All and
Locked views while unmastered, and in All and Earned once mastered.
- Art: the Platinum coin.
- Title "Platinum Crown", description "Earn every other coin in the game".
- Metric: `earned.total / total.total` with the progress bar and a "coins earned" label.
- Status: Earned or Locked from `isMastered`.
- Tier: Platinum.
- It can take focus, but Confirm does nothing on it.
- Search matches it on "platinum" or "crown".

**Empty states** (shown in the list area, the same way the library shows them):

- No coins after sync: "No coins to show."
- Search with no results: `No coins match "{query}".`
- Earned view, nothing earned: "No coins earned yet."
- Locked view, everything earned: "Every coin is earned."

### 3.4 Helper footer / touch band

These are built by a pure `shibaCoinsHelperItems(state)`, mirroring
`shibaLibraryHelperItems`:

| State | Items |
|---|---|
| Options open | A Select · B Close |
| Search editing | B Done |
| Search focused | A Type · X Search · Y Options · L1/R1 Change View · B Back |
| Hidden, unearned coin focused | A Reveal (A Hide once revealed) · X · Y · L1/R1 · B |
| Other coin or Platinum focused | X Search · Y Options · L1/R1 Change View · B Back (no A) |
| Unlinked link panel focused | A Auto-Match (or nothing for Local Steam / Vita) · Y Options · B Back |

In touch mode the footer hints fade out and an `XmbHeaderPill("Options")` shows in the same
band. The tabs, rows and ◀ can already be tapped.

### 3.5 Options menu (Triangle): `PspContextMenuOverlay`

The mockup drew a custom panel. For consistency this plan uses the shared
**`PspContextMenuOverlay`**, the same menu the library uses.

Root rows are built by a pure `coinOptionRows(state)`:

- `Sort (Tier)` opens a list: Tier, Earned, Rarest, with the active one checked.
- `Sync Now`: only when `linked || accountOnly`. While syncing it reads `Syncing…` and does
  nothing when picked.
- `Change Match`: only for a linked Steam library game (today's `hasChangeMatch`).

Picking a sort applies it and closes the menu. Sync Now and Change Match run their action and
close the menu.

---

## 4. State and ViewModel changes

### 4.1 `ShibaCoinsUiState`

- **Remove:** `focusIndex`, `actionOnChangeMatch`, and the `FOCUS_*` constants.
- **Add:**
  - `focusedRowId: String?`: null means the pinned row. Otherwise it holds a coin id, or
    `"platinum"`, or `"link"` for the unlinked panel.
  - `query: String`, `searchEditing: Boolean`.
  - `options: CoinOptionsMenu?`: `selectedIndex`, plus `group: CoinOptionGroup?` (only `SORT`).
  - `lastSyncedAt: Long?`, taken from `GameCoins`.
- **Derived:**
  - `rows: List<CoinListItem>`: an optional `Platinum` item plus a `Coin(CoinRow)` for each
    displayed coin, or a single `LinkPanel` when unlinked.
  - `focusPosition` and `focused`.
  - `viewCounts` (all / earned / locked).
  - `emptyMessage` and `optionRows`.
- `filter` keeps its name and type (`CoinFilter`). In the UI it is now the view.

### 4.2 `arrange`

The signature becomes `List<CoinRow>.arrange(sort, filter, query = "", revealedIds = emptySet())`.

- The query is trimmed and matched case-insensitively against the title and description.
- A **redacted hidden coin matches only "hidden"**, so search never gives away its title or
  description.

### 4.3 Input (`handleGamepadAction`)

- The Auto-Match prompts stay modal and come first, unchanged.
- Options open: Up/Down move the menu cursor, A activates, B or Y closes.
- Search editing: B ends editing and keeps the query. The IME keyboard handles typing.
- Otherwise:
  - **Up/Down:** move through `rows` by id, stopping at the ends. Up from the first row goes to
    the pinned row.
  - **Left/Right and L1/R1** (`PREV_CATEGORY` / `NEXT_CATEGORY`): cycle All, Earned, Locked,
    wrapping around.
  - **A:** on the pinned row, start editing. On a hidden, unearned coin, toggle reveal. On the
    link panel, run today's `onActionSelect()` minus the Change Match branch. Otherwise nothing.
  - **X** (`CHANGE_SORT`): if the pinned row has focus, start editing; otherwise focus the
    pinned row.
  - **Y** (`OPEN_CONTEXT_MENU`): open Options.
  - **B:** close the page.
- **Focus recovery**, the library's rule: after a sort, view change, search change or data
  refresh, keep the same id if it is still listed. Otherwise move to the nearest row at the old
  position, or to the pinned row if the list is empty.
- `load(target)` resets focus, query, editing, options and reveals.

### 4.4 Data: last sync time

- Add `val lastSyncedAt: Long? = null` to `GameCoins`, and fill it in
  `AccountAchievementSetEntity.toGameCoins()` (`AchievementRepository.kt:361`).
- The default keeps every other `GameCoins(...)` call unchanged.
- Where it shows is open question 1 in §8.

### 4.5 Messages

The `message` state stays. Instead of a tappable line in the header, it renders as a muted
notice line directly under the pinned row, above the list. It clears on the next controller
input or a tap. See open question 3.

---

## 5. Shared extractions (no visual change to the library)

Move these from `ShibaLibraryScreen.kt` into a new internal file,
`feature-xmb/.../ui/detail/ShibaDetailParts.kt`:

- `SearchRow` (with a new `placeholder` parameter)
- `ProgressLine`
- `CoinCount`
- `Separator`
- `Modifier.libraryFocus` (renamed to `shibaFocus`)
- `headerShade`
- the dimension constants `RowHeight`, `SearchRowHeight` and `FocusShape`

`SearchRow` currently takes `ShibaLibraryUiState`. Change it to take plain values:
`query`, `editing`, `focused`, `placeholder` and the callbacks.

Move `CoinArt` from `PlayerStatusScreen.kt` into `ShibaCoinArt.kt` as `internal`. Add a `dimmed`
flag that applies the greyscale and alpha.

The library screen and Player Status should look exactly as they do today after this step.

---

## 6. Phases (tests first, per phase)

Each phase starts by writing its failing tests, then the code that makes them pass. You run
every build and test; the commands are in §7.

### Phase 1: Pure logic
- **Tests:** extend `ShibaCoinsArrangeTest` to cover:
  - query matches the title
  - query matches the description
  - query is case-insensitive and trimmed
  - a redacted hidden coin is not matched by its real title, but is matched by "hidden"
  - a revealed hidden coin is matched by its title
  - an empty query changes nothing
- **Tests:** new `ShibaCoinsOptionsTest` for `coinOptionRows`:
  - the root for a linked RA game is Sort + Sync Now
  - a linked Steam game adds Change Match
  - an account entry has no Change Match
  - an unlinked game has Sort only
  - the Sort list checks the active sort
  - while syncing the row reads "Syncing…"
- **Code:** `arrange` with query and reveals; `CoinOptionsMenu`, `CoinOptionGroup`,
  `CoinOption`; `coinOptionRows`.

### Phase 2: ViewModel focus and input
- **Tests:** new `ShibaCoinsViewModelTest`, using mockk fakes for `GameRepository`,
  `AchievementController` and `AchievementAutoMatcher`, in the same style as
  `ShibaLibraryViewModelTest`:
  - Opening the page focuses the pinned row. Down goes to Platinum, then the first coin.
  - L1/R1 and Left/Right cycle All → Earned → Locked and wrap.
  - Changing the view keeps a still-listed focused coin, and otherwise recovers to the nearest
    row.
  - Search: X focuses the pinned row, X again starts editing, B ends editing and keeps the
    query.
  - A on a hidden coin toggles reveal. A on an earned coin or on Platinum does nothing.
  - Y opens Options. The Sort list applies the sort and closes the menu. Sync Now calls
    `syncGameById`. Change Match calls `unlink`.
  - B closes Options first, then the page.
  - Unlinked: the rows are just the link panel, and A runs `autoMatchRaByHash` for RA.
  - The Auto-Match CONFIRM_COPY prompt still captures Left/Right/A/B.
  - `load` resets query, focus, options and reveals.
  - A data refresh that removes the focused coin moves focus to the nearest row.
  - Platinum row visibility per view, for mastered and unmastered games.
- **Code:** the state changes in §4.1 and the input handling in §4.3. Remove the `FOCUS_*`
  constants.

### Phase 3: Helper footer
- **Tests:** new `ShibaCoinsHelperFooterTest`, one case per row of the §3.4 table.
- **Code:** `shibaCoinsHelperItems(state)`.

### Phase 4: Data plumbing
- **Tests:** in `AchievementRepositoryTest`, `observeGameCoins` emits `lastSyncedAt` from the
  set row, and a set that was never synced emits null.
- **Code:** add `GameCoins.lastSyncedAt`, map it in `toGameCoins()`, and copy it into
  `ShibaCoinsUiState`.

### Phase 5: Shared UI extraction
- **Tests:** the existing `ShibaLibraryHelperFooterTest`, `ShibaLibraryViewModelTest` and
  `DetailScaffoldLayoutTest` must stay green.
- **Code:** everything in §5. There should be no visual change, which you can confirm on the
  Thor.

### Phase 6: Screen rewrite
- **Code:** a new `ShibaCoinsScreen` built on `PfpDetailBackground`: `ShibaCoinsHeader`, the
  pinned row (`SearchRow` + `ShibaCoinsViewTabs`), a notice line, the LazyColumn of
  `CoinListRow`, `PlatinumCrownRow` and `LinkPanelRow`, the footer or touch band, and
  `PspContextMenuOverlay`.
  - Keep the snap-follow scrolling, keyed on `rows` ids + `focusPosition`, the same as the
    library.
  - The Auto-Match YES/NO prompt and the app-id text field move into `LinkPanelRow`. Their logic
    stays; only the styling changes.
- **XMBShell:** pass `showTouchControls = uiState.resolvedShowTouchButton`.
- **Remove** the old `SummaryHeader`, `SyncRow`, `SortFilterChips`, `Chip`, `PillButton` and
  `focusRing`, along with the hard-coded `TextPrimary`, `TextMuted` and `CardFill` colors.
  The screen then uses only `DetailPalette`.

### Phase 7: On-device check (Thor)
Walk through each of these:

- a linked RA game
- a linked Steam game (Change Match)
- a Local Steam game
- an account-only entry
- an unlinked RA game (Auto-Match)
- a mastered game
- a game with hidden coins
- search with no results
- touch mode
- the phone and tablet `wm size` overrides

---

## 7. Commands for you to run

Unit tests for this work:

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoins*"
```

```bash
./gradlew :feature:feature-achievements:testDebugUnitTest --tests "*AchievementRepositoryTest*"
```

Regression check on the library after the Phase 5 extraction:

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaLibrary*"
```

```bash
./gradlew :core:core-ui:testDebugUnitTest --tests "*DetailScaffoldLayoutTest*"
```

Install on the Thor:

```bash
./gradlew installFullDebug
```

---

## 8. Open questions — settled 2026-09-17

1. **Where the last sync time shows.** `PspMenuRow` has no subtitle, so the mockup's
   "Synced from RetroAchievements · 4 min ago" line under Sync Now can't be drawn without
   changing the shared menu. Options:
   - **(a) Recommended:** put it on the right of header line 1, as `Nintendo DS · Synced 4 min
     ago`, replacing "Tracked Games".
   - (b) Put it in the Sync Now label: `Sync Now (4 min ago)`.
   - (c) Add an optional `detail` line to `PspMenuRow`. This changes a shared component.
2. **The unlinked page.** The mockup doesn't show it. The proposal: the header shows the title
   with `—` stats, the tabs are hidden, and the list area holds one focused link-panel row with
   today's Auto-Match copy, restyled in the row look. This is new art, so it needs a mockup
   artboard and your approval first.
3. **Message line.** Is a muted notice line under the pinned row OK for sync and match results,
   or do you want a toast?
4. **New glyphs.** The mockup's check (Earned), lock (Locked), greyscale locked art, and tab
   pill were approved with the mockup. Confirm that the check should use a green success color
   rather than `palette.focus`.
5. **Badge art.** Rows use the provider badge (`iconUrl`) when there is one, and the tier coin
   otherwise. Should locked coins show their greyed-out badge, or always the tier coin?

### Answers

1. **Last sync time:** option (a). Header line 1's right side reads
   `{platform} · Synced 4 min ago`, `· Never synced` when the set has no sync, or `· Not linked`
   when the game is unmatched.
2. **The unlinked page:** build the proposal as written, without a new mockup artboard.
3. **Message line:** the muted notice line under the pinned row. No toast.
4. **The Earned check:** the success green, reusing `DetailLaunchFill` — the restrained green the
   detail pages already use for Launch — rather than `palette.focus`.
5. **Badge art:** locked coins show their greyed-out provider badge (greyscale `ColorMatrix` at
   0.6 alpha). A *redacted* hidden coin still falls back to the tier coin, because its badge can
   give it away.

### Decided during implementation

- **The LOCAL_STEAM ownership readout** (the old page's `syncSourceLabel`) had nowhere to go once
  the sync row was removed, and the plan didn't cover it. Rather than lose it, it became a short
  tag in the header subtitle: `Local Steam · Owned on Steam · Synced 4 min ago`. An unknown
  ownership stays silent, as before.
- **The platform label** for a library game now uses `platformDisplay()` ("Nintendo DS") instead of
  the raw upper-cased platform id ("NDS"), matching the library's rows.
