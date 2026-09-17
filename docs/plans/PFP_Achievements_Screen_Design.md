# Play Field Portal --- Achievements Screen Redesign

## 1. Purpose

Redesign the **Tracked Games** and **Untracked Games**
achievement-library screens so they feel closely inspired by the
information hierarchy and browsing rhythm of the PlayStation 3 trophy
screen, while remaining visually and functionally part of **Play Field
Portal (PFP)**.

The design must **not use PlayStation trademarks, logos, trophy artwork,
or other proprietary branding**. The PS3 screen is a layout and
interaction reference only. PFP should use its own Shiba Coin artwork,
game artwork/logos, theme colors, typography, and controller icon
system.

The screen should prioritize:

-   Fast controller-first browsing.
-   A clean, full-width game list rather than a modern card/grid
    interface.
-   Achievement progress that can be understood at a glance.
-   Consistent geometry between Tracked and Untracked views.
-   A permanently available, navigable search control.
-   A lightweight helper footer inspired by console system UIs.
-   Minimal visual clutter.

------------------------------------------------------------------------

## 2. Overall Visual Direction

The screen should resemble a **console-native achievement browser**, not
a Material settings page.

### Core characteristics

-   Full-screen XMB-derived background.
-   Darker header region using a darker variation of the current XMB
    accent/background colors.
-   Mostly transparent content rows.
-   Thin horizontal separators between games.
-   Minimal use of rounded cards or large containers.
-   Game artwork/logo positioned on the far left of each game row.
-   Game information positioned immediately to the right of the artwork.
-   Achievement statistics aligned consistently on the right.
-   Selected rows use the current PFP/XMB accent treatment.
-   Persistent controller helper footer along the bottom edge.
-   Information first; controls and decoration remain visually
    secondary.

The screen should feel spacious even when displaying a large amount of
achievement data.

------------------------------------------------------------------------

## 3. Screen Modes

The achievement library has two sibling views:

1.  **Tracked Games**
2.  **Untracked Games**

The overall screen geometry should remain consistent when switching
between them.

### View switching

-   **L / R** switches between Tracked Games and Untracked Games.
-   Switching views should preserve the overall layout rather than
    loading a visually unrelated screen.
-   The active view is shown in the breadcrumb.

Example:

``` text
◀ Achievements / Tracked Games
```

or:

``` text
◀ Achievements / Untracked Games
```

------------------------------------------------------------------------

## 4. Header

The top of the screen is divided into two conceptual areas.

### Left: Breadcrumb

Example:

``` text
◀ Achievements / Tracked Games
```

The breadcrumb communicates navigation hierarchy without requiring
tab-style navigation.

The back arrow and breadcrumb should use the same visual language as the
redesigned PFP App Drawer.

### Right: Achievement Summary

Tracked Games should use the otherwise empty upper-right area for the
player's global achievement/Shiba summary.

Suggested information:

``` text
[Shiba Level Icon]  263

NEXT LEVEL          TOTAL
26%                 1721
──────

[Platinum] 20   [Gold] 102   [Silver] 283   [Bronze] 1316
```

The exact labels should match the terminology already used by PFP.

Use **PFP Shiba Coin icons**, not PlayStation trophy icons.

The summary should be visually important but should not overpower the
game list.

------------------------------------------------------------------------

## 5. Search Row

A permanent search bar appears immediately before the first game row.

Example:

``` text
🔍  Search games...
────────────────────────────────────────────────────────────
```

### Navigation

The Search row is **navigation position 0**.

Normal navigation:

``` text
SEARCH
  ↓
GAME 01
  ↓
GAME 02
  ↓
GAME 03
```

-   Up from the first game moves to Search.
-   Down from Search moves to the first visible game.
-   Search remains reachable even when filters or sorting change the
    game list.
-   Search participates in normal controller navigation exactly like
    another focusable row.

### Square shortcut

**Square jumps directly to Search from anywhere on the screen.**

Example:

``` text
GAME 37
   │
   │  □
   └──────────────→ SEARCH
```

Square does **not** show or hide the search bar.

The search bar is always present.

If Search already has focus, Square may activate text-entry mode.

### Search activation

When Search is focused:

-   Confirm enters text-entry mode.
-   Typing filters the list in real time.
-   Leaving text-entry mode should preserve the current query.
-   Clearing the query restores the full filtered/sorted game
    collection.

The focused search bar should receive the same general XMB/PFP selection
language as a focused game row.

------------------------------------------------------------------------

## 6. Tracked Games Layout

Tracked Games uses a full-width list.

Each game occupies one horizontal row.

Suggested structure:

``` text
[GAME LOGO]   Final Fantasy IX               82%    [P] 1  [G] 3  [S] 8  [B] 26
              PlayStation                  ━━━━━━━
──────────────────────────────────────────────────────────────────────────────
```

### Left region

Contains:

-   Game artwork/logo.
-   Game title.
-   Platform.

The game logo/artwork occupies the same general visual role that the
square game icon occupies in the PS3 trophy reference.

Artwork should be rectangular or square depending on the source image,
but displayed inside a consistent bounding area.

### Center/right region

Contains:

-   Large completion percentage.
-   Short horizontal progress bar.
-   Shiba Coin counts.

Coin order:

1.  Platinum
2.  Gold
3.  Silver
4.  Bronze

Each value consists of:

``` text
[Coin Icon] Count
```

Do not add permanent text labels to every coin column.

The global summary at the top acts as the visual legend.

### Row density

Rows should display enough information to make browsing useful without
becoming cards.

Avoid:

-   Large rounded row containers.
-   Excessive internal padding.
-   Repeated headings.
-   Heavy shadows.
-   Material-style chips.

Use subtle separators to establish row boundaries.

------------------------------------------------------------------------

## 7. Selected Game State

The selected row should become the visual anchor of the list.

Selection treatment may include:

-   Thin XMB accent border.
-   Slightly brighter translucent background.
-   Soft accent glow.
-   Brighter title and statistics.

Do not substantially increase the selected row's height.

The list should remain stable while navigating.

The existing focus-follow behavior should be preserved: controller
navigation keeps the focused row comfortably visible and does not allow
the viewport to drift away from the cursor.

------------------------------------------------------------------------

## 8. Opening a Tracked Game

Pressing **Confirm / X** on a tracked game opens that game's achievement
list/details.

The helper footer should communicate this as:

``` text
✕ View Achievements
```

or a shorter equivalent such as:

``` text
✕ Select
```

depending on available footer width.

------------------------------------------------------------------------

## 9. Untracked Games Layout

Untracked Games uses the **same row geometry** as Tracked Games.

This is important so switching views does not cause the UI structure to
jump.

Example:

``` text
[GAME LOGO]   Final Fantasy VII
              Windows                       Achievement source not linked
──────────────────────────────────────────────────────────────────────────────

[GAME LOGO]   Sonic Adventure 2
              Windows                       No matching achievement set found
──────────────────────────────────────────────────────────────────────────────
```

### Left region

Same as Tracked:

-   Game artwork/logo.
-   Game title.
-   Platform.

### Right region

Instead of completion percentage and Shiba Coin counts, display the
reason/status explaining why achievements are not tracked.

Examples:

-   Achievement source not linked
-   No matching achievement set found
-   Match required
-   Provider unavailable

Use the existing repository-provided tracking reason wherever possible
rather than inventing unrelated status states.

The purpose is to answer:

> Why is this game not tracked?

without requiring the user to open another screen.

------------------------------------------------------------------------

## 10. Untracked Primary Action

When an untracked game's existing achievement flow supports matching,
Confirm should expose the appropriate matching/action flow.

Potential footer wording:

``` text
✕ Attempt Match
```

The exact behavior should use PFP's existing achievement matching logic
rather than introducing a separate matching system solely for this
screen.

------------------------------------------------------------------------

## 11. Options / Filter Context Menu

**Triangle opens the Options context menu.**

Triangle does not cycle filters directly.

The menu should be a lightweight console-style overlay, preferably
entering from or appearing near the right side of the screen.

The underlying game list remains visible but may be slightly darkened.

Example:

``` text
OPTIONS

SORT BY
› Title
  Progress
  Platform

ORDER
› Ascending
  Descending

PROVIDER
› All
  RetroAchievements
  Steam
  Local Steam
```

### Existing capabilities

The initial implementation should expose existing filtering and sorting
capabilities rather than inventing unnecessary new categories.

Tracked Games currently needs support for concepts such as:

-   Sort by Title
-   Sort by Progress
-   Sort by Platform/Console
-   Ascending / Descending
-   Provider filtering

Provider options should correspond to providers actually supported by
PFP.

### Persistence

Selected sorting/filter options should remain active until changed.

Returning from the context menu restores focus to the previously focused
row.

------------------------------------------------------------------------

## 12. Controller Mapping

The primary controller scheme is:

  -----------------------------------------------------------------------
  Input                               Action
  ----------------------------------- -----------------------------------
  D-pad Up / Down                     Navigate Search and game rows

  Square                              Jump directly to Search

  Triangle                            Open Options / Filter context menu

  L / R                               Switch Tracked Games / Untracked
                                      Games

  Confirm / X                         Select game / open achievements /
                                      perform primary row action

  Back / Circle                       Return to previous screen
  -----------------------------------------------------------------------

### Search shortcut rule

Square always means:

> **Go to Search**

It should work regardless of the currently selected game.

### Modal rule

While the Triangle Options menu or another modal is open:

-   Background list navigation is paused.
-   Controller input belongs to the modal.
-   Closing the modal returns focus to the previously selected element.

------------------------------------------------------------------------

## 13. Helper Footer

A persistent helper footer appears along the bottom edge of the screen.

The footer takes inspiration from later PlayStation console helper bars
but uses PFP's own controller icon assets and visual styling.

### Tracked Games

Suggested footer:

``` text
✕ Select     □ Search     △ Options     L1/R1 Change View     ○ Back
```

A more descriptive Confirm label may be used when space permits:

``` text
✕ View Achievements     □ Search     △ Options     L1/R1 Change View     ○ Back
```

### Untracked Games

Suggested footer:

``` text
✕ Attempt Match     □ Search     △ Options     L1/R1 Change View     ○ Back
```

### Footer styling

The footer should:

-   Remain visible.
-   Use small controller icons.
-   Use small, readable text.
-   Avoid large button containers.
-   Sit directly against the bottom region of the screen.
-   Have very low visual weight.
-   Never compete with the game list.

------------------------------------------------------------------------

## 14. Scrolling and Focus Behavior

Controller navigation must remain predictable.

### Requirements

-   Search is always position 0.
-   The first game is position 1.
-   Navigation proceeds sequentially through visible game rows.
-   Invisible/filtered games are not navigation targets.
-   The selected game must remain inside the visible viewport.
-   Scrolling should follow controller focus rather than allowing the
    cursor to leave the visible area.
-   Input should not produce multiple conflicting focus changes during
    scroll transitions.
-   Sorting/filtering should recover focus to a sensible visible row.
-   If the previously focused game still exists after sorting/filtering,
    prefer retaining focus on that game.
-   If it no longer exists, recover to the nearest sensible position.
-   The user must always be able to navigate back to Search.

------------------------------------------------------------------------

## 15. Empty States

Empty states should preserve the normal screen shell, including:

-   Breadcrumb.
-   Summary where appropriate.
-   Search.
-   Footer.

Examples:

### No tracked games

``` text
No tracked games yet.
```

### All eligible games tracked

``` text
Every eligible game is tracked.
```

### Search has no results

``` text
No games match "Final Fantasy".
```

Do not replace the entire screen with a separate empty-state page.

------------------------------------------------------------------------

## 16. Color and Theme

The screen should inherit the active PFP/XMB theme.

### Background

-   Use the existing XMB background system.
-   The header should use a darker version of the active XMB
    accent/background.
-   Content should remain readable over animated or gradient XMB
    backgrounds.

### Accent

Use the active PFP cursor/accent color for:

-   Focus border.
-   Selected-row highlight.
-   Progress bars.
-   Active menu selections.
-   Search focus.
-   Small hierarchy accents.

Avoid hard-coding PlayStation blue.

------------------------------------------------------------------------

## 17. Typography

Typography should evoke a clean console system interface.

### Hierarchy

**Breadcrumb / Screen title** - Large but lightweight. - Strong
contrast.

**Game title** - Primary row text. - Medium/semibold weight.

**Platform** - Smaller. - Muted.

**Completion percentage** - Larger than normal row text. - Easy to scan
vertically.

**Status / failure reason** - Muted but clearly readable.

**Footer** - Small. - Low visual weight.

Avoid excessive bold text.

------------------------------------------------------------------------

## 18. Artwork and Iconography

### Game artwork

Use game logos/artwork in the left icon position.

The image should:

-   Preserve aspect ratio.
-   Fit within a consistent bounding region.
-   Avoid aggressive cropping when possible.
-   Fall back gracefully if artwork is unavailable.

### Achievement icons

Use PFP's existing Shiba Coin iconography.

Do not use:

-   PlayStation logos.
-   PS3/PS4/PS5 logos.
-   PlayStation trophy graphics.
-   PlayStation button artwork unless PFP already provides legally
    appropriate generic controller glyphs.

The screen should feel familiar through **composition and interaction**,
not copied branding.

------------------------------------------------------------------------

## 19. Layout Summary

Approximate screen composition:

``` text
┌──────────────────────────────────────────────────────────────────────────────┐
│ ◀ Achievements / Tracked Games                 SHIBA LEVEL / GLOBAL TOTALS  │
│                                                                              │
│  🔍 Search games...                                                          │
│──────────────────────────────────────────────────────────────────────────────│
│                                                                              │
│ [LOGO] Game Title                       82%     P 1   G 3   S 8   B 26       │
│        Platform                         ━━━━━━━                              │
│──────────────────────────────────────────────────────────────────────────────│
│ [LOGO] Game Title                       63%     P 0   G 2   S 7   B 18       │
│        Platform                         ━━━━━                                │
│──────────────────────────────────────────────────────────────────────────────│
│ [LOGO] Game Title                      100%     P 1   G 4   S 9   B 32       │
│        Platform                         ━━━━━━━                              │
│──────────────────────────────────────────────────────────────────────────────│
│                                                                              │
│ ✕ Select     □ Search     △ Options     L1/R1 Change View        ○ Back     │
└──────────────────────────────────────────────────────────────────────────────┘
```

------------------------------------------------------------------------

## 20. Design Principles

When implementing or reviewing this screen, use these rules as the final
design test:

1.  **PS3-inspired, PFP-owned.** Borrow hierarchy and rhythm, not
    proprietary assets.
2.  **Games are the interface.** Avoid surrounding every game with UI
    chrome.
3.  **Search is always present.** It is both position 0 and directly
    reachable with Square.
4.  **Triangle owns options.** Filters and sorting live in a context
    menu instead of permanently occupying screen space.
5.  **Tracked and Untracked share geometry.** Information changes; the
    screen structure does not.
6.  **Controller behavior is visible.** The footer always explains the
    important actions.
7.  **Selection stays visible.** Scrolling follows controller focus.
8.  **Use PFP's theme.** Accent colors come from the active XMB theme,
    not a hard-coded imitation.
9.  **Keep the screen quiet.** Progress, artwork, titles, and Shiba
    Coins should carry the visual hierarchy.
10. **Do not regress touch support.** Controller-first behavior should
    coexist with tappable/clickable rows, search, breadcrumb actions,
    and options where appropriate.
