# Missing ROM Detection and Tracking

Keep the library fresh as ROM files come and go, without ever destroying user
data. When a scanned game's file disappears, the game is flagged missing (not
deleted); when the file returns, the game reactivates automatically.

## Goal

Two user needs, one mechanism:

1. New ROMs the user adds (download, PC transfer) should appear without manually
   pressing Rescan.
2. ROMs whose files are gone should stop showing dead, unlaunchable tiles.

Both fall out of running an incremental scan at the right moment and applying a
non-destructive write policy to the result.

## Core principle: state vs visibility

A missing game is never deleted. Its row, favorite flag, play stats, artwork,
and collections all survive. Missing-ness only changes what is *shown*:

- Hidden from every normal view (grids, favorites, recently played, counts).
- Visible only in a dedicated Missing bucket that explains why.

When a later scan sees the file again, the flag clears and the game reappears
everywhere it was before. Real deletion is only ever the user's explicit
"Remove permanently" action inside the Missing bucket.

This design makes a false positive (a game wrongly flagged missing because a card
was briefly unreadable) fully recoverable: it shows up in Missing, the user sees
it, and the next good scan pulls it back out. The dangerous action -- destroying
a curated entry -- is engineered out.

## Data model

Two additive columns on `games` (schema v37, `MIGRATION_36_37`, non-destructive):

- `is_missing INTEGER NOT NULL DEFAULT 0` -- flag. Default 0 means every existing
  row starts "not missing"; the library behaves exactly as before until a scan
  runs.
- `last_seen_at INTEGER` (nullable) -- millis of the last scan that confirmed the
  file present. NULL = never confirmed yet. Reserved for a future grace period
  ("only flag as missing if unseen for > N"); NULL, not 0, so old rows do not
  read as "last seen in 1970."

## Platform constraint: SAF only

The app scans via the Storage Access Framework (`content://` document trees), not
raw filesystem paths. This rules out true OS-level change detection:

- `FileObserver`/inotify needs raw filesystem read access -- the very permission
  SAF-only avoids. Not usable.
- SAF has no reliable change-notification API. `ContentObserver` on a tree does
  not fire dependably for files added by other apps / MTP / a card swap, so it is
  a trap, not a solution.

Therefore "detection" is not live watching; it is running the existing scan at a
smart moment and diffing the result. `RomScanner` already reports the set of
present ROM paths (`ScanResult.Complete.presentRomPaths`), so no new detection
machinery is needed.

## Safety model (the write policy)

Reconciliation runs after a scan and applies:

- Present paths -> `markSeen` (clear `is_missing`, stamp `last_seen_at`).
- Gone paths (DB paths not in the survey) -> `markMissing` (set `is_missing = 1`;
  `last_seen_at` left untouched, since it records the last time the file *was*
  present).

Removals are applied only when the survey is trustworthy. The reconciler bails
(touches nothing) when:

- The scan reported an error, or
- The present-set is null (a source could not survey -- unmounted card,
  permission loss; the caller passes null, never an empty set, in that case), or
- The present-set is empty while the library is non-empty (a half-mounted card;
  an empty survey against real games is a mount problem, not a mass deletion).

The DAO writes take an explicit, already-diffed path list -- never a
`WHERE rom_path NOT IN (...)` sweep -- so a bad scan cannot mass-flag the library
even if a guard were bypassed.

## Triggers (Phase 5, built)

SAF-only means the trigger is a cheap rescan at a user-present moment, not a
watcher. Two signals, plus a manual path:

- `onResume` -- weak, frequent signal (fires after every game launch). Covers the
  common "download / leave and come back" case. Must be time-throttled.
- `ACTION_MEDIA_MOUNTED` -- strong signal. Covers the "added from PC" case: the
  files become readable on remount/unplug, and this is when they appear. Runtime-
  registered (like `InstallShortcutReceiver`). May bypass the time-throttle.
- `ACTION_MEDIA_UNMOUNTED` and friends -- do nothing to the database. Unmount is
  the untrustworthy state the removal guard exists for; never diff on it.

Three guards, three jobs:

- Single-flight (mutex) -- one scan per root at a time; correctness.
- Debounce -- coalesce the mount broadcast burst.
- Throttle -- skip `onResume` scans when `memory_cards.last_scanned_at` is recent;
  battery.

Strong signals (mount) bypass the throttle but still respect single-flight and
debounce.

## UI (Phase 6, not yet built)

Mirror the existing Shiba "Untracked" pattern (`ShibaLibraryMode.UNTRACKED`): a
sibling view of rows, each carrying a reason. The Missing bucket:

- Reason per row ("File not found on last scan").
- Launch disabled.
- "Remove permanently" -- the explicit user delete (real delete + tombstone).
- Re-add-to-reactivate: dropping the file back clears the flag on the next scan.

## Module map

| Concern | Module |
| --- | --- |
| Schema, DAO, entity | `core-data` |
| Repository interface | `core-domain` |
| Repository impl, reconciler | `core-data` |
| Manual-scan wiring | `feature-settings` (`LibraryManagerViewModel`) |
| Triggers | `app` (`MainActivity`) + `feature-xmb` |
| Missing UI | `feature-xmb` |

Note: the reconciler lives in `core-data` (not `core-domain`) because
`core-domain` is a pure module without `javax.inject`.

## Phases

- [x] Phase 1 -- Data layer: schema v37, migration, entity fields, mappers,
  `Migration36To37Test`.
- [x] Phase 2 -- DAO: `is_missing = 0` filter on all display queries and counts;
  `markSeen` / `markMissing` / `observeMissing`; internal lookups left unfiltered.
- [x] Phase 3 -- Repository: expose the three DAO methods through `GameRepository`.
- [x] Phase 4 -- `LibraryReconciler` + non-destructive write policy; wired into the
  manual scan (flags instead of deleting).
- [x] Phase 5 -- Triggers: `onResume` (throttled) + `MEDIA_MOUNTED`, calling the
  reconcile path; single-flight / debounce / throttle.
- [ ] Phase 6 -- Missing bucket UI + "Remove permanently".
- [ ] Phase 7 -- Verify: add/remove/re-add matrix, pull-card-nothing-vanishes,
  remount reconciles, no wasted walks on rapid resume.

## Decisions settled in Phase 5

- Throttle interval N for `onResume`: **5 minutes** (`RESUME_THROTTLE_MS`).
- Lifecycle owner: **`Activity.onResume`** (`MainActivity`), not `ProcessLifecycleOwner`.
  PFP is a single-activity launcher, so `onResume` already is the "backed out of a
  game" moment, and it avoids adding an `androidx.lifecycle:lifecycle-process`
  dependency the project does not otherwise have.
- `MediaMountReceiver` does not use `goAsync()`: a pending broadcast result must be
  finished within ~10s, but a full SAF walk plus the 2s debounce can exceed that. The
  receiver is registered only while `MainActivity` lives, so the foreground activity
  keeps the process alive for the scan instead.
