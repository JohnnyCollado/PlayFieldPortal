# ADR-0001: LibraryScanner owns ROM survey and Missing reconciliation

- **Status:** Accepted for implementation
- **Date:** 2026-08-20
- **Scope:** `feature:feature-library` scan path and its settings/trigger callers

## Context

The ROM survey and Missing-reconciliation policy currently exists in two loops:

- `LibraryManagerViewModel.scanConsole` serves the settings interface.
- `LibraryRescanCoordinator.scanCard` serves app-resume, media-mount, and USB-unplug triggers.

Both loops resolve sources, seed existing paths from library rows and scan tombstones, collect source
flows, upsert new games, union present paths, propagate trust failures, reconcile Missing rows, and
record changes. `ScanSourceResolver` is shared, but the policy around it is duplicated. That makes
future scanner changes drift-prone and leaves the important behavior with poor locality.

The architecture review identified a deepening opportunity: one module should own this policy while
callers retain their distinct interfaces and scheduling responsibilities.

## Decision

Create a `LibraryScanner` module in `feature:feature-library/scanner/` with these responsibilities:

1. Survey one configured Memory Card's ROM sources.
2. Seed and grow the existing-path set using database rows plus scan tombstones.
3. Upsert newly discovered games exactly once across multiple sources.
4. Union `presentRomPaths` and treat a source error or null survey as untrustworthy.
5. Optionally invoke `LibraryReconciler` when `removeMissing` is requested.
6. Record the scan timestamp and recount only when new games were added or rows were marked Missing.
7. Scan enabled, configured ROM-backed Memory Cards sequentially and deterministically.
8. Return structured per-card outcomes; an unexpected card failure becomes an outcome and does not
   abort the remaining cards.
9. Return an explicit skipped outcome when source resolution produces no usable source.
10. Retain the first actionable source error plus an untrusted-survey flag; do not broaden the
    outcome into a provider-specific error taxonomy yet.
11. Fail a card before surveying when the database snapshot or tombstone lookup fails. An incomplete
    existing-path set is not safe for upserts or Missing reconciliation.
12. If an individual upsert fails, keep successful prior writes as a partial result, skip Missing
    reconciliation for that card, and return the first write failure.
13. Share per-card single-flight state across manual and triggered callers. A busy card returns a
    skipped outcome rather than queuing stale work or racing a source walk.
14. Enforce an injected IO dispatcher inside the module; callers do not need to remember where the
    scan runs.

The initial shape is:

```text
scanPlatform(platformId, removeMissing): PlatformScanOutcome
scanAllEnabled(removeMissing): List<PlatformScanOutcome>
```

`PlatformScanOutcome` carries the platform identity, display name, added count, Missing count,
trust status, skip/busy state, and the first actionable error message when applicable. It contains no
UI state or Compose types. Cancellation propagates normally; only real failures become outcomes.

Eligibility for `scanAllEnabled` is explicit: enabled Memory Cards must have a configured ROM source
and supported ROM extensions. PS Vita's ux0/app scan and Windows PC import remain separate special
paths because they do not fit the ROM-source model.

The module reuses the existing injected collaborators and `ScanSourceResolver` flow-factory seam.
No additional source interface is introduced before a second real adapter requires it.

The extraction is staged as a behavior-preserving move: first move the existing scan loop verbatim,
then deduplicate and simplify callers after parity tests pass.

## Consequences

### Positive

- ROM survey and Missing policy gain locality in one deep module.
- A scanner fix has leverage across settings scans and triggered rescans.
- Focused `LibraryScanner` tests become the primary test surface; caller tests only verify UI mapping
  and trigger scheduling.
- A future rescan-trigger module can depend on a stable scan seam.

### Negative

- The two existing loops may contain quiet behavioral differences that must be compared before the
  move.
- The settings interface still needs a thin caller-specific guard for per-platform scanning state.
- PS Vita and Windows paths remain special cases and are not unified by this decision.

## Alternatives rejected

- **Platform scan only:** leaves enabled-card orchestration duplicated in the trigger path.
- **Concurrent card scans:** changes SAF/provider contention, ordering, and persistence timing without
  evidence that parallelism is needed.
- **Always reconcile:** changes the current manual-scan behavior, where ordinary scans do not mark
  absent ROMs Missing.
- **New scanner-source interface:** the existing `ScanSourceResolver` seam is already real and is
  sufficient for this extraction; another adapter would add indirection without leverage.
- **Empty existing-path fallback:** treating a failed database read as an empty set could re-add
  tombstoned or already-known games, so the safer untrusted outcome is chosen.
- **Queued single-flight:** stale queued scans add work after the useful survey already ran; a busy
  outcome is easier for callers to ignore and keeps the trigger module lean.

## Verification obligations

Before implementation is considered complete:

- Compare both current loops line by line and record every intentional difference.
- Prove duplicate paths across multiple sources are upserted once.
- Prove tombstoned paths are not re-added.
- Prove null present sets and source errors disable Missing writes for the whole platform.
- Prove changed-only `recordScan` and `recountGames` semantics.
- Prove database/tombstone read failures do not scan or mutate the library.
- Prove partial upsert failure skips Missing reconciliation and returns an actionable outcome.
- Prove manual and triggered requests for the same Memory Card share single-flight behavior.
- Prove cancellation is not converted into an error outcome.
- Keep the existing coordinator guard tests and add thin caller-delegation coverage.
