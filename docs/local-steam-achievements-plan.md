# Local Steam Emu Achievements — Winlator/GameNative Games

Plan for a third achievement provider, LOCAL_STEAM: track achievements for Windows games run
through Android Wine emulators (Winlator, GameNative, and forks) whose bundled Steam emulator
(GSE / Goldberg fork, CODEX-style) writes achievement progress to local files. Modeled on
Achievement Watcher's approach (parse emu files, join with the Steam schema), reimplemented in
Kotlin inside PFP's existing provider framework.

Status: DRAFT — not started. Written 2026-07-16 against branch `achievement-integration`
(HEAD b293c47). Prerequisite reading: `docs/shiba-coins-achievements-plan.md` (architecture) and
the provider framework under `feature/feature-achievements/src/main/kotlin/.../provider/`.

---

## 1. Goal and scope

- Give emu-run Windows games the same Shiba Coins treatment as RA and Steam games: earned state
  from the local achievements file, names/descriptions/icons/rarity from the Steam Web API,
  tiers from the existing Steam tier rules (spec v2, Platinum detection included).
- Display-only. PFP parses progress files the game already wrote. Nothing here touches DRM,
  cracks, or bypasses — same posture as Achievement Watcher itself.
- Licensing: Achievement Watcher is LGPL-3.0. PFP reimplements the trivial file formats from
  format knowledge; no code is ported. Keep it that way.

## 2. Verified device findings (AYN Thor, Android 13, no root — probed 2026-07-16)

These findings drive the whole design; re-verify on other devices before generalizing.

| Surface | Finding |
|---|---|
| Winlator (`com.winlator`) | Wine prefix entirely app-private (`/data/data/...`); no `Android/data` footprint. Unreachable without root. |
| GameNative (`app.gamenative`) | `Android/data/app.gamenative/files/` holds only `wine_logs`; prefix private. `Android/data` trees cannot be SAF-granted to apps on Android 11+, so even that is out. |
| GameHub / Bannerhub | No packages installed; `/sdcard/Gamehub` was a stray ROM zip. Layouts unverified. |
| Windows game folders | Live on shared storage at `/sdcard/Games/<Game>/` — readable AND writable by PFP via one SAF tree grant. |
| Live emu example | MARVEL Cosmic Invasion ships GSE (Goldberg fork): `steam_api64.dll`, renamed original (`steam_api64_o.dll`), `steam_settings/` with `configs.*.ini` and `steam_appid.txt`. |
| Counter-examples | Torchlight 2 = GOG build; FF pixel remasters = DRM-free. No emu, no achievement data, nothing to track. Surface this honestly in the UI (untracked reason). |

Consequence: the Wine prefix (where `%APPDATA%/GSE Saves/<appid>/achievements.json` lands by
default) is unreachable. The workable path is the emu's save redirect (`local_save_path`),
which moves saves NEXT TO THE GAME EXE — i.e. into the PFP-readable game folder.

## 3. Architecture fit (uses the existing MVC provider framework)

One new island, one enum value, zero changes above the seam:

```
core-domain:  AchievementProvider += LOCAL_STEAM
              (exhaustive whens force every touchpoint at compile time)

feature-achievements/provider/localsteam/
    EmuAchievementFile.kt        parse achievements.json (GSE/Goldberg) [+ INI formats later]
    LocalSteamDiscovery.kt       find steam_settings/steam_appid.txt + achievements file
                                 under the granted game folders (SAF, via existing plumbing)
    LocalSteamSource.kt          : RemoteAchievementSource — earned state from the local file,
                                 schema + global % from the existing SteamWebApi, coins via
                                 SteamCoinMapper (tier rules reused verbatim)
    LocalSaveRedirect.kt         optional "one-tap enable": writes the emu's save-redirect
                                 config into the game folder (Phase 3)

RemoteAchievementSources         add the LOCAL_STEAM -> LocalSteamSource branch
AchievementAutoMatcher           windows games: if discovery finds steam_appid.txt in the
                                 game's folder -> link LOCAL_STEAM (beats title matching);
                                 else existing Steam-web ladder; else untracked with reason
```

Notes:
- Schema/rarity calls require the user's Steam Web API key (already stored, encrypted); global
  percentages need no key. The tier stability window and Platinum rules in
  `AchievementRepository.stabilizeTiers` / `summaryOf` apply unchanged (provider == STEAM checks
  must be reviewed: stability should cover LOCAL_STEAM too, since its tiers are rarity-based).
- Sync triggers: manual (coins screen / Sync All) and return-from-emulator-launch. NO file
  watching / background polling — house rule (DESIGN.md), and FileObserver is unreliable on SAF.
- DB: provider column is a string; new value needs no migration. Check every
  `AchievementProvider.fromName` / provider-string consumer (wallet SQL is provider-agnostic).

## 4. File formats (verify in Phase 0 before coding)

- GSE / gbe_fork `achievements.json` (progress file): CONFIRMED 2026-07-15 against the
  gbe_fork source (`dll/steam_user_stats.cpp`): JSON object keyed by achievement api name;
  each value carries `earned` (bool) and `earned_time` (uint32 unix seconds), plus optional
  `progress` / `max_progress` for partial-progress achievements. Do not confuse it with
  `steam_settings/achievements.json`, which is the schema INPUT the emu reads, not progress.
- Old Goldberg `achievements.json`: same object-keyed shape and `earned` / `earned_time`
  fields (gbe_fork continues the original's storage code); parser tolerance covers drift.
- CODEX/RUNE `achievements.ini`: INI with one section per achievement (`Achieved`, `UnlockTime`).
  Defer to a later phase; GSE is the format actually observed on-device.
- `steam_settings/steam_appid.txt`: plain appid — the join key to the Steam Web API.

## 5. Owned vs cracked — the four-state classification

Two independent signals classify every Windows game folder (decided 2026-07-15):

- **Emu markers** (local, free): `steam_settings/` + `steam_appid.txt`, and the renamed
  original DLL (`steam_api64_o.dll` beside the replacement `steam_api64.dll`). Present means
  the copy never talks to real Steam — its achievements exist only in the local emu file.
- **Ownership** (one Steam Web API call): the discovered appid is looked up in the owned
  list. ALREADY BUILT (2026-07-15): the account plan landed `SteamWebApi.getOwnedGames`,
  `SteamOwnedGamesDao`'s cache, and the (game_id, provider) link key this plan's
  coexistence rule needs — classification here reduces to a lookup against that cache,
  refreshed by every Steam library import.

| Emu markers | Owned on Steam | Meaning |
|---|---|---|
| Yes | No | Cracked/unowned copy — LOCAL_STEAM tracking only |
| Yes | Yes | Owned, playing an offline emu copy — both sets coexist (STEAM + LOCAL_STEAM) |
| No | Yes | Owned build, no emu — nothing writes local files; STEAM provider only |
| No | No | DRM-free/GOG/Epic build — no Steam achievement data; honest untracked reason |

PFP stays display-only in all four states: classification changes which tracking pipeline
applies and what the badge/untracked reason says, never whether the game launches. Ownership
lookup requires the user's key + public Game Details (same gate as every player call); when
unavailable, fall back to emu-markers-only with the ownership column treated as unknown.

## 6. Phased implementation

Every phase: tests ship with it (MockK; parser tests against verbatim sample files), lite-debug
on-device validation, pipefail-gated build+install, atomic conventional commits.

### Phase 0 — Verification gates (no product code)
- [x] Confirm gbe_fork's exact save-redirect mechanism and config key. DONE 2026-07-15
      against the gbe_fork repo (Detanup01/gbe_fork, dev branch):
      - File: `steam_settings/configs.user.ini`, section `[user::saves]`, key
        `local_save_path`. Path may be absolute or RELATIVE TO THE DLL/SO LOCATION (the
        folder holding `steam_api64.dll`, not steam_settings); whitespace is trimmed;
        setting it makes the emu ignore the global save folder entirely (fully portable).
      - Resulting layout: `<local_save_path>/<appid>/achievements.json` — the appid
        subfolder is kept (`Local_Storage` composes save_directory + appid + file), and a
        sibling `settings/` folder holds the portable account config.
      - Progress-file format confirmed (section 4): object keyed by api name,
        `earned` / `earned_time` (+ optional `progress` / `max_progress`).
      - Fallback for OLD Goldberg builds (pre-fork): a `local_save.txt` file beside the
        DLL (optionally containing a path) enables the same portable mode — check which
        mechanism the bundled emu honors during the on-device test.
- [x] On the Thor: prove the redirect chain end-to-end on MARVEL Cosmic Invasion. DONE
      2026-07-15, verified live over adb: `steam_settings/configs.user.ini` carries
      `[user::saves]` / `local_save_path=./GSE Saves` (note the trailing space in the
      value — the emu trims it, discovery should too), and after a play session the emu
      wrote `<game folder>/GSE Saves/2753970/achievements.json` with 3 earned
      achievements carrying real `earned_time` stamps, alongside `remote/GameSave.sav`,
      per-stat files under `stats/`, and `playtime.txt`. Format exactly as researched:
      object keyed by api name, `earned`/`earned_time`, optional `progress`/
      `max_progress`. The live file is checked in verbatim as the Phase 1 parser fixture
      (`feature-achievements/src/test/resources/localsteam/gse_achievements_progress.json`).
      Recipe for other games — append to (or create) `steam_settings/configs.user.ini`,
      never clobbering existing sections:

          [user::saves]
          local_save_path=./GSE Saves

      The path resolves relative to the folder holding the steam_api DLL; the appid
      subfolder comes from `steam_settings/steam_appid.txt`.
- [x] Decide where PFP's SAF grant points and whether existing plumbing is reused. DECIDED
      2026-07-15 after inspecting the live debug install:
      - The windows memory card's own folder (`memory_cards.tree_uri` — the per-card field
        every other platform already uses) points at the games root (`/sdcard/Games` on the
        Thor) via the standard card folder picker. NO new grant flow; `SafGrants` status
        rules and the recursive-tree behavior apply as-is. Discovery walks that tree with
        the shared `core.data.saf.querySafChildren` helper.
      - Discovered reality shaping scope: on the Thor the windows card has no folder yet,
        the only ROM-root grant is `primary:Roms`, and the two library windows games are
        GameNative STORE entries (intent-launched via app_id, `rom_path` NULL, already
        STEAM-linked). Such games have no shared-storage folder and are out of LOCAL_STEAM
        scope by construction — the real STEAM provider covers them.
      - GAP FOR PHASE 2: emu-marker game folders (e.g. MARVEL Cosmic Invasion) are not
        library games today — `scanPcFolder` only reads frontend-export files. Auto-match
        needs library entries to attach links to, so Phase 2 must either scan emu-marker
        folders into the windows card as games or match them to existing entries by folder
        name. Decide there; Phase 1's discovery+parser are folder-keyed and don't care.

### Phase 1 — Parser + provider island (read-only)  (CODE DONE 2026-07-15; on-device
validation pending — needs the windows card pointed at the games root and a library entry
for an emu game, which is Phase 2's gap)
- [x] `EmuAchievementFile` parser (GSE JSON) + `GseUserConfig` (redirect extraction and
      relative-path segments); tests run against the verbatim device fixture; hostile input
      parses to empty.
- [x] `LocalSteamDiscovery`: walks the windows card's granted tree for
      `steam_settings/steam_appid.txt` folders, resolves the progress file through the
      emu's own redirect semantics; grant-scoped, size-bounded, digits-validated.
- [x] `AchievementProvider.LOCAL_STEAM` + `LocalSteamSource` + router branch, exactly as
      designed (SteamCoinMapper reused verbatim; hidden-description enrichment skipped —
      section 8 decision). `stabilizeTiers` covers LOCAL_STEAM.
- [x] Manual link path — resolved as "no manual entry": the appid is discovered from the
      folder, so the coins screen explains folder-derived linking and defers to Auto-match
      (Phase 2). Nothing to type by hand was the plan's own suspicion ("may reduce to a
      confirm"), and a confirm needs the auto-match wiring anyway.
- Opt: schema fetch is once per sync as today, rate-limited; file read is trivial.
- Sec: local file is untrusted input — bounded size, tolerant parse, no strings surfaced raw;
  appid validated as digits before any web call; no new network hosts.

### Phase 2 — Auto-match + sync triggers
- [x] Emu folders enter the library (decided + built 2026-07-15, user's call: "scan the emu
      folders into the windows card as games"): `LocalSteamGameImporter` runs as a second
      pass of the Library Manager's "scan PC games" action — every discovered emu folder
      becomes a windows-card game (title = folder name, rom_path = derived raw path) and is
      linked to LOCAL_STEAM on the spot, since the appid comes from the folder itself.
      Re-scans converge by the shared normalized-title dedupe and refresh the link; a STEAM
      link on the same game coexists (one link per provider).
- [x] `AchievementAutoMatcher` (built 2026-07-16 with refactor-plan Phase 5): windows games
      are folder-first — an emu-marked folder links LOCAL_STEAM with its own appid and gets
      classified; the ladder's unmatched reason now names the absence of emulator data
      honestly.
- [x] Four-state classification (section 5) — BUILT 2026-07-16 (refactor-plan Phase 5): DB v35
      `ownership` column on the link, `LocalSteamOwnership` derivation at scan + post-import
      refresh, both links for owned emu copies, neutral coins-screen wording, and the
      shortcut-to-folder mapping (title + Steam-name bridge) all landed as locked below:
      - Computed AT SCAN TIME and PERSISTED: the PC scan (and each completed Steam import)
        derives an ownership state per LOCAL_STEAM link — OWNED / NOT_IN_LIBRARY / UNKNOWN —
        stored on the provider link row. UNKNOWN whenever the owned cache is empty, stale, or
        credentials are missing; `isOwned == false` alone never means "unowned".
      - Wording stays NEUTRAL: badge remains "Local"; the unowned state reads
        "Not in your Steam library — achievements tracked locally". The signal cannot prove
        piracy (family sharing, alternate accounts, and unplayed free games all look unowned),
        so the UI never says "cracked".
      - Emu marker stays `steam_settings/steam_appid.txt` alone — no DLL-pair verification
        (legit copies never ship steam_settings; the renamed `_o.dll` check adds SAF queries
        and some emu setups do not rename the original).
      - Owned + emu (state 2): scan also attaches the STEAM link with the same appid —
        appid equality beats any title ladder; both sets coexist per section 7.
      - `.desktop` shortcuts carry no appid; they classify only when their rawPath falls
        inside a discovered emu folder (inherit that folder's appid). Otherwise unknown.
- [x] Raise `LocalSteamDiscovery.SETTINGS_SEARCH_DEPTH` from 3 to 4 — DONE 2026-07-16 (found
      same day on device): Unity-built games nest the emu config at
      `<Game>/<Game>_Data/Plugins/x86_64/steam_settings` — depth 4 — which had left all four
      FF pixel remasters (appids 1173780/1173790/1173810/1173820) invisible.
- [ ] Shortcut-to-folder mapping (designed 2026-07-16 against live Thor data). A PC shortcut
      carries only (host package, shortcut id, label) — the OS strips intents for launchers,
      and the emulator's own id-to-path knowledge (private library DB, `exePath=` launch logs
      under `Android/data`) is unreachable by construction. The only shortcut type with an
      explicit path is a Winlator `.desktop` export (already captured as `rawPath`).
      Mapping is therefore a two-step derived join, used as the MERGE KEY that unifies the
      launchable shortcut entry with the trackable emu folder into one library game:
      1. Normalized-title match — shortcut label vs game folder name, lowercase alphanumeric
         only (the existing `LocalSteamGameImporter` dedupe rule). Matched 7 of 9 live pairs,
         surviving `NieR:Automata™` vs `NieR - Automata` and squashed labels like
         `MARVELCosmicInvasion`.
      2. Steam-name bridge for renamed folders — an emu-marked folder's appid resolves to the
         official Steam name (schema call already made), and THAT is matched against the
         shortcut label. Works because GameHub-family labels come from Steam metadata, so both
         strings share Steam as origin. Live proof: folder `FINAL FANTASY FFX-FFX-2 HD
         Remaster` (appid 359870) fails step 1 but its Steam name is exactly the shortcut
         label `FINAL FANTASY X/X-2 HD Remaster`.
      Combined: 8 of 9 live pairs map deterministically. Known miss: `FINAL FANTASY XIV` vs
      `FINAL FANTASY XIV - A Realm Reborn` — folder is not emu-marked (no appid to bridge)
      and prefix/fuzzy matching is deliberately NOT used (false-positive tier); an MMO has no
      local tracking to gain anyway. Unmapped entries simply stay separate — mapping never
      guesses.
- [ ] Return-from-launch sync for LOCAL_STEAM-linked games (hook the existing launch-return
      path named in the Shiba plan).
- [ ] Review `stabilizeTiers` provider gate to include LOCAL_STEAM.

### Phase 3 — One-tap redirect setup (quality of life)
- [ ] `LocalSaveRedirect`: PFP writes the verified Phase-0 config into the game folder on user
      confirmation, with a clear explanation of what it changes; never overwrites an existing
      user config silently.
- [ ] Settings/coins-screen surface: "Enable local achievement tracking for this game".
- Sec: writes only inside the SAF-granted game folder; show exactly what will be written.

### Phase 4 — Later / optional
- [ ] CODEX/RUNE INI formats; other emu layouts as encountered.
- [ ] Per-fork container-location support if a fork with shared-storage prefixes appears.
- [ ] Unlock notifications on launch-return diff (new coins since last sync) — fits the
      no-polling rule since it piggybacks on the sync.

## 7. Decisions (locked 2026-07-15)

- **Identity:** `provider_game_id` stays the raw Steam appid; uniqueness comes from the
  composite key `(game_id, provider)` — no compound/prefixed ids in storage. A combined
  identifier (`LOCAL_STEAM:12345`) may be derived at display/export edges only.
- **Badge:** LOCAL_STEAM entries show a distinct source badge labeled "Local" everywhere the
  provider badge appears in Shiba Coins (hub rows, coins screen header, coin corner badge).
  Exhaustive `when` on `AchievementProvider` forces every touchpoint to choose explicitly.
- **Coexistence:** a game MAY hold both a STEAM set and a LOCAL_STEAM set. The emu's unlock
  state is its own achievement set, tracked separately from the official Steam set; both count
  in the wallet as distinct sets. Dedupe rule: before inserting a new set/entry, check for an
  existing row for that (game, provider) — update it instead of adding a duplicate.

## 8. Open decisions

- DECIDED 2026-07-15 with Phase 1: the hidden-description community-page enrichment is
  SKIPPED for LOCAL_STEAM (it queries the user's own Steam profile, which only has the page
  for owned copies); schema descriptions for hidden achievements stay blank and show the
  existing placeholder.

---

*Play Field Portal · Internal Plan · Local Steam Emu Achievements*
