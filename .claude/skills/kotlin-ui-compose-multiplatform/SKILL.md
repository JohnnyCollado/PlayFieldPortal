---
name: kotlin-ui-compose-multiplatform
description: Use when designing, implementing, or reviewing shared UI in Compose Multiplatform projects, including state-driven architecture, composable decomposition, layout/modifier discipline, adaptive behavior, and previewable shared UI in common source sets.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.2.0"
---

# Compose Multiplatform UI

Use this skill when designing, implementing, or reviewing shared UI in a Compose Multiplatform project.

This skill is intentionally strict. Its purpose is to keep shared UI declarative, state-driven, decomposed, adaptive, previewable, and cleanly separated from business logic and platform entry concerns.

## Primary goals

The UI design should optimize for:

- state-driven rendering
- clear composable decomposition
- business logic outside composables
- layout structure that remains understandable
- modifier usage that is intentional and explainable
- adaptive behavior across different window sizes
- previewability in common code
- clean separation between shared UI and platform-specific startup or OS integration

Do not treat Compose UI as just “screens and widgets”.
Treat it as part of app architecture.

## Official defaults to prefer

Unless the project has a strong reason not to, prefer:

- UI driven from immutable state
- state hoisting when multiple composables need to coordinate state
- side effects handled deliberately rather than embedded in rendering paths
- composables focused on rendering and interaction, not business rules
- shared UI in `commonMain` when valid across targets
- platform-specific UI bootstrapping and OS integration in platform source sets
- adaptive layouts driven by structured window-size reasoning
- previewable shared UI with `@Preview` where useful

---

## KMP project-structure expectations

Compose Multiplatform projects typically place shared Compose code in a shared source set such as `composeApp/src/commonMain/kotlin`, with platform-specific code in source sets such as `androidMain` and `iosMain`.

Review expectations:
- shared composables, shared UI state models, and reusable UI components belong in shared source sets when they are valid across targets
- platform entry points, startup wiring, and OS-specific integrations stay in platform-specific source sets
- UI architecture does not assume Android-only behavior as the default model for shared code

Flag as a concern when:
- shared UI is duplicated across targets without a real platform difference
- platform-specific assumptions leak into shared composables
- common UI code depends on platform-only APIs

## Review dimensions

### 1. State-driven UI

Compose guidance treats state management as foundational.

Check whether:
- UI renders from explicit immutable state
- rendering is driven by state instead of imperative mutation
- state ownership is clear
- composables are not reaching into dependencies ad hoc

Flag as a concern when:
- composables fetch or mutate state from many unrelated places
- UI is driven by scattered booleans rather than a coherent model
- rendering depends on hidden mutable state

### 2. State hoisting

Compose explicitly documents state hoisting as a core design tool.

Check whether:
- state is hoisted when multiple composables need to coordinate it
- local state remains local when it is truly local
- app- or screen-level state is not trapped in leaf composables
- callbacks and state ownership are easy to trace

Flag as a concern when:
- sibling composables coordinate through hidden shared state
- screen-critical state lives in leaf components
- hoisting is skipped and duplication appears across the tree

### 3. State lifespans and saveability

Compose docs distinguish state lifespans and saving UI state.

Check whether:
- ephemeral UI state is distinguished from longer-lived screen state
- saveable UI state is used intentionally where needed
- business-critical state is not confused with short-lived widget state

**KMP platform note:** `rememberSaveable` with `Bundle`-based serialization is Android-specific. On iOS, desktop, and web KMP targets, the state-restoration mechanism differs or may not exist in the same form. If a project expects state restoration across process recreation on non-Android targets, verify that the chosen state-persistence mechanism is platform-appropriate rather than assuming `rememberSaveable` behavior is universal.

Flag as a concern when:
- important UI state is lost unnecessarily on any target
- trivial widget state is elevated too far
- long-lived state is modeled as disposable local widget state
- `rememberSaveable` with Android-specific serializers is used in shared code without a non-Android fallback

### 4. Side-effect discipline

Compose explicitly treats side effects as a distinct design concern.

Check whether:
- side effects are isolated and deliberate
- recomposition does not accidentally retrigger important actions
- navigation, analytics, toasts, and one-off actions are not embedded casually in rendering branches
- rendering stays as pure as practical

Flag as a concern when:
- side effects are triggered directly from unstable composable paths
- repeated recomposition can duplicate work
- business or navigation effects are hard to reason about

### 5. UI architecture and layering

Android’s Compose docs place UI architecture, architectural layering, and navigation alongside state guidance.

Check whether:
- composables focus on rendering and interaction
- state holders or orchestration layers sit between UI and lower layers
- business logic is not embedded in UI
- UI models are shaped for presentation instead of mirroring raw transport/data models

Flag as a concern when:
- UI calls repositories directly
- DTOs or persistence models reach rendering code directly
- screen composables own orchestration, data parsing, and business rules inline

### 6. Composable decomposition

Check whether:
- large screens are broken into meaningful subcomposables
- reusable UI pieces are extracted where repetition exists
- component boundaries are understandable
- destination-level composables do not become monoliths

Flag as a concern when:
- one screen file owns all structure, state, and rendering inline
- reusable visual patterns are duplicated broadly
- composable boundaries do not reflect ownership clearly

### 7. Layout structure

Kotlin’s layout docs identify Rows, Columns, Boxes, lazy lists, and related primitives as the main layout building blocks.

Check whether:
- layout structure is readable
- containers are chosen intentionally
- layout nesting is not excessive without reason
- lists and grids are used where scrolling collections exist
- app shells and screen bodies remain understandable under growth

Flag as a concern when:
- layout structure is overly tangled
- wrong container choices create brittle UI
- scrolling/content structure is improvised instead of modeled clearly

### 8. Modifier discipline

Modifier order and combination matter.

Check whether:
- modifier chains are readable
- modifier order is intentional
- sizing, padding, click handling, offsets, scrolling, and semantics are combined in a way that is explainable
- adaptive or interaction behavior is not hidden in long opaque chains

Flag as a concern when:
- modifier order causes accidental behavior
- layout understanding depends on trial and error
- giant modifier chains obscure ownership and behavior

### 9. Adaptive layout readiness

Kotlin's adaptive-layout docs support window-size-driven adaptation.

This skill reviews whether shared UI is *structurally ready* for adaptive behavior — composed cleanly enough that adaptive variations can be added without a full rewrite. For deep guidance covering canonical layouts, multi-window support, list-detail patterns, supporting-pane behavior, and the full adaptive review framework, use the `kotlin-ui-adaptive-resources` skill.

Check whether:
- shared UI can adapt between compact, medium, and expanded layouts where appropriate
- layout decisions can respond to window size rather than one fixed phone assumption
- screen structure can evolve into multi-pane or wider layouts without a rewrite
- adaptive concerns are considered early rather than retrofitted as an afterthought

Flag as a concern when:
- UI is designed only for one narrow window shape with no structural flexibility
- larger layouts just stretch phone UI without meaningful structural adaptation
- adaptation would require tearing apart the entire screen composable tree

### 10. Window-size-driven UI decisions

Adaptive docs provide structured size-class APIs (`rememberWindowAdaptiveInfo()`, `WindowWidthSizeClass`) for layout decisions. For full guidance on these APIs and canonical adaptive patterns, use the `kotlin-ui-adaptive-resources` skill. This section reviews structural readiness in shared UI.

Check whether:
- major shell/layout decisions can be expressed through structured window-size reasoning, not hardcoded dp breakpoints
- adaptive branching is centralized enough to stay maintainable — one place to read window size, not scattered per-screen
- width-driven changes preserve the same feature mental model across sizes

Flag as a concern when:
- arbitrary pixel/dp breakpoints are used instead of the structured size-class API
- adaptive behavior differs inconsistently from screen to screen without a reason
- layout branches are too fragmented to understand centrally
### 11. Previewability

Compose Multiplatform previews support `@Preview` in common code when configured with `ui-tooling-preview` in `commonMain`.

Check whether:
- reusable components are previewable independently
- screen states can be previewed without full app bootstrapping
- adaptive variants are previewed where useful
- previews are practical enough to support iteration

Flag as a concern when:
- every UI change requires running the app
- screens are too entangled to preview meaningfully
- key visual states are difficult to inspect in isolation

### 12. Accessibility and semantics

Compose UI quality should account for semantics and user interaction clarity.

Check whether:
- content structure is understandable for accessibility services (TalkBack on Android, VoiceOver on iOS via Compose Multiplatform semantics bridge)
- icon-only buttons and image-only interactive elements expose a `contentDescription`
- `clearAndSetSemantics` is used intentionally — it removes all child semantics, which silences descendant content for screen readers
- `semantics { }` merging behavior is explicit when composable groups are used as single logical units
- focus ordering makes sense when adaptive layout rearranges components spatially
- text contrast and touch target sizes remain acceptable across compact and expanded window states

Flag as a concern when:
- icon buttons or decorative-looking interactive elements have no `contentDescription`
- `clearAndSetSemantics {}` is applied broadly without auditing what it silences
- adaptive layout changes create focus traps or confusing reading order
- visible structure and semantic structure drift apart (e.g., a single logical card reads as multiple unrelated elements)
- semantics are omitted entirely from screens that change significantly with window size

### 13. Shared-vs-platform UI boundary

Check whether:
- shared composables remain platform-agnostic
- platform-specific startup, window integration, and OS hooks remain outside shared screen code
- the common UI layer does not own Android/iOS entry responsibilities

Flag as a concern when:
- platform bootstrapping logic leaks into `commonMain`
- shared UI depends on platform-specific APIs
- platform-specific visuals or lifecycle details shape common UI unnecessarily

### 14. Testability

UI architecture should support testing and inspection.

Check whether:
- shared UI behavior can be validated in common tests where appropriate
- composables are decomposed enough to test behavior or semantics
- state-holder behavior is validated outside UI when possible
- previewability and testability reinforce each other

Flag as a concern when:
- UI correctness can only be validated through full manual runs
- composables are too monolithic to test meaningfully
- state behavior is only inferred through large end-to-end paths

---

## Severity framework

### High severity
Likely to cause architectural drift or broken UI behavior.

Examples:
- business logic embedded in composables
- no clear state owner
- side effects triggered from unstable rendering paths
- platform-only APIs in shared UI
- UI built only for one narrow window size

### Medium severity
Workable, but likely to create maintenance cost.

Examples:
- weak state hoisting
- oversized screen composables
- tangled layout structure
- modifier order causing brittle behavior
- adaptive behavior present but inconsistent
- poor previewability

### Low severity
Structurally acceptable but worth improving.

Examples:
- composable boundaries could be cleaner
- preview coverage could be broader
- modifier chains could be simplified
- adaptive branches could be centralized more clearly

---

## Required output format

When performing the review, respond with:

1. **UI summary**
   - state model
   - composable structure
   - layout/modifier approach
   - adaptive strategy
   - preview strategy
   - shared/platform boundary

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - state-driven UI
   - state hoisting
   - state lifespans/saveability
   - side effects
   - UI architecture/layering
   - composable decomposition
   - layout structure
   - modifier discipline
   - adaptive readiness
   - window-size-driven decisions
   - previewability
   - accessibility/semantics
   - shared-vs-platform boundary
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact restructuring steps
   - what state should be hoisted
   - what should move out of composables
   - how layout/modifier structure should be simplified
   - where adaptive branches should live
   - what should become previewable/testable

6. **Suggested target structure**
   - proposed screen / component / state / preview split if useful

7. **Open risks**
   - migration cost
   - visual/regression risk
   - platform-specific constraints still to validate

---

## Tone

Be direct and practical.
Do not praise UI just because it renders.
If the design is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- business logic in composables
- hidden mutable state driving rendering
- poor or absent state hoisting
- side effects triggered from unstable composable paths
- giant screen composables
- long opaque modifier chains
- layouts designed for one phone size only
- shared UI coupled to platform-specific APIs
- UI that cannot be previewed meaningfully
- tests forced to validate simple UI behavior only through large integration paths

---

## References

- Jetpack Compose documentation: https://developer.android.com/develop/ui/compose/documentation
- Compose Multiplatform: Create your first app: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html
- Compose Multiplatform: Layout basics: https://kotlinlang.org/docs/multiplatform/compose-layout.html
- Compose Multiplatform: Modifiers: https://kotlinlang.org/docs/multiplatform/compose-layout-modifiers.html
- Compose Multiplatform: Adaptive layouts: https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html
- Compose Multiplatform: Previews: https://kotlinlang.org/docs/multiplatform/compose-previews.html
- Android: Compose state and Jetpack: https://developer.android.com/develop/ui/compose/state
- Android: Accessibility in Compose: https://developer.android.com/develop/ui/compose/accessibility