# Play Field Portal — Achievement Pages Touch Controls Implementation Plan

**Status:** Implementation plan  
**Scope:** Per-game achievements, Player Status, and Tracked/Untracked achievement browsers  
**Primary module:** `feature/feature-xmb`

## 1. Goal

Add a consistent touch-control mode to the three achievement-page families without creating a second navigation system:

- **Per-game page:** `ShibaCoinsScreen`
- **Player Status:** `PlayerStatusScreen`
- **Tracked/Untracked browser:** `ShibaLibraryScreen`

Touch and controller input should share the same state transitions and activation paths. Touch mode should make every meaningful action visibly tappable, hide controller-only helper hints, preserve the existing reserved footer band, and keep the current console-style focus model available for mixed touch/controller use.

## 2. Current architecture and gaps

### Existing reusable behavior

The project already has the desired patterns in Game Detail, App Detail, Video Detail, and Artwork Studio:

- `showTouchControls` is resolved globally from `TouchNavButtonMode` and `lastInputWasTouch`.
- `XMBViewModel.markTouchInput()` is the single input-source update path.
- Full-screen detail pages can report touch through an `onTouchInput` callback.
- `XmbHeaderPill` provides visible touch actions in the reserved footer/header band.
- `PfpDetailHelperFooter` provides controller prompts when touch controls are not shown.
- Existing achievement ViewModels already implement stable focus IDs, first-tap focus / second-tap activation, Search editing, Options ownership, and modal input handling.

### Current implementation status

`ShibaCoinsScreen` and `ShibaLibraryScreen` already accept `showTouchControls` and render an Options pill, but they do not yet have a complete, explicit touch interaction contract for every control. `PlayerStatusScreen` accepts `showTouchControls` and renders an Options pill, but the `XMBShell` host currently does not pass the resolved touch state to it.

The achievement pages also do not consistently report touch input at their root. As a result, AUTO mode can fail to reveal touch controls when the user taps these pages unless an ancestor happens to report the event.

### Files to reuse or update

- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaCoinsScreen.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaCoinsViewModel.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaLibraryScreen.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaLibraryViewModel.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusScreen.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/PlayerStatusViewModel.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt`
- Existing shared components in `core/core-ui/.../components` and `core/core-ui/.../detail`

## 3. Touch interaction contract

### 3.1 Input-source behavior

1. The first touch gesture on an achievement page calls `onTouchInput`, which delegates to `XMBViewModel.markTouchInput()`.
2. AUTO mode then exposes touch controls and hides controller helper prompts.
3. ALWAYS_SHOW and ALWAYS_HIDE continue to behave exactly as configured.
4. Reporting touch must not consume the gesture; scrolling, text entry, and the tapped control must still receive it.
5. Controller input continues to go through the existing `pendingGamepadAction` and ViewModel handlers.
6. A controller press after touch mode may hide touch pills according to the existing global input-source rules; no page should maintain a second local touch-mode flag.

Recommended implementation: add `onTouchInput: () -> Unit = {}` to the three screen composables and install one root-level `pointerInput` observer using the same `awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }` pattern already used by Game Detail and Artwork Studio. Pass `viewModel::markTouchInput` from `XMBShell`.

### 3.2 Tap semantics

Use the existing shared behavior already established by the achievement ViewModels:

- Tapping an unfocused row moves logical focus to that row.
- Tapping the already focused row activates it.
- Tapping a direct action button activates it immediately when it is unambiguous.
- Tapping Search enters text editing immediately.
- Tapping an Options pill opens the Options root.
- Tapping an Options row moves its menu selection and activates it on the same tap, matching `onOptionActivated`.
- A modal flow, such as Auto-Match or a text field, owns touch and prevents the underlying page from activating.

The visual focus ring remains useful in mixed input mode and must not be removed merely because touch controls are visible.

## 4. Page-specific design

## 4.1 Per-game achievements (`ShibaCoinsScreen`)

### Visible touch controls

In touch mode, keep the reserved footer band and show:

- `Options` pill
- `Back` pill if the page header's back affordance is not sufficiently discoverable on the target layout

The following page content remains directly tappable:

- Back arrow/title header
- Search field
- All / Earned / Locked tabs
- Platinum Crown row
- Coin rows
- Auto-Match panel buttons
- Auto-Match Yes/No choices
- Manual Steam app-id field and Link/Cancel buttons
- Sync/match notice dismissal line

The initial design should avoid duplicating controls in the footer when the content already has a large, obvious touch target. Add a footer Back pill only if device review shows the header back target is easy to miss.

### Required behavior

- Search tap enters edit mode and shows the IME.
- Tab taps call `setFilter`; active styling updates immediately.
- A coin's first tap focuses it; a second tap reveals/hides a redacted hidden coin when eligible.
- Platinum Crown and ordinary non-hideable coins do not perform a misleading action on a second tap.
- Link-panel buttons invoke the same ViewModel functions as controller Confirm.
- Options overlay blocks the list underneath.
- Tapping outside the Options overlay dismisses it only if that behavior matches `PspContextMenuOverlay`; otherwise use its existing dismissal contract.
- Touching while the page is syncing or matching must not duplicate the operation.

### ViewModel work

- Audit `onRowClick`, `onSearchClick`, tab selection, link-panel actions, and `onOptionActivated` so all are idempotent and safe during loading/sync.
- Add or preserve explicit tests for first-tap focus versus second-tap activation.
- Keep stable row IDs and focus recovery unchanged.

## 4.2 Player Status (`PlayerStatusScreen`)

### Host plumbing required

Update `XMBShell` so Player Status receives:

- `showTouchControls = uiState.resolvedShowTouchButton`
- `onTouchInput = onTouchInput` or the equivalent shell callback

This is required for Player Status to participate in the same AUTO/ALWAYS_SHOW/ALWAYS_HIDE behavior as the other detail pages.

### Visible touch controls

In touch mode, show:

- `Options` pill in the reserved footer band
- Optional `Back` pill if the header back target is not clear enough on the target device

The following content must be tappable:

- Header back affordance
- Rarest Unlocked row
- Recent achievement rows
- Options rows and submenus
- Any visible sync/result notice if it is currently dismissible

### Required behavior

- A first tap on Rarest or a Recent row focuses it and updates the focus ring.
- A second tap opens the existing `ShibaCoinsTarget` for that achievement when available.
- Rows without a valid target remain focusable but do not crash or navigate unexpectedly.
- Rarest remains the initial focus when available, matching the current product decision.
- The Options overlay owns input while open and prevents a tap from also activating the row behind it.
- Sorting, provider filtering, refresh, and sync preserve or recover focus using stable IDs.

### ViewModel work

- Keep touch handlers aligned with controller `SELECT`, not separate business logic.
- Add explicit `onRarestClick` and `onRecentClick` contract tests if current tests only cover gamepad actions.
- Ensure sync-in-progress state disables duplicate taps and keeps cached achievements visible.

## 4.3 Tracked/Untracked browser (`ShibaLibraryScreen`)

### Visible touch controls

In touch mode, retain the existing footer pills:

- `Options`
- Sibling view (`Tracked Games` / `Untracked Games`)

The following content must be tappable:

- Header back affordance
- Search field
- Game rows
- Options rows and Filter/Provider submenus
- Tracked/Untracked sibling pill
- Any row-specific match or open action exposed by the row

### Required behavior

- Search tap immediately enters editing; query changes update the list without losing the input field.
- A first tap on a game focuses it; a second tap opens its Shiba Coins page or starts the existing match path.
- The sibling pill changes mode through `switchSibling(1)` or a clearer direction-aware method, preserving the current ViewModel behavior.
- Provider and sort choices use the same `libraryOptionRows` and `onOptionActivated` path as controller input.
- A filtered or sorted list keeps the same game focused when it remains visible, otherwise recovers to the nearest valid row.
- Empty states remain non-interactive except for Search, Options, and Back.
- Untracked rows that cannot be matched remain readable and safely inert when tapped twice.

### ViewModel work

- Audit `onRowClick`, `onSearchClick`, `switchSibling`, and Options activation for repeated taps and retained ViewModel state.
- Add tests covering tap behavior in both Tracked and Untracked modes.
- Consider replacing the hard-coded sibling action `switchSibling(1)` with an explicit `toggleMode()` or direction-independent action if touch labeling becomes ambiguous.

## 5. Shared implementation tasks

### Task 1 — Establish a shared touch observer pattern

**Files:** the three achievement screens, optionally a new `AchievementTouchSurface.kt` in the detail package.

- Add the `onTouchInput` callback to each screen.
- Report the first pointer-down without consuming the event.
- Ensure nested overlays and text fields do not cause duplicate or stale callbacks.
- Prefer one shared modifier/helper if it can be introduced without obscuring Compose pointer-input ownership.
- Add comments documenting that touch-source reporting and touch activation are separate concerns.

### Task 2 — Complete shell propagation

**File:** `XMBShell.kt`.

- Pass the resolved touch-control boolean and touch callback to Shiba Coins, Shiba Library, and Player Status.
- Verify the Shiba Coins overlay receives the same values whether opened from Game Detail, the library, or Player Status.
- Preserve the return path and retained focus when an overlay closes.

### Task 3 — Normalize touch-visible actions

**Files:** three screens and shared detail components.

- Keep controller helper footer hidden when `showTouchControls` is true.
- Keep the footer's reserved height stable in both modes.
- Use `XmbHeaderPill` for controller-only actions that have no obvious page target.
- Use existing 48dp-or-larger touch targets for rows, tabs, buttons, and header back controls.
- Avoid introducing new colors, modal components, or navigation abstractions.

### Task 4 — Protect modal and overlay input

**Files:** three screens and their ViewModels.

- Confirm Options, Search editing, Auto-Match, and manual app-id entry take precedence over page navigation.
- Ensure a tap that opens an Options menu cannot also trigger the underlying focused row.
- Ensure selecting a menu item does not fall through to the page list.
- Ensure sync/match actions are disabled or ignored while already active.

### Task 5 — Align touch and controller state transitions

**Files:** three ViewModels.

Create a small behavior matrix for each page and verify that touch calls the same public ViewModel action used by controller Confirm wherever possible. The only deliberate differences should be:

- First tap focuses; second tap activates.
- Search tap enters editing directly.
- Menu row taps select/activate directly.
- Touch source reporting updates global presentation state.

### Task 6 — Add touch-focused tests

**New or updated tests:**

- `ShibaCoinsTouchTest` or additions to `ShibaCoinsOptionsTest`
- `ShibaLibraryTouchTest` or additions to existing library ViewModel/helper tests
- `PlayerStatusTouchTest` alongside `PlayerStatusOptionsTest`
- `XMBShell` integration/host test if an existing shell test harness supports parameter propagation

Test cases:

- First tap focuses an unfocused row; second tap activates it.
- Search tap starts editing for all pages that expose Search.
- Options pill opens the root menu.
- Options row tap invokes the same state transition as controller Select.
- Taps are ignored or safely handled while a modal flow is active.
- Touch mode hides helper prompts but preserves footer height.
- Shell passes touch mode to all three pages.
- Player Status receives the rarest initial focus and opens the correct game target.
- Library sibling pill switches between Tracked and Untracked.
- Per-game tabs change the filter and retain valid focus.
- Repeated sync/match taps do not start duplicate jobs.

### Task 7 — Add layout/semantics coverage where practical

- Assert key touch targets exist and have meaningful labels/content descriptions where the current test stack supports semantics.
- Assert Options and Back pills are present only in touch mode.
- Assert controller prompts are absent in touch mode and present in controller mode.
- Assert the scrolling body does not overlap the footer in either mode.
- Assert the Options overlay covers/blocking behavior is preserved.

### Task 8 — Device and visual verification

At the primary landscape target and a smaller landscape viewport, verify:

- Touch mode appears after the first tap in AUTO mode.
- Pills are not clipped and have adequate spacing.
- Search and IME interaction does not collapse the layout.
- Rows remain easy to tap despite dense achievement metrics.
- Long titles do not push Options or sibling pills off-screen.
- Options overlay remains readable and dismissible.
- Cached/offline, empty, loading, syncing, and matching states remain usable.
- Returning from a per-game page restores the prior library or Player Status focus.
- Controller-only behavior is unchanged when touch controls are hidden.

## 6. Recommended implementation phases

### Phase 1 — Plumbing and shared contract

- Add `onTouchInput` to the three screens.
- Add the root touch observer pattern.
- Pass touch state/callback from `XMBShell`, including the missing Player Status propagation.
- Add shell and presentation tests.

### Phase 2 — Per-game page

- Audit all existing tappable content and target sizes.
- Complete Search, tabs, rows, link panel, Options, and modal behavior.
- Add per-game touch and regression tests.

### Phase 3 — Tracked/Untracked browser

- Complete row, Search, sibling-view, Options, and empty-state touch behavior.
- Verify focus recovery after touch-triggered filtering/sorting.
- Add library touch tests.

### Phase 4 — Player Status

- Complete Rarest, Recent, Options, sync notice, and Back touch behavior.
- Verify rarest initial focus and Shiba Coins navigation.
- Add Player Status touch tests.

### Phase 5 — Device validation and polish

- Compare touch/controller modes on device.
- Fix target sizing, clipping, focus-ring visibility, and modal fall-through issues.
- Run all relevant regressions before merging.

## 7. Acceptance criteria

- All three achievement-page families participate in the global touch-control setting.
- AUTO mode reveals touch controls after a touch on any of the three pages.
- Touch controls are not duplicated or locally stateful; global `resolvedShowTouchButton` remains the source of truth.
- Every visible page action has an obvious, tappable target or an intentional inert state.
- Touch and controller actions converge on the same ViewModel logic.
- First-tap focus / second-tap activation works consistently for achievement and game rows.
- Search, Options, sync/match, and Auto-Match modal states correctly own input.
- Footer geometry is stable and content never hides behind it.
- Player Status receives touch state from `XMBShell` and preserves rarest-first behavior.
- Existing Game Detail, Shiba Coins, Shiba Library, and Player Status controller behavior remains green.
- No new navigation, sync, or Options framework is introduced.

## 8. Verification commands

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoins*" --tests "*ShibaLibrary*" --tests "*PlayerStatus*"
```

```bash
./gradlew :feature:feature-xmb:compileDebugKotlin
```

Run the relevant shell/detail Compose tests if available, then perform the landscape device walkthrough described in Task 8.
