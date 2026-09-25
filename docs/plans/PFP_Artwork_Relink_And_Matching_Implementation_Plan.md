# PFP — Artwork Relink, Matching Accuracy and Orphan Recovery Implementation Plan

Scope: the artwork **relink** path — `ArtworkImportManager.relinkLibrary`, the matcher that feeds
it, where it can be triggered from, and what happens to the files it cannot place. The scrape
pipeline, the Artwork Studio, the import/export workers and the identity index are touched only
where this plan names them, and are otherwise out of bounds.

Reference material: **ES-DE** (MIT, source read at `gitlab.com/es-de/emulationstation-de`) and
**Beacon** (`com.radikal.gamelauncher`, closed commercial, inspected only at DEX-string level).
Ideas only — no code from either is copied, and no API shape from either is mirrored.

> **ES-DE is not the model for artwork tracking.** ES-DE has no artwork records at all: it
> resolves media by filename convention plus an `exists()` probe at read time. PFP's
> `artwork_records` table, `ArtworkIdentityIndex` and the six-tier `RelinkOwnerLookup` are
> strictly ahead of that and are **not** to be reworked, simplified, or replaced by a
> convention-and-probe scheme. The only thing borrowed from ES-DE is the *region-rank* idea in
> §9 (T6).

---

## 1. Problem

Three distinct user-visible failures.

### 1.1 Relink is buried and fragile

The only user-invokable relink is the **Scan & Relink Library** row at
`feature/feature-settings/.../ui/ArtworkImportScreen.kt:121`, four levels deep under
Settings ▸ Artwork ▸ Artwork Import. It calls
`ArtworkImportViewModel.relinkLibrary()` (`.../viewmodel/ArtworkImportViewModel.kt:286`), which
runs the whole walk on `viewModelScope`:

```kotlin
fun relinkLibrary() {
    if (_uiState.value.relinking) return
    _uiState.value = _uiState.value.copy(relinking = true)
    viewModelScope.launch { runCatching { importManager.relinkLibrary() } … }
}
```

Leaving the screen cancels the scope and kills the walk part-way. There is no notification, no
progress, and no record that it ran. For a 50k-file library that is a guaranteed partial result.

### 1.2 Automated updates never relink

`RescanTriggerBus` (`feature/feature-library/.../scanner/RescanTriggerBus.kt`) is the automated
library-freshness path. On `AppResumed` (5-minute throttle), `MediaMounted` and `UsbDisconnected`
its `scanNow` runs exactly two things:

```kotlin
runCatching { romRootDiscoveryScanner.discover() }
val outcomes = libraryScanner.scanAllEnabled(removeMissing = true)
```

New game rows land in the DB with null artwork columns. Nothing ever connects them to artwork
already sitting in the linked folder. This is the gap behind "I copied my art over and the new
games are still blank."

### 1.3 Files that match nothing are discarded silently

`ArtworkImportManager.kt:468`:

```kotlin
if (ids.isNullOrEmpty()) { orphans++; continue }
```

Only a count survives. The filename, its platform, its kind and its URI are dropped on the floor,
so there is nothing a recovery UI could be built on. The Settings notice says "N unmatched" and
the user has no way to find out which N.

Two secondary facts about that counter, both verified:

- The grid-shape guard a few lines above (`ArtworkImportManager.kt:432`) also does `orphans++`
  for a landscape file in `covers/`. That is a **deliberate skip**, not an unmatched file, and it
  is conflated into the same number today.
- `artwork_import_reports` only ever gets a row from an *import* run (`ArtworkImportExecutor`).
  A relink writes no report at all, so not even the count is durable.

### 1.4 The matcher misses the single most common ROM-naming convention

`ArtworkImportMatcher.PlatformIndex` has four passes: exact ROM stem, display title, simplified
title, then a dump-index-prefix retry of all three. All of them compare strings that
`ArtworkNaming.simplifyTitle` produced, and that function does not touch word order or numerals.

So No-Intro/Redump's trailing-article convention never meets the natural title:

| Folder file | Game row | Simplified forms | Result |
| --- | --- | --- | --- |
| `Legend of Zelda, The - Ocarina of Time.png` | `The Legend of Zelda: Ocarina of Time` | `legend of zelda the ocarina of time` vs `the legend of zelda ocarina of time` | **miss** |
| `Final Fantasy VII.png` | `Final Fantasy 7` | `final fantasy vii` vs `final fantasy 7` | **miss** |

These are not exotic. They are the dominant shape of a scraped/downloaded artwork pack dropped
next to a No-Intro ROM set.

---

## 2. Goals

| # | Goal |
| --- | --- |
| G1 | The user can start a **full-library relink from the All Games memory card** and it completes whether or not they stay on screen. |
| G2 | A **scan that adds games automatically relinks** those games' platforms, with no user action. |
| G3 | The matcher stops missing article-inverted and roman-numeral titles, without ever guessing. |
| G4 | Files that still match nothing are **retained**, listed, and manually assignable. |
| G5 | (Optional) ScreenScraper asset selection honours a user region and arbitrates competing assets by region position. |

## 3. Non-goals

- **Do not touch `ArtworkNaming`'s existing functions.** `normalizeForMatch`, `simplifyTitle`,
  `slug` and `TAG_GROUPS` are frozen at `NORMALIZATION_VERSION = 1`. See AD-2.
- **Do not rework the identity index, `RelinkOwnerLookup`, or `RelinkSlotOrdering`.**
- **Do not add network calls anywhere in this feature.** Relink is offline today and stays
  offline; the orphan picker is explicitly offline (AD-7).
- **Do not change the import (`ArtworkImportPlanner`/`ArtworkImportExecutor`) flow's behaviour**
  beyond inheriting the new matcher pass.
- **Do not change the scrape pipeline**, except T6's severable region change.
- **Do not delete or move any file on disk.** Relink is read-only against the folder; that
  invariant survives every task here.

---

## 4. Architectural decisions

| # | Decision | Rules out |
| --- | --- | --- |
| **AD-1** | Retained orphans live in a **new Room table `artwork_orphan_files`** (migration 46 → 47), keyed `(platform_id, artwork_type, file_name)`. | Extending `artwork_import_reports.summary_json`. A blob cannot be queried per platform, cannot be partially replaced by a platform-scoped relink, and would force a whole-library parse to render one platform's picker. |
| **AD-2** | The new matching rules live in a **new pure object `TitleCanon`** in `feature-artwork/.../match/`, beside `TitleKey`. `ArtworkNaming` is not edited. | Any change inside `ArtworkNaming`. `slug()` calls `normalizeForMatch` and shares `TAG_GROUPS`; editing either would rename every existing user's on-disk folders. `TitleCanon` must never be reachable from `slug()`. |
| **AD-3** | Pass 5 is added **inside `PlatformIndex.matchStem`, below the `bySimplified` pass**, with a new `MatchConfidence.CANONICAL_TITLE` inserted between `SIMPLIFIED_TITLE` and `INDEXED_FILENAME`. | A separate matcher entry point. Living inside `matchStem` is what makes pass 4's dump-index retry inherit pass 5 and the `INDEXED_FILENAME` degradation for free. |
| **AD-4** | Pass 5 **auto-matches only on exactly one candidate**, exactly as `bySimplified` does. Several candidates → `Result.Ambiguous`. | Any scoring, any nearest-match, any tie-break heuristic. This is the valve that makes the `Mega Man X`/`Mega Man 10` collision survivable. |
| **AD-5** | Every user-invoked relink runs through a **new `ArtworkRelinkWorker`** (unique work, `KEEP`, reporting to `BackgroundTaskCenter` with `TaskKind.ARTWORK`), modelled on `MetadataScrapeWorker`. | Adding a second `viewModelScope` caller. The Settings button's fire-and-forget behaviour is a defect (§1.1), not a pattern to copy — T3b migrates it onto the same worker. |
| **AD-6** | The automated path uses a **platform-scoped** relink (`relinkLibrary(scope = Platforms(ids))`), triggered only when `scanAllEnabled` reports `added > 0`, and it **performs no missing-file sweep and clears no game column**. | A whole-library walk on every app resume; and a scoped walk that deletes records. A scoped pass is purely additive. See §7.2 for the argument. |
| **AD-7** | The orphan picker searches **the user's own games on that platform, locally**, and only on an **explicit submit**. No provider calls, no network, no search-as-you-type, no results rendered before submit. | Reusing `SearchOnlineScreen` / `StudioQuery`'s provider path. Only the *normalizer* (`TitleKey` + `TitleCanon`) is reused, never the fetch. |
| **AD-8** | Assigning an orphan writes an `artwork_records` row **plus** an `ArtworkIdentityIndex` entry, and does **not** rename or move the file. | A rename-to-portable-name on assign. The record's `(platform, kind, portableName)` key is already a tier-2 hit in `RelinkOwnerLookup`, so the next relink reconnects the file by its existing name; the identity row makes it survive a later rename. |
| **AD-9** | `ArtworkImportManager` gains a **single `Mutex` guarding the relink walk**. Full and scoped relinks serialize against each other. | Concurrent walks. Today nothing prevents the Settings button, `PcGameScanner`, `migrateV1IfNeeded` and a new automated trigger from overlapping; two walks sharing the missing sweep is a data-loss shape. |
| **AD-10** | T6 (region rank) is **severable**. It touches only `SsMediaSelection` + a new preference, and can be dropped without affecting T1–T5. | Coupling region work into the relink or matcher tasks. |

### Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Bump `NORMALIZATION_VERSION` to 2 and change `simplifyTitle` in place | The frozen-rules contract in `ArtworkNaming`'s KDoc exists precisely because `slug()` output is a live SAF directory name. A rewrite renames folders under every shipped user. A new stage below the existing one costs nothing and breaks nothing. |
| A whitespace-stripping pass (`superman64` == `super man 64`) | Explicitly out of scope. It collapses genuinely distinct titles and its value is a rounding error next to the article and numeral rules. |
| Fuzzy/edit-distance scoring with a threshold | Turns "never guess" into "guess below N edits". `Result.Ambiguous` already has a UI destination (import review) and now a second one (the orphan picker). |
| Storing orphans in `artwork_import_reports.summary_json` | AD-1. |
| Scoping the automated relink by **game id** rather than platform id | `LibraryScanner.scanAllEnabled` returns `List<PlatformScanOutcome>` with `added: Int` — a count, not ids (`LibraryScanner.kt:46-54`). Platform is the finest scope available without changing the scanner's contract, which is a non-goal. |
| Running the scoped relink inside `RescanTriggerBus`'s own coroutine | `RescanTriggerBus` is in `feature-library` and has no artwork dependency; the relink would also die with the app process. T5 injects a narrow interface and enqueues the same worker. |
| Rewriting relink as ES-DE-style convention + `exists()` probes | §0 note. Strictly a regression. |

---

## 5. Existing systems to reuse

| System | File | Why |
| --- | --- | --- |
| `RelinkOwnerLookup` | `feature-artwork/.../importer/RelinkOwnerLookup.kt` | The six-tier owner resolution. Unchanged; pass 5 arrives through its `fuzzyMatch` lambda. |
| `ArtworkImportMatcher.PlatformIndex` | `.../importer/ArtworkImportMatcher.kt` | Pass 5's only home. |
| `TitleKey` | `.../match/GameMatcher.kt:16` | The existing comparison form; `TitleCanon` composes on top, it does not replace it. |
| `MetadataScrapeWorker` | `.../api/MetadataScrapeWorker.kt` | The established worker shape: `UNIQUE_NAME`, `ExistingWorkPolicy.KEEP`, `tasks.start/progress/complete/fail`, cooperative cancellation, `enqueue`/`cancel` companions. |
| `BackgroundTaskCenter` + `TaskKind.ARTWORK` | `core-ui/.../notification/BackgroundTaskCenter.kt`, `core-domain/.../BackgroundTaskInfo.kt:46` | The shared sink for the in-app panel and the Android shade. |
| `ArtworkIdentityRecorder` | `.../portable/ArtworkIdentityRecorder.kt` | The index's only reader/writer. The picker buffers through it, like the walk does. |
| `ArtworkRecordDao.upsert(row)` | `core-data/.../dao/ArtworkRecordDao.kt:19` | Single-row write for the picker. |
| `ArtworkPathResolver` | `.../portable/ArtworkPathResolver.kt` | `kindForMediaDir`, `relativePath`. |
| `SETTINGS_SCREEN_ROUTES` + `SettingsNavHost` | `feature-settings/.../ui/SettingsNavHost.kt:11,84` | Where a new full-screen surface is registered. Both the set and the `when` must be updated — `SettingsHierarchyTest` enforces it. |
| XMB context-menu dispatch | `feature-xmb/.../viewmodel/XMBViewModel.kt:5696` (build), `:6211` (dispatch) | Where the new All Games item is added and routed. |

---

## 6. Corrections to the grounding brief

Found while reading. All verified in the tree at `8f5ed2c`.

1. **`MatchConfidence` ordinals ARE compared.** `ArtworkImportPlanner.kt:93`:
   `item.confidence.ordinal < existing.confidence.ordinal`. The brief said nothing compares
   ordinals. The **conclusion is still correct** — inserting `CANONICAL_TITLE` between
   `SIMPLIFIED_TITLE` and `INDEXED_FILENAME` preserves the relative order of every existing
   member, so the "strongest match wins" tie-break keeps its meaning and canonical-title hits
   correctly rank above index-prefixed ones. But T2 must state this, and the test suite must
   pin the ordering rather than only equality.
2. **The All Games dispatch is not at 8259/8389.** It is `XMBViewModel.kt:6211`
   (`menu.isAllGames -> …`), with the platform-card branch at `:6220`. `openAllGamesContextMenu`
   is at `:5696`, which matches.
3. **`ArtworkImportPlanner` is a fifth relink-matcher consumer.** The brief listed four callers of
   `relinkLibrary`; separately, `PlatformIndex` is used by the *import planner*
   (`ArtworkImportPlanner.kt:66`) as well as by relink. Pass 5 changes import matching too. That
   is desirable, but it must be an acknowledged blast radius, not a surprise.
4. **A relink writes no report row.** The brief implied `artwork_import_reports` was at least an
   option for orphan storage; it is only ever written by the import executor, so choosing it
   would also require inventing a relink report. Reinforces AD-1.
5. **`ArtworkImportManager.kt:323` `val rootDocId = …` is dead.** Assigned and never read inside
   `relinkLibrary`. Harmless; noted so nobody "fixes" it mid-task. Out of scope.
6. **There is no user region preference anywhere.** `ArtworkScrapePreferences` has five keys, none
   about region, and `GameEntity.region` is per-game scrape metadata, not a user setting. T6 must
   create the preference, which is why it is the largest of the optional items.

---

## 7. Design detail

### 7.1 Pass 5 — `TitleCanon`

A new pure object, JVM only, no Android types:

```
TitleCanon.of(raw): String
```

Applied to the output of `ArtworkNaming.simplifyTitle` (so tags, `&`, and punctuation are already
handled), then:

1. **Article rule.** Strip a leading **or** trailing `the` when it is a whole token. English only.
   `legend of zelda the ocarina of time` and `the legend of zelda ocarina of time` both lose their
   `the`. No other article, no other language.
2. **Numeral rule.** Convert a standalone roman-numeral token to arabic, **including bare `i`,
   `v`, `x`. `final fantasy vii` → `final fantasy 7`.
3. Collapse whitespace, trim.

**No whitespace-stripping pass.** Out of scope, deliberately.

Accepted collision: `mega man x` and `mega man 10` both canonicalize to `mega man 10`. AD-4's
uniqueness valve turns that into `Result.Ambiguous` — a review item, never a wrong link.

Index and lookup, mirroring `bySimplified` exactly:

```kotlin
private val byCanonical = buildMap<String, MutableList<Long>> {
    games.forEach { g ->
        listOfNotNull(g.romStem, g.displayTitle, g.scrapedTitle)
            .map { TitleCanon.of(ArtworkNaming.simplifyTitle(it)) }
            .filter { it.isNotBlank() }.distinct()
            .forEach { getOrPut(it) { mutableListOf() }.add(g.id) }
    }
}
```

and in `matchStem`, **after** the `bySimplified` block:

```kotlin
val canonical = TitleCanon.of(simplified)
if (canonical.isNotBlank() && canonical != simplified) {
    byCanonical[canonical]?.let { ids ->
        return if (ids.distinct().size == 1)
            Result.Matched(ids.distinct(), MatchConfidence.CANONICAL_TITLE)
        else Result.Ambiguous(ids.distinct())
    }
}
```

Two behaviours that must be preserved and tested:

- If `bySimplified` already returned `Ambiguous`, `matchStem` returns before pass 5. Correct —
  a looser pass must not rescue a title the tighter pass already found genuinely ambiguous.
- Pass 4 (`match`'s index-prefix retry) re-enters `matchStem`, so it inherits pass 5, and the
  `result.copy(confidence = MatchConfidence.INDEXED_FILENAME)` degradation still applies to a
  canonical hit. `ArtworkImportMatcher.kt:63-71` is unchanged.

### 7.2 Scoped relink and the missing sweep

`relinkLibrary` gains a scope parameter:

```kotlin
sealed interface RelinkScope {
    data object FullLibrary : RelinkScope
    data class Platforms(val platformIds: Set<String>) : RelinkScope
}
```

`FullLibrary` is the default and is byte-for-byte today's behaviour. `Platforms`:

- filters `library.platformDirs(tree)` to the named ids before the walk;
- builds `priorRecords` the same way but **uses it only for provenance preservation and locked
  lookups**;
- **runs no missing-file sweep and clears no game column.**

**Why no sweep, even restricted to the scoped platforms.** A platform-scoped walk does visit every
media dir of its platforms, so a platform-restricted sweep would be *logically* sound. It is
rejected on risk, not logic: the sweep is the only destructive operation in the whole relink, its
correctness depends on every `listChildren` call in the walk having succeeded, and the automated
trigger fires on every app resume and every media mount — precisely the moments when a SAF
provider is most likely to return a short or empty listing. A transient failure that costs a
user-invoked relink one bad run would, on the automated path, silently delete records repeatedly.
Making the sweep a property of *how the relink was triggered* is also exactly the
behaviour-depends-on-caller trap the brief warns about, so it is not merely *disabled* for scoped
runs — it is structurally unreachable from `RelinkScope.Platforms`, asserted by a test.

Additionally: the games a scoped relink exists to serve are **newly added rows**, which by
definition have no prior records to sweep.

`RelinkResult.missingFiles` is `0` for a scoped run, and the result gains a
`scope: RelinkScope` field so a caller can tell the two apart without inferring it from a zero.

### 7.3 Orphan retention

New table, one row per file the walk could not place:

```sql
CREATE TABLE IF NOT EXISTS artwork_orphan_files (
    platform_id   TEXT    NOT NULL,
    artwork_type  TEXT    NOT NULL,
    file_name     TEXT    NOT NULL,
    stem          TEXT    NOT NULL,
    document_uri  TEXT    NOT NULL,
    size_bytes    INTEGER NOT NULL,
    seen_at       INTEGER NOT NULL,
    PRIMARY KEY(platform_id, artwork_type, file_name)
)
```

plus `CREATE INDEX … ON artwork_orphan_files(platform_id)`.

Replacement semantics, which is the whole reason for a table:

- A `FullLibrary` relink clears the table and rewrites it.
- A `Platforms` relink clears **only the scoped platforms' rows** and rewrites those.
- Assigning a file in the picker deletes that one row.

The grid-shape skip at `ArtworkImportManager.kt:432` does **not** produce an orphan row — it is a
deliberate skip of a file that belongs to a different kind. It keeps its counter increment. (If
that conflation is ever worth fixing, it is its own task; it is not this one.)

### 7.4 Orphan picker flow

One full-screen settings surface, route `settings_artwork_orphans`, reachable from the Artwork
Import screen and from the relink task's completion notification action.

1. List retained orphans grouped by platform, each row showing file name and kind.
2. Selecting a row opens a title field **pre-filled with the file's stem**.
3. The user edits and **presses Search** (or the gamepad confirm). Nothing is queried, ranked or
   rendered before that press. No debounce, no `snapshotFlow` on the text, no `LaunchedEffect(query)`.
4. Results are the **user's own games on that file's platform**, ranked by, in order:
   exact `TitleKey.of` equality → `TitleCanon.of` equality → canonical-token containment.
   Ranking is a pure function and is unit-tested; the ViewModel only calls it.
5. Picking a game runs AD-8: `ArtworkRecordDao.upsert(row)` with `source = "manual_link"`,
   `userAssigned = true`, `portableName = fileStem`, `relativePath` from
   `ArtworkPathResolver.relativePath`; the matching game column is set if it is empty/dead/remote
   (same replaceability rule the walk uses); an `ArtworkIdentityIndex.Entry` is buffered through
   `ArtworkIdentityRecorder` and flushed; the orphan row is deleted.

---

## 8. Sequencing

T1 and T2 are the tasks that **reduce how often the picker is needed**, and their effect is
measurable (`RelinkResult.orphanEntries` before vs after, over the same folder). They land first
and their effect is recorded before any picker UI exists — otherwise the picker gets designed for
a volume of orphans that T2 was about to eliminate.

```
Phase 1 — Substrate and accuracy   T1  T2
Phase 2 — Entry points             T3  T3b   (T3 needs T1 for the orphan count in its summary)
Phase 3 — Recovery                 T4        (needs T1's data, wants T2's reduction first)
Phase 4 — Automation               T5        (needs T1's scoped clearing semantics)
Phase 5 — Optional, severable      T6
```

**Measurement gate between Phase 1 and Phase 3.** After T2 ships, run a full relink on a real
library and record `orphanEntries` against the pre-T2 number in the task's verification notes. T4
does not start until that number exists.

---

## 9. Tasks

### T1 — Retain unmatched files in a queryable table

**Scope.** Migration 46 → 47 creating `artwork_orphan_files`; `ArtworkOrphanFileEntity` +
`ArtworkOrphanFileDao`; registration in `PFPDatabase`; `relinkLibrary` collecting
`(platformId, kind, fileName, stem, documentUri, sizeBytes)` at `ArtworkImportManager.kt:468`
instead of only incrementing; scope-aware clear-and-rewrite per §7.3. No UI.

**Out of bounds.** The grid-shape skip's counter. Any change to what counts as a match.

**Change budget.** 3 new files (entity, dao, migration block is inside `PFPDatabase.kt`),
3 modified (`PFPDatabase.kt`, `DatabaseModule.kt`, `ArtworkImportManager.kt`), 1 new test file.
Also the exported schema JSON under `core/core-data/schemas/`.

**Tests.** Room migration test 46 → 47 (the repo exports schemas; follow the existing migration
test convention). A DAO test for per-platform replace. Pure-logic coverage of "orphan rows for
platform P are replaced, platform Q's are untouched".

**Stop when.** A relink writes orphan rows and a second relink over the same folder produces the
same rows, no duplicates.

---

### T2 — `TitleCanon` and matcher pass 5

**Scope.** New `feature-artwork/.../match/TitleCanon.kt` per §7.1; `CANONICAL_TITLE` added to
`MatchConfidence` between `SIMPLIFIED_TITLE` and `INDEXED_FILENAME`; `byCanonical` index and the
pass-5 block in `ArtworkImportMatcher.PlatformIndex`.

**Out of bounds.** `ArtworkNaming` — not one character. `TitleCanon` must not be referenced from
`slug()` or from anything `slug()` calls. No whitespace-stripping pass. No scoring.

**Change budget.** 1 new file, 2 modified (`ArtworkImportMatcher.kt`, `ImportModels.kt`),
2 test files extended + 1 new.

**Tests** (this repo unit-tests pure matching heavily; match that density):
- `TitleCanonTest` — leading `the`, trailing `the`, `the` inside a title left alone, `theme`
  not mangled, `I`/`V`/`X`/`VII`/`XIII` conversion, `x` in `mega man x` converting (the accepted
  cost, pinned as intended behaviour), idempotence, blank input.
- `ArtworkImportMatcherTest` — `Legend of Zelda, The - Ocarina of Time.png` → the natural-title
  game at `CANONICAL_TITLE`; `Final Fantasy VII.png` against a `Final Fantasy 7` row; two games
  canonicalizing alike → `Ambiguous`, never `Matched`; an already-`Ambiguous` simplified result
  is **not** rescued by pass 5; `0556 - Legend of Zelda, The.png` → `INDEXED_FILENAME`, proving
  pass 4 inherits pass 5 and still degrades.
- One test pinning `MatchConfidence` **order**: `EXACT_FILENAME < DISPLAY_TITLE <
  SIMPLIFIED_TITLE < CANONICAL_TITLE < INDEXED_FILENAME`, because `ArtworkImportPlanner.kt:93`
  compares ordinals (§6.1).
- `ArtworkNamingTest` — a test asserting `slug()` output is unchanged for the article and numeral
  cases, i.e. proving `TitleCanon` did not leak into the frozen path.

**Stop when.** The tests pass and a full relink on a real library reports a lower
`orphanEntries` than before. **Record both numbers** — Phase 3 is gated on them.

---

### T3 — Relink Artwork on the All Games context menu, via a worker

**Depends on** T1 (the completion summary reports the retained orphan count).

**Scope.** New `ArtworkRelinkWorker` in `feature-artwork/.../api/`, modelled on
`MetadataScrapeWorker`: `UNIQUE_NAME = "pfp_artwork_relink"`, `TASK_ID = "artwork_relink"`,
`ExistingWorkPolicy.KEEP`, `TaskKind.ARTWORK`, cooperative cancellation, completion action
routing to the Artwork Import screen. A fourth item `XMBContextMenuItem("relink_artwork",
"Relink Artwork")` in `openAllGamesContextMenu()` (`XMBViewModel.kt:5696`) and its dispatch in the
`menu.isAllGames` branch (`XMBViewModel.kt:6211`). The relink walk gets per-platform progress
reporting (`tasks.progress`) — a callback parameter on `relinkLibrary`, rate-limited the way
`MetadataScrapeWorker.onProgress` is. AD-9's `Mutex`.

**Out of bounds.** The Settings button (that is T3b). Any change to what the walk *does*.

**Change budget.** 1 new file, 2–3 modified (`XMBViewModel.kt`, `ArtworkImportManager.kt`, and a
manifest/DI touch if the worker needs one), 1 test file.

**Tests.** A `XMBViewModel` test that the All Games menu contains the new item and that selecting
it enqueues (inject a seam rather than calling `WorkManager` from the test). The existing All Games
menu assertions must be updated, not deleted.

**Stop when.** The item appears, starts a relink that survives navigating away, and shows progress
and a settled row in the notification panel.

---

### T3b — Migrate the Settings relink button onto the same worker

**Depends on** T3.

**Scope.** `ArtworkImportViewModel.relinkLibrary()` enqueues `ArtworkRelinkWorker` instead of
launching on `viewModelScope`; the screen's `relinking` state is driven by the worker's state /
`BackgroundTaskCenter` rather than a local boolean.

**Out of bounds.** `onFolderPicked` (`ArtworkImportViewModel.kt:134`), `InitialSetupViewModel.kt:441`
and `PcGameScanner.kt:220`. Those three call `relinkLibrary` as a *step inside* a larger operation
that already owns its own progress and must stay synchronous. Leave them alone and say so in the
code comment.

**Change budget.** 2 modified files, no new files.

**Stop when.** Leaving the Artwork Import screen mid-relink no longer cancels it.

---

### T4 — Orphan picker

**Depends on** T1, T2, and the Phase 1 measurement.

**Scope.** Route `settings_artwork_orphans` added to both `SETTINGS_SCREEN_ROUTES` and the
`SettingsNavHost` `when`; a screen + ViewModel per §7.4; a **pure** `OrphanTitleRanker` object
doing the local ranking; the assign operation per AD-8, exposed as a method on
`ArtworkImportManager` so the ViewModel does not touch DAOs directly (that is the manager's
stated contract).

**Out of bounds.** Any provider call. Any network permission. Any search-as-you-type,
`LaunchedEffect(query)`, debounce, or pre-submit result render — this is a hard product
requirement, and a reviewer should be able to confirm it by grepping the ViewModel for a single
`search()` entry point. Renaming or moving files.

**Change budget.** 2–3 new files (screen, ViewModel, ranker), 3 modified
(`SettingsNavHost.kt`, `ArtworkImportScreen.kt` for the entry row, `ArtworkImportManager.kt`),
1–2 test files. Exceeding this needs a stated reason.

**Tests.** `OrphanTitleRankerTest` — exact `TitleKey` hit ranks above canonical hit ranks above
containment; a query matching nothing returns empty; ranking is stable. `SettingsHierarchyTest`
already enforces route/`when` parity and will fail if only one is updated — let it.

**Stop when.** A retained orphan can be searched, assigned, and disappears from the list; a
subsequent relink reconnects the same file by its record without the user doing anything.

---

### T5 — Scoped relink wired into the automated path

**Depends on** T1 (scoped orphan clearing), T3 (the worker).

**Scope.** `RelinkScope` and the scoped walk per §7.2; `RelinkResult.scope`; a narrow interface
(e.g. `ArtworkRelinkTrigger` with `fun relinkPlatforms(ids: Set<String>)`) owned by
`feature-artwork` and injected into `RescanTriggerBus`, implemented by enqueuing
`ArtworkRelinkWorker` with a platform-ids input; `RescanTriggerBus.scanNow` calling it after
`scanAllEnabled` with the ids of outcomes where `added > 0`, and **not at all** when that set is
empty.

**Out of bounds.** Changing `LibraryScanner` or `PlatformScanOutcome`. Making the relink block the
scan. Any missing-file sweep on the scoped path. Any column clearing on the scoped path.

**Change budget.** 1–2 new files (the interface and its impl), 3 modified
(`ArtworkImportManager.kt`, `RescanTriggerBus.kt`, and the worker), 2 test files.

**Tests.**
- `RescanTriggerBusTest` (the existing suite uses the `RescanClock` seam) — no relink trigger when
  every outcome has `added == 0`; a trigger carrying exactly the platforms with `added > 0`;
  failures in the relink trigger do not fail the scan.
- A test asserting the sweep is **structurally unreachable** from `RelinkScope.Platforms` — i.e.
  a scoped run over a platform whose records point at deleted files leaves those records intact
  and returns `missingFiles == 0`.

**Stop when.** Dropping a new ROM into a watched folder, resuming the app, and having its artwork
appear with no user action — and a resume that adds nothing doing no artwork work at all.

---

### T6 — Region rank for ScreenScraper media selection *(optional, severable, lowest priority)*

**Depends on** nothing in T1–T5. Can be dropped entirely.

**Scope.** `SsMediaSelection.bestUrl` (`SsMediaSelection.kt:39-42`) currently hardcodes
`us → wor → null → any` and throws away *where* the winner matched. Replace with a
`bestMedia(medias, type): Pair<String, Int>?` returning the URL and a numeric `regionPos` over
the walk `{userRegion, wor, us, eu, jp, cus, firstAvailable}` — the shape ES-DE uses
(`ScreenScraper.cpp:659-671`), reimplemented, not copied. Use `regionPos` to arbitrate the
competing-asset cases `urls()` currently decides by bare `?:` ordering — notably
`logoUrl = bestUrl("wheel") ?: bestUrl("wheel-hd")`, which today prefers a plain `wheel` from a
worse region over a `wheel-hd` from a better one. Add a user region preference to
`ArtworkScrapePreferences` (there is none today — §6.6) with a row in the Artwork settings screen.

**Out of bounds.** The scrape pipeline's structure, the cache schema, `SsGameInfo`.

**Change budget.** 3 modified files (`SsMediaSelection.kt`, `ArtworkScrapePreferences.kt`, the
Artwork settings screen), 1 test file extended.

**Tests.** `SsMediaSelectionTest` (already exists) — region walk order incl. a non-default user
region; `regionPos` returned; a `wheel-hd` at a better region beating a `wheel` at a worse one;
`firstAvailable` fallback; empty list.

**Stop when.** The existing `SsMediaSelectionTest` cases still pass unchanged in meaning and the
new arbitration cases pass.

---

## 10. Verification

Per task, plus these repo-wide gates. Run from the repo root.

```bash
./gradlew :feature:feature-artwork:testDebugUnitTest
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest :feature:feature-library:testDebugUnitTest
```

```bash
./gradlew :core:core-data:testDebugUnitTest
```

```bash
./gradlew :app:assembleFullDebug
```

Device check for T3/T3b/T5 (the user runs this; the implementer does not drive the device):

```bash
./gradlew :app:installFullDebug
```

Manual, on device:
- T3 — All Games ▸ Relink Artwork, then immediately navigate away; the notification panel shows
  progress and a settled summary row.
- T3b — same, started from Settings ▸ Artwork ▸ Artwork Import.
- T4 — a known-unmatchable file appears in the picker, searching is inert until Search is pressed,
  and assigning it sticks across a relink.
- T5 — add a ROM, background the app for >5 min, resume; artwork appears unprompted.

---

## 11. Execution Task Index

| ID | Task | Depends On | Effort | Status |
| --- | --- | --- | --- | --- |
| T1 | Retain unmatched files in `artwork_orphan_files` (migration 46 → 47) | None | M | READY |
| T2 | `TitleCanon` + matcher pass 5 + `CANONICAL_TITLE` | None | M | READY |
| T3 | `ArtworkRelinkWorker` + Relink Artwork on the All Games menu | T1 | M | READY |
| T3b | Migrate the Settings relink button onto the worker | T3 | S | BLOCKED (on T3) |
| T4 | Orphan picker (local, explicit-submit search) | T1, T2, Phase 1 measurement | L | BLOCKED (on T1, T2) |
| T5 | Scoped relink wired into `RescanTriggerBus` | T1, T3 | M | BLOCKED (on T1, T3) |
| T6 | Region rank in `SsMediaSelection` *(severable)* | None | M | READY |

Status key: `READY` · `IMPLEMENTING` · `VERIFYING` · `DONE` · `BLOCKED`.
Effort: S = under a day · M = a few days · L = a week or more.

`DONE` requires: acceptance criteria met, the task's tests written and passing, the change budget
respected or the overrun justified in writing, and no known blocker.
