# PFP — Media Scan Performance & Freshness Implementation Plan

Scope: the Video / Music / Photo auto scan. Games library scanning is out of scope and is
touched only as the pattern being mirrored.

---

## 1. Problem

Two user-visible symptoms: the media auto scan "takes a long time" and is "not as accurate."
Both are real and have distinct causes.

### 1.1 Slow — the auto pass is one serial batch

`MediaRescanCoordinator.onResume()` and `.onMediaMounted()` both call
`WizardMediaScanRunner.kickoffAll()`, which is:

```kotlin
MediaRootKind.entries.forEach { kind ->
    runCatching { scanMutex.withLock { scan(kind) } }
}
```

One coroutine, one `Mutex`, MUSIC then PHOTO then VIDEO, and inside each `scan(kind)` the roots
are walked with `roots.forEach { }`. Nothing overlaps. Wall time is the **sum** of every root of
every kind.

The same `scanMutex` guards the per-kind `kickoff(kind)` entry point. A user-initiated scan
started from Settings or a context menu therefore queues behind the two unrelated kinds. The
`inFlight[kind]` guard makes it look started while it is actually blocked.

The mutex protects nothing shared: MUSIC, PHOTO and VIDEO touch disjoint repositories.

### 1.2 Slow — per-file probe cost is serial in two of three scanners

- `PhotoScanner` enumerates first (phase 1, cursor-only) then probes with
  `Semaphore(SCAN_PARALLELISM = 4)`.
- `VideoScanner` and `MusicScanner` probe **inline inside the DFS**, one file at a time.

Per new/changed video file `VideoScanner` opens `MediaMetadataRetriever` twice — once in
`readMetadata`, once in `generateThumbnail` — and `generateThumbnail` samples up to five frames
via `getFrameAtTime(OPTION_CLOSEST_SYNC)` looking for one brighter than `BRIGHT_ENOUGH`. That is
the dominant cost, and it is single-threaded.

### 1.3 Inaccurate — the quick-scan reuse key is unsound

`querySafChildren` maps a zero/absent `COLUMN_LAST_MODIFIED` to `null`
(`c.getLong(3).takeIf { it > 0 }`). `Video.lastModified`, `Photo.lastModified` and
`MusicTrack.lastModified` are all `Long?`. The reuse check in `toVideoOrNull` /
`toPhotoOrNull` / `toTrackOrNull` is:

```kotlin
if (!deep && prior != null && prior.lastModified == lastModified) return prior.copy(...)
```

On a provider that reports no mtime (common for USB/MTP and some SD document providers),
`null == null` is true, so **every file is reused forever** and edited content is never picked
up. On a provider with unstable mtime the opposite happens: every file re-probes every pass.
Nothing consults `sizeBytes`, which the cursor already returns.

### 1.4 Inaccurate — library rows are reconciled by exact string equality

Both `WizardMediaScanRunner.dropOrphan*Libraries` and the three settings ViewModels reconcile
with `it.treeUri !in roots`. Any normalization drift between the URI `MediaRootRepository`
persists and the URI stored on the library row drops the row and re-adds it with a fresh id.
`existing` then comes back empty: a full deep re-probe, new UUIDs, and lost resume positions,
favorites and custom titles.

### 1.5 The scan pipeline is duplicated

The scan → collect → notify → `replace*ForLibrary` pipeline exists in at least three places per
kind: `WizardMediaScanRunner.scan{Music,Photo,Video}`, the three
`{Music,Photo,Video}SettingsViewModel.rescan`, and — for photos only —
`XMBViewModel.scanPhotoLibrary`. Adding context-menu scans for video and music by the same
method would produce six copies.

### 1.6 Stale counts are visible in the XMB

Card subtitles render scan-derived counts, so any freshness delay shows up as a wrong number.

---

## 2. Decisions settled

| # | Decision |
|---|----------|
| D1 | The three kinds run concurrently. Roots within a kind stay serial. |
| D2 | Freshness on card open is a cursor-only **tree signature** compare, not a scan. |
| D3 | Every scan entry point routes through one runner API. No new inline copies. |
| D4 | Count subtitles are blanked on media cards **including playlists**. |
| D5 | Blank means blank — no replacement subtitle text. |
| D6 | The signature is a Room column on the library row (migration 43 → 44), not DataStore. |
| D7 | The music scan action lives on the `all_music` memory card. No new folder card type. |
| D8 | T8 is approved: `sizeBytes` joins the per-file reuse key and a null mtime forces a re-probe. |

### D2 in detail — why a signature is the cheap option

The expensive part of a scan is the per-file probe, never the walk. One
`querySafChildren` cursor per directory already returns `name`, `mime`, `lastModified` and
`sizeBytes` for every entry. So:

```
signature = "<fileCount>:<sum of sizeBytes>:<max of lastModified>"
```

is free once the walk has run. On card open: walk cursor-only, compare to the stored signature.
Equal means return — zero probes, zero writes. Different means hand the **already-enumerated**
file list straight into the probe phase, so the tree is never walked twice.

Cost for a 2,000-file library across a few dozen folders: a few dozen IPC queries, no media
decoding. Tens of milliseconds.

It also degrades honestly where §1.3 does not: `sizeBytes` moves even when a provider reports a
null mtime.

### D6 in detail

`music_folders`, `photo_libraries` and `video_libraries` are structurally parallel and all three
already carry `last_scanned_at`. Adding a nullable `scan_signature TEXT` to each is one
`ALTER TABLE` per table with no backfill: a null signature means "never signed", which correctly
forces a scan. DataStore was rejected because keys would orphan every time a library row is
removed — exactly the §1.4 failure mode.

---

## 3. Target design

```
XMB card open ─┐
Context menu ──┤
Settings rescan┼──> MediaScanRunner (one API, per-kind mutex)
Resume / mount ┘         │
                         ├─ MUSIC ─┐
                         ├─ PHOTO ─┼─ concurrent, roots serial within a kind
                         └─ VIDEO ─┘
                                   │
                                   └─> SafTreeWalker (shared, cursor-only)
                                          │
                                          ├─ signature unchanged ─> stop
                                          └─ changed ─> probe phase (bounded parallelism)
```

---

## 4. Execution Task Index

Tasks are ordered by dependency. Each is independently reviewable. No task may exceed its
change budget without stopping and reporting.

---

### T1 — Per-kind mutex; kinds run concurrently  ✅ DONE

**Files** `feature/feature-settings/.../media/WizardMediaScanRunner.kt`

**Change as landed** Replaced the single `scanMutex` with a per-`MediaRootKind` mutex map.
`kickoffAll()` now routes through `kickoff(kind)` for each kind rather than owning a second
code path, which deletes `allInFlight` and leaves one dedupe guard instead of two. The
get-then-put on `inFlight` became an atomic `ConcurrentHashMap.compute`, closing a latent race
where two callers could both launch a scan of the same kind.

The runner also stopped building its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and
now takes the existing `@RescanApplicationScope` — same lifetime, one fewer app-scoped
supervisor, and the scope becomes injectable so the concurrency is testable in virtual time.

**Budget** One file + its test.

**Stop condition** `kickoffAll()` no longer serializes across kinds; a `kickoff(VIDEO)` issued
during an in-flight music scan starts immediately.

**Tests** New `WizardMediaScanRunnerTest`: two kinds with a suspending fake scanner overlap in
time; two calls for the *same* kind do not.

**Note** This is the single highest value-per-line change in the plan. It is deliberately first
and standalone so it can ship without waiting on the rest.

---

### T2 — Extract the shared SAF tree walk  ✅ DONE

**Files** new `core/core-data/.../saf/SafTreeWalk.kt`; the three scanners consume it.

**Change** One iterative-DFS walker returning `List<Pair<SafChild, String>>` (child + relative
path). It carries the union of what the three copies do today: `visitedDirs` cycle guard and
`seenFiles` URI dedupe (currently Photo only), `.nomedia` handling, `isIgnoredDir` pruning, and
an optional `recursive` flag.

`MusicScanner` passes `recursive = true` unconditionally — `MusicFolderEntity` has no
`scan_recursively` column, so music folders are always recursive **by design**. This is not drift
and must not be "fixed."

**Budget** One new file + the DFS block replaced in each of the three scanners. No behavior
change beyond Video and Music gaining the cycle guard and dedupe.

**Stop condition** All three scanners walk through the shared function; existing
`LibraryScannerTest` and the scanner tests still pass.

---

### T3 — Tree signature: column, migration, plumbing  ✅ DONE

**Files** `PFPDatabase.kt` (version 43 → 44, `MIGRATION_43_44`); `MusicFolderEntity`,
`PhotoLibraryEntity`, `VideoLibraryEntity` + their `toDomain`/`toEntity`; the three domain models;
the three repositories; `SafTreeWalk.kt` (signature computation).

**Change** Add nullable `scan_signature TEXT` to the three tables. Compute
`fileCount:sumSize:maxMtime` from the walk result. Persist it alongside `lastScannedAt` in the
existing `replace*ForLibrary` calls.

**Budget** One migration + three entities + three models + three repositories. No UI.

**Stop condition** Migration test in the style of `Migration37To38Test` passes; a completed scan
writes a non-null signature.

**Watch** A raw-SQL index failing Room's post-migration validation has bitten this codebase
before — see the comment above `MIGRATION_39_40`. This migration adds no index, so it should not
apply, but verify rather than assume.

---

### T4 — Signature-gated freshness check on card open  ✅ DONE

**Files** `WizardMediaScanRunner.kt` (new `refreshIfStale(kind, libraryId)`);
`XMBViewModel.kt` hook sites: 2676 `all_videos`, 2697 `vlib_`, 3121 `all_photos`,
3139 `plib_`, 3455 `all_music`.

**Change** On card open, fire-and-forget `refreshIfStale`. It walks cursor-only, compares to the
stored signature, returns on a match, and otherwise feeds the enumerated list into the probe
phase. The card opens immediately either way; Room flows push any delta in.

**Budget** One runner method + five one-line call sites.

**Stop condition** Opening an unchanged card issues no probes and writes nothing. Opening a card
whose folder gained a file updates it without a manual rescan.

**Design constraint** `refreshIfStale` must reuse the walk, not walk twice. If the shape from T2
does not allow that, stop and report rather than adding a second walk.

---

### T5 — Route every scan entry point through the runner  ✅ DONE

**Files** `WizardMediaScanRunner.kt` (per-library entry point); `XMBViewModel.scanPhotoLibrary`
(delete, redirect); `{Music,Photo,Video}SettingsViewModel.rescan` (redirect).

**Change** Give the runner a per-library scan API. Collapse the duplicated
scan→collect→notify→replace pipelines onto it. Settings screens keep their own UI state but stop
owning the pipeline.

**Budget** Net line count should **fall**. If it rises, the abstraction is wrong — stop and
report.

**Stop condition** `photoScanner.scan(` / `videoScanner.scan(` / `musicScanner.scan(` appear in
exactly one non-test call site each.

**Side effect** Context-menu scans then automatically respect the per-kind mutex from T1 instead
of racing the auto pass.

---

### T6 — Context-menu scan for all three kinds  ✅ DONE

**Files** `XMBViewModel.kt` — `openVideoLibraryContextMenu` (2913) and its handler;
new music folder card menu; photo's existing `photo_lib_scan` (3223) redirected onto T5's API.

**Change** Video gains "Scan Library". Music gains an equivalent. Photo's "Scan Album" keeps its
label and behavior but loses its inline pipeline.

**Budget** Menu items + handler branches only. No new scan plumbing — T5 supplies it.

**Stop condition** All three kinds offer a scan action from the card context menu, and all three
go through one code path.

**Resolved (D7)** Music has no folder card in the XMB, so its scan action goes on the
`all_music` memory card's context menu. No new card type.

---

### T7 — Blank the count subtitles  ✅ DONE

**Files** `XMBViewModel.kt` lines 2276 (music total), 2300 (music playlist), 2520 (all videos),
2567 (video library), 2625 (video playlist), 3021 (all photos), 3060 (photo library).

**Change** Subtitle becomes empty per D4/D5. Counts survive in the `Timber.i` scan-complete lines
the scanners already emit, so debug loses nothing.

**Do not touch** `hasScannedFolder` / `hasScannedLibrary` at 2240, 2487 and 2988 — those gate the
getting-started "Add …" rows and are unrelated to display.

**Budget** Seven subtitle expressions.

**Stop condition** No media card renders a scan-derived count.

**Verify on device** Blanking leaves a hole where every other card has two lines. Confirm the
XMB row still looks right before this is called done.

---

### T8 — Sound the per-file reuse key  ✅ DONE

**Files** `VideoScanner.toVideoOrNull`, `PhotoScanner.toPhotoOrNull`,
`MusicScanner.toTrackOrNull`.

**Change** Fold `sizeBytes` into the quick-scan reuse comparison, and treat a null `lastModified`
as "unknown → re-probe" rather than as a match.

**Budget** A few lines per scanner.

**Stop condition** A file on a null-mtime provider is re-probed instead of being reused forever.

**Status** APPROVED (D8). Lands with the T2–T4 spine — without it the T4 signature correctly
detects a changed tree while the per-file pass still reuses the stale row.

---

## 5. Suggested sequencing

T1 ships alone for the immediate win. T2 → T3 → T4 is the freshness spine and should land
together. T5 → T6 is the entry-point cleanup. T7 is independent and can land any time.

---

## 6. Open items

1. **Per-file probing is still serial in Video and Music.** §1.2 named it, no task covered it,
   and none was added — that would have been scope creep. `PhotoScanner` probes with
   `Semaphore(SCAN_PARALLELISM = 4)`; now that all three scanners enumerate before probing, giving
   Video and Music the same treatment is a handful of lines and the largest remaining win.
2. **§1.4 has no task.** URI-normalization drift dropping and recreating library rows is a
   separate, nastier bug — it silently destroys resume positions and favorites. It needs its own
   investigation to confirm it actually occurs in the field before a fix is designed.
3. **Deferring video thumbnail generation** out of the scan entirely (rows land fast, thumbnails
   fill in lazily on display) was raised and is not in this plan. It is the largest remaining
   win for video specifically.
