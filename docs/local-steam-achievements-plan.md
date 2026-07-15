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

- GSE / gbe_fork `achievements.json`: JSON object keyed by achievement api name, values carry
  earned flag + unix earned time (exact field names to be confirmed against the gbe_fork repo —
  believed `earned` / `earned_time`).
- Old Goldberg `achievements.json`: same idea, possibly different field names.
- CODEX/RUNE `achievements.ini`: INI with one section per achievement (`Achieved`, `UnlockTime`).
  Defer to a later phase; GSE is the format actually observed on-device.
- `steam_settings/steam_appid.txt`: plain appid — the join key to the Steam Web API.

## 5. Phased implementation

Every phase: tests ship with it (MockK; parser tests against verbatim sample files), lite-debug
on-device validation, pipefail-gated build+install, atomic conventional commits.

### Phase 0 — Verification gates (no product code)
- [ ] Confirm gbe_fork's exact save-redirect mechanism and config key (repo: gbe_fork /
      Goldberg docs — `local_save_path` variant, which file it lives in, path semantics
      relative vs absolute) and its achievements.json field names. Do NOT code from memory.
- [ ] On the Thor: apply the redirect to MARVEL Cosmic Invasion by hand, run the game in the
      emulator, unlock or verify an achievement writes `achievements.json` into the game folder.
      This proves the whole chain end-to-end before any Kotlin exists.
- [ ] Decide where PFP's SAF grant points (likely the games root, e.g. `/sdcard/Games`), and
      whether the existing ROM-folder grant flow can be reused.

### Phase 1 — Parser + provider island (read-only)
- [ ] `EmuAchievementFile` parser (GSE JSON first) with fixture-file tests; hostile/garbage
      input yields empty, never throws.
- [ ] `LocalSteamDiscovery`: given a game's folder uri/path, locate `steam_settings/
      steam_appid.txt` and the achievements file (game folder redirect location first,
      configurable subfolder).
- [ ] `AchievementProvider.LOCAL_STEAM` + `LocalSteamSource` + router branch. Fetch = local
      earned state joined to `SteamWebApi.getSchemaForGame` + global percentages through
      `SteamCoinMapper.map` (appId from the file, not from a web link).
- [ ] Manual link path: coins screen "link" for LOCAL_STEAM games (appid is discovered, so this
      may reduce to a confirm).
- Opt: schema fetch is once per sync as today, rate-limited; file read is trivial.
- Sec: local file is untrusted input — bounded size, tolerant parse, no strings surfaced raw;
  appid validated as digits before any web call; no new network hosts.

### Phase 2 — Auto-match + sync triggers
- [ ] `AchievementAutoMatcher`: for `windows` games, discovery-before-title-ladder; record
      honest untracked reasons ("No Steam emulator data in the game folder", "DRM-free copy —
      no achievement data exists").
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

## 6. Open decisions

- Whether LOCAL_STEAM games show a distinct source badge vs the Steam badge (coins are
  provider-neutral with a corner badge per the Shiba plan — probably a variant badge).
- Games with BOTH a real Steam link and local emu data: which wins auto-match (proposal:
  local file wins, it reflects what the user actually plays; manual override always available).
- Whether the hidden-description community-page enrichment applies (it queries the user's own
  Steam profile — for emu games the user has no ownership, so it must be SKIPPED for
  LOCAL_STEAM; schema descriptions for hidden achievements will be blank and show the existing
  placeholder).

---

*Play Field Portal · Internal Plan · Local Steam Emu Achievements*
