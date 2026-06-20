---
name: kotlin-project-feature-implementation
description: Use when implementing or extending a feature in a Kotlin Multiplatform project. Provides pre-coding inspection, KMP source-set discipline, state pipeline design, architectural defaults, security/performance guardrails, and implementation rules. Forward-looking only — not a review skill.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "3.0.0"
---

# Kotlin Multiplatform Feature Implementation

Use this skill when **implementing** a new feature or extending an existing flow in a Kotlin Multiplatform project.

This skill is forward-looking only. It is not a review skill. It does not produce verdicts or issue severity ratings. For post-implementation or PR review, use `kotlin-project-architecture-review` instead.

Your job is to deliver production-grade code that fits the existing architecture, respects the shared UI system, preserves module boundaries, and remains easy to maintain over time.

Do not optimize for speed of output alone.  
Optimize for correctness, scalability, maintainability, security, consistency, and clean evolution of the existing codebase.

---

## What this skill does

- Provides a pre-coding inspection checklist to read the codebase before writing anything
- States the architectural defaults to implement against
- Gives layer-by-layer implementation rules
- Defines KMP source-set placement discipline
- Defines state pipeline and data ownership expectations
- Enforces shared UI system usage
- Adds performance, coroutine, concurrency, and security guardrails
- Defines the expected output format for an implementation plan

---

## Primary goals

For every implementation, optimize for:

1. **Architectural consistency**
2. **Small, coherent diffs**
3. **Business logic in the right layer**
4. **Strong separation of concerns**
5. **Model and boundary integrity**
6. **Predictable state management**
7. **Shared UI system consistency**
8. **Composable, reusable design**
9. **Compose performance**
10. **Coroutine and threading correctness**
11. **Concurrency and race-condition safety**
12. **Security and privacy safety**
13. **Source-of-truth discipline**
14. **Testability**
15. **Migration and rollout safety**
16. **Long-term maintainability**

---

## Default architecture assumptions

Unless the project clearly does otherwise, implement features using:

- UI driven from immutable state
- user events flowing into a state holder
- repositories owning data access and coordination
- domain layer only when business logic is complex, reused, or meaningfully reduces state-holder complexity
- shared logic in `commonMain` only when valid for all declared targets
- platform code at the edges
- narrow module APIs and cohesive feature ownership
- tests alongside new logic, not deferred

Do not invent new structure if the codebase already has a valid one. Match conventions unless there is a clear reason not to, and state that reason explicitly in the implementation plan.

---

## Implementation philosophy

Before writing code, understand how the existing app already solves similar problems.

Do not introduce parallel patterns unless the current pattern is clearly broken and the task explicitly requires changing it.

Default behavior:
- preserve existing architecture
- extend existing modules rather than creating shadow flows
- reuse existing components before creating new ones
- make the smallest coherent implementation that solves the task correctly
- keep business logic out of UI
- avoid unrelated refactors
- prefer explicit, readable code over clever abstractions

---

## Step 0: Read before writing

Inspect the relevant existing feature before writing any code. Identify:

1. **Module boundaries** — which modules own the feature area, and what their public APIs are
2. **Source-set placement** — what is in `commonMain` vs platform source sets; where new code belongs
3. **Route and navigation ownership** — where routes are defined, how new screens integrate
4. **State-holder pattern in use** — ViewModel, presenter, state machine; what the existing contract looks like
5. **Repository and data-source abstractions** — existing interfaces, implementations, source-of-truth rules, error model
6. **Domain layer presence and rationale** — whether it exists, whether it adds value, whether new logic belongs there
7. **Error-model conventions** — exceptions, result wrappers, sealed error types; what the project uses consistently
8. **Existing tests** — test locations, test doubles, patterns already established
9. **Shared UI system usage** — existing shared components, spacing tokens, typography patterns, strings/localization patterns
10. **Similar flows already implemented** — find the closest existing feature and copy the pattern, not just the visual result

Do not start coding before grounding the implementation in the current codebase.

---

## Required workflow

Follow this workflow for every feature unless explicitly told otherwise.

### Step 1: Inspect first
Before implementing:
- identify the feature/module involved
- identify navigation entry points
- identify existing presentation/state patterns
- identify domain/use case patterns
- identify repository/data/API boundaries
- identify existing shared UI components
- identify similar features/screens already implemented
- identify whether the change belongs in shared code or platform-specific code
- identify source-of-truth expectations
- identify security/trust-boundary implications if the feature touches auth, session, deep links, payments, files, web content, roles, or PII

### Step 2: Plan before editing
Before making changes, produce a short implementation plan that includes:
1. files to inspect
2. files likely to change
3. new files likely to be added
4. business logic placement
5. data ownership and source-of-truth decisions
6. state pipeline changes required
7. API/data implications
8. source-set placement decisions
9. risks and edge cases
10. tests that should be added

### Step 3: Implement the smallest coherent slice
Implement only what is required for the requested slice.

Prefer vertical slices such as:
- models + mapper + repository contract
- ViewModel/state changes
- UI rendering for known state
- API integration for one path
- loading/error handling
- one interaction flow at a time

Avoid broad end-to-end rewrites unless explicitly requested.

### Step 4: Self-check before finishing
Before considering the task done, check for:
- architecture drift
- large ViewModels
- logic in the wrong layer
- DTO leakage across boundaries
- ambiguous or contradictory state
- shared UI system misuse
- hardcoded strings
- Compose recomposition risks
- coroutine/threading issues
- cancellation and exception handling issues
- race conditions or duplicate-submission risks
- source-of-truth confusion
- security/privacy issues
- missing tests

---

## Layer-by-layer implementation rules

### UI layer

- Render from immutable `UiState`
- No ad hoc dependency access in composables
- No repository or data-source calls from UI code
- No business rules in composables
- No DTOs or persistence models in `UiState`
- No platform-specific APIs in shared composables
- Handle all reachable states explicitly: loading, success, empty, error, partial-data, retry
- Handle responsive layout needs at this layer, not in lower layers
- Prefer stateless rendering composables where possible
- Keep one-time effects separate from persistent UI state

#### Shared UI system rules

The feature must follow the existing project UI system.

Use and prefer:
- shared UI components already in the codebase
- layout abstractions when they already exist and fit
- spacing tokens instead of ad hoc spacing values
- shared typography and theme styles
- project-approved color and styling patterns
- existing shared primitives before creating new ones

Avoid:
- hardcoded spacing/dimensions when tokens already exist
- direct generic Compose primitives when a project abstraction already exists and fits
- duplicate UI patterns that should become shared components
- ad hoc styling inconsistent with the rest of the app

When a new reusable UI pattern is needed:
- extract it cleanly
- give it a meaningful name
- keep it focused
- place it in the right module/file

Do not over-abstract one-off UI fragments prematurely.

#### Strings and localization rules

Do not hardcode user-facing strings.

All product-facing text should:
- use resource-based strings according to project conventions
- follow the app’s default product tone and language unless the task says otherwise
- use parameterized resources where dynamic values are involved

This applies to:
- titles
- labels
- button text
- placeholders
- snackbars
- empty states
- error messages
- helper text
- accessibility text where relevant

---

### State holder

- Expose exactly one immutable observable state stream, or the single state output pattern the project uses
- Separate one-time effects from persistent `UiState`
- Consume user actions/events as inputs; do not let UI coordinate work directly
- Coordinate lower layers; do not own data-layer logic inline
- Do not become a god object
- Keep lifecycle/platform wiring out of shared state-holder logic unless the project explicitly puts it there

A state holder is allowed to:
- receive user intents
- call use cases/repositories
- coordinate screen state
- expose state/effects
- do lightweight mapping from domain results into UI state

A state holder should not grow into:
- a workflow engine
- a validator bag
- a mapper dumping ground
- a formatting layer
- an analytics monolith
- a place for unrelated helper functions

When complexity rises:
- extract mappers
- extract validators
- extract state transformers/reducers
- extract use cases/domain services
- split reusable logic into separate classes/files

**Pattern note:**  
On Android, ViewModel is the standard state holder and integrates with the lifecycle natively. On KMP targets without Android ViewModel, use the equivalent presenter or state-machine pattern the project has established. The shape must remain the same: one observable state output and separate effects regardless of the underlying implementation.

---

### Domain layer

Add a domain layer only when at least one of these is true:

- the business rule is reused by more than one state holder or flow
- the business rule is non-trivial and benefits from isolated testing
- extracting it makes the state holder meaningfully smaller and clearer
- the logic represents business concepts that should not live in the data or presentation layer

Do not add pass-through use cases to satisfy an architecture diagram.  
A use case that only forwards a repository call with no meaningful isolation is net-negative.

Business logic should primarily live here when it is:
- reusable
- non-trivial
- worth testing in isolation
- needed to keep the state holder focused

---

### Data layer

- Repositories expose domain-facing interfaces, not DTOs, not persistence schemas, not HTTP response shapes
- Repositories coordinate local and remote sources internally
- Callers should not see data-source coordination details
- DTOs and persistence models live below the repository boundary and do not cross it upward
- Preserve the project’s established error model consistently across new repositories
- New data sources must have a single, narrow responsibility
- Source-of-truth decisions must be explicit

Be clear about:
- where reads come from
- where writes go
- how cache/local/remote coordination works
- how refresh/invalidation works
- how optimistic updates reconcile, if applicable

Do not merge local and remote state ad hoc in the UI/state holder unless the codebase explicitly already does that and it is justified.

---

### Source sets

Before placing any new file in `commonMain`, confirm it is valid for all declared targets.

Decision order:
1. Does it compile and behave correctly on all targets with no platform-specific API? → `commonMain`
2. Is it valid for a platform family? → intermediate source set such as `appleMain` or `iosMain`
3. Does it genuinely differ per target? → platform source set with a shared abstraction in `commonMain` if needed

Do not default to `expect`/`actual` before checking whether an interface plus injected implementation would be simpler.

Additional rules:
- do not place feature logic inside bridge implementations
- do not place platform APIs in shared code
- prefer shared business logic, mapping, validation, and state logic when it truly applies to all targets
- prefer platform-specific placement for OS integrations, permissions, storage APIs, platform navigation adapters, native SDK interop, or target-specific lifecycle wiring

---

### Module boundaries

- Keep feature changes local to the owning module wherever possible
- Do not bypass module APIs for implementation convenience
- If a change requires touching many modules, treat that as a signal that boundaries may need review; flag it in the plan
- New public module APIs should be as narrow as needed and no wider
- Do not let one feature reach directly into another feature’s internal implementation details

---

### Navigation

- Follow the route model and entry-point patterns already established
- Do not re-architect navigation as part of a feature implementation unless explicitly scoped
- Preserve back/up behavior that matches user expectations
- Model deep-link entry realistically: the back stack after entry should be coherent, not empty
- Keep navigation decisions explicit and predictable
- Do not mix navigation events into persistent state

---

## Model and boundary rules

Each layer should speak in the right model type.

### Data layer models
Use:
- DTOs / transport models
- persistence entities
- remote/local data-source models

Do not leak these upward casually.

### Domain layer models
Use:
- business-oriented models
- use case input/output models
- validation/business concepts

Do not pollute domain models with:
- Compose/UI concerns
- persistence-only concerns
- transport-specific details

### Presentation layer models
Use:
- explicit UI state models
- screen-specific UI models when needed

Do not pass a single “god model” through all layers just to reduce mapping work.

### Mapping rules
Mapping responsibilities must be:
- explicit
- predictable
- easy to discover
- easy to test

If a screen requires a screen-specific projection, create the right UI model instead of overloading a domain model.

---

## State management rules

State must be predictable and testable.

### Prefer explicit state
Avoid ambiguous collections of booleans that create contradictory states.

Prefer:
- clear state data classes
- sealed sub-states when appropriate
- explicit fields with clear ownership
- state transformations that are easy to follow

### Separate durable state from transient effects
Do not mix:
- screen state
- navigation events
- snackbars/toasts
- permission requests
- one-time confirmations or one-time errors

Handle transient effects explicitly and safely.

### State ownership
A screen’s state should have clear ownership.
Do not let multiple unrelated async paths mutate shared state in ways that are hard to reason about.

### Avoid impossible states
Always ask:
- can this screen end up loading and success and blocking error at once?
- can stale data remain visible incorrectly?
- can a retried request corrupt the state?
- can multiple async updates interleave unpredictably?

---

## Data ownership decisions

For each new data type or flow, define:

- source of truth
- who owns reads
- who owns writes
- whether there is caching
- whether offline behavior matters
- how refresh/retry/invalidation works
- whether partial data is acceptable
- whether optimistic updates exist and how they reconcile

Do not create multiple writable sources of truth for the same data type unless this is clearly intentional and carefully coordinated.

---

## State pipeline design

For each feature flow, define:

- `UiState` shape
- user actions/events
- one-time effects
- loading → success path
- loading → error path
- retry path
- empty-state handling
- partial-data handling
- stale-response handling if multiple requests can overlap

State transitions should be explicit and easy to test.

---

## Compose implementation rules

### 1. Compose is for rendering, not heavy work
Do not do heavy or repeated work directly inside composables.

Avoid in composition:
- large filtering/sorting/mapping chains
- expensive derived calculations
- repeated object creation that could be stabilized
- broad state observation when a smaller slice is enough

Prefer:
- precomputed UI state
- smaller composables
- stable UI models
- `remember` only when justified
- `derivedStateOf` only when it materially helps
- state hoisting where appropriate

### 2. Recomposition discipline
Implement UI in ways that reduce unnecessary recomposition.

Watch for:
- unstable parameters
- lambdas recreated unnecessarily
- list items depending on overly broad parent state
- derived values recalculated every recomposition
- collecting state too high in the tree

### 3. Side-effects correctness
Use side-effect APIs intentionally.

Be careful with:
- `LaunchedEffect`
- `DisposableEffect`
- `SideEffect`
- `rememberCoroutineScope`
- `snapshotFlow`

Do not:
- launch work from composition without lifecycle reasoning
- use incorrect keys
- accidentally restart work
- capture stale values

### 4. Lazy list discipline
For lists:
- use stable keys where appropriate
- extract item content cleanly
- avoid expensive per-item computation in composition
- avoid re-rendering whole lists due to broad state coupling

---

## Coroutine and threading rules

### 1. Use the right dispatcher
Do not leave expensive work ambiguously on main thread.

Be explicit about:
- IO/network work
- database/persistence work
- CPU-heavy transformations
- testable dispatcher injection if the project pattern expects it

### 2. Respect structured concurrency
All async work should have clear ownership and lifecycle.

Avoid:
- detached jobs
- work that outlives the screen/feature scope unintentionally
- nested launches that obscure cancellation or sequencing

### 3. Preserve cancellation semantics
Do not accidentally swallow cancellation.

Be careful with:
- broad `catch`
- blanket `runCatching`
- generic failure wrappers that also catch `CancellationException`

Cancellation is not a normal failure and should usually propagate.

### 4. Error handling must be intentional
Do not silently fail.

Prefer:
- explicit error mapping
- clear UI failure states
- domain-level error modeling where useful
- observability for important failures

### 5. Flow usage must be intentional
Use the right abstraction:
- `Flow`
- `StateFlow`
- `SharedFlow`

Avoid:
- duplicate collectors without need
- replay misuse
- expensive transformations duplicated per collector
- collecting raw streams in UI when state should already be prepared upstream

---

## Concurrency and race-condition rules

Assume async interactions can race unless you deliberately prevent it.

Design flows to handle:
- repeated taps
- duplicate submissions
- stale responses arriving after newer ones
- retries
- refresh while another request is in flight
- partial failures
- concurrent state updates

Prefer:
- explicit coordination
- deterministic update rules
- idempotent or guarded submit behavior
- latest-wins or first-wins semantics chosen intentionally
- debounce/throttle where necessary

---

## Security and privacy rules

Treat trust boundaries seriously.

### Never assume UI gating is real authorization
Do not rely on hidden buttons or client-side checks as security.

### Handle sensitive data minimally
Avoid:
- exposing tokens unnecessarily
- passing PII through layers that do not need it
- logging sensitive values
- including sensitive data in analytics or crash reporting
- caching sensitive data casually

### Be defensive with external input
Be careful with:
- deep links
- URLs
- WebViews
- file/URI handling
- backend-provided text/data
- user-provided input
- route parameters

Validate and parse defensively.

### Session/auth safety
Consider:
- stale session state
- logout cleanup
- resume/re-entry paths
- expired auth
- privileged state left hanging after session changes

### Error exposure
Do not surface raw backend/internal errors directly to users unless explicitly appropriate.

---

## Dependency injection and construction rules

Use the project’s DI pattern consistently.

Avoid:
- ad hoc construction of important collaborators inside feature code
- hidden service locator patterns
- singleton everything
- stateful dependencies with overly broad scope

Prefer:
- explicit dependency injection
- scope aligned to ownership/lifecycle
- construction that is easy to test
- small, well-defined dependency surfaces

---

## Reusability and file organization rules

### Reuse before creating
Before introducing a new component/helper/mapper:
- check for an existing one
- extend or adapt existing patterns if appropriate

### Extract only real reuse
Extract reusable code when:
- duplication is real
- the abstraction has a clear name
- it improves maintainability

Do not create generic abstractions too early.

### Keep files focused
Prefer:
- one meaningful class/component/mapper/validator per file when appropriate
- discoverable organization
- focused files

Avoid:
- giant files with multiple unrelated responsibilities
- helper types buried inside unrelated files
- dumping many unrelated private utilities together

---

## Internal API design rules

Treat internal code as APIs for future maintainers.

Prefer:
- intention-revealing names
- narrow interfaces
- immutable public surfaces by default
- parameters that are hard to misuse
- cohesive responsibilities

Avoid:
- boolean parameter smells
- broad signatures
- APIs that force callers to know too much
- weak naming
- mutable public state unless truly needed

---

## Tests

Write tests alongside implementation, not after.

At minimum, test:
- state-holder transitions for the new flow
- loading → success
- loading → error
- retry
- empty/partial-data handling where relevant
- business rules in new domain use cases, if any
- repository coordination logic, mappers, and error-handling paths
- navigation decision logic for any conditional routing
- parsing/serialization or boundary mapping where important
- concurrency-sensitive behavior where multiple async paths interact

Use shared tests where logic is shared.  
Use platform-specific test infrastructure only where the code is actually platform-specific.

Do not defer meaningful tests unless explicitly told to.

---

## Observability rules

For important flows, make failures diagnosable.

Prefer:
- meaningful logs where the project expects them
- explicit failure handling
- useful diagnostics for critical paths
- privacy-safe logging and analytics

Avoid:
- silent failures
- vague catch-and-ignore code
- noisy logs with low value
- logs that expose sensitive data

---

## Backward compatibility and rollout rules

Implement code so it can survive:
- partial rollout
- evolving backend schemas
- missing fields
- unknown enum values
- partially available backend functionality
- migration-sensitive local persistence changes

Prefer:
- tolerant parsing where appropriate
- graceful degradation
- feature isolation
- rollout-safe assumptions

Do not assume all clients, servers, and data are updated simultaneously.

---

## Accessibility and UX robustness rules

Implement UI that behaves well under:
- slow network
- partial data
- empty states
- errors
- retries
- disabled states

Ensure:
- users get feedback for important actions
- retry/recovery paths exist when needed
- degraded states are understandable
- accessibility semantics are added where relevant and supported by the existing pattern

---

## KMP-specific rules

Because this is KMP, always consider:

### Common vs platform-specific placement
Prefer common code unless platform-specific behavior is actually required.

Do not introduce platform divergence casually.

### Portability
Avoid APIs or patterns that make shared code harder to port, test, or maintain.

### Cross-platform behavior consistency
Consider whether the implementation will behave consistently on iOS and Android.

### Cross-platform threading assumptions
Do not assume behavior that only makes sense for one target.

---

## Recommended feature structure

When the project has no established feature structure, this shape is a reasonable default:

```text
feature-<name>/
  presentation/
    <FeatureName>Screen.kt
    <FeatureName>ViewModel.kt
    <FeatureName>UiState.kt
    <FeatureName>UiAction.kt
    <FeatureName>UiEffect.kt
    <FeatureName>Route.kt
  domain/
    <FeatureName>UseCase.kt
    <FeatureName>Model.kt
  data/
    <FeatureName>Repository.kt
    <FeatureName>RepositoryImpl.kt
    <FeatureName>RemoteSource.kt
    <FeatureName>LocalSource.kt
    <FeatureName>Dto.kt
    <FeatureName>Mapper.kt
  di/
    <FeatureName>Module.kt
```

The folder shape is not the goal. Cohesive ownership and predictable placement are. Match existing structure unless it is clearly broken.

---

## Lint and static analysis expectations

Write code as if it must pass:
- ktlint
- detekt
- Android/Kotlin lint
- Compose-specific static analysis where relevant

Avoid:
- long methods
- long files
- high complexity
- deep nesting
- magic numbers
- dead code
- poor visibility choices
- nullable misuse
- misleading scope-function usage
- unused helpers/imports
- weak naming

---

## Anti-patterns to prevent

- starting to write code before reading the existing structure
- multiple writable sources of truth for the same data type
- direct repository or data-source access from composables
- pass-through use cases that add ceremony without isolation benefit
- DTOs or persistence models flowing into `UiState`
- platform-specific APIs in `commonMain`
- feature logic embedded in bridge implementations
- treating loading, error, empty, and partial-data states as afterthoughts
- inconsistent error modeling relative to the rest of the codebase
- new module APIs wider than the feature needs
- massive ViewModels
- business logic in composables
- broad state observation causing extra recomposition
- blocking work on main
- swallowing cancellation
- unstructured coroutine launches
- race conditions in submit/refresh flows
- duplicated UI patterns that should be components
- giant files with mixed responsibilities
- insecure token/session handling
- sensitive data in logs
- unclear source of truth
- brittle schema assumptions
- APIs that are easy to misuse
- hardcoded user-facing strings
- raw spacing/dimensions instead of design tokens where the system already provides them

---

## Required output format

When using this skill to guide implementation, produce:

1. **Pre-coding inspection summary**
   - modules and source sets affected
   - state-holder pattern in use
   - repository/data-source conventions observed
   - domain layer presence and rationale
   - error model in use
   - existing UI system patterns
   - existing test patterns

2. **Data ownership decisions**
   - source of truth for each new data type
   - who owns writes, who owns reads
   - offline/cache considerations if relevant

3. **State pipeline design**
   - `UiState` shape
   - user actions/events
   - one-time effects
   - loading → success
   - loading → error
   - retry
   - empty state
   - partial-data path if relevant

4. **Layer plan**
   - which files to create or modify
   - in which module
   - in which source set
   - what stays in shared code vs platform code
   - domain layer: yes/no and why

5. **Implementation**
   - code changes

6. **Tests to add**
   - state-holder transition tests
   - domain use case tests if applicable
   - repository/mapper tests
   - concurrency/boundary tests if needed
   - which test source set each test lives in

7. **Risks and edge cases**
   - source-set correctness risks
   - navigation edge cases
   - partial-data and error-path risks
   - concurrency/retry risks
   - security/trust-boundary risks
   - configuration/lifecycle edge cases

8. **Assumptions**
   - any architectural assumption made that is not directly verified in the codebase

If the task is clearly an editing task and not just analysis, keep the plan concise and then proceed with implementation.

---

## Default response structure

When asked to implement a feature, respond in this structure unless told otherwise:

1. **Pre-coding inspection summary**
2. **Data ownership decisions**
3. **State pipeline design**
4. **Layer plan**
5. **Risks and edge cases**
6. **Implementation**
7. **Tests added or recommended**
8. **Assumptions**

---

## Final instruction

Implement like an architect who will have to maintain this code for years.

Be strict about:
- correctness
- architecture boundaries
- scalability
- maintainability
- consistency
- performance
- security
- privacy
- testability
- rollout safety

Do not optimize for cleverness.  
Do not optimize for broad refactors.  
Optimize for clean, production-grade evolution of the existing codebase.

---

## References

- Android architecture recommendations — https://developer.android.com/topic/architecture/recommendations
- Android UI layer — https://developer.android.com/topic/architecture/ui-layer
- Android domain layer — https://developer.android.com/topic/architecture/domain-layer
- Android data layer — https://developer.android.com/topic/architecture/data-layer
- Android modularization — https://developer.android.com/topic/modularization
- Android navigation principles — https://developer.android.com/guide/navigation/principles
- Android configuration changes — https://developer.android.com/guide/topics/resources/runtime-changes
- Kotlin Multiplatform project structure — https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
- Compose Multiplatform — https://kotlinlang.org/docs/multiplatform/compose-multiplatform.html
- Navigation in Compose Multiplatform (Navigation 2, stable) — https://kotlinlang.org/docs/multiplatform/compose-navigation.html
- Navigation 3 in Compose Multiplatform (alpha as of mid-2025 — verify before adopting) — https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
