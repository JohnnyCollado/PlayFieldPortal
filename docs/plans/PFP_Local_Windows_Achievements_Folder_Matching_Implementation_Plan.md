# Play Field Portal — Local Windows Achievements: Folder-Picked Matching Implementation Plan

**Status:** Implemented in one pass (2026-09-26). See §9 for the baseline note and §7 for how the open questions were resolved.
**Repository baseline reviewed:** `achievement-ambience` at `2dfad125` (2026-09-25). Recheck HEAD before editing; this plan names a database version and file paths that move.
**Primary modules:** `feature/feature-achievements`, `feature/feature-xmb`, `feature/feature-settings`, `core/core-data`, `core/core-ui`.
**Mockup:** https://claude.ai/artifact/VHR428351LJo3EZR4LBUQ8 (10 artboards, 1920×1080 — architecture, the six per-game states, the three batch screens).
**Related plans:** `PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md`, `PFP_Metadata_Overrides_And_PC_Providers_Implementation_Plan.md`, `PFP_Achievements_Game_Page_Implementation_Plan.md`.

## 1. Goal and product contract

Local Steam achievement discovery currently assumes Windows games live under the windows library's scan surfaces. That assumption was correct when the Windows card was a scanned root. It is no longer true: Windows games now enter the library through OS pins, launcher exports dropped in `<ROM root>/windows/import`, `.pfpgame` restores and Add-by-ID, while the **game folders themselves live wherever the user's Wine/Winlator setup put them**. `LocalSteamDiscovery` therefore walks a tree that increasingly does not contain the games it is looking for.

This plan replaces library-wide discovery with **user-pointed discovery plus a persistent folder registry**.

Three entry points, one registry:

| Entry point | Scope | Writes to game folders? |
| --- | --- | --- |
| Shiba page ▸ Auto-Match ▸ "No" | One game, one picked folder | Only with explicit consent on that game |
| Import PC Games ▸ Batch Match Local Games | One picked parent folder of game folders | Only for games the user checks in the convert picker |
| Automatic freshness scan (wizard, Auto-Detect, Scan This Console) | `<ROM root>/windows/import` exports only | Never |

Rules that hold across all three:

- A picked folder is **registered**, so no later sync needs to re-walk a tree to find it.
- A folder's Steam app id comes from `steam_appid.txt` when present. When it is absent, the app id is resolved from the game's **display title through the 5-rule storefront matcher**, and the confirmed id is **written back as `steam_appid.txt`** so the folder identifies itself to PFP and to the emulator from then on.
- A guessed id is never written unattended. Batch runs auto-write only at EXACT/HIGH confidence; anything less is deferred to the per-game flow where a person decides.
- An unreachable folder, a revoked grant and an unreadable progress file are all **unknown**, never "nothing earned".
- The automatic scan never writes to a game folder and never walks for emulator data. All folder writes sit behind an opt-in toggle plus an on-screen statement of what will be written.

Out of scope: any change to how coins are mapped, displayed, fingerprinted or synced once a game is linked. `LocalSteamSource`, `SteamCoinMapper`, `LocalSteamReturnChecker` and `LocalSteamCheckStrategy` keep their current behavior; they simply resolve folders through the registry instead of through a scan.

## 2. Existing implementation and reuse

- `LocalSteamDiscovery` already contains the correct folder-inspection logic — the depth-4 `steam_settings` search (Unity nests it under `<Game>_Data/Plugins/x86_64/`), the `configs.user.ini` `local_save_path` redirect ladder, the `saves/[<appid>/]achievements.json` fallback convention, the `trackable` opt-in gate and the `hasSchema` flag. **None of that logic changes.** What changes is where it is pointed and that its result is persisted. Its 30-second scan cache and `scanMutex` remain useful for the batch path.
- `LocalSteamSchemaGenerator` + `LocalSteamSchemaWriter` already write the kit and swap the DLL, create-only and non-destructive, with the emu DLL names XOR-obfuscated and `installEmuDll` guarding against re-swapping an already-emulated folder. Extend, do not rewrite.
- `LocalSteamConvertPickerController` is already framework-agnostic and owned per ViewModel — it survives as-is. Its dialog does not.
- `LocalSteamGameImporter`'s mapping ladder (exact normalized title, then the Steam-name bridge through `SteamAppListResolver.officialNameOf`) is the right rule for attaching a discovered folder to a library game. Keep it; move its input from `discovery.scanAll()` to the batch matcher's scan result.
- `StorefrontMetadataResolver.resolve(game, allowAutoLink, ignoreStoredIdentity)` **is** the identification ladder this plan needs: stored storefront identity → authoritative captured id → `StorefrontTitleNormalizer` 5-rule candidates → provider search → `StorefrontMatchScorer` → auto-link at EXACT/HIGH, `NeedsConfirmation` otherwise. Call it; do not reimplement any part of it.
- `StorefrontMatchUi` / `StorefrontMatchPanel` already render exactly this decision, including the normalized `query` string (so a bad match has a visible cause), scored rows with signal chips, More Information, and the always-present "No correct match" escape hatch. Reuse the panel verbatim — this also satisfies the controller-nav requirement for that screen for free.
- `feature-achievements` already depends on `feature-artwork` (SteamGridDB for Steam-id resolution), so the resolver needs no new module edge.
- `ShibaCoinsViewModel` already owns an `AutoMatchStep` state machine with controller routing in `onGamepadAction`; the new steps extend it rather than introducing a parallel mechanism.
- SAF tree picking from an XMB detail screen has precedent (`ArtworkStudioScreen`, `AppDetailScreen`, `VideoDetailScreen` all use `rememberLauncherForActivityResult`), but all of those use `OpenDocument`. This plan introduces the first `OpenDocumentTree` in `feature-xmb`, which additionally requires `takePersistableUriPermission`.
- `PcGameScanner.scan(overrideFolder)` is already the one shared PC pass for every entry point. The only change is removing the emu reconcile from it.

### Audit findings this plan also corrects

1. `LocalSteamConvertPickerDialog` is imported only by `LibraryManagerScreen`. The XMB Windows-card "Scan This Console" path runs the same `PcGameScanner.scan()` and silently discards `EmuGameImportResult.missingSchema`, despite the controller's own KDoc claiming both surfaces are served.
2. Every automatic scan (first-run wizard, Auto-Detect, root autoload, Scan This Console) currently pays for a full recursive SAF walk of every windows surface via `emuGameImporter.import()`, on every pass. After this plan the automatic path does no emulator work at all.
3. `LocalSteamConvertPickerDialog` is a stock Material 3 `AlertDialog` with `Checkbox` rows: no focus model, no controller input path, no touch/controller split, and no `BringIntoViewRequester` scrolling. It predates the current panel conventions.

## 3. Data model

### New table — `local_steam_folders` (database v50 → v51)

One row per resolved app id, written when a user points PFP at a folder.

| Column | Purpose |
| --- | --- |
| `app_id` (PK) | The Steam app id the folder resolves to |
| `folder_name` | The game folder's display name, for pickers and reports |
| `tree_uri` | The granted SAF tree the folder was reached through |
| `folder_doc_id` | The game folder itself |
| `settings_dir_doc_id` | `steam_settings`, the write target for a kit |
| `settings_parent_doc_id` | The folder holding `steam_settings` — the DLL folder, the save-redirect base |
| `progress_doc_id` | The resolved `achievements.json`, nullable — absent means "not played yet", never "no unlocks" |
| `has_schema` | Whether the emu's own `achievements.json` schema exists |
| `appid_source` | `MARKER` / `STORED_IDENTITY` / `TITLE_MATCH` — how the id was established, for the report and for a later re-identify |
| `last_seen_at` | Last time the folder was successfully read |

This is the point of the redesign. A sync currently costs a deep SAF tree walk to re-find a folder; afterwards `findByAppId` is a primary-key read.

**Open question (see §7):** whether the key should be `(app_id, tree_uri, folder_doc_id)` instead, to allow two installs of one app id.

### Grants

Each `tree_uri` is held with `takePersistableUriPermission` at pick time. A revoked grant or a moved folder surfaces as unknown and offers a re-pick; it never clears earned coins and never reports zero.

### Backup

The registry joins the existing optional achievement zip entries: present entries restore, older archives restore unchanged. Rows whose grant does not survive the restore are kept and re-picked on demand rather than dropped.

## 4. The identification ladder

For one picked game folder, in order:

1. **`steam_settings/steam_appid.txt`.** Authoritative. Nothing else runs.
2. **Stored storefront identity** (`GameStorefrontIdentityDao`) for this game and the Steam store. Free — no request, no search.
3. **5-rule title resolution.** `StorefrontMetadataResolver.resolve(game, allowAutoLink = false)`, Steam provider only. `allowAutoLink = false` on purpose: the resolver's own auto-link writes a *storefront identity* row, which is a different relationship from a LOCAL_STEAM achievement link, and this path has a file-system side effect that must not happen inside a resolver call. Take the resolution, decide, then act.
   - `Linked` at EXACT/HIGH → use that app id.
   - `NeedsConfirmation` → open `StorefrontMatchPanel` with its candidates; the user chooses, or chooses "No correct match".
   - `NoMatch` / `Unavailable` → terminal, with the store's own reason. An unreachable store is never reported as a game that does not exist.
4. **Write the marker back.** `steam_settings/steam_appid.txt` is created with the confirmed id, creating `steam_settings` when absent.

### The anchor problem

Today `steam_settings` *is* the anchor: `settingsDirDocId`, `settingsParentDocId` and the save-redirect base are all derived from finding it. A folder with no `steam_settings` has no anchor, and `steam_settings` is only meaningful sitting beside the `steam_api` DLL.

So discovery needs `findDllFolder(tree, gameFolderDocId, depth = 4)` — the same depth-4 walk, looking for the Steam API DLL instead of the settings folder. It reuses `LocalSteamSchemaWriter.EMU_DLL` / `EMU_BACKUP_DLL` and adds the 32-bit name the same obfuscated way; a `steam_api.dll`-only game still **identifies** correctly even though the emu swap will report `NoTargetDll`.

### Resulting outcome taxonomy

| Finding | Outcome |
| --- | --- |
| Marker present | Link straight from it |
| No marker, Steam DLL found, ladder confident | Write marker → register → link → kit step |
| No marker, Steam DLL found, ladder ambiguous | Match picker → user chooses → write marker |
| No marker, Steam DLL found, no match or user says none | Terminal; nothing written |
| No Steam DLL anywhere | Terminal: not a Steam build, nothing to emulate |

That last row is the honest version of today's "no Steam-emulator data" message.

### Consent

Writing `steam_appid.txt` is categorically lighter than the kit install: it touches no saves and no DLL, and the writer's create-only rule means an existing marker is never overwritten. It therefore sits under the **Local Steam tracking** opt-in and is stated plainly on the confirm panel. The kit install (schema, stats, `configs.user.ini`, `saves/`, DLL swap) stays behind the **Goldberg installer** opt-in and the save-backup warning.

## 5. Flows

### 5.1 Per game — Shiba page

Auto-Match → `CONFIRM_COPY` → **No** → pick that game's folder → the ladder above → one of:

- **Kit present** → register, link LOCAL_STEAM, classify ownership, sync. An app id also found in the Steam owned-games cache keeps the existing OWNED double-link to STEAM.
- **Kit missing** → confirm panel offering **Install & Link** / **Link Only** / **Cancel**. Link Only tracks the game at 0% with its real coin list and writes nothing further to the folder; it exists so a user can see the achievement list before authorising a DLL swap. With the Goldberg installer toggle off, Install & Link is disabled with a line pointing at the setting.
- **Unidentifiable** → terminal state naming the actual cause.

`matchSingleAsLocalSteam`'s existing name ladder survives only as a **pre-check**: if the registry already holds a folder matching this game, link straight from it and never ask. A batch-matched game must not ask again.

### 5.2 Batch — Import PC Games ▸ Local Windows

Pick a parent folder → inspect every child folder → register all → partition:

- **Ready** (`hasSchema`): link through `LocalSteamGameImporter`'s mapping ladder and sync. A folder with no library game stays a tracked local entry and appears in Shiba Coins after sync, exactly as today.
- **Convertible** (no schema, id known): the convert picker. Converted games fall into the ready path and sync in the same run.
- **Needs identifying** (no marker, title resolution below HIGH): reported as a count, deferred to the per-game flow. Batch never writes a guessed marker.

### 5.3 Automatic

`PcGameScanner.scan()` reads `<ROM root>/windows/import` for exported game files and nothing else. Freshness for local games comes from the registry — `LocalSteamCheckStrategy` fingerprints each registered folder's progress file and rebuilds only what changed, from cached schema.

## 6. Execution Task Index

Each task is one reviewable change with its own tests. A task that discovers the baseline has moved stops and reports rather than inventing scope.

### Task 1 — Baseline confirmation note

**Files:** read-only across `LocalSteamDiscovery.kt`, `LocalSteamSource.kt`, `LocalSteamGameImporter.kt`, `PcGameScanner.kt`, `RomRootScanRunner.kt`, `StorefrontMetadataResolver.kt`, `ShibaCoinsViewModel.kt`, `LibraryManagerScreen.kt`, `PFPDatabase.kt`.

- Confirm the actual database version at HEAD and the next free migration number.
- Confirm `StorefrontMetadataResolver`'s current signature, `Resolution` shape and `MatchConfidence.autoLinkable` semantics.
- Enumerate every current caller of `LocalSteamDiscovery.scan()` / `scanAll()` / `findByAppId`.
- Confirm whether the XMB Windows card still drops `missingSchema`.

**Done when:** a short note records the real version, the real caller list, and any drift from this plan. No behavior changes.

### Task 2 — Folder registry storage and migration

**Files:** new `LocalSteamFolderEntity.kt` + `LocalSteamFolderDao.kt` (`core-data`), `PFPDatabase.kt`, `DatabaseModule.kt`, exported `51.json`, `BackupDao.kt` and the backup/restore paths.

- Create the table of §3 with its DAO (`upsert`, `getByAppId`, `getAll`, `deleteByAppId`, `touchLastSeen`).
- Migration creates the table only. Do **not** attempt to seed it from a tree walk at migration time — existing LOCAL_STEAM links keep working through the fallback scan until their folder is re-picked or batch-matched.
- Add the registry to backup/restore as an optional entry; verify an older archive still restores.

**Done when:** `Migration50To51Test` passes, the schema is exported, and a backup round-trip preserves the registry while an older archive restores unchanged.

### Task 3 — Scoped discovery

**Files:** `LocalSteamDiscovery.kt`, `LocalSteamSchemaWriter.kt` (constants only).

- `inspect(treeUri, docId): LocalSteamGame?` — the existing private `inspect` logic exposed for one picked folder.
- `scanFolder(treeUri, docId): List<LocalSteamGame>` — the same across the children of a picked parent.
- `findDllFolder(tree, gameFolderDocId, depth = 4)` — the anchor for a folder with no `steam_settings`; reuses the obfuscated DLL names and adds the 32-bit name.
- `findByAppId` reads the registry first and only falls back to a cached scan.
- `scan()` / `scanAll()` keep working so nothing already wired breaks.

**Done when:** unit tests over a fake SAF tree cover nested `steam_settings`, the redirect vs `saves/` fallback, a folder with a DLL but no settings folder, and a folder with neither.

### Task 4 — Identity resolution and marker write-back

**Files:** new `LocalSteamIdentityResolver.kt` (`feature-achievements`), `LocalSteamSchemaWriter.kt`.

- Implement the §4 ladder, returning `Resolved(appId, source)` / `NeedsConfirmation(StorefrontMatchResult)` / `NoMatch` / `Unavailable(reason)` / `NotASteamBuild`.
- Add `APPID_FILE = "steam_appid.txt"` written under `MIME_BINARY` (as with `configs.user.ini`, `text/plain` makes `ExternalStorageProvider` append an extension), plus creating `steam_settings` via `ensureDir` when absent.
- The create-only rule stands: an existing marker is never overwritten.

**Done when:** tests cover marker short-circuit (asserting the resolver is never called), stored-identity hit, EXACT title write-back, ambiguous → confirmation with nothing written, no-DLL terminal, and an existing marker left untouched.

### Task 5 — Per-game linker

**Files:** new `LocalSteamFolderLinker.kt`, `LocalSteamOwnership.kt` (no change expected), `AchievementController.kt`.

- `link(gameId, treeUri): LinkOutcome` — inspect, resolve identity, register the folder, `linkManually(LOCAL_STEAM, appId)`, classify ownership, keep the OWNED double-link to STEAM.
- Outcomes: `Linked`, `NeedsKit(game)`, `NeedsConfirmation(result)`, `NoEmuData(reason)`, `Failed`.
- Take the persistable grant before any read that must survive the process.

**Done when:** each outcome is covered, including installer-off (`NeedsKit` still returned, conversion refused) and a revoked grant mid-flow.

### Task 6 — Shiba page flow

**Files:** `ShibaCoinsViewModel.kt`, `ShibaCoinsScreen.kt`, `ShibaCoinsTarget.kt` if routing needs it, `StorefrontMatchPanel.kt` (reuse only).

- `AutoMatchStep` gains `PICK_FOLDER`, `IDENTIFY`, `CONFIRM_KIT`, `NO_EMU_DATA`.
- `chooseAutoMatch(false)` opens `PICK_FOLDER` instead of running the name ladder; the registry pre-check of §5.1 short-circuits it.
- `OpenDocumentTree` launcher in `ShibaCoinsScreen` with `takePersistableUriPermission`.
- `IDENTIFY` renders `StorefrontMatchPanel` from a `StorefrontMatchUi`, plus the strip naming the file that will be written.
- Controller routing extends the existing `when (s.autoMatchStep)` block; every step has a Back path.

**Done when:** view-model tests cover each transition, cancel at each step, the pre-check short-circuit, a pick that resolves nothing, and a lost grant offering a re-pick. Matches artboards 1–4c of the mockup.

### Task 7 — Batch matcher

**Files:** new `LocalSteamBatchMatcher.kt`, `LocalSteamGameImporter.kt` (input source only), `LocalSteamConvertPickerController.kt` (unchanged).

- `run(treeUri, onProgress): BatchReport` implementing §5.2, including the confidence rule that batch auto-writes markers only at EXACT/HIGH.
- Report counts: discovered, linked to a library game, tracked without one, converted, needs identifying, failed — plus per-failure reasons for the summary.
- One run at a time; cancellable; never runs concurrently with a sync.

**Done when:** tests cover the partition, the mapping ladder, convert→sync ordering, the LOW-confidence deferral, and an empty folder.

### Task 8 — Convert picker on the current nav structure

**Files:** new `LocalSteamConvertPanel.kt` (`core-ui`), delete `LocalSteamConvertPickerDialog.kt`, update `LibraryManagerScreen.kt`.

- Rebuild in the `StorefrontMatchPanel` idiom: full-screen scrim, `widthIn(min = 360.dp, max = 760.dp)`, panel fill `0xF20A0A14`, `RoundedCornerShape(8.dp)`, focus-driven rows using the caller's `focusFill` / `focusEdge`, `BringIntoViewRequester` scrolling (frame the focused row by geometry, never by scroll arithmetic).
- Controller prompt line hidden under `showTouchControls`: `Up/Down Rows • Select Toggle • △ All/None • Start Install • B Cancel`. Mirrored touch action row.
- Focus index lives in the hosting ViewModel's state; input arrives through its `onGamepadAction`.
- Rows carry the achievement count and an explicit unselectable **No list on Steam** state rather than failing at write time.
- Add a **Skip & Sync** exit that links the ready folders without writing to any game folder.

**Done when:** focus movement, toggle, select-all/none, the unselectable row and the confirm payload are covered by tests, and the touch path renders without the prompt line. Matches artboard 6.

### Task 9 — Local Windows group in Import PC Games

**Files:** `LibraryManagerScreen.kt`, `LibraryManagerViewModel.kt`.

- New `SettingsGroup("Local Windows")` with **Batch Match Local Games** (tree pick), **Matched Local Games (N)** (registry listing with per-entry Forget), and **Goldberg Installer** mirroring the Shiba Coins toggle.
- Reword **Scan Import Folder** to say the automatic scan always reads `<ROM root>/windows/import` and this row is a one-time pick.
- The save-backup warning strip below the group.

**Done when:** rows are controller-reachable in order, the picker opens from the group, and the report renders both on screen and in the tray. Matches artboards 5 and 7.

### Task 10 — Narrow the automatic scan

**Files:** `PcGameScanner.kt`, `PcGameScannerTest.kt`, `RomRootScanRunner.kt` (comment only).

- Remove the `emuGameImporter.import()` call and the emu note from the scan message; keep `EmuGameImportResult` in `PcScanReport` only if something still reads it, otherwise remove it from the report and fix `newGames`.
- `scan(overrideFolder)` keeps its manual one-shot behavior unchanged.

**Done when:** a test asserts the automatic path never touches the importer, and the wizard/Auto-Detect path no longer performs a recursive emulator walk.

### Task 11 — XMB Windows card entry point

**Files:** `GameContextMenuItems.kt` or the card's context menu source, `XMBViewModel.kt`, `XMBShell.kt`.

- Add **Batch Match Local Games** to the Windows card's context menu, opening the same controller and `LocalSteamConvertPanel`.
- Route the panel above the card in `onGamepadAction`, the way the coins page is routed.

**Done when:** the XMB path and the settings path produce identical reports from the same folder, and the card no longer silently discards convertible folders.

### Task 12 — Documentation and device verification

**Files:** `README.md` §4.20, `CHANGELOG.md`, this plan's §7.

- Rewrite §4.20 around the two pick surfaces, the registry and the marker write-back. The current text is written around `<ROM Root>/windows/<Game>/` being where local games live, which stops being true.
- Device pass: per-game pick on controller and touch; a folder with no marker resolving by title; the marker appearing on disk; the convert panel on both input paths; a batch run over a real emulator library; a launch/return check on a registered folder; a revoked grant; an offline start.

**Done when:** the README no longer describes the retired model and the device checklist is recorded with outcomes.

## 7. Open questions — resolved

1. **`Link Only` was kept.** It is the only way to see a game's coin list before authorising a DLL swap, and refusing to show the list until the user agrees to one is the wrong trade. It costs one extra outcome and one extra button; `LocalSteamFolderLinker.linkWithoutKit` is the whole implementation.
2. **Registry key: `app_id` alone**, as §3's table states. `findByAppId` is the one question every sync asks and the app id is the only thing a provider link carries, so the primary-key read is the point of the redesign. The consequence is recorded in `LocalSteamFolderEntity`'s KDoc: two installs of one app id collapse onto one row and the later pick wins. A composite key would make the lookup return more than one row and force a caller to choose between them, which is a choice no sync can make correctly.
3. **Marker consent stays under Local Steam tracking.** `LocalSteamIdentityResolver.markerWriteAllowed()` is the single gate. With tracking off, an id that was established is still returned and still usable — it is simply not written into the folder (`Resolved(written = false)`), so turning tracking off never breaks a link that already exists.

### Deviations from the plan, and why

- **Task 7 / Task 8 — the mapping ladder lives in one place.** `LocalSteamGameImporter` was kept (as the plan said) but became `reconcile(folders)` instead of `import()`: it is handed the batch matcher's ready pile rather than calling `discovery.scanAll()` itself. `LocalSteamBatchMatcher` delegates to it and syncs exactly the game ids it reports, so the ladder and the Steam-name bridge are not duplicated. `EmuGameImportResult` lost `missingSchema` (the matcher partitions convertibles itself) and gained `linkedGameIds`.
- **Task 8 — the convert picker's focus index lives in `LocalSteamConvertPickerController`.** The plan said "the hosting ViewModel's state"; the controller *is* per-ViewModel state, and putting focus there is what stops the XMB card and the Library Manager drifting apart on a controller. The controller also grew `onGamepadAction`, `skip()` and probed rows, so it is no longer "unchanged".
- **Task 8 — rows are probed before the panel opens.** Showing an honest achievement count and an unselectable "No list on Steam" needs the schema up front, so `LocalSteamSchemaGenerator` gained `probe(appId)` with a per-process cache that the following `generate()` re-uses. One request per game, not two.
- **Presence reconciliation was in scope after all.** `AchievementPresenceReconciler` read `discovery.scan()`, which no longer sees a picked folder, so registered links would have been demoted to history on the next pass. It now unions the registry in, and never demotes a *registered* app id even when its folder is unreachable — unmounted storage is unknown, not "the game is gone". Forget is the only way out.
- **Robolectric was added to `feature-achievements`** (test-only, `targetSdk = 36` pinned the same way `feature-artwork` does it) so Task 3's fake-SAF-tree tests can use a real `MatrixCursor` and a real `Uri`.
- **`GamepadAction.HOME` (Start) is claimed by the convert panel.** The XMB normally routes Start to the notification panel above every overlay; the convert panel is checked ahead of that branch, because inside it Start means Install and being taken off the screen mid-authorisation would be wrong.

## 8. Verification

```bash
./gradlew :core:core-data:testDebugUnitTest --tests "com.playfieldportal.core.data.database.*"
```

```bash
./gradlew :feature:feature-achievements:testDebugUnitTest
```

```bash
./gradlew :feature:feature-settings:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

## 9. Baseline confirmation (Task 1)

Read-only pass at `252b0773` (2026-09-26), one commit past the `2dfad125` this plan was written against.

- **Database version was 50**, so `local_steam_folders` is **v51** exactly as the plan says. `MIGRATION_50_51` is registered in `DatabaseModule` and `51.json` is exported by the build.
- **`StorefrontMetadataResolver.resolve(game, allowAutoLink = true, ignoreStoredIdentity = false)`** — signature, `Resolution` shape (`Linked` / `NeedsConfirmation` / `NoMatch` / `Unavailable`) and `MatchConfidence.autoLinkable` (`EXACT || HIGH`) are all as described. `StorefrontMatchResult` carries `store`, `confidence`, `best`, `alternatives`; `ScoredStorefrontCandidate` takes `(candidate, signals)` and derives its score, so the plan's "5-rule candidates → scorer → auto-link at EXACT/HIGH" is accurate.
- **Callers of `LocalSteamDiscovery`** were: `LocalSteamGameImporter.import()` (`scanAll`), `LocalSteamSource` (`findByAppId`, twice), `AchievementPresenceReconciler` (`scan`), `AchievementAutoMatcher` (`scanAll`, via its own folder cache) and `XMBViewModel:6987` (`scanAll`, for the per-game Install Goldberg action). The last two still use the legacy scan; both are explicit per-game actions on a game the user is looking at, so they degrade to "nothing found" rather than to a wrong answer, and the folder-pick flow is the replacement offered on the same page.
- **Drift found — the plan's audit finding 1 is stale.** `XMBViewModel` did *not* silently discard `EmuGameImportResult.missingSchema`: it started the same convert picker the Library Manager does (at what was line 7358), gated on the Goldberg opt-in. Findings 2 and 3 were both accurate. Both scan paths now do no emulator work at all, and the dialog has been replaced.
