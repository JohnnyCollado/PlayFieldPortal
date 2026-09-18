# PlayFieldPortal — Player Status Redesign Implementation Plan

**Status:** Implementation plan  
**Mockup:** `docs/mockup/Player Status Mockup.html`  
**Primary screen:** `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusScreen.kt`  
**Primary state:** `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusViewModel.kt`

## 1. Goal

Redesign Player Status to match the approved mockup while preserving the existing cached/offline achievement data, game-opening behavior, XMB entry points, settings entry point, and Shiba Coins navigation.

The target screen is a console-style detail page with:

- A large dark Player Status header.
- Player/rank identity and Bone count.
- A compact Shiba Level, next-level, total coin, and tier-count summary.
- A full-width Rarest Unlocked achievement row.
- A full-width Recent Achievements list.
- A permanent controller helper footer.
- A Triangle/Y Options overlay with sorting, provider filtering, and account sync.

## 2. Current implementation and reusable pieces

### Current Player Status implementation

`PlayerStatusScreen.kt` currently provides:

- Level, rank, Bone count, XP, and progress bar.
- Bronze/Silver/Gold/Platinum wallet counts.
- Total XP and earned/available coin totals.
- Recent achievement rows.
- A rarest achievement card.
- Touch activation and gamepad navigation.
- Navigation into a game’s Shiba Coins overlay.

`PlayerStatusViewModel.kt` currently provides:

- `LibraryStanding` and recent-coin collection.
- `RecentRow` and `RarestCard` UI models.
- Cached/offline state mapping.
- Recent/rarest focus handling.
- `ShibaCoinsTarget` activation.

### Shared structures to reuse

Do not introduce a second detail-page or Options framework. Reuse:

- `PfpDetailScaffold`
- `PfpDetailBackground`
- `PfpDetailBreadcrumb`
- `PfpDetailHelperFooter`
- `PfpDetailSectionLabel`
- `detailPalette()` / `DetailPalette`
- `detailFocusRing`
- `DetailContentPadding`, `DetailContentMaxWidth`, and `DetailFooterHeight`
- `PspContextMenuOverlay`
- `PspMenuRow`
- `XmbHeaderPill`
- `ControllerPromptItem`
- `ShibaLevelMedallion`
- Existing `CoinArt`, after extracting it from `PlayerStatusScreen.kt`
- Options and helper-footer patterns from `ShibaLibraryScreen.kt`, `ShibaLibraryViewModel.kt`, `ShibaCoinsScreen.kt`, and `ShibaCoinsViewModel.kt`

## 3. Differences from the mockup

The current screen differs from the mockup in these important ways:

1. It uses a rounded-card layout instead of full-width detail rows.
2. The breadcrumb is smaller and does not expose the mockup’s large header identity treatment.
3. Level/rank/XP are combined into a card rather than a compact header/stat band.
4. Recent achievements and Rarest Unlocked are arranged in two columns instead of a vertical page flow.
5. Achievement rows do not use the mockup’s full-width separator treatment.
6. There is no persistent helper footer.
7. There is no Player Status Options menu.
8. There is no sort or provider-filter state.
9. There is no account-wide sync action from Player Status.
10. Focus navigation uses a recent index plus `rarestPageNudge` rather than stable row identity.
11. The screen uses local hard-coded colors instead of the shared detail palette.

The existing ViewModel already contains most of the required achievement data. The main missing product behavior is Options/sort/provider/sync state, plus the mockup’s player identity source if it is intended to be dynamic.

## 4. Itemized implementation tasks

### Task 1 — Extract reusable achievement visual components

**Files:**

- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusScreen.kt`
- New shared detail file, preferably `ShibaDetailParts.kt` or `ShibaCoinArt.kt`

Actions:

- Extract `CoinArt` from `PlayerStatusScreen.kt`.
- Preserve provider-badge and tier-coin fallback behavior.
- Add shared tier/color helpers only where they are not already available.
- Keep existing Shiba Library and Shiba Coins visuals unchanged.

### Task 2 — Migrate Player Status state to stable focus identity

**File:**

- `PlayerStatusViewModel.kt`

Actions:

- Replace index-only focus as the authoritative model.
- Add stable focus IDs for the Rarest row and recent achievements.
- Preserve focus when data refreshes if the focused achievement remains.
- Recover to the nearest valid row if it disappears.
- Remove `rarestPageNudge` after focus-driven scrolling is in place.
- Keep the existing `ShibaCoinsTarget` activation flow.

Suggested IDs:

- `player-status:rarest`
- `player-status:recent:{provider}:{game}:{earnedAt}:{coin}`

The recent ID should use stable domain identity where available rather than visible list position alone.

### Task 3 — Add Player Status Options state

**File:**

- `PlayerStatusViewModel.kt`

Actions:

- Add an Options model following `LibraryOptionsMenu` or `CoinOptionsMenu`.
- Add root options:
  - `Sort (Newest)`
  - `Provider (All)`
  - `Sync All Games`
- Add submenu state and checked-row behavior.
- Ensure Options owns controller input while open.
- Ensure Back/Y closes Options before closing Player Status.

### Task 4 — Add sorting, provider filtering, and sync behavior

**Files to inspect and reuse:**

- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaCoinsViewModel.kt`
- `feature/feature-achievements/src/main/kotlin/com/playfieldportal/feature/achievements/AchievementController.kt`
- `feature/feature-achievements/src/main/kotlin/com/playfieldportal/feature/achievements/AchievementRepository.kt`
- Existing account-wide sync handling in `XMBViewModel.kt`

Actions:

- Keep Newest as the default ordering using `earnedAt`.
- Add Rarest ordering using `globalRarity`.
- Add provider filtering based on the provider data available in the current achievement model.
- Locate and reuse the existing account-wide sync operation.
- Add syncing/result state and prevent duplicate sync activation while syncing.
- Keep cached data visible while sync is in progress.

If provider identity is not currently present on `RecentCoin`, add the smallest domain-model extension required and update repository mapping; do not add a new provider or data source.

### Task 5 — Define pure helper-footer behavior

**Files:**

- `PlayerStatusViewModel.kt`
- New or existing detail helper file if preferred

Actions:

- Add `playerStatusHelperItems(state)` following `shibaLibraryHelperItems()`.
- Normal focused row:
  - `View Game`
  - `Options`
  - `Back`
- Options open:
  - `Select`
  - `Close`
- Syncing state should expose an appropriate non-duplicating action set.
- Use the existing `ControllerPromptItem` and controller mappings.

### Task 6 — Rebuild the screen on the shared detail scaffold

**File:**

- `PlayerStatusScreen.kt`

Actions:

- Replace the custom root `Box` and page scrolling with `PfpDetailScaffold`.
- Use `PfpDetailBackground` and `detailPalette()`.
- Use `PfpDetailBreadcrumb` or extend it only if the mockup requires an additional trailing identity block.
- Add `PfpDetailHelperFooter` as a real pinned layout row.
- Remove local hard-coded `TextPrimary`, `TextMuted`, `TextDim`, `CardFill`, and fixed background colors where palette equivalents exist.
- Keep the existing public composable parameters and shell contract stable unless a touch-control parameter is required.

### Task 7 — Implement the mockup header and stat band

**File:**

- `PlayerStatusScreen.kt`

Actions:

- Create a Player Status header matching the mockup’s hierarchy:
  - Back affordance.
  - Large `Player Status` title.
  - Player/rank identity.
  - Bone icon and count.
- Add the compact stat band:
  - `SHIBA LEVEL` and level value.
  - `NEXT LEVEL`, percentage, and progress line.
  - `TOTAL COINS`.
  - Bronze, Silver, Gold, and Platinum counts.
- Reuse `ShibaLevelMedallion` or adapt existing shared medallion styling rather than creating a duplicate level primitive.
- Resolve the mockup’s player identity label from existing account data if available. If no such data exists, retain the current rank label and document the limitation in code/comments rather than inventing a new source.

### Task 8 — Implement the Rarest Unlocked row

**File:**

- `PlayerStatusScreen.kt`

Actions:

- Render Rarest Unlocked below the stat band as a full-width detail row.
- Show:
  - Larger coin art.
  - `RAREST UNLOCKED` label.
  - Coin title.
  - Game title.
  - Global rarity percentage.
  - `of players have this` copy.
- Apply shared focus treatment.
- Activate the existing `ShibaCoinsTarget` when available.
- Add a clear empty state when no rarity data exists.

### Task 9 — Implement the Recent Achievements list

**File:**

- `PlayerStatusScreen.kt`

Actions:

- Render recent achievements as full-width separator-divided rows.
- Match the mockup’s row content:
  - Coin art.
  - Achievement title.
  - Game/platform label.
  - Relative time.
  - Tier label.
- Use stable keys and stable focus IDs.
- Use focus-driven scroll-to-item behavior within the scaffold viewport.
- Preserve touch behavior and existing game-opening behavior.
- Add empty states for no recent achievements and filtered-empty results.

### Task 10 — Implement Options overlay and touch mode

**File:**

- `PlayerStatusScreen.kt`

Actions:

- Render `PspContextMenuOverlay` from the ViewModel Options state.
- Render menu entries with `PspMenuRow`.
- Add `XmbHeaderPill("Options")` in touch mode using the existing library pattern.
- Ensure menu navigation is isolated from the underlying page.
- Ensure selecting sort/provider updates the page and closes the relevant menu level.
- Ensure sync starts through the existing sync path and closes or updates the menu consistently with existing screens.

### Task 11 — Verify shell and settings integration

**Files:**

- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt`
- `feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/SettingsNavHost.kt`
- Any Player Status host composables found during implementation

Actions:

- Preserve the XMB player-card entry point.
- Preserve the Settings player-card entry point.
- Verify touch-control state reaches Player Status if required.
- Preserve the existing Shiba Coins overlay return path.
- Confirm that closing Player Status returns to the same parent context.
- Avoid unrelated navigation changes.

### Task 12 — Add ViewModel unit tests

**New file:**

- `feature/feature-xmb/src/test/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusViewModelTest.kt`

Test cases:

- Initial focus selects the first recent item.
- With no recent items, focus selects Rarest when available.
- Up/down navigation moves through recent rows and stops at boundaries.
- Horizontal navigation moves between Recent and Rarest where applicable.
- Focus survives refresh when the same achievement remains.
- Focus recovers when the focused achievement disappears.
- Newest sorting uses `earnedAt`.
- Rarest sorting uses `globalRarity`.
- Provider filtering works and recovers focus correctly.
- Options root and submenus navigate correctly.
- Sort/provider selection updates state and closes the menu.
- Sync invokes the existing account-wide sync operation.
- Select opens the selected game’s Shiba Coins target.
- Back closes Options first, then the page.

### Task 13 — Add layout and regression coverage

**Files:**

- Existing `core-ui` detail scaffold tests.
- Existing Shiba Library and Shiba Coins tests.
- New Player Status Compose/layout tests where practical.

Test cases:

- Footer reserves space and never overlaps the scrolling body.
- Header, rarest row, and recent list remain readable at the target landscape size.
- Empty states render correctly.
- Touch mode hides controller hints without changing reserved footer geometry.
- Options overlay blocks input to the base page.
- Focused content scrolls above the footer.
- Accent/theme changes use palette values rather than fixed blue colors.
- Existing Shiba Library and Shiba Coins behavior remains green after shared extraction.

### Task 14 — Device and visual verification

Verify against both mockup states:

1. Recent coin focused.
2. Rarest focused with Options open.

Walk through:

- Populated account.
- No recent achievements.
- No rarest achievement.
- Provider filter with no results.
- Newest and Rarest sorting.
- Sync in progress and sync result.
- Touch mode.
- Controller mode.
- Long achievement/game titles.
- Multiple accent themes.
- Smaller landscape viewport and the primary 16:9 target.
- Opening a game’s Shiba Coins page and returning to Player Status.

## 5. Recommended implementation phases

### Phase 1 — Shared extraction and pure logic

- Extract CoinArt and any shared progress/focus pieces.
- Add Options/footer pure functions.
- Add sort/filter/options tests.

### Phase 2 — ViewModel state and controller behavior

- Add stable focus IDs.
- Add Options, sort, provider, and sync state.
- Implement focus recovery and helper-footer state.
- Add ViewModel tests.

### Phase 3 — Visual scaffold

- Migrate to `PfpDetailScaffold`.
- Implement header and stat band.
- Implement Rarest Unlocked row.
- Implement Recent Achievements rows.

### Phase 4 — Overlay, touch, and integration

- Add Options overlay.
- Add touch-mode pills.
- Verify shell/settings integration.
- Verify Shiba Coins navigation and return focus.

### Phase 5 — Regression and device validation

- Run unit and relevant Compose tests.
- Compare both mockup states in Preview/device testing.
- Validate empty, loading, sync, filtering, and responsive cases.

## 6. Verification commands

Use the repository’s Gradle wrapper and existing project conventions:

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlayerStatus*"
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaLibrary*" --tests "*ShibaCoins*"
```

```bash
./gradlew :feature:feature-achievements:testDebugUnitTest --tests "*AchievementRepositoryTest*"
```

Run the project type/build check appropriate to the active Android build configuration after implementation.

## 7. Acceptance criteria

The redesign is complete when:

- Player Status visually follows `docs/mockup/Player Status Mockup.html`.
- The page uses the shared detail background, palette, focus treatment, scaffold, and footer.
- Recent and Rarest content are full-width, readable, and controller-focusable.
- The two mockup states work: recent focused and Rarest focused with Options open.
- Options supports sorting, provider filtering, and account-wide sync through existing infrastructure.
- Touch and controller input share the same activation paths.
- Focus survives data refreshes and recovers safely when content disappears.
- Existing game and Shiba Coins navigation remains intact.
- Offline cached data continues to render.
- No duplicate navigation, Options, sync, or achievement-art systems are introduced.
- Automated tests cover state transitions, focus behavior, menu behavior, and regressions.
