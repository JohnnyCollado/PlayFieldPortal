# Play Field Portal — Virtual Keyboard: Implementation Plan

A PFP-drawn on-screen keyboard for typing with a controller. Touch keeps the system keyboard, and a
new setting turns the PFP keyboard off entirely.

**Status: T1–T3, T5, T6 implemented (tests green, 2026-10-01). T1–T9 done and device-verified; T10 (phase 2, batched at the user's choice) and T11 (phase 3) implemented with tests green, awaiting one device pass (2026-10-01).**
The look is approved (mockup below). Work the tasks in §7 in order, writing each task's tests (§6) first.

**Mockup (approved 2026-09-30):** https://claude.ai/artifact/MYh5ZB3wfGdVbh6qYSWiZy — five
1920×1080 artboards: settings field / symbols layer / game search / touch (system keyboard) /
Settings ▸ Controller toggle. Private to the owner's account; read it with the Artifact tool. §4
repeats every number from it, so the build does not depend on opening it.

> **Working rules for the implementing session**
> - Tests first: create each task's test file against the not-yet-existing API, then implement.
> - Do not run Gradle. Hand the user the commands in §9, one per block.
> - Controller input goes through the navigation core (`GamepadAction` → `XMBViewModel` →
>   handler). No `onKeyEvent` / raw keycodes in the keyboard.
> - The approved look is §4. Anything visual that §4 does not cover is listed in §10 and needs the
>   user's yes before it is drawn.
> - The working tree on `final-polish` already holds unrelated uncommitted scanner/artwork changes.
>   Leave them alone and do not commit.

---

## 1. Behaviour

| Edit started by | Setting ON (default) | Setting OFF |
| --- | --- | --- |
| Controller (SELECT on a field, or a field opened by a controller action) | **PFP keyboard** | system keyboard |
| Touch (tap on a field, or a field opened by a tap) | system keyboard | system keyboard |

- The setting is **Settings ▸ Interface ▸ Controller ▸ Keyboard ▸ Virtual Keyboard**, default on.
- Touch and setting-off paths must be byte-for-byte today's behaviour. That is the regression bar.
- While the PFP keyboard is open it owns the whole pad:

| Action | Does | Hint label |
| --- | --- | --- |
| `NAVIGATE_*` | move the key cursor | — |
| `SELECT` | press the focused key | Type |
| `CHANGE_SORT` | backspace | Delete |
| `OPEN_CONTEXT_MENU` | space | Space |
| `PREV_CATEGORY` / `NEXT_CATEGORY` | caret left / right | Cursor |
| `BACK` | close the keyboard (host decides keep vs cancel) | Close |
| `HOME` | nothing | — |

- A tap on the field while the PFP keyboard is open hands over to the system keyboard. The system
  keyboard never hands back by itself.

## 2. What exists today

- **No in-app keyboard.** Every field uses the system IME. 26 direct `TextField` sites; the two in
  `studio/` are the desktop Theme Studio and are out of scope.
- **Input pipeline.** `MainActivity.dispatchKeyEvent` → `GamepadInputHandler.onKeyEvent` →
  `GamepadAction` → `XMBViewModel.dispatchGamepadAction`
  ([XMBViewModel.kt#L5593](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L5593)),
  which routes by overlay tier. Pickers at the top "capture ALL input"; that is the tier the
  keyboard joins.
- **Settings forwarding** (`forwardsToSettings` in `XMBViewModel.kt`) now includes L1/R1 — fixed
  2026-10-01 for Initial Setup's RB Skip. Capturing above that branch still applies.
- **Input source** has one owner: `XMBViewModel.markTouchInput()` / `markControllerInput()` →
  `XMBUiState.lastInputWasTouch` (#L7852). Settings receives it as `LocalSettingsLastInputWasTouch`.
- **Confirm-to-edit field.** `SettingsTextFieldRow`
  ([SettingsScaffold.kt#L1328](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/SettingsScaffold.kt#L1328)):
  `readOnly = !editing`; controller SELECT (row registration `onSelect`) and the tap layer both call
  `beginEditing`. These two call paths are where controller and touch split.
- **Open-and-type field.** `GameSearchField`
  ([GameSearchField.kt](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/GameSearchField.kt))
  focuses and shows the IME on mount. While it is up `dispatchGamepadAction` gives BACK → cancel
  (restore the opening query) and SELECT → confirm (#L5835).
- **Setting template.** `leftBacksOut`: `ControllerLayoutPrefs` → `ControllerLayoutRepository` key
  `controller_left_backs_out` → `ControllerSettingsViewModel.setLeftBacksOut` → `SettingsToggleRow`
  in the Navigation group, mirrored into `XMBUiState` at #L5521. Test:
  `ControllerLeftBacksOutTest`.
- **Nav core.** `core/core-navigation` is pure JVM: `NavigationEngine`, `NavigationDirection`,
  `gridMove` (uniform grids only, no spans). `core-ui` depends on `core-domain`, not on
  `core-navigation`; `core-navigation` has no project dependencies, so adding that edge is safe.
- **Prompts and cursor.** `ControllerHintBar` / `ControllerPromptItem`; `Modifier.menuCursor`
  (`MenuCursor.kt`); sounds via `LocalMenuSounds` / `MenuSound`.
- **Compose BOM** `2026.08.00`. `windowSoftInputMode="stateAlwaysHidden"`.

## 3. Design

### 3.1 Pieces

| Piece | Module | Kind |
| --- | --- | --- |
| `spanGridMove` | `core-navigation` | pure function beside `gridMove` |
| `VirtualKeyboardLayout`, `TextEditBuffer`, `VirtualKeyboardState` + reducer | `core-ui` (`keyboard/`) | pure Kotlin |
| `resolveTextInputMode` | `core-ui` (`keyboard/`) | pure function |
| `VirtualKeyboardController` | `core-ui` (`keyboard/`) | `@Singleton`, holds `StateFlow<VirtualKeyboardSession?>` |
| `VirtualKeyboardPanel`, `VirtualKeyboardOverlay`, `LocalVirtualKeyboard` | `core-ui` (`keyboard/`) | Compose |
| Setting | `core-domain`, `core-data`, `feature-settings` | pref + toggle row |
| Capture tier + overlay mount | `feature-xmb` | `XMBViewModel`, `XMBShell` |

### 3.2 Flow

1. A field asks `LocalVirtualKeyboard.current.open(request)` with its text, callbacks
   (`onTextChange`, `onDone`, `onClose`), `isPassword` and a `placement`.
2. The controller publishes a session. `XMBViewModel.dispatchGamepadAction` checks it **first**,
   ahead of the picker tiers, and, when open, sends the action to the controller and
   returns. `hasBlockingOverlay` includes it.
3. The reducer turns the action into a new state plus effects (text change, done, close, sound).
4. The panel renders from the session. `placement` decides who draws it:
   - `SettingsFooter` — `SettingsScaffold` draws it inside its footer band and swaps the helper
     prompts. The content `Box` is `weight(1f)`, so the viewport and keep-in-view shrink on their own.
   - `BelowField` — the global overlay draws it under the game search field.
   - `BottomCenter` — the global overlay's default for every other screen (§10 Q2).
5. The global overlay is mounted in `XMBShell` inside the unscaled-density scope (#L961), above the
   settings layer and the dialogs.

### 3.3 Decisions already made (push back if wrong)

- **One session at a time**, owned by a singleton, so every feature module reaches it through one
  CompositionLocal and the ViewModel sees it without per-screen plumbing.
- **Source is decided at the call path where possible** (`onSelect` vs tap layer), and by
  `lastInputWasTouch` only for fields opened by an action (game search). The ViewModel mirrors its
  input source into the controller.
- **Key cursor maths lives in `core-navigation`**; the keyboard is a modal capture tier, not a
  `NavigationEngine` context, because the secondary buttons are not `NavigationCommand`s.
- **The field keeps real focus and a real caret.** The session edits a `TextFieldValue`; the system
  IME is suppressed for that field while the session is open (§7 T4 proves how).
- **`BACK` means Close.** Settings: end editing, keep text. Game search: existing cancel (restore the
  opening query). **Done key**: settings end editing; game search `onGameSearchConfirmed`.
- **Initial key focus** is `q`; the caret starts at the end of the existing text.
- **Sounds:** move `SCROLL`, key press `SELECT`, layer switch `SYSTEM_BROWSE`, Done `CONFIRM`,
  Close `BACK`, boundary or no-op silent.

## 4. Approved look (from the mockup; 1 dp = 2.306 px on the Thor / Odin 3)

- **Panel:** 383 × 188 dp, padding 8 dp, corner 10 dp, fill black 86%.
- **Grid:** 10 columns, 5 rows, key height 32 dp, gap 3 dp, key corner 6 dp.
- **Keys:** fill white 10% (Shift, layer, Backspace, Done: white 5%); label 15 sp white; layer and
  Done labels 12 sp SemiBold. Shift, Space and Backspace are drawn glyphs, no text.
- **Focused key:** `Modifier.menuCursor(selected = true, shape = RoundedCornerShape(6.dp))`.
- **Letters layer**

  | Row | Keys |
  | --- | --- |
  | 1 | `1 2 3 4 5 6 7 8 9 0` |
  | 2 | `q w e r t y u i o p` |
  | 3 | `a s d f g h j k l -` |
  | 4 | `z x c v b n m _ . @` |
  | 5 | Shift (1) · `?123` (2) · Space (4) · Backspace (1) · Done (2) |

- **Symbols layer:** row 1 unchanged; `! @ # $ % ^ & * ( )`; `- _ = + [ ] { } \ |`;
  `; : ' " , . < > / ?`; row 5 the same with `ABC` for the layer key and Shift dimmed to 35%.
- **Hint pill:** stock `ControllerHintBar`, items in this order — `SELECT` Type, `CHANGE_SORT`
  Delete, `OPEN_CONTEXT_MENU` Space, `[PREV_CATEGORY, NEXT_CATEGORY]` Cursor, `BACK` Close.
- **Settings placement:** the footer band grows. Top to bottom: divider, 12 dp, panel (centred),
  8 dp, hint pill (background black 70%, as today), 12 dp. The edited row scrolls fully into the
  viewport above the divider.
- **Game search placement:** panel top = field bottom + 8 dp; panel right edge = the field's right
  edge (`XmbStatusStripSidePadding`). Hint pill bottom-end at the XMB hint's paddings, at 1× size
  (the field and keyboard sit outside the XMB's scaled density).
- **Toggle:** new group `Keyboard` between Navigation and Reset. Label `Virtual Keyboard`, sublabel
  `Type with the controller on PFP's own keyboard. Off, or when you use touch, the system keyboard opens instead`.

## 5. Field inventory

| Site | Host | Phase |
| --- | --- | --- |
| `SettingsTextFieldRow` (Achievements ×4, Artwork ×6, Library Manager ×1, `EditorTextField` in Emulator Profile Editor) | settings scaffold | 1 |
| `GameSearchField` | XMB overlay | 1 |
| `WizardTextField` (`WizardRows.kt`) | wizard scaffold, own footer slot | 2 |
| `PfpTextEntryModal` (`PfpModals.kt`, used by Collections) and `CollectionsSettingsScreen.kt:254` | in-tree modal, uses `imePadding()` | 2 |
| `ArtworkOrphanScreen.kt:108` | settings | 2 |
| `MusicBrowserScreen`, `AppPickerScreen`, `ShibaDetailParts.SearchRow`, `ShibaCoinsScreen`, `ArtworkStudioScreen` ×2, `MetadataPreviewPanel`, `GameDetailScreen` ×2, `AppDetailScreen`, `CollectionPickerPanel` | feature-xmb fullscreen | 2 |
| `StorefrontAppDrawer`, `AppDrawerHeader` | feature-appbar | 2 |
| `AlertDialog` fields: `LibraryManagerScreen.kt:238`, `:890`, `:891`; `XMBShell.kt` `AppRenameDialog`, `CollectionNameDialog` | **separate dialog window** | 3 |

`AlertDialog` is its own window, so an overlay in `XMBShell` draws underneath it. Phase 3 moves
those onto `PfpTextEntryModal` first; until then they stay on the system keyboard.

## 6. Test cases (write these before the code they cover)

### 6.1 `ControllerVirtualKeyboardPrefTest` — `core-data`, Robolectric, mirror `ControllerLeftBacksOutTest`
1. An absent key reads as on, with nothing written.
2. It round-trips off and back on (`controller_virtual_keyboard`).
3. Reset returns it to on by removing the key.
4. Setting it does not change `leftBacksOut`, and vice versa.

### 6.2 `SpanGridMoveTest` — `core-navigation`, pure JVM
1. LEFT/RIGHT step one cell in a row and return null at either end (no wrap).
2. UP/DOWN between uniform rows keep the column.
3. DOWN into the spanning row lands on the cell covering the column: 0 → Shift, 1–2 → layer,
   3–6 → Space, 7 → Backspace, 8–9 → Done.
4. UP from a spanning cell entered vertically returns to the column it was entered from
   (`b` → Space → UP gives `b`, not `v`).
5. UP from a spanning cell reached horizontally uses that cell's first column.
6. RIGHT along the spanning row visits Shift, layer, Space, Backspace, Done in order.
7. UP on the first row and DOWN on the last row return null.
8. Empty grid or an out-of-range position returns null.

### 6.3 `TextEditBufferTest` — `core-ui`, pure JVM
1. Insert at the end appends and advances the caret.
2. Insert mid-text inserts at the caret.
3. Backspace deletes the character before the caret; at 0 it changes nothing.
4. Backspace removes a whole surrogate pair.
5. Caret left/right clamp to `0..length` and never stop inside a surrogate pair.
6. With a max length, an insert past it changes nothing.
7. Replacing the text from outside clamps the caret to the new length.

### 6.4 `VirtualKeyboardLayoutTest` — `core-ui`, pure JVM
1. Each layer has 5 rows and every row's spans sum to 10.
2. The letters layer equals §4 exactly.
3. The symbols layer equals §4 exactly.
4. Row 5 is identical across layers except the layer key's label and Shift's enabled flag.
5. No character appears twice within a layer.

### 6.5 `VirtualKeyboardReducerTest` — `core-ui`, pure JVM
1. Opening: letters layer, shift off, focus on `q`, caret at the end of the initial text.
2. SELECT on a character key inserts it and emits one text change; focus does not move.
3. Shift then a letter inserts the uppercase letter and clears shift.
4. Shift does not change digits or punctuation.
5. The layer key toggles letters ↔ symbols, keeps focus on itself and clears shift.
6. SELECT on Shift in the symbols layer does nothing and emits nothing.
7. Focus row and column survive a layer switch.
8. Space key and `OPEN_CONTEXT_MENU` each insert one space; the shortcut does not move focus.
9. Backspace key and `CHANGE_SORT` each delete before the caret; on empty text nothing is emitted.
10. `PREV_CATEGORY` / `NEXT_CATEGORY` move the caret and clamp at the ends.
11. A character typed after moving the caret lands at the caret.
12. Done emits `Done`; `BACK` emits `Close`; `HOME` emits nothing.
13. Directions move focus per `spanGridMove`; a boundary press changes nothing.
14. Sound effects: move `SCROLL`, key `SELECT`, layer `SYSTEM_BROWSE`, Done `CONFIRM`, Close
    `BACK`, boundary and no-op none.
15. A host-side text replacement while open updates the text and clamps the caret.

### 6.6 `TextInputModeTest` — `core-ui`, pure JVM
1. Controller, setting on → `VIRTUAL`.
2. Controller, setting off → `SYSTEM`.
3. Touch, setting on → `SYSTEM`.
4. Touch, setting off → `SYSTEM`.

### 6.7 `VirtualKeyboardControllerTest` — `core-ui`, JVM
1. `open` publishes a session carrying the request's text, placement and password flag.
2. `onGamepadAction` returns false when closed and true when open.
3. Each text change calls `onTextChange` exactly once with the new text.
4. Done calls `onDone` and clears the session; Close calls `onClose` and clears it.
5. A second `open` closes the first session (its `onClose` runs) before publishing the new one.
6. Closing with a stale token (a field that already lost the session) does nothing.
7. `handOverToSystemKeyboard` clears the session without calling `onDone` or `onClose`.
8. Turning the setting off while open closes the session.

### 6.8 `VirtualKeyboardShellRoutingTest` — `feature-xmb`, in the style of `NotificationBlockingOverlayTest` / `GamesFilterTest`
1. `hasBlockingOverlay` is true while a session is open.
2. With a session open over a settings screen, no action reaches `pendingSettingsAction`.
3. `PREV_CATEGORY` / `NEXT_CATEGORY` reach the keyboard while a settings screen is active.
4. Game search opened by controller with the setting on opens a `BelowField` session.
5. In that session Done confirms the search, `BACK` cancels and restores `textOnOpen`, and SELECT
   types instead of confirming.
6. Game search opened by touch opens no session and keeps today's BACK / SELECT handling.
7. With the setting off, a controller-opened game search opens no session.
8. `markTouchInput` / `markControllerInput` are mirrored into the controller's input source.

### 6.9 `SettingsTextFieldRowKeyboardTest` — `feature-settings`, Robolectric Compose, in the style of `SettingsScaffoldNavigationTest`
1. SELECT on the focused field with the setting on opens a session and the system keyboard is not requested.
2. A tap on the field opens no session and makes the field editable.
3. SELECT with the setting off opens no session and takes today's path.
4. Characters typed on the keyboard reach `onValueChange`.
5. Done ends editing, the cursor is back on the row, and DOWN moves to the next row.
6. `BACK` with the keyboard open closes only the keyboard; a second `BACK` calls `onBack`.
7. The footer shows the five keyboard prompts while open and the screen's own prompts after.
8. The edited row's bounds end above the footer divider while the keyboard is open.
9. Removing the field from composition closes its session.
10. A password field keeps its mask while typed into from the keyboard.

### 6.10 `VirtualKeyboardPanelTest` — `core-ui`, Robolectric Compose
1. Every key exposes a test tag and Shift, Space, Backspace, Done expose content descriptions.
2. Exactly one key is marked selected and it matches the state's focus.
3. Letter labels are uppercase while shift is active.
4. The symbols layer shows `ABC` and a disabled Shift.
5. The prompt bar lists the five prompts in §4's order.

### 6.11 `ControllerSettingsKeyboardRowTest` — `feature-settings`, Robolectric Compose
1. A `Keyboard` group sits between Navigation and Reset with the label and sublabel from §4.
2. Toggling the row calls `setVirtualKeyboard` with the new value.

## 7. Tasks

Each task: write its tests, implement, hand the user the matching command from §9, report what ran.

**T1 — Setting.** Tests 6.1, 6.11. Add `virtualKeyboard: Boolean = true` to `ControllerLayoutPrefs`;
key, setter and reset in `ControllerLayoutRepository`; `setVirtualKeyboard` in
`ControllerSettingsViewModel`; the `Keyboard` group and `SettingsToggleRow` in
`ControllerSettingsScreen`. Done when the toggle persists and nothing else reads it yet.

**T2 — Cursor maths.** Tests 6.2. Add `spanGridMove` beside `gridMove`. Done when 6.2 passes.

**T3 — Keyboard logic.** Tests 6.3–6.6. Add `implementation(project(":core:core-navigation"))` to
`core-ui`; create `keyboard/` with the layout, buffer, state, reducer and `resolveTextInputMode`.
No Compose imports in these files.

**T4 — IME suppression spike (device, one field).** Prove on the Thor or Odin 3 that an editable,
focused `OutlinedTextField` can show its caret while the system keyboard stays hidden — first
choice `InterceptPlatformTextInput`. If it cannot, fall back to a read-only field with a drawn
caret and say so; that fallback is a visual change and goes to the user first. Record the outcome
in §11 before T5. Do not build on a guess.

**T5 — Controller and panel.** Tests 6.7, 6.10. `VirtualKeyboardController` (Hilt singleton),
`LocalVirtualKeyboard`, `VirtualKeyboardPanel` to §4, `VirtualKeyboardOverlay` for `BelowField`
and `BottomCenter`, previews with `PfpPreview`.

**T6 — Shell wiring.** Tests 6.8. Inject the controller into `XMBViewModel`; capture tier at the
top of `dispatchGamepadAction`; add it to `hasBlockingOverlay`; mirror the input source and the
pref; provide `LocalVirtualKeyboard` and mount the overlay in `XMBShell`; play effects' sounds.

**T7 — Settings fields.** Tests 6.9. Split `SettingsTextFieldRow`'s `beginEditing` into controller
and touch paths; draw the panel in `SettingsScaffold`'s footer band for `SettingsFooter` sessions
and swap `helperFooterItems`; hand over to the system keyboard on a tap.

**T8 — Game search.** Rest of 6.8. `GameSearchField` opens a `BelowField` session when the mode
resolves to `VIRTUAL`, otherwise today's `keyboard.show()`.

**T9 — Device pass 1.** §8 on both placements, touch and setting-off regressions included. Stop
here and show the user before phase 2.

**T10 — Phase 2 fields.** Work down §5's phase 2 rows, one screen per change, each with a device
check. `PfpTextEntryModal` needs the panel's height where it uses `imePadding()` today.

**T11 — Phase 3 dialog fields.** Move the five `AlertDialog` fields onto `PfpTextEntryModal`, then
they inherit T10's behaviour.

## 8. Device checklist (Thor or Odin 3, debug build)

1. Library Manager ▸ a console ▸ Add Extension: SELECT opens the PFP keyboard; type, delete, move
   the caret, switch layers, Done; the `Add ".ext"` row appears and is reachable.
2. Same field by tap: system keyboard, no PFP keyboard.
3. PFP keyboard open, tap the field: system keyboard takes over.
4. Toggle off: SELECT on the field opens the system keyboard.
5. Games ▸ Filter ▸ Search by controller: keyboard under the field, column filters live and stays
   visible; Done keeps the query; BACK restores the previous one.
6. Same by tapping the status pill: system keyboard.
7. Password field (Artwork ▸ ScreenScraper): mask holds.
8. A/B swap and X/Y swap on: hint glyphs follow; each button does what its label says.
9. With the keyboard open: START does not open notifications and the XMB behind never moves.
10. Second screen size: `wm size 864x1920` + `wm density 336`, then reset both.

## 9. Commands for the user

```bash
./gradlew :core:core-navigation:test
```

```bash
./gradlew :core:core-ui:testDebugUnitTest
```

```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*ControllerVirtualKeyboardPrefTest"
```

```bash
./gradlew :feature:feature-settings:testDebugUnitTest
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

```bash
./gradlew :app:installFullDebug
```

## 10. Open questions for the user

1. **Shift indicator and caps lock.** The mockup never shows Shift active. Proposed: letter labels
   switch to uppercase and the Shift key takes the white 34% fill without the border; a second press
   locks. Needs a yes before it is drawn. Until then build one-shot shift only.
2. **Placement on screens outside the mockup.** Default `BottomCenter` with the hint pill beneath.
   Show screenshots per screen in T10.
3. **START as Done.** Not in the approved pill; would add a sixth prompt.
4. **Held backspace.** `GamepadInputHandler` only repeats directions, so Delete is one per press.
5. **Typing cue.** `SELECT` per key may be too busy; judge on device.

## 11. Findings log

- **2026-10-01 — T2 API.** `spanGridMove(rows: List<List<Int>>, from: SpanGridCursor, direction)`;
  `SpanGridCursor(row, cell, anchorColumn)` carries the column a vertical move travelled through.
- **2026-10-01 — T3.** `core-ui` takes `core-navigation` as `api` (the keyboard state exposes
  `SpanGridCursor`). Shift is one-shot and clears after any typed character (§10 Q1 still open).
  Caret moves emit `KeyboardEffect.CaretMoved` with a `SCROLL` cue; text changes emit `Edit(text, caret)`.
- **2026-10-01 — T4 spike built, not yet judged.** `SuppressPlatformKeyboard` (core-ui) wraps a field in
  `InterceptPlatformTextInput` and *holds* its input-session request while active, releasing it when
  active turns false — so the touch handover is just lifting the hold. It is always installed, never
  added on demand, so the field keeps its call site and focus. Wired into `SettingsTextFieldRow` behind
  `holdSystemKeyboardForController`, set only on Library Manager ▸ a console ▸ Add Extension.
- **2026-10-01 — T5/T6 built ahead of the T4 verdict** at the user's go-ahead; neither depends on how
  the field suppresses the system keyboard. `VirtualKeyboardController.release(token)` is the quiet
  drop for a field leaving composition; `close` happens only through BACK, a newer session or the
  setting turning off. `VirtualKeyboardRequest.anchor` carries the field's bottom-right for
  `BELOW_FIELD`. The shell's capture is `keyboardCaptures()` at the top of `dispatchGamepadAction`;
  the overlay mounts after the shell modals, inside the base-density scope.
- **2026-10-01 — T4 verdict (Odin3): first choice works.** Holding the session request in
  `InterceptPlatformTextInput` keeps the system keyboard down **and the caret blinks** (an earlier
  "no caret" report was a misread). Touch-only editing is unchanged. One gap: lifting the hold on a
  tap does not by itself show the system keyboard — the held request starts the session but nothing
  asks for the keyboard. T7's handover therefore calls `keyboard.show()` a frame after the hold lifts.
  No fallback; no visual change.
- **2026-10-01 — T7.** Every `SettingsTextFieldRow` now splits controller and touch: SELECT opens a
  `SETTINGS_FOOTER` session when `modeFor(CONTROLLER)` is `VIRTUAL`, a tap keeps the system keyboard.
  The field edits a `TextFieldValue` so the keyboard places the caret; `BringIntoViewRequester`
  frames the row above the grown footer. The T4 flag on Add Extension is gone.
- **2026-10-01 — T8.** `GameSearchField` decides its keyboard once, on open, from the shell's mirrored
  input source; the panel's anchor is the field's bottom-right in root px. New `onCancel` param, wired
  to `onGameSearchCancelled`, because the keyboard takes BACK before the ViewModel's search branch.
- **2026-10-01 — T9 device pass 1 passed** on the Odin3: all nine §8 checks (settings field, game
  search, touch and setting-off regressions, password mask, swapped layouts, START isolation).
- **2026-10-01 — §10 Q1 settled: caps lock.** Approved mockup: Shift cycles off → one-shot → locked;
  lit Shift takes white 34%, a lock adds a bar under the arrow. `ShiftMode` replaces the boolean.
- **2026-10-01 — T10 shared wiring.** `rememberVirtualKeyboardEdit` / `VirtualKeyboardTextInput` /
  `Modifier.virtualKeyboardField` (core-ui) hold the field value + caret, the session, the held system
  keyboard and the tap handover; a field calls `edit.start()` where it used to call `keyboard.show()`.
  `VirtualKeyboardBottomReserve` (256 dp) is what centred cards pad by while a `BOTTOM_CENTER` panel
  is up — `imePadding()` cannot see PFP's keyboard.
- **2026-10-01 — T10 sites (batched, one device pass).** Wizard field (footer band; the scaffold now
  swaps any footer, the wizard's included, for the keyboard). `PfpTextEntryModal` — so Collections,
  Game Detail note / title / new collection and App Detail name / new collection — with Done = save
  (or step to Cancel when invalid) and BACK = close the keyboard and move to the buttons. Music
  Browser, App Picker, the Shiba `SearchRow` (Shiba Coins, Shiba Library, Search Online), Artwork
  Studio change-match and search, Storefront Rematch (new `onStopQueryEdit`), App Drawer (new quiet
  `onCloseSearch`). All `BOTTOM_CENTER`.
- **Not converted, needs the user:** tap-only fields with no controller entry today — Artwork
  Orphans search, Shiba Coins' Enter App ID, the Metadata field editor — and Library Manager's
  Add by ID dialog (two fields + store chips + three buttons; too much for `PfpTextEntryModal`, so a
  redesign with a mockup). `StorefrontAppDrawer` is dead code.
- **2026-10-01 — T11.** The shell's App Rename / Collection Name dialogs had already moved to the
  shared modals, so they inherit T10; Library Manager's other dialogs no longer hold text fields.
- **2026-10-01 — test-harness note.** `DisplaySettingsViewModelFontColorTest`'s legibility case began
  failing every run once core-ui grew (more classes to load in a class's first test — the hazard
  `ViewModelTestWaits` documents). It now takes `observeUntilSettled`, as that helper prescribes for a
  test whose action reads `uiState.value`. Verified failing at the change and passing after, 3/3.
- **2026-10-01 — fixes from the phase 2 device pass.** (1) Games search raised both keyboards: it
  focused the field before opening the session, so the field's system-keyboard request went out
  unheld. It now opens the session, waits a frame, then focuses — the order every other site uses.
  (2) Artwork Studio's search card: with PFP's keyboard open, "Use game title" (and the other card
  buttons) were unreachable — the system IME had let Android focus wander onto them. The card now has
  controller navigation: keyboard BACK or DOWN moves to the buttons (Search · Use game title · Cancel,
  LEFT/RIGHT, A presses), UP returns to the field and reopens the keyboard, BACK on the buttons cancels.
- **2026-10-01 — §10 Q3 settled: START is Done.** The reducer maps `HOME` to Done (CONFIRM cue), and
  the prompt pill gains a sixth item, `HOME` Done, before Close.
- **2026-10-01 — the hold outlives the session.** After Done/BACK/stop the field could keep focus
  and its own request then raised the system keyboard (seen as "keyboard comes straight back after B"
  on Artwork Studio's search). `VirtualKeyboardEdit.holdsSystemKeyboard` now stays true until the
  field loses focus or a tap hands over; `VirtualKeyboardTextInput` follows it.
- **2026-10-01 — Manual metadata dialogs on the shared modals.** Game Detail's Current-vs-Incoming
  overlay drew its own field editor and title-replace confirm; both are now `metadataModalSpec` →
  `PfpTextEntryModal` / `PfpConfirmModal`, so they get PFP's keyboard. `PfpModalSpec.TextEntry`
  gained `label`, `multiline` (Description) and `numeric` (Year, Rating). The editor's "Revert to …"
  button is gone — each row already has its own revert, and an empty field saves as "use scraped".
- **2026-10-01 — legibility (device feedback).** Key fills 22% / function keys 14% / lit Shift 45%
  (from the mockup's 10 / 5 / 34); panel 96% black (from 86%); prompt pill 92% (from 70%). While
  the overlay keyboard is up, a screen's own bottom prompt bar fades out (`isVirtualKeyboardOverlayOpen`):
  App Drawer, Music Browser, App Picker, Artwork Studio and every `PfpDetailHelperFooter` screen.
- **2026-10-01 — X on an open search with text.** PFP's keyboard Done leaves a search open with the
  keyboard down; X used to then close it and wipe the query. App Drawer (`drawerSearchButton`) and
  App Picker (`AppPickerState.pressSearch`) now bring the keyboard back when the field holds text,
  close only an empty search, and leave touch's toggle as it was.

