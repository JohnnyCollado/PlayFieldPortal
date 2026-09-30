# Hidden Achievement Descriptions via Steam Hunters

## Context

Local Steam (LOCAL_STEAM, emulated Windows games) shows achievements from Steam's Web API schema.
`ISteamUserStats/GetSchemaForGame` never returns a hidden achievement's description, and an emu
copy is not owned, so the user's own community page (what the STEAM provider reads) does not exist.
Today `LocalSteamHiddenDescriptions` fills the gap by scraping the public achievements pages of a
fixed roster of "top owner" profiles.

Research (2026-09-29):

- **achievement-watchdog** fetches nothing; it reads `steam_settings/achievements.json` made by
  gbe_fork's `generate_emu_config`, which logs into Steam as a client and sends
  `ClientGetUserStats` with a top owner's SteamID to receive the full binary stats schema.
- **Steam Hunters** serves `GET https://steamhunters.com/api/apps/{appId}/achievements`: keyless,
  not behind the Cloudflare challenge that guards its HTML pages, JSON. Each entry carries
  `apiName`, `name`, `description`, `steamPercentage`, `points`, `obtainability`. Checked on
  NieR:Automata (47), Portal 2 (51) and Hollow Knight (63): no blank descriptions, and hidden
  ones are real (Hollow Knight `ENDING_B` — "Defeat the Hollow Knight with Hornet by your side").

## Problem

1. Coverage is partial. A roster page only reveals the hidden achievements that owner earned
   (Hollow Knight: four of five leaders show 40 of 63 rows).
2. Only achievements **earned at the first network sync** are ever looked up. The enrichment runs
   on the network path only (`LocalSteamSource.fetch` → `hiddenDescriptions.enrich`); routine syncs
   use cached metadata and skip it. A hidden achievement earned later never gets a description.
3. Unearned hidden achievements are never looked up, so the Shiba Coins **reveal** shows
   "Steam keeps this one's description secret" (`ShibaCoinsScreen.kt:557`).
4. Matching is by normalized title text parsed out of HTML, which breaks on markup changes.

## Current Behavior

- `LocalSteamSource.fetch(appId, renewMetadata)` — cached metadata → `SteamCoinMapper.map` only;
  network path → schema + rarity → `SteamCoinMapper.map` → `LocalSteamHiddenDescriptions.enrich`.
- `LocalSteamHiddenDescriptions.enrich` — filters `isHidden && isEarned && description.isBlank()`,
  walks `TOP_OWNER_IDS` via `SteamCommunityApi.achievementsPage`, parses with
  `SteamCommunityAchievementsParser`, joins on normalized title. Rate 1.1 s, 4 MB page cap,
  every failure non-fatal, cancellation rethrown.
- `AchievementSetWriter.carryDescriptions` keeps a stored description when a later sync returns
  it blank, so a description learned once survives every routine refresh.
- `SteamCoinMapper` sets `providerAchievementId = a.name` — Steam's `apiName`, the same key Steam
  Hunters returns.
- The UI already redacts unearned hidden coins ("Hidden Coin", "press Confirm to reveal").

## Root Cause

The only source consulted reveals descriptions per owner, per earned achievement, and is asked
once per game, at a moment when the user has typically earned few hidden achievements.

## Goals

- One keyless request per game fills every hidden achievement's description, earned or not.
- Match on `apiName` exactly — no title parsing for the primary source.
- Keep the roster scrape as the fallback for whatever Steam Hunters lacks.
- Add the five Steam Hunters leaders (verified 2026-09-29) to the roster.
- Games synced before this change pick descriptions up without a manual repair.

## Non-Goals

- Changing redaction, reveal or any Shiba Coins UI. Unearned hidden coins stay hidden until revealed.
- The STEAM (owned) provider's own-profile enrichment (`SteamRemoteDataSource`). Follow-up.
- Writing descriptions into generated `achievements.json` (`LocalSteamSchemaGenerator`). PFP never
  reads them back for display. Follow-up.
- Non-English descriptions (Steam Hunters is English only; the parser path already is too).
- A Steam client-protocol (`ClientGetUserStats`) implementation.

## Existing Systems to Reuse

| System | File | Use |
|---|---|---|
| Retrofit stack, bare OkHttp (no logging) | `provider/steam/SteamClientModule.kt` | Add one Retrofit instance for `https://steamhunters.com/` |
| Enricher | `provider/localsteam/LocalSteamHiddenDescriptions.kt` | Becomes Steam Hunters first, roster second |
| Rate limiting | `api/RateLimiter.kt` | Steam Hunters gets its own limiter |
| Description persistence | `sync/AchievementSetWriter.kt` (`carryDescriptions`) | Unchanged — already keeps learned descriptions |
| Enricher tests | `test/.../localsteam/LocalSteamHiddenDescriptionsTest.kt` | Extend |

## Architectural Decisions

- **AD-1 Source order.** Steam Hunters first; the roster scrape runs only for hidden coins still
  blank afterwards.
- **AD-2 Fill unearned hidden coins too.** *(Needs approval — changes documented behavior.)* The
  enricher's comment says "unearned hidden achievements keep their surprise". The surprise is kept
  by the UI's redaction, not by a blank description; filling them fixes Problems 2 and 3, because
  `carryDescriptions` then already holds the text on the day an achievement is earned. The roster
  fallback stays earned-only (its pages rarely reveal unearned ones anyway).
- **AD-3 Match by id.** Steam Hunters `apiName` ↔ `SyncedCoin.providerAchievementId`, exact. Only
  a non-blank description is taken; an existing non-blank description is never overwritten.
- **AD-4 Roster.** A new tier after the two hand-verified FF VI owners, before Goldberg's roster —
  all public, achievement pages readable (checked on 367520, 524220, 620):
  `76561197971398453` NEXGEN -EZ- (fullest coverage in the check), `76561198040673812` The
  Stranger, `76561198019373005` Parzival, `76561197977849691` DDtective, `76561198155124847` AFAK.
- **AD-5 Backfill.** *(Needs approval.)* The cached path in `LocalSteamSource.fetch` also runs
  enrichment when any mapped hidden coin is blank, at most once per appid per process (an
  in-memory set). Without this, games already tracked only improve after an explicit repair.
- **AD-6 Failure is silent and non-fatal**, exactly like today: HTTP error, 404 (game unknown to
  Steam Hunters), bad JSON or oversize body → fall through to the roster. Cancellation propagates.
- **AD-7 Politeness.** Identify as PlayFieldPortal in `User-Agent`; one request per appid; ~1 s
  limiter; response size capped. Nothing user-identifying is sent (appid only).

## Rejected Alternatives

- **Steam client protocol (`ClientGetUserStats`, as gbe_fork does).** Complete and multilingual,
  but needs a Steam client connection (e.g. JavaSteam) and login handling — far outweighs the gap.
- **Scraping Steam Hunters HTML.** Behind a Cloudflare managed challenge.
- **Dropping the roster.** Steam Hunters is a third party that can change or block; the roster
  keeps working without it.

## Data / Persistence

No schema change. Descriptions land in the existing achievement rows via `AchievementSetWriter`,
which already carries them forward. `SteamMetadataStore` is unchanged.

## Compatibility

Existing rows gain descriptions on their next sync (with AD-5) or next repair (without it). Old
behavior is the fallback path, so a Steam Hunters outage reproduces today's results exactly.

## Open Question (before shipping)

Steam Hunters' API is public but undocumented. Confirm their terms allow app use, or ask them
(they publish contact details). The plan keeps the roster fallback so the feature degrades rather
than breaks if access is withdrawn.

## Implementation Phases

**Phase 1 — Source.** A Steam Hunters client and model.
**Phase 2 — Enrichment.** Steam Hunters first, roster second, unearned included, new roster tier.
**Phase 3 — Backfill.** Enrich on the cached path, once per appid per process.

## Verification Strategy

- Unit: DTO decoding from a captured response; enricher ordering, id matching, earned/unearned
  rules, fallback on every failure kind, no overwrite of existing text, cancellation.
- `LocalSteamSourceTest`: cached path calls enrichment once per appid when a hidden coin is blank,
  never when none are.
- Device: a LOCAL_STEAM game with hidden achievements (e.g. Hollow Knight 367520). Sync, then in
  Shiba Coins reveal an unearned hidden coin — its real description shows; earned hidden coins
  show theirs; with network off, sync still succeeds with today's results.

## Execution Task Index

| ID  | Task | Depends On | Status |
| --- | ---- | ---------- | ------ |
| 1.1 | Steam Hunters API client and model | None | READY |
| 2.1 | Steam Hunters-first enrichment, unearned hidden coins, new roster tier | 1.1, AD-2 approval | READY |
| 3.1 | Backfill on the cached sync path | 2.1, AD-5 approval | READY |

### Task 1.1 — Steam Hunters API client and model

- **Objective:** A Retrofit interface that fetches one app's achievement list from Steam Hunters.
- **Parent:** Phase 1.
- **Scope:** `SteamHuntersApi` (`GET api/apps/{appId}/achievements`, returns
  `Response<List<SteamHuntersAchievement>>`), the `@Serializable` model (`apiName`, `name`,
  `description`; everything else ignored), and its `@Provides` in `SteamClientModule` on
  `https://steamhunters.com/` with the shared bare `OkHttpClient`.
- **Existing Code:** `SteamClientModule.kt`, `SteamCommunityApi.kt` (header and shape to copy).
- **Requirements:** `User-Agent: PlayFieldPortal`; no logging interceptor; lenient JSON as the
  module already configures.
- **Do Not Change:** Other Retrofit instances, the OkHttp client, any sync code.
- **Expected Files:** new `provider/steam/SteamHuntersApi.kt`; modified `SteamClientModule.kt`;
  new test `SteamHuntersApiTest.kt` (decode a captured response).
- **Acceptance:** The captured NieR response decodes to 47 entries with `apiName` and
  `description`; unknown fields are ignored; a missing `description` decodes as null/blank.
- **Change Budget:** 1 modified, 1 new, 1 test.
- **Verification:** `./gradlew :feature:feature-achievements:testDebugUnitTest --tests "*SteamHuntersApiTest*"`
- **Stop Condition:** Client exists and decodes; it is not called from anywhere yet.
- **If Blocked:** Report and stop.

### Task 2.1 — Steam Hunters-first enrichment

- **Objective:** `LocalSteamHiddenDescriptions.enrich` fills hidden descriptions from Steam Hunters
  by `apiName`, then from the roster for what remains.
- **Parent:** Phase 2 (AD-1, AD-2, AD-3, AD-4, AD-6, AD-7).
- **Scope:** Inject `SteamHuntersApi`; one call per `enrich`; map `apiName → description`; fill
  every hidden coin with a blank description (earned or not); then run the existing roster walk
  for **earned** hidden coins still blank. Add the AD-4 tier to `TOP_OWNER_IDS`. Update the class
  KDoc to the new rules.
- **Existing Code:** `LocalSteamHiddenDescriptions.kt`, its test, `SteamCommunityAchievementsParser`.
- **Requirements:** Own `RateLimiter`; body size cap; any failure (exception, non-2xx, 404, bad
  body) → skip to roster; `CancellationException` rethrown; never overwrite a non-blank
  description; no request at all when no hidden coin is blank.
- **Do Not Change:** `SteamCommunityAchievementsParser`, `LocalSteamSource`, `SteamCoinMapper`,
  `AchievementSetWriter`, any UI, the STEAM provider.
- **Expected Files:** `LocalSteamHiddenDescriptions.kt`, `LocalSteamHiddenDescriptionsTest.kt`.
- **Acceptance (tests first):** fills an earned and an unearned hidden coin from Steam Hunters by
  id; leaves visible and already-described coins alone; falls back to the roster only for earned
  coins Steam Hunters left blank; falls back fully on 404, IOException and malformed JSON; makes
  no roster request when Steam Hunters covered everything; makes no request when nothing is blank;
  rethrows cancellation. Existing tests still pass (adjusted only where AD-2 changes the rule).
- **Change Budget:** 1 modified source, 1 test.
- **Verification:** `./gradlew :feature:feature-achievements:testDebugUnitTest --tests "*LocalSteamHiddenDescriptionsTest*" --tests "*LocalSteamSourceTest*"`
- **Stop Condition:** Acceptance met. Do not touch the cached path (that is 3.1).
- **If Blocked:** Report and stop.

### Task 3.1 — Backfill on the cached sync path

- **Objective:** Already-tracked games gain descriptions on their next routine sync.
- **Parent:** Phase 3 (AD-5).
- **Scope:** In `LocalSteamSource.fetch`, on the cached-metadata branch, run
  `hiddenDescriptions.enrich` when any mapped coin is hidden with a blank description and the appid
  has not been enriched in this process; remember the appid either way.
- **Existing Code:** `LocalSteamSource.kt`, `LocalSteamSourceTest.kt`, `AchievementSetWriter.carryDescriptions`.
- **Requirements:** No key needed on this branch (Steam Hunters and the roster are keyless);
  `readEarned` / `mapFromCache` launch-return checks stay network-free.
- **Do Not Change:** The network branch, `readEarned`, `mapFromCache`, `SteamMetadataStore`.
- **Expected Files:** `LocalSteamSource.kt`, `LocalSteamSourceTest.kt`.
- **Acceptance (tests first):** cached sync with a blank hidden coin enriches once; a second sync of
  the same appid in the same process does not; a cached sync with no blank hidden coin never
  enriches; `readEarned` makes no enrichment call.
- **Change Budget:** 1 modified source, 1 test.
- **Verification:** `./gradlew :feature:feature-achievements:testDebugUnitTest --tests "*LocalSteamSourceTest*"`, then the device check above.
- **Stop Condition:** Acceptance met.
- **If Blocked:** Report and stop.

## Follow-ups (not in scope)

- STEAM provider: Steam Hunters for unearned hidden coins, so reveal works for owned games too.
- `LocalSteamSchemaGenerator`: write Steam Hunters descriptions into generated `achievements.json`.
