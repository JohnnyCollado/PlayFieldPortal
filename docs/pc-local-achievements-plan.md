# PC Local (Emulator) Achievements — Integration Plan

Bring Achievement-Watcher-style tracking into PlayFieldPortal: read the achievement-state files
that Steam emulators (Goldberg, CODEX, ALI213, SmartSteamEmu, SKIDROW, …) write to disk for PC
games, and surface them as Shiba Coins. Reference: https://github.com/xan105/Achievement-Watcher

Status: PLANNING. This does **not** crack, patch, or bypass anything — it is read-only parsing of
local state files an emulator already wrote, plus public Steam **schema** metadata. It is the offline
counterpart to the existing Steam Web-API source.

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
