# Parasite Eve II randomly shows as missing until reboot

**Status:** Fixed in code, unit tests written; device check pending · **Date:** 2026-10-22 ·
**Branch:** `fix/parasite-eve-missing` (from `main` @ `5ee271ea`), not committed yet

## Symptoms

- Parasite Eve II (PlayStation, two discs, `.cue` + `.bin` per disc) now and then disappears from
  the library or shows under Missing. Launching it is refused with "File not found on the last
  scan" (`GameDetailViewModel.kt`, the `selectedGame.isMissing` check).
- The files are still on the device. The game comes back after a reboot.
- It had been reported fixed before (see History), then came back.
- No other game was reported. Games that sit directly in `psx/` were not affected.

## Affected

- AYN Odin 3 (Android), `com.playfieldportal.launcher.lite` 1.3.2-lite (installed 2026-10-18).
- Internal storage, one folder per disc:
  - `/storage/emulated/0/PFP/Roms/psx/Parasite Eve II (USA) (Disc 1)/…cue + .bin`
  - `/storage/emulated/0/PFP/Roms/psx/Parasite Eve II (Disc 2)/…cue + .bin`
- Any game kept in its own subfolder under a console folder is exposed the same way.

## Root cause

Every automatic scan (app resume, storage mount, USB unplug) and every manual scan marks games
missing in one place: `LibraryScanner.scanLocked` → `LibraryReconciler.reconcile`. A game is
flagged missing when its path is not in the scan's "present" set.

1. **A folder that can't be read lists as empty, not as an error.** Android's storage provider
   (`FileSystemProvider.queryChildDocuments` in AOSP; it uses `listFilesOrEmpty` and returns an
   empty cursor for a non-directory) answers an unreadable folder with zero files. The earlier
   late-mount guard (`querySafChildrenOrNull` / `walkRomTree`, commit `01431a7d`) only catches a
   listing that throws or returns no cursor, so an empty answer passed as "this folder is empty".
   With the rest of `psx/` listed fine, the survey was non-empty, the whole-console guard
   (`LibraryReconciler.isTrustedSurvey`) did not trip, and the disc in that folder was flagged
   missing.
2. **An unreadable ROM root was dropped silently.** `ScanSourceResolver.rootScanTargets` used
   `listSubfolderNames`, which turns a failed listing into an empty list. With two ROM roots (say
   internal + SD), the readable one completed and every game under the other was flagged missing.
   Not the cause on this device (one root), but the same class of bug.

Device evidence (logcat, 2026-10-22, read-only): Android killed the storage provider process
(`com.android.externalstorage`) while idle at 01:19:21 and cold-started it at 17:04:08, the moment
the launcher resumed and ran its resume scan. Resume scans therefore often talk to a provider
that has just started up.

**Unverified:**
- That the provider returns an empty listing while starting up. The app's own scan log
  (Settings ▸ Logs) was not available: release builds don't log to logcat and can't be read with
  `run-as`.
- Why it lasted until reboot. A later good scan does clear the flag (`markSeen`). Either the bad
  listing persisted, or no later scan ran (resume scans run at most every 5 minutes,
  `RescanTriggerBus.RESUME_THROTTLE_MS`).

## Fix

- `core/core-data/.../repository/LibraryReconciler.kt`
  - New `gonePaths(romPaths, present)`: a game is only "gone" when the scan saw at least one
    other ROM in the **same folder**. A folder where nothing was seen is withheld: neither marked
    seen nor missing. A warning is logged:
    `Library reconcile — N game(s) left as they were: their folder listed no ROMs`.
  - `reconcile` and `withSurveyedFlags` both use it, so the disc-set primary choice sees the same
    flags that get written.
- `feature/feature-library/.../scanner/RomScanner.kt`: new `listSubfolderNamesOrNull` (null = the
  root could not be listed). `listSubfolderNames` now wraps it, so other callers are unchanged.
- `feature/feature-library/.../scanner/ScanSourceResolver.kt`: if any ROM root can't be listed,
  the card gets an extra source with a null present-set, so `LibraryReconciler` touches nothing.
  It also no longer falls back to the card's raw `romDirectory` in that case.

**Tests that guard it**

- `LibraryReconcilerTest`:
  - `a game folder that listed empty is not flagged missing while the rest of the console was seen`
  - `a gone file is still flagged missing when its folder was seen holding other games`
  - `an already missing game in a folder that listed empty stays missing`
  - `surveyed flags leave a game in a folder that listed empty as stored`
  - The existing `every game going missing at once is applied when the survey is trustworthy`
    still passes and guards against making the guard too wide.
- `ScanSourceResolverTest` (new):
  - unreadable root → null present-set
  - no raw-directory fallback when a root is unreadable
  - readable roots unchanged
  - no roots → raw directory still used

**Known trade-off:** deleting a game's *whole folder* no longer flags that game missing on the
next scan. It stays listed until a launch fails. Deleting one file from a folder that still holds
other ROMs is flagged as before. A follow-up could confirm a gone file at launch time.

## Do not change

- **Don't flag a game missing just because its path is absent.** `LibraryReconciler.gonePaths`
  needs some other ROM seen in the same folder. Removing that check brings this bug back for every
  one-folder-per-game layout (`LibraryReconcilerTest`, the "listed empty" tests).
- **Don't treat an empty folder listing as proof of deletion** anywhere else (new scanners, a
  launch-time check). The provider says "empty" for "can't read".
- **Don't make `ScanSourceResolver` skip an unreadable root and carry on.** It must add the null
  present-set source (`ScanSourceResolverTest`).
- **Keep `querySafChildrenOrNull` / `walkRomTree`'s `complete` flag** (commit `01431a7d`): a
  listing that throws still has to make the whole survey untrusted.
- **Keep `presentRomPaths = null` meaning "unknown"**, never "nothing present"
  (`ScanResult.Complete` KDoc).
- **Keep the whole-console guard** (`isTrustedSurvey`: empty survey + non-empty library → touch
  nothing).

## History (related earlier fixes; don't undo these either)

- `812ef36f`, `144f5047` (Aug 2026): multi-disc sets. The disc-set key cleans the folder name, so
  `Parasite Eve II (USA) (Disc 1)/` and `Parasite Eve II (Disc 2)/` form one set
  (`DiscSetBuilder.discNormalizedFolder`).
- `45b8d2c3` (2026-09-05): region reads of a `.cue`'s sibling `.bin` go through the granted tree
  (`safSiblingDocumentId`), fixing `SecurityException`s on Parasite Eve II's disc folders.
- `5f642976` (2026-09-30): stale `.bin` rows listed by a `.cue` are merged into the `.cue` row
  (`DiscSetReconciler.mergeStaleCompanions`). They used to show as phantom Missing entries. A set's
  primary is the lowest-numbered disc that is present.
- `01431a7d` (2026-10-09), "storage that mounts late at boot":
  - `querySafChildrenOrNull`; `walkRomTree` reports `complete = false` when a listing throws, and
    `scanTree` then sends no present-set.
  - `RescanTriggerBus` runs one more scan when a mount arrives mid-scan.
  - The same idea for media folders (`FolderAccess`, `WizardMediaScanRunner` retries).
- `b45953e4` (2026-10-18), "Repair PS2 cue scanning":
  - PS2 got the `cue` extension it was missing (DB migration 57 → 58; a user-edited extension
    list is left alone).
  - `DiscCompanionSuppressor` / `DiscImageResolver` only let a sheet hide its `.bin` files when
    the card can scan that sheet's extension (`scannableSheets`). Otherwise a card without `.cue`
    lost cue+bin discs entirely.
  - **Do not remove the `scannableSheets` check** (`CardDiscCompanionScanTest`,
    `DiscCompanionSuppressorTest`).

Note: the "consolidated scanner" (`WizardMediaScanRunner`, `MediaRescanCoordinator`, commits
`bc905591` and `2fa18c9a`) is the music/photo/video scanner, not the ROM scanner. ROM scans have
their own single path: `LibraryScanner` → `ScanSourceResolver` → `RomScanner` →
`LibraryReconciler`.
