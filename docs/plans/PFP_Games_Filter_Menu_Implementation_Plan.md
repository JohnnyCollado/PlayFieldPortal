# Play Field Portal — Games Filter Menu: Implementation Plan

Replace the Games category's blind sort *cycle* with a **Filter menu**: one Square press (or one tap
on the status pill) opens a two-row PSP menu — **Search** and **Sort** — and a typed search term
filters the live XMB column to games whose **display title** matches.

**Status: implemented, not yet verified.** T1–T8 are in the tree. Nothing has been compiled or run
— the unit suite in §8 is written but unexecuted, and none of it has been on a device. See §12 for
the two things the implementation had to decide that this plan did not anticipate.

**Mockup:** [Games_Filter_Menu_Mockup.html](../mockup/Games_Filter_Menu_Mockup.html) — five states
(baseline, root menu, Sort group, search entry, active query / no matches).

> **Scope.** Games only. Music and Video keep `cycleSort()` and the `Sort:` pill exactly as they are.
> This is deliberate and should stay that way until the Games version has been lived with: the
> music browser already has its own permanent search field and a different filter story, and
> merging the two designs before either is proven would produce a shared abstraction fitted to
> neither.

---

## 1. What exists today

### 1.1 Sort is a cycle with no menu

`GamepadAction.CHANGE_SORT` (X / Square) and the touch pill both land in `cycleSort()`
([XMBViewModel.kt#L4481](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L4481)),
which advances `gameSortMode` through `GAME_SORTS` (`TITLE`, `RECENT_PLAYED`, `DATE_ADDED`), resets
the cursor, bumps `scrollToTopToken`, and rebuilds via `loadItemsForCategory()`.

Whether the press does anything at all is decided by one pure function,
`XMBUiState.activeSortModes()` ([#L1193](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L1193)),
which `canSortCurrentList` and the idle hint pill also read. That single-source discipline is the
most valuable thing in this area and **must survive the change** — the hint pill must never promise
an action the press won't perform.

### 1.2 The pill

`currentSortLabel()` ([#L4764](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L4764))
returns `"Sort: ${mode.label}"` for games, music and video alike, into `XMBUiState.sortLabel`, drawn
by `XmbPspStatusStrip` ([XmbStatusStrip.kt](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XmbStatusStrip.kt)).
On touch it is a tappable chip (`onSortTapped` → `onSortLabelTapped()` → `cycleSort()`); on a
controller it is a plain readout.

### 1.3 The menu machinery already exists

`XMBContextMenu` + `XMBContextMenuItem` ([#L141](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L141))
render through `ContextMenuOverlay` → `PspContextMenuOverlay`, which `XMBShell` already mounts
whenever `activeContextMenu != null`
([XMBShell.kt#L1085](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt#L1085)).
`XMBContextMenuItem` already carries `checked`. Submenus are done by *replacing* `activeContextMenu`
with a new menu carrying a marker field (`pendingAppAction`, `collectionGameId`,
`playlistPickerTrackId`). **No new overlay plumbing is needed.**

### 1.4 The interaction model to copy: the Tracked/Untracked Options menu

`LibraryOptionsMenu` / `LibraryOptionGroup` / `libraryOptionRows(state)`
([ShibaLibraryViewModel.kt#L64](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaLibraryViewModel.kt#L64)):
a root list whose rows name their current choice (`"Filter (Title A–Z)"`, `"Provider (All)"`),
opening a row swaps the same overlay to that group's choices with a checkmark on the active one, and
the whole row set is a **pure function of state** so it unit-tests without a UI. This plan reproduces
that shape on the XMB.

### 1.5 The filtering model to copy: the music browser

`rebuildBrowserTrackRows()` ([#L3534](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L3534))
is the exact pattern this plan wants for games: **one funnel** that sorts, then filters by a trimmed
lowercase query, then picks between real rows, a no-results row, and an empty-library row. Its
`matchesQuery` already matches on `displayTitle`.

---

## 2. The problem this fixes

Two, and they are worth naming separately because only one of them is the feature the user asked for:

1. **Sorting is blind.** A cycle gives no menu, no checkmark, and no way to see the other options —
   you press until it lands. The menu fixes that as a side effect.
2. **A large library has no way in.** Games is the one column with hundreds of rows and no search,
   while the app drawer, the app picker, the music browser, the Shiba Library and the Artwork Studio
   all have one.

---

## 3. Interaction design

### 3.1 The two entry points, one destination

| Input | Today | After |
| --- | --- | --- |
| Square / X (`CHANGE_SORT`), Games | cycles sort | opens the Filter menu |
| Status pill tap, Games | cycles sort | opens the Filter menu |
| Square / X, Music or Video | cycles sort | **unchanged** |
| Status pill tap, Music or Video | cycles sort | **unchanged** |

### 3.2 Menu structure

```
Filter                       ← root, title "Filter"
  Search    None             ← value = the active query, or "None"
  Sort      Title            ← value = the active XmbSortMode.label

Sort                         ← group, title "Sort"
  Title             ✓
  Recently Played
  Date Added
```

Root rows name their current choice, per §1.4. A third row, **Clear Search**, appears in the root
*only while a query is active* — a row that would do nothing is worse than a shorter menu, and this
is the fast path off a filtered column.

### 3.3 Back semantics

Circle inside a **group** returns to the **root**, it does not close the menu. This is a genuine
departure from the XMB's existing submenus (`pendingAppAction` and friends, which close outright),
and it is the right behaviour here because the root is a real destination with two siblings rather
than a launcher for a one-shot picker. It is called out as its own task (T4) because it is the one
place this feature touches shared back-handling — see `onBackPressed` around
[#L5274](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L5274).

### 3.4 Search entry

Activating **Search** closes the menu and opens a single-line field over the top of the column
(mockup D). The field is **modal and transient**, not permanent like the music browser's:

- the XMB column is the whole screen and has no header to host a permanent field;
- the query's visibility is carried by the status pill instead (§3.5), which is why the pill has to
  change — it is not decoration.

The query is **live**: every keystroke re-filters the column behind the field, so Confirm only
dismisses the field. Cancel (Circle) restores the query as it was when the field opened.

### 3.5 The pill carries the query

| State | Pill |
| --- | --- |
| No query | `Filter: Title` |
| Query active | `Filter: "zel" · Title` |

A column silently missing four-fifths of its games is the worst outcome this feature can produce.
The pill is the only thing on screen that explains it, so showing the term is not a nicety.

### 3.6 Empty result

A query matching nothing produces a **no-results row**, never a blank column — the precedent is
`browserNoResultsItem()` (§1.5). Wording in the mockup: `No games match "zzz"`.

### 3.7 The hint pill

`ContextMenuHint`'s Square half currently reads `"Sort"`
([ContextMenuHint.kt#L72](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/ContextMenuHint.kt#L72)).
On Games it must read `"Filter"`; on Music and Video it keeps saying `"Sort"`. The label becomes a
parameter, not a constant.

---

## 4. State model

Added to `XMBUiState`:

```kotlin
/** The Games column's live search term. Empty = unfiltered. */
val gameQuery: String = "",
```

Added to `XMBContextMenu` (one marker field, matching the existing submenu convention):

```kotlin
/** Set on the Games Filter menu — null group = root, otherwise that group's choices. */
val gamesFilterGroup: GamesFilterGroup? = null,
val gamesFilterMenu: Boolean = false,
```

And a new dialog-ish state for the field, following `PlaylistNameDialogState`
([#L556](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L556)):

```kotlin
data class GameSearchFieldState(val text: String, val textOnOpen: String)
val gameSearchField: GameSearchFieldState? = null,
```

`gameSearchField != null` must join the list of states that suppress the idle hint and own input
(see the `||` chain at [#L993](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L993)
and [#L1004](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L1004)).

Row construction is a **pure top-level function**, testable without the ViewModel, mirroring
`libraryOptionRows`:

```kotlin
fun gamesFilterRows(state: XMBUiState, group: GamesFilterGroup?): List<XMBContextMenuItem>
```

---

## 5. T1 first: collapse the eight sort call sites

**This is the mandatory first task and it ships on its own, with no behaviour change.**

`gameSorted(_uiState.value.gameSortMode)` is called from **eight** places inside
`loadItemsForCategory` — lines
[2104](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L2104),
2170, 2182, 2192, 2203, 2226, 2319 and 2333. If the query filter is added at the call sites, one of
them will be missed, and that platform / collection / favourites view will ignore search forever —
a bug that is invisible until someone happens to search on that one screen.

Collapse them into a single funnel:

```kotlin
/** Every Games row the column shows, in order: filter by the live query, then sort. */
private fun gamesForDisplay(games: List<Game>): List<Game>
```

Filter **before** sort (cheaper, and it makes "sort the visible set" obviously true). Every path goes
through it; `gameSorted` becomes private to the funnel. T1's acceptance is that the full suite passes
with no test edits — if a test changes, the refactor was not behaviour-preserving.

---

## 6. Matching rules

- Match against the row's **display title**, not `Game.title`. Manual metadata overrides
  (`user_metadata_overrides`) mean those diverge, and the user searches for what is on screen.
  `MusicTrack.matchesQuery` ([#L3529](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L3529))
  already sets this precedent.
- Case-insensitive `contains` on a trimmed, lowercased query. **Substring, not prefix** — "zelda"
  must find "The Legend of Zelda".
- Blank / whitespace-only query = no filter at all (not "match everything separately").
- Title only in this pass. Platform, developer and genre are deliberately out (§11).

---

## 7. Query lifetime and cursor

| Event | Query |
| --- | --- |
| Moving the cursor inside the column | kept |
| Changing platform / collection / drilling in or out of Games | **cleared** |
| Leaving the Games category entirely | **cleared** |
| A library rescan republishing rows | kept |
| Process death | not persisted |

Clearing on drill is the honest default: the query was typed against one list, and silently applying
it to a different one produces a short column whose cause has scrolled off the user's memory.

On any query change, reset `selectedItemIndex = 0` and bump `scrollToTopToken`, exactly as
`cycleSort()` does — the list identity changed, so `cursorAfterRefresh`'s keep-the-cursor logic in
`publishGameItems` ([#L4964](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L4964))
is the wrong tool here.

---

## 8. Testing

Everything that matters here is pure, so almost none of it needs a device:

| Target | Test |
| --- | --- |
| `gamesForDisplay` | filter-then-sort order; blank query is identity; substring + case-insensitivity; display-title vs raw title when an override exists |
| `gamesFilterRows` | root shows current values; Clear Search present only with a query; Sort group checkmark tracks `gameSortMode` |
| `activeSortModes()` | unchanged for music/video; Games still gated the same way |
| Pill label | `Filter: Title` vs `Filter: "zel" · Title`; music/video still `Sort: …` |
| Back | group → root → closed, two presses |
| Query lifetime | cleared on platform change, kept on rescan |

Existing suites that must pass **unmodified**: the music and video sort tests, and whatever pins
`canSortCurrentList` / hint-pill visibility. If one of those needs editing, the Games-only scope leaked.

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

---

## 9. Execution Task Index

| ID | Task | Depends On | Effort | Status |
| --- | --- | --- | --- | --- |
| T1 | Collapse the 8 `gameSorted` call sites into `gamesForDisplay`; no behaviour change, no test edits | None | S | VERIFYING |
| T2 | `gameQuery` state + filtering inside `gamesForDisplay` + no-results row + cursor/scroll reset | T1 | M | VERIFYING |
| T3 | `gamesFilterRows` (pure) + `GamesFilterGroup` + menu markers on `XMBContextMenu`; Games branch of `CHANGE_SORT` and `onSortLabelTapped()` opens the root | T1 | M | VERIFYING |
| T4 | Group → root back handling; menu activation wiring (Sort choice, Clear Search) | T3 | S | VERIFYING |
| T5 | `GameSearchFieldState` + the transient search field composable in `XMBShell`, live-filtering, Confirm/Cancel semantics | T2, T3 | M | VERIFYING |
| T6 | Pill label: `Filter: …` with the active term, Games only; `currentSortLabel()` split so music/video are untouched | T2 | S | VERIFYING |
| T7 | Hint-pill label parameterised (`"Filter"` on Games, `"Sort"` elsewhere) | T3 | S | VERIFYING |
| T8 | Test pass per §8 | T4, T5, T6 | M | VERIFYING |
| T9 | *(added during T5)* Cached row rebuild so typing does not re-subscribe the database — see §12.1 | T2 | S | VERIFYING |

Status key: `READY` · `IMPLEMENTING` · `VERIFYING` · `DONE` · `BLOCKED`.
Effort: S = under a day · M = a few days · L = a week or more.

`DONE` requires: acceptance criteria met, the task's tests written and passing, and no known blocker.

**T1 ships alone and first.** T2 and T3 are independent of each other after it, so they can be taken
in either order; T5 is the only task that touches `XMBShell` composition.

---

## 10. Open questions

1. **Search field placement** — over the column (mockup D) or in the status strip where the pill is?
   The mockup assumes over the column, because the strip is 7% of screen height and already crowded,
   but the strip version keeps the query visually welded to the pill that reports it.
2. **Does Sort belong under a menu titled "Filter"?** Strictly, sorting is not filtering. The
   alternative is titling the root **"View"** with Search and Sort under it, which is more accurate
   and costs nothing — but "Filter" is what was asked for, so it is what the mockup shows.
3. **Should the query survive a drill into a platform?** §7 says no. Worth revisiting after living
   with it; a user who searches "mario" then enters the SNES card may well have wanted it kept.

---

## 11. Out of scope

- Music and Video: no menu, no search, no pill change.
- Filtering by anything but display title (platform, genre, developer, completion).
- Persisting the query across process death.
- Fuzzy / token matching. Plain substring first; if it proves too literal, that is a follow-up with
  its own evidence.


---

## 12. What the implementation changed

Two things this plan did not anticipate, both decided while building T2 and T5.

### 12.1 Typing must not re-subscribe the database (T9)

§4 had the query change call `loadItemsForCategory`, which is what a sort change does. That is
correct for a sort — it happens once per menu choice — and badly wrong for a live query, which
fires on **every keystroke**: each character would cancel the collector and re-subscribe a Room
flow over the whole library.

The fix follows the music browser's cached `browserRawTracks`. Each Games branch now publishes
through `publishGames(keepCursor) { … }`, which stores its row builder in `rebuildGameRows` — a
lambda closing over the list that branch last received. A query or sort change calls
`rebuildGameColumn()`, which re-runs that builder against the new state with no database round
trip, and falls back to a full reload when no builder is installed. `loadItemsForCategory` clears
it first thing, so a builder never outlives the column it was built for.

This also makes the sort change cheaper, which was not the reason for it but is welcome.

### 12.2 A gaming category's empty state is not the games half's to draw

§3.6 says a query matching nothing draws a no-results row. That is right for the seven lists that
are purely games, and wrong for the eighth: a custom gaming category's column is **collections and
games**. Letting the games half emit a no-results row would have drawn "No games match …" over a
column that still had collections in it.

So that one branch calls `gamesForDisplay` directly and assembles its own empty state: the
no-results row appears only when the *combined* list is empty. The consequence, stated plainly: in
a gaming category with collections, a query that matches no games leaves the collections visible
and no row explaining the absence — the status chip is what carries it. Filtering collections by
name would resolve that and is deliberately out of scope (§11).

### 12.3 One thing §7 got right for a reason worth recording

Clearing the query on navigation landed in `navigateRememberingCursor`, comparing the view key
before and after the mutation — every platform, collection and sub-view change already passes
through there. Only `onCategoryChanged` needed its own call. That is one clearing rule rather than
eleven call sites that must each remember.
