# Shiba Coins — RetroAchievements & Steam Achievement Integration

Plan for a unified, cross-provider achievement system rendered as **Shiba Coins**: a
PlayStation-style Bronze / Silver / Gold / Platinum tier set that players earn, track, and
display across RetroAchievements (emulated titles) and Steam (PC titles), with a single XP
economy and level. No Steam or RetroAchievements password is ever handled.

Status: Phases 1-8 done and validated on-device (RA connected, real coins synced; Shiba Coins XMB
column seeds on the Thor). Auto-match shipped (cartridge RA ROM-hash + NDS + disc ISO9660 hash +
Steam ladder + unmatched report + edit; RA is hash-only). Player card + category hub + batch "sync
all" live. Remaining: backup key handling, Phase 9 polish (inline card header, foil states).
Branch: `achievement-integration`. This document is the source of truth to resume from.

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

### 2.2 Tiers (difficulty-based, per provider)
Bronze / Silver / Gold come from a difficulty signal that differs by provider, because RA and Steam
expose different things:

- **RetroAchievements — by RA point value** (`ShibaTier.forRaPoints`). RA already weights each
  achievement by difficulty (standard values 0-5 / 10 / 25 / 50 / 100), so the points are the natural
  tier spine there:

  | Shiba tier | RA points |
  |---|---|
  | Bronze | < 10 (0-5) |
  | Silver | 10-49 (10, 25) |
  | Gold | >= 50 (50, 100) |

- **Steam — by global unlock rarity** (`ShibaTier.forRarity`), since Steam has no points. Calibrated
  against real PlayStation trophy data (sampled 17 AAA titles, 1,472 non-platinum trophies) to
  reproduce PlayStation's ~71 / 23 / 6 split:

  | Shiba tier | Global unlock rarity |
  |---|---|
  | Bronze | >= 20% |
  | Silver | 5-20% |
  | Gold | < 5% |

- **Platinum = mastery** (both providers). Crown is earned only at 100% of the set (RA "Mastery" /
  Steam 100%, hardcore for RA — see §9). It is not a per-achievement tier; it is the meta-award.
  Locked and shown as a banner until 100%.
- RA still records each coin's global unlock rarity for display on the coins screen; it just no longer
  drives the RA tier. Sub-1-2% coins get a foil/shine flourish rather than a fifth tier.
- Existing synced RA sets keep their old rarity-based tiers until re-synced; tiers recompute from
  points on the next sync.

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

### Phase 3 — Provider clients + repository — IN PROGRESS
New module `feature-achievements` (clients + repository; UI lands in later phases).
- [x] `SteamAchievementsApi` (schema + global % + player, plus `resolveVanity`) and
  `RetroAchievementsApi` (`GetGameInfoAndUserProgress`), each mapping to `SyncedCoin` with the tier
  derived from rarity. Rate-limited (`RateLimiter`, >=1.1s). `ProviderSyncResult` sealed type:
  `Success` / `MissingCredentials` / `ProfileNotPublic` / `NotFound` / `Failed`.
- [x] `AchievementNetworkModule` — Ktor client with **no logging plugin** (keys live in query
  params, so redaction-by-omission is stronger than scrubbing; supersedes the earlier "add the
  Steam `key=` param to `LogRedaction`" note).
- [x] MockEngine tests for both clients (parse, tier, earned, private-profile, missing-key).
- [x] `AchievementRepository` — offline-first Flows (`observeGameCoins`, `observeCoins`,
  `observeWallet`) straight from Room; `syncGame(gameId, provider, providerGameId)` maps a fetch
  to the per-coin rows + summary (pruning dropped coins), leaving the DB untouched on any
  non-success. Wallet derives reactively from the summaries. Tested with MockK doubles.
- [x] Provider-id resolution (link layer): persistent `provider_game_links` table (DB v30 -> v31)
  + repository `linkManually` / `resolveSteamLink` / `syncGameById`, so a stored link drives
  end-to-end sync. `ProviderSyncResult.NotLinked` added. Tested (resolver + repo link/sync).
- [x] Steam resolution ladder (most -> least reliable): (1) the appid the shortcut already carries —
  `SteamShortcut` parses GameNative intents (`i.app_id` + `game_source=STEAM`) and
  `steam://rungameid/<n>` URIs; (2) `SteamGridDbApi.getSteamAppId` (SGDB `platformdata=steam`, a
  hint, parsed tolerantly) when the game has an SGDB id; (3) `SteamAppListResolver` title match; (4)
  a manual "Find on Steam" picker on the coins screen (`SteamAppListResolver.search` → ranked
  candidates → link by appid). The auto-matcher walks 1→2→3; tier 4 is the manual escape hatch.
- [x] Auto-match (cartridge-first): `RaRomHasher` (full-MD5 + NES/SNES header strip + N64 byte-order
  normalization so z64/v64/n64 hash the same; Nintendo DS header+ARM9+ARM7+icon) + `RaConsole` id
  map + `RetroAchievementsApi.gameIdForHash` (per-console hash list, cached). `RomBytesReader` (raw
  path / SAF / single-entry zip, 256 MB cap). `AchievementAutoMatcher` batch-links unlinked games —
  RA by hash, Steam by title — and returns a `MatchReport`. Settings ▸ Shiba Coins has an
  "Auto-match games" action with progress + an unmatched report; the coins screen has a "Change
  match" edit. Tested (hasher, lookup, matcher).
- [x] Disc-system RA hashing: `DiscImage` (a seeking ISO9660 reader — cooked 2048 `.iso` and raw
  2352 `.bin` Mode 1/2, detected from the CD001 signature; never loads the multi-GB image) +
  `RaDiscHasher` (PSX/PS2: SYSTEM.CNF `BOOT`/`BOOT2` → exe name + contents, PS1 sized from its PS-X
  EXE header; PSP: `PARAM.SFO` + `EBOOT.BIN`), transcribed from rcheevos hash_disc.c. `DiscImageOpener`
  opens raw paths (following `.cue` → `.bin`) or SAF fds. `RaConsole` maps the disc consoles so the
  hash runs. Verified byte-for-byte against RA's live DB on the real library: PS2 GTA:SA (USA v1.03)
  `fe8b1b6c…` and PSX Parasite Eve II (USA D1) `813cc94b…` both matched exactly; PSP shares the same
  proven path (no RA set in this library to cross-check).
- [x] Remaining disc consoles hashed (Sega CD, Saturn, GameCube, Wii). `RaSegaDiscHasher` MD5s the
  512-byte boot header of sector 0 (Sega CD / Saturn share it, differ only by magic), reading via a
  new non-ISO9660 `DiscImage.openRawCd` that detects the sector layout from the raw sync pattern.
  `RaNintendoDiscHasher` hashes GameCube (`rc_hash_gamecube`) and Wii (`rc_hash_wii`, incl. WiiWare
  `.wad`) at raw byte offsets over a seekable image — no ISO9660 walk and, crucially, NO decryption:
  RA hashes a retail Wii disc's encrypted clusters verbatim, so no console key is ever needed
  (`DiscImageOpener.openRawSource`). Transcribed from rcheevos `hash_disc.c`; structural unit tests
  lock the byte layout, but live-DB cross-checks against real GC/Wii/Sega images are still pending
  (as PS2/PSX were verified on-device). Compressed containers (NKit/RVZ/CISO/WBFS/CHD) aren't
  expanded and stay untracked.
- [x] Dreamcast hashing (`RaDreamcastHasher`, `rc_hash_dreamcast`): MD5s IP.BIN (256 bytes) + the boot
  executable's contents. Handles GD-ROM multi-track (GDI) addressing — `DiscImage` gained a
  `SectorSource` seam + a `firstTrackSector` base (the single-file path is unchanged and still guarded
  by `RaDiscHasherTest`), and `GdiTrackSource` routes absolute logical sectors to the owning track
  file (per-track base LBA, 2048/2352 layout, byte offset). `DiscImageOpener.openGdi` parses the
  `.gdi` index, opens each track file, and anchors the disc at the IP.BIN track (track 3, else the
  first data track). Filesystem-only (SAF can't resolve sibling track files). Unit-tested via a
  GDI at LBA 45000 (in-memory tracks + an end-to-end `.gdi` parse); live-DB cross-check still pending.
  Now every RA-mapped console the app supports has a content hasher.
- [x] CHD (`.chd`) container support — zlib + LZMA DONE (structurally tested). CHD is a *compressed
  container*: its hunks decompress to the exact logical sectors of the uncompressed disc, so RA's hash
  of a `.chd` equals the equivalent BIN/GDI (confirmed against Flycast, which uses libchdr + stock
  rcheevos `rc_hash` over a custom cdreader). One CHD reader therefore lights up hashing for the CD
  disc consoles at once (PSX/PS2/Saturn/Sega CD), via the existing `DiscImage.SectorSource` seam.
  Transcribed from libchdr, unit-tested: `ChdBitReader` (MSB-first bitstream), `ChdHuffman` (canonical
  Huffman decoder), `ChdReader` (v5 header + hunk map [uncompressed + Huffman-coded compressed] + the
  hunk codecs: `cdzl`/zlib raw-inflate and `cdlz`/LZMA — the CD-frame de-interleave is shared; ECC/sync
  regen is skipped since only the 2048-byte user data is read), `ChdSectorSource` (CHTR/CHT2 track parse
  → LBA→FAD→frame mapping, `fad = lba + 150`, per-track 4-frame padding, per-type user-data offset),
  and wiring (`DiscImageOpener.openChd` + a `.chd` interception in `AchievementAutoMatcher.attemptHash`
  → `hashCdDiscImage`). LZMA is decoded via `org.tukaani:xz` as raw LZMA1 (props 0x5D, dict = the
  output size — no `.lzma` header, no 256 MB dict). This covers the two codecs chdman actually mixes
  for CD (`cdlz`+`cdzl`); FLAC/ZSTD-only hunks are declined cleanly.
- [x] Dreamcast GD-ROM CHD (the format Flycast's CHD support targets). `ChdSectorSource` now parses
  the GD-ROM track metadata (`CHGD`/`CHGT`, alongside CD `CHT2`/`CHTR`) and anchors the ISO9660 at the
  high-density data track (track 3, StartFAD 45150 → LBA 45000) via a new `firstTrackSector` it exposes;
  the `fad = lba + 150` mapping then resolves GD-ROM LBAs to CHD frames (chd_frame = lba). Dreamcast
  `.chd` routes through `RaDreamcastHasher`. Verified structurally on the real library (Dino Crisis /
  Soulcalibur / Power Stone GD-ROM CHDs all produce hashes — IP.BIN located, boot exe read via ISO9660,
  `cdlz`/LZMA decoded); on-device RA-DB match pending a re-run of auto-match. Out of scope: Wii/GC CHD
  (raw DVD, not CD frames). Live-DB verification via the RaHashVerification harness as for the others.
- [ ] Live-DB verification of the new disc hashers (GC / Wii / Sega CD / Saturn / Dreamcast) against
  real dumps — the remaining confidence gate. Tooling is in place: `RaHashVerification` (a normally
  inert unit test in `feature-achievements`) runs the real production hashers over real files and
  diffs the result against a known-good hash. Workflow: for each system, get one real dump, obtain its
  true hash from rcheevos `rhasher <system> <file>` (or RetroArch's load log, or the game's "Supported
  Game Files" list on retroachievements.org), then run:
  ```
  RA_HASH_MANIFEST=/path/manifest.txt ./gradlew :feature:feature-achievements:testDebugUnitTest \
      --tests '*RaHashVerification' --rerun
  ```
  The manifest is one entry per line: `platformId | path/to/file | expectedHash` (expected optional).
  With expected hashes present, a green run == verified; red == mismatch (report at
  `build/ra-hash-verification.txt`). `-Dra.hash.*` also works (forwarded in the module's build script).
  Point it at raw images — compressed containers (NKit/RVZ/CISO/WBFS/CHD) aren't expanded.
- [x] RetroAchievements is HASH-ONLY. A game links solely by its ROM/disc content hash
  (`gameIdForHash`); there is no title fallback and no manual/user-provided RA linking. If a ROM's
  hash isn't a registered RA hash, the game stays untracked (with a recorded reason). The coins
  screen shows a hash-only explanation instead of a link field for RA games, and "Change match" is
  Steam-only. (An earlier title fallback was removed at the user's direction — hash identification is
  what actually makes RA tracking correct; a title can link the wrong region's game entry.)
- Opt: offline-first; >=1.1s rate limit; Coil for badge art (in the UI phases).
- Sec: HTTPS only; read-only; keys never logged (no request logging at all); "profile not public"
  and missing-key are first-class results, never exceptions carrying a key.

### Phase 4 — Settings entry (connect accounts) — MOSTLY DONE
- [x] `AchievementsSettingsScreen` + `AchievementsSettingsViewModel`: master toggle, RA username +
  Web API key, Steam SteamID64/vanity + API key, per-provider connect/disconnect, last-synced,
  public-profile hint. Routed via `settings_achievements` (SettingsNavHost + the XMB settings
  menu). Keys are write-only/masked; the vanity name is resolved to a SteamID64 once and cached.
  Added `clearRetroAchievements()` / `clearSteam()` to the credential provider. VM-tested.
- [ ] `BackupManager` device-bound handling for the two new keys on restore (confirm the existing
  settings.json export already carries them; drop un-decryptable keys via `isUsableOnThisDevice`).
- [x] "Sync all coins" action — `AchievementRepository.syncAllLinked` iterates every provider link,
  re-fetches each (clients self-rate-limit), and tallies synced / no-coins / failed with a
  missing-credentials flag; per-game failures are counted, never thrown. Settings ▸ Shiba Coins ▸
  Sync shows it with live progress + a dismissable result summary. Repo-tested.
- Opt: vanity resolved once, SteamID64 cached; no per-key network on screen open.
- Sec: keys write-only, never echoed back; no request logging; per-provider disconnect.

### Phase 5 — Game Detail glance strip — MOSTLY DONE
- [x] `ShibaCoinStrip` in the `GameDetailScreen` scroll Column: coin-weighted progress, the
  Bronze/Silver/Gold tally, and the Platinum crown (lit only on mastery); a quiet "not tracked
  yet" state when the game has no coins. `GameDetailViewModel` streams `observeGameCoins(id)` into
  state (offline-first). Accent-driven chrome (`menuCursorEdge`); tier metals fixed.
- [ ] The "Open" drill-in door + `state.mainFocus` entry, and the `View Shiba coins` context-menu
  item — deferred to Phase 6, since both need the dedicated coins screen (`activeShibaCoinsGameId`)
  as their destination.
- Note: until Phase 6 adds link/sync UI (or a debug seed), the strip shows the "not tracked"
  state for every game — there is no in-app path to populate coin data yet.
- Opt: renders from cached Room only, no network on paint.
- Sec: display-only; no request on open.

### Phase 6 — Dedicated Shiba Coins screen — DONE
- [x] `ShibaCoinsScreen` + `ShibaCoinsViewModel`: `DetailBreadcrumb`, summary header + crown
  banner, sort (Tier/Earned/Rarest) + filter (All/Earned/Locked) chips, per-coin rows (tier metal,
  name, description, rarity %, date), hidden-coin redaction, and the **link + sync UI** (paste RA
  game id / Steam appid, or "Match by title" for Steam) that finally populates coin data.
  `arrange()` sort/filter is pure and unit-tested. Accent-driven chrome (`menuCursorFill/Edge`);
  fixed tier metals; theme-gradient backdrop.
- [x] Shell wiring: `activeShibaCoinsGameId` on `XMBUiState`, `onCloseShibaCoins` in
  `XMBViewModel`, render block in `XMBShell` (parallel to `GameDetailScreen`), added to
  `hasBlockingOverlay` + the wave-freeze condition, and a gamepad routing branch (touch-navigable
  overlay; BACK closes it on a controller).
- [x] `View Shiba coins` context-menu item on the game menu opens the overlay — the primary entry
  point. The GDS glance-strip "Open" door is a secondary path, still deferred (minor).
- Opt: sort/filter computed once via `remember`; stable `LazyColumn` keys.
- Sec: hidden coins stay redacted until earned; sync errors are first-class messages.

The feature now runs end to end: connect accounts (Phase 4) → game context menu ▸ View Shiba
Coins → link + sync → coins persist → the dedicated screen lists them and the glance strip
(Phase 5) lights up; the wallet derives reactively.

### Phase 7 — Level / partial-credit engine wired — MOSTLY DONE
- [x] Banking is reactive, not a discrete step: `AchievementSetDao.observeWalletCoins()` sums each
  set's earned coins (weighted) + the Platinum once mastered, so the wallet updates itself on every
  sync — no recompute-on-unlock code path to maintain. `AchievementRepository.observeWallet()`
  exposes it as a `CoinWallet` (level + rank + `LevelProgress`).
- [x] `ShibaPlayerCard` (core-ui, reusable): level medallion, rank title (`ShibaRank.label`), a
  level-progress bar, and the running coin total, all derived from a `CoinWallet`. Presentation-only
  and accent-driven so the Phase 8 hub reuses it as-is. Surfaced now at the top of the connect-
  accounts screen (`AchievementsSettingsViewModel` folds the account flows so the wallet flow fits
  `combine`).
- [ ] Cascade-on-first-sync reveal (the animated coin-bank flourish) — deferred to Phase 9 polish.
- Opt: wallet summed in SQL over summary rows (no per-coin load); card reads the derived wallet only.
- Sec: n/a (local math).

### Phase 8 — Category hub (XMB column) — MOSTLY DONE
- [x] Dedicated "Shiba Coins" built-in category (id `achievements`, `ic_achievements` trophy vector
  glyph, seeded at position 8 via the idempotent reconcile so it reaches existing installs without a
  migration; protected from deletion, reorderable). Present in every build (unlike Social).
- [x] `LibraryStanding` cross-library aggregate (wallet + tracked standings + rarest earned) via
  `AchievementRepository.observeLibraryStanding`, offline from cached rows.
- [x] Hub root shows a summary row (a tinted level circle "Lv 27" + rank + coins/tracked/mastered)
  plus lens rows with per-row glyphs (coin, diamond, help). Rarest Earned drills inline
  (`AchievementsNav.RarestEarned`); the summary opens Settings ▸ Shiba Coins. Closest to Mastery was
  removed (redundant once All Tracked shows progress).
- [x] All Tracked & Untracked are FULLSCREEN two-pane overlays (`ShibaLibraryScreen` /
  `ShibaLibraryViewModel`, `activeShibaLibrary` on the shell): a scrolling master list (box art,
  title, platform, progress bar, Bronze/Silver/Gold/Platinum tally) beside a detail panel (logo,
  progress %, Shiba Coins breakdown, Total Coin Score + next-reward bar). Untracked shows each game's
  reason instead of coins. Wired like the coins overlay (blocking, gamepad dispatch, wave-freeze,
  XMB-hide). Coins render as tier-colored discs (no medallion art bundled yet).
- [x] "Untracked" lens: every game with no achievement link, each with the reason it isn't tracked.
  The auto-matcher records the *specific* failure per game (DB v32 `achievement_match_notes`,
  rewritten each run) — e.g. "Couldn't read the ROM file", "Unsupported disc image (NKit/CHD/…) —
  can't hash", "Couldn't find the disc's boot executable", "ROM hash isn't registered on
  RetroAchievements", "RetroAchievements has no achievements for Xbox 360". The hub prefers that
  recorded note, falling back to a platform guess for games not yet auto-matched. Notes clear when a
  game gets linked (auto or manual). Opening a row goes to Game Detail to link by hand.
- [x] RA title matching folds accents (NFD + drop combining marks), so "Pokémon" and "Pokemon"
  match — RA titles carry accents, scraped titles often don't.
- [ ] Inline player-card header (rendered as a summary row for now; the rich card lives in settings)
  — deferred to Phase 9 polish.
- Note: lenses reflect *synced* sets, not merely linked games — a game populates once its set is
  synced (from its coins screen). A batch "sync all" stays deferred (see Phase 4).
- Opt: standing derived from cached rows (wallet SUM + small joins); game rows matched from the
  already-observed games list. Sec: display-only; no network on hub paint.

### Phase 9 — Polish
- [ ] Ultra-rare foil treatment; locked/hidden visual states finalized against themes.
- [ ] Optional per-provider point display (RA native points alongside the unified wallet).
- Opt: asset-level (vector medallions, `filterQuality` for any raster fallback).

### Phase 10 — Player Status view — DONE (July 2026)
- [x] Fullscreen account-wide status view (`PlayerStatusScreen` + `PlayerStatusViewModel`), opened
  from the XMB player-card row and from the Settings player card (which hides Settings while open;
  Back restores it). Layout: breadcrumb, full-width level/rank/XP panel, then Recent Achievements
  (left) beside the Shiba Coin Wallet and Rarest Achievement Unlocked (right).
- [x] Terminology (user decision): "XP" = the weighted score economy (level curve, totals);
  "coins" = achievement counts (earned / available, per-tier tallies). Rank line reads
  `<Rank> • <bones> [bone glyph]`; the glyph is the bone emoji flattened to a flat tint
  (`BoneGlyph`, core-ui).
- [x] Data: `AccountAchievementDao.observeRecentEarned(limit)` (parameterized, newest unlock
  first, timestamped unlocks only) -> `AchievementController.observeRecentCoins`;
  `LibraryStanding.walletCounts` derives the per-tier earned tally (Platinum = mastered count).
- [x] Controller navigation (D-pad only): UP/DOWN move through the Recent list, RIGHT jumps to
  the Rarest card, LEFT returns; scroll-to-focus via `BringIntoViewRequester` (first row returns
  to absolute top so the header stays reachable). Recent/rarest rows with a library game open its
  Shiba Coins overlay.
- [x] Debug: `ShibaStandingSeeder` (debug source set) seeds a deterministic fake standing per
  rank from the debug menu, for exercising the view at any rank/Bone count.

---

## 8. Non-goals (for now)
- No hosted proxy and no PFP-owned Steam/RA key — user-supplied keys only.
- No password/OAuth login flow.
- No writing to either service (read-only display and local tracking only).
- No milestone-unlocked themes or friend-compare — revisit post-v1.
- No background polling for new unlocks — sync is user-initiated or launch/open-triggered.

---

## 9. Open decisions
- **Softcore vs hardcore Platinum (RA):** DECIDED — **hardcore only, crown-scoped**. Individual
  coins bank into the Shiba Level the instant they unlock (softcore or hardcore); the Platinum is a
  meta-award, minted once every *other* coin in the set is earned, and it drops a 300-XP bonus into
  the wallet on top of the individual coins. For RetroAchievements that "every other coin earned"
  test is **hardcore** — a softcore 100% banks all its coin XP but earns neither the crown nor the
  bonus. Steam has no softcore/hardcore split, so there mastery is just 100% of the set. Implemented
  via `SyncedCoin.earnedHardcore` (RA = hardcore timestamp present; Steam = `isEarned`), with the
  crown computed in `AchievementRepository.summaryOf` and carried on `GameCoins.isMastered` (stored,
  not re-derived from counts). Existing sets correct their crown on the next sync.
- **Wallet: derived-only vs cached aggregate row** — start derived; add `ShibaWalletEntity`
  only if the player card shows measurable read cost.
- **Category vs XMBItem for the hub** — leaning category (its own column) as the aggregate
  home; the per-game surfaces ship first regardless.

---

*Play Field Portal · Internal Plan · Shiba Coins Achievement System*
