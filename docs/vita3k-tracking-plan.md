# Vita3K Support — Launch + Trophy Tracking Plan

Adds PS Vita (Vita3K) games to PFP: launching installed titles, and tracking their
trophies into Shiba Coins. Both features read the same Vita3K `ux0` tree, so a single
SAF folder grant powers everything.

## Why this is now possible

Android Vita3K stores its data under a configurable `pref-path`. By default that path is
app-private (`Android/data/org.vita3k.emulator/files/vita`), which Android 11+ forbids
other apps from reading via SAF. When the user points `pref-path` at ordinary shared
storage (verified layout: `/storage/emulated/0/Roms/vita`), the whole `ux0` becomes
SAF-grantable and PFP can read it on-device.

Verified layout (Disgaea 3, Title ID `PCSB00098`, trophy set `NPWR02979_00`):

```
Roms/vita/ux0/
  app/PCSB00098/sce_sys/param.sfo            # title + metadata
  app/PCSB00098/sce_sys/icon0.png            # tile art
  app/PCSB00098/sce_sys/trophy/NPWR02979_00  # game -> trophy-set join
  user/00/trophy/conf/NPWR02979_00/TROP.SFM  # trophy definitions (XML)
  user/00/trophy/conf/NPWR02979_00/TROP*.PNG # per-trophy icons
  user/00/trophy/data/NPWR02979_00/TROPUSR.DAT # unlock state + timestamps
```

Constraints that shaped this plan:
- Android Vita3K cannot install `.vpk` files, and cannot launch a loose `.vpk`. Only
  already-installed `ux0/app/<TITLEID>` titles are runnable. The current
  `vpk -> psvita` extension mapping is therefore misleading and is dropped.
- Vita3K launches by Title ID, not a ROM file: no `romPath`/`romUri` exists for a Vita
  game.

## Shared foundation — the Vita3K `ux0` grant (Phase 0)

New singleton `Vita3KLibrary` (mirrors `WindowsLibrarySetup`):
- Stores the granted `ux0` tree URI in DataStore (`vita3k_ux0_tree_uri`).
- Persists the SAF read permission (`takePersistableUriPermission`).
- Resolves child paths via tree-scoped SAF queries: `app/`, `user/00/trophy/conf`,
  `user/00/trophy/data`, `app/<id>/sce_sys/...`.
- Caches a scan (mutex + short TTL), like `LocalSteamDiscovery`.

Settings UI: a "PS Vita (Vita3K)" section with "Set Vita3K Data Folder"
(`OpenDocumentTree`) that grants the `ux0` folder, plus the current path and a Clear
action. One grant serves both launch discovery and trophies. No credentials, no Web API
key — everything is local file reads.

Everything Vita-related short-circuits to empty when the grant is absent.

## Phase 1 — Game discovery + launch

### Data model
1. `KnownEmulator` + `EmulatorProfile` (core-domain) + its Room entity / bundled JSON:
   add `intentArrayExtras: Map<String, List<String>>` (key -> array of value templates).
2. `EmulatorDetector.detect()`: copy `intentArrayExtras` through into the profile.
3. `LaunchTemplate`: add `TITLE_ID = "{title_id}"`.
4. `Game`: add `launchToken: String?` (Room column + migration). Holds the Vita Title ID.

### Intent building (`EmulatorIntentResolver`)
- `buildComponentIntent`: apply array extras —
  `profile.intentArrayExtras.forEach { (k, tmpls) -> putExtra(k, tmpls.map { resolveTemplate(it, game, profile, romUri) }.toTypedArray()) }`.
- `resolveTemplate`: `TITLE_ID -> game.launchToken ?: ""`.
- `validateBeforeLaunch`: detect a token launch
  (`intentArrayExtras` references `TITLE_ID`) and, in that branch, skip the ROM
  existence checks and require a non-blank `launchToken` instead.

### Catalog (`KnownEmulatorCatalog`)
- Remove Vita3K/EmuCoreV from the "Not representable yet" note.
- Add:
  ```
  KnownEmulator(
      packageNames      = listOf("org.vita3k.emulator", "org.vita3k.emulator.ikhoeyZX"),
      suggestedName     = "Vita3K",
      platformIds       = listOf("psvita"),
      intentType        = IntentType.COMPONENT,
      activityClass     = "org.vita3k.emulator.Emulator",
      intentArrayExtras = mapOf("AppStartParameters" to listOf("-r", LaunchTemplate.TITLE_ID)),
  )
  ```
  and an EmuCoreV entry (`com.sbro.emucorev` / `.core.vita.Emulator`, same array extra).

### Scan / discovery
5. `VitaGameScanner`: through `Vita3KLibrary`, enumerate `ux0/app/<TITLEID>`; for each,
   read `sce_sys/param.sfo` for the display title and `sce_sys/icon0.png` for tile art;
   upsert a `Game(platformId = "psvita", launchToken = <TITLEID>, title, art)`.
6. `ParamSfo` reader: a minimal PSF (SFO) binary parser to pull the `TITLE` key; fall
   back to the Title ID when absent.
7. Drop `vpk -> psvita` from `PlatformExtensionMap` (or keep only to show an
   "extract into Vita3K first" hint — a loose `.vpk` is never launchable on Android).

## Phase 2 — Trophy tracking (`VITA_TROPHY` provider)

Mirrors the LocalSteam achievement provider, minus the Web API (all local reads).

1. `AchievementProvider.VITA_TROPHY` — new enum value. The achievement link table is
   keyed by provider string, so no schema change beyond the new value.
2. `VitaTrophyDiscovery` (mirror `LocalSteamDiscovery`): through `Vita3KLibrary`, for each
   `user/00/trophy/conf/<NPCOMMID>` (with matching `data/<NPCOMMID>`), build a
   `VitaTrophyGame(npCommId, titleName, confDocId, tropUsrUri, iconUris)`. Join to a
   library game via `app/<TITLEID>/sce_sys/trophy/<NPCOMMID>` — exact, never fuzzy. A
   game may declare more than one set (list, not single).
3. Parsers:
   - `TropSfm` — XML: `<npcommid>`, `<title-name>`, and per `<trophy id ttype hidden pid>`
     the `<name>` / `<detail>`. `ttype` P/G/S/B -> Platinum/Gold/Silver/Bronze.
   - `TropUsr` — binary `TROPUSR.DAT`: per-trophy unlocked flag + unlock timestamp. This
     is the one genuinely new binary format; parse defensively and fall back to
     "definitions only, no progress" on an unexpected layout. (Reference the Vita3K /
     RPCS3 trophy-user readers for the byte layout.)
4. `VitaTrophySource` (mirror `LocalSteamSource`): given a linked game, return the
   achievement list (id, name, detail, grade, hidden, icon uri, earned, earnedAt) by
   merging `TropSfm` defs with `TropUsr` progress. Icons from `conf/<NPCOMMID>/TROP*.PNG`.
5. Sync: link game <-> `VITA_TROPHY` (like `linkManually`) and feed earned trophies into
   Shiba Coins with grade-weighted values, reusing the existing Sync All pipeline.

## Room migrations
- `Game.launchToken` (nullable String) — new column + migration + schema export.
- `EmulatorProfile.intentArrayExtras` if profiles persist in Room — new column
  (JSON-encoded map) + migration; otherwise only the bundled-profile JSON schema changes.
- `VITA_TROPHY` needs no migration (provider stored as a string).

## Tests
- `TropUsrTest` — parse a fixture `TROPUSR.DAT` (crafted, plus optionally the real Disgaea
  file) for unlock flags + timestamps.
- `TropSfmTest` — parse `TROP.SFM` -> trophy list with grades/hidden.
- `EmulatorIntentResolverTest` — Vita3K profile builds a COMPONENT intent with the
  `AppStartParameters` string-array and the Title ID, and skips ROM validation.
- `VitaTrophyDiscoveryTest` — the `app/<id>/sce_sys/trophy/<NPCOMMID>` join; empty when
  the grant is absent (gate test).
- `VitaGameScannerTest` — `ux0/app` enumeration + `param.sfo` title fallback.

## Open decisions
- Where the "Set Vita3K Data Folder" grant lives — Library Manager (it is a ROM-ish
  library) vs Achievements settings (it powers trophies). Recommendation: Library
  Manager, since it gates launch too, with Achievements linking to it.
- Grade -> coin weighting for Platinum/Gold/Silver/Bronze.
- Confirm the exact `TROPUSR.DAT` byte layout against a second title before shipping the
  parser.

## Risks / mitigations
- `TROPUSR.DAT` format drift across firmware/Vita3K versions -> defensive parse, degrade
  to definitions-only.
- Stale grant if the user moves `pref-path` again -> detect unreadable tree, re-prompt.
- SAF read cost over a large `ux0` -> cache the scan (mutex + TTL) like LocalSteam.

## Phasing
Phase 0 + Phase 1 first (games launch). Phase 2 (trophies) layers on afterward behind the
same grant, with no user-visible change to Phase 1.
