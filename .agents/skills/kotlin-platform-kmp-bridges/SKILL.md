---
name: kotlin-platform-kmp-bridges
description: Use when designing, implementing, or reviewing platform-specific integrations in KMP projects, including source-set placement, hierarchical sharing, expect/actual usage, platform API access, and shared-to-native abstraction boundaries.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.2.0"
---

# Kotlin Multiplatform Platform Bridges

Use this skill when designing, implementing, or reviewing platform-specific integrations and shared-to-native boundaries in a Kotlin Multiplatform project.

This skill is intentionally strict. Its purpose is to keep platform-specific code at the edges, maximize valid sharing through source-set hierarchy, avoid unnecessary `expect`/`actual`, and preserve testable abstractions between shared and native code.

## Primary goals

The bridge design should optimize for:

- correct source-set placement
- maximum valid code sharing before introducing platform splits
- clear shared-to-native abstraction boundaries
- minimal and justified `expect`/`actual` usage
- platform API access through the least-coupled mechanism
- reuse across similar platforms through intermediate source sets
- testability and replaceability of native integrations
- avoidance of platform/vendor types leaking into shared business logic

Do not default to `expect`/`actual` for every native dependency.
Start from the least specialized mechanism that solves the problem cleanly.

---

## Official defaults to prefer

Unless the project has a strong reason not to, prefer these defaults:

- share code in `commonMain` when it is valid for all declared targets
- share code among similar platforms through hierarchical source sets before duplicating implementations
- use the default hierarchy template unless the project truly requires custom hierarchy wiring
- check for existing multiplatform libraries before writing platform bridges
- prefer regular interfaces and injected implementations when they model the dependency well
- use `expect`/`actual` mainly for narrow platform-specific access points
- keep `actual` implementations in intermediate source sets like `iosMain` when one implementation is valid for several platform targets
- keep platform-specific registration, lifecycle, packaging, and SDK wiring outside shared business logic

---

## Review dimensions

### 1. Sharing-first source-set placement

Check whether code is placed in the highest valid shared source set before splitting into platform code.

Prefer:
- `commonMain` for logic valid for all targets
- intermediate shared source sets for code valid for some but not all targets
- platform source sets only when APIs or behavior genuinely differ

Flag as a concern when:
- code is duplicated in platform source sets even though it could live in shared code
- platform splitting happens too early
- `commonMain` is underused because the design assumes platform differences before validating them

### 2. Intermediate source-set usage

Kotlin explicitly supports sharing code among similar targets through hierarchical project structure and intermediate source sets. Examples include `iosMain` for several iOS targets.  (https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html)(https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html)

Check whether:
- similar targets reuse logic through intermediate source sets
- intermediate source sets are used for platform-family APIs and dependencies where appropriate
- platform-family bridges are not duplicated unnecessarily across concrete targets

Flag as a concern when:
- `iosX64Main`, `iosArm64Main`, and `iosSimulatorArm64Main` duplicate the same bridge code
- one platform family could share an `actual` implementation but does not
- intermediate source sets are ignored without reason

### 3. Default hierarchy template vs manual hierarchy

The hierarchy docs recommend the default hierarchy template for most projects and warn that explicit `dependsOn()` edges cancel it unless you deliberately reapply or opt out.  (https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)(https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)

Check whether:
- the project uses the default hierarchy template when it fits
- manual hierarchy configuration is justified
- additional source sets are introduced only when the default template is insufficient
- hierarchy changes preserve clarity rather than creating hidden build complexity

Flag as a concern when:
- manual `dependsOn()` graphs exist for cases already covered by the default template
- the hierarchy is custom but offers no clear architectural benefit
- source-set topology is difficult to reason about

### 4. Platform-library-before-bridge rule

Before introducing custom bridge code, check whether an existing multiplatform library or platform library already solves the problem.

Kotlin explicitly recommends first checking for multiplatform libraries, and notes that Kotlin/Native ships platform libraries such as Foundation, UIKit, and POSIX that are available to native shared source sets.  (https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)

Check whether:
- an existing multiplatform library should be preferred
- native platform libraries are being used directly where appropriate
- custom bridge code exists only where it adds value

Flag as a concern when:
- a bespoke bridge is created for a capability already well-covered by a multiplatform dependency
- platform wrappers duplicate standard library or platform-library behavior without benefit

### 5. Choosing between interfaces, entry points, and expect/actual

Kotlin’s platform-API guidance supports several approaches:
- interfaces in common code with platform implementations
- supplying platform implementations from different platform entry points
- `expect`/`actual` functions/properties
- DI frameworks for larger architectures.  (https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)

Review whether the chosen mechanism matches the problem size.

Prefer:
- interfaces in common code when the dependency is substantial or benefits from multiple implementations and easier testing
- platform entry-point construction when you control startup/bootstrapping cleanly
- narrow `expect`/`actual` functions or properties for small platform-specific access points

Flag as a concern when:
- `expect`/`actual` is used for complex object graphs that would be cleaner as interfaces
- platform implementations are hard-wired deep in shared code when entry-point wiring would suffice
- the bridge style makes testing or replacement harder than necessary

### 6. expect/actual correctness

If `expect`/`actual` is used, review it strictly.

Kotlin requires:
- the `expect` declaration in common code
- matching `actual` declarations for all relevant targets
- the same package for `expect` and `actual`
- no implementation in the `expect` declaration.  (https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)(https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)

Check whether:
- signatures match correctly
- package names match
- every required target has an `actual`
- `actual` implementations are placed at the right hierarchy level

Flag as a concern when:
- `expect` declarations include implementation
- packages differ
- one target is missing an `actual`
- `actual` code is duplicated in concrete targets when an intermediate source set would suffice

### 7. Avoid overusing expect/actual classes

Kotlin explicitly recommends relying on standard language constructs wherever possible. The `expect`/`actual` mechanism overall is stable, but `expect`/`actual` *classes* (non-annotation `expect` class declarations) carry restrictions: in Kotlin 2.0+, non-annotation `expect` classes must have a corresponding `actual` class (not a typealias) in each target, and their member declarations must match. This makes `expect`/`actual` classes heavier to maintain than `expect` functions or properties. Always check the current Kotlin docs for the latest stability status of this feature.  (https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)(https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)

Check whether:
- interfaces, functions, properties, or factories would be enough
- `expect`/`actual` classes are used only when truly justified
- the team understands the tradeoff of choosing a Beta language feature

Flag as a concern when:
- simple abstractions are modeled as `expect` classes without need
- the design unnecessarily restricts each target to one implementation
- fakes and alternative implementations become harder because of class-based actualization

### 8. Shared contract quality

Shared code should define what the app needs, not how each platform implements it.

Check whether:
- shared contracts are small and purposeful
- common abstractions hide platform/vendor details
- bridge surfaces are stable enough for callers
- shared contracts model capabilities, not SDK quirks

Flag as a concern when:
- shared contracts expose Android/iOS/vendor terminology unnecessarily
- common code depends on native SDK shapes
- platform concerns drive domain model design

### 9. Entry-point wiring

Kotlin documents passing platform implementations from platform entry points as a valid alternative to `expect`/`actual`. In practice, this means: at the platform main function or app startup (e.g. `MainActivity.onCreate()` on Android, the app entry point on iOS), the platform constructs concrete implementations of shared interfaces and passes them into shared code — typically via constructor injection, factory functions, or a DI container. Shared code receives an already-constructed dependency and does not need to know which platform provided it. (https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)

**What "entry-point wiring" means concretely:** the platform's main entry point (e.g., Android `Application.onCreate()`, iOS `main.kt`, desktop `main()`) constructs platform-specific implementations and injects them into shared code — typically through a constructor, factory function, or DI graph — rather than having shared code create or locate them itself. Shared code depends on an interface or abstract type; only the entry point knows the concrete class.

Check whether:
- platform-specific instantiation happens at platform entry points when appropriate
- shared code receives already-constructed dependencies instead of owning platform bootstrapping
- platform startup remains thin and explicit
- different platforms can supply different implementations of the same interface without touching shared code

Flag as a concern when:
- shared modules instantiate platform-specific implementations implicitly
- entry-point wiring is duplicated across many places
- platform lifecycle/setup concerns leak into common business logic

### 10. Native/vendor leakage

Check whether platform-specific types stay behind the bridge.

Prefer:
- SDK wrappers that convert callbacks and data into project-owned types
- shared modules depending on app-owned interfaces and models

Flag as a concern when:
- shared code imports vendor or platform SDK types
- platform callback shapes leak through common layers
- business logic becomes tied to native SDK behavior

### 11. Platform-family API access

The hierarchy docs note that intermediate source sets can access APIs available for the targets they compile to, and Kotlin/Native platform libraries can be used from such shared native source sets.  (https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)(https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)

Check whether:
- iOS-family code uses `iosMain` or another appropriate intermediate source set when one implementation covers the whole family
- target-subset sharing is used intentionally for API families such as Apple/native groupings
- the source-set choice matches actual API availability

Flag as a concern when:
- code is pushed down to per-target source sets unnecessarily
- intermediate source sets are used carelessly without validating API availability across their targets

### 12. Testability and replaceability

Check whether:
- common code can be tested against interfaces or narrow factories
- fake implementations are easy to provide
- bridge decisions do not force tests to run through full platform bootstrapping
- one platform can support multiple implementations when useful

Flag as a concern when:
- `expect`/`actual` choices make fakes harder than necessary
- bridge logic can only be validated in end-to-end platform runs
- common code is tightly coupled to one concrete platform implementation style

---

## Severity framework

### High severity
Likely to cause architectural drift or invalid source-set/platform coupling.

Examples:
- platform-specific APIs in `commonMain`
- missing `actual` implementations
- mismatched packages between `expect` and `actual`
- large feature logic embedded in platform bridges
- unnecessary per-target duplication instead of intermediate source sets

### Medium severity
Workable, but likely to create maintenance cost.

Examples:
- overuse of `expect`/`actual` where interfaces would be cleaner
- manual hierarchy configuration without strong benefit
- weak entry-point wiring boundaries
- native/vendor terminology leaking into shared contracts

### Low severity
Structurally acceptable but worth improving.

Examples:
- bridge naming obscures capability ownership
- an intermediate source set could be introduced later
- factories could be simplified

---

## Required output format

When performing the review, respond with:

1. **Bridge summary**
   - shared contracts
   - source-set placement
   - hierarchy usage
   - expect/actual usage
   - entry-point wiring
   - native integration boundaries

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - sharing-first placement
   - intermediate source sets
   - hierarchy-template usage
   - library-before-bridge choice
   - interface vs entry-point vs expect/actual choice
   - expect/actual correctness
   - expect/actual class overuse
   - shared contract quality
   - entry-point wiring
   - native/vendor leakage
   - platform-family API access
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact source-set moves
   - hierarchy simplifications
   - where to replace expect/actual with interfaces or factories
   - where to move actual implementations to intermediate source sets
   - where to push platform construction to entry points

6. **Suggested target structure**
   - proposed common / intermediate / platform split if useful

7. **Open risks**
   - migration cost
   - hierarchy/build impact
   - platform-specific constraints still to validate

---

## Tone

Be direct and practical.
Do not praise a bridge just because it works on one platform.
If the bridge design is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- platform-specific APIs in `commonMain`
- unnecessary duplication across similar platform targets
- manual source-set hierarchy where the default template would be enough
- using expect/actual classes when interfaces or factories would suffice
- missing or mismatched actual declarations
- platform/vendor types leaking into shared business logic
- shared contracts shaped around SDK quirks
- platform bootstrapping hidden inside common code
- bridge choices that make testing unnecessarily hard

---

## References

- Kotlin Multiplatform: Share code on platforms: https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html
- Kotlin Multiplatform: Expected and actual declarations: https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
- Kotlin Multiplatform: Use platform-specific APIs: https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html
- Kotlin Multiplatform: Hierarchical project structure: https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Kotlin releases and stability notes: https://kotlinlang.org/docs/releases.html