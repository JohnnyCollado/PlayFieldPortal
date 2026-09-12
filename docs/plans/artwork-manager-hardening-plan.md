# Artwork Manager Hardening

> Implementation handoff, approved 2026-09-09. Indexed as `C16` in [the plan index](README.md).
> Work the Execution Task Index in dependency order, one bounded task per helper.
>
> Source: `PFP_Artwork_Manager_Hardening_Design.md` (external design spec), analysed and corrected
> against the working tree on `more-customization` (DB v41). Every file and symbol below was
> verified read-only; nothing here is assumed.

## Context

The Artwork Studio is the single screen where a game's artwork is browsed and changed
(`ArtworkStudioScreen` + `ArtworkStudioViewModel`, ~950 lines each). It works, but it fails in ways
users notice: artwork vanishes when you switch source, you cannot search for anything other than the
game's existing title, and a ROM or Windows game with an imperfect filename simply finds nothing.

A design spec was produced externally to harden it. Its factual claims about the repository are
**accurate** — I verified all fourteen. Its problem is omission: three gaps that would each sink a
feature if they reached implementation unnoticed, plus a dependency on API surface that does not
exist. This plan is that spec, corrected and sequenced against the real code.

Decisions taken during analysis and folded in:

- A result page stays **one gridful** (page == screen). The spec's literal "50 per page" is dropped.
- **ICON1 stays its own single-art category.** Only `VIDEO` becomes multi-select.
- **MANUAL keeps its tab** but sits outside the grid-preview and crop model; **TITLESCREEN stays
  import-only** with no tab.
- Plan **B2's `ScrapeFailure` taxonomy is a dependency**, not something to reinvent.
- **Identity-tier matching ships first; the ranked suggestion picker is deferred** to its own plan
  (see Non-Goals and AD-4).
- **Crop ships a starter profile set** with Original Image as the universal fallback, not the full
  40-platform table.
- **Phases 0 and 1 are the first merge**; Phases 2–6 are replanned afterward with real feedback.
- The approved HTML mockup will be added to `docs/mockups/` (it is not in the repo today).

## Problem

Five distinct defects, currently conflated as "the artwork manager is flaky":

1. **Artwork disappears when switching source.** An asynchronous state bug, not a UI quirk.
2. **Search is not editable.** The query is always `game.displayTitle`; a bad filename is a dead end.
3. **No game-match step.** There is no way to say "this ROM is actually *that* game", so a wrong or
   absent match cannot be corrected.
4. **Screenshots and videos are limited to one each**, at both the database and the filesystem layer.
5. **Crop is generic.** One hardcoded ratio per artwork kind, no sense of physical packaging, and no
   live preview of the actual ICON0/box/disc result.

Plus one identity gap: **Windows games lose their storefront identity at import**, so a Steam or GOG
game can only ever be matched by title.

## Current Behavior

Verified against the tree. All line references checked.

**Search and results** — `ArtworkStudioViewModel.kt`
- Query is always `game.displayTitle`: [`:319`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ArtworkStudioViewModel.kt:319) (SGDB), `:346` (TGDB), `:369` (IGDB). `ArtworkStudioUiState` (`:50-113`) has no query field; no `onQueryChanged` exists.
- Results live in `private var allResults: List<StudioArt>` ([`:199`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ArtworkStudioViewModel.kt:199)) — a plain `var`, not state.
- `loadResults()` (`:274-295`) clears visible state first, then reassigns `allResults` inside a bare `viewModelScope.launch`. No `Job` handle, no cancellation, no request id compared after the suspend.
- Page size is **20** (`STUDIO_GRID_COLUMNS=4 × STUDIO_GRID_ROWS=5`, `:133-136`), sliced client-side (`nextPage :425`).
- `sourceIndex` is reset on `selectTab` (`:394`) but never re-validated against the new source list's length elsewhere.

**Navigation** — `enum class StudioZone { TABS, SOURCES, GRID }` (`:48`); BACK/LEFT/RIGHT/L1/R1 all branch on `when (s.zone)` (`:904-938`). There is **no Compose focus system in the screen at all** — no `FocusRequester`, no `onKeyEvent`. Selection is index-in-state; input arrives as a hoisted `pendingGamepadAction` forwarded from [`GameDetailScreen.kt:186-206`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt:186). Square/X toggles NSFW (`:947`); Triangle/Y opens the actions menu (`:951`).

**L2/R2 are already unbound.** The KDoc at `ArtworkStudioScreen.kt:70` and `ArtworkStudioViewModel.kt:173` claims "L2/R2 switch sources", but no `GamepadAction` maps to `KEYCODE_BUTTON_L2/R2` in [`GamepadBinding.kt:52-59`](core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/GamepadBinding.kt:52). Stale comments.

**Storage** — `ArtworkRecordEntity` has a unique `(game_id, artwork_type)` index ([`:15`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/entity/ArtworkRecordEntity.kt:15)) and already carries `origin_url`, `provider`, `prev_document_uri`, `prev_relative_path`, `prev_size_bytes`, `crop_rect`, `has_original`, `width/height/checksum`, `user_assigned`, `locked`. `ArtworkRecordDao` has `get(gameId,type)` returning one row, upsert-REPLACE riding that index, and no paged or ordered query.

**Metadata** — `MetadataRepository` picks winners itself (`finalBoxArtUrl = ss ?: tgdb ?: igdb ?: sgdb`, [`:182-189`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/MetadataRepository.kt:182)) and persists through `gameDao.updateMetadata`, COALESCE-per-column at [`GameDao.kt:299-321`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/dao/GameDao.kt:299). There is no candidate-retrieval-without-write path.

**Crop** — target aspect is a hardcoded `when (kind)` at [`:641-647`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ArtworkStudioViewModel.kt:641): `ICON/ICON1 → 144:80`, `HERO → 920:430`, `BACKGROUND → 16:9`, else free crop. No platform or region registry exists anywhere.

**Windows import** — `source` (STEAM/EPIC/GOG/AMAZON/CUSTOM_GAME) and the numeric app id are computed in `buildPcLaunch` ([`PcGameScanner.kt:162-165`](feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/pc/PcGameScanner.kt:162)) and discarded — the persisted `Game` keeps only `launchIntentUri` and `packageName`. `storefront` / `storefront_game_id` do not exist on `GameEntity` (zero hits in schema `41.json`).

**Coverage** — `ArtworkStudioViewModel`, the crop math, and `ArtworkRecordDao` have **zero tests**. `grep -rln ArtworkStudio` matches only the four main-source files.

## Root Cause

- **Disappearing artwork** — one shared mutable `allResults` written by uncancelled, unkeyed jobs, with visible state cleared optimistically before the await. Any slower earlier request wins.
- **Dead-end search** — the query was never modelled as state; it is read from the game row at each call site.
- **Single screenshot** — enforced at *two* layers, and the spec only saw one: the unique DB index, **and** `ArtworkFileNaming.fixedName(kind)` returning one constant filename per kind.
- **Generic crop** — crop geometry is keyed on artwork kind alone; platform and region were never inputs.
- **No Windows identity** — storefront is a local val in a function that returns an Intent.

## Goals

1. Editable, non-destructive search that never renames the game.
2. Identity-first matching (saved provider ID, ROM hash, storefront ID) with suggestions only as a fallback.
3. Race-safe source/category switching — a stale response can never mutate visible state.
4. Multiple ordered screenshots and videos, on disk and in the database, surviving Relink/Scan.
5. Metadata presets previewed Current-vs-Incoming and applied only by explicit user action.
6. Windows storefront identity captured at import, backfilled for existing installs, used in matching.
7. Crop that renders the final ICON0/box/disc result live, resolved from platform, region and media form.
8. Every control reachable by D-pad + Confirm + Back + Square + Triangle; touch as a first-class peer.

## Non-Goals

Carried from the spec, plus two added:

- Player count in the redesigned metadata workflow (`players` column stays; it is not surfaced).
- Permanent or multi-level undo history — one session-level Undo Last Apply only.
- A dedicated asset-provenance screen.
- Rejecting artwork because its dimensions do not match a crop profile.
- Uploading a Windows executable or treating a binary hash as a public game identifier.
- SteamGridDB text metadata — SGDB stays artwork-only.
- **Added:** bounded scrape concurrency and the failures screen. Those are plan B2's scope; this plan
  consumes only B2's `ScrapeFailure` type.
- **Added:** server-side provider pagination. No provider supports it (see Architectural Decisions).
- **Added:** the ranked suggestion picker and match Tiers 4–6, and the IGDB/TGDB multi-result search
  they require. Deferred to a follow-up plan (AD-4).
- **Added:** exhaustive per-platform crop profiles. This plan ships a starter set; the rest of the
  table is data, added later without code changes (AD-11).

## Existing Systems to Reuse

| Need | Reuse | Location |
|---|---|---|
| Fill-missing metadata semantics | reversed-COALESCE update already written | `GameDao.kt:355-365` |
| Persistent ScreenScraper media cache (zero API calls when cached) | `SsMediaCacheDao` + `SsMediaCatalog.mediasFor()` | `feature-artwork/api/` |
| Controller-vs-touch presentation mode | `TouchNavButtonMode { AUTO, ALWAYS_SHOW, ALWAYS_HIDE }` resolved against `lastInputWasTouch` | [`TouchNavButtonMode.kt:10-19`](core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/TouchNavButtonMode.kt:10), `XMBViewModel.kt:857-864`, pinned by `TouchNavButtonResolutionTest` |
| Drag-to-scroll on chrome; LEFT-backs-out | shipped by plan C15 (2026-09-09) | `Modifier.dragToScroll` in core-ui; `controller_left_backs_out` pref |
| Typed provider failure reasons | `ScrapeFailure` (`NoMatch`, `QuotaExceeded`, `AuthFailed`, `NetworkError`, `RateLimited`, `AssetMissing`, `WriteFailed`) | plan B2, `docs/plans/scraper-reliability-plan.md` |
| One-previous-version undo for files | `prev_document_uri` / `prev_relative_path` / `prev_size_bytes` | `ArtworkRecordEntity` |
| Lossless re-crop | `has_original` + `pfp/originals/`, `RoutingArtworkStore.saveCropBaked` | `feature-artwork/store/` |
| Existing provider IDs | `ss_id`, `tgdb_id`, `igdb_id`, `steam_grid_db_id` | `GameEntity` |
| Storefront backfill source | store + app id already encoded in `launch_intent_uri` | `GameEntity.launchIntentUri` |
| Compose UI tests on the JVM | Robolectric, already wired in feature-xmb | [`build.gradle.kts:62-66`](feature/feature-xmb/build.gradle.kts:62) |
| Migration test harness | `migrationTestHelper(DB)`, exported schemas 32–41 | `Migration40To41Test`, `core/core-data/schemas/` |

## Architectural Decisions

**AD-1. Multi-media is a filename change first, a schema change second.**
`ArtworkFileNaming.fixedName(kind)` returns one constant name per kind — `SCREENSHOT -> "screenshot.jpg"` ([`:16-31`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/store/ArtworkFileNaming.kt:16)) — and `saveVersionedFromUrl` *prunes* every prior file of that kind before writing (`isPruneCandidate`). Ten screenshots would overwrite each other, and saving #2 would delete #1's bytes. `portableName` collides too, under the `(platform_id, artwork_type, portable_name)` index. The entity header states the contract everything rests on: *"the folder stays the source of truth and Relink/Scan can rebuild rows"* — so **`sortOrder` must be derivable from the filename**, not only from a DB column. Ordinal naming (`screenshot_01.jpg`) lands before the migration.

**AD-2. Every `ArtworkKind` gets an explicit selection model.**
There are 12 kinds and 11 tabs (`STUDIO_TABS`, `:155-167`). `VIDEO` becomes multi-select. **`ICON1` stays single-art** — it is the XMB icon-slot snap, transcoded from a full `VIDEO` by `VideoSnapTranscoder`, and folding it into a multi-select Video category would break the icon animation. `MANUAL` (PDF) is excluded from the grid/preview/crop model entirely. `TITLESCREEN` stays import-only, no tab.

**AD-3. All paging is client-side. Delete the server-paging branch.**
No provider supports it: IGDB hardcodes `limit 1;` ([`IgdbApi.kt:66-77`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/api/IgdbApi.kt:66)), TGDB returns a single `TgdbGameInfo` ([`TheGamesDbApi.kt:130`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/TheGamesDbApi.kt:130)), ScreenScraper returns the whole `medias` list from one `jeuInfos` call, and SGDB's `getArt` takes styles/dimensions/nsfw filters but **no `page` or `limit`** ([`SteamGridDbApi.kt:104-125`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/api/SteamGridDbApi.kt:104)). Fetch once, cache, page client-side — which is what the Studio already does. This is a simplification, not a compromise.

**AD-4. Identity tiers ship now; the ranked suggestion picker is deferred.**
Because of AD-3's findings, Tiers 4–6 ("show ranked suggestions") have **no data source** — IGDB and TGDB, the two providers designated as metadata-preset providers, each return exactly one game. Building multi-result search for both is net-new API work (query bodies, response models, tests) sitting on the critical path of an already-large plan.

So Phase 2 delivers **Tiers 1–3 only**: saved provider ID → ROM hash / storefront ID → unique exact normalized title on the expected platform. That is where the identity evidence actually exists today, and it is what turns a dead-end match into a working one. For everything below Tier 3, the user gets the editable search field (Phase 1) plus a **manual Change Match** backed by `SteamGridDbApi.searchGame`, which already returns a list. `Matched as …` / **Change Match** / **Forget Match** all ship; the *ranked, edition-distinguishing, lazily-asset-counted* picker of spec §9 does not.

The deferred follow-up plan owns: IGDB/TGDB multi-result search, Tiers 4–6, and the suggestion-card UI. Nothing in this plan blocks it — the tiered matcher is written so Tiers 4–6 are additional branches, not a rewrite.

> **Superseded in part by Merge 3 (2026-09-10).** On-device use showed IGDB and TheGamesDB could
> never match anything under this decision, so their multi-result search landed early, and
> ScreenScraper gained `jeuRecherche` name search with them. Every provider now has
> `supportsTitleSearch = true` and backs Change Match, not SteamGridDB alone. Tiers 4–6 and the
> ranked picker are still deferred. See "Merge 3 landed" below.

**AD-5. A page is one gridful.** Page == screen, no in-page scrolling, paging is the only navigation model. Density is changed by adjusting rows/columns, never by decoupling page size from the grid. *(Refined by AD-17: the gridful is measured from the screen, not a fixed 4×5.)*

**AD-6. Race safety is coroutine ownership, never delays.** An immutable request key (`normalizedQuery + provider + category + confirmedMatchId + providerOptions`) plus a monotonic generation token; a response may reduce into state only if both still match. Per-key caches replace the single `allResults`. `flatMapLatest` is allowed but does not remove the equality guard at the reducer boundary.

**AD-7. Candidate retrieval and application are separate operations.** `MetadataRepository`'s auto-winner + COALESCE-write path stays for the batch scraper, but the preview screen gets a retrieval API that writes nothing. `GameDao.kt:355-365` backs **Fill Missing Only**.

**AD-8. MVVM, not MVI.** `ARCHITECTURE.md` says MVVM and the repo has zero `UiEvent`/`UiEffect` in production code. State stays `StateFlow<UiState>` with plain public ViewModel functions. The spec's "reduced by explicit events" is honoured in spirit — one immutable state, explicit reducers — without importing an MVI vocabulary.

**AD-9. Reuse `TouchNavButtonMode`; do not add an "Input Display Mode" setting.** It is already `AUTO/ALWAYS_SHOW/ALWAYS_HIDE`, already resolved against `lastInputWasTouch`, already tested. The only gap is that `ArtworkStudioScreen` is not passed `showTouchControls` — every other detail screen is.

**AD-10. LEFT moves spatially, and only falls through to back-out at the left edge.** C15 made LEFT a back-out fallthrough under `controller_left_backs_out`. Mirroring its fallthrough-never-override rule keeps both behaviours.

**AD-11. The crop registry is a data table with a universal fallback.** Ship the registry plus profiles for the platforms with real libraries; every unlisted platform resolves to **Original Image**, which is already the spec's own fallback for `windows`, `android`, `c64` and the arcade families. Adding a platform later is a data edit, never a UI change — which is what the spec's "centralize in a profile registry so corrections do not require UI changes" line asks for. This also disposes of the `vpk` gap: it falls back like anything else until someone supplies a real profile.

**AD-12. `MetadataRepository` splits at a seam that already exists.**
`fetchForGame` ([`:70`](feature/feature-artwork/src/main/kotlin/com/playfieldportal/feature/artwork/MetadataRepository.kt:70))
runs four provider steps, then hits an explicit `if (ssInfo == null && tgdbInfo == null && igdbInfo
== null && sgdbGridUrl == null) return` before it assembles winners, downloads a single byte, or
writes a single column. That check is the seam: everything above it is retrieval, everything below
it is application. Task 3.1 extracts the top half as `fetchCandidates` and has `fetchForGame` call
it — a move, not a rewrite, and the batch scraper's behaviour is unchanged by construction.

**AD-13. Multi-media needs a consumer, or it is invisible.**
`GameDetailViewModel` builds the media strip with `artworkStore.find(...)` — one video and one
screenshot ([`:265-273`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailViewModel.kt:265)).
Phase 0 shipped ordered storage and `findAll`, but nothing reads it, so a user who applies five
screenshots today still sees one. The original Phase 5 was entirely Studio-side and never mentioned
the strip. Task 5.0 fixes that first: it is small, it is the only part of Phase 5 with visible
payoff on its own, and it makes every later Phase 5 task demonstrable.

**AD-14. Crop resolves on GAME region, not artwork region — for now.**
The spec's order was kind → platform → artwork region → game region → default. Game region is
available (`games.region`, `GameRegion { NTSC_U, PAL, NTSC_J }`, added in v40). Artwork region is
not: ScreenScraper exposes `region` on each `SsCachedMedia`, but that value is never persisted —
`media_region` was deliberately left out of migration 41→42 under the plan's own "only if providers
expose structured values" condition, and SGDB/TGDB/IGDB expose nothing equivalent. Rather than add
a column for one provider, 6.1 resolves kind → platform → game region → default → source ratio.
Artwork-region keying joins the rest of the profile table in the deferred follow-up, where it is a
data-and-one-column change with a real use case behind it.

## Rejected Alternatives

- **Literal 50 results per page.** Rejected: 50 in a 4-wide grid is 13 scrolling rows *plus* explicit page controls — two stacked navigation models on a screen that currently has none. Page == screen instead (AD-5).
- **Folding ICON1 into a multi-select Video category.** Rejected: changes how the XMB icon animation resolves, for no user-visible gain (AD-2).
- **Dropping the MANUAL tab from the Studio.** Rejected: it is a shipped feature and removing it buys only a tidier category model.
- **Promoting TITLESCREEN to a browsable tab.** Rejected for now: more surface to build and test for a kind nothing renders yet. It stays import-only, exactly as today.
- **Building IGDB/TGDB multi-result search on this plan's critical path.** Rejected: it is net-new API work gating a picker that only helps below Tier 3, while Tiers 1–3 plus an editable query already resolve the reported pain (AD-4).
- **Populating all ~40 crop profiles up front.** Rejected: a large table of ratios where every wrong entry is a visible bad crop, and none of it is needed to prove the mechanism (AD-11).
- **A second provider-error taxonomy.** Rejected: B2 already specifies one; two vocabularies in one feature is worse than waiting for B2's typed-reasons slice.
- **Fixing the disappearing-artwork bug with debounces or delays.** Rejected explicitly — it is coroutine ownership and stale-result acceptance (AD-6).
- **Rebuilding the Studio from scratch.** Rejected: provenance, previous-version, crop and originals support already exist in `ArtworkRecordEntity` and `RoutingArtworkStore`.
- **Overloading `ProviderGameLinkEntity` for artwork identity.** Rejected: its ownership and provider semantics are achievement-specific.
- **Filtering artwork by dimensions.** Rejected: dimensions inform preview and crop framing, never search eligibility.

## Data / Persistence

**Migration 41 → 42 — artwork multi-media**
- Add `sort_order INTEGER NOT NULL DEFAULT 0`, `provider_asset_id TEXT`, `crop_profile_key TEXT`, and (only if providers expose structured values) `media_region TEXT` / `media_form TEXT`.
- Rebuild the unique index `(game_id, artwork_type)` → `(game_id, artwork_type, sort_order)`. Existing rows migrate at `sort_order = 0`.
- Keep the `(platform_id, artwork_type, portable_name)` collision index; portable names now carry the ordinal.

**Migration 42 → 43 — Windows storefront identity**
- Add `storefront TEXT` and `storefront_game_id TEXT` to `games`. Index for duplicate lookup on the **pair** — a cross-store id is not globally unique.
- **Backfill in the same migration** by parsing store + app id out of `launch_intent_uri` for existing Windows rows. No re-scan, no user action.

**Store rules.** Single-art kinds replace position `0` explicitly. `VIDEO` and `SCREENSHOT` append at the next ordinal. `saveVersionedFrom*`'s prune must become ordinal-aware so it can no longer delete siblings.

**Compatibility.** Never destructive — `fallbackToDestructiveMigration` is never called in this repo and must stay that way. Existing artwork rows survive at order 0; Relink, Scan, backup, restore, delete-game and portable-name collision handling all continue to work with multiple rows. `userTitleOverride`, `userNote`, `locked` and `user_assigned` artwork are never overwritten without an explicit user decision. A blank incoming metadata value never clears a populated one.

## Implementation Phases

**Phase 0 — Foundation.** Ordinal naming, the two migrations, DAO/store list+reorder ops, storefront capture and backfill. Ships invisible; unblocks everything.

**Phase 1 — Race-safe search.** Request keys, generation guard, per-key caches, editable/submitted query, paging — inside the existing zone-based shell. Highest pain-to-effort ratio in the document and needs none of the UI rewrite.

**Phases 0 and 1 are the first merge** and are reviewed on their own. Phases 2–6 are replanned after
that lands, with real feedback from it. Do not treat the phases below as one continuous effort.

**Phase 2 — Identity matching.** Provider capability/candidate models, the tiered matcher at Tiers
1–3, `Matched as …` / Change Match / Forget Match, with Change Match backed by
`SteamGridDbApi.searchGame`. No ranked suggestion picker (AD-4). Cheaper than first planned:
`StudioRequestKey.matchId` already exists and is already part of the cache key, so confirming a
match invalidates the right entries without touching the key, the cache or the guard. *(Landed in
Merge 2; Merge 3 widened Change Match to every provider — see the note under AD-4. Task 2.4 makes
it reachable by controller.)*

**Phase 3 — Metadata presets.** Retrieval without writes, Current-vs-Incoming preview, the four
apply policies. The split has a clean seam (see AD-12).

**Phase 4 — Input layer.** Touch mode via `showTouchControls`, and pending-change prompts.
Square-to-search, Triangle-to-context and the stale L2/R2 KDoc all landed inside Phase 1 — task 4.2
is retired, not deferred. **Spatial navigation moved out of this plan entirely**: it is now
[`C17`](artwork-studio-navigation-plan.md), because the work turned out to be an adapter onto the
existing `core-navigation` engine rather than a new focus system, and nothing in Phases 2, 3, 5 or
6 depends on it. Tasks `4.3` and `4.4` depend on C17 landing.

**Phase 5 — Multi-media, starting with a consumer.** Phase 0 made multiple screenshots and videos
*storable*; nothing yet makes them *visible* (AD-13). So Phase 5 now opens with the Game Detail
media strip and only then builds the Studio-side queue: cross-page selection, sequential download
states, duplicate handling, ordering, primary screenshot, partial retry/cancel, storage warnings.

**Phase 6 — Crop profiles.** Registry keyed on kind → platform → game region → default → source
ratio, with Original Image as the universal fallback, a starter profile set, live final-result
preview, per-game override, session undo. Artwork-region keying is dropped from the resolution
order for now (AD-14).

**No further migrations.** Phases 2–6 as replanned need no schema change: `provider_asset_id` and
`crop_profile_key` already shipped in 41→42, and the storefront pair in 42→43. The database is
expected to stay at v43 for the rest of this plan.

## Verification Strategy

- **Unit (JVM):** title normalization, tier resolution, cache-key isolation (SGDB mature must not affect other providers' keys), stale-request rejection, client paging, cross-page selection, duplicate detection, metadata apply policies, crop-profile resolution order, physical-media fit never clipping detected bounds.
- **Room migration (Robolectric, `migrationTestHelper`):** one asset of every type survives at `sortOrder = 0`; multiple screenshots/videos insert after migration; single-art replacement still yields one active record; reorder is atomic; game deletion still cascades; storefront + id store without cross-store collision; the intent-URI backfill produces the right pairs.
- **Compose (Robolectric, already wired in feature-xmb):** Square focuses search from every region; Triangle opens context and never toggles mature; every control reachable without L2/R2; Back closes the top overlay then exits; touch checkbox vs artwork hit targets; presentation switch preserves page/focus/overlay/selection/crop; source change shows cache or skeletons, never another provider's grid.
- **Integration (fake adapters / MockWebServer):** assert call counts, assert no prefetch, assert full-list providers are called once, slow-A-then-fast-B never regresses, partial download failure retries only failed assets.
- **Manual, on device:** the disappearing-artwork repro (rapidly switch source mid-load), a Windows game's storefront match, and the ICON0 live crop.

Note: there is **zero existing coverage** for `ArtworkStudioViewModel`, the crop math, or `ArtworkRecordDao`. Every test here is net-new with no harness to build on — budget accordingly.

## Execution Task Index

| ID | Task | Depends On | Status |
|---|---|---|---|
| 0.1 | Give artwork filenames and portable names an ordinal, so multiple assets of one kind coexist and `sortOrder` is recoverable from disk | None | DONE |
| 0.2 | Migration 41→42: multi-media columns, rebuilt unique index, ordinal-aware store rules | 0.1 | DONE |
| 0.3 | Ordered list / append / delete / atomic-reorder / duplicate-lookup operations on `ArtworkRecordDao` and `ArtworkStore`, with `findAll` alongside `find` | 0.2 | DONE |
| 0.4 | Make Relink/Scan rebuild all rows of a multi-asset kind instead of collapsing to one | 0.3 | DONE |
| 0.5 | Capture storefront + app id in `PcGameScanner` **and** `PcShortcutImporter.reconcilePinnedShortcuts()` | None | DONE |
| 0.6 | Migration 42→43: storefront columns plus intent-URI backfill for existing Windows games | 0.5 | DONE |
| 1.1 | Model the search query as state: editable + submitted, submit-only execution, normalization that never alters the typed form | None | DONE |
| 1.2 | Replace `allResults` with per-key caches behind an immutable request key and a monotonic generation guard; delete the optimistic pre-clear | 1.1 | DONE |
| 1.3 | Isolate SteamGridDB mature state into the SGDB cache key only; move it off Square onto the SGDB context menu | 1.2 | DONE |
| 1.4 | Client paging at one-gridful pages with range/position display and skeletons for uncached pages | 1.2 | DONE |
| 2.1 | Provider capability/candidate/preset models that return data without persisting | None | DONE |
| 2.2 | The tiered matcher at Tiers 1–3 (saved provider ID → ROM CRC32 / storefront **pair** → unique exact normalized title on the expected platform), with provider IDs never crossed between providers | 2.1, 0.6 | DONE |
| 2.3 | `Matched as …` status, Change Match (backed by `SteamGridDbApi.searchGame`) and Forget Match, neither deleting local artwork or metadata; feed the confirmed match into the existing `StudioRequestKey.matchId` | 2.2 | DONE |
| 2.4 | Make Change Match and Forget Match reachable by controller: add both to the Triangle menu with the row's own visibility rules, and let `openActions()` open whenever a match provider is set (see "Controller gap found after Merge 3") | 2.3 | DONE |
| 3.1 | Extract `MetadataRepository.fetchForGame`'s four provider steps into a write-free `fetchCandidates`, splitting at the existing "nothing found" return (AD-12) | 2.1 | DONE |
| 3.2 | Current-vs-Incoming preview with the four apply policies, reusing `GameDao.updateMetadataIfMissing` for Fill Missing Only | 3.1 | DONE |
| 4.1 | ~~Replace `StudioZone` with spatial focus~~ — split out as its own plan | — | MOVED to [C17](artwork-studio-navigation-plan.md) |
| 4.2 | ~~Rebind Square to search and Triangle to context; delete the stale L2/R2 KDoc~~ | — | DONE (in 1.3) |
| 4.3 | Thread `showTouchControls` from `GameDetailScreen.kt:206` into the Studio; touch-sized targets and hit-target separation | C17 | READY |
| 4.4 | Pending-change and pending-exit prompts (Apply / Discard / Stay) on context switch and exit | C17 | READY |
| 5.0 | **Render what Phase 0 can already store**: `GameDetailViewModel.kt:265-273` builds the media strip from `find` (one screenshot, one video) — switch it to `findAll` so extra assets are visible at all (AD-13) | 0.3 | DONE |
| 5.1 | Give `StudioArt` a provider asset id and key cross-page selection on `provider + (providerAssetId ?: url)`, never grid index — SGDB supplies a real `SgdbArtItem.id`, ScreenScraper/TGDB/IGDB do not | 1.4, 0.3 | READY |
| 5.2 | Sequential download queue over the shipped `studioAppendFromUrl`, with per-item states, partial-failure retention, Retry/Remove Failed | 5.1 | READY |
| 5.3 | Duplicate detection through the shipped `findByProviderAssetId` / `findByOriginUrl` / `findByChecksum`, offering View Existing or Replace Existing | 5.1 | READY |
| 5.4 | Reordering via the shipped `reorderAssets`, primary screenshot (position 0), and storage/size warnings before a large apply | 5.2, 5.0 | READY |
| 6.1 | Crop profile registry keyed on kind → platform → **game** region → default → source ratio, with Original Image as the universal fallback and a starter profile set (AD-14) | None | READY |
| 6.2 | Live final-result preview for ICON0, box art and physical media from the same crop state | 6.1 | READY |
| 6.3 | Per-game/category profile override persisted in the shipped `crop_profile_key` column, with Reset to Platform Default | 6.1 | READY |
| 6.4 | Session Undo Last Apply over metadata, artwork replacement, ordering and crop | 3.2, 5.4, 6.2 | READY |
| L.1 | Measured grid capacity in the ViewModel: a pure `StudioGridCapacity` plus per-tab tile class replaces the fixed 4×5 constants; re-paging keeps the focused result (AD-17) | None | DONE |
| L.2 | Render exactly one measured page: the grid slot reports its size and draws `gridColumns` × `gridRows` with no scrolling | L.1 | DONE |
| L.3 | Title line and flat tabs: search joins the header, breadcrumb trail and SEARCH label go, eleven compact chips with LB/RB glyphs | None | DONE (`a9e0d28`) |
| L.4 | Current-artwork rail: 150 dp (200 dp at ≥1000 dp wide), caption moved in, true-aspect thumbnail, Y hint | L.3 | DONE (`a9e0d28`) |
| L.5 | Sources row, match line, page line and prompt bar: NSFW becomes a START badge, PREV/NEXT move under the grid, prompts drop to four | L.2, L.4 | DONE (uncommitted) |
| L.6 | Verify the layout on the Thor and at least two other screen sizes against the capacity table | L.5 | DONE (closed by the user 2026-09-11; two unverified points accepted, see "L.6 status") |
| M.0 | Timing logs for ScreenScraper's request gate and the Studio's match resolution, plus the account's real rate limits, for a device baseline | L.6 | DONE (uncommitted; baseline below) |
| M.1 | One cancellable job per browse resolves the active provider's match and then browses with it; matches remembered per open, so a tab switch never resolves again (AD-20) | M.0 | DONE (uncommitted; unit tests green, device walk checked by the user) |
| M.2 | SteamGridDB browses by the resolved match or the cached search, never a second autocomplete | M.1 | DONE (uncommitted; unit tests green, device walk checked by the user) |
| M.3a | Skip ScreenScraper's title search on platforms without ROM files (AD-23) | M.0 | DONE (uncommitted; unit tests green, device walk checked by the user) |
| M.3b | ScreenScraper identity from the catalog's one `jeuInfos` before resolving, with no duplicate checksum lookup | M.1 | DONE (uncommitted; unit tests green after a test compile fix, device walk checked by the user) |
| M.4 | Space ScreenScraper requests start to start, from the account's per-minute limit | M.0 | DONE (uncommitted; unit tests green, device walk checked by the user) |
| M.5 | Keep title searches between opens: 7 days, ScreenScraper empty answers 1 day, other providers' empty answers never (AD-21) | M.1 | DONE (uncommitted; unit tests green, verified on device 2026-09-11) |
| M.6 | Resolve SteamGridDB and IGDB in the background when the Studio opens (AD-22) | M.1 | DONE (uncommitted; unit tests green, verified on device 2026-09-11) |
| 7.1 | Adopt B2's `ScrapeFailure` for inline provider errors with Retry / Choose Another Source | B2 typed-reasons slice | BLOCKED |

`7.1` is BLOCKED on plan B2 landing its typed-reasons slice.

**Merges 1–3 landed (Phases 0–3, plus 5.0).** Tasks `0.1`–`0.6`, `1.1`–`1.4`, `5.0`, `2.1`–`2.3`
and `3.1`–`3.2` are implemented on `artwork-revisions` (`0677011`, `49ae052`). Phases 2–6 were
replanned after Merge 1, as promised; that replan is below. Task `2.4` landed next (`40fc03e`), and
`L.1` is done, and `L.2` landed with it (`4f162ed`, which also carries the ScreenScraper search
rework): the screen measures the grid slot and draws exactly one measured page with
`userScrollEnabled = false`, `STUDIO_GRID_COLUMNS` is gone, and the paging pills read the
ViewModel's page size instead of a hardcoded `20`. L.1's Studio tests still pass, 74 across
`StudioGridCapacityTest`, `ArtworkStudioViewModelTest` and `StudioSearchTest`. `L.3` and `L.4` landed
in `a9e0d28`; `L.5` is implemented and uncommitted at the time of writing. Next up: finish `L.6`, then
the matching-latency tasks `M.0`–`M.6` (see "Matching latency"), then Merge 4.

### What landed, and the decisions taken while landing it

- **Ordinal rule (0.1).** `_NN`, exactly two digits, applied to BOTH the internal fixed name and
  the portable name (`ArtworkFileNaming.withOrdinal`). Position 0 keeps the historic bare name, so
  no existing install's files move. Two digits is what keeps the ordinal namespace disjoint from
  `versionedName`'s 13-digit timestamps, and ordinals are only ever PARSED for
  `MULTI_ASSET_KINDS` (`SCREENSHOT`, `VIDEO`) — a ROM stem that genuinely ends in `_07` can never
  be misread as another game's seventh screenshot.
- **Only one schema export exists for the pair of migrations.** Room exports the schema of the
  version the database currently declares, and 41→42→43 landed together, so there is no `42.json`.
  `Migration41To42Test` therefore runs both migrations and validates at 43; its assertions are all
  about what 41→42 does. If 42 ever needs auditing on its own, it has to be re-exported by
  compiling at that version.
- **The storefront index is declared on `GameEntity`, not only created in SQL.** Creating it in the
  migration alone reproduced exactly the `index_games_one_disc_primary` failure this repo already
  documents — Room's post-migration validation saw an index its schema did not expect and refused
  to open the database.
- **Relink matches on the FULL stem first** and only falls back to an ordinal-stripped base when
  that finds nothing (0.4), so ordinal recovery can never re-route a file that already matches a
  real game name. Known cosmetic limit: a game whose own portable name ends in `_NN` will have its
  single screenshot recovered at that position rather than 0. Nothing resolves by position 0
  specifically — `get()` returns the LOWEST position — so this affects ordering only.
- **Square now opens search (1.1/1.3).** Task 1.3 frees Square by moving the SteamGridDB mature
  filter onto that source's context menu; leaving it dead until task 4.2 would have shipped a
  search field no controller could reach. The full spatial-focus rework is still 4.1/4.2's.
- **ScreenScraper ignores the query.** It is addressed by `ss_id`, not by a title, so a different
  query returns the same media list. Re-pointing SS at another game is Phase 2's Change Match, not
  a search. Documented on `ssResults`.
- **`matchId` is already in `StudioRequestKey`** (always null today) so Phase 2's tiered matcher
  invalidates exactly the right cache entries without touching the key class.

### Verification actually run

`:core:core-data`, `:feature:feature-artwork`, `:feature:feature-launcher`,
`:feature:feature-settings` and `:feature:feature-xmb` unit tests pass, and `:app:assembleDebug`
succeeds. `DisplaySettingsViewModelGameBootTest > toggling retires the unreleased mode key` fails,
but it fails identically on the clean tree — it is C13's, not this plan's.

Net-new coverage (there was none for any of this before): `ArtworkFileNamingTest` (ordinals,
sibling-safe pruning), `StorefrontIdentityTest`, `Migration41To42Test`, `Migration42To43Test`,
`StudioSearchTest` (normalization, key isolation, LRU cache, paging) and
`ArtworkStudioViewModelTest` — including a direct repro of the disappearing-artwork bug: a slow
SteamGridDB response completing after the user has switched to TheGamesDB must not repaint the grid.

## Replan of Phases 2–6 (after the first merge)

The plan said Phases 2–6 would be replanned once Phases 0 and 1 landed. They have. Seven things
changed, all verified against the tree rather than assumed:

1. **Task 4.2 is done, not pending.** Task 1.3 had to free Square to move the mature filter onto
   the SteamGridDB context menu, so Square was rebound to search in the same change; Triangle
   already opened the context menu before this plan started; and both stale "L2/R2 switch sources"
   KDocs were deleted. Nothing of 4.2 is left. Retired rather than carried.
2. **Multi-media is storable but invisible** — new task 5.0, and it goes first (AD-13).
3. **`StudioArt` has no provider asset id**, so task 5.1's "keyed on `provider + providerAssetId`"
   cannot be implemented as written. Only SteamGridDB exposes a real per-asset id
   (`SgdbArtItem.id`); `SsCachedMedia` carries only `(type, region, url, format)`, and TGDB/IGDB
   return a single asset with no id at all. The key becomes
   `provider + (providerAssetId ?: url)`.
4. **Phase 2 got cheaper.** `StudioRequestKey.matchId` shipped in Phase 1 and is already part of
   the cache key and the generation guard, so 2.3 wires the confirmed match in without touching
   the key class, the cache or the reducer.
5. **Phase 3 has a clean seam** rather than an open-ended refactor (AD-12).
6. **Phase 6 loses artwork-region keying** for now, because it is the one input with no persisted
   source (AD-14).
7. **Phases 2–6 need no migration.** `provider_asset_id` and `crop_profile_key` shipped in 41→42,
   the storefront pair in 42→43, and the DAO/store operations 5.2/5.3/5.4/6.3 depend on
   (`findAll`, `getAt`, `maxSortOrder`, `deleteAtAndCompact`, `reorder`, `findByProviderAssetId`,
   `findByOriginUrl`, `findByChecksum`, `studioAppendFromUrl`, `deleteAssetAt`, `reorderAssets`,
   `nextSortOrder`) are all in place. The database should stay at v43 for the rest of this plan.

`7.1` stays BLOCKED: plan B2 is still `❌` in the index and `ScrapeFailure` has zero hits in the
tree, so there is nothing to adopt yet.

### Task 4.1 is now plan C17

Sizing `4.1` turned up something that changes its shape: this repository already has
`core-navigation`, a 613-line pure-JVM navigation engine with 670 lines of tests —
`NavigationNode`, a modal context stack, component-owned edit mode, nearest-survivor focus
recovery, and a `gridMove` helper for exactly this kind of tile grid. `feature-settings` is an
adapter onto it, and `feature-xmb` **already depends on it** and already uses `gridMove` in
`AppPickerLogic`.

So `4.1` is not "build a focus system for the Studio" — it is "write the Studio's adapter onto a
tested core another surface already proved out", with the results grid as a single edit-mode node.
That is a smaller and much better-understood job than it looked from inside this plan, and it is
still large enough, and independent enough, to be its own plan:
[`C17` — Artwork Studio spatial navigation](artwork-studio-navigation-plan.md).

Nothing in Phases 2, 3, 5 or 6 depends on C17. Tasks `4.3` and `4.4` do.

### Suggested merge order

Dependency order permits several sequences; this one front-loads visible payoff and keeps each
merge independently reviewable:

| Merge | Tasks | Why here |
|---|---|---|
| 2 | `5.0` → `2.1` → `2.2` → `2.3` | Makes Phase 0's storage visible, then fixes the reported "wrong match is a dead end" pain. No dependency on the input rework. |
| 3 | `3.1` → `3.2` | Metadata presets ride Phase 2's candidate models; the seam (AD-12) is already located. |
| 3b | `2.4` | *Added 2026-09-10.* Merge 2 shipped Change Match and Forget Match touch-only. One ViewModel file and its test; it should not wait for C17. |
| 3c | `L.1` → `L.2` → `L.3` → `L.4` → `L.5` → `L.6` | *Added 2026-09-10.* The approved target layout (see "Studio layout rework"). Lands before Merge 4 so Phase 5's queue UI is built into the final layout, and before C17 so C17's regions match it. |
| 3d | `M.0` → `M.1` → `M.2` → `M.3a` → `M.3b` → `M.4` → `M.5` → `M.6` | *Added 2026-09-11.* User request: identifying a game takes too long. M.1–M.3b and M.6 rewrite how the Studio sequences resolve and browse, which Merge 4's queue builds on, so they land first. |
| 4 | `5.1` → `5.2` → `5.3` → `5.4` | The Studio-side multi-media queue, on top of a strip that already renders it. |
| 5 | `6.1` → `6.2` → `6.3` | Crop, entirely self-contained. |
| 6 | [C17](artwork-studio-navigation-plan.md), then `4.3` → `4.4`, then `6.4` | The input rework, now its own plan; `6.4`'s session undo spans metadata, ordering and crop, so it wants all three landed. |

### Still open from Phase 1's own goals

- Task 1.4's "skeletons for uncached pages" is per-REQUEST, not per-page. Because all paging is
  client-side (AD-3), a page is never individually uncached — only a whole request key is.
- The manual on-device checks in Verification Strategy have not been run: the rapid-source-switch
  repro, a Windows game's storefront match, and the ICON0 live crop.
- `L.2`, `L.3` and `L.4` are built and unit-tested but have never been seen on the Thor, and every
  one of their acceptance criteria is visual: one measured gridful with the D-pad cursor always on
  screen, eleven chips visible at 833 dp inside a 36 dp header, and a rail that matches the mockup
  with nothing clipped. `L.6` is the task that checks them across screen sizes; until then the chip
  row has only an arithmetic argument behind it (11 chips at 10.5 sp plus 16 dp of padding and ten
  4 dp gaps is roughly 680 dp of the Thor's 833 dp of width).

## Merge 2 landed (5.0, 2.1, 2.2, 2.3)

Implemented on `artwork-revisions`, in the suggested order. Decisions taken while landing it:

- **The media strip reads `findAll` for VIDEO and SCREENSHOT only** (5.0). They are exactly
  `ArtworkFileNaming.MULTI_ASSET_KINDS`; TITLESCREEN stays a single `find`, and ICON1 stays the
  single-art fallback for a game with no full video. `videoUri` — what the player opens — is the
  FIRST video, so a game with five videos still has one default.
- **`TitleKey` replaced `StudioQuery`'s private regexes** and `StudioQuery` now delegates to it.
  The query that addresses a result cache and the title that resolves a match must be the same
  function, or a match and its cached page can disagree about what the user asked for.
- **The capability table is the matcher's only source of provider truth** (2.1). `supportsTitleSearch`
  is true for SteamGridDB alone — which is AD-4 stated as data instead of prose, and is what makes
  Tiers 4-6 a flag flip plus a branch rather than a rewrite.
- **A user-confirmed match is stored as `MatchTier.SAVED_PROVIDER_ID`** (2.3). It is about to be
  written to the game row, so the next resolve genuinely reads it back at Tier 1; inventing a
  fourth tier for it would have made the enum describe UI provenance instead of evidence strength.
- **`GameDao.updateProviderMatch` writes exactly one provider column** via a `CASE WHEN :provider`
  guard, and is deliberately NOT COALESCE-guarded — Forget Match has to actually clear the column.
  It touches no artwork and no metadata column, which is the plan's "neither deleting local
  artwork or metadata" made structural rather than promised.
- **`SteamGridDbApi.getGameBySteamAppId`** was added: `/games/steam/{appid}` is the one direct
  storefront lookup the API offers, and without it Phase 0's captured storefront pair had no Tier 2
  consumer at all. Only the Steam half of a pair resolves; an Epic or GOG id is never retried as a
  Steam id.
- **Change Match is disabled, not hidden, on single-result providers.** The mockup shows CHANGE
  MATCH while ScreenScraper is the active source, and the button stays there for every provider so
  the row does not change shape as the user walks the sources. On a provider that returns one game
  per title there is nothing to pick FROM, so the button renders inert and pressing it says why —
  silence on a press reads as broken. It becomes live for any provider whose
  `ProviderCapabilities` row gains `supportsTitleSearch`, which is what the deferred IGDB/TGDB
  multi-result search would do.
- **The approved mockup is `docs/mockups/artwork_image_mockup.png`** (supplied as a PNG, not HTML).
  The match row was built to it: check glyph, "Matched as <title>", a green `Confirmed` chip, and
  right-aligned FORGET / CHANGE MATCH, sitting between the source row and the grid. Its SAVED
  SCREENSHOTS panel, per-tile checkboxes, ADDED badge and "n selected · size" apply bar are Phase
  5's (tasks 5.1-5.4), not this merge's.

Net-new coverage: `GameMatcherTest` (tier ordering and short-circuiting, no cross-provider id
reads, the storefront pair, ambiguity as a miss, title keying) and six match cases added to
`ArtworkStudioViewModelTest`; the two media-strip cases live in `GameDetailViewModelTest`.

## Merge 3 landed (3.1, 3.2)

Implemented on `artwork-revisions`, in the suggested order. Decisions taken while landing it:

- **`fetchCandidates` is a move, as AD-12 promised** (3.1). Everything above the "nothing found"
  return became `MetadataRepository.fetchCandidates`, returning `MetadataCandidates`; `fetchForGame`
  calls it, checks `isEmpty` at the same seam, and runs its application half unchanged. Two pieces
  of provider bookkeeping stayed on the retrieval side because moving them would change what the
  batch scraper asks next: a live ScreenScraper response still refreshes `ss_media_cache` (a
  response cache, never game state), and SS quota/credential failures still trip the batch guards.
  Retrieval writes no `games` column and saves no artwork file — pinned by `confirmVerified`.
- **The four apply policies were not named anywhere** — not in this plan, the index, or the tree,
  and the external spec is not on disk. Only Fill Missing Only was. Chosen with the user on
  2026-09-10: **Replace All** (every differing incoming value overwrites; blank never clears),
  **Fill Missing Only**, **Choose Fields** (Replace All restricted to ticked rows) and **Keep
  Current** (close, write nothing).
- **One function decides what is written** (`MetadataApply.plan`). The preview's green "will
  change" markers and `ArtworkRepository.applyMetadata`'s SQL both read it, so the overlay cannot
  promise a change the database does not make. Fill Missing Only therefore treats NULL — and only
  NULL — as missing, mirroring the reversed COALESCE it runs through.
- **`updateMetadataIfMissing` grew four columns** (`age_rating`, `franchise`, `community_rating`,
  `release_date`) so Fill Missing Only covers every field a preset carries. Every new parameter
  defaults to null and `COALESCE(x, NULL) = x`, so the gamelist.xml importer's call is unchanged.
  Replace All / Choose Fields go through the existing `updateMetadata` with text columns only.
- **TITLE is `scraped_title`, never `user_title_override`.** A preset can refresh the scraped name;
  nothing in this path can write the override, which still wins on screen.
- **The preview bypasses the ScreenScraper media-URL cache.** A cache hit is URL-only
  (`SsMediaSelection.infoFromCache` nulls every text field), so honouring it would silently offer
  no ScreenScraper preset for exactly the games that were scraped before. Cost: one jeuInfos call
  per explicit open, never per batch scrape.
- **Presets come from ScreenScraper and TheGamesDB only.** `IgdbGameInfo` carries cover/hero URLs
  and no text (its Apicalypse query requests `name,cover,artworks` and nothing else), so IGDB never
  yields a preset. 2.1's `ProviderCapabilities` had marked IGDB `suppliesMetadata = true`; that was
  wrong against the API and is corrected here. Nothing read the flag, so no behaviour changed.
  TheGamesDB is still only asked when ScreenScraper left a gap (the retrieval
  order is `fetchForGame`'s, unchanged by 3.1), so a fully-populated SS answer shows one source.
- **The overlay lives on Game Detail** (Options ▸ Update Metadata), not in the Studio, which stays
  artwork-only. Focus opens on Apply with Fill Missing Only selected — the non-destructive default.
  Up/Down rows, Left/Right policy, L1/R1 source, Select toggles a row (which switches the policy to
  Choose Fields) or applies, Back closes without a write. A generation token drops a retrieval that
  finishes after the overlay was closed (AD-6's rule, applied here). Apply re-reads the game row,
  so a preview left open never writes against stale values.

**Fixed while landing Merge 3, from on-device use (2026-09-10).** Final Fantasy VI Advance read
"No IGDB match" although IGDB has the game — and the device log showed IGDB answering the query.
Two defects, both from earlier merges:

- **IGDB could never match anything.** Tier 1 needs a saved `igdb_id`, which only a confirmed
  Change Match writes; Tier 2 does not cover IGDB; Tier 3 and Change Match were both gated off by
  `supportsTitleSearch = false`. So every game in the library read "No IGDB match", with no way
  out. With the user's go-ahead, **part of AD-4's deferred work landed here**: `IgdbApi.searchGames`
  (`search …; fields name,first_release_date,cover.image_id; limit 10;`), an IGDB branch in
  `ProviderMatchEvidence.searchByTitle`, and IGDB `supportsTitleSearch = true`. Tier 3 now resolves a
  unique exact normalized title on IGDB and Change Match opens a real list. A matched IGDB game is
  browsed by id (`fetchGameInfoById`, `where id = …;`), so the art comes from the game the user
  picked, not whatever ranks first. IGDB search is not platform-scoped — there is no IGDB
  platform-id table in the tree — so uniqueness rests on normalized title alone, and ambiguity is
  still a miss. A batch scrape still does NOT save `igdb_id`: its `limit 1` hit is a guess, and
  persisting it would promote a guess to Tier 1 evidence. The ranked picker and Tiers 4–6 stay
  deferred.
- **TheGamesDB had the same dead end**, reported on-device right after. `Games/ByGameName` always
  returned a list; `fetchGameInfo` just kept `firstOrNull()`. Same shape of fix:
  `TheGamesDbApi.searchGames` (every hit, still `filter[platform]`-scoped when the platform is in
  `PLATFORM_IDS`, which makes TheGamesDB the one title-searchable provider that honours 2.2's "on the
  expected platform"), `fetchGameInfoById` over `Games/ByGameID`, a TheGamesDB branch in
  `ProviderMatchEvidence`, `supportsTitleSearch = true`, and the Studio browsing a matched game by id.
  Images are parsed per game id (`TheGamesDbApi.infoFrom`), since one ByGameName response carries
  every hit's images. Unlike IGDB, a batch scrape DOES save `tgdb_id` — pre-existing behaviour from
  `fetchForGame`, left unchanged — so a game scraped against a wrong first hit resolves that wrong id
  at Tier 1; Change Match is now the way to correct it. ScreenScraper is the only provider left
  without title search, and its Change Match message now says it can't be searched by title rather
  than claiming it returns one game.
- **TheGamesDB could never run at all.** The device log showed every lookup stopping at "no API
  key configured": `MetadataApiKeyProvider` has stored and read `tgdb_api_key` since `1b7da7e1`, but
  no screen ever called `saveTgdbKey`, so only a backup restore could supply one. Settings ▸ Artwork
  now has a TheGamesDB API key field (encrypted like the others; setup wizard not yet). In the
  Studio a keyless provider — SteamGridDB, TheGamesDB or IGDB — is **disabled, not hidden** (user
  decision, 2026-09-10): it keeps its place in the source row marked "no key", source cycling skips
  it, selecting it explains what it needs, and it is never asked. Previously SteamGridDB and IGDB
  were silently removed from the row. Availability is re-read on every open, including reopening
  the same game: the Studio VM outlives the screen and used to read keys once per game, which is
  why a key saved in Settings did not take effect.
- **The Change Match picker could not be navigated with a controller.** It focused its query field
  on open, which raised the IME; an open IME receives key events before
  `MainActivity.dispatchKeyEvent`, so `GamepadInputHandler` and the Studio never saw the D-pad, A or
  B. Rebuilt on the `WizardTextField` model: the field is cursor stop -1 above the candidates, the
  keyboard opens only when A (or Square) starts editing, the keyboard's Search ends editing, any pad
  press that reaches the ViewModel while editing ends editing first, and the list scrolls to the
  cursor. The Studio's own search overlay still focuses its field on open and has the same exposure;
  it only needs A and B, so it was left for a separate change.
- **ScreenScraper had two matching failures of its own** (device log, 2026-09-10):
  - *The grid matched but the row did not.* `SsMediaCatalog`'s live `jeuInfos` identified an `.nds`
    by ROM name + size + CRC and saved `ss_id`/`rom_crc32` to the row, but the match row had
    resolved against the game as first loaded (no id, no CRC) and never looked again. After a
    ScreenScraper browse the Studio now re-reads the game and re-resolves when either changed
    (`refreshSsIdentityAfterBrowse`) — no extra request, since the next browse is a media-cache hit.
  - *A Windows game could never match.* With no ROM, `jeuInfos` was sent a bare `systemeid` and
    answered HTTP 400 on every open. `fetchGameInfo` now refuses a lookup with no id and no ROM
    checksum or file name (`canLookUp`), and ScreenScraper gained name search —
    `ScreenScraperApi.searchGames` over `jeuRecherche.php`, `systemeid`-scoped when mapped — so
    `supportsTitleSearch` is now true for **every** provider. A title-matched ScreenScraper game is
    browsed by id through `SsMediaCatalog.mediasFor(gameId, matchedSsId)`, which caches its medias
    but never writes that id to the row: only a ROM identity or a confirmed Change Match sets
    `ss_id`. Cost: an unmatched game spends one `jeuRecherche` request per Studio open. *(Wrong in practice:
    it was one per match resolution. See "ScreenScraper Change Match dead end".)*
- **A cancelled browse was cached as "No results"** (a Phase 1 race-safety hole). `IgdbApi` caught
  `Exception` and the ViewModel's `runCatching` wrappers caught everything, so a source switch
  mid-load turned the `CancellationException` into an empty list — and `loadResults` then stored it
  under the request's own key. `loadResults` now calls `ensureActive()` before caching, and
  `IgdbApi` rethrows cancellation. Repro test: `a browse cancelled by a source switch is never
  cached as No results`.

Change budget: over `PLANNING_WORKFLOW.md` §4 by one modified file and one test file —
`GameDao`, `ArtworkRepository`, `GameDetailViewModel`, `GameDetailScreen`, `MetadataRepository`
modified; `MetadataApply.kt` and `MetadataPreviewPanel.kt` new; `MetadataApplyTest` and
`MetadataRepositoryCandidatesTest` new, `GameDetailViewModelTest` extended. 3.1 and 3.2 landed
together, which is where the overrun comes from.

## Controller gap found after Merge 3 — task 2.4

**Change Match and Forget Match can't be reached with a controller** (found 2026-09-10). This breaks
Goal 8 ("every control reachable by D-pad + Confirm + Back + Square + Triangle"), and it breaks
it on the one row Merges 2 and 3 were about.

Verified against `49ae052`:

- **The row's buttons are touch-only.** `FORGET` and `CHANGE MATCH` are `Modifier.clickable`
  text and nothing more (`ArtworkStudioScreen.kt:450-477`). No `GamepadAction` path reaches
  `onChangeMatchPressed()` or `forgetMatch()`. The zone ladder (`ArtworkStudioViewModel.kt:1489-1543`)
  has no rung for the match row, and `StudioAction` (`:184-191`) has no entry for either.
- **Triangle can't serve as a workaround yet.** `openActions()` returns early when the slot has no
  artwork and SteamGridDB is not the active source (`:1087`). That describes the unmatched game on
  IGDB, TheGamesDB or ScreenScraper, which is exactly the case Change Match exists to rescue. The
  menu wouldn't open there even with an entry in it.
- **No free button.** All eleven `GamepadAction`s are already bound in the Studio: D-pad, A, B,
  Square (search), Triangle (options), LB/RB, START (mature, SteamGridDB only).
- **Why the tests missed it.** Every match test enters through a ViewModel function
  (`vm.onChangeMatchPressed()`, `changeMatchOpenWithResults()`), never through
  `handleGamepadAction`. `the Change Match picker can be walked and confirmed with the controller
  alone` is true once the picker is open; nothing proves a controller can open it.

**Fix: both entries go on the Triangle menu.** Only `ArtworkStudioViewModel.kt` changes. The
overlay already renders `availableActions` generically (`ArtworkStudioScreen.kt:996-1009`), so
the screen needs no edit.

1. `StudioAction` gains `CHANGE_MATCH("Change Match")` and `FORGET_MATCH("Forget Match")`.
2. `availableActions` lists `CHANGE_MATCH` whenever `matchProvider != null`, and `FORGET_MATCH` only
   when `matchIsConfirmed`. These are the row's own visibility rules
   (`ArtworkStudioScreen.kt:449`, `:463`), so the menu and the row can never disagree about what
   is on offer. Both go after `TOGGLE_MATURE`, since all three concern the active source rather
   than the slot.
3. `runAction` routes `CHANGE_MATCH` through `onChangeMatchPressed()`, not `openChangeMatch()`, so
   a provider without title search explains itself exactly as the button does. That branch must
   close the menu before posting its message, or the message lands under the overlay.
   (`openChangeMatch()` and `forgetMatch()` already clear `actionsOpen`, at `:806` and `:909`.)
4. `openActions()` also opens when a match provider is set, alongside current artwork and an active
   SteamGridDB. A source with no match provider (`resolveMatch` nulls it at `:752`) and no artwork
   still opens nothing, so the menu is never empty.
5. Fix the doc comments this contradicts, all in the same file. `canChangeMatch` (`:157-161`) still
   says SteamGridDB alone, and `availableActions` and `openActions` still say "per-slot". The
   `onChangeMatchPressed` inert branch stays, because `ProviderCapabilities` is the switch, but no
   provider reaches it today, and its doc comment should say so.

**Tests** go in `ArtworkStudioViewModelTest`. Each one is driven **only** through
`handleGamepadAction`, which is the point of the task:

- On IGDB with no artwork and no match, Triangle opens the menu, `CHANGE_MATCH` is listed, and
  moving to it and pressing Select opens the picker and searches.
- `FORGET_MATCH` is absent until a match is confirmed and present after. Selecting it writes
  `updateProviderMatch(…, null)`.
- On SteamGridDB, `TOGGLE_MATURE` and `CHANGE_MATCH` are listed together, in that order.
- On a source with no match provider and no artwork, Triangle still opens nothing.

**Rejected:**

- **A fourth `StudioZone` rung** (MATCH, between SOURCES and GRID). This is exactly what C17 exists
  to stop: a new enum case plus a branch in all six `when (s.zone)` blocks, which C17's task 2.2
  would then delete.
- **Rebinding a button.** None is free, and giving START a second, source-dependent meaning would
  make it mean two things on one screen.
- **Waiting for C17.** C17 is Merge 6 in the suggested order. Change Match would stay touch-only
  through Merges 4 and 5 in a controller-first app.

**Relationship to C17.** This is the bridge, not the destination. In C17 the match row becomes a
real region between sources and grid, with `FORGET` and `CHANGE MATCH` as its `children`. The menu
entries survive as a shortcut, the same way Square survives as a shortcut to the search field.
Recorded in C17's task 1.2 and task 2.3.

## Landed with task 2.4 (2026-09-10)

Task 2.4 landed as specified above; its four tests drive the ViewModel only through
`handleGamepadAction`. Three more fixes came out of on-device use in the same session. None is a
task in this plan, so they are recorded here instead of silently widening one.

- **Every source is listed on every Studio tab** (user decision). `sourcesForTab()` is now
  `StudioSource.entries`. SteamGridDB, TheGamesDB and IGDB are drawn "· n/a" and skipped by
  cycling on ICON1, Manual and Video, the tabs no image provider has anything for; "n/a" outranks
  "no key", since a key would not help there. On 3D Box, Physical Media and Screenshots, which have
  no provider art type of their own, each provider offers everything it returns: SteamGridDB
  grids, heroes, logos and icons (one request per type, labelled by type), TheGamesDB box art,
  fanart and clear logo, IGDB cover and artwork. The five type-matched tabs are unchanged. Real
  TheGamesDB/IGDB screenshots are not fetched by either client today; adding them is follow-up work.
- **A renamed game kept its title "stale" in the XMB flyout.** It did not: game lists sort by
  display title, and every refresh replaced `currentItems` while keeping `selectedItemIndex`, so
  after a rename the cursor sat on whichever game moved into that slot. `cursorAfterRefresh`
  re-finds the row by id. Live game lists apply it on every emission after their first, and the
  three callers that reload the list already on screen (closing Game Detail or App Detail, Edit
  Title, and `observeCategories` reacting to any games-table write) pass `keepCursorOnRow = true`.
  A fresh drill-in still lands on its remembered cursor.
- **Artwork applied at a stable URI never refreshed on screen.** A portable-library write keeps its
  file name, so its document URI and the game column are unchanged: every `AsyncImage` already
  showing it had an equal model and never asked again, and evicting Coil did nothing for them.
  `ArtworkRevisions` (core-ui) keeps a per-URI revision in snapshot state, bumped by
  `ArtworkImageCache.evict`; `rememberArtworkModel` / `ArtworkRevisions.cacheKey` put the revision
  into the memory-cache key, so the model changes and the image reloads. Wired into the game art
  surfaces only: XMB icons in every tile style, hover background and logo, Game Detail media and
  hero/icon, App Detail's custom icon, and the Shiba Coins library.

Tests: 2.4's four controller tests and four source-visibility tests in `ArtworkStudioViewModelTest`,
`CursorAfterRefreshTest`, and a revision case in `ArtworkImageCacheTest`. All pass. Not yet checked
on device: the rename cursor, and a portable-folder art apply refreshing the XMB tile.

## ScreenScraper Change Match dead end (2026-09-10)

**Found on device** with a Windows install of Tactics Ogre. Every ScreenScraper name search went out
with `systemeid=138` (PC Windows) and came back HTTP 200, and no `jeuInfos` ever followed: nothing
matched. The Windows-scoped search most likely finds nothing because ScreenScraper files the game
under its console releases (response bodies are not logged, so this is inferred). Every way out ran
that same search: the row said no match, the grid said ScreenScraper had nothing, and Change Match
said "No games found" whatever was typed. The log also showed ten identical searches in four minutes,
because the matcher searched again on every source switch, tab switch and search.

Fixes (user-approved; implemented, not yet built or verified on device):
- **Change Match widens, for ScreenScraper only.** When the platform search is empty the picker
  searches every system (`ProviderMatchEvidence.searchScreenScraperOnAnyPlatform`), shows each hit's
  system, and says the list spans every platform. The matcher never widens: an automatic
  cross-platform match would be a guess. A picked id is browsed by `gameid`, which needs no system.
- **Title searches are remembered per Studio open** (`CachingMatchEvidence`), empty answers
  included, since providers report failures as empty. A picker search always asks again and
  refreshes the entry.
- **The picker has its own request token.** Sharing the matcher's left the row on "Matching…" when
  the picker opened mid-resolution; confirming a match mid-resolution now clears the flag too.
- **The ScreenScraper empty grid says why:** still looking, no match (use Change Match), or no media
  of this type.
- **`ScreenScraperApi.searchGames` logs its hit count**, as the SteamGridDB client already does.

Not done: TheGamesDB's search is platform-scoped the same way and can reach the same dead end.

**Device result (same day): the widening did not work.** The Windows search was confirmed empty
(`→ 0 hits`). The all-platforms search for "Tactics Ogre" hit the 15 s socket timeout and was shown
as "No games found"; for "Tactics Ogre Reborn" it answered after 13 s with 0 hits. The unit tests
stayed green because they mock `ProviderMatchEvidence` and use a hand-written `jeuRecherche`
fixture, so they never saw a real body, a slow reply or a failure. The same timeout's warning also
printed both ScreenScraper passwords to logcat: the debug `DebugTree` was not redacted.
Next, in order (user-approved 2026-09-10): (1) ground truth — the user checks screenscraper.fr, and
debug builds save each redacted `jeuRecherche` body to `cache/ss-captures/` for a real fixture;
(2) logcat now goes through `LogRedaction` (which also gained Steam `key` and IGDB `client_id`, and
no longer blanks the app's own `ssId=` lines). Then: failure distinct from "no hits", tests over
`ktor-client-mock` with the captured body, and keep or remove the widening based on (1).

**Captured, second device run:** parsing works on real bodies. "Tactics Ogre: Reborn" across every
platform returns one hit (PS5, id 478505); "Tactics Ogre" returns nine, including Switch "Tactics
Ogre - Reborn". The account allows one request at a time (`maxthreads` 1), a Windows-scoped search
takes 3–4 s and has found nothing for any title, and an every-platform search takes 9–11 s of
ScreenScraper's own time whatever the body size. Most of the wait users saw was queueing: old picker
searches were never cancelled and held the single slot. **Search optimizations** (user-approved;
implemented, not yet built or verified): a new submit, closing the picker or confirming cancels the
running search; every title search, every-platform included, is remembered per open and shared while
in flight, and a failure (now `SsSearchFailedException`) is never remembered and shows as an error,
not "No games found"; a Windows game's picker asks every platform once, Windows hits first; the
picker says when it is on the slow every-platform search. Tests now parse three real captured
bodies.

**Resolved: the PS5 entry's "104 media" were never artwork.** In the captured nine-hit body every one
of 478505's media has `parent` `editeur`, `developpeur`, `genre`, `classification`, `joueurs` or `note`
(publisher, genre and rating pictograms: `pictoliste`, `pictomonochrome`, `pictocouleur`), and none
has `parent: jeu`. ScreenScraper has no game art for the PS5 release, so "nothing of this type" was
correct; `SsMediaSelection` picks by type and never shows pictograms. The Switch release (425726)
carries the box, screenshot and title art (37 `parent: jeu` media). No `jeuInfos` capture was on the
device, so the installed build likely predates that capture. Also found: the captures redacted URL
credentials but not `ssuser.id`, the account name, inside the JSON; captures now drop `ssuser` and
`header.commandRequested` before writing (`ScreenScraperApi.scrubCapture`). Follow-ups (user-approved,
not yet built): a failed automatic match says the provider didn't answer (`matchFailed`) instead of
"no match", and each Change Match candidate shows how many media of its own (`parent: jeu`) its
release has, so a release with no art reads "no media" before it is confirmed.

## The browse-cancel crash: both Ktor clients moved to OkHttp (2026-09-10)

**Found on device, not by a test.** Cancelling a browse while its response body was still
downloading killed the app, and the exception came back out of `Job.cancel()` on the main thread —
so it was never one call site but *every* cancel path: the browse cancel, the new Change Match
cancel (which lands mid-download on a 10 s every-platform search), and the ViewModel's scope being
cleared on close.

**Cause, read out of Ktor 3.5.2's own sources rather than inferred.** `attachToUserJob` passes a
cancel to the request's job and then to the body reader (`RawSourceChannel`), whose cancel handler
calls `source.close()` with no try/catch, on the thread that called `cancel()`. With
`HttpClient(Android)` that source is the platform's `HttpURLConnection` stream, and closing it from
the main thread while the IO thread is still reading it throws from inside the platform's
networking stack. No unit test could see it: they mock the network layer, so no response body is
ever downloaded and then cancelled.

**Fix: both Ktor clients use the OkHttp engine** (`ArtworkModule.kt`, `DiscordNetworkModule.kt` —
Discord's device-grant polling is cancellable too). `ktor-client-okhttp` 3.5.2 cancels through
`callContext[Job]!!.invokeOnCompletion { call.cancel() }` — `Call.cancel()`, documented as safe
from any thread — instead of a blocking stream close. OkHttp's engine config has no
`connectTimeout`/`socketTimeout` properties (unlike the Android engine's), so the 15 s ceiling
moved into `engine { config { connectTimeout(...); readTimeout(...) } }`. The engine's
`error("OkHttpClient can't be constructed because HttpTimeout plugin is not installed")` line is
dead code rather than a trap: `createLRUCache`'s `get()` memoizes through its supplier, so
`HttpTimeout` never has to be installed for the engine to build a client.

**Cost, checked against the actual resolution rather than the POM in isolation.** The engine lifts
`com.squareup.okhttp3:okhttp` from 4.12.0 to 5.3.2 app-wide — Coil's network fetcher (every artwork
image) and Retrofit (Steam) included — and `logging-interceptor`, which only RetroAchievements'
api-kotlin asks for and only at 4.12.0, is pinned to 5.3.2 in the version catalog so the one
artifact that would otherwise stay behind cannot call 5.x internals it was not built against.
Pinning OkHttp back to 4.12.0 is not an option: Ktor 3.5.2's `Protocol.fromOkHttp()` references
`Protocol.HTTP_3`, which does not exist before OkHttp 5 (`NoSuchFieldError` at class init).

**Not verified:** ktor-client-okhttp was not in the Gradle cache, so its cancel path was read from
the 3.5.2 tag on GitHub instead of from the artifact that will ship, and nobody has run the new
engine on a device yet. The repro is one run: switch source while a ScreenScraper page is loading,
and re-submit a Change Match search during the every-platform wait. That same run should confirm
Coil image loading and Steam requests still behave on OkHttp 5.

**Rejected:** wrapping our own `cancel()` calls in try/catch. It covers the call sites this feature
owns, not the ViewModel's scope being cleared on close, and it leaves the half-closed connection
behind for the next canceller to trip over.

## Studio layout rework: target mockup (2026-09-10)

**Target:** [`docs/mockups/artwork_studio_layout.html`](../mockups/artwork_studio_layout.html), with flat
tabs (user decision, 2026-09-10). It replaces `artwork_image_mockup.png` as the layout target for the
Studio's main screen. The PNG stays the reference for what this layout does not place yet: Phase 5's
saved-screenshots panel, per-tile checkboxes and apply bar.

### Problem, as measured

AYN Thor main screen, 2026-09-10: 1920 × 1080 px at 369 dpi, font scale 1.0, which is **833 × 468 dp**.

- **The grid gets about 87 dp of 468: one row.** `STUDIO_GRID_ROWS = 5`
  (`ArtworkStudioViewModel.kt:213`), so a page holds 20 results but 4 are visible, and D-pad down walks
  the cursor off screen. That breaks AD-5.
- **The tabs don't fit.** The `LazyRow` of 12 sp pills (`ArtworkStudioScreen.kt:209-237`) scrolls the
  selected ICON0 out of view and clips ICON1 to "1".
- **Three bands repeat or float.** The breadcrumb subtitle (`:153-163`) repeats the tab and source; the
  SEARCH row (`:168-207`) and the tab caption (`:238-243`) each take a band of their own.
- **The Current panel is as big as the grid.** A fixed 230 dp column with a 150 dp box (`:248-257`). It
  also holds the PREV/NEXT pills (`:297-329`), which the prompt bar clips, and which page with a
  hardcoded `20` (`:298-299`) rather than `PAGE_SIZE`.
- **Status and controls crowd the sources.** The page range wraps beside them (`:386-394`), the ☐ NSFW
  checkbox (`:374-385`) duplicates START and the SteamGridDB menu entry, and the prompt bar lists seven
  prompts (`:607-630`).

### Decisions

**AD-15. The Thor's 833 × 468 dp is the reference canvas.** The mockup draws every size in dp at that
canvas; when a size in this section is quoted, it is dp at that canvas.

**AD-16. Chrome is fixed in dp and type never scales with the screen.** Every band except the grid has
a fixed height: header 36, tabs 28, sources 24, match 22, page line 16, prompts 22. A larger or longer
screen gives all its extra width and height to the grid as more columns and rows, never as bigger
tiles or bigger text.

**AD-17. A page is one measured gridful (refines AD-5).** Capacity comes from the grid slot's measured
size and the active tab's tile class, and is recomputed when either changes. `STUDIO_GRID_COLUMNS`,
`STUDIO_GRID_ROWS` and `PAGE_SIZE` go. After a capacity change the focused result stays focused, on
whichever page now contains it.

**AD-18. Flat tabs.** All eleven categories stay one press apart, with LB/RB glyphs at both ends of the
row (user decision).

**AD-19. The rail is 150 dp wide below a 1000 dp-wide window and 200 dp at or above it.**

### Rejected

- **Grouped tabs** (Icons / Box / Scene / Media): larger targets and a calmer row, but one more level
  for LB/RB to walk. The user chose flat.
- **Scaling chrome and type with the screen.** A tablet would show the Thor layout enlarged, with no
  more results per page.
- **A grid that scrolls inside a page.** Two navigation models on one screen, the reason AD-5 exists.
- **Keeping 4×5 and shrinking tiles to fit.** 20 tiles in 259 dp are about 52 dp tall on the Thor, too
  small to judge artwork.

### Grid capacity rules

Inputs: the grid slot's width W and height H in dp, and the active tab's tile class. Gap g = 8 dp.

| Tile class | Tabs | Aspect (w : h) | Minimum tile width |
|---|---|---|---|
| Landscape | ICON0, ICON1, HERO, BACKGROUND, SCREENSHOT, VIDEO | 1.5 | 112 dp |
| Portrait | BOX ART, 3D BOX, MANUAL | 0.7 | 80 dp |
| Square | PHYS. MEDIA | 1.0 | 96 dp |
| Wide | LOGO | 2.0 | 140 dp |

- columns = clamp(⌊(W + g) ÷ (minimum width + g)⌋, 3, 8)
- tile width = (W − g × (columns − 1)) ÷ columns, and tile height = tile width ÷ aspect
- rows = clamp(⌊(H + g) ÷ (tile height + g)⌋, 1, 6)

Worked examples, which L.1's unit tests pin by slot size. Slot sizes assume the band heights above,
32 dp of side padding and a 16 dp rail gap (209 dp of vertical chrome and spacing); on a device the
slot is measured, not assumed.

| Screen (dp) | Slot W × H | Landscape | Portrait |
|---|---|---|---|
| AYN Thor, 833 × 468 (reference) | 635 × 259 | 5 × 3 = 15 | 7 × 2 = 14 |
| 16:9 small handheld, 768 × 432 | 570 × 223 | 4 × 2 = 8 | 6 × 1 = 6 |
| 20:9 phone in landscape, 915 × 412 | 717 × 203 | 6 × 2 = 12 | 8 × 1 = 8 |
| TV, 960 × 540 | 762 × 331 | 6 × 3 = 18 | 8 × 2 = 16 |
| 4:3 tablet, 1024 × 768 (200 dp rail) | 776 × 559 | 6 × 6 = 36 | 8 × 4 = 32 |
| 16:10 tablet, 1280 × 800 (200 dp rail) | 1032 × 591 | 8 × 6 = 48 | 8 × 3 = 24 |

The Thor's landscape row count is ⌊3.02⌋: compare with a small epsilon so floating point cannot drop
it to 2.

### Execution tasks

All six touch `ArtworkStudioScreen.kt` or its ViewModel, so they land one at a time in index order.
Line references are against the tree after task 2.4 (uncommitted at the time of writing); re-verify
before editing.

**L.1: Measured grid capacity in the ViewModel**
- **Objective:** page size and D-pad grid movement come from a measured capacity instead of the fixed
  4×5 constants.
- **Scope:** a pure capacity function, the per-tab tile class, ViewModel state and re-paging, tests.
  No screen changes.
- **Existing code:** `STUDIO_GRID_COLUMNS` / `STUDIO_GRID_ROWS` / `PAGE_SIZE`
  (`ArtworkStudioViewModel.kt:212-215`), read by `skeletonCount` (`:149`), `showPage` → `StudioPage.of`
  (`:475`), `goToPage` (`:1033`) and D-pad up/down (`:1568-1574`); `StudioPage` in `StudioSearch.kt`;
  `STUDIO_TABS` (`:239-251`); the constants in `ArtworkStudioViewModelTest.kt:269` and `:364`.
- **Requirements:**
  - `StudioGridCapacity.of(widthDp, heightDp, tileClass)` implements the rules above in pure Kotlin.
  - Each `StudioTab` carries its tile class.
  - ViewModel state `gridColumns` and `gridRows` starts at 4 × 5, so behaviour is unchanged until the
    screen reports a size.
  - `onGridMeasured(widthDp, heightDp)` recomputes for the active tab; a tab change recomputes from
    the last measured size.
  - After a capacity change the focused result (page × old page size + `gridIndex`) lands on the page
    that contains it, still focused.
  - `skeletonCount`, `showPage`, `goToPage` and D-pad up/down read the state.
- **Do not change:** request keys, the result cache, the generation guard, providers,
  `ArtworkStudioScreen.kt`.
- **Expected files:** `ArtworkStudioViewModel.kt`; new `StudioGridCapacity.kt` (feature-xmb
  `ui/detail`); new `StudioGridCapacityTest.kt`; `ArtworkStudioViewModelTest.kt`. Two test files,
  because the capacity table is pure and deserves its own.
- **Acceptance:** every worked example passes by slot size; a capacity change keeps the focused result
  focused; D-pad up/down moves by the measured column count; the existing Studio tests pass.
- **Dependencies:** none.
- **Verification:** `:feature:feature-xmb:testDebugUnitTest` for the Studio tests.
- **Stop:** when acceptance is met. The screen still draws four columns; that is L.2.

**L.2: Render exactly one measured page**
- **Objective:** the grid shows one measured page with no scrolling, and reports its size.
- **Existing code:** both `LazyVerticalGrid`s use `GridCells.Fixed(STUDIO_GRID_COLUMNS)`
  (`ArtworkStudioScreen.kt:489` skeletons, `:538` results); the PREV/NEXT pills hardcode `20`
  (`:298-299`).
- **Requirements:** measure the grid slot (for example `BoxWithConstraints`) and call `onGridMeasured`
  only when its size changes; lay out `gridColumns` × `gridRows` tiles at the computed tile height so
  they fill the slot without scrolling; the skeleton count matches; the pills' `20` becomes the
  ViewModel's page size (the pills themselves move in L.5).
- **Do not change:** any other band.
- **Expected files:** `ArtworkStudioScreen.kt`; `ArtworkStudioViewModel.kt` only to expose the page
  size.
- **Acceptance:** on the Thor every tile of a page is visible and the D-pad cursor never leaves the
  screen; resizing (split screen, or an emulator rotation) re-pages without losing focus.
- **Dependencies:** L.1.
- **Verification:** build; manual on the Thor and one emulator at another size.

**L.3: Title line and flat tabs**
- **Objective:** the header and search share one 36 dp line, and all eleven tabs fit as compact chips.
- **Existing code:** `DetailBreadcrumb` (`DetailComponents.kt:54`) is shared with other detail
  screens, pads 16 dp vertically and has no trailing slot; the Studio calls it at
  `ArtworkStudioScreen.kt:153-163`; the search row is `:168-207`; the tab `LazyRow` is `:209-237`;
  `ControllerPrompt(action, label)` (`core-ui` `ControllerPrompt.kt:61`) draws binding-aware glyphs.
- **Requirements:**
  - One row: back arrow, game title, "Artwork Studio · <platform>", then the query field on the right
    (about 300 dp at most). Tapping it or pressing X opens search; "Reset" still appears while
    `queryIsCustom`.
  - The zone trail and the SEARCH label are removed.
  - Tabs become 24 dp chips (about 10.5 sp, 8 dp horizontal padding, 4 dp gaps) between LB and RB
    glyphs drawn through `ControllerPrompt`, so they follow the user's controller.
  - `LazyRow` and scroll-to-selected stay, so a narrower screen still keeps the selected tab visible.
- **Do not change:** `DetailBreadcrumb` for its other callers. Build a Studio-local header row, or add
  only defaulted parameters that leave every other caller identical. Tab order and `STUDIO_TABS`.
- **Expected files:** `ArtworkStudioScreen.kt`; `DetailComponents.kt` only on the defaulted-parameter
  route.
- **Acceptance:** at 833 dp wide all eleven tabs are visible with ICON0 selected; the header band is
  36 dp; the back arrow still acts like B.
- **Dependencies:** none (lands after L.2 because it edits the same file).
- **Verification:** build; manual on the Thor against the mockup.

**L.4: Current-artwork rail**
- **Objective:** the Current panel becomes a narrow rail.
- **Existing code:** `ArtworkStudioScreen.kt:248-296` (230 dp column, 150 dp box, "Ⓨ · OPTIONS"
  pill); tab caption `:238-243`; message text `:331-336`.
- **Requirements:** rail width per AD-19; the kind label with the tab's `contract` caption under it
  (moved from under the tabs, whose line is deleted); a thumbnail at the tab's tile aspect instead of a
  fixed 150 dp box, keeping `key(previewVersion)` and the MANUAL / VIDEO / ICON1 text states; a Y hint
  row ("Crop, restore, clear") that calls `openActions` on tap; the message stays at the rail's
  bottom. PREV/NEXT stay put until L.5.
- **Do not change:** the actions menu, the preview reload.
- **Expected files:** `ArtworkStudioScreen.kt`.
- **Acceptance:** the rail matches the mockup at 833 × 468 with nothing clipped.
- **Dependencies:** L.3.
- **Verification:** build; manual on the Thor.

**L.5: Sources row, match line, page line and prompt bar**
- **Objective:** finish the mockup's lower half.
- **Existing code:** source row, NSFW checkbox and range text `ArtworkStudioScreen.kt:345-395`; match
  row `:397-479`; PREV/NEXT pills `:297-329`; prompt bar `:601-635`.
- **Requirements:**
  - Source chips are 24 dp.
  - The ☐ NSFW checkbox becomes a "START · Mature off / on" badge, shown only while SteamGridDB is the
    source and still tappable.
  - The match row becomes one 22 dp line with the same content and buttons.
  - A 16 dp page line sits under the grid: "1–15 of 50" on the left, and LB ‹ Page x / y › RB on the
    right with arrows that call `previousPage` / `nextPage`.
  - The rail's PREV/NEXT pills and the range text beside the sources are deleted.
  - The prompt bar drops "prev page", "next page" and "mature"; the per-zone select/back prompts,
    search and options stay.
- **Do not change:** `sourceBadge`, the match row's visibility rules (task 2.4's menu entries mirror
  them), the per-zone structure of the prompt bar (C17 task 2.4 replaces it).
- **Expected files:** `ArtworkStudioScreen.kt`.
- **Acceptance:** side by side with the mockup at 833 × 468, band heights within ±2 dp; touch paging
  still works.
- **Dependencies:** L.2, L.4.
- **Verification:** build; manual on the Thor.

**L.6: Verify across screen sizes**
- **Objective:** show the layout scales per AD-16 and AD-17.
- **Scope:** verification, plus fixes limited to clipping it finds.
- **Requirements:** screenshots on the Thor and on at least two of the worked-example sizes, a 20:9
  phone in landscape and a 16:10 tablet at minimum, visiting one tab of each tile class. Reported
  capacity matches the table within one row or column (a device's measured chrome can differ from the
  assumed 209 dp); no text clipped; the D-pad cursor never leaves the page. feature-xmb has no Compose
  UI tests today, so a Robolectric screen test is optional: if composing `ArtworkStudioScreen` needs
  more than passing its `viewModel` parameter, stop and report rather than building a harness.
- **Expected files:** screenshots referenced from this plan; small fixes in `ArtworkStudioScreen.kt`.
- **Dependencies:** L.5.

Every task: **if blocked** (missing architecture, unexpected coupling, a needed out-of-scope change),
stop and report what was attempted, what blocked it, which file caused it and what decision is needed
(`PLANNING_WORKFLOW.md` §4).

### Landed: L.1–L.4 (2026-09-10)

`L.1` and `L.2` are part of `4f162ed`; `L.3` and `L.4` are uncommitted at the time of writing.
Decisions taken while landing the last two:

- **The header is Studio-local, not `DetailBreadcrumb`.** The shared breadcrumb pads 16 dp vertically
  and has no trailing slot, and adding one would have changed a component four other detail screens
  use. The Studio's row carries the query field itself, and the trail it used to print
  ("Artwork Studio › category › source") is exactly what the flat tabs replace.
- **The platform in the subtitle is `platformId.uppercase()`, not the platform row's `name`.** It
  matches the mockup's "Artwork Studio · PSP", and it adds no dependency to the ViewModel —
  `platformDao` is not one of its constructor arguments today.
- **The LB/RB glyphs are `ControllerPrompt`s for `PREV_CATEGORY`/`NEXT_CATEGORY`,** so they follow
  the user's controller exactly as the footer prompts do, and a chip row that overflows still
  scrolls to the selected chip.
- **The rail thumbnail uses the tab's `StudioTileClass.aspect`, not the mockup's 144:80.** 144:80 is
  ICON0's own crop target — that tab's data — while the rail previews in the shape the grid judges
  the same art in, which is what this task asked for. Cost: on ICON0 the rail thumbnail is a little
  wider than the drawing shows.
- **The Y hint is unconditional and replaced the "Ⓨ · OPTIONS" pill,** which only appeared once a
  slot had artwork. The mockup draws the hint beside "No artwork set", it is the rail's half of the
  footer's always-present `options` prompt, and `openActions()` still decides for itself whether
  anything can open.
- **`railWidth` reads `LocalConfiguration.current.screenWidthDp`, i.e. the window,** which is what
  AD-19 is stated against and what the worked examples assume. Deriving it from the rail's parent
  would have measured the window minus the screen's 26 dp of side padding, moving the threshold.

Still open from this group: `L.5` and `L.6`, and no part of `L.2`–`L.4` has yet been seen on a
screen.

### Landed: L.5 (2026-09-11)

`L.3` and `L.4` were committed in `a9e0d28`; `L.5` is uncommitted at the time of writing. It changes
`ArtworkStudioScreen.kt` only. Decisions taken while landing it:

- **The match line and the page line keep their bands when empty.** The match line is still drawn
  only while a source has a match provider (the visibility rule 2.4's menu entries mirror), and the
  page line's text only once there are results. Their 22 dp and 16 dp stay reserved either way. If
  they collapsed, switching to Local File or finishing a load would resize the grid slot. That
  would fire `onGridMeasured` and re-page results that had just been measured. This is AD-16's
  "every band except the grid has a fixed height", applied to the bands that come and go.
- **Source chips share the tab chips' style** (24 dp, 10.5 sp, 8 dp padding, 4 dp gaps), as the
  mockup draws them with one `.chip` class. The row scrolls horizontally rather than clipping, so a
  narrow screen with "· n/a" badges on every image provider still reaches Local File.
- **The mature badge is a `ControllerPrompt` for `HOME`**, so it shows whichever button the user's
  bindings put on START. Known edge: `ControllerPromptGlyphs` draws nothing when an action has no
  icon, so with START unbound the badge disappears. The SteamGridDB menu entry still toggles the
  filter.
- **The match line's CHANGE MATCH lost its filled background.** The mockup draws it as bold text
  beside a muted FORGET. Disabled still reads as 35 % white.
- **The pager hides on a single page** and keeps the range text. Its arrows use `hasPreviousPage` /
  `hasNextPage` instead of the rail pills' own `hasMore` arithmetic, so one definition of "is there
  another page" is left.
- **Prompts are down to four per zone** in the grid: select, back, search, options. The per-zone
  `when` stays for C17 task 2.4.

No unit test was added: nothing in L.5 is logic outside Compose, and feature-xmb has no screen
harness (L.6 decides whether to build one). Verification is the build plus the Thor, per the task.

### Device fixes, same session (2026-09-11)

- **Change Match needed two searches for "Tactics Ogre".** Logcat for the Windows install:
  - The Studio's Windows-scoped match found 0 hits (2.4 s).
  - The picker's every-platform seed, "Tactics Ogre: Reborn", found one PS5 hit with no art (8.6 s).
  - "Tactics Ogre" then failed with `SocketTimeoutException` at 15 s and showed "didn't answer".
  - The same search again returned 9 hits in 9.4 s.

  ScreenScraper sends nothing until a search is done, so the client's 15 s read timeout cut off
  a search that was still running. `jeuRecherche` now asks for a 40 s socket timeout through
  `timeout {}`, and `HttpTimeout` is installed with no defaults, so every other request keeps
  15 s. Not unit-tested: feature-artwork has no Ktor mock engine, and adding `ktor-client-mock`
  needs approval. Still slow: the every-platform search is 9–15 s of ScreenScraper's own time on
  a one-request-at-a-time account, and a seed title with a subtitle can still find the wrong
  release first.
- **Update Metadata closed itself when no source had anything** and left its reason in a message
  behind the overlay, which looked like a crash. The overlay now stays open. With no presets it
  says why: no source recognised the game (it points at a ScreenScraper Change Match, since
  presets come from ScreenScraper's saved id and TheGamesDB's title search), or the sources
  didn't answer. Its one button is Close, and Select, Back or a tap dismisses it. Tests are in
  `GameDetailViewModelTest`.

### L.6 in progress, and touch pills pulled forward from 4.3 (2026-09-11)

**Thor, 833 × 468 dp, build installed 00:53 (L.5).** Capacity matches the table for every tile class
checked: ICON0 5 × 3 = 15 ("1–15 of 23"), BOX ART 7 × 2 = 14 ("43–50 of 50", page 4 / 4, cursor on the
last tile and on screen), PHYS. MEDIA 5 × 2 = 10. LOGO drew 4 columns; SteamGridDB had only 6 logos,
so its 3 rows are unconfirmed. The measured slot is ~613 dp wide, not the assumed 635, with the same
result. Injected `adb input` D-pad presses do not drive the Studio reliably (taps and B do); cursor
walks are done on the pad.

Fixed from the screenshots, not yet seen on a build:
- **CHANGE MATCH was clipped** on one game's match line: its 4 dp vertical padding left ~14 dp
  inside the 22 dp band. FORGET and CHANGE MATCH are now full-height boxes padded sideways only,
  like the source chips.
- **The header subtitle touched the query field** ("Artwork Studio · WINDOWS" under a capped
  title left the weighted spacer ~2 dp). The field now has a 12 dp start gap.

Second batch, root causes found in code, also not yet seen on a build:
- **CHANGE MATCH sat ~115 dp short of the edge on "Tactics Ogre" but flush right on "Elliot".** The
  match title was weighted `fill = false` beside a separate `Spacer(weight(1f))`. Row splits free
  width between weighted children by weight, and a `fill = false` child leaves its unused share
  empty rather than handing it to the spacer, so the offset tracked the title's length. The title
  and the Confirmed badge now sit in one `Row(weight(1f))` with no spacer.
- **The rail caption's wide line spacing and the page line's raised glyphs were one cause.**
  `PFPTheme` uses Material3's default typography, so a `Text` with no style inherits `bodyLarge`'s
  24 sp line height. A wrapped 9.5 sp caption was spaced like two paragraphs, and in the 16 dp page
  line a 24 sp text box drew its text below the centred glyphs. Those texts set `lineHeight = 12.sp`.
  The caption still wraps on BOX ART / 3D BOX / PHYS. MEDIA at the 150 dp rail; that is expected.

**Seen on the Thor with both batches (2026-09-11, Tactics Ogre: Reborn):**
- CHANGE MATCH is flush right with the title short, and the query field has its gap.
- ICON0 on ScreenScraper is 5 × 3 ("1–13 of 13").
- BOX ART's caption wraps onto two tight lines.
- SteamGridDB BOX ART in touch mode is 7 × 2 ("1–14 of 50", Page 1 / 4), so the 40 dp page line
  cost no row. "‹ Prev" is dimmed on the first page, the pills are unclipped, the rail shows
  "⋯ Options", and the Mature badge is shown.
- An injected `keyevent` RB changes tabs, but an injected pad key does not return the Studio to
  controller mode. The controller-mode page line (glyph alignment) is checked on the pad.

**20:9 phone, `wm size 864x1920` + `wm density 336` = 914 × 411 dp (same build):**
- The measured grid slot is about 694 × 220 dp, not the table's 717 × 203. Side chrome is wider
  than assumed, as on the Thor, and vertical chrome is smaller.

| Tab (source) | Table | Measured | Note |
|---|---|---|---|
| ICON0 (ScreenScraper) | 6 × 2 = 12 | 5 × 2 = 10 ("1–10 of 13", Page 1 / 2) | ⌊702 ÷ 120⌋ = 5 at 694 dp |
| BOX ART (SteamGridDB, touch) | 8 × 1 = 8 | 7 × 1 = 7 ("1–7 of 50", Page 1 / 8) | ⌊702 ÷ 88⌋ = 7 |
| PHYS. MEDIA (ScreenScraper) | n/a | 6 columns (108 dp tiles, 2 results) | rows unconfirmed |
| LOGO (ScreenScraper) | n/a | 4 columns (168 dp tiles, 1 result) | rows unconfirmed |

- Every miss is one column, and it follows from the measured slot width. That is within L.6's
  tolerance.
- All eleven tabs, the sources, the match line and the pager fit. The controller-mode page line's
  LB/RB glyphs now line up with its text.
- **Clipping found and fixed (not yet on a build):** on BOX ART in touch mode, the rail's
  "⋯ Options" pill was cut off at the bottom. The thumbnail took its full height from the rail's
  width at 0.7 aspect (~214 dp), and nothing let it yield. The rail is now an inner column whose
  only weighted child is the thumbnail (`weight(1f, fill = false)` then `aspectRatio`, with no
  `fillMaxWidth`), so the thumbnail narrows to the height left. Options sits directly under it, and
  the message sits under the inner column at the rail's bottom. A weighted spacer beside the
  thumbnail would split the free height, the same `fill = false` trap as the match line.

**16:10 tablet, `wm size 1080x1728` + `wm density 216` = 1280 × 800 dp (same build, reset
afterwards):**
- The rail is 200 dp (AD-19). The measured grid slot is about 1010 dp wide, against the table's 1032.

| Tab (source) | Table | Measured | Note |
|---|---|---|---|
| ICON0 (ScreenScraper) | 8 × 6 = 48 | 8 columns (118 dp tiles, 13 results) | 6 rows by arithmetic, not seen |
| BOX ART (SteamGridDB, touch) | 8 × 3 = 24 | 8 × 3 = 24 ("1–24 of 50", Page 1 / 3) | exact |
| PHYS. MEDIA (ScreenScraper) | n/a | 8 columns (2 results) | rows unconfirmed |
| LOGO (ScreenScraper) | n/a | 6 columns (162 dp tiles, 1 result) | ⌊1018 ÷ 148⌋ = 6 |

- Nothing is clipped. The touch pills and "⋯ Options" fit.
- Under a `wm size` override, `input tap` takes override coordinates. Two injected LB presses after
  tapping a source chip did not change tabs (not investigated), so ICON0 on SteamGridDB was not
  reached at this size.

**L.6 status.** Capacity is within one column of the table at all three sizes, and the only
clipping found is fixed. **Closed by the user on 2026-09-11** with the rail fix checked on a build.
The two points that were never seen on a screen are accepted rather than verified, and no
screenshots were added to the repo. The list as it stood:
- ~~The rail fix is not yet on a build.~~ Checked by the user on a build, 2026-09-11: the rail's
  "⋯ Options" control is no longer cut off.
- ICON0's 6 rows at 1280 × 800 dp are arithmetic, not seen.
- The D-pad walk was done only on the Thor, because injected D-pad keys do not drive the Studio.
- The screenshots are in the session scratchpad, not the repo. Committing PNGs is the user's call.

**Touch pills (user request, part of 4.3 landed ahead of C17).** `GameDetailScreen` now passes
`showTouchControls` and `onTouchInput` into the Studio, which reports touches with the same
non-consuming detector, since it replaces Game Detail while open. In touch mode the page line's
‹ › become `XmbHeaderPill` "‹ Prev" / "Next ›" around "Page x / y" (dimmed, and inert, at either
end), the manual preview's Prev/Next do the same, and the rail's Y hint becomes an "⋯ Options"
pill. The page line is 40 dp in touch mode instead of 16 dp, so the pills are not clipped; the
mode flip therefore re-measures the grid once, and AD-17 keeps the focused result. On the Thor
that costs no capacity for any tile class (rows stay 3 / 2 / 2 / 3). The rest of 4.3, touch-sized
tabs, source chips and tiles, still waits on C17. No unit test: presentation only, and feature-xmb
has no screen harness.

The three controls live in `StudioTouchControls.kt` as stateless composables (`StudioPageLine`,
`StudioOptionsControl`, `StudioManualPager`) so they can be previewed; the screen takes a Hilt
ViewModel and cannot be. Each has a `@CombinedPreviews` preview showing touch above controller mode
at the Thor's measured widths (613 dp grid slot, 150 dp rail). feature-xmb gained
`debugImplementation(libs.compose.ui.tooling)`, as feature-settings and feature-appbar already have,
so Android Studio can render them.

**Full-screen previews.** `ArtworkStudioScreen` is now a thin wrapper (ViewModel, load, close,
pending pad action, file picker) around a stateless `ArtworkStudioContent(state, actions, …)`, the
split `PfpPreviewWrapper` recommends. `actions` is a new `ArtworkStudioActions` interface of the 37
functions the body calls; `ArtworkStudioViewModel` implements it, so the compiler keeps the two in
step, and nothing about the ViewModel's behaviour changed. `ArtworkStudioPreview.kt` renders the
content with sample state and a no-op implementation: Thor controller and touch, a 20:9 phone and a
16:10 tablet. A static preview never measures its grid slot, so each pages its sample with
`StudioGridCapacity` at the worked-example slot sizes; the counts are approximate, not a substitute
for L.6's device check. `:feature:feature-xmb:testDebugUnitTest` is green with the ViewModel
implementing the interface and with both fix batches applied.

## Matching latency (2026-09-11)

**User request, before Merge 4:** identifying a game takes too long, and every second of it costs
users. Read against the tree after L.6's fixes (uncommitted at the time of writing); re-verify line
numbers before editing.

### Problem, as measured and read

Device numbers are in "ScreenScraper Change Match dead end" above. The account has one request slot
(`maxthreads` 1). A platform-scoped `jeuRecherche` takes 3–4 s, and an every-platform one 9–11 s of
ScreenScraper's own time. Most of the wait users saw was queueing, not server time. Seven causes in
the tree:

1. **A superseded resolution is never cancelled.** `resolveMatch` launches without keeping the job
   (`ArtworkStudioViewModel.kt:896`), and `invalidateMatch` only bumps `matchGeneration`
   (`:859-862`). A superseded ScreenScraper search keeps the one slot while the user's next action
   queues. The Change Match picker had the same bug and was fixed with `changeMatchJob` (`:870`);
   the matcher path was not.
2. **Every tab switch forgets the match and browses without it.** `load`, `selectTab`,
   `selectSource` and `submitSearch` call `resolveMatch()` and then `loadResults()` (`:385-386`,
   `:718-719`, `:738-741`, `:1139-1140`). `resolveMatch` sets `match = null` at once (`:895`), so
   `loadResults` keys the browse with no match (`:457`), although a match never depends on the tab.
   - An unconfirmed ScreenScraper title match flashes "No ScreenScraper match", then browses again.
   - TheGamesDB and IGDB start a title browse (`:633`, `:672`) that is cancelled or repeated as a
     by-id browse once the match lands: three requests on a first visit where two would do.
   - Only a match confirmed this session escapes (`:889`).
3. **SteamGridDB searches twice per tab.** `sgdbResults` calls `steamGridDb.searchGame(query)`
   itself (`:594`), outside `titleSearches` and ignoring the resolved match. Each tab is its own
   result-cache key, so every tab visited sends another autocomplete request.
4. **ScreenScraper spends its slot on lookups that cannot win.**
   - Windows: Tier 3 always runs the `systemeid` search (`GameMatcher.kt:161` →
     `ProviderMatchEvidence.kt:135`). On device it has found nothing for any title, and it costs
     3–4 s on every open because ScreenScraper is the first source. The picker already skips it
     (`searchesEveryPlatformFirst`, `ProviderMatchEvidence.kt:162`).
   - A ROM game with `rom_crc32` but no `ss_id`: Tier 2 asks `jeuInfos` by checksum
     (`ProviderMatchEvidence.kt:45`) and discards its media. The browse's `SsMediaCatalog.mediasFor`
     then sends the same lookup with name, size and checksum (`SsMediaCatalog.kt:72-73`).
   - A ROM game with no checksum: Tier 3's title search can take the slot ahead of the catalog's ROM
     lookup, which is the stronger evidence.
5. **The gate spaces requests from the end of the last one.** `rateLimited`
   (`ScreenScraperApi.kt:465-473`) waits `MIN_REQUEST_INTERVAL_MS` (1.1 s, `:562`) after the previous
   request finished, even after a 10 s search. Holding the lock across the request is right with
   `maxthreads` 1. The gap only guards a per-minute limit, and `SsUser` (`:80-85`) parses none.
6. **Every open starts cold.** `titleSearches.clear()` runs on every `load` (`:354`). The reason
   is sound: TheGamesDB, IGDB and SteamGridDB report a failure as an empty list, and remembering
   that across opens would keep a game unmatched.
7. **The every-platform search's 9–11 s is ScreenScraper's.** The client can only avoid repeating it
   or queueing behind it.

### Decisions (user-approved 2026-09-11)

**AD-20. One owner per browse.** One cancellable job resolves the active provider's match, then
browses with it. Matches are remembered per open. A tab switch never resolves again, and leaving a
source cancels its resolution.

**AD-21. Title searches persist across opens.** Answers with hits are kept for 7 days (user-approved).
ScreenScraper's genuine empty answers are kept for 1 day, which is a drafting detail to confirm:
ScreenScraper throws on failure, so its empty answer is real, but a game it adds later should not
stay hidden for a week. Other providers' empty answers are never persisted. Failures never are.

**AD-22. Background resolution on open for SteamGridDB and IGDB only.** TheGamesDB has a monthly
request allowance on free keys, and ScreenScraper has one slot, so both resolve only when visited.

**AD-23. The matcher never title-searches ScreenScraper on a platform without ROM files.** The
matcher still never widens across platforms; Change Match stays the way to pick a console release.

### Rejected

- **More than one ScreenScraper request at a time.** The account allows one.
- **Dropping the 1.1 s gap before the per-minute limit is known.** Risks throttling the account.
- **Automatic cross-platform matching, or fuzzy Tier 4 guesses to match sooner.** Both contradict
  decisions above: a cross-platform match is a guess, and Tier 4 is deferred.
- **Starting the every-platform search in the background for Windows games.** It holds the slot for
  10 s and spends quota for users who never open Change Match. AD-21 makes the second time instant.
- **Seeding Change Match with SteamGridDB's or IGDB's canonical title.** On device "Tactics Ogre:
  Reborn" found only the PS5 release with no art, while "Tactics Ogre" found the Switch one.

### Execution tasks

Land in index order, one at a time: M.1, M.2, M.3b and M.6 all edit `ArtworkStudioViewModel.kt`.
Each task drafts its tests before the implementation. The user runs every Gradle command.

**M.0: Timing logs and the account's limits**
- **Objective:** measure queueing against provider time before changing anything, and read the
  account's real rate limits for M.4.
- **Requirements:**
  - `rateLimited` takes a label and logs, at debug level, the endpoint, the milliseconds spent
    waiting for the gate, and the milliseconds the request took. Every caller passes one
    (`jeuInfos`, `jeuRecherche`, `ssuserInfos`).
  - The Studio logs each resolution's provider, tier (or none, or failed) and elapsed milliseconds,
    and each Change Match search's elapsed milliseconds and whether it was every-platform.
  - Once per process, log the numeric fields of a response's `ssuser` block. Never log `id`: it is
    the account name. The per-minute field's exact name is unverified; this run reads it.
- **Do not change:** any behaviour.
- **Expected files:** `ScreenScraperApi.kt`, `ArtworkStudioViewModel.kt`. No test (logging only);
  `ScreenScraperApiTest` and the Studio tests must stay green.
- **Verification (device):** with logcat open, visit Tactics Ogre: Reborn (Windows) and one ROM
  game with no `ss_id`. Walk ICON0 → BOX ART → LOGO, switch ScreenScraper → SteamGridDB → IGDB → back,
  and run one Change Match search. Record the numbers here as the baseline.
- **Stop:** when the baseline and the `ssuser` limits are recorded.

**M.0 logs written (2026-09-11, uncommitted, not yet built).** Logcat lines to read on the walk, all at
debug level:
- `ScreenScraper gate <endpoint>: waited W ms (queued Q, spaced S), request R ms`. Q is time behind
  another request for the lock, S the 1.1 s gap, R ScreenScraper's own time. Logged after the request
  ends, failed or not.
- `Studio match <provider>: <tier | none | failed> in N ms`, with `(superseded)` when a newer
  resolution had already replaced it. The count of these lines per open is M.1's baseline.
- `Studio Change Match <provider>: <n candidates | failed> in N ms, every platform: true|false`.
- `ScreenScraper account limits: {…}`, once per process, from the first `jeuInfos` or `jeuRecherche`
  body with an `ssuser` block. Every numeric field is logged, since the per-minute field's name is
  unverified. `numid` is dropped as well as `id`: the account's number identifies it as surely as its
  name. That rule is pure (`ScreenScraperApi.accountLimits`) and has two cases in
  `ScreenScraperApiTest`, beyond the task's "no test", because it is a privacy guarantee.
- Not logged: `ssuserInfos` (Settings' credential check tests an account that may not be the stored one).

**M.0 baseline (Thor, 2026-09-11 08:43–08:53, unit tests green).** Read from logcat after the user's
walk. No `(superseded)` line appeared, and nothing queued except where noted.

- **Account limits:** `maxthreads` 1, `maxrequestspermin` **3072**, `maxrequestsperday` 20000
  (308 used), `maxrequestskoperday` 2000, `maxdownloadspeed` 128. The per-minute field is
  `maxrequestspermin`. M.4's formula gives `max(1 100, ⌈60 000 ÷ 3072⌉ = 20)` = 1 100 ms, so start-to-start
  spacing is the whole gain: the gap after a request that took 1 s drops from 1.1 s to 0.1 s. The limit
  itself would allow a much lower floor; lowering it below 1.1 s is a new decision, not part of M.4.
- **Tactics Ogre: Reborn (Windows), ScreenScraper first:** `jeuRecherche` on system 138, 3 526 ms of
  server time, 0 hits; resolution `none` in 3 804 ms. Confirms cause 4 and M.3a: every Windows open
  pays ~3.8 s for a search that finds nothing.
- **Other providers, first visit:** SteamGridDB `EXACT_TITLE` 532 ms, TheGamesDB `none` 891 ms, IGDB
  `EXACT_TITLE` 943 ms. Every later visit in the same open: 0 ms (the per-open `titleSearches` memo).
  TheGamesDB's Change Match then listed 6 candidates in 0 ms from that memo, so its `none` was ambiguity.
- **Change Match, every platform:** "Tactics Ogre: Reborn" 10 378 ms of server time, 1 hit; "Tactics
  Ogre" 9 651 ms, 9 hits. Queued 0 both times. The earlier cancellation work removed the queueing, so
  the wait left is ScreenScraper's own (cause 7). Confirming 425726 cost one `jeuInfos` of 797 ms.
- **ROM games with no `ss_id` (GBA, `Lufia - The Ruins of Lore (USA).gba` and
  `Pokemon - Emerald Crest.gba`):** Tier 3's platform search ran first (1 040 / 931 ms, 0 hits), then the
  browse's catalog `jeuInfos` waited 2 023 / 1 895 ms (queued 924 / 795 behind the search, spaced
  1 099 / 1 100) and identified the game in 1 034 / 1 150 ms. About 3.3 s to identify, where the
  `jeuInfos` alone is ~1.1 s. Confirms cause 4's third bullet and M.3b's order (ROM lookup first). No
  Tier 2 checksum `jeuInfos` appeared: neither game had a stored `rom_crc32`, so the duplicate
  checksum lookup was not exercised on this walk.
- **Tab walks:** one resolution per `selectTab`, including a burst of 13 at ~50 ms intervals in under a
  second (most likely a held bumper, inferred from the cadence). With a saved id they are Tier 1 and free (0 ms, no request), so on device the cost of
  cause 2 is the extra browse and the row flash, not requests.
- **Observed, not in scope:** ScreenScraper identified `Pokemon - Emerald Crest.gba`, a ROM hack, as
  "Pokémon Emerald Version" (84406) from name, size and checksum, and the catalog saved that id, so it
  now resolves at Tier 1. That is ScreenScraper's answer, and Forget Match / Change Match correct it.

**M.1: One owner job for resolve, then browse**
- **Objective:** causes 1 and 2.
- **Existing code:** `resolveMatch` (`:879-909`), `invalidateMatch` (`:859-862`), `loadResults`
  (`:450-497`), its callers above, `confirmMatch` (`:1063-1086`), `forgetMatch` (`:1094-1104`),
  `refreshSsIdentityAfterBrowse` (`:766-772`).
- **Requirements:**
  - A per-open match memo keyed on provider plus the query, normalized for case and spacing only,
    as `CachingMatchEvidence.SearchKey` does (punctuation changes what a provider returns).
    - A resolution that throws is not stored, and still sets `matchFailed`.
    - Cleared in `load`, next to `titleSearches.clear()`.
    - Cleared for one provider whenever that provider's saved id changes: `confirmMatch`,
      `forgetMatch`, and `refreshSsIdentityAfterBrowse` when the id moved.
  - `loadResults()` is the one entry. It cancels the previous job, then in one job:
    1. When the source has a match provider and no confirmed match for it, read the memo or resolve.
       `matchResolving` is shown, and the grid shows skeletons, never an empty result.
    2. Write `match`.
    3. Build `StudioRequestKey` with it.
    4. Check the cache, fetch, `showPage`.
  - The key and generation checks stay exactly as they are (AD-6).
  - `selectTab` resolves nothing new: the memo answers for the same provider and query.
  - Cancelling the job cancels a running resolution. `CachingMatchEvidence` already hands a shared
    in-flight search to a caller still waiting when the asker is cancelled ("when the caller asking
    is cancelled…"), so the Change Match picker is never stranded.
  - A confirmed match still wins and is never re-derived (`:888-892`).
  - `resolveMatch(force)` and its call sites fold into the above.
- **Do not change:** `GameMatcher`, `CachingMatchEvidence`, the picker's `changeMatchJob`,
  `StudioRequestKey`, `StudioResultCache`, the provider clients.
- **Expected files:** `ArtworkStudioViewModel.kt`, `ArtworkStudioViewModelTest.kt`.
- **Tests (draft first):**
  - `switching tabs keeps the match and never browses without it`: with a unique TheGamesDB title
    match, walk ICON0 → BOX ART → LOGO. `theGamesDb.fetchGameInfo` (by title) is never called,
    `fetchGameInfoById` is called once per tab, and `searchByTitle` once in total.
  - `the grid waits on skeletons, not an empty result, while its match resolves`: with a suspended
    ScreenScraper search, `matchResolving` and `resultsLoading` stay true until it answers.
  - `leaving a source cancels its running resolution`: a suspended ScreenScraper search records its
    cancellation when SteamGridDB is selected.
  - `returning to a source reuses its match without asking again`: ScreenScraper → SteamGridDB →
    ScreenScraper asks ScreenScraper once.
  - `a failed resolution is not remembered, so the next visit asks again`.
  - `a new query resolves again`.
  - `reopening the Studio resolves afresh`.
  - Keep green: `the match is part of the request key, so switching match refetches`, `opening
    Change Match while a match resolves never leaves the row on Matching`, `confirming a match
    mid-resolution clears the spinner and keeps the user's choice`, `re-resolving a match reuses the
    title search, and so does the picker`, `a ScreenScraper browse that identifies the game brings the
    match row along`.
- **Acceptance:** the tests pass. On the Thor a tab walk shows no "No ScreenScraper match" flash,
  and M.0's logs show one resolution per provider per open.
- **Dependencies:** M.0.
- **Verification:** `:feature:feature-xmb:testDebugUnitTest`, then the M.0 device walk.

**M.1 implemented (2026-09-11, uncommitted, not yet built).** Only `ArtworkStudioViewModel.kt` and its
test changed. Decisions taken while landing it:
- **`matchGeneration` and `invalidateMatch` are gone.** The resolution runs inside the browse job, so
  the browse's own `generation` token guards the match row, and `cancelLoad()` (cancel the job, clear
  `matchResolving`) replaces the invalidation in `confirmMatch` and `forgetMatch`.
- **A known match is applied synchronously.** No provider, a confirmed match, or a memo hit writes
  `match` and builds the key before `loadResults` returns, so a cached return to a source shows its
  match and grid with no "Matching…" or "No match" frame. Only a real resolution shows skeletons, and
  `activeKey` is null meanwhile, so paging and re-paging have nothing to act on.
- **The ScreenScraper identity refresh moved into the job.** `refreshSsIdentityAfterBrowse` now only
  reports whether the row's identity moved (and forgets ScreenScraper's memo entries when it did); the
  job resolves again and re-browses only if the key moved. It used to launch a separate resolution,
  which is exactly the unowned work this task removes.
- **`sgdbResults` takes the key's match** instead of reading `_uiState.value.match`, like the TheGamesDB
  and IGDB browses already did.
- **Opening another game cancels the previous game's job.** Otherwise its late resolution could be
  stored in the freshly cleared memo under the new game. A same-game reopen does not cancel, because
  that path does not always start a new browse.
- **`resetSearchToTitle` now re-resolves for the title.** It used to call only `loadResults`, which
  browsed with the custom query's match. Going through the memo fixes that without a change of its own.
- **Resolution cancellation is rethrown, never logged as a failure,** and `ensureActive()` runs before
  anything is memoized, since some provider clients turn a cancellation into an empty answer.
- **Tests.** The seven drafted cases were added. Three of them (`a failed resolution is not
  remembered…`, `a new query resolves again`, `reopening the Studio resolves afresh`) would also pass
  against the old code (by reading it; not run), because `titleSearches` already deduplicated provider calls; they guard the
  memo's rules rather than prove the change. What proves it: `switching tabs keeps the match and never
  browses without it` (the old code browsed TheGamesDB by title on every tab), `the grid waits on
  skeletons…` (the old code browsed ScreenScraper before its match), `leaving a source cancels its
  running resolution`, and `returning to a source reuses its match…`, which asserts the match is on
  screen before the dispatcher runs.

**M.2: SteamGridDB browses by the match**
- **Objective:** cause 3.
- **Existing code:** `sgdbResults` (`:581-621`), its id choice at `:588-595`.
- **Requirements:** choose the SteamGridDB id in this order, keeping today's query rules:
  1. A confirmed SteamGridDB match.
  2. The saved `steam_grid_db_id` while the query is the game's title (`:591`).
  3. The resolved match when its tier is `CONTENT_ID` or `EXACT_TITLE`. Not `SAVED_PROVIDER_ID`:
     Tier 1 ignores the query, and step 2 already keeps that rule.
  4. The first hit of `titleSearches.searchByTitle(STEAMGRIDDB, query, platformId)`: today's first
     autocomplete hit, now cached, so an ambiguous title still browses something.

  Never a direct `steamGridDb.searchGame`.
- **Expected files:** `ArtworkStudioViewModel.kt`, `ArtworkStudioViewModelTest.kt`. Existing SGDB
  browse tests stub `steamGridDb.searchGame` (`ArtworkStudioViewModelTest.kt:100`); move those
  stubs to `matchEvidence.searchByTitle(STEAMGRIDDB, …)`.
- **Tests (draft first):** `SteamGridDB searches once however many tabs are browsed` (ICON0, BOX ART,
  HERO: `searchGame` never, `searchByTitle` once); `an ambiguous SteamGridDB title still browses its
  first hit`; `a typed query browses its own hit, not the saved id`.
- **Dependencies:** M.1.

**M.2 implemented (2026-09-11, uncommitted, not yet built).** `sgdbResults` picks its id in the four
steps above; step 4 is `firstSgdbHit`, through `titleSearches`. The matcher's Tier 3 already asked that
search for the same query and platform, so on an unmatched game the browse reuses it. A failed search
browses nothing, as `searchGame(...).getOrNull()` did. The `ArtworkStudioViewModel` no longer calls
`steamGridDb.searchGame` at all (App Detail still does, for its own icon search). In the test, the
setup's `searchGame` stub became a SteamGridDB `searchByTitle` stub with the same id 77 and title
"Crash", which is not an exact title for the test game, so every existing SteamGridDB browse test
still browses 77 without becoming a match.

**M.3a: No ScreenScraper title search where there are no ROM files**
- **Objective:** cause 4, Windows (AD-23).
- **Requirements:** `ProviderMatchEvidence.searchByTitle` returns an empty list for ScreenScraper
  on `PLATFORMS_WITHOUT_ROMS` without sending a request. The picker is unaffected: it already goes
  straight to `searchScreenScraperOnAnyPlatform` there. The match row says "No ScreenScraper match"
  at once, and Change Match is the way on, as today.
- **Expected files:** `ProviderMatchEvidence.kt`; new `ProviderMatchEvidenceTest.kt` in
  feature-artwork `match/` (`ArtworkStudioViewModelTest` mocks this class, so it cannot see the
  change).
- **Tests (draft first):** `ScreenScraper is never title-searched on a platform without ROM files`
  (a mocked `ScreenScraperApi.searchGames` is never called); `other platforms still search ScreenScraper
  by title`.
- **Dependencies:** M.0.

**M.3a implemented (2026-09-11, uncommitted, not yet built).** `searchByTitle` returns an empty list for
ScreenScraper on `PLATFORMS_WITHOUT_ROMS`, the set `searchesEveryPlatformFirst` already reads, so the
matcher and the picker cannot disagree about which platforms have no ROM files. The only other callers
of `searchByTitle` are the Studio's picker, which does not reach it on Windows, and SteamGridDB's step 4
from M.2. The tests went into the existing `ProviderMatchEvidenceScreenScraperTest` rather than a new
`ProviderMatchEvidenceTest`, since that file already builds the class over a mocked `ScreenScraperApi`.
Its `a failed ScreenScraper search reaches the caller as a failure` case searched on `windows`, which
no longer sends a request, so it now searches on `psp`.

**M.3b: One ScreenScraper identity lookup, not two**
- **Objective:** cause 4, ROM games.
- **Requirements:**
  - On ScreenScraper, for a game with no `ss_id` on a platform with ROM files, M.1's job runs the
    catalog's identity lookup first: `ssMediaCatalog.mediasFor(gameId, null)`, then reload the game
    row. A hit saves `ss_id` (the catalog already does), so the resolution is Tier 1 with no further
    request, and the browse reads `ss_media_cache`.
  - On a miss, resolve with the checksum tier skipped, through a new defaulted parameter
    `GameMatcher.resolve(…, skipRomHash = false)`. The catalog just asked `jeuInfos` with the same
    checksum plus name and size.
  - Skip it only when the catalog could hash (`romPath` or `romUri` set). With neither, the catalog
    sends nothing, and a stored `rom_crc32` is still worth asking.
- **Expected files:** `GameMatcher.kt`, `ArtworkStudioViewModel.kt`, `GameMatcherTest.kt`,
  `ArtworkStudioViewModelTest.kt`. That is two test files, one over the §4 budget, because the new
  parameter belongs to the pure matcher.
- **Tests (draft first):**
  - `GameMatcherTest`: `skipping the ROM checksum tier goes straight to the title search`, `the
    checksum tier still runs by default`.
  - `ArtworkStudioViewModelTest`: `a ROM game's ScreenScraper identity comes from one lookup` (the
    catalog saves 555, `candidateByRomHash` is never called, and the match is Tier 1); `when the
    catalog finds nothing, the title search runs and the checksum is not asked again`.
- **Dependencies:** M.1.

**M.3b implemented (2026-09-11, uncommitted, not yet built).** The four expected files. Decisions taken
while landing it:
- **The lookup runs only when the job has to resolve** (no confirmed or remembered ScreenScraper match),
  the row has no `ss_id`, and it has a `romPath` or `romUri`. That last condition is "the catalog could
  hash", and it also keeps Windows out, so the ViewModel needs no platform list of its own.
- **The browse reuses the lookup's answer** (`SsRomLookup`) when the match is the lookup's own: the id
  it saved, or no match at all. The plan did not ask for this, but without it M.3b would have added a
  request: on a miss, the unmatched browse called `mediasFor(gameId, null)` and sent the same ROM
  `jeuInfos` again. A title match to a different id still browses that id through the catalog.
- **A lookup that throws does not count.** It returns null, so the matcher's checksum tier still runs
  and the browse asks the catalog as before.
- **`the checksum tier still runs by default`** was not added as a new `GameMatcherTest` case:
  `a ROM checksum resolves ScreenScraper when no id is saved` is exactly that test, and a comment beside
  the new skip case points at it.
- **`the grid waits on skeletons, not an empty result, while its match resolves`** had asserted no
  `mediasFor` call during resolution. It now allows exactly the identity lookup (`mediasFor(1L, null)`)
  and still forbids a browse by id.
- **Known leftover, not in this task:** a ROM game ScreenScraper does not know still sends one ROM
  `jeuInfos` per ScreenScraper tab. The memo answers the match, but each tab is a new result key, and
  its browse with no match asks the catalog, which has nothing cached for an unidentified game. Fixing
  it means remembering a ROM miss per open.

**M.4: ScreenScraper spacing, start to start**
- **Objective:** cause 5.
- **Blocked until** M.0 records the account's per-minute limit. If `ssuser` carries none, stop and
  report rather than guess.
- **Requirements:**
  - Record when each request starts, and wait for `interval − (now − lastStart)`.
  - `interval` is `max(1 100, ceil(60 000 ÷ perMinute))` ms, so a generous account is never slower
    than today, and a strict one never exceeds its limit.
  - Keep the `Mutex`, since `maxthreads` is 1.
  - The spacing rule is a pure function, so it is testable without a mock HTTP engine (adding
    `ktor-client-mock` still needs approval).
- **Expected files:** `ScreenScraperApi.kt`, `ScreenScraperApiTest.kt`.
- **Tests (draft first):** `a request that took longer than the interval sends the next one at
  once`, `back-to-back fast requests are spaced by the interval from their starts`, `the interval
  never drops below 1.1 s`.

**M.4 implemented (2026-09-11, uncommitted, not yet built).** Unblocked by M.0's reading
(`maxrequestspermin` 3072). The user left the floor to ToS-safe judgement, so it stays at 1.1 s: about
55 starts a minute against 3072, one request at a time. Decisions taken while landing it:
- **The limit is read at runtime, not hard-coded.** `SsUser` gained `maxrequestspermin`, and every
  `jeuInfos` and `jeuRecherche` response updates it. Until the first response it is unknown, and the
  floor applies. `ssuserInfos` does not update it: Settings' check tests an account that may not be
  the stored one.
- **`lastRequestStartedAt` is set when the request starts**, before it runs, so a request that throws
  or is cancelled mid-flight still spaces the next one. A request cancelled while waiting for its slot
  never started and sets nothing.
- **Two pure functions in the companion** (`requestIntervalMs`, `waitBeforeNextRequest`) carry the rule,
  so it is tested without a mock HTTP engine, as the task required. A limit of zero or less is treated
  as unknown.
- **`parseSearch` was split** into `decodeSearch` and `hitsOf`, so `searchGames` decodes a body once and
  reads both the hits and the account block from it. `parseSearch` still exists for the fixture tests.
- **Test name:** `the interval never drops below 1_1 s`. A JVM method name cannot contain a dot. A fourth
  case, `the per-minute limit is read from the account block`, pins the field name.
- **Expected gain on device:** on the GBA baseline, a ROM lookup queued behind a 1 s title search waited
  1.1 s of spacing. Start-to-start, that is about 0.1 s. After M.3b that pairing is rarer, so the gain
  shows mostly in Change Match searches right after a lookup and in batch scrapes.

**M.5: Title searches kept between opens**
- **Objective:** cause 6 (AD-21).
- **Requirements:**
  - A `TitleSearchStore` interface in feature-artwork `match/`, and a JSON-file implementation
    under `cacheDir/match-searches/`, provided as a Hilt singleton. `ArtworkImportWorker` already
    writes plan files the same way, in `filesDir`; this goes in `cacheDir` because it can be rebuilt.
    A serializable DTO lives inside the store, so `GameMatching.kt` stays free of serialization.
  - `CachingMatchEvidence` takes the store and an injectable clock. It reads memory, then an entry
    in the store still inside its TTL, then the provider.
  - Every answer goes to memory, as today. It goes to the store only when it has hits (7 days) or is
    ScreenScraper's empty answer (1 day). Failures and cancellations never do.
  - `remember(…, scope = "every-platform:…")` is stored under its own scope.
  - `clear()` still clears memory only.
- **Expected files:** `CachingMatchEvidence.kt`, new `TitleSearchStore.kt`, a Hilt binding in
  `ArtworkModule.kt`, `ArtworkStudioViewModel.kt` (constructor), `CachingMatchEvidenceTest.kt`, and a
  new `FileTitleSearchStoreTest.kt` over a temp directory. Two new files and two test files, over the
  §4 budget: the store's file format deserves its own round-trip test.
- **Tests (draft first):** `a search answered in an earlier open is served from the store without
  asking`, `an entry past its TTL is asked again and replaced`, `other providers' empty answers are
  never stored`, `a ScreenScraper empty answer is stored for one day only`, `a failed or cancelled
  search is never stored`, `the every-platform scope is stored apart from the platform search`, and the
  file store's round trip, including an unreadable file that reads as empty.
- **Accepted cost:** a game ScreenScraper adds can take up to a day to appear.
- **Dependencies:** M.1.

**M.5 implemented (2026-09-11, uncommitted, not yet built).** The expected files, plus the ViewModel test,
which constructs the ViewModel and so had to pass the new argument. Decisions taken while landing it:
- **The caller decides what is kept and for how long; the store only keeps it.** `TitleSearchStore`
  takes an already-normalized provider, query and scope plus a `StoredTitleSearch(candidates,
  expiresAtMillis)`. The TTL rules live in `CachingMatchEvidence.keep`, beside the memory rules they
  extend. A store that cannot read or write throws nothing: a lost entry costs one request.
- **One JSON file per search**, named by a SHA-256 of the key, under `cacheDir/match-searches/`. Each
  file also records its key, so a collision reads as a miss. Written to a `.tmp` and renamed over, so
  a reader never sees half a file. Expired, unreadable or foreign files read as a miss and are
  deleted, and only the newest 500 are kept, so the folder cannot grow without bound.
- **The store is read inside the in-flight slot**, so a caller arriving mid-read shares it, as it
  shares a running search. The write happens after the answer is handed out, and only for an answer
  that came from the provider, never for one read back from the store.
- **The store is a required constructor argument, with no default.** The only production caller is the
  ViewModel, and a default would let it skip persistence silently. `TitleSearchStore.None` keeps
  nothing; the nine existing `CachingMatchEvidenceTest` cases and `ArtworkStudioViewModelTest` use it,
  since they count what one open asks.
- **Tests:** the plan's six store cases in `CachingMatchEvidenceTest` over a fake store and clock, plus
  `clearing memory keeps what the store holds`. `FileTitleSearchStoreTest` covers the round trip (with
  every candidate field), an empty answer, key separation, expiry, an unreadable file, a missing folder
  and the 500-entry cap (at 2).
- **Known effects, accepted:**
  - The Change Match picker's searches go through the same store, so re-typing a title within its TTL
    is answered from the store. There is no "search again" that bypasses it.
  - M.3a's Windows ScreenScraper answer is an empty list sent without a request, and it is stored for
    a day like a real empty answer. It is one small file, and reading it back costs no request either.

**M.6: Background resolution on open**
- **Objective:** instant switches to SteamGridDB and IGDB (AD-22).
- **Requirements:**
  - After `load` starts the active source's job, launch one resolution each for SteamGridDB and
    IGDB, when they are available and not the active provider. They go through M.1's memo and
    `titleSearches`, so a visit that arrives mid-flight joins them.
  - A failure in one does not cancel the other, and is not remembered.
  - Never TheGamesDB, never ScreenScraper.
  - The ViewModel outlives the screen (host-scoped, `:348-351`), so these jobs are cancelled on the
    close path that sets `closed`, and when `load` moves to another game, not only in `onCleared`.
  - They use the query at open. A later query change is resolved on visit by M.1.
- **Expected files:** `ArtworkStudioViewModel.kt`, `ArtworkStudioViewModelTest.kt`.
- **Tests (draft first):** `opening the Studio resolves SteamGridDB and IGDB in the background, never
  TheGamesDB`, `switching to a source resolved in the background asks nothing more`, `a background
  resolution that fails does not stop the other`, `closing the Studio cancels background
  resolutions`.
- **Dependencies:** M.1.

**M.6 implemented (2026-09-11, uncommitted, not yet built).** The two expected files. Decisions taken while
landing it:
- **A visit mid-flight awaits the background resolution itself**, not just its title search. Each
  background resolution is a `Deferred` kept by provider with its memo key, and `resolveMatch` joins it
  when the key matches. Sharing through `titleSearches` alone would have repeated every lookup that is
  not a title search, such as SteamGridDB's Steam app-id lookup (pinned by `a visit mid-flight joins the
  background resolution instead of asking again`). If the background is cancelled while a visit waits,
  the visit resolves for itself.
- **The matcher call was split out** as `resolveAndRemember`, which memoizes and writes no state. Both
  the browse job and the background use it. The match row is written only by the job that owns the
  screen, and the log line marks `(background)`.
- **"Available" means has a key, not "serves the active tab".** A match does not depend on the tab, so
  opening on ICON1, where SteamGridDB is "n/a", still resolves it.
- **Started on every open, including a same-game reopen.** That path clears the memo too. Started after
  the active source's job, and skipped for the active provider, for one already answered, and for one
  already resolving the same query.
- **Cancelled** by `close()`, by `load` moving to another game, and per provider by `forgetMatches`
  (Confirm or Forget Match): a background answer still on its way was resolved against the old row.
  Each entry removes itself on completion (started lazily, so it is in the map before it can finish).
- **Known leftover, from M.2:** when a SteamGridDB resolution fails, the browse's first-hit search asks
  the same failing search once more, since no layer remembers failures. The M.6 failure test asserts
  "at least twice" rather than enshrining that third request.

**M.5 and M.6 on device (Thor, 2026-09-11 17:32, after `:app:installFullDebug`).** The first check
failed only because the app had not been reinstalled: Android Studio's Apply Changes had swapped in
method edits (M.3a was live) but not M.5's new class, Hilt binding and constructor argument or M.6's
new fields, so no `match-searches` folder existed and no background line was logged. Use a full install
for any task that adds a class, a field or a constructor argument. After the install:
- Every open logged `STEAMGRIDDB (background)` and `IGDB (background)` (397–1 555 ms cold), and never
  TheGamesDB.
- `cache/match-searches/` held seven entries.
- Marvel Cosmic Invasion's every-platform Change Match search took 9 800 ms at 17:32:25. After closing
  and reopening, at 17:32:48, the background matches answered in 18–19 ms and the same search returned
  2 candidates in **2 ms**.

**Merge 3d is complete** (`M.0`–`M.6`), all uncommitted. `L.6` from Merge 3c is still IN PROGRESS.

Every task: **if blocked**, stop and report what was attempted, what blocked it, which file caused
it and what decision is needed (`PLANNING_WORKFLOW.md` §4).

## Deferred to a follow-up plan

Written down so the next session does not re-derive them, and so nothing here silently absorbs them:

- **Ranked suggestion picker (spec §9) and match Tiers 4–6.** The multi-result search they need
  landed with Merge 3 for IGDB and TheGamesDB (`searchGames` on both). Still open: the ranked,
  edition-distinguishing picker UI itself, and an IGDB platform-id table so IGDB search can be
  scoped like TheGamesDB's. The Phase 2 matcher is written so these are extra branches, not a rewrite.
- **The remainder of the crop profile table.** Data edits against the AD-11 registry; no code change.
- **Plan B2's bounded scrape concurrency and failures screen.** Only B2's `ScrapeFailure` type is
  consumed here (task 7.1).

## Follow-ups (documented, not implemented)

- `sourceIndex` is reset on `selectTab` (`ArtworkStudioViewModel.kt:394`) but never re-validated
  against the new source list's length elsewhere.
- ~~`ArtworkStudioScreen.kt:70` and `ArtworkStudioViewModel.kt:173` both claim L2/R2 switch sources~~
  — both doc comments deleted in task 1.3 (which absorbed 4.2).
- **The Studio's own search overlay has the IME exposure the Change Match picker had.** It focuses
  its field on open, and an open IME receives key events before `MainActivity.dispatchKeyEvent`.
  The pad's Select and Back therefore never reach `handleGamepadAction`'s `searchOpen` branch
  (`ArtworkStudioViewModel.kt:1416-1423`). Left out of Merge 3 on purpose; the fix is the picker's
  `WizardTextField` model (a cursor stop, with the keyboard opening only when editing starts). It
  becomes a real focusable field in C17's task 2.3 either way.
- ~~The approved HTML mockup is not in `docs/mockups/`~~ — landed as
  `docs/mockups/artwork_image_mockup.png` (2026-09-10). It is a PNG, so the source spec's
  precedence clause resolves against an image rather than markup.

## Hand-off notes

This plan is written to be executed without the conversation that produced it. Every line reference
was verified against the working tree on `more-customization` on 2026-09-09 — re-check any that has
drifted, but do not assume a helper exists that is not named here.

- Repository is the source of truth, above this plan. If implementation contradicts something
  written above, stop and report rather than inventing architecture (`PLANNING_WORKFLOW.md` §6, §12).
- Work **one bounded task per helper**, in dependency order, with the change budget from
  `PLANNING_WORKFLOW.md` §4: 2–4 existing files modified, 1–2 new files, 1 test file, no new
  dependencies without approval.
- This plan is indexed as `C16` in `docs/plans/README.md`. Keep that row's Status cell current as
  phases land — the index is the record, and implemented plans are deleted once their row tells the
  full story.
- The originating design spec is `PFP_Artwork_Manager_Hardening_Design.md`. Where the two disagree,
  **this plan wins** — its corrections are the result of verifying that spec against the code.
