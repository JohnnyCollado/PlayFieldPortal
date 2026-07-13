# Shiba Coins — RetroAchievements & Steam Achievement Integration

Plan for a unified, cross-provider achievement system rendered as **Shiba Coins**: a
PlayStation-style Bronze / Silver / Gold / Platinum tier set that players earn, track, and
display across RetroAchievements (emulated titles) and Steam (PC titles), with a single XP
economy and level. No Steam or RetroAchievements password is ever handled.

Status: Phases 1-2 (domain core + persistence) landed and tested; Phases 3-9 not started.
Branch: `achievement-integration`. This document is the source of truth to resume from on any
machine.

---

## 1. Goal

Give every game a coin-weighted completion view and roll all coins into one account-wide
Shiba Level, surfaced through the existing PFP surfaces (Game Detail glance, a dedicated
coins screen, the game context menu, and a category hub) — all theme-aware, offline-first,
and credential-safe.

---

## 2. Locked design decisions

These came out of the design sessions and are settled unless a later step invalidates one.

### 2.1 Providers and credentials (security-critical)
- **RetroAchievements** — identity = RA username; secret = the user's personal **Web API
  key** (from their RA control panel). Read-only. Official Kotlin client library exists.
- **Steam** — identity = **SteamID64** (user pastes SteamID64 or vanity name; vanity resolved
  once via `ResolveVanityURL`); secret = the user's **own Steam Web API key**. Read-only.
  Requires the user's profile "Game details" set to Public, or the API returns
  "Profile is not public" (surface this as a clear inline state, not an error).
- **No passwords, no OAuth, no backend.** Both providers are read-only public-data APIs. We
  never ship a key of our own and never run a proxy. This is the maximally credential-safe
  model and mirrors how `sgdb_api_key` already works.

### 2.2 Tiers (rarity-calibrated)
Bronze / Silver / Gold are assigned from **global unlock rarity**, calibrated against real
PlayStation trophy data (sampled 17 AAA titles, 1,472 non-platinum trophies) so the tier
mix reproduces PlayStation's real ~71 / 23 / 6 split:

| Shiba tier | Global unlock rarity | ~Share of coins |
|---|---|---|
| Bronze | >= 20% | ~66-71% |
| Silver | 5-20% | ~23-27% |
| Gold | < 5% | ~6-7% |
| Platinum | 100% set completion (minted locally) | 1 per game |

- **Platinum = mastery.** Crown is earned only at 100% of the set (RA "Mastery" / Steam
  100%). It is not a per-achievement tier; it is the meta-award. Locked and shown as a
  banner until 100%.
- **Rarity is the only signal both providers share**, so it is the tier spine. Sub-1-2%
  coins get a foil/shine flourish rather than a fifth tier.

### 2.3 XP economy and level
- **Coin weights:** Bronze 15, Silver 30, Gold 90, Platinum 300 (PSN ratio 1:2:6:20). One
  unified wallet across Steam + RA.
- **Partial credit as you go** — each coin banks into the Shiba Level the instant it
  unlocks, never gated on set completion. This powers the "cascade on first sync" reveal.
- **Shiba Level curve** (banded, calibrated to a ~1,700-coin median 100% game):

  | Levels | Coins per level |
  |---|---|
  | 1-9 | 100 |
  | 10-24 | 250 |
  | 25-49 | 600 |
  | 50-74 | 1,200 |
  | 75-99 | 2,000 |
  | 100+ | 3,000 |

- **Rank names** (cosmetic, per band): Pup -> Scout -> Tracker -> Ronin -> Elder ->
  Inu Master.
- **Progress %** on a game is coin-weighted over its individual coins (Platinum is the prize,
  excluded from the denominator).

### 2.4 UI surfaces
- **Game Detail Screen** — a compact coin summary strip (progress, tally, locked crown, one
  "Open Shiba coins" door). Glance only.
- **Dedicated Shiba Coins screen** (per game) — summary header + crown banner + the full
  per-coin list with sort (Tier list, Earned, Rarest) and filters (All / Earned / Locked),
  hidden-coin teases, badge art, rarity %, unlock date.
- **Context menu** — a `View Shiba coins` item on the game context menu (same shelf as
  Direct Launch) so players reach the coins screen without opening the GDS.
- **Category hub** (later phase) — a dedicated XMB column: cross-library aggregate, player
  card, Shiba Level / rank, and library lenses (closest to mastery, rarest earned).

### 2.5 Coin art
- Four medallions (Bronze / Silver / Gold / Platinum), the Platinum crowned. Same Shiba face
  across all four, metal + crown differentiate. User-supplied art templates.
- Coins are **provider-neutral**; the source (RA / Steam) shows as a small corner badge.
- States per coin: earned (full color), locked (desaturated), hidden (silhouette, redacted
  description), ultra-rare foil.

---

## 3. Theme integration and accent-color priority

The coin system must feel native to the active PFP theme. Rule of thumb: **chrome follows
the theme accent; coin identity does not.**

- **Accent-driven (reads `LocalPFPColors.current.accentColor`):** progress-bar fills,
  selected-row highlight, active sort/filter chip, section headers, the "Open Shiba coins"
  and action buttons, the level/rank progress bar. These must never hardcode a color — they
  resolve through `PFPColors`, exactly like the rest of the XMB shell (see
  `core-ui/theme/PFPTheme.kt`, `LocalPFPColors`). In practice, reuse the shared
  `menuCursorFill()` / `menuCursorEdge()` helpers (`core-ui/theme/MenuCursor.kt`) for
  focus/selection/glow — they already lerp `accentColor` toward white for legibility on dark
  panels, exactly as `DetailContextMenu` and `GameDetailScreen` do.
- **Fixed identity (not themed):** the four tier metals (bronze/silver/gold/platinum). A gold
  coin must read as gold under every theme; theming the metal would destroy the tier signal.
- **Legibility on any wallpaper:** coin medallions carry their own rim + internal shading, so
  they stay distinguishable against arbitrary theme backgrounds — the same Sony guideline the
  icon system follows (see [icon-system-plan.md](icon-system-plan.md)). Coin art is therefore
  **exempt from the unified icon tint** (`PFPColors.iconColor` / `PortalIcon`), like the
  shaded media icons.
- **Surfaces** reuse the XMB overlay conventions: dark scrim + `PFPTheme(colors)` wrapper, so
  the coins screen and category hub inherit theme colors through the existing CompositionLocal
  rather than a parallel color path.
- **Category hub icon** = the crowned Platinum Shiba coin, registered as a multi-color asset
  (not a tintable silhouette).

---

## 4. Architecture

Follows the existing PFP module layout (see DESIGN.md section 4.1).

### 4.1 New module: `feature-achievements`
Holds the provider API clients, the sync/repository layer, the coin/level domain logic entry
points, and the coins UI (dedicated screen + strip composables). Mirrors how `feature-artwork`
owns the SteamGridDB client + repository.

- Domain models (enums, value types, level math) live in **`core-domain`**.
- Room entities / DAOs live in **`core-data`** (single DB, explicit migration).
- Secret providers reuse **`core-common/security/KeystoreSecretCipher`** and
  **`core-common/logging/LogRedaction`**.

### 4.2 Domain model (`core-domain`)
- `ShibaTier { BRONZE(15), SILVER(30), GOLD(90), PLATINUM(300) }` — weight + the rarity
  thresholds; `tierForRarity(percent): ShibaTier` pure function (unit-tested against the
  calibration table).
- `AchievementProvider { RETRO_ACHIEVEMENTS, STEAM }`.
- `ShibaLevel` — pure banded curve: `levelForCoins(total): Int`, `coinsForLevel(n)`,
  `progressInLevel(total)`, `rankFor(level)`. No Android deps; fully unit-tested.
- `GameCoins` / `CoinWallet` — value types the UI renders.

### 4.3 Persistence (`core-data`, Room)
New entities (added via one explicit migration; `exportSchema = true`, no destructive
fallback — house rule):

- `AchievementSetEntity` — one row per (game, provider): provider game id, tier counts,
  earned counts, mastered flag, last-synced timestamp.
- `AchievementEntity` — one row per coin: id, gameId, provider, title, description, tier,
  globalRarity, iconUrl, isHidden, isEarned, earnedAt.
- The **wallet / level is derived** reactively from earned coins (summed by weight). A small
  cached aggregate row (`ShibaWalletEntity`) may back the player card for O(1) reads; it is
  recomputed on sync, never the source of truth.

DataStore keys (all in `pfp_prefs`, snake_case, mirroring `sgdb_api_key`):

| Key | Type | Notes |
|---|---|---|
| `ra_username` | String | not secret |
| `ra_api_key` | String | **encrypted at rest** via `KeystoreSecretCipher` |
| `steam_id64` | String | resolved SteamID64 (not secret) |
| `steam_api_key` | String | **encrypted at rest** via `KeystoreSecretCipher` |
| `achievements_enabled` | Boolean | master toggle |
| `achievements_sync_last` | Long | last successful sync epoch |

### 4.4 Provider layer
- `RetroAchievementsApi` (official Kotlin lib or Ktor) — `getGameInfoAndUserProgress` (per-game
  coins + earned state in one call), ROM hash resolution hooked into the existing `RomScanner`.
- `SteamAchievementsApi` (Ktor) — `GetSchemaForGame` (names/desc/icons/global %),
  `GetPlayerAchievements` (earned + timestamps), `GetGlobalAchievementPercentagesForApp`
  (rarity), `ResolveVanityURL` (one-time id resolution).
- `AchievementRepository` — the single entry point (like `ArtworkRepository`): resolves a
  game's provider id, fetches, maps to `AchievementEntity`, computes tiers, upserts to Room,
  recomputes the wallet. Exposes cold Flows for the UI.

### 4.5 UI composition anchors (verified against code)
Every coin surface reuses an existing pattern — no new UI frameworks.

| Coin surface | Reuses | Mechanism |
|---|---|---|
| Grid `View Shiba coins` menu item | `XMBContextMenu` -> `ContextMenuOverlay` -> `PspContextMenuOverlay` | add one `XMBContextMenuItem` to the game-menu builder in `XMBViewModel`; activation sets the shell state below |
| GDS coin summary strip | `GameDetailScreen` scroll Column + `DetailComponents.kt` idiom | new section composable + new `state.mainFocus` index; "Open" sets the shell state |
| GDS options `Shiba coins` row (optional) | `DetailContextMenu` / `DetailMenuRow` (`GameDetailViewModel`) | add a `DetailMenuRow` |
| Dedicated Shiba Coins screen | shell-level overlay parallel to `GameDetailScreen`; `DetailBreadcrumb` + translucent theme-gradient backdrop | new `uiState.activeShibaCoinsGameId?.let { ShibaCoinsScreen(...) }` block in `XMBShell` |
| Accent / focus / selection | `menuCursorFill()` / `menuCursorEdge()`, `LocalPFPColors.accentColor` | already theme-derived; no new color path |

- **Structural decision — the dedicated coins screen is a shell overlay, not a GDS
  sub-screen.** `ArtworkStudio` renders inside `GameDetailScreen` (`state.showArtworkStudio`),
  but coins must open from the grid context menu *without* entering the GDS, so it lives at the
  shell level (`activeShibaCoinsGameId`) and both the context menu and the GDS strip set that
  same state. It takes the same params as `GameDetailScreen` (`onBack`, `pendingGamepadAction`,
  `onGamepadActionConsumed`) with its own focus/selection in a `ShibaCoinsViewModel`.
- **Wave-freeze:** add `activeShibaCoinsGameId != null` to the opaque-overlay condition in
  `XMBShell` (~line 354) so the wave stops animating behind the full-screen coins overlay.

---

## 5. Sync and optimization strategy

Consistent with the "Manual control / no background polling" design principle (DESIGN.md 2).

- **Offline-first.** UI always renders from Room; the network is never on the render path.
- **Sync triggers:** (a) opening a game's coins surface, throttled to once per
  `achievements_sync_last + N`; (b) an explicit "Sync" action; (c) returning from an emulator
  launch (RA coins may have changed). No `FileObserver`, no background polling.
- **Background execution:** long/multi-game syncs run through **WorkManager** (battery- and
  thermal-constrained, reusing the `BackgroundTaskTray`), never on the UI thread.
- **Rate limiting:** >= 1.1s between requests per provider (matches the existing artwork
  pipeline; RA asks callers to be gentle). Batch per-game where the API allows.
- **Badge art:** cached through **Coil** (existing disk + memory cache), same as artwork. Coin
  medallion frames are bundled vector/asset, not network.
- **Coin-weighted math** is integer arithmetic over cached counts — O(coins) on sync only, not
  per-recomposition. Player card reads the cached wallet aggregate.
- **Compose hygiene:** stable keys in coin lists, `remember`ed derived state, tier grouping
  precomputed in the ViewModel (not in composition).

---

## 6. Security requirements (apply to every step)

- **Never handle a password or run an OAuth/redirect flow.** Identity is a pasted id; secrets
  are user-provided read-only API keys.
- **Encrypt both API keys at rest** with `KeystoreSecretCipher` (AES-256/GCM, hardware-backed),
  exactly as `SgdbApiKeyProvider` does. Never store plaintext keys.
- **Redact keys from logs** via `LogRedaction` / the Timber file tree; add unit coverage like
  `RedactSecretsTest`.
- **Keys in transit:** HTTPS only; keys in the `Authorization` header (RA) or query for Steam
  — never logged, never placed in error messages surfaced to the UI.
- **Backup/restore:** the two keys are device-bound. On restore, use
  `KeystoreSecretCipher.isUsableOnThisDevice()` to drop un-decryptable keys and re-prompt —
  same handling the SGDB key needs. SteamID64 and RA username may restore normally.
- **Input validation:** validate/normalize pasted SteamID64 and vanity names before any
  request; never build requests from untrusted responses.
- **Least data:** store only what the coins UI needs. No compiling of cross-service personal
  data.

---

## 7. Phased implementation

Each phase lists its tasks plus an **Opt** (optimization) and **Sec** (security) note, per the
standing requirement to keep both in mind at every step. Tests ship with each module
(house rule: MockK + Turbine for logic).

### Phase 1 — Domain core (no Android) — DONE
- [x] `ShibaTier` (weights + `forRarity`), `AchievementProvider`.
- [x] `ShibaLevel` banded curve (`coinsForLevel` / `levelForCoins` / `progress`) + `ShibaRank` /
  `rankFor`.
- [x] `CoinCounts` / `GameCoins` / `CoinWallet` — coin-weighted progress + wallet aggregation.
- [x] Unit tests (`ShibaTierTest`, `ShibaLevelTest`, `ShibaCoinsTest`), passing.
- Files: `core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/achievement/`.
- Opt: pure integer math, table-driven bands, closed-form level lookup (no per-level loop growth).
- Sec: no secrets in this layer; dependency-free and trivially testable.

### Phase 2 — Persistence — DONE
- [x] `AchievementSetEntity` (per game/provider summary + sync metadata), `AchievementEntity`
  (per coin). `ShibaWalletEntity` intentionally skipped — the wallet is derived via a SQL
  aggregate (see below), matching the "start derived" open decision.
- [x] `AchievementSetDao` / `AchievementDao` + `MIGRATION_29_30` (DB v29 -> v30), registered in
  `DatabaseModule`. No destructive fallback; additive tables, cascade-delete with the game.
- [x] DataStore keys (`ra_username`, `ra_api_key`, `steam_id64`, `steam_api_key`,
  `achievements_enabled`, `achievements_sync_last`) + `AchievementCredentialsProvider`
  (`core-data/achievement/`), mirroring `SgdbApiKeyProvider`.
- [x] Tests: `AchievementDaoTest` (wallet aggregate, cascade, per-game reads),
  `AchievementCredentialsProviderTest` (round-trip + clear). Passing.
- Opt: composite PK (game_id, provider) indexes the FK; Flow reads; wallet is a single SQL
  `SUM` over the summary rows (`observeWalletCoins`) — O(games), no per-coin scan.
- Sec: both API keys encrypted via `KeystoreSecretCipher`; identities stored plain; keys never
  logged. Log redaction of the Steam `key=` param moves to Phase 3, where request logging lands
  (the RA `Authorization` header is already covered by `LogRedaction`).

### Phase 3 — Provider clients + repository
- [ ] `RetroAchievementsApi` + ROM-hash resolution into `RomScanner`.
- [ ] `SteamAchievementsApi` (schema + player + global % + vanity resolve).
- [ ] `AchievementRepository` (fetch -> map -> tier -> upsert -> recompute wallet).
- Opt: offline-first; >=1.1s rate limit; batch per-game; Coil for badge art.
- Sec: HTTPS only; keys only in headers/query, never logged; handle "profile not public" and
  missing-key states as first-class UI results, not thrown exceptions with key context.

### Phase 4 — Settings entry (connect accounts)
- [ ] Achievements settings screen: RA username + key, Steam id + key, master toggle, "Sync
  now", public-profile hint.
- [ ] Wire into `BackupManager` (settings + entities, device-bound key handling on restore).
- Opt: validate/resolve vanity once and cache SteamID64.
- Sec: key fields are write-only in the UI (show masked); never echo stored keys back;
  `isUsableOnThisDevice` on restore.

### Phase 5 — Game Detail glance strip
- [ ] Coin summary strip as a new section composable in the `GameDetailScreen` scroll Column
  (progress, tally, locked crown, "Open" door), added to the `state.mainFocus` sequence.
- [ ] `View Shiba coins` item added to the game context menu — one `XMBContextMenuItem` in the
  `XMBViewModel` game-menu builder (see 4.5 and DESIGN.md 3.22); activation sets
  `activeShibaCoinsGameId`.
- Opt: renders from cached Room only; throttled sync-on-open.
- Sec: no network on first paint; sync is user-visible via the task tray.

### Phase 6 — Dedicated Shiba Coins screen
- [ ] Shell-level overlay `activeShibaCoinsGameId?.let { ShibaCoinsScreen(...) }` in `XMBShell`,
  parallel to `GameDetailScreen` (see 4.5) — reachable from both the context menu and the GDS
  strip. Reuse `DetailBreadcrumb` + the translucent theme-gradient backdrop; own
  `ShibaCoinsViewModel` with focus + `pendingGamepadAction` plumbing. Add to the wave-freeze
  condition (~XMBShell:354).
- [ ] Summary header + crown banner + full coin list: Tier-list / Earned / Rarest sorts,
  All / Earned / Locked filters, hidden-coin teases, badge art, rarity %, dates.
- [ ] Accent-driven chrome via `menuCursorFill()/menuCursorEdge()`; fixed tier metals;
  provider source badge.
- Opt: precompute tier groups in the ViewModel; stable `LazyColumn` keys.
- Sec: hidden coins keep descriptions redacted until earned.

### Phase 7 — Level / partial-credit engine wired
- [ ] Bank coins into the wallet on each unlock at sync time; cascade-on-first-sync reveal.
- [ ] Player card fragment (level, rank, wallet) for reuse in the hub.
- Opt: incremental wallet update on sync deltas, not full recompute where possible.
- Sec: n/a (local math).

### Phase 8 — Category hub (XMB column)
- [ ] Dedicated XMB category: cross-library aggregate, player card, library lenses (closest
  to mastery, rarest earned). Crowned-coin category icon.
- Opt: aggregate reads from cached wallet + counts; virtualized game list.
- Sec: n/a beyond the above.

### Phase 9 — Polish
- [ ] Ultra-rare foil treatment; locked/hidden visual states finalized against themes.
- [ ] Optional per-provider point display (RA native points alongside the unified wallet).
- Opt: asset-level (vector medallions, `filterQuality` for any raster fallback).

---

## 8. Non-goals (for now)
- No hosted proxy and no PFP-owned Steam/RA key — user-supplied keys only.
- No password/OAuth login flow.
- No writing to either service (read-only display and local tracking only).
- No milestone-unlocked themes or friend-compare — revisit post-v1.
- No background polling for new unlocks — sync is user-initiated or launch/open-triggered.

---

## 9. Open decisions
- **Softcore vs hardcore Platinum (RA):** does softcore mastery earn the crown, or only
  hardcore? RA draws a hard line; pick one before Phase 3.
- **Wallet: derived-only vs cached aggregate row** — start derived; add `ShibaWalletEntity`
  only if the player card shows measurable read cost.
- **Category vs XMBItem for the hub** — leaning category (its own column) as the aggregate
  home; the per-game surfaces ship first regardless.

---

*Play Field Portal · Internal Plan · Shiba Coins Achievement System*
