# Windows Game Library Refactor — Shortcuts, Scanning, Classification

Plan for restructuring how PC games enter and live in the Windows Games memory card: the card's
own settings menu, shortcut importing (pin + auto-import), emulator variant detection, folder
scanning, and the shortcut-to-folder merge. Driven by live AYN Thor findings (2026-07-16) and
locked user decisions recorded per section.

Status: DRAFT — decisions locked, implementation NOT started (user gate: no coding until told).
Written 2026-07-16 against branch `achievement-integration`. Companion plan:
`docs/local-steam-achievements-plan.md` (LOCAL_STEAM provider; shares the discovery/mapping
machinery).

---

## 1. Live device baseline (Thor, 2026-07-16)

- Library: 5 windows games, all `is_manual_entry`, zero artwork/metadata, three launch shapes
  (GameNative intent, GameHub-family intent, bare `launch_shortcut_id`).
- OS shortcut service holds 12 pins split across TWO PFP installs (release vs lite-debug);
  pins are per-launcher-package, so an install can only reconcile pins addressed to itself.
- Games live in TWO roots — `/storage/emulated/0/Games` (6 folders) and
  `/storage/408C-3861/Games` (12 folders) — disjoint sets, neither named `windows`.
- Emulator variants installed: `com.xiaoji.egggame` 6.0.9 (GameHub v6; `app_nav_*` extras,
  `.DeepLinkActivity`), `com.ludashi.aibench` 5.1.7 (GameHub v5/Lite lineage; `com.xj.*`
  classes, `therouter_path` extras), `app.gamenative` (shortcut id carries the appid:
  `game_<appid>`), `com.winlator` (no shortcuts published).
- A launcher can never read another app's shortcut INTENT (OS strips it); it CAN read the
  shortcut's id, labels, and target activity component (`ShortcutInfo.getActivity()`).

## 2. Windows card menu restructure (locked 2026-07-16)

The Windows card gets its own detail-screen branch (as Android already has), replacing the
generic console layout:

- ADD "Import PC Games" — the existing `IMPORT_PC` step, reachable from the card itself.
- REMOVE the Emulator row — windows games launch through launcher apps, never an emulator
  profile.
- REMOVE the Supported Files / extensions section — PC scanning is extension-free.
- Default directory: `<ROM Root>/windows`; PFP CREATES the folder when missing (mirrors the
  ES-DE folder-setup behavior) and assigns it automatically when a ROM root exists.
- Dedicated import drop-folder (locked 2026-07-16): `<ROM Root>/windows/import/` holds the
  frontend-export files (`*.steam` / `*.epic` / `*.gog` / `*.amazon` / `*.pcgame` /
  `*.desktop`). The export scan reads THIS folder (point GameNative/Winlator exports at it)
  instead of trawling the whole windows folder; game folders live as siblings under
  `<ROM Root>/windows/<Game>/`. Created alongside the windows folder by the same
  auto-create rule.
- RESOLVED 2026-07-16: the user adopted the canonical layout — internal games now live at
  `Roms/windows` (verified on device; the `import/` subfolder already created by hand) and
  the SD-card games will be moved by the user as well. No extra folder-name support needed;
  multiple ROM roots are already supported as-is.

## 3. Shortcut importing (locked 2026-07-16)

Goal: importing is effortless from either direction — the user pins a game from the emulator's
own shortcut builder, or runs PFP's auto-import menu — and both funnels produce identical
Windows-card entries through one shared importer.

- Gate: a shortcut becomes a Windows game only when its host package is a VERIFIED known PC
  launcher (section 4). Non-PC hosts keep today's collection behavior. The ad-hoc
  `contains("gamehub")/"banner"` label heuristic in `XMBViewModel.importGameShortcuts` is
  replaced by the single catalog rule.
- Pin path: `PinShortcutActivity` (modern `CONFIRM_PIN_SHORTCUT`) routes verified PC-launcher
  shortcuts to the Windows card — today it files them as `android`/`ANDROID_APP` collection
  entries. Legacy `INSTALL_SHORTCUT` broadcasts keep their confirm-notification flow
  (unauthenticated broadcast, must stay user-confirmed) and route to the Windows card after
  confirmation when the host verifies.
- Entry shape: `(packageName, shortcutId)` launched via `LauncherApps.startShortcut` — which
  is variant-agnostic. GameNative ids (`game_<appid>`) are parsed for an instant STEAM appid.
- Auto-import (harvest) additionally runs the shortcut-to-folder mapping join (companion plan,
  Phase 2) so folder-backed games merge with their emu folder: shortcut provides launch,
  folder provides appid/achievements/classification.
- AUTO-IMPORT DROPPED (user decision 2026-07-16). Rationale, verified on device: GameHub-family
  apps publish NO dynamic shortcuts — shortcuts exist only once the user pins them, and pins
  are visible only to the launcher package they were pinned through — so a harvest button
  cannot deliver a full-library pull; and forcing shortcut creation is impossible (publishing
  is publisher-side only; none of the four emulators implement the `ACTION_CREATE_SHORTCUT`
  picker — device query shows only stock apps handle it). The pin workflow above is THE
  shortcut path; Import PC Games keeps the export-file scan and Add by ID.
- Add by ID supports each app's id structure (manifest contracts verified from pulled APKs,
  2026-07-16). The action pattern `<pkg>.LAUNCH_GAME` holds across every variant; the target
  component and extras differ by generation, selected by the section-4 variant fingerprint:
  - GameNative: `app.gamenative.MainActivity`, extras `app_id` (int) + `game_source`
    (STEAM/EPIC/GOG/AMAZON). Id = the store appid. Current adapter correct.
  - GameHub v6 lineage (xiaoji 6.0.9): `com.xiaoji.egggame.DeepLinkActivity` (explicit-only,
    no filters — invisible to resolver queries but present in the manifest), extras
    `steamAppId` or `localGameId` + `autoStartGame`. Current adapter correct FOR V6 ONLY.
  - GameHub v5 lineage (ludashi 5.1.7): current adapter is BROKEN here — the app contains no
    `com.xiaoji.egggame.DeepLinkActivity`. Its real contract:
    `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity`, exported=true, intent
    filter action `com.ludashi.aibench.LAUNCH_GAME`; extras per its own shortcut bundles
    (`steamAppId`, `localGameId`, `autoStartGame`, `id`, `type`). Also exposes a BROWSABLE
    `gamesir://gamehub` deep link on `com.xj.app.DeepLinkRouterActivity`.
  - Winlator: no id contract (`.desktop` path launch) — stays outside Add by ID.
  - Id-entry caveat: v5 locally-imported games use `localGameId` UUIDs no user can type;
    Add by ID is realistically Steam-appid entry, and UUID games arrive via pins only.
- Canonical pin workflow (locked 2026-07-16): user in the WinEmu app presses "Add to home" ->
  the PC Game entity is created and added to the Windows Library when it exists; when it does
  not, the entity is STILL written immediately (pins are never lost) and the user is prompted
  to set the library up. Prompt surface: a notification fires right away ("Game added —
  finish setting up your Windows Library"; the user is inside the WinEmu app at pin time, PFP
  has no visible UI), and if ignored, a one-time dialog appears on the next XMB open — two
  chances, no nagging afterwards. Setup = card + ROM root / accept the auto-suggested
  `<ROM Root>/windows`, replacing today's silent directory-less card creation.

## 4. Emulator variant detection (locked 2026-07-16)

Problem: GameHub-family variants ship under spoofed package names that are the GENUINE names
of real apps (AnTuTu, PUBG, Genshin, Ludashi...), and different variants run different
generations with different launch APIs. Label-only detection is fragile.

Detection rule — package in family pool, then signals in order of strength:

1. Component fingerprint (primary): probe for known activity classes via `PackageManager` —
   `com.xj.app.DeepLinkRouterActivity` = v5/Lite lineage; `com.xiaoji.egggame.DeepLinkActivity`
   = v6 lineage. Real AnTuTu/PUBG/Genshin contain neither, killing the spoof ambiguity.
2. `versionName` major (5.x vs 6.x) selects the launch-intent format where PFP builds intents
   itself (export files / add-by-ID). Live: xiaoji=6.0.9, ludashi=5.1.7.
3. App label — display naming only (BannerHub vs GameHub Lite branding on the same
   architecture), never the sole gate.
4. Harvested shortcut's target activity component corroborates per shortcut.

Attribution (user's call): `packageName` on the game row stays the source of truth; the
variant fingerprint is computed and CACHED at runtime (refreshed on package change), and the
UI shows the resolved name as the game's emulator subtitle (existing Platform (Emulator)
pattern). No schema change; survives emulator app updates.

## 5. Ownership classification

Locked decisions and the four-state model live in the companion plan
(`docs/local-steam-achievements-plan.md`, sections 5 and Phase 2): scan-time persisted
ownership state on the LOCAL_STEAM link (OWNED / NOT_IN_LIBRARY / UNKNOWN — never guessing
from an empty cache), neutral "Local copy" wording, emu marker = `steam_settings/
steam_appid.txt` alone, owned+emu games get both provider links.

Live validation (19 real games): 8 confidently local emu copies via markers; GameNative games
legal by construction (real Steam login); GOG markers identify DRM-free store builds; one
provably cracked copy (explicit `_CrackOnly (RUNE)` folder); the rest resolve only after the
Steam owned-games import runs.

Auto-match ordering for PC games (locked 2026-07-16) — folder presence is the FIRST check:

1. Look for the game's corresponding folder under the windows folder (the shortcut-to-folder
   mapping join: normalized title, then the Steam-name bridge).
2. Folder found + emu markers -> local emu copy: link LOCAL_STEAM with the folder's appid;
   ownership state from the owned-cache lookup (companion plan).
3. Folder found, NO emu markers -> not a local emu copy; classify from what the folder shows
   (GOG markers -> DRM-free build; bare Steam files -> undetermined) and fall through to the
   Steam title ladder for a possible STEAM link.
4. NO folder -> most likely legit: the game lives in the launcher's private storage
   (GameNative downloads, GameHub Steam-sourced entries) — proceed with the existing STEAM
   ladder (appid from shortcut id / export file when available, else title match). Live data
   agrees: every no-folder game on the Thor is launcher-managed (GameNative pair, GameHub
   Steam-store entries), while every emu copy has a shared-storage folder by construction —
   the emu needs the files where the Wine runtime can reach them.

## 6. Phased implementation

Every phase: tests ship with it (MockK; contract tests against the verified manifest/shortcut
fixtures), lite-debug on-device validation, pipefail-gated build+install, atomic conventional
commits. No phase starts before the user lifts the coding gate.

### Phase 0 — Verification gates (no product code)
- [x] Decide the open question from section 2 — DONE 2026-07-16: the user moved the internal
      games to `Roms/windows` (verified live, `import/` included) and is moving the SD games
      too. `<ROM Root>/windows` is the documented setup; no extra folder names.
- [x] Prove the v5 Add-by-ID contract live — DONE 2026-07-16: fired
      `com.ludashi.aibench.LAUNCH_GAME` -> `com.xj...GameDetailActivity` over adb with a
      `localGameId` + `autoStartGame=false`; the activity resolved and became the top resumed
      activity. The v6 recipe was already proven by the working Tactics Ogre row.
- [x] Confirm component-fingerprint visibility — DONE 2026-07-16: PFP holds
      `QUERY_ALL_PACKAGES` for its launcher role (app manifest line 21), so `getActivityInfo`
      on other packages' activities needs no `<queries>` additions; both lineage marker
      classes are exported anyway.

### Phase 1 — Variant detection foundation  (CODE DONE 2026-07-16)
- [x] `PcLauncherCatalog` gains the component fingerprint (section 4): lineage classes decide
      (`com.xj.*` = v5, `DeepLinkActivity` = v6); family-labeled installs without known
      classes fall back to `versionName` major; label demoted to brand naming only
      (`brandMatches` — BannerHub takes banner labels, GameHub Lite takes every other verified
      variant, so arbitrary rebrand labels like the live Ludashi install now verify). Result
      cached per package keyed on `lastUpdateTime`. New `isVerifiedPcLauncher` is the Phase 3
      shortcut-routing gate.
- [x] `GameHubFamilyAdapter` fixed: the fingerprint selects the target component (v6
      `DeepLinkActivity` recipe unchanged; v5 targets the exported `GameDetailActivity`);
      extras naming shared per the verified bundles. `PcLauncherAdapters.forType` takes an
      optional `PackageManager`; pm-less callers (UI metadata) assume v6.
- [x] Tests (16, all green): fingerprint resolution incl. spoofed-package rejection and
      component-beats-version; brand attribution; per-generation intent shapes under
      Robolectric; id validation; GameNative shape; no-contract launchers.
- Sec: no new permissions (QUERY_ALL_PACKAGES already held for the launcher role).

### Phase 2 — Windows card + directories  (CODE DONE 2026-07-16)
- [x] Windows card detail screen gets its own branch: Games Directory + count, "Import PC
      Games" (returns to the card on back) and "Scan For PC Games" actions; no Emulator row,
      no Supported Files section; shared card actions kept.
- [x] `WindowsLibrarySetup` (core-data): one place that creates the card, finds or creates
      `<ROM Root>/windows` (+ `import/` drop-folder), assigns the directory, and clears stray
      extensions. The card stays ROOT-MANAGED (`treeUri` null, scanning walks the roots'
      windows subfolders via `windowsFolders()` surfaces); an explicitly picked folder always
      wins. Runs on card-detail open and after every PC scan (self-healing). New ROM roots now
      persist WRITABLE so creation works; old read-only roots degrade to find-only with an
      honest `FolderUnavailable` state.
- [x] `LocalSteamDiscovery` reworked onto the same surfaces — fixing the live bug where the
      card's empty `treeUri` left discovery permanently dead — and skips the `import/`
      drop-folder.
- [x] Tests (8, all green): find-vs-create across roots, read-only degradation, idempotence,
      explicit-pick override, extension clearing, surface listing.
- Sec: folder creation only inside the SAF-granted ROM root.

### Phase 3 — Pin workflow (shortcut routing)  (CODE DONE 2026-07-16)
- [x] Shared `PcShortcutImporter` (feature-launcher): the one funnel behind pins, legacy
      captures, and harvests. Entity-first writes; three-shape dedupe (launch handle, then
      normalized title within the card — a shortcut MERGES into its folder-imported game,
      attaching its launch handle); GameNative `game_<appid>` ids and explicit
      `steamAppId`/`app_id` intent extras link STEAM instantly through the
      `PcGameAchievementLinker` seam (bound by feature-achievements); GameHub `localGameId`
      internal ids are never trusted as appids.
- [x] `PinShortcutActivity`: verified hosts route to the Windows card; the store runs
      synchronously (bounded) before `finish()` so the write cannot be lost to process death;
      non-PC hosts keep the collection behavior.
- [x] Legacy `INSTALL_SHORTCUT`: confirm-notification flow unchanged (unauthenticated
      broadcast); on confirmation a verified PC-launcher shortcut routes to the Windows card.
- [x] Missing-setup prompt: entity saved regardless; `WindowsSetupNotifications` fires at pin
      time, and a one-time XMB dialog (DataStore-flagged, consumed on next open; A = Library
      Manager, B = later) backs it up. The XMB harvest path (`importGameShortcuts`) also
      drops its label heuristic for the shared gate + importer.
- [x] Tests (7 new, all green): GameNative pin + appid link; dedupe by handle; title merge
      with handle attachment; setup-flag on missing library; legacy steamAppId link;
      localGameId never linked; intent-uri dedupe.
- Sec: pin acceptance unchanged; only PFP-built or user-confirmed intents are parsed.

### Phase 4 — Import PC Games menu rework  (CODE DONE 2026-07-16)
- [x] Import PC Games now lives ONLY under the Windows card (the top-level Library Manager row
      is gone; the games context-menu entry stays); opening it self-heals the card + folders.
      Auto-Import rows and `harvestLauncher` dropped; the Home-role row remains, reworded as
      the "Add To Home Capture" status for the pin workflow.
- [x] Export scan reads the `<windows>/import/` drop-folder(s) via
      `WindowsLibrarySetup.importFolders()` — never the windows folder root; `.desktop`
      `rawPath` kept for Winlator; second-pass emu import unchanged; messages point users at
      `<windows>/import`.
- [x] Add by ID uses the Phase 1 per-variant adapter selection (already wired); launcher rows
      point to Add-to-home / export-to-import as the alternatives.
- [x] Tests: `importFolders` resolves only existing drop-folders under the windows surfaces
      (9/9 in WindowsLibrarySetupTest); per-variant intent shapes already pinned by the
      Phase 1 adapter tests.
- Sec: export files remain untrusted input (existing sanitizer path unchanged).

### Phase 5 — Auto-match, mapping, classification  (CODE DONE 2026-07-16)
- [x] Shortcut-to-folder mapping join in `LocalSteamGameImporter`: normalized title, then the
      STEAM-NAME BRIDGE — the appid's official store name via the keyless storefront
      `appdetails` call (`SteamAppListResolver.officialNameOf`), matched exactly. Merged
      entries carry launch (shortcut) + tracking (folder); unmapped folders become their own
      game — never guesses.
- [x] Auto-match ordering per section 5: windows games are folder-first (one discovery pass
      per run) — an emu-marked folder links LOCAL_STEAM with its own appid + classification;
      no folder falls to the STEAM ladder; the unmatched reason now says honestly that no
      Steam-emulator data exists in the game folders.
- [x] Ownership persisted at scan time: DB v35 adds `ownership` to `provider_game_links`;
      `LocalSteamOwnership` derives OWNED / NOT_IN_LIBRARY / null-UNKNOWN (empty cache never
      classifies), refreshed by every scan AND every completed Steam account import; an OWNED
      emu copy gets BOTH provider links (appid equality beats any title ladder). The coins
      screen's sync-source line reads the state neutrally ("Local copy — not in your Steam
      library, tracked locally"), silent when unknown.
- [x] Discovery depth 3 -> 4 (Unity nesting; the FF pixel remasters become discoverable).
- [x] Tests (17 across the module's new/updated suites, 128 module-wide green): the live
      FFX/X-2 bridge pair, the squashed-title merge, the never-guess case, both-links-when-
      owned, the UNKNOWN-vs-NOT_IN_LIBRARY rules, folder-first matcher ordering.
- Sec: all folder access via the card's SAF grant; appids digits-validated before any web
  call; the appdetails call is keyless (nothing sensitive in its URL).

## 7. Known bugs this plan fixes

- Windows card uses the generic console layout (emulator row, extensions, unset directory).
- `PinShortcutActivity` files PC-launcher pins as `android`/`ANDROID_APP` collection entries.
- `importGameShortcuts` gates PC routing on fragile label substrings.
- Windows card created silently with no directory (`tree_uri` empty on the live device), so
  LOCAL_STEAM discovery never runs.
- `GameHubFamilyAdapter` hardcodes the v6 component — Add by ID targets a class that does not
  exist in v5-lineage variants (live: `com.ludashi.aibench` 5.1.7).
- Titles imported from shortcut ids keep squashed/renamed forms (`MARVELCosmicInvasion`,
  `_` for `:`), no dedupe against folder names without the mapping join.
- Companion-plan bug: `SETTINGS_SEARCH_DEPTH` 3 misses Unity-nested `steam_settings`.

Dictation complete 2026-07-16; implementation not started (user gate stands).

---

*Play Field Portal · Internal Plan · Windows Library Refactor*
