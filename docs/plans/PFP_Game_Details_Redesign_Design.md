# PlayFieldPortal Game Details Redesign

**Status:** Approved design handoff  
**Target:** `PlayFieldPortal` Android application  
**Primary screen:** `GameDetailScreen.kt`  
**Navigation target:** `core/core-navigation` unified `NavigationEngine`  

---

## 1. Purpose

Redesign the Game Details page into a controller-first, console-style information screen that fits PlayFieldPortal's current PSP/XMB-inspired interface.

The redesign must preserve the existing Game Details features while replacing its hard-coded controller focus system with the shared unified navigation engine.

The approved visual direction combines:

- A restrained PS3-era console information layout.
- The organization and readability of a classic handheld storefront.
- PFP's active XMB accent colors.
- Full-width translucent information rows instead of a collection of floating Material cards.
- A permanent, context-sensitive controller helper footer.

This is a redesign and navigation migration, not a reduction of the current feature set.

---

## 2. Relevant Existing Files

Claude must inspect these files before making changes:

- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailViewModel.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/DetailComponents.kt`
- `feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt`
- `core/core-navigation/src/main/kotlin/com/playfieldportal/core/navigation/NavigationEngine.kt`
- `feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/ControllerNavigation.kt`

Also inspect the App Drawer and App Picker implementations for the approved permanent helper-footer behavior and controller glyph mapping.

Do not create a second unrelated navigation framework for this screen.

---

## 3. Existing Features That Must Be Preserved

The redesigned page must continue to support:

- Game hero artwork.
- Game icon or cover artwork.
- Game title and platform.
- Launching the game.
- Direct-launch/auto-launch behavior.
- Favorite status.
- Options context menu.
- Artwork Studio.
- Manual viewer.
- Multi-disc game selection and preferred-disc persistence.
- Shiba Coins summary and navigation to the full Shiba Coins screen.
- Release year, developer, publisher, genre, play history, and playtime.
- Resolved emulator, core, and launch-source information.
- Emulator selection.
- Game description.
- Video and screenshot previews.
- Fullscreen image preview.
- Built-in or external video playback.
- Collection picker.
- Note editing.
- Title editing.
- Removal confirmation.
- Launch error and recovery behavior.
- Controller and touch input.
- Android/package-backed game differences.
- Artwork refresh and game-location actions.

Existing repository and ViewModel logic should be reused wherever possible.

---

## 4. Approved Page Structure

Use a single, vertically scrollable details page. Do not divide the first implementation into L1/R1 tabs.

The screen is ordered as follows:

1. Breadcrumb header.
2. Hero banner.
3. Primary action region.
4. Optional disc selector.
5. Shiba Coins row, when supported.
6. Overview row.
7. Game Information row.
8. Media strip, when media exists.
9. Permanent controller helper footer.

The header and page body may scroll together. The helper footer must remain pinned in its own layout row and must never float over content.

### 4.1 Breadcrumb Header

Show:

`LIBRARY / {PLATFORM} / {GAME TITLE}`

Requirements:

- Use a slim, darkened version of the current accent color.
- Preserve a touch-accessible Back target.
- Keep the current clock/status treatment where supplied by the shell.
- Do not include platform-holder trademarks or logos.

### 4.2 Hero Banner

The hero is the main visual anchor.

Show:

- Hero artwork using the existing fallback order.
- Game logo or title.
- Platform name.
- Last-played value when available.
- Total playtime when available.
- Favorite status.

Requirements:

- Use a dark readability gradient over the artwork.
- Keep game identity readable regardless of the hero's brightness.
- Do not make the hero itself a controller-focus target.
- Missing hero artwork must fall back gracefully without changing page geometry.

### 4.3 Primary Action Region

The primary region contains:

- Game icon/cover on the left.
- Large `Launch` action.
- Quick actions below Launch.

Initial quick actions:

1. Favorite
2. Artwork
3. Manual
4. Emulator

Rules:

- `Launch` is the initial controller focus.
- The Launch button uses the strongest focus treatment on the page.
- Manual may remain visible while unavailable, but its unavailable state must be visually clear and non-focusable. If repository convention prefers hiding unavailable actions, follow the existing shared convention consistently.
- Emulator is omitted or disabled for package-backed games where emulator configuration is irrelevant.
- Quick actions must use existing business actions rather than duplicate logic.

### 4.4 Disc Selection

Only show this section when the game belongs to a multi-disc set.

- Each disc is its own stable navigation node.
- Selecting a disc persists it as the preferred disc using the current repository behavior.
- Adding or removing disc members must not invalidate focus or move focus to an unrelated element.

### 4.5 Shiba Coins

Show a full-width information row containing:

- `SHIBA COINS` label.
- Earned and total count.
- Completion percentage.
- Progress bar.
- Disclosure indicator.

Behavior:

- Confirm opens the dedicated Shiba Coins screen.
- Android games do not show this row.
- If achievement data is loading, preserve the row's geometry and use an appropriate loading state.

### 4.6 Overview

Show the game description in a full-width row.

- Prefer two or three readable lines in the collapsed view.
- If the description exceeds the available space, Confirm expands or opens the complete text.
- Do not place long text inside a tiny fixed-height card.

### 4.7 Game Information

Use a full-width row or structured information band rather than a detached card.

Supported fields include:

- Developer
- Publisher
- Genre
- Release year/date
- Last played
- Total playtime
- Emulator
- Emulator core
- Launch-source/default information

Rules:

- Hide absent values rather than filling the screen with `Unknown` unless the existing data contract explicitly requires it.
- The Emulator value is focusable only when changing it is supported.
- Confirm on the Emulator field opens the emulator picker.
- Package-backed Android games must not show irrelevant emulator metadata.

### 4.8 Media Strip

Show video previews before screenshots, matching the current behavior.

- Each media item is a stable horizontal navigation node.
- Left and Right move within the strip and stop at the ends.
- Up returns to the nearest logical row above.
- Confirm opens the existing video or image viewer.
- Returning from the viewer restores focus to the media item that opened it.
- Missing media removes the section without leaving an empty gap.

---

## 5. Approved Background Treatment

The background must be plain, accent-driven, and slightly transparent.

Implementation intent:

- Source color from the active PFP/XMB color theme through `LocalPFPColors` or the existing equivalent.
- Paint a mostly uniform accent-derived surface at approximately 75–85% opacity.
- Allow the launcher/XMB backdrop to remain faintly visible underneath.
- An extremely subtle vertical tonal gradient is acceptable for text separation.
- The design must work with every supported accent color, not only blue.

Do not use:

- A baked-in blue background.
- Bright wave streaks.
- Star fields or particles.
- High-contrast decorative gradients.
- Busy textures.
- Independent per-card glass effects.

The hero artwork and game media should remain the visually dominant imagery.

---

## 6. Surface and Color Rules

- Header: darker derivative of the active accent.
- Page background: active accent at approximately 75–85% opacity.
- Information rows: dark translucent neutral surface with a subtle accent-derived edge.
- Primary text: high-contrast white or resolved theme text color.
- Secondary text: readable muted theme text color.
- Focus edge: current `menuCursorEdge()` or the approved shared focus edge.
- Focus fill/glow: current `menuCursorFill()` or shared cursor fill.
- Launch fill: restrained green may remain, provided text contrast passes.
- Destructive actions belong in the Options context menu and use the existing destructive styling.

Do not use the accent color as small body text when it fails contrast requirements.

---

## 7. Focus Appearance

Use one consistent controller-focus language:

- Thin bright accent/cyan outline.
- Small, restrained outer glow or fill lift.
- No oversized scaling animation.
- No layout shift when focus changes.
- Unfocused elements retain enough border definition to remain discoverable.
- Touch input hides the visual cursor but does not destroy logical focus.
- The next controller input restores the cursor at the preserved logical node.

Launch may receive a slightly stronger focus treatment because it is the primary action.

---

## 8. Unified Controller Navigation

The redesign must migrate Game Details to the shared `NavigationEngine`.

The current combination of `mainFocus`, `discFocusIndex`, `mediaFocus`, and `pageScrollSteps` must not remain the authoritative navigation model.

### 8.1 Stable Node Keys

Use stable semantic keys. Recommended keys:

| Element | Stable key |
| --- | --- |
| Launch | `game-detail:launch` |
| Favorite | `game-detail:favorite` |
| Artwork | `game-detail:artwork` |
| Manual | `game-detail:manual` |
| Emulator quick action | `game-detail:emulator-action` |
| Disc | `game-detail:disc:{gameId}` |
| Shiba Coins | `game-detail:shiba-coins` |
| Overview | `game-detail:overview` |
| Emulator information | `game-detail:emulator-info` |
| Media item | `game-detail:media:{mediaStableId}` |

Do not use visible list indices as stable identity.

### 8.2 Directional Behavior

- Up/Down moves between visual rows using registered geometry.
- Left/Right moves between siblings within a row.
- Directional movement stops at boundaries; it never wraps.
- Moving between rows should select the nearest reasonable horizontal neighbor.
- Invisible, disabled, or unavailable nodes are excluded from traversal.
- The page scrolls to keep the focused node comfortably visible.
- Input is blocked during navigation recovery/scroll alignment animation when required by the shared engine.
- Repeated input during a recovery lock is dropped, not queued.

### 8.3 Initial Focus and Readiness

- Initial focus is `game-detail:launch`.
- Do not accept navigation input until the game is loaded and the initial node graph is registered.
- Call the shared readiness mechanism after the first usable graph exists.
- Input received before readiness is ignored, not replayed later.
- Loading, missing-game, and load-error states must not leave the navigation engine permanently unready.

### 8.4 Scroll-to-Focus

Replace artificial page scroll steps with focus-driven scrolling.

- Register or associate a `BringIntoViewRequester` with each focusable page node.
- When the focused key changes, bring that node fully into the safe visible body area above the helper footer.
- Center focused rows when practical, but do not force the top or bottom of the page beyond its scroll bounds.
- The helper footer's height must be included in the safe viewport calculation.
- Touch scrolling remains natural and does not forcibly snap until controller navigation resumes.

### 8.5 Controller Shortcuts

Base mapping:

| Input | Action |
| --- | --- |
| D-pad / left stick | Navigate visible nodes |
| Cross / A | Activate focused node |
| Triangle / Y | Open Options from anywhere on the base page |
| Circle / B | Back |

Do not assign L1/R1 in the first implementation because the approved page is not tabbed.

### 8.6 Helper Footer

The helper footer is a real, permanent layout row below the scrolling body.

Requirements:

- It does not overlay or resize content when its labels change.
- It uses the configured controller icon family: PlayStation, Xbox, or Nintendo/Switch style.
- It displays only actions available in the current context.
- Default Launch-focused example: `Confirm Launch`, `Options`, `Back`.
- On a media tile, Confirm text changes to `Preview` or `Play` as appropriate.
- On Shiba Coins, Confirm text changes to `View`.
- During a blocking modal, the footer changes to that modal's actions.
- If existing PFP behavior fades controller hints, opacity may fade; the footer's reserved geometry must remain.

---

## 9. Modal and Overlay Navigation

Every blocking overlay must own the active navigation context.

This includes:

- Options menu.
- Emulator picker.
- Collection picker.
- Removal confirmation.
- Note editor.
- Title editor.
- Manual viewer.
- Image viewer.
- Video player.
- Launch-recovery surface.

Rules:

- Push a modal navigation context when opened.
- The underlying Game Details graph is paused and receives no input.
- Back closes the topmost modal before leaving Game Details.
- Closing a modal restores the exact previously focused base-page node.
- Confirm and directional actions must never leak through to the base page.
- Artwork Studio remains a full-screen replacement and owns its own input while visible.

---

## 10. Touch Behavior

Controller and touch must invoke the same business actions.

- Touching an actionable element updates logical focus to that element before activation where appropriate.
- A touch anywhere on the screen marks touch as the active input source without consuming scrolling or button gestures.
- Touch hides the controller cursor.
- Touch scrolling must work across the header, hero, and body where the page is intended to scroll.
- Touch must not create a second action implementation separate from controller activation.
- After touch use, the next controller direction re-anchors from the nearest valid logical node according to the unified navigation behavior.

---

## 11. Suggested Component Breakdown

Reuse or refactor existing components before creating duplicates.

Suggested structure:

- `GameDetailScaffold`
  - Owns header/body/footer layout.
  - Owns accent-derived background treatment.
- `GameDetailBreadcrumb`
- `GameHeroBanner`
- `GamePrimaryActions`
- `GameDiscRow`
- `GameShibaCoinsRow`
- `GameOverviewRow`
- `GameInformationRow`
- `GameMediaRow`
- `GameDetailHelperFooter`

Shared focus visuals and controller glyphs should come from existing PFP components or shared core UI rather than being copied locally.

---

## 12. State Migration

### Remove as authoritative navigation state

- `mainFocus`
- `discFocusIndex`
- `mediaFocus`
- `pageScrollSteps`

Temporary compatibility fields are acceptable during migration, but final controller behavior must be driven by stable navigation keys and the shared engine.

### Preserve business/UI state

Keep state for:

- Loaded game/platform.
- Selected disc.
- Resolved launch.
- Available media.
- Visible actions.
- Modal visibility and modal-specific selection where the modal has not yet migrated.
- Loading/error/message states.
- Open viewer/manual/video state.

Navigation state should not be mixed into game-domain state unless required for persistence across screen recreation.

---

## 13. Dynamic Content Rules

The navigation graph must safely update when content changes.

Examples:

- Manual becomes available after an artwork refresh.
- Media arrives or is removed.
- Emulator resolution changes.
- Achievement data loads.
- Disc membership changes.
- A package-backed game omits emulator controls.

Required behavior:

- Preserve the current focused key if it remains valid.
- If the focused node disappears, recover to the nearest visible enabled node using previous and current geometry.
- Never focus an invisible or disabled element.
- Never reset to Launch merely because unrelated asynchronous content changed.

---

## 14. Responsive Layout

Primary target is landscape Android handheld/TV-style display, including 16:9 at approximately 960×540 logical layout and higher physical resolutions.

Requirements:

- Keep the content centered with a sensible maximum width.
- Maintain readable margins at smaller landscape sizes.
- Hero and primary action region may compact vertically on short screens.
- Quick actions must remain usable without text clipping.
- Game Information may wrap from one horizontal band into multiple rows at narrower widths.
- Footer remains one stable row.
- Do not solve smaller layouts by scaling text below readable sizes.

Portrait is not the design target, but the screen must fail gracefully if encountered.

---

## 15. Accessibility and Readability

- Primary body text should meet a 4.5:1 contrast target.
- Large display text should meet at least 3:1.
- Do not rely on color alone to communicate focus, favorite state, progress, errors, or disabled state.
- Provide content descriptions for icon-only controls.
- Touch targets should meet Android minimum sizing guidance.
- Respect reduced-motion behavior.
- Focus animation must remain clear when system animation scale is reduced.
- Long titles and metadata must ellipsize or wrap without covering adjacent controls.

---

## 16. Implementation Order

### Phase 1 — Scaffold and visual structure

- Introduce the pinned header/body/footer structure.
- Implement the accent-derived translucent background.
- Recompose existing data into the approved hero and full-width rows.
- Preserve existing actions using the current callbacks.

### Phase 2 — Unified navigation graph

- Register stable semantic nodes.
- Establish initial Launch focus and readiness.
- Implement geometry-based directional movement.
- Add focus-driven bring-into-view behavior.
- Remove artificial page-step navigation.

### Phase 3 — Modal contexts

- Migrate or adapt Options and pickers to modal navigation contexts.
- Verify input isolation.
- Restore base-page focus after close.

### Phase 4 — Touch and helper footer

- Route touch and controller through shared actions.
- Add contextual footer labels and controller glyph styles.
- Verify that hint fading never changes layout geometry.

### Phase 5 — Cleanup and tests

- Remove obsolete focus indices and page scroll state.
- Add unit and Compose tests.
- Validate package-backed, multi-disc, missing-artwork, missing-manual, and no-media cases.

Do not mix unrelated feature work into this migration.

---

## 17. Required Tests

### Navigation unit tests

- Initial focus is Launch after readiness.
- Input before readiness is ignored and not buffered.
- Left/Right stops at quick-action and media-strip boundaries.
- Up/Down chooses the correct neighboring row.
- Disabled/hidden nodes are skipped.
- Removing the focused dynamic node recovers to the nearest valid node.
- Touch hides the cursor while preserving logical focus.
- Controller input restores the cursor.
- Recovery lock drops repeated input.

### Modal tests

- Options receives all navigation while open.
- Base-page actions do not fire through a modal.
- Back closes the modal before closing Game Details.
- Closing Options restores the exact prior node.
- Viewer, emulator picker, collection picker, and confirmation surfaces obey the same isolation rule.

### Compose/layout tests

- Footer occupies a permanent row and never overlaps body content.
- Footer visibility/opacity changes do not alter page geometry.
- Focusing each lower-page node scrolls it above the footer.
- A multi-disc game exposes one stable node per disc.
- Android/package-backed games omit unsupported achievement/emulator controls.
- Missing manual and missing media do not create focusable ghost nodes.
- Long titles do not overlap header actions.
- Active accent changes update the page background without rebuilding fixed blue assets.

### Regression tests

- Launch and direct-launch behavior remain unchanged.
- Preferred-disc selection remains persistent.
- Artwork Studio returns to the refreshed Game Details page.
- Shiba Coins returns to the same page and focus position.
- Media preview opens and closes correctly.
- Manual viewer paging remains functional.
- All existing Options actions remain reachable.

---

## 18. Acceptance Criteria

The redesign is complete when:

- The page matches the approved console/storefront visual direction.
- The background is derived from the active accent color and remains slightly transparent.
- The Game Details feature set is preserved.
- Launch is the initial focus.
- Every visible controller-actionable element has a stable semantic node.
- Directional navigation follows visual geometry and never wraps.
- Controller focus automatically scrolls into view without artificial page-step state.
- Touch and controller share the same action paths.
- Touch hides the cursor without losing logical focus.
- Modals pause the underlying page and restore exact focus when closed.
- The permanent helper footer never overlays content or changes page geometry.
- Footer hints match the current context and controller icon family.
- Unavailable or invisible actions cannot receive focus.
- The screen remains usable for standard ROMs, multi-disc games, Windows entries, and package-backed Android games.
- Automated tests cover navigation boundaries, modal isolation, dynamic-node recovery, and footer layout.

---

## 19. Explicit Non-Goals

Do not include these in the first implementation:

- L1/R1 tab navigation.
- A new game-metadata provider.
- New Shiba Coins functionality.
- New Artwork Studio functionality.
- A replacement launch pipeline.
- Emulator gameplay/controller remapping.
- Platform-holder branding.
- A fixed blue background asset.
- A second navigation engine.
- Automatic removal of existing Options actions.

---

## 20. Claude Handoff Instruction

Before editing code:

1. Inspect all files listed in Section 2.
2. Identify the existing shared helper-footer, controller-glyph, focus-border, and modal patterns.
3. Map every current Game Details action to its redesigned location.
4. Present a concise file-by-file implementation plan.
5. Confirm that no existing action will be lost.
6. Implement in the phases listed above, keeping each phase reviewable and testable.

When repository behavior conflicts with an assumption in this document, preserve verified business behavior and report the conflict before changing the product rule.
