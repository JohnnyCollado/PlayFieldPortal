---
name: kotlin-project-modularization
description: Use when designing, reviewing, or refactoring module boundaries in KMP or Android projects — feature, data, app, and common modules, dependency direction, visibility control, and granularity.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.1.0"
---

# Kotlin Multiplatform Modularization

Use this skill when designing, reviewing, or refactoring module boundaries in a Kotlin Multiplatform project.

This skill is intentionally strict. Its purpose is to keep module boundaries meaningful, dependency direction clean, ownership clear, visibility tight, and project growth manageable.

## Primary goals

The modularization strategy should optimize for:

- reusability
- strict visibility control
- scalability
- ownership
- encapsulation
- testability
- build performance
- clear app entry points
- separation of platform-specific dependencies from reusable code

Do not modularize only for aesthetics.
Modularization should create clearer ownership and lower coupling.

---

## Official defaults to prefer

Unless the codebase has a strong reason not to, prefer:

- modules with a clear purpose
- narrow public APIs
- hidden implementation details
- feature ownership that is easy to infer
- shared code extracted only when it is truly shared
- module boundaries that reduce coupling
- app modules as entry points
- feature modules aligned to screens or closely related flows
- data modules that expose repositories and hide data sources
- common/core modules only for code reused by many modules
- Kotlin/Java modules instead of Android modules where Android resources, assets, and manifest support are unnecessary

---

## Benefits to review for

A modular design is stronger when it improves:

- **Reusability**: modules act as building blocks and can be reused across variants or apps
- **Strict visibility control**: internal details stay hidden outside the module
- **Scalability**: changes stay local instead of cascading widely
- **Ownership**: one team or person can clearly own a module
- **Encapsulation**: each module knows as little as possible about others
- **Testability**: logic can be tested in isolation
- **Build time**: Gradle can better leverage parallelism, caching, and incremental builds

If the proposed modularization does not improve at least some of these, be skeptical.

---

## Module types to recognize

### 1. Data modules

A data module usually contains:
- repositories
- data sources
- model classes

Its primary responsibilities are:
- encapsulate all data and business logic of a certain domain
- expose the repository as the external API
- hide implementation details and data sources from the outside

Review expectations:
- repositories are the public surface
- data sources stay internal to the module
- data ownership is domain-oriented, not endpoint-oriented
- callers do not depend directly on local/remote sources

Flag as a concern when:
- a data module exposes transport or persistence details publicly
- repositories are thin pass-through wrappers
- multiple unrelated domains are bundled together without a good reason

### 2. Feature modules

A feature module is an isolated part of app functionality, usually a screen or a closely related flow.

Review expectations:
- a feature corresponds to a user-visible capability
- a feature module likely contains UI plus a ViewModel/state-holder
- feature modules depend on data modules
- feature ownership is obvious from the module name and content

Flag as a concern when:
- one feature is scattered across many unrelated modules
- feature modules contain unrelated cross-cutting concerns
- features depend on each other too directly and too broadly

### 3. App modules

App modules are application entry points.

Review expectations:
- app modules depend on feature modules
- app modules usually provide root navigation
- if multiple device types are targeted, separate app modules may be appropriate to isolate platform-specific dependencies

Flag as a concern when:
- app modules own feature business logic that should live lower
- platform-specific concerns are not separated where they should be
- app modules become oversized containers for unrelated code

### 4. Common or core modules

Common/core modules contain code that many modules reuse and do not represent one specific app layer by themselves.

Examples may include:
- UI/design system
- analytics
- networking infrastructure

Review expectations:
- common modules exist because the code is genuinely reused
- they do not become dumping grounds
- their APIs stay focused and narrow

Flag as a concern when:
- `core`, `common`, or `shared` modules accumulate unrelated responsibilities
- code is moved into common modules too early
- common modules become backdoors that weaken feature boundaries

---

## Review dimensions

### 1. Module purpose clarity

Check whether every module has a clear purpose.

Questions:
- Can the module be described in one sentence?
- Is the module organized around a domain, feature, platform entry point, or a clearly shared capability?
- Would a new developer understand why it exists?

Flag as a concern when:
- the module name is vague
- the module mixes several unrelated purposes
- boundaries are justified only by folder convenience

### 2. Dependency direction

Check whether dependencies flow in a clean direction.

Prefer:
- app modules -> feature modules -> data/common modules
- feature modules depending on data modules, not vice versa
- shared infrastructure dependencies flowing inward without creating feature coupling

Flag as a concern when:
- feature modules depend on each other cyclically
- lower-level modules depend on UI or app modules
- module relationships are unclear or brittle

### 3. Visibility control

Use module boundaries to hide internals aggressively.

Check whether:
- implementation details are not publicly exposed without reason
- `internal` or `private` visibility is used appropriately
- only the intended API of the module is consumable

Flag as a concern when:
- everything is public by default
- repositories, data sources, mappers, and internals all leak outward
- module boundaries exist but do not enforce encapsulation

### 4. Granularity

Granularity must be intentional.

The Android modularization guide (https://developer.android.com/topic/modularization) identifies these pitfalls:
- **too fine-grained**: too much overhead, build complexity, and boilerplate
- **too coarse-grained**: modules become mini-monoliths and lose modular benefits
- **too complex**: modularization overhead outweighs benefits for the size of the project

Check whether:
- the number of modules matches the size and complexity of the codebase
- splitting adds clarity rather than ceremony
- module count is justified by ownership, reuse, or coupling reduction

Flag as a concern when:
- module count explodes without real boundary value
- large modules still contain many unrelated features
- the build becomes harder to reason about than the code itself

### 5. Feature ownership cohesion

Check whether a feature mostly lives in its owning module(s).

Prefer:
- feature UI, state-holder logic, and feature coordination staying together
- dependencies on shared modules only where they are actually shared
- modules that let feature work stay local

Flag as a concern when:
- feature implementation requires touching many unrelated modules
- feature ownership is hidden by broad technical slicing
- multiple modules partially own the same feature with no clear leader

### 6. Data ownership cohesion

Check whether data domains are modularized coherently.

Prefer:
- data modules aligned to meaningful domains
- repositories as the public API
- data sources hidden internally

Flag as a concern when:
- every endpoint gets its own module without domain cohesion
- data modules expose source internals
- the same domain logic is split across several modules arbitrarily

### 7. Common-module discipline

Common modules should reduce redundancy, not centralize chaos.

Check whether:
- common modules are truly reused by multiple consumers
- the module API is stable and small
- feature-specific code is not prematurely generalized

Flag as a concern when:
- common modules become default homes for code with unclear ownership
- teams use common modules to avoid choosing proper feature ownership
- changes to one feature require unrelated common-module edits

### 8. App-entry-point discipline

App modules should act as entry points.

Check whether:
- root navigation lives in app modules or a clearly designated top-level integration layer
- app modules compose features instead of owning their internal business logic
- device-specific entry points are separated when the project targets multiple device types

Flag as a concern when:
- app modules become giant orchestration layers for all business logic
- platform/device concerns are mixed in a single entry point despite differing requirements

### 9. Build and dependency hygiene

Check whether the module graph supports maintainability and build performance.

Prefer:
- `implementation` by default unless public API exposure is required
- fewer unnecessary transitive exposures
- module boundaries that support isolated rebuilds and isolated tests

Flag as a concern when:
- `api` is overused
- implementation details leak through public dependency surfaces
- the module graph forces many rebuilds for small changes

### 10. Prefer the lightest correct module type

Android library modules add overhead because they carry resources, assets, manifest, and AGP compilation. Use the lightest module type that correctly models the code's role:

- **KMP multiplatform library modules** (`kotlin("multiplatform")` plugin): the correct vehicle for shared KMP code targeting multiple platforms (Android, iOS, desktop, web). Not the same as a plain JVM or Android module.
- **Plain Kotlin/JVM modules** (`kotlin("jvm")`): suitable for pure JVM logic with no Android or multiplatform needs (e.g., a build-logic module or a server-side module in the same build).
- **Android library modules** (`com.android.library`): reserved for code that genuinely needs Android resources, assets, or manifest support and is not shared to non-Android targets.
- **Android application modules** (`com.android.application`): app entry points only.

Check whether:
- KMP shared code uses the `kotlin("multiplatform")` plugin, not the Android library plugin
- Android library modules are reserved for code that actually needs Android-specific packaging
- plain Kotlin/JVM modules are used where neither Android packaging nor multiplatform targeting is required
- the module type choice is intentional rather than defaulted

Flag as a concern when:
- Android library modules are used by default for logic that could live in a KMP multiplatform module
- shared KMP logic is placed in Android-only modules without a clear reason
- the build overhead (resources, manifest, AGP) does not match the module's actual role

### 11. Testability as a modularization outcome

Check whether modularization improves isolated testing.

Prefer:
- module APIs that can be faked or mocked cleanly
- business logic isolated away from app entry points
- tests that can run at module scope instead of only at app scope

Flag as a concern when:
- modularization does not improve isolation
- critical flows still require app-wide integration tests for simple validation
- module contracts are too broad or unclear to test well

---

## Severity framework

### High severity
Likely to cause structural problems.

Examples:
- cyclic dependencies
- modules with no clear purpose
- app modules containing feature/business internals
- data modules exposing data-source internals
- common modules acting as dumping grounds

### Medium severity
Workable, but likely to add maintenance cost.

Examples:
- granularity slightly too fine or too coarse
- overuse of `api`
- weak ownership boundaries
- feature logic spread across too many modules
- Android modules used where plain Kotlin modules would suffice

### Low severity
Structurally acceptable but worth improving.

Examples:
- naming obscures intent
- a common module could be split later
- a feature boundary is slightly awkward but still understandable

---

## Required output format

When performing the review, respond with:

1. **Modularization summary**
   - current module types
   - dependency direction
   - entry-point modules
   - shared/common modules

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - module purpose
   - dependency direction
   - visibility control
   - granularity
   - feature ownership
   - data ownership
   - common-module discipline
   - app-entry-point discipline
   - build/dependency hygiene
   - Android vs plain Kotlin module choice
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact module moves or merges
   - which modules should split or consolidate
   - which APIs should become internal
   - where dependency direction should change
   - where a plain Kotlin module should replace an Android module

6. **Suggested target structure**
   - proposed module graph if useful

7. **Open risks**
   - migration cost
   - build impact
   - rollout and compatibility concerns

---

## Tone

Be direct and practical.
Do not praise modularization just because many modules exist.
If the modularization is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- cyclic dependencies
- modules with unclear purpose
- too-fine-grained modules that add overhead without stronger boundaries
- too-coarse-grained modules that behave like mini-monoliths
- common/core/shared dumping grounds
- data modules exposing data sources publicly
- feature modules depending broadly on other feature modules
- app modules owning feature internals
- overuse of `api`
- Android library modules used when KMP multiplatform library modules or plain Kotlin modules would suffice

---

## References

- Android modularization guide: https://developer.android.com/topic/modularization
- Android modularization patterns: https://developer.android.com/topic/modularization/patterns
- Kotlin Multiplatform DSL reference: https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-dsl-reference.html