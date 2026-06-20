---
name: kotlin-navigation-compose-multiplatform
description: Use when designing, implementing, or reviewing navigation in Compose Multiplatform projects — route modeling, back stack ownership, argument passing, NavOptions, conditional flows, deep links, and adaptive navigation UI.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.2.0"
---

# Compose Multiplatform Navigation

Use this skill when designing, implementing, or reviewing navigation in a Compose Multiplatform project.

This skill is intentionally strict. Its purpose is to keep route modeling coherent, back stack behavior explicit, argument passing minimal, navigation side effects controlled, and multiplatform routing boundaries clean across Android and shared Compose code.

> **Navigation library scope**: This skill covers **Jetpack Navigation 2** and its Compose Multiplatform equivalent — the current stable navigation library. **Navigation 3** (Jetpack Navigation 3, a ground-up redesign) was in alpha as of mid-2025 and has a substantially different back-stack model, entry API, and lifecycle integration. If your project uses Navigation 3, verify which guidance applies before proceeding. Navigation 3 status: https://developer.android.com/guide/navigation/navigation3

## Primary goals

The navigation design should optimize for:

- clear route ownership
- explicit back stack ownership
- controlled navigation side effects
- minimal and stable argument passing
- predictable back-stack behavior
- conditional flows that preserve user context
- coherent deep-link handling
- separation between shared routing logic and platform entry concerns
- adaptive navigation chrome for different window sizes
- previewable and testable navigation-hosting UI

Do not treat navigation as only “go from screen A to screen B”.
Treat it as app-state movement with user-history consequences.

## Navigation library version note

This skill covers Compose Multiplatform Navigation 2 (current stable as of mid-2025), which is the `navigation-compose` integration used in most KMP projects today.

Jetpack Navigation 3 (a significant redesign with a different back-stack model built around `NavDisplay` and `NavEntry`) was in alpha as of mid-2025. If the project is using Navigation 3, some of this guidance — particularly around `NavController` ownership, `popUpTo`, and argument passing — applies differently. Verify against current Navigation 3 documentation before applying this skill to a Navigation 3 project.

## Navigation library version scope

This skill covers **Jetpack Navigation 2** (stable) as used in Compose Multiplatform, including the `compose-navigation` artifact and the `NavHost`/`NavController` model. **Navigation 3** (a separate alpha-stage redesign as of mid-2025) uses a fundamentally different back-stack model and is not covered here. If the project uses Navigation 3, verify that the patterns below still apply — some will, some will not. Check the [Navigation 3 documentation](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html) for current guidance.

## Official defaults to prefer

Unless the project has a strong reason not to, prefer:

- a single owner of `NavController` or equivalent navigation state
- event-based navigation from composables instead of passing `NavController` downward
- typed or otherwise structured routes
- minimal argument payloads, usually identifiers rather than complex objects
- explicit use of back-stack options like `popUpTo`, `inclusive`, `saveState`, `restoreState`, and `launchSingleTop`
- conditional navigation driven by shared state rather than duplicated guards
- deep-link patterns that are non-overlapping and predictable
- shared route interpretation in common code when valid across targets
- browser URL binding only at the platform/web edge

---

## Review dimensions

### 1. Route modeling

Check whether routes are modeled clearly and intentionally.

Prefer:
- route models that are explicit and typed where possible
- destination identity that is understandable from the code
- route definitions separated from screen implementation details

In Compose Multiplatform navigation, a route identifies a destination and defines the arguments required to navigate there, while staying separate from the UI implementation.

Flag as a concern when:
- routes are brittle string literals scattered through the app
- route parameters are implicit or weakly structured
- screen identity and navigation actions are hard to trace

### 2. NavController ownership and navigation events

Android explicitly recommends that composables expose navigation events rather than receiving a `NavController` reference directly.

Check whether:
- the navigation host or app shell owns the `NavController`
- lower composables expose callbacks like `onOpenDetails()` rather than calling `navigate()` directly
- `navigate()` is called from callbacks or controlled side effects, not from normal rendering paths

Flag as a concern when:
- `NavController` is passed deep into leaf composables
- leaf composables call `navigate()` directly
- navigation can be retriggered on recomposition

### 3. Back stack ownership

Navigation state should have a clear owner.

Check whether:
- the back stack is owned by one clear navigation state holder
- navigation mutations happen through a small number of controlled paths
- unrelated screens or helpers are not mutating navigation state ad hoc

Flag as a concern when:
- many layers can push and pop destinations freely
- back stack state is effectively global without clear ownership
- destination changes are triggered from rendering code without architectural control

### 4. Basic navigation policy

Android’s Navigation docs treat `navigate()` and `popBackStack()` as core app-history operations.

Check whether:
- forward navigation uses the right destination key or typed route
- back navigation uses `popBackStack()` or `navigateUp()` intentionally
- failed `popBackStack()` cases are handled safely instead of leaving the app in an invalid state

Flag as a concern when:
- back behavior is assumed rather than designed
- popping the last destination can leave a blank screen with no recovery path
- history behavior is inconsistent across similar flows

### 5. NavOptions and stack-shaping rules

Review the explicit use of navigation options.

Important options include:
- `popUpTo()`
- `inclusive`
- `saveState`
- `restoreState`
- `launchSingleTop`

Check whether:
- `popUpTo()` is used to shape history intentionally
- `inclusive` is used only when removing the target destination is correct
- `saveState` / `restoreState` are used where returning to preserved stacks matters
- `launchSingleTop` is used to avoid duplicate top destinations

Flag as a concern when:
- duplicate destinations accumulate unnecessarily
- history is rewritten accidentally
- stack state should be preserved but is discarded
- `popUpTo()` behavior is hard to reason about

### 6. Argument-passing discipline

Android recommends passing the minimum necessary information and avoiding complex object transfer through navigation arguments.

Check whether:
- arguments are small and stable
- identifiers are passed instead of whole UI/data models
- destinations load their own data from minimal navigation input
- route serialization stays understandable

Flag as a concern when:
- complex objects are passed through navigation
- route payloads become a substitute for state ownership
- process recreation would be fragile because arguments are oversized or inconsistent

### 7. Conditional navigation

Conditional navigation should be modeled through shared state and explicit transitions, not scattered checks.

Check whether:
- auth gates, onboarding gates, permission gates, or result-based flows are modeled through shared state
- returning from a conditional flow preserves user context
- prior back-stack entries or saved state are used intentionally where results must be returned

Flag as a concern when:
- login or gating logic is duplicated across many screens
- guarded destinations redirect in ways that lose user context unnecessarily
- success/failure results from intermediate flows are not modeled clearly

### 8. State hoisting and one-off effects

Navigation is a side effect and should be coordinated with hoisted state.

Check whether:
- navigation-related state is hoisted when multiple composables need to coordinate it
- persistent state is separated from one-time navigation effects
- side effects are not fired from unstable rendering branches

Flag as a concern when:
- navigation decisions are trapped inside leaf composables
- events and persistent UI state are mixed together carelessly
- recomposition can retrigger navigation

### 9. Deep links

Compose Multiplatform navigation supports destination deep links through `NavDeepLink` patterns.

Check whether:
- deep-link patterns are explicit and non-overlapping
- placeholders are used intentionally
- required path parameters and optional query parameters are modeled clearly
- the same URI pattern is not claimed by multiple destinations

Flag as a concern when:
- deep-link patterns intersect
- route matching is ambiguous
- deep-link handling depends on accidental ordering

### 10. Multiplatform routing and browser URL binding

On web, Compose Multiplatform can bind navigation state to browser history and URL fragments.

Check whether:
- browser URL binding happens at the web entry point via `bindToBrowserNavigation()` (this API is part of the Compose Multiplatform web-target navigation layer; verify its current stability status against the Compose Multiplatform release notes before treating it as stable API)
- route-to-URL translation is explicit where readability matters
- `@SerialName` or custom route mapping is used when default generated URLs are too implementation-heavy
- browser-specific concerns stay out of generic shared navigation design

Flag as a concern when:
- web URL policy is implicit
- browser-history binding is mixed into unrelated shared UI code
- generated URLs are treated as stable public contracts without review

### 11. Adaptive navigation UI

Navigation chrome should adapt to available space.

Check whether:
- bottom navigation, rail, drawer, or pane structures are chosen intentionally based on window size
- route state remains stable while shell chrome changes
- adaptive shell behavior does not duplicate destination logic

Flag as a concern when:
- phone navigation chrome is forced unchanged onto large layouts
- shell adaptation and route state are tightly tangled
- large-screen navigation is bolted on with special cases

### 12. Navigation host architecture

Check whether the navigation host and app shell are decomposed clearly.

Prefer:
- a navigation host or root shell responsible for app-level structure
- destination composables that focus on their own UI/state concerns
- shell chrome separated from destination implementation where practical

Flag as a concern when:
- one root composable owns all destinations, all shell UI, and all navigation logic inline
- host code becomes a monolith
- destination registration and shell layout are tightly tangled

### 13. Animated transitions

Android’s Navigation docs support configuring transitions between destinations, including enter, exit, pop-enter, and pop-exit transitions.

Check whether:
- transitions support the navigation model instead of obscuring it
- pop transitions reflect back-stack behavior coherently
- animation choices do not fight shared-element or other transition systems

Flag as a concern when:
- transition policy is inconsistent with navigation semantics
- transitions are added everywhere without improving UX clarity
- conflicting transition systems are mixed carelessly

### 14. Destination isolation

Check whether each destination remains a meaningful UI boundary.

Prefer:
- destination UI that owns its local rendering/state concerns
- dependencies passed through clear contracts
- destination screens that do not know excessive app-shell detail

Flag as a concern when:
- destinations depend directly on root-shell internals
- screens coordinate each other directly instead of going through navigation state
- navigation concerns dominate destination implementation

### 15. Testability

Navigation architecture should support validation without relying only on full app runs.

Check whether:
- route selection logic can be tested
- navigation state transitions can be tested
- back-stack shaping rules can be validated
- deep-link matching behavior can be tested separately
- destination UI can be previewed or tested in isolation

Flag as a concern when:
- navigation correctness can only be verified through manual app flows
- route transitions are deeply coupled to platform bootstrapping
- deep-link ambiguity is only discovered at runtime

---

## Severity framework

### High severity
Likely to cause broken navigation behavior or architectural drift.

Examples:
- no clear `NavController` owner
- `navigate()` called from composable rendering paths
- complex objects passed through navigation
- overlapping deep-link patterns
- uncontrolled back-stack mutation from many places

### Medium severity
Workable, but likely to create maintenance cost.

Examples:
- route modeling is too stringly typed
- shell and destination responsibilities are too tangled
- back-stack options are used inconsistently
- browser URL mapping is unclear
- adaptive navigation exists but is patchy

### Low severity
Structurally acceptable but worth improving.

Examples:
- route naming could be clearer
- transition policy could be more coherent
- shell composables could be split more cleanly

---

## Required output format

When performing the review, respond with:

1. **Navigation summary**
   - route model
   - `NavController` / back-stack ownership
   - argument strategy
   - stack-shaping policy
   - deep-link strategy
   - adaptive shell approach
   - platform/web boundary

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - route modeling
   - `NavController` ownership
   - back stack ownership
   - basic navigation policy
   - nav options / stack shaping
   - argument passing
   - conditional navigation
   - state hoisting / one-off effects
   - deep links
   - multiplatform routing / browser binding
   - adaptive navigation
   - navigation host architecture
   - animated transitions
   - destination isolation
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact restructuring steps
   - what state should be hoisted
   - what should stop receiving `NavController`
   - where to use `launchSingleTop` / `popUpTo` / `saveState`
   - how to reduce argument payloads
   - how to clarify deep-link and web-routing policy

6. **Suggested target structure**
   - proposed route model / shell / destination / deep-link split if useful

7. **Open risks**
   - migration cost
   - rollout concerns
   - platform-specific constraints still to validate

---

## Tone

Be direct and practical.
Do not praise navigation just because it works on one device or one happy path.
If the design is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- passing `NavController` deep into composables
- calling `navigate()` during composition
- stringly typed routes scattered across the codebase
- complex objects passed through navigation
- uncontrolled duplicate destinations on top of the stack
- auth/onboarding guards duplicated across many screens
- overlapping deep-link patterns
- oversized root navigation shells
- browser-routing policy leaking into generic shared navigation code

---

## References

- Android: Navigate to a destination: https://developer.android.com/guide/navigation/use-graph/navigate
- Android: Navigate with options: https://developer.android.com/guide/navigation/use-graph/navoptions
- Android: Pass data between destinations: https://developer.android.com/guide/navigation/use-graph/pass-data
- Android: Animate transitions between destinations: https://developer.android.com/guide/navigation/use-graph/animate-transitions
- Android: Conditional navigation: https://developer.android.com/guide/navigation/use-graph/conditional
- Android: Navigation and the back stack: https://developer.android.com/guide/navigation/backstack
- Compose Multiplatform: Navigation and routing: https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html
- Compose Multiplatform: Deep links: https://kotlinlang.org/docs/multiplatform/compose-navigation-deep-links.html
- Navigation 3 (alpha — verify status before use): https://developer.android.com/guide/navigation/navigation3