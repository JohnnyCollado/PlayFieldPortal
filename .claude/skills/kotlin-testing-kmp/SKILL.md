---
name: kotlin-testing-kmp
description: Use when designing, implementing, or reviewing tests in KMP projects — unit tests, instrumented tests, Compose Multiplatform UI tests, test doubles, test strategy, stability, performance, and screenshot testing.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.2.0"
---

# Kotlin Multiplatform Testing

Use this skill when designing, implementing, or reviewing tests in a Kotlin Multiplatform project.

This skill is intentionally strict. Its purpose is to keep the test suite fast, trustworthy, behavior-focused, and aligned with Android’s testing guidance while still respecting shared KMP boundaries and Compose Multiplatform testing patterns.

## Primary goals

The testing strategy should optimize for:

- strong confidence in business logic
- fast feedback from local/unit tests
- clear separation between local, Robolectric, instrumented, and UI tests
- behavior-focused tests instead of implementation-detail tests
- appropriate use of test doubles
- stable and performant larger tests
- isolated testing of shared KMP logic
- practical Compose Multiplatform UI test coverage
- targeted screenshot testing where visual regressions matter

Do not optimize for raw coverage percentages alone.
Optimize for confidence, speed, signal quality, and maintainability.

---

## Official defaults to prefer

Unless the project has a strong reason not to, prefer these defaults:

- many small local/unit tests
- fewer integration tests
- fewer end-to-end/UI tests than lower-level tests
- tests focused on user-visible behavior and business outcomes
- use of test doubles to isolate dependencies
- `kotlin.test` for assertions and test declarations in shared KMP test source sets — this is the cross-platform test API recommended by JetBrains and works across JVM, native, and JS/Wasm targets
- local tests in `src/test` (JVM-side tests in Android modules)
- Android instrumented tests in `src/androidTest`
- Robolectric only when Android framework behavior is needed but device execution is not necessary
- common Compose Multiplatform UI tests where the behavior is truly shared
- stable UI tests with clear synchronization and controlled environment setup
- screenshot tests for important visual surfaces when appearance regressions matter

---

## Test strategy defaults

### 1. Testing pyramid

Prefer a pyramid-shaped suite:

- many unit tests
- fewer integration tests
- fewer UI/end-to-end tests

Review expectation:
- lower-level tests should catch most regressions
- larger tests should validate integration and user workflows, not replace unit coverage

Flag as a concern when:
- most confidence depends on slow UI tests
- business logic is only exercised through end-to-end flows
- the suite is top-heavy and expensive to run

### 2. Behavior over implementation detail

Prefer tests that validate:
- observable outputs
- state transitions
- user-visible effects
- business rules

Be cautious with tests that lock down:
- internal private structure
- implementation-specific sequencing that users do not observe
- framework internals

Flag as a concern when:
- tests fail after harmless refactors
- mocks assert incidental calls instead of real behavior
- UI tests are verifying widget internals rather than actual interaction outcomes

---

## Test-scope review dimensions

### 3. What should be tested first

Prioritize tests for:

- business rules
- domain/use-case behavior
- repository coordination logic
- DTO/domain/UI mapping
- state-holder transitions
- failure and retry handling
- navigation decision logic where important
- shared UI behavior that is meaningful across targets

Flag as a concern when:
- trivial pass-through code is heavily tested while important rules are not
- mapping and error paths are untested
- state transitions are inferred rather than verified

### 4. Local unit tests

Local tests should be the default choice for fast feedback.

For **shared KMP modules**, tests in `commonTest` (using `kotlin.test`) run on all declared targets — JVM, native, JS/Wasm — making them the correct layer for shared business logic. Do not confuse `commonTest` (shared KMP tests) with `src/test` (Android/JVM-local tests).

Check whether:
- pure Kotlin/shared logic is tested in `commonTest` using `kotlin.test`, not pushed into Android-specific test layers
- Android-specific behavior is tested in `src/test` (local JVM) or `src/androidTest` (instrumented), not in shared test source sets
- test setup avoids Android device/emulator dependency when unnecessary
- most business logic tests live in the lowest practical test layer

Review expectations:
- shared logic in KMP should be exercised in `commonTest` first, before platform-specific layers
- `src/test` remains the main home for fast-running JVM-side tests on Android-only modules
- local tests are preferred unless the behavior truly needs Android runtime support

Flag as a concern when:
- shared business logic is only tested in Android-specific layers (`src/androidTest`) without reason
- the suite pays emulator/device cost for logic that could be a `commonTest` unit test
- `kotlin.test` is not used in shared source sets — shared tests depend on JUnit directly, breaking non-JVM targets

### 4a. kotlin.test for shared source sets

In KMP projects, shared test source sets (e.g., `commonTest`) should use `kotlin.test` for assertions and test structure. `kotlin.test` provides `@Test`, `assertEquals`, `assertNotNull`, `assertFailsWith`, and other essentials that compile correctly for all KMP targets (JVM, native, JS, Wasm).

Check whether:
- shared test code uses `kotlin.test` rather than JUnit or platform-specific assertion libraries
- `kotlin.test` is declared as a dependency in the `commonTest` source set
- platform-specific test libraries (JUnit, XCTest wrappers) are added only in platform-specific test source sets when needed

Flag as a concern when:
- JUnit annotations or assertions appear in `commonTest` without a JVM-only source set constraint
- shared tests fail on native or JS targets because of JVM-specific test infrastructure
- `kotlin.test` is absent from a KMP project's test dependencies despite having shared business logic

### 5. Robolectric usage

Robolectric is an Android-only testing tool. It is not available in KMP shared test source sets — it can only be used in Android-specific test source sets (`src/test` in an Android module, or an `androidUnitTest` source set in a KMP module). Do not attempt to configure Robolectric in `commonTest`.

Robolectric is useful when Android-dependent behavior must be exercised on the JVM without a real device or emulator.

Check whether:
- Robolectric is used for Android framework interactions that do not require full device/emulator fidelity
- it is placed in Android-specific test source sets, not shared KMP test source sets
- it is not used as a default for tests that could be plain local tests or `kotlin.test` unit tests
- the project uses it intentionally rather than as a catch-all compromise

Flag as a concern when:
- Robolectric is used for pure business logic that does not interact with Android APIs
- device-only behavior is assumed to be fully proven by Robolectric alone
- the suite becomes slow and brittle because Robolectric is overused
- Robolectric dependencies appear in shared KMP source sets

### 6. Instrumented tests

Instrumented tests should cover behavior that genuinely needs a real Android runtime, emulator, or device.

Check whether:
- Android integration behavior is validated in `src/androidTest`
- tests that need framework/runtime fidelity are placed here
- instrumented tests are selective rather than the default

Good candidates:
- platform integration
- app-component interactions
- behavior that depends on real Android runtime semantics
- high-value UI flows

Flag as a concern when:
- most feature validation lives only in instrumented tests
- instrumented tests are used for logic that should be local
- test layering is blurry and costly

### 7. Compose Multiplatform UI tests

Compose Multiplatform supports shared UI testing.

Check whether:
- shared UI behavior is tested in common code when it is genuinely shared
- `runComposeUiTest` is used for common Compose Multiplatform UI tests (note: as of mid-2025, `runComposeUiTest` requires `@OptIn(ExperimentalTestApi::class)`; verify stability status against the current Compose Multiplatform release)
- platform-specific setup is added only when required
- shared UI tests focus on semantics and observable behavior

Flag as a concern when:
- shared UI can only be validated through Android-only tests without reason
- common UI tests are skipped despite heavily shared Compose behavior
- tests depend too much on implementation details instead of semantics

### 8. AndroidX Test setup discipline

AndroidX Test setup should be coherent and intentional.

Check whether:
- test runners, rules, and AndroidX Test dependencies are configured consistently
- instrumented tests use the standard test infrastructure instead of ad hoc setup
- test environment setup is centralized enough to avoid drift

Flag as a concern when:
- instrumented test setup differs arbitrarily across modules
- runner/rule configuration is duplicated or inconsistent
- test infrastructure itself becomes hard to trust

---

## Test doubles

### 9. Appropriate test-double choice

Choose test doubles deliberately.

Common categories:
- fake
- mock
- stub
- spy

Prefer:
- fakes for repositories, data sources, and meaningful behavior simulation
- stubs for simple fixed responses
- mocks only when interaction verification is actually the point
- spies sparingly

Flag as a concern when:
- everything is mocked by default
- tests are interaction-heavy but behavior-light
- a fake would express the scenario more clearly than a deep mock tree

### 10. Dependency isolation

Check whether:
- tests isolate external systems appropriately
- network, database, file, and platform dependencies are replaced when the test does not need them
- doubles reduce flakiness and improve speed

Flag as a concern when:
- tests depend on real external services unnecessarily
- fake/test data behavior diverges so much from production that the test misleads
- the chosen double makes the test harder to understand

---

## Stability and performance for larger tests

### 11. Big-test stability

Android’s guidance treats stability as a first-class quality attribute for larger tests.

Check whether:
- tests control asynchronous work predictably
- environment setup is repeatable
- test state is isolated between runs
- flakiness sources are identified and reduced
- retries are not hiding real nondeterminism

Flag as a concern when:
- UI/integration tests pass only intermittently
- timing assumptions replace synchronization
- global shared state leaks between tests

### 12. Performance of instrumented tests

Instrumented tests should be optimized because they are expensive.

Check whether:
- the instrumented suite stays focused on high-value scenarios
- setup and teardown are not wasteful
- large tests are not duplicated unnecessarily across many layers
- performance-sensitive test suites are monitored and trimmed

Flag as a concern when:
- large tests are used where local tests would suffice
- startup/setup costs dominate every test
- the suite becomes too slow to run regularly

---

## UI testing guidance

### 13. UI tests should validate behavior

UI tests should focus on what the user can do and observe.

Check whether:
- assertions reflect visible behavior or meaningful semantics
- user actions are modeled realistically
- UI tests validate flows, not incidental structure

Flag as a concern when:
- tests lock onto fragile implementation details
- the suite checks internal tree shapes with little user value
- behavior is under-tested while low-value rendering details dominate

### 14. Screenshot testing

Screenshot tests are useful for detecting visual regressions on stable surfaces.

Check whether:
- screenshots are applied to important visual states
- expected variants are intentional and controlled
- screenshots complement, rather than replace, behavioral tests
- visual baselines are maintained carefully

Good candidates:
- design-system components
- stable destination screens
- major adaptive-layout variants
- key theme or locale states where appearance matters

Flag as a concern when:
- screenshot tests are used as the main correctness signal for behavior
- baselines churn constantly because surfaces are too unstable
- too many low-value screenshots make maintenance noisy

---

## KMP-specific review dimensions

### 15. Shared-vs-platform test placement

Test-source-set placement should mirror code-source-set placement:

- `commonTest` (using `kotlin.test`): shared business logic, domain rules, shared repository behavior, Compose Multiplatform UI behavior
- Android `src/test` (JUnit/Robolectric): Android-specific logic, ViewModel behavior where ViewModel is Android-only
- Android `src/androidTest`: Android integration, real framework behavior, UI flows on Android
- iOS/native test source sets: iOS-platform-specific behavior

Check whether:
- shared behavior is tested in `commonTest` using `kotlin.test`
- Android-specific behavior remains in Android test layers
- platform-specific assertions do not leak into shared tests without reason
- `kotlin.test` is used consistently in shared source sets (not JUnit4/JUnit5 directly, which are JVM-only)

Flag as a concern when:
- shared logic is only tested in Android-specific layers
- JUnit annotations appear in `commonTest` (breaks non-JVM compilation targets)
- platform-specific test setup (context, activity, application) pollutes shared test code
- the team does not know which test layer owns which behavior

### 16. State-holder and flow testing

Check whether:
- state-holder outputs are tested deterministically
- loading, success, empty, error, retry, and partial-data paths are covered
- one-time events are tested separately from persistent state
- flow-based logic is verified without over-relying on UI tests

Flag as a concern when:
- state behavior is inferred only through UI rendering
- event emission is untested
- async/state tests are timing-based rather than deterministic

### 17. Repository and mapping testing

Check whether:
- repositories are tested for coordination logic, source-of-truth behavior, and error handling
- mapping is tested directly
- test doubles are used where they provide clarity

Flag as a concern when:
- mapping bugs can only be caught through broad integration tests
- repository conflict resolution is untested
- data-layer correctness depends mainly on manual QA

---

## Severity framework

### High severity
Likely to undermine trust in the suite.

Examples:
- business logic only covered by UI/instrumented tests
- highly flaky big tests
- no clear separation between local and instrumented tests
- shared KMP logic untested or only tested through one platform
- unstable UI tests driven by timing assumptions

### Medium severity
Workable, but likely to create maintenance cost.

Examples:
- overuse of mocks
- Robolectric used too broadly
- screenshot coverage is noisy or poorly targeted
- instrumented suite too large for its value
- important state transitions missing direct tests

### Low severity
Structurally acceptable but worth improving.

Examples:
- test naming could be clearer
- some fakes could replace mocks
- preview/screenshot variants could be better targeted

---

## Required output format

When performing the review, respond with:

1. **Testing summary**
   - overall strategy
   - test pyramid shape
   - local / Robolectric / instrumented / UI / screenshot split
   - shared-vs-platform test placement

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - strategy and pyramid
   - what is being tested
   - local tests
   - Robolectric usage
   - instrumented tests
   - Compose Multiplatform UI tests
   - AndroidX Test setup
   - test doubles
   - stability
   - performance
   - UI behavior tests
   - screenshot tests
   - shared-vs-platform placement
   - state-holder/flow tests
   - repository/mapping tests

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - which tests should move down the pyramid
   - where to replace mocks with fakes/stubs
   - what should become local vs instrumented
   - what shared UI should be covered in common tests
   - where screenshot tests add value
   - how to reduce flakiness and runtime

6. **Suggested target structure**
   - proposed test-layer split if useful

7. **Open risks**
   - migration cost
   - flakiness still to investigate
   - platform-specific validation still required

---

## Tone

Be direct and practical.
Do not praise a large suite just because it has many tests.
If the testing strategy is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- top-heavy test suites dominated by UI/end-to-end tests
- business logic verified only through instrumented/UI tests
- unstable tests that rely on sleeps or timing guesses
- heavy mock usage where fakes would be clearer
- Android-specific assumptions inside shared KMP tests
- screenshot tests used as a substitute for behavior tests
- duplicated large tests across multiple layers
- expensive instrumented tests with low signal

---

## References

- Android: Fundamentals of testing: https://developer.android.com/training/testing/fundamentals
- Android: What to test: https://developer.android.com/training/testing/fundamentals/what-to-test
- Android: Test doubles: https://developer.android.com/training/testing/fundamentals/test-doubles
- Android: Testing strategies: https://developer.android.com/training/testing/fundamentals/strategies
- Android: Local unit tests: https://developer.android.com/training/testing/local-tests
- Android: Robolectric: https://developer.android.com/training/testing/local-tests/robolectric
- Android: Instrumented tests: https://developer.android.com/training/testing/instrumented-tests
- Android: Big test stability: https://developer.android.com/training/testing/instrumented-tests/stability
- Android: Instrumented test performance: https://developer.android.com/training/testing/instrumented-tests/performance
- Android: AndroidX Test setup: https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/test-setup
- Android: UI tests: https://developer.android.com/training/testing/ui-tests
- Android: Behavior UI tests: https://developer.android.com/training/testing/ui-tests/behavior
- Android: Screenshot testing: https://developer.android.com/training/testing/ui-tests/screenshot
- Compose Multiplatform: Testing UI: https://kotlinlang.org/docs/multiplatform/compose-test.html
- kotlin.test API: https://kotlinlang.org/api/latest/kotlin.test/