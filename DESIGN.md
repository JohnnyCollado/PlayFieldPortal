# Play Field Portal — Design Document
**Version 2.3 · June 2026**

---

## 1. Vision

Play Field Portal (PFP) is a full Android launcher styled after the PlayStation Portable's XMB (Cross Media Bar). It replaces the Android home screen and serves as a unified game frontend — bringing together ROM emulation, Android games, PC-layer titles (via Winlator), and native Android apps under a single, cohesive interface that feels like the golden era of handheld gaming.

The name **PFP** is a deliberate double entendre: *Play Field Portal* as the product name, and the affectionate shorthand that calls back to fan culture (PFP as a profile picture in anime/gaming communities). Legal review determined the name carries no trademark conflict with Sony, as "XMB" and "Cross Media Bar" are Sony trademarks but the visual style itself is not protectable as trade dress in a non-competing product category.

---

## 2. Core Design Principles

| Principle | What it means in practice |
|---|---|
| **Launcher-first** | Replaces the Android home screen. Users should never need to leave PFP to play games. |
| **Performance over polish** | The XMB wave is iconic but must never kill frame rate. Tiered degradation over dropped frames. |
| **Manual control** | ROM scanning is always user-initiated. No background polling, no FileObserver, no surprises. |
| **Zero junk in release** | Debug tooling is compile-time excluded. The release APK contains no simulation code. |
| **PSP soul, Android body** | The aesthetic is XMB but the interaction model is Android — back gestures, Intents, Compose. |
| **Tested by default** | Every new module ships with unit tests. Pure-JVM tests (MockK + Turbine) for logic; integration tests for DB and WorkManager. |

---

## 3. Feature Decisions (from design discussions)

### 3.1 Launcher Role
- **Decision:** Full launcher (HOME intent). PFP is the user's home screen, not a floating overlay.
- **Rationale:** Immersion. Launching RetroArch and pressing home should return to PFP, not the stock launcher.
- **Implementation:** `AndroidManifest.xml` declares `CATEGORY_HOME` + `CATEGORY_DEFAULT` + `CATEGORY_LAUNCHER`.

### 3.2 XMB Navigation - PSP Category Bar
- **Decision:** The horizontal XMB remains faithful to PSP-style categories: Settings, Photos, Music, Videos, Games, Network.
- **No horizontal platforms:** Consoles/platforms must not appear as top-level horizontal XMB categories.
- **Games drill-down:** Selecting Games shows platforms/consoles in the vertical list. Platforms behave like folders.
- **Platform drill-down:** Selecting a platform opens `Games > Platform` and replaces the vertical list with that platform's games.
- **Controller behavior:** D-pad/stick left/right moves categories; up/down moves the vertical list. A/Cross enters a platform or opens a game. B/Circle backs out from a platform to the platform list. Triangle/Y opens platform options or the game hub/options for the focused item.
- **Category icons:** Use PSP-inspired icons for Settings, Photos, Music, Videos, Games, Network. Games uses a PSP-style controller icon.
- **Game counts:** `XMBUiState.platformGameCounts: Map<String, Int>` is populated reactively from `GameDao` and displayed in platform folder subtitles.
- **Settings long-press:** Long-pressing the Settings slot still opens the debug control panel in debug builds.

### 3.3 Categories

| Category | Type | Notes |
|---|---|---|
| **Music** | Built-in | XMB staple, secondary priority |
| **Videos** | Built-in | XMB staple, secondary priority |
| **Games** | Built-in | Core feature — drill-down to platforms |
| **Favorites** | Built-in | Static, always present, cannot be deleted |
| **Recently Played** | Built-in | Per-platform drill-down (not a flat list) |
| **Android** | Built-in | App drawer gateway + direct emulator access |
| **Settings** | Built-in | All PFP configuration lives here |
| **User-created** | Smart/Manual | Users can create custom categories |

### 3.4 Game Library

#### Folder Structure
- **Root directory:** User-selected on first run, stored as DataStore preference `library_root_path`.
- **Platform subfolders:** Named by platform ID (lowercase, no spaces) — `{root}/psx/`, `{root}/psp/`, `{root}/gba/`, etc. Derived at runtime; never stored in DB.
- **Folder creation:** `File.mkdirs()` called when the user picks a root folder via SAF `OpenDocumentTree`.
- **Per-platform extra sources:** `LibrarySourceEntity` has a `platform_id` column (nullable). Non-null = extra scan folder scoped to that specific platform. Null = global path (extension detection). Platform override is applied post-scan in `LibrarySettingsViewModel`.
- **Scanner path resolution per platform:** `[enabled LibrarySource rows]` — root is the first source inserted on setup.

#### First-Run Setup
- **Guard:** DataStore flag `library_setup_complete`. Once set, the prompt never appears again.
- **XMB prompt:** When `library_setup_complete = false`, the GAMES category injects a "Set up ROM Library →" item at the top of its list. Tapping it opens the Library Settings screen directly via `activeSettingsScreen = "settings_library"`. The prompt disappears live when setup completes (reactive via DataStore observation in `XMBViewModel`).
- **Library Settings flow:** Pick root folder via SAF `OpenDocumentTree` → `LibrarySettingsViewModel.setupRoot()` extracts the real path from the tree URI document ID, takes a persistable read permission, creates the folder on disk, saves to DataStore, inserts the root as a `LibrarySourceEntity`, and sets `library_setup_complete = true`.

#### Scanning
- **ROM scanning:** Manual-only, user-initiated. Power users can trigger a full re-scan from Settings.
- **Disc image resolution:** `.cue` is the definitive PS1 launch file. Companion `.bin` files listed in the CUE sheet are suppressed. Orphan `.bin` → Mega Drive. `.chd`/`.img` → user assignment required (`UnmatchedRomEntity`).
- **Missing ROM detection:** `GameRepository.getMissingRoms()` checks `File(path).exists()` — flagged in UI.
- **Scan results persistence:** `LibrarySettingsViewModel.scanNow()` upserts `Game` objects via `GameRepository`, saves `UnmatchedRomEntity` rows, and updates `LibrarySourceEntity.lastScannedAt` + `gameCount`.

#### DB Migration History
| Version | Change |
|---|---|
| v1 → v2 | Added `is_active` column to `themes` table |
| v2 → v3 | `ALTER TABLE library_sources ADD COLUMN platform_id TEXT` |

### 3.5 Artwork Scraping — Three-Tier Metadata Pipeline
- **Tier 1 — ScreenScraper:** Hash-based match (MD5/SHA1 of ROM file). Most accurate; used first.
- **Tier 2 — TheGamesDB (TGDB):** Title-based fallback when ScreenScraper returns no match. Returns metadata + artwork URLs.
- **Tier 3 — SteamGridDB:** Artwork-only fallback. Also used as the primary user-facing artwork picker in Game Detail.
- **Rate limiting:** 1.1s between requests per source (above SteamGridDB's 1 req/s limit).
- **Cache:** Coil disk cache (512 MB) + memory cache (20% available RAM). `MetadataRepository.prewarm()` pre-fetches API keys from DataStore on init.
- **Art types:** Grid (box art), Hero (banner), Logo (transparent PNG), Icon.
- **Trigger:** Manual — user initiates from Game Detail screen or XMB game context menu (Y/Triangle → Refresh Artwork).
- **`ArtworkRepository.fetchArtworkForGame(gameId, title)`** — entry point for all artwork refresh actions; delegates to `MetadataRepository` which runs the three-tier cascade.
- **`CardArtworkProcessor`:** Downloads artwork via Coil (`allowHardware(false)` for pixel access), composites onto a platform card template using Android `Canvas`/`Matrix`, saves result as PNG to `artwork/cards/{platformId}/{gameId}.png`. Used for the SD card tile thumbnails in `PlatformSdCardRow`.

### 3.6 Emulator Compatibility Layer
- **Decision:** JSON profiles per emulator, bundled in assets, remote-updatable, user-editable in-app.
- **Intent types:** `ACTION_VIEW`, `COMPONENT` (explicit package/class), `SHORTCUT`, `CUSTOM_COMMAND`.
- **Template variables:** `{rom_path}`, `{core_path}`, `{game_title}`, `{platform_id}`, `{save_path}`.
- **Bundled emulators (13):** RetroArch, RetroArch aarch64, PPSSPP, PPSSPP Gold, Dolphin, DuckStation, NetherSX2, Azahar, Sudachi, melonDS, mGBA, Winlator (shortcut), GameHub (shortcut).
- **In-app editor:** Users add custom emulators without editing JSON files.

### 3.7 App Drawer
- **Inspiration:** Daijisho and Beacon launchers.
- **Filter views (fullscreen):** All Apps, Games Only, Emulators, Recently Used, User-defined filter groups.
- **Entry point:** Android category on the XMB bar. Tapping a filter opens it fullscreen.
- **Implementation:** `InstalledAppRepository`, `AppDrawerViewModel`, fullscreen Compose overlay via `activeAppDrawerFilter` state field in `XMBViewModel`.

### 3.8 XMB Wave
- **Decision:** User-selectable render style — the wave never blocks the UI thread.
- **Modes (`WaveStyle`, stored in DataStore `KEY_WAVE_STYLE`):** `ANIMATED` (full framerate, default), `REDUCED` (half speed, dimmer/lower-amplitude), `STATIC` (no animation, frozen frame), `REDUCED_STATIC`. The style is the user's manual choice (Settings ▸ Display).
- **Automatic power throttle:** `rememberWavePowerThrottle` (in `XmbBackground.kt`) freezes the animation to its `WaveStyle.frozen` counterpart while the OS is in **battery-saver** mode or reporting **moderate+ thermal throttling**, gated by the `display_battery_saver` / `display_thermal_aware` toggles (both default-on). `XMBShell` also freezes the wave whenever an opaque layer covers it (boot, detail/player overlays, app drawer, music player) — nothing visible to animate.
- **Frame loop:** Driven by Compose's `withInfiniteAnimationFrameMillis`, so it advances only while the background is on screen and pauses automatically when PFP is backgrounded (e.g. while an emulator runs). No frame loop is created for the static styles.
- **RuntimeShader:** Used on API 33+ for GPU-accelerated wave. Canvas fallback below API 33.

### 3.9 Themes
- **Format:** `.xmbtheme` — a renamed ZIP containing `theme.json` + optional asset files.
- **theme.json fields:** `format_version`, `id`, `name`, `author`, `version`, `wave_color` (#RRGGBB or #AARRGGBB), `wave_opacity`, `wave_speed`, `wave_amplitude`, `accent_color`, `text_color`, `has_background`, `has_boot_animation`, `has_sound_pack`, `font_key`.
- **Asset layout inside ZIP:** `background.jpg`, `boot_animation.mp4`, `sounds/{navigate_h,navigate_v,select,back,category_change,boot}.ogg`.
- **Install path (internal):** `filesDir/themes/{id}/` — assets copied on install, not read from external storage at runtime.
- **Install source:** Users pick a `.xmbtheme` file through the Storage Access Framework file picker in Themes Settings (`ThemeRepository.installTheme(uri)` → `XmbThemeLoader.loadFromUri`); no storage permission and no external discovery folder are needed. Extraction is hardened with per-entry size/count limits (zip-bomb) and a canonical-path guard (zip-slip).
- **Color propagation:** Active `ThemeEntity` observed by `XMBViewModel` → converted to `PFPColors` → passed to `PFPTheme {}` wrapper in `XMBShell` → `LocalPFPColors` CompositionLocal available to `XmbBackground` and all child composables.
- **Built-in themes:** "Classic PSP Blue" seeded by `DatabaseInitializer` on first launch (guarded by `themes_seeded_v1` DataStore flag). Cannot be deleted.
- **Sound events (deferred):** `NAVIGATE_HORIZONTAL`, `NAVIGATE_VERTICAL`, `SELECT`, `BACK`, `CATEGORY_CHANGE`, `BOOT` — sound pack assets extracted but playback engine not yet wired.
- **Boot animation override (deferred):** `bootAnimationUri` stored in DB; `BootSequenceOverlay` still uses default hardcoded animation.
- **Custom fonts (deferred):** `fontKey` stored in DB; runtime font switching via `FontFamily` not yet implemented.

### 3.10 Console Boot Sequence
- **Decision:** Must-have. Themeable.
- **Default sequence:** Fade-in logo (800ms) → hold (1200ms) → wave sweep (600ms) → hold (400ms) → fade-out (500ms).
- **Theme override:** Custom boot animation asset in `.xmbtheme` replaces the default.

### 3.11 Controller Mapping
- **Decision:** Full XMB navigation via physical controller — must-have for the couch gaming use case.
- **API:** Android `InputDevice` — D-Pad, analog stick, face buttons, shoulder buttons.
- **Default mapping:** D-Pad/stick = navigate, A = select, B = back, Y = options/long-press (context menu), L1/R1 = jump between categories.
- **Y / Triangle — Context Menu:** Long-press (Y button) opens a context menu overlay. When a platform tile is focused, opens the platform context menu. When a game item is focused, opens the game context menu. All gamepad input is captured by the context menu until it is dismissed.
- **Implementation:** `GamepadInputHandler` (`@Singleton`), `GamepadAction` enum, `ControllerMappingRepository` (DataStore), tap-to-remap UI in Controller Settings.

### 3.22 Context Menu System
- **Trigger:** `GamepadAction.LONG_PRESS` (Y/Triangle). Dispatched in `XMBViewModel.dispatchGamepadAction()` before any overlay routing — the context menu intercepts all input when open.
- **Data model:** `XMBContextMenu(title, items, selectedIndex, platformId?, gameId?)` + `XMBContextMenuItem(id, label, isDestructive)`.
- **UI:** `ContextMenuOverlay` — right-aligned panel (256–340dp), 6dp corners, PSP/Xbox-style. Full-screen scrim dismisses on tap. `LazyColumn` auto-scrolls to `selectedIndex` via `LaunchedEffect`.
- **Platform context menu items:** Pin to XMB, Unpin from XMB, Scan ROMs, Refresh Artwork, Refresh Metadata.
- **Game context menu items:** Add to Favorites / Remove from Favorites, Refresh Artwork, Refresh Metadata.
- **Platform scan from XMB:** `scanPlatformRoms(platformId)` — reads `LibrarySourceDao.getEnabled()`, filters by `platformId` (falls back to all sources if none are platform-locked), runs `RomScanner.scan()`, upserts new games, updates the background task tray with progress.
- **Background task lifecycle:** `addBackgroundTask` → `updateBackgroundTask` → `completeBackgroundTask` (auto-removes after 4s via `delay + filterNot`) or `failBackgroundTask`.

### 3.12 Background Tasks
- **Engine:** WorkManager (battery-constrained, thermal-aware).
- **Task types:** Artwork fetch, library scan (when long), backup/restore.
- **UI:** `BackgroundTaskTray` — badge on status bar, expandable progress panel.
- **Thermal guard:** Severe/critical thermal status pauses non-critical background work.

### 3.13 Backup & Restore
- **Scope:** Game library metadata, custom categories, play history, emulator profiles.
- **Format:** `.pfpbackup` — a renamed ZIP with named JSON entries (games, categories, category_items, play_sessions, settings, manifest).
- **Manifest:** Records format version, app version, entity counts. Format version check rejects backups from future app versions.
- **Workers:** `BackupWorker` and `RestoreWorker` (both `@HiltWorker CoroutineWorker`) enqueued via `WorkManager`. SAF URI picker for restore.
- **Destination:** `/storage/emulated/0/Documents/PlayFieldPortal/backups/`.

### 3.14 Game Detail View
- **Confirmed fields:** Title, platform, artwork (hero + grid + logo), description, developer, publisher, release year, genre, total play time, last played, user notes.
- **Actions:** Launch, toggle favorite, fetch artwork from SteamGridDB, edit note, customize artwork, delete from library.
- **Implementation:** Fullscreen `Box` overlay rendered inside `XMBShell` when `activeGameId != null`. `GameDetailViewModel` exposes `loadGame(id)` called via `LaunchedEffect`. Launch intent emitted as `SharedFlow<Intent>` to keep `startActivity` out of the ViewModel.
- **Status:** ✅ Complete.

### 3.15 Debug Simulation Layer
- **Decision:** Full scenario control system — zero footprint in release APK.
- **Access:** Long-press Settings category icon (debug builds only).
- **Controls:** Scenario picker (7 scenarios), wave mode override, thermal simulation, boot replay, task tray, perf overlay.

### 3.16 Unit Testing Strategy
- **Framework:** JUnit 4 + MockK + Turbine (Flow) + kotlinx-coroutines-test.
- **Rule:** Every module we actively build gets a `src/test` source set and tests before moving on.
- **Coverage targets:**
  - Pure logic (ViewModels, repositories): MockK stubs + `StandardTestDispatcher`.
  - ZIP I/O (BackupManager): `TemporaryFolder` rule, real file system.
  - Flow assertions: Turbine `.test {}` blocks.
  - Android types (Drawable, MotionEvent): MockK with `relaxed = true`.

### 3.17 Game Icon Styles
- **Styles:** Two options for ROM games, one fixed style for Android apps.
  - `PSP_RECTANGLE` (default): 62×86dp portrait rectangle, 4dp rounded corners, `ContentScale.Crop` artwork, gradient-with-initial fallback, gloss shine overlay.
  - `CARTRIDGE`: Same size but with a custom `GenericShape` (top-right notch), dark body, inset label area showing artwork, simulated 6-pin connector strip at the bottom with an accent stripe above it.
  - Android apps: Always 52×52dp squircle (`RoundedCornerShape(14dp)`), icon loaded from `PackageManager` via Bitmap → `BitmapPainter` (no Accompanist dependency).
- **Setting:** `display_icon_style` DataStore key (string, enum name). Cycled via "Game Icons" row in Display Settings. XMBViewModel observes it reactively.
- **Routing:** `XMBItem.isAndroidApp` flag (derived from `game.packageName != null`) selects the Android style unconditionally. ROM games use the user's style preference.
- **Accent color propagation:** `XMBItem.accentColor: Long?` populated from `PlatformEntity.accentColor` via a platform cache in `XMBViewModel`, used as the fallback gradient color in both icon styles.

### 3.18 Custom Per-Game Artwork
- **Slots:** Three independent slots per game — Box Art (`artworkUri`), Hero Banner (`heroUri`), Logo (`logoUri`).
- **Entry point:** Palette icon in the Game Detail action row. Toggles a `CustomArtworkPanel` below the action row.
- **Panel:** Each slot shows its current status (green dot when set), a "Choose"/"Replace" button that launches `OpenDocument(image/*)`, and a trash icon to clear.
- **Storage:** Picked image is copied to `filesDir/artwork/{gameId}/{box_art|hero|logo}.jpg` immediately on pick (`Dispatchers.IO`). The local `file://` path is written to the DB. SAF URI expiration is not a concern since we copy eagerly.
- **Clear:** Sets the DB field to `null`. Coil's disk cache is invalidated on next load.
- **GameRepository additions:** `updateBoxArt(id, uri?)`, `updateHeroArt(id, uri?)`, `updateLogoArt(id, uri?)` — each maps to a targeted Room `@Query`.

### 3.19 Deferred / Secondary Goals
- **Friends & Socials** — XMB-style friends list, activity feed. Deprioritized; XMB functionality ships first.
- **Global Search** — Too clunky as a primary feature. Revisit after v1.
- **Second metadata source** — SteamGridDB is primary; second source (IGDB, TGDB) TBD.
- **`PACKAGE_USAGE_STATS` integration** — `AppFilter.RECENT` currently shows `lastUsedAt = 0`. Requires runtime permission grant flow.

---

## 4. Technical Architecture

### 4.1 Module Structure
```
app/
  src/main/          — MainActivity, PFPApplication, Manifest
  src/debug/         — DebugController, DebugSeeder, DebugMenuScreen (excluded from release)

core/
  core-common/       — Shared utilities, extensions
  core-domain/       — Models (Game, Platform, Category, EmulatorProfile, PFPTheme, PlaySession)
  core-data/         — Room DB v20, DAOs, repositories, DataStore, seeding
  core-ui/           — PFPTheme, WaveStyle, GameIconStyle enum, shared Composables, PreviewData

feature/
  feature-xmb/       — XMBShell, XMBViewModel, category bar, item list, game icons, overlays, gamepad
  feature-library/   — ROM scanner, disc image resolver, platform extension map
  feature-launcher/  — Emulator intent resolver, profile repository
  feature-artwork/   — SteamGridDB API client, artwork repository
  feature-themes/    — .xmbtheme loader, theme engine  [not yet built]
  feature-settings/  — All 9 settings screens + ViewModels (incl. fully wired LibrarySettings)
  feature-appbar/    — Android app drawer, filter views, InstalledAppRepository
  feature-backup/    — BackupManager, BackupWorker, RestoreWorker
```

### 4.2 Key Technology Choices
| Layer | Choice | Reason |
|---|---|---|
| UI | Jetpack Compose | State hoisting, `@Preview` support, no XML |
| DI | Hilt | Standard Android DI, WorkManager integration |
| Database | Room 2.6.1 | Type-safe queries, Flow support, explicit migrations |
| Networking | Ktor (Android engine) | Multiplatform-ready, coroutine-native |
| Image loading | Coil 2.6 | Compose-native, bitmap pooling |
| Background | WorkManager 2.9 | Battery/thermal constraints built-in |
| Logging | Timber | Debug tree + 7-day file tree |
| Serialization | Kotlinx Serialization | JSON for EmulatorProfile, FilterRules, Backup, GamepadMappings |
| Settings | DataStore Preferences | Replaces SharedPreferences |
| Testing | JUnit 4 + MockK + Turbine | Pure-JVM, no emulator required for logic tests |

### 4.3 Database — Room v3
**8 entities:** `GameEntity`, `PlatformEntity`, `CategoryEntity`, `CategoryItemEntity`, `PlaySessionEntity`, `LibrarySourceEntity`, `UnmatchedRomEntity`, `ThemeEntity`

- `exportSchema = true` — schema JSON committed to version control
- Explicit migrations only — `fallbackToDestructiveMigration` is never used
- **v1→v2:** Added `is_active` column to `themes` table
- **v2→v3:** Added `platform_id TEXT` column to `library_sources` table
- All four backup-exported entities carry `@Serializable` for ZIP export

### 4.4 DataStore Keys (all in `pfp_prefs`)
| Key | Type | Owner | Purpose |
|---|---|---|---|
| `display_wave_style` | String | DisplaySettingsVM / XMBViewModel | WaveStyle enum name (ANIMATED \| REDUCED \| STATIC \| REDUCED_STATIC); applies only when no wallpaper is set |
| `display_color_scheme` | String | ColorSchemeSettingsVM / XMBViewModel | XmbColorScheme enum name (ORIGINAL \| CLASSIC_BLUE \| SUNSET_ORANGE \| FRESH_GREEN \| ROYAL_PURPLE \| CRIMSON_RED \| SILVER_MONO); ORIGINAL resolves to a month-based palette. Source of truth for the XMB palette (supersedes the DB theme's colors). |
| `display_show_boot` | Boolean | DisplaySettingsVM | Boot sequence on launch |
| `display_boot_on_resume` | Boolean | DisplaySettingsVM | Boot sequence on resume |
| `display_thermal_aware` | Boolean | DisplaySettingsVM | Thermal throttle awareness |
| `display_battery_saver` | Boolean | DisplaySettingsVM | Force static wave in battery saver |
| `display_icon_style` | String | DisplaySettingsVM / XMBViewModel | GameIconStyle enum name |
| `library_root_path` | String | LibrarySettingsVM | Real FS path of ROM root folder |
| `library_setup_complete` | Boolean | LibrarySettingsVM / XMBViewModel | First-run setup guard |
| `sgdb_api_key` | String | ArtworkSettingsVM | SteamGridDB API key — encrypted at rest via `KeystoreSecretCipher` (AES-256/GCM, Android Keystore) |
| `db_seeded` | Boolean | DatabaseInitializer | One-time platform seed guard |

### 4.5 Circular Dependency Note
`feature-xmb` → `feature-settings` → `feature-xmb` is a known architectural debt. Current workaround: accepted as-is for speed. Long-term fix: extract `GamepadAction` + `ControllerMappingRepository` to `core-domain`.

### 4.6 Permission Rationale
| Permission | Justification |
|---|---|
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Scoped media access on API 33+ (photo/video/music libraries) |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | Legacy raw-path ROM scanning on API ≤32 only |
| *(SAF — no permission)* | ROM libraries, media libraries, theme packs and backups all use the Storage Access Framework folder/file picker; `MANAGE_EXTERNAL_STORAGE` is no longer declared |
| `QUERY_ALL_PACKAGES` | Required to build app drawer and detect installed emulators |
| `PACKAGE_USAGE_STATS` | Required for "Recently Used" app drawer filter |
| `RECEIVE_BOOT_COMPLETED` | Restore launcher role after device restart |
| `POST_NOTIFICATIONS` | Background task progress notifications |

---

## 5. Roadmap

| # | Feature / Module | Status | Notes |
|---|---|---|---|
| **Foundation** | | | |
| 1 | Module structure & Gradle setup | ✅ Complete | 12 modules, version catalog, parallel builds |
| 2 | AndroidManifest (launcher role, permissions) | ✅ Complete | HOME intent, FileProvider, all permissions declared |
| 3 | Hilt DI wiring (app + debug modules) | ✅ Complete | AppModule, DebugModule, BackupModule |
| 4 | PFPApplication (HiltWorkerFactory, initializer) | ✅ Complete | SupervisorJob, WorkManager config, DatabaseInitializer |
| 5 | MainActivity (gamepad forwarding, debug gate) | ✅ Complete | KeyEvent + MotionEvent dispatch, BuildConfig.DEBUG branch |
| **Database** | | | |
| 6 | Room entities (8) + @Serializable on 4 | ✅ Complete | All entities with indices, FKs, cascade rules |
| 7 | Room DAOs (7) + backup export methods | ✅ Complete | getAll / insertAllReplace / deleteAll on all 4 export DAOs |
| 8 | PFPDatabase v3 + MIGRATION_1_2 + MIGRATION_2_3 | ✅ Complete | themes.is_active, library_sources.platform_id |
| 9 | Repository implementations | ✅ Complete | GameRepositoryImpl + updateBoxArt/HeroArt/LogoArt added |
| 10 | Platform seeder (17 platforms) | ✅ Complete | All major platforms with accent colors |
| 11 | DatabaseInitializer + DataStore guard | ✅ Complete | Seeds once, guarded by DataStore flag |
| **Core UI** | | | |
| 12 | PFPTheme + color system | ✅ Complete | CompositionLocal, DefaultPFPColors |
| 13 | XMB wave renderer (tiered) | ✅ Complete | FULL/REDUCED/STATIC, RuntimeShader on API 33+ |
| 14 | PreviewData (shared Compose preview states) | ✅ Complete | 4 pre-built XMBUiState snapshots |
| 15 | GameIconStyle enum (core-ui) | ✅ Complete | PSP_RECTANGLE / CARTRIDGE, shared across modules |
| **XMB Shell** | | | |
| 16 | XMBShell (state-hoisted) + @Previews | ✅ Complete | Pure UI + Container; 4 previews at 960×540dp |
| 17 | PlatformSdCardRow + PlatformInfoStrip | ✅ Complete | SD card shape via SdCardShapeImpl : Shape, glow + scale animations, game count strip |
| 17a | XMBCategoryBar (legacy) | ✅ Replaced | Superseded by PlatformSdCardRow in v2.3 |
| 18 | XMBItemList + GameIconView (icon styles) | ✅ Complete | PSP rectangle, cartridge, Android squircle icons |
| 19 | XMBStatusBar | ✅ Complete | Clock, battery, task badge, BroadcastReceiver |
| 20 | BootSequenceOverlay | ✅ Complete | Animatable phases, themeable timing |
| 21 | BackgroundTaskTray | ✅ Complete | Progress bars, color-coded states |
| 22 | XMBViewModel (DB data, overlay state, icon style, setup flag) | ✅ Complete | Full category dispatch, overlay routing, DataStore observation |
| 22a | XMBViewModel — platform-first architecture | ✅ Complete | combine(platformDao, gameRepo) → reactive category list; pin/unpin; context menu; scan; artwork refresh |
| 22b | ContextMenuOverlay | ✅ Complete | Right-aligned PSP-style panel; gamepad input captured by VM when activeContextMenu != null |
| 23 | XMB first-run library setup prompt | ✅ Complete | Injects "Set up ROM Library →" item in GAMES when unconfigured |
| **Emulator Launcher** | | | |
| 24 | EmulatorIntentResolver + FileProvider | ✅ Complete | All 4 intent types, SAF URI support |
| 25 | EmulatorProfileRepository + 13 bundled profiles | ✅ Complete | Bundled + custom JSON, installed check |
| 26 | In-app emulator profile editor UI | ✅ Complete | Full CRUD form; add/edit/delete custom profiles; intent type picker; EmulatorProfileEditorScreen |
| **ROM Library** | | | |
| 27 | PlatformExtensionMap + DiscImageResolver | ✅ Complete | CUE/BIN suppression, orphan BIN → Mega Drive |
| 28 | RomScanner (two-phase, manual) | ✅ Complete | Phase 1 disc, Phase 2 definitive walk |
| 29 | LibrarySourceEntity v3 migration (platform_id) | ✅ Complete | ALTER TABLE library_sources ADD COLUMN platform_id TEXT |
| 30 | ROM root dir picker + first-run setup | ✅ Complete | SAF OpenDocumentTree, path extraction, DataStore persistence |
| 31 | Library scan fully wired (upsert, unmatched, timestamps) | ✅ Complete | RomScanner → GameRepository.upsert, UnmatchedRomDao.insertAll |
| 32 | Per-platform extra source folder | ✅ Complete | addExtraFolder(uri, platformId?) in LibrarySettingsViewModel |
| 33 | PSP-style platform folders under Games | ✅ Complete | Platforms appear vertically inside Games; selecting one opens its game list |
| 34 | Unmatched ROM assignment UI | ❌ Incomplete | User resolves .chd/.img platform ambiguity |
| 35 | Missing ROM indicator in game list | ❌ Incomplete | Flag games whose romPath no longer exists |
| **Artwork** | | | |
| 36 | SteamGridDB API client (Ktor) | ✅ Complete | Search, Grid/Hero/Logo/Icon endpoints |
| 37 | SgdbApiKeyProvider + ArtworkRepository | ✅ Complete | Rate limiting, Coil pre-warm, persistence, clearCache() |
| 37a | Three-tier metadata pipeline | ✅ Complete | ScreenScraper (hash) → TheGamesDB (title) → SteamGridDB (artwork); MetadataRepository.prewarm() |
| 37b | CardArtworkProcessor + PlatformCardTemplate | ✅ Complete | Coil download (allowHardware=false) → Canvas composite onto platform template → PNG to artwork/cards/ |
| 38 | Custom per-game artwork (box art, hero, logo) | ✅ Complete | OpenDocument picker, copy to filesDir, GameRepository updates |
| 39 | SteamGridDB inline artwork picker (Game Detail) | ✅ Complete | Grid of results shown in-screen; tap to apply; updates artworkUri in DB |
| **Game Detail** | | | |
| 40 | GameDetailViewModel (load, launch, favorite, notes, artwork) | ✅ Complete | SharedFlow<Intent> for launch, ArtworkType enum, custom art copy |
| 41 | GameDetailScreen (hero banner, metadata, action row) | ✅ Complete | HeroBanner, ActionRow, NoteEditor, FeedbackBanner, CustomArtworkPanel |
| 42 | Game icon styles (PSP rectangle, cartridge, Android squircle) | ✅ Complete | GameIconView composables, DataStore-backed style preference |
| **Debug Layer** | | | |
| 43 | DebugController + DebugState + DebugSeeder | ✅ Complete | 7 scenarios, real Room inserts, DataStore guard |
| 44 | DebugMenuScreen + DebugMenuViewModel | ✅ Complete | Two-panel UI, all controls wired |
| 45 | DebugAwareXMBHost (BuildConfig.DEBUG gate) | ✅ Complete | Zero release footprint |
| **App Drawer** | | | |
| 46 | InstalledAppRepository (emulator/game tagging) | ✅ Complete | PackageManager query, 13 known emulator prefixes |
| 47 | AppDrawerViewModel (filter + search) | ✅ Complete | AppFilter enum, reactive applyFilter() |
| 48 | AppDrawerScreen (fullscreen overlay) | ✅ Complete | LazyVerticalGrid, search, filter tabs, EMU badge |
| 49 | User-defined filter groups | ❌ Incomplete | Architecture ready; UI deferred |
| 50 | Recently Used (PACKAGE_USAGE_STATS) | ✅ Complete | Usage Access handoff, app resume refresh, lastUsedAt populated from UsageStatsManager |
| **Controller Mapping** | | | |
| 51 | GamepadInputHandler + GamepadAction | ✅ Complete | KeyEvent/MotionEvent → SharedFlow, 0.5 dead zone, 400/120ms repeat |
| 52 | ControllerMappingRepository | ✅ Complete | DataStore persistence, saveMappings, remap, resetToDefaults |
| 53 | ControllerSettingsScreen (tap-to-remap) | ✅ Complete | Live key capture, live mapping table |
| **Settings** | | | |
| 54 | SettingsNavHost + SettingsScaffold | ✅ Complete | Routes 9 screen IDs, shared dark overlay scaffold |
| 55 | Library settings (fully wired) | ✅ Complete | SAF pickers, scan progress, per-platform folder badges |
| 56 | Display settings (wave, boot, icon style) | ✅ Complete | All toggles + icon style cycle row |
| 57 | Artwork / Emulator / Theme settings | ✅ Complete | ViewModels + screens wired |
| 58 | Controller settings | ✅ Complete | See row 53 |
| 59 | Logs settings (7-day rolling viewer) | ✅ Complete | Timber file tree, horizontal+vertical scroll viewer |
| 60 | About screen | ✅ Complete | BuildConfig.VERSION_NAME/CODE |
| **Backup & Restore** | | | |
| 61 | BackupManifest + SettingsSnapshot models | ✅ Complete | @Serializable, format version, entry name constants |
| 62 | BackupManager (ZIP read/write) | ✅ Complete | createBackup + restoreBackup, format version guard |
| 63 | BackupWorker + RestoreWorker (WorkManager) | ✅ Complete | @HiltWorker, SAF URI input, Success/Failure result data |
| 64 | BackupSettingsScreen (SAF picker, file list) | ✅ Complete | OpenDocument launcher, live worker status, error row |
| 65 | DataStore settings export/import | ✅ Complete | settings.json now backs up/restores typed DataStore preferences, including API keys |
| **Unit Tests** | | | |
| 66 | Test infrastructure (libs.versions.toml, bundles) | ✅ Complete | JUnit 4, MockK, Turbine, coroutines-test, work-testing |
| 67 | BackupManifestTest | ✅ Complete | JSON round-trip, format version, entry name stability |
| 68 | BackupManagerTest | ✅ Complete | ZIP creation, DAO reads, restore failure modes, file listing |
| 69 | GamepadInputHandlerTest | ✅ Complete | All button actions, dead zone, all 4 stick directions, custom remap |
| 70 | GamepadBindingTest | ✅ Complete | Default bindings, JSON round-trip, displayLabel/keycodeDisplayName |
| 71 | AppDrawerViewModelTest | ✅ Complete | All filters, search, selection, isLoading |
| 72 | ControllerSettingsViewModelTest | ✅ Complete | Mapping display, remap flow, cancel, resetToDefaults |
| 73 | GameDetailViewModelTest | ✅ Complete | 11 tests — load, not-found, favorite, note CRUD, launch error, artwork |
| 74-t | XmbThemeManifestTest | ✅ Complete | JSON round-trip, defaults, snake_case keys, unknown fields ignored, parseHexColor (13 cases) |
| 75-t | XmbThemeLoaderTest | ✅ Complete | valid ZIP, missing manifest, future version, bad colors, bad JSON, asset extraction, backgroundUri path |
| **Theme Engine** | | | |
| 74 | XmbThemeManifest + parseHexColor | ✅ Complete | @Serializable, snake_case @SerialName, #RRGGBB/#AARRGGBB → Long; test coverage in XmbThemeManifestTest |
| 75 | XmbThemeLoader — ZIP parsing + asset extraction | ✅ Complete | loadFromUri/loadFromStream, ByteArray entries, asset copy to filesDir/themes/{id}/; test coverage in XmbThemeLoaderTest |
| 76 | ThemeLoadResult sealed class | ✅ Complete | Success, InvalidFormat, UnsupportedVersion, IoError |
| 77 | ThemeRepository + ThemeDiModule | ✅ Complete | observeActiveTheme(), observeAll(), installTheme(uri), uninstallTheme(id); Hilt @Binds |
| 78 | Built-in "Classic PSP Blue" theme seeding | ✅ Complete | BuiltInThemes.CLASSIC_PSP_BLUE; themes_seeded_v1 DataStore guard in DatabaseInitializer |
| 79 | Theme colors wired into XMB shell | ✅ Complete | themeColors: PFPColors in XMBUiState; ThemeDao observed in XMBViewModel; XMBShell wrapped in PFPTheme(colors) |
| 80 | ThemesSettingsScreen — install + uninstall UI | ✅ Complete | SAF OpenDocument picker, LinearProgressIndicator, remove button on user themes, installMessage feedback |
| 81 | Sound pack system | ❌ Incomplete | sounds/ extracted to filesDir; playback via SoundPool/MediaPlayer deferred |
| 82 | Boot animation override from theme | ❌ Incomplete | bootAnimationUri stored in DB; BootSequenceOverlay still uses hardcoded animation |
| 83 | Custom font support | ❌ Incomplete | fontKey stored in DB; no runtime font switching yet |
| **User-Created Categories** | | | |
| 79 | Smart category builder (filter rules UI) | ❌ Incomplete | FilterRules model + @Serializable ready |
| 80 | Manual category (drag-to-add game list) | ❌ Incomplete | — |
| **Known Technical Debt** | | | |
| T1 | feature-xmb ↔ feature-settings circular dep | ✅ Fixed | GamepadAction/GamepadBinding/GamepadMappings → core-domain; ControllerMappingRepository → core-data |
| T2 | PACKAGE_USAGE_STATS not requested at runtime | ✅ Fixed | App drawer opens Usage Access settings and refreshes usage stats on resume |
| T3 | DataStore settings not included in backup ZIP | ✅ Fixed | BackupManager writes/restores settings.json with typed preference handling |
| **Previously Fixed Bugs** | | | |
| B1 | GBA accent color — space before L literal | ✅ Fixed | `0xFF6A0DADL` |
| B2 | FileProvider missing from manifest | ✅ Fixed | Provider + file_paths.xml added |
| B3 | MainActivity wrong import (XMBShell vs Container) | ✅ Fixed | Correct import in place |
| B4 | ThemeEntity missing is_active column | ✅ Fixed | Column added, MIGRATION_1_2 written |
| B5 | ArtworkSettingsViewModel wrong callback arity | ✅ Fixed | Correct 3-param lambda |
| B6 | accompanist-drawablepainter missing from catalog | ✅ Fixed | Added to libs.versions.toml + feature-appbar gradle |
| B7 | Game nullable fields missing defaults | ✅ Fixed | All `String?`/`Int?`/`Long?` fields now have `= null` defaults |
| B8 | GenericShape lambda — unresolved path methods | ✅ Fixed | Replaced with `SdCardShapeImpl : Shape` using `createOutline()` + `Outline.Generic(path)` |
| B9 | `quadraticTo` unresolved in custom Shape | ✅ Fixed | Compose version requires `quadraticBezierTo` — renamed |
| B10 | `BlurMaskFilter` unresolved import in PlatformSdCardRow | ✅ Fixed | Removed unused import |
| B11 | `MetadataApiKeyProvider.firstValue()` using wrong collector | ✅ Fixed | Changed `collect {}` to `first()` suspend call |
| B12 | `MetadataRepository.prewarmCache()` broken context injection | ✅ Fixed | Switched to `@ApplicationContext` constructor injection |

---

## 6. Next Moves — Priority Order

1. **Smart + Manual category builder UI** — `FilterRules` model is `@Serializable` and ready; build the creation flow and drag-to-add game list.
2. **Unmatched ROM assignment UI** — let users resolve `.chd`/`.img` platform ambiguity from a dedicated screen in Library Settings.
3. **Missing ROM indicator** — flag games whose `romPath` no longer exists on disk; surface in the game list.
4. **Game Detail screen visual refresh** — redesign for a richer layout (hero art, metadata columns, launch button). Implement using the Write tool directly, not as inline text.
5. **Theme sound playback** — `sounds/` assets already extracted to `filesDir/themes/{id}/sounds/`; wire `SoundPool` player triggered by `ThemeSoundEvent`.
6. **Boot animation override** — `bootAnimationUri` stored in DB; feed into `BootSequenceOverlay` as a `VideoPlayer` composable.
7. **Custom font support** — `fontKey` stored in DB; apply via `FontFamily` inside the `PFPTheme` composable.

---

*Play Field Portal · Internal Design Document · Not for distribution*
