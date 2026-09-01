# Play Field Portal App Drawer Redesign Specification

## 1. Purpose

Redesign the Play Field Portal (PFP) App Drawer so that it evokes the
visual language and interaction feel of the classic PSP-era PlayStation
Network storefront while remaining an original PFP interface.

The goal is **nostalgic familiarity, not reproduction**. The App Drawer
should feel as though it belongs to the same era and school of handheld
UI design, without using PlayStation trademarks, logos, proprietary
artwork, branded terminology, or copied assets.

The redesign should preserve the App Drawer's existing functionality and
underlying application model wherever practical. The primary change is
the presentation layer and controller interaction mapping.

------------------------------------------------------------------------

## 2. Core Design Principles

### 2.1 PSP Storefront-Inspired, PFP-Owned

The interface should borrow broad design characteristics from the PSP
storefront:

-   Strong blue and cyan color palette.
-   Glossy blue gradients.
-   Thin bright borders and separators.
-   Rectangular panels rather than modern floating cards.
-   Compact handheld-oriented information density.
-   Clear separation between header, navigation/content, and footer.
-   Bright, unmistakable selection states.
-   White primary text with lighter blue/gray secondary text.
-   Minimal corner rounding.
-   Controller-first navigation.
-   A persistent bottom control legend.

The interface must **not** contain:

-   PlayStation logos.
-   PSP logos.
-   PlayStation Store logos.
-   Sony branding.
-   PlayStation-specific trademarked artwork.
-   Copied PSN Store assets.
-   PlayStation terminology where PFP terminology is appropriate.

PFP should reinterpret the design language rather than clone the
original storefront.

------------------------------------------------------------------------

## 3. High-Level Layout

The App Drawer should be divided into three primary horizontal regions:

1.  **Header / Breadcrumb Bar**
2.  **Navigation + Application Content**
3.  **Controller Command Bar**

Conceptual layout:

``` text
┌──────────────────────────────────────────────────────────────────┐
│ ‹ Android  ›  All Apps                         Search      View  │
├────────────────┬─────────────────────────────────────────────────┤
│                │                                                 │
│ ALL APPS       │  ALL APPS                             42 APPS   │
│ Games          │  ────────────────────────────────────────────   │
│ Emulators      │                                                 │
│ Recent         │   ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │
│ Favorites      │   │ APP  │ │ APP  │ │ APP  │ │ APP  │          │
│                │   └──────┘ └──────┘ └──────┘ └──────┘          │
│                │                                                 │
│                │   ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │
│                │   │ APP  │ │ APP  │ │ APP  │ │ APP  │          │
│                │   └──────┘ └──────┘ └──────┘ └──────┘          │
│                │                                                 │
├────────────────┴─────────────────────────────────────────────────┤
│ L Prev Category   R Next Category   B Back   A Launch            │
│                                      Y Options   X Search         │
└──────────────────────────────────────────────────────────────────┘
```

Exact dimensions should remain responsive to the device, but the visual
hierarchy should remain consistent.

------------------------------------------------------------------------

## 4. Header / Breadcrumb Bar

### 4.1 Purpose

The upper-left area used for storefront branding in the PSP-era
interface should instead contain PFP's existing back/breadcrumb
navigation.

This allows the screen to retain the visual balance of the reference UI
without introducing unnecessary branding.

### 4.2 Breadcrumb

Example:

``` text
‹ Android  ›  All Apps
```

The breadcrumb should communicate:

-   The parent location.
-   The currently selected App Drawer category.
-   A clear back affordance.

The existing App Drawer navigation behavior should remain intact.

### 4.3 Header Actions

The right side of the header should reserve space for:

-   **Search**
-   **View**

Example:

``` text
‹ Android  ›  All Apps                         Search     View
```

Search is existing functionality.

View is a planned presentation control and should be visually reserved
even if only the default view is implemented initially.

### 4.4 Visual Treatment

The header should:

-   Use a medium-to-deep PSP-era blue gradient.
-   Have a thin cyan/light-blue bottom border.
-   Use white text.
-   Remain compact.
-   Avoid oversized titles.
-   Avoid large modern Material-style pills or cards.
-   Use subtle glossy highlights rather than heavy shadows.

------------------------------------------------------------------------

## 5. Category Navigation Rail

### 5.1 Placement

Categories appear vertically along the left side of the App Drawer.

This replaces the current horizontal filter-chip presentation.

### 5.2 Categories

The rail should be generated from the App Drawer's actual available
filters rather than introducing arbitrary new categories.

Existing filter behavior should remain controlled by the App Drawer
state/ViewModel.

### 5.3 Interaction

The category rail is primarily a **visual category indicator**, not a
controller focus column.

Controller category switching is performed using the shoulder buttons:

-   **L / LB:** Previous Category
-   **R / RB:** Next Category

Pressing L or R should immediately change the active category without
requiring the user to move focus away from the application grid.

Touch interaction may still allow categories in the rail to be tapped
directly.

### 5.4 Selected Category

The active category should have a strong PSP-storefront-style selection
treatment:

-   Bright cyan/blue gradient.
-   Thin luminous border.
-   White bold text.
-   Optional subtle horizontal glow.
-   Slightly brighter right edge or directional accent toward the
    content pane.

Inactive categories should use darker blue panels with less visual
emphasis.

### 5.5 Category Counts

Where useful and already available from the data model, an application
count may appear right-aligned.

Example:

``` text
ALL APPS          42
GAMES             28
EMULATORS          9
```

Counts are supplementary and should not overpower the category label.

------------------------------------------------------------------------

## 6. Main Application Content Area

### 6.1 Content Header

The application pane should begin with a small section header.

Example:

``` text
ALL APPS ───────────────────────────────────────── 42 APPS
```

This reinforces the active category and provides context without
consuming significant vertical space.

### 6.2 Application Grid

The existing grid-based application browsing model should remain.

The current `LazyVerticalGrid` architecture can continue to provide:

-   Scrolling.
-   Controller selection.
-   Touch interaction.
-   Selection restoration.
-   Application launching.
-   Context/options actions.

The redesign should therefore avoid unnecessary replacement of existing
navigation infrastructure.

### 6.3 App Tiles

Tiles should move away from the current rounded modern-card appearance.

Preferred characteristics:

-   Square or nearly square application artwork area.
-   Approximately 0-3dp visual corner radius.
-   Thin blue border.
-   Dark blue background.
-   Application name beneath or within the lower portion of the tile.
-   Limited use of shadows.
-   Strong geometric alignment.
-   Consistent grid spacing.

The overall result should feel more like a handheld digital catalog than
a modern Android launcher.

------------------------------------------------------------------------

## 7. Application Selection State

Controller focus must be immediately recognizable.

A selected application should use:

-   Bright cyan/blue outer border.
-   Lighter blue inner border or highlight.
-   Brighter tile background.
-   Optional restrained glow.
-   White application label.

Conceptually:

``` text
Normal

  ┌──────────┐
  │          │
  │   ICON   │
  │          │
  └──────────┘
    Dolphin


Selected

╔══════════════╗
║ ┌──────────┐ ║
║ │          │ ║
║ │   ICON   │ ║
║ │          │ ║
║ └──────────┘ ║
║   Dolphin    ║
╚══════════════╝
```

Selection should be obvious without requiring large scaling animations.

------------------------------------------------------------------------

## 8. Color Specification

The palette should closely evoke the **PSP-era PSN Store blue palette**,
rather than the darker navy/neon aesthetic of later PlayStation
interfaces.

The following values are starting points and may be tuned during
implementation:

  Role                       Suggested Direction
  -------------------------- ------------------------
  Background Deep            `#003C8F` to `#0050A8`
  Background Mid             `#006BC4`
  Header Blue                `#0068BE`
  Panel Blue                 `#0879CA`
  Inactive Category          `#0874BE`
  Selected Category Top      `#19B9F1`
  Selected Category Bottom   `#008AD5`
  Selection Edge             `#7EE8FF`
  Divider                    `#68C9EB`
  Primary Text               `#FFFFFF`
  Secondary Text             `#D6EDF7`
  Muted Text                 `#A8D0E5`

### 8.1 Gradients

Gradients are important to the visual identity.

They should generally be:

-   Vertical.
-   Relatively subtle.
-   Brighter toward the top.
-   Darker toward the bottom.

The interface should **not** become a flat single-blue screen.

### 8.2 Theme Integration

The storefront palette should ideally be represented through PFP's theme
architecture rather than scattered hard-coded colors.

Possible semantic color roles:

``` text
storeChromeTop
storeChromeBottom
storePanel
storePanelAlternate
storeSelectionTop
storeSelectionBottom
storeSelectionEdge
storeDivider
storeTextPrimary
storeTextSecondary
```

This allows future PFP themes to reinterpret the storefront design while
preserving its structure.

------------------------------------------------------------------------

## 9. Controller Mapping

The App Drawer is controller-first.

### 9.1 Primary Controls

  -------------------------------------------------------------------------
  Physical Action   Xbox-Style        PlayStation-Style   Function
                                      Position            
  ----------------- ----------------- ------------------- -----------------
  Confirm / Bottom  A                 Cross               Launch / Select
  Face                                                    

  Back / Right Face B                 Circle              Back

  Left Face         X                 Square              Search

  Top Face          Y                 Triangle            Options

  Left Shoulder     LB                L1/L                Previous Category

  Right Shoulder    RB                R1/R                Next Category

  Directional Input D-pad / Stick     D-pad / Stick       Navigate App Grid
  -------------------------------------------------------------------------

### 9.2 Important Mapping Change

The current behavior where Y activates search should be changed.

New behavior:

``` text
Y / Triangle = Options
X / Square   = Search
```

Options applies to the currently selected application.

Search applies to the App Drawer globally.

### 9.3 Category Navigation

L/R category navigation should work regardless of the currently selected
app in the grid.

Changing categories should not require moving focus into the category
rail.

------------------------------------------------------------------------

## 10. Bottom Controller Command Bar

A persistent footer should communicate the available controls.

Example:

``` text
L  Prev Category   R  Next Category   B  Back   A  Launch
                                      Y  Options   X  Search
```

The exact layout may adapt to screen width.

### 10.1 Controller Glyphs

PFP should prefer controller-neutral or dynamically resolved glyphs.

For example:

**Xbox-style controller**

``` text
A Launch   B Back   X Search   Y Options
```

**PlayStation-style controller**

The corresponding physical face-button symbols may be displayed when PFP
can reliably identify the controller layout.

The underlying actions remain tied to consistent physical button
positions.

The interface should not use PlayStation symbols as permanent branding
or decoration.

------------------------------------------------------------------------

## 11. Search

Search remains a first-class App Drawer function.

### Activation

``` text
X / Square
```

Touch users may activate Search from the header.

### Behavior

Activating Search should:

1.  Enter search mode.
2.  Focus the search input.
3.  Show the software keyboard when appropriate.
4.  Filter visible applications using the existing search behavior.

The visual search field should fit into the storefront chrome rather
than appearing as a large modern rounded input.

Prefer:

-   Rectangular field.
-   Thin light-blue border.
-   Dark or medium-blue fill.
-   White input text.
-   Minimal corner radius.

------------------------------------------------------------------------

## 12. Options

Options is activated using:

``` text
Y / Triangle
```

It operates on the currently selected application.

Existing application actions such as app-specific menu functionality
should be preserved.

The options menu itself should eventually receive the same storefront
visual treatment:

-   Rectangular blue panel.
-   Minimal rounding.
-   Bright selection bar.
-   White text.
-   Clear destructive-action treatment where necessary.

Functionality should remain unchanged unless separately specified.

------------------------------------------------------------------------

## 13. View

The header reserves a **View** control.

The purpose of View is to allow alternate application presentation modes
in the future.

Potential examples include:

-   Standard icon grid.
-   Compact grid.
-   List/detail presentation.

These alternate modes are **not required as part of the initial visual
redesign** unless separately specified.

The redesign should simply avoid structuring the screen in a way that
prevents View from being added cleanly.

------------------------------------------------------------------------

## 14. Touch Behavior

The redesign must not sacrifice PFP's existing touch support.

Touch users should be able to:

-   Scroll the app grid.
-   Tap applications.
-   Long-press or otherwise access application options according to
    existing behavior.
-   Tap categories.
-   Tap Search.
-   Tap View when implemented.
-   Use the breadcrumb/back control.

Existing controller/touch selection reconciliation should be preserved
where possible.

The visual redesign should not require rewriting functional touch
behavior without a concrete reason.

------------------------------------------------------------------------

## 15. Navigation State

Existing App Drawer navigation concepts should remain intact:

-   Selected application index.
-   Active filter/category.
-   Search query.
-   Touch/controller mode.
-   Application context menu.
-   Uninstall confirmation.
-   Grid scrolling.

The redesign is intentionally focused on **presentation and input
mapping**, not replacing the App Drawer state model.

------------------------------------------------------------------------

## 16. Visual Characteristics to Avoid

The redesigned drawer should avoid drifting into modern Android/Material
styling.

Avoid:

-   Large rounded cards.
-   Pill-shaped filter chips.
-   Excessive 12-16dp corner radii.
-   Floating surfaces everywhere.
-   Large empty margins.
-   Oversized typography.
-   Heavy blur/glass effects.
-   Material You appearance.
-   Flat monochromatic backgrounds.
-   Excessive animation.
-   Giant app icons with minimal surrounding information.

The desired interface is compact, geometric, bright, and deliberately
"handheld."

------------------------------------------------------------------------

## 17. Motion

Animations should be restrained.

Good candidates:

-   Short category transition.
-   Subtle selection glow transition.
-   Fast grid scroll-to-selection.
-   Search field reveal.
-   Small menu transitions.

Avoid large zooms, bouncing cards, or elaborate page transitions.

The UI should feel responsive and immediate.

------------------------------------------------------------------------

## 18. Implementation Direction

The existing App Drawer architecture should be reused wherever possible.

### Preserve

-   `AppDrawerViewModel`
-   Existing application repository/data source.
-   Existing filtering.
-   Existing search logic.
-   Existing grid scrolling logic.
-   Existing touch/controller reconciliation.
-   Existing app launching.
-   Existing app options/actions.
-   Existing uninstall flow.

### Restyle / Restructure

-   `DrawerHeader`
-   `FilterTabRow`
-   `AppGridItem`
-   `AppMiniMenu`
-   App Drawer background.
-   Overall screen composition.
-   Controller legend/footer.

### Replace Visually

`FilterTabRow` should become the vertical category rail.

The underlying `AppFilter` model does not need to be replaced simply to
support the new presentation.

------------------------------------------------------------------------

## 19. Reference Layout Target

The final result should communicate this hierarchy immediately:

``` text
HEADER
Breadcrumb / Back                          Search | View

CATEGORY RAIL            CONTENT
Selected category        Active category title
Other categories         App count
                         Application grid
                         Strong selected-app cursor

FOOTER
L/R Categories | Back | Launch | Options | Search
```

A user familiar with the PSP-era storefront should recognize the
*feeling* immediately, while every piece of the interface should still
clearly belong to Play Field Portal.

------------------------------------------------------------------------

## 20. Design Summary

The redesigned App Drawer is:

**A PFP application browser presented through the visual grammar of an
early handheld digital storefront.**

It is not a recreation of the PlayStation Store.

The defining characteristics are:

-   PSP-era blue/cyan gradients.
-   Breadcrumb in place of storefront branding.
-   Vertical category rail.
-   Shoulder-button category navigation.
-   Dense rectangular app grid.
-   Bright cyan controller focus.
-   Search on X/Square.
-   Options on Y/Triangle.
-   Launch on A/Cross.
-   Back on B/Circle.
-   Persistent controller command footer.
-   Minimal rounding.
-   Existing App Drawer functionality preserved underneath the new
    presentation.
