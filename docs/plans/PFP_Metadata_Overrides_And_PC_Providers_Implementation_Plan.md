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
| T6 | Steam `appdetails` keyless provider *(severable)* | T4 | M | READY (not started) |

T1–T5 are implemented, and the unit suites pass: 1,486 tests across `:core:core-domain`,
`:core:core-data`, `:feature:feature-artwork` and `:feature:feature-xmb`, 0 failures. `schemas/48.json`
is exported. The manual pass in §9 — type a Developer, Re-scrape All, confirm it survives, revert it
— has NOT been run on a device and is what remains before this is believed end to end.

`MetadataApplyTest` passes **unmodified**, as §9 required. Three other suites needed updating, each
because it pinned behaviour a task deliberately changed: `IgdbApiTest` (the Apicalypse field list
widened in T5), `MetadataRepositoryCandidatesTest` (it asserted IGDB is skipped on metadata-only
runs — exactly what T5 reverses) and `GameDetailViewModelTest` (`MetadataPreview` gained fields, and
the no-provider overlay stopped being a dead end).

T6 was deliberately not started: its own entry says to start it only if T4 lands and IGDB coverage
disappoints in practice, and T4 covers all three stores (see Q1/Q2 below), so there is nothing yet
to be disappointed by.

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
