# Plan: ScreenScraper media-URL caching (+ optional maxthreads-aware rate limit)

## Goal

Cut ScreenScraper (SS) scrape time on our end. Two independent changes, in priority order:

1. **Media-URL caching** — persist the full media-URL list from every `jeuInfos` response so
   later scrapes of newly-enabled artwork kinds skip the metadata call entirely and go straight
   to downloads. Saves ~1.1 s + server latency per game on any re-run.
2. **(Optional, smaller) Honor the account's real `maxthreads`** — the rate limiter currently
   hardcodes single-flight at 1.1 s spacing; donor accounts are allowed 5+ threads.

Do **not** change what gets downloaded, artwork storage layout, or the portable library format.

## Background / current behavior

- `ScreenScraperApi.fetchGameInfo()` ([ScreenScraperApi.kt](../feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/api/ScreenScraperApi.kt))
  calls `jeuInfos.php`. The response's `medias` array contains URLs for **every** artwork kind
  SS has for the game, but parsing keeps only a fixed set of named fields on `SsGameInfo`
  (`artworkUrl`, `boxArtUrl`, `box3dUrl`, `physicalMediaUrl`, `heroUrl`, `logoUrl`, `manualUrl`,
  `videoUrl`). Everything else is discarded.
- When a game was matched before, its SS id is stored on the game row (`GameEntity.ssId`) and
  the next lookup queries by `gameid` — the match itself is already cached. What is NOT cached
  is the media-URL list, so enabling a new artwork kind later still costs one `jeuInfos` call
  per game just to re-learn URLs we were already served once.
- All SS API calls are serialized through `rateLimited {}` in `ScreenScraperApi`
  (mutex + `MIN_REQUEST_INTERVAL_MS = 1_100`).
- Scrape orchestration lives in `MetadataRepository.kt` (per-game fetch + asset downloads) and
  `MetadataScrapeWorker.kt` (`MODE_MISSING` default / `MODE_ALL`).
- Room DB is at **version 27** in `PFPDatabase.kt`; schema JSON is exported to `/schemas/`;
  destructive migration is forbidden — every schema change needs a real `Migration`.

## Part 1 — media-URL cache

### 1a. Persist the raw media list

Add a small cache keyed by SS game id. Recommended shape: a new Room entity/table
`ss_media_cache`:

| column        | type    | notes                                        |
|---------------|---------|----------------------------------------------|
| `ssId`        | Long PK | ScreenScraper game id                        |
| `mediasJson`  | String  | the `medias` array as served (or a trimmed projection: `type`, `region`, `url`, `format`) |
| `fetchedAt`   | Long    | epoch millis, for staleness display/debug    |

- New DAO (`SsMediaCacheDao`): `get(ssId)`, `upsert`, `clearAll`.
- DB version 27 → 28 with a real `MIGRATION_27_28` (plain `CREATE TABLE`), registered alongside
  the existing migrations in `PFPDatabase.kt` / `DatabaseModule.kt`.
- Write path: in the code path where a successful `jeuInfos` response is parsed, also upsert the
  raw/trimmed media list. Keep this inside feature-artwork; core-data only gains the
  entity/DAO (follow how existing feature-owned tables are wired).

### 1b. Read path — skip `jeuInfos` when the cache can serve

In `MetadataRepository` (the per-game scrape step):

- If the game has `ssId` AND the cache has an entry for it AND every *needed* artwork kind for
  this run resolves to a URL from the cached media list → skip `fetchGameInfo` entirely and go
  straight to downloads using cached URLs.
- "Needed kinds" = whatever the current scrape options request minus what the game already has
  (existing skip logic stays authoritative).
- Metadata text fields (description, genre, etc.): if the game row already has them, nothing
  more is needed; if the run also wants to fill missing text metadata, that still requires
  `jeuInfos` — don't serve half from cache in that case, just do the normal call (and refresh
  the cache from its response).
- URL selection from the cached `medias` array must reuse the exact same type/region preference
  logic the live parser uses (extract that selection into a shared function rather than
  duplicating it — the mapping from `medias[]` → per-kind URL currently lives inside the
  response parsing in `ScreenScraperApi`).

### 1c. Dead-URL fallback (required)

SS occasionally moves media. If a download from a cached URL fails with 404/410:

- Invalidate that game's cache row, do one fresh `jeuInfos` (normal rate-limited path), refresh
  the cache, retry the download once with the fresh URL.
- Any other failure (network, 429/430) keeps existing behavior — do not add retries there.

### 1d. Cache hygiene

- "Re-scrape all" (`MODE_ALL`) must bypass the cache read (it exists to pick up upstream
  changes) but should refresh cache entries from its responses.
- Add "Clear ScreenScraper URL cache" to wherever artwork maintenance actions live in settings
  (near the existing artwork scan/recovery actions).
- No TTL needed — dead-URL fallback covers staleness.

## Part 2 (optional, separate commit) — maxthreads-aware rate limiting

- `ssuserInfos.php` already returns `maxthreads` (parsed on `SsUser.maxThreads`, currently
  unused). When user credentials are present, fetch/refresh it once per scrape run.
- Replace the single mutex in `rateLimited {}` with a semaphore of
  `min(maxthreads, 4)` permits (cap defensively), keeping a per-permit minimum spacing so a
  1-thread free account behaves exactly as today (1.1 s single-flight).
- On any 429 (`RATE_LIMITED`), drop back to single-flight for the rest of the run.
- Without user credentials or on parse failure: current behavior, unchanged.

## Constraints

- Kotlin multi-module project; follow existing module boundaries (SS specifics stay in
  `feature-artwork`; only the entity/DAO/migration touch `core-data`).
- Room: real migration, `exportSchema` stays true (a new schema JSON lands in `/schemas/`).
- No new dependencies.
- Match surrounding code style, Timber logging patterns, and the existing comment tone.

## Verification

- Unit tests: cache hit skips `fetchGameInfo`; cache miss falls through; dead-URL fallback
  refreshes and retries once; `MODE_ALL` bypasses the read path; migration 27→28 creates the
  table (Room migration test if the project has that harness, otherwise schema assert).
- `./gradlew :feature:feature-artwork:test :core:core-data:test` green, then full `assembleDebug`.
- On-device sanity check happens later on the release APK (project rule: never validate
  behavior on the debug build) — not part of this task, just don't claim device-verified.

## Acceptance criteria

1. Second scrape run that only adds a new artwork kind performs **zero** `jeuInfos` calls for
   games with a cached media list containing that kind's URL (verify via Timber logs).
2. Dead cached URL degrades gracefully into one fresh lookup + one retry, not a hard failure.
3. Fresh install and 27→28 upgrade both open the DB without data loss.
4. Free-account (1-thread) behavior is byte-for-byte unchanged unless Part 2 is implemented.
