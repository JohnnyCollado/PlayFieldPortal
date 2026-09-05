# Permanent helper footer — App Picker & App Drawer

Small, self-contained UI change. Both screens currently draw their controller helper bar as a
**floating overlay** aligned to `Alignment.BottomCenter` of the root `Box`, so the grid scrolls
underneath it. This plan turns that overlay into a **permanent footer slot** — a real row at the
bottom of the `Column`, below the grid — while keeping the bar horizontally centered exactly as it
looks today.

Visibility rules differ per screen, on purpose:

| Screen | Slot | Bar visibility |
|---|---|---|
| App Picker | permanent | always visible (never fades) |
| App Drawer | permanent | fades **in and out** on the idle-controller gate |

The slot is permanent on both. That is the whole point: grid geometry stops depending on whether
the bar is showing, so the last row is never covered and nothing shifts when the drawer's hint
comes and goes.

---

## 1. App Picker — `AppPickerScreen.kt`

Today (`AppPickerScreen`, the `AnimatedVisibility(visible = true, …)` block after the `Column`):

- the footer is a sibling of the `Column`, aligned `BottomCenter`;
- it is wrapped in an `AnimatedVisibility` whose `visible` is the constant `true`, so the animation
  only ever runs once, on first composition — it buys nothing;
- the grid's bottom rows scroll under it.

Change:

1. **Delete the `AnimatedVisibility` wrapper.** `visible = true` plus `ExitTransition.None` is a
   no-op; the bar is unconditional chrome.
2. **Move `AppPickerFooter` inside the `Column`**, as the last child, after the grid `Box(weight(1f))`.
   Keep `Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)` on the bar so it stays centered;
   give the footer `Modifier.fillMaxWidth()` so the centering has the full width to work with.
3. **Add a divider above it**, mirroring the 1.dp `sf.chromeDivider` line already used under the
   header, so the footer reads as chrome rather than as a floating row of text.
4. Replace the old `padding(bottom = 14.dp)` with symmetric footer padding
   (`padding(vertical = 12.dp)`), and drop the grid's now-redundant bottom breathing room if the
   spacing looks doubled — `contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)`
   stays fine as-is in the first pass.

Unchanged: the `confirmingRemovals` branch inside `AppPickerFooter` (the prompts still swap to the
modal's controls), and the fact that `RemovalConfirmPanel`'s full-screen scrim draws over the footer
— the footer already sat below the panel in z-order, so that behavior is identical.

Imports to drop if nothing else uses them: `AnimatedVisibility`, `ExitTransition`, `fadeIn` is still
needed (the search field uses it), `tween` is still needed (tile animations).

## 2. App Drawer — `AppDrawerScreen.kt` (+ `AppDrawerHintBar.kt` doc comment)

Today (`AppDrawerContent`, the `AnimatedVisibility` after the `Column`):

- same overlay convention, gated by
  `showControllerHint && state.menuApp == null && state.confirmUninstall == null`;
- `enter = fadeIn(tween(200))`, `exit = ExitTransition.None` — deliberate: it mirrors `XMBShell`,
  where the pill **cuts out instantly** on any input so the hint never lags behind the user's hand.
  This plan changes that on the drawer only; if the instant cut turns out to matter on device, the
  fallback is a very short fade-out (~90 ms) rather than reverting to a permanent overlay.

Change:

1. **Move `AppDrawerHintBar` inside the `Column`**, as the last child after the grid
   `Box(weight(1f))`, wrapped in a `fillMaxWidth()` `Box` with `contentAlignment = Alignment.Center`.
2. **Replace `AnimatedVisibility` with an alpha fade**, so the slot's height is reserved whether or
   not the pill is showing:

   ```kotlin
   val hintAlpha by animateFloatAsState(
       targetValue = if (showControllerHint && state.menuApp == null && state.confirmUninstall == null) 1f else 0f,
       animationSpec = tween(200),
       label = "appDrawerHint",
   )
   ```

   then `AppDrawerHintBar(modifier = Modifier.alpha(hintAlpha))`.

   `Modifier.alpha` rather than `AnimatedVisibility` is the key move: `AnimatedVisibility` removes the
   composable from layout when hidden, which is what makes the footer height collapse. Alpha keeps
   the bar measured at its natural height in both states, so the slot never changes size and we get a
   symmetric fade in **and** out for free. The pill has no clickables, so a fully transparent bar
   swallowing touches is not a concern.
3. **Add the same 1.dp `sf.chromeDivider` line above the footer** as the App Picker, so the two
   screens match.
4. Replace `padding(bottom = 20.dp)` with the footer's own `padding(vertical = 12.dp)`.
5. **Update the header comments** in `AppDrawerScreen.kt` (the "floats as an overlay" paragraph) and
   in `AppDrawerHintBar.kt` (the "Rendered as an overlay … not a row in the layout" paragraph) —
   both now state the opposite of what the code does.

## 3. Not in scope

- `storefront/StorefrontAppDrawer.kt` — the pre-redesign layout kept for the future RSS Channels
  feature. Leave it alone.
- `XMBShell`'s own idle hint. It genuinely floats over the wave (there is no column to sit at the
  bottom of) and its instant cut-out is the established XMB feel.

## 4. Verification

- Previews: `AppDrawerScreenPreview` and the six accent previews already pass
  `showControllerHint = true`; add nothing, just confirm the footer renders in the reserved slot and
  the accent sweep still reads.
- On device: scroll the App Picker grid to the last row and confirm no tile hides behind the footer;
  in the App Drawer, idle until the pill fades in, then press a button and watch it fade out with the
  grid **not** moving.
- No test changes expected — `AppPickerLogicTest` and `AppDrawerViewModelTest` cover logic, not layout.

## 5. Effort

S — two files touched plus two comment blocks; no state, ViewModel, or navigation changes.
