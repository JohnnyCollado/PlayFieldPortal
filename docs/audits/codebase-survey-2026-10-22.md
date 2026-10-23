# Codebase survey — 2026-10-22

Branch `fix/battery-zero-percent` at `f2daf964`. Read-only survey: nothing was edited, built or run.

**How to read the tags**

- **Checked** — I opened the cited lines myself and confirmed the defect.
- **Traced** — a reviewer traced it from the UI or caller down to the effect and quoted the code; I did not re-open it.
- **Plausible** — the code path exists, but one link in the trigger chain (a real input, device behaviour) is unconfirmed.
- **Latent** — real, but no user can hit it today (code is gated off or has no caller).

Scope covered: every module in `settings.gradle.kts` (app, studio, core/*, feature/*, discord). Not covered: `.claude/worktrees/*` (stale copies), build output, `assets/`, `dist/`.

---

## 1. Logic bugs

### High — can lose or hide user data

| # | Defect | Source | Tag | Proposed fix |
|---|---|---|---|---|
| L1 | Root-managed memory cards mark an SD card's games **Missing** at boot if the card mounts late. The root's folder listing turns "unreadable" into "empty", so that root is simply dropped from the scan and the survey still looks trusted. The late-mount guard from `01431a7d` covers the tree walk but not this entry point. | `feature/feature-library/.../scanner/ScanSourceResolver.kt:56-66` calls `RomScanner.listSubfolderNames` (`RomScanner.kt:552-559`), which uses the empty-on-failure `querySafChildren` (`core/core-data/.../saf/SafChildren.kt:104-105`) | Checked | Use `querySafChildrenOrNull` in `listSubfolderNames`; when any root can't be listed, make the scan report `presentRomPaths = null` so `LibraryScanner` skips removals. |
| L2 | One-time startup migrations are marked **done even when they throw**, so a half-finished migration never retries. | `core/core-data/.../seeder/LibraryConsolidation.kt:89-96` (`runCatching{…}.onFailure{log}` then the flag is written unconditionally); same pattern in `StartupDataPrep.kt:45-61` | Checked (LibraryConsolidation), Traced (StartupDataPrep) | Write the flag only when `runCatching(...).isSuccess`. |
| L3 | The Windows duplicate merge deletes the losing row directly, so its **play sessions, achievement links, store identities, artwork records and UMD slots** are lost or orphaned. A proper merge already exists. | `LibraryConsolidation.kt:150-155` (`gameDao.deleteById(loser.id)`); the correct helper is `GameDao.kt:206` `mergeRows` | Checked | Call `gameDao.mergeRows(mergedSurvivor, loser.id)` instead of update + delete. |
| L4 | Creating a custom category can **overwrite an existing one**. The id is `custom_<slug>_<maxPosition+1>` and is saved with REPLACE; after a reorder and a delete, the same id comes back and REPLACE cascade-deletes the old category's items. | `core/core-data/.../repository/CategoryRepositoryImpl.kt:105-118`; `CategoryDao.kt:35-36` (`OnConflictStrategy.REPLACE`) | Checked (code), Plausible (whether the UI allows a duplicate name was not confirmed) | Use a collision-free suffix (UUID, or loop until `getById(id) == null`) and insert new categories with ABORT/IGNORE. |
| L5 | Steam **errors other than "profile not public" are saved as "nothing earned"**, flipping every stored coin to unearned until the next good fetch. | `feature/feature-achievements/.../provider/steam/SteamRemoteDataSource.kt:177-185` (falls through to `Success(stats.achievements…)` when `stats.success == false`) | Checked (code), Plausible (which Steam errors return a body) | Return `Failed` when `stats.success == false` and the error isn't the known "no stats" case. |
| L6 | **Music player points at the wrong MediaPlayer** after a track fails to load. `next()` runs inside the `apply{}` block before `player =` is assigned, so the outer assignment overwrites the working player with the dead one. Pause/stop then act on the dead one and the real one leaks and keeps playing. | `feature/feature-xmb/.../music/MusicPlayerController.kt:120-145` | Checked | In `onFailure`, set a flag; after the assignment, release the failed player and post `next()`. |

### Medium — wrong behaviour users will notice

| # | Defect | Source | Tag | Proposed fix |
|---|---|---|---|---|
| L7 | Discord **QR login reopens in a stale state**. The ViewModel is activity-scoped and only calls `start()` in `init`; after a sign-out, reopening sees the old `Success` and closes instantly, and after Cancel the old QR shows with nothing polling. | `feature/feature-social/.../ui/QrLoginViewModel.kt:29-31`, `QrLoginScreen.kt:60-64` | Checked | Call `viewModel.start()` from a `LaunchedEffect(Unit)` in the screen; drop it from `init`. |
| L8 | Games with **no achievement set are re-fetched on every sync and every page open**. `recordNoSet` writes `last_checked_at` only, while "is new" and the staleness gate both key on `last_detail_at`. | `feature/feature-achievements/.../sync/AchievementSyncStore.kt:37-38`; `AchievementTrackingDao.kt:147-152`; `SyncTypes.kt:37`; `AchievementSyncCoordinator.kt:260` | Checked | Make `recordNoSet` also stamp `last_detail_at` (use `recordDetail`). |
| L9 | Achievement hub lists show the **raw scanned title, not the user's rename**. | `core/core-data/.../dao/AccountAchievementDao.kt:86,105`; `AccountAchievementSetDao.kt:92` (`COALESCE(g.title, s.title)`) | Checked | Use `COALESCE(g.user_title_override, g.scraped_title, g.title, s.title)` like the other achievement queries. |
| L10 | **Playlist counts disagree with contents.** The count counts every `playlist_tracks` row; the contents inner-join `music_tracks`. Removing a folder leaves dead rows, so a playlist reads "20 tracks" and opens empty. | `core/core-data/.../dao/PlaylistDao.kt:25-28` vs `:38-45`; same in `VideoPlaylistDao.kt:27` | Checked | Join the track table in the count; prune playlist rows when a folder is removed. |
| L11 | RetroAchievements coin is marked **unearned when its date fails to parse**, while `earnedHardcore` (presence-based) stays true. | `feature/feature-achievements/.../provider/retro/RaCoinMapper.kt:30-44` | Checked (code), Plausible (needs RA to change date format) | `isEarned = a.dateEarned != null || a.dateEarnedHardcore != null`. |
| L12 | Winlator "shared shortcut" test treats `/storage/emulated/0/Android/data/<fork>/…` as shared, so **Launch With offers forks that can't read the file** on Android 11+. | `feature/feature-launcher/.../WinlatorDesktop.kt:42` (`!startsWith("/data/")`), used at `PcLaunchRouting.kt:105` | Checked (code), Plausible (whether such paths occur) | Also exclude paths containing `/Android/data/` or `/Android/obb/`. |
| L13 | Detail pages (App, Video, Photo) can **show the previous item after a fast close-and-reopen**: their loads have no generation guard. Game Detail already has one. | `feature/feature-xmb/.../ui/app/AppDetailViewModel.kt` (`loadApp`), `VideoDetailViewModel.kt:147-158`, `PhotoViewerViewModel.kt:135-159`; compare `GameDetailViewModel.kt:937` | Plausible | Keep a `loadJob` and cancel it on each new load. |
| L14 | App Drawer: overlapping reloads can bring back a just-uninstalled app, and the **cursor is never clamped** after the list shrinks. Also runs ViewModel side effects directly in composition. | `feature/feature-appbar/.../AppDrawerViewModel.kt:105-118` and `applyFilter`; `AppDrawerScreen.kt:163-168` | Traced | Single `loadJob`; clamp `selectedIndex` in `applyFilter`; move the initial filter into `LaunchedEffect`. |
| L15 | `.m3u` playlist can **pull a same-named disc from an unrelated folder** into a set, against the "different folders never merge" rule. | `feature/feature-library/.../scanner/DiscSetBuilder.kt:200-201` | Plausible | Restrict the basename fallback to subfolders of the `.m3u`'s folder. |
| L16 | Disc-set reconcile writes back a **minutes-old copy of favourite / play time / artwork**, reverting changes made during a long scan. | `LibraryScanner.kt:131-142, 213-215`; `DiscSetReconciler.kt:106-126` | Plausible | Re-read the stored row and copy only the disc and missing fields onto it. |
| L17 | Media scanners (music, video, photo) walk folders with the empty-on-failure listing. The root `canRead()` check covers an unmounted card, but a listing that fails after that check still **replaces the library with nothing**. | `core/core-data/.../saf/SafTreeWalk.kt:63`; guard at `feature/feature-library/.../scanner/MusicScanner.kt:85` | Checked (code), Plausible (trigger) | Use `querySafChildrenOrNull` in the walk and fail the scan on a null root listing. |
| L18 | **Text colour readability clamp is shown but never drawn.** The "Adjusted for readability" notice describes a clamp that only the settings ViewModel computes; the renderer uses the raw colour. | `feature/feature-settings/.../viewmodel/DisplaySettingsViewModel.kt:457`; renderer `ThemeLook.kt:157` | Traced | Apply the clamp in the text-colour path (respecting "exact colour"), or drop the notice. |

### Reported by the owner: no way to create a non-gaming custom memory card

The XMB is built to show custom memory cards in app categories: Network, App Store, and custom non-gaming categories (`feature/feature-xmb/.../viewmodel/XmbLists.kt:121-127`, `categoryShowsCollections`). Deleting an app category also moves its cards to App Store (`core/core-data/.../repository/CategoryRepositoryImpl.kt:157-160`). Creating one is the gap.

| # | Defect | Source | Tag | Proposed fix |
|---|---|---|---|---|
| L28 | **Settings ▸ Custom Memory Cards can only create gaming cards.** The "Add to Category" picker is fed a list filtered to gaming categories, and `create` defaults to the Game category, so no app category can ever be chosen. | `feature/feature-settings/.../viewmodel/CollectionsSettingsViewModel.kt:29-32` (`filter { it.isGamingCategory }`), `:37` (`categoryId = "games"`); picker at `ui/CollectionsSettingsScreen.kt:233-240` | Checked | List every category where `categoryShowsCollections` is true, grouped "Games" / "Apps", and rename `gamingCategories`. The "e.g. RPGs, Currently Playing" sublabel (`:303`) and the "N games" count (`:317`) also need app-aware wording. |
| L29 | **The XMB has only one way in, and it's hidden.** A card can only be created from an item's Options ▸ Add to Card ▸ "New Custom Card Here…". There is no create row at the root of an app category; gaming columns don't have one either. | Only callers of `promptCreateCollection` are the Add to Card submenu: `XMBViewModel.kt:7473, 7492`; the row itself is `XmbLists.kt:570` | Checked | Add a "New Custom Card" entry to the category's Options menu (or the list's Options menu) for any category where `categoryShowsCollections` is true. |
| L30 | **From the Android column, an app's "New Custom Card Here…" makes a *game* card in Main Game.** Android is in `NON_COLLECTION_CATEGORY_IDS`, so the home falls back to Game, and the Add to Card list shows game cards only. The app is then put on a game card. | `XMBViewModel.kt:2703-2706` (`collectionHomeCategoryId` falls back to `GAMES`); `:7281` (`wantGaming … ?: true`); `XmbLists.kt:116-120` | Checked (code), Plausible (not run on device) | When the item is an app, home the new card in App Store (`collectionHomeFor(false)`) and set `wantGaming` from the item, not the column. |

The only working path today: open an app inside Network, App Store or a custom app category, then Options ▸ Add to Card ▸ New Custom Card Here…. Not tested on device.

### Low

| # | Defect | Source | Tag |
|---|---|---|---|
| L19 | Volume/balance cycle skips a step from off-grid values (30 → 75). | `core/core-data/.../discord/DiscordVoiceController.kt:275` | Traced |
| L20 | `AudioFileFilter` trusts `application/octet-stream`, so FLAC/Opus reported that way are skipped; video and photo filters already fall back to the extension. | `core/core-data/.../music/AudioFileFilter.kt:30` vs `VideoFileFilter.kt:16` | Plausible |
| L21 | Startup path normaliser rewrites any path containing `/files/`, which could null external artwork paths. | `StartupDataPrep.kt:129` | Plausible |
| L22 | Display-name cache is a shared `HashMap` written from IO threads. | `core/core-data/.../repository/MediaDisplayNames.kt:27` | Plausible |
| L23 | Theme store scans every `.pfptheme` zip in its constructor, likely on the main thread during injection. | `core/core-data/.../repository/PfpThemeStore.kt:99` | Plausible |
| L24 | Folder named `game` is always read as PSP (e.g. `/ps2/game/x.iso`). | `core/core-data/.../platform/PlatformFolderHintResolver.kt:52` | Plausible |
| L25 | Reset Lock Screen reports success even when `WallpaperManager.clear` fails; no double-press guard. | `feature/feature-settings/.../viewmodel/LockScreenViewModel.kt:76-81`; `LockScreenImage.kt:104` | Traced |
| L26 | "Import All" PC games reports SUCCESS with "Imported 0" when every import failed. | `LibraryManagerViewModel.kt:1357` | Traced |
| L27 | Sound import leaves the progress bar stuck if `store.import` throws. | `AudioSettingsViewModel.kt:162-163` | Plausible |

### Latent — gated off or no caller today

- **Backup & Restore** (behind `FeatureFlags.BACKUP_RESTORE = false`, `core/core-domain/.../FeatureFlags.kt:9`). Fix these before turning the flag on:
  - A normal-sized backup **can't be restored**. Backup bundles all artwork with no limit; restore reads with the default limits of 512 entries and 128 MB (`core/core-archive/.../BoundedZipReader.kt:18-25`, `feature/feature-backup/.../restore/RestoreArchive.kt:51`, `BackupManager.kt:218-226`). Checked.
  - Restore **wipes every preference not in the backup list**, including one-time migration flags (`BackupManager.kt:564-566`, `prefs.clear()`). Checked.
  - `db_seeded_v1` sits **inside a comment**, so it is never backed up (`BackupManager.kt:776-777`). Checked.
  - Restore is not wrapped in a database transaction, so a failure leaves a half-wiped library (`BackupManager.kt:297-403`; no `withTransaction` in the file). Checked (no transaction), Plausible (trigger).
  - Work-status collectors pile up (`BackupSettingsViewModel.kt:86,91`) and cancellation is reported as failure (`BackupManager.kt:102,211`). Traced.
- `RomScanner.scan(folders, …)` drops a standalone `.bin` beside any unrelated `.cue`; the function has no callers (`DiscImageResolver.kt:101-119`, `RomScanner.kt:196-201`). Traced.
- `ControllerRegistry` never lists pads connected before launch (`ControllerRegistry.kt:43,56`); `ControllerMappingRepository.remap` drops all bindings for an action (`:66`). Neither has a consumer. Traced.
- Recent-games-per-platform query orders `DISTINCT` by an arbitrary row (`PlaySessionDao.kt:38`). No consumer. Traced.

---

## 2. Controller / touch parity gaps

The project rule is that every feature works fully by pad and by touch.

| # | Gap | Source | Tag | Proposed fix |
|---|---|---|---|---|
| P1 | Discord QR overlay ignores every pad button but Back, so **Try again, Cancel and Copy are touch-only**. Back also leaves the login poll running. | `feature/feature-xmb/.../viewmodel/XMBViewModel.kt:6539-6542` | Checked | Forward actions to the overlay like the other overlays do; make close call `cancel()`. |
| P2 | Launch-failure sheet: **Change Emulator, Change per-system default and Copy diagnostics are touch-only**; pad only gets Retry and Dismiss. | `XMBViewModel.kt:6401-6409`; `ui/XMBShell.kt:2068-2108` | Traced | Draw it via `PfpModalHost` with an option cursor. |
| P3 | Shiba Coins, unlinked Local Steam game: **"Pick Game Folder" is drawn focused but Confirm does nothing**. | `feature/feature-xmb/.../ui/detail/ShibaCoinsViewModel.kt:824-830` (`LOCAL_STEAM -> Unit`); button at `ShibaCoinsScreen.kt:819-833` | Checked | Route `LOCAL_STEAM` to `chooseAutoMatch(legit = false)`. |
| P4 | Search Online: the **"Try again" / "Open Settings" button has no pad path**. | `SearchOnlineScreen.kt:393-400`; `SearchOnlineViewModel.kt:347-374, 600-605` | Traced | Add both to the Options menu, or make it a focusable row. |
| P5 | **Rename Theme is pad-only**: its menu opens on Triangle only, and the touch card row has no Rename. | `feature/feature-settings/.../ui/ThemesSettingsScreen.kt:257, 515, ~563` | Traced | Add a long-press gesture or a Rename action on the card. |
| P6 | Several settings `AlertDialog`s (category/icon pickers, Music, Video, Add PC Game) have **no pad routing**. | `CollectionsSettingsScreen.kt:111/236/252`, `MusicSettingsScreen.kt:142`, `VideoSettingsScreen.kt:141`, `LibraryManagerScreen.kt:1009`, `DisplaySettingsScreen.kt:696` | Plausible | Convert to `rememberSettingsModal` / `PfpModalSpec`. |
| P7 | A disabled slider can still be adjusted by pad if focus is already on it. | `feature/feature-settings/.../ui/SettingsSliderRow.kt:129` | Plausible | Register the focus tracker only when enabled. |

---

## 3. UI text that doesn't match behaviour

### Says it does something it doesn't

| # | UI text | Source of text | What the code does | Tag | Proposed fix |
|---|---|---|---|---|---|
| U1 | "Re-Scan All (Remove Missing) — Also removes games whose ROM file no longer exists" | `feature/feature-settings/.../ui/LibraryManagerScreen.kt:350-358` | Marks them missing; never deletes (`core/core-data/.../repository/LibraryReconciler.kt:51`) | Checked | "Re-Scan All (Mark Missing) — marks games whose ROM is gone as missing; favourites, stats and artwork are kept" |
| U2 | "Text Legibility — Automatic / None / Drop Shadow / Outline / Contrast Plate" | `DisplaySettingsScreen.kt:448-454` | Saved and exported only; no renderer reads it (`DisplaySettingsViewModel.kt:92-94` says so) | Checked | Hide the row until it's wired, or say "Saved with themes — not applied yet" |
| U3 | "Import All — Add every found game to a custom memory card named after its launcher" | `LibraryManagerScreen.kt:1000-1003` | Moves all into the one Windows card (`LibraryManagerViewModel.kt:1288-1289, 1336-1350`) | Checked | "Move every found game into the Windows Games Memory Card" |
| U4 | Manual viewer footer: "L1/R1 Prev / Next" | `feature/feature-xmb/.../ui/detail/GameDetailScreen.kt:1008-1010` | Pages with Left/Right; L1/R1 do nothing (`GameDetailViewModel.kt:1441-1458`) | Checked | Footer "◀ ▶ Page, ▲ ▼ Scroll", matching the viewer's own hint |
| U5 | "Reset Sound to Defaults? … Menu Sounds is turned back on" | `feature/feature-settings/.../ui/AudioSettingsScreen.kt:134-136` | No Menu Sounds toggle exists; reset also sets every level to full (`AudioSettingsViewModel.kt:236-241`) | Checked | "Every menu sound returns to the bundled sample and every level returns to full." |
| U6 | "Thermal Throttle Awareness — reduce background quality when device runs hot" | `DisplaySettingsScreen.kt:603-604` | Freezes the wave / releases motion wallpaper (`XmbBackground.kt:131-146`, `XMBShell.kt:722-756`) | Checked (text), Traced (behaviour) | "Freeze the background while the device is thermal-throttling" |
| U7 | Logs subtitle "Debug & error log viewer" | `feature/feature-xmb/.../viewmodel/XMBViewModel.kt:572` | No viewer; opens/shares files; only INFO+ is saved (`LogsSettingsScreen.kt:38-47`, `PfpFileLoggingTree.kt:41`) | Checked (text), Traced (behaviour) | "Open or share session logs" |
| U8 | "Export your files — Share them as one .json" (under "Your files") | `EmulatorKnowledgeScreen.kt:295-296` | Exports custom/edited emulators, not the imported files (`EmulatorKnowledgeViewModel.kt:357-375`) | Traced | "Export your emulators" |
| U9 | Artwork Studio "Reset to Scraped Default" | `ArtworkStudioViewModel.kt:661` | After a Studio pick, re-downloads the user's own pick and relabels it scraper-owned (`feature/feature-artwork/.../store/RoutingArtworkStore.kt:227-251, 385-395`) | Traced | Keep the scraper's URL separately, or only offer the row for scraped art |
| U10 | Store Match panel title "Rematch Storefront Metadata" | `StorefrontRematchPanel.kt:103` | Only changes store links; the panel's own note says metadata is untouched (`:182`) | Traced | "Store Match" |
| U11 | Wizard Welcome footer "Skip" | `feature/feature-settings/.../ui/wizard/WizardScaffold.kt:118-120` | On Welcome, Skip just goes to the next step, same as Get Started (`InitialSetupScreen.kt:180-181`) | Checked | No Skip on Welcome, or point it at "Skip Setup" |
| U12 | Wizard Finish page "Auto-fit the XMB layout" checkbox | `InitialSetupScreen.kt:1051` | Saved only by Finish; leaving via "Open Library Manager" or "Go to your library" drops it (`InitialSetupViewModel.kt:736-742`) | Traced | Call `finishSetup()` before those two exits |
| U13 | "Set Up ROM Folders (ES-DE) … the root will be scanned automatically" | `LibraryManagerScreen.kt:333-336`; `LibraryManagerViewModel.kt:1394` | Never scanned; no visible "Scan ROM Root" button (`LibraryManagerViewModel.kt:1373-1380`, `LibraryManagerScreen.kt:264`) | Traced | Pass `onScanRomRoot` into `RootAccessSection`; update the message |
| U14 | "Install Goldberg Achievements" says "No proper achievements.json for this game" | `XMBViewModel.kt:8104` | Shown whenever the installer toggle is off, without checking the game (`:8101-8107`) | Traced | "Goldberg installation is off. Turn it on in Achievement settings." |
| U15 | Launch With value names a launcher | `GameContextMenuItems.kt:119` | Label shows the chosen package even when launch falls back to the game's own launcher (`feature/feature-launcher/.../PcLaunchRouting.kt` `currentLabel` vs `chosenLaunchIntent`) | Traced | Derive the label from the same resolution |
| U16 | Status strip clock and date | `feature/feature-xmb/.../ui/XmbStatusStrip.kt:303-305` | Hard-coded `h:mm a` and `MM/dd/yyyy`; ignores the device's 24-hour and locale setting (`:675-679`) | Checked | Use `DateFormat.getTimeFormat(context)` / `getDateFormat(context)` |
| U17 | Still WebP wallpaper shows "Motion wallpaper applied" | `DisplaySettingsScreen.kt:287`; `DisplaySettingsViewModel.kt:685` | Every `image/webp` takes the motion path (`DisplaySettingsViewModel.kt:544`; `MotionLimits.kt:23`) | Traced | Use the motion path only for animated WebP |

### Points somewhere that doesn't exist

| # | UI text | Source | Reality | Tag |
|---|---|---|---|---|
| U18 | "Add a ROM Root first in Settings → Folder Access." | `feature/feature-settings/.../viewmodel/RomRootScanRunner.kt:82` (also `BackupManager.kt:179`, latent) | No Folder Access screen; ROM roots moved to Library Manager (`LibraryManagerViewModel.kt:908`) | Checked |
| U19 | "Settings ▸ Shiba Coins" | `LibraryManagerScreen.kt:733, 762, 928`; `LibraryManagerViewModel.kt:823, 839`; `InitialSetupViewModel.kt:653` | Section is called "Achievements" (`XMBViewModel.kt:509`) | Checked (text), Traced (section name) |
| U20 | GameBoot Video "…even with Menu Sounds off" | `DisplaySettingsScreen.kt:541-542`; `studio/.../preview/screens/SettingsScreenPreview.kt:357-359` | Menu Sounds toggle removed; clip plays at master × GameBoot level | Traced |
| U21 | Wizard "run Initial Setup from Settings anytime" | `InitialSetupScreen.kt:439` | The row is named "Setup Wizard" (`XMBViewModel.kt:576`) | Checked (row name) |

### Wrong numbers or incomplete lists

| # | UI text | Source | Reality | Tag |
|---|---|---|---|---|
| U22 | Theme Studio "Boot Video … up to 10 seconds" | `studio/.../preview/screens/SettingsScreenPreview.kt:344` | `UiMediaLimits.BOOT_MAX_MS = 15_000L` (`core/theme-kit/.../UiMediaLimits.kt:86`) | Checked |
| U23 | "Unsupported format — use MP4, WebM, or GIF" | `core/theme-kit/.../MotionLimits.kt:36` | WebP is also accepted (`:23, :47`) | Checked |
| U24 | Background Motion lists 3 states | `DisplaySettingsScreen.kt:349-354` | The row cycles 4, including "Reduced + Static" (`DisplaySettingsViewModel.kt:352-356`) | Traced |
| U25 | "$added new games across $cards Memory Cards" | `MemoryCardContextMenuItems.kt:111` | `cards` counts every card scanned, not the ones that found games (`XMBViewModel.kt:8676`) | Traced |
| U26 | Sound subtitle "Menu & boot sounds" | `XMBViewModel.kt:550` | No assignable boot sound, only a level | Checked (text) |

### Shown when it can't apply, or wrong confirm copy

| # | Issue | Source | Tag |
|---|---|---|---|
| U27 | Remove Card on the **Android** card says "ROM files stay on disk, and a scan can bring them back". The Android card holds apps. | `viewmodel/XmbConfirm.kt:97`; gating `MemoryCardContextMenuItems.kt:64` | Traced |
| U28 | Remove from Library on an app-style row talks about "the file" and "a later scan". | `XmbConfirm.kt:63`; reached from `GameContextMenuItems.kt:147` | Traced |
| U29 | Launch With is shown with only one choice. | `GameContextMenuItems.kt:119`; `XMBViewModel.kt:7187` | Traced |
| U30 | Notification panel always shows Options, which does nothing on empty history. | `ui/NotificationPanel.kt:411`; `XMBViewModel.kt:9044-9045` | Traced |
| U31 | Preview Wallpaper row is shown with no wallpaper set. | `DisplaySettingsScreen.kt:293-297` | Traced |
| U32 | Library Manager's Goldberg switch **skips the "back up your saves" confirm** that the same switch has in Achievements. | `LibraryManagerScreen.kt:925-929` vs `AchievementsSettingsScreen.kt:95-99, 260-264` | Traced |
| U33 | "Reset Emulator Configuration" is **unreachable** from the settings tree (only drawn when no section is passed). | `EmulatorsSettingsScreen.kt:250-257`; `SettingsNavHost.kt:152-154` | Traced |
| U34 | Game List link dialog offers "install the emulator kit" when that button is hidden. | `WindowsGameListScreen.kt:207-219` | Traced |

---

## 4. Dead and orphaned code

All found by a reference-count script (13,558 declarations), then opened by hand to rule out Hilt, Room, reflection and previews.

**Whole files or classes with no callers**

| Item | Source | Tag | Proposed fix |
|---|---|---|---|
| `ShibaCoinStrip` composable, whole file | `feature/feature-xmb/.../ui/detail/ShibaCoinStrip.kt:52` | Checked | Delete |
| `StorefrontMetadataSync` (only its test uses it) | `feature/feature-artwork/.../match/StorefrontMetadataSync.kt:25` | Checked | Wire into PC bulk match, or delete with its test |
| `MatchReport.summaryLine()` / `detailLine()`, whole file; KDoc claims Settings and Shiba use it | `feature/feature-achievements/.../AchievementReportSummary.kt:20,23` | Traced | Delete, or wire in |
| `XmbBackTouchButton`, `XmbTouchPill` (previews only) | `core/core-ui/.../components/XmbTouchButton.kt:75,178` | Checked | Delete |
| `GamepadInputHandler.bypassToComposeFocus` (only a test sets it) | `feature/feature-xmb/.../gamepad/GamepadInputHandler.kt:81,143` | Traced | Delete with its test |
| `DiscordNativeBridge.callStatus()` and its JNI function | `discord/discord-native/.../DiscordNativeBridge.kt:181,218`; `DiscordBridge.cpp:556` | Traced | Delete all three |
| `EmulatorProfileRepository.getInstalledVersionCode()` | `feature/feature-launcher/.../EmulatorProfileRepository.kt:108` | Traced | Delete |
| `MotionLimits.ADVISORY_MAX_FPS` | `core/theme-kit/.../MotionLimits.kt:33` | Traced | Delete |
| `BackupSettingsViewModel.restoreFromFile()` (only logs) | `feature/feature-settings/.../viewmodel/BackupSettingsViewModel.kt:136` | Traced | Delete |

**DAOs provided by Hilt but injected nowhere.** Keep the entities so Room still knows the tables and no migration is needed.

- `LibrarySourceDao`, `UnmatchedRomDao`, `ScanTombstoneDao` in `core/core-data/.../database/dao/`. `ScanTombstoneDao`'s own KDoc already says "RETIRED". Remove each DAO, its accessor in `PFPDatabase.kt:140,141,155` and its provider in `DatabaseModule.kt:148,149,163`. Traced.

**Repository interface methods only the implementation mentions** (delete from interface and impl). Traced.

- `GameRepository`: `observeRecentPlatforms`, `recordPlaySession`, `getMissingRoms`
- `MusicRepository`: `observeEnabledFolders`, `getDefaultPlayerPackage`
- `PhotoRepository`: `setLibraryScanRecursively`, `setLibraryTreeUri`, `getPhoto`
- `VideoRepository`: `observeEnabledLibraries`, `setLibraryArtwork`
- `AchievementController.syncGame`

**Production code reached only from tests** (delete with the test, or wire up). Traced.

- `AchievementAutoMatcher.matchSingleAsLocalSteam` (`:162`)
- `GameDetailViewModel.onLaunchFailed` (`:1674`), `dismissArtworkMessage` (`:1949`)
- `AppDrawerViewModel.onAppSelected` (`:132`)
- `StudioSearch.evictSource` (`:283`)
- `NavigationEngine.recoverReadiness` (`:88`)
- `StudioViewModel.setMotionCrop` (`:811`)
- `GameStorefrontIdentityDao.byStoreId` (`:32`), `MusicTrackDao.countForFolder` (`:45`)
- About 45 more test-only members were flagged by the script but not opened. Some may be intended test seams.

**Clean:** no TODO/FIXME, no `NotImplementedError`, no `@Suppress("unused")`, no large commented-out blocks; every string, drawable, raw and font resource is referenced. The one feature flag (`BACKUP_RESTORE`) gates real code and is documented.

---

## 5. Spelling

No misspellings in UI text, docs, identifiers or comments. A tokenised scan of about 20,000 unique words was checked against a 600-entry misspelling list and reviewed by hand.

- One nonstandard word in a comment: "ellipsises" at `feature/feature-xmb/.../ui/GameBootSequence.kt:357`. The rest of the repo uses "ellipsizes".
- `org.retroachivements.api` is the upstream RetroAchievements library's own package name and can't be fixed here.
- Mixed British and American spelling (colour/color, favourite/favorite) is deliberate and not flagged.

---

## 6. What this survey did not check

- Nothing was run on a device, so every behavioural claim comes from reading code.
- Plausible items name the unconfirmed link in their row.
- Three reviewer notes were left unverified and are not listed above: "Show on Bar" may only ever hide, the launch-failure sheet may offer Change Emulator for Android or PC games, and the Remove from Library copy for Windows games depends on the PC scan's pin sweep.
- The `.claude/worktrees/*` copies were excluded. One of them, `parasite-eve-missing`, is a detached HEAD that may be abandoned.

---

## 7. Fix log — 2026-10-23

Every fix landed test-first: the test named below was written, then the code. Paths are the main
file changed; tests sit beside them under `src/test`.

### Logic

| Item | Outcome | Test |
|---|---|---|
| L1 late-mount root marks games missing | Unreadable root adds an "unknown survey" source plus a readable error; no removals (`ScanSourceResolver.kt`, `RomScanner.listSubfolderNamesOrNull`) | `ScanSourceResolverTest` |
| L2 migrations marked done on failure | Shared `runOneShotStep`; flag written only on success (`OneShotStep.kt`, `LibraryConsolidation.kt`, `StartupDataPrep.kt`) | `OneShotStepTest` |
| L3 merge dropped the loser's history | Uses `GameDao.mergeRows` | `LibraryConsolidationRunTest` |
| L4 category id collision | Collision-free id, insert with ABORT (`CategoryRepositoryImpl.kt`, `CategoryDao.insertNew`) | `CategoryRepositoryCreateTest` |
| L5 Steam error wiped coins | `success:false` is a failure (`SteamRemoteDataSource.kt`) | `SteamRemoteDataSourceTest` |
| L6 music player kept dead player | Skip happens after the failed player is stored and released (`MusicPlayerController.kt`) | `MusicPlayerControllerSkipTest` |
| L7 QR login stale | Starts per opening, cancels and resets on close (`QrLoginViewModel.kt`, `QrLoginScreen.kt`) | `QrLoginViewModelTest` |
| L8 re-fetch storm | `recordNoSet` stamps detail time (`AchievementSyncStore.kt`) | `AchievementSyncStoreTest` |
| L9 hub ignored renames | Display-title order in the three hub queries | `AchievementDaoTest` |
| L10 playlist counts | Counts join live tracks; folder/library removal prunes dead rows | `PlaylistDaoImportTest`, `MusicRepositoryImplTest` |
| L11 RA unparseable date | Earned = either date present (`RaCoinMapper.kt`) | `RaCoinMapperTest` |
| L12 Winlator app-private paths | `/Android/data` and `/Android/obb` are not shared (`WinlatorDesktop.kt`) | `WinlatorDesktopTest` |
| L13 detail pages stale load | One load job per view model (App, Video, Photo) | `AppDetailLoadTest`, `VideoDetailViewModelTest`, `PhotoViewerLoadTest` |
| L14 App Drawer reload / cursor | Single load job, clamped cursor, initial filter in an effect | `AppDrawerViewModelTest` |
| L15 m3u adopted unrelated disc | Fallback limited to the playlist's own subfolders (`DiscSetBuilder.kt`) | `DiscSetBuilderTest` |
| L16 disc rewrite reverted changes | Stored row is the base (`DiscSetReconciler.kt`) | `DiscSetReconcilerTest` |
| L17 media walk read failure as empty | `SafTreeWalk.incomplete`; music/photo/video scans stop with a message | `SafTreeWalkTest` |
| L18 contrast clamp never drawn | Notice is now an honest warning; "Use My Exact Colour" removed (colour is always exact) | `DisplaySettingsViewModelFontColorTest` |
| L19 voice step skipped a level | `nextVoiceStep` (`DiscordVoiceController.kt`) | `VoiceStepTest` |
| L20 octet-stream audio skipped | Extension decides (`AudioFileFilter.kt`) | `AudioFileFilterTest` |
| L21 path normaliser erased artwork | Only app-private roots re-homed (`StartupDataPrep.rehomeInternalPath`) | `RehomeInternalPathTest` |
| L22 display-name cache | Concurrent map (`MediaDisplayNames.kt`) | none: a concurrency test would be flaky; correct by construction |
| L23 theme scan on main thread | **Not changed.** The scan reads only each theme's manifest header, and making it async would race several synchronous readers (`ThemesSettingsViewModel`, apply/rename/export). Risk outweighed the gain. | — |
| L24 "game" folder read as PSP | Only under a `PSP` folder (`PlatformFolderHintResolver.kt`) | `PlatformFolderHintResolverTest` |
| L25 lock screen reset reported success on failure | `clear()` returns success; error reported; busy guard | `LockScreenImageTest`, `LockScreenViewModelTest` |
| L26 Import All hid failures | Counted; success / warning / error line | `PcImportAllSummaryTest` |
| L27 sound import stuck busy | try/finally with a message | `AudioSettingsViewModelTest` |
| L28–L30 non-gaming memory cards | Shared rule `Category.showsCustomCards`; Settings offers app categories and words cards by kind; category menu has New Custom Card; an app from the Android column gets an App Store card | `CategoryCardsTest`, `CollectionsSettingsViewModelTest`, `CustomCardTextTest`, `CategoryMenuItemsTest`, `NewCardHomeTest` |

### Controller / touch parity

| Item | Outcome | Test |
|---|---|---|
| P1 Discord QR | Confirm = Try again / Copy code; Back closes and stops the poll | `DiscordLoginInputTest`, `DeviceLoginRetryableTest` |
| P2 launch-failure sheet | Now the shared Choice modal | `ShellModalSpecTest` |
| P3 Local Steam Pick Game Folder | Confirm presses it. Note: an unlinked library game always gets its platform's provider (Steam), so this panel may not be reachable today | `ShibaCoinsHelperFooterTest` |
| P4 Search Online recovery | Offered in Options | `SearchOnlineViewModelTest` |
| P5 Rename Theme | Long-press on a saved theme opens its menu | `ThemesSavedThemeStripTest` |
| P6 settings dialogs | Player pickers, card category and contrast notice are shared Choice modals; card icon and Add PC Game are settings steps | `PlayerPickerSpecTest`, `CustomCardTextTest`, `AddPcGameStepTest` |
| P7 disabled slider | No pad fallback when disabled | none: needs a scaffold-level focus harness |

### UI copy

All U1–U34 rows are fixed as proposed, with these notes: U9 hides Reset to Scraped Default for
user-picked art (Restore Previous covers it); U11 removes the footer Skip on Welcome; U13 draws
the existing Scan ROM Roots row; U16 follows the device 24-hour setting and the locale's date order;
U17 routes a still WebP to the still path; U27/U28 give apps their own confirm copy.

### Backup (still behind `FeatureFlags.BACKUP_RESTORE = false`)

Shared `BackupLimits` (20,000 files, 160 MB per file, 2 GB total) used by creation and restore;
settings restore touches only backed-up keys; `db_seeded_v1` is backed up; the restore's database
writes run in one transaction via `DatabaseTransactions`, files commit after it; cancellation is
rethrown and staged files are cleaned; work watchers stop at the first finished state. Tests:
`BackupLimitsTest`, `BackupManagerTest`, `BackupSettingsViewModelTest`.

### Dead code removed

`ShibaCoinStrip.kt`, `StorefrontMetadataSync.kt` (+ test), `AchievementReportSummary.kt`,
`XmbBackTouchButton`, `XmbTouchPill`, `GamepadInputHandler.bypassToComposeFocus`, Discord
`callStatus` and its native function and write-only `gCallStatus`, `getInstalledVersionCode`,
`MotionLimits.ADVISORY_MAX_FPS`, `restoreFromFile`, three uninjected DAOs, eleven unused repository
members and the queries/models only they used (`PlaySession`, `RecentPlatform`), the test-only
members listed in section 4 (with their tests), the old folder-list `RomScanner.scan` and what only
it used (`PlatformExtensionMap`, `DiscImageResolver`'s disc list and orphan-bin logic,
`sanitizeRomName`). Kept on purpose: `ControllerRegistry` (the gamepad handler uses it) and the
theme-render contrast clamp, which belongs to the not-yet-wired Text Legibility engine.
