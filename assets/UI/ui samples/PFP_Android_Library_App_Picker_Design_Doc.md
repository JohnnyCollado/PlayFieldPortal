# Play Field Portal --- Android Library & App Picker Design Specification

## Purpose

This document defines two related changes to Play Field Portal (PFP):

1.  Restore the **Android Console Memory Card** as a visible entry in
    the main Library Manager.
2.  Redesign the **Android App Picker** as a controller-first grid
    picker that visually belongs to the same family as the App Drawer,
    while remaining simpler and purpose-built for multi-selection.

This work should preserve the existing Android library behavior and
installed-app data flow. The goal is to correct platform visibility and
redesign the presentation, not rewrite working domain logic.

------------------------------------------------------------------------

# 1. Platform Visibility Rules

## 1.1 Main Library Manager

The main Library Manager should treat Android as a special but valid
library platform.

### Required behavior

-   **Windows must NOT appear** as a normal console Memory Card in the
    Library Manager.
-   **Android MUST appear** as a console Memory Card in the Library
    Manager.
-   Android's Memory Card acts as the management entry point for Android
    applications.
-   Android does not use the normal ROM-folder scanning workflow.
-   Opening the Android Memory Card should expose the Android-specific
    app management flow.

The intended filtering rule is conceptually:

``` text
Library Manager
    Hide: Windows
    Show: Android
    Show: normal ROM-backed consoles
```

Do not reuse a broader "unsupported emulator platform" filter here.

------------------------------------------------------------------------

## 1.2 Custom Emulator Platform Picker

The Custom Emulator flow has different requirements from the Library
Manager.

Android and Windows should both be excluded from the platform picker
used to configure Custom Emulators.

Conceptually:

``` text
Custom Emulator Platform Picker
    Hide: Windows
    Hide: Android
    Show: supported emulator/ROM platforms
```

This rule must remain separate from the Library Manager filtering rule.

### Important

Do not create one shared exclusion rule that removes Android from both
screens.

The two screens answer different questions:

-   **Library Manager:** What library platforms can the user manage?
-   **Custom Emulator Picker:** What ROM/emulator platforms can be
    assigned to a custom emulator?

Android belongs to the first category but not the second.

------------------------------------------------------------------------

# 2. Android Memory Card

## Purpose

The Android Memory Card represents the user's Android application
library inside PFP.

Unlike a traditional console Memory Card, it is backed by installed
Android packages rather than ROM directories.

## Android Memory Card behavior

Opening the Android Memory Card should allow the user to:

-   View applications currently included in the Android
    Console/category.
-   Open **Add Apps** to launch the Android App Picker.
-   Remove an application from the Android library.
-   Return to the Library Manager normally.

It should NOT expose normal ROM-console configuration such as:

-   ROM root/folder selection.
-   File extensions.
-   Emulator selection.
-   ROM scanning.
-   Disc-set configuration.

The Memory Card is still important even though Android has a different
backing source. It provides the Library Manager entry point that
initializes and manages the installed-app picker workflow.

------------------------------------------------------------------------

# 3. Android App Picker Redesign

## Design Goal

Replace the current list-oriented installed-app picker presentation with
a **grid-based application picker**.

The picker should visually resemble a simplified version of the PFP App
Drawer.

It should feel like the user temporarily entered a selection mode for
the Android library rather than navigating into an unrelated Settings
screen.

The App Drawer remains the richer browsing experience. The App Picker is
intentionally more focused.

------------------------------------------------------------------------

# 4. High-Level Layout

Recommended structure:

``` text
┌────────────────────────────────────────────────────────────────────┐
│ ‹ Android Apps                                      8 Selected     │
│                                                                    │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│ │        ✓ │ │          │ │        ✓ │ │          │               │
│ │          │ │          │ │          │ │          │               │
│ │   ICON   │ │   ICON   │ │   ICON   │ │   ICON   │               │
│ │          │ │          │ │          │ │          │               │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
│  Dolphin      PPSSPP       RetroArch    Moonlight                 │
│                                                                    │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│ │          │ │        ✓ │ │          │ │          │               │
│ │   ICON   │ │   ICON   │ │   ICON   │ │   ICON   │               │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
│                                                                    │
├────────────────────────────────────────────────────────────────────┤
│ D-pad Navigate    Confirm Toggle    Search    Apply    Back         │
└────────────────────────────────────────────────────────────────────┘
```

Exact column count should respond appropriately to available screen
width and existing PFP layout conventions.

------------------------------------------------------------------------

# 5. Header

The picker should use a compact PFP/PSP-era header.

Recommended content:

``` text
‹ Android Apps                                      8 Selected
```

## Header requirements

-   Provide a clear Back/Breadcrumb affordance.
-   Clearly identify the screen as Android application selection.
-   Display the current number of selected Android applications.
-   Avoid oversized Material headers.
-   Keep the header visually related to the redesigned App Drawer.
-   Derive visual accents from the active PFP theme/accent system.

The selected count should update immediately when an application is
toggled.

------------------------------------------------------------------------

# 6. Application Grid

## Tile content

Each grid tile should contain:

-   Application icon.
-   Application display name.
-   Selection/check state.
-   Controller focus state.

Avoid unnecessary metadata.

Do not show:

-   Package name by default.
-   Version information.
-   Emulator tags.
-   Large descriptions.
-   Extra action buttons inside each tile.

The screen should remain visually lightweight.

## Grid density

The picker should be slightly denser and simpler than the App Drawer.

The App Drawer is for browsing and launching.

The App Picker is for quickly scanning installed apps and deciding which
ones belong in the Android library.

------------------------------------------------------------------------

# 7. Focus State vs Selection State

This distinction is critical.

There are two independent states for every tile:

### Focus

Focus answers:

> Which app is the controller currently pointing at?

Use the normal PFP PSP-era focus treatment:

-   Thin bright outer border.
-   Accent-colored edge or glow.
-   Slight label brightness increase.
-   Restrained transition.
-   No large scaling or bouncing.

### Selected

Selection answers:

> Is this app currently included in the Android Console/category?

Selected applications should display a persistent **check mark**.

Recommended treatment:

-   Check mark in the upper-right corner of the tile.
-   Small accent-backed or high-contrast check indicator.
-   Optional subtle selected tint/border that remains visible even when
    the cursor moves elsewhere.

The check mark must remain visible when the tile is not focused.

### Combined state

A focused and selected app should show both:

-   The controller focus treatment.
-   The persistent selected/check state.

Do not use the same visual treatment for both states.

------------------------------------------------------------------------

# 8. Existing Android Library Membership

When the App Picker opens, it must initialize its selected state from
the apps already included in the Android Console/category.

Example:

``` text
Android library currently contains:
    Dolphin
    RetroArch
    Moonlight

Picker opens:

    Dolphin      ✓
    PPSSPP
    RetroArch    ✓
    Moonlight    ✓
```

The user should immediately understand which applications are already
part of their library.

Do not initialize the picker as an empty selection if Android library
entries already exist.

------------------------------------------------------------------------

# 9. Selection Interaction

## Controller

Pressing the normal Confirm/Select action while focused on an
application should toggle that application's selected state.

Conceptually:

``` text
Not Selected + Confirm
    → Selected
    → Check mark appears

Selected + Confirm
    → Not Selected
    → Check mark disappears
```

Toggling an item should NOT close the picker.

The cursor should remain on the same application.

This allows rapid multi-selection.

## Touch

Touching an application tile should toggle its selection.

Do not require the user to precisely tap the check mark.

The entire tile is the touch target.

------------------------------------------------------------------------

# 10. Apply / Confirm Behavior

Selection changes should be treated as a picker session.

The user should have an explicit action for applying the selected set.

Recommended controller model:

``` text
Directional Input  → Navigate
Confirm / Select   → Toggle focused app
Search Action      → Search installed apps
Start / Apply      → Apply selection
Back               → Cancel / Return
```

Claude must inspect the existing PFP input architecture before assigning
physical buttons.

Use semantic `GamepadAction` / controller mappings and the shared
controller prompt system.

Do not hard-code PlayStation, Xbox, or Nintendo button labels.

If the current installed-app picker already has a working
confirmation/cancellation model, preserve its underlying behavior where
practical and adapt the new grid UI around it.

------------------------------------------------------------------------

# 11. Back / Unsaved Selection Behavior

The implementation should avoid accidental library modifications.

Preferred behavior:

-   Toggling apps updates the picker's temporary selection state.
-   **Apply** commits the final selected set.
-   **Back** exits without committing new changes.

If the current architecture already commits changes differently, Claude
should inspect that behavior before modifying it.

Do not silently introduce destructive behavior.

If changing from immediate persistence to staged persistence would
require a significant domain rewrite, preserve the existing persistence
model and document the difference rather than destabilizing the feature
solely for UI consistency.

------------------------------------------------------------------------

# 12. Search

The picker should support searching installed applications.

Search should be lightweight and visually related to the App Drawer
search experience.

Requirements:

-   Search filters the visible grid.
-   Existing selected state must survive filtering.
-   Clearing search restores the full installed-app grid.
-   Search must not deselect hidden applications.
-   Controller focus should recover safely when the result set changes.
-   Touch should be able to activate the search UI.

Example:

``` text
SEARCH
────────────────────────────────────────
> retro_
────────────────────────────────────────
```

Avoid large Material search surfaces unless required by existing
architecture.

------------------------------------------------------------------------

# 13. Controller Helper Footer

The redesigned App Picker must use the shared PFP **ControllerPrompt /
ControllerPromptBar** infrastructure.

Do not create another hard-coded helper implementation.

The footer should communicate the actions relevant to the picker, such
as:

``` text
Navigate     Toggle     Search     Apply     Back
```

Actual glyphs must resolve from the user's configured controller family
and mappings.

## Footer behavior

Use the established PFP helper behavior wherever applicable:

-   Controller-aware glyphs.
-   Existing sizing and spacing conventions.
-   Existing visibility/fade conventions if the surrounding
    Settings/picker flow uses contextual helper visibility.
-   No hardcoded PS/Xbox/Nintendo symbols.
-   Stable screen geometry.

If the helper fades in/out, the grid must not jump vertically when
visibility changes.

Reserve the footer region or otherwise maintain stable layout geometry.

------------------------------------------------------------------------

# 14. Accent Colors and Visual Language

The picker should belong to the same visual family as the App Drawer.

It should NOT be permanently blue.

Use the active PFP/XMB accent/theme colors for:

-   Background atmosphere.
-   Focus border.
-   Check indicator.
-   Header accents.
-   Dividers.
-   Search accents.
-   Footer accents.

The picker should remain simpler than the App Drawer.

Think:

``` text
App Drawer
    Full library browsing experience
    Categories
    Sorting/filtering
    Launching
    Context menus
    Richer chrome

Android App Picker
    Installed app grid
    Search
    Selection state
    Apply/Back
    Minimal chrome
```

The relationship should be immediately recognizable without making the
two screens identical.

------------------------------------------------------------------------

# 15. Motion

Keep motion restrained and responsive.

Appropriate:

-   Short focus-border transition.
-   Quick check-mark appearance/disappearance.
-   Search reveal.
-   Fast scroll-to-focused-item.
-   Existing helper-footer fade.

Avoid:

-   Large tile zoom.
-   Bounce.
-   Spring-heavy Material motion.
-   Floating-card animation.
-   Slow transitions.
-   Animated check marks that delay input.

Controller navigation responsiveness takes priority.

------------------------------------------------------------------------

# 16. Navigation Safety

The picker recently required protection against stale selection indexes
when the installed-app result list changes.

The redesign must preserve that safety.

When:

-   Search changes the visible list.
-   Installed apps refresh.
-   Apps are filtered.
-   The current selected/focused index becomes invalid.

The picker must clamp or recover focus safely.

Never call a lazy-grid scroll operation with an index outside the
current visible item range.

Empty results must also be handled safely.

Example empty search state:

``` text
No installed apps match "retroarchx"
```

The user must still be able to access Back and Search/Clear Search.

------------------------------------------------------------------------

# 17. Touch and Controller Reconciliation

Preserve PFP's controller/touch coexistence.

Requirements:

-   Touch scrolling works.
-   Touch selection works.
-   Controller navigation works after touch interaction.
-   Returning to controller input must restore a valid visible focus.
-   Focus must never point to an item outside the filtered grid.
-   Touch interaction should not leave a misleading controller cursor on
    an unrelated tile.

Reuse the project's established input-mode behavior where possible.

------------------------------------------------------------------------

# 18. Reusability

The grid picker should be implemented cleanly enough that it can
eventually support other application-selection flows.

However, do not over-engineer a generic framework during this change.

Good component boundaries might eventually resemble:

``` text
InstalledAppPicker
    PickerHeader
    AppSelectionGrid
    AppSelectionTile
    PickerSearch
    PickerFooter
```

These names are illustrative only.

Claude should inspect the existing project structure and choose the
smallest clean refactor.

------------------------------------------------------------------------

# 19. Non-Goals

This work should NOT:

-   Rewrite the Android application repository without need.
-   Change how installed Android packages are discovered unless required
    for correctness.
-   Turn Android into a ROM-scanned platform.
-   Add Android to the Custom Emulator platform picker.
-   Add Windows to the Library Manager.
-   Redesign the entire Library Manager.
-   Duplicate the App Drawer implementation wholesale.
-   Introduce PlayStation or Sony trademarks/assets.
-   Hard-code controller-family glyphs.
-   Introduce large Material cards.
-   Remove existing bounds/crash protections.
-   Break touch support.

------------------------------------------------------------------------

# 20. Implementation Order

1.  Inspect the current Library Manager platform filtering.
2.  Inspect the Custom Emulator platform filtering.
3.  Separate the two filtering rules if they currently share exclusions.
4.  Restore Android Memory Card visibility in the main Library Manager.
5.  Verify Windows remains hidden there.
6.  Verify both Windows and Android remain unavailable in the Custom
    Emulator platform picker.
7.  Verify opening Android Memory Card still reaches the installed-app
    management flow.
8.  Inspect the current `InstalledAppPicker` state and persistence
    model.
9.  Replace the list presentation with a responsive app grid.
10. Initialize check states from current Android library membership.
11. Implement distinct focus and selected visuals.
12. Preserve/tighten bounds-safe controller navigation.
13. Add lightweight search.
14. Integrate the shared controller helper footer.
15. Verify touch behavior.
16. Verify Apply/Back behavior.
17. Test with multiple accent colors.
18. Test empty, filtered, large, and changing installed-app lists.

------------------------------------------------------------------------

# 21. Acceptance Criteria

The change is complete when all of the following are true:

### Library Manager

-   Android appears as a Memory Card in the main Library Manager.
-   Windows does not appear there.
-   Android Memory Card opens its Android-specific management screen.
-   Android does not expose ROM folder/emulator configuration.

### Custom Emulator

-   Android is not available in the Custom Emulator platform picker.
-   Windows is not available in the Custom Emulator platform picker.
-   Normal emulator-backed platforms remain available.

### App Picker

-   Installed Android applications appear in a grid.
-   Grid visually resembles a simplified App Drawer.
-   Each tile displays the app icon and name.
-   Current Android library members are checked when the picker opens.
-   Focus and selected states are visually distinct.
-   Confirm toggles the focused app without closing the picker.
-   Touching a tile toggles it.
-   Selected count updates correctly.
-   Search filters the grid without losing selections.
-   Controller navigation remains bounds-safe.
-   Empty search/results cannot crash the picker.
-   Helper footer uses the shared controller-aware prompt system.
-   Helper/footer visibility does not cause the grid to jump.
-   Active PFP accent colors influence the screen.
-   Apply/confirmation behavior is explicit and safe.
-   Back behavior does not accidentally corrupt the Android library.
-   Existing installed-app discovery remains functional.

------------------------------------------------------------------------

# 22. Instructions to Claude Before Editing

Before changing code:

1.  Inspect the current `LibraryManagerScreen` and
    `LibraryManagerViewModel`.
2.  Identify exactly where Windows and Android are filtered for the main
    Library Manager.
3.  Inspect the Custom Emulator platform picker and identify its
    platform exclusion logic separately.
4.  Confirm how the Android Memory Card is created/seeded and how its
    detail screen launches the installed-app picker.
5.  Inspect `InstalledAppPicker` and its ViewModel/state owner.
6.  Determine whether selections currently persist immediately or are
    staged until confirmation.
7.  Inspect the current controller mappings and `ControllerPromptBar`.
8.  Inspect how the App Drawer derives its accent colors and grid focus
    treatment.
9.  Reuse existing PFP navigation and touch/controller reconciliation
    infrastructure.
10. Preserve the recent stale-index and empty-list crash protections.

Do not assume an API, helper, theme role, or repository behavior exists
without checking the current branch.

Make the smallest clean change that satisfies this specification and
keep the project compiling incrementally.
