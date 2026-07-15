# Account-Wide Achievements — Full RA + Steam History

Plan for tracking the user's ENTIRE achievement history on RetroAchievements and Steam: every
title their account has achievements in — with full locked/unlocked coin details — not just the
games stored in the PFP library. The Shiba Coins hub becomes the one place to see overall
standing across both services.

Status: DRAFT — not started. Written 2026-07-16 against branch `achievement-integration`
(HEAD cf2bbba). Prerequisite reading: `docs/shiba-coins-achievements-plan.md` (architecture),
`docs/local-steam-achievements-plan.md` (the LOCAL_STEAM sibling plan). Builds on the provider
framework under `feature-achievements/provider/`.

---

## 1. Goal

- Import every game the connected accounts have achievement progress in, as first-class tracked
  entries with per-coin lock/unlock detail — even when the game has no local copy.
- Keep the library-linked flow exactly as it is; account entries and library entries must
  reconcile into ONE entry when they are the same game (no double counting, ever).
- Same tier rules as today: RA by points, Steam by rarity spec v2 (Platinum detection included).

## 2. Provider endpoints

### RetroAchievements (api-kotlin — the clean case)
- `getUserCompletionProgress(user)` — PAGINATED list of every game the user has any
  achievements in, with earned/total counts and game ids. This is the discovery primitive;
  api-kotlin already wraps it (`GetUserCompletionProgress.kt`). Verify pagination params
  (count/offset) against the interface before coding.
- Per-game detail: the existing `getGameInfoAndUserProgress` path via `RaRemoteDataSource.fetch`
  — reused unchanged for full coin rows.
- Incremental refresh: `getUserRecentAchievements` (also wrapped) to find games needing a
  re-fetch without walking the whole list.

### Steam (extend our SteamWebApi — the expensive case)
- Add `IPlayerService/GetOwnedGames/v1` (key + steamid, `include_played_free_games=1`,
  `include_appinfo=1` for names). Returns every owned game + playtime; it does NOT say which
  have achievements.
- SHARED DEPENDENCY: `docs/local-steam-achievements-plan.md` (section 5) needs the same
  endpoint for its owned-vs-cracked four-state model — emu markers in the game folder crossed
  with ownership from the cached owned-games list:

  | Emu markers | Owned on Steam | Meaning |
  |---|---|---|
  | Yes | No | Cracked/unowned copy — LOCAL_STEAM tracking only |
  | Yes | Yes | Owned, playing an offline emu copy — both sets coexist (STEAM + LOCAL_STEAM) |
  | No | Yes | Owned build, no emu — STEAM provider only |
  | No | No | DRM-free/GOG/Epic build — no Steam achievement data; honest untracked reason |

  Whichever plan lands first adds `getOwnedGames` to `SteamWebApi`; the owned-appid cache
  should be one shared component, not two fetch paths.
- Filter: one `getPlayerAchievements` call per candidate game — a game without achievements
  returns success=false and is skipped (and remembered, so it is never probed again).
- Full detail for games that pass: existing schema + global-percentages + mapper path, reused.
- Scale reality: ~3 rate-limited calls per achievement game, 1 per non-achievement game. A
  300-game library is a 10-20 minute FIRST import. This must be a resumable background job
  (WorkManager per the Shiba plan's background-execution rule), never a foreground blocker,
  with per-title progress and a persisted cursor so interruption resumes instead of restarting.
- Incremental refresh: only games with playtime since the last import (GetOwnedGames playtime
  deltas + GetRecentlyPlayedGames), plus manual per-game sync.

## 3. Data model — the core design decision

Today `achievement_sets` / `achievements` / `provider_game_links` are keyed by `game_id` with a
CASCADE FK to the library `games` table. Account entries have no library game. Two options:

**Option A (recommended): provider-keyed source of truth.**
New tables keyed by (provider, provider_game_id):
- `account_achievement_sets`: provider, provider_game_id, title, icon/capsule url, tier counts,
  earned counts, mastered, last_synced_at, in_library flag (derived, denormalized for the hub).
- `account_achievements`: per-coin rows, same columns as today's `achievements`.
- `provider_game_links` remains the bridge: a library game linked to (provider, id) means its
  coins ARE that account entry — the library-side `achievement_sets`/`achievements` tables are
  then MIGRATED into the account tables and dropped, with `game_id` resolved through the link
  at read time. One sync path, one storage, dedupe by construction.
- Migration: explicit Room migration (house rule: exportSchema, no destructive fallback);
  existing rows copy into the account tables using their link's provider id.

**Option B (rejected): shadow library rows or nullable game_id.** Pollutes the games table or
weakens the FK; dedupe becomes a runtime chore; every consumer needs null-handling.

Wallet: `observeWalletCoins` SUM moves to the account tables — the wallet becomes genuinely
account-wide (this is the feature's point: overall standing). Because library games resolve to
the same account rows, nothing double-counts. Expect a large one-time wallet/level jump on
first import; surface it as the earned import result, not a bug.

## 4. UI surfaces

- Hub "All Tracked" becomes account-wide: every account entry, with an "in library" marker on
  titles PFP can launch. Rows without library games show the provider's game title + icon
  (capsule/badge art via Coil, same cache as artwork).
- Detail panel unchanged (progress, per-tier breakdown, total score) — it already renders from
  set rows.
- Opening a non-library entry opens the coins screen keyed by (provider, providerGameId)
  instead of gameId — `ShibaCoinsViewModel.load` gains a provider-keyed entry point; link/sync
  actions hide for entries with no library game (nothing to link).
- "Untracked" keeps its current meaning (library games without achievement data).
- Settings ▸ Shiba Coins gains "Import my Steam library" / "Import my RA history" actions with
  resumable progress + result summary (imported / no achievements / failed), mirroring the
  existing batch-sync reporting.

## 5. Phased implementation

Tests ship with each phase; pipefail-gated builds; atomic conventional commits.

### Phase 0 — Verification gates
- [x] Confirm api-kotlin `getUserCompletionProgress` signature + pagination and its POJO fields.
      DONE 2026-07-15 against the 2.0.0 jar: `getUserCompletionProgress(username, count, offset)`
      -> `Response(count, total, results)`; Progress carries gameId/title/imageIcon/consoleId/
      maxPossible/numAwarded(Hardcore)/award fields. ImageIcon is a media-host path.
- [ ] Confirm GetOwnedGames response shape (appid, name, playtime fields) and that
      `getPlayerAchievements` on a no-achievement game returns a distinguishable result.
- [x] Validate MIGRATION_32_33 on a copy of a real device DB before any install. DONE
      2026-07-15 against the Thor's lite-debug DB (v32: 2 games, 2 Steam sets, 90 coins):
      integrity_check ok, no foreign_key_check rows, all sets/coins carried into the account
      tables with correct titles, wallet SUM identical (315) before and after, every set
      reachable through its widened link.

### Phase 1 — Data model migration  (DONE 2026-07-15)
- [x] Account tables + explicit migration (v32->33) moving existing library sets/coins into
      them; read paths resolve library games through `provider_game_links`, whose key widened
      to (game_id, provider) for STEAM+LOCAL_STEAM coexistence. Decisions locked with it:
      "in library" derives from the link join (no denormalized flag to maintain); provider
      disconnect keeps achievement data cached (matches existing behavior — Phase 4's
      "disconnect cleans account rows" is superseded); account rows survive library deletion.

### Phase 2 — RA history import  (DONE 2026-07-15)
- [x] `RaRemoteDataSource.userCompletionProgress()` paginated walk + `RaAccountImporter`:
      insert-if-absent set stubs for every RA game with earned progress, full-detail fetch per
      stub through the shared sync path, resumable by construction (pending = sets without
      last_synced_at — no separate cursor state), progress + result reporting.
- [x] Hub account-wide; provider-keyed coins screen (`ShibaCoinsTarget`); Settings ▸ Shiba
      Coins "Import my RA history". Account entries keep a Sync action (refresh is meaningful);
      only link/match affordances hide.

### Phase 3 — Steam library import
- [ ] `SteamWebApi.getOwnedGames` + import job with the probe-filter-fetch pipeline, the
      no-achievements memo table, WorkManager execution, resumable cursor.
- [ ] Incremental refresh path (playtime deltas) so routine syncs stay cheap.

### Phase 4 — Polish
- [ ] Import result summaries; per-provider disconnect cleans account rows; "in library"
      cross-linking when a library game later links to an already-imported entry.

## 6. Security / cost notes

- Same credential posture: user's own keys, read-only, no logging, typed errors. GetOwnedGames
  requires the profile's Game Details public (same as today's per-game path).
- Least-data: store only what the hub renders (title, art url, counts, coins). No friends,
  no cross-account data.
- Rate limits: both providers self-throttled at >= 1.1s as today; imports are the first PFP
  feature where total call volume matters — the resumable cursor and the no-achievements memo
  exist to make every call count once.

## 7. Open decisions

- Whether the account-wide wallet is opt-in ("Count my whole account in the Shiba Level") or
  the new default. Recommendation: default ON — it is the feature's purpose — with the import
  actions being explicit user choices anyway.
- Hub ordering once hundreds of entries exist (alphabetical vs recent-first vs progress);
  search already exists in the fullscreen library.
- Whether non-library RA entries also surface console/platform grouping.

---

*Play Field Portal · Internal Plan · Account-Wide Achievements*
