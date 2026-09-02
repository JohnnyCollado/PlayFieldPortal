# Play Field Portal --- App Drawer PSP-Era Redesign

## 1. Purpose

Redesign the Play Field Portal (PFP) App Drawer so that it keeps the
**original grid-centric App Drawer layout** while adopting the visual
language, density, motion, and controller-first character of the classic
PSP era.

The goal is **not** to reproduce the PSP PlayStation Store. The App
Drawer should instead feel like a local application library that could
plausibly have existed as part of a PSP-era handheld interface.

The current PSP Store-inspired App Drawer implementation must be
preserved for a future **RSS Channels** feature, where its storefront
structure is a better functional and visual match.

This document is intended as an implementation specification for the
existing Kotlin / Jetpack Compose PFP codebase.

------------------------------------------------------------------------

## 2. High-Level Decision

### App Drawer

The App Drawer will return to the original PFP information architecture:

-   Grid-centric application browsing.
-   Horizontal filter/category navigation.
-   Breadcrumb/back navigation at the top.
-   Search support.
-   Sort/filter access.
-   Controller-first navigation.
-   Touch support.
-   Contextual controller helper/hint bar.
-   Existing application actions and state behavior.

The redesign should primarily change **presentation**, not the
underlying browsing model.

### RSS Channels

The current PSP Store-inspired App Drawer layout should **not be
deleted**.

Its current characteristics are intentionally suited to a future RSS
Channels screen:

-   Storefront-style header.
-   Vertical category rail.
-   Main content pane.
-   Strong panel separation.
-   PSP Store-inspired chrome.
-   Content/category browsing structure.

Before replacing the App Drawer UI, preserve this implementation so it
can later be adapted into an RSS Channels feature.

------------------------------------------------------------------------

# 3. Design Philosophy

## 3.1 PSP-Era, Not PSP Store

The App Drawer should answer this question:

> What would PFP's existing application library look like if it had been
> designed during the PSP era?

It should **not** answer:

> How closely can we recreate the PSP PlayStation Store?

The distinction is important.

The application artwork and library remain the visual focus. PSP
character should come from:

-   Background treatment.
-   Typography.
-   Selection treatment.
-   Thin borders.
-   Compact spacing.
-   Navigation chrome.
-   Accent colors.
-   Controller hints.
-   Restrained transitions.

Do not force every piece of content into a storefront panel.

------------------------------------------------------------------------

## 3.2 Preserve PFP Identity

PFP should feel inspired by classic handheld interfaces without becoming
a replica.

Do not introduce:

-   PlayStation logos.
-   PSP logos.
-   PlayStation Store logos.
-   Sony branding.
-   Proprietary PlayStation artwork.
-   Permanent PlayStation-specific decorative symbols.

Controller glyphs may dynamically reflect the user's controller layout
through PFP's existing controller helper system.

------------------------------------------------------------------------

# 4. Required Layout

The target structure is:

``` text
┌────────────────────────────────────────────────────────────────────┐
│ ‹ Apps / Android                                   Search    Sort  │
│                                                                    │
│ ALL     GAMES     EMULATORS     RECENT     FAVORITES               │
│                                                                    │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│ │          │ │          │ │          │ │          │               │
│ │   ICON   │ │   ICON   │ │   ICON   │ │   ICON   │               │
│ │          │ │          │ │          │ │          │               │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
│  Dolphin      PPSSPP       RetroArch    Moonlight                 │
│                                                                    │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│ │   ICON   │ │   ICON   │ │   ICON   │ │   ICON   │               │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
│                                                                    │
├────────────────────────────────────────────────────────────────────┤
│ L/R Category        Back    Launch    Options    Search             │
└────────────────────────────────────────────────────────────────────┘
```

The helper footer shown above is contextual and is **not permanently
visible**.

------------------------------------------------------------------------

# 5. Header

## 5.1 Breadcrumb

Keep the existing PFP back/breadcrumb concept.

Example:

``` text
‹ Apps / Android
```

The breadcrumb should remain compact.

Do not replace it with a large page title or branding block.

The header should visually belong to the active PFP theme and accent
color.

------------------------------------------------------------------------

## 5.2 Header Actions

The right side should support lightweight actions such as:

-   Search.
-   Sort / Filter.

Avoid large Material buttons.

Use small text, icons, or PSP-era geometric action indicators.

These should feel integrated into the screen chrome rather than floating
above it.

------------------------------------------------------------------------

# 6. Horizontal Categories

The App Drawer should use **horizontal categories**, restoring the
original browsing relationship rather than the current vertical
storefront rail.

Examples may include the filters already supported by the App Drawer:

``` text
ALL     GAMES     EMULATORS     RECENT     FAVORITES
```

Do not invent new categories merely for the redesign.

The actual available filters remain driven by the existing App Drawer
state.

## 6.1 Selected Category

The selected category should be clear but restrained.

Possible treatment:

``` text
ALL     GAMES     EMULATORS     RECENT
        ━━━━━
```

or:

``` text
ALL    [ GAMES ]    EMULATORS    RECENT
```

Prefer:

-   Thin underline.
-   Bright accent-colored indicator.
-   Slight text brightness increase.
-   Small geometric marker.

Avoid:

-   Large pills.
-   Material filter chips.
-   Oversized selected tabs.
-   Large cards around category labels.

------------------------------------------------------------------------

# 7. Category Navigation

Controller category switching remains:

``` text
L / LB  = Previous Category
R / RB  = Next Category
```

Changing category should not require moving focus into the category row.

The category row primarily communicates state.

Touch users may still tap categories directly.

------------------------------------------------------------------------

# 8. Application Grid

The application grid is the primary visual element.

Preserve the existing grid browsing model and existing app data/state
infrastructure wherever possible.

Application artwork should dominate the presentation.

## 8.1 App Tile

Preferred structure:

``` text
┌────────────┐
│            │
│    ICON    │
│            │
└────────────┘
   Dolphin
```

Characteristics:

-   Large artwork/icon.
-   App name beneath artwork.
-   Tight spacing.
-   Minimal container chrome.
-   Little or no corner rounding.
-   No large Material card background.
-   No permanent shadow.
-   No unnecessary metadata inside every tile.

The screen should read as a **library of artwork**, not a collection of
Android cards.

------------------------------------------------------------------------

# 9. Selection State

Controller selection must be unmistakable.

Preferred concept:

``` text
NORMAL                     SELECTED

┌──────────┐              ╔════════════╗
│          │              ║ ┌────────┐ ║
│   ICON   │              ║ │  ICON  │ ║
│          │              ║ └────────┘ ║
└──────────┘              ╚════════════╝
  PPSSPP                     Dolphin
```

Selection may use:

-   Thin bright outer border.
-   Secondary inner highlight.
-   Accent-color glow.
-   Slightly brighter app label.
-   Very subtle translucent plate behind the artwork.

Do **not** rely on:

-   Large scaling.
-   Bouncing.
-   Large floating cards.
-   Heavy shadows.
-   Material elevation.

Selection should feel crisp and immediate.

------------------------------------------------------------------------

# 10. Accent Color Integration

Accent color support is a **core requirement**.

The App Drawer must not be hard-coded to PSP blue.

The screen should derive its appearance from PFP's current
theme/accent-color system.

Examples:

-   Blue theme → classic PSP blue/cyan atmosphere.
-   Orange theme → warm amber/orange PSP-style environment.
-   Green theme → green handheld/XMB-like environment.
-   Purple theme → violet/purple environment.
-   Pink theme → pink/magenta environment.

The accent color should influence:

-   Background gradients.
-   Selected-category indicator.
-   Selection border/glow.
-   Dividers.
-   Header accents.
-   Small decorative highlights.
-   Helper/footer accents.

## 10.1 Accent Color Philosophy

The accent color should behave like **environmental lighting**, not a
bucket of paint.

Do not recolor every surface to the exact same value.

Instead derive tonal roles such as:

``` text
appDrawerBackgroundDeep
appDrawerBackgroundMid
appDrawerChrome
appDrawerChromeDark
appDrawerAccent
appDrawerAccentBright
appDrawerDivider
appDrawerSelectionEdge
appDrawerTextPrimary
appDrawerTextSecondary
```

These should ideally be calculated from or supplied by the existing PFP
theme architecture.

Maintain sufficient contrast regardless of accent color.

------------------------------------------------------------------------

# 11. Background

The background should evoke classic PSP/XMB-era UI without copying
proprietary assets.

Preferred characteristics:

-   Accent-derived gradient.
-   Darker upper/header region.
-   Rich midtone content region.
-   Slight depth variation.
-   Optional extremely subtle atmospheric gradient/wave treatment if
    already supported by PFP themes.

Avoid:

-   Material surfaces everywhere.
-   Frosted glass.
-   Heavy blur.
-   Modern gradient blobs.
-   Flat single-color backgrounds.
-   Excessive animated backgrounds.

The application artwork should remain visually dominant.

------------------------------------------------------------------------

# 12. Controller Mapping

Preserve the established App Drawer mapping:

  -------------------------------------------------------------------------
  Physical Position Xbox Style        PlayStation-Style   Function
  / Action                            Position            
  ----------------- ----------------- ------------------- -----------------
  Confirm / Bottom  A                 Cross               Launch / Select
  Face                                                    

  Back / Right Face B                 Circle              Back

  Left Face         X                 Square              Search

  Top Face          Y                 Triangle            Options

  Left Shoulder     LB                L1 / L              Previous Category

  Right Shoulder    RB                R1 / R              Next Category

  Directional       D-pad / Stick     D-pad / Stick       Navigate Grid
  -------------------------------------------------------------------------

The App Drawer should use physical-action semantics internally rather
than hard-coding one controller brand.

------------------------------------------------------------------------

# 13. Controller Hint / Helper Bar

The helper bar must use the **same contextual fade-in and cut-out
behavior used elsewhere in PFP**.

This is a required behavior.

## 13.1 Behavior

The helper bar should:

1.  Become visible when controller interaction makes the available
    commands relevant.
2.  Fade in using the same timing/animation convention as the existing
    PFP hint system.
3.  Remain visible for the same established duration.
4.  Fade/cut out according to the same existing behavior.
5.  Reappear when appropriate controller activity occurs.

Do not invent a second hint-animation system specifically for the App
Drawer.

Reuse or integrate with the existing helper behavior.

## 13.2 Layout Stability

The App Drawer content should **not jump vertically** when the helper
appears or disappears.

Choose an implementation that maintains stable content geometry.

The helper can overlay/reserve its established region as appropriate to
the existing PFP helper implementation.

## 13.3 Example

``` text
L  Previous Category
R  Next Category
B  Back
A  Launch
Y  Options
X  Search
```

Actual glyphs should come from PFP's controller glyph/helper
infrastructure.

------------------------------------------------------------------------

# 14. Search

Search remains activated through:

``` text
X / Square position
```

Touch users should also be able to activate Search from the header.

Search should reuse existing filtering behavior.

Visually, Search should feel like a PSP-era mode rather than a large
modern Material search component.

Possible treatment:

``` text
SEARCH
────────────────────────────────────────
> retro_
────────────────────────────────────────
```

Search may temporarily expand within the header or use a lightweight
overlay.

Do not unnecessarily replace the existing search logic.

------------------------------------------------------------------------

# 15. Options

Options remains:

``` text
Y / Triangle position
```

It operates on the currently selected application.

Existing actions should remain functional.

The options menu should eventually share the same PSP-era visual
language:

-   Sharp/low-radius rectangular panel.
-   Accent-derived selection row.
-   Thin border.
-   Compact text.
-   Minimal shadow.
-   Clear destructive-action treatment where needed.

------------------------------------------------------------------------

# 16. Sort / Filter

Sort/filter functionality should remain available without dominating the
screen.

It may appear as a small header action.

The exact sort UI may continue using existing behavior initially.

Do not add a large permanent sort panel to the App Drawer.

------------------------------------------------------------------------

# 17. Touch Support

The redesign must preserve touch operation.

Touch users should still be able to:

-   Scroll the application grid.
-   Tap applications.
-   Open app-specific actions through the existing touch behavior.
-   Tap categories.
-   Activate Search.
-   Access Sort/Filter.
-   Use Back/Breadcrumb navigation.

Do not redesign controller behavior in a way that breaks
touch/controller reconciliation.

------------------------------------------------------------------------

# 18. Navigation and State

Preserve the existing App Drawer concepts wherever possible:

-   Active filter.
-   Visible applications.
-   Selected application/index.
-   Search query.
-   Grid scroll position.
-   Touch/controller mode.
-   App menu.
-   Uninstall confirmation.
-   Application launching.
-   Existing filtering.
-   Existing repository/data source.
-   Existing usage-access behavior.

This redesign is not authorization to rewrite working domain/state
logic.

Prefer presentation-layer changes unless architecture genuinely prevents
the required behavior.

------------------------------------------------------------------------

# 19. Motion

Motion should be restrained and handheld-like.

Appropriate:

-   Short focus transition.
-   Subtle border/glow fade.
-   Category indicator slide/fade.
-   Fast scroll-to-selection.
-   Search reveal.
-   Existing controller-hint fade behavior.

Avoid:

-   Large zoom animations.
-   Bounce.
-   Springy Material motion.
-   Elaborate page transitions.
-   Slow cinematic animations.

Input responsiveness takes priority over spectacle.

------------------------------------------------------------------------

# 20. Current PSP Storefront Implementation Must Be Preserved

The current App Drawer implementation already contains a PSP
Store-inspired layout with:

-   Storefront header.
-   Vertical category rail.
-   Category/content split.
-   PSP-style blue chrome.
-   Main content panel.
-   Controller command bar.

Do **not** simply destroy this work while implementing the new App
Drawer.

Before restructuring the screen, preserve the current storefront
implementation in a clean form suitable for future reuse.

------------------------------------------------------------------------

# 21. Future RSS Channels Feature

The preserved storefront layout is intended to become the visual
foundation for a future **RSS Channels** feature.

Conceptually:

``` text
┌─────────────────────────────────────────────────────────────┐
│ ‹ RSS Channels                              Search / View   │
├──────────────────┬──────────────────────────────────────────┤
│ ALL CHANNELS     │                                          │
│ Gaming           │                                          │
│ News             │        ARTICLES / FEED CONTENT           │
│ Releases         │                                          │
│ Community        │        Cards / Stories / Entries         │
│ Favorites        │                                          │
│                  │                                          │
├──────────────────┴──────────────────────────────────────────┤
│                 Contextual Controller Hints                 │
└─────────────────────────────────────────────────────────────┘
```

The vertical rail makes considerably more sense for:

-   RSS feed categories.
-   Content channels.
-   News sections.
-   Community feeds.
-   Subscriptions.
-   Favorites.

This allows PFP to maintain two related but distinct PSP-inspired visual
languages:

### Local Applications

``` text
App Drawer
→ PSP/XMB-era local media library
→ Horizontal categories
→ Artwork-first grid
```

### Network Content

``` text
RSS Channels
→ PSP Store-era content browser
→ Vertical category rail
→ Storefront/content layout
```

Do not implement the RSS feature as part of this task unless separately
requested.

The current goal is only to **preserve the reusable storefront UI** so
it is not lost.

------------------------------------------------------------------------

# 22. Suggested Refactor Boundary

Claude should inspect the existing code before choosing exact class/file
names.

Conceptually, separate reusable storefront presentation from App
Drawer-specific behavior.

Possible direction:

``` text
feature-appbar/
    AppDrawerScreen.kt
    AppDrawerViewModel.kt

    appdrawer/
        AppDrawerHeader.kt
        AppDrawerCategoryTabs.kt
        AppDrawerGrid.kt
        AppDrawerGridItem.kt
        AppDrawerSearch.kt
        AppDrawerOptions.kt

    storefront/
        StorefrontHeader.kt
        StorefrontCategoryRail.kt
        StorefrontContentHeader.kt
        StorefrontScaffold.kt
```

Later:

``` text
feature-rss/
    RssChannelsScreen.kt
```

could consume/adapt the preserved storefront components.

These names are illustrative.

**Do not perform a large architecture refactor solely to match this
folder example.**

Inspect the current project structure and make the smallest clean change
that preserves the storefront implementation.

------------------------------------------------------------------------

# 23. Implementation Priorities

Implement in this order:

1.  **Preserve the current PSP Store-inspired UI.**
2.  Restore/restructure the App Drawer around the grid-centric layout.
3.  Restore horizontal categories.
4.  Integrate active PFP accent colors.
5.  Restyle the application grid.
6.  Implement PSP-era focus/selection treatment.
7.  Integrate the existing contextual hint fade behavior.
8.  Restyle header/search/sort controls.
9.  Verify controller navigation.
10. Verify touch behavior.
11. Verify search/options/uninstall flows.
12. Test multiple accent colors.
13. Verify no layout movement occurs when hints appear/disappear.

------------------------------------------------------------------------

# 24. Non-Goals

Do not:

-   Implement RSS Channels yet.
-   Replace working App Drawer domain logic without reason.
-   Introduce PlayStation/Sony branding.
-   Copy proprietary PSP assets.
-   Hard-code the interface to blue.
-   Add Material You styling.
-   Convert categories back into large filter chips.
-   Build a permanent vertical category rail for the new App Drawer.
-   Permanently display the helper bar.
-   Break touch support.
-   Change controller mappings without a separate design decision.
-   Introduce large scaling/bounce animations.
-   Turn every application into a floating card.

------------------------------------------------------------------------

# 25. Acceptance Criteria

The redesign is complete when:

-   [ ] App Drawer retains an artwork-first grid layout.
-   [ ] Categories are horizontal.
-   [ ] L/R shoulder buttons switch categories.
-   [ ] X/Square-position action activates Search.
-   [ ] Y/Triangle-position action opens Options.
-   [ ] Confirm launches/selects the application.
-   [ ] Back returns normally.
-   [ ] Controller grid navigation remains reliable.
-   [ ] Touch browsing remains functional.
-   [ ] Existing filtering/search behavior remains functional.
-   [ ] Existing app actions remain functional.
-   [ ] Active PFP accent colors visibly influence the screen.
-   [ ] Multiple accent colors maintain good contrast.
-   [ ] Selected apps use a crisp PSP-era highlight rather than Material
    elevation.
-   [ ] Controller hints use PFP's existing fade-in/fade-out behavior.
-   [ ] Content does not jump when controller hints appear or disappear.
-   [ ] Current PSP Store-inspired UI has been preserved for future RSS
    reuse.
-   [ ] No PlayStation/Sony trademark assets or branding are introduced.
-   [ ] The resulting screen still feels like PFP, not a direct PSP
    clone.

------------------------------------------------------------------------

# 26. Instruction to Implementation Agent

Before modifying code:

1.  Inspect the current `AppDrawerScreen` implementation.
2.  Inspect `AppDrawerViewModel` and its UI state.
3.  Identify the current grid, search, filtering, options, and
    controller behavior.
4.  Identify the existing controller hint/helper implementation
    elsewhere in PFP and reuse its established behavior.
5.  Identify how PFP currently derives theme/accent colors.
6.  Determine the safest way to preserve the existing PSP Store-inspired
    presentation for later RSS reuse.

Do not assume APIs, theme roles, or helper components exist without
checking the repository.

Prefer reusing existing PFP infrastructure over creating parallel
systems.

The implementation should be incremental and should keep the project
compiling throughout the redesign.
