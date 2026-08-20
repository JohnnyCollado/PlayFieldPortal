# A1 - Deep library scan module

Source: `docs/feedback/architecture-review-20260820-013228.html`, Candidate 01 (Strong, top
recommendation).

## Problem

The ROM survey and Missing-reconciliation policy exists twice.

- [LibraryManagerViewModel.kt:532-608](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/LibraryManagerViewModel.kt) —
  `scanConsole` resolves sources, seeds the existing-path set from DB rows plus scan tombstones,
  collects every source flow, upserts new games, unions `presentRomPaths`, tracks `scanErrored`,
  calls `LibraryReconciler`, then records the scan and recounts.
- [LibraryRescanCoordinator.kt:30-158](../../feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/LibraryRescanCoordinator.kt) —
  repeats the same loop headlessly for resume/mount/unplug triggers. Its own comment names the
  duplication.

`ScanSourceResolver` is shared, but the policy around it is not. Any scanner fix (trust rules,
tombstone handling, error propagation, present-path union) must be made twice or it drifts.

## Goal

One module owns "survey these sources and reconcile the result". Both callers keep their own
distinct interface: the ViewModel maps the result to UI state and messages, the coordinator owns
trigger timing.

## Approach

1. Add `LibraryScanner` (new file in `feature/feature-library/scanner/`) with one entry point:

   ```
   suspend fun scanPlatform(platformId: String, removeMissing: Boolean): PlatformScanOutcome
   suspend fun scanAllEnabled(removeMissing: Boolean): List<PlatformScanOutcome>
   ```

   `PlatformScanOutcome` carries `platformId`, `displayName`, `added`, `markedMissing`,
   `errorMessage`, `surveyTrusted`. No Android UI types, no `_scratch` state.
2. Move into it, verbatim first, then deduplicated: source resolution, existing-path seeding
   (DB rows + `scanTombstoneDao`), the collect loop, upserts, the `present` union with its
   null-on-untrusted semantics, `libraryReconciler.reconcile`, `recordScan`, `recountGames`.
3. `LibraryManagerViewModel.scanConsole` becomes: guard on `scanningPlatformIds`, keep the PS Vita
   special case, call `scanPlatform`, render the outcome into `_scratch.message`.
4. `LibraryRescanCoordinator` keeps throttle/debounce/single-flight and calls `scanAllEnabled`.
   Delete its copy of the loop.
5. Leave the PS Vita path (`scanVitaGames`) in the ViewModel for now - it has no ROM folder and
   does not fit the source model. Note it as a follow-up.

## Files touched

- New: `feature/feature-library/.../scanner/LibraryScanner.kt`
- New: `feature/feature-library/src/test/.../scanner/LibraryScannerTest.kt`
- Edit: `LibraryManagerViewModel.kt` (shrinks), `LibraryRescanCoordinator.kt` (shrinks)
- Unchanged: `ScanSourceResolver.kt`, `LibraryReconciler.kt`, `RomScanner.kt`

## Tests

Move the guard fixtures that currently live in the manual-scan and triggered-scan test modules onto
`LibraryScannerTest`:

- new ROMs upserted once when two roots both contain the same path
- tombstoned paths never re-added
- `present == null` from any source disables removals for the whole platform
- scan error disables removals even when other sources surveyed cleanly
- `recordScan`/`recountGames` only fire when something changed

Keep one thin test per caller proving it delegates (ViewModel message text, coordinator throttle).

## Risks

- The two loops may have quietly diverged already. Diff them line by line before merging and record
  every behavioural difference chosen in the commit body.
- `recountGames` and `recordScan` currently only run when `added > 0 || removed > 0`. Preserve that
  exactly; changing it silently alters last-scanned timestamps.

## Done when

Both callers contain no scan-policy code, the new test module covers the guards, and the existing
manual and triggered scan tests still pass unmodified in intent.
