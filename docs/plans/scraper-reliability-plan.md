# B2 - Scraper throughput and honest failure reporting

Source: `docs/feedback/frontend-user-research.md`, complaint cluster #2 and deal-breaker #2.
Direct precedent: ES-DE #1967 ("6x+ slower than ArkOS"); PFP press coverage on broken box-art
scraping and the pasted Reddit report that scraping is not working.

## Problem

Two separate failures the corpus keeps conflating, both of which apply to PFP.

**Speed.** `ArtworkRepository.fetchForGames` walks games with a sequential `forEachIndexed`
(`ArtworkRepository.kt:231`), one network round trip at a time. Every other loop in that file
(`fetchMissingArtwork:67`, `computeStatus:154`) has the same shape. A large library scrapes at
whatever one connection sustains, which is exactly the ES-DE complaint.

**Legibility.** `MetadataScrapeWorker` reports `succeeded`/`failed` counts and a single
"Artwork scrape failed" notification carrying `e.message`. A user whose ScreenScraper account is
out of quota, whose key is wrong, or whose game simply has no match all see the same "failed"
number. "Scraping is broken" in the corpus is very often "scraping told me nothing useful".

## Goal

Scraping that finishes in a time the user believes, and that names exactly why each game did not
get artwork.

## Approach

### 1. Bounded concurrency

Replace the sequential loop with a bounded-parallelism map, using a `Semaphore` sized from a user
setting. ScreenScraper's per-account thread allowance is real and account-dependent, so make it a
setting in `ArtworkScrapePreferences` with a conservative default and a note that raising it beyond
the account's allowance causes throttling, not speed.

### 2. Respect the server's own limits

Parse ScreenScraper's quota and thread-allowance fields from the API response
(`ScreenScraperApi.kt`) and clamp concurrency to what the account actually permits. Surface the
remaining daily quota in the scrape UI. Back off on 429 rather than counting a failure.

### 3. Typed failure reasons

Introduce a `ScrapeFailure` reason on every failed game: `NoMatch`, `QuotaExceeded`, `AuthFailed`,
`NetworkError`, `RateLimited`, `AssetMissing`, `WriteFailed`. Persist it per game. The summary
becomes "1,842 scraped, 61 no match, 12 network errors, 0 quota" rather than "61 failed".

### 4. A failures screen

After a batch, let the user open the failed list grouped by reason, retry just one group, and
manually search a single game. `NoMatch` is the group that needs manual search; `NetworkError` is
the group that needs a plain retry. Treating them the same is what makes scraping feel broken.

### 5. Resume, do not restart

`MetadataScrapeWorker` uses `ExistingWorkPolicy.KEEP` and cancellation already keeps what was
fetched. Extend that: on re-enqueue, skip games that already succeeded and games that failed with
`NoMatch`, unless the user explicitly asks for a full re-scrape.

## Files touched

- `ArtworkRepository.kt` (the four sequential loops)
- `ScreenScraperApi.kt` (quota and thread-allowance parsing, 429 handling)
- `ArtworkScrapePreferences.kt` (concurrency setting)
- `MetadataScrapeWorker.kt` (richer result data)
- New: `ScrapeFailure.kt`, a per-game failure column plus migration, failures screen in settings

## Tests

- A concurrency setting of 1 reproduces today's sequential ordering exactly.
- A 429 backs off and retries rather than marking failure.
- An auth error fails fast for the whole batch instead of burning through every game.
- Each failure reason round-trips through the worker result and the DB.
- Re-enqueue skips already-succeeded games.

## Risks

- Parallel writes to the artwork store and to Room must be safe. Check the `ArtworkStore` seam for
  filename collisions before raising the default above 1.
- Aggressive concurrency can get a ScreenScraper account temporarily banned. Default low, clamp to
  the server-reported allowance, and never let the setting exceed it.

## Done when

A large library scrapes measurably faster on the same connection, and every game that did not get
artwork has a reason the user can act on.
