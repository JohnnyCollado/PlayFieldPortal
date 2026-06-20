---
name: kotlin-kmp-refactor-safety
description: Refactor discipline for existing codebases. Enforces scope control, migration safety, compatibility, observability, and tests to keep refactors reviewable and low-risk.
license: Apache-2.0
metadata:
  version: "1.0.0"
---

# KMP Refactor Safety Skill

Use this skill when the task is primarily a refactor, migration, reliability hardening, or architectural cleanup of existing code (not a greenfield feature).
This skill is about **change discipline** and **reviewability**, not system architecture itself. Pair it with your architecture/style/testing skills as needed.

## Primary objective

Deliver refactors that are:
- minimal in surface area
- safe to review and merge
- free of parallel/competing implementations
- regression-resistant (tests + observability)

## Non-negotiables

### 1) Scope control (no opportunistic rewrites)
- Touch only files required to achieve the goal.
- Do not reformat, rename, or “clean up” unrelated code.
- Avoid broad mechanical changes unless explicitly requested.
- Keep diffs small and intention-revealing.

### 2) No parallel implementations
- Do not introduce a new pattern while leaving the old pattern active.
- End state must be one of:
  - old path removed, OR
  - old path gated behind a **feature flag** with a clear removal plan (explicit TODO + tracking issue).
- Avoid duplicate sources of truth. If ownership must move, move it once and rewire callers deterministically.

### 3) Preserve public contracts unless required
- Keep public APIs stable (interfaces, model shapes, routing/public endpoints, configuration surface).
- If a contract must change:
  - update all call sites in the same change-set
  - document the breaking change in PR notes/release notes
  - add targeted tests proving compatibility and intent

### 4) Refactor in safe phases
Prefer this order:
1. **Foundation**: introduce the new core abstraction (e.g., manager/store/service) and wire it without changing behavior.
2. **Adoption**: migrate existing call sites incrementally to the new abstraction.
3. **Lock-in**: remove the old path or gate it behind a flag (default off) and make the new path the default.
4. **Cleanup**: delete dead code, consolidate configuration, reduce complexity.

Never ship an ambiguous half-state where both old and new paths may run without a flag and clear routing.

### 5) Observability (redacted)
For flows prone to edge cases (auth, payments, retries, background/foreground, webhooks, state restoration):
- add logs/metrics around transitions and decisions
- never log secrets (tokens, passwords, codes, personal data)
- log only:
  - booleans/branch decisions
  - status codes and operation names
  - hashed or truncated IDs if needed (e.g., last 4 chars)
- ensure logs can be disabled or are appropriate for production environments

### 6) Tests are part of the refactor
Minimum expectations:
- unit tests for pure logic/state transitions/mappers
- regression tests for the bug or failure mode motivating the refactor
- coverage for:
  - success path
  - failure path
  - concurrency/race behavior when applicable (mutex, retry, idempotency)
- avoid brittle tests; prefer deterministic inputs/outputs and stable assertions

### 7) Idempotency and re-entrancy for callbacks
For events that can repeat (callbacks, deep links, webhooks, background resume):
- handle duplicates safely
- validate inputs before side effects
- operations should be safe to retry without corrupting state

### 8) Backwards compatibility and migration safety
- Use versioning or feature flags for behavior changes that might break existing users.
- Provide migration steps (data migrations, config updates) in a single place.
- If data shape changes:
  - define transitional support window
  - ensure rollback strategy (or non-destructive migrations)

### 9) Performance and resource safety
- Avoid adding disk/network reads on hot paths (e.g., per-request storage reads).
- Prefer caching with explicit invalidation when correctness requires it.
- Ensure retries are bounded (max 1 retry unless specified) and do not create loops.

### 10) PR hygiene (reviewable output)
- Prefer small, coherent commits with clear messages.
- Avoid reordering code blocks unless necessary.
- Include PR notes:
  - What changed
  - Why
  - How to test
  - Flags/migration steps
  - Rollback plan (if relevant)

## Quick refactor checklist
- [ ] Single source of truth after refactor
- [ ] No stale in-memory state alongside persisted state (unless intentionally synchronized)
- [ ] No competing interceptors/validators/plugins that overwrite each other
- [ ] Duplicate events/callbacks are handled idempotently
- [ ] Failure modes are explicit and do not cause silent data loss/logouts
- [ ] Tests cover the motivating regression and key edge cases
- [ ] Clear migration/flag/rollback notes included
