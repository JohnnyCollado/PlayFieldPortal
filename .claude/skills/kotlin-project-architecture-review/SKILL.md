---
name: kotlin-project-architecture-review
description: Use when reviewing KMP architecture, feature proposals, PR structure, layer boundaries, state-holder design, Android entry-point discipline, source-set placement, modularization, and long-term maintainability in Kotlin Multiplatform / Compose Multiplatform projects.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "4.1.0"
---

# Kotlin Multiplatform Architecture Review

Use this skill to review architecture decisions, feature plans, pull requests, migrations, and refactors in a Kotlin Multiplatform project.

This skill is **architecture-review only**. It evaluates structural fit, ownership, boundaries, layering, source-set placement, Android entry-point discipline, resilience, security/privacy boundaries, rollout safety, and long-term maintainability.

It does **not** perform detailed implementation-level code review for Compose recomposition, coroutine misuse, static-analysis details, line-by-line refactoring, or file-level style issues. For that, use `kotlin-project-code-review`.

This skill is intentionally strict. Its purpose is to protect maintainability, correctness of shared code placement, clean boundaries between layers, realistic ownership of state and data, Android entry-point discipline, safe trust boundaries, diagnosability, rollout resilience, and long-term scalability.

---

## Primary review goals

The review should validate whether the proposal:

- preserves a clear single source of truth for important data
- follows unidirectional data flow
- uses a proper UI state production pipeline
- keeps Android components as platform entry points rather than business-logic containers
- places business logic in the right layer
- uses the domain layer only when it adds real value
- keeps repositories and data sources responsible for data ownership concerns
- uses source sets correctly in KMP
- preserves modular boundaries and avoids accidental coupling
- keeps platform-specific behavior at the edges
- remains testable and understandable as the codebase grows
- is resilient to partial backend data, evolving schemas, and phased rollouts
- does not introduce architectural security or privacy weaknesses
- preserves diagnosability for important flows
- does not over-couple features, modules, or targets in ways that will slow future evolution

Do not optimize for theoretical purity alone.

Optimize for:
- maintainability
- correctness
- consistency
- architectural clarity
- safe evolution
- production resilience

---

## Official architecture defaults to review against

Unless the project has a strong, deliberate reason not to, prefer these defaults:

- Single source of truth for each important data type
- Unidirectional data flow
- UI driven from data models
- State holders for UI complexity
- ViewModels or equivalent state holders such as presenters, reducers, or state machines exposing UI state and receiving user actions
- Coroutines and Flow for async work and observable state
- Clear separation of UI, domain, data, and platform integration
- Domain layer only when business logic is complex or reused
- Repositories as the main boundary for exposing and coordinating app data
- Android components treated as lifecycle-bound entry points, not as general-purpose business-logic containers
- Platform-specific behavior isolated at the edges
- Transport, persistence, and external SDK details hidden behind stable boundaries
- Defensive handling of partial data, unknown values, and rollout skew
- Observability designed into high-risk flows
- Minimal, explicit trust boundaries for auth/session/admin or privileged behavior

---

## What this skill should review

Use this skill for:
- feature architecture proposals
- source-set and shared-vs-platform placement decisions
- module extraction or consolidation decisions
- state-holder strategy decisions
- navigation architecture changes
- deep link / intent / manifest surface changes
- SSOT ownership decisions
- repository/domain boundary design
- Android entry-point ownership and delegation
- cross-feature coordination and modularization
- persistence/source-of-truth architecture
- architecture-level security/privacy and rollout concerns
- PRs that introduce structural changes rather than only local implementation changes

Use `kotlin-project-code-review` instead when the main question is whether implemented code is clean, safe, performant, and consistent within an already chosen architecture.

---

## Review dimensions

### 1. Single source of truth

Check whether every important piece of data has one clear owner.

Questions:
- What is the source of truth for this data?
- Is there more than one writable owner?
- Is UI holding durable app truth that should live lower?
- Is repository ownership explicit?
- In offline-first or cache-backed flows, is local persistence treated as source of truth when appropriate?
- Is there a coherent ownership strategy across app memory, persistence, network, and UI?

Flag as a concern when:
- multiple layers mutate the same data independently
- UI owns business-critical state beyond screen-local concerns
- network payloads are treated as truth where resilient local ownership is needed
- ownership is ambiguous
- memory, persistence, and remote state are merged ad hoc without a clear owner
- feature-level local caches bypass the intended source of truth

### 2. Unidirectional data flow

Review whether state and events move in one direction.

Expected shape:
- user events move upward into a state holder
- state is produced by state holders from data/domain inputs
- rendered UI consumes state
- updates come back as new state instead of ad hoc mutation

Check whether:
- UI renders from state instead of pulling dependencies directly
- state is not mutated from several unrelated places
- one-off events are not confused with long-lived UI state
- data changes propagate through a coherent pipeline
- state restoration or background refresh still fits the same flow model

Flag as a concern when:
- composables call repositories directly
- business logic runs in rendering code
- state is changed from multiple layers without a clear owner
- navigation, snackbar, toast, or permission effects are mixed into persistent state without clear modeling
- side-entry flows bypass the main state pipeline

### 3. Android component entry-point discipline

Android components are entry points with distinct lifecycles, and Activities / Fragments primarily host UI.

Check whether:
- Activities and Fragments are treated as UI hosts and platform lifecycle boundaries
- Services are used only for real background/service responsibilities
- BroadcastReceivers are thin entry points that delegate quickly
- ContentProviders are used intentionally rather than as general architecture shortcuts
- platform entry points delegate to state holders, repositories, or other appropriate layers
- component boundaries still make sense under process death, restart, background delivery, and external invocation

Flag as a concern when:
- Activities or Fragments own business rules, repository coordination, transport parsing, or data orchestration
- Services are used as architecture dumping grounds
- BroadcastReceivers contain meaningful feature orchestration inline
- platform entry points become the effective source of truth
- entry-point behavior depends on hidden assumptions rather than explicit delegation

### 4. UI layer responsibilities

The UI layer should consume app data, render it, handle user interactions, and reflect event effects in UI state.

Check whether:
- UI is focused on rendering and interaction
- state holders sit between UI and lower layers
- UI models are shaped for rendering rather than mirroring transport models
- screen states include loading, success, empty, error, partial-data, and retry when needed
- presentation-specific formatting and rendering concerns are kept in the presentation edge

Flag as a concern when:
- UI owns repository or data-source orchestration
- DTOs reach the UI directly
- UI performs large business transformations inline
- UI state is incoherent or under-modeled
- UI is responsible for interpreting backend semantics that belong lower

### 5. State-holder quality

Review whether state holders are doing the right work.

The specific mechanism is less important than whether the contract is satisfied:
- one immutable observable state output
- separate one-time effects
- user actions as inputs
- no business logic in the UI layer

Check whether:
- there is a clear state-holder boundary such as ViewModel, presenter, reducer, or equivalent
- immutable state is exposed, not mutable state flows accessible from outside
- the state holder consumes user actions and produces UI state without being a two-way bridge
- business logic and UI rendering logic are not mixed inline
- state production is based on clear, traceable inputs and outputs
- one-time effects are modeled separately from persistent `UiState`
- async state transitions are structurally understandable
- similar screens use similar state-holder patterns unless there is a good reason not to

Flag as a concern when:
- no state holder exists despite meaningful screen complexity
- the state holder is a god object
- mutable state is leaked broadly
- UI state and one-time effects are conflated into the same stream
- state-holder pattern is inconsistent across similar screens without a stated reason
- state holders absorb responsibilities that should belong to repositories, use cases, or coordinators

### 6. Domain layer usage

The domain layer is optional. It should exist when it reduces duplication or isolates meaningful business logic.

Check whether:
- domain use cases encapsulate complex or reusable business logic
- domain models stay independent of UI and transport concerns
- the domain layer adds clarity rather than indirection
- use cases own meaningful decisions rather than forwarding trivially

Flag as a concern when:
- a domain layer exists but only forwards calls
- trivial pass-through use cases add ceremony with no isolation benefit
- domain code depends on framework, UI, or transport details
- reusable business logic is duplicated across state holders instead of extracted
- the domain layer is omitted even though meaningful business rules are shared or complex

### 7. Data layer responsibilities

Repositories should expose data, centralize changes, resolve conflicts across sources, abstract sources, and own data-related coordination.

Check whether:
- repositories expose app/domain-facing outputs
- repositories centralize writes and coordination
- multiple data sources are resolved in one place
- data sources remain implementation details where appropriate
- the rest of the app is insulated from transport and persistence specifics
- cache / persistence / refresh behavior has a clear ownership model
- remote, local, and in-memory coordination is coherent

Flag as a concern when:
- repositories merely mirror raw endpoints
- UI or state-holder coordinates local and remote data directly
- repository ownership is bypassed
- persistence and network details leak upward
- refresh / invalidation / reconciliation responsibilities are ambiguous
- repositories are so generic they stop owning meaningful app data decisions

### 8. Failure model and error-handling architecture

Check whether:
- the project has a consistent failure model
- failures are surfaced deliberately
- repository/data errors are normalized when needed
- user-facing messages are derived at the presentation edge
- retryable and non-retryable failures are distinguishable when relevant
- cancellation, timeout, auth failure, validation failure, and partial-data cases have structurally sensible treatment
- degraded-state behavior is intentionally modeled

Flag as a concern when:
- strings are the effective error model
- each layer invents its own failure contract
- failures are swallowed silently
- transport messages are shown directly to users by default
- failure handling differs arbitrarily across similar features
- error pathways are only understandable by reading scattered implementation details

### 9. Layering and separation of concerns

Expected layers:
- presentation / UI
- orchestration / state-holder
- optional domain
- data / repositories / services / persistence
- platform integration

Check whether:
- UI contains business rules
- domain depends on transport, UI, or platform details
- DTOs leak into domain or presentation
- repositories own data coordination
- platform concerns remain outside business logic
- mapping responsibility is explicit and stable

Flag as a concern when:
- one file or module mixes UI, networking, mapping, and business rules
- repository implementations live inside state holders
- domain models are actually transport models
- platform SDK types appear in shared business code
- abstractions exist but do not line up with real ownership

### 10. Dependency boundaries and lifetime design

Check whether:
- dependency ownership is clear
- stateful collaborators are scoped appropriately
- lifetimes align with feature, screen, session, or app ownership
- construction paths remain testable and explicit
- long-lived state is not accidentally held by short-lived components or vice versa

Flag as a concern when:
- major collaborators are instantiated ad hoc in feature code
- stateful objects are shared too broadly
- object lifetime is longer than necessary
- implicit dependency access or service-locator-like patterns obscure ownership
- dependencies make ownership impossible to trace

### 11. Source-set correctness in KMP

Check whether code in `commonMain` is truly valid for all declared targets.

Review:
- whether `commonMain` references platform APIs
- whether target-specific behavior is isolated
- whether source-set placement follows compilation reality rather than convenience
- whether the design would still work if more targets were added
- whether the proposal is over-sharing code that is only common accidentally

Flag as a concern when:
- platform-specific APIs appear in shared code
- shared code assumes one platform’s lifecycle, resources, filesystem, or navigation model
- platform-only dependencies leak into common code
- code is pushed into `commonMain` only to reduce duplication, despite bad abstraction fit

### 12. Shared vs platform-specific boundary quality

Check whether:
- the proposal shares the right things
- native concerns stay at the edges
- expect/actual is justified and small
- abstraction boundaries are minimal and clear
- platform differences remain understandable rather than hidden behind vague interfaces

Flag as a concern when:
- platform-specific code leaks into business logic
- large expect/actual surfaces own feature logic
- native or vendor types spread through shared modules
- abstractions hide meaningful behavioral differences in a confusing way
- the proposal creates portability costs without meaningful reuse benefit

### 13. Module boundaries and modularization quality

Check whether:
- each module has a clear purpose
- dependencies are intentional and minimal
- public APIs are narrow
- shared modules are truly shared and not dumping grounds
- feature ownership remains cohesive
- modules align with actual ownership, not only packaging aesthetics

Flag as a concern when:
- unrelated features depend on each other directly
- common/shared/core modules accumulate unrelated code
- visibility is broad for convenience
- granularity is either too coarse or too fragmented
- modules exist only to satisfy theory while increasing coupling or indirection

### 14. Navigation and Android component interaction

Check whether:
- navigation ownership is clear
- route definitions are coherent
- start-destination behavior is understandable
- back behavior is realistic
- intent-driven entry points fit the navigation model
- Activities launched via explicit or implicit intents still delegate into the same architecture instead of bypassing it
- deep-link entry and in-app navigation converge cleanly

Flag as a concern when:
- deep links or intent entries create architecture bypasses
- routes are brittle and stringly typed without structure
- navigation behavior depends on hidden assumptions
- a feature’s real entry points differ depending on how the user arrived there
- navigation ownership is split across too many layers

### 15. Manifest and exported-surface review

Android components must be visible to the system through the manifest, and manifest declarations define part of the app’s architectural surface.

Check whether:
- Activities, Services, and ContentProviders that should run are declared appropriately
- BroadcastReceivers are declared or dynamically registered intentionally
- intent filters are added only where they represent real external entry points
- manifest exposure matches the intended architecture surface
- privileged or admin-like flows are not overexposed
- exported components are justified and bounded

Flag as a concern when:
- components rely on accidental manifest exposure
- architectural entry points are unclear from declarations
- too many components are externally reachable without a clear reason
- manifest declarations and actual ownership boundaries drift apart
- exported surface is broader than the product actually needs

### 16. Security and privacy architecture

Review the proposal for structural trust-boundary issues.

Check whether:
- authorization-sensitive behavior is not trusted to UI alone
- external input boundaries are explicit
- deep links, WebView, URLs, intents, files, or externally supplied identifiers are handled defensively at an architectural level
- sensitive data stays in the minimum number of layers
- privileged/admin flows are isolated appropriately
- logging/analytics boundaries avoid leaking sensitive values by design
- session/auth state transitions are owned and bounded clearly

Flag as a concern when:
- permission checks are only enforced in UI
- client state is treated as trusted for privileged behavior
- external inputs can bypass intended ownership boundaries
- sensitive data spreads through layers that do not need it
- architecture assumes backend authorization without clear boundary handling
- post-logout or role-change state ownership is unclear
- observability design would require logging sensitive data to diagnose problems

### 17. Responsiveness and configuration resilience

Check whether:
- UI state production is resilient to configuration changes
- layout adaptation is structured rather than bolted on
- state holders remain valid across lifecycle/configuration changes where appropriate
- layout assumptions are not hard-coded to one form factor
- state restoration and re-entry do not break ownership assumptions

Flag as a concern when:
- configuration change handling is fragile
- adaptive layouts require rewriting feature logic
- state is tied too tightly to one screen shape
- architecture assumes one device class or one platform behavior
- restored state and fresh data paths are structurally incompatible

### 18. Resources and presentation boundaries

Check whether:
- localization and resources stay in presentation/platform concerns where appropriate
- shared business logic does not hard-code values that belong in resources
- locale-sensitive formatting happens at the presentation edge, not in repositories or use cases
- the feature can evolve to alternative resources, configurations, or locales without major refactoring

Flag as a concern when:
- user-facing strings are assembled or formatted in repositories or use cases
- locale-sensitive logic runs inside business rules rather than at the presentation edge
- presentation constants or hard-coded display values are buried in shared data or domain layers
- the design assumes a single language, locale, density, or configuration
- presentation details leak into supposedly reusable shared logic

### 19. Observability and diagnosability as architecture

Check whether:
- important flows have a diagnosable path
- failures can be surfaced with enough context to debug in production
- high-risk operations have structural places for logging, analytics, or error capture
- sensitive information is not required to diagnose common failures
- ownership of runtime faults is clear enough that teams can reason about failures quickly

Flag as a concern when:
- critical flows can fail silently
- diagnostics would require reading UI code paths only
- feature ownership and runtime failure ownership are unclear
- important errors have no clear propagation path
- observability is bolted on in ways that cross too many boundaries
- there is no architecture-level place to capture or correlate meaningful failure context

### 20. Backward compatibility, migration, and rollout safety

Check whether:
- the design tolerates partial backend rollout
- unknown enum values, missing fields, and extra fields are survivable
- local persistence changes consider migration
- old and new app versions can coexist reasonably when needed
- new feature paths degrade safely when unavailable
- capability mismatches are handled explicitly where relevant

Flag as a concern when:
- the design assumes all backends and clients upgrade simultaneously
- persisted models change without migration thought
- server capabilities are treated as always available
- unknown values break business flows
- rollout requires risky all-at-once coupling
- the architecture gives no clean place to branch for capability differences

### 21. Testability as an architectural property

Check whether:
- business rules are isolated for unit tests
- mappers are pure and testable
- repositories can be tested with fakes/mocks
- state-holder logic can be tested without rendering UI
- key failure paths are testable
- platform entry points are thin enough that most logic is testable outside them
- architecture supports meaningful deterministic tests rather than only end-to-end coverage

Flag as a concern when:
- critical logic is trapped in Activities, Services, Receivers, or other platform entry points
- key flows can only be tested end to end
- architecture relies on hidden global state
- ownership is so blurred that tests must duplicate architecture knowledge
- async coordination is too implicit to test predictably

### 22. Architecture consistency with existing project patterns

Review whether the proposal fits the project’s established architectural direction unless there is a strong reason to depart from it.

Check whether:
- the proposal extends existing module, state-holder, and repository patterns where reasonable
- new abstractions are justified instead of introduced for novelty
- design choices are consistent with surrounding feature architecture
- the proposal avoids parallel patterns for the same problem

Flag as a concern when:
- the change introduces a competing architecture style without justification
- similar features would now require different mental models
- the proposal solves a local problem by creating long-term inconsistency
- existing patterns are bypassed without a documented reason

---

## Severity framework

### High severity

Likely to cause architectural drift, correctness problems, security exposure, or rollout risk.

Examples:
- no single source of truth
- business logic embedded in Activities or Fragments
- repositories bypassed by UI/state-holder code
- platform APIs in `commonMain`
- major module-boundary violations
- manifest/exported entry points that bypass intended architecture
- authorization-sensitive behavior trusted to UI only
- rollout assumptions that require synchronized upgrades
- externally reachable entry points that expose privileged flows accidentally

### Medium severity

Workable, but likely to create maintenance cost or fragility.

Examples:
- weak domain-layer justification
- oversized state holder
- DTO leakage into presentation
- unclear module ownership
- inconsistent failure modeling
- partial observability gaps
- insufficient migration or partial-data resilience
- portability costs introduced without strong benefit
- competing patterns appearing in nearby features

### Low severity

Structurally acceptable but worth improving.

Examples:
- naming obscures ownership
- package split could be clearer
- tests miss important transitions
- route modeling could be more explicit
- diagnostics could be more deliberate
- ownership is correct but not obvious enough from the design

---

## Required output format

When performing the review, respond with:

1. **Verdict**
   - good fit
   - acceptable with revisions
   - poor fit

2. **Architecture summary**
   - what the proposal is doing
   - which layers, modules, source sets, and Android entry points it affects
   - whether this is a local structural adjustment or a broader architectural shift

3. **What is structurally sound**
   - concrete strengths only

4. **Issues by review dimension**
   - SSOT
   - UDF
   - Android component boundaries
   - UI layer
   - state-holder quality
   - domain-layer usage
   - data-layer design
   - failure model
   - dependency/lifetime design
   - source sets
   - shared vs platform boundaries
   - modularization
   - navigation / intents / manifest surface
   - security / privacy architecture
   - responsiveness/resources
   - observability
   - backward compatibility / rollout safety
   - testability
   - architecture consistency with existing project patterns
   - other relevant sections

5. **Severity for each issue**
   - high / medium / low

6. **Concrete recommendations**
   - exact structural changes
   - better layer placement
   - better component delegation
   - better module/source-set placement
   - better ownership boundaries
   - safer rollout / migration / authorization boundaries where needed
   - whether the proposal should be narrowed to reduce architectural surface area

7. **Suggested target structure**
   - proposed module/package/source-set / entry-point layout if useful
   - proposed ownership map if useful

8. **Open risks**
   - migration cost
   - rollout concerns
   - backward-compatibility concerns
   - operational/debugging concerns
   - cross-platform consistency concerns

---

## Tone

Be direct and practical.

Do not give vague praise.

If the proposal is weak, say so clearly and explain why.

Do not soften structural criticism with filler. The value of the review comes from precision.

---

## Anti-patterns to flag aggressively

- no clear single source of truth
- bidirectional or ad hoc state mutation
- business logic in composables, Activities, or Fragments
- DTO-driven UI
- state-holder-free complex screens
- meaningless pass-through domain layer
- repositories bypassed by upper layers
- transport details leaking upward
- platform-specific APIs in `commonMain`
- modules with unclear purpose
- manifest or intent-filter surface that does not match the intended architecture
- hidden or inconsistent failure handling
- architecture that is only testable through large integration paths
- permission checks only in UI
- untrusted external input bypassing intended architecture boundaries
- rollout-sensitive changes with brittle assumptions
- critical flows with no diagnosable ownership path
- parallel architectural patterns introduced without good reason
- large shared abstractions that hide important platform behavior
- source-set decisions made for convenience rather than correctness

---

## Review method

Follow this sequence:

### Step 1: Understand the proposal

Identify:
- what is changing
- what architectural problem it is trying to solve
- which layers/modules/entry points/source sets are affected
- whether the proposal is local or cross-cutting
- what new ownership or responsibilities are being introduced

### Step 2: Review structural fit

Evaluate:
- ownership clarity
- layering
- source-of-truth placement
- state-holder boundaries
- repository/domain responsibilities
- platform/shared split
- Android component delegation
- navigation and entry-point fit
- trust boundaries
- rollout/migration resilience

### Step 3: Review long-term evolution cost

Evaluate:
- whether the proposal makes future features easier or harder
- whether it creates a new parallel pattern
- whether it increases coupling across targets/modules/features
- whether it is diagnosable in production
- whether it will be testable without fragile end-to-end dependence

### Step 4: Prefer targeted structural changes

Do not recommend a broad rewrite unless the proposal is fundamentally broken.

Prefer:
- narrowing responsibility
- restoring proper ownership
- moving code or responsibilities to the right boundary
- reducing exported surface
- simplifying shared/platform boundaries
- clarifying module responsibilities
- making rollout and failure handling more explicit

### Step 5: Summarize with a clear verdict

Use the required output format and be explicit about what is:
- structurally sound
- structurally risky
- fixable locally
- likely to require broader migration planning

---

## Final instruction

Review like an architect who will have to maintain this system for years.

Be strict about:
- correctness
- long-term scalability
- ownership clarity
- architectural consistency
- safe platform/shared boundaries
- security and privacy boundaries
- diagnosability
- rollout safety
- testability

Do not optimize for politeness.

Optimize for protecting the codebase.


## References

- Android app architecture: https://developer.android.com/topic/architecture
- Android architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Android UI layer: https://developer.android.com/topic/architecture/ui-layer
- Android domain layer: https://developer.android.com/topic/architecture/domain-layer
- Android data layer: https://developer.android.com/topic/architecture/data-layer
- Android application fundamentals: https://developer.android.com/guide/components/fundamentals
- Kotlin Multiplatform project structure: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
- Kotlin Multiplatform hierarchy: https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
