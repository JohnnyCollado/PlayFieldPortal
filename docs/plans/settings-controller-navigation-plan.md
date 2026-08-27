# Settings Controller Navigation Refactor Plan

## Goal

Simplify controller navigation for all current and future Settings screens while keeping navigation predictable across dynamic content, different screen sizes, and variable row heights.

The change is scoped to the Settings navigation system. It must not alter Game Picker behavior or unrelated XMB screens.

## Current problems

The existing Settings navigation is distributed across several mechanisms:

- `XMBViewModel` forwards controller actions through pending action state.
- `SettingsScaffold` receives actions through `CompositionLocal` values.
- Rows register `FocusRequester` instances and callbacks independently.
- Up/Down navigation searches registered rows by their on-screen Y coordinates.
- Select invokes a callback stored for whichever row currently has focus.
- Scroll visibility is calculated manually from screen coordinates.
- A bootstrap focus requester is used to seed initial focus.

This has solved focus escaping into the XMB, but it makes future screens dependent on implementation details such as focus requester registration, coordinate reporting, and callback ownership. It also makes headers and dynamic rows require special handling.

## Proposed architecture

Introduce a reusable controller-navigation model and controller state holder for vertical Settings screens.

Each navigation item should have an explicit stable identity and behavior:

```kotlin
data class ControllerNavItem(
    val key: String,
    val focusable: Boolean = true,
    val selectable: Boolean = true,
    val enabled: Boolean = true,
    val onSelect: (() -> Unit)? = null,
)
```

The model should distinguish these concepts:

| Item type | Focusable | Selectable | Example |
|---|---:|---:|---|
| Action row | Yes | Yes | Open Artwork Settings |
| Toggle row | Yes | Yes | Enable sound |
| Read-only value | Yes | No | Version information |
| Section header | No | No | Appearance |
| Disabled action | Policy-defined | No | Temporarily unavailable |

The key rule is that controller navigation is declarative: a screen describes its items, while the navigation controller owns movement and selection behavior.

## Implementation steps

### 1. Add the reusable navigation state/controller

Create a small Settings-focused controller abstraction responsible for:

- Current focused item key/index
- Ordered Up/Down movement
- Boundary clamping
- Initial focus
- Focus restoration
- Recovery when an item disappears
- Select dispatch
- Back dispatch
- Filtering non-focusable and disabled items

The controller should be independent of Compose UI where practical so its behavior can be unit tested without instrumentation.

### 2. Use stable keys and ordered traversal

Replace coordinate-based Up/Down traversal for ordinary vertical Settings screens with ordered item traversal.

The navigation sequence should be derived from the current list of items:

```kotlin
val navigableItems = items.filter { it.focusable && it.enabled }
```

Movement should clamp at the first and last item instead of escaping into the XMB.

Stable keys must be used when content changes so focus remains on the same logical item when possible.

### 3. Keep scrolling as a presentation concern

The scaffold should scroll the focused item into view after the controller changes the focused key.

Scrolling should no longer determine which item is selected. This separates:

- **Navigation:** which logical item is focused
- **Presentation:** where that item appears on screen

Screen coordinates may still be used by the UI only for visibility calculations.

### 4. Make Settings headers focusable but non-selectable

Update `SettingsGroup` so section headers:

- Remain visible inside the scrollable content
- Participate in the ordered controller focus sequence
- Display a visible focus state
- Do not respond to Select
- Do not interrupt Up/Down traversal

The required sequence must be deterministic: `header → first row → second row`. Headers are navigation landmarks, not actions.

### 5. Preserve existing Settings APIs

Keep the existing reusable composables and avoid rewriting every screen:

- `SettingsRow`
- `SettingsToggleRow`
- `SettingsValueRow`
- `SettingsFocusable`
- `SettingsTextFieldRow`
- `SettingsGroup`

Existing `onClick` callbacks should map to the new navigation item model. Rows with no action remain available for focus if that is needed for full-list navigation, but Select must be a no-op.

### 6. Support custom and dynamic rows

The design must work with:

- Custom `SettingsFocusable` content
- Text fields that enter edit mode only on Select/tap
- Loading and error rows
- Rows that appear or disappear
- Dynamic imported content
- Child-screen focus restoration
- Empty screens

When the focused item disappears, focus should move to the nearest surviving navigable item by list order or restore to the first available item if no predecessor exists.

### 7. Keep special navigation strategies separate

The reusable controller should target vertical Settings lists. It should not force all screens into one navigation strategy.

Future strategies can remain separate for:

- Grids
- Dialogs
- Media controls
- Text input
- Multi-column layouts
- Game Picker screens

The common contract should be reusable, while each layout can choose its own traversal policy.

## Testing plan

Add unit tests for the navigation controller/state holder covering:

- Up movement
- Down movement
- Boundary clamping
- Navigating onto focusable, non-selectable headers
- Ignoring non-selectable rows on Select
- Disabled item behavior
- Empty item lists
- Stable-key focus preservation after list updates
- Recovery after the focused item is removed
- Select dispatch to the focused action
- No stale callback invocation after an item changes

Add or update Compose tests where practical to verify:

- Settings headers are focusable but Select is a no-op
- Focused rows scroll into view
- Controller Select activates action rows
- Read-only rows do not activate
- Text fields do not open the keyboard merely by receiving focus

## Verification commands

Run the relevant Settings tests and compilation checks:

```bash
./gradlew :feature:feature-settings:testDebugUnitTest
./gradlew :feature:feature-settings:compileDebugKotlin
```

If shared controller or XMB types are changed, also compile the affected XMB module:

```bash
./gradlew :feature:feature-xmb:compileDebugKotlin
```

## Scope constraints

This refactor must not:

- Change Game Picker behavior
- Change controller button mappings
- Change unrelated XMB screens
- Rewrite all Settings screens unnecessarily
- Introduce a new dependency without verifying it is already used
- Commit or push changes as part of implementation

## Recommended behavior decision

Read-only Settings rows should remain focusable and scrollable so users can traverse the complete list. Section headers are the exception: they should be visible and included in controller focus, but excluded from selection.
