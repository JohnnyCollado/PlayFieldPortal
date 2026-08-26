# Multi-Disc — Status and Follow-Ups

Working notes for `multi-disc-games-plan.md` (C1). The full plan is implemented on the
`polishing-UI` branch as of 2026-08-26 — this file now records what shipped, which commits landed
it, and what optional backlog remains. No uncommitted work remains.

## Shipped

- [x] Steps 1–3 (`812ef36`): `DiscTag`, `DiscSetBuilder`, schema v38 (`disc_set_key`/`disc_number`/
      `is_disc_primary`), `MIGRATION_37_38`, wiring into all three scan paths
      (`RomScanner.scan`/`scanDirectory`/`scanTree`).
- [x] Step 4: companion suppression — `DiscSheets` (cue/gdi parsers), `DiscCompanionSuppressor`
      (SAF), `DiscImageResolver` `.gdi` handling, shared cue parser.
- [x] Step 5: projection — `observeAll`/`observeFavorites`/`countGamesByPlatform` +
      `observeAllGames`/`observePlatformGames`; XMB display surfaces switched.
      Deliberately unprojected: `observeByPlatform` (scan baselines) and `observeGamesOnly`
      (per-disc achievement matching) — pinned by `GameDaoProjectionTest`.
- [x] Incremental reconciliation (`6e637d6`): `DiscSetBuilder.reconcile` (derive + diff),
      `M3uPlaylistReader` extraction, `DiscSetReconciler` (single owner of reconcile-and-persist)
      wired into `LibraryScanner.scanLocked`, XMB manual Memory Card scan, and
      `LibraryManagerViewModel` ROM-root autoload.
- [x] Step 6 — Disc picker on game detail: `getDiscSetMembers` DAO query + `GameRepository`
      exposure; `GameDetailUiState.discMembers`/`selectedDiscId`/`showDiscPicker`; picking a
      non-primary disc launches that disc's path; the projected one-row-per-set list opens the
      picker.
- [x] Step 7 — Set-level Missing: display queries require at least one present member, and
      `observeMissing()` shows one primary row per fully-missing set ("The Missing bucket — one
      primary per fully missing set, plus ordinary missing games"). `LibraryReconciler` keeps its
      explicit-path per-row contract; the projection supplies the set semantics.
- [x] Legacy multi-folder scan reconciled: `LibrarySettingsViewModel`'s direct
      `romScanner.scan(folders, NEW_FILES_ONLY, …)` path groups new games by platform and runs
      `DiscSetReconciler.reconcilePlatform` per platform.
- [x] Structural one-primary-per-set invariant (`2c00f66`): partial unique index
      `ON games(disc_set_key) WHERE disc_set_key IS NOT NULL AND is_disc_primary = 1` → schema
      v39 + `MIGRATION_38_39`, applied on upgrade and on fresh installs.
- [x] Live-data verification (D:\Emulators\Roms): separator-agnostic paths; m3u-unifies-per-disc-
      subfolders pinned for backslash and forward-slash layouts.

Test conventions: hermetic JUnit + mockk; test-first. Suites: feature-library (DiscTag,
DiscSetBuilder, DiscSheets, DiscCompanionSuppressor, DiscImageResolver, LibraryScanner,
LibraryRescanCoordinator, ExistingRomPathResolver), feature-settings (LibraryManagerViewModel),
feature-xmb.

## Backlog / open questions (not required for the plan)

- [ ] Confirm artwork behavior for non-primary discs (`ArtworkImportMatcher` is disc-aware via
      `-discN` slugs) — is set-level art (one row per set) desired, and does it need a change?
- [ ] Optional UI polish: disc-count badge on set rows (e.g. "2 discs") using the projected lists.
- [ ] End-to-end on-device/desktop run against `D:\Emulators\Roms\psx` with a real sibling
      `.m3u` (needs user permission to create a temporary m3u next to the per-disc folders).
