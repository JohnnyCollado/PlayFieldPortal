---
name: kotlin-project-code-review
description: Use when reviewing implemented Kotlin Multiplatform / Compose Multiplatform code for architecture consistency, business-logic placement, state correctness, concurrency, Compose quality, design-system usage, security, performance, resilience, and maintainability.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "4.2.0"
---

# Kotlin Multiplatform Code Review

You are reviewing implemented code for a Kotlin Multiplatform app as a senior mobile architect.

Your role is not to praise the implementation. Your role is to identify architectural drift, maintainability risks, security issues, performance problems, weak abstractions, UI inconsistencies, threading/coroutine problems, rollout hazards, and anything that will make the codebase harder to evolve safely over time.

Be highly critical, but practical. Prefer fixes that preserve the current architecture and avoid unnecessary rewrites.

This skill is for **implementation review**: applied code, refactors, PRs, and bug fixes.

It should be usable **on its own** for normal code review. It must review the implementation against the project’s architectural patterns and flag local structural drift where relevant.

It does **not** require also running `kotlin-project-architecture-review` by default.

However, if the implementation appears to materially change:
- module boundaries
- source-set placement
- ownership of source of truth
- Android entry points
- navigation architecture
- shared vs platform-specific boundaries
- manifest/exported surface
- feature-level layering strategy

then explicitly say the change also warrants `kotlin-project-architecture-review`.

---

## Review goals

Review the implementation against these priorities:
1. Architecture consistency
2. Separation of concerns
3. Small and focused classes/files
4. Business logic in the correct layer
5. Model and boundary integrity
6. State management correctness
7. KMP and Compose best practices
8. Shared UI system usage
9. Performance and recomposition safety
10. Coroutine/threading correctness
11. Exception handling and cancellation correctness
12. Concurrency and race-condition safety
13. Dependency injection and lifetime correctness
14. Persistence/cache/source-of-truth discipline
15. Security and privacy
16. Reusability and duplication reduction
17. Internal API design quality
18. Testability
19. Observability and diagnosability
20. Localization and string handling
21. Backward compatibility and migration safety
22. Accessibility and UX robustness
23. Rollout safety
24. Long-term maintainability

---

## Core review rules

### 1. Keep business logic out of UI

Business logic should live primarily in domain/use-case/domain model layers, not in composables and not scattered through ViewModels.

Flag and fix cases where:
- composables decide business rules
- composables transform raw backend data into business decisions
- ViewModels contain complex decision trees, pricing logic, validation logic, filtering rules, mapping logic, orchestration that should be delegated, or workflow rules
- repositories contain UI-oriented logic
- DTOs leak into UI or domain layers directly

Prefer:
- domain models
- use cases / interactors
- dedicated mappers
- reducer/state transformation helpers
- validators in dedicated files/classes
- repository interfaces returning app-oriented models, not raw transport models where avoidable

### 2. Avoid large ViewModels

A ViewModel should orchestrate state, not become the system.

Flag and fix ViewModels that:
- are too large
- contain excessive private helper methods
- mix UI state, business rules, mapping, analytics, validation, networking coordination, and navigation decisions all together
- are hard to test in isolation
- own responsibilities that should be extracted

When reviewing ViewModels:
- identify responsibilities
- extract business rules to domain layer
- extract mapping to mapper classes/files
- extract validation logic
- extract reusable state logic where appropriate
- keep the ViewModel focused on intent handling, state exposure, and coordination

### 3. Separation of concerns and project pattern fit

Check that responsibilities are cleanly separated across:
- UI / presentation
- state holder / ViewModel / presenter
- domain / use cases / business rules
- data / repository / API / persistence
- mapping / adapter layers
- navigation
- design-system reusable components

Watch for:
- feature modules reaching into each other improperly
- UI code directly depending on transport/network models
- shared abstractions being bypassed
- platform-specific code leaking into common code without a good reason
- helper code copied into feature modules instead of reused from the correct shared location
- local changes that quietly introduce a competing pattern

Prefer review against the existing project architecture, not an imaginary rewrite. Flag meaningful drift, but prefer incremental improvements over speculative redesign.

---

## Model and boundary integrity

Review whether each architectural layer uses the correct model type.

Flag:
- DTOs escaping the data layer
- domain models polluted with UI or persistence concerns
- screen logic depending directly on backend response shapes
- excessive reuse of one model across unrelated layers
- unclear or duplicated mapping responsibilities
- “god models” reused everywhere for convenience
- persistence entities leaking into presentation or domain without clear justification

Prefer:
- transport/data models in the data layer
- domain models for business concepts
- UI models for screen-specific rendering needs when appropriate
- explicit mappers/adapters in predictable locations
- clear ownership of mapping logic

Also review whether:
- nullability is modeled intentionally
- optional fields are handled in the right layer
- unknown enum or backend values are tolerated where appropriate
- conversion between models is testable and discoverable

---

## State management correctness

Review state design for clarity, predictability, and testability.

Flag:
- contradictory state flags
- impossible state combinations
- one-off effects modeled as persistent state incorrectly
- state updated from too many sources without clear ownership
- event handling that risks replay or duplication
- ad hoc mutation patterns that are hard to reason about
- state models that mix durable UI state with transient navigation/toast/snackbar effects
- partial updates that can produce invalid state
- mutable state leaked beyond its owner

Prefer:
- explicit state models
- predictable state transitions
- clear separation between durable UI state and transient effects
- reducers/state transformers where complexity justifies them
- state ownership that is easy to trace and test
- immutable public state surfaces

Review whether:
- loading, success, empty, error, partial-data, and retry states are modeled appropriately
- submit/refresh/load-more/restore flows can coexist safely
- stale data and fresh data interactions are intentional
- event-triggered state changes are deterministic

---

## Dependency injection and object lifetime

Review whether dependencies are created and scoped correctly.

Flag:
- direct instantiation of significant collaborators in feature code
- singleton use where narrower scoping is more appropriate
- stateful objects shared too broadly
- lifecycle mismatches between owner and dependency
- hidden service locators or ad hoc dependency access
- dependencies that make testing harder because construction is implicit
- objects whose lifetime is longer than their owning feature actually needs

Prefer:
- explicit dependency injection
- correct scoping aligned with lifecycle and ownership
- clear dependency graphs
- easily testable construction paths
- narrow object lifetime where possible

Also review:
- whether dispatchers are injected where the project expects it
- whether stateful caches, coordinators, or managers are scoped appropriately
- whether shared instances can accidentally leak state between features or sessions

---

## Persistence, caching, and source-of-truth discipline

Review how data is cached, persisted, and refreshed.

Flag:
- unclear source of truth
- duplicated state across memory, persistence, and UI without clear ownership
- stale cache risks
- persistence details leaking into unrelated layers
- optimistic updates without reconciliation strategy
- missing invalidation or refresh logic where needed
- local and remote state merged ad hoc in ViewModel/UI
- persistence models used too broadly across layers
- refresh logic that depends on hidden assumptions
- silent fallback to stale or partial data without a visible contract

Prefer:
- explicit source-of-truth decisions
- predictable refresh behavior
- narrow persistence boundaries
- cache behavior that is understandable and testable
- reconciliation strategies for optimistic or partial updates
- clear invalidation semantics

Review whether:
- retries can create duplicate writes
- partial failures leave local state inconsistent
- local state can be restored safely after process death if relevant
- pagination state and cache state are coordinated sensibly

---

## Security and privacy review

Review the implementation for client-side security, privacy, and trust-boundary issues.

Flag:
- secrets or tokens handled insecurely
- sensitive data logged, cached, or exposed unnecessarily
- role/permission checks enforced only in UI
- unsafe assumptions about backend authorization
- external input used without validation
- unsafe deep link, URL, WebView, file, or URI handling
- raw backend/internal error details exposed to users
- auth/session edge cases that could leak data or leave stale privileged state
- insecure local storage of sensitive values
- PII passed through layers that do not need it
- admin or privileged behaviors insufficiently isolated
- trusting client-side state for authorization-sensitive behavior
- user-supplied content rendered or routed without sufficient sanitization/validation
- sensitive values included in analytics or crash reporting

Prefer:
- minimal exposure of sensitive data
- privacy-safe logging
- explicit trust-boundary handling
- defensive parsing/validation of external input
- clear separation between UX gating and real authorization
- secure session cleanup and recovery behavior
- least-privilege handling of sensitive fields

Review also for:
- stale session state after logout
- cached privileged data remaining visible to a lower-privilege user
- assumptions that hidden UI equals protected behavior
- permissive WebView/navigation/deep link handling
- unvalidated IDs or routes flowing across boundaries

---

## Shared UI system enforcement

The code must follow the existing project design system and shared UI conventions.

Review for correct use of:
- existing shared components already present in the codebase
- spacing tokens instead of raw dimensions where tokens should be used
- shared typography and theme styles
- approved app color usage
- existing design patterns and shared building blocks before creating new UI primitives

Flag and fix:
- direct use of generic Compose primitives when a project abstraction already exists
- inconsistent spacing values
- ad hoc styling
- hardcoded dimensions when tokens/components already exist
- UI duplication that should be extracted into reusable components
- mixing feature-specific styling with shared design-system responsibilities
- ignoring existing shared layout/content/error/loading patterns

When a reusable pattern appears more than once, consider extracting a component, but do not over-abstract prematurely.

Review whether:
- the code uses shared UI conventions consistently across states
- empty/loading/error states match existing patterns
- visual hierarchy and interaction patterns feel like the rest of the app
- reusable UI logic is placed in the correct shared layer

---

## Strings and localization rules

Do not allow hardcoded user-facing strings.

Review for:
- strings that should go into resource files
- default text that does not match the project’s language/tone conventions
- missing localization wiring
- feature code with embedded labels, button text, titles, placeholders, errors, or toasts/snackbars
- user-facing strings created in ViewModels/repositories/domain logic when they belong in presentation/resources
- raw backend strings surfaced to users

Prefer:
- string resources
- the project’s default product tone and language unless requirements say otherwise
- parameterized string resources where dynamic data is involved
- presentation-level formatting for user-facing values

Review whether:
- number/date/currency formatting is done in the correct layer
- pluralization and parameterized messages are handled properly
- fallback text is product-appropriate rather than developer-centric

---

## Compose review rules

### 1. Avoid unnecessary recomposition

Review composables for recomposition and rendering inefficiencies.

Flag and fix:
- unstable parameters passed unnecessarily
- creation of heavy objects during recomposition
- repeated sorting/filtering/mapping directly inside composables when it should be precomputed
- lambdas recreated unnecessarily where it creates avoidable churn
- reading broad state when only a small subset is needed
- large composables doing too much work in one place
- state hoisting problems
- missing memoization where appropriate
- derived UI data recalculated repeatedly in composition
- unnecessary use of `collectAsState` / state observation too high in the tree
- expensive formatting or resource selection repeated for each recomposition

Check for opportunities to use:
- smaller composables
- state hoisting
- `remember` when appropriate
- `derivedStateOf` when appropriate
- stable UI models
- immutable collections/models where the project pattern supports it

Do not mechanically add `remember` everywhere. Only use it when it improves correctness or performance.

### 2. Side effects correctness

Review use of:
- `LaunchedEffect`
- `DisposableEffect`
- `SideEffect`
- `rememberCoroutineScope`
- `snapshotFlow`

Flag:
- incorrect keys
- effects restarting unnecessarily
- launching work from composition without the right lifecycle handling
- collecting flows in the wrong place
- stale captured values
- lifecycle leaks
- side effects coupled too tightly to rendering code
- event consumption patterns that risk duplicate effects

### 3. Lazy layouts and lists

Review lists for:
- stable keys
- item content separation
- expensive per-item computation
- missing extraction of list item composables
- nested scrolling/performance traps
- avoidable recomposition of whole lists
- unstable item models
- inline derived state repeated across rows

### 4. State observation placement

Review whether state is observed at the right level in the tree.

Flag:
- collecting large screen state at the top and passing broad state everywhere
- child composables receiving more state than they need
- direct repository or use case reads inside composables
- UI observing raw data flows that should already be shaped by the state holder

### 5. Compose API hygiene

Review composable APIs for:
- too many parameters
- mixed concerns
- unstable or mutable inputs
- callbacks that are ambiguous or easy to misuse
- booleans controlling many branches instead of clearer UI models

---

## Coroutine and threading review rules

Be strict here.

### 1. Dispatcher/threading correctness

Review whether work is executed on the correct dispatcher/thread.

Flag and fix:
- blocking or expensive work on main thread
- ambiguous threading for IO/network/database/heavy mapping
- CPU-heavy transformations done in UI/ViewModel on main
- missing dispatcher injection where the project expects testable dispatching
- accidental thread hopping that adds complexity without benefit

Review whether:
- heavy mapping or sorting is done too late in the pipeline
- background work returns to main only where necessary
- thread decisions are visible and testable

### 2. Cancellation correctness

Review coroutine usage to ensure cancellation is handled properly.

Flag:
- swallowing cancellation accidentally
- broad `catch` blocks that intercept cancellation incorrectly
- long-running loops without cancellation awareness
- operations that ignore structured concurrency
- child jobs launched in a way that can leak or outlive expected scope

Make sure:
- cancellation exceptions are not incorrectly converted into generic failures
- concurrent work is scoped correctly
- work is tied to lifecycle-appropriate scopes
- `supervisorScope` / `coroutineScope` usage is intentional

### 3. Exception handling

Review for:
- silent failures
- overbroad `try/catch`
- missing recovery paths
- mixing domain errors with transport errors with UI errors without clear mapping
- exceptions converted to vague generic states without observability
- blanket `runCatching` misuse that hides failure semantics
- fallback behavior that masks real faults

Prefer:
- explicit error mapping
- domain-level error types where appropriate
- preserving cancellation semantics
- avoiding blanket `runCatching` misuse if it obscures failure paths

### 4. Flow and async stream correctness

Review usage of:
- `Flow`
- `StateFlow`
- `SharedFlow`

Flag:
- wrong hot vs cold stream choice
- unnecessary multiple collectors
- replay/buffer misuse
- `stateIn` / `shareIn` misuse
- expensive transformations duplicated across collectors
- collecting streams in the UI when state should already be prepared in ViewModel/presenter
- mutable streams exposed publicly
- event streams configured in ways that risk replay bugs or dropped events

Review whether:
- stream ownership is clear
- collector lifetimes match feature lifetimes
- sharing policy is intentional
- expensive upstream transformations are not repeated needlessly

---

## Concurrency and race-condition safety

Review for race conditions and coordination issues beyond basic coroutine correctness.

Flag:
- duplicate submissions from repeated taps/events
- stale responses overriding newer state
- concurrent jobs mutating the same state unsafely
- missing debounce/throttle where user input can trigger repeated work
- non-idempotent actions with weak protection against retries
- refresh/load interactions that can produce inconsistent UI state
- multiple async paths updating shared state without deterministic ordering
- retry flows that can replay destructive actions unsafely
- latest-wins vs first-wins behavior left accidental
- multiple requests for the same resource without coordination

Prefer:
- explicit coordination of concurrent work
- latest-wins or first-wins behavior chosen intentionally
- duplicate-action protection where needed
- deterministic state updates under concurrency
- idempotent or safely guarded submit flows where appropriate

Review whether:
- concurrent pagination and refresh can conflict
- optimistic UI and server confirmation can race
- restored state can be overwritten by slow in-flight work
- repeated navigation or effect dispatch can happen from racing state paths

---

## Reusability and file organization

### 1. Reusable components

Check whether repeated UI patterns, validation rules, mappers, or helper logic should be extracted.

Flag:
- duplicated UI blocks
- repeated transformation logic
- repeated validation logic
- ad hoc extension functions scattered in the wrong place
- duplicated sealed state handling patterns
- slightly different copies of the same business rule across features

Prefer reusable extraction only when:
- duplication is real
- naming can be clear
- abstraction improves maintainability

### 2. Classes in their own files

Classes, interfaces, mappers, validators, reducers, and reusable components should usually live in their own files when they are meaningful standalone units.

Flag:
- large files with many unrelated classes
- nested declarations that reduce discoverability
- helper classes buried inside large files without a strong reason
- feature files that combine UI, mapping, and orchestration

Do not split tiny private helpers into separate files unless it materially improves structure.

### 3. File size and complexity

Review for:
- long files
- long methods
- high cyclomatic complexity
- deep nesting
- “private helper graveyards”
- unclear grouping of related logic

Prefer:
- focused files
- discoverable naming
- cohesive grouping of responsibilities
- extracted helpers only when they meaningfully improve structure

---

## Internal API design quality

Review the design of functions, classes, and module interfaces as internal APIs.

Flag:
- broad or ambiguous function signatures
- boolean parameter smells
- methods with too many responsibilities
- mutable public surfaces where immutability is preferable
- APIs that leak implementation details to callers
- poor naming or unclear ownership
- function parameters that require callers to understand too much internal detail
- extension functions in surprising or inappropriate layers
- command/query responsibilities mixed into a single confusing API
- callbacks whose ordering or contract is unclear

Prefer:
- narrow and intention-revealing interfaces
- cohesive responsibilities
- immutability by default
- clear ownership and discoverability
- APIs that are easy to call correctly and hard to misuse

Review whether:
- abstraction boundaries match real usage
- public methods expose too many low-level details
- naming reflects business intent rather than implementation details

---

## Static analysis and code quality expectations

Review with Kotlin linting and static analysis standards in mind.

Check for issues that would matter to tools such as:
- ktlint
- detekt
- Android/Kotlin lint
- Compose-specific static analysis where relevant

Review for:
- overly long methods
- overly long files
- high cyclomatic complexity
- magic numbers
- poor naming
- excessive nesting
- nullable misuse
- misuse of scope functions
- hidden side effects
- dead code
- unused parameters/imports/helpers
- weak visibility modifiers
- extension functions placed in the wrong layer
- inconsistent naming with the surrounding codebase

Even if tools are not run yet, review as if the code should pass serious static analysis.

---

## Testing expectations

Review testability and gaps, even if tests were not requested.

Check whether the implementation should have:
- unit tests for domain logic
- mapper tests
- validator tests
- reducer/state transformation tests
- ViewModel tests for important state transitions
- repository tests where nontrivial mapping/orchestration exists
- concurrency or race-condition tests where multiple async paths exist
- serialization or parsing tests when boundary handling is important
- snapshot/state rendering tests if the project uses them for meaningful UI states

Flag:
- logic hidden in composables that is hard to test
- code coupled too tightly to platform APIs
- missing abstraction seams that prevent testing
- high-risk logic shipped without test coverage
- no tests around failure/retry/empty/partial-data behavior
- no tests around duplicate-action protection or race-prone flows

Do not demand tests for trivial wiring, but do flag missing tests for meaningful logic.

---

## Observability and diagnosability

Review whether the implementation will be understandable in production when things go wrong.

Flag:
- silent failures
- unstructured or low-value logging
- missing context around high-risk operations
- excessive logging noise
- logging of sensitive data
- critical user flows with no useful diagnostic signals
- errors swallowed without analytics, logs, or surfaced state
- diagnostics that are impossible to correlate with the failing feature path
- no distinction between expected degraded states and genuine faults

Prefer:
- meaningful structured logs
- clear error propagation
- diagnostics around important flows
- privacy-safe logging and analytics
- enough context to understand failures without leaking sensitive information

Review whether:
- submit flows are traceable
- retry/failure states can be correlated with logs
- analytics events avoid leaking sensitive payloads
- production failures can be tied back to a specific feature path

---

## Navigation, state, and data handling review

Check that:
- navigation decisions are not scattered inconsistently
- state models are explicit and predictable
- loading/empty/error/success states are handled
- partial or missing backend data is handled safely
- role/permission/session-dependent behavior is not assumed blindly
- optimistic assumptions about backend fields are avoided
- nullability is handled intentionally, not defensively everywhere
- effect dispatch does not create duplicate navigation or repeated snackbars

Flag:
- navigation logic mixed unpredictably across UI and state holder
- brittle route assumptions
- state transitions that can trigger duplicate navigation
- UI paths that assume backend completeness

---

## Backward compatibility and migration safety

Review whether the implementation is resilient to evolving schemas, partial rollouts, and app upgrades.

Flag:
- brittle enum/string assumptions
- code that assumes fields are always present
- serialization changes that may break older persisted data
- local model changes without migration consideration
- non-defensive parsing of backend responses
- assumptions that all clients/backends are upgraded simultaneously
- logic that breaks when unknown enum values or new fields appear
- all-or-nothing rollout assumptions
- old cached state that becomes unreadable or misinterpreted

Prefer:
- tolerant readers where appropriate
- explicit handling of unknown/missing values
- rollout-safe behavior
- migration-aware persistence changes
- defensive parsing at boundaries

Review whether:
- fallback behavior is defined for new/unknown backend values
- feature flags or capability checks degrade safely
- storage changes require migration paths

---

## Accessibility and UX robustness

Review whether the implementation is robust and understandable for users across normal and degraded states.

Flag:
- unclear loading, empty, or error handling
- actions without feedback
- color-only communication of meaning
- poor accessibility semantics where relevant
- fragile flows under slow network or partial data
- retry/recovery paths that are missing or unclear
- confusing disabled states
- degraded-state UX that leaves the user stuck without guidance
- no distinction between “no data yet” and “failed to load”
- inaccessible click targets or semantics where the platform supports better patterns

Prefer:
- explicit user feedback
- resilient degraded-state UX
- accessible semantics where supported by the platform/pattern
- clear retry and recovery behavior
- recoverable user paths under partial backend failure

---

## Rollout and feature isolation readiness

Review whether the implementation is safe to release incrementally.

Flag:
- unfinished dependencies wired as hard requirements
- weak handling of unavailable backend capabilities
- no clear isolation for risky new flows
- assumptions that everything is enabled simultaneously
- code paths that cannot degrade safely if partial rollout occurs
- no capability checks where the backend may lag behind the client
- new flows tightly coupled to unrelated existing flows

Prefer:
- graceful degradation
- clear feature boundaries
- rollout-safe assumptions
- safe handling of partially available functionality
- explicit feature capability handling where relevant

---

## KMP-specific review concerns

Because this is a KMP project, check for:
- unnecessary platform divergence
- common code that should remain common
- platform-specific logic introduced without justification
- APIs that reduce portability
- abstractions that will make iOS/Android behavior inconsistent
- threading assumptions that do not hold well across targets
- shared code using APIs that complicate testing or platform compatibility
- platform differences hidden in ways that make behavior hard to reason about
- shared logic depending indirectly on platform-only behavior
- platform abstractions that are too wide or too leaky

If the implementation starts changing source-set placement, shared-vs-platform ownership, or target-specific architectural boundaries, say that the change should also be reviewed with `kotlin-project-architecture-review`.

---

## Documentation and discoverability

Review whether the code is understandable to future maintainers.

Flag:
- non-obvious decisions with no explanation
- reusable abstractions with unclear intended usage
- surprising constraints hidden in implementation details
- high-value architectural choices that are undocumented
- naming that makes responsibilities or ownership hard to discover
- behavior that depends on invariants not visible at call sites

Prefer:
- concise comments for non-obvious decisions
- discoverable naming
- lightweight documentation where it materially helps future work
- clear file and type organization

---

## How to conduct the review

### Step 1: Understand the change
Identify:
- which feature/module changed
- the architectural path of the feature
- what responsibilities are present
- where business logic is currently placed
- whether the implementation fits existing patterns
- the risk areas for security, concurrency, persistence, and maintainability
- whether the change is local implementation work or is pushing into structural architecture territory

### Step 2: Review in categories
Review at minimum:
1. Architecture / layering
2. ViewModel size and responsibilities
3. Domain/business logic placement
4. Model and boundary integrity
5. State correctness
6. DI and lifetime management
7. Persistence/cache/source-of-truth
8. Security/privacy
9. UI system/design consistency
10. Compose recomposition/performance
11. Coroutine/threading/cancellation/exception handling
12. Concurrency/race conditions
13. Reusability / duplication / file organization
14. Internal API quality
15. Testability and tests
16. Observability/diagnostics
17. Static-analysis quality
18. Localization / strings
19. Backward compatibility / migration safety
20. Accessibility / UX robustness
21. Rollout safety
22. KMP portability / shared-vs-platform concerns

### Step 3: Prefer minimal, high-value fixes
Do not rewrite the entire feature unless the implementation is fundamentally broken.

Prefer:
- targeted improvements
- extractions that reduce complexity
- moving logic to the right layer
- improving naming and file organization
- correcting threading/error-handling issues
- extracting reusable components
- reducing recomposition risk
- strengthening security and trust-boundary handling
- improving resilience under partial data, retries, and race conditions

### Step 4: Escalate structural issues when needed
If the implementation appears to materially change:
- module boundaries
- source-set placement
- shared vs platform boundaries
- navigation architecture
- manifest/exported entry points
- Android entry-point ownership
- feature-level source of truth ownership

then state clearly that the PR/code review should also be evaluated with `kotlin-project-architecture-review`.

### Step 5: Summarize findings clearly
When reporting or reviewing, structure the output as:
1. High-risk issues
2. Security and privacy issues
3. Architectural issues
4. State/model boundary issues
5. Performance issues
6. Coroutine/threading/concurrency issues
7. Persistence/source-of-truth issues
8. UI/design-system/localization issues
9. Maintainability issues
10. Test gaps
11. Suggested fixes
12. Optional follow-up refactors
13. Whether architecture-review escalation is needed

Be explicit about severity and impact.

---

## Fix rules

If asked to apply fixes:
- apply only necessary and justified fixes
- preserve the existing architecture
- do not introduce broad unrelated refactors
- keep diffs understandable
- do not create abstractions that are more complex than the problem
- prefer incremental improvement
- do not weaken security, observability, or testability for the sake of brevity
- if a structural issue exists, fix locally where possible but explicitly call out larger architectural follow-up separately

---

## Anti-patterns to flag aggressively

- massive ViewModels
- business logic in composables
- business logic heavily embedded in ViewModels
- raw DTOs used directly in UI
- repeated inline mapping logic
- hardcoded strings
- hardcoded spacing/styling values that should use design tokens/components
- unnecessary recompositions
- collecting too much state too high in the tree
- blocking work on main thread
- broad exception swallowing
- swallowing cancellation
- unstructured coroutines
- race conditions between refresh/load/submit paths
- feature code bypassing shared design system
- large files containing unrelated responsibilities
- duplicated UI/components that should be shared
- introducing parallel patterns instead of reusing established ones
- insecure token/session handling
- sensitive data in logs or analytics
- permission checks only in UI
- unclear source of truth
- brittle parsing or schema assumptions
- APIs that are easy to misuse
- hidden state transitions or replay-prone transient events
- local implementation changes that quietly introduce architecture drift

---

## Final instruction

Review like an architect who will have to maintain this code for years.

Be strict about:
- correctness
- scalability
- maintainability
- consistency
- performance
- architecture boundaries
- security
- privacy
- diagnosability
- rollout safety

Optimize for protecting the codebase.
