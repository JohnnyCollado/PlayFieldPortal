# Settings Controller Navigation Issue

## Status

**Open — persistent after multiple attempted fixes.**

## User-visible symptom

On long Settings screens, repeatedly pressing **Up** can scroll the content away from the expected section boundary. The section header, such as **Installed**, is not visible when the list reaches the top-most relevant position.

After making headers focusable, a second symptom appeared: when focus is on the top-most header and the user presses **Down**, the first row can be skipped. The expected sequence is:

```text
Installed header → first row → second row
```

The actual behavior can appear as:

```text
Installed header → second row
```

or the header/list position can become visually inconsistent with the focused item.

## What the behavior must be

Settings headers are part of the controller navigation sequence, but are not actions:

- Headers must be reachable with Up/Down.
- Headers must display a visible focus state.
- Pressing Select/A on a header must do nothing.
- Pressing Down from a header must focus the first row immediately below it.
- Pressing Up from the first row must return focus to its preceding header.
- Repeated Up at the first header must remain inside the Settings overlay and preserve the top content/header visibility.
- Controller focus must never escape into the XMB behind the Settings screen.

## Current implementation context

`SettingsScaffold` currently combines:

- Pending `GamepadAction` values forwarded by `XMBViewModel`.
- `CompositionLocal` callbacks for focused-row selection.
- `FocusRequester` instances registered by rows and headers.
- A mutable navigation-order list.
- Screen-coordinate tracking for visibility and focus recovery.
- A manually controlled `verticalScroll` state.
- A bootstrap focus requester for initial focus.

This is currently a hybrid system: logical navigation is being moved toward ordered requesters, while focus restoration and scrolling still use measured screen coordinates.

## Why previous fixes did not fully solve it

### Headers were initially excluded

The original behavior treated headers as presentation-only and skipped them in controller traversal. That prevented users from navigating onto headers and did not satisfy the required interaction model.

### Headers were then made focusable independently

Headers were added as focusable elements and registered alongside rows. However, their registration lifecycle differs from normal Settings rows, and the focus system still relies on Compose focus callbacks plus mutable requester collections.

### Coordinate-based traversal was only partially replaced

The navigation code was changed to use an ordered requester list for Up/Down, but scroll visibility and focus state still depend on independently measured positions. This means logical focus order and visual content order can diverge during recomposition or scrolling.

### Composition order may not equal stable content order

`DisposableEffect` registration and removal happen as composables enter and leave composition. For dynamic Settings content, the requester list can be temporarily incomplete, reordered, or contain stale entries while Compose is recomposing. A header-to-row transition can therefore resolve against an incorrect or incomplete order.

### Focus movement can race scroll/recomposition

A controller action can trigger focus movement, which triggers scrolling, which causes layout callbacks and focus state updates. If another action arrives before those updates settle, the navigation code may use stale `focusedRow` or an incomplete requester registry.

## Required investigation before another fix

The next implementation should not guess at offsets or add another Y-coordinate exception. It should instrument and verify:

1. The exact ordered sequence of registered navigation requesters.
2. The currently focused requester at every action.
3. The requester selected for each Up/Down action.
4. The focused composable’s label/key.
5. The scroll offset before and after the action.
6. The visible bounds of the header and adjacent rows.
7. Registration and removal events during recomposition.

Logs should make the following transition observable:

```text
focused=Installed
DOWN target=FirstRow
focused=FirstRow
```

If the log says the target is `FirstRow` but the UI highlights `SecondRow`, the issue is Compose focus ownership or a stale requester. If the target itself is `SecondRow`, the issue is registry ordering. If focus is correct but the header is not visible, the issue is scroll/layout policy.

## Architectural direction

The robust solution should use one authoritative navigation model per Settings screen:

- Stable logical keys define content order.
- Headers are normal navigation items with `selectable = false`.
- Rows and headers do not independently mutate an ordering list based on lifecycle timing.
- Up/Down changes the selected key/index in the model.
- Compose focus is synchronized to that selected key.
- Scrolling brings the selected item into view after selection changes.
- Select dispatches only if the selected item is selectable.

For vertical Settings screens, content order must be derived from the screen’s declared item structure, not reconstructed from transient screen coordinates or unordered focus callbacks.

## Regression scenarios

At minimum, verify these sequences on a screen containing multiple sections:

1. Open the screen: first header or first row is visible and focused according to the defined initial-focus policy.
2. Press Down from the first header: first row is focused, not the second row.
3. Press Up from the first row: first header is focused and visible.
4. Press Up repeatedly from the first header: focus remains on the first header and the header remains visible.
5. Move through a section boundary: final row → next header → next row.
6. Press Select on every header: no action or navigation occurs.
7. Remove a focused dynamic row: focus recovers to a nearby valid item without escaping the overlay.
8. Press Down repeatedly to the final item: focus clamps at the last valid item.

## Scope

This issue concerns Settings screens only. Do not change Game Picker behavior while resolving it.
