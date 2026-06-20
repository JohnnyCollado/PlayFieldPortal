---
name: kotlin-project-bugfix
description: Use when diagnosing and fixing bugs in a Kotlin Multiplatform project. Focus on root-cause analysis, minimal safe fixes, KMP correctness, UI/state/data/persistence/concurrency issues, and regression prevention.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.0.0"
---

# Kotlin Project Bug Fix

Use this skill when fixing an existing bug in a Kotlin Multiplatform project.

This is a bug-fixing skill, not a feature-building skill and not a review-only skill.

Your goal is to:
- identify the real root cause
- fix the bug with the smallest correct change
- avoid unrelated refactors
- preserve the current architecture unless the bug proves the design is wrong
- prevent regressions with targeted tests where appropriate

Do not optimize for speed alone.
Optimize for correctness, stability, maintainability, and regression safety.

---

## Core bug-fixing philosophy

Do not patch symptoms before understanding the path that produces them.

Always trace the bug across the full flow that could be involved:
- UI rendering
- state holder / ViewModel / presenter
- domain/business logic
- repository/data layer
- persistence/cache
- mapping between layers
- navigation/lifecycle/re-entry
- source-set/platform-specific behavior
- coroutine/flow/concurrency timing

Prefer the smallest coherent fix that addresses the true cause.

Do not refactor unrelated areas during bug fixing unless the refactor is necessary to safely fix the bug.

---

## Primary goals

For every bug fix, optimize for:

1. **Root-cause correctness**
2. **Minimal diff**
3. **Preservation of architecture**
4. **No unrelated cleanup**
5. **Regression prevention**
6. **Clear state/model boundary handling**
7. **KMP source-set correctness**
8. **Compose/lifecycle correctness**
9. **Coroutine/cancellation/concurrency correctness**
10. **Persistence/re-entry safety**
11. **Security/privacy safety where relevant**
12. **Testability**

---

## Required workflow

Follow this workflow unless explicitly told otherwise.

### Step 1: Classify the bug
First classify the bug. A bug may involve more than one category.

Possible categories:
- UI/layout bug
- design-system integration bug
- state-management bug
- ViewModel/presenter orchestration bug
- business-logic bug
- mapper/model-boundary bug
- persistence/cache/reload bug
- navigation/back-stack bug
- lifecycle/re-entry bug
- coroutine/flow/cancellation bug
- concurrency/race-condition bug
- platform-specific bug
- backend-contract/parsing bug
- permissions/session/auth bug

### Step 2: Inspect before editing
Before changing code, inspect the end-to-end path relevant to the bug.

At minimum inspect:
- screen/composable(s) involved
- state holder / ViewModel / presenter
- relevant UI models
- domain/use-case logic if present
- repository/data source path if relevant
- persistence/storage/entity/DTO models if relevant
- mappers between storage/domain/UI if relevant
- navigation/lifecycle/re-entry behavior if relevant
- source-set placement if any platform-specific code is involved
- existing tests around the affected flow

Do not jump to a UI-only fix if the bug may come from state, mapping, persistence, or lifecycle.

### Step 3: Diagnose root-cause candidates
Before implementing, produce a short diagnosis:
1. observed behavior
2. expected behavior
3. likely root-cause candidates
4. layers involved
5. files likely to change
6. chosen fix strategy
7. risks / edge cases

If multiple causes are plausible, choose the most evidence-based one and verify it against the code.

### Step 4: Implement the smallest correct fix
Apply only the changes needed to fix the bug safely.

Prefer:
- fixing the root cause instead of masking the symptom
- extracting a small mapper/helper only if needed
- preserving existing public APIs unless change is required
- keeping the diff easy to review

### Step 5: Add regression protection
Where appropriate, add or update tests for:
- pure bug-triggering logic
- mapper/model conversion issues
- state transitions
- repository coordination
- parsing/serialization issues
- concurrency-sensitive behavior

Do not add noisy tests for trivial wiring.

---

## Root-cause rules by bug type

### UI/layout bugs
For layout, keyboard, scrolling, spacing, visibility, clipping, or overlapping issues:
- inspect insets handling before adding padding
- inspect scaffold/content padding
- inspect duplicate `imePadding`, `navigationBarsPadding`, or bottom padding
- inspect list content padding vs composer/input bar spacing
- inspect scroll state ownership
- inspect whether the bug happens only on first entry, re-entry, or keyboard transitions
- inspect whether state timing is causing the UI symptom

Do not assume the UI layout is the sole cause if the issue appears after navigation or re-entry.

### State-management bugs
For wrong loading/error/success behavior, stale content, impossible states, or wrong transient effects:
- inspect state ownership
- inspect whether one-time effects are mixed into persistent state
- inspect whether multiple async paths can mutate the same state inconsistently
- inspect stale response handling
- inspect whether UI is deriving too much logic locally

Prefer explicit state transitions over ad hoc boolean combinations.

### Mapper/model-boundary bugs
If data displays correctly initially but breaks after reload/re-entry:
- inspect storage model
- inspect serialization/deserialization
- inspect entity ↔ domain ↔ UI mapping
- inspect whether transient in-memory fields are incorrectly required for rendering
- inspect whether content type is inferred from text instead of modeled explicitly

If a persisted rich-content item becomes plain text later, treat that as a model/mapping bug first, not a UI bug.

### Persistence/re-entry bugs
If a bug appears after:
- navigating away and back
- screen recreation
- process recreation
- retry/reload
- app restart

Then inspect persistence/cache/source-of-truth behavior before changing UI rendering.

Be explicit about:
- source of truth
- reload path
- mapper behavior on restored data
- stale cache behavior
- local vs remote precedence

### Coroutine/flow/cancellation bugs
If the bug involves loading stuck forever, duplicate events, missing updates, or inconsistent async behavior:
- inspect coroutine scope ownership
- inspect cancellation handling
- inspect `catch` / `runCatching`
- inspect `StateFlow` / `SharedFlow` usage
- inspect duplicate collectors
- inspect race conditions between refresh, send, retry, and navigation

Do not swallow `CancellationException`.
Do not fix timing bugs with brittle arbitrary delays unless absolutely unavoidable.

### Concurrency/race-condition bugs
If the bug depends on timing or overlapping actions:
- inspect repeated taps
- inspect duplicate submissions
- inspect stale responses overriding fresh state
- inspect multiple jobs writing to the same state
- inspect whether latest-wins / first-wins behavior is defined
- inspect scroll-after-update timing carefully in chat/list UIs

### Navigation/lifecycle bugs
If behavior changes on re-entry, deep link entry, back navigation, or app resume:
- inspect route arguments
- inspect state restoration
- inspect screen recreation behavior
- inspect whether the state holder is recreated unexpectedly
- inspect whether lifecycle-side effects run too often or not enough

### Platform-specific bugs
If the bug may differ between Android and iOS:
- verify whether the logic belongs in shared code or platform code
- inspect source-set placement
- inspect bridge code / expect-actual / injected platform adapter if present
- avoid moving shared logic into platform code unless actually necessary

---

## Architecture rules during bug fixing

### Keep business logic out of composables
Do not fix business/state bugs by pushing more logic into composables.

### Keep ViewModels focused
Do not let bug fixes turn the ViewModel into a dumping ground.
If the fix requires more logic, consider:
- mapper extraction
- validator extraction
- state transformer extraction
- small helper extraction
- domain extraction if the bug exposed real business-logic complexity

### Preserve model boundaries
Do not leak:
- DTOs into UI
- persistence entities into presentation
- platform-specific APIs into shared code

### Preserve module boundaries
Do not bypass the owning module’s API just to land a quick fix.

---

## Shared UI system rules

While fixing bugs, preserve the app’s existing UI conventions.

Use and preserve:
- shared UI components already in the codebase
- spacing tokens and shared styling abstractions when the project has them
- existing typography/theme patterns
- existing strings/localization conventions

Avoid:
- hardcoded spacing values
- ad hoc styling
- hardcoded user-facing strings
- replacing shared components with raw primitives unless required for the fix

---

## Compose bug-fixing rules

When fixing Compose UI bugs:

### Recomposition and state
Check for:
- unstable parameters
- broad state observation
- recreated lambdas/objects
- derived values recalculated repeatedly
- stale captured values in effects

### Side effects
Check:
- `LaunchedEffect` keys
- `DisposableEffect` cleanup
- `snapshotFlow` usage
- scroll timing relative to data updates
- lifecycle-safe triggering of effects

### Lazy lists
Check:
- stable item keys
- bottom padding correctness
- content padding vs input bar overlap
- scrolling to the correct item after data changes
- not repeatedly animating scroll in a janky way

---

## Coroutine and flow rules

When fixing async bugs:

- preserve structured concurrency
- ensure work has clear ownership
- avoid detached jobs
- do not swallow cancellation
- make error handling explicit
- ensure flows are collected in the right place
- avoid duplicate collectors
- do not hide race conditions with superficial guards unless the coordination logic is actually correct

---

## Security and privacy rules

If the bug touches auth, session, files, deep links, WebViews, external input, payments, or PII:
- inspect trust boundaries first
- do not rely on UI gating as security
- do not expose raw internal/backend errors unnecessarily
- avoid logging sensitive values
- validate external input defensively
- verify logout/session cleanup behavior if relevant

Do not introduce insecure shortcuts while fixing a bug.

---

## Tests

Add or update tests where appropriate, especially for:
- mappers
- state transitions
- reload/re-entry behavior at the pure-logic level
- repository coordination
- parsing/serialization
- concurrency-sensitive behavior

If a bug was caused by a mapper/persistence mismatch, add a regression test for exactly that path.

If a bug was caused by state timing, add the strongest test possible at the pure/state-holder level without creating brittle UI tests unnecessarily.

---

## Anti-patterns to prevent

- fixing symptoms without identifying likely root cause
- changing UI when the bug is actually in mapping or persistence
- changing persistence when the bug is actually in UI state/rendering
- large opportunistic refactors during a bug fix
- broad cleanup mixed with the bug fix
- timing hacks without understanding ordering/cause
- swallowing cancellation or exceptions
- patching around duplicate submissions instead of coordinating state properly
- leaking DTOs/entities into UI for convenience
- platform-specific fixes in shared code or shared fixes in platform code without justification
- adding hardcoded strings/styling during the fix
- leaving the bug fixed only for the happy path but broken on reload/re-entry/retry

---

## Required output format

When using this skill, respond in this structure:

1. **Bug classification**
2. **Observed vs expected behavior**
3. **Likely root-cause candidates**
4. **Files/layers to inspect**
5. **Chosen fix strategy**
6. **Implementation**
7. **Tests added or recommended**
8. **Remaining risks / follow-ups**

If the task is clearly an editing task, keep diagnosis concise and then proceed with the implementation.

---

## Final instruction

Fix the bug like an architect who will have to maintain the code later.

Be strict about:
- root-cause correctness
- minimal safe diffs
- architecture boundaries
- state/model integrity
- concurrency correctness
- persistence/re-entry safety
- security/privacy
- regression prevention

Do not optimize for cleverness.
Do not optimize for cleanup unrelated to the bug.
Optimize for the smallest correct, durable fix.
