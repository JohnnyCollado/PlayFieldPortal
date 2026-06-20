---
name: kotlin-project-state-management
description: Use when choosing, implementing, or reviewing state-holder patterns in a KMP project — ViewModel, shared presenter, MVI, or StateFlow-in-common — including effect handling, UiState modeling, and testability.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.0.0"
---

# Kotlin Multiplatform State Management

Use this skill when choosing, implementing, or reviewing how UI state is owned and produced in a Kotlin Multiplatform project.

State management sits at the intersection of every other KMP architectural concern. How state is held, where it lives in source sets, and how it integrates with each platform's lifecycle determines the testability, predictability, and long-term maintainability of the entire UI layer.

This skill is intentionally precise. It distinguishes between patterns clearly, explains when each is appropriate, and flags common mistakes that look correct at first but create problems at scale.

## What this skill covers

- The invariant state contract that all valid state-holder patterns must satisfy
- The main pattern families available in KMP and what each costs
- Platform-specific differences that affect state-holder choice
- Effect handling: one-time events vs persistent state
- State modeling: how to shape `UiState` correctly
- Testability implications of each choice
- Anti-patterns in each category

## What this skill does not cover

- Navigation (see `kotlin-navigation-compose-multiplatform`)
- Data-layer design (see `kotlin-data-kmp-data-layer`)
- Full architecture review (see `kotlin-project-architecture-review`)
- Full feature implementation (see `kotlin-project-feature-implementation`)

---

## The state-holder contract

Regardless of which pattern a project uses, a valid state holder must satisfy this contract:

1. **One observable state output** — a single stream of immutable `UiState` that the UI renders from. Not several scattered booleans, not a mutable object the UI reads fields from directly.
2. **Separate one-time effects** — navigation triggers, snackbar requests, permission launches, and similar one-shot actions must not be modeled as persistent state. Once consumed, they must not replay.
3. **User actions as inputs** — the UI sends events or calls action functions; it does not set state directly or coordinate work.
4. **No business rules in rendering** — the state holder processes inputs, coordinates lower layers, and produces output. Composables render.
5. **Platform lifecycle transparency** — the state holder must behave correctly across the platform lifecycle events relevant to its target (configuration changes on Android, view disappear/appear on iOS, window focus on desktop).

Every pattern described below is evaluated against this contract.

---

## Pattern families

### 1. Android ViewModel with StateFlow (Android-only or Android-primary)

**What it is:**  
`ViewModel` from AndroidX lifecycle, holding a `MutableStateFlow<UiState>` privately and exposing it as `StateFlow<UiState>`. Effects emitted via a separate `SharedFlow` or `Channel`-backed flow.

**When it is the right choice:**
- The project targets Android only, or Android plus other platforms where ViewModel wrapper libraries are used
- The team is already fluent with ViewModel semantics
- Jetpack Compose with `collectAsStateWithLifecycle()` or `collectAsState()` is the rendering layer

**What it provides:**
- Automatic scoping to Android's ViewModel lifecycle (survives configuration changes by default via `viewModelScope`)
- Strong Jetpack integration (`hiltViewModel()`, Navigation ViewModel scoping, `SavedStateHandle`)
- Well-understood by Android developers

**Platform constraint:**
ViewModel is an AndroidX library. It does not exist natively on iOS, desktop, or web. Projects using ViewModel in shared `commonMain` code require an additional library to provide a ViewModel-compatible abstraction on non-Android targets (see KMP ViewModel libraries below).

**Source-set placement:**
- Pure Android ViewModel implementations belong in `androidMain`
- If the project uses a KMP ViewModel abstraction, the shared interface can live in `commonMain`

**Testability:**
- Good: can be tested on JVM with `kotlinx-coroutines-test`, `TestScope`, `runTest`, and `Turbine` or `collectValues()`
- `viewModelScope` must be replaced with an injected `CoroutineScope` in tests, or use the `ViewModelScenario`/rule pattern from `lifecycle-viewmodel-testing`

---

### 2. Shared presenter / state machine in commonMain

**What it is:**  
A plain Kotlin class in `commonMain` that holds a `MutableStateFlow<UiState>` and exposes it, processes user actions via functions or a sealed `Action` type, and emits effects. No AndroidX dependency. Lifecycle management is the platform entry point's responsibility.

**When it is the right choice:**
- The project targets multiple platforms and wants state-holder logic to be shared and tested once
- The team wants to avoid a dependency on AndroidX ViewModel in shared code
- The shared Kotlin logic is already substantial enough that platform-specific state holders would duplicate significant logic

**What it provides:**
- Fully testable in `commonTest` with `kotlin.test` and `kotlinx-coroutines-test`
- No platform dependency — compiles for all KMP targets
- Explicit lifecycle management (no magic — the platform entry point starts and cancels the scope)

**Platform responsibility:**
The presenter owns no lifecycle. Each platform must:
- Create the presenter at the right point in the view lifecycle
- Provide a `CoroutineScope` that is cancelled when the view is gone
- Cancel the scope correctly on back navigation or view destruction
- Handle process death / state restoration separately if needed (there is no `SavedStateHandle` equivalent without explicit implementation)

On Android, this usually means creating the presenter inside a ViewModel to retain it across configuration changes, then delegating to it. On iOS, this means creating the presenter in the view owner (e.g., `ObservableObject`-wrapping in SwiftUI or direct state subscription in UIKit) and tying its scope to the view's lifetime.

**Source-set placement:**
- Presenter class: `commonMain`
- Platform lifecycle wiring: `androidMain`, `iosMain`, etc.
- Tests: `commonTest`

**Testability:**
- Excellent: all state-transition logic testable in `commonTest` with no Android dependency
- Coroutine scope injection makes deterministic testing straightforward

---

### 3. KMP ViewModel abstraction libraries

**What it is:**  
Third-party or JetBrains-supported libraries that provide a `ViewModel` class in `commonMain` that compiles to AndroidX ViewModel on Android and to a lifecycle-aware equivalent on other targets.

As of mid-2025, the main options are:
- **`lifecycle-viewmodel` KMP artifact** (from AndroidX/JetBrains): JetBrains introduced official KMP support for `androidx.lifecycle.ViewModel` as a multiplatform artifact. This is the most official path when it matches the project's target set.
- **Third-party ViewModel abstractions**: several community libraries provide similar functionality; evaluate for stability, maintenance, and target support before adopting.

**When it is the right choice:**
- The project wants to write ViewModel-style code once in `commonMain` and have it behave correctly on all targets including Android
- The team does not want to manage lifecycle wiring manually per platform
- The lifecycle-viewmodel KMP artifact covers the project's targets

**What it provides:**
- Shared ViewModel in `commonMain` with lifecycle-correct behavior per platform
- `viewModelScope` (or equivalent) provided per-platform
- Familiar ViewModel API surface

**What to verify:**
- Target coverage: confirm the chosen artifact supports all declared KMP targets
- API stability: check current release status — KMP ViewModel support matured significantly in 2024–2025 but verify against current AndroidX release notes
- `SavedStateHandle` availability: may not be supported on non-Android targets; check per-library docs

**Source-set placement:**
- ViewModel classes: `commonMain`
- Platform-specific lifecycle wiring (if any): platform source sets

**Testability:**
- Similar to shared presenter: use injected scopes and `kotlinx-coroutines-test` in `commonTest`
- Avoid testing through `AndroidX` test infrastructure if the goal is shared test coverage

---

### 4. MVI (Model-View-Intent) pattern

**What it is:**  
A stricter unidirectional architecture where user actions are explicitly typed as `Intent` or `Action` objects, the state holder reduces them into new `State` objects (often via a pure function or a reducer), and side effects are modeled explicitly as an `Effect` or `SideEffect` type.

MVI is a pattern, not a library. It can be implemented on top of any of the above state-holder mechanisms. Several community libraries (Orbit MVI, MVI Kotlin, etc.) provide MVI structure as a framework.

**When it is the right choice:**
- Screens have complex, non-trivial state transitions where the action → state relationship benefits from being an explicit, traceable function
- The team wants stricter discipline on what can cause a state change
- Testing with explicit action/state pairs (action `X` in current state `S` produces state `S'`) is valuable for the use case

**When it is likely overkill:**
- Simple screens with few states (loading/success/error) — the overhead of action types and reducers exceeds the complexity
- Small teams or fast-moving projects where ceremony slows iteration more than it helps

**What it provides:**
- Highly deterministic state transitions
- Explicit, auditable action log
- Effects (one-time events) are a first-class concern in most MVI implementations
- Straightforward to test at the reducer level with pure functions

**What to watch:**
- Action type proliferation: large screens can accumulate dozens of action subtypes, making the sealed class unwieldy
- Effect vs state confusion still occurs even with MVI — a one-time navigation trigger modeled as persistent state is wrong regardless of the pattern name
- Library choice matters for KMP: verify the chosen MVI library supports your full target set

**Source-set placement:**
- If using a KMP-compatible MVI library: state holder and reducer in `commonMain`
- Platform wiring as needed in platform source sets

---

## Effect handling

One-time effects are the most common source of state-management bugs. The core mistake is modeling an effect as persistent state.

### The problem

```kotlin
// WRONG: snackbar as persistent state
data class UiState(
    val items: List<Item>,
    val errorMessage: String?  // stays in state after being shown — shows again on recomposition
)
```

A user sees the snackbar. They rotate the screen. The state is re-collected. The snackbar shows again. The error message is now part of permanent UI state and will survive any state restoration.

### Correct approaches

**Option A: Separate `SharedFlow` for effects**
```kotlin
// State holds only persistent UI truth
data class UiState(val items: List<Item>, val isLoading: Boolean)

// Effects are fire-and-forget
sealed interface UiEffect {
    data class ShowError(val message: String) : UiEffect
    data object NavigateToDetail : UiEffect
}

val uiState: StateFlow<UiState>
val effects: SharedFlow<UiEffect>  // replay = 0
```

The `SharedFlow` with `replay = 0` emits once to current subscribers. No replay on new subscription. UI collects this in a `LaunchedEffect` keyed to the composable lifecycle.

**Option B: Nullable one-time event in state with explicit consumption**  
Some teams model effects as nullable fields with an explicit "consumed" action. This works but requires discipline — forgetting to send the consumed action is a common mistake.

**Option C: `Channel`-backed flow (FIFO queue)**  
A `Channel(BUFFERED)` exposed as a `receiveAsFlow()` delivers effects one at a time to one subscriber. Good for effects that must not be dropped even during lifecycle transitions, but adds buffering complexity.

**Review rule:** Every non-null, non-boolean field in `UiState` that represents an action rather than a fact is likely a misplaced effect. Ask: "If this screen is recreated, should this still be shown?" If no, it is an effect.

---

## UiState modeling

### Shape `UiState` around screen truth, not data-layer truth

```kotlin
// WRONG: mirrors the repository response
data class UiState(
    val user: User?,
    val posts: List<Post>?,
    val isLoadingUser: Boolean,
    val isLoadingPosts: Boolean,
    val userError: Throwable?,
    val postsError: Throwable?
)
// 64 incoherent combinations

// BETTER: models screen reality
sealed interface UiState {
    data object Loading : UiState
    data class Success(val user: User, val posts: List<Post>) : UiState
    data class Error(val reason: ErrorReason, val canRetry: Boolean) : UiState
    data class PartialContent(val user: User, val postsError: String) : UiState
}
```

### Rules

- Immutable: `data class` with `val` fields; expose as interface or sealed type when substate variation exists
- No raw `Throwable` exposed to UI — map to a presentation error type at the state-holder boundary
- No DTOs, persistence models, or network response types in `UiState`
- Include all states the UI can actually be in: loading, success, empty (distinct from loading), error, partial, retry-available
- Avoid boolean flag explosion: three booleans produce 8 states; most are incoherent in practice

---

## Platform-specific lifecycle differences

| Platform | Config change behavior | State restoration | Scope owner |
|---|---|---|---|
| Android (ViewModel) | Survives by default | `SavedStateHandle` | `viewModelScope` |
| Android (shared presenter in ViewModel) | Survives if hosted in ViewModel | Manual or `SavedStateHandle` via wrapper | ViewModel-provided scope |
| iOS (SwiftUI ObservableObject) | N/A — no config changes | Manual (`@AppStorage`, custom) | View owner; must cancel on deinit |
| iOS (UIKit VC) | N/A | Manual | VC; must cancel in `viewDidDisappear`/`deinit` |
| Desktop (Compose Desktop) | Window resize does not recreate | Manual | Root composable or explicit scope |
| Web (Compose Web/Wasm) | N/A | Manual or URL-driven | Entry point |

### Review implications

- On Android, a shared presenter not hosted inside a ViewModel will be destroyed on rotation and recreate its state from scratch — this is usually wrong for screens with significant load cost
- On iOS, a presenter scope that is not cancelled on view disappearance leaks the coroutine indefinitely
- Do not assume Android configuration-change semantics apply to other targets
- Do not assume iOS memory-pressure behavior matches Android process death

---

## Review dimensions

### 1. Contract satisfaction

Check whether the state-holder satisfies the full contract: one observable state output, separate effects, user actions as inputs, no business rules in rendering, lifecycle correctness.

Flag as a concern when:
- Multiple state streams are exposed for the same screen
- Effects are modeled as persistent state fields
- Business logic runs in composables
- State is mutated from outside the state holder

### 2. Pattern appropriateness

Check whether the chosen pattern matches the project's actual target set and team context.

Flag as a concern when:
- AndroidX ViewModel is used in `commonMain` without a KMP ViewModel abstraction layer
- A shared presenter is used on Android without a ViewModel wrapper, causing loss on config changes
- MVI is applied to simple screens where it adds ceremony without clarity
- The pattern is inconsistent across similar screens without a stated reason

### 3. Effect handling correctness

Check whether one-time effects are modeled distinctly from persistent `UiState`.

Flag as a concern when:
- Effects are modeled as nullable or boolean fields in `UiState`
- Effects replay on recomposition or lifecycle re-entry
- Navigation triggers are part of persistent screen state

### 4. UiState shape

Check whether `UiState` correctly models the states the screen can actually be in.

Flag as a concern when:
- Boolean flag combinations produce incoherent states
- Raw `Throwable` or error strings are exposed directly
- DTOs or data-layer models are present in `UiState`
- Empty, partial-data, and retry states are absent despite the feature needing them

### 5. Source-set placement

Check whether state-holder code is placed correctly for its actual dependencies.

Flag as a concern when:
- AndroidX ViewModel is imported in `commonMain` without a KMP abstraction
- Platform lifecycle types (Activity, UIViewController) appear in shared state-holder logic
- A `commonMain` state holder uses `android.os.Bundle` or other Android-specific types

### 6. Scope and lifecycle management

Check whether the coroutine scope the state holder uses is managed correctly for each platform.

Flag as a concern when:
- Scopes are not cancelled when the view is destroyed on any target
- Android presenters survive configuration changes unintentionally (or fail to survive them when they should)
- The scope owner is unclear or inconsistent

### 7. Testability

Check whether state-holder logic is testable at the unit level.

Flag as a concern when:
- State transitions can only be validated through UI tests
- The scope is not injectable, making deterministic testing hard
- Effects cannot be asserted without running the full UI
- The state holder has hidden dependencies on platform singletons

---

## Severity framework

### High severity

- Effects modeled as persistent state (causes replay bugs)
- AndroidX ViewModel in `commonMain` without KMP abstraction (build fails on non-Android targets)
- Presenter scope not cancelled on view destruction (coroutine leak)
- State mutated from outside the state holder (breaks UDF)
- Business logic in composables with no state-holder boundary

### Medium severity

- Android shared presenter not hosted in ViewModel (loses state on config change)
- Inconsistent pattern across similar screens without reason
- Boolean flag explosion in `UiState`
- DTOs present in `UiState`
- State transitions only testable through UI or instrumented tests

### Low severity

- State holder growing large but still correct (consider splitting the screen or extracting domain logic)
- MVI applied to a simple screen (ceremonial but not broken)
- Effect mechanism works but could be clearer

---

## Required output format

When reviewing or designing state management, respond with:

1. **State management summary**
   - pattern in use (ViewModel, shared presenter, KMP ViewModel library, MVI)
   - source-set placement
   - effect handling mechanism
   - `UiState` shape
   - scope/lifecycle owner per platform

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by dimension**
   - contract satisfaction
   - pattern appropriateness
   - effect handling
   - `UiState` shape
   - source-set placement
   - scope/lifecycle management
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact changes to effect handling
   - `UiState` restructuring
   - scope management fixes
   - source-set corrections
   - pattern migration steps if needed

6. **Suggested target structure**
   - proposed state holder / effect / UiState split if useful

7. **Open risks**
   - platform-specific lifecycle edge cases still to validate
   - migration cost
   - KMP ViewModel library stability caveats if relevant

---

## Anti-patterns to flag aggressively

- Effects modeled as nullable or boolean fields in `UiState`
- `MutableStateFlow` exposed as public API from a state holder
- Business logic in composable rendering functions
- Multiple independent state streams for the same screen
- AndroidX ViewModel imported in `commonMain` without a KMP abstraction
- Presenter scope leaked on iOS or desktop (not cancelled on view destruction)
- Raw `Throwable` or DTO types in `UiState`
- State transitions that can only be verified through full UI tests
- Inconsistent pattern choice across similar screens without a documented reason

---

## References

- Android Developers: UI layer — https://developer.android.com/topic/architecture/ui-layer
- Android Developers: UI events — https://developer.android.com/topic/architecture/ui-layer/events
- Android Developers: State holders and UI state — https://developer.android.com/topic/architecture/ui-layer/stateholders
- Android Developers: ViewModel overview — https://developer.android.com/topic/libraries/architecture/viewmodel
- AndroidX Lifecycle KMP (ViewModel multiplatform) — https://developer.android.com/jetpack/androidx/releases/lifecycle
- Kotlin coroutines guide — https://kotlinlang.org/docs/coroutines-guide.html
- kotlinx-coroutines-test — https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- Compose Multiplatform state and side effects — https://kotlinlang.org/docs/multiplatform/compose-multiplatform.html
- kotlin.test — https://kotlinlang.org/api/latest/kotlin.test/
