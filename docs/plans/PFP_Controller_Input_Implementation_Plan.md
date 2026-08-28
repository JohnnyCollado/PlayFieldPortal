# Play Field Portal: Controller / Input Architecture Implementation Plan

**Target branch:** `settings-refactor`\
**Implementation target:** Claude\
**Scope:** Introduce a reusable application-level controller/input
architecture and migrate Settings controller navigation onto it without
overfitting the architecture to the current Settings implementation.

## 1. Goal

Create a clean input layer underneath Play Field Portal's current
Settings controller-navigation work.

The architecture must separate:

-   Android hardware input
-   controller/device tracking
-   physical input normalization
-   configurable bindings
-   semantic UI actions
-   feature-specific navigation behavior

Settings should be the **first major consumer** of this architecture,
not the owner of the global controller system.

Do not rewrite working Settings navigation simply for architectural
purity. Preserve feature-level behavior where it belongs and move only
hardware/input responsibilities into the shared input layer.

------------------------------------------------------------------------

## 2. Design Principles

### 2.1 Keep hardware concerns out of features

Feature code should not need to know:

-   Android controller keycodes
-   `MotionEvent` axis values
-   joystick dead zones
-   HAT axes
-   device IDs
-   controller connection/disconnection details
-   physical controller compatibility quirks

Features should receive semantic actions such as:

``` kotlin
UiInputAction.Up
UiInputAction.Down
UiInputAction.Confirm
UiInputAction.Back
```

### 2.2 Keep feature behavior out of the input layer

The shared input system must **not** know concepts such as:

-   Settings category rail
-   Settings rows
-   library grid positions
-   selected Settings panel
-   which Settings control should gain focus

For example:

> "Controller moved right" is an input concern.

> "Move focus to the next Settings category" is a Settings concern.

### 2.3 Do not create a god-object

Do not implement one giant `GamepadInputManager` responsible for device
discovery, parsing, normalization, mappings, repeat behavior, settings
persistence, UI navigation, and feature actions.

Separate responsibilities into small components, but do not create
excessive interfaces or unnecessary abstraction.

### 2.4 Do not blindly preserve the current implementation

Use the current Settings controller work as evidence of actual
requirements, not as the architecture template.

If current code mixes Android input handling with Settings navigation,
separate those responsibilities where practical.

Do not preserve a weak boundary solely to minimize changes.

### 2.5 Do not rewrite working code without a concrete reason

The inverse also applies.

If existing Settings code correctly owns:

-   focus movement
-   category/content transitions
-   row selection
-   setting adjustment behavior

keep it in Settings.

------------------------------------------------------------------------

## 3. Proposed Input Pipeline

``` text
Android KeyEvent / MotionEvent / InputDevice
                    |
                    v
            AndroidInputBridge
                    |
                    v
           ControllerRegistry
                    |
                    v
          ControllerNormalizer
                    |
                    v
             ControllerInput
                    |
          +---------+---------+
          |                   |
    Capture Mode         Normal Mode
          |                   |
          v                   v
   Binding Capture     InputBindingResolver
                              |
                              v
                        UiInputAction
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
          Settings          Library         Other UI
             |
             v
      Compose focus/navigation
```

The pipeline should retain useful physical device metadata long enough
for debugging, configuration, and future controller profiles.

------------------------------------------------------------------------

## 4. Module / Package Placement

Preferred location:

``` text
core/input/
```

Use the repository's existing Gradle/module conventions. If introducing
a new Gradle module would cause disproportionate churn, initially place
the implementation in the appropriate existing core module/package and
document the reason.

Do **not** place the global implementation under `feature-settings`.

Suggested initial files:

``` text
core/input/
├── AndroidInputBridge.kt
├── ControllerRegistry.kt
├── ControllerDevice.kt
├── ControllerInput.kt
├── ControllerNormalizer.kt
├── ControllerAxisState.kt
├── InputBinding.kt
├── InputBindingResolver.kt
├── InputBindingsRepository.kt
├── InputCaptureController.kt
└── UiInputAction.kt
```

This list is guidance, not a requirement. Merge files/classes where the
distinction does not justify separate code.

------------------------------------------------------------------------

## 5. Core Models

### 5.1 Semantic UI actions

Create a UI-level action model similar to:

``` kotlin
sealed interface UiInputAction {
    data object Up : UiInputAction
    data object Down : UiInputAction
    data object Left : UiInputAction
    data object Right : UiInputAction

    data object Confirm : UiInputAction
    data object Back : UiInputAction

    data object PreviousSection : UiInputAction
    data object NextSection : UiInputAction

    data object Menu : UiInputAction
}
```

Adjust the exact action set based on actual PFP requirements discovered
during implementation.

Do not add speculative actions without a consumer.

The action model should not encode Android-specific terminology.

### 5.2 Physical controller inputs

Use normalized physical names rather than Xbox/PlayStation labels:

``` kotlin
enum class ControllerButton {
    SOUTH,
    EAST,
    WEST,
    NORTH,

    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,

    LEFT_BUMPER,
    RIGHT_BUMPER,

    LEFT_STICK,
    RIGHT_STICK,

    START,
    SELECT
}
```

Expand only as required.

Analog inputs should have their own representation rather than
pretending every axis is a button.

### 5.3 Preserve source metadata

Normalized events should retain source information where useful:

``` kotlin
data class ControllerInputEvent(
    val deviceId: Int,
    val input: ControllerInput,
    val state: InputState,
    val eventTime: Long
)
```

Do not immediately discard `deviceId`.

Future debugging, device-specific mappings, configuration, and
controller profiles may require it.

------------------------------------------------------------------------

## 6. AndroidInputBridge

Create a narrow Android-facing entry point responsible for receiving:

-   `KeyEvent`
-   `MotionEvent`
-   relevant `InputDevice` information

The Activity/UI host may delegate events similarly to:

``` kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    return if (inputBridge.onKeyEvent(event)) {
        true
    } else {
        super.dispatchKeyEvent(event)
    }
}

override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    return if (inputBridge.onMotionEvent(event)) {
        true
    } else {
        super.onGenericMotionEvent(event)
    }
}
```

### Critical rule

Returning `true` must mean:

> PFP deliberately consumed this event.

Do **not** globally swallow every event that resembles controller input.

Unconsumed events must fall through to Android/Compose so focused
controls, text fields, dialogs, accessibility behavior, and other normal
input paths continue functioning.

------------------------------------------------------------------------

## 7. ControllerRegistry

Provide authoritative tracking for connected controller-capable devices.

At minimum expose:

``` kotlin
connectedControllers
lastActiveController
```

Prefer concepts similar to:

``` kotlin
StateFlow<List<ControllerDevice>>
StateFlow<ControllerDevice?>
```

Do not enforce a single exclusive "active controller" unless a real
requirement demands it.

If controller #2 presses Confirm, PFP should generally accept it even if
controller #1 was used previously.

`lastActiveController` may later support:

-   glyph selection
-   Settings configuration context
-   diagnostics
-   controller profiles

but should not implicitly lock input to one device.

------------------------------------------------------------------------

## 8. ControllerNormalizer

This is a critical component.

Translate Android-specific physical input into canonical controller
input.

Handle relevant sources such as:

-   `KEYCODE_DPAD_*`
-   controller face-button keycodes
-   shoulder buttons
-   `AXIS_HAT_X`
-   `AXIS_HAT_Y`
-   left analog stick axes
-   triggers where required

### 8.1 Edge detection

Do not emit repeated "pressed" actions for every `MotionEvent` while a
stick remains held.

Conceptually:

``` text
0.0 -> 0.3 -> 0.6 -> 0.9
              |
          RIGHT pressed

0.9 -> 0.7 -> 0.4 -> 0.2
                    |
               RIGHT released
```

Track state and emit meaningful transitions.

### 8.2 Dead zones and hysteresis

Use Android device motion-range information where appropriate.

Account for `MotionRange.getFlat()` or equivalent device-specific
neutral ranges.

Use separate activation/release thresholds when helpful so stick noise
around the threshold does not rapidly alternate between
pressed/released.

### 8.3 D-pad / HAT / stick duplication

Some devices may expose overlapping directional sources.

Do not naïvely produce independent navigation actions from:

``` text
DPAD_RIGHT
HAT_X positive
AXIS_X positive
```

if the hardware reports multiple representations for one physical
action.

Design normalization/state handling to minimize double-navigation.

Test this on real hardware where available.

------------------------------------------------------------------------

## 9. InputBindingResolver

Translate normalized physical inputs into semantic PFP actions.

Example:

``` text
ControllerButton.SOUTH
        |
        v
UiInputAction.Confirm
```

Provide sensible default bindings.

Keep binding resolution separate from Android normalization.

The resolver should not know how Settings or Library navigation works.

------------------------------------------------------------------------

## 10. Binding Persistence

Define a narrow persistence boundary similar to:

``` kotlin
interface InputBindingsRepository {
    fun bindings(): Flow<InputBindings>

    suspend fun setBinding(
        action: UiInputAction,
        input: ControllerInput
    )
}
```

Adapt this to the repository's current settings/data architecture
instead of creating duplicate persistence infrastructure.

The important dependency direction is:

``` text
feature-settings
      |
      | edits
      v
InputBindingsRepository
      ^
      | reads
      |
input resolver
```

The input system must never depend on `feature-settings`.

Settings is an editor/client of the configuration.

------------------------------------------------------------------------

## 11. Input Capture Mode

Design for rebinding even if the complete rebinding UI is not part of
the first implementation.

When Settings is capturing a physical input:

``` text
Select "Confirm" binding
        |
        v
Capture Mode starts
        |
        v
User presses R1
        |
        v
Normalizer produces RIGHT_BUMPER
        |
        v
Capture receives RIGHT_BUMPER
        |
        v
Binding is saved
```

The event must **not** first become the normal mapped action.

Incorrect behavior:

``` text
R1
 |
 v
NextSection
 |
 v
Settings navigates away
```

Provide an interception/capture boundary between normalization and
normal binding resolution.

------------------------------------------------------------------------

## 12. Integration with Current Settings Work

Review the existing Settings controller-navigation implementation before
modifying it.

### Keep in Settings

Logic answering questions such as:

-   Where should focus move?
-   What does Left/Right do to this setting?
-   When does Back move from content to categories?
-   Which row/category should become selected?
-   How does Settings move between category and content regions?

### Move out of Settings

Logic answering questions such as:

-   Which Android keycode represents a face button?
-   Is this event from a joystick?
-   What does `AXIS_X` mean?
-   Is the stick outside its dead zone?
-   Did a controller connect/disconnect?
-   What physical input maps to Confirm?
-   How should controller mappings be stored?

### Migration objective

Settings should ultimately consume `UiInputAction` rather than interpret
Android controller hardware itself.

Do not replace the feature's focus/navigation rules with a generic
global navigation engine.

------------------------------------------------------------------------

## 13. Compose Integration

Allow Compose to continue owning feature-specific focus behavior.

The input architecture should deliver semantic actions. The feature
decides what those actions mean in its current state.

Conceptually:

``` kotlin
when (action) {
    UiInputAction.Down -> moveToNextSetting()
    UiInputAction.Left -> adjustCurrentSetting(-1)
    UiInputAction.Back -> leaveCurrentPanel()
    else -> Unit
}
```

Do not make `core:input` aware of composables such as
`SettingsScaffold`, category rails, rows, or library grids.

Avoid fighting Compose's existing focus/key event system.

------------------------------------------------------------------------

## 14. Event Transport

Do not default to sending every raw Android controller sample through a
`SharedFlow`.

High-frequency axis input does not need coroutine-stream overhead simply
because the UI uses Compose.

Prefer a synchronous low-level path where appropriate:

``` kotlin
interface ControllerInputSink {
    fun onControllerInput(event: ControllerInputEvent): Boolean
}
```

Use reactive state for information that is actually stateful:

``` kotlin
connectedControllers: StateFlow<List<ControllerDevice>>
lastActiveController: StateFlow<ControllerDevice?>
```

A high-level action stream may be appropriate **after
normalization/binding resolution**, if it fits the existing
architecture.

Choose based on the current codebase rather than forcing this exact API.

------------------------------------------------------------------------

## 15. Implementation Phases

### Phase 1: Audit and models

Before editing:

1.  Inspect current Settings controller-navigation code.
2.  Identify all Android key/axis handling currently living in feature
    code.
3.  Identify the current settings/data persistence mechanism.
4.  Identify the Activity or root UI event entry points.
5.  Identify existing module/dependency conventions.
6.  Record which existing behavior must remain unchanged.

Then introduce the minimum core models:

-   `ControllerInput`
-   `ControllerInputEvent`
-   `UiInputAction`
-   required device/state models

**Exit criteria:** Models compile and do not introduce feature
dependencies.

### Phase 2: Controller normalization

Implement and test:

-   key normalization
-   D-pad handling
-   HAT axes
-   analog directional thresholds
-   dead zones
-   edge detection
-   hysteresis
-   duplicate-direction mitigation

Do not integrate deeply into Settings yet.

**Exit criteria:** Unit tests demonstrate stable normalized input from
representative Android events/state transitions.

### Phase 3: Android bridge and registry

Implement:

-   Activity/root input delegation
-   controller discovery/tracking
-   connection/disconnection handling
-   `connectedControllers`
-   `lastActiveController`

Verify events not deliberately consumed still reach normal
Android/Compose handling.

**Exit criteria:** Real or representative controllers can connect,
disconnect, and generate normalized input without breaking
touch/keyboard/Compose behavior.

### Phase 4: Default binding resolution

Implement:

``` text
ControllerInput -> UiInputAction
```

Add only the default actions currently needed by PFP.

Keep defaults centralized.

**Exit criteria:** Hardware-specific input can produce semantic PFP
actions independently of Settings.

### Phase 5: Settings migration

Migrate the current Settings controller navigation so hardware parsing
is no longer owned by Settings.

Preserve existing Settings focus/navigation semantics unless there is a
demonstrated bug.

Settings should consume `UiInputAction`.

**Exit criteria:**

-   current controller Settings navigation still works
-   touch navigation still works
-   keyboard behavior is not regressed
-   Settings no longer owns low-level controller interpretation

### Phase 6: Binding persistence and capture

Integrate with the existing settings persistence architecture.

Add:

-   default bindings
-   saved overrides
-   capture/interception state
-   ability to update a binding

A complete polished rebinding screen is optional unless already in
scope, but the architecture must support it cleanly.

**Exit criteria:** A captured physical input can be assigned without
triggering its previous normal UI action.

------------------------------------------------------------------------

## 16. Testing Requirements

### Unit tests

Prioritize tests for the parts most likely to produce subtle
regressions:

-   button press/release normalization
-   stick activation edge
-   stick release edge
-   no repeated press while held
-   dead-zone behavior
-   hysteresis around thresholds
-   HAT direction normalization
-   duplicate directional input mitigation
-   binding lookup
-   capture-mode interception
-   unknown/unmapped input behavior

### Integration/manual checks

Verify:

-   controller can navigate Settings
-   controller disconnect does not crash or strand UI state
-   second controller can provide input
-   touch remains usable
-   keyboard remains usable
-   text fields and dialogs do not have relevant events swallowed
-   holding an analog direction does not create uncontrolled duplicate
    navigation
-   D-pad does not double-step
-   Settings Back behavior remains correct
-   switching between touch and controller does not corrupt
    focus/selection

------------------------------------------------------------------------

## 17. Guardrails for Claude

### Do

-   inspect the current branch before making architectural changes
-   reuse existing repository patterns where they are sound
-   keep diffs incremental
-   write tests around normalization/state behavior
-   preserve working Settings semantics
-   document any intentional departure from this plan when repository
    evidence supports it
-   prefer the simplest design that maintains the ownership boundaries
    above

### Do not

-   build a giant `GamepadInputManager`
-   move Settings-specific focus logic into the global input layer
-   make `core:input` depend on `feature-settings`
-   swallow all controller-looking Android events globally
-   convert every raw axis sample into a `SharedFlow` event without a
    concrete need
-   enforce one exclusive active controller without a requirement
-   create controller-brand-specific semantic actions such as `XboxA` or
    `PlayStationCross`
-   rewrite current Settings navigation merely to make the architecture
    look cleaner
-   add speculative abstractions for features that do not exist
-   silently change unrelated settings behavior during the migration

------------------------------------------------------------------------

## 18. Expected Deliverable

Implementation should leave PFP with this ownership model:

``` text
Android / hardware details
          |
          v
      core input
          |
          v
semantic UiInputAction
          |
          v
     feature logic
```

The Settings feature should know **what the user wants to do**, not
**how Android represented the controller input**.

The input layer should know **what physical input occurred and what
application action it maps to**, not **how a Settings screen or Library
grid should respond**.

------------------------------------------------------------------------

## 19. Definition of Done

The work is complete when:

-   shared controller/input infrastructure exists outside Settings
-   Android controller inputs normalize consistently
-   analog/D-pad behavior does not produce obvious duplicate navigation
-   controller devices can be tracked without enforcing unnecessary
    exclusivity
-   default bindings resolve to semantic `UiInputAction`s
-   current Settings controller navigation consumes the shared action
    layer
-   Settings-specific focus/navigation behavior remains feature-owned
-   input capture is architecturally supported
-   unconsumed events can still reach Android/Compose
-   touch and keyboard behavior remain functional
-   critical normalization and binding behavior is covered by tests
-   no unnecessary global manager or feature dependency has been
    introduced

------------------------------------------------------------------------

## 20. Implementation Philosophy

Treat this plan as an architectural constraint and implementation guide,
**not as a demand to reproduce every proposed class name literally**.

If the current codebase reveals a simpler implementation that preserves
the same boundaries, use it.

If the current implementation conflicts with these boundaries, be
willing to refactor it.

The goal is not to make the repository match this document.

The goal is to leave Play Field Portal with an input architecture that
can support Settings today and the rest of the launcher later without
turning either the input layer or Settings into the permanent dumping
ground for controller behavior.
