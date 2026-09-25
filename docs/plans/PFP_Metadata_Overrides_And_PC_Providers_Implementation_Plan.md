# PFP — Manual Metadata Overrides and Windows Storefront Providers Implementation Plan

Scope: the **text metadata** a game carries — the ten fields in `MetadataField` — who may write
them, and where Windows games get theirs from. Artwork, the Artwork Studio, the relink path, the
identity index and the import/export workers are out of bounds except where this plan names them.

Two tracks that share a seam and nothing else:

- **Override track (T1–T3)** — let a user set any of the ten fields by hand, and make that value
  outrank every automatic writer.
- **Provider track (T4–T6)** — let Windows games resolve metadata from their storefront identity.

They meet only at `MetadataPreset`, which is already the currency the apply pipeline speaks. Either
track can ship without the other.

---

## 1. Problem

### 1.1 Nine of the ten metadata fields are read-only to the user

Game Detail displays description, developer, publisher, release year, release date, genre, age
rating, franchise and community rating. Exactly one of the ten — the title — can be changed by
hand, through **Edit Title** (`GameDetailViewModel.kt:245`, `XMBViewModel.kt:6548`), which writes
`user_title_override`.

There is no way to correct a wrong developer, write a description for an obscure ROM no scraper
knows, fix a mis-parsed genre, or enter anything at all for a game the providers cannot match. The
`is_manual_entry` column exists and is set at import, but nothing lets the user populate a manual
entry's fields afterwards.

### 1.2 Nothing the user typed would survive a re-scrape

There are two writers of the metadata columns, and only one of them asks permission.

**The preview panel** — `ArtworkRepository.applyMetadata` (`ArtworkRepository.kt:155`) — is
user-driven, offers `REPLACE_ALL` / `FILL_MISSING_ONLY` / `CHOOSE_FIELDS` / `KEEP_CURRENT`, and
defaults to filling gaps only. It has exactly one caller,
`GameDetailViewModel.applyMetadataPreview()`. This path is well behaved.

**The bulk scrape** does not use it. Settings ▸ Re-scrape All and scrape-missing run
`MetadataRepository` → `GameDao.updateMetadata` directly (`MetadataRepository.kt:375`), and that
query is:

```sql
description = COALESCE(:description, description),
developer   = COALESCE(:developer,   developer),
…
```

The incoming value wins whenever the provider supplies one. No policy, no preview, no user.

The tree already recognises this as wrong — for one field. `GameDao.kt:393`:

> An automatic scrape may NAME an unnamed game but must never RENAME one. `updateMetadata` takes
> the opposite side for every other column (`COALESCE(:new, old)` — the incoming value wins),
> which is right for a description or a release year and wrong for the title.

That reasoning holds **only while no human can type a description**. The moment §1.1 is fixed,
"incoming wins" becomes wrong for the other nine columns for precisely the reason it was already
wrong for the title. Fixing §1.1 without fixing this ships a feature whose output silently expires.

### 1.3 Windows games have an exact identity that nothing consults

`GameEntity` carries `storefront` (STEAM / EPIC / GOG / AMAZON / CUSTOM_GAME) and
`storefront_game_id`, captured at import, indexed as a pair, with `getByStorefront` and
`updateStorefrontIdentity` on the repository. This is the strongest identity any game in the
library has — better than a CRC, better than a title.

No metadata provider reads it. ScreenScraper and TheGamesDB are console-oriented and match Windows
games by title if at all. A Steam game whose exact app id is sitting in the row still gets
title-matched or missed.

---

## 2. Goals

1. Any of the ten `MetadataField` values can be set by hand, per game.
2. A hand-set value outranks **every** automatic writer, including Re-scrape All.
3. A hand-set value can be reverted, revealing the scraped value underneath.
4. Windows games resolve metadata from `(storefront, storefront_game_id)` with an exact id match —
   Steam, GOG and Epic.
5. No new writer of the metadata columns. The two that exist stay the two that exist.

---

## 3. Non-goals

- **Artwork.** Overrides cover text only. The Artwork Studio owns images.
- **`players`.** Already excluded from `MetadataField` (Non-Goals of C16 task 3.2); it stays out.
- **Bulk editing** across multiple games.
- **An Epic-specific API client.** See D3 — there is no public endpoint to build one on.
- **Changing `GameDao.updateMetadata`'s COALESCE semantics.** D1 is chosen specifically so that
  the most-exercised write path in the module needs no edit.
- **Migrating `user_title_override`** into the new representation. See D2.

---

## 4. Architectural decisions

### D1 — User overrides live in a shadow layer, not in the metadata columns

A new `user_metadata_overrides` TEXT column on `games`, holding a JSON object keyed by
`MetadataField.name`. Display coalesces override over stored value, generalising the existing
`Game.displayTitle = userTitleOverride ?: scrapedTitle ?: title`.

The consequence that makes this the right call: **both existing writers are untouched.** The bulk
scrape keeps refreshing the scraped layer as often and as destructively as it likes, and simply
never wins on screen. Revert-to-scraped is removing a key from the map. No change to
`MetadataRepository`, no change to the COALESCE query, no change to `updateMetadataIfMissing`.

### D2 — TITLE keeps `user_title_override` and is *not* folded into the map

Two representations of one concept is normally a smell, and this one is deliberate.
`user_title_override` is read **in SQL** by at least four queries that join against games and
project a display title:

- `AccountAchievementSetDao.kt:131` — `COALESCE(g.user_title_override, g.title) AS title`
- `AchievementTrackingDao.kt:48` and `:55` — same shape
- `PFPDatabase.kt:1413` — correlated subquery inside a migration

SQLite cannot read a key out of a JSON blob in those queries without `json_extract` and a rewrite
of each. Folding TITLE into the map would mean rewriting achievement joins to gain nothing.

So: **TITLE stays a column; the map covers the other nine.** A single accessor,
`MetadataOverrides.of(game)`, hides the split so no call site has to know about it. This is the one
place the design is deliberately asymmetric, and it is documented at the accessor.

### D3 — One provider for all three storefronts: IGDB via `external_games`

Not three clients. The reasoning, store by store:

| Store | Public metadata API | Verdict |
| --- | --- | --- |
| Steam | `store.steampowered.com/api/appdetails?appids=` — undocumented, keyless, long-stable | Usable |
| GOG | `api.gog.com/products/{id}?expand=description` — unofficial, stable | Usable |
| Epic | **None.** No official catalog endpoint; the unofficial GraphQL requires auth and changes | Not buildable to the same standard |

IGDB solves all three at once. It maintains an `external_games` mapping from store ids (Steam, GOG,
Epic among them) to IGDB games, and PFP already persists exactly that key. IGDB is also already in
the tree — `IgdbApi.kt` has a client, a Twitch token cache and a credentials provider — so this is
widening an existing query, not adding a dependency.

> **Blocking unknown before T4 starts.** IGDB's `external_games.category` enum values for Steam,
> GOG and Epic must be read from IGDB's current API documentation. They are not to be hardcoded
> from memory or from a blog post. If Epic turns out not to be covered by `external_games`, T4
> ships Steam and GOG and Epic falls back to title search — state that outcome in the task rather
> than inventing an endpoint.

### D4 — IGDB gains text, and joins the preset list

`IgdbGameInfo` currently carries `artworkUrl`, `heroUrl`, `logoUrl` and nothing else
(`IgdbApi.kt:46`), which is why `MetadataApply.presetsFrom` explicitly excludes it:

> IGDB's `IgdbGameInfo` carries cover/hero URLs and no text today, and SteamGridDB is artwork-only
> by design, so neither is offered.

T5 adds name, summary, involved companies, first release date, genres and rating to the Apicalypse
field list and offers an IGDB preset. That comment gets corrected in the same task.

### Rejected alternatives

**Locked-fields column.** A `locked_fields` list, with the typed value living in the normal column
and writers skipping locked fields. Rejected: `MetadataRepository` and the COALESCE query both have
to learn about locks, which puts the change squarely in the hottest write path; there is no
revert-to-scraped; and TITLE ends up with two different protection mechanisms.

**Ten shadow columns.** One per field, mirroring `user_title_override` literally. Rejected: ten
columns of migration surface for no capability the single map does not have, and the SQL-readability
argument in D2 only applies to TITLE.

**Bespoke Steam and GOG clients, no IGDB change.** Rejected as the primary route: two undocumented
endpoints to own, and Epic gets nothing. Retained as **T6, severable** — Steam's endpoint needs no
API key at all, which matters for a user who has never configured IGDB/Twitch credentials.

**Teaching every writer to respect a user-owned flag.** Rejected for the same reason as
locked-fields, more so: it spreads the concept across every current and future writer instead of
containing it in one accessor.

---

## 5. Existing systems to reuse

| System | File | Used for |
| --- | --- | --- |
| `MetadataField` (10 fields) | `match/MetadataApply.kt` | The override map's key set — unchanged |
| `MetadataApplyPolicy` | `match/MetadataApply.kt` | Manual edits reuse `CHOOSE_FIELDS` wholesale |
| `MetadataApply.plan()` | `match/MetadataApply.kt` | Single source of "what will change" — unchanged |
| `MetadataPreset` | `match/GameMatching.kt` | A manual edit is just a preset with a new provider |
| `MatchProvider` | `match/GameMatching.kt` | Gains `MANUAL` |
| `ArtworkRepository.applyMetadata` | `api/ArtworkRepository.kt:155` | The one writer; gains one branch |
| `MetadataPreviewPanel` | `xmb/ui/detail/MetadataPreviewPanel.kt` | Current-vs-Incoming UI, gains an editable column |
| Edit Title dialog plumbing | `XMBViewModel.kt:6547` | Per-field text entry already exists and clears on blank |
| `IgdbApi` + token cache | `api/IgdbApi.kt` | The PC provider — widened, not replaced |
| `(storefront, storefront_game_id)` | `GameEntity.kt`, `GameRepository.kt:67` | The exact PC identity, already persisted and indexed |

---

## 6. Design detail

### 6.1 The accessor

```kotlin
/**
 * A game's effective metadata: the user's value where one is set, the scraped value otherwise.
 *
 * TITLE is deliberately not in the map — see D2. This accessor is the only place that knows the
 * split, so no call site has to.
 */
class MetadataOverrides private constructor(private val map: Map<MetadataField, Any>) {
    operator fun get(field: MetadataField): Any?
    fun isOverridden(field: MetadataField): Boolean
    companion object { fun of(game: GameEntity): MetadataOverrides }
}
```

Malformed or unparseable JSON reads as an empty map and is logged, never thrown: a corrupt override
blob must degrade to "no overrides", not break Game Detail.

### 6.2 Manual edits flow through the existing pipeline

A manual edit is a `MetadataPreset(provider = MANUAL, …)` built from what the user typed. It goes
through `MetadataApply.plan()` exactly like a provider preset, so the preview's "will change"
markers stay honest for free.

The one branch is at the write, in `applyMetadata`:

- `preset.provider == MANUAL` → the plan is merged into `user_metadata_overrides`. Columns untouched.
- anything else → the existing column writes, unchanged.

A field the user blanks is removed from the map — same convention as Edit Title, where blank input
clears the override (`XMBViewModel.kt:6547`).

### 6.3 Where the editing happens

`MetadataPreviewPanel` already renders a Current column and one column per provider preset, with a
per-field tick for `CHOOSE_FIELDS`. The manual editor is one more column, whose cells are text
fields, plus a per-row revert affordance when that field is overridden. This reuses the policy
selector, the change markers and the apply button rather than building a second editing surface.

**Alternative if the panel gets too crowded:** a dedicated Edit Metadata screen reached from
`GameDetailAction` next to `RENAME`. Costs a screen and duplicates the diff view; decide during T3
with the panel in front of you, not now.

### 6.4 The PC lookup

```
game.storefront + game.storefrontGameId
  → IGDB external_games (where category = <store>, uid = <id>)
  → IGDB game id
  → existing IgdbApi fetch-by-id, widened with text fields (T5)
  → MetadataPreset(provider = IGDB, …)
```

Exact match, no title guessing. Falls back to the existing title search when the row has no
storefront pair or IGDB does not know the id.

---

## 7. Sequencing

Two independent chains. Nothing crosses.

```
Override track:   T1 ──► T2 ──► T3
Provider track:   T5 ──► T4
                  T6 (severable, any time)
```

T1–T3 and T4–T6 can run concurrently by different helpers. T6 is optional and should only start if
T4 lands and IGDB coverage disappoints in practice.

---

## 8. Tasks

### T1 — `user_metadata_overrides` column and the accessor

Migration 47 → 48 adding `user_metadata_overrides TEXT` to `games`. `MetadataOverrides` accessor
per §6.1. `Game` gains effective-value accessors mirroring `displayTitle`.

**Change budget:** `PFPDatabase.kt`, `GameEntity.kt`, `Game.kt`, `GameDao.kt` (one setter), one new
file. No writer changes.

**Acceptance:** round-trips a full map; malformed JSON reads as empty and logs; absent column value
(legacy rows) reads as empty; TITLE is not in the map and `displayTitle` is unchanged.

**Stop condition:** the column exists and reads back. Nothing writes it yet.

### T2 — `MANUAL` preset and the override write path

`MatchProvider.MANUAL`. `applyMetadata` gains the §6.2 branch. A clear/revert entry point.

**Change budget:** `GameMatching.kt`, `ArtworkRepository.kt` (one branch), `MetadataApply.kt`
(`presetsFrom` unchanged — manual presets are built by the ViewModel, not from candidates).

**Acceptance:** provider presets write columns exactly as before, proven by the existing
`MetadataApplyTest` still passing untouched; a MANUAL preset writes only the map; blanking a field
removes its key; `FILL_MISSING_ONLY` against a MANUAL preset fills only fields with neither an
override nor a stored value.

### T3 — Editable column in the metadata preview

Per §6.3. Text entry per field, revert affordance per overridden row.

**Acceptance:** typing a value and applying shows it on Game Detail; Re-scrape All afterwards leaves
it visible; reverting reveals the scraped value.

**Stop condition:** if the panel cannot carry the editor legibly, stop and report — do not
unilaterally build the separate screen from §6.3's alternative.

### T4 — IGDB lookup by storefront identity *(blocked on the D3 unknown)*

**Do not start until** IGDB's `external_games` category values for Steam, GOG and Epic have been
read from current IGDB documentation and recorded in the task notes.

**Acceptance:** a Steam game with a known app id resolves to the right IGDB game without any title
comparison; a GOG game likewise; Epic either works or is documented as uncovered with the fallback
stated; a row with no storefront pair falls back to title search exactly as today.

### T5 — IGDB text metadata and preset

Widen the Apicalypse field list; extend `IgdbGameInfo`; add the IGDB branch to
`MetadataApply.presetsFrom`; correct the KDoc that says IGDB has no text.

**Acceptance:** an IGDB preset appears in the preview for a game IGDB knows; empty IGDB responses
still produce no preset (`isEmpty` filter).

### T6 — Steam `appdetails` provider *(severable, lowest priority)*

Keyless Steam provider for users with no IGDB credentials. Only start if T4 lands and coverage is
genuinely insufficient.

---

## 9. Verification

**Unit.** `MetadataOverrides` round-trip and corruption tolerance. `applyMetadata` branch behaviour
for both provider kinds under all four policies. Storefront → IGDB id resolution with a faked
`external_games` response. The existing `MetadataApplyTest` must pass **unmodified** — if T2 needs
to change it, the branch is in the wrong place.

**Manual, in this order, because it is the whole point of the plan:**

1. Type a Developer on a game by hand.
2. Settings ▸ Artwork ▸ Re-scrape All.
3. The typed Developer is still shown.
4. Revert it; the scraped Developer appears.

**Regression.** Edit Title still works and still outranks `scraped_title`. Achievement list titles
(the four SQL `COALESCE` readers in D2) are unaffected.

---

## 10. Execution Task Index

| ID | Task | Depends On | Effort | Status |
| --- | --- | --- | --- | --- |
| T1 | `user_metadata_overrides` column (migration 47 → 48) + `MetadataOverrides` accessor | None | M | DONE |
| T2 | `MatchProvider.MANUAL` + override write branch in `applyMetadata` | T1 | M | DONE |
| T3 | Editable column in `MetadataPreviewPanel` + revert affordance | T2 | M | DONE |
| T4 | IGDB lookup by `(storefront, storefront_game_id)` via `external_games` | T5, D3 unknown resolved | M | DONE |
| T5 | IGDB text fields + IGDB preset in `presetsFrom` | None | S | DONE |
| T6 | Shared storefront resolver + Steam `appdetails` keyless provider *(severable)* | T4 | L | DONE |

T1–T5 are implemented, and the unit suites pass: 1,486 tests across `:core:core-domain`,
`:core:core-data`, `:feature:feature-artwork` and `:feature:feature-xmb`, 0 failures. `schemas/48.json`
is exported. The manual pass in §9 — type a Developer, Re-scrape All, confirm it survives, revert it
— has NOT been run on a device and is what remains before this is believed end to end.

`MetadataApplyTest` passes **unmodified**, as §9 required. Three other suites needed updating, each
because it pinned behaviour a task deliberately changed: `IgdbApiTest` (the Apicalypse field list
widened in T5), `MetadataRepositoryCandidatesTest` (it asserted IGDB is skipped on metadata-only
runs — exactly what T5 reverses) and `GameDetailViewModelTest` (`MetadataPreview` gained fields, and
the no-provider overlay stopped being a dead end).

T6 was subsequently started, at the user's direction and against a wider brief than this plan's own
one-line entry: a shared **multi-storefront resolver** with Steam as its first and only provider.
The brief's own Phase 22 forbids building the three stores at once, so GOG and Epic are explicitly
out of this pass — the point is to validate the shared architecture against one store before a
second store's quirks can reach it. See §12.

Status key: `READY` · `IMPLEMENTING` · `VERIFYING` · `DONE` · `BLOCKED`.
Effort: S = under a day · M = a few days · L = a week or more.

`DONE` requires: acceptance criteria met, the task's tests written and passing, the change budget
respected or the overrun justified in writing, and no known blocker.

---

## 11. Open questions

1. **IGDB `external_games` category ids** for Steam, GOG and Epic — blocked T4. **RESOLVED**, read
   from IGDB's own API documentation (api-docs.igdb.com, "External Game Enums") on 2026-09-24:
   `steam = 1`, `gog = 5`, `epic_game_store = 26`. Recorded at
   `IgdbApi.EXTERNAL_GAME_CATEGORIES`, with a test pinning the three values.

   One thing the documentation added that D3 did not anticipate: `external_games.category` is
   marked **DEPRECATED** in favour of `external_game_source`. The deprecated field is still the one
   used, on purpose — the replacement's ids live behind the `/external_game_sources` endpoint and
   are published nowhere, so switching would trade a documented constant for an undocumented one
   plus a round trip. The mapping is a single map, so this is one edit when IGDB publishes them.

   AMAZON was left unmapped rather than guessed: IGDB splits it three ways (`amazon_asin` 20,
   `amazon_luna` 22, `amazon_adg` 23) and `games.storefront` does not say which. A wrong exact match
   is worse than the title-search fallback.

2. **Does IGDB cover Epic at all** in `external_games`? **RESOLVED — yes**, `epic_game_store = 26`.
   Goal 4 is met for all three stores, not two of three.

3. **T3's surface** — **RESOLVED: the editable column**, as §6.3 preferred. The panel carried it
   without crowding, because the Manual column reuses the existing Current column and the existing
   per-row structure; only the incoming cell changes, from a label to typed text. The separate Edit
   Metadata screen was not built.

   Two things the panel needed that §6.3 did not name. The Manual column shows **all ten fields**,
   not only the ones a source supplied — an empty field is precisely the one the user needs to
   reach. And "no provider recognised this game" stopped being a dead end: that overlay used to
   offer only a Close button, which was exactly backwards, since a game no scraper knows is the one
   most worth typing by hand. It now opens on the Manual column with the note above it.

---

## 12. T6 — the shared storefront resolver (Steam first)

T6 grew from "a keyless Steam client" into the resolver the whole Windows-metadata path needed, on
a 22-phase brief supplied with the task. What follows is what was built, what was reused rather
than rebuilt, and what was deliberately left.

### 12.1 What the tree already had (Phase 0)

The architecture review found that more than half the brief already existed, so most of T6 is
composition rather than new machinery:

| The brief asks for | What already existed | What T6 did |
| --- | --- | --- |
| Storefront identity | `games.storefront` + `storefront_game_id` (import identity) | Added `game_storefront_identities` for the *resolved* identity — a different fact (§12.2) |
| Title normalization rules 2, 3 | `ArtworkNaming.normalizeForMatch` / `simplifyTitle`, `TitleCanon` | Composed them; added only rules 1, 4 and 5 |
| Candidate model | `GameCandidate`, `MatchTier` | Added `StorefrontCandidate` + `MatchConfidence` for storefront scoring |
| Metadata currency | `MetadataPreset`, `MetadataApply.plan` | `MatchProvider.STEAM` joins `presetsFrom`; no new writer |
| Search caching | `TitleSearchStore` (file-backed, Studio-owned) | New in-memory `StorefrontSearchCache` — see §12.4 |
| Notifications | `BackgroundTaskCenter` | One aggregate row per bulk run, no new infrastructure |
| A keyless Steam client | `feature-achievements`' Retrofit `SteamStoreApi` | A Ktor one in `feature-artwork`; see §12.3 |

### 12.2 Why a new table and not the existing storefront columns

`games.storefront` / `storefront_game_id` are the **installation** identity: where an entry was
imported from, and what `getByStorefront` deduplicates future imports against. A resolved identity
is a different claim — "the game in this row is the one Steam calls 620" — and is true of a
Winlator shortcut or a hand-added game no launcher ever reported. Writing a resolved id into the
import columns would make a later Steam import deduplicate against a game it never installed.

`game_storefront_identities` is therefore a child table keyed `(game_id, store)`, cascade-deleted
with its game, carrying Epic's namespace/catalogItemId/appName columns unused so that adding Epic
costs no migration. Migration **49 → 50**, purely additive, nothing backfilled.

### 12.3 Why the Steam client is duplicated

`feature-achievements` already has a keyless `SteamStoreApi`. It is not reused, for three reasons
in order of weight: a feature module must not depend on another feature module; it is
Retrofit/OkHttp where `feature-artwork` is Ktor throughout; and it requests `filters=basic`, which
returns the name and nothing else — no developer, publisher, release date or genres, which is
exactly the payload a metadata provider exists to fetch.

### 12.4 Why the search cache is not `TitleSearchStore`

`TitleSearchStore` is keyed by `MatchProvider` and holds `GameCandidate`, which carries no
developer and no publisher. Reusing it would silently drop the two corroborating signals the
scorer depends on most, turning HIGH matches into AMBIGUOUS ones. Confirmed identities and cached
searches are also required by Phase 14 to expire differently: the cache is six hours, the identity
table expires never.

### 12.5 The rule the design enforces

A title is how an identity is **discovered**; the id is the relationship. Once a row exists in
`game_storefront_identities`, the resolver does not normalize, search or score — it fetches by id.
A title search reopens only when the store says the id is gone (and the link was not
user-confirmed), when the user unlinks, or on an explicit rematch. A provider failure never
reopens it: an outage must not be able to unlink a library.

False positives are treated as strictly worse than prompts. Two candidates sharing an exact
normalized title are AMBIGUOUS no matter how far apart they score; an exact title whose year
contradicts the local one is AMBIGUOUS; and an edition-stripped or partial title can never reach
an auto-linking confidence without independent corroboration.

### 12.6 What T6 did NOT build, and why

- **The GOG and Epic providers.** Phase 22 is explicit: do not implement the three at once, and
  validate the shared resolver against Steam first. Adding either is one entry in
  `StorefrontModule.provideProviders` — the identity table, normalizer, scorer and resolver already
  accommodate them.
- ~~The ambiguous-match picker (Phase 10) and the Rematch menu entry (Phase 18).~~ **Built**, after
  the artboards were approved — see §12.8.
- **A settings entry point for the bulk sync.** `StorefrontMetadataSync.run` exists and reports
  correctly; nothing calls it yet, because where it belongs in Settings is a UI decision.

### 12.7 Verification status

Seven new suites, all green alongside the existing ones in `:core:core-data` and
`:feature:feature-artwork`: `StorefrontTitleNormalizerTest`, `StorefrontMatchScorerTest`,
`SteamMetadataProviderTest` (MockEngine — no network), `StorefrontRequestQueueTest`,
`StorefrontSearchCacheTest`, `StorefrontMetadataResolverTest`, `StorefrontMetadataSyncTest` and
`Migration49To50Test`. `schemas/50.json` is exported.

Three existing suites needed a change, each because T6 changed what it pinned, and each a
constructor argument or a comment rather than an assertion:
`MetadataRepositoryCandidatesTest` and `ProviderMatchEvidenceScreenScraperTest` (both gained one
relaxed mock) and `GameMatcherTest` (a comment saying Change Match offers "four" providers; the
assertion itself, `MatchProvider.entries - MANUAL`, was already correct and is unchanged).
`MetadataApplyTest` passes **unmodified**, as §9 requires.

**Live check: done.** Every unit test mocks the transport, so the open risk was that the response
shapes in `SteamStorefrontApi` were pinned against fixtures written from the observed format rather
than a captured live response — `storesearch` and `appdetails` being keyless and undocumented is
what makes them usable and also what makes them free to change. Steam search has since been
confirmed working against the real endpoint on device, through the picker, so the search half of
the contract is real rather than assumed.

Two things the device pass has NOT yet exercised, neither of them blocking: fetching a preset by a
stored appid (`appdetails` → `MetadataPreset` → the Steam column in Update Metadata), and the §9
manual override pass, which has been outstanding since T1–T5 and is unrelated to this task.

Adding `MatchProvider.STEAM` also made two existing `when` expressions non-exhaustive
(`GameMatcher.savedIdFor` and `ProviderMatchEvidence.searchByTitle`). Both were given real answers
rather than an `else`: Steam has no saved id on `games` by design, and its title search delegates to
`SteamMetadataProvider` so there stays exactly ONE queued, deduplicated, cached path to Steam. The
same pass closed a gap the compiler did not flag — `candidateByStorefront` returned null for Steam
despite its capability saying `addressableByStorefrontId = true`.

### 12.8 The picker and Rematch (Phases 10 and 18)

Designed as artboards first, approved, then built to them.

**The seam.** `StorefrontMatchRepository` is the only thing the XMB talks to, so `feature-xmb`
never touches `GameStorefrontIdentityDao` or a provider. Its `lookup` resolves with
`allowAutoLink = false`: **opening either screen cannot link a game**, and the one write in the
class is `confirm`, which happens because a person chose.

**`ignoreStoredIdentity`.** Rematch needed a way past the stored-identity fast path — otherwise
"search again" would return the stored id forever. It is a parameter on `resolve`, never set
automatically, and it still writes nothing: the existing link survives until the user picks a
replacement. The import-captured pair is skipped by it too, for the same reason — the user is
saying the id PFP has is wrong, and the captured pair is an id PFP has.

**Overlay order.** Rematch sits UNDER the picker, so Back from a picker opened by Replace returns
to Rematch rather than dropping the user out to Game Detail.

**One deviation from the artboard, deliberate.** The Rematch artboard drew Replace and Remove as
two separate targets on each row — three focus stops per line. Built, each row is ONE stop whose
action is chosen with Left/Right and taken with Select. A line of small targets is hard to hit on
a TV, and Remove is not a button anyone should reach by accident. The look is unchanged; only the
traversal is. Touch still taps the buttons directly.

**Wording.** The More Information ledger lists signals that scored NOTHING, worded by which side
was silent — `Release year — the store didn't say` versus `Developer — no match against your copy`.
The mapper can only see the candidate, so it never claims the user's own row is the thing missing
a value when it does not know that.

Two suites cover it: `StorefrontMatchRepositoryTest` (every `lookup` branch, and that looking
never links) and `StorefrontMatchUiTest` (the mapping and the wording). `GameDetailViewModelTest`
gained one relaxed mock and one explicit stub, because `Lookup` is a sealed interface a relaxed
mock cannot invent.

**The floor governs linking, not looking.** Found on device with `Bravely Default`, where Steam
has `BRAVELY DEFAULT II` — a different game, which scores a partial title and nothing else and so
falls below `PLAUSIBLE_FLOOR`. The scorer was right to refuse it and the resolver was wrong to
return NO_MATCH: the picker showed an empty screen while the store plainly listed two near
namesakes, which reads as a broken search and offers no way to say which one is yours. Near misses
now reach a picker the user opened, at `LOW`, with a notice saying they are not confident matches.
An automatic pass still treats them as NO_MATCH — a bulk run must not queue a confirmation for
every game with a near-namesake — and a candidate that scored zero is never shown, because that is
a search-engine artefact rather than a near miss. Three tests in `StorefrontMetadataResolverTest`
pin both halves.

**A layout bug the same session found.** Both panels put their explanatory text as a sibling below
a `weight(1f, fill = false)` scroll region, so once the rows plus that text outgrew the 540dp cap
the text was placed over the LAST row — the first row could never show it, which is why Steam
looked right and Epic did not. The text now lives inside the scroll region with the rows it
explains, and the region fills.

Still unbuilt, and still needing a decision rather than code: where the bulk
`StorefrontMetadataSync.run` belongs in Settings.
