# PFP — GOG and Epic Games Store Storefront Providers Implementation Plan

Successor to `PFP_Metadata_Overrides_And_PC_Providers_Implementation_Plan.md` §12 (C23 T6), which
built the shared storefront resolver with Steam as its only provider and deferred GOG and Epic
("Phase 22": validate the architecture against one store first). Steam search has since been
confirmed on device (§12.7 there), so the deferral's condition is met.

Two tracks. **GOG** is planned in full and ships on its own. **Epic** is a decision for the user
(§5) and its tasks are severable: nothing in the GOG track waits on it.

---

## 1. Context and current behavior

All paths are under `feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/`
unless stated.

- `match/StorefrontModule.kt` — `provideProviders` returns `StorefrontProviders(listOf(steam))`.
  This list is the only thing that decides which stores are asked.
- `match/StorefrontMetadataResolver.kt` — `resolve()` loops `providers.all`; per provider:
  stored identity → import-captured id (`authoritativeId`) → `discover` (normalize, search, score,
  `rescoreWithDetails`). A provider that throws becomes `Resolution.Unavailable` for that store only.
- `match/StorefrontMatching.kt` — `Storefront` already has `GOG` and `EPIC`; the provider contract
  is `search` / `getMetadata` / `validateIdentity` returning `StorefrontOutcome`.
- `match/SteamMetadataProvider.kt` + `api/SteamStorefrontApi.kt` — the model: a Ktor client that
  turns every transport/status/parse problem into a named failure, and a provider that owns its own
  `StorefrontRequestQueue` (250 ms spacing, in-flight dedup) and `StorefrontSearchCache` (6 h).
- `core/core-data/.../entity/GameStorefrontIdentityEntity.kt` and `PFPDatabase.MIGRATION_49_50` —
  `game_storefront_identities` is keyed `(game_id, store)` and already has `namespace`,
  `catalog_item_id`, `app_name`. **Verified: no migration is needed for GOG or Epic.**

### Single-store assumptions found (each is addressed by a task)

| # | Where | What it assumes |
| --- | --- | --- |
| S1 | `MetadataRepository.resolveStorefrontPreset` | Returns `resolution.presets.firstOrNull()` and reports progress as `"Steam"`. A second store's preset is silently dropped. |
| S2 | `MetadataCandidates.steamPreset`, `MetadataApply.presetsFrom` | One nullable preset slot, named for Steam. |
| S3 | `match/GameMatching.kt` `MatchProvider` | No `GOG` value, so a GOG `MetadataPreset` cannot be labelled. `GameMatcher.savedIdFor` and `ProviderMatchEvidence.searchByTitle` are exhaustive `when`s over it. |
| S4 | `feature-achievements/.../localsteam/LocalSteamIdentityResolver.identify` | Calls `storefrontResolver.resolve(subject, allowAutoLink = false)` and reads only `byStore[Storefront.STEAM]`. With GOG registered, every folder identification would also pay a GOG search plus up to three detail requests for an answer nobody reads. |
| S5 | `feature-xmb/.../detail/GameDetailViewModel.storefrontMatchFrom` | `lookup.pending.first()` — only the first store's candidates reach the picker. |
| S6 | `StorefrontMatchRepository.lookup` | Returns "exactly one of" `NeedsChoice` / `Settled` / `Unavailable` / `NoMatch`. With two stores, Steam needing a choice hides GOG being unreachable or already linked. |
| S7 | `GameDetailViewModel.takeRematchAction` | A row's Search/Replace calls `openStorefrontMatch(ignoreStoredIdentity = true)` with no store — it looks past **every** store's stored link, not the row's. |
| S8 | `StorefrontMatchUi.storefrontRematchRowOf` | Unsearchable note reads "No provider yet — coming after Steam is proven". |
| S9 | `StorefrontMatchPanel` | One `storeLabel` in the header; thumbnail slot sized for Steam's `tiny_image` (92×35 dp). |

### Where a game's store id comes from at import (question 6)

- `feature-settings/.../pc/PcGameScanner.buildPcLaunch` reads a GameNative per-store export file
  (`.steam` / `.gog` / `.epic` / `.amazon`), requires its content to parse as a **positive Int**,
  and stores `(normalizeStore(source), id)` on `games.storefront` / `storefront_game_id`.
  `core-data/.../model/StorefrontIdentity.fromLaunchIntentUri` does the same from `i.app_id` +
  `S.game_source`, accepting digits only, 12 at most.
- **GOG.** The captured value is GameNative's `app_id` for a GOG game. Nothing in the repository
  establishes that this equals the gog.com product id; `feature-achievements/.../steam/SteamShortcut.kt`
  explicitly calls a non-Steam GameNative id "internal", and `PcShortcutImporter` trusts only ids
  that are explicitly Steam's. **Unverified — see decision D-2.**
- **Epic.** The captured value is a positive Int. Epic's own identifiers observed today are
  32-character hex strings and slugs (§4.2). A captured Epic value therefore cannot be an Epic
  catalog id, and **`namespace` / `catalogItemId` / `appName` are never captured anywhere**:
  the only code that touches them is the resolver copying nulls to and from the table.

---

## 2. Goals

1. A Windows game can be matched to a GOG product by title, and its GOG metadata offered as a
   preset in Update Metadata, through the existing resolver, scorer, normalizer and identity table.
2. The Match Game picker and the Store Match screen stay correct with more than one searchable
   store, one store on screen at a time.
3. Every existing consumer of the resolver behaves identically for Steam after the change.
4. Epic's position is stated honestly on screen and decided explicitly.

## 3. Non-goals

- **No new writer of metadata columns.** GOG contributes a `MetadataPreset`; `ArtworkRepository.applyMetadata` stays the only writer.
- **No change to `StorefrontMatchScorer`, `StorefrontTitleNormalizer`, `StorefrontRequestQueue`, `StorefrontSearchCache`.** If GOG needs one of them changed, that is a blocker to report, not a fix to make.
- **No GOG artwork source in the Artwork Studio.** `StudioSource` is untouched.
- **No GOG or Epic achievements.** `LocalSteamIdentityResolver` stays Steam-only.
- **No change to the import path** (`PcGameScanner`, `StorefrontIdentity`, `PcGameExport`) or to IGDB's `external_games` lookup (`MetadataRepository.resolveIgdbIdByStorefront`).
- **No Settings entry point for `StorefrontMetadataSync.run`** — still an open UI decision from C23 §12.6.
- **No migration.**
- **No Epic-specific client** unless §5 is decided otherwise.

---

## 4. Verified endpoints

Fetched with WebFetch on **2026-09-30**. WebFetch passes the body through a summarising model, so
the key names below are observations, not byte-exact captures: task 1.1 captures raw responses as
test fixtures and the fixture outranks this table wherever they differ.

### 4.1 GOG

| Endpoint | Result | Observed |
| --- | --- | --- |
| `GET catalog.gog.com/v1/catalog?query=like:doom&limit=6&productType=in:game&order=desc:score` | 200, keyless | Top level `pages`, `productCount`, `products[]`. Each product: `id` (JSON **string**), `title`, `productType`, `releaseDate` (`2016.05.13`), `storeReleaseDate` (`2025.04.18`), `developers[]`, `publishers[]`, `genres[{name,slug}]`, `coverHorizontal`, `coverVertical`, `slug`, `reviewsRating`. 84 results, 6 shown. |
| same, `query=like:the witcher 3 wild hunt`, no `productType` | 200 | 2 products, both `productType: "pack"`. The base game is a **pack**. |
| same, `query=like:cyberpunk 2077`, no `productType` | 200 | 5 products: `pack`, `dlc`, `pack`, `dlc`, `dlc`. |
| same, `query=like:witcher 3&productType=in:game` | 200 | 1 product (a modding tool) — the `game` filter excludes the packs above. |
| `GET embed.gog.com/games/ajax/filtered?mediaType=game&search=doom` (and `witcher 3`) | 200 | `products: []`, `totalResults: 0` both times. **Not usable.** |
| `GET api.gog.com/products/{id}?expand=description` | 200 | `id`, `title`, `slug`, `game_type`, `release_date`, `description{lead,full,whats_cool_about_it}`. **No developer, publisher, genres or tags at any level.** `description.lead` contains HTML. `release_date` was the GOG store date (DOOM 2016 → `2025-04-18…`) or `null`. |
| `GET api.gog.com/v2/games/{id}` (ids 1441199941 game, 1640424747 pack) | 200 | `_embedded.product{id,title,globalReleaseDate,gogReleaseDate,category}`, `_embedded.publisher{name}`, `_embedded.developers[{name}]`, `_embedded.tags[{id,name,level,slug}]`, `description` and `overview` (HTML), `esrbRating{category{name},ageRating}` and `pegiRating{ageRating}` on one product and absent on the other. |
| `api.gog.com/products/999999999999`, `api.gog.com/v2/games/999999999999` | **404** | Unknown id is a 404 on both. |

Not verified: whether GOG requires a browser `User-Agent`; what a rate-limited response looks
like (none was provoked); any published rate limit (none found); the scale of `reviewsRating`.

### 4.2 Epic

| Endpoint | Result | Observed |
| --- | --- | --- |
| `store.epicgames.com/graphql?query={Catalog{searchStore(...)}}` | **403** | Refused unauthenticated. |
| `www.epicgames.com/graphql?query=…` | **403** | Same. |
| `store-content.ak.epicgames.com/api/en-US/content/products/fortnite` | 200 | Addressed by **slug**, not id. `namespace` at root; developer/publisher/description under `pages[].data.about`. No release date. No search. |
| `store-content.ak.epicgames.com/api/content/productmapping` | 200 | Flat map `namespace → slug`, roughly 1,900–2,000 entries. |
| `store-site-backend-static.ak.epicgames.com/freeGamesPromotions` | 200 | 13 promotional elements only. Ids are 32-hex (`id`, `namespace`, `items[].id`). Not a catalog. |

**No unauthenticated Epic endpoint that searches by title or fetches by a catalog id was found.**
IGDB's Epic `uid` format could not be checked: it needs the user's Twitch credentials.

---

## 5. Epic — decision required (D-1)

| Option | What it would be | Trade-off |
| --- | --- | --- |
| **(a) IGDB-only, by stored/captured Epic id** | Resolve Epic games through IGDB `external_games` category 26. | **The premise does not hold today.** No real Epic id is ever captured (§1), so there is nothing to look up. `MetadataRepository.resolveIgdbIdByStorefront` already sends `("EPIC", <int>)` and an Int cannot equal a hex id or a slug. What Epic games get today — and keep — is IGDB **by title**, the same as any Windows game with IGDB credentials. An "Epic via IGDB" provider would need IGDB credentials, an unverified `uid` format, and would produce a second copy of the IGDB column labelled Epic, with no consumer for the stored Epic identity. |
| **(b) Unofficial Epic endpoint** | An Epic client like Steam's. | C23 D3 rejected this; today's checks confirm it. Search is 403. The one open endpoint is slug-addressed with no search, so a provider would have to *guess* a slug from a title — a match by construction rather than by evidence, which C23 §12.5 rules out. |
| **(c) Leave Epic unsearchable and say so** | No provider. Store Match keeps an Epic row whose note states plainly that it cannot be searched. | Costs nothing beyond the wording task 3.3 already carries. Loses nothing a user has today. |

**Recommendation: (c).** Revisit only if (1) a launcher starts exporting real Epic identifiers, or
(2) a consumer for a stored Epic identity appears. Under (c) there is **no Epic task**; tasks E1–E2
exist only for option (a) and stay `BLOCKED` until the user chooses it.

---

## 6. Architectural decisions

**AD-1 — GOG is a second `StorefrontMetadataProvider`, registered in `StorefrontModule` after
Steam.** `GogStorefrontApi` (Ktor, in `api/`) + `GogMetadataProvider` (in `match/`), each mirroring
its Steam counterpart, with its **own** `StorefrontRequestQueue` and `StorefrontSearchCache`.
*Reason:* it is the seam C23 built for exactly this, and per-provider queues are what Phase 15's
isolation means. *Rules out:* a shared queue, a GOG branch inside the resolver, reuse of
`TitleSearchStore`.

**AD-2 — Search is `catalog.gog.com/v1/catalog`; metadata is `api.gog.com/v2/games/{id}`.**
*Reason:* both verified keyless (§4.1); the catalog returns developers, publishers and a release
date on every hit, so the scorer has corroboration without a detail request; v2 carries every
preset field. *Rules out:* `embed.gog.com` (returned nothing) and the v1
`products/{id}?expand=description` endpoint C23 D3 recorded (no developer, publisher or genres).

**AD-3 — `game` and `pack` are linkable; `dlc` and anything else are dropped in the client.**
*Reason:* base games are observed as `pack` (§4.1). Filtered client-side, like
`SteamStorefrontApi.search` filters `type == app`, because a multi-value server filter was not
proven. *Rules out:* `productType=in:game`.

**AD-4 — Release date is the game's, never the store's.** Catalog `releaseDate` and v2
`globalReleaseDate`; never `storeReleaseDate`, `gogReleaseDate` or v1 `release_date`. *Reason:* a
2016 game carrying its 2025 GOG listing date would fire `RELEASE_YEAR_CONFLICT` against a correct
local year.

**AD-5 — GOG's preset: title, plain-text description, developer, publisher, year, date, genre, age
rating. No franchise, no community rating.** Description is v2's HTML reduced to plain text by a
pure function inside the provider (Game Detail renders plain text; no stripper exists in
`feature-artwork`). Age rating is `esrbRating.category.name` when present, else `PEGI <n>` from
`pegiRating.ageRating`, else absent. *Rules out:* mapping `reviewsRating` (scale unverified).

**AD-6 — The import-captured GOG id is not trusted until D-2 is answered.**
`StorefrontMetadataProvider` gains `trustsCapturedId` (default `true`); `GogMetadataProvider`
returns `false`; the resolver skips its captured-id step for a provider that says so and goes to
discovery. *Reason:* `MetadataRepository.resolveStorefrontPreset` calls `resolve(game)` with
auto-link on, so an untrustworthy captured id would be fetched and stored as `EXACT` — a silent
false positive, which C23 §12.5 treats as strictly worse than a prompt. *Rules out:* trusting by
default; cross-checking the fetched title inside the resolver (more logic than one flag that D-2
flips).

**AD-7 — `resolve()` and `lookup()` take an optional store scope.** Null means every provider,
as today. *Reason:* it fixes S4 (Local Steam asks Steam only) and S7 (a row acts on its own store)
with one parameter. *Rules out:* a second resolver entry point.

**AD-8 — Store Match's per-row Search/Replace become per-store; "Search every store again" and the
name search bar stay all-store.** *Reason:* Replace on the Steam row must not look past a GOG link
the user is happy with.

**AD-9 — A lookup that needs a choice also reports the stores that did not.** `NeedsChoice`
carries which stores were unavailable and which were already settled. *Reason:* C23 Phase 15 — a
store that timed out must never read as "no match", and S6 currently hides it.

**AD-10 — The picker shows one store at a time; L1/R1 moves between stores.** `StorefrontMatchUi`
holds every pending store's rows and an index. `PREV_CATEGORY` / `NEXT_CATEGORY` switch store —
the keys `handleMetadataPreviewInput` already uses for `cycleMetadataSource`; touch uses store
chips where `storeLabel` sits. Proposed, subject to mockup: after a pick the picker stays open on
the next store still needing a choice and closes after the last. *Rules out:* a merged
cross-store list (the stated principle in `StorefrontMatchUi.kt`). **Layout is gated on a mockup
the user signs off (task 3.1).**

**AD-11 — `MetadataCandidates.steamPreset` becomes `storefrontPresets: List<MetadataPreset>` in
provider order.** `MatchProvider` gains `GOG`. *Rules out:* a `gogPreset` slot per store.

**AD-12 — Provider order is Steam, then GOG.** Order decides display order (picker tabs, preview
columns), not who is asked — every available provider always is. Steam first because it is the
validated one and the one achievements depend on.

**AD-13 — GOG is registered last.** Tasks 1.x–3.x land with `listOf(steam)` unchanged; task 4.1
flips it. *Reason:* GOG never reaches a consumer or a screen that still assumes one store, and
every earlier task is behavior-neutral for Steam.

### Rejected alternatives

- **GOG through IGDB `external_games` only.** Needs a trustworthy captured GOG id (D-2) and IGDB credentials; gives no title discovery and no picker rows.
- **Reuse `TitleSearchStore` for GOG search caching.** Rejected in C23 §12.4 for Steam; the reason is unchanged.
- **One merged candidate list with a store badge per row.** Invites comparing ids across stores.
- **Registering GOG first and fixing consumers after.** Would drop GOG presets (S1), double Local Steam traffic (S4) and hide GOG candidates (S5) in between.

---

## 7. Data and compatibility

- No schema change. GOG rows use `store = "GOG"`, `store_id` = product id, Epic columns null.
- Existing Steam identities are untouched; nothing is backfilled.
- The working tree already carries uncommitted edits to `StorefrontMetadataResolver.kt`,
  `StorefrontMatchRepository.kt`, `GameDetailViewModel.kt`, the three storefront panel files and
  their tests (`titleOverride`, `Resolution.Linked.match`, the Store Match search bar). Tasks build
  on that state and must not revert it.
- Once 4.1 lands, a bulk scrape auto-links GOG at `EXACT`/`HIGH` exactly as it does Steam, and
  Update Metadata may show a GOG column beside Steam's. Both follow from C23 Phase 11.

---

## 8. Phases

1. **GOG provider** (1.1–1.2) — client, fixtures, provider. Unregistered; nothing user-visible.
2. **Multi-store-safe consumers** (2.1–2.3) — resolver scope and trust flag, repository seam, preset list. Steam-only behavior unchanged.
3. **Multi-store UI** (3.1–3.3) — mockup gate, picker, Store Match.
4. **Switch on** (4.1) — register GOG; device pass.
E. **Epic** (E1–E2) — only under option (a); blocked on D-1.

---

## 9. Verification strategy

- **Tests first in every task**: the failing test is written and seen to fail before production code.
- **Unit**, all with mocked transport (`MockEngine`, as `SteamMetadataProviderTest`): fixtures in
  `feature/feature-artwork/src/test/resources/gog/`, following the existing `screenscraper/` folder.
- **Regression bar**: `StorefrontMetadataResolverTest`, `StorefrontMatchRepositoryTest`,
  `StorefrontMetadataSyncTest`, `SteamMetadataProviderTest`, `MetadataApplyTest` and
  `LocalSteamIdentityResolverTest` keep every existing assertion. A changed assertion needs a
  written reason tied to an AD.
- **Device pass** (task 4.1, run by the user): a game on both stores; a GOG-only game; a game on
  neither; Store Match Replace on one store leaves the other's link alone. A single store being
  unreachable cannot be staged on a device, so the unit tests in 2.2 and 3.2 are its proof.
- Commands are not run by the planner. Builds run only when the user asks.

```bash
./gradlew :feature:feature-artwork:testDebugUnitTest
./gradlew :feature:feature-xmb:testDebugUnitTest
./gradlew :feature:feature-achievements:testDebugUnitTest
```

---

## 10. Execution tasks

Standing rules for every task: tests before production code; no new dependencies; no commits; no
Gradle run without the user asking; stop at the acceptance criteria. **If Blocked** always means:
stop and report what was attempted, what blocked it, which file caused it, and what decision is
needed — never invent an endpoint, a field or architecture to keep moving.

### 1.1 — GOG Ktor client and captured fixtures

- **Objective:** `GogStorefrontApi` with `search(term)` and `game(id)`, proven against captured responses.
- **Scope:** capture raw JSON for the §4.1 catalog and v2 requests (a `game`, a `pack`, a query returning `dlc`, a 404) into `src/test/resources/gog/`; response models; a `call`-style wrapper giving a GOG result type with `NETWORK_ERROR` / `RATE_LIMITED` / `PROVIDER_ERROR`; 404 on `game(id)` is an honest empty, not a failure; `dlc` and unknown product types filtered (AD-3).
- **Relevant code:** `api/SteamStorefrontApi.kt` (shape to mirror), `api/ArtworkModule.kt` (the shared `HttpClient`), `SteamMetadataProviderTest` (MockEngine fixture style).
- **Do not change:** `SteamStorefrontApi`, `ArtworkModule`, anything in `match/`.
- **Expected files:** add `api/GogStorefrontApi.kt`, `api/GogStorefrontApiTest.kt`, fixtures.
- **Acceptance:** fixtures are raw captures with the capture date noted; search parses ids as strings and drops `dlc`; 404 → empty; 429/403 → rate-limited; 5xx and malformed body → provider error; IOException → network error; cancellation rethrows. Any field path that differs from §4.1 is corrected in §4.1 in the same change.
- **Change budget:** 0 modified, 1 new source, 1 test, fixtures.
- **Stop condition:** the client parses the fixtures. No provider, no DI.
- **If blocked:** an endpoint no longer answers, needs auth, or a needed field is absent from the raw capture.

### 1.2 — `MatchProvider.GOG` and `GogMetadataProvider`

- **Objective:** GOG as a `StorefrontMetadataProvider`, constructed by DI but not in the provider list.
- **Scope:** `MatchProvider.GOG` + capability row (`addressableBySavedId = false`, `addressableByRomHash = false`, `addressableByStorefrontId = false`, `supportsTitleSearch = true`, `suppliesMetadata = true`, `suppliesArtwork = false`); `GameMatcher.savedIdFor` → null; `ProviderMatchEvidence.searchByTitle` GOG branch delegating to the GOG provider as the Steam branch does; `GogMetadataProvider` per AD-2..AD-5 with its own queue and cache; `StorefrontModule.provideGogProvider`.
- **Relevant code:** `match/SteamMetadataProvider.kt`, `match/GameMatching.kt`, `match/GameMatcher.kt`, `match/ProviderMatchEvidence.kt`, `match/StorefrontModule.kt`.
- **Do not change:** `provideProviders` (stays `listOf(steam)`), `StorefrontMatching.kt`, scorer, normalizer, queue, `StudioSource`.
- **Expected files:** modify the four above; add `match/GogMetadataProvider.kt`, `match/GogMetadataProviderTest.kt`. `ProviderMatchEvidenceScreenScraperTest` gains one constructor argument.
- **Acceptance:** search stops at the first non-empty query, caches empties, dedups in flight; candidates carry year, developer, publisher and thumb; preset fields follow AD-4/AD-5 with HTML reduced to plain text; non-numeric or over-long id → `NoMatch` without a request; `validateIdentity` is `false` only on a 404 and a `Failure` on an outage; `GameMatcherTest` passes unmodified.
- **Change budget:** 4 modified, 1 new, 1 new test, 1 existing test touched for a constructor argument.
- **Stop condition:** the provider's suite is green in isolation. GOG is not registered.
- **If blocked:** a preset field cannot be built from the 1.1 fixtures, or adding `MatchProvider.GOG` breaks a `when` outside the two named.

### 2.1 — Resolver store scope and captured-id trust

- **Objective:** `resolve()` can be limited to named stores, and a provider can decline the captured-id step.
- **Scope:** `stores: Set<Storefront>? = null` on `resolve` (AD-7); `trustsCapturedId` on the provider interface, honored in `resolveOne` (AD-6); `GogMetadataProvider.trustsCapturedId = false`; `LocalSteamIdentityResolver.identify` passes `setOf(Storefront.STEAM)`.
- **Relevant code:** `match/StorefrontMetadataResolver.kt`, `match/StorefrontMatching.kt`, `feature-achievements/.../localsteam/LocalSteamIdentityResolver.kt`.
- **Do not change:** discovery, scoring, `persist`, `confirm`, `unlink`; Local Steam's ladder order or marker writing.
- **Expected files:** modify the three above and `GogMetadataProvider.kt`; tests in `StorefrontMetadataResolverTest`, `LocalSteamIdentityResolverTest`.
- **Acceptance:** a scoped resolve never calls an out-of-scope provider (verified with two fakes); an untrusting provider with a captured id goes to discovery and makes no by-id request for it; Steam's captured-id path is unchanged; Local Steam asks only Steam.
- **Change budget:** 4 modified, 2 test files.
- **Stop condition:** criteria met. No repository or UI change.
- **If blocked:** Local Steam turns out to need another store's answer.

### 2.2 — Repository seam: per-store lookup and honest partial answers

- **Objective:** `StorefrontMatchRepository.lookup` can target one store and no longer hides stores that did not need a choice.
- **Scope:** `store: Storefront? = null` on `lookup` (AD-7); `NeedsChoice` carries unavailable and already-settled stores (AD-9); `pending` ordered by provider order.
- **Relevant code:** `match/StorefrontMatchRepository.kt`.
- **Do not change:** `confirm`, `unlink`, `rematchRows`, the rule that `lookup` writes nothing.
- **Expected files:** modify `StorefrontMatchRepository.kt`; tests in `StorefrontMatchRepositoryTest`. `GameDetailViewModel` compiles unchanged or with a defaulted argument only.
- **Acceptance:** Steam needs a choice + GOG unavailable → `NeedsChoice` naming GOG unavailable; Steam settled + GOG needs a choice → `NeedsChoice` naming Steam settled; a scoped lookup leaves the other store's stored identity out of the question; "looking never links" still holds.
- **Change budget:** 1–2 modified, 1 test file.
- **Stop condition:** criteria met. No UI mapping.
- **If blocked:** the sealed `Lookup` shape cannot carry this without breaking `GameDetailViewModelTest` beyond a stub.

### 2.3 — Storefront presets as a list

- **Objective:** every linked store's preset reaches Update Metadata.
- **Scope:** `MetadataCandidates.storefrontPresets` replaces `steamPreset` (AD-11); `resolveStorefrontPreset` returns all presets and reports progress with the available stores' labels; `MetadataApply.presetsFrom` appends them; KDoc corrected.
- **Relevant code:** `MetadataRepository.kt` (`MetadataCandidates`, `fetchCandidates`, `resolveStorefrontPreset`), `match/MetadataApply.kt`.
- **Do not change:** provider order 1–4 in `fetchCandidates`, `fetchForGame`'s writes, `GameDao.updateMetadata`, `resolveIgdbIdByStorefront`.
- **Expected files:** modify the two above; test in `MetadataRepositoryCandidatesTest`.
- **Acceptance:** two linked stores → two presets in provider order; Steam only → identical to today; a console ROM still returns none; `MetadataApplyTest` passes unmodified.
- **Change budget:** 2 modified, 1 test file.
- **Stop condition:** criteria met.
- **If blocked:** `MetadataApplyTest` would need editing.

### 3.1 — Mockup: multi-store Match Game and Store Match *(sign-off gate)*

- **Objective:** an approved mockup of AD-10 and of the Store Match rows with two searchable stores and one unsearchable.
- **Scope:** picker header with store chips and the L1/R1 hint; a store tab that did not answer; a store already linked; behavior after a pick; GOG thumbnail in a slot shaped for Steam's capsule; the unsearchable row's note wording; Store Match per-row actions.
- **Do not change:** any source file. This task produces a mockup only.
- **Acceptance:** the user has signed off, and the approved points are written back into AD-10 of this plan.
- **Change budget:** 0 source files.
- **Stop condition:** sign-off recorded.
- **If blocked:** the user rejects one-store-at-a-time — that reopens AD-10 and tasks 3.2–3.3.

### 3.2 — Match Game picker across stores

- **Objective:** the picker holds every store's candidates, shows one store, and switches with L1/R1 or a chip.
- **Scope:** `StorefrontMatchUi` state for several stores; `storefrontMatchFrom` maps all pending, unavailable and settled stores; store switching in `handleStorefrontMatchInput`; post-pick behavior as approved in 3.1; panel header per the mockup.
- **Relevant code:** `detail/StorefrontMatchUi.kt`, `detail/StorefrontMatchPanel.kt`, `GameDetailViewModel.kt` ("Storefront match picker and Rematch" section, and the `MODAL_STOREFRONT_MATCH` node list).
- **Do not change:** `StorefrontMoreInfoPanel` content, the metadata preview, the Rematch panel, focus handling outside this modal.
- **Expected files:** modify the three above; tests in `StorefrontMatchUiTest`, `GameDetailViewModelTest`.
- **Acceptance:** with one pending store the picker is indistinguishable from today; with two, L1/R1 switches rows and header, focus resets to the first row, and confirming writes the shown store's candidate with that store's confidence; an unavailable store is named, never shown as empty.
- **Change budget:** 3 modified, 2 test files.
- **Stop condition:** criteria met. Store Match untouched.
- **If blocked:** the approved mockup needs a focus pattern the nav engine does not support.

### 3.3 — Store Match acts on the row's store

- **Objective:** a row's Search/Replace searches that store only, and an unsearchable store says so truthfully.
- **Scope:** `takeRematchAction` passes the row's store through `openStorefrontMatch` to `lookup` (AD-8); the note in `storefrontRematchRowOf` replaced with the wording approved in 3.1; "Search every store again" and the name bar unchanged.
- **Relevant code:** `GameDetailViewModel.kt` (`takeRematchAction`, `openStorefrontMatch`), `detail/StorefrontMatchUi.kt`.
- **Do not change:** `StorefrontRematchPanel` layout, `unlinkStorefront`, the search bar.
- **Expected files:** modify the two above; tests in `StorefrontMatchUiTest`, `GameDetailViewModelTest`.
- **Acceptance:** Replace on one store issues a lookup scoped to it; the other store's row is unchanged afterwards; the stale "coming after Steam is proven" text is gone.
- **Change budget:** 2 modified, 2 test files.
- **Stop condition:** criteria met.
- **If blocked:** the approved wording depends on D-1 and D-1 is unanswered — ship the neutral wording from the mockup and report.

### 4.1 — Register GOG

- **Objective:** GOG is live.
- **Scope:** `provideProviders` → `listOf(steam, gog)`; stale "only Steam" KDoc in `StorefrontMatching.kt`, `StorefrontModule.kt` and `StorefrontMatchUi.kt` corrected; a two-store case in `StorefrontMetadataSyncTest`; hand the user the §9 device checklist.
- **Do not change:** anything else. If a consumer misbehaves with two stores, that is a finding for a new task, not a fix here.
- **Expected files:** modify `StorefrontModule.kt` plus KDoc-only edits; 1 test file.
- **Acceptance:** Store Match shows GOG as searchable; the device checklist is reported back by the user; §4.1's "not verified" items are updated with what the device showed.
- **Change budget:** 1 modified with behavior, 2 KDoc-only, 1 test file.
- **Stop condition:** registration and tests. The device pass is the user's.
- **If blocked:** any task 1.1–3.3 is not `DONE`.

### E1 — Epic via IGDB: evidence spike *(only if D-1 = a)*

- **Objective:** establish, with the user's IGDB credentials, what `external_games.uid` holds for category 26 and whether PFP can ever obtain that value for a library game.
- **Scope:** read-only queries and a written finding appended to §5. No production code.
- **Stop condition:** the finding is written. **If blocked:** no credentials available.

### E2 — Epic via IGDB provider *(only if E1 finds a usable id)*

- Specified after E1; it cannot be scoped before the `uid` format is known.

---

## 11. Decisions needed from the user

| ID | Decision | Default if unanswered | Blocks |
| --- | --- | --- | --- |
| D-1 | Epic route: (a), (b) or (c) — §5 | (c) | E1–E2 only |
| D-2 | Is GameNative's GOG `app_id` (the number inside a `.gog` export file) the gog.com product id? Check one game against the `id` the GOG catalog returns for it. | Not trusted (AD-6) | Nothing; "yes" is a one-line follow-up flipping `trustsCapturedId` |
| D-3 | Mockup sign-off for AD-10 and the Store Match wording | — | 3.2, 3.3 |

---

## 12. Follow-ups noted, not planned

- `PcGameScanner.buildPcLaunch` requires the exported id to fit a positive Int; GOG ids observed reach 2,093,619,782, close to the limit. Out of scope here.
- `MetadataRepository.resolveIgdbIdByStorefront` sends captured GOG and Epic values to IGDB as exact ids; its usefulness depends on D-2 and §1's Epic finding.
- C23 §12.6 still says GOG and Epic are unbuilt; update that paragraph when 4.1 is `DONE`.

---

## 13. Execution Task Index

| ID | Task | Depends On | Effort | Status |
| --- | --- | --- | --- | --- |
| 1.1 | GOG Ktor client and captured fixtures | None | S | VERIFYING |
| 1.2 | `MatchProvider.GOG` and `GogMetadataProvider` (unregistered) | 1.1 | M | VERIFYING |
| 2.1 | Resolver store scope and captured-id trust; Local Steam asks Steam only | 1.2 | S | VERIFYING |
| 2.2 | Repository seam: per-store lookup and honest partial answers | 2.1 | S | VERIFYING |
| 2.3 | Storefront presets as a list | 1.2 | S | VERIFYING |
| 3.1 | Mockup: multi-store Match Game and Store Match (sign-off gate) | None | S | SKIPPED — see note |
| 3.2 | Match Game picker across stores | 2.2, 3.1 | M | VERIFYING |
| 3.3 | Store Match acts on the row's store | 2.2, 3.1 | S | VERIFYING |
| 4.1 | Register GOG; device pass | 1.2, 2.1, 2.2, 2.3, 3.2, 3.3 | S | VERIFYING — device pass owed |

**Implemented 2026-09-30 in one pass at the user's instruction.** Every task's tests were written
before its production code; **none has been compiled or run** (builds run only when the user
asks), so `VERIFYING` here means "written, unverified". Decisions taken at their defaults: D-1 =
(c), D-2 = not trusted. D-3's mockup gate (3.1) was not held: the picker's store chips, the L1/R1
switch, the stay-open-for-the-next-store behavior and the unsearchable-row wording were built as
AD-10 proposed them and are owed a look on device. Fixtures in `src/test/resources/gog/` are raw
captures from 2026-09-30; they agreed with §4.1 except that the catalog's per-product age rating
is `ratings[{name, ageRating}]` (unused) and v2's `product.id` is a JSON number.
| E1 | Epic via IGDB: evidence spike | D-1 = (a) | S | BLOCKED (D-1) |
| E2 | Epic via IGDB provider | E1 | — | BLOCKED (D-1) |

Status key: `READY` · `IMPLEMENTING` · `VERIFYING` · `DONE` · `BLOCKED`.
Effort: S = under a day · M = a few days · L = a week or more.

`DONE` requires: acceptance criteria met, the task's tests written first and passing, the change
budget respected or the overrun justified in writing, and no known blocker.
