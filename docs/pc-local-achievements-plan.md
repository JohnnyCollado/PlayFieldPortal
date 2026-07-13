# PC Local (Emulator) Achievements — Integration Plan

Bring Achievement-Watcher-style tracking into PlayFieldPortal: read the achievement-state files
that Steam emulators (Goldberg, CODEX, ALI213, SmartSteamEmu, SKIDROW, …) write to disk for PC
games, and surface them as Shiba Coins. Reference: https://github.com/xan105/Achievement-Watcher

Status: **AWAITING APPROVAL — no code until approved.** Section 8 is the concrete, buildable spec for
Phases 1 + 2 (schema provider + parsers) to review. This does **not** crack, patch, or bypass
anything — it is read-only parsing of local state files an emulator already wrote, plus public Steam
**schema** metadata. It is the offline counterpart to the existing Steam Web-API source.

---

## 1. The key insight — this is a new *source*, not a new system

These are still **Steam** achievements: the same `apiName`s the Steam Web API knows. So the local
files supply only the **unlock state**; everything else reuses the Shiba Coins pipeline:

| Piece | Web-API Steam (owned games) | Local emulator (cracked/offline) |
| --- | --- | --- |
| Definitions (name, desc, icons, hidden) | `GetSchemaForGame` | **same** `GetSchemaForGame` |
| Global rarity → tier | `GetGlobalAchievementPercentagesForApp` | **same** |
| Unlock state (earned + when) | `GetPlayerAchievements` | **local state files** |

For a cracked game the player-achievements call returns nothing (the game isn't on the account), so
the local files replace *only* that call. The mapped `apiName`s line up with the schema, so a synced
set looks identical to a Web-API set: `SyncedCoin(providerAchievementId = apiName, tier = forRarity,
iconUrl, isEarned, earnedAt)` → the existing `AchievementRepository` persistence, `GameCoins`,
wallet, hub, and glance strip all work unchanged.

**Consequence:** metadata still needs the user's Steam Web API key (already stored, encrypted, in
`AchievementCredentialsProvider`). Achievement-Watcher's old cached metadata server is dead, so there
is no key-free path — we document this and reuse the existing key.

---

## 2. Module shape (fits the existing `feature-achievements`)

Add a `local/` package rather than a whole new module, so it shares the repository, credentials, and
`SteamAchievementsApi`:

```
feature-achievements/local/
├── LocalAchievementSource.kt      // orchestrates: schema + local state → List<SyncedCoin>
├── EmulatorFolder.kt              // a persisted SAF root: uri, format, last scan, timestamps
├── LocalAchievementScanner.kt     // walks SAF roots, finds <appid> dirs + state files
├── AchievementStateParser.kt      // interface (below)
├── SteamSchemaProvider.kt         // GetSchemaForGame + global % (cached in Room)
└── parser/
    ├── GoldbergParser.kt          // achievements.json  (JSON)
    ├── CodexParser.kt             // achievements.ini    (INI, [ACH]/Achieved/UnlockTime)
    ├── Ali213Parser.kt            // Achievements.ini     (INI, HaveAchieved/HaveAchievedTime)
    ├── SmartSteamEmuParser.kt     // stats / achievements (INI; SSE .bin is a later maybe)
    └── GenericIniParser.kt        // best-effort fallback
```

Common parser interface (as proposed), format-independent output:

```kotlin
interface AchievementStateParser {
    val format: EmulatorFormat
    fun canParse(files: List<DocumentFile>): Boolean
    suspend fun parse(appId: Long, files: List<DocumentFile>): List<ImportedAchievementState>
}

data class ImportedAchievementState(
    val apiName: String,
    val unlocked: Boolean,
    val unlockTimeEpochSeconds: Long?,
    val currentProgress: Int? = null,
    val maximumProgress: Int? = null,
)
```

### Known formats (transcribe exact quirks from the emulator/AW source at build time)
- **Goldberg** — `…/Goldberg SteamEmu Saves/<appid>/achievements.json`; JSON keyed by apiname:
  `{ "ACH_X": { "earned": true, "earned_time": 1609459200 } }`. The de-facto default now.
- **CODEX / RUNE / friends** — `…/Steam/CODEX/<appid>/achievements.ini`; INI, one `[ACH_X]` section
  each with `Achieved=1` and `UnlockTime=<epoch>`.
- **ALI213** — `…/ALI213/<appid>/Stats/achievements.ini`; `[ACH_X]` with `HaveAchieved=1`,
  `HaveAchievedTime=<epoch>` (also a flat `[Achievements] ACH_X=1` variant).
- **SmartSteamEmu** — INI achievements alongside `stats`; the binary `.bin` stats form is deferred.
- **GenericIniParser** — any INI with per-achievement sections carrying an achieved flag +
  optional unlock time; the safety net for the long tail (3DM, SKIDROW, Reloaded, …).

---

## 3. User-selected folders (SAF)

Android scoped storage blocks free scanning of another app's storage, so the user grants folders via
SAF (`OpenDocumentTree`) and we persist the URI permission (`takePersistableUriPermission`).

Settings ▸ Shiba Coins ▸ **PC Achievements**:
```
Enable PC achievements            [toggle]
Add achievement-data folder       → SAF picker
  • <folder>  format: Goldberg  games: 14  last scan: …
Scan now
Show unlock notifications         [toggle]
```

Each `EmulatorFolder` persists: SAF uri, detected format, and per-`<appid>` last-known file
timestamps (so a scan only re-parses changed files). A scan enumerates the root's `<appid>`
subdirectories; each maps to a PFP `windows` game via the Steam **appid** already stored in
`provider_game_links` (the Steam ladder we built). Unmapped appids are reported so the user can link
them.

---

## 4. Monitoring while a PC game runs

For near-real-time popups, start a foreground `AchievementMonitorService` when PFP launches a PC
game, poll only that game's known state file(s) every ~1–2 s (SAF has no reliable recursive watch),
diff against Room, and post a popup on each newly-unlocked achievement. Stop when the game closes.

```
launch PC game → start service → poll known state file(s) → diff vs Room
    → new unlock → PFP popup (+ bank coins) → stop on game close
```

Outside gameplay, a WorkManager job does occasional reconciliation scans of all folders.

Open question — detecting "game running / closed": PFP launches PC titles via GameNative / a
front-end intent; we need a reliable close signal (process/foreground check, or a timeout after the
launch intent returns). Prototype with a bounded poll + manual "Scan now" first; add the live service
once the close signal is solid.

---

## 5. Persistence

- Reuse `achievements` / `achievement_sets` (provider = `STEAM`) — a local-sourced set is
  indistinguishable downstream. Add a `source` marker on the set (`WEB_API` | `LOCAL`) only if the UI
  needs to badge it.
- New `SteamSchemaCache` table (appid → definitions + global %, TTL) so schema is fetched once.
- New `emulator_folders` table (SAF uri, format, enabled, last_scan) + per-appid file-timestamp map.

---

## 6. Phasing (each shippable + testable)

1. **Schema provider** — `SteamSchemaProvider` (GetSchemaForGame + global %) cached in Room; unit
   tested with MockEngine. No UI. Foundation for tiers/icons of unowned games.
2. **Parsers** — the `AchievementStateParser` set + `ImportedAchievementState`, pure and unit-tested
   against sample Goldberg/CODEX/ALI213 fixtures. No Android.
3. **Local source + manual scan** — `LocalAchievementScanner` + `LocalAchievementSource` combining
   schema + parsed state into `SyncedCoin`s → `AchievementRepository`. SAF folder picker + "Scan now"
   in Settings. This already makes cracked-game coins show up in the hub.
4. **Notifications** — popup on newly-unlocked (reuses `AchievementNotificationManager`-style path).
5. **Live monitor service** — foreground poll during gameplay, once the game-close signal is solid.
6. **Polish** — per-emulator auto-detect, unmapped-appid report, WorkManager reconciliation.

## 7. Non-goals
- No cracking, patching, DRM bypass, or distributing anything — read-only local parsing only.
- No SSE binary `.bin` decoding initially (INI paths first).
- No key-free metadata (the AW cache server is gone; the user's Steam Web API key is required).

---

## 8. Phase 1 + 2 — Detailed spec (for approval)

Both phases are **pure / testable** (no UI, no Android framework beyond Room in Phase 1's cache).
They add the two ingredients the local source later combines: metadata (Phase 1) and unlock state
(Phase 2). Nothing user-visible ships until Phase 3.

### Phase 1 — `SteamSchemaProvider` (definitions + rarity)

Two public Steam Web API calls, merged and cached:

- **Definitions** — `GET https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/`
  `?key=<STEAM_KEY>&appid=<APPID>&l=english`
  → `game.availableGameStats.achievements[]`, each:
  `{ name (apiname), displayName, hidden (0/1), description, icon (unlocked url), icongray (locked url) }`.
  Needs the user's Steam Web API key (already stored encrypted).
- **Global rarity** — `GET https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v0002/`
  `?gameid=<APPID>&format=json` → `achievementpercentages.achievements[] { name, percent }`.
  Public (no key). `percent` → `ShibaTier.forRarity(percent)`, exactly as the RA/Steam path already does.

```kotlin
data class SteamAchievementDef(
    val apiName: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val iconUrl: String?,       // unlocked
    val iconGrayUrl: String?,   // locked
    val globalPercent: Double,  // 0..100, 0 when unknown
)

@Singleton
class SteamSchemaProvider @Inject constructor(
    @AchievementsHttpClient client: HttpClient,
    credentials: AchievementCredentialsProvider,
    schemaDao: SteamSchemaDao,
) {
    // Cached per appid (TTL ~7d). Returns [] on missing key / private-less schema / network error
    // (first-class, never throws with the key attached — same discipline as the rest of the module).
    suspend fun schemaFor(appId: Long): List<SteamAchievementDef>
}
```

Room cache (new table, DB v32 → v33 migration):
`steam_achievement_schema(appid, api_name, display_name, description, hidden, icon_url, icon_gray_url,
global_percent, fetched_at; PK(appid, api_name))`. `schemaFor` returns cached rows when
`fetched_at` is fresh, else re-fetches + rewrites.

**Tests** (`SteamSchemaProviderTest`, MockEngine): merges the two responses by `apiName`; maps
`percent` to the right tier; tolerates a missing global-% entry (tier from 0 → Bronze-ish default);
returns `[]` on 401/403 or no key, never leaking the key.

### Phase 2 — parsers (`AchievementStateParser` set)

Pure JVM. Parsers read **text**, not `DocumentFile`, so they unit-test with string fixtures; the
Phase-3 Android scanner reads a `DocumentFile` to text and hands it over.

```kotlin
enum class EmulatorFormat { GOLDBERG, CODEX, ALI213, SMART_STEAM_EMU, GENERIC_INI }

data class SourceFile(val name: String, val text: String)   // name = lowercased file name

interface AchievementStateParser {
    val format: EmulatorFormat
    fun canParse(files: List<SourceFile>): Boolean
    fun parse(appId: Long, files: List<SourceFile>): List<ImportedAchievementState>
}

data class ImportedAchievementState(
    val apiName: String,
    val unlocked: Boolean,
    val unlockTimeEpochSeconds: Long?,
    val currentProgress: Int? = null,
    val maximumProgress: Int? = null,
)
```

Formats (exact quirks transcribed from the emulator / Achievement-Watcher source at build time):
- **GoldbergParser** — `achievements.json`; `{ "ACH_X": { "earned": true, "earned_time": <epoch> } }`
  (tolerate string/number epoch; ignore non-object values). `canParse`: a file named
  `achievements.json` that parses to a JSON object.
- **CodexParser** — `achievements.ini`; sections `[ACH_X]` with `Achieved=1` and `UnlockTime=<epoch>`;
  also the flat `[Achievements] ACH_X=1` variant. `canParse`: INI containing `Achieved=` or an
  `[Achievements]` section.
- **Ali213Parser** — `achievements.ini`; `[ACH_X]` with `HaveAchieved=1`, `HaveAchievedTime=<epoch>`.
  `canParse`: INI containing `HaveAchieved`.
- **SmartSteamEmuParser** — INI achievements (the `.bin` stats form deferred). Best-effort.
- **GenericIniParser** — fallback: any INI whose sections carry a truthy achieved key
  (`achieved`/`haveachieved`/`unlocked` = `1`/`true`) + an optional time key; the long-tail safety net.

A tiny shared INI reader (sections → key/value, case-insensitive) lives beside the parsers so each
parser stays a few lines.

**Tests** (`…ParserTest` per format): a fixture string per emulator → the expected
`List<ImportedAchievementState>` (earned flags + timestamps); `canParse` picks the right parser and
rejects the others; `GenericIniParser` catches an unknown-but-INI sample.

### The bridge (proves 1 + 2 slot in; built in Phase 3, not now)
`SteamAchievementDef` + `ImportedAchievementState` (joined on `apiName`) →
`SyncedCoin(providerAchievementId = apiName, title = displayName, description, tier =
ShibaTier.forRarity(globalPercent), iconUrl, isHidden = hidden, isEarned = unlocked, earnedAt =
unlockTime*1000)` → the existing `AchievementRepository` persistence. Identical to a Web-API set, so
the hub / wallet / glance strip need no changes.

### Out of scope for 1 + 2 (later phases)
SAF folder picker, the scanner, appid→game mapping, Settings UI, notifications, the monitor service,
and the DB tables for `emulator_folders`. Only the schema cache table + migration land in Phase 1.
