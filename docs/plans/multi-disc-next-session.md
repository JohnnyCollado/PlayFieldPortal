# Multi-Disc — Next Session Task Plan

Continuation of `multi-disc-games-plan.md` (C1). Working notes + ordered task list, not a
design doc. Verify the current git branch/status first — the multi-disc patch (steps 1–5 plus
reconciliation) is uncommitted in the working tree.

## State at the end of the previous session

- [x] Steps 1–3: `DiscTag`, `DiscSetBuilder`, schema v38 (`disc_set_key`/`disc_number`/
      `is_disc_primary`), `MIGRATION_37_38`, wiring into all three scan paths
      (`RomScanner.scan`/`scanDirectory`/`scanTree`).
- [x] Step 4: companion suppression — `DiscSheets` (cue/gdi parsers), `DiscCompanionSuppressor`
      (SAF), `DiscImageResolver` `.gdi` handling, shared cue parser.
- [x] Step 5: projection — `observeAll`/`observeFavorites`/`countGamesByPlatform` +
      `observeAllGames`/`observePlatformGames`; XMB display surfaces switched.
      Deliberately unprojected: `observeByPlatform` (scan baselines) and `observeGamesOnly`
      (per-disc achievement matching) — pinned by `GameDaoProjectionTest`.
- [x] Live-data verification (D:\Emulators\Roms): separator-agnostic paths; m3u-unifies-
      per-disc-subfolders pinned for backslash and forward-slash layouts.
- [x] Incremental reconciliation: `DiscSetBuilder.reconcile` (derive + diff), `M3uPlaylistReader`
      extraction, `DiscSetReconciler` (single owner of reconcile-and-persist) wired into
      `LibraryScanner.scanLocked`, XMB manual Memory Card scan, and `LibraryManagerViewModel`
      ROM-root autoload.

Test conventions: hermetic JUnit + mockk; test-first (write the failing test, then implement).
Full suites green at last run: feature-library 77, feature-settings 51, feature-xmb compiles.

## Next tasks (in order)

### 1. Step 6 — Disc picker on game detail (M)
- [ ] Find the game-detail entry point (feature-xmb `GameDetailViewModel` + detail screen) and
      the launch path that resolves `romPath`/`romUri`.
- [ ] Add a DAO query for a set's members (`WHERE disc_set_key = :key`) and expose it through
      `GameRepository`; the projected platform/All-Games queries already return the primary.
- [ ] Picker state: default selection = the set's primary; launching a non-primary disc must
      launch that disc's `romPath`/`romUri`, not the primary's.
- [ ] Test-first: picker selection + launch resolution unit tests (red → green).
- [ ] Wire into the detail screen; verify the projected list (one row per set) opens the picker.

### 2. Step 7 — Set-level Missing in LibraryReconciler (M)
- [ ] A set is missing only when *every* disc is missing; the primary row represents the set in
      the Missing bucket.
- [ ] Inspect `LibraryReconciler.reconcile` / `markSeen` / `markMissing` — currently per-row;
      extend to set granularity without mass-sweeping (the reconciler's explicit-path contract).
- [ ] Mind the interplay with `DiscSetReconciler` upserts (they preserve `is_missing`).
- [ ] Test-first: reconciler tests for partial vs. full-set disappearance.

### 3. Reconcile the legacy multi-folder scan (S)
- [ ] `LibrarySettingsViewModel` line ~182 calls `romScanner.scan(folders, NEW_FILES_ONLY, …)`
      directly — the only scan path not reconciled. Group its new games by platform and run
      `DiscSetReconciler.reconcilePlatform` per platform (needs a per-platform baseline fetch).

### 4. Structural one-primary-per-set invariant (S, optional)
- [ ] Partial unique index `CREATE UNIQUE INDEX … ON games(disc_set_key) WHERE is_disc_primary = 1`
      → schema v39 + migration + validation, mirroring the v38 migration test pattern. The
      builder already guarantees it; the index guards against future drift.

## Backlog / verification ideas

- [ ] Confirm artwork behavior for non-primary discs (`ArtworkImportMatcher` is disc-aware via
      `-discN` slugs) — is set-level art (one row per set) desired, and does it need a change?
- [ ] Optional UI polish: disc-count badge on set rows (e.g. "2 discs") using the projected lists.
- [ ] End-to-end on-device/desktop run against `D:\Emulators\Roms\psx` with a real sibling
      `.m3u` (needs user permission to create a temporary m3u next to the per-disc folders).
